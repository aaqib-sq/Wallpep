package dev.wallpep

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.wallpep.cutout.CutoutCoordinator
import dev.wallpep.cutout.CutoutState
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.render.WallpaperScene
import dev.wallpep.storage.BackgroundStore
import dev.wallpep.storage.DesignStore
import dev.wallpep.storage.SavedDesign
import dev.wallpep.storage.SettingsRepository
import dev.wallpep.ui.AppShell
import dev.wallpep.ui.EditorScreen
import dev.wallpep.ui.HomeScreen
import dev.wallpep.ui.SettingsScreen
import dev.wallpep.ui.dialogInput
import dev.wallpep.ui.dp
import dev.wallpep.ui.textField
import dev.wallpep.wallpaper.WallpepService
import dev.wallpep.wallpaper.millisUntilNextMinute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns persistence, the private photo copy and the save/apply semantics for three screens:
 * My Collection, Settings and the seven-tab editor. Every editor control is written into the
 * single settings document, so nothing on screen is decorative.
 */
class MainActivity : ComponentActivity(), HomeScreen.Listener, SettingsScreen.Listener {
    private val repository by lazy { SettingsRepository(applicationContext) }
    private val designStore by lazy { DesignStore(applicationContext) }
    private val backgrounds by lazy { BackgroundStore(applicationContext) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var shell: AppShell
    private lateinit var home: HomeScreen
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var editor: EditorScreen

    private var settings = WallpaperSettings()
    private var designs: List<SavedDesign> = emptyList()
    private var currentDesignId: String? = null
    private var currentName: String = "Untitled wallpaper"
    private var favorited = false
    private var inRotation = false
    private var initialized = false
    private var busy = false
    private var saving = false
    private var dirty = false
    private var previewGeneration = 0L
    private var previewVisible = false
    private var previewJob: Job? = null
    private var persistJob: Job? = null
    private val thumbnailCache = mutableMapOf<String, Pair<Long, Bitmap>>()

    private val editorListener = object : EditorScreen.Listener {
        override fun currentSettings(): WallpaperSettings = settings
        override fun currentName(): String = currentName
        override fun isFavorited(): Boolean = favorited
        override fun canInteract(): Boolean = initialized && !busy
        override fun onSettingsChanged(next: WallpaperSettings) = applySettings(next)
        override fun onPickPhoto() = pickPhoto()
        override fun onRemovePhoto() = removePhoto()
        override fun onRename(name: String) = renameCurrent(name)
        override fun onToggleFavorite() = toggleCurrentFavorite()
        override fun onApply() = applyToSystem()
        override fun onBack() = closeEditor()
    }

    private val previewTick = object : Runnable {
        override fun run() {
            editor.invalidatePreview()
            main.postDelayed(this, millisUntilNextMinute(System.currentTimeMillis()))
        }
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch { if (ready()) importPhoto(uri) }
    }

    private val wallpaperChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // OEMs do not consistently report an application result; do not claim it was applied.
        toast("Your saved settings are unchanged. Choose Home screen in the system chooser.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = HomeScreen(this, this)
        settingsScreen = SettingsScreen(this, this)
        editor = EditorScreen(this, editorListener)
        shell = AppShell(this, home.root, settingsScreen.root, editor.root)
        setContentView(shell.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    shell.editorVisible() -> closeEditor()
                    shell.current() == AppShell.Destination.SETTINGS -> shell.select(AppShell.Destination.COLLECTION)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        lifecycleScope.launch {
            var observedName: String? = null
            var observedState: CutoutState? = null
            CutoutCoordinator.states.collect { states ->
                if (!initialized) return@collect
                val name = settings.backgroundName
                val state = name?.let { states[it] }
                if (name == observedName && state == observedState) return@collect
                observedName = name
                observedState = state
                renderAll()
                if (state == CutoutState.READY && previewVisible) refreshPreviewImage()
            }
        }
        lifecycleScope.launch {
            designStore.designs.collect { stored ->
                designs = stored
                home.render(stored)
                refreshThumbnails()
            }
        }
        lifecycleScope.launch { load() }
    }

    private fun ready(): Boolean = initialized && !busy

    private suspend fun load() {
        try {
            val snapshot = repository.snapshots.first()
            if (!snapshot.schemaSupported) {
                editor.showNotice("Settings are from a newer Wallpep version. Saving is disabled.")
                settingsScreen.render(settings)
                return
            }
            settings = snapshot.settings
            val stored = designStore.designs.first()
            if (stored.isEmpty() && settings.backgroundName != null) {
                designStore.upsert(
                    SavedDesign(
                        id = DesignStore.newId(),
                        name = "My wallpaper",
                        settings = settings,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            initialized = true
            // Finish orphan cleanup before enabling photo import; keep every referenced photo.
            backgrounds.cleanupExcept(keepNames())
            CutoutCoordinator.ensure(applicationContext, settings.backgroundName)
            renderAll()
            refreshPreviewImage()
            refreshThumbnails()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            editor.showNotice("Settings could not be read. Reopen the app to try again.")
            initialized = true
            renderAll()
        }
    }

    private fun keepNames(): Set<String> {
        val names = designs.mapNotNull { it.settings.backgroundName }.toMutableSet()
        settings.backgroundName?.let { names.add(it) }
        return names
    }

    override fun onResume() {
        super.onResume()
        previewVisible = true
        editor.invalidatePreview()
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
        editor.setPreviewImages(null, null)
        persistNow()
        super.onStop()
    }

    // ---------------------------------------------------------------- screens

    private fun renderAll() {
        home.render(designs)
        settingsScreen.render(settings)
        editor.render(settings, currentName, favorited, photoLabel(), saving)
        refreshThumbnails()
    }

    /** Short status for the pill over the live preview; nothing here is an instruction. */
    private fun photoLabel(): String {
        val name = settings.backgroundName ?: return "Built-in backdrop"
        return when (CutoutCoordinator.states.value[name]) {
            CutoutState.PROCESSING -> "Photo · building cutout on device…"
            CutoutState.READY -> "Photo · depth ready"
            else -> "Photo · center-cropped"
        }
    }

    // ---------------------------------------------------------------- collection

    override fun onCreateDesign() {
        if (!ready()) return
        currentDesignId = null
        settings = WallpaperSettings()
        currentName = "Untitled wallpaper"
        favorited = false
        inRotation = false
        dirty = false
        shell.openEditor()
        settingsScreen.render(settings)
        editor.render(settings, currentName, favorited, photoLabel(), saving)
        refreshPreviewImage()
    }

    override fun onOpenDesign(id: String) {
        if (!ready()) return
        val design = designs.firstOrNull { it.id == id } ?: return
        currentDesignId = design.id
        settings = design.settings
        currentName = design.name
        favorited = design.favorited
        inRotation = design.inRotation
        dirty = false
        shell.openEditor()
        CutoutCoordinator.ensure(applicationContext, settings.backgroundName)
        renderAll()
        refreshPreviewImage()
    }

    override fun onToggleFavorite(id: String) {
        val design = designs.firstOrNull { it.id == id } ?: return
        lifecycleScope.launch {
            designStore.setFavorited(id, !design.favorited)
            if (currentDesignId == id) {
                favorited = !design.favorited
                editor.render(settings, currentName, favorited, photoLabel(), saving)
            }
        }
    }

    override fun onDesignOptions(design: SavedDesign) {
        val actions = arrayListOf("Open", "Rename")
        actions.add(if (design.favorited) "Remove from favourites" else "Add to favourites")
        actions.add(if (design.inRotation) "Remove from rotate cycle" else "Add to rotate cycle")
        actions.add("Delete")
        AlertDialog.Builder(this)
            .setTitle(design.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onOpenDesign(design.id)
                    1 -> askRename(design)
                    2 -> onToggleFavorite(design.id)
                    3 -> lifecycleScope.launch { designStore.setInRotation(design.id, !design.inRotation) }
                    else -> confirmDelete(design)
                }
            }
            .show()
    }

    private fun askRename(design: SavedDesign) {
        val input = textField("Wallpaper name") { }
        input.setText(design.name)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle("Rename wallpaper")
            .setView(dialogInput(input))
            .setPositiveButton("Rename") { _, _ ->
                lifecycleScope.launch {
                    designStore.rename(design.id, input.text.toString())
                    if (currentDesignId == design.id) {
                        currentName = DesignStore.cleanName(input.text.toString())
                        renderAll()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(design: SavedDesign) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${design.name}?")
            .setMessage("The saved design is removed. Its private photo copy is deleted unless another design uses it.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val photo = design.settings.backgroundName
                    designStore.remove(design.id)
                    thumbnailCache.remove(design.id)
                    if (currentDesignId == design.id) {
                        currentDesignId = null
                        dirty = false
                        shell.showCollection()
                    }
                    if (photo != null && designs.none { it.id != design.id && it.settings.backgroundName == photo }) {
                        withContext(Dispatchers.IO) { backgrounds.delete(photo) }
                    }
                    renderAll()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- editing

    private fun applySettings(next: WallpaperSettings) {
        if (!ready()) return
        val clean = next.sanitized()
        val photoChanged = clean.backgroundName != settings.backgroundName
        settings = clean
        dirty = true
        if (photoChanged) {
            CutoutCoordinator.ensure(applicationContext, clean.backgroundName)
            refreshPreviewImage()
        }
        renderAll()
        schedulePersist()
    }

    private fun renameCurrent(name: String) {
        currentName = DesignStore.cleanName(name)
        dirty = true
        renderAll()
        schedulePersist()
    }

    private fun toggleCurrentFavorite() {
        favorited = !favorited
        dirty = true
        renderAll()
        schedulePersist()
    }

    private fun pickPhoto() {
        if (!ready()) return
        try {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: ActivityNotFoundException) {
            toast("No photo picker is available on this device.")
        }
    }

    private suspend fun importPhoto(uri: Uri) {
        if (!ready()) return
        busy = true
        renderAll()
        try {
            val previous = settings.backgroundName
            val name = backgrounds.importPhoto(uri)
            settings = settings.copy(backgroundName = name)
            dirty = true
            if (previous != null && designs.none { it.settings.backgroundName == previous }) {
                withContext(Dispatchers.IO) { backgrounds.delete(previous) }
            }
            CutoutCoordinator.ensure(applicationContext, name)
            renderAll()
            schedulePersist()
            refreshPreviewImage()
            toast("Photo copied privately. Save to use it.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            toast("Could not use that photo. Try an image under 40 MB.")
        } finally {
            busy = false
            renderAll()
        }
    }

    private fun removePhoto() {
        if (!ready()) return
        val previous = settings.backgroundName
        settings = settings.copy(backgroundName = null)
        dirty = true
        if (previous != null && designs.none { it.settings.backgroundName == previous }) {
            lifecycleScope.launch(Dispatchers.IO) { backgrounds.delete(previous) }
        }
        renderAll()
        schedulePersist()
        refreshPreviewImage()
    }

    private fun closeEditor() {
        persistNow()
        shell.closeEditor()
        thumbnailCache.remove(currentDesignId)
        currentDesignId?.let { home.setThumbnail(it, null) }
        renderAll()
        refreshThumbnails()
    }

    private fun applyToSystem() {
        if (!ready()) return
        // Applying is an explicit intent: a brand-new design is kept even if nothing was edited.
        if (currentDesignId == null) dirty = true
        persistNow()
        openChooser()
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

    // ---------------------------------------------------------------- persistence

    private fun schedulePersist() {
        saving = true
        editor.render(settings, currentName, favorited, photoLabel(), saving)
        persistJob?.cancel()
        persistJob = lifecycleScope.launch {
            delay(PERSIST_DELAY_MS)
            writeDesign()
        }
    }

    private fun persistNow() {
        persistJob?.cancel()
        persistJob = lifecycleScope.launch { writeDesign() }
    }

    /** Writes the settings document and the collection entry together. */
    private suspend fun writeDesign() {
        if (!initialized) return
        if (currentDesignId == null) {
            if (!dirty) {
                saving = false
                editor.render(settings, currentName, favorited, photoLabel(), saving)
                return
            }
            currentDesignId = DesignStore.newId()
        }
        val id = currentDesignId ?: return
        val record = SavedDesign(
            id = id,
            name = DesignStore.cleanName(currentName),
            settings = settings,
            favorited = favorited,
            inRotation = inRotation,
            updatedAt = System.currentTimeMillis(),
        )
        try {
            repository.save(settings)
            designStore.upsert(record)
            dirty = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            toast("Could not save settings. Please try again.")
        } finally {
            saving = false
            renderAll()
        }
    }

    // ---------------------------------------------------------------- settings screen

    override fun currentSettings(): WallpaperSettings = settings

    override fun canInteract(): Boolean = initialized && !busy

    override fun onSettingsChanged(next: WallpaperSettings) = applySettings(next)

    override fun onClearCache() {
        if (!initialized) return
        lifecycleScope.launch {
            val removed = try {
                withContext(Dispatchers.IO) { backgrounds.clearCache() }
            } catch (_: Exception) {
                0
            }
            CutoutCoordinator.ensure(applicationContext, settings.backgroundName)
            refreshPreviewImage()
            toast(if (removed > 0) "Cleared $removed cached file(s)." else "Cache is already empty.")
            renderAll()
        }
    }

    override fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    // ---------------------------------------------------------------- preview and thumbnails

    private fun refreshPreviewImage() {
        previewGeneration++
        val ticket = previewGeneration
        previewJob?.cancel()
        if (!previewVisible) return
        val name = settings.backgroundName
        if (name == null) {
            editor.setPreviewImages(null, null)
            return
        }
        previewJob = lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = backgrounds.decode(name, 900)
            var foreground: Bitmap? = null
            var transferred = false
            try {
                if (bitmap != null) foreground = backgrounds.decodeCutout(name, 900)
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    if (ticket == previewGeneration && previewVisible && !isFinishing && !isDestroyed) {
                        editor.setPreviewImages(bitmap, foreground)
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

    private fun refreshThumbnails() {
        val live = designs.map { it.id }.toSet()
        val stale = thumbnailCache.keys.filterNot { it in live }
        stale.forEach { id -> thumbnailCache.remove(id)?.second?.recycle() }
        val pending = home.pendingThumbnails()
        if (pending.isEmpty()) return
        val targets = designs.filter { it.id in pending }
        if (targets.isEmpty()) return
        lifecycleScope.launch {
            targets.forEach { design ->
                val cached = thumbnailCache[design.id]
                if (cached != null && cached.first == design.updatedAt) {
                    home.setThumbnail(design.id, cached.second)
                    return@forEach
                }
                val bitmap = renderThumbnail(design) ?: return@forEach
                thumbnailCache[design.id] = design.updatedAt to bitmap
                home.setThumbnail(design.id, bitmap)
            }
        }
    }

    /** Draws the real scene into a card-sized bitmap so a card is that wallpaper, not a photo. */
    private suspend fun renderThumbnail(design: SavedDesign): Bitmap? {
        val name = design.settings.backgroundName
        val background = withContext(Dispatchers.IO) { backgrounds.decode(name, 520) }
        val foreground = withContext(Dispatchers.IO) {
            if (background == null) null else backgrounds.decodeCutout(name, 520)
        }
        return withContext(Dispatchers.Main.immediate) {
            var scene: WallpaperScene? = null
            try {
                val width = dp(164)
                val height = dp(232)
                val render = WallpaperScene(this@MainActivity, miniature = true)
                scene = render
                render.settings = design.settings
                render.setImages(background, foreground)
                render.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                render.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                render.draw(Canvas(bitmap))
                bitmap
            } catch (_: OutOfMemoryError) {
                background?.recycle()
                foreground?.recycle()
                null
            } catch (_: RuntimeException) {
                background?.recycle()
                foreground?.recycle()
                null
            } finally {
                scene?.release()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PERSIST_DELAY_MS = 320L
    }
}
