package dev.wallpep.model

import dev.wallpep.fonts.FontCatalog

const val SETTINGS_SCHEMA_VERSION = 1

/** Names are persisted, so new values should be added rather than renaming old ones. */
enum class TimeStyle { SYSTEM, TWELVE_HOUR, TWENTY_FOUR_HOUR;
    fun uses24Hour(systemUses24Hour: Boolean): Boolean = when (this) {
        SYSTEM -> systemUses24Hour
        TWELVE_HOUR -> false
        TWENTY_FOUR_HOUR -> true
    }
}

/**
 * Convenient starting colours. Custom colours are stored as unrestricted ARGB integers.
 */
object TextColors {
    const val WHITE: Int = -0x1
    const val BLACK: Int = -0x1000000
    val MIST: Int = 0xffdce6f2.toInt()
    val SAND: Int = 0xffbfd3ee.toInt()
    val ROSE: Int = 0xffe7c9d6.toInt()
    val INK: Int = 0xff24362f.toInt()
    val SLATE: Int = 0xff8b90a0.toInt()
    val BLUE: Int = 0xff2563eb.toInt()
    val SKY: Int = 0xff60a5fa.toInt()
    val DEEP_BLUE: Int = 0xff1e3a8a.toInt()
    val GREY: Int = 0xff6b7280.toInt()

    /** Every selectable colour, in swatch order. */
    val all: List<Int> = listOf(WHITE, MIST, SAND, ROSE, SKY, BLUE, DEEP_BLUE, SLATE, GREY, INK, BLACK)

    /** Accessible names for the swatch rows, index-aligned with [all]. */
    val labels: List<String> = listOf(
        "White", "Mist", "Steel", "Rose", "Sky", "Electric blue", "Deep blue", "Slate", "Grey", "Ink", "Black",
    )

    @Suppress("UNUSED_PARAMETER")
    fun sanitize(color: Int, fallback: Int): Int = color

    fun label(color: Int): String = labels.getOrElse(all.indexOf(color)) { "Colour" }
}

/**
 * Only an opaque, app-generated file name is persisted, never the photo picker URI.
 *
 * Clock and date each carry their own typography, colour, opacity and position; the third text
 * layer, Colorize, Parallax and Effects keep their own values. Every new field is additive to
 * schema 1: a stored value that is missing falls back to the default below, so an install from
 * an earlier slice keeps working. Letter spacing is stored in hundredths of an em (`5` = 0.05 em)
 * and opacity in whole percent, so preferences stay integers.
 */
