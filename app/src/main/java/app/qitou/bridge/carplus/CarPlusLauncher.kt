// SPDX-License-Identifier: AGPL-3.0-or-later
package app.qitou.bridge.carplus

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build

/**
 * Starts an installed phone-side CarLife host and leaves the wireless connection
 * choice to the system UI. OPPO Car+ and Honor CarConnect both expose a generic
 * local CarLife sender that QITOU consumes through the same loopback receiver.
 */
object CarPlusLauncher {
    private const val CARLIFE_PLUS_PACKAGE = "com.baidu.carlife.honor"
    private const val CARLIFE_PLUS_ACTIVITY =
        "com.baidu.carlife.CarlifeActivity"
    private const val HONOR_PACKAGE = "com.hihonor.auto"
    private const val HONOR_MANAGER =
        "com.hihonor.auto.settings.ManagerActivity"
    private const val HONOR_MANAGER_ACTION =
        "com.hihonor.auto.action.CAR_LIFE_MANAGER_VIEW"

    private data class Host(
        val packageName: String,
        val displayName: String,
    )

    private val hosts = listOf(
        Host("com.oplus.ocar", "OPPO Car+"),
        Host("com.coloros.ocar", "OPPO Car+"),
        Host("com.oppo.ocar", "OPPO Car+"),
        Host("com.oplus.car", "OPPO Car+"),
        Host(CARLIFE_PLUS_PACKAGE, "CarLife+ 增强版"),
        Host(HONOR_PACKAGE, "荣耀车联"),
    )

    /**
     * Honor's CarLife implementation only enters the real projection state after the
     * system guide has completed the Bluetooth and wireless-WLAN steps. Launching
     * CarlifeActivity directly leaves its launch artwork as the H.264 source.
     */
    fun requiresHonorGuide(context: Context): Boolean =
        isHonorDevice() && isInstalled(context, HONOR_PACKAGE)

    fun launch(context: Context, log: (String) -> Unit): Boolean {
        if (requiresHonorGuide(context)) {
            try {
                val intent = Intent().apply {
                    action = HONOR_MANAGER_ACTION
                    component = ComponentName(HONOR_PACKAGE, HONOR_MANAGER)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                }
                context.startActivity(intent)
                log("[CAR+] opened Honor CarConnect wireless guide")
                return true
            } catch (error: Exception) {
                log(
                    "[CAR+] Honor CarConnect guide rejected: " +
                        error.javaClass.simpleName,
                )
            }
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

            if (pkg == HONOR_PACKAGE) {
                val intents = listOf(
                    Intent().apply {
                        action = HONOR_MANAGER_ACTION
                        component = ComponentName(pkg, HONOR_MANAGER)
                    },
                    Intent().apply {
                        component = ComponentName(pkg, HONOR_MANAGER)
                    },
                )
                for (intent in intents) {
                    try {
                        intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                        )
                        context.startActivity(intent)
                        log("[CAR+] opened Honor CarConnect wireless guide")
                        return true
                    } catch (error: Exception) {
                        log(
                            "[CAR+] Honor CarConnect manager rejected: " +
                                error.javaClass.simpleName,
                        )
                    }
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

    private fun orderedHosts(): List<Host> {
        val honorDevice = isHonorDevice()
        if (!honorDevice) return hosts
        return hosts.sortedBy {
            when (it.packageName) {
                HONOR_PACKAGE -> 0
                CARLIFE_PLUS_PACKAGE -> 1
                else -> 2
            }
        }
    }

    private fun isHonorDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return "honor" in manufacturer || "honor" in brand
    }
}
