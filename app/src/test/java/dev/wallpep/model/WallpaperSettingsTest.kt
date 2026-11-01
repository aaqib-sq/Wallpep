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

    @Test fun typographyColourAndOpacityAreClamped() {
        val s = WallpaperSettings(
            clockSizeSp = 5,
            clockLetterSpacing = 99,
            clockOpacity = 0,
            dateSizeSp = 900,
            dateLetterSpacing = -4,
            dateOpacity = 400,
            datePositionX = -20,
            datePositionY = 480,
            dateColor = 0x123456,
        ).sanitized()
        assertEquals(WallpaperSettings.MIN_CLOCK_SIZE, s.clockSizeSp)
        assertEquals(WallpaperSettings.MAX_LETTER_SPACING, s.clockLetterSpacing)
        assertEquals(WallpaperSettings.MIN_OPACITY, s.clockOpacity)
        assertEquals(WallpaperSettings.MAX_DATE_SIZE, s.dateSizeSp)
        assertEquals(0, s.dateLetterSpacing)
        assertEquals(100, s.dateOpacity)
        assertEquals(0, s.datePositionX)
        assertEquals(100, s.datePositionY)
        assertNull(s.dateColor)
    }

    @Test fun dateColourFollowsTheClockUntilItIsOverridden() {
        assertNull(WallpaperSettings().dateColor)
        assertEquals(TextColors.SAND, WallpaperSettings(dateColor = TextColors.SAND).sanitized().dateColor)
        assertEquals(TextColors.WHITE, WallpaperSettings(textColor = TextColors.WHITE).sanitized().textColor)
    }

    @Test fun editorDefaultsKeepThePhaseTwoLook() {
        val defaults = WallpaperSettings()
        assertEquals(72, defaults.clockSizeSp)
        assertEquals(0, defaults.clockLetterSpacing)
        assertEquals(100, defaults.clockOpacity)
        assertTrue(defaults.showClock)
        assertEquals(17, defaults.dateSizeSp)
        assertEquals(0, defaults.dateLetterSpacing)
        assertEquals(100, defaults.dateOpacity)
        assertFalse(defaults.dateUppercase)
        assertEquals(50, defaults.datePositionX)
        assertEquals(38, defaults.datePositionY)
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
