package dev.wallpep.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import dev.wallpep.model.DateStyle
import dev.wallpep.model.FontChoice
import dev.wallpep.model.TimeStyle
import dev.wallpep.model.WallpaperSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A View drawn to an Engine's SurfaceHolder, or into the settings screen's small preview. */
class WallpaperScene(context: Context, private val miniature: Boolean = false) : View(context) {
    var settings: WallpaperSettings = WallpaperSettings()
        set(value) { field = value.sanitized(); invalidate() }

    private var backgroundBitmap: Bitmap? = null
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fontCache = mutableMapOf<FontChoice, Typeface>()

    /** Ownership transfers to this scene. The previous engine-local bitmap is released. */
    fun setBackgroundBitmap(bitmap: Bitmap?) {
        if (backgroundBitmap !== bitmap) {
            backgroundBitmap?.recycle()
            backgroundBitmap = bitmap
        }
        invalidate()
    }

    fun release() = setBackgroundBitmap(null)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawBackground(canvas, w, h)
        drawClock(canvas, w, h)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val image = backgroundBitmap
        if (image != null && !image.isRecycled) {
            val scale = max(w / image.width, h / image.height)
            val dw = image.width * scale
            val dh = image.height * scale
            val destination = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            canvas.drawBitmap(image, null, destination, imagePaint)
            canvas.drawColor(0x26000000)
        } else {
            // A synthetic, code-drawn fallback; no copyrighted or personal photo is bundled.
            fillPaint.shader = LinearGradient(0f, 0f, w, h, 0xff263f34.toInt(), 0xff111c19.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, fillPaint)
            fillPaint.shader = null
            fillPaint.color = 0x362f5946
            canvas.drawCircle(w * 0.82f, h * 0.78f, w * 0.58f, fillPaint)
            fillPaint.color = 0x40234235
            val hill = Path().apply {
                moveTo(0f, h * 0.83f)
                cubicTo(w * 0.3f, h * 0.64f, w * 0.65f, h * 0.93f, w, h * 0.70f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            canvas.drawPath(hill, fillPaint)
        }
    }

    private fun drawClock(canvas: Canvas, w: Float, h: Float) {
        val locale = resources.configuration.locales.get(0) ?: Locale.getDefault()
        val now = Date()
        val use24 = when (settings.timeStyle) {
            TimeStyle.SYSTEM -> DateFormat.is24HourFormat(context)
            TimeStyle.TWELVE_HOUR -> false
            TimeStyle.TWENTY_FOUR_HOUR -> true
        }
        val time = format(locale, if (use24) "Hm" else "hm", now)
        val date = when (settings.dateStyle) {
            DateStyle.FULL -> format(locale, "EEEEMMMMd", now)
            DateStyle.SHORT -> format(locale, "EEEMMMd", now)
            DateStyle.HIDDEN -> ""
        }
        val screen = resources.displayMetrics
        val previewScale = if (miniature) min(w / screen.widthPixels, h / screen.heightPixels).coerceAtMost(1f) else 1f
        val unit = screen.scaledDensity * previewScale
        val font = fontCache.getOrPut(settings.font) { loadFont(settings.font) }
        val availableWidth = (w * 0.90f).coerceAtLeast(1f)

        timePaint.typeface = font
        timePaint.color = settings.textColor
        timePaint.textSize = settings.clockSizeSp * unit
        timePaint.setShadowLayer(3f * unit, 0f, unit, 0x99000000.toInt())
        val measuredTime = timePaint.measureText(time)
        if (measuredTime > availableWidth) timePaint.textSize *= availableWidth / measuredTime

        datePaint.typeface = font
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
        val halfWidth = max(timePaint.measureText(time), datePaint.measureText(date)) / 2f
        val x = (w * settings.positionX / 100f).coerceIn(min(w / 2f, halfWidth), max(w / 2f, w - halfWidth))
        val timeBaseline = top - fm.ascent
        canvas.drawText(time, x, timeBaseline, timePaint)
        if (date.isNotEmpty()) {
            val dateBaseline = timeBaseline + fm.descent + gap - dateFm.ascent
            canvas.drawText(date, x, dateBaseline, datePaint)
        }
    }

    private fun format(locale: Locale, skeleton: String, date: Date): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).format(date)
    }

    private fun loadFont(choice: FontChoice): Typeface = try {
        when (choice) {
            FontChoice.OUTFIT -> Typeface.createFromAsset(context.assets, "fonts/outfit.ttf")
            FontChoice.DM_SANS -> Typeface.createFromAsset(context.assets, "fonts/dm_sans.ttf")
            FontChoice.SYSTEM -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    } catch (_: RuntimeException) {
        Typeface.DEFAULT
    }
}
