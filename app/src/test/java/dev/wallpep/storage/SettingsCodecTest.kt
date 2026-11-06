package dev.wallpep.storage

import dev.wallpep.model.WallpaperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings document's schema for the battery element (#147) and the surface switches
 * (#145, #146).
 *
 * The document itself is written and read with `org.json`, which the local JVM's Android stub
 * cannot execute, so these tests pin the parts that are pure: the **key names** the writer and
 * the reader share, and the **missing-key rule** that keeps a document written before a key
 * existed decoding to the shipped default.
 */
class SettingsCodecTest {

    @Test fun theBatteryElementAndTheSurfaceSwitchesHaveTheirOwnKeys() {
        // The key names are part of the on-disk schema, so they are pinned here.
        assertEquals("battery_while_charging", SettingsKeys.BATTERY_WHILE_CHARGING)
        assertEquals("show_on_lockscreen", SettingsKeys.SHOW_ON_LOCKSCREEN)
        assertEquals("show_on_homescreen", SettingsKeys.SHOW_ON_HOMESCREEN)
    }

    @Test fun theSliceKeysAreTheThreeThisSliceAdded() {
        assertEquals(
            listOf(
                SettingsKeys.BATTERY_WHILE_CHARGING,
                SettingsKeys.SHOW_ON_HOMESCREEN,
                SettingsKeys.SHOW_ON_LOCKSCREEN,
            ),
            SettingsKeys.sliceKeys,
        )
    }

    @Test fun aStoredValueIsKeptAsItWas() {
        assertTrue(SettingsDefaults.booleanFor(SettingsKeys.BATTERY_WHILE_CHARGING, stored = true))
        assertFalse(SettingsDefaults.booleanFor(SettingsKeys.BATTERY_WHILE_CHARGING, stored = false))
        assertTrue(SettingsDefaults.booleanFor(SettingsKeys.SHOW_ON_LOCKSCREEN, stored = true))
        assertFalse(SettingsDefaults.booleanFor(SettingsKeys.SHOW_ON_HOMESCREEN, stored = false))
    }

    @Test fun aDocumentWithoutTheNewerKeysDecodesToTheShippedDefaults() {
        // A document written before these keys existed carries no value for them, so each one
        // falls back to the default the model declares.
        val defaults = WallpaperSettings()
        assertEquals(
            defaults.batteryWhileCharging,
            SettingsDefaults.booleanFor(SettingsKeys.BATTERY_WHILE_CHARGING, stored = null),
        )
        assertEquals(
            defaults.showOnLockscreen,
            SettingsDefaults.booleanFor(SettingsKeys.SHOW_ON_LOCKSCREEN, stored = null),
        )
        assertEquals(
            defaults.showOnHomescreen,
            SettingsDefaults.booleanFor(SettingsKeys.SHOW_ON_HOMESCREEN, stored = null),
        )
    }

    @Test fun theBatteryElementIsOffByDefaultAndTheSurfaceSwitchesAreOn() {
        // The battery element is opt-in, so the clock stays until the user asks for it.
        assertFalse(SettingsDefaults.batteryWhileCharging)
        // Both surfaces show their content unless the user turns it off.
        assertTrue(SettingsDefaults.showOnLockscreen)
        assertTrue(SettingsDefaults.showOnHomescreen)
    }

    @Test fun theDefaultsMatchTheModelItDescribes() {
        val defaults = WallpaperSettings()
        assertEquals(defaults.batteryWhileCharging, SettingsDefaults.batteryWhileCharging)
        assertEquals(defaults.showOnLockscreen, SettingsDefaults.showOnLockscreen)
        assertEquals(defaults.showOnHomescreen, SettingsDefaults.showOnHomescreen)
    }

    @Test fun anUnknownKeyFailsLoudlyInsteadOfSilentlyDefaulting() {
        // A typo in a key name must not quietly turn a setting off.
        val failed = try {
            SettingsDefaults.booleanFor("show_on_lock_screens", stored = null)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(failed)
    }
}
