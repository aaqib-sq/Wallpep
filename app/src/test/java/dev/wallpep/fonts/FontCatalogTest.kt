package dev.wallpep.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FontCatalogTest {
    @Test fun libraryContainsEveryListedFamily() {
        assertEquals(112, FontCatalog.families.size)
        assertEquals(112, FontCatalog.families.map { it.id }.toSet().size)
        assertEquals(112, FontCatalog.families.map { it.label }.toSet().size)
        assertEquals(185, FontCatalog.families.sumOf { it.files })
    }

    @Test fun everyBundledFontAndItsLicenseIsInTheApkAssets() {
        val assets = assetsRoot()
        var files = 0
        FontCatalog.families.forEach { family ->
            val font = File(assets, family.assetPath)
            assertTrue("Missing bundled font ${family.assetPath}", font.isFile)
            assertTrue("Empty bundled font ${family.assetPath}", font.length() > 1_000)
            val license = File(assets, family.licenseAssetPath)
            assertTrue("Missing license ${family.licenseAssetPath}", license.isFile)
            assertTrue("Empty license ${family.licenseAssetPath}", license.length() > 100)
            assertTrue(family.assetPath.startsWith("fonts/${family.category.name.lowercase()}/"))
            files += File(assets, family.assetPath.substringBeforeLast('/')).listFiles()
                .orEmpty().count { it.name.endsWith(".ttf") }
        }
        assertEquals(185, files)
        val onDisk = File(assets, "fonts").walkTopDown().count { it.isFile && it.name.endsWith(".ttf") }
        assertEquals(185, onDisk)
    }

    @Test fun assetNamesStayPortable() {
        FontCatalog.families.forEach { family ->
            listOf(' ', '[', ']', ',', '(', ')').forEach { forbidden ->
                assertTrue("Unexpected '$forbidden' in ${family.assetPath}", !family.assetPath.contains(forbidden))
            }
            assertTrue(family.assetPath.endsWith(".ttf"))
        }
    }

    @Test fun storedTypefacesAreValidatedAndMigrated() {
        assertEquals("outfit", FontCatalog.sanitizeId(null))
        assertEquals("outfit", FontCatalog.sanitizeId("not-a-bundled-family"))
        assertEquals("outfit", FontCatalog.sanitizeId("outfit"))
        assertEquals("dmsans", FontCatalog.sanitizeId("DM_SANS"))
        assertEquals("system", FontCatalog.sanitizeId("SYSTEM"))
        assertEquals(FontCatalog.FOLLOW_CLOCK, FontCatalog.sanitizeDateId(null))
        assertEquals(FontCatalog.FOLLOW_CLOCK, FontCatalog.sanitizeDateId("unknown"))
        assertEquals("cinzel", FontCatalog.sanitizeDateId("Cinzel"))
        // The clock face may not silently follow itself.
        assertNotEquals(FontCatalog.FOLLOW_CLOCK, FontCatalog.sanitizeId(FontCatalog.FOLLOW_CLOCK))
    }

    @Test fun catalogLookupsAndLabelsBehave() {
        val outfit = FontCatalog.family("outfit")
        assertEquals("Outfit", outfit?.label)
        assertEquals(FontLicense.OFL, outfit?.license)
        assertNull(FontCatalog.family("missing"))
        assertNull(FontCatalog.assetPath("missing"))
        assertEquals("System sans", FontCatalog.displayName(FontCatalog.SYSTEM_ID))
        assertEquals("Same as clock", FontCatalog.displayName(FontCatalog.FOLLOW_CLOCK))
        assertEquals("Outfit", FontCatalog.displayName("outfit"))
        assertTrue(FontCatalog.search("cinzel").isNotEmpty())
        assertTrue(FontCatalog.search("mono").any { it.category == FontCategory.MONO })
        assertTrue(FontCatalog.search("").size == 112)
        assertTrue(FontCatalog.search("zzzz").isEmpty())
    }

    @Test fun apacheFamiliesKeepTheirApacheLicense() {
        val apache = FontCatalog.families.filter { it.license == FontLicense.APACHE }
        assertTrue(apache.isNotEmpty())
        apache.forEach { assertEquals("LICENSE-Apache-2.0.txt", it.licenseAssetPath.substringAfterLast('/')) }
        FontCatalog.families.filter { it.license == FontLicense.OFL }.forEach {
            assertEquals("OFL.txt", it.licenseAssetPath.substringAfterLast('/'))
        }
    }

    /** Unit tests run with the module (app/) as the working directory; fall back to the repository root. */
    private fun assetsRoot(): File {
        val start = File("").absoluteFile
        return generateSequence(start) { it.parentFile }
            .flatMap { listOf(File(it, "src/main/assets"), File(it, "app/src/main/assets")) }
            .firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main/assets from $start")
    }
}
