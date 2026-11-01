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

/**
 * Only an opaque, app-generated file name is persisted, never the photo picker URI.
 *
 * Clock and date each carry their own typography, colour, opacity and position. Every new field
 * is additive to schema 1: a stored value that is missing falls back to the default below, so an
 * install from an earlier slice keeps working. Letter spacing is stored in hundredths of an em
 * (`5` = 0.05 em) and opacity in whole percent, so preferences stay integers.
 */
data class WallpaperSettings(
    val timeStyle: TimeStyle = TimeStyle.SYSTEM,
    val dateStyle: DateStyle = DateStyle.FULL,
    // Font ids are stable catalog ids; Phase 1/2's stored enum names are migrated on read.
    val font: String = FontCatalog.DEFAULT_ID,
    val dateFont: String = FontCatalog.FOLLOW_CLOCK,
    // The clock colour. Kept on the original `text_color` key so existing saves still load.
    val textColor: Int = TextColors.WHITE,
    // Null means the date follows the clock colour.
    val dateColor: Int? = null,
    val clockSizeSp: Int = 72,
    val clockLetterSpacing: Int = 0,
    val clockOpacity: Int = 100,
    val showClock: Boolean = true,
    val positionX: Int = 50,
    val positionY: Int = 32,
    val dateSizeSp: Int = 17,
    val dateLetterSpacing: Int = 0,
    val dateOpacity: Int = 100,
    val dateUppercase: Boolean = false,
    val datePositionX: Int = 50,
    val datePositionY: Int = 38,
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
        dateColor = dateColor?.takeIf { it in TextColors.all },
        clockSizeSp = clockSizeSp.coerceIn(MIN_CLOCK_SIZE, MAX_CLOCK_SIZE),
        clockLetterSpacing = clockLetterSpacing.coerceIn(0, MAX_LETTER_SPACING),
        clockOpacity = clockOpacity.coerceIn(MIN_OPACITY, 100),
        positionX = positionX.coerceIn(0, 100),
        positionY = positionY.coerceIn(0, 100),
        dateSizeSp = dateSizeSp.coerceIn(MIN_DATE_SIZE, MAX_DATE_SIZE),
        dateLetterSpacing = dateLetterSpacing.coerceIn(0, MAX_LETTER_SPACING),
        dateOpacity = dateOpacity.coerceIn(MIN_OPACITY, 100),
        datePositionX = datePositionX.coerceIn(0, 100),
        datePositionY = datePositionY.coerceIn(0, 100),
        backgroundName = backgroundName?.takeIf(::isSafeBackgroundName),
    )

    companion object {
        const val MIN_CLOCK_SIZE = 36
        const val MAX_CLOCK_SIZE = 120
        const val MIN_DATE_SIZE = 10
        const val MAX_DATE_SIZE = 72
        /** Hundredths of an em: 30 = 0.30 em. */
        const val MAX_LETTER_SPACING = 30
        /** Percent. A layer is never fully invisible, so its slider always leaves something. */
        const val MIN_OPACITY = 10
    }
}

private val backgroundFilePattern = Regex("^background-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.img$")

fun isSafeBackgroundName(name: String): Boolean = backgroundFilePattern.matches(name)

inline fun <reified T : Enum<T>> storedEnum(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value?.uppercase(Locale.ROOT) } ?: fallback
