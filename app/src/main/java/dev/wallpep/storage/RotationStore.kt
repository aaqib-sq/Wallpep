package dev.wallpep.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wallpep.model.RotationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.autoRotateStore by preferencesDataStore(name = "wallpep_auto_rotate")

/**
 * Global, wall-clock state of the collection cycle — deliberately separate from the per-design
 * settings document so rotation survives a reboot and never rewrites what the user is editing.
 * Kept in its own DataStore so a damaged collection can never take the rotation clock with it.
 */
class RotationStore(context: Context) {
    private val store = context.applicationContext.autoRotateStore

    val state: Flow<RotationState> = store.data.map { prefs ->
        RotationState(
            nextDueAt = prefs[NEXT_DUE_AT] ?: 0L,
            cursor = prefs[CURSOR] ?: -1,
        )
    }

    suspend fun read(): RotationState = try {
        state.first()
    } catch (_: Exception) {
        RotationState()
    }

    suspend fun write(state: RotationState) {
        store.edit { prefs ->
            prefs[NEXT_DUE_AT] = state.nextDueAt
            prefs[CURSOR] = state.cursor
        }
    }

    private companion object {
        val NEXT_DUE_AT = androidx.datastore.preferences.core.longPreferencesKey("next_due_at")
        val CURSOR = intPreferencesKey("cursor")
    }
}
