package dev.wallpep.render

import dev.wallpep.model.WallpaperSettings

internal data class DepthPlan(
    val hourBefore: Boolean,
    val minuteBefore: Boolean,
    val dateBefore: Boolean,
    val foreground: Boolean,
    val hourAfter: Boolean,
    val minuteAfter: Boolean,
    val dateAfter: Boolean,
)

/** Each clock part is drawn exactly once, even when no usable foreground is available. */
internal fun depthPlan(settings: WallpaperSettings, hasForeground: Boolean): DepthPlan =
    if (!hasForeground) {
        DepthPlan(true, true, true, false, false, false, false)
    } else {
        DepthPlan(
            hourBefore = !settings.hourOverForeground,
            minuteBefore = !settings.minuteOverForeground,
            dateBefore = !settings.dateOverForeground,
            foreground = true,
            hourAfter = settings.hourOverForeground,
            minuteAfter = settings.minuteOverForeground,
            dateAfter = settings.dateOverForeground,
        )
    }
