package app.qitou.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFrameRateTest {
    @Test
    fun targets60FpsByDefault() {
        assertEquals(60, VideoFrameRate.MAX_FPS)
        assertEquals(16L, VideoFrameRate.FRAME_INTERVAL_MS)
        assertEquals(16L, VideoFrameRate.intervalMs(60))
    }
}
