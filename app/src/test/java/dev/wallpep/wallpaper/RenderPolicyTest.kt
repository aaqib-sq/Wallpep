package dev.wallpep.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPolicyTest {
    @Test fun engineRequiresItsOwnVisibilityAndUsableSurface() {
        val engine = RenderGate()
        assertFalse(engine.canRender)
        engine.visible = true
        assertFalse(engine.canRender)
        engine.surfaceAvailable = true
        engine.width = 1080
        assertFalse(engine.canRender)
        engine.height = 2400
        assertTrue(engine.canRender)
        engine.visible = false
        assertFalse(engine.canRender)
        engine.visible = true
        engine.surfaceAvailable = false
        assertFalse(engine.canRender)
    }

    @Test fun visiblePreviewDoesNotPermitHiddenHomeEngineToRender() {
        val home = RenderGate().apply { surfaceAvailable = true; width = 1080; height = 2400 }
        val preview = RenderGate().apply { visible = true; surfaceAvailable = true; width = 400; height = 800 }
        assertTrue(preview.canRender)
        assertFalse(home.canRender)
        preview.visible = false
        assertFalse(preview.canRender)
    }

    @Test fun screenOffLockedAndLockOnlyTargetsNeverRender() {
        val gate = RenderGate().apply { visible = true; surfaceAvailable = true; width = 10; height = 20 }
        gate.screenAwake = false
        assertFalse(gate.canRender)
        gate.screenAwake = true
        gate.unlocked = false
        assertFalse(gate.canRender)
        gate.unlocked = true
        gate.lockOnly = true
        assertFalse(gate.canRender)
        gate.lockOnly = false
        assertTrue(gate.canRender)
    }

    @Test fun minuteDelayIsAlignedAndDoesNotDrift() {
        assertEquals(60_000L, millisUntilNextMinute(0))
        assertEquals(59_999L, millisUntilNextMinute(1))
        assertEquals(1L, millisUntilNextMinute(59_999))
        assertEquals(60_000L, millisUntilNextMinute(60_000))
        assertEquals(1L, millisUntilNextMinute(-1))
    }

    @Test fun paletteNotificationsRequireMaterialChange() {
        val base = PaletteSignature(0xff202020.toInt(), null, null, 0)
        assertFalse(materiallyDifferent(base, base))
        assertFalse(materiallyDifferent(base, base.copy(primary = 0xff242424.toInt())))
        assertTrue(materiallyDifferent(base, base.copy(primary = 0xff402020.toInt())))
        assertTrue(materiallyDifferent(base, base.copy(secondary = 0xff202020.toInt())))
        assertTrue(materiallyDifferent(base, base.copy(hints = 1)))
    }
}
