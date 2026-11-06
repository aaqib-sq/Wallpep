package dev.wallpep.render

import dev.wallpep.model.WallpaperSettings

internal data class DepthPlan(
    val hourBefore: Boolean,
    val minuteBefore: Boolean,
    val dateBefore: Boolean,
    val textBefore: Boolean,
    val foreground: Boolean,
    val hourAfter: Boolean,
    val minuteAfter: Boolean,
    val dateAfter: Boolean,
    val textAfter: Boolean,
)

/** Each clock part, the date and the text layer are drawn exactly once, even with no usable foreground. */
internal fun depthPlan(settings: WallpaperSettings, hasForeground: Boolean): DepthPlan =
    if (!hasForeground) {
        DepthPlan(true, true, true, true, false, false, false, false, false)
    } else {
        DepthPlan(
            hourBefore = !settings.hourOverForeground,
            minuteBefore = !settings.minuteOverForeground,
            dateBefore = !settings.dateOverForeground,
            textBefore = !settings.text.overForeground,
            foreground = true,
            hourAfter = settings.hourOverForeground,
            minuteAfter = settings.minuteOverForeground,
            dateAfter = settings.dateOverForeground,
            textAfter = settings.text.overForeground,
        )
    }
