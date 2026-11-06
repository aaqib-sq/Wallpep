package dev.wallpep.model

/**
 * Persisted state of the collection auto-rotate cycle.
 *
 * [nextDueAt] is an epoch-millis instant: the cycle survives reboot because the remaining time is
 * stored in wall-clock time rather than in process-local elapsed time. [cursor] is the index
 * (into the stored collection order) of the design the live wallpaper should be showing.
 */
data class RotationState(
    val nextDueAt: Long = 0L,
    val cursor: Int = -1,
)

/**
 * What the wallpaper should do right now. Pure and deterministic; the caller persists [state].
 */
data class RotationDecision(
    /** True when the wallpaper must switch to the design at [index] now. */
    val due: Boolean,
    /** The state to persist (identical to the input when nothing changed). */
    val state: RotationState,
    /** Collection index of the design to show after this call; -1 when unchanged. */
    val index: Int,
    /** How many cycle positions were traversed at once (catch-up after a long absence). */
    val steps: Int,
)

object AutoRotate {
    const val HOUR_MS: Long = 3_600_000L

    /**
     * Decides whether the cycle advances at [now] (epoch millis).
     *
     * Collection order is the saved (storage) order: the array order of the collection document,
     * i.e. the order designs were saved in (a re-save moves the design to the end).
     *
     * With nothing saved the cycle does nothing at all — no crash, no fake rotation. With a zero
     * or negative interval the cycle is off. A never-scheduled cycle schedules one interval from
     * [now] instead of yanking the wallpaper on first sight. Time spent hidden is caught up in a
     * single decision by traversing whole elapsed intervals (modulo the collection size).
     */
    fun decide(now: Long, intervalHours: Int, collectionSize: Int, state: RotationState): RotationDecision {
        if (intervalHours <= 0 || collectionSize <= 0) {
            return RotationDecision(due = false, state = state.normalized(collectionSize), index = -1, steps = 0)
        }
        val cursor = state.cursor.takeIf { it in 0 until collectionSize } ?: -1
        val intervalMs = intervalHours.toLong() * HOUR_MS
        if (state.nextDueAt <= 0L) {
            return RotationDecision(
                due = false,
                state = RotationState(nextDueAt = now + intervalMs, cursor = cursor),
                index = -1,
                steps = 0,
            )
        }
        if (now < state.nextDueAt) {
            val normalizedDue = state.copy(cursor = cursor)
            return RotationDecision(due = false, state = normalizedDue, index = -1, steps = 0)
        }
        val steps = ((now - state.nextDueAt) / intervalMs).toInt() + 1
        val next = Math.floorMod(cursor.toLong() + steps, collectionSize.toLong()).toInt()
        val nextDue = state.nextDueAt + steps.toLong() * intervalMs
        return RotationDecision(due = true, state = RotationState(nextDue, next), index = next, steps = steps)
    }

    private fun RotationState.normalized(collectionSize: Int): RotationState =
        if (collectionSize <= 0) this else copy(cursor = cursor.takeIf { it in 0 until collectionSize } ?: -1)
}
