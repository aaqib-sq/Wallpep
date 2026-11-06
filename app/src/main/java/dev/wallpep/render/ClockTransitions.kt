package dev.wallpep.render

import dev.wallpep.model.AnimationStyle
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The visual state of one entrance-transition frame, in layer-relative units so the same curves
 * drive the editor preview and the applied wallpaper identically. `alpha` multiplies the layer's
 * own opacity; `dx`/`dy` are fractions of the layer content size; `blur` is a fraction of the
 * content height; `pivotX`/`pivotY` are fractions of the content box (0.5/0.5 = centre);
 * `glitch` is a 0..1 band-jitter intensity quantised by `glitchStep`.
 *
 * Pure Kotlin with no Android types, so every curve is unit-testable off device.
 */
data class TransitionFrame(
    val alpha: Float,
    val scaleX: Float,
    val scaleY: Float,
    val dx: Float,
    val dy: Float,
    val rotation: Float,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
    val blur: Float = 0f,
    val glitch: Float = 0f,
    val glitchStep: Int = 0,
)

/**
 * Entrance transitions for the clock and date layers (#150, #151): the 18 selectable animation
 * styles from the reference inventory as pure progress curves. Progress is wall-time based —
 * 0..1 over [durationMillis] — so a transition costs frames only while it runs and the wallpaper
 * is event-driven (idle) again the moment it ends. Every curve starts hidden at p = 0 and ends
 * exactly at rest at p = 1, so the last animated frame and the first idle frame agree.
 *
 * Legacy enum names that older documents may still carry (kept readable but no longer offered)
 * fall back to plain curves; [AnimationStyle.NONE] never animates.
 */
object ClockTransitions {

    /** False only for NONE: every other stored style renders a real transition. */
    fun playable(style: AnimationStyle): Boolean = style != AnimationStyle.NONE

    /** How long one entrance transition runs before the scene idles again. */
    fun durationMillis(style: AnimationStyle): Long = when (style) {
        AnimationStyle.NONE -> 0L
        AnimationStyle.SLIDE_UP, AnimationStyle.SLIDE_DOWN,
        AnimationStyle.SLIDE_LEFT, AnimationStyle.SLIDE_RIGHT -> 550L
        AnimationStyle.POP_IN -> 520L
        AnimationStyle.FADE_AND_SCALE, AnimationStyle.ZOOM_IN, AnimationStyle.ZOOM_OUT,
        AnimationStyle.ROTATE_IN, AnimationStyle.STRETCH_HORIZONTAL,
        AnimationStyle.STRETCH_VERTICAL -> 620L
        AnimationStyle.UNFOLD -> 650L
        AnimationStyle.SPIN_IN, AnimationStyle.GLITCH, AnimationStyle.BLUR_IN -> 700L
        AnimationStyle.SWING, AnimationStyle.DROP_BOUNCE -> 900L
        AnimationStyle.HEARTBEAT -> 950L
        AnimationStyle.CROSS_FADE -> 450L
        // Legacy names kept only so old documents stay readable; they rest like the default.
        else -> 600L
    }

