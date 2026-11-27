package app.qitou.bridge

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class PxcFrameSafetyTest {
    @Test
    fun rejectsOversizedControlFrameBeforeAllocation() {
        val cmd = PxcFrame.CMD_CLIENT_INFO
        val totalLen = 1_048_577
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, cmd)
            putInt(4, totalLen)
            putInt(8, cmd xor totalLen)
        }.array()

        assertThrows(IllegalArgumentException::class.java) {
            PxcFrame.read(ByteArrayInputStream(header))
        }
    }

    @Test
    fun rejectsFrameShorterThanHeader() {
        val cmd = PxcFrame.CMD_CLIENT_INFO
        val totalLen = 15
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, cmd)
            putInt(4, totalLen)
            putInt(8, cmd xor totalLen)
        }.array()

        assertThrows(IllegalArgumentException::class.java) {
            PxcFrame.read(ByteArrayInputStream(header))
        }
    }
}
