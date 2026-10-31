package dev.wallpep.cutout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModNetInputTest {
    @Test fun inputDimensionsKeepOrientationAreBoundedAndMatchStride() {
        val portrait = modelSize(1080, 2400)
        assertEquals(ModelSize(448, 1024), portrait)
        assertEquals(ModelSize(1024, 448), modelSize(2400, 1080))
        assertEquals(ModelSize(512, 512), modelSize(100, 100))
        for ((width, height) in listOf(1 to 5000, 5000 to 1, 43 to 57, 2048 to 2048)) {
            val size = modelSize(width, height)
            assertTrue(size.width in 32..1024)
            assertTrue(size.height in 32..1024)
            assertEquals(0, size.width % 32)
            assertEquals(0, size.height % 32)
        }
    }

    @Test fun rgbAndContinuousAlphaMatchTheModelContract() {
        assertEquals(-1f, normalizedChannel(0), 0.00001f)
        assertEquals(1f, normalizedChannel(255), 0.00001f)
        assertEquals(0, matteAlpha(-0.5f))
        assertEquals(128, matteAlpha(0.5f))
        assertEquals(255, matteAlpha(2f))
        assertFalse(0.5f.isNaN())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMatteDoesNotBecomeACorruptForeground() {
        matteAlpha(Float.NaN)
    }

    @Test fun migrationAndFailureAreOneShotDecisions() {
        assertEquals(CacheDecision.CREATE, cacheDecision(true, false, false))
        assertEquals(CacheDecision.USE_CACHED, cacheDecision(true, true, false))
        assertEquals(CacheDecision.USE_CACHED, cacheDecision(true, true, true))
        assertEquals(CacheDecision.SKIP, cacheDecision(true, false, true))
        assertEquals(CacheDecision.SKIP, cacheDecision(false, true, false))
    }
}
