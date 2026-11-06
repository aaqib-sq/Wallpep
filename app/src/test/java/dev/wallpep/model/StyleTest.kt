package dev.wallpep.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleTest {
    @Test fun clockFormatsAreUniqueAndCountMatchesTheStructureFile() {
        val ids = ClockFormats.all.map { it.id }
        assertEquals(17, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ClockFormats.DEFAULT_ID, ClockFormats.sanitize(ClockFormats.DEFAULT_ID))
    }

    @Test fun unknownOrMissingClockFormatsFallBackWithoutBreakingSaves() {
        assertEquals(ClockFormats.DEFAULT_ID, ClockFormats.sanitize(null))
        assertEquals(ClockFormats.DEFAULT_ID, ClockFormats.sanitize("future-format"))
        assertEquals(ClockFormats.DEFAULT_ID, ClockFormats.sanitize(""))
        assertEquals("h:mm", ClockFormats.sanitize("h:mm"))
    }

    @Test fun onlyTheVerticalFormatsStackAndSaySo() {
        ClockFormats.all.filter { it.label.contains("vertical") }.forEach { format ->
            assertTrue("$format should stack", format.stacked)
        }
        // The structure file marks hh/mm as the one stacked format that is not called "vertical".
        assertEquals("hh/mm", ClockFormats.all.first { it.stacked && !it.label.contains("vertical") }.label)
        assertEquals(5, ClockFormats.all.count { it.stacked })
    }

    @Test fun secondsFormatsMentionSecondsAndTheRestDoNot() {
        ClockFormats.all.forEach { format ->
            assertEquals(format.label, format.withSeconds, format.label.contains("ss"))
        }
    }

    @Test fun secondsSeparatorIsIndependentOfTheHourMinuteSeparator() {
        assertEquals("", ClockFormats.format("hhmmss")?.secondsSeparator)
        assertEquals(" ", ClockFormats.format("hh:mm_ss")?.secondsSeparator)
        assertEquals(":", ClockFormats.format("hh:mm_ss")?.separator)
        assertEquals(" ", ClockFormats.format("hh_mm_ss")?.secondsSeparator)
    }

    @Test fun secondsFormatsDriveThePerSecondClockTick() {
        assertTrue(ClockFormats.needsSeconds("hh_mm_ss"))
        assertTrue(ClockFormats.needsSeconds("hhmmss"))
        assertTrue(ClockFormats.needsSeconds("hh:mm_ss_v"))
        assertFalse(ClockFormats.needsSeconds(ClockFormats.DEFAULT_ID))
        assertFalse(ClockFormats.needsSeconds(ClockFormats.SYSTEM))
        assertFalse(ClockFormats.needsSeconds("future-format"))
        assertFalse(ClockFormats.needsSeconds(null))
    }

    @Test fun everyDateFormatIsAValidJvmPattern() {
        val date = Date(0L)
        DateFormats.all.forEach { format ->
            assertEquals(format.id, format.label)
            SimpleDateFormat(format.id, Locale.US).format(date)
        }
        assertEquals(11, DateFormats.all.size)
        assertEquals(DateFormats.all.size, DateFormats.all.map { it.id }.toSet().size)
    }

    @Test fun unknownDateFormatFallsBackToTheGrantedDefault() {
        assertEquals("dd MMM yyyy", DateFormats.DEFAULT_ID)
        assertEquals(DateFormats.DEFAULT_ID, DateFormats.pattern(null))
        assertEquals(DateFormats.DEFAULT_ID, DateFormats.pattern(""))
        assertEquals(DateFormats.DEFAULT_ID, DateFormats.pattern("yyy-MM"))
        assertEquals("yyyy-MM-dd", DateFormats.pattern("yyyy-MM-dd"))
    }

    @Test fun strokeAndShadowValuesAreClamped() {
        val stroke = StrokeSettings(width = 900, gradientAngle = -20, color = 7).sanitized()
        assertEquals(StrokeSettings.MAX_WIDTH, stroke.width)
        assertEquals(0, stroke.gradientAngle)
        assertEquals(7, stroke.color)
        val shadow = ShadowSettings(blur = 400, offsetX = -900, offsetY = 900, opacity = 500).sanitized()
        assertEquals(ShadowSettings.MAX_BLUR, shadow.blur)
        assertEquals(ShadowSettings.MIN_OFFSET, shadow.offsetX)
        assertEquals(ShadowSettings.MAX_OFFSET, shadow.offsetY)
        assertEquals(100, shadow.opacity)
    }

    @Test fun transformValuesAreClamped() {
        val transform = TransformSettings(
            perspectiveX = 400,
            perspectiveY = -400,
            rotation = 900,
            stretch = 10,
        ).sanitized()
        assertEquals(TransformSettings.MAX_ANGLE, transform.perspectiveX)
        assertEquals(TransformSettings.MIN_ANGLE, transform.perspectiveY)
        assertEquals(359, transform.rotation)
        assertEquals(TransformSettings.MIN_STRETCH, transform.stretch)
        assertEquals(100, TransformSettings().stretch)
    }

    @Test fun textLayerCannotPersistUnsafeOrUnboundedValues() {
        val settings = WallpaperSettings(
            text = TextLayerSettings(
                content = "x".repeat(500),
                sizeSp = 400,
                opacity = 0,
                positionX = -10,
                positionY = 400,
                letterSpacing = 99,
                color = 0,
                font = "not-a-font",
                gradientAngle = 999,
            ),
        ).sanitized()
        assertEquals(TextLayerSettings.MAX_CONTENT, settings.text.content.length)
        assertEquals(TextLayerSettings.MAX_SIZE, settings.text.sizeSp)
        assertEquals(WallpaperSettings.MIN_OPACITY, settings.text.opacity)
        assertEquals(0, settings.text.positionX)
        assertEquals(100, settings.text.positionY)
        assertEquals(WallpaperSettings.MAX_LETTER_SPACING, settings.text.letterSpacing)
        assertEquals(360, settings.text.gradientAngle)
        assertEquals(0, settings.text.color)
        assertFalse(settings.text.font == "not-a-font")
    }

    @Test fun everyArgbColourIsPreserved() {
        TextColors.all.forEach { color ->
            assertEquals(color, TextColors.sanitize(color, TextColors.WHITE))
        }
        assertEquals  (0x000000, TextColors.sanitize(0x000000, TextColors.WHITE))
        assertEquals(TextColors.all.size, TextColors.labels.size)
        assertNotEquals(TextColors.WHITE, TextColors.BLUE)
        assertEquals("Electric blue", TextColors.label(TextColors.BLUE))
    }

    @Test fun effectAndAnimationChoicesKeepTheirPersistedNames() {
        assertEquals(EffectStyle.NONE, storedEnum("NONE", EffectStyle.SAKURA))
        assertEquals(EffectStyle.SAKURA, storedEnum("sakura", EffectStyle.NONE))
        assertEquals(EffectStyle.NONE, storedEnum("HURRICANE", EffectStyle.NONE))
        assertEquals(AnimationStyle.FADE_AND_SCALE, storedEnum(null, AnimationStyle.FADE_AND_SCALE))
        assertEquals(18, AnimationStyle.entries.count { it.selectable })
        assertEquals(AnimationStyle.entries.size, AnimationStyle.entries.map { it.label }.toSet().size)
    }
}
