package app.qitou.bridge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Car+ H.264 decode -> adaptive bike canvas encode pipeline. */
class MiniVideoPipeline(
    private val log: (String) -> Unit,
    sourceWidth: Int = 1280,
    sourceHeight: Int = 480,
) {
    @Volatile private var sourceWidth = sourceWidth
    @Volatile private var sourceHeight = sourceHeight
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
        log("[MINI] source ${sourceWidth}x$sourceHeight, output waits for bike canvas")
    }

    fun updateSourceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (sourceWidth == width && sourceHeight == height) return
        sourceWidth = width
        sourceHeight = height
        compositor.updateCaptureSize(width, height)
        log("[MINI] source size updated to ${width}x$height")
    }

    fun setFrameCap(fps: Int) {
        compositor.setFrameCap(fps)
    }

    @Synchronized
    fun configureBikeCanvas(
        width: Int = 800,
        height: Int = 386,
        fit: ScreenFit = ScreenFit.FIT,
        insets: CanvasInsets = CanvasInsets(),
        fps: Int = 30,
    ) {
        if (codec != null) return
        // H.264 4:2:0 requires even dimensions; the bike reports its native canvas at runtime.
        val encodeWidth = width.coerceAtLeast(2) and -2
        val encodeHeight = height.coerceAtLeast(2).let { if (it and 1 == 0) it else it + 1 }
        val safeFps = fps.coerceIn(1, 60)
        val policy = VideoCodecPolicy.forEncoder(encodeWidth, encodeHeight, safeFps)
        val candidates = encoderCandidates(encodeWidth, encodeHeight, safeFps)
        log(
            "[MINI] encoder candidates=" +
                candidates.joinToString {
                    "${it.name ?: "default"}:${it.hardware}:secure=${it.secure}"
                },
        )
        var c: MediaCodec? = null
        var selected: EncoderChoice? = null
        var surface: Surface? = null
        var lastFailure: Throwable? = null
        var explicitProfileLevel = true
        var profileLevelFallback = false
        for (useProfileLevel in booleanArrayOf(true, false)) {
            for (candidate in candidates) {
                try {
                    val configured = createEncoder(
                        candidate,
                        encodeWidth,
                        encodeHeight,
                        safeFps,
                        policy,
                        useProfileLevel,
                    )
                    c = configured.first
                    surface = configured.second
                    selected = candidate
                    explicitProfileLevel = useProfileLevel
                    profileLevelFallback = !useProfileLevel
                    break
                } catch (error: Exception) {
                    lastFailure = error
                    log(
                        "[MINI] encoder rejected ${candidate.name ?: "default"} " +
                            "hardware=${candidate.hardware} " +
                            "profileLevel=$useProfileLevel: ${error.message}",
                    )
                }
            }
            if (c != null) break
            if (useProfileLevel) {
                log("[MINI] all encoders rejected explicit profile/level; retrying codec defaults")
            }
        }
        val activeCodec = c
        val activeSurface = surface
        if (activeCodec == null || activeSurface == null) {
            throw (lastFailure ?: IllegalStateException("No surface H.264 encoder available"))
        }
        inputSurface = activeSurface
        c = activeCodec
        codec = c
        compositor.setOutput(
            activeSurface,
            encodeWidth,
            encodeHeight,
            sourceWidth,
            sourceHeight,
            fit,
            insets,
        )
        drain = thread(name = "carplus-encode", isDaemon = true) { drainLoop(c) }
        log(
            "[MINI] encoder started ${activeCodec.name} " +
                "hardware=${selected?.hardware == true} ${encodeWidth}x$encodeHeight@$safeFps " +
                "bitrate=${policy.bitrate} level=${policy.avcLevel} fit=$fit " +
                "insets=${insets.summary()} " +
                "explicitProfileLevel=$explicitProfileLevel " +
                "profileLevelFallback=$profileLevelFallback",
        )
        MiniState.updateEncoderCodec(
            "${activeCodec.name} · " +
                CodecCandidatePolicy.displayKind(selected?.hardware == true),
        )
    }

    fun updateCanvasLayout(fit: ScreenFit, insets: CanvasInsets) {
        compositor.updateCanvasLayout(fit, insets)
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        compositor.setPreview(surface, width, height)
    }

    fun updatePreviewSize(width: Int, height: Int) {
        compositor.updatePreviewSize(width, height)
    }

    fun clearPreview() = compositor.clearPreview()

    fun attachBikePreview(surface: Surface, width: Int, height: Int) {
        compositor.setBikePreview(surface, width, height)
    }

    fun updateBikePreviewSize(width: Int, height: Int) {
        compositor.updateBikePreviewSize(width, height)
    }

    fun clearBikePreview() = compositor.clearBikePreview()

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

    private data class EncoderChoice(
        val name: String?,
        val hardware: Boolean,
        val secure: Boolean,
    )

    private fun encoderCandidates(width: Int, height: Int, fps: Int): List<EncoderChoice> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
            .distinctBy { it.name }
        val surfaceCapable = infos.filter { supportsEncoderSurface(it, mime) }
        val sizeCapable = surfaceCapable.filter { supportsEncoderSizeRate(it, mime, width, height, fps) }
        return sizeCapable.ifEmpty { surfaceCapable }.ifEmpty { infos }
            .sortedWith(
                compareBy<MediaCodecInfo>(
                    { CodecCandidatePolicy.rank(it.name, it.isHardwareAccelerated) },
                    { it.name },
                ),
            )
            .map {
                EncoderChoice(
                    name = it.name,
                    hardware = it.isHardwareAccelerated,
                    secure = CodecCandidatePolicy.isSecure(it.name),
                )
            }
            .ifEmpty { listOf(EncoderChoice(null, false, false)) }
    }

    private fun supportsEncoderSurface(info: MediaCodecInfo, mime: String): Boolean =
        runCatching {
            info.getCapabilitiesForType(mime).colorFormats.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
        }.getOrDefault(false)

    private fun supportsEncoderSizeRate(
        info: MediaCodecInfo,
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
    ): Boolean = runCatching {
        val capabilities = info.getCapabilitiesForType(mime)
        val video = capabilities.videoCapabilities ?: return@runCatching true
        video.areSizeAndRateSupported(width, height, fps.toDouble()) ||
            video.isSizeSupported(width, height)
    }.getOrDefault(true)

    private fun createEncoder(
        choice: EncoderChoice,
        width: Int,
        height: Int,
        fps: Int,
        policy: VideoEncoderPolicy,
        useProfileLevel: Boolean,
    ): Pair<MediaCodec, Surface> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val codec = choice.name?.let(MediaCodec::createByCodecName)
            ?: MediaCodec.createEncoderByType(mime)
        try {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, policy.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (useProfileLevel) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                    )
                    setInteger(MediaFormat.KEY_LEVEL, policy.avcLevel)
                }
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 900_000L)
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            return codec to surface
        } catch (error: Exception) {
            runCatching { codec.release() }
            throw error
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
