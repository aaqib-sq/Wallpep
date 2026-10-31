package dev.wallpep.cutout

import android.content.Context
import dev.wallpep.model.isSafeBackgroundName
import dev.wallpep.storage.BackgroundStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal enum class CutoutState { PROCESSING, READY, UNAVAILABLE }

/**
 * Shared by the activity and all engines in this process. Requests for the same photo coalesce,
 * and a single worker bounds peak memory. No service, wake lock, timer, or network is used.
 */
internal object CutoutCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val semaphore = Semaphore(1)
    private val lock = Any()
    private val mutableStates = MutableStateFlow<Map<String, CutoutState>>(emptyMap())
    val states: StateFlow<Map<String, CutoutState>> = mutableStates.asStateFlow()

    fun ensure(context: Context, name: String?) {
        if (name == null || !isSafeBackgroundName(name)) return
        synchronized(lock) {
            if (mutableStates.value.containsKey(name)) return
            mutableStates.value = mutableStates.value + (name to CutoutState.PROCESSING)
        }
        // Never retain an Activity/Engine. Work may finish across configuration changes, but
        // normal process death simply interrupts it; there is no background restart mechanism.
        val app = context.applicationContext
        scope.launch {
            val store = BackgroundStore(app)
            val state = try {
                semaphore.withPermit { makeOrFindCutout(app, store, name) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                CutoutState.UNAVAILABLE
            } catch (_: LinkageError) {
                // Includes a missing/unsupported native runtime on the device.
                CutoutState.UNAVAILABLE
            } catch (_: Exception) {
                CutoutState.UNAVAILABLE
            }
            if (state == CutoutState.UNAVAILABLE) {
                try {
                    withContext(Dispatchers.IO) { store.markCutoutFailure(name) }
                } catch (_: OutOfMemoryError) {
                    // Keep the process-local failure state even when storage is unavailable.
                } catch (_: Exception) {
                    // The current process still remembers the failed attempt if storage is full.
                }
            }
            synchronized(lock) {
                mutableStates.value = mutableStates.value + (name to state)
            }
        }
    }

    private suspend fun makeOrFindCutout(app: Context, store: BackgroundStore, name: String): CutoutState {
        val decision = withContext(Dispatchers.IO) {
            cacheDecision(store.exists(name), store.hasCutout(name), store.hasCutoutFailure(name))
        }
        when (decision) {
            CacheDecision.USE_CACHED -> return CutoutState.READY
            CacheDecision.SKIP -> return CutoutState.UNAVAILABLE
            CacheDecision.CREATE -> Unit
        }

        val photo = withContext(Dispatchers.IO) { store.decode(name, 2048) }
            ?: return CutoutState.UNAVAILABLE
        try {
            val foreground = ModNetCutout(app).createForeground(photo)
            try {
                val saved = withContext(Dispatchers.IO) { store.writeCutout(name, foreground) }
                return if (saved) CutoutState.READY else CutoutState.UNAVAILABLE
            } finally {
                foreground.recycle()
            }
        } finally {
            photo.recycle()
        }
    }
}
