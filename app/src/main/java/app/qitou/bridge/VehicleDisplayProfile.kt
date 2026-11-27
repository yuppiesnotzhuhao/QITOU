package app.qitou.bridge

import android.content.Context

enum class DisplayScaleMode(
    val storageValue: String,
    val label: String,
) {
    FIT("fit", "完整显示"),
    FILL("fill", "铺满裁切"),
    STRETCH("stretch", "拉伸铺满");

    fun screenFit(): ScreenFit = when (this) {
        FIT -> ScreenFit.FIT
        FILL -> ScreenFit.FILL
        STRETCH -> ScreenFit.STRETCH
    }

    companion object {
        fun fromStorage(value: String?): DisplayScaleMode =
            entries.firstOrNull { it.storageValue == value } ?: FIT
    }
}

data class CanvasInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
) {
    val isEmpty: Boolean get() = top == 0 && bottom == 0 && left == 0 && right == 0

    fun summary(): String =
        if (isEmpty) "无" else "上$top 下$bottom 左$left 右$right"
}

data class CarlifeSourceResolution(
    val width: Int,
    val height: Int,
) {
    val label: String get() = "${width}×$height"
}

object CarlifeSourceResolutions {
    val presets = listOf(
        CarlifeSourceResolution(848, 480),
        CarlifeSourceResolution(1024, 480),
        CarlifeSourceResolution(1280, 480),
        CarlifeSourceResolution(1280, 720),
        CarlifeSourceResolution(1920, 720),
        CarlifeSourceResolution(1920, 1080),
    )
    val default = presets.first { it.width == 1280 && it.height == 480 }

    fun normalize(width: Int, height: Int): CarlifeSourceResolution =
        presets.firstOrNull { it.width == width && it.height == height } ?: default
}

data class VehicleDisplayProfile(
    val mode: DisplayScaleMode = DisplayScaleMode.FIT,
    val insets: CanvasInsets = CanvasInsets(),
    val customWidth: Int = 0,
    val customHeight: Int = 0,
    val sourceWidth: Int = CarlifeSourceResolutions.default.width,
    val sourceHeight: Int = CarlifeSourceResolutions.default.height,
) {
    val outputWidth: Int get() = customWidth.coerceIn(240, 1920) and -2
    val outputHeight: Int
        get() = customHeight.coerceIn(240, 1080).let { if (it % 2 == 0) it else it + 1 }
    val usesCustomOutput: Boolean get() = customWidth > 0 && customHeight > 0
    val sourceRequest: CarlifeSourceResolution
        get() = CarlifeSourceResolutions.normalize(sourceWidth, sourceHeight)

    fun resolveOutput(reportedWidth: Int, reportedHeight: Int): Pair<Int, Int> =
        if (usesCustomOutput) outputWidth to outputHeight else reportedWidth to reportedHeight

    fun summary(): String = buildString {
        append(mode.label)
        append(" · 源 ${sourceRequest.label}")
        if (usesCustomOutput) append(" · 输出 ${outputWidth}×${outputHeight}")
        append(" · 安全区 ${insets.summary()}")
    }
}

object VehicleDisplayProfileStore {
    private const val PREFS = "qitou_display_profiles"

    fun load(context: Context): VehicleDisplayProfile {
        val key = vehicleKey(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VehicleDisplayProfile(
            mode = DisplayScaleMode.fromStorage(
                prefs.getString("${key}_mode", null),
            ),
            insets = CanvasInsets(
                top = prefs.getInt("${key}_top", 0).coerceAtLeast(0),
                bottom = prefs.getInt("${key}_bottom", 0).coerceAtLeast(0),
                left = prefs.getInt("${key}_left", 0).coerceAtLeast(0),
                right = prefs.getInt("${key}_right", 0).coerceAtLeast(0),
            ),
            customWidth = prefs.getInt("${key}_output_width", 0).coerceAtLeast(0),
            customHeight = prefs.getInt("${key}_output_height", 0).coerceAtLeast(0),
            sourceWidth = prefs.getInt(
                "${key}_source_width",
                CarlifeSourceResolutions.default.width,
            ),
            sourceHeight = prefs.getInt(
                "${key}_source_height",
                CarlifeSourceResolutions.default.height,
            ),
        )
    }

    fun save(context: Context, profile: VehicleDisplayProfile) {
        val key = vehicleKey(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("${key}_mode", profile.mode.storageValue)
            .putInt("${key}_top", profile.insets.top.coerceAtLeast(0))
            .putInt("${key}_bottom", profile.insets.bottom.coerceAtLeast(0))
            .putInt("${key}_left", profile.insets.left.coerceAtLeast(0))
            .putInt("${key}_right", profile.insets.right.coerceAtLeast(0))
            .putInt("${key}_output_width", profile.customWidth.coerceAtLeast(0))
            .putInt("${key}_output_height", profile.customHeight.coerceAtLeast(0))
            .putInt("${key}_source_width", profile.sourceRequest.width)
            .putInt("${key}_source_height", profile.sourceRequest.height)
            .apply()
    }

    private fun vehicleKey(context: Context): String {
        val vehicle = MotoVehiclePrefs.load(context)
        return vehicle?.mac?.takeIf(String::isNotBlank)
            ?: vehicle?.sn?.takeIf(String::isNotBlank)
            ?: vehicle?.ssid
            ?: "__local__"
    }
}