data class WallpaperSettings(
    // ---------------------------------------------------------------- clock
    val showClock: Boolean = true,
    val timeStyle: TimeStyle = TimeStyle.SYSTEM,
    val clockFormat: String = ClockFormats.DEFAULT_ID,
    // Font ids are stable catalog ids; Phase 1/2's stored enum names are migrated on read.
    val font: String = FontCatalog.DEFAULT_ID,
    // The clock colour. Kept on the original `text_color` key so existing saves still load.
    val textColor: Int = TextColors.WHITE,
    val clockSecondaryColor: Int = TextColors.MIST,
    val secondsColor: Int = TextColors.MIST,
    val clockGradientEnabled: Boolean = false,
    val clockGradientAngle: Int = 90,
    val clockSizeSp: Int = 72,
    val clockLetterSpacing: Int = 0,
    val clockAlign: TextAlignment = TextAlignment.CENTER,
    val clockVerticalSpacing: Int = 8,
    val clockOpacity: Int = 100,
    val positionX: Int = 50,
    val positionY: Int = 32,
    val clockStroke: StrokeSettings = StrokeSettings(),
    val clockShadow: ShadowSettings = ShadowSettings(),
    val clockTransform: TransformSettings = TransformSettings(),
    // ---------------------------------------------------------------- date
    val showDate: Boolean = true,
    val dateFormat: String = DateFormats.DEFAULT_ID,
    val dateFont: String = FontCatalog.FOLLOW_CLOCK,
    // Null means the date follows the clock colour.
    val dateColor: Int? = null,
    val dateSecondaryColor: Int = TextColors.MIST,
    val dateGradientEnabled: Boolean = false,
    val dateGradientAngle: Int = 90,
    val dateSizeSp: Int = 17,
    val dateLetterSpacing: Int = 7,
    val dateAlign: TextAlignment = TextAlignment.CENTER,
    val dateOpacity: Int = 100,
    val dateUppercase: Boolean = false,
    val datePositionX: Int = 50,
    val datePositionY: Float = 14.4f,
    val dateStroke: StrokeSettings = StrokeSettings(),
    val dateShadow: ShadowSettings = ShadowSettings(),
    val dateTransform: TransformSettings = TransformSettings(),
    // ---------------------------------------------------------------- text layer
    val text: TextLayerSettings = TextLayerSettings(),
    // ---------------------------------------------------------------- colorize (wallpaper)
    val hueRotation: Int = 0,
    val hue: Int = 0,
    val saturation: Int = 100,
    val grayscale: Int = 0,
    val brightness: Int = 100,
    // ---------------------------------------------------------------- parallax
    val parallaxLayersEnabled: Boolean = false,
    val parallaxLayersStrength: Int = 40,
    // Per-layer refinements of the global "Clock, Date & Text" parallax: a layer moves only
    // when the global switch and its own switch are on, and its strength is a percent of the
    // global travel. Defaults (on, 100) reproduce the pre-per-layer behaviour exactly, so
    // documents saved before these keys existed render unchanged.
    val parallaxClockEnabled: Boolean = true,
    val parallaxClockStrength: Int = 100,
    val parallaxDateEnabled: Boolean = true,
    val parallaxDateStrength: Int = 100,
    val parallaxWallpaperEnabled: Boolean = false,
    val parallaxWallpaperStrength: Int = 25,
    // ---------------------------------------------------------------- effects
    val effectStyle: EffectStyle = EffectStyle.NONE,
    val effectSpeed: Int = 15,
    val effectAngle: Int = 90,
    val effectParticles: Int = 40,
    val effectSize: Int = 30,
    val effectColor: Int = TextColors.WHITE,
    val effectOpacity: Int = 80,
    // ---------------------------------------------------------------- settings preferences
    val showOnLockscreen: Boolean = true,
    val showOnHomescreen: Boolean = true,
    val batteryWhileCharging: Boolean = false,
    val clockAnimations: Boolean = false,
    val animationStyle: AnimationStyle = AnimationStyle.FADE_AND_SCALE,
    // Hours between automatic changes of the live wallpaper; 0 turns the cycle off.
    val autoRotateIntervalHours: Int = 12,
    // ---------------------------------------------------------------- background photo
    val backgroundName: String? = null,
    // Additive schema-1 preferences: missing keys from Phase 1 default behind the cutout.
    val hourOverForeground: Boolean = false,
    val minuteOverForeground: Boolean = false,
    val dateOverForeground: Boolean = false,
) {
    val clockOverForeground: Boolean get() = hourOverForeground && minuteOverForeground

    val dateResolutionColor: Int get() = TextColors.sanitize(dateColor ?: textColor, textColor)

    fun sanitized(): WallpaperSettings = copy(
        clockFormat = ClockFormats.sanitize(clockFormat),
        font = FontCatalog.sanitizeId(font),
        textColor = TextColors.sanitize(textColor, TextColors.WHITE),
        clockSecondaryColor = TextColors.sanitize(clockSecondaryColor, TextColors.MIST),
        secondsColor = TextColors.sanitize(secondsColor, TextColors.MIST),
        clockGradientAngle = clockGradientAngle.coerceIn(0, 360),
        clockSizeSp = clockSizeSp.coerceIn(MIN_CLOCK_SIZE, MAX_CLOCK_SIZE),
        clockLetterSpacing = clockLetterSpacing.coerceIn(0, MAX_LETTER_SPACING),
        clockVerticalSpacing = clockVerticalSpacing.coerceIn(MIN_VERTICAL_SPACING, MAX_VERTICAL_SPACING),
        clockOpacity = clockOpacity.coerceIn(MIN_OPACITY, 100),
        positionX = positionX.coerceIn(0, 100),
        positionY = positionY.coerceIn(0, 100),
        clockStroke = clockStroke.sanitized(),
        clockShadow = clockShadow.sanitized(),
        clockTransform = clockTransform.sanitized(),
        dateFormat = DateFormats.pattern(dateFormat),
        dateFont = FontCatalog.sanitizeDateId(dateFont),
        dateColor = dateColor,
        dateSecondaryColor = TextColors.sanitize(dateSecondaryColor, TextColors.MIST),
        dateGradientAngle = dateGradientAngle.coerceIn(0, 360),
        dateSizeSp = dateSizeSp.coerceIn(MIN_DATE_SIZE, MAX_DATE_SIZE),
        dateLetterSpacing = dateLetterSpacing.coerceIn(0, MAX_LETTER_SPACING),
        dateOpacity = dateOpacity.coerceIn(MIN_OPACITY, 100),
        datePositionX = datePositionX.coerceIn(0, 100),
        datePositionY = if (datePositionY.isFinite()) datePositionY.coerceIn(0f, 100f) else 14.4f,
        dateStroke = dateStroke.sanitized(),
        dateShadow = dateShadow.sanitized(),
        dateTransform = dateTransform.sanitized(),
        text = text.sanitized(),
        hueRotation = hueRotation.coerceIn(0, 360),
        hue = hue.coerceIn(0, 360),
        saturation = saturation.coerceIn(0, 200),
        grayscale = grayscale.coerceIn(0, 100),
        brightness = brightness.coerceIn(0, 200),
        parallaxLayersStrength = parallaxLayersStrength.coerceIn(0, 100),
        parallaxClockStrength = parallaxClockStrength.coerceIn(0, 100),
        parallaxDateStrength = parallaxDateStrength.coerceIn(0, 100),
        parallaxWallpaperStrength = parallaxWallpaperStrength.coerceIn(0, 100),
        autoRotateIntervalHours = autoRotateIntervalHours.coerceIn(0, MAX_ROTATE_INTERVAL_HOURS),
        effectSpeed = effectSpeed.coerceIn(MIN_EFFECT_SPEED, MAX_EFFECT_SPEED),
        effectAngle = effectAngle.coerceIn(0, 360),
        effectParticles = effectParticles.coerceIn(MIN_EFFECT_PARTICLES, MAX_EFFECT_PARTICLES),
        effectSize = effectSize.coerceIn(MIN_EFFECT_SIZE, MAX_EFFECT_SIZE),
        effectColor = TextColors.sanitize(effectColor, TextColors.WHITE),
        effectOpacity = effectOpacity.coerceIn(0, 100),
        backgroundName = backgroundName?.takeIf(::isSafeBackgroundName),
    )

    companion object {
        const val MIN_CLOCK_SIZE = 36
        const val MAX_CLOCK_SIZE = 120
        const val MIN_DATE_SIZE = 10
        const val MAX_DATE_SIZE = 72
        /** Hundredths of an em: 30 = 0.30 em. */
        const val MAX_LETTER_SPACING = 30
        /** Percent, including fully transparent. */
        const val MIN_OPACITY = 0
        const val MIN_VERTICAL_SPACING = 0
        const val MAX_VERTICAL_SPACING = 40
        const val MIN_EFFECT_SPEED = 1
        const val MAX_EFFECT_SPEED = 100
        const val MIN_EFFECT_PARTICLES = 10
        const val MAX_EFFECT_PARTICLES = 200
        const val MIN_EFFECT_SIZE = 5
        const val MAX_EFFECT_SIZE = 100
        /** Seven days; 0 is reserved for "auto rotate off". */
        const val MAX_ROTATE_INTERVAL_HOURS = 168
    }
}

private val backgroundFilePattern = Regex("^background-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.img$")

fun isSafeBackgroundName(name: String): Boolean = backgroundFilePattern.matches(name)
