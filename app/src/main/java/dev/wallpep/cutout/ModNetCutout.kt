package dev.wallpep.cutout

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.system.Os
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One CPU inference per photo; never called from a draw callback. */
internal class ModNetCutout(context: Context) {
    private val app = context.applicationContext

    fun createForeground(photo: Bitmap): Bitmap {
        val size = modelSize(photo.width, photo.height)
        val scaled = Bitmap.createScaledBitmap(photo, size.width, size.height, true)
        val pixels = IntArray(size.width * size.height)
        try {
            scaled.getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
        } finally {
            if (scaled !== photo) scaled.recycle()
        }

        // MODNet expects RGB, NCHW float32, normalized from [0,255] to [-1,1].
        val plane = pixels.size
        val input = ByteBuffer.allocateDirect(plane * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in pixels.indices) {
            val color = pixels[i]
            input.put(i, normalizedChannel((color ushr 16) and 0xff))
            input.put(plane + i, normalizedChannel((color ushr 8) and 0xff))
            input.put(2 * plane + i, normalizedChannel(color and 0xff))
        }

        // Set the process opt-out before ORT is initialized, then explicitly disable its events.
        // The pinned 1.22 Android/POSIX implementation is a no-op; newer versions require review.
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        val model = app.assets.open(MODEL_ASSET).use { it.readBytes() }
        var options = options(preferXnnpack = true)
        try {
            val session = try {
                environment.createSession(model, options)
            } catch (_: OrtException) {
                // Some devices/builds cannot initialize XNNPACK. Retry session creation on CPU;
                // the model itself is still evaluated only once.
                options.close()
                options = options(preferXnnpack = false)
                environment.createSession(model, options)
            }
            session.use {
                OnnxTensor.createTensor(
                    environment, input, longArrayOf(1, 3, size.height.toLong(), size.width.toLong()),
                ).use { tensor ->
                    session.run(mapOf("input" to tensor)).use { result ->
                        val matte = result[0] as? OnnxTensor ?: error("Unexpected MODNet output")
                        val shape = matte.info.shape
                        check(shape.contentEquals(longArrayOf(1, 1, size.height.toLong(), size.width.toLong())))
                        val values = matte.floatBuffer ?: error("Missing MODNet matte")
                        check(values.remaining() == plane)
                        for (i in pixels.indices) {
                            pixels[i] = (matteAlpha(values.get()) shl 24) or 0x00ffffff
                        }
                    }
                }
            }
        } finally {
            options.close()
        }

        val mask = Bitmap.createBitmap(pixels, size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            val foreground = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(foreground)
                canvas.drawBitmap(photo, 0f, 0f, null)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                // Filtered scaling provides a light feather while retaining the continuous alpha.
                canvas.drawBitmap(mask, null, RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()), paint)
                paint.xfermode = null
                return foreground
            } catch (failure: Throwable) {
                foreground.recycle()
                throw failure
            }
        } finally {
            mask.recycle()
        }
    }

    private fun options(preferXnnpack: Boolean): OrtSession.SessionOptions {
        val result = OrtSession.SessionOptions()
        try {
            result.setInterOpNumThreads(1)
            // Avoid spinning a thread pool while the one-shot inference is idle.
            result.addConfigEntry("session.intra_op.allow_spinning", "0")
            if (preferXnnpack) {
                try {
                    result.setIntraOpNumThreads(1)
                    result.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                } catch (_: OrtException) {
                    result.setIntraOpNumThreads(2)
                }
            } else {
                result.setIntraOpNumThreads(2)
            }
            return result
        } catch (failure: Throwable) {
            result.close()
            throw failure
        }
    }

    companion object {
        private const val MODEL_ASSET = "models/modnet_photographic_portrait_matting.onnx"
    }
}
