package app.qitou.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper

/**
 * Owns the motorcycle SoftAP association and keeps trying to restore it while the
 * foreground service is alive. WifiNetworkSpecifier requests are terminal after a loss,
 * so re-requesting the same network is required when the dash sleeps or reboots.
 */
object MotoWifi {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var desired: MotoVehicle? = null
    @Volatile private var readyCallback: (() -> Unit)? = null
    @Volatile private var errorCallback: ((String) -> Unit)? = null
    @Volatile private var shouldMaintain = false
    @Volatile private var reconnectAttempts = 0
    @Volatile private var reconnectScheduled = false
    @Volatile private var connecting = false

    @Volatile var network: Network? = null
        private set

    fun connect(
        context: Context,
        vehicle: MotoVehicle,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        MiniState.attach(context)
        synchronized(lock) {
            desired = vehicle
            readyCallback = onReady
            errorCallback = onError
            shouldMaintain = true
        }
        MiniState.setVehicle(vehicle.displayName)
        if (network != null && !connecting) {
            MiniState.setWifi("已连接 ${vehicle.ssid}", Stage.OK)
            main.post(onReady)
            return
        }
        if (connecting) return
        requestJoin(context.applicationContext)
    }

    private fun requestJoin(context: Context) {
        val vehicle = desired ?: return
        if (!shouldMaintain) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifi = app.getSystemService(WifiManager::class.java)

        if (wifi.isWifiEnabled) {
            @Suppress("DEPRECATION")
            val connectedSsid = runCatching {
                wifi.connectionInfo?.ssid?.trim('"')
            }.getOrNull()
            if (connectedSsid.equals(vehicle.ssid, ignoreCase = true)) {
                val existing = cm.allNetworks.firstOrNull { candidate ->
                    val caps = cm.getNetworkCapabilities(candidate)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                if (existing != null) {
                    network = existing
                    connecting = false
                    reconnectAttempts = 0
                    runCatching { cm.bindProcessToNetwork(existing) }
                    MiniState.setWifi("已连接 ${vehicle.ssid}", Stage.OK)
                    main.post { readyCallback?.invoke() }
                    return
                }
            }
        }

        unregister(cm, preserveState = true)
        connecting = true
        MiniState.setWifi(
            if (reconnectAttempts == 0) {
                "正在连接 ${vehicle.ssid}"
            } else {
                "正在重连 ${vehicle.ssid} · 第 ${reconnectAttempts + 1} 次"
            },
            Stage.WAIT,
        )

        val specifier = WifiNetworkSpecifier.Builder().apply {
            setSsid(vehicle.ssid)
            if (vehicle.password.isNotBlank()) setWpa2Passphrase(vehicle.password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                if (desired?.ssid != vehicle.ssid) return
                network = net
                connecting = false
                reconnectAttempts = 0
                reconnectScheduled = false
                runCatching { cm.bindProcessToNetwork(net) }
                MiniState.setWifi("已连接 ${vehicle.ssid}", Stage.OK)
                MiniState.log("[WIFI] joined ${vehicle.ssid}")
                main.post { readyCallback?.invoke() }
            }

            override fun onUnavailable() {
                if (!shouldMaintain || desired?.ssid != vehicle.ssid) return
                connecting = false
                network = null
                MiniState.setWifi("未检测到 ${vehicle.ssid}", Stage.ERROR)
                MiniState.log("[WIFI] ${vehicle.ssid} unavailable")
                failAndStop("车机 Wi-Fi 未连接")
            }

            override fun onLost(net: Network) {
                if (network != net) return
                network = null
                connecting = false
                runCatching { cm.bindProcessToNetwork(null) }
                MiniState.setWifi("车机 Wi-Fi 已断开", Stage.ERROR)
                MiniState.log("[WIFI] ${vehicle.ssid} lost")
                scheduleReconnect(app, "lost")
            }
        }

        callback = cb
        try {
            cm.requestNetwork(request, cb, 60_000)
        } catch (error: Exception) {
            connecting = false
            MiniState.setWifi("无法请求车机 Wi-Fi", Stage.ERROR)
            failAndStop(
                "无法发起车机 Wi-Fi 连接：" +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    private fun scheduleReconnect(context: Context, reason: String) {
        if (!shouldMaintain) return
        if (reconnectAttempts >= 1) {
            failAndStop("车机 Wi-Fi 已断开")
            return
        }
        if (reconnectScheduled) return
        reconnectScheduled = true
        reconnectAttempts++
        val delay = 1_500L
        MiniState.log("[WIFI] reconnect in ${delay}ms ($reason)")
        main.postDelayed({
            reconnectScheduled = false
            if (shouldMaintain && network == null && !connecting) {
                requestJoin(context)
            }
        }, delay)
    }

    private fun failAndStop(message: String) {
        shouldMaintain = false
        connecting = false
        reconnectScheduled = false
        errorCallback?.invoke(message)
    }

    fun release(cm: ConnectivityManager? = null) {
        shouldMaintain = false
        connecting = false
        reconnectScheduled = false
        reconnectAttempts = 0
        main.removeCallbacksAndMessages(null)
        unregister(cm, preserveState = false)
        readyCallback = null
        errorCallback = null
        desired = null
        network = null
        runCatching { cm?.bindProcessToNetwork(null) }
    }

    private fun unregister(cm: ConnectivityManager?, preserveState: Boolean) {
        callback?.let { cb -> cm?.let { runCatching { it.unregisterNetworkCallback(cb) } } }
        callback = null
        if (!preserveState) connecting = false
    }
}
