package dev.wallpep.render

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorOpacityTest {
    @Test fun multipliesColorAndLayerAlphaWithoutChangingRgb() {
        assertEquals(0x00123456, multiplyColorAlpha(0x00123456, 255))
        assertEquals(0x80123456.toInt(), multiplyColorAlpha(0x80123456.toInt(), 255))
        assertEquals(0x40123456, multiplyColorAlpha(0x80123456.toInt(), 128))
        assertEquals(0x00123456, multiplyColorAlpha(0xff123456.toInt(), 0))
        assertEquals(0xff123456.toInt(), multiplyColorAlpha(0xff123456.toInt(), 999))
    }
}
