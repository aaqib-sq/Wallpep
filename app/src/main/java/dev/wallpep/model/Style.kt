package dev.wallpep.model

import java.util.Locale

/** Left / centre / right anchoring for a text layer. */
enum class TextAlignment { LEFT, CENTER, RIGHT }

/** The particle look for the Effects tab; drawn by the shared particle system in `WallpaperScene`. */
enum class EffectStyle(val label: String) { NONE("None"), RAIN("Rain"), SNOW("Snow"), SAKURA("Sakura") }

/** Stroke outline style for a text layer. */
enum class StrokeStyle(val label: String) { SOLID("Solid"), DASHED("Dashed"), DOTTED("Dotted") }

/** Wallpaper transition styles offered in Settings; the engine honours these in a later slice. */
enum class AnimationStyle(val label: String, val selectable: Boolean = true) {
    FADE_AND_SCALE("Fade & Scale"),
    SLIDE_UP("Slide Up Fade"), SLIDE_DOWN("Slide Down Fade"),
    SLIDE_LEFT("Slide Left Fade"), SLIDE_RIGHT("Slide Right Fade"),
    ZOOM_IN("Zoom In"), ZOOM_OUT("Zoom Out"), POP_IN("Pop In"),
    SPIN_IN("Spin In"), ROTATE_IN("Rotate In"), SWING("Swing"),
    UNFOLD("Unfold"), GLITCH("Glitch"), HEARTBEAT("Heartbeat"),
    DROP_BOUNCE("Drop Bounce"), BLUR_IN("Blur In"),
    STRETCH_HORIZONTAL("Stretch Horizontal"), STRETCH_VERTICAL("Stretch Vertical"),
    // Keep old enum names readable in existing documents; do not offer them as new choices.
    CROSS_FADE("Cross Fade", false), FLIP("Flip", false), CUBE("Cube", false),
    ROTATE("Rotate", false), BLUR_FADE("Blur Fade", false), WIPE("Wipe", false),
    DEPTH("Depth", false), DRIFT("Drift", false), REVEAL("Reveal", false),
    STACK("Stack", false), PUSH("Push", false), GLIDE("Glide", false), NONE("None", false),
}

/**
 * Stroke controls shared by the clock, date and text layers.
 * [width] is whole tenths of a dp (`8` = 0.8 dp) so the value survives a JSON round trip.
 */
data class StrokeSettings(
    val enabled: Boolean = false,
    val color: Int = TextColors.WHITE,
    val secondaryColor: Int = TextColors.MIST,
    val secondsColor: Int = TextColors.MIST,
    val width: Int = 8,
    val style: StrokeStyle = StrokeStyle.SOLID,
    val gradientEnabled: Boolean = false,
    val gradientAngle: Int = 90,
) {
    fun sanitized() = copy(
        color = TextColors.sanitize(color, TextColors.WHITE),
        secondaryColor = TextColors.sanitize(secondaryColor, TextColors.MIST),
        secondsColor = TextColors.sanitize(secondsColor, TextColors.MIST),
        width = width.coerceIn(MIN_WIDTH, MAX_WIDTH),
        gradientAngle = gradientAngle.coerceIn(0, 360),
    )

    companion object {
        const val MIN_WIDTH = 0
        const val MAX_WIDTH = 40
    }
}

/** Drop-shadow controls shared by the clock, date and text layers. */
data class ShadowSettings(
    val enabled: Boolean = false,
    val color: Int = TextColors.BLACK,
    val blur: Int = 12,
    val offsetX: Int = 0,
    val offsetY: Int = 4,
    val opacity: Int = 60,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    fun sanitized() = copy(
        color = TextColors.sanitize(color, TextColors.BLACK),
        blur = blur.coerceIn(MIN_BLUR, MAX_BLUR),
        offsetX = offsetX.coerceIn(MIN_OFFSET, MAX_OFFSET),
        offsetY = offsetY.coerceIn(MIN_OFFSET, MAX_OFFSET),
        opacity = opacity.coerceIn(0, 100),
    )

    companion object {
        const val MIN_BLUR = 0
        const val MAX_BLUR = 40
        const val MIN_OFFSET = -40
        const val MAX_OFFSET = 40
    }
}

