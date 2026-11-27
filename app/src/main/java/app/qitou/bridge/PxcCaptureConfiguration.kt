package app.qitou.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PxcCaptureConfiguration(
    val width: Int,
    val height: Int,
    val fps: Int,
    val reportedFps: Int?,
    val encoder: Int,
    val extensionFlag: Int,
)

object PxcCaptureConfigurationParser {
    fun parse(body: ByteArray): PxcCaptureConfiguration {
        require(body.size >= 4) { "PXC capture payload is shorter than 4 bytes" }
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val rawWidth = buffer.getShort(0).toInt() and 0xffff
        val rawHeight = buffer.getShort(2).toInt() and 0xffff
        require(rawWidth in 240..1920) { "PXC capture width out of range: $rawWidth" }
        require(rawHeight in 240..1080) { "PXC capture height out of range: $rawHeight" }

        val reportedFps = if (body.size >= 8) buffer.getInt(4) else null
        val fps = reportedFps?.takeIf { it in 1..60 } ?: if (rawWidth == 800 && rawHeight == 386) 25 else 30
        val encoder = if (body.size >= 12) buffer.getInt(8).takeUnless { it == 0 } ?: 2 else 2
        return PxcCaptureConfiguration(
            width = rawWidth and -2,
            height = if (rawHeight % 2 == 0) rawHeight else rawHeight + 1,
            fps = fps,
            reportedFps = reportedFps,
            encoder = encoder,
            extensionFlag = if (body.size >= 30) body[29].toInt() and 0xff else 0,
        )
    }
}
