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
import app.qitou.bridge.carplus.CarPlusVideoReceiver

class CarPlusBridgeService : Service() {
    private var pipeline: MiniVideoPipeline? = null
    private var receiver: CarPlusVideoReceiver? = null
    private var motoLink: MotoLink? = null
    private var audioPlayer: MotoAudioPlayer? = null
    private var bluetoothKeeper: BluetoothA2dpKeeper? = null
    private var videoLive = false
    private var liveUpdateLogged = false
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
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            notification("正在等待车机和 Car+ 画面", "连接中"),
        )
        MiniState.serviceActive = true
        acquireLocks()
        connectVehicle()
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
            onReady = {
                if (!MiniState.running) startBridge()
                else logAndRepublish("车机 Wi-Fi 已恢复")
            },
            onError = { error ->
                MiniState.setWifi(error, Stage.ERROR)
                MiniState.update(error)
            },
        )
    }

    private fun startBridge() {
        if (MiniState.running) return
        MiniState.running = true
        MiniState.serviceActive = true
        MiniState.update("正在启动 Car+ 接收")
        val pipe = MiniVideoPipeline(MiniState::log)
        pipe.start()
        pipeline = pipe
        MiniRuntime.pipeline = pipe
        audioPlayer = MotoAudioPlayer(applicationContext, MiniState::log)
        bluetoothKeeper = BluetoothA2dpKeeper(applicationContext, MiniState::log).also { it.start() }
        val input = pipe.decoderInputSurface
        if (input == null) {
            MiniState.update("视频合成器启动失败")
            MiniState.setCar("视频合成器启动失败", Stage.ERROR)
            stopSelf()
            return
        }

        receiver = CarPlusVideoReceiver(
            context = applicationContext,
            surface = input,
            log = {
                MiniState.log(it)
            },
            requestedWidth = 1280,
            requestedHeight = 480,
            requestedFps = 30,
            onFps = { fps ->
                MiniState.update(fps = fps)
                if (fps > 0) MiniState.setCar("Car+ 正在解码 · $fps fps", Stage.OK)
            },
            onVideoSize = { w, h ->
                MiniState.log("[MINI] Car+ stream ${w}x$h")
            },
            onReady = {
                if (!videoLive) {
                    videoLive = true
                    pipeline?.configureBikeCanvas(800, 384)
                    if (motoLink?.isRunning != true) {
                        runCatching { motoLink?.stop() }
                        motoLink = MotoLink(applicationContext, pipe, MiniState::log).also { it.start() }
                    }
                    MiniState.update("Car+ 画面已接收 · 正在连接 MotoPlay")
                    MiniState.setCar("Car+ 画面已接收 · 800×384", Stage.OK)
                    updateNotification("Car+ 已连接 · MotoPlay 投送中", "投送中")
                }
            },
            onSessionEnded = {
                videoLive = false
                MiniState.update("Car+ 画面中断，正在重连")
                MiniState.setCar("Car+ 画面中断，自动重连", Stage.ERROR)
                updateNotification("Car+ 画面中断，正在重连", "重连中")
            },
            onAudioPacket = { isTts, pcm, rate, channels ->
                audioPlayer?.write(isTts, pcm, rate, channels)
            },
        ).also {
            MiniRuntime.receiver = it
            it.start()
        }
    }

    override fun onDestroy() {
        MiniState.log("[SERVICE] stopping")
        MiniState.running = false
        MiniState.serviceActive = false
        MiniState.update("已停止", 0)
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
        MiniState.resetLinkStates()
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
            .setSubText("OPPO Car+ · CFMOTO")
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
        private const val CHANNEL = "qitou_bridge"
        private const val NOTIFICATION_ID = 41
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 8 * 60 * 1000L
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

        fun start(context: android.content.Context) {
            val intent = Intent(context, CarPlusBridgeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CarPlusBridgeService::class.java))
        }
    }
}
