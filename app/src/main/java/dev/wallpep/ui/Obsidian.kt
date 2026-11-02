package dev.wallpep.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.wallpep.model.TextColors
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * Obsidian Pulse: the app's own near-black OLED surfaces with one electric-blue accent.
 * Blue appears only on primary actions, active tabs, selected states and switches that are ON.
 * There is no gold, and red/green are reserved for genuine error/success states.
 */
object Pulse {
    val BACKGROUND = 0xFF0D0E13.toInt()
    val SURFACE = 0xFF121318.toInt()
    val SURFACE_RAISED = 0xFF1A1B20.toInt()
    val SETTINGS_SURFACE = 0xFF161820.toInt()
    val FIELD = 0xFF1A1B20.toInt()
    val ACCENT = 0xFF2563EB.toInt()
    val ACCENT_PRESSED = 0xFF1D4ED8.toInt()
    val ACCENT_LIGHT = 0xFF60A5FA.toInt()
    val ACCENT_BORDER = 0x8C2563EB.toInt()
    val ACCENT_WASH = 0x262563EB
    val LINE = 0xFF262832.toInt()
    val LINE_STRONG = 0xFF343744.toInt()
    val TEXT = 0xFFF3F5F9.toInt()
    val MUTED = 0xFF8C93A3.toInt()
    val ON_ACCENT = 0xFFFFFFFF.toInt()
    val TRACK = 0x33FFFFFF
    val DANGER = 0xFFEF4444.toInt()
    val SUCCESS = 0xFF22C55E.toInt()
}

/** Space Grotesk headlines and Plus Jakarta Sans body/labels: both already bundled. */
object WallpepType {
    private val display = WeakHashMap<Context, Typeface>()
    private val body = WeakHashMap<Context, Typeface>()
    private val bodyMedium = WeakHashMap<Context, Typeface>()

    private fun load(context: Context, path: String, fallback: String): Typeface = try {
        Typeface.createFromAsset(context.assets, path)
    } catch (_: Exception) {
        Typeface.create(fallback, Typeface.NORMAL)
    }

    fun display(context: Context): Typeface = display.getOrPut(context.applicationContext) {
        load(context, "fonts/sans/spacegrotesk/SpaceGrotesk-Variable.ttf", "sans-serif-medium")
    }

    fun body(context: Context): Typeface = body.getOrPut(context.applicationContext) {
        load(context, "fonts/sans/plusjakartasans/PlusJakartaSans-Variable.ttf", "sans-serif")
    }

    fun bodyMedium(context: Context): Typeface = bodyMedium.getOrPut(context.applicationContext) {
        load(context, "fonts/sans/plusjakartasans/PlusJakartaSans-Variable.ttf", "sans-serif-medium")
    }
}

/**
 * A small LRU of bundled faces shared by the font cards and the full-library picker, so a
 * screen full of samples never holds more than a handful of loaded files.
 */
internal object FaceCache {
    private const val MAX_FACES = 24

    private val faces = object : LinkedHashMap<String, Typeface>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean = size > MAX_FACES
    }

    fun get(context: Context, id: String?): Typeface {
        val key = id ?: dev.wallpep.fonts.FontCatalog.DEFAULT_ID
        synchronized(faces) {
            faces[key]?.let { return it }
            val face = dev.wallpep.fonts.FontCatalog.typeface(context, id)
            faces[key] = face
            return face
        }
    }
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
internal fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** Rounded fill with an optional hairline border, used for every card, field and chip. */
internal fun Context.shape(fill: Int, radiusDp: Float, stroke: Int? = null, strokeWidthDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(strokeWidthDp).coerceAtLeast(1), stroke)
    }

internal fun Context.uiText(
    value: String,
    sizeSp: Float,
    color: Int,
    face: Typeface = WallpepType.body(this),
    tracking: Float = 0f,
): TextView = TextView(this).apply {
    text = value
    textSize = sizeSp
    setTextColor(color)
    typeface = face
    includeFontPadding = true
    if (tracking != 0f) letterSpacing = tracking
}

/** A surfaced card with a hairline border; every section on every screen uses this shape. */
internal fun Context.card(radiusDp: Float = 20f, fill: Int = Pulse.SURFACE, padding: Int = 16): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = shape(fill, radiusDp, Pulse.LINE)
    }

