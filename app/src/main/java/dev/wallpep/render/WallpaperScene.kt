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
import dev.wallpep.model.ClockFormat
import dev.wallpep.model.ClockFormats
import dev.wallpep.model.DateFormats
import dev.wallpep.model.TextAlignment
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
 * A View drawn to an Engine's SurfaceHolder, or into the editor's live preview and collection
 * thumbnails.
 *
 * The clock and the date are independent layers: each has its own face, size, letter spacing,
 * colour, opacity, alignment and position, and each can be flipped in front of the cutout
 * subject. Clock formats can stack the hour, minute and seconds on their own rows.
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
        val clock = if (settings.showClock) prepareClock(w, h, now) else null
        val date = if (settings.showDate) prepareDate(w, h, now) else null
        val foreground = foregroundBitmap?.takeUnless { it.isRecycled }
        // Without a usable foreground this is exactly the Phase 1 photo -> clock/date order.
        val plan = depthPlan(settings, destination != null && foreground != null)
        if (clock != null) drawClock(canvas, clock, hour = plan.hourBefore, minute = plan.minuteBefore)
        if (date != null && plan.dateBefore) drawDate(canvas, date)
        if (plan.foreground && destination != null && foreground != null) {
            canvas.drawBitmap(foreground, null, destination, foregroundPaint)
        }
        if (clock != null) drawClock(canvas, clock, hour = plan.hourAfter, minute = plan.minuteAfter)
        if (date != null && plan.dateAfter) drawDate(canvas, date)
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
        fillPaint.shader = LinearGradient(0f, 0f, w, h, 0xff0b1220.toInt(), 0xff05060a.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
        fillPaint.color = 0x2E2563EB
        canvas.drawCircle(w * 0.82f, h * 0.78f, w * 0.58f, fillPaint)
        fillPaint.color = 0x3A2563EB
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

    /** One row of a text layer: the text, its centre on the x axis, and its baseline. */
    private class Line(val text: String, val centreX: Float, val baseline: Float)

    private sealed interface ClockLayout {
        /** A single row; [hourStart]/[hourEnd] locate the hour so it can be layered on its own. */
        class Single(val time: ClockText, val centreX: Float, val baseline: Float) : ClockLayout

        /** Separate hour, minute (and optional seconds) rows, as the vertical formats ask for. */
        class Stacked(val hour: List<Line>, val minute: List<Line>) : ClockLayout
    }

    private fun prepareClock(w: Float, h: Float, now: Date): ClockLayout {
        val unit = unitScale(w, h)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)

        timePaint.typeface = face(settings.font)
        timePaint.textSize = settings.clockSizeSp * unit
        timePaint.letterSpacing = settings.clockLetterSpacing / 100f
        ink(timePaint, settings.textColor, settings.clockOpacity, 3f * unit, unit)

        val format = ClockFormats.format(settings.clockFormat)
        if (format != null && format.stacked) return stackedClock(w, h, unit, availableWidth, format, now)

        val time = if (format == null) localeClock(now) else explicitClock(format, now)
        fitWidth(timePaint, time.text, availableWidth)
        val fm = timePaint.fontMetrics
        val blockHeight = fm.descent - fm.ascent
        val top = (h * settings.positionY / 100f - blockHeight / 2f).coerceIn(0f, max(0f, h - blockHeight))
        val centre = lineCentre(w, settings.positionX, timePaint.measureText(time.text), settings.clockAlign)
        return ClockLayout.Single(time, centre, top - fm.ascent)
    }

    private fun stackedClock(
        w: Float,
        h: Float,
        unit: Float,
        availableWidth: Float,
        format: ClockFormat,
        now: Date,
    ): ClockLayout {
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
        val minuteLines = listOf(line(minuteText, 1)) + if (secondText != null) listOf(line(secondText, 2)) else emptyList()
        return ClockLayout.Stacked(hourLines, minuteLines)
    }

    /** The locale's own hour/minute pattern: exactly what this app rendered before formats. */
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
        if (format.withSeconds) builder.append(' ').append(SimpleDateFormat("ss", locale).format(now))
        builder.append(amPmSuffix(locale, now))
        return ClockText(builder.toString(), 0, hour.length)
    }

    /** 12-hour formats would be ambiguous without the marker, so it travels with the minute. */
    private fun amPmSuffix(locale: Locale, now: Date): String =
        if (use24Hour()) "" else " " + SimpleDateFormat("a", locale).format(now)

    private fun use24Hour(): Boolean = when (settings.timeStyle) {
        TimeStyle.SYSTEM -> DateFormat.is24HourFormat(context)
        TimeStyle.TWELVE_HOUR -> false
        TimeStyle.TWENTY_FOUR_HOUR -> true
    }

    private fun prepareDate(w: Float, h: Float, now: Date): Line {
        val locale = locale()
        val raw = try {
            SimpleDateFormat(DateFormats.pattern(settings.dateFormat), locale).format(now)
        } catch (_: IllegalArgumentException) {
            ""
        }
        val text = if (settings.dateUppercase) raw.uppercase(locale) else raw
        val unit = unitScale(w, h)
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)
        val faceId = if (settings.dateFont == FontCatalog.FOLLOW_CLOCK) settings.font else settings.dateFont

        datePaint.typeface = face(faceId)
        datePaint.textSize = settings.dateSizeSp * unit
        datePaint.letterSpacing = settings.dateLetterSpacing / 100f
        ink(datePaint, settings.dateResolutionColor, settings.dateOpacity, 2f * unit, unit)
        fitWidth(datePaint, text, availableWidth)

        val fm = datePaint.fontMetrics
        val blockHeight = fm.descent - fm.ascent
        val top = (h * settings.datePositionY / 100f - blockHeight / 2f).coerceIn(0f, max(0f, h - blockHeight))
        val centre = lineCentre(w, settings.datePositionX, datePaint.measureText(text), settings.dateAlign)
        return Line(text, centre, top - fm.ascent)
    }

    private fun drawDate(canvas: Canvas, date: Line) {
        if (date.text.isNotEmpty()) canvas.drawText(date.text, date.centreX, date.baseline, datePaint)
    }

    /** Each part is drawn only in the pass its depth switch selects, so it is drawn once. */
    private fun drawClock(canvas: Canvas, clock: ClockLayout, hour: Boolean, minute: Boolean) {
        when (clock) {
            is ClockLayout.Single -> drawTime(canvas, clock, hour, minute)
            is ClockLayout.Stacked -> {
                if (hour) clock.hour.forEach { canvas.drawText(it.text, it.centreX, it.baseline, timePaint) }
                if (minute) clock.minute.forEach { canvas.drawText(it.text, it.centreX, it.baseline, timePaint) }
            }
        }
    }

    private fun drawTime(canvas: Canvas, clock: ClockLayout.Single, hour: Boolean, minute: Boolean) {
        if (!hour && !minute) return
        val time = clock.time
        if (hour && minute) {
            // The common case remains the same formatting/draw call as Phase 1.
            canvas.drawText(time.text, clock.centreX, clock.baseline, timePaint)
            return
        }
        if (!time.hasHour) {
            // Unrecognized locale pattern: render the whole clock with the minute, never drop it.
            if (minute) canvas.drawText(time.text, clock.centreX, clock.baseline, timePaint)
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
            if (minute) canvas.drawText(time.text, clock.centreX, clock.baseline, timePaint)
            return
        }
        val save = canvas.save()
        val lineCenter = (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
        canvas.translate(clock.centreX - lineCenter, clock.baseline - layout.getLineBaseline(0))
        if (hour) {
            canvas.clipRect(selection.left, -MAX_CLIP, selection.right, MAX_CLIP)
        } else {
            canvas.clipOutRect(selection.left, -MAX_CLIP, selection.right, MAX_CLIP)
        }
        layout.draw(canvas)
        canvas.restoreToCount(save)
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
