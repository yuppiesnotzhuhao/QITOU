// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.carplus

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts an installed phone-side CarLife host and leaves the wireless connection
 * choice to the system UI. OPPO Car+ and Honor CarConnect both expose a generic
 * local CarLife sender that QITOU consumes through the same loopback receiver.
 */
object CarPlusLauncher {
    private const val CARLIFE_PLUS_PACKAGE = "com.baidu.carlife.honor"
    private const val CARLIFE_PLUS_ACTIVITY =
        "com.baidu.carlife.CarlifeActivity"
    private const val XIAOMI_CARWITH_PACKAGE = "com.miui.carlink"
    private const val VIVO_CAR_PACKAGE = "com.vivo.car.networking"
    private const val VIVO_CAR_LAUNCHER_PACKAGE = "com.vivo.carlauncher"
    private const val HONOR_AUTO_PACKAGE = "com.hihonor.auto"
    private const val HONOR_AUTO_MANAGER_ACTIVITY =
        "com.hihonor.auto.settings.ManagerActivity"
    private const val HONOR_AUTO_MANAGER_ACTION =
        "com.hihonor.auto.action.CAR_LIFE_MANAGER_VIEW"

    private data class Host(
        val packageName: String,
        val displayName: String,
    )

    private val hosts = listOf(
        Host("com.oplus.ocar", "Car+ 车联"),
        Host("com.coloros.ocar", "Car+ 车联"),
        Host("com.oppo.ocar", "Car+ 车联"),
        Host("com.oplus.car", "Car+ 车联"),
        Host(XIAOMI_CARWITH_PACKAGE, "小米 CarWith"),
        Host(VIVO_CAR_PACKAGE, "vivo 智能车载 Jovi InCar"),
        Host(VIVO_CAR_LAUNCHER_PACKAGE, "vivo 车载 launcher"),
        Host(CARLIFE_PLUS_PACKAGE, "CarLife+ 增强版"),
    )

