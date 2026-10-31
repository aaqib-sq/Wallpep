package dev.wallpep.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.wallpep.R
import dev.wallpep.fonts.FontCatalog
import dev.wallpep.model.DateStyle
import dev.wallpep.model.TextColors
import dev.wallpep.model.TimeStyle
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.render.WallpaperScene

/**
 * The golden editor screen: large live preview, a Clock / Date tab bar, and card sections.
 * Only controls backed by real settings are built here; Text, Transform, Effects, Colorize and
 * Parallax tabs arrive with their own engine slices.
 */
class EditorScreen(private val activity: ComponentActivity, private val listener: Listener) {

    interface Listener {
        fun currentSettings(): WallpaperSettings
        fun canInteract(): Boolean
        fun onSettingsChanged(next: WallpaperSettings)
        fun onPickPhoto()
        fun onRemovePhoto()
        fun onSave()
        fun onApplyWallpaper()
    }

    private enum class Tab(val label: String) { CLOCK("Clock"), DATE("Date") }

    val scene = WallpaperScene(activity, miniature = true)
    val root: View

    private var syncing = false
    private val interactive = mutableListOf<View>()
    private val swatches = mutableListOf<TextView>()

    private lateinit var statusChip: TextView
    private lateinit var notice: TextView
    private lateinit var photoStatus: TextView
    private lateinit var clockTab: TextView
    private lateinit var dateTab: TextView
    private lateinit var clockPage: LinearLayout
    private lateinit var datePage: LinearLayout
    private lateinit var timeSpinner: Spinner
    private lateinit var dateSpinner: Spinner
    private lateinit var sizeBar: SeekBar
    private lateinit var sizeValue: TextView
    private lateinit var xBar: SeekBar
    private lateinit var xValue: TextView
    private lateinit var yBar: SeekBar
    private lateinit var yValue: TextView
    private lateinit var clockFontName: TextView
    private lateinit var clockFontSample: TextView
    private lateinit var dateFontName: TextView
    private lateinit var dateFontSample: TextView
    private lateinit var clockForeground: Switch
    private lateinit var hourForeground: Switch
    private lateinit var minuteForeground: Switch
    private lateinit var dateForeground: Switch
    private lateinit var removeButton: Button
    private lateinit var saveButton: Button
    private lateinit var applyButton: Button
    private var loadedClockFace: String? = null
    private var loadedDateFace: String? = null

    init {
        root = buildScreen()
        selectTab(Tab.CLOCK)
    }

    // ---------------------------------------------------------------- layout

