package dev.wallpep.cutout

import kotlin.math.roundToInt

internal data class ModelSize(val width: Int, val height: Int)

/** Preserve the photo's aspect ratio, bound memory, and satisfy MODNet's 32-pixel stride. */
internal fun modelSize(width: Int, height: Int): ModelSize {
    require(width > 0 && height > 0)
    val scale = minOf(512.0 / minOf(width, height), 1024.0 / maxOf(width, height))
    fun dimension(value: Int): Int =
        ((value * scale / 32.0).roundToInt() * 32).coerceIn(32, 1024)
    return ModelSize(dimension(width), dimension(height))
}

internal fun normalizedChannel(value: Int): Float = value / 127.5f - 1f

internal fun matteAlpha(value: Float): Int {
    require(value.isFinite()) { "Invalid matte" }
    return (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
