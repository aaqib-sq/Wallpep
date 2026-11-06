package dev.wallpep.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRotateTest {
    private val hour = AutoRotate.HOUR_MS

    @Test fun anEmptyCollectionNeverRotates() {
        val decision = decide(now = 10_000L, intervalHours = 12, size = 0, state = RotationState())
        assertFalse(decision.due)
        assertEquals(-1, decision.index)
        assertEquals(0, decision.steps)
    }

    @Test fun zeroOrNegativeIntervalsTurnTheCycleOff() {
        val off = decide(now = 10_000L, intervalHours = 0, size = 3, state = RotationState(nextDueAt = 0L, cursor = 1))
        assertFalse(off.due)
        assertEquals(-1, off.index)
        val negative = decide(now = 10_000L, intervalHours = -4, size = 3, state = RotationState(0L, 2))
        assertFalse(negative.due)
        assertEquals(-1, negative.index)
    }

    @Test fun firstEvaluationSchedulesWithoutSwitching() {
        val decision = decide(now = 1_000L, intervalHours = 12, size = 2, state = RotationState())
        assertFalse(decision.due)
        assertEquals(1_000L + 12 * hour, decision.state.nextDueAt)
        assertEquals(-1, decision.state.cursor)
    }

    @Test fun waitsUntilTheDueInstant() {
        val state = RotationState(nextDueAt = 5_000L, cursor = 0)
        val decision = decide(now = 4_999L, intervalHours = 1, size = 3, state = state)
        assertFalse(decision.due)
        assertEquals(state, decision.state)
    }

    @Test fun dueAdvancesOnePositionAndReschedules() {
        val state = RotationState(nextDueAt = 5_000L, cursor = 0)
        val decision = decide(now = 5_000L, intervalHours = 1, size = 3, state = state)
        assertTrue(decision.due)
        assertEquals(1, decision.index)
        assertEquals(1, decision.state.cursor)
        assertEquals(5_000L + hour, decision.state.nextDueAt)
        assertEquals(1, decision.steps)
    }

    @Test fun overdueCatchUpTraversesWholeIntervalsOnly() {
        // now is 2 full intervals plus 500ms past the due instant: three boundaries are crossed
        // (the due instant itself plus two more), never a partial slot.
        val state = RotationState(nextDueAt = 1_000L, cursor = 0)
        val decision = decide(now = 1_000L + 2 * hour + 500L, intervalHours = 1, size = 2, state = state)
        assertTrue(decision.due)
        assertEquals(3, decision.steps)
        assertEquals(1, decision.index)
        assertEquals(1_000L + 3 * hour, decision.state.nextDueAt)
    }

    @Test fun cyclesThroughTheWholeCollectionInStoredOrder() {
        var state = RotationState(nextDueAt = 1_000L, cursor = -1)
        val order = mutableListOf<Int>()
        repeat(6) {
            val decision = decide(now = 1_000L + it * hour, intervalHours = 1, size = 3, state = state)
            assertTrue(decision.due)
            order.add(decision.index)
            state = decision.state
        }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), order)
    }

    @Test fun anOutOfRangeCursorIsNormalisedBeforeChoosing() {
        val decision = decide(now = 5_000L, intervalHours = 1, size = 3, state = RotationState(nextDueAt = 5_000L, cursor = 9))
        assertTrue(decision.due)
        // Unknown cursor: restart from the beginning of the stored order.
        assertEquals(0, decision.index)
    }

    @Test fun shrinkingTheCollectionBelowTheCursorRestartsSafely() {
        val decision = decide(now = 5_000L, intervalHours = 1, size = 2, state = RotationState(nextDueAt = 5_000L, cursor = 7))
        assertTrue(decision.due)
        assertEquals(0, decision.index)
    }

    private fun decide(now: Long, intervalHours: Int, size: Int, state: RotationState) =
        AutoRotate.decide(now, intervalHours, size, state)
}
