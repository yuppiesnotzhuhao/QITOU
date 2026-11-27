package app.qitou.bridge

import android.media.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecPolicyTest {
    @Test
    fun keepsReferenceBitrateFor450MtCanvas() {
        val policy = VideoCodecPolicy.forEncoder(800, 386, 25)

        assertEquals(2_500_000, policy.bitrate)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel3, policy.avcLevel)
    }

    @Test
    fun usesLevel3For450MtCanvasAtThirtyFps() {
        val policy = VideoCodecPolicy.forEncoder(800, 386, 30)

        assertEquals(3_000_000, policy.bitrate)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel3, policy.avcLevel)
    }

    @Test
    fun usesLevel4For1080p30() {
        val policy = VideoCodecPolicy.forEncoder(1920, 1080, 30)

        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel4, policy.avcLevel)
        assertEquals(10_000_000, policy.bitrate)
    }

    @Test
    fun usesLevel32For720p60() {
        val policy = VideoCodecPolicy.forEncoder(1280, 720, 60)

        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel32, policy.avcLevel)
    }

    @Test
    fun accountsForFrameMacroblocksAtLevel3Boundary() {
        val policy = VideoCodecPolicy.forEncoder(720, 576, 30)

        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31, policy.avcLevel)
    }

    @Test
    fun bitrateIsBounded() {
        assertTrue(VideoCodecPolicy.forEncoder(320, 240, 10).bitrate >= 1_500_000)
        assertTrue(VideoCodecPolicy.forEncoder(1920, 1080, 60).bitrate <= 10_000_000)
    }
}
