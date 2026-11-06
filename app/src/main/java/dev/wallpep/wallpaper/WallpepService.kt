package dev.wallpep.wallpaper

import android.app.KeyguardManager
import android.app.WallpaperColors
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import kotlin.math.abs
import dev.wallpep.cutout.CutoutCoordinator
import dev.wallpep.cutout.CutoutState
import dev.wallpep.model.AutoRotate
import dev.wallpep.model.ClockFormats
import dev.wallpep.model.EffectStyle
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.render.WallpaperScene
import dev.wallpep.storage.BackgroundStore
import dev.wallpep.storage.DesignStore
import dev.wallpep.storage.RotationStore
import dev.wallpep.storage.SavedDesign
import dev.wallpep.storage.SettingsRepository
import dev.wallpep.system.BatteryReader
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
        private val designStore by lazy { DesignStore(applicationContext) }
        private val rotationStore by lazy { RotationStore(applicationContext) }
        @Volatile private var baseSettings: WallpaperSettings? = null
        @Volatile private var collectionDesigns: List<SavedDesign> = emptyList()
        @Volatile private var rotationCursor: Int = -1
        private var sensorManager: SensorManager? = null
        private var sensorListener: SensorEventListener? = null
        private var lastTiltX = 0f
        private var lastTiltY = 0f
        private var particlesArmed = false
        private var secondsArmed = false
        // Identity of the settings document the scene last received; a *new* document (first
        // load after becoming visible, an editor save, an auto-rotate switch) is the "wallpaper
        // applied / returned to" moment that arms an entrance transition.
        private var lastAppliedSettings: WallpaperSettings? = null

        private val fallbackColors = WallpaperColors(Color.valueOf(0xff263f34.toInt()), null, null)
        @Volatile private var cachedColors: WallpaperColors = fallbackColors
        private var cachedSignature: PaletteSignature = signatureOf(fallbackColors)

        private val frameCallback = Choreographer.FrameCallback {
            framePosted = false
            if (!running || !gate.canRender) return@FrameCallback
            // Defensive against delayed OEM visibility callbacks around screen-off/locking.
            // A locked device still draws: the lock screen is a surface Wallpep renders on
            // (#145), and the keyguard state only decides which content that surface shows.
            if (!power.isInteractive) {
                readScreenState()
                reconcile()
                return@FrameCallback
            }
            // The keyguard can change without a broadcast (a device that locks while the
            // screen stays on), so the state that names the visible surface is re-read here,
            // before the frame is drawn.
            applyContentGate()
            val holder = surfaceHolder
            var canvas: android.graphics.Canvas? = null
            var drewActiveTransition = false
            try {
                if (!holder.surface.isValid) return@FrameCallback
                canvas = holder.lockCanvas() ?: return@FrameCallback
                // Visibility can only change on the main thread, but check again at the draw site.
                if (running && gate.canRender && power.isInteractive) {
                    scene.draw(canvas)
                    drewActiveTransition = scene.transitionActive
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
            // Entrance transitions (#150, #151) are bounded wall-timed bursts: keep vsync frames
            // coming only while the scene reports an active transition, then the engine is idle
            // again until its next event. No always-on animation loop exists.
            if (drewActiveTransition) requestFrame()
        }

        private val minuteTick = object : Runnable {
            override fun run() {
                if (!running || !gate.canRender) return
                activeScope?.launch {
                    evaluateRotation(System.currentTimeMillis())
                    applyEffectiveSettings()
                }
                requestFrame()
                scheduleMinute()
            }
        }

        // Particles need continuous frames; the loop only exists while the engine is visible.
        private val particleTick = object : Runnable {
            override fun run() {
                if (!running || !gate.canRender) {
                    particlesArmed = false
                    return
                }
                val style = effectiveSettings().effectStyle
                if (style != EffectStyle.NONE && power.isInteractive) {
                    requestFrame()
                    main.postDelayed(this, FRAME_INTERVAL_MS)
                } else {
                    particlesArmed = false
                }
            }
        }

        // Seconds-bearing clock formats need one wall-clock-aligned frame per second. This loop
        // only requests frames; rotation and settings work still belongs to the minute tick, so
        // no DataStore read happens at 1 Hz. It never runs while the engine is hidden.
        private val secondTick = object : Runnable {
            override fun run() {
                secondsArmed = false
                if (!running || !gate.canRender) return
                if (!ClockFormats.needsSeconds(effectiveSettings().clockFormat)) return
                requestFrame()
                secondsArmed = true
                main.postDelayed(this, millisUntilNextSecond(System.currentTimeMillis()))
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
                // The keyguard state names the visible surface for a shared engine, and it can
                // change without a frame being pending (a device that locks or unlocks without
                // a screen-off/on pair). Re-apply the gate and draw the surface again.
                if (running && gate.canRender) {
                    applyContentGate()
                    requestFrame()
                }
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

        /**
         * The battery feed for the battery element (#147).
         *
         * `ACTION_BATTERY_CHANGED` is the sticky broadcast the platform sends whenever the
         * level or the charging state changes; the two power broadcasts cover the moments
         * where a charger is plugged in or pulled out. All three are public broadcasts that
         * need **no permission**, and the receiver only exists while this engine is visible.
         */
        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!running || !gate.canRender) return
                updateBatteryReading()
            }
        }
        private var batteryReceiverRegistered = false
        private val batteryReader by lazy { BatteryReader(applicationContext) }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            // API 34+: the engine reports which screen it renders to. Below that there is only
            // one surface, so the engine is shared and the keyguard state decides.
            if (Build.VERSION.SDK_INT >= 34) updateTarget(currentWallpaperFlags())
        }

        /** The engine's own wallpaper flags; API 34+ only. */
        @androidx.annotation.RequiresApi(34)
        private fun currentWallpaperFlags(): Int = getWallpaperFlags()

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
            // #145, #146: remember which surface this engine serves so the content layers can
            // be gated per surface.
            //
            // API 34+ reports the engine's own wallpaper flags: FLAG_SYSTEM alone means the
            // home screen, FLAG_LOCK alone means the lock screen, and both together means one
            // engine serves both surfaces (the default, and the only option before Android
            // 14). A shared engine is resolved with the keyguard state instead — see
            // SurfacePolicy for why that is the only signal available there.
            surfaceTarget = if (isPreview) {
                SurfaceTarget.SHARED
            } else {
                surfaceTargetFromFlags(which, supported = Build.VERSION.SDK_INT >= 34)
            }
            applyContentGate()
            reconcile()
        }

        /** The surface this engine draws for; SHARED until the platform says otherwise. */
        private var surfaceTarget: SurfaceTarget = SurfaceTarget.SHARED

        /**
         * Applies the "Show content on lockscreen" / "Show content on homescreen" switches to
         * the scene. The wallpaper image is never gated; only the clock, the date and the text
         * layer are.
         */
        private fun applyContentGate(settings: WallpaperSettings? = null) {
            // The surface switches belong to the document the engine is actually showing, so
            // an auto-rotated design keeps its own choice.
            val effective = settings ?: baseSettings ?: return
            // The keyguard state is read live: it is the only signal that names the visible
            // surface for an engine that serves both, and it can change without a broadcast.
            val keyguardLocked = keyguard.isKeyguardLocked
            if (keyguardLocked != !gate.unlocked) {
                gate.unlocked = !keyguardLocked
            }
            val visible = contentVisibleFor(
                target = surfaceTarget,
                showOnLockscreen = effective.showOnLockscreen,
                showOnHomescreen = effective.showOnHomescreen,
                keyguardLocked = keyguardLocked,
            )
            if (scene.contentVisible == visible) return
            scene.contentVisible = visible
            // The scene is drawn straight into the surface, so a plain invalidate() would not
            // reach the screen: the engine has to ask for the next frame itself.
            requestFrame()
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
            registerBatteryReceiver()
            rotationCursor = -1
            baseSettings = null
            lastAppliedSettings = null
            collectionDesigns = emptyList()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            activeScope = scope
            scope.launch {
                try {
                    settingsRepository.snapshots.collect { snapshot ->
                        if (!running || !gate.canRender) return@collect
                        baseSettings = snapshot.settings
                        evaluateRotation(System.currentTimeMillis())
                        applyEffectiveSettings()
                        evaluateParallaxSensor()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Storage failures leave the safe fallback; never log image paths or data.
                    Log.w(TAG, "Could not read wallpaper settings")
                }
            }
            scope.launch {
                try {
                    designStore.designs.collect { designs ->
                        if (!running || !gate.canRender) return@collect
                        collectionDesigns = designs
                        evaluateRotation(System.currentTimeMillis())
                        applyEffectiveSettings()
                        evaluateParallaxSensor()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Log.w(TAG, "Could not read the saved collection")
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
            armParticleLoop()
        }

        private fun effectiveSettings(): WallpaperSettings {
            baseSettings?.let { base ->
                if (base.autoRotateIntervalHours > 0) {
                    // The cursor survives reboots through RotationStore, so the engine resumes
                    // on the rotating design instead of reverting to the last hand-edited one.
                    collectionDesigns.getOrNull(rotationCursor)?.let { return it.settings }
                }
            }
            return baseSettings ?: WallpaperSettings()
        }

        /**
         * Advances the collection cycle when it is due and persists the next due instant.
         * Called from the settings/collection collectors, the minute tick and startRendering.
         */
        private suspend fun evaluateRotation(now: Long) {
            val base = baseSettings ?: return
            val designs = collectionDesigns
            val intervalHours = base.autoRotateIntervalHours
            if (intervalHours <= 0 || designs.isEmpty()) {
                if (rotationCursor != -1 && intervalHours <= 0) rotationCursor = -1
                return
            }
            val stored = rotationStore.read()
            val normalized = stored.copy(cursor = stored.cursor.takeIf { it in designs.indices } ?: -1)
            val decision = AutoRotate.decide(now, intervalHours, designs.size, normalized)
            if (decision.state != normalized) rotationStore.write(decision.state)
            rotationCursor = decision.state.cursor
        }

        private fun applyEffectiveSettings() {
            if (!running || !gate.canRender) return
            val effective = effectiveSettings()
            scene.settings = effective
            // #145, #146: the two surface switches decide what the current surface may show.
            applyContentGate(effective)
            if (effective !== lastAppliedSettings) {
                lastAppliedSettings = effective
                // The apply/return-to-home entrance moment. Whether an animation actually plays
                // is the scene's decision (Enable clock animations + the selected style); this
                // only arms it for the next drawn frame.
                scene.playEntranceAnimation()
            }
            if (!requestedImage || lastImageName != effective.backgroundName) {
                requestedImage = true
                lastImageName = effective.backgroundName
                loadedReadyCutout = effective.backgroundName?.let {
                    CutoutCoordinator.states.value[it] == CutoutState.READY
                } == true
                loadBackground(effective.backgroundName)
                // Also migrates a Phase 1 photo when the visible engine starts, even if the
                // settings activity has not been opened since the update.
                CutoutCoordinator.ensure(applicationContext, effective.backgroundName)
            }
            armParticleLoop()
            armSecondsLoop()
            requestFrame()
        }

        /** Arms (or disarms) the per-second clock loop when the active format changes. */
        private fun armSecondsLoop() {
            if (!running || !gate.canRender || !ClockFormats.needsSeconds(effectiveSettings().clockFormat)) {
                main.removeCallbacks(secondTick)
                secondsArmed = false
                return
            }
            if (secondsArmed) return
            secondsArmed = true
            main.postDelayed(secondTick, millisUntilNextSecond(System.currentTimeMillis()))
        }

        private fun armParticleLoop() {
            if (particlesArmed) return
            if (!running || !gate.canRender) return
            if (effectiveSettings().effectStyle == EffectStyle.NONE) return
            particlesArmed = true
            main.postDelayed(particleTick, FRAME_INTERVAL_MS)
        }

        private fun evaluateParallaxSensor() {
            val effective = effectiveSettings()
            val wantsSensor = effective.parallaxLayersEnabled || effective.parallaxWallpaperEnabled
            if (!running || !gate.canRender || !wantsSensor) {
                unregisterSensor()
                return
            }
            if (sensorListener != null) return
            val manager = getSystemService(SensorManager::class.java) ?: return
            val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: return
            sensorManager = manager
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = onTiltSensor(sensor, event.values)
                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
            }
            // SENSOR_DELAY_UI ≈ 16 Hz; plenty for a soft parallax, gentle on the battery.
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            sensorListener = listener
        }

        private fun unregisterSensor() {
            val listener = sensorListener ?: return
            try { sensorManager?.unregisterListener(listener) } catch (_: Exception) { }
            sensorListener = null
            sensorManager = null
            lastTiltX = 0f
            lastTiltY = 0f
        }

        private fun onTiltSensor(sensor: Sensor, values: FloatArray) {
            if (!running || !gate.canRender) return
            val tiltX: Float
            val tiltY: Float
            if (sensor.type == Sensor.TYPE_GRAVITY) {
                val gravity = 9.80665f
                val gx = values.getOrElse(0) { 0f } / gravity
                val gy = values.getOrElse(1) { 0f } / gravity
                val pair = tiltedPair(displayRotation(), gx, gy)
                tiltX = pair.first
                tiltY = pair.second
            } else {
                val rotationMatrix = FloatArray(9)
                try {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                } catch (_: IllegalArgumentException) {
                    return
                }
                val adjusted = FloatArray(9)
                when (displayRotation()) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjusted)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjusted)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjusted)
                    else -> System.arraycopy(rotationMatrix, 0, adjusted, 0, 9)
                }
                val orientation = FloatArray(3)
                SensorManager.getOrientation(adjusted, orientation)
                tiltX = (orientation[2] / MAX_TILT_RADIANS).coerceIn(-1f, 1f)
                tiltY = (orientation[1] / MAX_TILT_RADIANS).coerceIn(-1f, 1f)
            }
            // Critically damped-ish smoothing so the wallpaper glides instead of jittering.
            val smoothX = 0.65f * lastTiltX + 0.35f * tiltX
            val smoothY = 0.65f * lastTiltY + 0.35f * tiltY
            if (abs(smoothX - lastTiltX) < 0.004f && abs(smoothY - lastTiltY) < 0.004f) return
            lastTiltX = smoothX
            lastTiltY = smoothY
            scene.setTilt(smoothX, smoothY)
            requestFrame()
        }

        private fun tiltedPair(rotation: Int, gx: Float, gy: Float): Pair<Float, Float> = when (rotation) {
            Surface.ROTATION_90 -> gy to -gx
            Surface.ROTATION_270 -> -gy to gx
            Surface.ROTATION_180 -> -gx to -gy
            else -> gx to -gy
        }

        private fun displayRotation(): Int = try {
            this@WallpepService.display?.rotation ?: Surface.ROTATION_0
        } catch (_: Exception) {
            Surface.ROTATION_0
        }

        /**
         * Subscribes to the battery broadcasts while this engine is visible.
         *
         * The battery element is off by default, so the subscription only exists while the
         * engine renders at all; the level is read from [BatteryManager], which needs no
         * permission.
         */
        private fun registerBatteryReceiver() {
            if (batteryReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            try {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } catch (_: RuntimeException) {
                return
            }
            batteryReceiverRegistered = true
            updateBatteryReading()
        }

        private fun unregisterBatteryReceiver() {
            if (!batteryReceiverRegistered) return
            try { unregisterReceiver(batteryReceiver) } catch (_: RuntimeException) { }
            batteryReceiverRegistered = false
        }

        /** Reads the real battery level and charging state and hands them to the scene. */
        private fun updateBatteryReading() {
            val reading = batteryReader.read()
            val previous = scene.batteryReading
            if (previous?.percent == reading.percent &&
                previous?.chargeState == reading.chargeState
            ) {
                return
            }
            scene.batteryReading = reading
            requestFrame()
        }

        private fun stopRendering() {
            if (!running) return
            running = false
            generation++
            lastAppliedSettings = null
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
            unregisterBatteryReceiver()
            main.removeCallbacks(particleTick)
            particlesArmed = false
            main.removeCallbacks(secondTick)
            secondsArmed = false
            unregisterSensor()
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
                        catch (_: OutOfMemoryError) { fallbackColors }
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

    companion object {
        private const val TAG = "Wallpep"
        private const val FRAME_INTERVAL_MS = 33L
        // ~34 degrees of physical tilt reaches full parallax travel.
        private const val MAX_TILT_RADIANS = 0.6f
    }
}
