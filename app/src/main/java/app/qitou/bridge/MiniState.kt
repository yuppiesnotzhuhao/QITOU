package app.qitou.bridge

import android.content.Context
import java.io.File
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class Stage {
    OFF,
    WAIT,
    OK,
    ERROR,
}

/**
 * Shared, process-local state for the foreground service and both control activities.
 * UI reads snapshots; protocol code only has to publish short status changes.
 */
object MiniState {
    private const val MAX_LOG_LINES = 900
    private const val MAX_LOG_BYTES = 1_500_000L
    private const val ACTIVE_LOG_NAME = "qitou.log"
    private const val FIRST_ARCHIVE_NAME = "qitou.1.log"
    private const val SECOND_ARCHIVE_NAME = "qitou.2.log"
    private const val SUPPORT_ARCHIVE_NAME = "QITOU-support.zip"
    private val LOG_FILE_NAMES = listOf(
        ACTIVE_LOG_NAME,
        FIRST_ARCHIVE_NAME,
        SECOND_ARCHIVE_NAME,
    )

    @Volatile var running: Boolean = false
    @Volatile var serviceActive: Boolean = false
    @Volatile var status: String = "未启动"
    @Volatile var framesPerSecond: Int = 0
    @Volatile var lastLog: String = ""
    @Volatile var vehicleName: String = ""
    @Volatile var wifiText: String = "未连接"
    @Volatile var carText: String = "未启动"
    @Volatile var pxcText: String = "未启动"
    @Volatile var audioText: String = "未连接"
    @Volatile var bluetoothText: String = "未连接"
    @Volatile var wifiStage: Stage = Stage.OFF
    @Volatile var carStage: Stage = Stage.OFF
    @Volatile var pxcStage: Stage = Stage.OFF
    @Volatile var audioStage: Stage = Stage.OFF
    @Volatile var bluetoothStage: Stage = Stage.OFF
    @Volatile var listener: (() -> Unit)? = null

    private val logs = ArrayDeque<String>(MAX_LOG_LINES)
    private val logLock = Any()
    private val logFileLock = Any()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private val logExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "qitou-log-writer").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var logDirectory: File? = null
    @Volatile private var logFile: File? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        if (appContext === app && logFile != null) return
        synchronized(logFileLock) {
            if (appContext === app && logFile != null) return
            val directory = app.filesDir
            val active = File(directory, ACTIVE_LOG_NAME)
            appContext = app
            logDirectory = directory
            logFile = active
            if (active.length() > MAX_LOG_BYTES) {
                runCatching { rotateLogsLocked(directory) }
            }
        }
    }

    @Synchronized
    fun update(status: String? = null, fps: Int? = null) {
        if (status != null) this.status = status
        if (fps != null) framesPerSecond = fps
        listener?.invoke()
    }

    fun setVehicle(name: String) {
        vehicleName = name
        listener?.invoke()
    }

    fun setWifi(text: String, stage: Stage) {
        wifiText = text
        wifiStage = stage
        listener?.invoke()
    }

    fun setCar(text: String, stage: Stage) {
        carText = text
        carStage = stage
        listener?.invoke()
    }

    fun setPxc(text: String, stage: Stage) {
        pxcText = text
        pxcStage = stage
        listener?.invoke()
    }

    fun setAudio(text: String, stage: Stage) {
        audioText = text
        audioStage = stage
        listener?.invoke()
    }

    fun setBluetooth(text: String, stage: Stage) {
        bluetoothText = text
        bluetoothStage = stage
        listener?.invoke()
    }

    fun resetLinkStates() {
        wifiText = "未连接"
        wifiStage = Stage.OFF
        carText = "未启动"
        carStage = Stage.OFF
        pxcText = "未启动"
        pxcStage = Stage.OFF
        audioText = "未连接"
        audioStage = Stage.OFF
        bluetoothText = "未连接"
        bluetoothStage = Stage.OFF
        listener?.invoke()
    }

    fun log(line: String) {
        val safe = LogRedactor.redact(line)
        lastLog = safe
        val stamped = "${timeFormat.format(LocalTime.now())}  $safe"
        android.util.Log.i("QITOU", safe)
        synchronized(logLock) {
            if (logs.size >= MAX_LOG_LINES) logs.removeFirst()
            logs.addLast(stamped)
        }
        logExecutor.execute {
            runCatching { appendLogLocked(stamped + "\n") }
        }
    }

    fun recentLogs(): List<String> = synchronized(logLock) { logs.toList() }

    /**
     * Writes a redacted ZIP containing every retained log and the latest crash report.
     */
    fun exportLog(context: Context): File {
        attach(context)
        runCatching { logExecutor.submit {}.get(2, TimeUnit.SECONDS) }
        val shareDir = File(context.cacheDir, "shared").apply { mkdirs() }
        File(shareDir, "QITOU-support.log").delete()
        val out = File(shareDir, SUPPORT_ARCHIVE_NAME)
        val summary = buildString {
            appendLine("${Brand.CHINESE_NAME} ${Brand.LATIN_NAME} diagnostic log")
            appendLine("time=${Date()}")
            appendLine("android=${android.os.Build.VERSION.RELEASE}")
            appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("appVersion=${info.versionName} (${info.longVersionCode})")
            }
            appendLine("vehicle=${vehicleName.ifBlank { "未选择" }}")
            appendLine("wifi=${wifiText}")
            appendLine("carplus=${carText}")
            appendLine("pxc=${pxcText}")
            appendLine("audio=${audioText}")
            appendLine("bluetooth=${bluetoothText}")
            appendLine("fps=$framesPerSecond")
            appendLine("status=$status")
            appendLine("----------------------------------------")
        }

        synchronized(logFileLock) {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
                writeTextEntry(zip, "summary.txt", LogRedactor.redact(summary))
                var foundLog = false
                for (name in LOG_FILE_NAMES) {
                    val file = File(context.filesDir, name)
                    if (file.isFile && file.length() > 0) {
                        copyFileEntry(zip, "logs/$name", file)
                        foundLog = true
                    }
                }
                if (!foundLog) {
                    val memory = recentLogs().joinToString("\n", postfix = "\n")
                    writeTextEntry(zip, "logs/session-memory.log", LogRedactor.redact(memory))
                }
                val crash = File(context.filesDir, CrashLogger.FILE_NAME)
                if (crash.isFile && crash.length() > 0) {
                    copyFileEntry(zip, CrashLogger.FILE_NAME, crash)
                }
            }
        }
        return out
    }

    private fun appendLogLocked(line: String) {
        val directory = logDirectory ?: return
        val payload = line.toByteArray(StandardCharsets.UTF_8)
        val active = File(directory, ACTIVE_LOG_NAME)
        synchronized(logFileLock) {
            if (active.length() + payload.size > MAX_LOG_BYTES) {
                rotateLogsLocked(directory)
            }
            FileOutputStream(active, true).use { it.write(payload) }
        }
    }

    private fun rotateLogsLocked(directory: File) {
        val active = File(directory, ACTIVE_LOG_NAME)
        if (!active.isFile || active.length() == 0L) return
        val first = File(directory, FIRST_ARCHIVE_NAME)
        val second = File(directory, SECOND_ARCHIVE_NAME)
        second.delete()
        if (first.exists()) moveOrCopy(first, second)
        moveOrCopy(active, first)
    }

    private fun moveOrCopy(source: File, target: File) {
        target.delete()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun copyFileEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

}
