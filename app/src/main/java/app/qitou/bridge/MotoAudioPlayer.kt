package app.qitou.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Plays CarLife's media (9240) and navigation (9241) PCM tracks on an explicitly
 * selected output. Android does not provide a public per-app API to force arbitrary
 * media to an A2DP device, but AudioTrack.setPreferredDevice is the supported
 * best-effort media-routing mechanism.
 */
class MotoAudioPlayer(
    context: Context,
    private val log: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val audioLock = Any()
    private var media: AudioTrack? = null
    private var tts: AudioTrack? = null
    private var mediaRate = 0
    private var mediaChannels = 0
    private var ttsRate = 0
    private var ttsChannels = 0
    private var lastRestore: Runnable? = null
    private var activeRouteId = -1
    private var ducking = false
    @Volatile private var settings = AudioVolumeStore.current

    private val settingsListener: (AudioVolumeSettings) -> Unit = { value ->
        main.post {
            settings = value
            log(
                "[AUDIO] mix media=${value.mediaPercent}% " +
                    "navigation=${value.navigationPercent}% " +
                    "duck=${value.duckMediaDuringNavigation} " +
                    "route=${effectiveRoute().storageValue}" +
                    if (MiniState.localCarLife) " forced=local_carlife" else "",
            )
            refreshRouteAndVolumes()
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            main.post(::refreshRouteAndVolumes)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            main.post(::refreshRouteAndVolumes)
        }
    }

    init {
        AudioVolumeStore.attach(context)
        settings = AudioVolumeStore.current
        AudioVolumeStore.listener = settingsListener
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        refreshRouteAndVolumes()
    }

    fun write(isTts: Boolean, pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (pcm.isEmpty()) return
        synchronized(audioLock) {
            val device = resolveExplicitDevice()
            if (effectiveRoute() == AudioOutputRoute.BIKE_BLUETOOTH && device == null) {
                releaseTracksLocked()
                publishRouteUnavailable()
                return
            }
            applyRouteLocked(device)
            try {
                if (isTts) {
                    ensureTtsLocked(sampleRate, channels, device)
                    val written = tts?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) ?: 0
                    if (written > 0) {
                        MiniState.setAudio(
                            "${routeLabel()} · 导航播报 · ${sampleRate / 1000} kHz",
                            Stage.OK,
                        )
                        duckMedia()
                    }
                } else {
                    ensureMediaLocked(sampleRate, channels, device)
                    val written = media?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) ?: 0
                    if (written > 0) {
                        MiniState.setAudio(
                            "${routeLabel()} · 媒体音频 · ${sampleRate / 1000} kHz",
                            Stage.OK,
                        )
                    }
                }
            } catch (error: Exception) {
                log("[AUDIO] PCM write failed: ${error.javaClass.simpleName}: ${error.message}")
                MiniState.setAudio("音频输出异常，正在恢复", Stage.ERROR)
                if (isTts) {
                    runCatching { tts?.release() }
                    tts = null
                    ttsRate = 0
                    ttsChannels = 0
                } else {
                    runCatching { media?.release() }
                    media = null
                    mediaRate = 0
                    mediaChannels = 0
                }
            }
        }
    }

    private fun ensureMediaLocked(rate: Int, channels: Int, device: AudioDeviceInfo?) {
        if (media != null && mediaRate == rate && mediaChannels == channels) return
        runCatching { media?.release() }
        mediaRate = rate
        mediaChannels = channels
        media = create(rate, channels, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, device)
        applyMediaVolume()
        log(
            "[AUDIO] media track ${rate}Hz ${channels}ch volume=" +
                "${(settings.mediaGain * 100).toInt()}%",
        )
        log("[AUDIO] media ${rate}Hz ${channels}ch via ${routeLabel()}")
        if (media == null) MiniState.setAudio("${routeLabel()} · 媒体输出不可用", Stage.ERROR)
    }

    private fun ensureTtsLocked(rate: Int, channels: Int, device: AudioDeviceInfo?) {
        if (tts != null && ttsRate == rate && ttsChannels == channels) return
        runCatching { tts?.release() }
        ttsRate = rate
        ttsChannels = channels
        tts = create(
            rate,
            channels,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.CONTENT_TYPE_SPEECH,
            device,
        )
        applyNavigationVolume()
        log(
            "[AUDIO] navigation track ${rate}Hz ${channels}ch volume=" +
                "${(settings.navigationGain * 100).toInt()}%",
        )
        log("[AUDIO] navigation ${rate}Hz ${channels}ch via ${routeLabel()}")
        if (tts == null) MiniState.setAudio("${routeLabel()} · 导航输出不可用", Stage.ERROR)
    }

    private fun create(
        rate: Int,
        channels: Int,
        usage: Int,
        contentType: Int,
        device: AudioDeviceInfo?,
    ): AudioTrack? {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            log("[AUDIO] unsupported ${rate}Hz ${channels}ch")
            return null
        }
        return runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(min * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (device != null) {
                if (!track.setPreferredDevice(device)) {
                    track.release()
                    error("preferred route rejected: ${routeLabel()}")
                }
            }
            track.play()
            track
        }.onFailure {
            log("[AUDIO] AudioTrack ${rate}Hz ${channels}ch failed: ${it.message}")
        }.getOrNull()
    }

    private fun refreshRouteAndVolumes() {
        synchronized(audioLock) {
            val device = resolveExplicitDevice()
            if (effectiveRoute() == AudioOutputRoute.BIKE_BLUETOOTH && device == null) {
                releaseTracksLocked()
                publishRouteUnavailable()
                return
            }
            applyRouteLocked(device)
            applyNavigationVolume()
            applyMediaVolume()
            if (media == null && tts == null) {
                MiniState.setAudio("${routeLabel()} · 等待音频", Stage.WAIT)
            }
        }
    }

    private fun resolveExplicitDevice(): AudioDeviceInfo? {
        if (effectiveRoute() != AudioOutputRoute.BIKE_BLUETOOTH) return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .sortedByDescending {
                it.productName?.toString()?.contains("CFMOTO", ignoreCase = true) == true
            }
            .firstOrNull()
    }

    private fun applyRouteLocked(device: AudioDeviceInfo?) {
        val routeId = device?.id ?: DEFAULT_ROUTE_ID
        if (activeRouteId == routeId) return
        if (device == null) {
            // Android has no public clearPreferredDevice API. Recreate the tracks so
            // the system media router is free to choose speaker or a personal headset.
            releaseTracksLocked()
            activeRouteId = routeId
            return
        }
        activeRouteId = routeId
        media?.let { track ->
            if (!track.setPreferredDevice(device)) {
                log("[AUDIO] media route rejected: ${routeLabel()}")
                releaseTracksLocked()
                publishRouteUnavailable()
                return
            }
        }
        tts?.let { track ->
            if (!track.setPreferredDevice(device)) {
                log("[AUDIO] navigation route rejected: ${routeLabel()}")
                releaseTracksLocked()
                publishRouteUnavailable()
            }
        }
    }

    private fun effectiveRoute(): AudioOutputRoute =
        if (MiniState.localCarLife) AudioOutputRoute.PHONE else settings.outputRoute

    private fun routeLabel(): String = when (effectiveRoute()) {
        AudioOutputRoute.PHONE -> "手机"
        AudioOutputRoute.BIKE_BLUETOOTH -> "车机蓝牙"
    }

    private fun publishRouteUnavailable() {
        MiniState.setAudio(
            when (effectiveRoute()) {
                AudioOutputRoute.PHONE -> "手机默认音频路由不可用"
                AudioOutputRoute.BIKE_BLUETOOTH -> "车机蓝牙未连接（需要 A2DP）"
            },
            Stage.ERROR,
        )
    }

    private fun duckMedia() {
        if (!settings.duckMediaDuringNavigation) {
            cancelDuckAndRestore()
            return
        }
        val duckedGain = settings.mediaGain * DUCK_GAIN
        media?.setVolume(duckedGain)
        if (!ducking) {
            ducking = true
            log("[AUDIO] duck media to ${(duckedGain * 100).toInt()}%")
        }
        lastRestore?.let(main::removeCallbacks)
        lastRestore = Runnable {
            synchronized(audioLock) {
                media?.setVolume(settings.mediaGain)
                if (ducking) {
                    ducking = false
                    log("[AUDIO] restore media to ${(settings.mediaGain * 100).toInt()}%")
                }
                lastRestore = null
            }
        }.also { main.postDelayed(it, 900) }
    }

    private fun applyVolumesLocked() {
        applyNavigationVolume()
        if (lastRestore != null && settings.duckMediaDuringNavigation) {
            media?.setVolume(settings.mediaGain * DUCK_GAIN)
        } else {
            cancelDuckAndRestoreLocked()
        }
    }

    private fun applyMediaVolume() {
        if (lastRestore != null && settings.duckMediaDuringNavigation) {
            media?.setVolume(settings.mediaGain * DUCK_GAIN)
        } else {
            media?.setVolume(settings.mediaGain)
        }
    }

    private fun applyNavigationVolume() {
        tts?.setVolume(settings.navigationGain)
    }

    private fun cancelDuckAndRestore() {
        synchronized(audioLock) { cancelDuckAndRestoreLocked() }
    }

    private fun cancelDuckAndRestoreLocked() {
        lastRestore?.let(main::removeCallbacks)
        lastRestore = null
        media?.setVolume(settings.mediaGain)
        ducking = false
    }

    private fun releaseTracksLocked() {
        lastRestore?.let(main::removeCallbacks)
        lastRestore = null
        runCatching { media?.pause() }
        runCatching { media?.flush() }
        runCatching { media?.release() }
        runCatching { tts?.pause() }
        runCatching { tts?.flush() }
        runCatching { tts?.release() }
        media = null
        tts = null
        mediaRate = 0
        mediaChannels = 0
        ttsRate = 0
        ttsChannels = 0
        activeRouteId = -1
        ducking = false
    }

    fun stop() {
        if (AudioVolumeStore.listener === settingsListener) {
            AudioVolumeStore.listener = null
        }
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        synchronized(audioLock) { releaseTracksLocked() }
        MiniState.setAudio("未连接", Stage.OFF)
    }

    companion object {
        private const val DUCK_GAIN = 0.32f
        private const val DEFAULT_ROUTE_ID = -2
    }
}
