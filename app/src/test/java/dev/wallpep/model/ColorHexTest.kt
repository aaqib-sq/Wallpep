package dev.wallpep.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorHexTest {
    @Test fun rgbAndArgbRoundTripIncludingTransparentColors() {
        assertEquals(0xff123456.toInt(), ColorHex.parse("#123456"))
        assertEquals(0x00123456, ColorHex.parse("00123456"))
        assertEquals(0x80123456.toInt(), ColorHex.parse("  #80123456  "))
        listOf(0, -1, 0x80123456.toInt(), 0xffaabbcc.toInt()).forEach {
            assertEquals(it, ColorHex.parse(ColorHex.format(it)))
        }
    }

    @Test fun incompleteOrInvalidValuesAreRejected() {
        listOf("", "#123", "12345z", "#123456789", "#-12345").forEach {
            assertNull(ColorHex.parse(it))
        }
    }

    @Test fun arbitraryColorsAndGradientAnglesSurviveSanitization() {
        val color = 0x40123456
        val s = WallpaperSettings(
            textColor = color, clockSecondaryColor = color, secondsColor = color,
            dateColor = color, dateSecondaryColor = color, effectColor = color,
            clockStroke = StrokeSettings(color = color, secondaryColor = color, secondsColor = color),
            dateShadow = ShadowSettings(color = color),
            text = TextLayerSettings(color = color, secondaryColor = color, gradientAngle = 225),
        ).sanitized()
        assertEquals(color, s.textColor)
        assertEquals(color, s.dateColor)
        assertEquals(color, s.clockStroke.secondsColor)
        assertEquals(color, s.dateShadow.color)
        assertEquals(color, s.text.secondaryColor)
        assertEquals(color, s.effectColor)
        assertEquals(225, s.text.gradientAngle)
        assertEquals(360, s.text.copy(gradientAngle = 999).sanitized().gradientAngle)
    }
}
