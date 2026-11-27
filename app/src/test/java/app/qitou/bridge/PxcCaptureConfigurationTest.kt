package app.qitou.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PxcCaptureConfigurationTest {
    @Test
    fun parsesNegotiatedSizeFpsEncoderAndExtension() {
        val body = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 800)
            putShort(2, 386)
            putInt(4, 25)
            putInt(8, 2)
            put(29, 7)
        }.array()

        val config = PxcCaptureConfigurationParser.parse(body)

        assertEquals(800, config.width)
        assertEquals(386, config.height)
        assertEquals(25, config.fps)
        assertEquals(2, config.encoder)
        assertEquals(7, config.extensionFlag)
    }

    @Test
    fun alignsOddHeightToEvenEncoderHeight() {
        val body = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 1024)
            putShort(2, 515)
        }.array()

        val config = PxcCaptureConfigurationParser.parse(body)

        assertEquals(1024, config.width)
        assertEquals(516, config.height)
        assertEquals(30, config.fps)
    }

    @Test
    fun rejectsInvalidCanvasSize() {
        val body = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 100)
            putShort(2, 100)
        }.array()

        assertThrows(IllegalArgumentException::class.java) {
            PxcCaptureConfigurationParser.parse(body)
        }
    }
}