/**
 * A titled card. The returned column is where the caller adds its controls; the title sits in
 * a Space Grotesk label at the top, matching the editor's sub-section names.
 */
internal fun Context.sectionCard(title: String, subtitle: String? = null, fill: Int = Pulse.SURFACE): LinearLayout {
    val card = card(fill = fill)
    card.addView(
        uiText(title.uppercase(), 11f, Pulse.MUTED, WallpepType.display(this), 0.18f),
        LinearLayout.LayoutParams(-1, -2),
    )
    if (subtitle != null) {
        card.addView(uiText(subtitle, 11f, Pulse.MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) }, full(top = 4))
    }
    return card
}

internal fun Context.actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        stateListAnimator = null
        typeface = WallpepType.bodyMedium(context)
        setTextColor(if (primary) Pulse.ON_ACCENT else Pulse.TEXT)
        background = if (primary) shape(Pulse.ACCENT, 14f) else shape(0x00000000, 14f, Pulse.LINE_STRONG)
        minHeight = dp(48)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

internal fun Context.accentSeekBar(maximum: Int, description: String): SeekBar = SeekBar(this).apply {
    max = maximum
    contentDescription = description
    progressTintList = ColorStateList.valueOf(Pulse.ACCENT)
    thumbTintList = ColorStateList.valueOf(Pulse.ACCENT_LIGHT)
    progressBackgroundTintList = ColorStateList.valueOf(Pulse.TRACK)
}

/** A switch tinted with the accent only while checked. */
@Suppress("UseSwitchCompatOrMaterialCode")
internal fun Context.accentSwitch(label: String): Switch = Switch(this).apply {
    text = label
    textSize = 13f
    setTextColor(Pulse.TEXT)
    typeface = WallpepType.body(context)
    minHeight = dp(44)
    setPadding(0, dp(2), 0, dp(2))
    thumbTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Pulse.ON_ACCENT, Pulse.SURFACE_RAISED),
    )
    trackTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Pulse.ACCENT, Pulse.LINE_STRONG),
    )
}

internal fun Context.full(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
    topMargin = dp(top)
    bottomMargin = dp(bottom)
}

internal fun Context.wrap(top: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(-2, -2).apply {
    topMargin = dp(top)
    marginEnd = dp(end)
}

/** A one-line separator that also gives long card stacks a readable rhythm. */
internal fun Context.hairline(): View = View(this).apply {
    setBackgroundColor(Pulse.LINE)
    layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) }
}

