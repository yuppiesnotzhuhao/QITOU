package app.qitou.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlin.concurrent.thread

/** Keeps the paired CFMOTO device selected as the A2DP media output. */
class BluetoothA2dpKeeper(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var proxy: BluetoothProfile? = null
    private var profileRequested = false
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
            proxy = service
            if (AudioVolumeStore.current.outputRoute == AudioOutputRoute.BIKE_BLUETOOTH) {
                MiniState.setBluetooth("正在检查车机蓝牙", Stage.WAIT)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            proxy = null
            profileRequested = false
            if (AudioVolumeStore.current.outputRoute == AudioOutputRoute.BIKE_BLUETOOTH) {
                MiniState.setBluetooth("蓝牙服务已断开", Stage.ERROR)
            }
        }
    }

    fun start() {
        if (running) return
        AudioVolumeStore.attach(context)
        running = true
        val useBikeAudio =
            AudioVolumeStore.current.outputRoute == AudioOutputRoute.BIKE_BLUETOOTH
        MiniState.setBluetooth(
            if (useBikeAudio) "正在检查车机蓝牙" else "手机音频模式，不主动连接车机蓝牙",
            if (useBikeAudio) Stage.WAIT else Stage.OFF,
        )
        worker = thread(name = "a2dp-keeper", isDaemon = true) {
            while (running) {
                try {
                    if (AudioVolumeStore.current.outputRoute != AudioOutputRoute.BIKE_BLUETOOTH) {
                        MiniState.setBluetooth("手机音频模式，不主动连接车机蓝牙", Stage.OFF)
                        Thread.sleep(5_000)
                        continue
                    }
                    if (!hasBluetoothPermission()) {
                        MiniState.setBluetooth("需要蓝牙权限", Stage.ERROR)
                        Thread.sleep(5_000)
                        continue
                    }
                    val a = adapter
                    if (a == null) {
                        MiniState.setBluetooth("手机不支持蓝牙", Stage.ERROR)
                        Thread.sleep(5_000)
                        continue
                    }
                    if (!a.isEnabled) {
                        MiniState.setBluetooth("蓝牙未开启", Stage.ERROR)
                        Thread.sleep(5_000)
                        continue
                    }
                    if (!profileRequested) {
                        profileRequested = true
                        val requested = runCatching {
                            a.getProfileProxy(context, listener, BluetoothProfile.A2DP)
                        }.getOrElse {
                            profileRequested = false
                            log("[BT] A2DP profile unavailable: ${it.javaClass.simpleName}")
                            false
                        }
                        if (!requested) {
                            profileRequested = false
                            log("[BT] A2DP profile request was rejected")
                        }
                    }
                    val bike = findPairedBike(a)
                    if (bike == null) {
                        MiniState.setBluetooth("未找到已配对的 CFMOTO", Stage.ERROR)
                    } else {
                        val state = proxy?.getConnectionState(bike)
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED ->
                                MiniState.setBluetooth(
                                    "A2DP 已连接 · ${safeDeviceName(bike) ?: "CFMOTO"}",
                                    Stage.OK,
                                )
                            BluetoothProfile.STATE_CONNECTING ->
                                MiniState.setBluetooth("A2DP 连接中", Stage.WAIT)
                            else -> {
                                MiniState.setBluetooth("A2DP 未连接，正在重连", Stage.WAIT)
                                connect(bike)
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (error: Exception) {
                    log("[BT] A2DP check failed: ${error.javaClass.simpleName}")
                    MiniState.setBluetooth("蓝牙检查失败", Stage.ERROR)
                }
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            MiniState.setBluetooth("蓝牙权限已撤销", Stage.ERROR)
            return
        }
        val service = proxy ?: return
        try {
            service.javaClass.getMethod("connect", BluetoothDevice::class.java)
                .invoke(service, device)
            log("[BT] requested A2DP connection to ${safeDeviceName(device) ?: "CFMOTO"}")
        } catch (error: Exception) {
            log("[BT] A2DP connect unavailable: ${error.javaClass.simpleName}")
            MiniState.setBluetooth("A2DP 无法自动连接，请在系统蓝牙中连接", Stage.ERROR)
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun findPairedBike(adapter: BluetoothAdapter): BluetoothDevice? {
        if (!hasBluetoothPermission()) return null
        return runCatching {
            adapter.bondedDevices?.firstOrNull { device ->
                safeDeviceName(device)?.contains("CFMOTO", ignoreCase = true) == true
            }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String? {
        if (!hasBluetoothPermission()) return null
        return runCatching { device.name }.getOrNull()
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        val a = adapter
        val p = proxy
        if (a != null && p != null) {
            runCatching { a.closeProfileProxy(BluetoothProfile.A2DP, p) }
        }
        proxy = null
        profileRequested = false
        if (!running) MiniState.setBluetooth("未连接", Stage.OFF)
    }
}