/**
 * Perspective, skew, rotation and stretch for one text layer. Angles are whole degrees
 * (structure file shows the `0.0` tenths in the reference UI) and stretch is a percent.
 */
data class TransformSettings(
    val perspectiveX: Int = 0,
    val perspectiveY: Int = 0,
    val skewHorizontal: Int = 0,
    val skewVertical: Int = 0,
    val skewLeftRight: Int = 0,
    val skewLeftVertical: Int = 0,
    val skewBottomHorizontal: Int = 0,
    val skewTopBottom: Int = 0,
    val rotation: Int = 0,
    val stretch: Int = 100,
) {
    fun sanitized() = copy(
        perspectiveX = perspectiveX.coerceIn(MIN_ANGLE, MAX_ANGLE),
        perspectiveY = perspectiveY.coerceIn(MIN_ANGLE, MAX_ANGLE),
        skewHorizontal = skewHorizontal.coerceIn(MIN_ANGLE, MAX_ANGLE),
        skewVertical = skewVertical.coerceIn(MIN_ANGLE, MAX_ANGLE),
        skewLeftRight = skewLeftRight.coerceIn(MIN_ANGLE, MAX_ANGLE),
        skewLeftVertical = skewLeftVertical.coerceIn(MIN_ANGLE, MAX_ANGLE),
        skewBottomHorizontal = skewBottomHorizontal.coerceIn(MIN_ANGLE, MAX_ANGLE),
        skewTopBottom = skewTopBottom.coerceIn(MIN_ANGLE, MAX_ANGLE),
        rotation = rotation.coerceIn(0, 359),
        stretch = stretch.coerceIn(MIN_STRETCH, MAX_STRETCH),
    )

    companion object {
        const val MIN_ANGLE = -60
        const val MAX_ANGLE = 60
        const val MIN_STRETCH = 50
        const val MAX_STRETCH = 200
    }
}

/**
 * The optional third text layer. Everything here is persisted and rendered by `WallpaperScene`.
 * `gradientAngle` is in degrees: 0° sweeps left to right, 90° sweeps top to bottom.
 */
data class TextLayerSettings(
    val enabled: Boolean = false,
    val content: String = "",
    val font: String = dev.wallpep.fonts.FontCatalog.DEFAULT_ID,
    val sizeSp: Int = 30,
    val letterSpacing: Int = 0,
    val align: TextAlignment = TextAlignment.CENTER,
    val color: Int = TextColors.WHITE,
    val secondaryColor: Int = TextColors.MIST,
    val gradientEnabled: Boolean = false,
    val gradientAngle: Int = 90,
    val opacity: Int = 100,
    val positionX: Int = 50,
    val positionY: Int = 50,
    val overForeground: Boolean = false,
    val stroke: StrokeSettings = StrokeSettings(),
    val shadow: ShadowSettings = ShadowSettings(),
    val transform: TransformSettings = TransformSettings(),
) {
    fun sanitized() = copy(
        content = content.take(MAX_CONTENT),
        font = dev.wallpep.fonts.FontCatalog.sanitizeId(font),
        sizeSp = sizeSp.coerceIn(MIN_SIZE, MAX_SIZE),
        letterSpacing = letterSpacing.coerceIn(0, WallpaperSettings.MAX_LETTER_SPACING),
        color = TextColors.sanitize(color, TextColors.WHITE),
        secondaryColor = TextColors.sanitize(secondaryColor, TextColors.MIST),
        gradientAngle = gradientAngle.coerceIn(0, 360),
        opacity = opacity.coerceIn(WallpaperSettings.MIN_OPACITY, 100),
        positionX = positionX.coerceIn(0, 100),
        positionY = positionY.coerceIn(0, 100),
        stroke = stroke.sanitized(),
        shadow = shadow.sanitized(),
        transform = transform.sanitized(),
    )

    companion object {
        const val MIN_SIZE = 12
        const val MAX_SIZE = 96
        const val MAX_CONTENT = 120
    }
}

/**
 * One clock time format. `separator` sits between the hour and the minute; `stacked` formats
 * draw the hour and the minute on their own rows, which is what "vertical" means in the
 * reference structure. Ids are persisted, so they must never be renamed.
 */