    fun isHonorDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return "honor" in manufacturer || "honor" in brand
    }

    fun canUseHonorLocalPresentation(context: Context): Boolean =
        isHonorDevice() &&
            isInstalled(context, CARLIFE_PLUS_PACKAGE) &&
            isInstalled(context, HONOR_AUTO_PACKAGE)

    fun connectedHostLabel(context: Context): String = when {
        canUseHonorLocalPresentation(context) -> "荣耀车联"
        isXiaomiDevice() && isInstalled(context, XIAOMI_CARWITH_PACKAGE) -> "小米 CarWith"
        isVivoDevice() && isInstalled(context, VIVO_CAR_PACKAGE) ->
            if ("iqoo" in deviceIdentity()) {
                "iQOO 智能车载（Jovi InCar）"
            } else {
                "vivo 智能车载（Jovi InCar）"
            }
        isOnePlusDevice() && hasOplusCarHost(context) -> "一加 Car+ 车联"
        isRealmeDevice() && hasOplusCarHost(context) -> "realme Car+ 车机互联"
        isOppoDevice() && hasOplusCarHost(context) -> "OPPO Car+ 车联"
        isInstalled(context, XIAOMI_CARWITH_PACKAGE) -> "小米 CarWith"
        isInstalled(context, VIVO_CAR_PACKAGE) -> "vivo 智能车载（Jovi InCar）"
        hasOplusCarHost(context) -> "Car+ 车联"
        isInstalled(context, CARLIFE_PLUS_PACKAGE) -> "CarLife+ 增强版"
        else -> "手机车联"
    }

    /**
     * Opens Honor CarConnect's own wireless connection page.
     *
     * CarLife+ must be started by that page and remain on "正在连接". Directly
     * starting CarlifeActivity produces only its welcome screen: the Honor
     * CarConnect virtual display and cast surface are not created on that path.
     */
    fun launchHonorLocalPresentation(context: Context, log: (String) -> Unit): Boolean {
        if (!isInstalled(context, HONOR_AUTO_PACKAGE)) {
            log("[CAR+] 荣耀车联未安装，无法进入官方无线连接页")
            return false
        }
        return try {
            context.startActivity(
                Intent(HONOR_AUTO_MANAGER_ACTION).apply {
                    component = ComponentName(HONOR_AUTO_PACKAGE, HONOR_AUTO_MANAGER_ACTIVITY)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                },
            )
            log("[CAR+] opened Honor CarConnect wireless connection page")
            true
        } catch (error: Exception) {
            log(
                "[CAR+] Honor CarConnect page rejected: " +
                    error.javaClass.simpleName,
            )
            false
        }
    }

    fun restartInstalledHosts(context: Context, log: (String) -> Unit) {
        val packages = buildList {
            addAll(orderedHosts().map { it.packageName })
            add(CARLIFE_PLUS_PACKAGE)
            add(HONOR_AUTO_PACKAGE)
        }.distinct().filter { isInstalled(context, it) }
        val activityManager = context.getSystemService(ActivityManager::class.java)
        packages.forEach { packageName ->
            val result = runCatching {
                activityManager.killBackgroundProcesses(packageName)
            }
            if (result.isSuccess) {
                log("[CAR+] requested host process restart: $packageName")
            } else {
                log(
                    "[CAR+] host process restart rejected: $packageName " +
                        (result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"),
                )
            }
        }
    }

    fun launch(context: Context, log: (String) -> Unit): Boolean {
        if (canUseHonorLocalPresentation(context)) {
            return launchHonorLocalPresentation(context, log)
        }
        if (isCarLifeLoopbackReady()) {
            log("[CAR+] existing phone-side CarLife engine detected; keeping current screen")
            return true
        }

        var installedAny = false
        for (host in orderedHosts()) {
            val pkg = host.packageName
            val installed = isInstalled(context, pkg)
            if (!installed) continue
            installedAny = true
            log("[CAR+] installed host package: ${host.displayName} ($pkg)")

            if (pkg == CARLIFE_PLUS_PACKAGE) {
                try {
                    context.startActivity(
                        Intent().apply {
                            component = ComponentName(pkg, CARLIFE_PLUS_ACTIVITY)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                            )
                        },
                    )
                    log("[CAR+] launched CarLife+ enhanced host")
                    return true
                } catch (error: Exception) {
                    log(
                        "[CAR+] CarLife+ enhanced host rejected: " +
                            error.javaClass.simpleName,
                    )
                }
            }

            if (pkg == "com.oplus.ocar") {
                val known = listOf(
                    "com.oplus.ocar.CarlifeActivity",
                    "com.oplus.ocar.settings.connect.ConnectTypeRouteActivity",
                    "com.oplus.ocar.carmode.CarModeActivity",
                )
                for (activity in known) {
                    try {
                        context.startActivity(
                            Intent().apply {
                                component = ComponentName(pkg, activity)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                                )
                            },
                        )
                        log("[CAR+] launched known phone-link pairing entry: $activity")
                        return true
                    } catch (error: Exception) {
                        log("[CAR+] known phone-link entry $activity rejected: ${error.javaClass.simpleName}")
                    }
                }
            }

            val activities = try {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                    .activities
                    ?.toList()
                    .orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            val candidates = activities
                .filter { it.exported }
                .map { info ->
                    val name = info.name.lowercase()
                    var score = 0
                    if ("carlife" in name) score += 100
                    if ("pair" in name) score += 80
                    if ("connect" in name) score += 60
                    if ("wireless" in name || "wlan" in name) score += 50
                    if ("guide" in name) score += 30
                    if ("main" in name || "home" in name) score += 20
                    info to score
                }
                .sortedByDescending { it.second }
            log(
                "[CAR+] activities: " +
                    activities.take(24).joinToString {
                        "${it.name}${if (it.exported) "" else "(private)"}"
                    },
            )

            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                    log("[CAR+] launched phone-side CarLife host ($pkg)")
                return true
            } catch (_: Exception) {
            }

            for ((activity, score) in candidates) {
                if (score <= 0) continue
                try {
                    val intent = Intent().apply {
                        component = ComponentName(pkg, activity.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                    context.startActivity(intent)
                    log("[CAR+] launched phone-link activity ${activity.name} (score=$score)")
                    return true
                } catch (error: Exception) {
                    log("[CAR+] activity ${activity.name} failed: ${error.javaClass.simpleName}")
                }
            }
        }
        if (installedAny) {
            log("[CAR+] phone-side CarLife host has no launcher entry; using its cast service directly")
        } else {
            log("[CAR+] no supported phone-side CarLife host is visible/installed")
        }
        return false
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES,
            )
            true
        } catch (_: Exception) {
            false
        }

    private fun isCarLifeLoopbackReady(): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", CarPlusProtocol.PORT_CMD), 300)
            }
            true
        }.getOrDefault(false)

    private fun orderedHosts(): List<Host> {
        val preferredPackages = when {
            isHonorDevice() -> setOf(CARLIFE_PLUS_PACKAGE)
            isXiaomiDevice() -> setOf(XIAOMI_CARWITH_PACKAGE)
            isVivoDevice() -> setOf(VIVO_CAR_PACKAGE, VIVO_CAR_LAUNCHER_PACKAGE)
            isOnePlusDevice() || isRealmeDevice() || isOppoDevice() ->
                setOf("com.oplus.ocar", "com.coloros.ocar", "com.oppo.ocar", "com.oplus.car")
            else -> emptySet()
        }
        return hosts.sortedBy { if (it.packageName in preferredPackages) 0 else 1 }
    }

    private fun deviceIdentity(): String =
        "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()

    private fun isXiaomiDevice(): Boolean = deviceIdentity().let {
        "xiaomi" in it || "redmi" in it || "poco" in it
    }

    private fun isVivoDevice(): Boolean = deviceIdentity().let {
        "vivo" in it || "iqoo" in it
    }

    private fun isOnePlusDevice(): Boolean = "oneplus" in deviceIdentity()

    private fun isRealmeDevice(): Boolean = "realme" in deviceIdentity()

    private fun isOppoDevice(): Boolean = "oppo" in deviceIdentity()

    private fun hasOplusCarHost(context: Context): Boolean =
        listOf(
            "com.oplus.ocar",
            "com.coloros.ocar",
            "com.oppo.ocar",
            "com.oplus.car",
        ).any { isInstalled(context, it) }

}
