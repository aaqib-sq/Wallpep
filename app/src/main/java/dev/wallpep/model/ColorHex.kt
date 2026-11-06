package dev.wallpep.model

import java.util.Locale

/** Hex input accepts RGB (opaque) or Android's ARGB, and never guesses partial input. */
object ColorHex {
    fun parse(value: String): Int? {
        val hex = value.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val parsed = hex.toLongOrNull(16) ?: return null
        return (if (hex.length == 6) parsed or 0xff000000L else parsed).toInt()
    }

    fun format(color: Int): String = String.format(Locale.ROOT, "#%08X", color)
}
