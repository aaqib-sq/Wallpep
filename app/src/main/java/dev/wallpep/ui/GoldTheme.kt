package dev.wallpep.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.wallpep.fonts.FontCatalog
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * Wallpep's golden studio palette: black surfaces, gold accents, warm off-white type.
 * Gold is reserved for primary buttons, the active tab and selected states; every other
 * border is a neutral muted hairline. Colours are the app's own; nothing here is taken
 * from another app.
 */
object Gold {
    val BACKGROUND = 0xFF000000.toInt()
    val SURFACE = 0xFF141414.toInt()
    val SURFACE_RAISED = 0xFF1E1E1E.toInt()
    val FIELD = 0xFF1A1A1A.toInt()
    val GOLD = 0xFFD4AF37.toInt()
    val GOLD_LIGHT = 0xFFF3E3A6.toInt()
    val GOLD_BORDER = 0x59D4AF37.toInt()
    val GOLD_BORDER_STRONG = 0xA6D4AF37.toInt()
    /** The muted hairline used by every unselected card, field and segmented control. */
    val LINE = 0xFF2C2C2C.toInt()
    val TEXT = 0xFFF4EFE3.toInt()
    val MUTED = 0xFF9C9587.toInt()
    val ON_GOLD = 0xFF0A0A0A.toInt()
    val TRACK = 0x33FFFFFF
}

/** Bundled Cinzel for headings and tab labels, with a safe platform fallback. */
object WallpepType {
    private val display = WeakHashMap<Context, Typeface>()

    fun display(context: Context): Typeface = display.getOrPut(context.applicationContext) {
        try {
            Typeface.createFromAsset(context.assets, "fonts/display/cinzel/Cinzel-Variable.ttf")
        } catch (_: Exception) {
            Typeface.create("serif", Typeface.NORMAL)
        }
    }

    fun body(context: Context): Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun bodyMedium(context: Context): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        val key = id ?: FontCatalog.DEFAULT_ID
        synchronized(faces) {
            faces[key]?.let { return it }
            val face = FontCatalog.typeface(context, id)
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

/** A black card with a muted (non-gold) hairline border. */
internal fun Context.card(radiusDp: Float = 18f, fill: Int = Gold.SURFACE, padding: Int = 16): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = shape(fill, radiusDp, Gold.LINE)
    }

internal fun Context.actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13.5f
        stateListAnimator = null
        typeface = WallpepType.bodyMedium(context)
        setTextColor(if (primary) Gold.ON_GOLD else Gold.TEXT)
        background = if (primary) shape(Gold.GOLD, 14f) else shape(0x00000000, 14f, Gold.LINE)
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

internal fun Context.goldSeekBar(maximum: Int, description: String): SeekBar = SeekBar(this).apply {
    max = maximum
    contentDescription = description
    progressTintList = ColorStateList.valueOf(Gold.GOLD)
    thumbTintList = ColorStateList.valueOf(Gold.GOLD_LIGHT)
    progressBackgroundTintList = ColorStateList.valueOf(Gold.TRACK)
}

/** A switch tinted with the gold accent only while checked. */
@Suppress("UseSwitchCompatOrMaterialCode")
internal fun Context.goldSwitch(label: String): Switch = Switch(this).apply {
    text = label
    textSize = 12.5f
    setTextColor(Gold.TEXT)
    typeface = WallpepType.body(context)
    minHeight = dp(44)
    setPadding(0, dp(2), 0, dp(2))
    thumbTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Gold.GOLD, Gold.SURFACE_RAISED),
    )
    trackTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Gold.GOLD_BORDER_STRONG, Gold.LINE),
    )
}
