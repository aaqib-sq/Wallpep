package dev.wallpep.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Wallpep's golden studio palette: black surfaces, gold accents, warm off-white type.
 * Colours are the app's own; nothing here is taken from another app.
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

/** A black card with a subtle golden hairline border. */
internal fun Context.card(radiusDp: Float = 20f, fill: Int = Gold.SURFACE, padding: Int = 16): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        background = shape(fill, radiusDp, Gold.GOLD_BORDER)
    }

internal fun Context.actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13.5f
        typeface = WallpepType.bodyMedium(context)
        setTextColor(if (primary) Gold.ON_GOLD else Gold.GOLD)
        background = if (primary) shape(Gold.GOLD, 14f) else shape(0x00000000, 14f, Gold.GOLD_BORDER_STRONG)
        minHeight = dp(48)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

internal fun Context.goldSeekBar(maximum: Int, description: String): SeekBar = SeekBar(this).apply {
    max = maximum
    contentDescription = description
    progressTintList = ColorStateList.valueOf(Gold.GOLD)
    thumbTintList = ColorStateList.valueOf(Gold.GOLD_LIGHT)
    progressBackgroundTintList = ColorStateList.valueOf(Gold.TRACK)
}
