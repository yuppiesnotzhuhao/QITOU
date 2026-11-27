package app.qitou.bridge

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object CrashLogger {
    const val FILE_NAME = "last_crash.txt"
    private const val MAX_CRASH_BYTES = 512_000
    private const val PREFS = "qitou_crash_state"
    private const val KEY_VERSION_CODE = "last_version_code"
    private val timeFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val lock = Any()

    fun clearStale(context: Context) {
        val appContext = context.applicationContext
        val (versionCode, _) = versionInfo(appContext)
        synchronized(lock) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_VERSION_CODE, -1L) == versionCode) return
            runCatching { File(appContext.filesDir, FILE_NAME).delete() }
            prefs.edit().putLong(KEY_VERSION_CODE, versionCode).apply()
        }
    }

    fun install(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(appContext, previous))
        }
    }

    private class CrashHandler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching { write(context, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().use { buffer ->
            PrintWriter(buffer).use(error::printStackTrace)
            buffer.toString()
        }
        val (versionCode, versionName) = versionInfo(context)
        val report = LogRedactor.redact(
            buildString {
                appendLine("${Brand.CHINESE_NAME} ${Brand.LATIN_NAME} crash report")
                appendLine("time=${timeFormat.format(ZonedDateTime.now())}")
                appendLine("appVersion=$versionName ($versionCode)")
                appendLine("android=${Build.VERSION.RELEASE}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("thread=${thread.name}")
                appendLine("----------------------------------------")
                appendLine(trace)
            },
        ).toByteArray(StandardCharsets.UTF_8)

        val bounded = if (report.size <= MAX_CRASH_BYTES) {
            report
        } else {
            report.copyOf(MAX_CRASH_BYTES)
        }
        val file = File(context.filesDir, FILE_NAME)
        file.writeBytes(bounded)
    }

    @Suppress("DEPRECATION")
    private fun versionInfo(context: Context): Pair<Long, String> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.longVersionCode to (info.versionName ?: "unknown")
    }
}
