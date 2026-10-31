package dev.wallpep.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dev.wallpep.model.isSafeBackgroundName
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Images are private, unshared, excluded from backup, and addressed only by generated names. */
class BackgroundStore(context: Context) {
    private val app = context.applicationContext
    private val directory: File get() = File(app.noBackupFilesDir, "wallpapers").also { it.mkdirs() }

    private fun imageFile(name: String?): File? =
        name?.takeIf(::isSafeBackgroundName)?.let { File(directory, it) }

    private fun cutoutFile(name: String?): File? =
        name?.let(::cutoutFileName)?.let { File(directory, it) }

    private fun failureFile(name: String?): File? =
        name?.let(::cutoutFailureFileName)?.let { File(directory, it) }

    fun exists(name: String?): Boolean = imageFile(name)?.isFile == true
    fun hasCutout(name: String?): Boolean = cutoutFile(name)?.isFile == true
    fun hasCutoutFailure(name: String?): Boolean = failureFile(name)?.isFile == true

    /** Copy now while the temporary picker grant exists; validate before exposing the name. */
    suspend fun importPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = "background-${UUID.randomUUID()}.img"
        val temp = File(directory, ".$name.tmp")
        val destination = File(directory, name)
        try {
            val input = app.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open the chosen photo")
            input.use { source ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) throw IOException("Photo exceeds 40 MB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            // ImageDecoder respects EXIF orientation. Decode a tiny software bitmap as validation.
            decodeFile(temp, 64)?.recycle() ?: throw IOException("Unsupported or damaged photo")
            currentCoroutineContext().ensureActive()
            if (!temp.renameTo(destination)) throw IOException("Could not store the photo")
            name
        } finally {
            temp.delete()
        }
    }

    /** Caller runs these off the main thread. Decoded pixels are capped per engine. */
    fun decode(name: String?, maxDimension: Int): Bitmap? = decodeSafely(imageFile(name), maxDimension)
    fun decodeCutout(name: String?, maxDimension: Int): Bitmap? = decodeSafely(cutoutFile(name), maxDimension)

    private fun decodeSafely(file: File?, maxDimension: Int): Bitmap? {
        if (file?.isFile != true) return null
        return try {
            decodeFile(file, maxDimension.coerceIn(64, 2048))
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Atomic sidecar publication. A deleted/replaced photo must never regain an orphan cutout. */
    fun writeCutout(name: String, bitmap: Bitmap): Boolean = synchronized(fileLock) {
        val image = imageFile(name) ?: return@synchronized false
        val destination = cutoutFile(name) ?: return@synchronized false
        if (!image.isFile) return@synchronized false
        val temp = File(directory, ".${destination.name}.tmp")
        try {
            temp.outputStream().buffered().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Could not encode foreground")
                }
            }
            if (!temp.renameTo(destination)) throw IOException("Could not store foreground")
            failureFile(name)?.delete()
            true
        } finally {
            temp.delete()
        }
    }

    /** Remember a completed failure so a broken model/photo is not retried on every redraw/start. */
    fun markCutoutFailure(name: String): Unit = synchronized(fileLock) {
        if (imageFile(name)?.isFile == true && cutoutFile(name)?.isFile != true) {
            failureFile(name)?.writeText("")
        }
    }

    fun delete(name: String?) = synchronized(fileLock) {
        imageFile(name)?.delete()
        cutoutFile(name)?.delete()
        failureFile(name)?.delete()
    }

    /** Remove abandoned drafts and their sidecars, but never touch arbitrary files. */
    suspend fun cleanupExcept(keep: Set<String>) = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            directory.listFiles()?.forEach { file ->
                val name = file.name
                val cutoutOwner = cutoutBackgroundName(name)
                if ((isSafeBackgroundName(name) && name !in keep) ||
                    (name.startsWith(".background-") && name.endsWith(".img.tmp")) ||
                    (cutoutOwner != null && (name.startsWith(".") || cutoutOwner !in keep))) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
        // Shared across activity/engine instances; cleanup cannot race sidecar publication.
        private val fileLock = Any()

        private fun decodeFile(file: File, maxDimension: Int): Bitmap? {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width <= 0 || height <= 0) throw IOException("Invalid image dimensions")
                val ratio = minOf(1.0, maxDimension.toDouble() / max(width, height))
                decoder.setTargetSize(
                    max(1, (width * ratio).roundToInt()),
                    max(1, (height * ratio).roundToInt()),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }
}
