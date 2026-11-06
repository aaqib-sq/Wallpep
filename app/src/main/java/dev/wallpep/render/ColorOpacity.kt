package dev.wallpep.render

/** Multiply the ARGB colour's own alpha by an independent layer/effect alpha. */
internal fun multiplyColorAlpha(color: Int, opacity: Int): Int {
    val alpha = ((color ushr 24) * opacity.coerceIn(0, 255) + 127) / 255
    return (color and 0x00ffffff) or (alpha shl 24)
}
