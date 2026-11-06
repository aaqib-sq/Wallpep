package dev.wallpep.storage

import dev.wallpep.model.WallpaperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings document is one versioned JSON blob. These tests pin the keys the battery
 * element (#147) and the surface switches (#145, #146) rely on, and prove that a document
 * saved before those keys existed still decodes to the shipped defaults.
 */
class SettingsCodecTest {

    @Test fun theBatteryElementAndTheSurfaceSwitchesSurviveARoundTrip() {
        val original = WallpaperSettings(
            batteryWhileCharging = true,
            showOnLockscreen = false,
            showOnHomescreen = true,
        )
        val decoded = SettingsCodec.decode(SettingsCodec.encode(original))
        assertNotNull(decoded)
        assertTrue(decoded!!.batteryWhileCharging)
        assertFalse(decoded.showOnLockscreen)
        assertTrue(decoded.showOnHomescreen)
    }

    @Test fun aDocumentWithoutTheNewerKeysDecodesToTheShippedDefaults() {
        // The keys the battery element and the surface switches use.
        val legacy = """{"show_clock":true,"clock_format":"hh:mm"}"""
        val decoded = SettingsCodec.decode(legacy)
        assertNotNull(decoded)
        val defaults = WallpaperSettings()
        assertEquals(defaults.batteryWhileCharging, decoded!!.batteryWhileCharging)
        assertEquals(defaults.showOnLockscreen, decoded.showOnLockscreen)
        assertEquals(defaults.showOnHomescreen, decoded.showOnHomescreen)
    }

    @Test fun aDamagedDocumentIsRejectedInsteadOfThrowing() {
        assertNull(SettingsCodec.decode("{not json"))
        assertNull(SettingsCodec.decode(""))
        assertNull(SettingsCodec.decode(null))
    }
}
