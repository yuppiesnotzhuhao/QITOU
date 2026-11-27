package app.qitou.bridge

import java.util.Locale

data class BackgroundRestrictionGuide(
    val title: String,
    val path: List<String>,
) {
    companion object {
        fun forManufacturer(manufacturer: String): BackgroundRestrictionGuide {
            val name = manufacturer.lowercase(Locale.ROOT)
            return when {
                listOf("oppo", "oneplus", "realme").any(name::contains) ->
                    BackgroundRestrictionGuide(
                        title = "ColorOS 后台行为",
                        path = listOf("应用信息", "耗电管理", "完全允许后台行为"),
                    )

                name.contains("honor") ->
                    BackgroundRestrictionGuide(
                        title = "MagicOS 后台行为",
                        path = listOf("应用信息", "电池", "允许后台活动"),
                    )

                listOf("xiaomi", "redmi", "poco").any(name::contains) ->
                    BackgroundRestrictionGuide(
                        title = "HyperOS 后台行为",
                        path = listOf("应用信息", "省电策略", "无限制"),
                    )

                name.contains("samsung") ->
                    BackgroundRestrictionGuide(
                        title = "One UI 后台行为",
                        path = listOf("应用信息", "电池", "不受限制"),
                    )

                listOf("vivo", "iqoo").any(name::contains) ->
                    BackgroundRestrictionGuide(
                        title = "OriginOS 后台行为",
                        path = listOf("应用信息", "电池", "后台耗电管理"),
                    )

                else ->
                    BackgroundRestrictionGuide(
                        title = "系统后台限制",
                        path = listOf("应用信息", "电池/后台", "允许后台运行"),
                    )
            }
        }
    }
}
