package dev.wallpep.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.View
import dev.wallpep.fonts.FontCatalog
import dev.wallpep.model.AnimationStyle
import dev.wallpep.model.ClockFormat
import dev.wallpep.model.ClockFormats
import dev.wallpep.model.DateFormats
import dev.wallpep.model.EffectStyle
import dev.wallpep.model.ShadowSettings
import dev.wallpep.model.StrokeSettings
import dev.wallpep.model.StrokeStyle
import dev.wallpep.model.TextAlignment
import dev.wallpep.model.TimeStyle
import dev.wallpep.model.TransformSettings
import dev.wallpep.model.WallpaperSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * A View drawn to an Engine's SurfaceHolder, or into the editor's live preview and collection
 * thumbnails.
 *
 * The clock and the date are independent layers: each has its own face, size, letter spacing,
 * colour, gradient, stroke, shadow, transform, opacity, alignment and position, and each can be
 * flipped in front of the cutout subject. The optional text layer behaves the same and sits
 * behind the subject by default. Clock formats can stack the hour, minute and seconds on their
 * own rows.
 *
 * Every text-ish layer is rendered through a small offscreen software canvas (cached per minute,
 * or per second for seconds formats) and composited with a full 2D/projective matrix, so the
 * Transform fields (perspective, skews, rotation, stretch) behave identically in the editor
 * preview, the collection thumbnails and the live wallpaper. Cached layer bitmaps let the
 * particle animation and a parallax tilt redraw the scene without re-shaping any glyph.
 *
 * Entrance transitions (#150, #151) animate the cached clock and date bitmaps — translate,
 * scale, rotate, alpha, blur and glitch bands — for a bounded wall-timed burst on the apply /
 * return-to-home moment and on minute rollovers; between bursts the scene is event-driven and
 * idle, exactly as before. Collection thumbnails set [animationsEnabled] to false so cards
 * always show the resting scene.
 */
class WallpaperScene(context: Context, private val miniature: Boolean = false) : View(context) {
    var settings: WallpaperSettings = WallpaperSettings()
        set(value) { field = value.sanitized(); invalidate() }

    private var backgroundBitmap: Bitmap? = null
    private var foregroundBitmap: Bitmap? = null
    private var imagesGeneration = 0L

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Match Phase 1's 0x26 black scrim on the foreground too, so the two copies line up in color.
    private val foregroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = BASE_FOREGROUND_FILTER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val compositePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val secondsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokeSecondsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    // Only the in-use faces are ever loaded; a tiny LRU keeps repeated face changes from
    // accumulating every family the user tries in the editor preview.
    private val fontCache = object : LinkedHashMap<String, Typeface>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean = size > 4
    }

    // ---------------------------------------------------------------- parallax tilt

    /** Simulated tilt, normalised to roughly -1..+1 on both axes. */
    private var tiltX = 0f
    private var tiltY = 0f
    private var tiltReturnQueued = false

    /** The editor feeds drag offsets; the engine feeds smoothed gyro-derived values here. */
    fun setTilt(x: Float, y: Float) {
        tiltReturnQueued = false
        tiltX = x.coerceIn(-1f, 1f)
        tiltY = y.coerceIn(-1f, 1f)
        invalidate()
    }

    /** Eases a dragged preview back to centre; the wallpaper keeps its gyro-driven tilt as-is. */
    fun releaseTilt() {
        if (tiltX == 0f && tiltY == 0f) {
            tiltReturnQueued = false
            return
        }
        if (tiltReturnQueued) return
        tiltReturnQueued = true
        postOnAnimation(tiltReturnRunnable)
    }

    private val tiltReturnRunnable = object : Runnable {
        override fun run() {
            if (!tiltReturnQueued) return
            tiltX *= TILT_RETURN
            tiltY *= TILT_RETURN
            if (abs(tiltX) < TILT_EPSILON && abs(tiltY) < TILT_EPSILON) {
                tiltX = 0f
                tiltY = 0f
                tiltReturnQueued = false
                invalidate()
                return
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    // ---------------------------------------------------------------- entrance transitions

    /**
     * Entrance transitions (#150, #151) for the clock and date layers. Battery discipline: a
     * transition is a bounded, wall-timed burst — it plays on the apply/return-to-home moment
     * (the engine calls [playEntranceAnimation]) and on minute rollovers (detected in [onDraw]),
     * needs frames only while [transitionActive], and leaves the scene event-driven afterwards.
     * Attached views (editor preview) pump their own frames with [postOnAnimation]; the engine's
     * detached scene is pumped by the service, which re-requests frames while [transitionActive].
     */
    var animationsEnabled: Boolean = true

    private var transitionPending = false
    private var transitionRunning = false
    private var transitionStartNanos = 0L
    private var transitionStyle = AnimationStyle.FADE_AND_SCALE
    private var lastMinuteTick = Long.MIN_VALUE
    private var transitionFrameQueued = false

    /** True while an entrance transition still needs frames; false again once the layers rest. */
    val transitionActive: Boolean get() = transitionPending || transitionRunning

    /**
     * Arms the selected entrance animation for the next drawn frame. No-op (and any pending arm
     * is cleared) when animations are disabled or the stored style is NONE; a running transition
     * is never restarted mid-flight.
     */
    fun playEntranceAnimation() {
        if (!animationsAllowed()) {
            transitionPending = false
            return
        }
        if (transitionRunning) return
        transitionPending = true
        armTransitionFrame()
    }

    private fun animationsAllowed(): Boolean =
        animationsEnabled && settings.clockAnimations && ClockTransitions.playable(settings.animationStyle)

    /**
     * Advances the transition state for this frame: consumes a pending entrance, auto-starts on
     * a minute rollover, and retires the transition when its wall-time budget (including the
     * date's stagger tail) is spent. The very first drawn minute never auto-animates.
     */
    private fun updateTransition(nowNanos: Long, minuteTick: Long) {
        val minuteChanged = minuteTick != lastMinuteTick
        if (minuteChanged) lastMinuteTick = minuteTick
        if (transitionPending) {
            transitionPending = false
            if (animationsAllowed()) {
                transitionStyle = settings.animationStyle
                transitionStartNanos = nowNanos
                transitionRunning = true
            }
        }
        if (transitionRunning) {
            val totalMillis = ClockTransitions.durationMillis(transitionStyle) + DATE_STAGGER_MS
            if (nowNanos - transitionStartNanos >= totalMillis * 1_000_000L) transitionRunning = false
        } else if (minuteChanged && animationsAllowed()) {
            transitionStyle = settings.animationStyle
            transitionStartNanos = nowNanos
            transitionRunning = true
        }
        if (transitionRunning) armTransitionFrame()
    }

    /**
     * The frame for one layer, or null when that layer rests. The date trails the clock by
     * [DATE_STAGGER_MS]; during the stagger it holds its initial (hidden) state.
     */
    private fun transitionFrameAt(nowNanos: Long, delayMillis: Long): TransitionFrame? {
        if (!transitionRunning) return null
        val duration = ClockTransitions.durationMillis(transitionStyle)
        if (duration <= 0L) return null
        val elapsed = (nowNanos - transitionStartNanos) / 1_000_000L - delayMillis
        if (elapsed < 0L) return ClockTransitions.frame(transitionStyle, 0f)
        if (elapsed >= duration) return null
        return ClockTransitions.frame(transitionStyle, elapsed.toFloat() / duration.toFloat())
    }

    /** Only attached, shown views pump themselves; the engine's scene is pumped by the service. */
    private fun armTransitionFrame() {
        if (transitionFrameQueued) return
        if (!isAttachedToWindow || !isShown) return
        transitionFrameQueued = true
        postOnAnimation(transitionRunnable)
    }

    private val transitionRunnable = Runnable {
        transitionFrameQueued = false
        // Drawing restarts only through onDraw; detached or hidden views never re-arm the loop.
        if (isAttachedToWindow && isShown && (transitionRunning || transitionPending)) invalidate()
    }

    private fun cancelTransition() {
        transitionPending = false
        transitionRunning = false
        lastMinuteTick = Long.MIN_VALUE
        transitionFrameQueued = false
        removeCallbacks(transitionRunnable)
    }

    // ---------------------------------------------------------------- particles

    private class Particle {
        var x = 0f
        var y = 0f
        var speedFactor = 1f
        var phase = 0f
        var spin = 0f
        var size = 1f
    }

    private val particles = ArrayList<Particle>()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var particlesPrimed = false
    private var lastParticleFrameNanos = 0L
    private var particleNanos = 0L
    private var particleFrameQueued = false
    private val particleRng = java.util.Random()

    private val particleRunnable = Runnable {
        particleFrameQueued = false
        // Drawing may only restart from onDraw; detached or hidden views never re-arm the loop.
        if (isAttachedToWindow && isShown && settings.effectStyle != EffectStyle.NONE) invalidate()
    }

    /** Ownership of both engine-local bitmaps transfers to this scene, together. */
    fun setImages(background: Bitmap?, foreground: Bitmap?) {
        val usableForeground = if (background == null) null else foreground
        if (foreground !== usableForeground) foreground?.recycle()
        if (backgroundBitmap !== background) {
            backgroundBitmap?.recycle()
            backgroundBitmap = background
        }
        if (foregroundBitmap !== usableForeground) {
            foregroundBitmap?.recycle()
            foregroundBitmap = usableForeground
        }
        imagesGeneration++
        releaseLayerCaches()
        invalidate()
    }

    /** Releases both images and any transition state, so a hidden scene never keeps animating. */
    fun release() {
        setImages(null, null)
        cancelTransition()
    }

    // ---------------------------------------------------------------- colorize

    private var colorizeKey = Int.MIN_VALUE

    /** Folds Hue Rotation + Hue (additive) + Saturation + Grayscale + Brightness into one matrix. */
    private fun applyColorize() {
        val key = ((((settings.hueRotation * 31 + settings.hue) * 31 + settings.saturation) * 31 + settings.grayscale) * 31 + settings.brightness)
        if (key == colorizeKey) return
        colorizeKey = key
        val hue = (((settings.hueRotation + settings.hue) % 360) + 360) % 360
        val saturation = (settings.saturation / 100f) * (1f - settings.grayscale / 100f)
        val brightness = settings.brightness
        val neutral = hue == 0 && abs(saturation - 1f) < 0.001f && brightness == 100
        if (neutral) {
            imagePaint.colorFilter = null
            fillPaint.colorFilter = null
            foregroundPaint.colorFilter = BASE_FOREGROUND_FILTER
            return
        }
        val matrix = ColorMatrix()
        if (hue != 0) matrix.postConcat(hueRotationMatrix(hue.toFloat()))
        if (abs(saturation - 1f) >= 0.001f) {
            val sat = ColorMatrix()
            sat.setSaturation(saturation.coerceIn(0f, 2f))
            matrix.postConcat(sat)
        }
        if (brightness != 100) {
            val b = ColorMatrix()
            applyBrightness(b, brightness)
            matrix.postConcat(b)
        }
        imagePaint.colorFilter = ColorMatrixColorFilter(matrix)
        fillPaint.colorFilter = ColorMatrixColorFilter(matrix)
        val combined = ColorMatrix()
        combined.set(FOREGROUND_DARKEN)
        combined.postConcat(matrix)
        foregroundPaint.colorFilter = ColorMatrixColorFilter(combined)
    }

    /** SVG-style hue rotation that keeps the luminance plane fixed. */
    private fun hueRotationMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        return ColorMatrix(
            floatArrayOf(
                lr + c * (1f - lr) - s * lr, lg - c * lg - s * lg, lb - c * lb + s * (1f - lb), 0f, 0f,
                lr - c * lr + s * 0.143f, lg + c * (1f - lg) + s * 0.140f, lb - c * lb - s * 0.283f, 0f, 0f,
                lr - c * lr - s * (1f - lr), lg - c * lg + s * lg, lb + c * (1f - lb) + s * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }

    /** brightness < 100 darkens; > 100 blends toward white so 200 approaches a soft clip. */
    private fun applyBrightness(matrix: ColorMatrix, brightness: Int) {
        if (brightness <= 100) {
            val scale = brightness.coerceIn(0, 100) / 100f
            matrix.setScale(scale, scale, scale, 1f)
            return
        }
        val t = (brightness.coerceIn(0, 200) - 100) / 100f
        matrix.setScale(1f - t, 1f - t, 1f - t, 1f)
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 255f * t,
                    0f, 1f, 0f, 0f, 255f * t,
                    0f, 0f, 1f, 0f, 255f * t,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- cached layers

    private class PreparedLayer(
        val originX: Float,
        val originY: Float,
        val bitmapW: Int,
        val bitmapH: Int,
        val anchorX: Float,
        val anchorY: Float,
        val contentW: Float,
        val contentH: Float,
        val transform: TransformSettings,
        val draw: (Canvas) -> Unit,
    )

    private class LayerCache(
        val settings: WallpaperSettings,
        val imagesGen: Long,
        val tick: Long,
        val width: Int,
        val height: Int,
        val bitmap: Bitmap,
        val prepared: PreparedLayer,
    )

    private val layerCaches = arrayOfNulls<LayerCache>(4)

    private fun releaseLayerCaches() {
        for (i in layerCaches.indices) {
            layerCaches[i]?.bitmap?.recycle()
            layerCaches[i] = null
        }
    }

    private fun cacheValid(cache: LayerCache?, tick: Long): LayerCache? =
        if (cache != null && cache.settings === settings && cache.imagesGen == imagesGeneration &&
            cache.tick == tick && cache.width == width && cache.height == height
        ) {
            cache
        } else {
            null
        }

    private fun obtainLayer(slot: Int, tick: Long, prepare: () -> PreparedLayer?): LayerCache? {
        val cached = cacheValid(layerCaches[slot], tick)
        if (cached != null) return cached
        val prepared = prepare() ?: run {
            layerCaches[slot]?.bitmap?.recycle()
            layerCaches[slot] = null
            return null
        }
        val bitmap = Bitmap.createBitmap(prepared.bitmapW, prepared.bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-prepared.originX, -prepared.originY)
        prepared.draw(canvas)
        layerCaches[slot]?.bitmap?.recycle()
        val fresh = LayerCache(settings, imagesGeneration, tick, width, height, bitmap, prepared)
        layerCaches[slot] = fresh
        return fresh
    }

    /** Clock parts share metrics, so both are prepared from one pass but cached independently. */
    private fun obtainClock(w: Float, h: Float, now: Date, tick: Long): Pair<LayerCache?, LayerCache?> {
        val hour = cacheValid(layerCaches[PART_CLOCK_HOUR], tick)
        val minute = cacheValid(layerCaches[PART_CLOCK_MINUTE], tick)
        if (hour != null && minute != null) return hour to minute
        val pair = prepareClockParts(w, h, now) ?: run {
            layerCaches[PART_CLOCK_HOUR]?.bitmap?.recycle()
            layerCaches[PART_CLOCK_MINUTE]?.bitmap?.recycle()
            layerCaches[PART_CLOCK_HOUR] = null
            layerCaches[PART_CLOCK_MINUTE] = null
            return null to null
        }
        val hourLayer = obtainLayer(PART_CLOCK_HOUR, tick) { pair.first }
        val minuteLayer = obtainLayer(PART_CLOCK_MINUTE, tick) { pair.second }
        return hourLayer to minuteLayer
    }

    /**
     * Composites one cached layer at its anchor, with the parallax shift, the layer's own warp
     * and — only while an entrance transition runs — the transition's translate/scale/rotate,
     * alpha, blur and glitch applied on top. The transition matrix sits outside the user's
     * Transform warp, so an animated layer keeps its saved perspective/skew/rotation.
     */
    private fun compositeLayer(canvas: Canvas, layer: LayerCache, layerShift: Pair<Float, Float>, transition: TransitionFrame?) {
        val prepared = layer.prepared
        // Content centre expressed in bitmap space (the space the bitmap is drawn in).
        val cx = prepared.anchorX - prepared.originX
        val cy = prepared.anchorY - prepared.originY
        var anchorX = prepared.anchorX + layerShift.first
        var anchorY = prepared.anchorY + layerShift.second
        if (transition != null) {
            anchorX += transition.dx * prepared.contentW
            anchorY += transition.dy * prepared.contentH
        }
        val matrix = android.graphics.Matrix()
        matrix.setTranslate(anchorX, anchorY)
        if (transition != null) matrix.postConcat(transitionWarp(transition, prepared.contentW, prepared.contentH))
        matrix.postConcat(centerWarp(prepared.transform, prepared.contentW, prepared.contentH))
        matrix.postTranslate(-cx, -cy)
        val save = canvas.save()
        canvas.concat(matrix)
        var blurSet = false
        if (transition != null) {
            compositePaint.alpha = (255f * transition.alpha.coerceIn(0f, 1f)).toInt()
            val blurPx = transition.blur * prepared.contentH
            if (blurPx > 0.6f) {
                // Paint has no RenderEffect API (that lives on View/RenderNode); the supported
                // per-paint blur is BlurMaskFilter, which softens the layer's alpha mask and is
                // hardware-canvas capable since API 28 (minSdk is 33). Blur In degrades to the
                // alpha/scale ramp on any canvas that ignores mask filters — never a crash.
                compositePaint.maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                blurSet = true
            }
        }
        if (transition != null && transition.glitch > 0.01f) {
            drawGlitched(canvas, layer.bitmap, transition)
        } else {
            canvas.drawBitmap(layer.bitmap, 0f, 0f, compositePaint)
        }
        if (blurSet) compositePaint.maskFilter = null
        if (transition != null) compositePaint.alpha = 255
        canvas.restoreToCount(save)
    }

    /** Scale/rotation of one transition frame around its pivot, in content-centred space. */
    private fun transitionWarp(frame: TransitionFrame, contentW: Float, contentH: Float): android.graphics.Matrix {
        val matrix = android.graphics.Matrix()
        matrix.setScale(frame.scaleX, frame.scaleY)
        if (frame.rotation != 0f) matrix.postRotate(frame.rotation)
        val px = (frame.pivotX - 0.5f) * contentW
        val py = (frame.pivotY - 0.5f) * contentH
        if (px != 0f || py != 0f) {
            matrix.preTranslate(-px, -py)
            matrix.postTranslate(px, py)
        }
        return matrix
    }

    /**
     * Glitch style: the layer bitmap is drawn in horizontal bands, each offset and dimmed by a
     * deterministic amount that decays with the transition. Only runs during the glitch burst.
     */
    private fun drawGlitched(canvas: Canvas, bitmap: Bitmap, frame: TransitionFrame) {
        val bandHeight = max(1, bitmap.height / GLITCH_BANDS)
        val intensity = frame.glitch.coerceIn(0f, 1f)
        val baseAlpha = compositePaint.alpha
        val src = Rect()
        val dst = RectF()
        var band = 0
        var top = 0
        while (top < bitmap.height) {
            val bottom = min(bitmap.height, top + bandHeight)
            src.set(0, top, bitmap.width, bottom)
            val jitter = ClockTransitions.glitchOffset(band, frame.glitchStep)
            val offsetX = jitter * intensity * bitmap.width * GLITCH_SLICE_FRACTION
            val flicker = 1f - 0.4f * intensity * abs(ClockTransitions.glitchOffset(band + 97, frame.glitchStep))
            dst.set(offsetX, top.toFloat(), bitmap.width + offsetX, bottom.toFloat())
            compositePaint.alpha = (baseAlpha * flicker).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, src, dst, compositePaint)
            top = bottom
            band++
        }
        compositePaint.alpha = baseAlpha
    }

    /** Stretch → rotation → skews → keystones, all around the layer's centre. */
    private fun centerWarp(transform: TransformSettings, contentW: Float, contentH: Float): android.graphics.Matrix {
        val matrix = android.graphics.Matrix()
        if (transform.stretch != 100) matrix.postScale(transform.stretch / 100f, 1f)
        if (transform.rotation % 360 != 0) matrix.postRotate(transform.rotation.toFloat())
        if (transform.skewLeftRight != 0) {
            matrix.postSkew(0f, tan(Math.toRadians(transform.skewLeftRight.toDouble())).toFloat())
        }
        if (transform.skewHorizontal != 0) {
            matrix.postSkew(tan(Math.toRadians(transform.skewHorizontal.toDouble())).toFloat(), 0f)
        }
        if (transform.skewVertical != 0) {
            matrix.postSkew(0f, -tan(Math.toRadians(transform.skewVertical.toDouble())).toFloat())
        }
        if (transform.skewTopBottom != 0) {
            matrix.postSkew(-tan(Math.toRadians(transform.skewTopBottom.toDouble())).toFloat(), 0f)
        }
        if (transform.skewLeftVertical != 0) matrix.postConcat(keystoneVertical(transform.skewLeftVertical, contentW, contentH))
        if (transform.skewBottomHorizontal != 0) matrix.postConcat(keystoneHorizontal(transform.skewBottomHorizontal, contentW, contentH))
        if (transform.perspectiveX != 0) matrix.postConcat(keystoneVertical(transform.perspectiveX, contentW, contentH))
        if (transform.perspectiveY != 0) matrix.postConcat(keystoneHorizontal(transform.perspectiveY, contentW, contentH))
        return matrix
    }

    /** Rotate around the vertical axis: x' = cos·x / (1 + tan·x / focal). */
    private fun keystoneVertical(angle: Int, contentW: Float, contentH: Float): android.graphics.Matrix {
        val rad = Math.toRadians(angle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val focal = 2f * max(contentW, contentH).coerceAtLeast(1f)
        return android.graphics.Matrix().apply {
            setValues(floatArrayOf(c, 0f, 0f, 0f, 1f, 0f, s / focal, 0f, 1f))
        }
    }

    /** Rotate around the horizontal axis: y' = cos·y / (1 + tan·y / focal). */
    private fun keystoneHorizontal(angle: Int, contentW: Float, contentH: Float): android.graphics.Matrix {
        val rad = Math.toRadians(angle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val focal = 2f * max(contentW, contentH).coerceAtLeast(1f)
        return android.graphics.Matrix().apply {
            setValues(floatArrayOf(1f, 0f, 0f, 0f, c, 0f, 0f, s / focal, 1f))
        }
    }

    // ---------------------------------------------------------------- onDraw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = Date()
        val frameNanos = System.nanoTime()
        applyColorize()
        val wallpaperShift = wallpaperShift(w, h)
        // Per-layer parallax (#46, #47, #88, #89): the clock and the date each gate and scale
        // the global layer travel; the text layer keeps the global pair (it has no own fields).
        val clockShift = layerShiftFor(settings.parallaxClockEnabled, settings.parallaxClockStrength, w, h)
        val dateShift = layerShiftFor(settings.parallaxDateEnabled, settings.parallaxDateStrength, w, h)
        val textShift = layerShiftFor(true, 100, w, h)
        val destination = drawBackground(canvas, w, h, wallpaperShift)
        val foreground = foregroundBitmap?.takeUnless { it.isRecycled }
        val hasForeground = destination != null && foreground != null
        val plan = depthPlan(settings, hasForeground)
        val clockTick = if (clockNeedsSeconds()) now.time / 1000L else now.time / 60000L
        val minuteTick = now.time / 60000L
        updateTransition(frameNanos, minuteTick)
        val clockFrame = transitionFrameAt(frameNanos, 0L)
        val dateFrame = transitionFrameAt(frameNanos, DATE_STAGGER_MS)

        if (plan.hourBefore || plan.minuteBefore) {
            val (hourPart, minutePart) = obtainClock(w, h, now, clockTick)
            if (plan.hourBefore) hourPart?.let { compositeLayer(canvas, it, clockShift, clockFrame) }
            if (plan.minuteBefore) minutePart?.let { compositeLayer(canvas, it, clockShift, clockFrame) }
        }
        if (plan.dateBefore) {
            obtainLayer(PART_DATE, minuteTick) { prepareDateLayer(w, h, now) }
                ?.let { compositeLayer(canvas, it, dateShift, dateFrame) }
        }
        if (plan.textBefore) {
            obtainLayer(PART_TEXT, minuteTick) { prepareTextLayer(w, h) }
                ?.let { compositeLayer(canvas, it, textShift, null) }
        }
        if (plan.foreground && destination != null && foreground != null) {
            val save = canvas.save()
            canvas.translate(wallpaperShift.first, wallpaperShift.second)
            canvas.drawBitmap(foreground, null, destination, foregroundPaint)
            canvas.restoreToCount(save)
        }
        if (plan.hourAfter || plan.minuteAfter) {
            val (hourPart, minutePart) = obtainClock(w, h, now, clockTick)
            if (plan.hourAfter) hourPart?.let { compositeLayer(canvas, it, clockShift, clockFrame) }
            if (plan.minuteAfter) minutePart?.let { compositeLayer(canvas, it, clockShift, clockFrame) }
        }
        if (plan.dateAfter) {
            obtainLayer(PART_DATE, minuteTick) { prepareDateLayer(w, h, now) }
                ?.let { compositeLayer(canvas, it, dateShift, dateFrame) }
        }
        if (plan.textAfter) {
            obtainLayer(PART_TEXT, minuteTick) { prepareTextLayer(w, h) }
                ?.let { compositeLayer(canvas, it, textShift, null) }
        }
        drawParticles(canvas, w, h)
    }

    private fun wallpaperShift(w: Float, h: Float): Pair<Float, Float> {
        if (!settings.parallaxWallpaperEnabled) return 0f to 0f
        val strength = settings.parallaxWallpaperStrength / 100f
        return tiltX * strength * w * PARALLAX_FRAC_X to tiltY * strength * h * PARALLAX_FRAC_Y
    }

    /** The global layer travel gated and scaled by one layer's own parallax switch/strength. */
    private fun layerShiftFor(layerEnabled: Boolean, layerStrength: Int, w: Float, h: Float): Pair<Float, Float> {
        val scale = layerParallaxScale(settings.parallaxLayersEnabled, settings.parallaxLayersStrength, layerEnabled, layerStrength)
        if (scale <= 0f) return 0f to 0f
        return tiltX * scale * w * PARALLAX_FRAC_X to tiltY * scale * h * PARALLAX_FRAC_Y
    }

    private fun drawParticles(canvas: Canvas, w: Float, h: Float) {
        val style = settings.effectStyle
        if (style == EffectStyle.NONE) {
            particlesPrimed = false
            lastParticleFrameNanos = 0L
            return
        }
        val frameNanos = System.nanoTime()
        val dt = if (!particlesPrimed) 0f else min(0.05f, (frameNanos - lastParticleFrameNanos) / 1_000_000_000f)
        lastParticleFrameNanos = frameNanos
        particlesPrimed = true
        particleNanos += (dt * 1_000_000_000L).toLong()
        val elapsed = particleNanos / 1_000_000_000f

        val sizeScale = settings.effectSize / 100f
        val baseSize = max(1.5f, w * 0.016f * (0.35f + 0.65f * sizeScale))
        val angle = Math.toRadians(settings.effectAngle.toDouble())
        val dirX = cos(angle).toFloat()
        val dirY = sin(angle).toFloat()
        val fall = h * (0.10f + (settings.effectSpeed / 100f) * 0.95f)
        val alpha = (255f * settings.effectOpacity.coerceIn(0, 100) / 100f).toInt()
        particlePaint.color = withAlpha(settings.effectColor, alpha)
        particleStroke.color = withAlpha(settings.effectColor, alpha)
        particleStroke.strokeWidth = max(1.2f, baseSize * 0.16f)

        val target = settings.effectParticles
        while (particles.size < target) particles.add(newParticle(w, h, randomAnywhere = true))
        while (particles.size > target) particles.removeAt(particles.size - 1)

        for (particle in particles) {
            val step = fall * particle.speedFactor * dt
            particle.x += dirX * step
            particle.y += dirY * step
            val margin = baseSize * 4f
            if (particle.y > h + margin || particle.x > w + margin || particle.x < -margin || particle.y < -margin) {
                respawnParticle(particle, w, h, dirX, dirY, margin)
            }
            when (style) {
                EffectStyle.RAIN -> {
                    val len = baseSize * (1.8f + particle.size * 1.3f)
                    canvas.drawLine(
                        particle.x, particle.y,
                        particle.x - dirX * len, particle.y - dirY * len,
                        particleStroke,
                    )
                }
                EffectStyle.SNOW -> {
                    val wobble = sin(elapsed * 0.9f + particle.phase) * baseSize * 0.7f
                    canvas.drawCircle(particle.x + wobble, particle.y, baseSize * 0.75f * particle.size, particlePaint)
                }
                EffectStyle.SAKURA -> {
                    val sway = sin(elapsed * 1.7f + particle.phase) * baseSize * 1.1f
                    val rotation = Math.toDegrees((elapsed * particle.spin + particle.phase).toDouble()).toFloat()
                    canvas.save()
                    canvas.translate(particle.x + sway, particle.y)
                    canvas.rotate(rotation)
                    val halfW = baseSize * 1.35f * particle.size
                    val tip = baseSize * 2.1f * particle.size
                    val petal = Path().apply {
                        moveTo(0f, 0f)
                        cubicTo(halfW, -tip * 0.35f, halfW * 0.85f, -tip * 0.8f, 0f, -tip)
                        cubicTo(-halfW * 0.85f, -tip * 0.8f, -halfW, -tip * 0.35f, 0f, 0f)
                        close()
                    }
                    canvas.drawPath(petal, particlePaint)
                    canvas.restore()
                }
                EffectStyle.NONE -> Unit
            }
        }
        armParticleLoopIfNeeded()
    }

    private fun newParticle(w: Float, h: Float, randomAnywhere: Boolean): Particle = Particle().apply {
        x = particleRng.nextFloat() * w
        y = if (randomAnywhere) particleRng.nextFloat() * h else -baseMarginFor(w)
        speedFactor = 0.55f + particleRng.nextFloat() * 0.9f
        phase = particleRng.nextFloat() * (2f * PI.toFloat())
        spin = (particleRng.nextFloat() - 0.5f) * 2.6f
        size = 0.7f + particleRng.nextFloat() * 0.65f
    }

    private fun baseMarginFor(w: Float): Float = max(8f, w * 0.02f)

    private fun respawnParticle(p: Particle, w: Float, h: Float, dirX: Float, dirY: Float, margin: Float) {
        if (abs(dirY) >= abs(dirX)) {
            p.y = if (dirY >= 0f) -margin else h + margin
            p.x = particleRng.nextFloat() * w
        } else {
            p.x = if (dirX >= 0f) -margin else w + margin
            p.y = particleRng.nextFloat() * h
        }
        p.speedFactor = 0.55f + particleRng.nextFloat() * 0.9f
        p.phase = particleRng.nextFloat() * (2f * PI.toFloat())
        p.spin = (particleRng.nextFloat() - 0.5f) * 2.6f
        p.size = 0.7f + particleRng.nextFloat() * 0.65f
    }

    private fun armParticleLoopIfNeeded() {
        if (particleFrameQueued) return
        if (!isAttachedToWindow) return
        particleFrameQueued = true
        postDelayed(particleRunnable, FRAME_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        particleFrameQueued = false
        removeCallbacks(particleRunnable)
        tiltReturnQueued = false
        removeCallbacks(tiltReturnRunnable)
        transitionFrameQueued = false
        removeCallbacks(transitionRunnable)
        super.onDetachedFromWindow()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float, wallpaperShift: Pair<Float, Float>): RectF? {
        val overscan = if (settings.parallaxWallpaperEnabled) OVERSCAN else 1f
        val image = backgroundBitmap
        val save = canvas.save()
        canvas.translate(wallpaperShift.first, wallpaperShift.second)
        if (image != null && !image.isRecycled) {
            val scale = max(w / image.width, h / image.height) * overscan
            val dw = image.width * scale
            val dh = image.height * scale
            val destination = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            canvas.drawBitmap(image, null, destination, imagePaint)
            canvas.drawColor(0x26000000)
            canvas.restoreToCount(save)
            return destination
        }
        // A synthetic, code-drawn fallback; no copyrighted or personal photo is bundled.
        // applyColorize() caches by value, so re-read imagePaint's filter here every frame and
        // never clear it: clearing it used to leave this built-in backdrop unfiltered on the
        // next frame (the filter key had not changed, so applyColorize() early-returned).
        fillPaint.colorFilter = imagePaint.colorFilter
        fillPaint.shader = LinearGradient(-80f, -80f, w + 80f, h + 80f, 0xff0b1220.toInt(), 0xff05060a.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(-80f, -80f, w + 80f, h + 80f, fillPaint)
        fillPaint.shader = null
        fillPaint.color = 0x2E2563EB
        canvas.drawCircle(w * 0.82f, h * 0.78f, w * 0.58f, fillPaint)
        fillPaint.color = 0x3A2563EB
        val hill = Path().apply {
            moveTo(-80f, h * 0.83f)
            cubicTo(w * 0.3f, h * 0.64f, w * 0.65f, h * 0.93f, w + 80f, h * 0.70f)
            lineTo(w + 80f, h + 80f)
            lineTo(-80f, h + 80f)
            close()
        }
        canvas.drawPath(hill, fillPaint)
        canvas.restoreToCount(save)
        return null
    }

    private fun clockNeedsSeconds(): Boolean = ClockFormats.needsSeconds(settings.clockFormat)

    private fun padFor(stroke: StrokeSettings, shadow: ShadowSettings, unit: Float): Float {
        val strokePx = if (stroke.enabled) (stroke.width / 10f) * unit + 2f else 0f
        val shadowPx = if (shadow.enabled) {
            max(abs(shadow.offsetX), abs(shadow.offsetY)) * unit + shadow.blur * unit * 0.9f + 2f
        } else {
            3f * unit + 2f
        }
        return max(strokePx, shadowPx).coerceAtLeast(6f * max(1f, unit))
    }

    /** Gradient angle: 0° sweeps left→right, 90° sweeps top→bottom. */
    private fun gradientShader(span: RectF, angleDeg: Int, start: Int, end: Int, alpha: Int): LinearGradient {
        val cx = span.centerX()
        val cy = span.centerY()
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val half = (span.width() * abs(dx) + span.height() * abs(dy)) / 2f
        return LinearGradient(
            cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
            withAlpha(start, alpha), withAlpha(end, alpha), Shader.TileMode.CLAMP,
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = multiplyColorAlpha(color, alpha)

    /** Layer colour and its drop shadow, both scaled by the layer's opacity percent. */
    private fun ink(
        paint: Paint,
        color: Int,
        secondary: Int,
        gradient: Boolean,
        gradientAngle: Int,
        opacity: Int,
        span: RectF,
        shadow: ShadowSettings,
        fallbackRadius: Float,
        fallbackDy: Float,
        unit: Float,
    ) {
        val alpha = (255f * opacity.coerceIn(0, 100) / 100f).toInt()
        if (gradient) {
            paint.shader = gradientShader(span, gradientAngle, color, secondary, alpha)
            paint.color = Color.WHITE
        } else {
            paint.shader = null
            paint.color = withAlpha(color, alpha)
        }
        if (shadow.enabled) {
            val shadowAlpha = (alpha * shadow.opacity.coerceIn(0, 100) / 100f).toInt()
            val sx = (if (shadow.flipH) -shadow.offsetX else shadow.offsetX) * unit
            val sy = (if (shadow.flipV) -shadow.offsetY else shadow.offsetY) * unit
            paint.setShadowLayer(max(0.1f, shadow.blur * unit), sx, sy, withAlpha(shadow.color, shadowAlpha))
        } else {
            val colorAlpha = if (gradient) max(Color.alpha(color), Color.alpha(secondary)) else Color.alpha(color)
            val fallback = (0x99 * alpha * colorAlpha / (255 * 255)).coerceIn(0, 255)
            paint.setShadowLayer(fallbackRadius, 0f, fallbackDy, fallback shl 24)
        }
    }

    /** Stroke paint: round joins, alpha follows the layer's opacity, dashing for non-solid styles. */
    private fun inkStroke(
        paint: Paint,
        primary: Int,
        secondary: Int,
        stroke: StrokeSettings,
        layerOpacity: Int,
        span: RectF,
        unit: Float,
    ) {
        if (!stroke.enabled) return
        val alpha = (255f * layerOpacity.coerceIn(0, 100) / 100f).toInt()
        val widthPx = (stroke.width / 10f) * unit
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(0.5f, widthPx)
        paint.clearShadowLayer()
        paint.pathEffect = when (stroke.style) {
            StrokeStyle.SOLID -> null
            StrokeStyle.DASHED -> DashPathEffect(floatArrayOf(widthPx * 5f + 1f, widthPx * 3.2f + 1f), 0f)
            StrokeStyle.DOTTED -> DashPathEffect(floatArrayOf(0.1f, max(1f, widthPx * 2.6f + 1f)), 0f)
        }
        if (stroke.gradientEnabled) {
            paint.shader = gradientShader(span, stroke.gradientAngle, primary, secondary, alpha)
            paint.color = Color.WHITE
        } else {
            paint.shader = null
            paint.color = withAlpha(primary, alpha)
        }
    }

    private class Line(val text: String, val centreX: Float, val baseline: Float)

    /** Builds both clock parts (hour and minute) from one measurement pass. */
    private fun prepareClockParts(w: Float, h: Float, now: Date): Pair<PreparedLayer, PreparedLayer>? {
        if (!settings.showClock) return null
        val format = ClockFormats.format(settings.clockFormat)
        if (format != null && format.stacked) return stackedClockParts(w, h, format, now)

        val unit = unitScale(w, h)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)
        timePaint.typeface = face(settings.font)
        timePaint.textSize = settings.clockSizeSp * unit
        timePaint.letterSpacing = settings.clockLetterSpacing / 100f

        val time = if (format == null) localeClock(now) else explicitClock(format, now)
        fitWidth(timePaint, time.text, availableWidth)
        val fm = timePaint.fontMetrics
        val blockHeight = fm.descent - fm.ascent
        val contentW = timePaint.measureText(time.text)
        val top = (h * settings.positionY / 100f - blockHeight / 2f).coerceIn(0f, max(0f, h - blockHeight))
        val centre = lineCentre(w, settings.positionX, contentW, settings.clockAlign)
        val baseline = top - fm.ascent
        val box = RectF(centre - contentW / 2f, top, centre + contentW / 2f, top + blockHeight)
        val pad = padFor(settings.clockStroke, settings.clockShadow, unit)

        secondsPaint.set(timePaint)
        ink(timePaint, settings.textColor, settings.clockSecondaryColor, settings.clockGradientEnabled,
            settings.clockGradientAngle, settings.clockOpacity, box, settings.clockShadow, 3f * unit, 3f * unit, unit)
        ink(secondsPaint, settings.secondsColor, settings.clockSecondaryColor, settings.clockGradientEnabled,
            settings.clockGradientAngle, settings.clockOpacity, box, settings.clockShadow, 3f * unit, 3f * unit, unit)
        strokePrimaryPaint.set(timePaint)
        strokeSecondsPaint.set(secondsPaint)
        inkStroke(strokePrimaryPaint, settings.clockStroke.color, settings.clockStroke.secondaryColor,
            settings.clockStroke, settings.clockOpacity, box, unit)
        inkStroke(strokeSecondsPaint, settings.clockStroke.secondsColor, settings.clockStroke.secondaryColor,
            settings.clockStroke, settings.clockOpacity, box, unit)

        val hourBounds = if (time.hasHour) selectionBounds(time, timePaint, time.hourStart, time.hourEnd, centre, baseline) else null
        val secondsBounds = if (time.hasSeconds) selectionBounds(time, secondsPaint, time.secondsStart, time.secondsEnd, centre, baseline) else null
        val strokeEnabled = settings.clockStroke.enabled
        val strokePrimary: Paint = strokePrimaryPaint
        val strokeSeconds: Paint = strokeSecondsPaint

        fun drawPart(canvas: Canvas, part: Int) {
            when (part) {
                PART_SECONDS -> drawStaticLine(canvas, time, centre, baseline, secondsPaint, strokeSeconds, strokeEnabled)
                else -> drawStaticLine(canvas, time, centre, baseline, timePaint, strokePrimary, strokeEnabled)
            }
        }
        fun withClips(canvas: Canvas, clips: List<RectF?>, outs: List<RectF?>, part: Int) {
            val save = canvas.save()
            clips.filterNotNull().forEach { canvas.clipRect(it) }
            outs.filterNotNull().forEach { canvas.clipOutRect(it) }
            drawPart(canvas, part)
            canvas.restoreToCount(save)
        }

        val hourPrepared = preparedLayer(box, pad, settings.clockTransform) { canvas ->
            // Without an hour range this draws the whole line (the Phase 1 fallback).
            withClips(canvas, listOfNotNull(hourBounds), emptyList(), PART_PRIMARY)
        }
        val minutePrepared = preparedLayer(box, pad, settings.clockTransform) { canvas ->
            withClips(canvas, emptyList(), listOfNotNull(hourBounds, secondsBounds), PART_PRIMARY)
            if (secondsBounds != null) {
                withClips(canvas, listOf(secondsBounds), listOfNotNull(hourBounds), PART_SECONDS)
            }
        }
        return hourPrepared to minutePrepared
    }

    private fun stackedClockParts(w: Float, h: Float, format: ClockFormat, now: Date): Pair<PreparedLayer, PreparedLayer> {
        val unit = unitScale(w, h)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)
        timePaint.typeface = face(settings.font)
        timePaint.textSize = settings.clockSizeSp * unit
        timePaint.letterSpacing = settings.clockLetterSpacing / 100f

        val locale = locale()
        val hourPattern = if (use24Hour()) (if (format.padHour) "HH" else "H") else (if (format.padHour) "hh" else "h")
        val hourText = SimpleDateFormat(hourPattern, locale).format(now)
        val minuteText = SimpleDateFormat("mm", locale).format(now) + amPmSuffix(locale, now)
        val secondText = if (format.withSeconds) SimpleDateFormat("ss", locale).format(now) else null
        val rows = listOfNotNull(hourText, minuteText, secondText)
        rows.forEach { fitWidth(timePaint, it, availableWidth) }

        val fm = timePaint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val gap = settings.clockVerticalSpacing * unit
        val block = lineHeight * rows.size + gap * (rows.size - 1)
        val top = (h * settings.positionY / 100f - block / 2f).coerceIn(0f, max(0f, h - block))

        fun line(text: String, index: Int): Line = Line(
            text = text,
            centreX = lineCentre(w, settings.positionX, timePaint.measureText(text), settings.clockAlign),
            baseline = top + (lineHeight + gap) * index - fm.ascent,
        )

        val hourLines = listOf(line(hourText, 0))
        val minuteLine = line(minuteText, 1)
        val secondLine = if (secondText != null) line(secondText, 2) else null
        val minuteLines = listOfNotNull(minuteLine, secondLine)
        val boxLeft = (hourLines + minuteLines).minOf { it.centreX - timePaint.measureText(it.text) / 2f }
        val boxRight = (hourLines + minuteLines).maxOf { it.centreX + timePaint.measureText(it.text) / 2f }
        val box = RectF(boxLeft, top, boxRight, top + block)
        val pad = padFor(settings.clockStroke, settings.clockShadow, unit)

        secondsPaint.set(timePaint)
        ink(timePaint, settings.textColor, settings.clockSecondaryColor, settings.clockGradientEnabled,
            settings.clockGradientAngle, settings.clockOpacity, box, settings.clockShadow, 3f * unit, 3f * unit, unit)
        ink(secondsPaint, settings.secondsColor, settings.clockSecondaryColor, settings.clockGradientEnabled,
            settings.clockGradientAngle, settings.clockOpacity, box, settings.clockShadow, 3f * unit, 3f * unit, unit)
        strokePrimaryPaint.set(timePaint)
        strokeSecondsPaint.set(secondsPaint)
        inkStroke(strokePrimaryPaint, settings.clockStroke.color, settings.clockStroke.secondaryColor,
            settings.clockStroke, settings.clockOpacity, box, unit)
        inkStroke(strokeSecondsPaint, settings.clockStroke.secondsColor, settings.clockStroke.secondaryColor,
            settings.clockStroke, settings.clockOpacity, box, unit)
        val strokeEnabled = settings.clockStroke.enabled

        fun drawRows(canvas: Canvas, lines: List<Line>, fill: Paint, stroke: Paint) {
            if (strokeEnabled) lines.forEach { canvas.drawText(it.text, it.centreX, it.baseline, stroke) }
            lines.forEach { canvas.drawText(it.text, it.centreX, it.baseline, fill) }
        }
        val hourPrepared = preparedLayer(box, pad, settings.clockTransform) { canvas ->
            drawRows(canvas, hourLines, timePaint, strokePrimaryPaint)
        }
        val minutePrepared = preparedLayer(box, pad, settings.clockTransform) { canvas ->
            drawRows(canvas, listOf(minuteLine), timePaint, strokePrimaryPaint)
            if (secondLine != null) drawRows(canvas, listOf(secondLine), secondsPaint, strokeSecondsPaint)
        }
        return hourPrepared to minutePrepared
    }

    private fun prepareDateLayer(w: Float, h: Float, now: Date): PreparedLayer? {
        if (!settings.showDate) return null
        val locale = locale()
        val raw = try {
            SimpleDateFormat(DateFormats.pattern(settings.dateFormat), locale).format(now)
        } catch (_: IllegalArgumentException) {
            ""
        }
        val text = if (settings.dateUppercase) raw.uppercase(locale) else raw
        if (text.isEmpty()) return null

        val unit = unitScale(w, h)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)
        val faceId = if (settings.dateFont == FontCatalog.FOLLOW_CLOCK) settings.font else settings.dateFont

        datePaint.typeface = face(faceId)
        datePaint.textSize = settings.dateSizeSp * unit
        datePaint.letterSpacing = settings.dateLetterSpacing / 100f
        fitWidth(datePaint, text, availableWidth)
        val fm = datePaint.fontMetrics
        val blockHeight = fm.descent - fm.ascent
        val contentW = datePaint.measureText(text)
        val top = (h * settings.datePositionY / 100f - blockHeight / 2f).coerceIn(0f, max(0f, h - blockHeight))
        val centre = lineCentre(w, settings.datePositionX, contentW, settings.dateAlign)
        val baseline = top - fm.ascent
        val box = RectF(centre - contentW / 2f, top, centre + contentW / 2f, top + blockHeight)
        val pad = padFor(settings.dateStroke, settings.dateShadow, unit)

        ink(datePaint, settings.dateResolutionColor, settings.dateSecondaryColor, settings.dateGradientEnabled,
            settings.dateGradientAngle, settings.dateOpacity, box, settings.dateShadow, 2f * unit, 2f * unit, unit)
        strokePrimaryPaint.set(datePaint)
        inkStroke(strokePrimaryPaint, settings.dateStroke.color, settings.dateStroke.secondaryColor,
            settings.dateStroke, settings.dateOpacity, box, unit)
        val strokeEnabled = settings.dateStroke.enabled

        return preparedLayer(box, pad, settings.dateTransform) { canvas ->
            if (strokeEnabled) canvas.drawText(text, centre, baseline, strokePrimaryPaint)
            canvas.drawText(text, centre, baseline, datePaint)
        }
    }

    private fun prepareTextLayer(w: Float, h: Float): PreparedLayer? {
        val text = settings.text
        if (!text.enabled) return null
        val content = text.content.trim()
        if (content.isEmpty()) return null

        val unit = unitScale(w, h)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)
        textPaint.typeface = face(text.font)
        textPaint.textSize = text.sizeSp * unit
        textPaint.letterSpacing = text.letterSpacing / 100f
        fitWidth(textPaint, content, availableWidth)
        val fm = textPaint.fontMetrics
        val blockHeight = fm.descent - fm.ascent
        val contentW = textPaint.measureText(content)
        val top = (h * text.positionY / 100f - blockHeight / 2f).coerceIn(0f, max(0f, h - blockHeight))
        val centre = lineCentre(w, text.positionX, contentW, text.align)
        val baseline = top - fm.ascent
        val box = RectF(centre - contentW / 2f, top, centre + contentW / 2f, top + blockHeight)
        val pad = padFor(text.stroke, text.shadow, unit)

        ink(textPaint, text.color, text.secondaryColor, text.gradientEnabled, text.gradientAngle,
            text.opacity, box, text.shadow, 2f * unit, 2f * unit, unit)
        strokePrimaryPaint.set(textPaint)
        inkStroke(strokePrimaryPaint, text.stroke.color, text.stroke.secondaryColor,
            text.stroke, text.opacity, box, unit)
        val strokeEnabled = text.stroke.enabled

        return preparedLayer(box, pad, text.transform) { canvas ->
            if (strokeEnabled) canvas.drawText(content, centre, baseline, strokePrimaryPaint)
            canvas.drawText(content, centre, baseline, textPaint)
        }
    }

    /**
     * Draws one clock line (stroke pass under fill pass) at absolute coordinates. The caller owns
     * any clipping; both passes share the same StaticLayout geometry.
     */
    private fun drawStaticLine(
        canvas: Canvas,
        time: ClockText,
        centre: Float,
        baseline: Float,
        fill: Paint,
        stroke: Paint,
        strokeEnabled: Boolean,
    ) {
        val fillText = TextPaint(fill)
        val layoutWidth = layoutWidthFor(fill, time.text)
        val fillLayout = buildLayout(time.text, fillText, layoutWidth)
        val lineCenter = (fillLayout.getLineLeft(0) + fillLayout.getLineRight(0)) / 2f
        val dx = centre - lineCenter
        val dy = baseline - fillLayout.getLineBaseline(0)
        // Gradients are built in scene coordinates (the clock's text box), but the StaticLayout
        // is drawn after translating the canvas. Offset the shader's local matrix so the fill
        // and stroke gradients stay over their glyphs instead of clamping to one end colour.
        alignShaderToTranslation(fill, dx, dy)
        if (strokeEnabled) alignShaderToTranslation(stroke, dx, dy)
        val save = canvas.save()
        canvas.translate(dx, dy)
        if (strokeEnabled) {
            val strokeText = TextPaint(stroke)
            val strokeLayout = buildLayout(time.text, strokeText, layoutWidthFor(stroke, time.text))
            strokeLayout.draw(canvas)
        }
        fillLayout.draw(canvas)
        canvas.restoreToCount(save)
    }

    /** Keeps a gradient shader pinned to scene coordinates across a canvas translation. */
    private fun alignShaderToTranslation(paint: Paint, dx: Float, dy: Float) {
        val shader = paint.shader ?: return
        shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-dx, -dy) })
    }

    private fun layoutWidthFor(paint: Paint, text: String): Int =
        ceil(paint.measureText(text).toDouble() * 2.0 + 16.0).toInt().coerceAtLeast(1)

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setMaxLines(1)
            .build()

    /** Selection bounds for the clip passes, expressed in absolute canvas coordinates. */
    private fun selectionBounds(time: ClockText, paint: Paint, start: Int, end: Int, centre: Float, baseline: Float): RectF? {
        val textPaint = TextPaint(paint)
        val layout = buildLayout(time.text, textPaint, layoutWidthFor(paint, time.text))
        val path = Path()
        layout.getSelectionPath(start, end, path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() <= 0f) return null
        val lineCenter = (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
        bounds.offset(centre - lineCenter, baseline - layout.getLineBaseline(0))
        return bounds
    }

    private fun preparedLayer(box: RectF, pad: Float, transform: TransformSettings, draw: (Canvas) -> Unit): PreparedLayer {
        val originX = box.left - pad
        val originY = box.top - pad
        return PreparedLayer(
            originX = originX,
            originY = originY,
            bitmapW = max(1, ceil(box.width() + 2 * pad).toInt()),
            bitmapH = max(1, ceil(box.height() + 2 * pad).toInt()),
            anchorX = box.centerX(),
            anchorY = box.centerY(),
            contentW = box.width(),
            contentH = box.height(),
            transform = transform,
            draw = draw,
        )
    }

    private fun locale(): Locale = resources.configuration.locales.get(0) ?: Locale.getDefault()

    /** Sp -> px, scaled down when this scene is the small on-screen preview rather than a screen. */
    private fun unitScale(w: Float, h: Float): Float {
        val screen = resources.displayMetrics
        val previewScale = if (miniature) min(w / screen.widthPixels, h / screen.heightPixels).coerceAtMost(1f) else 1f
        return screen.scaledDensity * previewScale
    }

    private fun fitWidth(paint: Paint, text: String, availableWidth: Float) {
        if (text.isEmpty()) return
        val measured = paint.measureText(text)
        if (measured > availableWidth && measured > 0f) paint.textSize *= availableWidth / measured
    }

    /**
     * Honours both the 0-100 position and the layer's alignment while keeping the text on screen.
     * The position is the anchor: left starts there, right ends there, centre is centred on it.
     */
    private fun lineCentre(w: Float, positionPercent: Int, textWidth: Float, align: TextAlignment): Float {
        val anchor = w * positionPercent / 100f
        val centre = when (align) {
            TextAlignment.CENTER -> anchor
            TextAlignment.LEFT -> anchor + textWidth / 2f
            TextAlignment.RIGHT -> anchor - textWidth / 2f
        }
        val halfWidth = textWidth / 2f
        return centre.coerceIn(min(w / 2f, halfWidth), max(w / 2f, w - halfWidth))
    }

    private fun localeClock(now: Date): ClockText {
        val locale = locale()
        val pattern = DateFormat.getBestDateTimePattern(locale, if (use24Hour()) "Hm" else "hm")
        return formatClock(SimpleDateFormat(pattern, locale), now)
    }

    /** An explicit format from the structure file; the hour is always the first glyphs. */
    private fun explicitClock(format: ClockFormat, now: Date): ClockText {
        val locale = locale()
        val hourPattern = if (use24Hour()) (if (format.padHour) "HH" else "H") else (if (format.padHour) "hh" else "h")
        val hour = SimpleDateFormat(hourPattern, locale).format(now)
        val minute = SimpleDateFormat("mm", locale).format(now)
        val builder = StringBuilder(hour).append(format.separator).append(minute)
        var secondsStart = -1
        var secondsEnd = -1
        if (format.withSeconds) {
            builder.append(format.secondsSeparator)
            secondsStart = builder.length
            builder.append(SimpleDateFormat("ss", locale).format(now))
            secondsEnd = builder.length
        }
        builder.append(amPmSuffix(locale, now))
        return ClockText(builder.toString(), 0, hour.length, secondsStart, secondsEnd)
    }

    /** 12-hour formats would be ambiguous without the marker, so it travels with the minute. */
    private fun amPmSuffix(locale: Locale, now: Date): String =
        if (use24Hour()) "" else " " + SimpleDateFormat("a", locale).format(now)

    private fun use24Hour(): Boolean = when (settings.timeStyle) {
        TimeStyle.SYSTEM -> DateFormat.is24HourFormat(context)
        TimeStyle.TWELVE_HOUR -> false
        TimeStyle.TWENTY_FOUR_HOUR -> true
    }

    private fun face(id: String?): Typeface = fontCache.getOrPut(id ?: FontCatalog.DEFAULT_ID) {
        FontCatalog.typeface(context, id)
    }

    companion object {
        private const val MAX_CLIP = 100_000f
        private const val OVERSCAN = 1.15f
        private const val PARALLAX_FRAC_X = 0.055f
        private const val PARALLAX_FRAC_Y = 0.045f
        private const val FRAME_INTERVAL_MS = 33L
        private const val TILT_RETURN = 0.82f
        private const val TILT_EPSILON = 0.015f
        /** The date trails the clock by this much during an entrance transition. */
        private const val DATE_STAGGER_MS = 90L
        private const val GLITCH_BANDS = 6
        private const val GLITCH_SLICE_FRACTION = 0.05f
        private const val PART_CLOCK_HOUR = 0
        private const val PART_CLOCK_MINUTE = 1
        private const val PART_DATE = 2
        private const val PART_TEXT = 3
        private const val PART_PRIMARY = 0
        private const val PART_SECONDS = 1

        private val FOREGROUND_DARKEN = floatArrayOf(
            217f / 255f, 0f, 0f, 0f, 0f,
            0f, 217f / 255f, 0f, 0f, 0f,
            0f, 0f, 217f / 255f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        private val BASE_FOREGROUND_FILTER = ColorMatrixColorFilter(FOREGROUND_DARKEN)
    }
}
