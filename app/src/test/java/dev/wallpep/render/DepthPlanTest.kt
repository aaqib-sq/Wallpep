package dev.wallpep.render

import dev.wallpep.model.WallpaperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthPlanTest {
    @Test fun defaultIsBackgroundThenTimeAndDateAndTextThenSubject() {
        val plan = depthPlan(WallpaperSettings(), hasForeground = true)
        assertTrue(plan.hourBefore)
        assertTrue(plan.minuteBefore)
        assertTrue(plan.dateBefore)
        assertTrue(plan.textBefore)
        assertTrue(plan.foreground)
        assertFalse(plan.hourAfter)
        assertFalse(plan.minuteAfter)
        assertFalse(plan.dateAfter)
        assertFalse(plan.textAfter)
    }

    @Test fun everyCombinationDrawsEachPartExactlyOnce() {
        for (hour in listOf(false, true)) {
            for (minute in listOf(false, true)) {
                for (date in listOf(false, true)) {
                    for (text in listOf(false, true)) {
                        val settings = WallpaperSettings(
                            hourOverForeground = hour,
                            minuteOverForeground = minute,
                            dateOverForeground = date,
                            text = WallpaperSettings().text.copy(overForeground = text),
                        )
                        val plan = depthPlan(settings, hasForeground = true)
                        assertEquals(!hour, plan.hourBefore)
                        assertEquals(hour, plan.hourAfter)
                        assertEquals(!minute, plan.minuteBefore)
                        assertEquals(minute, plan.minuteAfter)
                        assertEquals(!date, plan.dateBefore)
                        assertEquals(date, plan.dateAfter)
                        assertEquals(!text, plan.textBefore)
                        assertEquals(text, plan.textAfter)
                        assertTrue(plan.foreground)
                    }
                }
            }
        }
    }

    @Test fun missingOrFailedCutoutAlwaysUsesPhaseOneOrder() {
        val settings = WallpaperSettings(
            hourOverForeground = true,
            dateOverForeground = true,
            text = WallpaperSettings().text.copy(overForeground = true),
        )
        assertEquals(
            DepthPlan(true, true, true, true, false, false, false, false, false),
            depthPlan(settings, false),
        )
    }
}
