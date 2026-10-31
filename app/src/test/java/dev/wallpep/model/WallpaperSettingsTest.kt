package dev.wallpep.model

import dev.wallpep.fonts.FontCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperSettingsTest {
    @Test fun invalidPersistedValuesAreSafelyClamped() {
        val s = WallpaperSettings(
            textColor = 0,
            clockSizeSp = 999,
            positionX = -1,
            positionY = 150,
            backgroundName = "../../secret.jpg",
        ).sanitized()
        assertEquals(TextColors.WHITE, s.textColor)
        assertEquals(120, s.clockSizeSp)
        assertEquals(0, s.positionX)
        assertEquals(100, s.positionY)
        assertNull(s.backgroundName)
    }

    @Test fun onlyGeneratedPrivateFileNamesAreAccepted() {
        assertTrue(isSafeBackgroundName("background-3b70bf09-554e-42fb-80dc-45f67be67335.img"))
        assertFalse(isSafeBackgroundName("../background-3b70bf09-554e-42fb-80dc-45f67be67335.img"))
        assertFalse(isSafeBackgroundName("content://gallery/photo"))
        assertFalse(isSafeBackgroundName("background-3b70bf09-554e-42fb-80dc-45f67be67335.img/other"))
    }

    @Test fun phaseOneDefaultsPutClockAndDateBehindACutout() {
        val defaults = WallpaperSettings()
        assertFalse(defaults.hourOverForeground)
        assertFalse(defaults.minuteOverForeground)
        assertFalse(defaults.dateOverForeground)
        assertFalse(defaults.clockOverForeground)
        assertFalse(defaults.copy(hourOverForeground = true).clockOverForeground)
        assertTrue(defaults.copy(hourOverForeground = true, minuteOverForeground = true).clockOverForeground)
        assertTrue(defaults.copy(dateOverForeground = true).dateOverForeground)
    }

    @Test fun unknownEnumValuesUseDefaults() {
        assertEquals(TimeStyle.SYSTEM, storedEnum("FUTURE", TimeStyle.SYSTEM))
        assertEquals(TimeStyle.TWELVE_HOUR, storedEnum("twelve_hour", TimeStyle.SYSTEM))
        assertEquals(1, SETTINGS_SCHEMA_VERSION)
    }

    @Test fun typefacesFallBackToBundledDefaults() {
        val fallback = WallpaperSettings(font = "not-a-bundled-family", dateFont = "not-a-bundled-family").sanitized()
        assertEquals(FontCatalog.DEFAULT_ID, fallback.font)
        assertEquals(FontCatalog.FOLLOW_CLOCK, fallback.dateFont)
        assertEquals(FontCatalog.DEFAULT_ID, WallpaperSettings().font)
        assertEquals(FontCatalog.FOLLOW_CLOCK, WallpaperSettings().dateFont)
        // Phase 1/2 persisted enum names keep working.
        assertEquals("outfit", WallpaperSettings(font = "OUTFIT").sanitized().font)
        assertEquals("dmsans", WallpaperSettings(font = "DM_SANS").sanitized().font)
        assertEquals("system", WallpaperSettings(font = "SYSTEM").sanitized().font)
        assertEquals("cinzel", WallpaperSettings(font = "cinzel", dateFont = "cinzel").sanitized().dateFont)
    }

    @Test fun dateTypefaceCanFollowTheClock() {
        val s = WallpaperSettings(font = "anton", dateFont = FontCatalog.FOLLOW_CLOCK).sanitized()
        assertEquals(FontCatalog.FOLLOW_CLOCK, s.dateFont)
        assertEquals("anton", s.font)
    }
}
