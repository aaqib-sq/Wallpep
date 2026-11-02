package dev.wallpep.storage

import dev.wallpep.model.WallpaperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignStoreTest {
    @Test fun generatedIdsAreAcceptedAndArbitraryOnesAreNot() {
        val id = DesignStore.newId()
        assertTrue(id, DesignStore.isSafeDesignId(id))
        assertFalse(DesignStore.isSafeDesignId(""))
        assertFalse(DesignStore.isSafeDesignId("design-../../etc/passwd"))
        assertFalse(DesignStore.isSafeDesignId("design-3b70bf09-554e-42fb-80dc-45f67be6733"))
        assertFalse(DesignStore.isSafeDesignId(id.uppercase()))
        assertFalse(DesignStore.isSafeDesignId("background-3b70bf09-554e-42fb-80dc-45f67be67335.img"))
    }

    @Test fun namesAreTrimmedCollapsedAndBounded() {
        assertEquals("Untitled wallpaper", DesignStore.cleanName(""))
        assertEquals("Untitled wallpaper", DesignStore.cleanName("   "))
        assertEquals("Quiet blue", DesignStore.cleanName("  Quiet   blue  "))
        assertEquals(40, DesignStore.cleanName("x".repeat(200)).length)
        assertEquals("Untitled wallpaper", DesignStore.cleanName("\n\t "))
    }

    @Test fun aSavedDesignAlwaysCarriesSanitizedSettings() {
        val design = SavedDesign(
            id = DesignStore.newId(),
            name = "  spaced   name ",
            settings = WallpaperSettings(clockSizeSp = 9999, backgroundName = "../evil.img"),
        )
        val clean = DesignStore.cleaned(design)
        assertEquals("spaced name", clean.name)
        assertEquals(WallpaperSettings.MAX_CLOCK_SIZE, clean.settings.clockSizeSp)
        assertEquals(null, clean.settings.backgroundName)
        assertFalse(clean.favorited)
        assertFalse(clean.inRotation)
    }
}
