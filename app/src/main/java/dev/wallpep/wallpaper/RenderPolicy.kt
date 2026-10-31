package dev.wallpep.wallpaper

/** An engine is eligible only while *its own* surface is visible and usable.
 * A preview gets no special exemption from the visibility rule. */
class RenderGate {
    var visible: Boolean = false
    var surfaceAvailable: Boolean = false
    var width: Int = 0
    var height: Int = 0
    var screenAwake: Boolean = true
    var unlocked: Boolean = true
    // On API 34+, the OS can identify a lock-only engine. We never draw one.
    var lockOnly: Boolean = false

    val canRender: Boolean
        get() = visible && surfaceAvailable && width > 0 && height > 0 && screenAwake && unlocked && !lockOnly
}

/** Schedule from the wall clock rather than accumulating a repeating timer's drift. */
fun millisUntilNextMinute(nowMillis: Long): Long = 60_000L - Math.floorMod(nowMillis, 60_000L)

/** Changes smaller than this perceptual-ish RGB distance leave the cached palette alone. */
data class PaletteSignature(val primary: Int, val secondary: Int?, val tertiary: Int?, val hints: Int)

fun materiallyDifferent(old: PaletteSignature, new: PaletteSignature): Boolean {
    fun differs(a: Int?, b: Int?): Boolean {
        if (a == null || b == null) return a != b
        val red = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val green = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val blue = (a and 0xff) - (b and 0xff)
        return red * red + green * green + blue * blue >= 24 * 24
    }
    return old.hints != new.hints || differs(old.primary, new.primary) ||
        differs(old.secondary, new.secondary) || differs(old.tertiary, new.tertiary)
}
