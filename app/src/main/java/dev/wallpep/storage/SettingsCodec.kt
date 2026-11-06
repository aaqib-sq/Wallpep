package dev.wallpep.storage

import dev.wallpep.model.AnimationStyle
import dev.wallpep.model.EffectStyle
import dev.wallpep.model.ShadowSettings
import dev.wallpep.model.StrokeSettings
import dev.wallpep.model.StrokeStyle
import dev.wallpep.model.TextAlignment
import dev.wallpep.model.TextLayerSettings
import dev.wallpep.model.TimeStyle
import dev.wallpep.model.TransformSettings
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.model.storedEnum
import org.json.JSONException
import org.json.JSONObject

/**
 * A single versioned JSON document holds every persisted preference. Keeping one document means
 * a new control only has to be added here and in [WallpaperSettings], and an unknown or damaged
 * document falls back to the defaults instead of losing the wallpaper.
 *
 * Decoding never throws: each field is read with the same default the model declares.
 */
internal object SettingsCodec {

    fun encode(settings: WallpaperSettings): String = toJson(settings).toString()

    /** Returns null when there is nothing usable to read. */
    fun decode(raw: String?): WallpaperSettings? {
        if (raw.isNullOrBlank()) return null
        return try {
            fromJson(JSONObject(raw))
        } catch (_: JSONException) {
            null
        }
    }

    fun toJson(settings: WallpaperSettings): JSONObject = JSONObject().apply {
        put("show_clock", settings.showClock)
        put("time_style", settings.timeStyle.name)
        put("clock_format", settings.clockFormat)
        put("font", settings.font)
        put("text_color", settings.textColor)
        put("clock_secondary_color", settings.clockSecondaryColor)
        put("seconds_color", settings.secondsColor)
        put("clock_gradient", settings.clockGradientEnabled)
        put("clock_gradient_angle", settings.clockGradientAngle)
        put("clock_size_sp", settings.clockSizeSp)
        put("clock_letter_spacing", settings.clockLetterSpacing)
        put("clock_align", settings.clockAlign.name)
        put("clock_vertical_spacing", settings.clockVerticalSpacing)
        put("clock_opacity", settings.clockOpacity)
        put("position_x", settings.positionX)
        put("position_y", settings.positionY)
        put("hour_over_foreground", settings.hourOverForeground)
        put("minute_over_foreground", settings.minuteOverForeground)
        put("clock_stroke", strokeToJson(settings.clockStroke))
        put("clock_shadow", shadowToJson(settings.clockShadow))
        put("clock_transform", transformToJson(settings.clockTransform))
        put("show_date", settings.showDate)
        put("date_format", settings.dateFormat)
        put("date_font", settings.dateFont)
        put("date_uppercase", settings.dateUppercase)
        put("date_secondary_color", settings.dateSecondaryColor)
        put("date_gradient", settings.dateGradientEnabled)
        put("date_gradient_angle", settings.dateGradientAngle)
        put("date_size_sp", settings.dateSizeSp)
        put("date_letter_spacing", settings.dateLetterSpacing)
        put("date_align", settings.dateAlign.name)
        put("date_opacity", settings.dateOpacity)
        put("date_position_x", settings.datePositionX)
        put("date_position_y", settings.datePositionY)
        put("date_over_foreground", settings.dateOverForeground)
        put("date_stroke", strokeToJson(settings.dateStroke))
        put("date_shadow", shadowToJson(settings.dateShadow))
        put("date_transform", transformToJson(settings.dateTransform))
        settings.dateColor?.let { put("date_color", it) }
        put("text_layer", textToJson(settings.text))
        put("hue_rotation", settings.hueRotation)
        put("hue", settings.hue)
        put("saturation", settings.saturation)
        put("grayscale", settings.grayscale)
        put("brightness", settings.brightness)
        put("parallax_layers", settings.parallaxLayersEnabled)
        put("parallax_layers_strength", settings.parallaxLayersStrength)
        put("parallax_clock", settings.parallaxClockEnabled)
        put("parallax_clock_strength", settings.parallaxClockStrength)
        put("parallax_date", settings.parallaxDateEnabled)
        put("parallax_date_strength", settings.parallaxDateStrength)
        put("parallax_wallpaper", settings.parallaxWallpaperEnabled)
        put("parallax_wallpaper_strength", settings.parallaxWallpaperStrength)
        put("effect_style", settings.effectStyle.name)
        put("effect_speed", settings.effectSpeed)
        put("effect_angle", settings.effectAngle)
        put("effect_particles", settings.effectParticles)
        put("effect_size", settings.effectSize)
        put("effect_color", settings.effectColor)
        put("effect_opacity", settings.effectOpacity)
        put("show_on_lockscreen", settings.showOnLockscreen)
        put("show_on_homescreen", settings.showOnHomescreen)
        put("battery_while_charging", settings.batteryWhileCharging)
        put("clock_animations", settings.clockAnimations)
        put("animation_style", settings.animationStyle.name)
        put("auto_rotate_interval_hours", settings.autoRotateIntervalHours)
        settings.backgroundName?.let { put("background_file", it) }
    }

