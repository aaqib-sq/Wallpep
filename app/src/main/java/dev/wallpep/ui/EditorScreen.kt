package dev.wallpep.ui

import android.app.AlertDialog
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.wallpep.R
import dev.wallpep.fonts.FontCatalog
import dev.wallpep.model.ClockFormats
import dev.wallpep.model.DateFormats
import dev.wallpep.model.EffectStyle
import dev.wallpep.model.ShadowSettings
import dev.wallpep.model.StrokeSettings
import dev.wallpep.model.StrokeStyle
import dev.wallpep.model.TextAlignment
import dev.wallpep.model.TextColors
import dev.wallpep.model.TextLayerSettings
import dev.wallpep.model.TransformSettings
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.render.WallpaperScene
import java.util.Locale

/**
 * The Obsidian Pulse editor: the exact seven tabs from the granted structure file
 * (Clock, Date, Text, Transform, Colorize, Parallax, Effects), every control unlocked and every
 * control writing its value into the persisted settings document.
 *
 * Clock, date, the text layer and the depth sandwich all render in the live preview, along
 * with transform warps, colorize, drag-simulated parallax and the particle effects; every
 * persisted setting in those areas shows its effect immediately. The Collection tab reuses the
 * same scene, so no separate "preview engine" ships.
 */
class EditorScreen(private val activity: ComponentActivity, private val listener: Listener) {

    interface Listener {
        fun currentSettings(): WallpaperSettings
        fun currentName(): String
        fun isFavorited(): Boolean
        fun canInteract(): Boolean
        fun onSettingsChanged(next: WallpaperSettings)
        fun onPickPhoto()
        fun onRemovePhoto()
        fun onRename(name: String)
        fun onToggleFavorite()
        fun onApply()
        fun onBack()
    }

    private enum class Tab(val label: String) {
        CLOCK("Clock"),
        DATE("Date"),
        TEXT("Text"),
        TRANSFORM("Transform"),
        COLORIZE("Colorize"),
        PARALLAX("Parallax"),
        EFFECTS("Effects"),
    }

    private enum class ParallaxTab(val label: String) { LAYERS("Clock, Date & Text"), WALLPAPER("Wallpaper") }

    private enum class TransformLayer(val label: String) { CLOCK("Clock"), DATE("Date"), TEXT("Text") }

    val scene = WallpaperScene(activity, miniature = true)
    val root: FrameLayout

    // Controls built once live in the static sinks; the Transform and Parallax hosts rebuild
    // their contents when their layer/sub-tab changes, so they use sinks that are cleared first.
    private val staticInteractive = mutableListOf<View>()
    private val staticBinders = mutableListOf<(WallpaperSettings) -> Unit>()
    private val dynamicInteractive = mutableListOf<View>()
    private val dynamicBinders = mutableListOf<(WallpaperSettings) -> Unit>()
    private var interactive: MutableList<View> = staticInteractive
    private var binders: MutableList<(WallpaperSettings) -> Unit> = staticBinders

    private lateinit var nameLabel: TextView
    private lateinit var favoriteButton: TextView
    private lateinit var statusChip: TextView
    private lateinit var photoStatus: TextView
    private lateinit var notice: TextView
    private lateinit var pageScroll: ScrollView
    private lateinit var pages: Map<Tab, LinearLayout>
    private lateinit var tabButtons: Map<Tab, TextView>
    private lateinit var removePhoto: Button

    private lateinit var clockPage: LinearLayout
    private lateinit var datePage: LinearLayout
    private lateinit var textPage: LinearLayout
    private lateinit var transformPage: LinearLayout
    private lateinit var colorizePage: LinearLayout
    private lateinit var parallaxPage: LinearLayout
    private lateinit var effectsPage: LinearLayout
    private lateinit var transformLayerRow: ChipRow
    private lateinit var parallaxTabRow: ChipRow
    private lateinit var transformHost: LinearLayout
    private lateinit var parallaxHost: LinearLayout

    private var selectedTab = Tab.CLOCK
    private var selectedTransformLayer = TransformLayer.CLOCK
    private var selectedParallaxTab = ParallaxTab.LAYERS
    private enum class SubTab(val label: String) {
        FORMAT("Format"),
        CONTENT("Content"),
        TYPOGRAPHY("Typography"),
        COLORS("Colors"),
        POSITION("Position"),
        EFFECTS("Effects"),
        TRANSFORM("Transform"),
    }
    private val formatSubTabs = listOf(SubTab.FORMAT, SubTab.TYPOGRAPHY, SubTab.COLORS, SubTab.POSITION, SubTab.EFFECTS, SubTab.TRANSFORM)
    private val textSubTabs = listOf(SubTab.CONTENT, SubTab.TYPOGRAPHY, SubTab.COLORS, SubTab.POSITION, SubTab.EFFECTS, SubTab.TRANSFORM)
    private var selectedClockSubTab = SubTab.FORMAT
    private var selectedDateSubTab = SubTab.FORMAT
    private var selectedTextSubTab = SubTab.CONTENT
    private lateinit var clockSubTabBar: SubTabBar
    private lateinit var dateSubTabBar: SubTabBar
    private lateinit var textSubTabBar: SubTabBar
    private lateinit var clockSections: Map<SubTab, View>
    private lateinit var dateSections: Map<SubTab, View>
    private lateinit var textSections: Map<SubTab, View>
    private inner class SubTabBar(val view: View, private val buttons: Map<SubTab, TextView>) {
        fun render(selected: SubTab) {
            buttons.forEach { (sub, button) ->
                button.background = if (sub == selected) activity.shape(Pulse.ACCENT, 11f) else activity.shape(0x00000000, 11f)
                button.setTextColor(if (sub == selected) Pulse.ON_ACCENT else Pulse.MUTED)
            }
        }
    }
    private fun buildSubTabBar(subTabs: List<SubTab>, selected: SubTab, onSelect: (SubTab) -> Unit): SubTabBar {
        val scroll = HorizontalScrollView(activity)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(16, 8, 16, 4)
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val buttons = mutableMapOf<SubTab, TextView>()
        subTabs.forEach { sub ->
            val button = activity.uiText(sub.label, 12.5f, Pulse.MUTED, WallpepType.display(activity), 0.04f).apply {
                setPadding(20, 10, 20, 10)
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 8, 0)
            button.layoutParams = params
            button.setOnClickListener { onSelect(sub) }
            row.addView(button)
            buttons[sub] = button
        }
        scroll.addView(row)
        val bar = SubTabBar(scroll, buttons)
        bar.render(selected)
        return bar
    }
    private fun renderClockSubTab() {
        clockSubTabBar.render(selectedClockSubTab)
        clockSections.forEach { (sub, section) -> section.visibility = if (sub == selectedClockSubTab) View.VISIBLE else View.GONE }
    }
    private fun renderDateSubTab() {
        dateSubTabBar.render(selectedDateSubTab)
        dateSections.forEach { (sub, section) -> section.visibility = if (sub == selectedDateSubTab) View.VISIBLE else View.GONE }
    }
    private fun renderTextSubTab() {
        textSubTabBar.render(selectedTextSubTab)
        textSections.forEach { (sub, section) -> section.visibility = if (sub == selectedTextSubTab) View.VISIBLE else View.GONE }
    }
    private lateinit var previewFrame: FrameLayout
    private lateinit var fullscreenOverlay: FrameLayout
    private var livePreview = true