    /** The visual state at progress `p` (0..1). `frame(style, 1f)` is always the resting state. */
    fun frame(style: AnimationStyle, progress: Float): TransitionFrame {
        val p = progress.coerceIn(0f, 1f)
        return when (style) {
            AnimationStyle.SLIDE_UP -> slide(p, dy = 1f)
            AnimationStyle.SLIDE_DOWN -> slide(p, dy = -1f)
            AnimationStyle.SLIDE_LEFT -> slide(p, dx = 1f)
            AnimationStyle.SLIDE_RIGHT -> slide(p, dx = -1f)
            AnimationStyle.ZOOM_IN -> frameOf(
                alpha = min(1f, p / 0.4f),
                scaleX = 0.35f + 0.65f * easeOutQuint(p),
                scaleY = 0.35f + 0.65f * easeOutQuint(p),
            )
            AnimationStyle.ZOOM_OUT -> frameOf(
                alpha = min(1f, p / 0.45f),
                scaleX = 1.7f - 0.7f * easeOutQuint(p),
                scaleY = 1.7f - 0.7f * easeOutQuint(p),
            )
            AnimationStyle.POP_IN -> frameOf(
                alpha = min(1f, p / 0.35f),
                scaleX = easeOutBack(p, 2.0f),
                scaleY = easeOutBack(p, 2.0f),
            )
            AnimationStyle.SPIN_IN -> frameOf(
                alpha = min(1f, p / 0.3f),
                scaleX = 0.35f + 0.65f * easeOutCubic(p),
                scaleY = 0.35f + 0.65f * easeOutCubic(p),
                rotation = -180f * (1f - easeOutQuint(p)),
            )
            AnimationStyle.ROTATE_IN -> frameOf(
                alpha = min(1f, p / 0.4f),
                scaleX = 0.92f + 0.08f * easeOutCubic(p),
                scaleY = 0.92f + 0.08f * easeOutCubic(p),
                rotation = -90f * (1f - easeOutCubic(p)),
            )
            AnimationStyle.SWING -> frameOf(
                alpha = min(1f, p / 0.2f),
                // Damped pendulum hung from the top edge: sin(2π·2.5·p) is exactly 0 at p = 1.
                rotation = SWING_DEGREES * (1f - p) * sin(2f * PI.toFloat() * 2.5f * p),
                pivotY = 0f,
            )
            AnimationStyle.UNFOLD -> frameOf(
                alpha = min(1f, p / 0.35f),
                scaleY = 0.02f + 0.98f * easeOutCubic(p),
                pivotY = 0f,
            )
            AnimationStyle.GLITCH -> {
                val intensity = 1f - easeOutCubic(p)
                val step = (p * GLITCH_STEPS).toInt()
                frameOf(
                    alpha = min(1f, p / 0.25f),
                    dx = glitchOffset(0, step) * intensity * 0.04f,
                    dy = glitchOffset(1, step) * intensity * 0.02f,
                    glitch = intensity,
                    glitchStep = step,
                )
            }
            AnimationStyle.HEARTBEAT -> frameOf(
                alpha = min(1f, p / 0.15f),
                scaleX = keyframed(p, HEARTBEAT_KEYS, HEARTBEAT_SCALES),
                scaleY = keyframed(p, HEARTBEAT_KEYS, HEARTBEAT_SCALES),
            )
            AnimationStyle.DROP_BOUNCE -> frameOf(alpha = min(1f, p / 0.18f), dy = dropOffset(p))
            AnimationStyle.BLUR_IN -> frameOf(
                alpha = min(1f, p / 0.6f),
                scaleX = 1.05f - 0.05f * easeOutCubic(p),
                scaleY = 1.05f - 0.05f * easeOutCubic(p),
                blur = 0.16f * (1f - easeOutCubic(p)),
            )
            AnimationStyle.STRETCH_HORIZONTAL -> frameOf(
                alpha = min(1f, p / 0.3f),
                scaleX = 0.25f + 0.75f * easeOutBack(p, 1.9f),
                scaleY = 1.14f - 0.14f * easeOutCubic(p),
            )
            AnimationStyle.STRETCH_VERTICAL -> frameOf(
                alpha = min(1f, p / 0.3f),
                scaleX = 1.14f - 0.14f * easeOutCubic(p),
                scaleY = 0.25f + 0.75f * easeOutBack(p, 1.9f),
            )
            AnimationStyle.CROSS_FADE -> frameOf(alpha = easeOutCubic(p))
            AnimationStyle.NONE -> frameOf()
            // Fade & Scale is the reference default; legacy document names share its curve.
            else -> frameOf(
                alpha = easeOutCubic(p),
                scaleX = 0.86f + 0.14f * easeOutCubic(p),
                scaleY = 0.86f + 0.14f * easeOutCubic(p),
            )
        }
    }