    fun fromJson(json: JSONObject): WallpaperSettings {
        val defaults = WallpaperSettings()
        return WallpaperSettings(
            showClock = json.optBoolean("show_clock", defaults.showClock),
            timeStyle = storedEnum(json.optString("time_style"), defaults.timeStyle),
            clockFormat = json.optString("clock_format", defaults.clockFormat),
            font = json.optString("font", defaults.font),
            textColor = json.optInt("text_color", defaults.textColor),
            clockSecondaryColor = json.optInt("clock_secondary_color", defaults.clockSecondaryColor),
            secondsColor = json.optInt("seconds_color", defaults.secondsColor),
            clockGradientEnabled = json.optBoolean("clock_gradient", defaults.clockGradientEnabled),
            clockGradientAngle = json.optInt("clock_gradient_angle", defaults.clockGradientAngle),
            clockSizeSp = json.optInt("clock_size_sp", defaults.clockSizeSp),
            clockLetterSpacing = json.optInt("clock_letter_spacing", defaults.clockLetterSpacing),
            clockAlign = storedEnum(json.optString("clock_align"), defaults.clockAlign),
            clockVerticalSpacing = json.optInt("clock_vertical_spacing", defaults.clockVerticalSpacing),
            clockOpacity = json.optInt("clock_opacity", defaults.clockOpacity),
            positionX = json.optInt("position_x", defaults.positionX),
            positionY = json.optInt("position_y", defaults.positionY),
            hourOverForeground = json.optBoolean("hour_over_foreground", defaults.hourOverForeground),
            minuteOverForeground = json.optBoolean("minute_over_foreground", defaults.minuteOverForeground),
            clockStroke = strokeFromJson(json.optJSONObject("clock_stroke"), defaults.clockStroke),
            clockShadow = shadowFromJson(json.optJSONObject("clock_shadow"), defaults.clockShadow),
            clockTransform = transformFromJson(json.optJSONObject("clock_transform"), defaults.clockTransform),
            showDate = json.optBoolean("show_date", defaults.showDate),
            dateFormat = json.optString("date_format", defaults.dateFormat),
            dateFont = json.optString("date_font", defaults.dateFont),
            dateUppercase = json.optBoolean("date_uppercase", defaults.dateUppercase),
            dateColor = if (json.has("date_color")) json.optInt("date_color") else null,
            dateSecondaryColor = json.optInt("date_secondary_color", defaults.dateSecondaryColor),
            dateGradientEnabled = json.optBoolean("date_gradient", defaults.dateGradientEnabled),
            dateGradientAngle = json.optInt("date_gradient_angle", defaults.dateGradientAngle),
            dateSizeSp = json.optInt("date_size_sp", defaults.dateSizeSp),
            // Pre-parity documents without this key used zero spacing.
            dateLetterSpacing = json.optInt("date_letter_spacing", 0),
            dateAlign = storedEnum(json.optString("date_align"), defaults.dateAlign),
            dateOpacity = json.optInt("date_opacity", defaults.dateOpacity),
            datePositionX = json.optInt("date_position_x", defaults.datePositionX),
            // Preserve Phase 1 documents lacking the newer date position field.
            datePositionY = json.optDouble("date_position_y", 38.0).toFloat(),
            dateOverForeground = json.optBoolean("date_over_foreground", defaults.dateOverForeground),
            dateStroke = strokeFromJson(json.optJSONObject("date_stroke"), defaults.dateStroke),
            dateShadow = shadowFromJson(json.optJSONObject("date_shadow"), defaults.dateShadow),
            dateTransform = transformFromJson(json.optJSONObject("date_transform"), defaults.dateTransform),
            text = textFromJson(json.optJSONObject("text_layer"), defaults.text),
            hueRotation = json.optInt("hue_rotation", defaults.hueRotation),
            hue = json.optInt("hue", defaults.hue),
            saturation = json.optInt("saturation", defaults.saturation),
            grayscale = json.optInt("grayscale", defaults.grayscale),
            brightness = json.optInt("brightness", defaults.brightness),
            parallaxLayersEnabled = json.optBoolean("parallax_layers", defaults.parallaxLayersEnabled),
            parallaxLayersStrength = json.optInt("parallax_layers_strength", defaults.parallaxLayersStrength),
            // Missing per-layer keys default to on/100 %, the pre-per-layer behaviour.
            parallaxClockEnabled = json.optBoolean("parallax_clock", defaults.parallaxClockEnabled),
            parallaxClockStrength = json.optInt("parallax_clock_strength", defaults.parallaxClockStrength),
            parallaxDateEnabled = json.optBoolean("parallax_date", defaults.parallaxDateEnabled),
            parallaxDateStrength = json.optInt("parallax_date_strength", defaults.parallaxDateStrength),
            parallaxWallpaperEnabled = json.optBoolean("parallax_wallpaper", defaults.parallaxWallpaperEnabled),
            parallaxWallpaperStrength = json.optInt("parallax_wallpaper_strength", defaults.parallaxWallpaperStrength),
            effectStyle = storedEnum(json.optString("effect_style"), defaults.effectStyle),
            effectSpeed = json.optInt("effect_speed", defaults.effectSpeed),
            effectAngle = json.optInt("effect_angle", defaults.effectAngle),
            effectParticles = json.optInt("effect_particles", defaults.effectParticles),
            effectSize = json.optInt("effect_size", defaults.effectSize),
            effectColor = json.optInt("effect_color", defaults.effectColor),
            effectOpacity = json.optInt("effect_opacity", defaults.effectOpacity),
            showOnLockscreen = json.optBoolean("show_on_lockscreen", defaults.showOnLockscreen),
            showOnHomescreen = json.optBoolean("show_on_homescreen", defaults.showOnHomescreen),
            batteryWhileCharging = json.optBoolean("battery_while_charging", defaults.batteryWhileCharging),
            clockAnimations = json.optBoolean("clock_animations", defaults.clockAnimations),
            animationStyle = storedEnum(json.optString("animation_style"), defaults.animationStyle),
            autoRotateIntervalHours = json.optInt("auto_rotate_interval_hours", defaults.autoRotateIntervalHours),
            backgroundName = if (json.has("background_file")) json.optString("background_file") else null,
        ).sanitized()
    }