data class ClockFormat(
    val id: String,
    val label: String,
    val separator: String = ":",
    val withSeconds: Boolean = false,
    // The separator preceding seconds is independent of the hour/minute separator.
    val secondsSeparator: String = " ",
    val stacked: Boolean = false,
    val padHour: Boolean = true,
)

object ClockFormats {
    /** Locale-driven hour/minute with the device clock style: the behaviour this app shipped. */
    const val SYSTEM = "system"

    val all: List<ClockFormat> = listOf(
        ClockFormat("hhmm", "hhmm", separator = ""),
        ClockFormat("hh_mm", "hh mm", separator = " "),
        ClockFormat("hh:mm", "hh:mm", separator = ":"),
        ClockFormat("hh.mm", "hh.mm", separator = "."),
        ClockFormat("hh/mm", "hh/mm", separator = "/", stacked = true),
        ClockFormat("hh_mm_ss", "hh mm ss", separator = " ", withSeconds = true),
        ClockFormat("hhmmss", "hhmmss", separator = "", withSeconds = true, secondsSeparator = ""),
        ClockFormat("hh:mm_ss", "hh:mm ss", separator = ":", withSeconds = true),
        ClockFormat("hh.mm_ss", "hh.mm ss", separator = ".", withSeconds = true),
        ClockFormat("h:mm", "h:mm", separator = ":", padHour = false),
        ClockFormat("hmm", "hmm", separator = "", padHour = false),
        ClockFormat("h_mm", "h mm", separator = " ", padHour = false),
        ClockFormat("h.mm", "h.mm", separator = ".", padHour = false),
        ClockFormat("hh_mm_ss_v", "hh mm ss (vertical)", separator = " ", withSeconds = true, stacked = true),
        ClockFormat("hhmm_ss_v", "hhmm ss (vertical)", separator = "", withSeconds = true, stacked = true),
        ClockFormat("hh:mm_ss_v", "hh:mm ss (vertical)", separator = ":", withSeconds = true, stacked = true),
        ClockFormat("hh.mm_ss_v", "hh.mm ss (vertical)", separator = ".", withSeconds = true, stacked = true),
    )

    const val DEFAULT_ID = "hh:mm"

    fun format(id: String?): ClockFormat? = all.firstOrNull { it.id == id }

    /** True when the format shows seconds and therefore needs a redraw every wall-clock second. */
    fun needsSeconds(id: String?): Boolean = format(id)?.withSeconds == true

    fun sanitize(value: String?): String =
        if (value == SYSTEM) SYSTEM else format(value)?.id ?: DEFAULT_ID
}

/** One date pattern. Ids are the persisted values and are also the display labels. */
data class DateFormat(val id: String, val label: String)

object DateFormats {
    val all: List<DateFormat> = listOf(
        DateFormat("dd MMM yyyy", "dd MMM yyyy"),
        DateFormat("MMM dd, yyyy", "MMM dd, yyyy"),
        DateFormat("yyyy-MM-dd", "yyyy-MM-dd"),
        DateFormat("dd/MM/yyyy", "dd/MM/yyyy"),
        DateFormat("MM/dd/yyyy", "MM/dd/yyyy"),
        DateFormat("EEEE, MMMM dd", "EEEE, MMMM dd"),
        DateFormat("EEE, MMM dd", "EEE, MMM dd"),
        DateFormat("EEEE MMMM dd", "EEEE MMMM dd"),
        DateFormat("MMM dd yyyy", "MMM dd yyyy"),
        DateFormat("EEE MMM dd", "EEE MMM dd"),
        DateFormat("EEEE MMM dd", "EEEE MMM dd"),
    )

    const val DEFAULT_ID = "dd MMM yyyy"

    fun pattern(id: String?): String = all.firstOrNull { it.id == id }?.id ?: DEFAULT_ID

    fun label(id: String?): String = all.firstOrNull { it.id == id }?.label ?: DEFAULT_ID
}

/** Enum values are persisted by name; unknown or future names fall back to the default. */
inline fun <reified T : Enum<T>> storedEnum(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value?.uppercase(Locale.ROOT) } ?: fallback