    private fun buildScreen(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Gold.BACKGROUND)
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Gold.BACKGROUND)
        }
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(24))
        }
        scroll.addView(page)
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(buildActionBar())

        buildHeader(page)
        page.addView(buildPreviewPanel(), full(top = 16))
        page.addView(buildPhotoCard(), full(top = 12))
        page.addView(buildTabBar(), full(top = 12))
        clockPage = buildClockTab()
        datePage = buildDateTab().apply { visibility = View.GONE }
        page.addView(clockPage, full(top = 12))
        page.addView(datePage, full(top = 12))
        return column
    }

    private fun buildHeader(page: LinearLayout) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = TextView(activity).apply {
            text = "W"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Gold.ON_GOLD)
            typeface = WallpepType.display(activity)
            background = activity.shape(Gold.GOLD, 13f)
        }
        row.addView(mark, LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)))
        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        names.addView(activity.uiText("WALLPEP", 14f, Gold.GOLD, WallpepType.display(activity), 0.22f))
        names.addView(
            activity.uiText("GOLDEN STUDIO", 8.5f, Gold.MUTED, WallpepType.bodyMedium(activity), 0.22f),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = activity.dp(3) },
        )
        row.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = activity.dp(12) })
        row.addView(
            activity.uiText("ON DEVICE", 8.5f, Gold.GOLD, WallpepType.bodyMedium(activity), 0.14f).apply {
                setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7))
                background = activity.shape(0x00000000, 100f, Gold.GOLD_BORDER)
            },
        )
        page.addView(row)
        page.addView(
            activity.uiText("Your golden hour.", 26f, Gold.TEXT, WallpepType.display(activity)),
            full(top = 20),
        )
        page.addView(
            activity.uiText(
                "A clock and a date over your own photo. Everything is drawn on this device and never leaves it.",
                12.5f,
                Gold.MUTED,
            ),
            full(top = 8),
        )
        notice = activity.uiText("", 11.5f, Gold.TEXT).apply {
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            background = activity.shape(0x1AD4AF37, 12f, Gold.GOLD_BORDER)
            visibility = View.GONE
        }
        page.addView(notice, full(top = 12))
    }

    private fun buildPreviewPanel(): View {
        val panel = activity.card(padding = 14)
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            activity.uiText("LIVE PREVIEW", 9.5f, Gold.GOLD, WallpepType.bodyMedium(activity), 0.2f),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        statusChip = activity.uiText("SAVED", 8.5f, Gold.GOLD, WallpepType.bodyMedium(activity), 0.12f).apply {
            setPadding(activity.dp(10), activity.dp(5), activity.dp(10), activity.dp(5))
            background = activity.shape(0x00000000, 100f, Gold.GOLD_BORDER_STRONG)
        }
        header.addView(statusChip)
        panel.addView(header)

        val metrics = activity.resources.displayMetrics
        // Large enough to judge the type, small enough that the tab bar stays on screen.
        val previewHeight = (metrics.heightPixels * 0.36f).toInt()
            .coerceIn(activity.dp(260), activity.dp(380))
        val previewWidth = (previewHeight * 0.56f).toInt()
            .coerceAtMost(metrics.widthPixels - activity.dp(96))
            .coerceAtLeast(activity.dp(150))

        val stage = FrameLayout(activity)
        val phone = FrameLayout(activity).apply {
            background = activity.shape(Gold.SURFACE_RAISED, 26f, Gold.GOLD_BORDER_STRONG, 1)
            clipToOutline = true
        }
        phone.addView(scene, FrameLayout.LayoutParams(-1, -1))
        val camera = View(activity).apply { background = activity.shape(0xCC000000.toInt(), 100f) }
        phone.addView(
            camera,
            FrameLayout.LayoutParams(activity.dp(30), activity.dp(4), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = activity.dp(8)
            },
        )
        stage.addView(phone, FrameLayout.LayoutParams(previewWidth, previewHeight, Gravity.CENTER))
        panel.addView(stage, LinearLayout.LayoutParams(-1, previewHeight + activity.dp(22)).apply { topMargin = activity.dp(10) })
        panel.addView(
            activity.uiText(
                "Home-screen frame · center-cropped. With a cutout ready, the subject draws in front of the clock and date.",
                11f,
                Gold.MUTED,
            ),
            full(top = 2),
        )
        return panel
    }

    private fun buildPhotoCard(): View {
        val cardView = card("01", "BACKGROUND", "Copied into app-private storage; the original is untouched.")
        photoStatus = activity.uiText("", 12.5f, Gold.TEXT).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        cardView.addView(photoStatus, full(top = 12))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val pickButton = activity.actionButton(activity.getString(R.string.choose_photo), primary = true) { listener.onPickPhoto() }
        removeButton = activity.actionButton(activity.getString(R.string.remove_photo), primary = false) { listener.onRemovePhoto() }
        interactive.add(pickButton)
        interactive.add(removeButton)
        row.addView(pickButton, LinearLayout.LayoutParams(0, activity.dp(46), 1f))
        row.addView(removeButton, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { marginStart = activity.dp(10) })
        cardView.addView(row, full(top = 12))
        return cardView
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(5), activity.dp(5), activity.dp(5), activity.dp(5))
            background = activity.shape(Gold.SURFACE, 16f, Gold.GOLD_BORDER)
        }
        clockTab = tabButton(Tab.CLOCK)
        dateTab = tabButton(Tab.DATE)
        bar.addView(clockTab, LinearLayout.LayoutParams(0, activity.dp(42), 1f))
        bar.addView(dateTab, LinearLayout.LayoutParams(0, activity.dp(42), 1f).apply { marginStart = activity.dp(5) })
        return bar
    }

    private fun tabButton(target: Tab): TextView = TextView(activity).apply {
        text = target.label
        textSize = 13.5f
        gravity = Gravity.CENTER
        typeface = WallpepType.display(activity)
        letterSpacing = 0.12f
        isClickable = true
        isFocusable = true
        contentDescription = "${target.label} tab"
        setOnClickListener { selectTab(target) }
    }

    private fun buildClockTab(): LinearLayout {
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val format = card("01", "CLOCK FORMAT", "How the time is written.")
        timeSpinner = selector(format, "Time format", listOf("Follow system", "12-hour", "24-hour")) { index ->
            listener.onSettingsChanged(listener.currentSettings().copy(timeStyle = TimeStyle.entries[index]))
        }
        val size = slider(format, "Clock size", MAX_CLOCK_SIZE - MIN_CLOCK_SIZE) { progress ->
            listener.onSettingsChanged(listener.currentSettings().copy(clockSizeSp = progress + MIN_CLOCK_SIZE))
        }
        sizeBar = size.first
        sizeValue = size.second
        page.addView(format, full())

        val typography = card("02", "CLOCK TYPOGRAPHY", "Every face below is bundled in the app.")
        val clockRow = fontRow("Typeface", "clock") { pickFont(allowFollowClock = false, selected = FontCatalog.sanitizeId(listener.currentSettings().font)) { id ->
            listener.onSettingsChanged(listener.currentSettings().copy(font = id))
        } }
        clockFontName = clockRow.name
        clockFontSample = clockRow.sample
        typography.addView(clockRow.row, full(top = 12))
        colorRow(typography, "Text color · clock and date")
        page.addView(typography, full(top = 12))

        val position = card("03", "POSITION", "Places the clock and date together. Text stays inside the edges.")
        val x = slider(position, "Left ↔ right", 100) { progress ->
            listener.onSettingsChanged(listener.currentSettings().copy(positionX = progress))
        }
        xBar = x.first
        xValue = x.second
        val y = slider(position, "Top ↔ bottom", 100) { progress ->
            listener.onSettingsChanged(listener.currentSettings().copy(positionY = progress))
        }
        yBar = y.first
        yValue = y.second
        page.addView(position, full(top = 12))

        val depth = card("04", "DEPTH", "The cutout subject starts in front of the clock. Without a cutout, everything stays above the photo.")
        clockForeground = toggle(depth, "Show clock over foreground") { checked ->
            listener.onSettingsChanged(listener.currentSettings().copy(hourOverForeground = checked, minuteOverForeground = checked))
        }
        hourForeground = toggle(depth, "Hour over foreground") { checked ->
            listener.onSettingsChanged(listener.currentSettings().copy(hourOverForeground = checked))
        }
        minuteForeground = toggle(depth, "Minute over foreground") { checked ->
            listener.onSettingsChanged(listener.currentSettings().copy(minuteOverForeground = checked))
        }
        depth.addView(
            activity.uiText(
                "The whole-clock switch sets hour and minute together; the separator and AM/PM follow the minute.",
                11f,
                Gold.MUTED,
            ),
            full(top = 10),
        )
        page.addView(depth, full(top = 12))
        return page
    }

    private fun buildDateTab(): LinearLayout {
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val format = card("01", "DATE FORMAT", "The date sits under the clock.")
        dateSpinner = selector(format, "Date", listOf("Full date", "Short date", "Hidden")) { index ->
            listener.onSettingsChanged(listener.currentSettings().copy(dateStyle = DateStyle.entries[index]))
        }
        page.addView(format, full())

        val typography = card("02", "DATE TYPOGRAPHY", "Use the clock's face, or give the date its own.")
        val dateRow = fontRow("Typeface", "date") { pickFont(allowFollowClock = true, selected = listener.currentSettings().dateFont) { id ->
            listener.onSettingsChanged(listener.currentSettings().copy(dateFont = id))
        } }
        dateFontName = dateRow.name
        dateFontSample = dateRow.sample
        typography.addView(dateRow.row, full(top = 12))
        colorRow(typography, "Text color · clock and date")
        page.addView(typography, full(top = 12))

        val depth = card("03", "DATE DEPTH", "Choose whether the date sits behind or in front of the cutout subject.")
        dateForeground = toggle(depth, "Show date over foreground") { checked ->
            listener.onSettingsChanged(listener.currentSettings().copy(dateOverForeground = checked))
        }
        depth.addView(
            activity.uiText("The date shares the clock's size and position.", 11f, Gold.MUTED),
            full(top = 8),
        )
        page.addView(depth, full(top = 12))
        return page
    }

    private fun buildActionBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Gold.SURFACE)
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(12))
        }
        bar.addView(View(activity).apply { setBackgroundColor(Gold.GOLD_BORDER) }, LinearLayout.LayoutParams(-1, 1))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        saveButton = activity.actionButton(activity.getString(R.string.save_settings), primary = true) { listener.onSave() }
        applyButton = activity.actionButton(activity.getString(R.string.apply_wallpaper), primary = false) { listener.onApplyWallpaper() }
        interactive.add(saveButton)
        interactive.add(applyButton)
        row.addView(saveButton, LinearLayout.LayoutParams(0, activity.dp(50), 1f))
        row.addView(applyButton, LinearLayout.LayoutParams(0, activity.dp(50), 1f).apply { marginStart = activity.dp(10) })
        bar.addView(row, full(top = 12))
        bar.addView(
            activity.uiText(
                "Saving updates the active settings at once. The chooser applies them to the home screen; cancelling it does not undo a save.",
                10.5f,
                Gold.MUTED,
            ),
            full(top = 8),
        )
        return bar
    }

    // ---------------------------------------------------------------- controls

    private fun card(number: String, title: String, subtitle: String?): LinearLayout {
        val view = activity.card()
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(activity.uiText(number, 9.5f, Gold.GOLD, WallpepType.display(activity), 0.16f))
        header.addView(
            activity.uiText(title, 9.5f, Gold.MUTED, WallpepType.bodyMedium(activity), 0.18f),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = activity.dp(10) },
        )
        view.addView(header)
        if (subtitle != null) {
            view.addView(activity.uiText(subtitle, 12f, Gold.MUTED), full(top = 8))
        }
        return view
    }

    private fun fieldLabel(parent: LinearLayout, label: String) {
        parent.addView(
            activity.uiText(label, 11f, Gold.MUTED, WallpepType.bodyMedium(activity), 0.07f),
            full(top = 16),
        )
    }

    private fun selector(parent: LinearLayout, label: String, choices: List<String>, onSelect: (Int) -> Unit): Spinner {
        fieldLabel(parent, label)
        val spinner = Spinner(activity, Spinner.MODE_DROPDOWN).apply {
            background = activity.shape(Gold.FIELD, 12f, Gold.GOLD_BORDER)
            contentDescription = label
            adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, choices) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { view ->
                        (view as TextView).apply {
                            setTextColor(Gold.TEXT)
                            textSize = 14f
                            typeface = WallpepType.body(activity)
                            setPadding(activity.dp(14), 0, activity.dp(14), 0)
                        }
                    }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).also { view ->
                        (view as TextView).apply {
                            setTextColor(Gold.TEXT)
                            setBackgroundColor(Gold.SURFACE_RAISED)
                            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
                        }
                    }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!syncing && listener.canInteract()) onSelect(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        interactive.add(spinner)
        parent.addView(spinner, LinearLayout.LayoutParams(-1, activity.dp(46)).apply { topMargin = activity.dp(8) })
        return spinner
    }

    private fun slider(parent: LinearLayout, label: String, max: Int, onChange: (Int) -> Unit): Pair<SeekBar, TextView> {
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            activity.uiText(label, 11f, Gold.MUTED, WallpepType.bodyMedium(activity), 0.07f),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val value = activity.uiText("", 11f, Gold.GOLD, WallpepType.bodyMedium(activity))
        header.addView(value)
        parent.addView(header, full(top = 16))
        val bar = activity.goldSeekBar(max, label).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !syncing && listener.canInteract()) onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        interactive.add(bar)
        parent.addView(bar, LinearLayout.LayoutParams(-1, activity.dp(36)))
        return bar to value
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun toggle(parent: LinearLayout, label: String, onChange: (Boolean) -> Unit): Switch {
        val control = Switch(activity).apply {
            text = label
            textSize = 12.5f
            setTextColor(Gold.TEXT)
            typeface = WallpepType.body(activity)
            minHeight = activity.dp(46)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing && listener.canInteract()) onChange(checked)
            }
        }
        interactive.add(control)
        parent.addView(control, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(6) })
        return control
    }

    private data class FontRow(val name: TextView, val sample: TextView, val row: LinearLayout)

    private fun fontRow(label: String, rowTag: String, onPick: () -> Unit): FontRow {
        val holder = LinearLayout(activity).apply { tag = rowTag }
        val sample = activity.uiText("Aa", 20f, Gold.GOLD)
        holder.addView(sample, LinearLayout.LayoutParams(activity.dp(38), -2).apply { gravity = Gravity.CENTER_VERTICAL })
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val name = activity.uiText("", 13.5f, Gold.TEXT, WallpepType.bodyMedium(activity))
        column.addView(name)
        column.addView(activity.uiText("Tap to browse ${FontCatalog.families.size} bundled typefaces", 10f, Gold.MUTED), full(top = 2))
        holder.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = activity.dp(10) })
        holder.addView(activity.uiText("›", 20f, Gold.GOLD), LinearLayout.LayoutParams(-2, -2).apply { marginStart = activity.dp(6) })
        holder.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
            background = activity.shape(Gold.FIELD, 12f, Gold.GOLD_BORDER)
            isClickable = true
            isFocusable = true
            contentDescription = "$label picker"
            setOnClickListener { if (listener.canInteract()) onPick() }
        }
        interactive.add(holder)
        return FontRow(name, sample, holder)
    }

    private fun colorRow(parent: LinearLayout, label: String) {
        fieldLabel(parent, label)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        TextColors.all.forEachIndexed { index, color ->
            val swatch = TextView(activity).apply {
                text = "Aa"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (Color.luminance(color) > 0.45f) Color.BLACK else Color.WHITE)
                background = activity.shape(color, 12f)
                tag = color
                contentDescription = "${COLOR_NAMES[index]} text color"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (listener.canInteract()) listener.onSettingsChanged(listener.currentSettings().copy(textColor = color))
                }
            }
            swatches.add(swatch)
            interactive.add(swatch)
            row.addView(swatch, LinearLayout.LayoutParams(0, activity.dp(44), 1f).apply {
                if (index > 0) marginStart = activity.dp(8)
            })
        }
        parent.addView(row, full(top = 10))
    }

    private fun pickFont(allowFollowClock: Boolean, selected: String, onPicked: (String) -> Unit) {
        FontPickerDialog.show(activity, allowFollowClock, selected) { id ->
            if (listener.canInteract()) onPicked(id)
        }
    }

    private fun selectTab(next: Tab) {
        if (::clockPage.isInitialized) clockPage.visibility = if (next == Tab.CLOCK) View.VISIBLE else View.GONE
        if (::datePage.isInitialized) datePage.visibility = if (next == Tab.DATE) View.VISIBLE else View.GONE
        styleTab(clockTab, next == Tab.CLOCK)
        styleTab(dateTab, next == Tab.DATE)
    }

    private fun styleTab(view: TextView, selected: Boolean) {
        view.background = if (selected) activity.shape(Gold.GOLD, 12f) else activity.shape(0x00000000, 12f)
        view.setTextColor(if (selected) Gold.ON_GOLD else Gold.MUTED)
        view.isSelected = selected
    }

    // ---------------------------------------------------------------- state

    fun showNotice(message: String) {
        notice.text = message
        notice.visibility = View.VISIBLE
    }

    /** Mirrors the draft into every control without echoing change callbacks. */
    fun render(settings: WallpaperSettings, saved: WallpaperSettings, photoLabel: String, enabled: Boolean) {
        syncing = true
        scene.settings = settings
        timeSpinner.setSelection(settings.timeStyle.ordinal)
        dateSpinner.setSelection(settings.dateStyle.ordinal)
        sizeBar.progress = settings.clockSizeSp - MIN_CLOCK_SIZE
        xBar.progress = settings.positionX
        yBar.progress = settings.positionY
        sizeValue.text = "${settings.clockSizeSp} sp"
        xValue.text = "${settings.positionX}%"
        yValue.text = "${settings.positionY}%"
        clockForeground.isChecked = settings.clockOverForeground
        hourForeground.isChecked = settings.hourOverForeground
        minuteForeground.isChecked = settings.minuteOverForeground
        dateForeground.isChecked = settings.dateOverForeground
        photoStatus.text = photoLabel
        val clean = settings == saved
        statusChip.text = if (clean) "SAVED" else "UNSAVED"
        statusChip.setTextColor(if (clean) Gold.GOLD else Gold.ON_GOLD)
        statusChip.background = if (clean) activity.shape(0x00000000, 100f, Gold.GOLD_BORDER_STRONG) else activity.shape(Gold.GOLD, 100f)
        updateFontLabels(settings)
        interactive.forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.45f
        }
        val textColor = settings.textColor
        swatches.forEach { swatch ->
            val color = swatch.tag as Int
            val selected = color == textColor
            swatch.background = activity.shape(color, 12f, if (selected) Gold.GOLD_LIGHT else null, if (selected) 3 else 1)
            swatch.isSelected = selected
            swatch.alpha = when {
                !enabled -> 0.45f
                selected -> 1f
                else -> 0.82f
            }
        }
        val canRemove = enabled && settings.backgroundName != null
        removeButton.isEnabled = canRemove
        removeButton.alpha = if (canRemove) 1f else 0.45f
        syncing = false
    }

    private fun updateFontLabels(settings: WallpaperSettings) {
        clockFontName.text = FontCatalog.displayName(settings.font)
        clockFontName.contentDescription = "Clock typeface: ${FontCatalog.displayName(settings.font)}"
        if (loadedClockFace != settings.font) {
            loadedClockFace = settings.font
            clockFontSample.typeface = FontCatalog.typeface(activity, settings.font)
        }
        val dateFaceId = if (settings.dateFont == FontCatalog.FOLLOW_CLOCK) settings.font else settings.dateFont
        dateFontName.text = if (settings.dateFont == FontCatalog.FOLLOW_CLOCK) {
            "Same as clock · ${FontCatalog.displayName(settings.font)}"
        } else {
            FontCatalog.displayName(settings.dateFont)
        }
        dateFontName.contentDescription = "Date typeface: ${FontCatalog.displayName(settings.dateFont)}"
        if (loadedDateFace != dateFaceId) {
            loadedDateFace = dateFaceId
            dateFontSample.typeface = FontCatalog.typeface(activity, dateFaceId)
        }
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(top) }

    private companion object {
        const val MIN_CLOCK_SIZE = 36
        const val MAX_CLOCK_SIZE = 120
        val COLOR_NAMES = listOf("White", "Mist", "Sand", "Rose", "Ink")
    }
}
