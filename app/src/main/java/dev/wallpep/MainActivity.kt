package dev.wallpep

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.wallpep.cutout.CutoutCoordinator
import dev.wallpep.cutout.CutoutState
import dev.wallpep.fonts.FontCatalog
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.model.storedEnum
import dev.wallpep.storage.BackgroundStore
import dev.wallpep.storage.SettingsRepository
import dev.wallpep.ui.EditorScreen
import dev.wallpep.wallpaper.WallpepService
import dev.wallpep.wallpaper.millisUntilNextMinute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns settings, the private photo copy and save/apply semantics; [EditorScreen] owns the UI. */
class MainActivity : ComponentActivity() {
    private val repository by lazy { SettingsRepository(applicationContext) }
    private val backgrounds by lazy { BackgroundStore(applicationContext) }
    private val ready = CompletableDeferred<Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var active = WallpaperSettings()
    private var draft = WallpaperSettings()
    private var initialized = false
    private var busy = false
    private var previewGeneration = 0L
    private var previewVisible = false
    private var previewJob: Job? = null
    private lateinit var editor: EditorScreen

    private val editorListener = object : EditorScreen.Listener {
        override fun currentSettings(): WallpaperSettings = draft

        override fun canInteract(): Boolean = initialized && !busy

        override fun onSettingsChanged(next: WallpaperSettings) = changeDraft(next)

        override fun onPickPhoto() {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        override fun onRemovePhoto() = removePhoto()

        override fun onSave() {
            lifecycleScope.launch { saveDraft(showToast = true) }
        }

        override fun onApplyWallpaper() = applyWallpaper()
    }

    private val previewTick = object : Runnable {
        override fun run() {
            editor.scene.invalidate()
            main.postDelayed(this, millisUntilNextMinute(System.currentTimeMillis()))
        }
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch {
            if (ready.await()) importPhoto(uri)
        }
    }
    private val wallpaperChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // OEMs do not consistently report an application result; do not claim it was applied.
        toast("Your saved settings are unchanged. Choose Home screen in the system chooser.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = EditorScreen(this, editorListener)
        setContentView(editor.root)
        renderUi()
        lifecycleScope.launch {
            var observedName: String? = null
            var observedState: CutoutState? = null
            CutoutCoordinator.states.collect { states ->
                if (!initialized) return@collect
                val name = draft.backgroundName
                val state = name?.let { states[it] }
                val changed = name != observedName || state != observedState
                observedName = name
                observedState = state
                renderUi()
                if (changed && state == CutoutState.READY && previewVisible) refreshPreviewImage()
            }
        }
        lifecycleScope.launch {
            try {
                val snapshot = repository.snapshots.first()
                if (!snapshot.schemaSupported) {
                    editor.showNotice("Settings are from a newer Wallpep version. Saving is disabled.")
                    ready.complete(false)
                    renderUi()
                    return@launch
                }
                active = snapshot.settings
                draft = savedInstanceState?.getBundle(STATE_DRAFT)?.let(::readDraft) ?: active
                if (draft.backgroundName != null && !backgrounds.exists(draft.backgroundName)) {
                    draft = draft.copy(backgroundName = active.backgroundName)
                }
                // Finish orphan cleanup before enabling photo import or saving; neither operation
                // can race this cleanup and lose a freshly copied draft.
                backgrounds.cleanupExcept(setOfNotNull(active.backgroundName, draft.backgroundName))
                initialized = true
                // Requests coalesce with any visible wallpaper engine and run off the UI thread.
                CutoutCoordinator.ensure(applicationContext, active.backgroundName)
                if (draft.backgroundName != active.backgroundName) {
                    CutoutCoordinator.ensure(applicationContext, draft.backgroundName)
                }
                renderUi()
                refreshPreviewImage()
                ready.complete(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                editor.showNotice("Settings could not be read. Reopen the app to try again.")
                ready.complete(false)
                renderUi()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        previewVisible = true
        editor.scene.invalidate()
        main.removeCallbacks(previewTick)
        main.postDelayed(previewTick, millisUntilNextMinute(System.currentTimeMillis()))
        if (initialized) refreshPreviewImage()
    }

    override fun onPause() {
        previewVisible = false
        main.removeCallbacks(previewTick)
        super.onPause()
    }

    override fun onStop() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        editor.scene.release()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (initialized) outState.putBundle(STATE_DRAFT, writeDraft(draft))
        super.onSaveInstanceState(outState)
    }

    private fun changeDraft(next: WallpaperSettings) {
        if (!initialized || busy) return
        val photoChanged = draft.backgroundName != next.backgroundName
        draft = next.sanitized()
        renderUi()
        if (photoChanged) refreshPreviewImage()
    }

    private fun renderUi() {
        if (::editor.isInitialized) editor.render(draft, active, photoLabel(), initialized && !busy)
    }

    private fun photoLabel(): String {
        val name = draft.backgroundName ?: return "Built-in golden backdrop · choose a photo to make it yours."
        return when (CutoutCoordinator.states.value[name]) {
            CutoutState.PROCESSING -> "Photo selected · building the depth cutout on this device…"
            CutoutState.READY -> "Photo selected · depth sandwich ready"
            else -> "Photo selected · center-cropped to your screen"
        }
    }

    private suspend fun importPhoto(uri: Uri) {
        if (!initialized || busy) return
        busy = true
        renderUi()
        try {
            val previous = draft.backgroundName
            val name = backgrounds.importPhoto(uri)
            if (previous != null && previous != active.backgroundName) {
                withContext(Dispatchers.IO) { backgrounds.delete(previous) }
            }
            draft = draft.copy(backgroundName = name)
            CutoutCoordinator.ensure(applicationContext, name)
            renderUi()
            refreshPreviewImage()
            toast("Photo copied privately. Save to use it.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            toast("Could not use that photo. Try an image under 40 MB.")
        } finally {
            busy = false
            renderUi()
        }
    }

    private fun removePhoto() {
        if (!initialized || busy) return
        val previous = draft.backgroundName
        changeDraft(draft.copy(backgroundName = null))
        if (previous != null && previous != active.backgroundName) {
            lifecycleScope.launch(Dispatchers.IO) { backgrounds.delete(previous) }
        }
    }

    private suspend fun saveDraft(showToast: Boolean): Boolean {
        if (!initialized || busy) return false
        busy = true
        renderUi()
        return try {
            val toSave = draft.sanitized()
            repository.save(toSave)
            active = toSave
            draft = toSave
            renderUi()
            // Cleanup is best-effort: a failure here must not misreport an already-committed save.
            try {
                backgrounds.cleanupExcept(setOfNotNull(active.backgroundName))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A later startup/save can remove an orphan.
            }
            if (showToast) toast("Saved. Your wallpaper settings are active.")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            toast("Could not save settings. Please try again.")
            false
        } finally {
            busy = false
            renderUi()
        }
    }

    private fun applyWallpaper() {
        if (!initialized || busy) return
        if (draft != active) {
            AlertDialog.Builder(this)
                .setTitle("Save before opening the chooser?")
                .setMessage("Saving updates the active wallpaper settings immediately. Canceling the chooser won't undo them. In the chooser, select Home screen only.")
                .setPositiveButton("Save & continue") { _, _ ->
                    lifecycleScope.launch { if (saveDraft(showToast = false)) openChooser() }
                }
                .setNegativeButton("Keep editing", null)
                .show()
        } else openChooser()
    }

    private fun openChooser() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, WallpepService::class.java),
        )
        try {
            wallpaperChooser.launch(intent)
        } catch (_: ActivityNotFoundException) {
            toast("The system live-wallpaper chooser is unavailable on this device.")
        }
    }

    private fun refreshPreviewImage() {
        previewGeneration++
        val ticket = previewGeneration
        previewJob?.cancel()
        if (!previewVisible) return
        val name = draft.backgroundName
        if (name == null) {
            editor.scene.setImages(null, null)
            return
        }
        previewJob = lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = backgrounds.decode(name, 900)
            var foreground: android.graphics.Bitmap? = null
            var transferred = false
            try {
                if (bitmap != null) foreground = backgrounds.decodeCutout(name, 900)
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    if (ticket == previewGeneration && previewVisible && !isFinishing && !isDestroyed) {
                        editor.scene.setImages(bitmap, foreground)
                        transferred = true
                    }
                }
            } finally {
                if (!transferred) {
                    bitmap?.recycle()
                    foreground?.recycle()
                }
            }
        }
    }

    private fun writeDraft(s: WallpaperSettings) = Bundle().apply {
        putString("time", s.timeStyle.name); putString("date", s.dateStyle.name)
        putString("font", s.font); putString("dateFont", s.dateFont)
        putInt("color", s.textColor)
        // An absent date colour means the date follows the clock colour.
        s.dateColor?.let { putInt("dateColor", it) }
        putInt("size", s.clockSizeSp)
        putInt("clockSpacing", s.clockLetterSpacing)
        putInt("clockOpacity", s.clockOpacity)
        putBoolean("showClock", s.showClock)
        putInt("x", s.positionX); putInt("y", s.positionY)
        putInt("dateSize", s.dateSizeSp)
        putInt("dateSpacing", s.dateLetterSpacing)
        putInt("dateOpacity", s.dateOpacity)
        putBoolean("dateUppercase", s.dateUppercase)
        putInt("dateX", s.datePositionX); putInt("dateY", s.datePositionY)
        putString("background", s.backgroundName)
        putBoolean("hourForeground", s.hourOverForeground)
        putBoolean("minuteForeground", s.minuteOverForeground)
        putBoolean("dateForeground", s.dateOverForeground)
    }

    private fun readDraft(bundle: Bundle): WallpaperSettings = WallpaperSettings(
        timeStyle = storedEnum(bundle.getString("time"), active.timeStyle),
        dateStyle = storedEnum(bundle.getString("date"), active.dateStyle),
        font = FontCatalog.sanitizeId(bundle.getString("font") ?: active.font),
        dateFont = FontCatalog.sanitizeDateId(bundle.getString("dateFont") ?: active.dateFont),
        textColor = bundle.getInt("color", active.textColor),
        dateColor = if (bundle.containsKey("dateColor")) bundle.getInt("dateColor") else null,
        clockSizeSp = bundle.getInt("size", active.clockSizeSp),
        clockLetterSpacing = bundle.getInt("clockSpacing", active.clockLetterSpacing),
        clockOpacity = bundle.getInt("clockOpacity", active.clockOpacity),
        showClock = bundle.getBoolean("showClock", active.showClock),
        positionX = bundle.getInt("x", active.positionX),
        positionY = bundle.getInt("y", active.positionY),
        dateSizeSp = bundle.getInt("dateSize", active.dateSizeSp),
        dateLetterSpacing = bundle.getInt("dateSpacing", active.dateLetterSpacing),
        dateOpacity = bundle.getInt("dateOpacity", active.dateOpacity),
        dateUppercase = bundle.getBoolean("dateUppercase", active.dateUppercase),
        datePositionX = bundle.getInt("dateX", active.datePositionX),
        datePositionY = bundle.getInt("dateY", active.datePositionY),
        backgroundName = bundle.getString("background"),
        hourOverForeground = bundle.getBoolean("hourForeground", active.hourOverForeground),
        minuteOverForeground = bundle.getBoolean("minuteForeground", active.minuteOverForeground),
        dateOverForeground = bundle.getBoolean("dateForeground", active.dateOverForeground),
    ).sanitized()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATE_DRAFT = "draft"
    }
}
