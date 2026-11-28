package app.qitou.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import app.qitou.bridge.carplus.CarPlusLauncher
import app.qitou.bridge.carplus.CarLifeReceiver
import app.qitou.bridge.carplus.CarPlusVideoReceiver

class CarPlusBridgeService : Service() {
    private var pipeline: MiniVideoPipeline? = null
    private var receiver: CarLifeReceiver? = null
    private var motoLink: MotoLink? = null
    private var audioPlayer: MotoAudioPlayer? = null
    private var bluetoothKeeper: BluetoothA2dpKeeper? = null
    private var videoLive = false
    private var sourceRestartPending = false
    private var sourceOnly = false
    private var localCarLife = false
    private var liveUpdateLogged = false
    private var connectionFailure: String? = null
    @Volatile private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val wakeLockRenewal = object : Runnable {
        override fun run() {
            if (MiniState.serviceActive) {
                acquireWakeLock()
                main.postDelayed(this, WAKE_LOCK_RENEW_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        MiniState.attach(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            connectionFailure = null
            runCatching {
                MotoWifi.release(getSystemService(android.net.ConnectivityManager::class.java))
            }
            MiniState.update("正在停止", 0)
            MiniState.setCar("正在停止", Stage.OFF)
            MiniState.setPxc("正在停止", Stage.OFF)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_APPLY_CARLIFE_SOURCE) {
            if (MiniState.serviceActive) {
                val source = CarlifeSourceResolutions.normalize(
                    intent.getIntExtra(EXTRA_SOURCE_WIDTH, 0),
                    intent.getIntExtra(EXTRA_SOURCE_HEIGHT, 0),
                )
                restartReceiverForSource(source, "manual selection")
            }
            return START_STICKY
        }
        stopRequested = false
        connectionFailure = null
        startForeground(
            NOTIFICATION_ID,
            notification("正在等待车机和手机车联画面", "连接中"),
        )
        MiniState.serviceActive = true
        acquireLocks()
        sourceOnly = intent?.getBooleanExtra(EXTRA_SOURCE_ONLY, false) == true
        localCarLife = sourceOnly &&
            intent?.getBooleanExtra(EXTRA_LOCAL_CARLIFE, false) == true
        MiniState.localOnly = sourceOnly
        MiniState.localCarLife = localCarLife
        if (sourceOnly) {
            val localScreen = LocalCarLifeDisplay.sourceForScreen(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
            MiniState.setLocalScreenSize(localScreen.width, localScreen.height)
            MiniState.setVehicle(if (localCarLife) "本地 CarLife" else "本地诊断")
            MiniState.setWifi("无需连接车机", Stage.OK)
            MiniState.setPxc(
                if (localCarLife) "本地显示 · 无需连接车机" else "等待车机输出预览",
                if (localCarLife) Stage.OK else Stage.WAIT,
            )
            MiniState.update(
                if (localCarLife) {
                    "正在启动本地 CarLife · ${localScreen.label}"
                } else {
                    "正在启动车机输出预览"
                },
            )
            startBridge()
        } else {
            connectVehicle()
        }
        return START_STICKY
    }

    private fun connectVehicle() {
        val vehicle = MotoVehiclePrefs.load(this)
        if (vehicle == null) {
            MiniState.setWifi("尚未选择车辆", Stage.ERROR)
            MiniState.setCar("请先在${Brand.CHINESE_NAME}扫码", Stage.OFF)
            MiniState.update("尚未选择车辆，请先扫码")
            stopSelf()
            return
        }
        MiniState.setVehicle(vehicle.displayName)
        MotoWifi.connect(
            context = applicationContext,
            vehicle = vehicle,
            onReady = ready@{
                if (stopRequested) return@ready
                if (!MiniState.running) startBridge()
                else logAndRepublish("车机 Wi-Fi 已恢复")
            },
            onError = { error ->
                connectionFailure = error
                MiniState.setWifi(error, Stage.ERROR)
                MiniState.update(error)
                stopSelf()
            },
        )
    }

    private fun startBridge() {
        if (stopRequested || MiniState.running) return
        MiniState.running = true
        MiniState.serviceActive = true
        MiniState.resetVideoCodecs()
        MiniState.update("正在启动手机车联接收")
        val pipe = MiniVideoPipeline(MiniState::log)
        pipe.start()
        pipe.setFrameCap(VideoFrameRate.MAX_FPS)
        pipeline = pipe
        MiniRuntime.pipeline = pipe
        audioPlayer = MotoAudioPlayer(applicationContext, MiniState::log)
        bluetoothKeeper = if (
            !sourceOnly &&
            AudioVolumeStore.current.outputRoute == AudioOutputRoute.BIKE_BLUETOOTH
        ) {
            BluetoothA2dpKeeper(applicationContext, MiniState::log).also { it.start() }
        } else {
            null
        }
        val displayProfile = VehicleDisplayProfileStore.load(applicationContext)
        val sourceRequest = if (localCarLife) {
            LocalCarLifeDisplay.sourceForScreen(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
        } else {
            displayProfile.sourceRequest
        }
        val input = pipe.decoderInputSurface
        if (input == null) {
            MiniState.update("视频合成器启动失败")
            MiniState.setCar("视频合成器启动失败", Stage.ERROR)
            stopSelf()
            return
        }

        val receiverLog: (String) -> Unit = { MiniState.log(it) }
        val onFps: (Int) -> Unit = { fps ->
            MiniState.update(fps = fps)
            if (fps > 0) MiniState.setCar("车联画面解码 · $fps fps", Stage.OK)
        }
        val onVideoSize: (Int, Int) -> Unit = { w, h ->
            pipeline?.updateSourceSize(w, h)
            MiniState.setSourceSize(w, h)
            MiniState.log("[MINI] CarLife stream ${w}x$h")
        }
        val onReady: () -> Unit = {
            if (!videoLive) {
                videoLive = true
                if (!sourceOnly && motoLink?.isRunning != true) {
                    runCatching { motoLink?.stop() }
                    motoLink = MotoLink(
                        context = applicationContext,
                        pipeline = pipe,
                        log = MiniState::log,
                        onCarLifeSourceChanged = ::notifyReportedCarLifeSource,
                    ).also { it.start() }
                }
                if (localCarLife) {
                    val screen = LocalCarLifeDisplay.sourceForScreen(
                        resources.displayMetrics.widthPixels,
                        resources.displayMetrics.heightPixels,
                    )
                    MiniState.setOutputSize(screen.width, screen.height, "本地 CarLife")
                    MiniState.update(
                        "本地 CarLife · ${screen.width}×${screen.height} · 手机音频",
                    )
                    MiniState.setCar("本地 CarLife 已连接", Stage.OK)
                    MiniState.setPxc("无需连接车机", Stage.OFF)
                    MiniState.setAudio("手机 · 本地 CarLife", Stage.OK)
                    updateNotification(
                        "本地 CarLife · ${screen.width}×${screen.height}",
                        "手机音频",
                    )
                } else if (sourceOnly) {
                    val profile = VehicleDisplayProfileStore.load(applicationContext)
                    val reported = VehicleDisplayProfileStore.loadReportedCanvas(applicationContext)
                        ?: (800 to 386)
                    val output = profile.resolveOutput(reported.first, reported.second)
                    pipeline?.configureBikeCanvas(
                        output.first,
                        output.second,
                        profile.mode.screenFit(),
                        profile.insets,
                    )
                    MiniState.setOutputSize(output.first, output.second, profile.summary())
                    MiniState.update("车机输出预览 · 已接收手机车联")
                    MiniState.setCar("手机车联画面已接收", Stage.OK)
                    MiniState.setPxc(
                        "车机输出预览 · ${MiniState.outputWidth}×${MiniState.outputHeight}",
                        Stage.OK,
                    )
                    updateNotification(
                        "车机输出预览 · ${MiniState.outputWidth}×${MiniState.outputHeight}",
                        "本地诊断",
                    )
                } else {
                    MiniState.update("手机车联画面已接收 · 等待车机画布")
                    MiniState.setCar("手机车联画面已接收 · 等待车机尺寸", Stage.WAIT)
                    updateNotification("手机车联已连接 · 正在协商车机画布", "协商中")
                }
            }
        }
        val onSessionEnded: () -> Unit = ended@{
            if (stopRequested || !MiniState.serviceActive) return@ended
            videoLive = false
            MiniState.update("手机车联画面中断，正在重连")
            MiniState.setCar("车联画面中断，自动重连", Stage.ERROR)
            updateNotification("手机车联画面中断，正在重连", "重连中")
        }
        val onAudioPacket: (Boolean, ByteArray, Int, Int) -> Unit =
            { isTts, pcm, rate, channels ->
                audioPlayer?.write(isTts, pcm, rate, channels)
            }

        receiver = CarPlusVideoReceiver(
            context = applicationContext,
            surface = input,
            log = receiverLog,
            requestedWidth = sourceRequest.width,
            requestedHeight = sourceRequest.height,
            requestedFps = VideoFrameRate.MAX_FPS,
            onFps = onFps,
            onVideoSize = onVideoSize,
            onReady = onReady,
            onSessionEnded = onSessionEnded,
            onAudioPacket = onAudioPacket,
        ).also {
            MiniRuntime.receiver = it
            it.start()
        }
    }

    private fun notifyReportedCarLifeSource(source: CarlifeSourceResolution) {
        main.post {
            if (stopRequested || !MiniState.serviceActive) return@post
            MiniState.log(
                "[CAR+] reported CarLife source ${source.label} saved for next reconnect",
            )
            MiniState.update(
                "已获取车机分辨率 ${source.label}，建议重启连接适配车机画面",
            )
            Toast.makeText(
                applicationContext,
                "已获取车机分辨率，建议重启连接适配车机画面",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun restartReceiverForSource(source: CarlifeSourceResolution, reason: String) {
        if (localCarLife || stopRequested || sourceRestartPending) return
        sourceRestartPending = true
        main.post {
            sourceRestartPending = false
            val pipe = pipeline
            val input = pipe?.decoderInputSurface
            if (stopRequested || !MiniState.serviceActive || pipe == null || input == null) {
                return@post
            }
            if (!videoLive ||
                (MiniState.sourceWidth == source.width &&
                    MiniState.sourceHeight == source.height)
            ) {
                MiniState.log(
                    "[CAR+] source ${source.label} saved; " +
                        "current CarLife stream already matches",
                )
                return@post
            }
            MiniState.log("[CAR+] applying CarLife source ${source.label} ($reason)")
            MiniState.update("正在应用 CarLife 源分辨率 ${source.label}")
            updateNotification("正在应用 CarLife ${source.label}", "适配中")
            videoLive = false
            receiver?.stop()
            val next = CarPlusVideoReceiver(
                context = applicationContext,
                surface = input,
                log = MiniState::log,
                requestedWidth = source.width,
                requestedHeight = source.height,
                requestedFps = VideoFrameRate.MAX_FPS,
                onFps = { fps ->
                    MiniState.update(fps = fps)
                    if (fps > 0) {
                        MiniState.setCar("车联画面解码 · $fps fps", Stage.OK)
                    }
                },
                onVideoSize = { width, height ->
                    pipeline?.updateSourceSize(width, height)
                    MiniState.setSourceSize(width, height)
                    MiniState.log("[MINI] CarLife stream ${width}x$height")
                },
                onReady = {
                    if (!videoLive) {
                        videoLive = true
                        MiniState.update(
                            "CarLife ${source.label} 已应用 · 等待车机画布",
                        )
                        MiniState.setCar(
                            "CarLife ${source.label} 已应用 · 等待车机画布",
                            Stage.WAIT,
                        )
                    }
                },
                onSessionEnded = {
                    if (!stopRequested && MiniState.serviceActive) {
                        videoLive = false
                        MiniState.update("CarLife 新分辨率会话中断，正在重连")
                        MiniState.setCar("CarLife 画面中断，自动重连", Stage.ERROR)
                    }
                },
                onAudioPacket = { isTts, pcm, rate, channels ->
                    audioPlayer?.write(isTts, pcm, rate, channels)
                },
            )
            receiver = next
            MiniRuntime.receiver = next
            next.start()
        }
    }

    override fun onDestroy() {
        MiniState.log("[SERVICE] stopping")
        val failure = connectionFailure
        connectionFailure = null
        MiniState.running = false
        MiniState.serviceActive = false
        MiniState.localOnly = false
        MiniState.localCarLife = false
        try {
            receiver?.stop()
        } catch (_: Exception) {
        }
        receiver = null
        MiniRuntime.receiver = null
        try {
            motoLink?.stop()
        } catch (_: Exception) {
        }
        motoLink = null
        try {
            audioPlayer?.stop()
        } catch (_: Exception) {
        }
        audioPlayer = null
        try {
            bluetoothKeeper?.stop()
        } catch (_: Exception) {
        }
        bluetoothKeeper = null
        runCatching {
            MotoWifi.release(getSystemService(android.net.ConnectivityManager::class.java))
        }
        try {
            pipeline?.stop()
        } catch (_: Exception) {
        }
        pipeline = null
        MiniRuntime.pipeline = null
        releaseLocks()
        MiniState.resetVideoCodecs()
        MiniState.resetLinkStates()
        if (failure != null) {
            MiniState.setWifi(failure, Stage.ERROR)
            MiniState.update(failure, 0)
        } else {
            MiniState.update("已停止", 0)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                Brand.CHINESE_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun updateNotification(text: String, chipText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val built = notification(text, chipText)
        manager.notify(NOTIFICATION_ID, built)
        logLiveUpdateEligibility(manager, built)
    }

    private fun notification(text: String, chipText: String): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Brand.CHINESE_NAME)
            .setContentText(text)
            .setSubText("手机车联 · CFMOTO")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, MiniActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_rotate),
                    "重新连接",
                    PendingIntent.getActivity(
                        this,
                        3,
                        Intent(this, MiniActivity::class.java)
                            .setAction(MiniActivity.ACTION_RECONNECT)
                            .putExtra(MiniActivity.EXTRA_RECONNECT, true)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "停止",
                    PendingIntent.getService(
                        this,
                        2,
                        Intent(this, CarPlusBridgeService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )

        if (Build.VERSION.SDK_INT >= 36) {
            builder.addExtras(
                Bundle().apply {
                    putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                    putString(EXTRA_SHORT_CRITICAL_TEXT, chipText)
                },
            )
        }
        return builder.build()
    }

    private fun logLiveUpdateEligibility(manager: NotificationManager, notification: Notification) {
        if (Build.VERSION.SDK_INT < 36 || liveUpdateLogged) return
        liveUpdateLogged = true
        MiniState.log(
            "[LIVE] promotable=${notification.hasPromotableCharacteristics()} " +
                "userEnabled=${manager.canPostPromotedNotifications()}",
        )
    }

    private fun acquireLocks() {
        acquireWakeLock()
        main.removeCallbacks(wakeLockRenewal)
        main.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MS)
        if (wifiLock?.isHeld != true) {
            val wifi = getSystemService(WifiManager::class.java)
            wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "QITOU:low-latency-wifi",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            MiniState.log("[WIFI] high-performance Wi-Fi lock acquired")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QITOU:stream")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        MiniState.log("[POWER] partial wake lock renewed for screen-off streaming")
    }

    private fun releaseLocks() {
        main.removeCallbacks(wakeLockRenewal)
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun logAndRepublish(message: String) {
        MiniState.log("[SERVICE] $message")
        MiniState.update(message)
    }

    companion object {
        const val ACTION_START = "app.qitou.bridge.START"
        const val ACTION_STOP = "app.qitou.bridge.STOP"
        const val ACTION_APPLY_CARLIFE_SOURCE = "app.qitou.bridge.APPLY_CARLIFE_SOURCE"
        const val EXTRA_SOURCE_ONLY = "source_only"
        const val EXTRA_LOCAL_CARLIFE = "local_carlife"
        const val EXTRA_SOURCE_WIDTH = "source_width"
        const val EXTRA_SOURCE_HEIGHT = "source_height"
        private const val CHANNEL = "qitou_bridge"
        private const val NOTIFICATION_ID = 41
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 8 * 60 * 1000L
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

        fun start(
            context: android.content.Context,
            sourceOnly: Boolean = false,
            localCarLife: Boolean = false,
        ) {
            val intent = Intent(context, CarPlusBridgeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE_ONLY, sourceOnly)
                .putExtra(EXTRA_LOCAL_CARLIFE, localCarLife)
            context.startForegroundService(intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, CarPlusBridgeService::class.java).setAction(ACTION_STOP),
            )
        }

        fun applyCarLifeSource(
            context: android.content.Context,
            source: CarlifeSourceResolution,
        ) {
            context.startService(
                Intent(context, CarPlusBridgeService::class.java)
                    .setAction(ACTION_APPLY_CARLIFE_SOURCE)
                    .putExtra(EXTRA_SOURCE_WIDTH, source.width)
                    .putExtra(EXTRA_SOURCE_HEIGHT, source.height),
            )
        }
    }
}
