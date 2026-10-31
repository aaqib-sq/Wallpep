package dev.wallpep.render

import java.text.AttributedCharacterIterator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/** The hour range is identified by formatting fields, not by assuming a colon or ASCII digits. */
internal data class ClockText(val text: String, val hourStart: Int, val hourEnd: Int) {
    val hasHour: Boolean get() = hourStart >= 0 && hourEnd > hourStart
}

internal fun formatClock(formatter: SimpleDateFormat, date: Date): ClockText {
    val iterator = formatter.formatToCharacterIterator(date)
    val hourFields = listOf(
        DateFormat.Field.HOUR0, DateFormat.Field.HOUR1,
        DateFormat.Field.HOUR_OF_DAY0, DateFormat.Field.HOUR_OF_DAY1,
    )
    val text = StringBuilder()
    var start = -1
    var end = -1
    var character = iterator.first()
    while (character != AttributedCharacterIterator.DONE) {
        val offset = iterator.index - iterator.beginIndex
        if (hourFields.any { iterator.getAttribute(it) != null }) {
            if (start < 0) start = offset
            end = offset + 1
        }
        text.append(character)
        character = iterator.next()
    }
    return ClockText(text.toString(), start, end)
}