    /**
     * Deterministic -1..1 jitter for the Glitch style's band offsets. A pure integer hash keeps
     * the renderer free of RNG state and makes every frame reproducible in tests.
     */
    fun glitchOffset(band: Int, step: Int): Float {
        var h = band.toLong() * 374761393L + step.toLong() * 668265263L + 1013904223L
        h = (h xor (h ushr 15)) * 1274126177L
        h = h xor (h ushr 13)
        val unit = (h and 0x7fffffffL).toDouble() / 0x7fffffffL.toDouble()
        return (unit * 2.0 - 1.0).toFloat()
    }

    // ---------------------------------------------------------------- curves

    private fun slide(p: Float, dx: Float = 0f, dy: Float = 0f): TransitionFrame = frameOf(
        alpha = min(1f, p / 0.5f),
        dx = dx * SLIDE_DISTANCE * (1f - easeOutQuint(p)),
        dy = dy * SLIDE_DISTANCE * (1f - easeOutQuint(p)),
    )

    /**
     * Gravity-flavoured drop with three decaying bounces, as a `dy` fraction (negative = above
     * the resting position). Continuous at every segment boundary and exactly 0 at p = 1.
     */
    internal fun dropOffset(t: Float): Float = when {
        t <= 0f -> -1.3f
        t < 0.45f -> {
            val u = t / 0.45f
            -1.3f * (1f - u * u)
        }
        t < 0.70f -> bounce((t - 0.45f) / 0.25f, 0.30f)
        t < 0.87f -> bounce((t - 0.70f) / 0.17f, 0.11f)
        t < 1f -> bounce((t - 0.87f) / 0.13f, 0.04f)
        else -> 0f
    }

    /** Parabolic bounce: 0 at both ends, peak `-height` in the middle. */
    private fun bounce(u: Float, height: Float): Float = -4f * height * u * (1f - u)

    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    private fun easeOutQuint(t: Float): Float {
        val u = 1f - t
        val u2 = u * u
        return 1f - u2 * u2 * u
    }

    /** Overshooting settle: 0 at t = 0, exactly 1 at t = 1, a bounded bulge past 1 in between. */
    private fun easeOutBack(t: Float, overshoot: Float): Float {
        val u = t - 1f
        return 1f + (overshoot + 1f) * u * u * u + overshoot * u * u
    }

    private fun keyframed(t: Float, keys: FloatArray, values: FloatArray): Float {
        if (t <= keys[0]) return values[0]
        for (i in 1 until keys.size) {
            if (t <= keys[i]) {
                val span = keys[i] - keys[i - 1]
                val u = if (span <= 0f) 1f else (t - keys[i - 1]) / span
                return values[i - 1] + (values[i] - values[i - 1]) * u
            }
        }
        return values[values.size - 1]
    }

    private fun frameOf(
        alpha: Float = 1f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        dx: Float = 0f,
        dy: Float = 0f,
        rotation: Float = 0f,
        pivotX: Float = 0.5f,
        pivotY: Float = 0.5f,
        blur: Float = 0f,
        glitch: Float = 0f,
        glitchStep: Int = 0,
    ) = TransitionFrame(
        alpha = alpha.coerceIn(0f, 1f),
        scaleX = max(0f, scaleX),
        scaleY = max(0f, scaleY),
        dx = dx,
        dy = dy,
        rotation = rotation,
        pivotX = pivotX,
        pivotY = pivotY,
        blur = max(0f, blur),
        glitch = glitch.coerceIn(0f, 1f),
        glitchStep = glitchStep,
    )

    /** Heartbeat: two decaying systolic beats over a settle to exactly 1.0. */
    private val HEARTBEAT_KEYS = floatArrayOf(0f, 0.18f, 0.30f, 0.38f, 0.46f, 0.54f, 0.62f, 0.72f, 0.85f, 1f)
    private val HEARTBEAT_SCALES = floatArrayOf(0.72f, 0.95f, 1.00f, 1.10f, 0.98f, 1.02f, 1.08f, 0.99f, 1.00f, 1.00f)

    private const val SLIDE_DISTANCE = 1.15f
    private const val SWING_DEGREES = 15f
    private const val GLITCH_STEPS = 14
}
