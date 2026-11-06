package dev.wallpep.render

import dev.wallpep.model.AnimationStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ClockTransitionsTest {
    private val selectable = AnimationStyle.entries.filter { it.selectable }

    @Test fun exactlyTheEighteenReferenceStylesAreSelectable() {
        assertEquals(18, selectable.size)
        assertEquals(
            listOf(
                "Fade & Scale", "Slide Up Fade", "Slide Down Fade", "Slide Left Fade", "Slide Right Fade",
                "Zoom In", "Zoom Out", "Pop In", "Spin In", "Rotate In", "Swing", "Unfold", "Glitch",
                "Heartbeat", "Drop Bounce", "Blur In", "Stretch Horizontal", "Stretch Vertical",
            ),
            selectable.map { it.label },
        )
    }

    @Test fun everyStyleRestsExactlyAtTheEndOfItsTransition() {
        for (style in AnimationStyle.entries) {
            val frame = ClockTransitions.frame(style, 1f)
            assertEquals(style.name, 1f, frame.alpha, 1e-4f)
            assertEquals(style.name, 1f, frame.scaleX, 1e-4f)
            assertEquals(style.name, 1f, frame.scaleY, 1e-4f)
            assertEquals(style.name, 0f, frame.dx, 1e-4f)
            assertEquals(style.name, 0f, frame.dy, 1e-4f)
            assertEquals(style.name, 0f, frame.rotation, 1e-3f)
            assertEquals(style.name, 0f, frame.blur, 1e-4f)
            assertEquals(style.name, 0f, frame.glitch, 1e-4f)
        }
    }

    @Test fun everySelectableStyleStartsHidden() {
        for (style in selectable) {
            val frame = ClockTransitions.frame(style, 0f)
            assertTrue(style.name, frame.alpha <= 0.02f)
        }
    }

    @Test fun durationsAreBoundedBurstsAndNoneNeverAnimates() {
        for (style in selectable) {
            val duration = ClockTransitions.durationMillis(style)
            assertTrue(style.name, duration in 300L..1200L)
            assertTrue(style.name, ClockTransitions.playable(style))
        }
        assertEquals(0L, ClockTransitions.durationMillis(AnimationStyle.NONE))
        assertFalse(ClockTransitions.playable(AnimationStyle.NONE))
        // Legacy document names still render a bounded curve instead of failing.
        for (style in AnimationStyle.entries.filter { !it.selectable && it != AnimationStyle.NONE }) {
            assertTrue(style.name, ClockTransitions.durationMillis(style) in 300L..1200L)
        }
    }

    @Test fun framesStayInSafeRangesAcrossTheWholeProgress() {
        for (style in AnimationStyle.entries) {
            for (step in 0..100) {
                val frame = ClockTransitions.frame(style, step / 100f)
                assertTrue(style.name, frame.alpha in 0f..1f)
                assertTrue(style.name, frame.scaleX in 0f..2f)
                assertTrue(style.name, frame.scaleY in 0f..2f)
                assertTrue(style.name, abs(frame.dx) <= 1.5f)
                assertTrue(style.name, abs(frame.dy) <= 1.5f)
                assertTrue(style.name, abs(frame.rotation) <= 360f)
                assertTrue(style.name, frame.blur in 0f..0.5f)
                assertTrue(style.name, frame.glitch in 0f..1f)
                assertTrue(style.name, frame.pivotX in 0f..1f)
                assertTrue(style.name, frame.pivotY in 0f..1f)
            }
        }
    }

    @Test fun progressOutsideZeroToOneIsClamped() {
        val early = ClockTransitions.frame(AnimationStyle.FADE_AND_SCALE, -2f)
        val late = ClockTransitions.frame(AnimationStyle.FADE_AND_SCALE, 5f)
        assertEquals(0f, early.alpha, 1e-6f)
        assertEquals(1f, late.alpha, 1e-6f)
    }

    @Test fun fadeAlphaOnlyGrows() {
        var previous = -1f
        for (step in 0..100) {
            val alpha = ClockTransitions.frame(AnimationStyle.FADE_AND_SCALE, step / 100f).alpha
            assertTrue(alpha >= previous - 1e-6f)
            previous = alpha
        }
    }

    @Test fun glitchOffsetsAreDeterministicBoundedAndVaried() {
        for (band in 0..8) {
            for (step in 0..20) {
                val value = ClockTransitions.glitchOffset(band, step)
                assertTrue(value >= -1f && value <= 1f)
                assertEquals(value, ClockTransitions.glitchOffset(band, step), 0f)
            }
        }
        assertTrue((0..8).map { ClockTransitions.glitchOffset(it, 3) }.distinct().size > 1)
    }

    @Test fun dropBounceIsContinuousAndSettlesAtRest() {
        assertEquals(-1.3f, ClockTransitions.dropOffset(0f), 1e-6f)
        assertEquals(0f, ClockTransitions.dropOffset(1f), 1e-6f)
        var previous = ClockTransitions.dropOffset(0f)
        for (step in 1..1000) {
            val current = ClockTransitions.dropOffset(step / 1000f)
            assertTrue(abs(current - previous) < 0.05f)
            previous = current
        }
        // The bounce actually leaves the resting line in between (it is not a plain slide).
        assertTrue((1..999).any { ClockTransitions.dropOffset(it / 1000f) < -0.05f })
    }

    @Test fun swingAndUnfoldHangFromTheTopEdge() {
        assertEquals(0f, ClockTransitions.frame(AnimationStyle.SWING, 0.4f).pivotY, 1e-6f)
        assertEquals(0f, ClockTransitions.frame(AnimationStyle.UNFOLD, 0.4f).pivotY, 1e-6f)
        assertEquals(0.5f, ClockTransitions.frame(AnimationStyle.FADE_AND_SCALE, 0.4f).pivotY, 1e-6f)
    }
}
