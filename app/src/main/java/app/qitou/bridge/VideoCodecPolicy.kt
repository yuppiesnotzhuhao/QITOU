package app.qitou.bridge

import android.media.MediaCodecInfo

data class VideoEncoderPolicy(
    val bitrate: Int,
    val avcLevel: Int,
)

object VideoCodecPolicy {
    private const val REFERENCE_BITRATE = 2_500_000L
    private const val MIN_BITRATE = 1_500_000L
    private const val MAX_BITRATE = 10_000_000L
    private const val REFERENCE_WIDTH = 800L
    private const val REFERENCE_HEIGHT = 386L
    private const val REFERENCE_FPS = 25L

    private data class AvcLevelSpec(
        val level: Int,
        val maxFrameMacroblocks: Long,
        val maxMacroblocksPerSecond: Long,
        val maxBitrate: Long,
    )

    // Baseline/Main-profile limits from the H.264 level table. The lowest level is Level 3
    // because the motorcycle canvas and all supported CarLife source sizes fit there or above.
    private val AVC_LEVELS = listOf(
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel3,
            maxFrameMacroblocks = 1_620,
            maxMacroblocksPerSecond = 40_500,
            maxBitrate = 10_000_000,
        ),
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel31,
            maxFrameMacroblocks = 3_600,
            maxMacroblocksPerSecond = 108_000,
            maxBitrate = 14_000_000,
        ),
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel32,
            maxFrameMacroblocks = 5_120,
            maxMacroblocksPerSecond = 216_000,
            maxBitrate = 20_000_000,
        ),
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            maxFrameMacroblocks = 8_192,
            maxMacroblocksPerSecond = 245_760,
            maxBitrate = 20_000_000,
        ),
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel41,
            maxFrameMacroblocks = 8_192,
            maxMacroblocksPerSecond = 245_760,
            maxBitrate = 50_000_000,
        ),
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel42,
            maxFrameMacroblocks = 8_704,
            maxMacroblocksPerSecond = 522_240,
            maxBitrate = 50_000_000,
        ),
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel5,
            maxFrameMacroblocks = 22_080,
            maxMacroblocksPerSecond = 589_824,
            maxBitrate = 135_000_000,
        ),
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel51,
            maxFrameMacroblocks = 36_864,
            maxMacroblocksPerSecond = 983_040,
            maxBitrate = 240_000_000,
        ),
        AvcLevelSpec(
            MediaCodecInfo.CodecProfileLevel.AVCLevel52,
            maxFrameMacroblocks = 36_864,
            maxMacroblocksPerSecond = 2_073_600,
            maxBitrate = 240_000_000,
        ),
    )

    fun forEncoder(width: Int, height: Int, fps: Int): VideoEncoderPolicy {
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        val safeFps = fps.coerceIn(1, 60)
        val scaled = REFERENCE_BITRATE * safeWidth * safeHeight * safeFps /
            (REFERENCE_WIDTH * REFERENCE_HEIGHT * REFERENCE_FPS)
        val bitrate = scaled.coerceIn(MIN_BITRATE, MAX_BITRATE)
        val macroblocksPerFrame =
            ((safeWidth + 15) / 16).toLong() * ((safeHeight + 15) / 16).toLong()
        val macroblocksPerSecond = macroblocksPerFrame * safeFps
        val level = AVC_LEVELS.firstOrNull { spec ->
            macroblocksPerFrame <= spec.maxFrameMacroblocks &&
                macroblocksPerSecond <= spec.maxMacroblocksPerSecond &&
                bitrate <= spec.maxBitrate
        }?.level ?: MediaCodecInfo.CodecProfileLevel.AVCLevel52
        return VideoEncoderPolicy(
            bitrate = bitrate.toInt(),
            avcLevel = level,
        )
    }
}
