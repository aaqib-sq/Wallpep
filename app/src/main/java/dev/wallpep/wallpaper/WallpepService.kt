package dev.wallpep.wallpaper

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.View
import dev.wallpep.cutout.CutoutCoordinator
import dev.wallpep.cutout.CutoutState
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.render.WallpaperScene
import dev.wallpep.storage.BackgroundStore
import dev.wallpep.storage.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Original View-on-Surface implementation. No animation loop, timers, or layers survive hiding. */
class WallpepService : WallpaperService() {
    private val engines = mutableSetOf<ClockEngine>()
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val backgrounds by lazy { BackgroundStore(applicationContext) }

    override fun onCreateEngine(): Engine = ClockEngine().also { engines.add(it) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engines.toList().forEach { it.configurationChanged() }
    }

    private inner class ClockEngine : Engine() {
        private val gate = RenderGate()
        private val main = Handler(Looper.getMainLooper())
        private val choreographer = Choreographer.getInstance()
        private val scene = WallpaperScene(this@WallpepService)
        private val power = getSystemService(PowerManager::class.java)
        private val keyguard = getSystemService(KeyguardManager::class.java)
        private var screenMonitorRegistered = false
        private var signalsRegistered = false
        private var running = false
        private var framePosted = false
        private var activeScope: CoroutineScope? = null
        private var imageJob: Job? = null
        private var generation = 0L
        private var requestedImage = false
        private var lastImageName: String? = null
        private var loadedReadyCutout = false

        private val fallbackColors = WallpaperColors(Color.valueOf(0xff263f34.toInt()), null, null)
        @Volatile private var cachedColors: WallpaperColors = fallbackColors
        private var cachedSignature: PaletteSignature = signatureOf(fallbackColors)

        private val frameCallback = Choreographer.FrameCallback {
            framePosted = false
            if (!running || !gate.canRender) return@FrameCallback
            // Defensive against delayed OEM visibility callbacks around screen-off/locking.
            if (!power.isInteractive || keyguard.isKeyguardLocked) {
                readScreenState()
                reconcile()
                return@FrameCallback
            }
            val holder = surfaceHolder
            var canvas: android.graphics.Canvas? = null
            try {
                if (!holder.surface.isValid) return@FrameCallback
                canvas = holder.lockCanvas() ?: return@FrameCallback
                // Visibility can only change on the main thread, but check again at the draw site.
                if (running && gate.canRender && power.isInteractive && !keyguard.isKeyguardLocked) {
                    scene.draw(canvas)
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Wallpaper surface became unavailable")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Wallpaper surface became unavailable")
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: IllegalArgumentException) { }
                }
            }
        }

        private val minuteTick = object : Runnable {
            override fun run() {
                if (!running || !gate.canRender) return
                requestFrame()
                scheduleMinute()
            }
        }

        // This receiver stays only while this engine is reported visible and has a surface.
        // It lets us release layers immediately on screen-off even if an OEM delays visibility.
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    gate.screenAwake = false
                } else {
                    readScreenState()
                }
                reconcile()
            }
        }
        private val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (running && gate.canRender) {
                    requestFrame()
                    scheduleMinute()
                }
            }
        }
        private val timeFormatObserver = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                if (running && gate.canRender) requestFrame()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            if (Build.VERSION.SDK_INT >= 34) updateTarget(wallpaperFlags)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            gate.surfaceAvailable = true
            gate.width = holder.surfaceFrame.width()
            gate.height = holder.surfaceFrame.height()
            reconcile()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            gate.surfaceAvailable = true
            gate.width = width
            gate.height = height
            layoutScene()
            reconcile()
            requestFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            requestFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            gate.surfaceAvailable = false
            reconcile()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            gate.visible = visible
            reconcile()
        }

        @androidx.annotation.RequiresApi(34)
        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            updateTarget(which)
        }

        private fun updateTarget(which: Int) {
            // API 34+ can identify lock-only engines. Shared home+lock engines cannot be
            // distinguished per surface; users must choose Home screen in the system chooser.
            gate.lockOnly = !isPreview && (which and WallpaperManager.FLAG_LOCK) != 0 &&
                (which and WallpaperManager.FLAG_SYSTEM) == 0
            reconcile()
        }

        fun configurationChanged() {
            if (running && gate.canRender) {
                requestFrame()
                scheduleMinute()
            }
        }

        override fun onComputeColors(): WallpaperColors = cachedColors

        override fun onDestroy() {
            gate.visible = false
            gate.surfaceAvailable = false
            reconcile()
            engines.remove(this)
            super.onDestroy()
        }

        private fun readScreenState() {
            gate.screenAwake = power.isInteractive
            gate.unlocked = !keyguard.isKeyguardLocked
        }

        private fun reconcile() {
            val needsMonitor = gate.visible && gate.surfaceAvailable && gate.width > 0 && gate.height > 0
            if (!needsMonitor) {
                stopRendering()
                if (screenMonitorRegistered) {
                    unregisterReceiver(screenReceiver)
                    screenMonitorRegistered = false
                }
                return
            }
            if (!screenMonitorRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                screenMonitorRegistered = true
                readScreenState()
            }
            if (gate.canRender) startRendering() else stopRendering()
        }

        private fun startRendering() {
            if (running) return
            running = true
            requestedImage = false
            layoutScene()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
            registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.TIME_12_24), false, timeFormatObserver,
            )
            signalsRegistered = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            activeScope = scope
            scope.launch {
                try {
                    settingsRepository.snapshots.collect { snapshot ->
                        if (!running || !gate.canRender) return@collect
                        val settings: WallpaperSettings = snapshot.settings
                        scene.settings = settings
                        if (!requestedImage || lastImageName != settings.backgroundName) {
                            requestedImage = true
                            lastImageName = settings.backgroundName
                            loadedReadyCutout = settings.backgroundName?.let {
                                CutoutCoordinator.states.value[it] == CutoutState.READY
                            } == true
                            loadBackground(settings.backgroundName)
                            // Also migrates a Phase 1 photo when the visible engine starts, even
                            // if the settings activity has not been opened since the update.
                            CutoutCoordinator.ensure(applicationContext, settings.backgroundName)
                        }
                        requestFrame()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Storage failures leave the safe fallback; never log image paths or data.
                    Log.w(TAG, "Could not read wallpaper settings")
                }
            }
            scope.launch {
                CutoutCoordinator.states.collect { states ->
                    val name = lastImageName
                    if (running && gate.canRender && requestedImage && name != null &&
                        !loadedReadyCutout && states[name] == CutoutState.READY) {
                        loadedReadyCutout = true
                        loadBackground(name)
                    }
                }
            }
            requestFrame()
            scheduleMinute()
        }

        private fun stopRendering() {
            if (!running) return
            running = false
            generation++
            if (framePosted) choreographer.removeFrameCallback(frameCallback)
            framePosted = false
            main.removeCallbacks(minuteTick)
            imageJob?.cancel()
            imageJob = null
            activeScope?.cancel()
            activeScope = null
            if (signalsRegistered) {
                unregisterReceiver(timeReceiver)
                contentResolver.unregisterContentObserver(timeFormatObserver)
                signalsRegistered = false
            }
            // Per-engine scaled bitmap is never retained while hidden or surface-less.
            scene.release()
        }

        private fun layoutScene() {
            if (gate.width <= 0 || gate.height <= 0) return
            scene.measure(
                View.MeasureSpec.makeMeasureSpec(gate.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(gate.height, View.MeasureSpec.EXACTLY),
            )
            scene.layout(0, 0, gate.width, gate.height)
        }

        private fun requestFrame() {
            if (!running || !gate.canRender || framePosted) return
            framePosted = true
            choreographer.postFrameCallback(frameCallback)
        }

        private fun scheduleMinute() {
            main.removeCallbacks(minuteTick)
            if (running && gate.canRender) {
                main.postDelayed(minuteTick, millisUntilNextMinute(System.currentTimeMillis()))
            }
        }

        private fun loadBackground(name: String?) {
            imageJob?.cancel()
            generation++
            val ticket = generation
            if (name == null) {
                scene.setImages(null, null)
                updateColors(fallbackColors)
                requestFrame()
                return
            }
            val scope = activeScope ?: return
            imageJob = scope.launch(Dispatchers.IO) {
                var bitmap: Bitmap? = null
                var foreground: Bitmap? = null
                var transferred = false
                try {
                    val decoded = backgrounds.decode(name, 2048)
                    bitmap = decoded
                    if (!isActive) return@launch
                    if (decoded != null) foreground = backgrounds.decodeCutout(name, 2048)
                    if (!isActive) return@launch
                    val colors = if (decoded != null) {
                        try { WallpaperColors.fromBitmap(decoded) ?: fallbackColors }
                        catch (_: RuntimeException) { fallbackColors }
                    } else fallbackColors
                    // NonCancellable is used only to dispose or transfer the bitmaps on main.
                    // The ticket and gate prevent a late decode from reviving a hidden engine.
                    withContext(Dispatchers.Main.immediate + NonCancellable) {
                        if (ticket == generation && running && gate.canRender) {
                            scene.setImages(decoded, foreground)
                            transferred = true
                            updateColors(colors)
                            requestFrame()
                        }
                    }
                } finally {
                    if (!transferred) {
                        bitmap?.recycle()
                        foreground?.recycle()
                    }
                }
            }
        }

        private fun updateColors(candidate: WallpaperColors?) {
            val safe = candidate ?: fallbackColors
            val signature = signatureOf(safe)
            if (materiallyDifferent(cachedSignature, signature)) {
                cachedColors = safe
                cachedSignature = signature
                notifyColorsChanged()
            }
        }

        private fun signatureOf(colors: WallpaperColors) = PaletteSignature(
            primary = colors.primaryColor?.toArgb() ?: 0xff263f34.toInt(),
            secondary = colors.secondaryColor?.toArgb(),
            tertiary = colors.tertiaryColor?.toArgb(),
            hints = colors.colorHints,
        )
    }

    companion object { private const val TAG = "Wallpep" }
}
