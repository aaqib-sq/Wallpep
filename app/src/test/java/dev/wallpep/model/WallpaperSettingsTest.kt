package dev.wallpep.model

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

    @Test fun unknownEnumValuesUseDefaults() {
        assertEquals(TimeStyle.SYSTEM, storedEnum("FUTURE", TimeStyle.SYSTEM))
        assertEquals(TimeStyle.TWELVE_HOUR, storedEnum("twelve_hour", TimeStyle.SYSTEM))
        assertEquals(FontChoice.OUTFIT, storedEnum(null, FontChoice.OUTFIT))
        assertEquals(1, SETTINGS_SCHEMA_VERSION)
    }
}
