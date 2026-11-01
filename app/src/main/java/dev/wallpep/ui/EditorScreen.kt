package dev.wallpep.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
 * The golden editor: a full-width live preview of the real wallpaper scene on top, the photo
 * actions, a Clock / Date tab bar, then card sections for the selected tab.
 *
 * Every control here is backed by a setting the engine already draws: format, typography
 * (face, size, letter spacing), colour with opacity, position, and the four over-foreground
 * switches. Text layers, Transform warp, Effects, Colorize and Parallax are deliberately absent
 * until their engine slices exist. Everything is unlocked; there is no premium gate.
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
    /** One state projection per control, so [render] never echoes a user callback. */
    private val binders = mutableListOf<(WallpaperSettings) -> Unit>()

    private lateinit var statusChip: TextView
    private lateinit var notice: TextView
    private lateinit var photoStatus: TextView
    private lateinit var clockTab: TextView
    private lateinit var dateTab: TextView
    private lateinit var clockPage: LinearLayout
    private lateinit var datePage: LinearLayout
    private lateinit var removeButton: Button
    private lateinit var pageScroll: ScrollView

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
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        column.addView(buildHeader())
        column.addView(buildPreview(), LinearLayout.LayoutParams(-1, previewHeightPx()))
        column.addView(buildPhotoRow())
        column.addView(buildTabBar())

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(14), activity.dp(20), activity.dp(24))
        }
        pageScroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Gold.BACKGROUND)
            addView(page)
        }
        column.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        notice = activity.uiText("", 11.5f, Gold.TEXT).apply {
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            background = activity.shape(0x1AD4AF37, 12f, Gold.GOLD_BORDER)
            visibility = View.GONE
        }
        page.addView(notice, full(bottom = 12))

        clockPage = buildClockTab()
        datePage = buildDateTab().apply { visibility = View.GONE }
        page.addView(clockPage)
        page.addView(datePage)

        column.addView(buildActionBar())
        return column
    }

    private fun buildHeader(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(10))
        }
        val mark = TextView(activity).apply {
            text = "W"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Gold.ON_GOLD)
            typeface = WallpepType.display(activity)
            background = activity.shape(Gold.GOLD, 13f)
        }
        row.addView(mark, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
        row.addView(
            activity.uiText("WALLPEP", 13f, Gold.GOLD, WallpepType.display(activity), 0.22f),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = activity.dp(11) },
        )
        statusChip = activity.uiText("SAVED", 8.5f, Gold.MUTED, WallpepType.bodyMedium(activity), 0.12f).apply {
            setPadding(activity.dp(10), activity.dp(5), activity.dp(10), activity.dp(5))
        }
        row.addView(statusChip)
        return row
    }

    /** The real scene, full width, edge to edge. No phone frame and no decorative chrome. */
    private fun buildPreview(): View {
        val stage = FrameLayout(activity).apply { setBackgroundColor(Gold.SURFACE) }
        stage.addView(scene, FrameLayout.LayoutParams(-1, -1))
        photoStatus = activity.uiText("", 10f, Gold.MUTED).apply {
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
            background = activity.shape(0xB3000000.toInt(), 10f)
        }
        stage.addView(
            photoStatus,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                marginStart = activity.dp(14)
                bottomMargin = activity.dp(14)
                rightMargin = activity.dp(14)
            },
        )
        return stage
    }

    private fun previewHeightPx(): Int {
        val metrics = activity.resources.displayMetrics
        return (metrics.heightPixels * 0.36f).toInt().coerceIn(activity.dp(210), activity.dp(400))
    }

    private fun buildPhotoRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(20), activity.dp(10), activity.dp(20), 0)
        }
        val pickButton = activity.actionButton(activity.getString(R.string.choose_photo), primary = true) {
            listener.onPickPhoto()
        }
        removeButton = activity.actionButton(activity.getString(R.string.remove_photo), primary = false) {
            listener.onRemovePhoto()
        }
        interactive.add(pickButton)
        interactive.add(removeButton)
        row.addView(pickButton, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        row.addView(removeButton, LinearLayout.LayoutParams(0, activity.dp(44), 1f).apply { marginStart = activity.dp(10) })
        return row
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), 0)
        }
        val track = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
            background = activity.shape(Gold.SURFACE, 15f, Gold.LINE)
        }
        clockTab = tabButton(Tab.CLOCK)
        dateTab = tabButton(Tab.DATE)
        track.addView(clockTab, LinearLayout.LayoutParams(0, activity.dp(42), 1f))
        track.addView(dateTab, LinearLayout.LayoutParams(0, activity.dp(42), 1f).apply { marginStart = activity.dp(4) })
        bar.addView(track, LinearLayout.LayoutParams(-1, -2))
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

        val format = card("FORMAT")
        segmented(format, "Time format", listOf("System", "12-hour", "24-hour"), { it.timeStyle.ordinal }) { index ->
            change { it.copy(timeStyle = TimeStyle.entries[index]) }
        }
        toggle(format, "Show clock", { it.showClock }) { checked -> change { it.copy(showClock = checked) } }
        page.addView(format)

        val typography = card("TYPOGRAPHY")
        fontCards(typography, CLOCK_FACE_CARDS, { it.font }) { id -> change { it.copy(font = id) } }
        browseRow(typography, { it.font }) { picked ->
            FontPickerDialog.show(activity, allowFollowClock = false, selectedId = picked) { id -> change { it.copy(font = id) } }
        }
        slider(typography, "Size", WallpaperSettings.MIN_CLOCK_SIZE..WallpaperSettings.MAX_CLOCK_SIZE, { it.clockSizeSp }, { "$it sp" }) { value ->
            change { it.copy(clockSizeSp = value) }
        }
        slider(typography, "Letter spacing", 0..WallpaperSettings.MAX_LETTER_SPACING, { it.clockLetterSpacing }, ::spacingLabel) { value ->
            change { it.copy(clockLetterSpacing = value) }
        }
        page.addView(typography, full(top = 12))

        val colour = card("COLOUR")
        colourRow(colour, allowFollowClock = false, valueOf = { s -> s.textColor }) { picked ->
            change { it.copy(textColor = picked ?: TextColors.WHITE) }
        }
        slider(colour, "Opacity", WallpaperSettings.MIN_OPACITY..100, { it.clockOpacity }, { "$it%" }) { value ->
            change { it.copy(clockOpacity = value) }
        }
        page.addView(colour, full(top = 12))

        val position = card("POSITION")
        slider(position, "Left ↔ right", 0..100, { it.positionX }, { "$it%" }) { value ->
            change { it.copy(positionX = value) }
        }
        slider(position, "Top ↔ bottom", 0..100, { it.positionY }, { "$it%" }) { value ->
            change { it.copy(positionY = value) }
        }
        page.addView(position, full(top = 12))

        val depth = card("DEPTH")
        toggle(depth, "Show clock over foreground", { it.clockOverForeground }) { checked ->
            change { it.copy(hourOverForeground = checked, minuteOverForeground = checked) }
        }
        toggle(depth, "Hour over foreground", { it.hourOverForeground }) { checked ->
            change { it.copy(hourOverForeground = checked) }
        }
        toggle(depth, "Minute over foreground", { it.minuteOverForeground }) { checked ->
            change { it.copy(minuteOverForeground = checked) }
        }
        note(depth, "Off draws behind the cutout subject. The clock switch sets hour and minute together.")
        page.addView(depth, full(top = 12))
        return page
    }

    private fun buildDateTab(): LinearLayout {
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val format = card("FORMAT")
        segmented(format, "Date format", listOf("Full", "Short", "Hidden"), { it.dateStyle.ordinal }) { index ->
            change { it.copy(dateStyle = DateStyle.entries[index]) }
        }
        toggle(format, "All uppercase", { it.dateUppercase }) { checked -> change { it.copy(dateUppercase = checked) } }
        page.addView(format)

        val typography = card("TYPOGRAPHY")
        fontCards(typography, DATE_FACE_CARDS, { it.dateFont }) { id -> change { it.copy(dateFont = id) } }
        browseRow(typography, { it.dateFont }) { picked ->
            FontPickerDialog.show(activity, allowFollowClock = true, selectedId = picked) { id -> change { it.copy(dateFont = id) } }
        }
        slider(typography, "Size", WallpaperSettings.MIN_DATE_SIZE..WallpaperSettings.MAX_DATE_SIZE, { it.dateSizeSp }, { "$it sp" }) { value ->
            change { it.copy(dateSizeSp = value) }
        }
        slider(typography, "Letter spacing", 0..WallpaperSettings.MAX_LETTER_SPACING, { it.dateLetterSpacing }, ::spacingLabel) { value ->
            change { it.copy(dateLetterSpacing = value) }
        }
        page.addView(typography, full(top = 12))

        val colour = card("COLOUR")
        colourRow(colour, allowFollowClock = true, valueOf = { it.dateColor }) { picked ->
            change { it.copy(dateColor = picked) }
        }
        slider(colour, "Opacity", WallpaperSettings.MIN_OPACITY..100, { it.dateOpacity }, { "$it%" }) { value ->
            change { it.copy(dateOpacity = value) }
        }
        page.addView(colour, full(top = 12))

        val position = card("POSITION")
        slider(position, "Left ↔ right", 0..100, { it.datePositionX }, { "$it%" }) { value ->
            change { it.copy(datePositionX = value) }
        }
        slider(position, "Top ↔ bottom", 0..100, { it.datePositionY }, { "$it%" }) { value ->
            change { it.copy(datePositionY = value) }
        }
        page.addView(position, full(top = 12))

        val depth = card("DEPTH")
        toggle(depth, "Show date over foreground", { it.dateOverForeground }) { checked ->
            change { it.copy(dateOverForeground = checked) }
        }
        note(depth, "Off draws behind the cutout subject.")
        page.addView(depth, full(top = 12))
        return page
    }

    private fun buildActionBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Gold.SURFACE)
            setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(12))
        }
        bar.addView(View(activity).apply { setBackgroundColor(Gold.LINE) }, LinearLayout.LayoutParams(-1, 1))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val saveButton = activity.actionButton(activity.getString(R.string.save_settings), primary = true) { listener.onSave() }
        val applyButton = activity.actionButton(activity.getString(R.string.apply_wallpaper), primary = false) { listener.onApplyWallpaper() }
        interactive.add(saveButton)
        interactive.add(applyButton)
        row.addView(saveButton, LinearLayout.LayoutParams(0, activity.dp(50), 1f))
        row.addView(applyButton, LinearLayout.LayoutParams(0, activity.dp(50), 1f).apply { marginStart = activity.dp(10) })
        bar.addView(row, full(top = 12))
        bar.addView(
            activity.uiText("Save applies at once. The chooser installs it on the home screen.", 10f, Gold.MUTED),
            full(top = 8),
        )
        return bar
    }

    // ---------------------------------------------------------------- controls

    /** A card section: quiet uppercase title, no numbering, muted border. */
    private fun card(title: String): LinearLayout {
        val view = activity.card()
        view.addView(activity.uiText(title, 9.5f, Gold.MUTED, WallpepType.bodyMedium(activity), 0.18f))
        return view
    }

    /** A one-line note, only where an action genuinely needs explaining. */
    private fun note(parent: LinearLayout, text: String) {
        parent.addView(activity.uiText(text, 10.5f, Gold.MUTED), full(top = 10))
    }

    private fun fieldLabel(parent: LinearLayout, label: String) {
        parent.addView(
            activity.uiText(label, 11f, Gold.MUTED, WallpepType.bodyMedium(activity), 0.07f),
            full(top = 16),
        )
    }

    /** A row of mutually exclusive options; the selected one is the only gold fill. */
    private fun segmented(
        parent: LinearLayout,
        label: String,
        options: List<String>,
        selectedIndex: (WallpaperSettings) -> Int,
        onSelect: (Int) -> Unit,
    ) {
        fieldLabel(parent, label)
        val track = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
            background = activity.shape(Gold.FIELD, 13f, Gold.LINE)
        }
        val views = options.mapIndexed { index, option ->
            TextView(activity).apply {
                text = option
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = WallpepType.bodyMedium(activity)
                isClickable = true
                isFocusable = true
                contentDescription = "$label: $option"
                setOnClickListener { if (listener.canInteract()) onSelect(index) }
            }
        }
        views.forEachIndexed { index, view ->
            track.addView(view, LinearLayout.LayoutParams(0, activity.dp(38), 1f).apply {
                if (index > 0) marginStart = activity.dp(4)
            })
            interactive.add(view)
        }
        binders.add { settings ->
            val selected = selectedIndex(settings)
            views.forEachIndexed { index, view ->
                val on = index == selected
                view.background = if (on) activity.shape(Gold.GOLD, 10f) else activity.shape(0x00000000, 10f)
                view.setTextColor(if (on) Gold.ON_GOLD else Gold.TEXT)
                view.isSelected = on
            }
        }
        parent.addView(track, full(top = 10))
    }

    private class SliderRow(private val bar: SeekBar, private val value: TextView, private val format: (Int) -> String) {
        fun render(valueInRange: Int) {
            bar.progress = valueInRange
            value.text = format(valueInRange)
        }
    }

    private fun slider(
        parent: LinearLayout,
        label: String,
        range: IntRange,
        valueOf: (WallpaperSettings) -> Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ) {
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            activity.uiText(label, 11f, Gold.MUTED, WallpepType.bodyMedium(activity), 0.07f),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val value = activity.uiText("", 11f, Gold.GOLD, WallpepType.bodyMedium(activity))
        header.addView(value)
        parent.addView(header, full(top = 16))
        val bar = activity.goldSeekBar(range.last - range.first, label).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !syncing && listener.canInteract()) onChange(range.first + progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        interactive.add(bar)
        parent.addView(bar, LinearLayout.LayoutParams(-1, activity.dp(36)))
        val row = SliderRow(bar, value, format)
        binders.add { settings -> row.render(valueOf(settings).coerceIn(range.first, range.last) - range.first) }
    }

    private fun toggle(
        parent: LinearLayout,
        label: String,
        valueOf: (WallpaperSettings) -> Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val control = activity.goldSwitch(label)
        control.setOnCheckedChangeListener { _, checked ->
            if (!syncing && listener.canInteract()) onChange(checked)
        }
        interactive.add(control)
        parent.addView(control, full(top = 8))
        binders.add { settings -> control.isChecked = valueOf(settings) }
    }

    /** Swatch row; [onChange] receives null when the date is told to follow the clock colour. */
    private fun colourRow(
        parent: LinearLayout,
        allowFollowClock: Boolean,
        valueOf: (WallpaperSettings) -> Int?,
        onChange: (Int?) -> Unit,
    ) {
        fieldLabel(parent, "Colour")
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = mutableListOf<TextView>()
        var index = 0
        if (allowFollowClock) {
            val auto = TextView(activity).apply {
                text = "Auto"
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = WallpepType.bodyMedium(activity)
                contentDescription = "Date colour follows the clock"
                isClickable = true
                isFocusable = true
                setOnClickListener { if (listener.canInteract()) onChange(null) }
            }
            interactive.add(auto)
            chips.add(auto)
            row.addView(auto, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
            index++
        }
        TextColors.all.forEach { color ->
            val swatch = TextView(activity).apply {
                text = "Aa"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (Color.luminance(color) > 0.45f) Color.BLACK else Color.WHITE)
                contentDescription = "${COLOR_NAMES[TextColors.all.indexOf(color)]} text colour"
                isClickable = true
                isFocusable = true
                setOnClickListener { if (listener.canInteract()) onChange(color) }
            }
            interactive.add(swatch)
            chips.add(swatch)
            row.addView(swatch, LinearLayout.LayoutParams(0, activity.dp(44), 1f).apply {
                if (index > 0) marginStart = activity.dp(7)
            })
            index++
        }
        binders.add { settings ->
            val selected = valueOf(settings)
            var cursor = 0
            if (allowFollowClock) {
                styleSwatch(chips[cursor], fill = Gold.FIELD, selected = selected == null, label = Gold.MUTED)
                cursor++
            }
            TextColors.all.forEach { color ->
                styleSwatch(chips[cursor], fill = color, selected = selected == color, label = null)
                cursor++
            }
        }
        parent.addView(row, full(top = 10))
    }

    private fun styleSwatch(chip: TextView, fill: Int, selected: Boolean, label: Int?) {
        chip.background = activity.shape(fill, 11f, if (selected) Gold.GOLD else Gold.LINE, if (selected) 2 else 1)
        chip.isSelected = selected
        if (label != null) chip.setTextColor(if (selected) Gold.GOLD else label)
    }

    /** One face card: the holder, its sample and name in the family's own face, and its id. */
    private class FontCard(val holder: FrameLayout, val sample: TextView, val name: TextView, val id: String)

    /**
     * The face cards themselves, each drawn in its own family, plus a full-library entry.
     * Selecting a card is the only gold state; unselected cards keep a muted border.
     */
    private fun fontCards(
        parent: LinearLayout,
        entries: List<String>,
        selectedOf: (WallpaperSettings) -> String,
        onPick: (String) -> Unit,
    ) {
        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        val cards = mutableListOf<FontCard>()
        entries.forEach { id ->
            val card = fontCard(id) { if (listener.canInteract()) onPick(id) }
            cards.add(card)
            interactive.add(card.holder)
            strip.addView(card.holder, LinearLayout.LayoutParams(activity.dp(96), activity.dp(92)).apply {
                marginEnd = activity.dp(10)
            })
        }
        binders.add { settings ->
            val selected = selectedOf(settings)
            cards.forEach { card ->
                val on = card.id == selected
                card.holder.background = activity.shape(if (on) Gold.SURFACE_RAISED else Gold.FIELD, 14f, if (on) Gold.GOLD else Gold.LINE, if (on) 2 else 1)
                card.holder.isSelected = on
                card.sample.setTextColor(if (on) Gold.GOLD else Gold.TEXT)
                card.name.setTextColor(if (on) Gold.GOLD else Gold.MUTED)
                if (card.id == FontCatalog.FOLLOW_CLOCK) {
                    val face = FaceCache.get(activity, settings.font)
                    card.sample.typeface = face
                    card.name.typeface = face
                }
            }
        }
        parent.addView(scroll, full(top = 12))
    }

    private fun fontCard(id: String, onClick: () -> Unit): FontCard {
        val holder = FrameLayout(activity).apply {
            background = activity.shape(Gold.FIELD, 14f, Gold.LINE)
            isClickable = true
            isFocusable = true
            contentDescription = "${FontCatalog.displayName(id)} typeface"
            setOnClickListener { onClick() }
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val face = FaceCache.get(activity, id)
        val sample = activity.uiText("Aa", 22f, Gold.TEXT, face).apply { gravity = Gravity.CENTER }
        val name = activity.uiText(FontCatalog.displayName(id), 10f, Gold.MUTED, face).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        column.addView(sample)
        column.addView(name, full(top = 4))
        holder.addView(column, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = activity.dp(6)
            rightMargin = activity.dp(6)
        })
        return FontCard(holder, sample, name, id)
    }

    /** Entry to the full 112-family picker, showing which face is currently in use. */
    private fun browseRow(parent: LinearLayout, selectedOf: (WallpaperSettings) -> String, onOpen: (String) -> Unit) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), activity.dp(11), activity.dp(14), activity.dp(11))
            background = activity.shape(Gold.FIELD, 13f, Gold.LINE)
            isClickable = true
            isFocusable = true
            contentDescription = "Browse all ${FontCatalog.families.size} bundled typefaces"
            setOnClickListener { if (listener.canInteract()) onOpen(selectedOf(listener.currentSettings())) }
        }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(activity.uiText("All ${FontCatalog.families.size} families", 13f, Gold.TEXT, WallpepType.bodyMedium(activity)))
        val current = activity.uiText("", 10f, Gold.MUTED)
        column.addView(current, full(top = 2))
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(activity.uiText("›", 20f, Gold.MUTED), LinearLayout.LayoutParams(-2, -2))
        interactive.add(row)
        parent.addView(row, full(top = 10))
        binders.add { settings ->
            val id = selectedOf(settings)
            val label = FontCatalog.displayName(id)
            current.text = if (id == FontCatalog.FOLLOW_CLOCK) "Same as clock · $label" else label
        }
    }

    private fun spacingLabel(hundredths: Int): String = String.format(java.util.Locale.ROOT, "%.2f em", hundredths / 100f)

    private fun change(next: (WallpaperSettings) -> WallpaperSettings) {
        if (!listener.canInteract()) return
        listener.onSettingsChanged(next(listener.currentSettings()))
    }

    private fun selectTab(next: Tab) {
        if (::clockPage.isInitialized) clockPage.visibility = if (next == Tab.CLOCK) View.VISIBLE else View.GONE
        if (::datePage.isInitialized) datePage.visibility = if (next == Tab.DATE) View.VISIBLE else View.GONE
        // Each tab starts at its first card rather than inheriting the other tab's scroll offset.
        if (::pageScroll.isInitialized) pageScroll.scrollTo(0, 0)
        styleTab(clockTab, next == Tab.CLOCK)
        styleTab(dateTab, next == Tab.DATE)
    }

    private fun styleTab(view: TextView, selected: Boolean) {
        view.background = if (selected) activity.shape(Gold.GOLD, 11f) else activity.shape(0x00000000, 11f)
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
        binders.forEach { binder -> binder(settings) }
        photoStatus.text = photoLabel
        val clean = settings == saved
        statusChip.text = if (clean) "SAVED" else "UNSAVED"
        statusChip.setTextColor(if (clean) Gold.MUTED else Gold.GOLD)
        statusChip.background = activity.shape(0x00000000, 100f, if (clean) Gold.LINE else Gold.GOLD_BORDER_STRONG)
        interactive.forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.45f
        }
        val canRemove = enabled && settings.backgroundName != null
        removeButton.isEnabled = canRemove
        removeButton.alpha = if (canRemove) 1f else 0.45f
        syncing = false
    }

    private fun full(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = activity.dp(top)
        bottomMargin = activity.dp(bottom)
    }

    private companion object {
        val COLOR_NAMES = listOf("White", "Mist", "Sand", "Rose", "Ink")

        /** A handful of bundled families shown as face cards before the full-library entry. */
        val CLOCK_FACE_CARDS = listOf("outfit", "cinzel", "playfairdisplay", "anton", "bebasneue", "dancingscript", "spacemono")

        val DATE_FACE_CARDS = listOf(FontCatalog.FOLLOW_CLOCK, "outfit", "playfairdisplay", "dancingscript", "spacemono")
    }
}
