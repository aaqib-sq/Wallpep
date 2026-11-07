package dev.wallpep.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInPresetsTest {
    @Test fun presetsAreIndependentCompleteStartingSettings() {
        val presets = BuiltInPresets.all
        assertEquals(4, presets.size)
        assertEquals(4, presets.map { it.asset }.toSet().size)
        presets.forEach { preset ->
            val settings = preset.settings
            assertEquals(settings, settings.sanitized())
            assertEquals(null, settings.backgroundName)
            assertTrue(settings.showClock && settings.showDate)
            assertTrue(settings.clockShadow.enabled && settings.dateShadow.enabled)
            assertFalse(settings.parallaxLayersEnabled)
            assertFalse(settings.parallaxWallpaperEnabled)
            assertEquals(EffectStyle.NONE, settings.effectStyle)
        }
    }
}
