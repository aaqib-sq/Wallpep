package dev.wallpep.render

/**
 * The battery element (#147) — pure data and formatting, no Android types, so the whole
 * behaviour is unit-testable off device.
 *
 * The reference app reads the battery through a receiver and, while the phone charges, shows
 * the battery percentage in place of the clock or the date. Wallpep ships the same idea as an
 * optional element: the toggle is off by default and the value is always the real level the
 * system reports — never a hardcoded or simulated number.
 */

/** What the system says about the charger. */
enum class ChargeState {
    /** A charger is connected and the battery is still filling. */
    CHARGING,

    /** A charger is connected and the battery is already full. */
    FULL,

    /** No charger is connected. */
    NOT_CHARGING,

    /** The platform did not report a usable charging state. */
    UNKNOWN,
    ;

    /** True when the phone is on power, which is when the element may replace the clock. */
    val onPower: Boolean get() = this == CHARGING || this == FULL
}

/**
 * One battery reading: the real charge percentage (0..100) and the charging state.
 *
 * [percent] is null when the platform reported no usable level. A null level is never
 * substituted with a made-up number — the element simply does not draw.
 */
data class BatteryReading(
    val percent: Int?,
    val chargeState: ChargeState = ChargeState.UNKNOWN,
) {
    /** The element only draws for a real level while the phone is on power. */
    val showsPercentage: Boolean get() = percent != null && chargeState.onPower

    companion object {
        /** Nothing is known yet: no level, no charging state. */
        val UNKNOWN = BatteryReading(null, ChargeState.UNKNOWN)
    }
}

/**
 * The battery percentage as it is drawn, for example "87%".
 *
 * Returns null when there is no real level to show, so a missing reading can never be
 * rendered as a fake zero or a placeholder.
 */
fun formatBatteryPercent(reading: BatteryReading?): String? {
    val percent = reading?.percent ?: return null
    return "$percent%"
}

/**
 * Whether the battery element takes the clock's place.
 *
 * The element replaces the clock while the phone is charging, exactly as the reference app
 * does. With the toggle off — the default — the clock always draws and the element never
 * appears.
 */
fun batteryReplacesClock(enabled: Boolean, reading: BatteryReading?): Boolean =
    enabled && reading?.showsPercentage == true
