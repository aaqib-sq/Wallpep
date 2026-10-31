package dev.wallpep.render

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTextTest {
    private val time = Date((13 * 60L + 5L) * 60_000L)

    private fun formatted(pattern: String, locale: Locale): ClockText {
        val formatter = SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return formatClock(formatter, time)
    }

    @Test fun hourRangeSurvivesTwelveHourSuffixAndTwentyFourHourFormat() {
        val twelve = formatted("h:mm a", Locale.US)
        assertEquals("1:05 PM", twelve.text)
        assertEquals("1", twelve.text.substring(twelve.hourStart, twelve.hourEnd))
        val twentyFour = formatted("HH:mm", Locale.US)
        assertEquals("13:05", twentyFour.text)
        assertEquals("13", twentyFour.text.substring(twentyFour.hourStart, twentyFour.hourEnd))
    }

    @Test fun hourRangeDoesNotAssumeColonEnglishDigitsOrAmPmPosition() {
        val japanese = formatted("a h時mm分", Locale.JAPAN)
        assertTrue(japanese.hasHour)
        assertEquals("1", japanese.text.substring(japanese.hourStart, japanese.hourEnd))
        assertTrue(japanese.hourStart > 0) // AM/PM precedes the hour.
        val arabic = formatted("HH:mm", Locale.forLanguageTag("ar"))
        assertTrue(arabic.hasHour)
        assertEquals(2, arabic.hourEnd - arabic.hourStart)
        assertTrue(arabic.text.contains(":"))
    }

    @Test fun missingHourFieldCanFallBackToAWholeClock() {
        val result = formatted("mm", Locale.US)
        assertEquals("05", result.text)
        assertFalse(result.hasHour)
    }
}
