package app.qitou.bridge

import org.junit.Assert.assertTrue
import org.junit.Test

class CodecCandidatePolicyTest {
    @Test
    fun prefersCodec2HardwareBeforeLegacyOmx() {
        val codec2 = CodecCandidatePolicy.rank("c2.qti.avc.decoder", hardware = true)
        val omx = CodecCandidatePolicy.rank("OMX.qcom.video.decoder.avc", hardware = true)

        assertTrue(codec2 < omx)
    }

    @Test
    fun defersSecureAndLowLatencyVariants() {
        val normal = CodecCandidatePolicy.rank("c2.qti.avc.decoder", hardware = true)
        val lowLatency = CodecCandidatePolicy.rank(
            "c2.qti.avc.decoder.low_latency",
            hardware = true,
        )
        val secure = CodecCandidatePolicy.rank("c2.qti.avc.decoder.secure", hardware = true)

        assertTrue(normal < lowLatency)
        assertTrue(lowLatency < secure)
    }

    @Test
    fun keepsEveryHardwareCandidateBeforeSoftwareFallback() {
        val secureHardware = CodecCandidatePolicy.rank(
            "c2.qti.avc.decoder.secure",
            hardware = true,
        )
        val software = CodecCandidatePolicy.rank("c2.android.avc.decoder", hardware = false)

        assertTrue(secureHardware < software)
    }
}
