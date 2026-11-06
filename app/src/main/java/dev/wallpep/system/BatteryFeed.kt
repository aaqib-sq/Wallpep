package dev.wallpep.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.wallpep.render.BatteryReading

/**
 * Keeps a live battery reading for the scene.
 *
 * The feed subscribes to the platform's battery broadcasts while it is running and pushes each
 * new reading to [onReading]. Every broadcast it listens to is public and needs **no
 * permission**:
 * - [Intent.ACTION_BATTERY_CHANGED] — the sticky broadcast the platform sends whenever the
 *   level or the charging state changes;
 * - [Intent.ACTION_POWER_CONNECTED] / [Intent.ACTION_POWER_DISCONNECTED] — the charger being
 *   plugged in or pulled out.
 *
 * The receiver is registered dynamically and only while the feed is running, so nothing
 * survives the screen that started it.
 */
class BatteryFeed(
    context: Context,
    private val onReading: (BatteryReading) -> Unit,
) {
    private val appContext = context.applicationContext
    private val reader = BatteryReader(appContext)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onReading(reader.read())
        }
    }

    /** Subscribes and delivers the current reading straight away. */
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        try {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (_: RuntimeException) {
            return
        }
        registered = true
        onReading(reader.read())
    }

    /** Unsubscribes. The feed can be started again afterwards. */
    fun stop() {
        if (!registered) return
        try { appContext.unregisterReceiver(receiver) } catch (_: RuntimeException) { }
        registered = false
    }
}
