package dev.wallpep.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.View
import dev.wallpep.fonts.FontCatalog
import dev.wallpep.model.DateStyle
import dev.wallpep.model.TimeStyle
import dev.wallpep.model.WallpaperSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A View drawn to an Engine's SurfaceHolder, or into the editor's full-width live preview.
 *
 * The clock and the date are independent layers: each has its own face, size, letter spacing,
 * colour, opacity and position, and each can be flipped in front of the cutout subject.
 */
class WallpaperScene(context: Context, private val miniature: Boolean = false) : View(context) {
    var settings: WallpaperSettings = WallpaperSettings()
        set(value) { field = value.sanitized(); invalidate() }

    private var backgroundBitmap: Bitmap? = null
    private var foregroundBitmap: Bitmap? = null
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Match Phase 1's 0x26 black scrim on the foreground too, so the two copies line up in color.
    private val foregroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        val light = 217f / 255f
        colorFilter = ColorMatrixColorFilter(floatArrayOf(
            light, 0f, 0f, 0f, 0f,
            0f, light, 0f, 0f, 0f,
            0f, 0f, light, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    // Only the clock and date faces are ever in use; a tiny LRU keeps repeated face changes
    // from accumulating every family the user tries in the editor preview.
    private val fontCache = object : LinkedHashMap<String, Typeface>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean = size > 4
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
        invalidate()
    }

    fun release() = setImages(null, null)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = Date()
        val destination = drawBackground(canvas, w, h)
        val clock = prepareClock(w, h, now)
        val date = prepareDate(w, h, now)
        val foreground = foregroundBitmap?.takeUnless { it.isRecycled }
        // Without a usable foreground this is exactly the Phase 1 photo -> clock/date order.
        val plan = depthPlan(settings, destination != null && foreground != null)
        if (settings.showClock) drawTime(canvas, clock, hour = plan.hourBefore, minute = plan.minuteBefore)
        if (plan.dateBefore) drawDate(canvas, date)
        if (plan.foreground && destination != null && foreground != null) {
            canvas.drawBitmap(foreground, null, destination, foregroundPaint)
        }
        if (settings.showClock) drawTime(canvas, clock, hour = plan.hourAfter, minute = plan.minuteAfter)
        if (plan.dateAfter) drawDate(canvas, date)
    }