/** The granted Wallpep mark, used in the top bar and as the launcher icon. */
internal fun Context.logoMark(sizeDp: Int): ImageView = ImageView(this).apply {
    setImageBitmap(
        try {
            assets.open("wallpep-mark.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        },
    )
    scaleType = ImageView.ScaleType.CENTER_CROP
    contentDescription = "Wallpep logo"
    background = shape(Pulse.SURFACE_RAISED, 12f, Pulse.LINE)
    clipToOutline = true
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** A small blue label pill, e.g. the saved/unsaved state of the current design. */
internal fun Context.statusPill(text: String): TextView = uiText(text, 10f, Pulse.MUTED, WallpepType.bodyMedium(this), 0.08f).apply {
    setPadding(dp(10), dp(5), dp(10), dp(5))
    background = shape(0x00000000, 100f, Pulse.LINE)
}

// ------------------------------------------------------------------------------- controls

/**
 * A labelled switch. The row owns the echo guard so [render] never re-fires [onChange].
 */
internal class SwitchRow(val root: View, private val toggle: Switch, private val value: TextView?) {
    private var echoing = false
    private val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
        if (!echoing) onChanged(checked)
    }
    private var onChanged: (Boolean) -> Unit = {}

    fun bind(onChange: (Boolean) -> Unit) {
        onChanged = onChange
        toggle.setOnCheckedChangeListener(listener)
    }

    fun render(checked: Boolean, enabled: Boolean = true) {
        echoing = true
        toggle.isChecked = checked
        toggle.isEnabled = enabled
        echoing = false
        value?.text = if (checked) "ON" else "OFF"
        value?.setTextColor(if (checked) Pulse.ACCENT_LIGHT else Pulse.MUTED)
        root.alpha = if (enabled) 1f else 0.45f
    }
}

internal fun Context.switchRow(
    label: String,
    note: String? = null,
    onChange: (Boolean) -> Unit,
): SwitchRow {
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val toggle = accentSwitch(label).apply { setTextColor(Pulse.TEXT) }
    val state = uiText(if (toggle.isChecked) "ON" else "OFF", 10f, Pulse.MUTED, WallpepType.bodyMedium(this), 0.1f)
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    head.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(state)
    column.addView(head, LinearLayout.LayoutParams(-1, -2))
    if (note != null) {
        column.addView(uiText(note, 10.5f, Pulse.MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) }, full(top = 2))
    }
    val row = SwitchRow(column, toggle, state)
    row.bind(onChange)
    return row
}

/** A labelled slider with a live value read-out on the right; the bar runs `min..max`. */
internal class SliderRow(
    val root: View,
    private val bar: SeekBar,
    private val value: TextView,
    private val format: (Int) -> String,
    private val min: Int,
) {
    private var echoing = false
    private var onChanged: (Int) -> Unit = {}

    fun bind(onChange: (Int) -> Unit) {
        onChanged = onChange
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val current = progress + min
                value.text = format(current)
                if (fromUser && !echoing) onChanged(current)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    /** Pushes a stored value into the bar without echoing it back as a user change. */
    fun render(current: Int, enabled: Boolean = true) {
        echoing = true
        bar.progress = (current - min).coerceIn(0, bar.max)
        echoing = false
        bar.isEnabled = enabled
        value.text = format(current)
        value.setTextColor(if (enabled) Pulse.TEXT else Pulse.MUTED)
        root.alpha = if (enabled) 1f else 0.45f
    }
}

internal fun Context.sliderRow(
    label: String,
    min: Int,
    max: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
): SliderRow {
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val value = uiText(format(min), 12f, Pulse.TEXT, WallpepType.bodyMedium(this))
    head.addView(uiText(label, 13f, Pulse.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(value)
    column.addView(head, LinearLayout.LayoutParams(-1, -2))
    val bar = accentSeekBar(max - min, label)
    column.addView(bar, full(top = 2))
    val row = SliderRow(column, bar, value, format, min)
    row.bind(onChange)
    row.render(min)
    return row
}

/** A wrapping grid of single-choice chips; the selected chip is the only accent-filled one. */
internal class ChipRow(val root: View, private val chips: List<TextView>) {
    private var onSelected: (Int) -> Unit = {}

    fun bind(onSelect: (Int) -> Unit) {
        onSelected = onSelect
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener { onSelected(index) }
        }
    }

    fun render(selected: Int, enabled: Boolean = true) {
        chips.forEachIndexed { index, chip ->
            val on = index == selected
            chip.background = if (on) {
                chip.context.shape(Pulse.ACCENT, 11f)
            } else {
                chip.context.shape(Pulse.FIELD, 11f, Pulse.LINE)
            }
            chip.setTextColor(if (on) Pulse.ON_ACCENT else Pulse.TEXT)
            chip.isSelected = on
            chip.isEnabled = enabled
            chip.alpha = if (enabled) 1f else 0.45f
        }
    }
}

internal fun Context.chipRow(options: List<String>, columns: Int, onSelect: (Int) -> Unit): ChipRow {
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val chips = mutableListOf<TextView>()
    var row: LinearLayout? = null
    options.forEachIndexed { index, label ->
        if (index % columns == 0) {
            row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            column.addView(row, full(top = if (index == 0) 8 else 6))
        }
        val chip = uiText(label, 11.5f, Pulse.TEXT, WallpepType.bodyMedium(this)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(9))
            background = shape(Pulse.FIELD, 11f, Pulse.LINE)
            isClickable = true
            isFocusable = true
            contentDescription = label
        }
        chips.add(chip)
        row?.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (index % columns != 0) marginStart = dp(6)
        })
    }
    val control = ChipRow(column, chips)
    control.bind(onSelect)
    return control
}

/** Colour swatches; [allowAuto] adds a leading "Auto" chip that means "follow the clock". */
internal class SwatchRow(val root: View, private val swatches: List<TextView>) {
    private var onSelected: (Int?) -> Unit = {}

