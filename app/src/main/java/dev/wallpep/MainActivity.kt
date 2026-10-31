package dev.wallpep

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.wallpep.model.DateStyle
import dev.wallpep.model.FontChoice
import dev.wallpep.model.TextColors
import dev.wallpep.model.TimeStyle
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.model.storedEnum
import dev.wallpep.render.WallpaperScene
import dev.wallpep.storage.BackgroundStore
import dev.wallpep.storage.SettingsRepository
import dev.wallpep.wallpaper.WallpepService
import dev.wallpep.wallpaper.millisUntilNextMinute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private val repository by lazy { SettingsRepository(applicationContext) }
    private val backgrounds by lazy { BackgroundStore(applicationContext) }
    private val ready = CompletableDeferred<Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var active = WallpaperSettings()
    private var draft = WallpaperSettings()
    private var initialized = false
    private var busy = false
    private var syncing = false
    private var previewGeneration = 0L
    private var previewJob: Job? = null
    private val inputs = mutableListOf<View>()
    private val colorButtons = mutableListOf<TextView>()

    private lateinit var scene: WallpaperScene
    private lateinit var status: TextView
    private lateinit var photoStatus: TextView
    private lateinit var saveButton: Button
    private lateinit var applyButton: Button
    private lateinit var removeButton: Button
    private lateinit var timeSpinner: Spinner
    private lateinit var dateSpinner: Spinner
    private lateinit var fontSpinner: Spinner
    private lateinit var sizeBar: SeekBar
    private lateinit var xBar: SeekBar
    private lateinit var yBar: SeekBar
    private lateinit var sizeValue: TextView
    private lateinit var xValue: TextView
    private lateinit var yValue: TextView

    private val previewTick = object : Runnable {
        override fun run() {
            scene.invalidate()
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
        setContentView(createScreen())
        setEnabledState()
        lifecycleScope.launch {
            try {
                val snapshot = repository.snapshots.first()
                if (!snapshot.schemaSupported) {
                    status.text = "Settings are from a newer Wallpep version. Saving is disabled."
                    ready.complete(false)
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
                syncControls()
                refreshPreviewImage()
                setEnabledState()
                ready.complete(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status.text = "Settings could not be read. Reopen the app to try again."
                ready.complete(false)
                setEnabledState()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scene.invalidate()
        main.removeCallbacks(previewTick)
        main.postDelayed(previewTick, millisUntilNextMinute(System.currentTimeMillis()))
        if (initialized) refreshPreviewImage()
    }

    override fun onPause() {
        main.removeCallbacks(previewTick)
        super.onPause()
    }

    override fun onStop() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        scene.release()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (initialized) outState.putBundle(STATE_DRAFT, writeDraft(draft))
        super.onSaveInstanceState(outState)
    }

    private fun createScreen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(32))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(scroll)
        scroll.addView(page)

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val mark = text("W", 18f, BG, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = shape(ACCENT, 13f)
        }
        top.addView(mark, LinearLayout.LayoutParams(dp(38), dp(38)))
        top.addView(text("WALLPEP", 13f, INK, Typeface.BOLD).apply { letterSpacing = 0.14f },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        top.addView(text("ON DEVICE", 10f, ACCENT, Typeface.BOLD).apply {
            letterSpacing = 0.08f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = shape(PANEL, 100f, STROKE)
        })
        page.addView(top)
        page.addView(text("A little more\nin the moment.", 32f, INK, Typeface.BOLD).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
            isAccessibilityHeading = true
        }, lp(top = 28))
        page.addView(text("A quiet clock, a date, and a photo that feels like you.", 14f, MUTED), lp(top = 10))

        val previewCard = card()
        val previewHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        previewHeader.addView(text("YOUR WALLPAPER", 10f, MUTED, Typeface.BOLD).apply { letterSpacing = 0.1f },
            LinearLayout.LayoutParams(0, -2, 1f))
        status = text("LOADING", 10f, ACCENT, Typeface.BOLD)
        previewHeader.addView(status)
        previewCard.addView(previewHeader)
        val previewArea = FrameLayout(this)
        val phone = FrameLayout(this).apply {
            background = shape(0xff263f34.toInt(), 19f, 0xff648170.toInt())
            clipToOutline = true
        }
        scene = WallpaperScene(this, miniature = true)
        phone.addView(scene, FrameLayout.LayoutParams(-1, -1))
        val camera = View(this).apply { background = shape(0x99000000.toInt(), 100f) }
        phone.addView(camera, FrameLayout.LayoutParams(dp(28), dp(4), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(7) })
        previewArea.addView(phone, FrameLayout.LayoutParams(dp(150), dp(280), Gravity.CENTER))
        previewCard.addView(previewArea, LinearLayout.LayoutParams(-1, dp(308)).apply { topMargin = dp(6) })
        previewCard.addView(text("A preview. Your photo is center-cropped to fill the screen.", 12f, MUTED).apply {
            gravity = Gravity.CENTER
        })
        page.addView(previewCard, lp(top = 24))

        val photoCard = section("01", "Background", "A photo stays on this device, in app-private storage.")
        photoStatus = text("Using the built-in backdrop", 13f, INK)
        photoCard.addView(photoStatus, lp(top = 14))
        val photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pickButton = button(getString(R.string.choose_photo), false) {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        removeButton = button(getString(R.string.remove_photo), false) { removePhoto() }
        photoRow.addView(pickButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        photoRow.addView(removeButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        photoCard.addView(photoRow, lp(top = 12))
        page.addView(photoCard, lp(top = 14))

        val clockCard = section("02", "Clock & date", "Choose the details that make it yours.")
        timeSpinner = addSelector(clockCard, "Time format", listOf("Follow system", "12-hour", "24-hour")) { index ->
            changeDraft(draft.copy(timeStyle = TimeStyle.entries[index]))
        }
        dateSpinner = addSelector(clockCard, "Date", listOf("Full date", "Short date", "Hidden")) { index ->
            changeDraft(draft.copy(dateStyle = DateStyle.entries[index]))
        }
        fontSpinner = addSelector(clockCard, "Typeface", listOf("Outfit", "DM Sans", "System sans")) { index ->
            changeDraft(draft.copy(font = FontChoice.entries[index]))
        }
        clockCard.addView(text("Text color", 12f, MUTED, Typeface.BOLD), lp(top = 17))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("White", "Mist", "Sand", "Rose", "Ink").forEachIndexed { index, name ->
            val swatch = text("Aa", 15f, TextColors.all[index], Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                contentDescription = "$name text color"
                isClickable = true
                isFocusable = true
                setOnClickListener { changeDraft(draft.copy(textColor = TextColors.all[index])) }
            }
            colorButtons.add(swatch)
            inputs.add(swatch)
            colors.addView(swatch, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) marginStart = dp(7) })
        }
        clockCard.addView(colors, lp(top = 10))
        val size = addSlider(clockCard, "Clock size", 84) { progress ->
            changeDraft(draft.copy(clockSizeSp = progress + 36))
        }
        sizeBar = size.first
        sizeValue = size.second
        page.addView(clockCard, lp(top = 14))

        val positionCard = section("03", "Position", "Move the clock within the screen. It stays inside the edges.")
        val x = addSlider(positionCard, "Left ↔ right", 100) { progress ->
            changeDraft(draft.copy(positionX = progress))
        }
        xBar = x.first
        xValue = x.second
        val y = addSlider(positionCard, "Top ↔ bottom", 100) { progress ->
            changeDraft(draft.copy(positionY = progress))
        }
        yBar = y.first
        yValue = y.second
        page.addView(positionCard, lp(top = 14))

        page.addView(text("YOUR CHANGES", 10f, MUTED, Typeface.BOLD).apply { letterSpacing = 0.1f }, lp(top = 25))
        page.addView(text("Saving updates the active settings. Closing the chooser does not undo a save.", 13f, MUTED), lp(top = 8))
        saveButton = button(getString(R.string.save_settings), true) {
            lifecycleScope.launch { saveDraft(showToast = true) }
        }
        applyButton = button(getString(R.string.apply_wallpaper), false) { applyWallpaper() }
        page.addView(saveButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(15) })
        page.addView(applyButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(9) })
        page.addView(text("In the system chooser, select Home screen only. Available choices depend on your device.", 12f, MUTED), lp(top = 12))
        return root
    }

    private fun section(number: String, title: String, subtitle: String): LinearLayout = card().apply {
        addView(text("$number  /  WALLPAPER STUDIO", 10f, ACCENT, Typeface.BOLD).apply { letterSpacing = 0.1f })
        addView(text(title, 21f, INK, Typeface.BOLD).apply { isAccessibilityHeading = true }, lp(top = 9))
        addView(text(subtitle, 12f, MUTED), lp(top = 5))
    }

    private fun addSelector(parent: LinearLayout, label: String, choices: List<String>, selected: (Int) -> Unit): Spinner {
        parent.addView(text(label, 12f, MUTED, Typeface.BOLD), lp(top = 16))
        val spinner = Spinner(this, Spinner.MODE_DROPDOWN).apply {
            background = shape(FIELD, 12f, STROKE)
            contentDescription = label
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, choices) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    super.getView(position, convertView, parent).also { view ->
                        (view as TextView).apply { setTextColor(INK); textSize = 14f; setPadding(dp(14), 0, dp(14), 0) }
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).also { view ->
                        (view as TextView).apply { setTextColor(INK); setBackgroundColor(FIELD); setPadding(dp(14), dp(12), dp(14), dp(12)) }
                    }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (initialized && !syncing && !busy) selected(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        inputs.add(spinner)
        parent.addView(spinner, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        return spinner
    }

    private fun addSlider(parent: LinearLayout, label: String, max: Int, changed: (Int) -> Unit): Pair<SeekBar, TextView> {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 12f, MUTED, Typeface.BOLD), LinearLayout.LayoutParams(0, -2, 1f))
        val value = text("", 12f, ACCENT, Typeface.BOLD)
        row.addView(value)
        parent.addView(row, lp(top = 18))
        val seek = SeekBar(this).apply {
            this.max = max
            contentDescription = label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && initialized && !busy) changed(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        inputs.add(seek)
        parent.addView(seek, LinearLayout.LayoutParams(-1, dp(42)))
        return seek to value
    }

    private fun changeDraft(next: WallpaperSettings) {
        if (!initialized || busy) return
        val photoChanged = draft.backgroundName != next.backgroundName
        draft = next.sanitized()
        syncControls()
        if (photoChanged) refreshPreviewImage()
        setEnabledState()
    }

    private fun syncControls() {
        syncing = true
        scene.settings = draft
        timeSpinner.setSelection(draft.timeStyle.ordinal)
        dateSpinner.setSelection(draft.dateStyle.ordinal)
        fontSpinner.setSelection(draft.font.ordinal)
        sizeBar.progress = draft.clockSizeSp - 36
        xBar.progress = draft.positionX
        yBar.progress = draft.positionY
        sizeValue.text = "${draft.clockSizeSp} sp"
        xValue.text = "${draft.positionX}%"
        yValue.text = "${draft.positionY}%"
        photoStatus.text = if (draft.backgroundName == null) "Using the built-in backdrop" else "Photo selected · center crop"
        status.text = if (draft == active) "SAVED" else "UNSAVED"
        colorButtons.forEachIndexed { index, view ->
            val selected = TextColors.all[index] == draft.textColor
            view.background = shape(FIELD, 12f, if (selected) ACCENT else STROKE, if (selected) 2 else 1)
            view.isSelected = selected
        }
        syncing = false
    }

    private fun setEnabledState() {
        val enabled = initialized && !busy
        inputs.forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.5f }
        if (::removeButton.isInitialized) removeButton.isEnabled = enabled && draft.backgroundName != null
        if (::saveButton.isInitialized) saveButton.isEnabled = enabled
        if (::applyButton.isInitialized) applyButton.isEnabled = enabled
    }

    private suspend fun importPhoto(uri: Uri) {
        if (!initialized || busy) return
        busy = true
        setEnabledState()
        try {
            val previous = draft.backgroundName
            val name = backgrounds.importPhoto(uri)
            if (previous != null && previous != active.backgroundName) {
                withContext(Dispatchers.IO) { backgrounds.delete(previous) }
            }
            draft = draft.copy(backgroundName = name)
            syncControls()
            refreshPreviewImage()
            toast("Photo copied privately. Save to use it.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            toast("Could not use that photo. Try an image under 40 MB.")
        } finally {
            busy = false
            setEnabledState()
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
        setEnabledState()
        return try {
            val toSave = draft.sanitized()
            repository.save(toSave)
            active = toSave
            draft = toSave
            syncControls()
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
            setEnabledState()
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
        val name = draft.backgroundName
        if (name == null) {
            scene.setBackgroundBitmap(null)
            return
        }
        previewJob = lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = backgrounds.decode(name, 900)
            var transferred = false
            try {
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    if (ticket == previewGeneration && !isFinishing && !isDestroyed) {
                        scene.setBackgroundBitmap(bitmap)
                        transferred = true
                    }
                }
            } finally {
                if (!transferred) bitmap?.recycle()
            }
        }
    }

    private fun writeDraft(s: WallpaperSettings) = Bundle().apply {
        putString("time", s.timeStyle.name); putString("date", s.dateStyle.name)
        putString("font", s.font.name); putInt("color", s.textColor); putInt("size", s.clockSizeSp)
        putInt("x", s.positionX); putInt("y", s.positionY); putString("background", s.backgroundName)
    }

    private fun readDraft(bundle: Bundle): WallpaperSettings = WallpaperSettings(
        timeStyle = storedEnum(bundle.getString("time"), active.timeStyle),
        dateStyle = storedEnum(bundle.getString("date"), active.dateStyle),
        font = storedEnum(bundle.getString("font"), active.font),
        textColor = bundle.getInt("color", active.textColor),
        clockSizeSp = bundle.getInt("size", active.clockSizeSp),
        positionX = bundle.getInt("x", active.positionX),
        positionY = bundle.getInt("y", active.positionY),
        backgroundName = bundle.getString("background"),
    ).sanitized()

    private fun button(label: String, primary: Boolean, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) BG else INK)
        background = shape(if (primary) ACCENT else FIELD, 13f, if (primary) ACCENT else STROKE)
        minHeight = dp(48)
        setOnClickListener { click() }
        inputs.add(this)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = shape(PANEL, 20f, STROKE)
    }

    private fun text(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", style)
        includeFontPadding = true
    }

    private fun shape(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(strokeWidth), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATE_DRAFT = "draft"
        private val BG = Color.rgb(16, 23, 19)
        private val PANEL = Color.rgb(27, 37, 31)
        private val FIELD = Color.rgb(37, 48, 40)
        private val STROKE = Color.rgb(62, 80, 67)
        private val ACCENT = Color.rgb(189, 214, 196)
        private val INK = Color.rgb(239, 245, 237)
        private val MUTED = Color.rgb(163, 180, 167)
    }
}
