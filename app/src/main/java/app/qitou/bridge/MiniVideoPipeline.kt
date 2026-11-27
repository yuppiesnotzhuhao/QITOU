package app.qitou.bridge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Minimal Car+ H.264 decode -> 800x384 encode pipeline for the focused product. */
class MiniVideoPipeline(
    private val log: (String) -> Unit,
    private val sourceWidth: Int = 1280,
    private val sourceHeight: Int = 480,
    private val bottomInset: Int = 80,
) {
    private val compositor = AaCompositor(log)
    private val frames = LinkedBlockingDeque<ByteArray>(8)
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var running = false
    @Volatile private var awaitKeyframe = false
    @Volatile private var codecConfig: ByteArray? = null
    private var drain: Thread? = null

    val decoderInputSurface: Surface? get() = compositor.inputSurface

    fun start() {
        if (running) return
        running = true
        compositor.start(sourceWidth, sourceHeight)
        compositor.setForcedBottomInset(bottomInset)
        log("[MINI] source ${sourceWidth}x$sourceHeight, output 800x384, bottom $bottomInset")
    }

    fun configureBikeCanvas(width: Int = 800, height: Int = 384) {
        if (codec != null) return
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 900_000L)
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        compositor.setOutput(inputSurface!!, width, height, sourceWidth, sourceHeight, ScreenFit.FILL)
        drain = thread(name = "carplus-encode", isDaemon = true) { drainLoop(c) }
        log("[MINI] encoder started ${width}x$height")
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        compositor.setPreview(surface, width, height)
    }

    fun updatePreviewSize(width: Int, height: Int) {
        compositor.updatePreviewSize(width, height)
    }

    fun clearPreview() = compositor.clearPreview()

    fun onBikeDataStart() {
        frames.clear()
        awaitKeyframe = true
        requestKeyframe("bike attached")
    }

    fun pollFrame(timeoutMs: Long): ByteArray? =
        try {
            frames.pollFirst(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }

    fun requestKeyframe(reason: String) {
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            log("[MINI] IDR requested ($reason)")
        } catch (_: Exception) {
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                c.dequeueOutputBuffer(info, 100_000)
            } catch (error: Exception) {
                if (running) log("[MINI] encoder stopped: ${error.message}")
                break
            }
            if (index < 0) continue
            try {
                if (info.size > 0) {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codecConfig = bytes
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            if (!awaitKeyframe || key) {
                                if (key) awaitKeyframe = false
                                val accessUnit = if (key && codecConfig != null) codecConfig!! + bytes else bytes
                                if (!frames.offerLast(accessUnit)) {
                                    frames.pollFirst()
                                    frames.offerLast(accessUnit)
                                }
                            }
                        }
                    }
                }
            } finally {
                try {
                    c.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        running = false
        frames.clear()
        try {
            compositor.release()
        } catch (_: Exception) {
        }
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        inputSurface = null
    }
}