    private fun strokeToJson(stroke: StrokeSettings): JSONObject = JSONObject().apply {
        put("enabled", stroke.enabled)
        put("color", stroke.color)
        put("secondary_color", stroke.secondaryColor)
        put("seconds_color", stroke.secondsColor)
        put("width", stroke.width)
        put("style", stroke.style.name)
        put("gradient", stroke.gradientEnabled)
        put("gradient_angle", stroke.gradientAngle)
    }

    private fun strokeFromJson(json: JSONObject?, fallback: StrokeSettings): StrokeSettings =
        if (json == null) fallback else StrokeSettings(
            enabled = json.optBoolean("enabled", fallback.enabled),
            color = json.optInt("color", fallback.color),
            secondaryColor = json.optInt("secondary_color", fallback.secondaryColor),
            secondsColor = json.optInt("seconds_color", fallback.secondsColor),
            width = json.optInt("width", fallback.width),
            style = storedEnum(json.optString("style"), fallback.style),
            gradientEnabled = json.optBoolean("gradient", fallback.gradientEnabled),
            gradientAngle = json.optInt("gradient_angle", fallback.gradientAngle),
        ).sanitized()

    private fun shadowToJson(shadow: ShadowSettings): JSONObject = JSONObject().apply {
        put("enabled", shadow.enabled)
        put("color", shadow.color)
        put("blur", shadow.blur)
        put("offset_x", shadow.offsetX)
        put("offset_y", shadow.offsetY)
        put("opacity", shadow.opacity)
        put("flip_h", shadow.flipH)
        put("flip_v", shadow.flipV)
    }