    init {
        root = FrameLayout(activity)
        root.addView(buildScreen(), FrameLayout.LayoutParams(-1, -1))
        selectTab(Tab.CLOCK)
    }

    // ---------------------------------------------------------------- layout

    private fun buildScreen(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pulse.BACKGROUND)
        }
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        column.addView(buildHeader())

        val stage = FrameLayout(activity).apply {
            setBackgroundColor(Pulse.BACKGROUND)
            setPadding(activity.dp(20), activity.dp(4), activity.dp(20), activity.dp(6))
        }
        stage.addView(buildPreview(), FrameLayout.LayoutParams(previewWidthPx(), previewHeightPx(), Gravity.CENTER_HORIZONTAL))
        val expand = activity.uiText("⤢", 15f, Pulse.TEXT).apply {
            gravity = Gravity.CENTER
            background = activity.shape(0xCC121318.toInt(), 10f, Pulse.LINE_STRONG)
            isClickable = true
            isFocusable = true
            contentDescription = "Expand preview"
            setOnClickListener { showFullscreen() }
        }
        stage.addView(expand, FrameLayout.LayoutParams(activity.dp(34), activity.dp(34), Gravity.END or Gravity.CENTER_VERTICAL))
        column.addView(stage)

        column.addView(buildPhotoRow())
        column.addView(buildTabBar())

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(12), activity.dp(18), activity.dp(28))
        }
        notice = activity.uiText("", 11.5f, Pulse.TEXT).apply {
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            background = activity.shape(Pulse.ACCENT_WASH, 12f, Pulse.ACCENT_BORDER)
            visibility = View.GONE
        }
        page.addView(notice, activity.full(bottom = 12))

        clockPage = buildClockTab()
        datePage = buildDateTab()
        textPage = buildTextTab()
        transformPage = buildTransformTab()
        colorizePage = buildColorizeTab()
        parallaxPage = buildParallaxTab()
        effectsPage = buildEffectsTab()
        pages = mapOf(
            Tab.CLOCK to clockPage,
            Tab.DATE to datePage,
            Tab.TEXT to textPage,
            Tab.TRANSFORM to transformPage,
            Tab.COLORIZE to colorizePage,
            Tab.PARALLAX to parallaxPage,
            Tab.EFFECTS to effectsPage,
        )
        pages.values.forEach { page.addView(it) }

        pageScroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Pulse.BACKGROUND)
            addView(page)
        }
        column.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return column
    }

    private fun buildHeader(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(8))
        }
        val back = activity.backButton { listener.onBack() }
        interactive.add(back)
        row.addView(back)

        val titles = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(10), 0, activity.dp(8), 0)
        }
        nameLabel = activity.uiText("Untitled wallpaper", 14f, Pulse.TEXT, WallpepType.display(activity)).apply {
            maxLines = 1
            isClickable = true
            isFocusable = true
            contentDescription = "Rename this wallpaper"
            setOnClickListener { askRename() }
        }
        statusChip = activity.statusPill("SAVED")
        titles.addView(nameLabel)
        titles.addView(statusChip, activity.full(top = 3))
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        favoriteButton = activity.uiText("♡", 20f, Pulse.MUTED).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Favourite this wallpaper"
            setOnClickListener { listener.onToggleFavorite() }
            setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(6))
        }
        interactive.add(favoriteButton)
        row.addView(favoriteButton)

        val apply = activity.actionButton(activity.getString(R.string.apply_wallpaper), primary = true) {
            listener.onApply()
        }.apply {
            textSize = 13f
            minHeight = activity.dp(40)
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
        }
        interactive.add(apply)
        row.addView(apply, LinearLayout.LayoutParams(-2, activity.dp(40)).apply { marginStart = activity.dp(8) })
        return row
    }

    /** The phone frame from the structure file: the real scene, date above a large clock. */
    private fun buildPreview(): View {
        val frame = FrameLayout(activity).apply {
            background = activity.shape(Pulse.SURFACE_RAISED, 24f, Pulse.LINE_STRONG, 2)
            clipToOutline = true
        }
        previewFrame = frame
        frame.addView(scene, FrameLayout.LayoutParams(-1, -1))
        armParallaxDrag(frame)
        photoStatus = activity.uiText("", 9.5f, Pulse.MUTED).apply {
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(activity.dp(8), activity.dp(5), activity.dp(8), activity.dp(5))
            background = activity.shape(0xB3000000.toInt(), 9f)
        }
        frame.addView(
            photoStatus,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                marginStart = activity.dp(8)
                bottomMargin = activity.dp(8)
                marginEnd = activity.dp(8)
            },
        )
        return frame
    }

    private fun previewHeightPx(): Int {
        val metrics = activity.resources.displayMetrics
        return (metrics.heightPixels * 0.36f).toInt().coerceIn(activity.dp(240), activity.dp(360))
    }

    private fun previewWidthPx(): Int = (previewHeightPx() * 9f / 19.5f).toInt()

    private fun buildPhotoRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(18), 0, activity.dp(18), activity.dp(4))
        }
        val pick = activity.actionButton(activity.getString(R.string.choose_photo), primary = false) {
            listener.onPickPhoto()
        }.apply {
            textSize = 12.5f
            minHeight = activity.dp(38)
        }
        removePhoto = activity.actionButton(activity.getString(R.string.remove_photo), primary = false) {
            listener.onRemovePhoto()
        }.apply {
            textSize = 12.5f
            minHeight = activity.dp(38)
        }
        interactive.add(pick)
        interactive.add(removePhoto)
        row.addView(pick, LinearLayout.LayoutParams(0, activity.dp(38), 1f))
        row.addView(removePhoto, LinearLayout.LayoutParams(0, activity.dp(38), 1f).apply { marginStart = activity.dp(8) })
        return row
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(18), activity.dp(8), activity.dp(12), activity.dp(2))
        }
        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        val buttons = mutableMapOf<Tab, TextView>()
        Tab.entries.forEach { tab ->
            val button = activity.uiText(tab.label, 12.5f, Pulse.MUTED, WallpepType.display(activity), 0.04f).apply {
                gravity = Gravity.CENTER
                setPadding(activity.dp(14), activity.dp(9), activity.dp(14), activity.dp(9))
                isClickable = true
                isFocusable = true
                contentDescription = "${tab.label} tab"
                setOnClickListener { selectTab(tab) }
            }
            buttons[tab] = button
            interactive.add(button)
            strip.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = activity.dp(6) })
        }
        tabButtons = buttons
        bar.addView(scroll, LinearLayout.LayoutParams(0, -2, 1f))

        val reset = activity.uiText("↺", 17f, Pulse.MUTED).apply {
            gravity = Gravity.CENTER
            background = activity.shape(Pulse.FIELD, 10f, Pulse.LINE)
            isClickable = true
            isFocusable = true
            contentDescription = "Reset every control on this wallpaper"
            setOnClickListener { askReset() }
            setPadding(activity.dp(9), activity.dp(7), activity.dp(9), activity.dp(7))
        }
        interactive.add(reset)
        bar.addView(reset, LinearLayout.LayoutParams(-2, -2).apply { marginStart = activity.dp(4) })
        return bar
    }

    // ---------------------------------------------------------------- tabs

    private fun buildClockTab(): LinearLayout {
        val page = page()

        val format = section("Format")
        switch(format, "Show Clock", read = { it.showClock }, write = { s, on -> s.copy(showClock = on) })
        format.addView(activity.uiText("Time Format", 12f, Pulse.MUTED), activity.full(top = 12))
        chips(
            format,
            ClockFormats.all.map { it.label },
            columns = 3,
            read = { s -> ClockFormats.all.indexOfFirst { it.id == s.clockFormat }.coerceAtLeast(0) },
            write = { s, index -> s.copy(clockFormat = ClockFormats.all[index].id) },
        )
        clockSections = emptyMap()
    clockSubTabBar = buildSubTabBar(formatSubTabs, selectedClockSubTab) { sub ->
        selectedClockSubTab = sub
        renderClockSubTab()
    }
    page.addView(clockSubTabBar.view)
    page.addView(format)

        val typography = section("Typography")
        faceCards(typography, CLOCK_FACES, read = { it.font }, write = { s, id -> s.copy(font = id) })
        browseRow(typography, allowFollowClock = false, read = { it.font }, write = { s, id -> s.copy(font = id) })
        slider(
            typography, "Font size", WallpaperSettings.MIN_CLOCK_SIZE, WallpaperSettings.MAX_CLOCK_SIZE,
            format = { "$it sp" },
            read = { it.clockSizeSp },
            write = { s, value -> s.copy(clockSizeSp = value) },
        )
        slider(
            typography, "Letter spacing", 0, WallpaperSettings.MAX_LETTER_SPACING,
            format = ::spacing,
            read = { it.clockLetterSpacing },
            write = { s, value -> s.copy(clockLetterSpacing = value) },
        )
        alignmentRows(typography, read = { it.clockAlign }, write = { s, value -> s.copy(clockAlign = value) })
        slider(
            typography, "Vertical spacing", WallpaperSettings.MIN_VERTICAL_SPACING, WallpaperSettings.MAX_VERTICAL_SPACING,
            format = { "$it sp" },
            read = { it.clockVerticalSpacing },
            write = { s, value -> s.copy(clockVerticalSpacing = value) },
        )
        page.addView(typography)

        val colors = section("Colors")
        colorPicker(colors, "Color", read = { it.textColor }, write = { s, c -> s.copy(textColor = c ?: TextColors.WHITE) })
        colorPicker(colors, "Secondary color", read = { it.clockSecondaryColor }, write = { s, c -> s.copy(clockSecondaryColor = c ?: TextColors.MIST) })
        colorPicker(colors, "Seconds color", read = { it.secondsColor }, write = { s, c -> s.copy(secondsColor = c ?: TextColors.MIST) })
        switch(colors, "Gradient", read = { it.clockGradientEnabled }, write = { s, on -> s.copy(clockGradientEnabled = on) })
        slider(
            colors, "Gradient angle", 0, 360, format = { "$it°" },
            read = { it.clockGradientAngle }, write = { s, value -> s.copy(clockGradientAngle = value) },
        )
        slider(
            colors, "Opacity", WallpaperSettings.MIN_OPACITY, 100, format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { it.clockOpacity }, write = { s, value -> s.copy(clockOpacity = value) },
        )
        page.addView(colors)

        val position = section("Position")
        slider(
            position, "Position X", 0, 100, format = { "$it%" },
            read = { it.positionX }, write = { s, value -> s.copy(positionX = value) },
        )
        slider(
            position, "Position Y", 0, 100, format = { "$it%" },
            read = { it.positionY }, write = { s, value -> s.copy(positionY = value) },
        )
        switch(
            position, "Show Over Foreground",
            note = "OFF keeps the whole clock behind the cutout subject; ON draws it in front.",
            read = { it.clockOverForeground },
            write = { s, on -> s.copy(hourOverForeground = on, minuteOverForeground = on) },
        )
        switch(position, "Show Over Foreground (Hour)", read = { it.hourOverForeground }, write = { s, on -> s.copy(hourOverForeground = on) })
        switch(position, "Show Over Foreground (Minute)", read = { it.minuteOverForeground }, write = { s, on -> s.copy(minuteOverForeground = on) })
        page.addView(position)

        val effects = section("Effects")
        strokeSection(effects, read = { it.clockStroke }, write = { s, stroke -> s.copy(clockStroke = stroke) })
        shadowSection(effects, read = { it.clockShadow }, write = { s, shadow -> s.copy(clockShadow = shadow) })
        page.addView(effects)

        val transform = section("Transform")
        transformSection(transform, read = { it.clockTransform }, write = { s, value -> s.copy(clockTransform = value) })
        page.addView(transform)
    clockSections = mapOf(
        SubTab.FORMAT to format,
        SubTab.TYPOGRAPHY to typography,
        SubTab.COLORS to colors,
        SubTab.POSITION to position,
        SubTab.EFFECTS to effects,
        SubTab.TRANSFORM to transform,
    )
    renderClockSubTab()
        return page
    }

    private fun buildDateTab(): LinearLayout {
        val page = page()

        val format = section("Format")
        switch(format, "Show Date", read = { it.showDate }, write = { s, on -> s.copy(showDate = on) })
        format.addView(activity.uiText("Date Format", 12f, Pulse.MUTED), activity.full(top = 12))
        chips(
            format,
            DateFormats.all.map { it.label },
            columns = 2,
            read = { s -> DateFormats.all.indexOfFirst { it.id == s.dateFormat }.coerceAtLeast(0) },
            write = { s, index -> s.copy(dateFormat = DateFormats.all[index].id) },
        )
        switch(format, "All Uppercase", read = { it.dateUppercase }, write = { s, on -> s.copy(dateUppercase = on) })
        dateSections = emptyMap()
    dateSubTabBar = buildSubTabBar(formatSubTabs, selectedDateSubTab) { sub ->
        selectedDateSubTab = sub
        renderDateSubTab()
    }
    page.addView(dateSubTabBar.view)
    page.addView(format)

        val typography = section("Typography")
        faceCards(typography, DATE_FACES, read = { it.dateFont }, write = { s, id -> s.copy(dateFont = id) })
        browseRow(typography, allowFollowClock = true, read = { it.dateFont }, write = { s, id -> s.copy(dateFont = id) })
        slider(
            typography, "Font size", WallpaperSettings.MIN_DATE_SIZE, WallpaperSettings.MAX_DATE_SIZE,
            format = { "$it sp" },
            read = { it.dateSizeSp },
            write = { s, value -> s.copy(dateSizeSp = value) },
        )
        slider(
            typography, "Letter spacing", 0, WallpaperSettings.MAX_LETTER_SPACING,
            format = ::spacing,
            read = { it.dateLetterSpacing },
            write = { s, value -> s.copy(dateLetterSpacing = value) },
        )
        alignmentRows(typography, read = { it.dateAlign }, write = { s, value -> s.copy(dateAlign = value) })
        page.addView(typography)

        val colors = section("Colors")
        colorPicker(
            colors, "Color", allowAuto = true,
            read = { it.dateColor },
            write = { s, c -> s.copy(dateColor = c) },
        )
        colorPicker(colors, "Secondary color", read = { it.dateSecondaryColor }, write = { s, c -> s.copy(dateSecondaryColor = c ?: TextColors.MIST) })
        switch(colors, "Gradient", read = { it.dateGradientEnabled }, write = { s, on -> s.copy(dateGradientEnabled = on) })
        slider(
            colors, "Gradient angle", 0, 360, format = { "$it°" },
            read = { it.dateGradientAngle }, write = { s, value -> s.copy(dateGradientAngle = value) },
        )
        slider(
            colors, "Opacity", WallpaperSettings.MIN_OPACITY, 100, format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { it.dateOpacity }, write = { s, value -> s.copy(dateOpacity = value) },
        )
        page.addView(colors)

        val position = section("Position")
        slider(
            position, "Horizontal Position", 0, 100, format = { "$it%" },
            read = { it.datePositionX }, write = { s, value -> s.copy(datePositionX = value) },
        )
        slider(
            position, "Vertical Position", 0, 100, format = { "$it%" },
            read = { it.datePositionY }, write = { s, value -> s.copy(datePositionY = value) },
        )
        switch(
            position, "Show Over Foreground",
            note = "OFF keeps the date behind the cutout subject, sandwiched in the depth stack.",
            read = { it.dateOverForeground }, write = { s, on -> s.copy(dateOverForeground = on) },
        )
        page.addView(position)

        val effects = section("Effects")
        strokeSection(effects, read = { it.dateStroke }, write = { s, stroke -> s.copy(dateStroke = stroke) })
        shadowSection(effects, read = { it.dateShadow }, write = { s, shadow -> s.copy(dateShadow = shadow) })
        page.addView(effects)

        val transform = section("Transform")
        transformSection(transform, read = { it.dateTransform }, write = { s, value -> s.copy(dateTransform = value) })
        page.addView(transform)
    dateSections = mapOf(
        SubTab.FORMAT to format,
        SubTab.TYPOGRAPHY to typography,
        SubTab.COLORS to colors,
        SubTab.POSITION to position,
        SubTab.EFFECTS to effects,
        SubTab.TRANSFORM to transform,
    )
    renderDateSubTab()
        return page
    }

    private fun buildTextTab(): LinearLayout {
        val page = page()

        val content = section("Content")
        switch(content, "Layer Enabled", read = { it.text.enabled }, write = { s, on -> s.copy(text = s.text.copy(enabled = on)) })
        content.addView(activity.uiText("Content", 12f, Pulse.MUTED), activity.full(top = 12))
        val field = activity.textField("Your text", onChange = { value ->
            act { s -> s.copy(text = s.text.copy(content = value)) }
        })
        interactive.add(field)
        content.addView(field, activity.full(top = 6))
        binders.add { settings ->
            val stored = settings.text.content
            if (!field.hasFocus() && field.text.toString() != stored) field.setText(stored)
        }
        textSections = emptyMap()
    textSubTabBar = buildSubTabBar(textSubTabs, selectedTextSubTab) { sub ->
        selectedTextSubTab = sub
        renderTextSubTab()
    }
    page.addView(textSubTabBar.view)
    page.addView(content)

        val typography = section("Typography")
        faceCards(typography, TEXT_FACES, read = { it.text.font }, write = { s, id -> s.copy(text = s.text.copy(font = id)) })
        browseRow(typography, allowFollowClock = false, read = { it.text.font }, write = { s, id -> s.copy(text = s.text.copy(font = id)) })
        slider(
            typography, "Font size", TextLayerSettings.MIN_SIZE, TextLayerSettings.MAX_SIZE,
            format = { "$it sp" },
            read = { it.text.sizeSp },
            write = { s, value -> s.copy(text = s.text.copy(sizeSp = value)) },
        )
        slider(
            typography, "Letter spacing", 0, WallpaperSettings.MAX_LETTER_SPACING, format = ::spacing,
            read = { it.text.letterSpacing }, write = { s, value -> s.copy(text = s.text.copy(letterSpacing = value)) },
        )
        alignmentRows(typography, read = { it.text.align }, write = { s, value -> s.copy(text = s.text.copy(align = value)) })
        page.addView(typography)

        val colors = section("Colors")
        switch(colors, "Enable Gradient", read = { it.text.gradientEnabled }, write = { s, on -> s.copy(text = s.text.copy(gradientEnabled = on)) })
        slider(
            colors, "Gradient angle", 0, 360, format = { "$it°" },
            read = { it.text.gradientAngle }, write = { s, value -> s.copy(text = s.text.copy(gradientAngle = value)) },
        )
        colorPicker(
            colors, "Primary color", read = { it.text.color },
            write = { s, c -> s.copy(text = s.text.copy(color = c ?: TextColors.WHITE)) },
        )
        colorPicker(
            colors, "Secondary color", read = { it.text.secondaryColor },
            write = { s, c -> s.copy(text = s.text.copy(secondaryColor = c ?: TextColors.MIST)) },
        )
        slider(
            colors, "Opacity", WallpaperSettings.MIN_OPACITY, 100, format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { it.text.opacity }, write = { s, value -> s.copy(text = s.text.copy(opacity = value)) },
        )
        page.addView(colors)

        val position = section("Position")
        slider(
            position, "Horizontal Position", 0, 100, format = { "$it%" },
            read = { it.text.positionX }, write = { s, value -> s.copy(text = s.text.copy(positionX = value)) },
        )
        slider(
            position, "Vertical Position", 0, 100, format = { "$it%" },
            read = { it.text.positionY }, write = { s, value -> s.copy(text = s.text.copy(positionY = value)) },
        )
        switch(
            position, "Show Over Foreground", read = { it.text.overForeground },
            write = { s, on -> s.copy(text = s.text.copy(overForeground = on)) },
        )
        page.addView(position)

        val effects = section("Effects")
        strokeSection(effects, read = { it.text.stroke }, write = { s, stroke -> s.copy(text = s.text.copy(stroke = stroke)) })
        shadowSection(effects, read = { it.text.shadow }, write = { s, shadow -> s.copy(text = s.text.copy(shadow = shadow)) })
        page.addView(effects)

        val transform = section("Transform")
        transformSection(transform, read = { it.text.transform }, write = { s, value -> s.copy(text = s.text.copy(transform = value)) })
        page.addView(transform)
    textSections = mapOf(
        SubTab.CONTENT to content,
        SubTab.TYPOGRAPHY to typography,
        SubTab.COLORS to colors,
        SubTab.POSITION to position,
        SubTab.EFFECTS to effects,
        SubTab.TRANSFORM to transform,
    )
    renderTextSubTab()
        return page
    }

    /**
     * The structure file notes the top-level Transform tab's own layout was not confirmed, while
     * the transform fields themselves are. It therefore edits one layer's confirmed field set.
     */
    private fun buildTransformTab(): LinearLayout {
        val page = page()
        val picker = section("Layer", "Transform applies to one layer at a time.")
        transformLayerRow = activity.chipRow(TransformLayer.entries.map { it.label }, columns = 3) { index ->
            selectedTransformLayer = TransformLayer.entries[index]
            useDynamicSinks { renderTransformLayer() }
        }
        picker.addView(transformLayerRow.root)
        page.addView(picker)

        transformHost = activity.card()
        page.addView(transformHost, activity.full(top = 14))
        useDynamicSinks { renderTransformLayer() }
        return page
    }

    private fun renderTransformLayer() {
        transformLayerRow.render(selectedTransformLayer.ordinal)
        transformHost.removeAllViews()
        transformHost.addView(
            activity.uiText(selectedTransformLayer.label.uppercase(), 11f, Pulse.MUTED, WallpepType.display(activity), 0.18f),
        )
        val read: (WallpaperSettings) -> TransformSettings = { settings ->
            when (selectedTransformLayer) {
                TransformLayer.CLOCK -> settings.clockTransform
                TransformLayer.DATE -> settings.dateTransform
                TransformLayer.TEXT -> settings.text.transform
            }
        }
        val write: (WallpaperSettings, TransformSettings) -> WallpaperSettings = { settings, value ->
            when (selectedTransformLayer) {
                TransformLayer.CLOCK -> settings.copy(clockTransform = value)
                TransformLayer.DATE -> settings.copy(dateTransform = value)
                TransformLayer.TEXT -> settings.copy(text = settings.text.copy(transform = value))
            }
        }
        transformSection(transformHost, read = read, write = write)
        renderDynamic()
    }

    private fun buildColorizeTab(): LinearLayout {
        val page = page()
        val adjustment = section("Adjustment", "Applied to the wallpaper image, never to the clock or date.")
        slider(
            adjustment, "Hue Rotation", 0, 360, format = { "$it°" },
            read = { it.hueRotation }, write = { s, value -> s.copy(hueRotation = value) },
        )
        slider(
            adjustment, "Hue", 0, 360, format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { it.hue }, write = { s, value -> s.copy(hue = value) },
        )
        slider(
            adjustment, "Saturation", 0, 200, format = { "$it%" },
            read = { it.saturation }, write = { s, value -> s.copy(saturation = value) },
        )
        slider(
            adjustment, "Grayscale", 0, 100, format = { String.format(Locale.ROOT, "%.2f", it / 100f) },
            read = { it.grayscale }, write = { s, value -> s.copy(grayscale = value) },
        )
        slider(
            adjustment, "Brightness", 0, 200, format = { "$it%" },
            read = { it.brightness }, write = { s, value -> s.copy(brightness = value) },
        )
        page.addView(adjustment)
        return page
    }

    private fun buildParallaxTab(): LinearLayout {
        val page = page()
        val picker = section("Parallax", "Choose what tilts with the device.")
        parallaxTabRow = activity.chipRow(ParallaxTab.entries.map { it.label }, columns = 2) { index ->
            selectedParallaxTab = ParallaxTab.entries[index]
            useDynamicSinks { renderParallaxTab() }
        }
        picker.addView(parallaxTabRow.root)
        page.addView(picker)

        parallaxHost = activity.card()
        page.addView(parallaxHost, activity.full(top = 14))
        useDynamicSinks { renderParallaxTab() }
        return page
    }

    private fun renderParallaxTab() {
        parallaxTabRow.render(selectedParallaxTab.ordinal)
        parallaxHost.removeAllViews()
        when (selectedParallaxTab) {
            ParallaxTab.LAYERS -> {
                parallaxHost.addView(activity.uiText("CLOCK, DATE & TEXT", 11f, Pulse.MUTED, WallpepType.display(activity), 0.18f))
                addSwitchTo(parallaxHost, "Enable 3D Parallax", read = { it.parallaxLayersEnabled }, write = { s, on -> s.copy(parallaxLayersEnabled = on) })
                addSliderTo(
                    parallaxHost, "Parallax strength", 0, 100, format = { "$it%" },
                    read = { it.parallaxLayersStrength }, write = { s, value -> s.copy(parallaxLayersStrength = value) },
                )
            }
            ParallaxTab.WALLPAPER -> {
                parallaxHost.addView(activity.uiText("WALLPAPER", 11f, Pulse.MUTED, WallpepType.display(activity), 0.18f))
                addSwitchTo(parallaxHost, "Wallpaper parallax", read = { it.parallaxWallpaperEnabled }, write = { s, on -> s.copy(parallaxWallpaperEnabled = on) })
                addSliderTo(
                    parallaxHost, "Wallpaper parallax strength", 0, 100, format = { "$it%" },
                    read = { it.parallaxWallpaperStrength }, write = { s, value -> s.copy(parallaxWallpaperStrength = value) },
                )
            }
        }
        renderDynamic()
    }

    private fun buildEffectsTab(): LinearLayout {
        val page = page()
        val type = section("Type")
        type.addView(activity.uiText("Effect Style", 12f, Pulse.MUTED), activity.full(top = 6))
        chips(
            type,
            EffectStyle.entries.map { it.label },
            columns = 4,
            read = { s -> EffectStyle.entries.indexOf(s.effectStyle).coerceAtLeast(0) },
            write = { s, index -> s.copy(effectStyle = EffectStyle.entries[index]) },
        )
        page.addView(type)

        val properties = section("Properties")
        slider(
            properties, "Speed", WallpaperSettings.MIN_EFFECT_SPEED, WallpaperSettings.MAX_EFFECT_SPEED,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { it.effectSpeed }, write = { s, value -> s.copy(effectSpeed = value) },
        )
        slider(
            properties, "Angle / Direction", 0, 360, format = { "$it°" },
            read = { it.effectAngle }, write = { s, value -> s.copy(effectAngle = value) },
        )
        slider(
            properties, "Particles", WallpaperSettings.MIN_EFFECT_PARTICLES, WallpaperSettings.MAX_EFFECT_PARTICLES,
            format = { "$it" },
            read = { it.effectParticles }, write = { s, value -> s.copy(effectParticles = value) },
        )
        slider(
            properties, "Size", WallpaperSettings.MIN_EFFECT_SIZE, WallpaperSettings.MAX_EFFECT_SIZE,
            format = { "$it" },
            read = { it.effectSize }, write = { s, value -> s.copy(effectSize = value) },
        )
        page.addView(properties)

        val appearance = section("Appearance")
        colorPicker(
            appearance, "Color", read = { it.effectColor },
            write = { s, c -> s.copy(effectColor = c ?: TextColors.WHITE) },
        )
        slider(
            appearance, "Opacity", 0, 100, format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { it.effectOpacity }, write = { s, value -> s.copy(effectOpacity = value) },
        )
        page.addView(appearance)
        return page
    }

    // ---------------------------------------------------------------- shared sections

    private fun strokeSection(
        parent: LinearLayout,
        read: (WallpaperSettings) -> StrokeSettings,
        write: (WallpaperSettings, StrokeSettings) -> WallpaperSettings,
    ) {
        val header = activity.uiText("Stroke", 12.5f, Pulse.TEXT, WallpepType.bodyMedium(activity))
        parent.addView(header, activity.full(top = 12))
        addSwitchTo(
            parent, "Enable Stroke",
            read = { read(it).enabled },
            write = { s, on -> write(s, read(s).copy(enabled = on)) },
        )
        colorPicker(
            parent, "Color", read = { read(it).color },
            write = { s, c -> write(s, read(s).copy(color = c ?: TextColors.WHITE)) },
        )
        colorPicker(
            parent, "Secondary color", read = { read(it).secondaryColor },
            write = { s, c -> write(s, read(s).copy(secondaryColor = c ?: TextColors.MIST)) },
        )
        colorPicker(
            parent, "Seconds color", read = { read(it).secondsColor },
            write = { s, c -> write(s, read(s).copy(secondsColor = c ?: TextColors.MIST)) },
        )
        addSliderTo(
            parent, "Width", StrokeSettings.MIN_WIDTH, StrokeSettings.MAX_WIDTH,
            format = { String.format(Locale.ROOT, "%.1f", it / 10f) },
            read = { read(it).width },
            write = { s, value -> write(s, read(s).copy(width = value)) },
        )
        parent.addView(activity.uiText("Style", 12f, Pulse.MUTED), activity.full(top = 10))
        chips(
            parent,
            StrokeStyle.entries.map { it.label },
            columns = 3,
            read = { s -> StrokeStyle.entries.indexOf(read(s).style).coerceAtLeast(0) },
            write = { s, index -> write(s, read(s).copy(style = StrokeStyle.entries[index])) },
        )
        addSwitchTo(
            parent, "Stroke gradient",
            read = { read(it).gradientEnabled },
            write = { s, on -> write(s, read(s).copy(gradientEnabled = on)) },
        )
        addSliderTo(
            parent, "Gradient angle", 0, 360, format = { "$it°" },
            read = { read(it).gradientAngle },
            write = { s, value -> write(s, read(s).copy(gradientAngle = value)) },
        )
    }

    private fun shadowSection(
        parent: LinearLayout,
        read: (WallpaperSettings) -> ShadowSettings,
        write: (WallpaperSettings, ShadowSettings) -> WallpaperSettings,
    ) {
        parent.addView(activity.uiText("Shadow", 12.5f, Pulse.TEXT, WallpepType.bodyMedium(activity)), activity.full(top = 14))
        addSwitchTo(
            parent, "Enable Shadow",
            read = { read(it).enabled },
            write = { s, on -> write(s, read(s).copy(enabled = on)) },
        )
        colorPicker(
            parent, "Color", read = { read(it).color },
            write = { s, c -> write(s, read(s).copy(color = c ?: TextColors.BLACK)) },
        )
        addSliderTo(
            parent, "Blur", ShadowSettings.MIN_BLUR, ShadowSettings.MAX_BLUR, format = { "$it" },
            read = { read(it).blur }, write = { s, value -> write(s, read(s).copy(blur = value)) },
        )
        addSliderTo(
            parent, "Offset X", ShadowSettings.MIN_OFFSET, ShadowSettings.MAX_OFFSET, format = { "$it" },
            read = { read(it).offsetX }, write = { s, value -> write(s, read(s).copy(offsetX = value)) },
        )
        addSliderTo(
            parent, "Offset Y", ShadowSettings.MIN_OFFSET, ShadowSettings.MAX_OFFSET, format = { "$it" },
            read = { read(it).offsetY }, write = { s, value -> write(s, read(s).copy(offsetY = value)) },
        )
        addSliderTo(
            parent, "Opacity", 0, 100, format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).opacity }, write = { s, value -> write(s, read(s).copy(opacity = value)) },
        )
        addSwitchTo(
            parent, "Flip H", read = { read(it).flipH },
            write = { s, on -> write(s, read(s).copy(flipH = on)) },
        )
        addSwitchTo(
            parent, "Flip V", read = { read(it).flipV },
            write = { s, on -> write(s, read(s).copy(flipV = on)) },
        )
    }

    private fun transformSection(
        parent: LinearLayout,
        read: (WallpaperSettings) -> TransformSettings,
        write: (WallpaperSettings, TransformSettings) -> WallpaperSettings,
    ) {
        parent.addView(activity.uiText("Perspective", 12.5f, Pulse.TEXT, WallpepType.bodyMedium(activity)), activity.full(top = 12))
        addSliderTo(
            parent, "X-Axis Rotation", TransformSettings.MIN_ANGLE, TransformSettings.MAX_ANGLE,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).perspectiveX }, write = { s, value -> write(s, read(s).copy(perspectiveX = value)) },
        )
        addSliderTo(
            parent, "Y-Axis Rotation", TransformSettings.MIN_ANGLE, TransformSettings.MAX_ANGLE,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).perspectiveY }, write = { s, value -> write(s, read(s).copy(perspectiveY = value)) },
        )

        parent.addView(activity.uiText("Skew", 12.5f, Pulse.TEXT, WallpepType.bodyMedium(activity)), activity.full(top = 12))
        addSliderTo(
            parent, "Left-right skew", TransformSettings.MIN_ANGLE, TransformSettings.MAX_ANGLE,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).skewLeftRight }, write = { s, value -> write(s, read(s).copy(skewLeftRight = value)) },
        )
        addSliderTo(
            parent, "Horizontal skew", TransformSettings.MIN_ANGLE, TransformSettings.MAX_ANGLE,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).skewHorizontal }, write = { s, value -> write(s, read(s).copy(skewHorizontal = value)) },
        )
        addSliderTo(
            parent, "Vertical skew", TransformSettings.MIN_ANGLE, TransformSettings.MAX_ANGLE,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).skewVertical }, write = { s, value -> write(s, read(s).copy(skewVertical = value)) },
        )
        addSliderTo(
            parent, "Left vertical skew", TransformSettings.MIN_ANGLE, TransformSettings.MAX_ANGLE,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).skewLeftVertical }, write = { s, value -> write(s, read(s).copy(skewLeftVertical = value)) },
        )
        addSliderTo(
            parent, "Bottom horizontal skew", TransformSettings.MIN_ANGLE, TransformSettings.MAX_ANGLE,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).skewBottomHorizontal }, write = { s, value -> write(s, read(s).copy(skewBottomHorizontal = value)) },
        )
        addSliderTo(
            parent, "Top-Bottom skew", TransformSettings.MIN_ANGLE, TransformSettings.MAX_ANGLE,
            format = { String.format(Locale.ROOT, "%.1f", it.toFloat()) },
            read = { read(it).skewTopBottom }, write = { s, value -> write(s, read(s).copy(skewTopBottom = value)) },
        )

        addSliderTo(
            parent, "Rotation Angle", 0, 359, format = { "$it°" },
            read = { read(it).rotation }, write = { s, value -> write(s, read(s).copy(rotation = value)) },
        )
        val step = activity.actionButton("Rotate 90°", primary = false) {
            act { s -> write(s, read(s).copy(rotation = (read(s).rotation + 90) % 360)) }
        }.apply {
            textSize = 12.5f
            minHeight = activity.dp(38)
        }
        interactive.add(step)
        parent.addView(step, activity.full(top = 8))

        addSliderTo(
            parent, "Horizontal Stretch", TransformSettings.MIN_STRETCH, TransformSettings.MAX_STRETCH,
            format = { String.format(Locale.ROOT, "%.1f", it / 100f) },
            read = { read(it).stretch }, write = { s, value -> write(s, read(s).copy(stretch = value)) },
        )
    }

    // ---------------------------------------------------------------- control plumbing

    /** Rebuilds a host's controls with fresh sinks, so switching layers cannot stack binders. */
    private fun useDynamicSinks(rebuild: () -> Unit) {
        dynamicBinders.clear()
        dynamicInteractive.clear()
        interactive = dynamicInteractive
        binders = dynamicBinders
        try {
            rebuild()
        } finally {
            interactive = staticInteractive
            binders = staticBinders
        }
    }

    private fun renderDynamic() {
        val settings = listener.currentSettings()
        dynamicBinders.forEach { binder -> binder(settings) }
    }

    private fun page(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    private fun section(title: String, subtitle: String? = null): LinearLayout {
        val card = activity.sectionCard(title, subtitle)
        // The page adds the card itself; margins come from the same helper used everywhere.
        card.layoutParams = activity.full(top = 12)
        return card
    }

    private fun act(write: (WallpaperSettings) -> WallpaperSettings) {
        if (!listener.canInteract()) return
        listener.onSettingsChanged(write(listener.currentSettings()).sanitized())
    }

    private fun switch(
        parent: LinearLayout,
        label: String,
        note: String? = null,
        read: (WallpaperSettings) -> Boolean,
        write: (WallpaperSettings, Boolean) -> WallpaperSettings,
    ) {
        val row = activity.switchRow(label, note) { on -> act { s -> write(s, on) } }
        addRow(parent, row.root)
        interactive.add(row.root)
        binders.add { settings -> row.render(read(settings), listener.canInteract()) }
    }

    private fun addSwitchTo(
        parent: LinearLayout,
        label: String,
        read: (WallpaperSettings) -> Boolean,
        write: (WallpaperSettings, Boolean) -> WallpaperSettings,
    ) = switch(parent, label, null, read, write)

    private fun slider(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        format: (Int) -> String,
        read: (WallpaperSettings) -> Int,
        write: (WallpaperSettings, Int) -> WallpaperSettings,
    ) {
        val row = activity.sliderRow(label, min, max, format) { value -> act { s -> write(s, value) } }
        addRow(parent, row.root)
        interactive.add(row.root)
        binders.add { settings -> row.render(read(settings), listener.canInteract()) }
    }

    private fun addSliderTo(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        format: (Int) -> String,
        read: (WallpaperSettings) -> Int,
        write: (WallpaperSettings, Int) -> WallpaperSettings,
    ) = slider(parent, label, min, max, format, read, write)

    private fun chips(
        parent: LinearLayout,
        options: List<String>,
        columns: Int,
        read: (WallpaperSettings) -> Int,
        write: (WallpaperSettings, Int) -> WallpaperSettings,
    ) {
        val row = activity.chipRow(options, columns) { index -> act { s -> write(s, index) } }
        parent.addView(row.root, activity.full(top = 2))
        interactive.add(row.root)
        binders.add { settings -> row.render(read(settings), listener.canInteract()) }
    }

    private fun colorPicker(
        parent: LinearLayout,
        label: String,
        allowAuto: Boolean = false,
        read: (WallpaperSettings) -> Int?,
        write: (WallpaperSettings, Int?) -> WallpaperSettings,
    ) {
        parent.addView(activity.uiText(label, 12f, Pulse.MUTED), activity.full(top = 10))
        val row = activity.swatchRow(allowAuto) { color -> act { s -> write(s, color) } }
        parent.addView(row.root)
        val offset = if (allowAuto) 1 else 0
        binders.add { settings ->
            val color = read(settings)
            val index = color?.let { TextColors.all.indexOf(it) }?.takeIf { it >= 0 }?.plus(offset)
            if (index != null) row.render(index) else row.render(if (allowAuto) 0 else null)
        }
    }

    private fun alignmentRows(
        parent: LinearLayout,
        read: (WallpaperSettings) -> TextAlignment,
        write: (WallpaperSettings, TextAlignment) -> WallpaperSettings,
    ) {
        parent.addView(activity.uiText("Text alignment", 12f, Pulse.MUTED), activity.full(top = 10))
        chips(
            parent,
            TextAlignment.entries.map { it.name.lowercase(Locale.ROOT).replaceFirstChar { c -> c.uppercase() } },
            columns = 3,
            read = { s -> TextAlignment.entries.indexOf(read(s)).coerceAtLeast(0) },
            write = { s, index -> write(s, TextAlignment.entries[index]) },
        )
    }

    private fun addRow(parent: LinearLayout, view: View) {
        parent.addView(view, activity.full(top = 8))
    }

    private fun spacing(hundredths: Int): String = String.format(Locale.ROOT, "%.2f em", hundredths / 100f)

    /** One face card: the holder, its sample and name in the family's own face, and its id. */
    private class FontCard(val holder: FrameLayout, val sample: TextView, val name: TextView, val id: String)

    private fun faceCards(
        parent: LinearLayout,
        entries: List<String>,
        read: (WallpaperSettings) -> String,
        write: (WallpaperSettings, String) -> WallpaperSettings,
    ) {
        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        val cards = mutableListOf<FontCard>()
        entries.forEach { id ->
            val card = fontCard(id)
            cards.add(card)
            interactive.add(card.holder)
            card.holder.setOnClickListener { act { s -> write(s, id) } }
            strip.addView(card.holder, LinearLayout.LayoutParams(activity.dp(92), activity.dp(88)).apply {
                marginEnd = activity.dp(8)
            })
        }
        parent.addView(scroll, activity.full(top = 12))
        binders.add { settings ->
            val selected = read(settings)
            cards.forEach { card ->
                val on = card.id == selected
                card.holder.background = activity.shape(
                    if (on) Pulse.SURFACE_RAISED else Pulse.FIELD,
                    14f,
                    if (on) Pulse.ACCENT else Pulse.LINE,
                    if (on) 2 else 1,
                )
                card.holder.isSelected = on
                card.sample.setTextColor(if (on) Pulse.ACCENT_LIGHT else Pulse.TEXT)
                card.name.setTextColor(if (on) Pulse.ACCENT_LIGHT else Pulse.MUTED)
            }
        }
    }

    private fun fontCard(id: String): FontCard {
        val holder = FrameLayout(activity).apply {
            background = activity.shape(Pulse.FIELD, 14f, Pulse.LINE)
            isClickable = true
            isFocusable = true
            contentDescription = "${FontCatalog.displayName(id)} typeface"
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val face = FaceCache.get(activity, id)
        val sample = activity.uiText("Aa", 21f, Pulse.TEXT, face).apply { gravity = Gravity.CENTER }
        val name = activity.uiText(FontCatalog.displayName(id), 9.5f, Pulse.MUTED, face).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        column.addView(sample)
        column.addView(name, activity.full(top = 3))
        holder.addView(column, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = activity.dp(5)
            rightMargin = activity.dp(5)
        })
        return FontCard(holder, sample, name, id)
    }

    /** Entry to the full bundled picker, showing which face is currently in use. */
    private fun browseRow(
        parent: LinearLayout,
        allowFollowClock: Boolean,
        read: (WallpaperSettings) -> String,
        write: (WallpaperSettings, String) -> WallpaperSettings,
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), activity.dp(11), activity.dp(14), activity.dp(11))
            background = activity.shape(Pulse.FIELD, 13f, Pulse.LINE)
            isClickable = true
            isFocusable = true
            contentDescription = "Browse all ${FontCatalog.families.size} bundled typefaces"
            setOnClickListener {
                val current = read(listener.currentSettings())
                FontPickerDialog.show(activity, allowFollowClock = allowFollowClock, selectedId = current) { id ->
                    act { s -> write(s, id) }
                }
            }
        }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(activity.uiText("All ${FontCatalog.families.size} families", 13f, Pulse.TEXT, WallpepType.bodyMedium(activity)))
        val current = activity.uiText("", 10f, Pulse.MUTED)
        column.addView(current, activity.full(top = 2))
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(activity.uiText("›", 20f, Pulse.MUTED), LinearLayout.LayoutParams(-2, -2))
        interactive.add(row)
        parent.addView(row, activity.full(top = 10))
        binders.add { settings ->
            val id = read(settings)
            val label = FontCatalog.displayName(id)
            current.text = if (id == FontCatalog.FOLLOW_CLOCK) "Same as clock · $label" else label
        }
    }

    // ---------------------------------------------------------------- preview and state

    fun setPreviewImages(background: android.graphics.Bitmap?, foreground: android.graphics.Bitmap?) {
        scene.setImages(background, foreground)
    }

    fun render(settings: WallpaperSettings, name: String, favorited: Boolean, photoLabel: String, saving: Boolean) {
        scene.settings = settings
        staticBinders.forEach { binder -> binder(settings) }
        dynamicBinders.forEach { binder -> binder(settings) }
        nameLabel.text = name
        photoStatus.text = photoLabel
        favoriteButton.text = if (favorited) "♥" else "♡"
        favoriteButton.setTextColor(if (favorited) Pulse.ACCENT else Pulse.MUTED)
        favoriteButton.contentDescription = if (favorited) "Remove from favourites" else "Add to favourites"
        statusChip.text = if (saving) "SAVING" else "SAVED"
        statusChip.setTextColor(if (saving) Pulse.ACCENT_LIGHT else Pulse.MUTED)
        val canRemove = listener.canInteract() && settings.backgroundName != null
        removePhoto.isEnabled = canRemove
        removePhoto.alpha = if (canRemove) 1f else 0.45f
        staticInteractive.forEach { view -> view.isEnabled = listener.canInteract() }
        dynamicInteractive.forEach { view -> view.isEnabled = listener.canInteract() }
    }

    fun showNotice(message: String) {
        notice.text = message
        notice.visibility = View.VISIBLE
    }

    /**
     * The full-screen "Preview" switch is real: OFF freezes the scene so the wallpaper can be
     * judged as a still, ON resumes the minute-driven redraw.
     */
    fun invalidatePreview() {
        if (livePreview) scene.invalidate()
    }

    private fun selectTab(next: Tab) {
        selectedTab = next
        pages.forEach { (tab, page) -> page.visibility = if (tab == next) View.VISIBLE else View.GONE }
        tabButtons.forEach { (tab, button) ->
            val on = tab == next
            button.background = if (on) activity.shape(Pulse.ACCENT, 11f) else activity.shape(0x00000000, 11f)
            button.setTextColor(if (on) Pulse.ON_ACCENT else Pulse.MUTED)
            button.isSelected = on
        }
        pageScroll.scrollTo(0, 0)
    }

    fun showTab(tabName: String) {
        Tab.entries.firstOrNull { it.label.equals(tabName, ignoreCase = true) }?.let(::selectTab)
    }

    /**
     * Drag-to-tilt: a pointer drag maps to the simulated tilt the scene applies to the wallpaper
     * and the individual layers (the same channel the live wallpaper drives from the gyroscope),
     * so the saved parallax strengths become visible without leaving the editor.
     */
    private fun armParallaxDrag(frame: View) {
        var downX = 0f
        var downY = 0f
        frame.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val halfWidth = (view.width / 2f).coerceAtLeast(1f)
                    val halfHeight = (view.height / 2f).coerceAtLeast(1f)
                    scene.setTilt(
                        ((event.x - downX) / halfWidth).coerceIn(-1f, 1f),
                        ((event.y - downY) / halfHeight).coerceIn(-1f, 1f),
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scene.releaseTilt()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * The full-screen preview moves the one live scene instead of decoding the photo twice, so
     * there is a single owner of the bitmaps and nothing can draw a recycled image.
     */
    private fun showFullscreen() {
        val overlay = if (::fullscreenOverlay.isInitialized) fullscreenOverlay else buildFullscreen()
        if (scene.parent === previewFrame) previewFrame.removeView(scene)
        if (scene.parent == null) overlay.addView(scene, FrameLayout.LayoutParams(-1, -1))
        previewFrame.visibility = View.INVISIBLE
        scene.settings = listener.currentSettings()
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
    }

    private fun hideFullscreen() {
        if (!::fullscreenOverlay.isInitialized) return
        fullscreenOverlay.visibility = View.GONE
        if (scene.parent === fullscreenOverlay) fullscreenOverlay.removeView(scene)
        if (scene.parent == null) previewFrame.addView(scene, FrameLayout.LayoutParams(-1, -1))
        previewFrame.visibility = View.VISIBLE
        scene.settings = listener.currentSettings()
    }

    private fun buildFullscreen(): FrameLayout {
        val overlay = FrameLayout(activity).apply { setBackgroundColor(Pulse.BACKGROUND) }
        fullscreenOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(20))
            background = activity.shape(0xE60D0E13.toInt(), 20f, Pulse.LINE)
        }
        val preview = activity.accentSwitch("Preview").apply {
            isChecked = livePreview
            setOnCheckedChangeListener { _, checked ->
                livePreview = checked
                if (checked) scene.invalidate()
            }
        }
        bar.addView(preview)
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val close = activity.actionButton("Close", primary = false) { hideFullscreen() }
        val set = activity.actionButton(activity.getString(R.string.set_wallpaper), primary = true) {
            hideFullscreen()
            listener.onApply()
        }
        buttons.addView(close, LinearLayout.LayoutParams(0, activity.dp(46), 1f))
        buttons.addView(set, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { marginStart = activity.dp(10) })
        bar.addView(buttons, activity.full(top = 10))
        overlay.addView(
            bar,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = activity.dp(14)
                rightMargin = activity.dp(14)
                bottomMargin = activity.dp(18)
            },
        )
        val closeTop = activity.uiText("✕", 16f, Pulse.TEXT).apply {
            gravity = Gravity.CENTER
            background = activity.shape(0xCC121318.toInt(), 100f, Pulse.LINE_STRONG)
            isClickable = true
            isFocusable = true
            contentDescription = "Close full-screen preview"
            setOnClickListener { hideFullscreen() }
        }
        overlay.addView(
            closeTop,
            FrameLayout.LayoutParams(activity.dp(38), activity.dp(38), Gravity.TOP or Gravity.END).apply {
                topMargin = activity.dp(48)
                marginEnd = activity.dp(16)
            },
        )
        armParallaxDrag(overlay)
        overlay.visibility = View.GONE
        return overlay
    }

    private fun askRename() {
        val input = activity.textField("Wallpaper name", onChange = {})
        input.setText(listener.currentName())
        input.setSelection(input.text.length)
        AlertDialog.Builder(activity)
            .setTitle("Rename wallpaper")
            .setView(activity.dialogInput(input))
            .setPositiveButton("Rename") { _, _ -> listener.onRename(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askReset() {
        AlertDialog.Builder(activity)
            .setTitle("Reset this wallpaper?")
            .setMessage("Every clock, date, text, transform, colorize, parallax and effect control returns to its default. Your photo is kept.")
            .setPositiveButton("Reset") { _, _ ->
                val photo = listener.currentSettings().backgroundName
                listener.onSettingsChanged(WallpaperSettings(backgroundName = photo))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private companion object {
        /** A handful of bundled families shown as face cards before the full-library entry. */
        val CLOCK_FACES = listOf("spacegrotesk", "outfit", "cinzel", "playfairdisplay", "bebasneue", "dancingscript", "spacemono")
        val DATE_FACES = listOf(FontCatalog.FOLLOW_CLOCK, "plusjakartasans", "spacegrotesk", "playfairdisplay", "dancingscript")
        val TEXT_FACES = listOf("plusjakartasans", "spacegrotesk", "outfit", "anton", "dancingscript")
    }
}