    /** Returns the exact photo destination so the cached foreground uses the identical crop. */
    private fun drawBackground(canvas: Canvas, w: Float, h: Float): RectF? {
        val image = backgroundBitmap
        if (image != null && !image.isRecycled) {
            val scale = max(w / image.width, h / image.height)
            val dw = image.width * scale
            val dh = image.height * scale
            val destination = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            canvas.drawBitmap(image, null, destination, imagePaint)
            canvas.drawColor(0x26000000)
            return destination
        }
        // A synthetic, code-drawn fallback; no copyrighted or personal photo is bundled.
        fillPaint.shader = LinearGradient(0f, 0f, w, h, 0xff1a1408.toInt(), 0xff000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
        fillPaint.color = 0x2ED4AF37
        canvas.drawCircle(w * 0.82f, h * 0.78f, w * 0.58f, fillPaint)
        fillPaint.color = 0x3AD4AF37
        val hill = Path().apply {
            moveTo(0f, h * 0.83f)
            cubicTo(w * 0.3f, h * 0.64f, w * 0.65f, h * 0.93f, w, h * 0.70f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.drawPath(hill, fillPaint)
        return null
    }

    private data class ClockDrawing(val time: ClockText, val x: Float, val baseline: Float)
    private data class DateDrawing(val text: String, val x: Float, val baseline: Float)

    private fun prepareClock(w: Float, h: Float, now: Date): ClockDrawing {
        val locale = locale()
        val use24 = when (settings.timeStyle) {
            TimeStyle.SYSTEM -> DateFormat.is24HourFormat(context)
            TimeStyle.TWELVE_HOUR -> false
            TimeStyle.TWENTY_FOUR_HOUR -> true
        }
        val pattern = DateFormat.getBestDateTimePattern(locale, if (use24) "Hm" else "hm")
        val time = formatClock(SimpleDateFormat(pattern, locale), now)
        val unit = unitScale(w, h)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)

        timePaint.typeface = face(settings.font)
        timePaint.textSize = settings.clockSizeSp * unit
        timePaint.letterSpacing = settings.clockLetterSpacing / 100f
        ink(timePaint, settings.textColor, settings.clockOpacity, 3f * unit, unit)
        fitWidth(timePaint, time.text, availableWidth)

        val fm = timePaint.fontMetrics
        val blockHeight = fm.descent - fm.ascent
        val top = (h * settings.positionY / 100f - blockHeight / 2f).coerceIn(0f, max(0f, h - blockHeight))
        return ClockDrawing(time, centreX(w, settings.positionX, timePaint.measureText(time.text)), top - fm.ascent)
    }

    private fun prepareDate(w: Float, h: Float, now: Date): DateDrawing {
        val locale = locale()
        val raw = when (settings.dateStyle) {
            DateStyle.FULL -> format(locale, "EEEEMMMMd", now)
            DateStyle.SHORT -> format(locale, "EEEMMMd", now)
            DateStyle.HIDDEN -> ""
        }
        val text = if (raw.isEmpty() || !settings.dateUppercase) raw else raw.uppercase(locale)
        val unit = unitScale(w, h)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)
        val faceId = if (settings.dateFont == FontCatalog.FOLLOW_CLOCK) settings.font else settings.dateFont

        datePaint.typeface = face(faceId)
        datePaint.textSize = settings.dateSizeSp * unit
        datePaint.letterSpacing = settings.dateLetterSpacing / 100f
        ink(datePaint, settings.dateColor ?: settings.textColor, settings.dateOpacity, 2f * unit, unit)
        fitWidth(datePaint, text, availableWidth)

        val fm = datePaint.fontMetrics
        val blockHeight = fm.descent - fm.ascent
        val top = (h * settings.datePositionY / 100f - blockHeight / 2f).coerceIn(0f, max(0f, h - blockHeight))
        return DateDrawing(text, centreX(w, settings.datePositionX, datePaint.measureText(text)), top - fm.ascent)
    }

    private fun drawDate(canvas: Canvas, date: DateDrawing) {
        if (date.text.isNotEmpty()) canvas.drawText(date.text, date.x, date.baseline, datePaint)
    }

    private fun drawTime(canvas: Canvas, clock: ClockDrawing, hour: Boolean, minute: Boolean) {
        if (!hour && !minute) return
        val time = clock.time
        if (hour && minute) {
            // The common case remains byte-for-byte the same formatting/draw call as Phase 1.
            canvas.drawText(time.text, clock.x, clock.baseline, timePaint)
            return
        }
        if (!time.hasHour) {
            // Unrecognized locale pattern: render the whole clock with the minute, never drop it.
            if (minute) canvas.drawText(time.text, clock.x, clock.baseline, timePaint)
            return
        }

        // Draw the complete shaped/bidi line on both passes, clipped to its actual visual hour
        // range or its complement. Separators and AM/PM follow the minute. StaticLayout computes
        // the selection in visual rather than assumed left-to-right character order.
        val paint = TextPaint(timePaint).apply { textAlign = Paint.Align.LEFT }
        // Leave room for locale-dependent shaping/bidi widths so StaticLayout never wraps.
        val layoutWidth = ceil(timePaint.measureText(time.text).toDouble() * 2.0 + 16.0).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(time.text, 0, time.text.length, paint, layoutWidth)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setMaxLines(1)
            .build()
        val path = Path()
        layout.getSelectionPath(time.hourStart, time.hourEnd, path)
        val selection = RectF()
        path.computeBounds(selection, true)
        if (selection.width() <= 0f) {
            if (minute) canvas.drawText(time.text, clock.x, clock.baseline, timePaint)
            return
        }
        val save = canvas.save()
        val lineCenter = (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
        canvas.translate(clock.x - lineCenter, clock.baseline - layout.getLineBaseline(0))
        if (hour) {
            canvas.clipRect(selection.left, -MAX_CLIP, selection.right, MAX_CLIP)
        } else {
            canvas.clipOutRect(selection.left, -MAX_CLIP, selection.right, MAX_CLIP)
        }
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun locale(): Locale = resources.configuration.locales.get(0) ?: Locale.getDefault()

    private fun format(locale: Locale, skeleton: String, date: Date): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).format(date)
    }

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

    /** Keeps a text block inside the edges while honouring the 0-100 position setting. */
    private fun centreX(w: Float, positionPercent: Int, textWidth: Float): Float {
        val halfWidth = textWidth / 2f
        return (w * positionPercent / 100f).coerceIn(min(w / 2f, halfWidth), max(w / 2f, w - halfWidth))
    }

    /** Layer colour and its drop shadow, both scaled by the layer's opacity percent. */
    private fun ink(paint: Paint, color: Int, opacityPercent: Int, shadowRadius: Float, shadowDy: Float) {
        val alpha = (255f * opacityPercent.coerceIn(0, 100) / 100f).toInt()
        paint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        paint.setShadowLayer(shadowRadius, 0f, shadowDy, (0x99 * alpha / 255) shl 24)
    }

    /** One loaded face per id per scene; the editor never grows this beyond the faces in use. */
    private fun face(id: String?): Typeface = fontCache.getOrPut(id ?: FontCatalog.DEFAULT_ID) {
        FontCatalog.typeface(context, id)
    }

    companion object { private const val MAX_CLIP = 100_000f }
}