    private fun shadowFromJson(json: JSONObject?, fallback: ShadowSettings): ShadowSettings =
        if (json == null) fallback else ShadowSettings(
            enabled = json.optBoolean("enabled", fallback.enabled),
            color = json.optInt("color", fallback.color),
            blur = json.optInt("blur", fallback.blur),
            offsetX = json.optInt("offset_x", fallback.offsetX),
            offsetY = json.optInt("offset_y", fallback.offsetY),
            opacity = json.optInt("opacity", fallback.opacity),
            flipH = json.optBoolean("flip_h", fallback.flipH),
            flipV = json.optBoolean("flip_v", fallback.flipV),
        ).sanitized()

    private fun transformToJson(transform: TransformSettings): JSONObject = JSONObject().apply {
        put("perspective_x", transform.perspectiveX)
        put("perspective_y", transform.perspectiveY)
        put("skew_horizontal", transform.skewHorizontal)
        put("skew_vertical", transform.skewVertical)
        put("skew_left_right", transform.skewLeftRight)
        put("skew_left_vertical", transform.skewLeftVertical)
        put("skew_bottom_horizontal", transform.skewBottomHorizontal)
        put("skew_top_bottom", transform.skewTopBottom)
        put("rotation", transform.rotation)
        put("stretch", transform.stretch)
    }

    private fun transformFromJson(json: JSONObject?, fallback: TransformSettings): TransformSettings =
        if (json == null) fallback else TransformSettings(
            perspectiveX = json.optInt("perspective_x", fallback.perspectiveX),
            perspectiveY = json.optInt("perspective_y", fallback.perspectiveY),
            skewHorizontal = json.optInt("skew_horizontal", fallback.skewHorizontal),
            skewVertical = json.optInt("skew_vertical", fallback.skewVertical),
            skewLeftRight = json.optInt("skew_left_right", fallback.skewLeftRight),
            skewLeftVertical = json.optInt("skew_left_vertical", fallback.skewLeftVertical),
            skewBottomHorizontal = json.optInt("skew_bottom_horizontal", fallback.skewBottomHorizontal),
            skewTopBottom = json.optInt("skew_top_bottom", fallback.skewTopBottom),
            rotation = json.optInt("rotation", fallback.rotation),
            stretch = json.optInt("stretch", fallback.stretch),
        ).sanitized()

    private fun textToJson(text: TextLayerSettings): JSONObject = JSONObject().apply {
        put("enabled", text.enabled)
        put("content", text.content)
        put("font", text.font)
        put("size_sp", text.sizeSp)
        put("letter_spacing", text.letterSpacing)
        put("align", text.align.name)
        put("color", text.color)
        put("secondary_color", text.secondaryColor)
        put("gradient", text.gradientEnabled)
        put("gradient_angle", text.gradientAngle)
        put("opacity", text.opacity)
        put("position_x", text.positionX)
        put("position_y", text.positionY)
        put("over_foreground", text.overForeground)
        put("stroke", strokeToJson(text.stroke))
        put("shadow", shadowToJson(text.shadow))
        put("transform", transformToJson(text.transform))
    }

    private fun textFromJson(json: JSONObject?, fallback: TextLayerSettings): TextLayerSettings =
        if (json == null) fallback else TextLayerSettings(
            enabled = json.optBoolean("enabled", fallback.enabled),
            content = json.optString("content", fallback.content),
            font = json.optString("font", fallback.font),
            sizeSp = json.optInt("size_sp", fallback.sizeSp),
            letterSpacing = json.optInt("letter_spacing", fallback.letterSpacing),
            align = storedEnum(json.optString("align"), fallback.align),
            color = json.optInt("color", fallback.color),
            secondaryColor = json.optInt("secondary_color", fallback.secondaryColor),
            gradientEnabled = json.optBoolean("gradient", fallback.gradientEnabled),
            gradientAngle = json.optInt("gradient_angle", fallback.gradientAngle),
            opacity = json.optInt("opacity", fallback.opacity),
            positionX = json.optInt("position_x", fallback.positionX),
            positionY = json.optInt("position_y", fallback.positionY),
            overForeground = json.optBoolean("over_foreground", fallback.overForeground),
            stroke = strokeFromJson(json.optJSONObject("stroke"), fallback.stroke),
            shadow = shadowFromJson(json.optJSONObject("shadow"), fallback.shadow),
            transform = transformFromJson(json.optJSONObject("transform"), fallback.transform),
        ).sanitized()
}
