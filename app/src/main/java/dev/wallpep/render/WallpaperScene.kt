package dev.wallpep.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A View drawn to an Engine's SurfaceHolder, or into the settings screen's small preview. */
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
    private val fontCache = mutableMapOf<String, Typeface>()

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
        val destination = drawBackground(canvas, w, h)
        val clock = prepareClock(w, h)
        val foreground = foregroundBitmap?.takeUnless { it.isRecycled }
        // Without a usable foreground this is exactly the Phase 1 photo -> clock/date order.
        val plan = depthPlan(settings, destination != null && foreground != null)
        drawTime(canvas, clock, hour = plan.hourBefore, minute = plan.minuteBefore)
        if (plan.dateBefore) drawDate(canvas, clock)
        if (plan.foreground && destination != null && foreground != null) {
            canvas.drawBitmap(foreground, null, destination, foregroundPaint)
        }
        drawTime(canvas, clock, hour = plan.hourAfter, minute = plan.minuteAfter)
        if (plan.dateAfter) drawDate(canvas, clock)
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

    private data class ClockDrawing(
        val time: ClockText,
        val date: String,
        val x: Float,
        val timeBaseline: Float,
        val dateBaseline: Float,
    )

    private fun prepareClock(w: Float, h: Float): ClockDrawing {
        val locale = resources.configuration.locales.get(0) ?: Locale.getDefault()
        val now = Date()
        val use24 = when (settings.timeStyle) {
            TimeStyle.SYSTEM -> DateFormat.is24HourFormat(context)
            TimeStyle.TWELVE_HOUR -> false
            TimeStyle.TWENTY_FOUR_HOUR -> true
        }
        val pattern = DateFormat.getBestDateTimePattern(locale, if (use24) "Hm" else "hm")
        val time = formatClock(SimpleDateFormat(pattern, locale), now)
        val date = when (settings.dateStyle) {
            DateStyle.FULL -> format(locale, "EEEEMMMMd", now)
            DateStyle.SHORT -> format(locale, "EEEMMMd", now)
            DateStyle.HIDDEN -> ""
        }
        val screen = resources.displayMetrics
        val previewScale = if (miniature) min(w / screen.widthPixels, h / screen.heightPixels).coerceAtMost(1f) else 1f
        val unit = screen.scaledDensity * previewScale
        val clockFace = face(settings.font)
        val dateFace = face(if (settings.dateFont == FontCatalog.FOLLOW_CLOCK) settings.font else settings.dateFont)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)

        timePaint.typeface = clockFace
        timePaint.color = settings.textColor
        timePaint.textSize = settings.clockSizeSp * unit
        timePaint.setShadowLayer(3f * unit, 0f, unit, 0x99000000.toInt())
        val measuredTime = timePaint.measureText(time.text)
        if (measuredTime > availableWidth) timePaint.textSize *= availableWidth / measuredTime

        datePaint.typeface = dateFace
        datePaint.color = settings.textColor
        datePaint.textSize = max(10f * unit, timePaint.textSize * 0.23f)
        datePaint.setShadowLayer(2f * unit, 0f, unit, 0x99000000.toInt())
        val measuredDate = datePaint.measureText(date)
        if (measuredDate > availableWidth) datePaint.textSize *= availableWidth / measuredDate

        val fm = timePaint.fontMetrics
        val dateFm = datePaint.fontMetrics
        val gap = if (date.isEmpty()) 0f else 5f * unit
        val totalHeight = (fm.descent - fm.ascent) + if (date.isEmpty()) 0f else gap + dateFm.descent - dateFm.ascent
        val desiredCenterY = h * settings.positionY / 100f
        val top = (desiredCenterY - totalHeight / 2f).coerceIn(0f, max(0f, h - totalHeight))
        val halfWidth = max(timePaint.measureText(time.text), datePaint.measureText(date)) / 2f
        val x = (w * settings.positionX / 100f).coerceIn(min(w / 2f, halfWidth), max(w / 2f, w - halfWidth))
        val timeBaseline = top - fm.ascent
        return ClockDrawing(time, date, x, timeBaseline, timeBaseline + fm.descent + gap - dateFm.ascent)
    }

    private fun drawDate(canvas: Canvas, clock: ClockDrawing) {
        if (clock.date.isNotEmpty()) canvas.drawText(clock.date, clock.x, clock.dateBaseline, datePaint)
    }

    private fun drawTime(canvas: Canvas, clock: ClockDrawing, hour: Boolean, minute: Boolean) {
        if (!hour && !minute) return
        val time = clock.time
        if (hour && minute) {
            // The common case remains byte-for-byte the same formatting/draw call as Phase 1.
            canvas.drawText(time.text, clock.x, clock.timeBaseline, timePaint)
            return
        }
        if (!time.hasHour) {
            // Unrecognized locale pattern: render the whole clock with the minute, never drop it.
            if (minute) canvas.drawText(time.text, clock.x, clock.timeBaseline, timePaint)
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
            if (minute) canvas.drawText(time.text, clock.x, clock.timeBaseline, timePaint)
            return
        }
        val save = canvas.save()
        val lineCenter = (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
        canvas.translate(clock.x - lineCenter, clock.timeBaseline - layout.getLineBaseline(0))
        if (hour) {
            canvas.clipRect(selection.left, -MAX_CLIP, selection.right, MAX_CLIP)
        } else {
            canvas.clipOutRect(selection.left, -MAX_CLIP, selection.right, MAX_CLIP)
        }
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun format(locale: Locale, skeleton: String, date: Date): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).format(date)
    }

    /** One loaded face per id per scene; the picker never grows this beyond the two in use. */
    private fun face(id: String?): Typeface = fontCache.getOrPut(id ?: FontCatalog.DEFAULT_ID) {
        FontCatalog.typeface(context, id)
    }

    companion object { private const val MAX_CLIP = 100_000f }
}
