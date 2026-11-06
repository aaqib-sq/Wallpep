package dev.wallpep.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battery element (#147).
 *
 * The rules under test: the element only draws a real level, it only draws while the phone is
 * on power, and it never replaces the clock unless the user turned it on.
 */
class BatteryElementTest {

    @Test fun thePercentageIsRenderedAsAWholeNumberWithASign() {
        assertEquals("87%", formatBatteryPercent(BatteryReading(87, ChargeState.CHARGING)))
        assertEquals("0%", formatBatteryPercent(BatteryReading(0, ChargeState.CHARGING)))
        assertEquals("100%", formatBatteryPercent(BatteryReading(100, ChargeState.FULL)))
    }

    @Test fun aMissingLevelIsNeverRenderedAsANumber() {
        assertNull(formatBatteryPercent(null))
        assertNull(formatBatteryPercent(BatteryReading(null, ChargeState.CHARGING)))
        assertNull(formatBatteryPercent(BatteryReading(null, ChargeState.UNKNOWN)))
    }

    @Test fun chargingAndFullBothMeanThePhoneIsOnPower() {
        assertTrue(ChargeState.CHARGING.onPower)
        assertTrue(ChargeState.FULL.onPower)
        assertFalse(ChargeState.NOT_CHARGING.onPower)
        assertFalse(ChargeState.UNKNOWN.onPower)
    }

    @Test fun theElementOnlyShowsWhileThePhoneIsOnPower() {
        assertTrue(BatteryReading(50, ChargeState.CHARGING).showsPercentage)
        assertTrue(BatteryReading(50, ChargeState.FULL).showsPercentage)
        assertFalse(BatteryReading(50, ChargeState.NOT_CHARGING).showsPercentage)
        assertFalse(BatteryReading(50, ChargeState.UNKNOWN).showsPercentage)
        assertFalse(BatteryReading(null, ChargeState.CHARGING).showsPercentage)
    }

    @Test fun theElementNeverReplacesTheClockWhileTheToggleIsOff() {
        val charging = BatteryReading(64, ChargeState.CHARGING)
        assertFalse(batteryReplacesClock(enabled = false, reading = charging))
        assertTrue(batteryReplacesClock(enabled = true, reading = charging))
    }

    @Test fun theElementNeverReplacesTheClockWithoutARealChargingReading() {
        assertFalse(batteryReplacesClock(enabled = true, reading = null))
        assertFalse(batteryReplacesClock(enabled = true, reading = BatteryReading.UNKNOWN))
        assertFalse(batteryReplacesClock(enabled = true, reading = BatteryReading(64, ChargeState.NOT_CHARGING)))
        assertFalse(batteryReplacesClock(enabled = true, reading = BatteryReading(null, ChargeState.CHARGING)))
    }
}
