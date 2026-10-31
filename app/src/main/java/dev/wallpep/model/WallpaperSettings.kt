package dev.wallpep.model

import dev.wallpep.fonts.FontCatalog
import java.util.Locale

const val SETTINGS_SCHEMA_VERSION = 1

/** Names are persisted, so new values should be added rather than renaming old ones. */
enum class TimeStyle { SYSTEM, TWELVE_HOUR, TWENTY_FOUR_HOUR }
enum class DateStyle { FULL, SHORT, HIDDEN }

object TextColors {
    const val WHITE: Int = -0x1
    val MIST: Int = 0xffd9e8dc.toInt()
    val SAND: Int = 0xfff2dfb7.toInt()
    val ROSE: Int = 0xfff0cbcd.toInt()
    val INK: Int = 0xff24362f.toInt()
    val all = listOf(WHITE, MIST, SAND, ROSE, INK)
}

/** Only an opaque, app-generated file name is persisted, never the photo picker URI. */
data class WallpaperSettings(
    val timeStyle: TimeStyle = TimeStyle.SYSTEM,
    val dateStyle: DateStyle = DateStyle.FULL,
    // Font ids are stable catalog ids; Phase 1/2's stored enum names are migrated on read.
    val font: String = FontCatalog.DEFAULT_ID,
    val dateFont: String = FontCatalog.FOLLOW_CLOCK,
    val textColor: Int = TextColors.WHITE,
    val clockSizeSp: Int = 72,
    val positionX: Int = 50,
    val positionY: Int = 32,
    val backgroundName: String? = null,
    // Additive schema-1 preferences: missing keys from Phase 1 default behind the cutout.
    val hourOverForeground: Boolean = false,
    val minuteOverForeground: Boolean = false,
    val dateOverForeground: Boolean = false,
) {
    val clockOverForeground: Boolean get() = hourOverForeground && minuteOverForeground
    fun sanitized(): WallpaperSettings = copy(
        font = FontCatalog.sanitizeId(font),
        dateFont = FontCatalog.sanitizeDateId(dateFont),
        textColor = textColor.takeIf { it in TextColors.all } ?: TextColors.WHITE,
        clockSizeSp = clockSizeSp.coerceIn(36, 120),
        positionX = positionX.coerceIn(0, 100),
        positionY = positionY.coerceIn(0, 100),
        backgroundName = backgroundName?.takeIf(::isSafeBackgroundName),
    )
}

private val backgroundFilePattern = Regex("^background-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.img$")

fun isSafeBackgroundName(name: String): Boolean = backgroundFilePattern.matches(name)

inline fun <reified T : Enum<T>> storedEnum(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value?.uppercase(Locale.ROOT) } ?: fallback
