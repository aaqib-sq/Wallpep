package dev.wallpep.model

/** Offline starting points. Unspecified fields deliberately use the complete model defaults. */
data class BuiltInPreset(val name: String, val asset: String, val settings: WallpaperSettings)

object BuiltInPresets {
    private val shadow = ShadowSettings(enabled = true, blur = 18, offsetY = 4, opacity = 80)
    val all = listOf(
        BuiltInPreset("Mountain Dusk", "presets/mountains.jpg", WallpaperSettings(
            clockSizeSp = 76, positionY = 29, datePositionY = 17f,
            textColor = TextColors.WHITE, hourOverForeground = true, minuteOverForeground = true, dateOverForeground = true, clockShadow = shadow, dateShadow = shadow)),
        BuiltInPreset("Ocean Sunset", "presets/ocean.jpg", WallpaperSettings(
            clockSizeSp = 72, positionY = 30, datePositionY = 17f,
            textColor = TextColors.WHITE, hourOverForeground = true, minuteOverForeground = true, dateOverForeground = true, clockShadow = shadow.copy(blur = 24, opacity = 100),
            dateShadow = shadow.copy(blur = 24, opacity = 100))),
        BuiltInPreset("Forest Mist", "presets/forest.jpg", WallpaperSettings(
            clockSizeSp = 72, positionY = 28, datePositionY = 16f,
            textColor = TextColors.WHITE, hourOverForeground = true, minuteOverForeground = true, dateOverForeground = true, clockShadow = shadow.copy(opacity = 100),
            dateShadow = shadow.copy(opacity = 100))),
        BuiltInPreset("Blue Waves", "presets/waves.jpg", WallpaperSettings(
            clockSizeSp = 80, positionY = 27, datePositionY = 15f,
            textColor = TextColors.WHITE, hourOverForeground = true, minuteOverForeground = true, dateOverForeground = true, clockShadow = shadow, dateShadow = shadow)),
    )
}
