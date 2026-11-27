package app.qitou.bridge.carplus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlusProtocolTest {
    @Test
    fun authenticationMessagesUseCarLifeProtobufFieldNumbers() {
        assertArrayEquals(
            byteArrayOf(0x0a, 0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()),
            CarPlusProtocol.authenRequest("abc"),
        )
        assertArrayEquals(
            byteArrayOf(0x08, 0x01),
            CarPlusProtocol.authenResult(true),
        )
        assertArrayEquals(
            byteArrayOf(0x08, 0x00),
            CarPlusProtocol.authenResult(false),
        )
    }

    @Test
    fun commandFrameCarriesHuAuthenticationRequestType() {
        val payload = byteArrayOf(0x08, 0x01)
        val frame = CarPlusProtocol.command(CarPlusProtocol.HU_AUTHEN_REQUEST, payload)

        assertEquals(2, ((frame[0].toInt() and 0xff) shl 8) or (frame[1].toInt() and 0xff))
        assertEquals(98376, readInt(frame, 4))
        assertArrayEquals(payload, frame.copyOfRange(8, frame.size))
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
}