    fun bind(onSelect: (Int?) -> Unit) {
        onSelected = onSelect
    }

    fun render(selectedIndex: Int?) {
        swatches.forEachIndexed { index, chip ->
            val on = selectedIndex != null && index == selectedIndex
            chip.background = chip.context.shape(
                chip.tag as Int,
                11f,
                if (on) Pulse.ACCENT else Pulse.LINE,
                if (on) 2 else 1,
            )
            chip.isSelected = on
        }
    }
}

internal fun Context.swatchRow(allowAuto: Boolean, onSelect: (Int?) -> Unit): SwatchRow {
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val chips = mutableListOf<TextView>()
    var row: LinearLayout? = null
    val entries = (if (allowAuto) listOf<Int?>(null) else emptyList()) + TextColors.all.map { it as Int? }
    entries.forEachIndexed { index, color ->
        if (index % 6 == 0) {
            row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            column.addView(row, full(top = 8))
        }
        val chip = TextView(this).apply {
            text = if (color == null) "Auto" else "Aa"
            textSize = if (color == null) 10f else 13f
            gravity = Gravity.CENTER
            typeface = WallpepType.bodyMedium(context)
            if (color == null) {
                tag = Pulse.FIELD
                setTextColor(Pulse.MUTED)
            } else {
                tag = color
                setTextColor(if (android.graphics.Color.luminance(color) > 0.45f) 0xFF0D0E13.toInt() else Pulse.TEXT)
            }
            setPadding(dp(4), dp(11), dp(4), dp(11))
            background = shape(tag as Int, 11f, Pulse.LINE)
            isClickable = true
            isFocusable = true
            contentDescription = if (color == null) "Follow the clock colour" else "${TextColors.label(color)} colour"
            setOnClickListener { onSelect(color) }
        }
        chips.add(chip)
        row?.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (index % 6 != 0) marginStart = dp(6)
        })
    }
    val control = SwatchRow(column, chips)
    control.bind(onSelect)
    return control
}

/** A single-line text field used by the Text tab and by design renames. */
internal fun Context.textField(hint: String, onChange: (String) -> Unit): EditText = EditText(this).apply {
    this.hint = hint
    setSingleLine(true)
    inputType = InputType.TYPE_CLASS_TEXT
    textSize = 14f
    typeface = WallpepType.body(context)
    setTextColor(Pulse.TEXT)
    setHintTextColor(Pulse.MUTED)
    background = shape(Pulse.FIELD, 12f, Pulse.LINE)
    setPadding(dp(14), dp(10), dp(14), dp(10))
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString().orEmpty())
    })
}

/** A tappable row with a label, an optional value and a chevron; used by Settings help rows. */
internal class LinkRow(val root: LinearLayout, val value: TextView)

internal fun Context.linkRow(label: String, value: String, onClick: () -> Unit): LinkRow {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = shape(Pulse.FIELD, 13f, Pulse.LINE)
        isClickable = true
        isFocusable = true
        contentDescription = "$label, $value"
        setOnClickListener { onClick() }
    }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    column.addView(uiText(label, 13f, Pulse.TEXT, WallpepType.bodyMedium(context)))
    val detail = uiText(value, 11f, Pulse.MUTED)
    detail.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    column.addView(detail, full(top = 2))
    row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(uiText("›", 20f, Pulse.MUTED), LinearLayout.LayoutParams(-2, -2))
    return LinkRow(row, detail)
}

/** An icon-free back affordance: a compact chevron button with a spoken label. */
internal fun Context.backButton(onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
    background = shape(Pulse.FIELD, 12f, Pulse.LINE)
    isClickable = true
    isFocusable = true
    contentDescription = "Back"
    setOnClickListener { onClick() }
    addView(uiText("‹", 24f, Pulse.TEXT, WallpepType.display(context)).apply {
        gravity = Gravity.CENTER
    }, FrameLayout.LayoutParams(-1, -1))
    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
}

/** A padded container for a dialog input; AlertDialog.Builder has no padded setView overload. */
internal fun Context.dialogInput(view: View): View = FrameLayout(this).apply {
    setPadding(dp(22), dp(8), dp(22), 0)
    addView(view, FrameLayout.LayoutParams(-1, -2))
}
