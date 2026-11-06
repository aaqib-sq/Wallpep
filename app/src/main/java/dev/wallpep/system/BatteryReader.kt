package dev.wallpep.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.wallpep.render.BatteryReading
import dev.wallpep.render.ChargeState

/**
 * Reads the real battery level and charging state from the platform.
 *
 * Everything here is a public system API that needs **no permission**:
 * - the level comes from [BatteryManager.getIntProperty] with
 *   [BatteryManager.BATTERY_PROPERTY_CAPACITY], which reports the remaining capacity as a
 *   whole percentage;
 * - the charging state comes from [BatteryManager.isCharging], and the sticky
 *   [Intent.ACTION_BATTERY_CHANGED] broadcast is used as the fallback so a level and a state
 *   arrive together.
 *
 * The reader never invents a number: when the platform reports no usable level the reading
 * carries a null percentage and the element simply does not draw.
 */
class BatteryReader(context: Context) {

    private val appContext = context.applicationContext
    private val batteryManager: BatteryManager? =
        appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    /** The filter used for the sticky battery broadcast; it needs no permission. */
    private val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    /**
     * The current reading.
     *
     * The percentage comes from [BatteryManager.getIntProperty], which returns
     * [Int.MIN_VALUE] when the platform cannot answer; anything outside 0..100 is treated as
     * "no answer" rather than as a level. The sticky [Intent.ACTION_BATTERY_CHANGED] broadcast
     * is the fallback for both the level and the state on the rare device where the manager
     * cannot answer, so a reading always describes the same moment.
     */
    fun read(): BatteryReading {
        val sticky = stickyBatteryIntent()
        val percent = readPercent() ?: percentFromBroadcast(sticky)
        val state = readChargeState(percent) ?: chargeStateFromBroadcast(sticky)
        return BatteryReading(percent, state)
    }

    private fun readPercent(): Int? {
        val manager = batteryManager ?: return null
        val capacity = try {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: RuntimeException) {
            return null
        }
        // Integer.MIN_VALUE is the documented "no answer" sentinel on modern releases; a
        // value outside 0..100 is not a battery level either.
        return if (capacity in 0..100) capacity else null
    }

    /**
     * The charging state from [BatteryManager.isCharging], or null when the manager cannot
     * answer. A plugged-in device whose battery is no longer filling is full, not charging.
     */
    private fun readChargeState(percent: Int?): ChargeState? {
        val manager = batteryManager ?: return null
        val charging = try {
            manager.isCharging
        } catch (_: RuntimeException) {
            return null
        }
        if (!charging) return ChargeState.NOT_CHARGING
        return if (percent == 100) ChargeState.FULL else ChargeState.CHARGING
    }

    /**
     * The charging state from the sticky battery broadcast. `EXTRA_STATUS` reports
     * CHARGING, FULL, DISCHARGING, NOT_CHARGING or UNKNOWN; only the first two mean the
     * phone is on power.
     */
    private fun chargeStateFromBroadcast(intent: Intent?): ChargeState {
        intent ?: return ChargeState.UNKNOWN
        return when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeState.CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeState.FULL
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                ChargeState.NOT_CHARGING
            else -> ChargeState.UNKNOWN
        }
    }

    /**
     * The sticky battery broadcast, or null when the platform has none. It is registered with
     * a null receiver, which reads the sticky value without subscribing to anything.
     *
     * The export flag is passed even though `ACTION_BATTERY_CHANGED` is a protected system
     * broadcast: the platform only *requires* the flag for receivers that are not exclusively
     * system broadcasts, and passing it is harmless and keeps the call uniform.
     */
    fun stickyBatteryIntent(): Intent? = try {
        appContext.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
    } catch (_: RuntimeException) {
        null
    }

    /** The percentage from the sticky broadcast, used when [BatteryManager] cannot answer. */
    private fun percentFromBroadcast(intent: Intent?): Int? {
        intent ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = (level * 100f / scale).toInt()
        return percent.coerceIn(0, 100)
    }
}
