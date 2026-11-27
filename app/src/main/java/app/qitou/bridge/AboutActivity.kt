package app.qitou.bridge

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.ColorRes

/** In-app version, attribution, and open-source license disclosure. */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarAppTheme.prepare(this)

        val background = CarAppTheme.canvas(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(26))
            setBackground(background)
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = "${Brand.FULL_NAME}图标"
            setBackground(rounded(color(R.color.surface_preview), 22))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }, LinearLayout.LayoutParams(dp(92), dp(92)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(title(Brand.CHINESE_NAME), top(18))
        root.addView(
            body(Brand.LATIN_NAME, color(R.color.text_body), 14f, bold = true).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
            top(2),
        )
        root.addView(body(versionText(), color(R.color.success), 15f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, top(4))

        root.addView(sectionCard(
            title = "项目与作者",
            lines = listOf(
                "作者：yuppiesnotzhuhao",
                "GitHub 项目主页：https://github.com/yuppiesnotzhuhao/QITOU",
                "问题反馈与功能建议：https://github.com/yuppiesnotzhuhao/QITOU/issues",
                "欢迎提交 Issue 或 Pull Request。",
            ),
            links = true,
        ))

        root.addView(sectionCard(
            title = "手机车联兼容性",
            lines = listOf(
                "兼容支持本地 CarLife 输出的手机车联应用，例如 OPPO Car+、" +
                    "荣耀车联和 CarLife+ 增强版。",
                "应用不内置车联服务；实际可用能力取决于手机厂商、系统版本和已安装应用。",
            ),
            links = false,
        ))

        root.addView(sectionCard(
            title = "开源许可",
            lines = listOf(
                "${Brand.FULL_NAME} 以 GNU Affero General Public License v3.0 only " +
                    "（AGPL-3.0-only）开源。",
                "你可以按照该许可证使用、研究、修改和再分发本应用。分发修改版时，" +
                    "必须保留版权与许可声明，并按 AGPL 要求提供 Corresponding Source。",
            ),
            links = false,
        ))
        root.addView(actionButton("查看完整 AGPL-3.0 协议") {
            showAsset("GNU AGPL-3.0-only", "licenses/AGPL-3.0.txt")
        })
        root.addView(actionButton("查看版权、CarWithin 致谢与第三方声明") {
            showAsset("版权与第三方声明", "licenses/NOTICE.txt")
        })

        root.addView(sectionCard(
            title = "致谢 CarWithin",
            lines = listOf(
                "感谢 CarWithin 项目及其作者 xmz28。",
                "CarWithin 的 iOS + CarPlay/CarLife 互联方案为${Brand.CHINESE_NAME}" +
                    "的产品设计和协议研究提供了启发。",
                "CarWithin 项目地址：https://github.com/xmz28/CarWithin",
                "作者主页：https://github.com/xmz28",
            ),
            links = true,
        ))

        root.addView(sectionCard(
            title = "其他来源",
            lines = listOf(
                "${Brand.FULL_NAME} 还包含或改编自 OpenCfMoto、CarPToCarL、CarVeX 等开源项目，" +
                    "其版权与许可证完整列在“版权与第三方声明”中。",
                "AndroidX：Apache-2.0",
                "Google ML Kit Barcode Scanning：Google APIs Terms of Service",
            ),
            links = true,
        ))

        root.addView(Button(this).apply {
            text = "返回"
            isAllCaps = false
            textSize = 15f
            stateListAnimator = null
            elevation = 0f
            setTextColor(color(R.color.button_secondary_text))
            setBackground(rounded(color(R.color.button_secondary_surface), 14))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(18) })

        val scroll = ScrollView(this).apply {
            setBackground(background)
            clipToPadding = true
            setPadding(0, dp(8), 0, dp(8))
            addView(root)
        }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                scroll.setPadding(0, safe.top + dp(8), 0, safe.bottom + dp(8))
            } else {
                @Suppress("DEPRECATION")
                scroll.setPadding(
                    0,
                    insets.systemWindowInsetTop + dp(8),
                    0,
                    insets.systemWindowInsetBottom + dp(8),
                )
            }
            insets
        }
        setContentView(scroll)
    }

    private fun versionText(): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return "版本 ${info.versionName} (${info.longVersionCode})"
    }

    private fun sectionCard(
        title: String,
        lines: List<String>,
        links: Boolean,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setBackground(
            rounded(
                color(R.color.surface_card),
                16,
                color(R.color.stroke_card),
            ),
        )
        addView(body(title, color(R.color.text_heading), 17f, bold = true))
        for ((index, line) in lines.withIndex()) {
            addView(TextView(this@AboutActivity).apply {
                text = line
                setTextColor(color(R.color.text_body))
                textSize = 13f
                setLineSpacing(dp(2).toFloat(), 1.12f)
                if (links) {
                    autoLinkMask = android.text.util.Linkify.WEB_URLS
                    movementMethod = LinkMovementMethod.getInstance()
                    linksClickable = true
                }
            }, top(if (index == 0) 8 else 7))
        }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun showAsset(title: String, path: String) {
        val text = runCatching {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse {
            Toast.makeText(this, "无法读取 $path：${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        val content = TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.dialog_code_text))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        CarDialogs.builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_heading))
        textSize = 29f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun body(
        text: String,
        color: Int,
        size: Float,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(text: String, click: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            stateListAnimator = null
            elevation = 0f
            setTextColor(color(R.color.button_secondary_text))
            setBackground(
                rounded(
                    color(R.color.button_secondary_surface),
                    13,
                    color(R.color.button_secondary_stroke),
                ),
            )
            setOnClickListener { click() }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
        }

    private fun top(margin: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(margin)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) =
        CarAppTheme.rounded(color, dp(radius), stroke, dp(1))

    private fun color(@ColorRes resource: Int): Int = CarAppTheme.color(this, resource)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
