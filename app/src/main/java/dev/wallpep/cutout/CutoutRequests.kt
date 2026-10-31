package dev.wallpep.cutout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class CutoutState { PROCESSING, READY, UNAVAILABLE }

/** Thread-safe single-flight registry shared by all activity and wallpaper-engine callers. */
internal class CutoutRequests {
    private val lock = Any()
    private val mutableStates = MutableStateFlow<Map<String, CutoutState>>(emptyMap())
    val states: StateFlow<Map<String, CutoutState>> = mutableStates.asStateFlow()

    fun claim(name: String): Boolean = synchronized(lock) {
        if (mutableStates.value.containsKey(name)) return@synchronized false
        mutableStates.value = mutableStates.value + (name to CutoutState.PROCESSING)
        true
    }

    fun complete(name: String, state: CutoutState) = synchronized(lock) {
        mutableStates.value = mutableStates.value + (name to state)
    }
}
