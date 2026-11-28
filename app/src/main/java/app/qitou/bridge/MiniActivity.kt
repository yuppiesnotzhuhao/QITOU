package app.qitou.bridge

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import app.qitou.bridge.carplus.CarPlusLauncher

/** Single-page CarWithin-style controller embedded in the APK. */
class MiniActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var fps: TextView
    private lateinit var action: Button
    private lateinit var scanButton: Button
    private lateinit var fullscreenButton: Button
    private lateinit var carPlusButton: Button
    private lateinit var mediaVolumeText: TextView
    private lateinit var navigationVolumeText: TextView
    private lateinit var mediaVolumeSeek: SeekBar
    private lateinit var navigationVolumeSeek: SeekBar
    private lateinit var duckSwitch: Switch
    private lateinit var autoConnectSwitch: Switch
    private lateinit var audioSummary: TextView
    private lateinit var audioBody: LinearLayout
    private lateinit var audioToggleButton: Button
    private lateinit var advancedBody: LinearLayout
    private lateinit var advancedToggleButton: Button
    private lateinit var carPlusHint: TextView
    private lateinit var displayProfileSummary: TextView
    private lateinit var carlifeSourceSummary: TextView
    private lateinit var phoneRouteButton: Button
    private lateinit var bikeRouteButton: Button
    private lateinit var audioRouteHint: TextView
    private lateinit var preview: TextureView
    private var previewSurface: Surface? = null
    private lateinit var previewHint: TextView
    private lateinit var localPreviewCard: LinearLayout
    private lateinit var bikePreview: TextureView
    private lateinit var bikePreviewHint: TextView
    private var bikePreviewSurface: Surface? = null
    private var bikePreviewAttached = false
    private var reconnectPending = false
    private var localCarLifeFullscreenOpened = false
    private lateinit var rowVehicle: TextView
    private lateinit var rowWifi: TextView
    private lateinit var rowCar: TextView
    private lateinit var rowPxc: TextView
    private lateinit var rowAudio: TextView
    private lateinit var rowBluetooth: TextView
    private lateinit var rowDecoder: TextView
    private lateinit var rowEncoder: TextView
    private lateinit var pageHost: FrameLayout
    private lateinit var settingsButton: LinearLayout
    private lateinit var tabBar: LiquidGlassTabBar
    private lateinit var settingsPanel: LinearLayout
    private lateinit var simulationStatus: TextView
    private lateinit var simulationFps: TextView
    private lateinit var simulationAction: Button
    private lateinit var localStatus: TextView
    private lateinit var localFps: TextView
    private lateinit var localAction: Button
    private lateinit var localOpenButton: Button
    private lateinit var localReconnectButton: Button
    private lateinit var aboutVersion: TextView
    private val pageViews = arrayOfNulls<View>(HomeTab.entries.size)
    private var selectedTab = HomeTab.PROJECTION
    private var settingsDialog: AlertDialog? = null
    private var attached = false
    private var lastX = 0
    private var lastY = 0
    private var autoConnectAttempted = false
    private var permissionPurpose = PermissionPurpose.START_BRIDGE
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (permissionPurpose == PermissionPurpose.FIRST_LAUNCH) {
            showInitialPermissionResult(result)
            return@registerForActivityResult
        }
        val required = result.filterKeys {
            it != Manifest.permission.POST_NOTIFICATIONS &&
                it != Manifest.permission.BLUETOOTH_CONNECT
        }
        when {
            required.all { it.value } -> {
                if (result[Manifest.permission.BLUETOOTH_CONNECT] == false) {
                    AudioVolumeStore.setOutputRoute(this, AudioOutputRoute.PHONE)
                    renderAudioRouteButtons()
                    Toast.makeText(
                        this,
                        "蓝牙未授权，已继续使用手机音频",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                startWifiThenBridge()
            }
            else -> showPermissionRecovery(required.filterValues { !it }.keys.toTypedArray())
        }
    }
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            selectBikeAudioRoute()
        } else {
            AudioVolumeStore.setOutputRoute(this, AudioOutputRoute.PHONE)
            renderAudioRouteButtons()
            showBluetoothPermissionRecovery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarAppTheme.prepare(this)
        MiniState.attach(applicationContext)
        AudioVolumeStore.attach(applicationContext)
        setContentView(buildTabbedUi())
        savedInstanceState?.getString(KEY_HOME_TAB)?.let { name ->
            HomeTab.entries.firstOrNull { it.name == name }?.let(::selectTab)
        }
        MiniState.listener = { runOnUiThread(::render) }
        val vehicle = MotoVehiclePrefs.load(this)
        if (vehicle != null) MiniState.setVehicle(vehicle.displayName)
        render()
        val reconnectRequested = consumeReconnectRequest(intent)
        if (
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.getBooleanExtra(CarPlusBridgeService.EXTRA_SOURCE_ONLY, false) == true
        ) {
            startCarLifeInput(
                sourceOnly = true,
                localCarLife =
                    intent.getBooleanExtra(CarPlusBridgeService.EXTRA_LOCAL_CARLIFE, false),
            )
        }
        window.decorView.post {
            if (reconnectRequested) reconnectBridge() else runInitialLaunchAutomation()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeReconnectRequest(intent)) {
            window.decorView.post { reconnectBridge() }
        }
    }

    override fun onResume() {
        super.onResume()
        MiniState.listener = { runOnUiThread(::render) }
        syncPreviewForTab()
        render()
    }

    override fun onPause() {
        MiniState.listener = null
        detachPreview()
        detachBikePreview()
        super.onPause()
    }

    override fun onDestroy() {
        MiniState.listener = null
        detachBikePreview()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_HOME_TAB, selectedTab.name)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity result for the embedded QR scanner")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_QR && resultCode == RESULT_OK) startWifiThenBridge()
    }

    private fun buildTabbedUi(): View {
        val background = gradient()
        val frame = FrameLayout(this).apply {
            setBackground(gradient())
            clipChildren = false
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MiniActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = Brand.FULL_NAME
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(this@MiniActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, 0, 0)
                addView(TextView(this@MiniActivity).apply {
                    text = Brand.CHINESE_NAME
                    setTextColor(color(R.color.text_heading))
                    textSize = 23f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MiniActivity).apply {
                    text = "手机车联 · 本地投送控制台"
                    setTextColor(color(R.color.text_body))
                    textSize = 11f
                    setPadding(0, dp(1), 0, 0)
                })
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))

        settingsButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "设置"
            setPadding(dp(12), 0, dp(13), 0)
            setBackground(rounded(color(R.color.surface_card), 22, color(R.color.stroke_card)))
            elevation = 0f
            addView(ImageView(this@MiniActivity).apply {
                setImageResource(R.drawable.ic_settings_sliders)
                setColorFilter(color(R.color.text_heading))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(TextView(this@MiniActivity).apply {
                text = "设置"
                setTextColor(color(R.color.text_heading))
                textSize = 13f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(6), 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnClickListener { showSettingsDialog() }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(98), dp(48)))
        shell.addView(header)

        settingsPanel = buildSettingsPanel()
        pageHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        shell.addView(
            pageHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        frame.addView(
            shell,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        tabBar = LiquidGlassTabBar(this).apply {
            setItems(
                listOf(
                    LiquidGlassTabBar.TabItem("车机投屏", R.drawable.ic_tab_projection),
                    LiquidGlassTabBar.TabItem("模拟输出", R.drawable.ic_tab_simulation),
                    LiquidGlassTabBar.TabItem("本地车联", R.drawable.ic_tab_local),
                    LiquidGlassTabBar.TabItem("关于", R.drawable.ic_tab_about),
                ),
            )
            setOnTabSelected { index -> selectTab(HomeTab.entries[index]) }
        }
        frame.addView(
            tabBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(68),
                Gravity.BOTTOM,
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                bottomMargin = dp(14)
            },
        )

        frame.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                shell.setPadding(0, safe.top, 0, 0)
                (tabBar.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                    safe.bottom + dp(12)
            } else {
                @Suppress("DEPRECATION")
                shell.setPadding(0, insets.systemWindowInsetTop, 0, 0)
                (tabBar.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                    insets.systemWindowInsetBottom + dp(12)
            }
            insets
        }

        selectTab(HomeTab.PROJECTION)
        return frame
    }

    private fun selectTab(tab: HomeTab) {
        val previousTab = selectedTab
        selectedTab = tab
        detachPreview()
        detachBikePreview()
        val page = pageViews[tab.ordinal] ?: when (tab) {
            HomeTab.PROJECTION -> buildProjectionPage()
            HomeTab.SIMULATION -> buildSimulationPage()
            HomeTab.LOCAL_CAR_LIFE -> buildLocalCarLifePage()
            HomeTab.ABOUT -> buildAboutPage()
        }.also { pageViews[tab.ordinal] = it }
        pageHost.removeAllViews()
        pageHost.addView(
            page,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        if (previousTab != tab) {
            page.alpha = 0f
            page.translationY = dp(8).toFloat()
            page.scaleX = 0.992f
            page.scaleY = 0.992f
            page.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
        }
        tabBar.select(tab.ordinal)
        settingsButton.visibility = if (tab == HomeTab.ABOUT) View.GONE else View.VISIBLE
        pageHost.post {
            syncPreviewForTab()
            render()
        }
    }

    private fun pageRoot(): Pair<ScrollView, LinearLayout> {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(112))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackground(gradient())
            addView(root)
        }
        return scroll to root
    }

    private fun responsivePreviewHeight(preferredDp: Int): Int {
        val availableDp = resources.configuration.screenHeightDp
        return when {
            availableDp < 500 -> 112
            availableDp < 700 -> 150
            else -> preferredDp
        }
    }

    private fun buildProjectionPage(): View {
        val (scroll, root) = pageRoot()
        val hero = card()
        hero.addView(sectionLabel("连接状态"))
        status = TextView(this).apply {
            setTextColor(color(R.color.text_heading))
            textSize = 31f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, 0)
        }
        hero.addView(status)
        fps = TextView(this).apply {
            setTextColor(color(R.color.success))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        }
        hero.addView(fps)
        addStatusPair(hero, "车辆", "未选择", "Wi-Fi", "未连接").let {
            rowVehicle = it.first
            rowWifi = it.second
        }
        addStatusPair(hero, "车联画面", "未启动", "MotoPlay", "未启动").let {
            rowCar = it.first
            rowPxc = it.second
        }
        addStatusPair(hero, "解码", "未启动", "编码", "未启动").let {
            rowDecoder = it.first
            rowEncoder = it.second
        }
        addStatusPair(hero, "音频", "未连接", "蓝牙 A2DP", "未连接").let {
            rowAudio = it.first
            rowBluetooth = it.second
        }
        action = Button(this).apply {
            textSize = 17f
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            setTextColor(color(R.color.accent_on_primary))
            setBackground(rounded(color(R.color.accent_primary), 18))
            setOnClickListener {
                if (MiniState.serviceActive) {
                    CarPlusBridgeService.stop(this@MiniActivity)
                    render()
                } else {
                    requestNotificationThenStart()
                }
            }
        }
        carPlusButton = secondaryButton("重新连接") { reconnectBridge() }
        carPlusButton.visibility = View.GONE
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(action, LinearLayout.LayoutParams(0, dp(54), 1f))
            addView(
                carPlusButton,
                LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(8) },
            )
        }, linearParams(top = 4, bottom = 7))
        carPlusHint = TextView(this).apply {
            setTextColor(color(R.color.text_body))
            textSize = 11f
            setLineSpacing(0f, 1.15f)
        }
        hero.addView(carPlusHint)

        val previewFrame = FrameLayout(this).apply {
            setBackground(
                rounded(color(R.color.surface_card), 20, color(R.color.stroke_card)),
            )
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
                    attachPreview(width, height)
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
                    if (attached) MiniRuntime.pipeline?.updatePreviewSize(width, height)
                    else attachPreview(width, height)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    detachPreview()
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
            setOnTouchListener { view, event -> handlePreviewTouch(view, event) }
        }
        previewFrame.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        previewHint = TextView(this).apply {
            text = "连接后画面显示在这里"
            setTextColor(color(R.color.text_on_video))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        previewFrame.addView(
            previewHint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            previewFrame,
            linearParams(height = responsivePreviewHeight(200), bottom = 10),
        )
        fullscreenButton = secondaryButton("全屏操作画面") {
            if (MiniRuntime.pipeline == null || !MiniState.running) {
                Toast.makeText(this, "请先启动投送", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ControlActivity::class.java))
            }
        }
        root.addView(fullscreenButton, linearParams(height = 50, bottom = 14))
        root.addView(hero, linearParams(bottom = 14))

        val connection = card()
        connection.addView(sectionLabel("连接"))
        autoConnectSwitch = Switch(this).apply {
            text = "打开应用后自动连接已保存车辆"
            setTextColor(color(R.color.text_heading))
            textSize = 14f
            minHeight = dp(48)
            isChecked = AutoConnectStore.isEnabled(this@MiniActivity)
            isEnabled = MotoVehiclePrefs.load(this@MiniActivity) != null
            setOnCheckedChangeListener { _, checked ->
                AutoConnectStore.setEnabled(this@MiniActivity, checked)
            }
        }
        connection.addView(autoConnectSwitch)
        connection.addView(bodyText(
            "首次连接仍需要通过扫码或手动 Wi-Fi 选择车辆。",
        ))
        connection.addView(secondaryButton("重新扫码配对") {
            if (MiniState.serviceActive) CarPlusBridgeService.stop(this@MiniActivity)
            startActivityForResult(
                Intent(this@MiniActivity, QrScanActivity::class.java),
                REQUEST_QR,
            )
        }, linearParams(height = 48, top = 10))
        scanButton = connection.getChildAt(connection.childCount - 1) as Button
        root.addView(connection, linearParams(bottom = 14))

        root.addView(infoCard(
            "使用步骤",
            "1. 在仪表打开 MotoPlay。\n" +
                "2. 选择车辆并开始投送。\n" +
                "3. 保持仪表停留在手机互联页面。\n" +
                "4. 可从标题栏右侧打开设置。",
        ))
        return scroll
    }

    private fun buildSimulationPage(): View {
        val (scroll, root) = pageRoot()
        val hero = card()
        hero.addView(sectionLabel("模拟车机端效果"))
        simulationStatus = TextView(this).apply {
            setTextColor(color(R.color.text_heading))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, 0)
        }
        hero.addView(simulationStatus)
        simulationFps = TextView(this).apply {
            setTextColor(color(R.color.text_body))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        }
        hero.addView(simulationFps)
        simulationAction = Button(this).apply {
            textSize = 17f
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            setTextColor(color(R.color.accent_on_primary))
            setBackground(rounded(color(R.color.accent_primary), 18))
            setOnClickListener {
                when {
                    MiniState.serviceActive && MiniState.localOnly && !MiniState.localCarLife ->
                        CarPlusBridgeService.stop(this@MiniActivity)
                    MiniState.serviceActive ->
                        Toast.makeText(this@MiniActivity, "请先停止当前连接", Toast.LENGTH_SHORT).show()
                    else -> startLocalDiagnosticMode()
                }
                render()
            }
        }
        hero.addView(simulationAction, linearParams(height = 54, top = 2))

        val outputFrame = FrameLayout(this).apply {
            setBackground(
                rounded(color(R.color.surface_card), 20, color(R.color.stroke_card)),
            )
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bikePreview = TextureView(this).apply {
            contentDescription = "模拟车机端效果预览"
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
                    attachBikePreview(width, height)
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
                    if (bikePreviewAttached) MiniRuntime.pipeline?.updateBikePreviewSize(width, height)
                    else attachBikePreview(width, height)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    detachBikePreview()
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
        }
        outputFrame.addView(
            bikePreview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        bikePreviewHint = TextView(this).apply {
            text = "启动后显示模拟车机端效果"
            setTextColor(color(R.color.text_on_video))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        outputFrame.addView(
            bikePreviewHint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            outputFrame,
            linearParams(height = responsivePreviewHeight(220), bottom = 14),
        )
        root.addView(hero, linearParams(bottom = 14))
        return scroll
    }

    private fun buildLocalCarLifePage(): View {
        val (scroll, root) = pageRoot()
        val hero = card()
        hero.addView(sectionLabel("CarLife 状态"))
        localStatus = TextView(this).apply {
            setTextColor(color(R.color.text_heading))
            textSize = 29f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, 0)
        }
        hero.addView(localStatus)
        localFps = TextView(this).apply {
            setTextColor(color(R.color.success))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        }
        hero.addView(localFps)
        localAction = Button(this).apply {
            textSize = 17f
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            setTextColor(color(R.color.accent_on_primary))
            setBackground(rounded(color(R.color.accent_primary), 18))
            setOnClickListener {
                when {
                    MiniState.serviceActive && MiniState.localCarLife ->
                        CarPlusBridgeService.stop(this@MiniActivity)
                    MiniState.serviceActive ->
                        Toast.makeText(this@MiniActivity, "请先停止当前连接", Toast.LENGTH_SHORT).show()
                    else -> startLocalCarLifeMode()
                }
                render()
            }
        }
        hero.addView(localAction, linearParams(height = 54, bottom = 8))
        localOpenButton = secondaryButton("全屏本地 CarLife") {
            if (MiniRuntime.pipeline == null || !MiniState.running) {
                Toast.makeText(this, "请先启动本地 CarLife", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ControlActivity::class.java))
            }
        }
        localReconnectButton = secondaryButton("重新连接") { reconnectBridge() }
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(localOpenButton, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(
                localReconnectButton,
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) },
            )
        })
        root.addView(hero, linearParams(bottom = 14))

        root.addView(infoCard(
            "运行方式",
            "本地 CarLife 使用手机当前显示尺寸，例如 3168×1440；" +
                "音频固定由手机扬声器或当前耳机输出。\n\n" +
                "画面连接成功后会进入全屏操作；也可以从本页再次打开。",
        ))
        return scroll
    }

    private fun buildAboutPage(): View {
        val (scroll, root) = pageRoot()
        val identity = card()
        identity.gravity = Gravity.CENTER_HORIZONTAL
        identity.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = Brand.FULL_NAME
        }, LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        identity.addView(TextView(this).apply {
            text = Brand.FULL_NAME
            setTextColor(color(R.color.text_heading))
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        aboutVersion = TextView(this).apply {
            text = versionLabel()
            setTextColor(color(R.color.success))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        identity.addView(aboutVersion)
        root.addView(identity, linearParams(bottom = 14))
        root.addView(infoCard(
            "项目与作者",
            "作者：yuppiesnotzhuhao\n" +
                "项目源码、更新记录和功能建议均在 GitHub 维护。\n" +
                "欢迎提交 Issue 或 Pull Request。",
        ), linearParams(bottom = 8))
        root.addView(secondaryButton("打开 GitHub 项目") {
            openExternalUrl("https://github.com/yuppiesnotzhuhao/QITOU")
        }, linearParams(height = 48, bottom = 4))
        root.addView(secondaryButton("提交问题或功能建议") {
            openExternalUrl("https://github.com/yuppiesnotzhuhao/QITOU/issues")
        }, linearParams(height = 48, bottom = 12))
        root.addView(infoCard(
            "手机车联兼容性",
            "兼容支持本地 CarLife 输出的手机车联应用，例如 OPPO/一加/realme Car+、" +
                "小米 CarWith、vivo/iQOO 智能车载、荣耀车联等。\n\n" +
                "应用不内置车联服务；实际可用能力取决于手机厂商、系统版本和已安装应用。",
        ), linearParams(bottom = 12))
        root.addView(infoCard(
            "开源许可",
            "${Brand.FULL_NAME} 以 GNU Affero General Public License v3.0 only " +
                "（AGPL-3.0-only）开源。\n\n" +
                "你可以按照该许可证使用、研究、修改和再分发本应用。分发修改版时，" +
                "必须保留版权与许可声明，并按 AGPL 要求提供 Corresponding Source。",
        ), linearParams(bottom = 8))
        root.addView(secondaryButton("查看完整 AGPL-3.0 协议") {
            showLicenseAsset("GNU AGPL-3.0-only", "licenses/AGPL-3.0.txt")
        }, linearParams(height = 48, bottom = 4))
        root.addView(secondaryButton("查看版权、CarWithin 致谢与第三方声明") {
            showLicenseAsset("版权与第三方声明", "licenses/NOTICE.txt")
        }, linearParams(height = 48, bottom = 12))
        root.addView(infoCard(
            "致谢 CarWithin",
            "感谢 CarWithin 项目及其作者 xmz28。\n\n" +
                "CarWithin 的 iOS + CarPlay/CarLife 互联方案为${Brand.CHINESE_NAME}" +
                "的产品设计和协议研究提供了启发。",
        ), linearParams(bottom = 8))
        root.addView(secondaryButton("打开 CarWithin 项目") {
            openExternalUrl("https://github.com/xmz28/CarWithin")
        }, linearParams(height = 48, bottom = 4))
        root.addView(secondaryButton("打开作者 xmz28 的主页") {
            openExternalUrl("https://github.com/xmz28")
        }, linearParams(height = 48, bottom = 12))
        root.addView(infoCard(
            "其他来源",
            "${Brand.FULL_NAME} 还包含或改编自 OpenCfMoto、CarPToCarL、CarVeX 等开源项目，" +
                "其版权与许可证完整列在“版权与第三方声明”中。\n\n" +
                "AndroidX：Apache-2.0\n" +
                "Google ML Kit Barcode Scanning：Google APIs Terms of Service",
        ))
        return scroll
    }

    private fun showLicenseAsset(title: String, path: String) {
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
        val licenseScroll = ScrollView(this).apply { addView(content) }
        CarDialogs.builder(this)
            .setTitle(title)
            .setView(licenseScroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(settingsSectionLabel("音频"))
        val audioCard = card().apply {
            setBackground(rounded(color(R.color.dialog_input_surface), 20))
        }
        audioSummary = TextView(this).apply {
            setTextColor(color(R.color.success))
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        }
        val audioHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        audioHeader.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MiniActivity).apply {
                text = "声音与音量"
                setTextColor(color(R.color.text_heading))
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(audioSummary)
        }, weighted())
        audioToggleButton = compactToggleButton("展开") {
            toggleSection(audioBody, audioSummary, audioToggleButton)
        }
        audioHeader.addView(audioToggleButton, LinearLayout.LayoutParams(-2, dp(48)))
        audioCard.addView(audioHeader)
        audioBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        audioBody.addView(bodyText(
            "只调整骑投内的混音；系统音量和车机蓝牙音量仍由对应设备控制。",
        ))
        mediaVolumeSeek = SeekBar(this).apply {
            max = 100
            progress = AudioVolumeStore.current.mediaPercent
            setOnSeekBarChangeListener(simpleSeekListener { value ->
                AudioVolumeStore.setMedia(this@MiniActivity, value)
                mediaVolumeText.text = "$value%"
                renderAudioSummary()
            })
        }
        audioBody.addView(settingsSliderRow("音乐", mediaVolumeSeek).also {
            mediaVolumeText = it.getChildAt(2) as TextView
        })
        navigationVolumeSeek = SeekBar(this).apply {
            max = 100
            progress = AudioVolumeStore.current.navigationPercent
            setOnSeekBarChangeListener(simpleSeekListener { value ->
                AudioVolumeStore.setNavigation(this@MiniActivity, value)
                navigationVolumeText.text = "$value%"
                renderAudioSummary()
            })
        }
        audioBody.addView(settingsSliderRow("导航", navigationVolumeSeek).also {
            navigationVolumeText = it.getChildAt(2) as TextView
        })
        duckSwitch = Switch(this).apply {
            text = "导航播报时自动压低音乐"
            setTextColor(color(R.color.text_body))
            textSize = 13f
            isChecked = AudioVolumeStore.current.duckMediaDuringNavigation
            setOnCheckedChangeListener { _, checked ->
                AudioVolumeStore.setAutoDuck(this@MiniActivity, checked)
            }
        }
        audioBody.addView(duckSwitch)
        audioBody.addView(settingsSectionLabel("声音输出设备").apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        audioBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            phoneRouteButton = routeButton("手机") {
                AudioVolumeStore.setOutputRoute(this@MiniActivity, AudioOutputRoute.PHONE)
                if (!MiniState.serviceActive) MiniState.setAudio("手机 · 投送未启动", Stage.OFF)
                renderAudioRouteButtons()
            }
            bikeRouteButton = routeButton("车机蓝牙") { requestBikeAudioRoute() }
            addView(phoneRouteButton, weighted())
            addView(bikeRouteButton, weighted(left = 8))
        }, linearParams(height = 48, top = 2))
        audioRouteHint = TextView(this).apply {
            setTextColor(color(R.color.text_body))
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(4), 0, 0)
        }
        audioBody.addView(audioRouteHint)
        audioCard.addView(audioBody)
        panel.addView(audioCard, linearParams(bottom = 16))

        panel.addView(settingsSectionLabel("连接与画面"))
        val tools = card()
        scanButton = secondaryButton("重新扫码配对") {
            if (MiniState.serviceActive) CarPlusBridgeService.stop(this@MiniActivity)
            startActivityForResult(
                Intent(this@MiniActivity, QrScanActivity::class.java),
                REQUEST_QR,
            )
        }
        tools.addView(secondaryButton("车机画面适配") {
            showDisplayProfileDialog()
        }, linearParams(height = 48, bottom = 5))
        displayProfileSummary = TextView(this).apply {
            setTextColor(color(R.color.text_body))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        tools.addView(displayProfileSummary)
        tools.addView(secondaryButton("CarLife 源分辨率请求") {
            showCarlifeSourceDialog()
        }, linearParams(height = 48, bottom = 5))
        carlifeSourceSummary = TextView(this).apply {
            setTextColor(color(R.color.text_body))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        tools.addView(carlifeSourceSummary)
        tools.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("手动 Wi-Fi") { showManualPairing() }, weighted())
            addView(secondaryButton("蓝牙设置") {
                SystemLinks.openBluetoothSettings(this@MiniActivity)
            }, weighted(left = 8))
        }, linearParams(height = 48, bottom = 8))
        tools.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("后台运行设置") {
                showBackgroundRunSettings()
            }, weighted())
            addView(secondaryButton("导出诊断包") { shareLogs() }, weighted(left = 8))
        }, linearParams(height = 48))
        panel.addView(tools)
        renderAudioRouteButtons()
        return panel
    }

    private fun showSettingsDialog() {
        if (selectedTab == HomeTab.ABOUT) return
        settingsDialog?.dismiss()
        (settingsPanel.parent as? android.view.ViewGroup)?.removeView(settingsPanel)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(4))
            addView(settingsPanel)
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(wrapper)
        }
        val dialog = CarDialogs.builder(this)
            .setTitle("设置")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .setOnDismissListener {
                (settingsPanel.parent as? android.view.ViewGroup)?.removeView(settingsPanel)
                settingsDialog = null
            }
            .create()
        settingsDialog = dialog
        dialog.show()
    }

    private fun syncPreviewForTab() {
        when (selectedTab) {
            HomeTab.PROJECTION -> {
                attachPreview()
                detachBikePreview()
            }
            HomeTab.SIMULATION -> {
                detachPreview()
                if (MiniState.localOnly && !MiniState.localCarLife) attachBikePreview()
                else detachBikePreview()
            }
            HomeTab.LOCAL_CAR_LIFE, HomeTab.ABOUT -> {
                detachPreview()
                detachBikePreview()
            }
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(15), dp(17), dp(15))
        setBackground(rounded(color(R.color.surface_card), 20))
        elevation = 0f
    }

    private fun infoCard(title: String, text: String): LinearLayout = card().apply {
        addView(sectionLabel(title))
        addView(bodyText(text))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_label))
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun settingsSectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_label))
        textSize = 12f
        setPadding(dp(4), 0, 0, dp(6))
    }

    private fun bodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_body))
        textSize = 13f
        setLineSpacing(dp(2).toFloat(), 1.15f)
        setPadding(0, dp(6), 0, 0)
    }

    private fun settingsSliderRow(label: String, seekBar: SeekBar): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MiniActivity).apply {
                text = label
                setTextColor(color(R.color.text_body))
                textSize = 13f
            }, LinearLayout.LayoutParams(dp(48), -2))
            addView(seekBar, LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(TextView(this@MiniActivity).apply {
                text = "${seekBar.progress}%"
                setTextColor(color(R.color.success))
                textSize = 12f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(dp(46), -2))
        }

    private fun versionLabel(): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return "版本 ${info.versionName} (${info.longVersionCode})"
    }

    private fun requestNotificationThenStart() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            AudioVolumeStore.current.outputRoute == AudioOutputRoute.BIKE_BLUETOOTH &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT in 29..32) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissionsWithRationale(
                needed.toTypedArray(),
                PermissionPurpose.START_BRIDGE,
            )
            return
        }
        startWifiThenBridge()
    }

    private fun runInitialLaunchAutomation() {
        if (!maybeShowInitialPermissionPrompt()) maybeAutoConnectSavedVehicle()
    }

    /**
     * Shows the one-time permission explanation when needed.
     * Returns true when the dialog is shown, so startup auto-connect waits for the user's choice.
     */
    private fun maybeShowInitialPermissionPrompt(): Boolean {
        val prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INITIAL_PERMISSIONS_SHOWN, false)) return false
        val includeBluetooth =
            AudioVolumeStore.current.outputRoute == AudioOutputRoute.BIKE_BLUETOOTH
        val missing = SystemLinks.missingRuntimePermissions(
            this,
            includeBluetooth = includeBluetooth,
        )
        if (missing.isEmpty()) {
            prefs.edit().putBoolean(KEY_INITIAL_PERMISSIONS_SHOWN, true).apply()
            return false
        }
        CarDialogs.builder(this)
            .setTitle("允许${Brand.CHINESE_NAME}连接车机")
            .setMessage(
                "${Brand.CHINESE_NAME}需要以下权限：\n\n" +
                    "• 附近 Wi‑Fi：连接仪表 MotoPlay 热点\n" +
                    "• 蓝牙：选择车机 A2DP 声音时再授权\n" +
                    "• 通知：显示投送前台状态\n" +
                    "• 相机：扫描仪表配对二维码\n\n" +
                    "拒绝 Wi‑Fi 将无法完成投送；蓝牙和相机可以稍后授权。",
            )
            .setNegativeButton("稍后") { _, _ ->
                prefs.edit().putBoolean(KEY_INITIAL_PERMISSIONS_SHOWN, true).apply()
            }
            .setPositiveButton("继续授权") { _, _ ->
                prefs.edit().putBoolean(KEY_INITIAL_PERMISSIONS_SHOWN, true).apply()
                requestPermissionsWithRationale(
                    missing,
                    PermissionPurpose.FIRST_LAUNCH,
                )
            }
            .show()
        return true
    }

    private fun requestPermissionsWithRationale(
        permissions: Array<String>,
        purpose: PermissionPurpose,
    ) {
        permissionPurpose = purpose
        val requiresExplanation = permissions.any(::shouldShowRequestPermissionRationale)
        if (!requiresExplanation) {
            permissionLauncher.launch(permissions)
            return
        }
        CarDialogs.builder(this)
            .setTitle("需要设备访问权限")
            .setMessage(
                "${Brand.CHINESE_NAME}需要附近 Wi‑Fi 和蓝牙权限来连接车机。" +
                    "通知权限可让投送状态保持可见。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> permissionLauncher.launch(permissions) }
            .show()
    }

    private fun showInitialPermissionResult(result: Map<String, Boolean>) {
        val connectivityDenied = result.filterKeys {
            it == Manifest.permission.NEARBY_WIFI_DEVICES ||
                it == Manifest.permission.ACCESS_FINE_LOCATION ||
                it == Manifest.permission.ACCESS_COARSE_LOCATION
        }.filterValues { !it }.keys
        val bluetoothDenied = result[Manifest.permission.BLUETOOTH_CONNECT] == false
        val cameraDenied = result[Manifest.permission.CAMERA] == false
        when {
            connectivityDenied.isNotEmpty() -> {
                showPermissionRecovery(connectivityDenied.toTypedArray())
                return
            }
            bluetoothDenied || cameraDenied -> {
                if (bluetoothDenied) {
                    AudioVolumeStore.setOutputRoute(this, AudioOutputRoute.PHONE)
                }
                val message = when {
                    bluetoothDenied && cameraDenied ->
                        "蓝牙和相机未授权；本次使用手机音频，可稍后扫码时授权相机。"
                    bluetoothDenied ->
                        "蓝牙未授权，本次使用手机音频；需要车机声音时可重新授权。"
                    else ->
                        "相机权限未授权，可稍后扫码时授权或使用“手动 Wi‑Fi”。"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        maybeAutoConnectSavedVehicle()
    }

    private fun maybeAutoConnectSavedVehicle() {
        if (autoConnectAttempted) return
        val enabled = AutoConnectStore.isEnabled(this)
        val vehicle = MotoVehiclePrefs.load(this)
        if (!LaunchConnectionPolicy.shouldAutoConnect(
                enabled = enabled,
                hasSavedVehicle = vehicle != null,
                serviceActive = MiniState.serviceActive,
            )
        ) {
            return
        }
        autoConnectAttempted = true
        MiniState.setVehicle(vehicle!!.displayName)
        MiniState.log("[AUTO] launch auto-connect enabled; connecting ${vehicle.ssid}")
        requestNotificationThenStart()
    }

    private fun showPermissionRecovery(denied: Array<String>) {
        val permanent = denied.none(::shouldShowRequestPermissionRationale)
        val dialog = CarDialogs.builder(this)
            .setTitle("权限不足")
            .setMessage("无法连接车机，请授予 Wi‑Fi 和蓝牙权限。")
            .setNegativeButton("关闭", null)
        if (permanent) {
            dialog.setPositiveButton("打开应用设置") { _, _ ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        } else {
            dialog.setPositiveButton("重新授权") { _, _ ->
                requestPermissionsWithRationale(denied, PermissionPurpose.START_BRIDGE)
            }
        }
        dialog.show()
    }

    private fun startWifiThenBridge() {
        val vehicle = MotoVehiclePrefs.load(this)
        if (vehicle == null) {
            startActivityForResult(Intent(this, QrScanActivity::class.java), REQUEST_QR)
            return
        }
        MiniState.setVehicle(vehicle.displayName)
        MotoWifi.connect(
            context = this,
            vehicle = vehicle,
            onReady = { launchBridge() },
            onError = { error ->
                MiniState.setWifi(error, Stage.ERROR)
                MiniState.update(error)
            },
        )
    }

    private fun startLocalDiagnosticMode() {
        if (MiniState.serviceActive) {
            Toast.makeText(
                this,
                when {
                    MiniState.localCarLife -> "请先停止本地 CarLife"
                    MiniState.localOnly -> "本地输出预览已经在运行"
                    else -> "请先停止当前车机投送"
                },
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        startCarLifeInput(sourceOnly = true)
        Toast.makeText(
            this,
            "正在启动车机输出预览，不会连接摩托车机",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun startLocalCarLifeMode() {
        if (MiniState.serviceActive) {
            if (MiniState.localCarLife) {
                startActivity(Intent(this, ControlActivity::class.java))
            } else {
                Toast.makeText(this, "请先停止当前连接或本地输出预览", Toast.LENGTH_SHORT).show()
            }
            return
        }
        localCarLifeFullscreenOpened = false
        startCarLifeInput(sourceOnly = true, localCarLife = true)
        Toast.makeText(
            this,
            "正在启动本地 CarLife；声音固定由手机输出",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun showDisplayProfileDialog() {
        val current = VehicleDisplayProfileStore.load(this)
        val (canvasWidth, canvasHeight) = configuredCanvasSize(current)
        val canvasSource = if (current.usesCustomOutput) "自定义" else "跟随车机上报"
        val source = current.sourceRequest
        var selectedMode = current.mode
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        content.addView(TextView(this).apply {
            text = "车机画布：$canvasWidth×$canvasHeight（$canvasSource）\n" +
                "手机车联源：${source.label}\n\n" +
                "“完整显示”保证内容不被裁切；“铺满裁切”会放大并裁边。" +
                "宽高填写 0 表示跟随车机上报尺寸；自定义分辨率在重新连接后生效。" +
                "本地 CarLife 使用手机屏幕分辨率，此配置只用于车机投屏与模拟输出。"
            setTextColor(color(R.color.dialog_message))
            textSize = 13f
            setLineSpacing(0f, 1.12f)
        }, linearParams(bottom = 10))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val fitButton = secondaryButton("完整显示") {}
        val fillButton = secondaryButton("铺满裁切") {}
        val stretchButton = secondaryButton("拉伸铺满") {}
        fun renderModeButtons() {
            styleRouteButton(fitButton, selectedMode == DisplayScaleMode.FIT)
            styleRouteButton(fillButton, selectedMode == DisplayScaleMode.FILL)
            styleRouteButton(stretchButton, selectedMode == DisplayScaleMode.STRETCH)
        }
        fitButton.setOnClickListener {
            selectedMode = DisplayScaleMode.FIT
            renderModeButtons()
        }
        fillButton.setOnClickListener {
            selectedMode = DisplayScaleMode.FILL
            renderModeButtons()
        }
        stretchButton.setOnClickListener {
            selectedMode = DisplayScaleMode.STRETCH
            renderModeButtons()
        }
        modeRow.addView(fitButton, weighted())
        modeRow.addView(fillButton, weighted(left = 8))
        modeRow.addView(stretchButton, weighted(left = 8))
        content.addView(modeRow, linearParams(height = 44, bottom = 10))
        renderModeButtons()

        fun readNumber(input: EditText, fallback: Int): Int =
            input.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: fallback

        fun numberField(label: String, value: Int): Pair<LinearLayout, EditText> {
            val input = EditText(this).apply {
                setText(value.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                textSize = 14f
                setTextColor(color(R.color.dialog_input_text))
                setHintTextColor(color(R.color.dialog_input_hint))
                setBackgroundResource(R.drawable.bg_qitou_dialog_input)
                setPadding(dp(10), 0, dp(10), 0)
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MiniActivity).apply {
                    text = label
                    setTextColor(color(R.color.dialog_hint))
                    textSize = 11f
                })
                addView(input, linearParams(height = 44, bottom = 0))
            }
            return cell to input
        }

        val (outputWidthCell, outputWidthInput) = numberField(
            "自定义宽（0=跟随）",
            current.customWidth,
        )
        val (outputHeightCell, outputHeightInput) = numberField(
            "自定义高（0=跟随）",
            current.customHeight,
        )
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(outputWidthCell, weighted())
            addView(outputHeightCell, weighted(left = 10))
        }, linearParams(bottom = 8))
        content.addView(secondaryButton("恢复跟随车机尺寸") {
            outputWidthInput.setText("0")
            outputHeightInput.setText("0")
        }, linearParams(height = 44, bottom = 8))

        val (topCell, topInput) = numberField("上安全区（px）", current.insets.top)
        val (bottomCell, bottomInput) = numberField("下安全区（px）", current.insets.bottom)
        val (leftCell, leftInput) = numberField("左安全区（px）", current.insets.left)
        val (rightCell, rightInput) = numberField("右安全区（px）", current.insets.right)
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(topCell, weighted())
            addView(bottomCell, weighted(left = 10))
        }, linearParams(bottom = 8))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(leftCell, weighted())
            addView(rightCell, weighted(left = 10))
        }, linearParams(bottom = 8))

        content.addView(secondaryButton("清零安全区") {
            topInput.setText("0")
            bottomInput.setText("0")
            leftInput.setText("0")
            rightInput.setText("0")
        }, linearParams(height = 44))

        val dialog = CarDialogs.builder(this)
            .setTitle("车机画面适配")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var customWidth = readNumber(outputWidthInput, current.customWidth)
                var customHeight = readNumber(outputHeightInput, current.customHeight)
                val validCustom = customWidth == 0 && customHeight == 0 ||
                    customWidth in 240..1920 && customHeight in 240..1080
                if (!validCustom) {
                    customWidth = 0
                    customHeight = 0
                    Toast.makeText(
                        this,
                        "自定义分辨率需在 240–1920 × 240–1080 之间，已恢复跟随车机",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                val profile = VehicleDisplayProfile(
                    mode = selectedMode,
                    insets = CanvasInsets(
                        top = readNumber(topInput, current.insets.top),
                        bottom = readNumber(bottomInput, current.insets.bottom),
                        left = readNumber(leftInput, current.insets.left),
                        right = readNumber(rightInput, current.insets.right),
                    ),
                    customWidth = customWidth,
                    customHeight = customHeight,
                    sourceWidth = current.sourceWidth,
                    sourceHeight = current.sourceHeight,
                    sourceFollowsReported = current.sourceFollowsReported,
                )
                showDisplayProfileConfirmation(profile) {
                    applyDisplayProfile(current, profile)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showDisplayProfileConfirmation(
        profile: VehicleDisplayProfile,
        onConfirm: () -> Unit,
    ) {
        val (canvasWidth, canvasHeight) = configuredCanvasSize(profile)
        val canvasSource = if (profile.usesCustomOutput) "自定义" else "跟随车机上报"
        val source = profile.sourceRequest
        val rect = computeScreenViewport(
            canvasWidth,
            canvasHeight,
            source.width,
            source.height,
            profile.mode.screenFit(),
            profile.insets,
        )
        val horizontalBars = (canvasWidth - rect.width).coerceAtLeast(0)
        val verticalBars = (canvasHeight - rect.height).coerceAtLeast(0)
        val hint = buildString {
            append("画布 ${canvasWidth}×${canvasHeight}，实际画面 ${rect.width}×${rect.height}")
            append("\n左右留黑 ${horizontalBars}px，上下留黑 ${verticalBars}px")
            if (profile.mode == DisplayScaleMode.FIT && verticalBars > 0) {
                append("\n画布变高不会自动放大“完整显示”的画面；需要填满高度请选择“拉伸铺满”。")
            } else if (profile.usesCustomOutput) {
                append("\n自定义尺寸只改变编码画布，最终显示仍由车机视口决定。")
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        content.addView(TextView(this).apply {
            text = "显示方式：${profile.mode.label}\n" +
                "车机画布：$canvasWidth×$canvasHeight（$canvasSource）\n" +
                "手机车联源：${source.label}\n" +
                "安全区：${profile.insets.summary()}"
            setTextColor(color(R.color.dialog_message))
            textSize = 13f
            setLineSpacing(0f, 1.15f)
        }, linearParams(bottom = 12))
        content.addView(TextView(this).apply {
            text = hint
            setTextColor(color(R.color.dialog_hint))
            textSize = 12f
            setLineSpacing(0f, 1.15f)
        })
        CarDialogs.builder(this)
            .setTitle("确认画面适配")
            .setView(content)
            .setNegativeButton("返回修改", null)
            .setPositiveButton("确认保存") { _, _ -> onConfirm() }
            .show()
    }

    private fun applyDisplayProfile(
        current: VehicleDisplayProfile,
        profile: VehicleDisplayProfile,
    ) {
        VehicleDisplayProfileStore.save(this, profile)
        val outputChanged = current.customWidth != profile.customWidth ||
            current.customHeight != profile.customHeight
        if (outputChanged && MiniState.serviceActive) {
            Toast.makeText(
                this,
                "自定义分辨率已保存，重新连接后生效",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            MiniRuntime.pipeline?.updateCanvasLayout(
                profile.mode.screenFit(),
                profile.insets,
            )
            MiniState.setOutputSize(
                MiniState.outputWidth,
                MiniState.outputHeight,
                profile.summary(),
            )
            Toast.makeText(this, "画面适配已保存并应用", Toast.LENGTH_SHORT).show()
        }
        MiniState.log("[DISPLAY] ${profile.summary()}")
        render()
    }

    private fun configuredCanvasSize(profile: VehicleDisplayProfile): Pair<Int, Int> =
        VehicleDisplayProfileStore.loadReportedCanvas(this)
            ?.let { profile.resolveOutput(it.first, it.second) }
            ?: profile.resolveOutput(800, 386)

    private fun formatDisplayProfileSummary(profile: VehicleDisplayProfile): String = buildString {
        append(profile.summary())
        if (!profile.usesCustomOutput) {
            VehicleDisplayProfileStore.loadReportedCanvas(this@MiniActivity)?.let {
                append(" · 跟随 ${it.first}×${it.second}")
            }
        }
    }

    private fun showCarlifeSourceDialog() {
        val currentProfile = VehicleDisplayProfileStore.load(this)
        var selected = currentProfile.sourceRequest
        var followsReported = currentProfile.sourceFollowsReported
        var customSelected =
            !followsReported &&
                CarlifeSourceResolutions.presets.none { it == currentProfile.sourceRequest }
        var updatingFields = false
        lateinit var customWidthInput: EditText
        lateinit var customHeightInput: EditText
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        content.addView(TextView(this).apply {
            val actualStatus = when {
                MiniState.localCarLife ->
                    "本地 CarLife 当前使用手机屏幕：${MiniState.sourceWidth}×${MiniState.sourceHeight}。"
                MiniState.serviceActive ->
                    "当前车机投送实际收到：${MiniState.sourceWidth}×${MiniState.sourceHeight}。"
                else -> "当前没有正在运行的车联会话。"
            }
            text = "这是向手机 CarLife 发送端请求的视频编码尺寸，不影响车机输出分辨率。\n\n" +
                "$actualStatus\n" +
                "默认跟随车机上报尺寸；手动选择预设或修改数值后会固定为自定义值。\n" +
                "部分车联应用会忽略请求并使用自己的白名单尺寸；日志会以实际 SPS 为准。"
            setTextColor(color(R.color.dialog_message))
            textSize = 13f
            setLineSpacing(0f, 1.12f)
        }, linearParams(bottom = 10))

        fun sourceField(label: String, value: Int): Pair<LinearLayout, EditText> {
            val input = EditText(this).apply {
                setText(value.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                textSize = 14f
                setTextColor(color(R.color.dialog_input_text))
                setHintTextColor(color(R.color.dialog_input_hint))
                setBackgroundResource(R.drawable.bg_qitou_dialog_input)
                setPadding(dp(10), 0, dp(10), 0)
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MiniActivity).apply {
                    text = label
                    setTextColor(color(R.color.dialog_hint))
                    textSize = 11f
                })
                addView(input, linearParams(height = 44))
            }
            return cell to input
        }

        val customWidthField = sourceField("宽", selected.width)
        val customHeightField = sourceField("高", selected.height)
        customWidthInput = customWidthField.second
        customHeightInput = customHeightField.second

        val customHint = TextView(this).apply {
            setTextColor(color(R.color.dialog_hint))
            textSize = 11f
            setLineSpacing(0f, 1.1f)
            setPadding(dp(2), 0, dp(2), dp(6))
        }

        fun readSourceNumber(input: EditText): Int =
            input.text?.toString()?.toIntOrNull() ?: 0

        fun updateCustomHint() {
            if (followsReported) {
                val reported = VehicleDisplayProfileStore.loadReportedCanvas(this@MiniActivity)
                customHint.text = if (reported != null) {
                    "正在跟随车机上报：${reported.first}×${reported.second}；" +
                        "后续收到新尺寸会自动更新。"
                } else {
                    "尚未获取车机上报尺寸；首次获取后会更新为实际车机画布。"
                }
                return
            }
            if (!customSelected) {
                customHint.text =
                    "当前选择预设 ${selected.label}；修改宽或高后切换为自定义请求。"
                return
            }
            val width = readSourceNumber(customWidthInput)
            val height = readSourceNumber(customHeightInput)
            customHint.text = if (width in 240..1920 && height in 240..1080) {
                val normalized = CarlifeSourceResolutions.normalize(width, height)
                "将请求 ${normalized.label}；实际码流仍以车联应用和 SPS 为准。"
            } else {
                "宽需在 240–1920，高需在 240–1080；编码前会自动对齐偶数。"
            }
        }

        val choiceButtons = mutableListOf<Pair<CarlifeSourceResolution, Button>>()
        lateinit var followButton: Button
        fun renderChoices() {
            styleRouteButton(followButton, followsReported)
            choiceButtons.forEach { (resolution, button) ->
                styleRouteButton(
                    button,
                    !followsReported && !customSelected && resolution == selected,
                )
            }
        }

        fun currentReportedResolution(): CarlifeSourceResolution =
            VehicleDisplayProfileStore.loadReportedCanvas(this)
                ?.let { CarlifeSourceResolutions.normalize(it.first, it.second) }
                ?: CarlifeSourceResolutions.default

        fun selectReportedDefault() {
            followsReported = true
            selected = currentReportedResolution()
            customSelected =
                CarlifeSourceResolutions.presets.none { it == selected }
            updatingFields = true
            customWidthInput.setText(selected.width.toString())
            customHeightInput.setText(selected.height.toString())
            updatingFields = false
            renderChoices()
            updateCustomHint()
        }

        val reportedResolution = currentReportedResolution()
        followButton = secondaryButton("跟随车机上报 · ${reportedResolution.label}") {
            selectReportedDefault()
        }
        content.addView(followButton, linearParams(height = 48, bottom = 8))

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        CarlifeSourceResolutions.presets.chunked(2).forEachIndexed { rowIndex, rowPresets ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowPresets.forEachIndexed { index, resolution ->
                val button = secondaryButton(resolution.label) {
                    selected = resolution
                    customSelected = false
                    followsReported = false
                    updatingFields = true
                    customWidthInput.setText(resolution.width.toString())
                    customHeightInput.setText(resolution.height.toString())
                    updatingFields = false
                    renderChoices()
                    updateCustomHint()
                }
                choiceButtons += resolution to button
                row.addView(button, weighted(left = if (index == 0) 0 else 8))
            }
            if (rowPresets.size == 1) {
                row.addView(View(this), weighted(left = 8))
            }
            grid.addView(row, linearParams(height = 44, bottom = if (rowIndex == 2) 0 else 8))
        }
        content.addView(grid)

        content.addView(TextView(this).apply {
            text = "自定义源分辨率（偶数，240–1920 × 240–1080）"
            setTextColor(color(R.color.dialog_hint))
            textSize = 11f
            setPadding(0, dp(10), 0, dp(5))
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(customWidthField.first, weighted())
            addView(customHeightField.first, weighted(left = 10))
        }, linearParams(bottom = 8))
        content.addView(customHint)

        customWidthInput.doAfterTextChanged {
            if (updatingFields) return@doAfterTextChanged
            customSelected = true
            followsReported = false
            renderChoices()
            updateCustomHint()
        }
        customHeightInput.doAfterTextChanged {
            if (updatingFields) return@doAfterTextChanged
            customSelected = true
            followsReported = false
            renderChoices()
            updateCustomHint()
        }

        content.addView(secondaryButton("恢复默认（跟随车机上报）") {
            selectReportedDefault()
        }, linearParams(height = 44, top = 6))
        renderChoices()
        updateCustomHint()

        val dialog = CarDialogs.builder(this)
            .setTitle("CarLife 源分辨率请求")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val width = readSourceNumber(customWidthInput)
                val height = readSourceNumber(customHeightInput)
                if (!followsReported &&
                    customSelected &&
                    (width !in 240..1920 || height !in 240..1080)
                ) {
                    Toast.makeText(
                        this,
                        "CarLife 源分辨率需在 240–1920 × 240–1080 之间",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setOnClickListener
                }
                val sourceRequest = when {
                    followsReported -> VehicleDisplayProfileStore
                        .loadReportedCanvas(this)
                        ?.let { CarlifeSourceResolutions.normalize(it.first, it.second) }
                        ?: CarlifeSourceResolutions.default
                    customSelected -> CarlifeSourceResolutions.normalize(width, height)
                    else -> selected
                }
                val updated = VehicleDisplayProfile(
                    mode = currentProfile.mode,
                    insets = currentProfile.insets,
                    customWidth = currentProfile.customWidth,
                    customHeight = currentProfile.customHeight,
                    sourceWidth = sourceRequest.width,
                    sourceHeight = sourceRequest.height,
                    sourceFollowsReported = followsReported,
                )
                VehicleDisplayProfileStore.save(this, updated)
                MiniState.log("[CAR+] source request set to ${sourceRequest.label}")
                val applyingNow = MiniState.serviceActive && !MiniState.localCarLife
                if (applyingNow) {
                    CarPlusBridgeService.applyCarLifeSource(this, sourceRequest)
                }
                render()
                Toast.makeText(
                    this,
                    if (applyingNow && followsReported) {
                        "已恢复跟随车机上报 ${sourceRequest.label}，正在应用"
                    } else if (applyingNow) {
                        "已保存 ${sourceRequest.label}，正在重启 CarLife"
                    } else if (followsReported) {
                        "已保存，后续连接默认跟随车机上报尺寸"
                    } else {
                        "已保存 CarLife 源分辨率请求：${sourceRequest.label}"
                    },
                    Toast.LENGTH_LONG,
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun requestBikeAudioRoute() {
        if (Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            selectBikeAudioRoute()
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            CarDialogs.builder(this)
                .setTitle("需要车机蓝牙权限")
                .setMessage("授权后才能访问已配对的 CFMOTO，并选择车机 A2DP 声音。")
                .setNegativeButton("使用手机音频", null)
                .setPositiveButton("继续授权") { _, _ ->
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                .show()
        } else {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun selectBikeAudioRoute() {
        AudioVolumeStore.setOutputRoute(this, AudioOutputRoute.BIKE_BLUETOOTH)
        if (!MiniState.serviceActive) {
            MiniState.setAudio("车机蓝牙 · 需要 A2DP", Stage.WAIT)
        }
        renderAudioRouteButtons()
    }

    private fun showBluetoothPermissionRecovery() {
        val permanent =
            !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
        CarDialogs.builder(this)
            .setTitle("车机蓝牙不可用")
            .setMessage("已继续使用手机音频。若需要摩托车蓝牙声音，请授予蓝牙权限。")
            .setNegativeButton("继续使用手机", null)
            .setPositiveButton(if (permanent) "打开应用设置" else "重新授权") { _, _ ->
                if (permanent) {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                } else {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            .show()
    }

    private fun showBackgroundRunSettings() {
        val ignoringOptimization = SystemLinks.isIgnoringBatteryOptimizations(this)
        val backgroundGuide = BackgroundRestrictionGuide.forManufacturer(Build.MANUFACTURER)
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(2), padding, dp(4))
        }
        content.addView(TextView(this).apply {
            text = "建议先允许系统电池优化。只有随后仍出现息屏断连或画面停更时，" +
                "再检查${backgroundGuide.title}。"
            setTextColor(color(R.color.dialog_message))
            textSize = 14f
            setLineSpacing(0f, 1.12f)
        }, linearParams(bottom = 12))

        fun stepCard(step: String, title: String, status: String, statusColor: Int) =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                setBackground(
                    rounded(
                        color(R.color.dialog_input_surface),
                        14,
                        stroke = color(R.color.dialog_input_stroke),
                    ),
                )
                addView(LinearLayout(this@MiniActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MiniActivity).apply {
                        text = step
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(color(R.color.accent_on_primary))
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setBackground(rounded(color(R.color.accent_primary), 13))
                    }, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                        rightMargin = dp(9)
                    })
                    addView(TextView(this@MiniActivity).apply {
                        text = title
                        gravity = Gravity.CENTER_VERTICAL
                        includeFontPadding = false
                        setTextColor(color(R.color.dialog_title))
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                    }, weighted())
                    addView(TextView(this@MiniActivity).apply {
                        text = status
                        gravity = Gravity.CENTER_VERTICAL
                        includeFontPadding = false
                        setTextColor(statusColor)
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                })
            }

        val batteryCard = stepCard(
            "1",
            "系统电池优化",
            if (ignoringOptimization) "已允许" else "未允许",
            if (ignoringOptimization) color(R.color.success) else color(R.color.warning),
        )
        batteryCard.addView(TextView(this).apply {
            text = "防止 Android 在手机息屏后暂停骑投的后台网络。"
            setTextColor(color(R.color.dialog_message))
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.1f)
        })
        content.addView(batteryCard, linearParams(bottom = 10))

        val oemCard = stepCard(
            "2",
            backgroundGuide.title,
            "可选",
            color(R.color.text_label),
        )
        oemCard.addView(TextView(this).apply {
            text = "仅当第 1 项设置后仍然投送不稳定时，再打开应用详情，按以下顺序检查："
            setTextColor(color(R.color.dialog_message))
            textSize = 13f
            setPadding(0, dp(8), 0, dp(7))
        })
        fun pathText(text: String, weightedStep: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.dialog_code_text))
            textSize = 13f
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            if (weightedStep) {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }
        oemCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackground(
                rounded(
                    color(R.color.dialog_surface),
                    10,
                    stroke = color(R.color.dialog_input_stroke),
                ),
            )
            backgroundGuide.path.forEachIndexed { index, segment ->
                if (index > 0) {
                    addView(pathText("→").apply {
                        setTextColor(color(R.color.dialog_action))
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(24), -2))
                }
                addView(
                    pathText(segment, weightedStep = index == backgroundGuide.path.lastIndex),
                    if (index == backgroundGuide.path.lastIndex) weighted()
                    else LinearLayout.LayoutParams(-2, -2),
                )
            }
        })
        oemCard.addView(TextView(this).apply {
            text = "这一项不是强制设置；部分系统不开放状态读取，需要手动去应用详情页设置。"
            setTextColor(color(R.color.dialog_hint))
            textSize = 12f
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(oemCard, linearParams(bottom = 14))

        val dialog = CarDialogs.builder(this)
            .setTitle("设置后台运行")
            .setView(content)
            .create()
        if (!ignoringOptimization) {
            content.addView(
                secondaryButton("允许系统电池优化") {
                    dialog.dismiss()
                    SystemLinks.requestIgnoreBatteryOptimizations(this)
                },
                linearParams(height = 46, bottom = 10),
            )
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("关闭") { dialog.dismiss() }, weighted())
            addView(primaryButton("打开应用详情") {
                dialog.dismiss()
                SystemLinks.openApplicationDetails(this@MiniActivity)
            }, weighted(left = 10))
        }, linearParams(height = 50))
        dialog.show()
    }

    private fun primaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        stateListAnimator = null
        elevation = 0f
        setTextColor(color(R.color.accent_on_primary))
        setBackground(rounded(color(R.color.accent_primary), 13))
        setOnClickListener { click() }
    }

    private fun reconnectBridge() {
        if (reconnectPending) return
        reconnectPending = true
        val restartLocalOnly = MiniState.localOnly
        val restartLocalCarLife = MiniState.localCarLife
        localCarLifeFullscreenOpened = false
        MiniState.update("正在完整重启连接", 0)
        MiniState.log("[CAR+] manual reconnect requested")
        carPlusButton.isEnabled = false
        carPlusButton.text = "正在重连"
        CarPlusBridgeService.stop(this)
        awaitBridgeStopped(
            attempt = 0,
            restartLocalOnly = restartLocalOnly,
            restartLocalCarLife = restartLocalCarLife,
        )
    }

    private fun awaitBridgeStopped(
        attempt: Int,
        restartLocalOnly: Boolean,
        restartLocalCarLife: Boolean,
    ) {
        if (MiniState.serviceActive && attempt < 20) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                awaitBridgeStopped(
                    attempt + 1,
                    restartLocalOnly,
                    restartLocalCarLife,
                )
            }, 100)
            return
        }
        CarPlusLauncher.restartInstalledHosts(this, MiniState::log)
        Handler(Looper.getMainLooper()).postDelayed({
            reconnectPending = false
            if (isFinishing || isDestroyed) return@postDelayed
            if (restartLocalOnly) {
                startCarLifeInput(
                    sourceOnly = true,
                    localCarLife = restartLocalCarLife,
                )
            } else {
                requestNotificationThenStart()
            }
        }, 300)
    }

    private fun consumeReconnectRequest(intent: Intent?): Boolean {
        val requested = intent?.action == ACTION_RECONNECT ||
            intent?.getBooleanExtra(EXTRA_RECONNECT, false) == true
        intent?.removeExtra(EXTRA_RECONNECT)
        if (intent?.action == ACTION_RECONNECT) intent.action = null
        return requested
    }

    private fun launchBridge() {
        startCarLifeInput(sourceOnly = false)
    }

    private fun startCarLifeInput(
        sourceOnly: Boolean,
        localCarLife: Boolean = false,
    ) {
        if (CarPlusLauncher.canUseHonorLocalPresentation(this)) {
            MiniState.update("请在荣耀车联页面点击“开始连接”")
            MiniState.setCar("荣耀车联 · 等待点击“开始连接”", Stage.WAIT)
        }

        CarPlusBridgeService.start(
            this,
            sourceOnly = sourceOnly,
            localCarLife = localCarLife,
        )
        CarPlusLauncher.launch(this, MiniState::log)
        if (CarPlusLauncher.canUseHonorLocalPresentation(this)) {
            showHonorConnectionGuideToast()
        }
        render()
    }

    private fun showHonorConnectionGuideToast() {
        Toast.makeText(
            applicationContext,
            "请在荣耀车联中选择“无线 WLAN 连接” → 点击“开始连接” → 看到车联桌面后返回骑投",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun showManualPairing() {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(8), padding, 0)
        }
        val help = TextView(this).apply {
            text = "填写仪表显示的 Wi‑Fi 名称，例如 CFMOTO-4e5bc0；开放网络可留空密码。"
            setTextColor(color(R.color.dialog_message))
            textSize = 13f
            setLineSpacing(0f, 1.12f)
        }
        val ssid = EditText(this).apply {
            hint = "车机 Wi‑Fi 名称"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(color(R.color.dialog_input_text))
            setHintTextColor(color(R.color.dialog_input_hint))
            setBackgroundResource(R.drawable.bg_qitou_dialog_input)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val password = EditText(this).apply {
            hint = "Wi‑Fi 密码（可选）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(color(R.color.dialog_input_text))
            setHintTextColor(color(R.color.dialog_input_hint))
            setBackgroundResource(R.drawable.bg_qitou_dialog_input)
            setPadding(dp(14), 0, dp(14), 0)
        }
        content.addView(help, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })
        content.addView(ssid, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(password, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(10)
        })
        CarDialogs.builder(this)
            .setTitle("手动连接车机 Wi‑Fi")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("连接") { _, _ ->
                val vehicle = MotoVehicle.manual(
                    ssid.text?.toString().orEmpty(),
                    password.text?.toString().orEmpty(),
                )
                if (vehicle == null) {
                    Toast.makeText(this, "请输入 Wi‑Fi 名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val appContext = applicationContext
                Thread {
                    val saved = MotoVehiclePrefs.save(appContext, vehicle)
                    runOnUiThread {
                        if (!saved) {
                            Toast.makeText(
                                this@MiniActivity,
                                "无法安全保存车辆信息",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@runOnUiThread
                        }
                        MiniState.setVehicle(vehicle.displayName)
                        if (MiniState.serviceActive) CarPlusBridgeService.stop(this@MiniActivity)
                        startWifiThenBridge()
                    }
                }.apply {
                    name = "vehicle-prefs-save"
                    isDaemon = true
                    start()
                }
            }
            .show()
    }

    private fun shareLogs() {
        val appContext = applicationContext
        Thread {
            val file = runCatching {
                MiniState.exportLog(appContext)
            }.getOrElse { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "导出日志失败：${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@Thread
            }
            val uri = FileProvider.getUriForFile(
                appContext,
                "${packageName}.files",
                file,
            )
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "${Brand.CHINESE_NAME}诊断日志")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "包含全部轮转日志、诊断摘要和最近一次崩溃记录。",
                    )
                    clipData = android.content.ClipData.newRawUri(
                        "${Brand.CHINESE_NAME}诊断日志",
                        uri,
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    startActivity(Intent.createChooser(intent, "导出${Brand.CHINESE_NAME}日志"))
                }.onFailure {
                    Toast.makeText(this, "无法打开分享面板：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            name = "support-log-export"
            isDaemon = true
            start()
        }
    }

    private fun attachPreview(
        width: Int = preview.width,
        height: Int = preview.height,
    ) {
        val pipe = MiniRuntime.pipeline ?: return
        if (attached) return
        val texture = preview.surfaceTexture ?: return
        if (width <= 0 || height <= 0) return
        texture.setDefaultBufferSize(width, height)
        val surface = Surface(texture)
        previewSurface = surface
        pipe.attachPreview(surface, width, height)
        attached = true
        previewHint.visibility = View.GONE
    }

    private fun detachPreview() {
        if (!attached) return
        runCatching { MiniRuntime.pipeline?.clearPreview() }
        runCatching { previewSurface?.release() }
        previewSurface = null
        attached = false
    }

    private fun attachBikePreview(
        width: Int = bikePreview.width,
        height: Int = bikePreview.height,
    ) {
        if (!MiniState.localOnly || bikePreviewAttached) return
        val pipe = MiniRuntime.pipeline ?: return
        val texture = bikePreview.surfaceTexture ?: return
        if (width <= 0 || height <= 0) return
        texture.setDefaultBufferSize(width, height)
        val surface = Surface(texture)
        bikePreviewSurface = surface
        pipe.attachBikePreview(surface, width, height)
        bikePreviewAttached = true
    }

    private fun detachBikePreview() {
        if (!bikePreviewAttached) return
        runCatching { MiniRuntime.pipeline?.clearBikePreview() }
        runCatching { bikePreviewSurface?.release() }
        bikePreviewSurface = null
        bikePreviewAttached = false
    }

    private fun handlePreviewTouch(view: View, event: MotionEvent): Boolean {
        val pointer = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            event.actionIndex
        } else {
            event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
        }
        val mapped = mapPoint(event.getX(pointer), event.getY(pointer), view.width, view.height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (mapped != null) {
                lastX = mapped.first
                lastY = mapped.second
                MiniRuntime.receiver?.sendTouch(0, lastX, lastY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mapped != null) {
                MiniRuntime.receiver?.sendTouchComposite(
                    MotionEvent.ACTION_POINTER_DOWN or (event.actionIndex shl 8),
                    mapped.first,
                    mapped.second,
                )
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount <= 1) {
                    if (mapped != null) {
                        lastX = mapped.first
                        lastY = mapped.second
                        MiniRuntime.receiver?.sendTouch(2, lastX, lastY)
                    }
                } else {
                    for (index in 0 until event.pointerCount) {
                        val point = mapPoint(event.getX(index), event.getY(index), view.width, view.height)
                            ?: continue
                        MiniRuntime.receiver?.sendTouchComposite(
                            MotionEvent.ACTION_MOVE or (index shl 8),
                            point.first,
                            point.second,
                        )
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (mapped != null) {
                MiniRuntime.receiver?.sendTouchComposite(
                    MotionEvent.ACTION_POINTER_UP or (event.actionIndex shl 8),
                    mapped.first,
                    mapped.second,
                )
            }
            MotionEvent.ACTION_UP -> MiniRuntime.receiver?.sendTouch(1, lastX, lastY)
            MotionEvent.ACTION_CANCEL -> MiniRuntime.receiver?.sendTouch(1, lastX, lastY)
        }
        return true
    }

    private fun mapPoint(x: Float, y: Float, w: Int, h: Int): Pair<Int, Int>? {
        if (w <= 0 || h <= 0) return null
        val sourceAspect = MiniState.sourceWidth.toFloat() / MiniState.sourceHeight
        val viewAspect = w.toFloat() / h
        val drawW: Float
        val drawH: Float
        if (sourceAspect > viewAspect) {
            drawW = w.toFloat()
            drawH = drawW / sourceAspect
        } else {
            drawH = h.toFloat()
            drawW = drawH * sourceAspect
        }
        val left = (w - drawW) / 2f
        val top = (h - drawH) / 2f
        if (x < left || x >= left + drawW || y < top || y >= top + drawH) return null
        return (((x - left) * MiniState.sourceWidth / drawW).toInt()
            .coerceIn(0, MiniState.sourceWidth - 1)) to
            (((y - top) * MiniState.sourceHeight / drawH).toInt()
                .coerceIn(0, MiniState.sourceHeight - 1))
    }

    private fun render() {
        if (!::status.isInitialized) return
        val vehicle = MotoVehiclePrefs.load(this)
        status.text = MiniState.status
        status.textSize = if (MiniState.status.length > 24) 18f else 21f
        fps.text = if (MiniState.framesPerSecond > 0) {
            if (MiniState.localCarLife) {
                "${MiniState.framesPerSecond} fps · " +
                    "${MiniState.sourceWidth}×${MiniState.sourceHeight} 本地显示 · 手机音频"
            } else {
                "${MiniState.framesPerSecond} fps · " +
                    "${MiniState.sourceWidth}×${MiniState.sourceHeight} 控制源 · " +
                    "${MiniState.outputWidth}×${MiniState.outputHeight} 车机输出 · " +
                    MiniState.outputFit
            }
        } else if (MiniState.wifiStage == Stage.ERROR) {
            "请保持仪表停留在 MotoPlay 页面，确认 Wi-Fi 热点可搜索后重试"
        } else if (MiniState.serviceActive) {
            "等待手机车联画面"
        } else if (vehicle == null) {
            "扫码或手动填写 Wi-Fi 后开始连接"
        } else {
            "点击“连接已保存车辆”开始"
        }
        fps.setTextColor(when {
            MiniState.framesPerSecond > 0 -> color(R.color.success)
            MiniState.wifiStage == Stage.ERROR -> color(R.color.warning)
            MiniState.serviceActive -> color(R.color.text_body)
            else -> color(R.color.text_muted)
        })
        rowVehicle.text = if (MiniState.localOnly) {
            if (MiniState.localCarLife) "本地 CarLife" else "本地诊断"
        } else {
            vehicle?.displayName ?: MiniState.vehicleName.ifBlank { "未选择" }
        }
        rowWifi.text = MiniState.wifiText
        rowCar.text = MiniState.carText
        rowPxc.text = MiniState.pxcText
        rowAudio.text = MiniState.audioText
        rowBluetooth.text = MiniState.bluetoothText
        rowDecoder.text = MiniState.decoderCodec
        rowEncoder.text = MiniState.encoderCodec
        rowWifi.setTextColor(stageColor(MiniState.wifiStage))
        rowCar.setTextColor(stageColor(MiniState.carStage))
        rowPxc.setTextColor(stageColor(MiniState.pxcStage))
        rowAudio.setTextColor(stageColor(MiniState.audioStage))
        rowBluetooth.setTextColor(stageColor(MiniState.bluetoothStage))
        rowDecoder.setTextColor(codecColor(MiniState.decoderCodec))
        rowEncoder.setTextColor(codecColor(MiniState.encoderCodec))
        action.text = if (MiniState.serviceActive) {
            when {
                MiniState.localCarLife -> "停止本地连接"
                MiniState.localOnly -> "停止本地画面"
                else -> "停止投送"
            }
        } else {
            if (vehicle == null) "扫码连接车机" else "连接已保存车辆"
        }
        scanButton.visibility = if (vehicle == null) View.GONE else View.VISIBLE
        rowVehicle.setTextColor(
            if (vehicle == null && !MiniState.localOnly) {
                color(R.color.status_off)
            } else {
                color(R.color.success)
            },
        )
        autoConnectSwitch.isEnabled = vehicle != null
        autoConnectSwitch.alpha = if (vehicle != null) 1f else 0.55f
        action.setTextColor(
            if (MiniState.serviceActive) {
                color(R.color.text_on_stopped)
            } else {
                color(R.color.accent_on_primary)
            },
        )
        action.setBackground(
            rounded(
                if (MiniState.serviceActive) {
                    color(R.color.action_stopped)
                } else {
                    color(R.color.accent_primary)
                },
                16,
            ),
        )
        fullscreenButton.isEnabled = MiniState.running
        fullscreenButton.alpha = if (MiniState.running) 1f else 0.62f
        fullscreenButton.text = "全屏操作画面"
        val carPlusAvailable = MiniState.serviceActive
        carPlusButton.visibility = if (MiniState.serviceActive) View.VISIBLE else View.GONE
        carPlusHint.visibility = carPlusButton.visibility
        carPlusButton.isEnabled = carPlusAvailable
        carPlusButton.alpha = if (carPlusAvailable) 1f else 0.65f
        val honorCarConnect = CarPlusLauncher.canUseHonorLocalPresentation(this)
        carPlusButton.text = "重新连接"
        carPlusHint.text = when {
            MiniState.localCarLife ->
                "本地 CarLife 始终使用手机音频；画面中断时可一键重新启动。"
            honorCarConnect ->
                "会再次打开荣耀车联连接页；请点击“开始连接”恢复画面。"
            else -> "车联画面中断或应用被关闭时，可点“重新连接”恢复。"
        }
        if (::displayProfileSummary.isInitialized) {
            val profile = VehicleDisplayProfileStore.load(this)
            displayProfileSummary.text =
                "当前：${formatDisplayProfileSummary(profile)}"
        }
        if (::carlifeSourceSummary.isInitialized) {
            val profile = VehicleDisplayProfileStore.load(this)
            val source = profile.sourceRequest
            carlifeSourceSummary.text =
                "车机投屏与模拟输出：${source.label}" +
                (if (profile.sourceFollowsReported) "（跟随车机上报）" else "（手动覆盖）") +
                "；本地 CarLife：使用手机屏幕分辨率"
        }
        if (::phoneRouteButton.isInitialized) renderAudioRouteButtons()
        if (::simulationStatus.isInitialized) {
            val simulationActive =
                MiniState.serviceActive && MiniState.localOnly && !MiniState.localCarLife
            simulationStatus.text = when {
                simulationActive -> "模拟车机端效果运行中"
                MiniState.serviceActive -> "其他投送正在运行"
                else -> "未启动"
            }
            simulationStatus.setTextColor(
                if (simulationActive) color(R.color.success) else color(R.color.text_heading),
            )
            simulationFps.text = if (simulationActive && MiniState.framesPerSecond > 0) {
                "${MiniState.framesPerSecond} fps · ${MiniState.sourceWidth}×" +
                    "${MiniState.sourceHeight} → ${MiniState.outputWidth}×${MiniState.outputHeight}"
            } else {
                "模拟实际发送给车机的画面与布局。"
            }
            simulationAction.text = when {
                simulationActive -> "停止模拟输出"
                MiniState.serviceActive -> "其他投送正在运行"
                else -> "开始模拟输出"
            }
            simulationAction.isEnabled = simulationActive || !MiniState.serviceActive
            simulationAction.setTextColor(
                if (simulationActive) color(R.color.text_on_stopped)
                else color(R.color.accent_on_primary),
            )
            simulationAction.background = rounded(
                if (simulationActive) color(R.color.action_stopped)
                else color(R.color.accent_primary),
                18,
            )
        }
        if (::localStatus.isInitialized) {
            localStatus.text = when {
                MiniState.serviceActive && MiniState.localCarLife -> "本地 CarLife 已启动"
                MiniState.serviceActive -> "其他投送正在运行"
                else -> "未启动"
            }
            localStatus.setTextColor(
                if (MiniState.serviceActive && MiniState.localCarLife) {
                    color(R.color.success)
                } else {
                    color(R.color.text_heading)
                },
            )
            localFps.text = if (MiniState.localCarLife && MiniState.framesPerSecond > 0) {
                "${MiniState.framesPerSecond} fps · ${MiniState.sourceWidth}×" +
                    "${MiniState.sourceHeight} · 手机音频"
            } else {
                "画面连接成功后将自动进入全屏。"
            }
            localAction.text = when {
                MiniState.serviceActive && MiniState.localCarLife -> "停止本地连接"
                MiniState.serviceActive -> "其他投送正在运行"
                else -> "开始本地 CarLife"
            }
            localAction.isEnabled =
                !MiniState.serviceActive || MiniState.localCarLife
            localAction.setTextColor(
                if (MiniState.serviceActive && MiniState.localCarLife) {
                    color(R.color.text_on_stopped)
                } else {
                    color(R.color.accent_on_primary)
                },
            )
            localAction.background = rounded(
                if (MiniState.serviceActive && MiniState.localCarLife) {
                    color(R.color.action_stopped)
                } else {
                    color(R.color.accent_primary)
                },
                18,
            )
            val localReady = MiniState.running && MiniState.localCarLife
            localOpenButton.isEnabled = localReady
            localOpenButton.alpha = if (localReady) 1f else 0.55f
            localReconnectButton.visibility =
                if (MiniState.serviceActive && MiniState.localCarLife) View.VISIBLE else View.GONE
        }
        if (::aboutVersion.isInitialized) aboutVersion.text = versionLabel()
        settingsButton.visibility =
            if (selectedTab == HomeTab.ABOUT) View.GONE else View.VISIBLE
        previewHint.visibility = if (attached && MiniState.framesPerSecond > 0) View.GONE else View.VISIBLE
        if (::bikePreviewHint.isInitialized) {
            bikePreviewHint.visibility =
                if (MiniState.framesPerSecond > 0) View.GONE else View.VISIBLE
        }
        syncPreviewForTab()
        if (
            MiniState.localCarLife &&
            MiniState.framesPerSecond > 0 &&
            !localCarLifeFullscreenOpened
        ) {
            localCarLifeFullscreenOpened = true
            startActivity(Intent(this, ControlActivity::class.java))
        } else if (!MiniState.localCarLife) {
            localCarLifeFullscreenOpened = false
        }
    }

    private fun addStatusPair(
        parent: LinearLayout,
        firstLabel: String,
        firstValue: String,
        secondLabel: String,
        secondValue: String,
    ): Pair<TextView, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val first = addStatusCell(row, firstLabel, firstValue)
        val second = addStatusCell(row, secondLabel, secondValue, left = 12)
        parent.addView(row, linearParams(height = 48))
        return first to second
    }

    private fun codecColor(value: String): Int =
        if (MiniState.serviceActive && value != "未启动") {
            color(R.color.success)
        } else {
            color(R.color.text_muted)
        }

    private fun addStatusCell(
        parent: LinearLayout,
        label: String,
        initial: String,
        left: Int = 0,
    ): TextView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cell.addView(TextView(this).apply {
            text = label
            setTextColor(color(R.color.text_label))
            textSize = 11f
        })
        val value = TextView(this).apply {
            text = initial
            setTextColor(color(R.color.text_body))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        cell.addView(value)
        parent.addView(cell, weighted(left))
        return value
    }

    private fun secondaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        stateListAnimator = null
        elevation = 0f
        setTextColor(color(R.color.button_secondary_text))
        setBackground(rounded(color(R.color.button_secondary_surface), 13))
        setOnClickListener { click() }
    }

    private fun compactToggleButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        stateListAnimator = null
        elevation = 0f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(color(R.color.accent_primary))
        setBackground(rounded(color(R.color.button_secondary_surface), 12))
        setOnClickListener { click() }
    }

    private fun toggleSection(
        body: LinearLayout,
        summary: TextView?,
        button: Button,
    ) {
        val expand = body.visibility != View.VISIBLE
        body.visibility = if (expand) View.VISIBLE else View.GONE
        summary?.visibility = if (expand) View.GONE else View.VISIBLE
        button.text = if (expand) "收起" else "展开"
    }

    private fun renderAudioSummary() {
        if (!::audioSummary.isInitialized) return
        val route = when {
            MiniState.localCarLife -> "手机（本地 CarLife）"
            AudioVolumeStore.current.outputRoute == AudioOutputRoute.PHONE -> "手机"
            else -> "车机蓝牙"
        }
        audioSummary.text = "音乐 ${AudioVolumeStore.current.mediaPercent}% · " +
            "导航 ${AudioVolumeStore.current.navigationPercent}% · $route"
    }

    private fun routeButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        stateListAnimator = null
        elevation = 0f
        setOnClickListener { click() }
    }

    private fun renderAudioRouteButtons() {
        if (!::phoneRouteButton.isInitialized || !::bikeRouteButton.isInitialized) return
        val phoneSelected =
            MiniState.localCarLife ||
                AudioVolumeStore.current.outputRoute == AudioOutputRoute.PHONE
        styleRouteButton(phoneRouteButton, phoneSelected)
        styleRouteButton(bikeRouteButton, !phoneSelected)
        phoneRouteButton.isEnabled = !MiniState.localCarLife
        bikeRouteButton.isEnabled = !MiniState.localCarLife
        phoneRouteButton.alpha = if (MiniState.localCarLife) 0.82f else 1f
        bikeRouteButton.alpha = if (MiniState.localCarLife) 0.45f else 1f
        if (::audioRouteHint.isInitialized) {
            audioRouteHint.text = if (MiniState.localCarLife) {
                "本地 CarLife 始终使用手机音频；默认跟随手机当前媒体设备（扬声器或耳机）。"
            } else {
                "“手机”跟随系统当前媒体输出（扬声器或耳机）；" +
                    "“车机蓝牙”仅使用已连接的摩托车 A2DP。"
            }
        }
        renderAudioSummary()
    }

    private fun styleRouteButton(button: Button, selected: Boolean) {
        button.setTextColor(
            if (selected) {
                color(R.color.accent_on_primary)
            } else {
                color(R.color.button_secondary_text)
            },
        )
        button.setBackground(
            rounded(
                if (selected) {
                    color(R.color.accent_primary)
                } else {
                    color(R.color.button_secondary_surface)
                },
                13,
                if (selected) null else color(R.color.button_secondary_stroke),
            ),
        )
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChanged(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun weighted(left: Int = 0) = LinearLayout.LayoutParams(0, -1, 1f).apply {
        leftMargin = dp(left)
    }

    private fun stageColor(stage: Stage): Int = when (stage) {
        Stage.OK -> color(R.color.success)
        Stage.WAIT -> color(R.color.warning)
        Stage.ERROR -> color(R.color.error)
        Stage.OFF -> color(R.color.status_off)
    }

    private fun linearParams(
        width: Int = LinearLayout.LayoutParams.MATCH_PARENT,
        height: Int = -2,
        top: Int = 0,
        bottom: Int = 0,
    ) = LinearLayout.LayoutParams(
        width,
        if (height > 0) dp(height) else height,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun gradient() = CarAppTheme.canvas(this)

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun color(@ColorRes resource: Int): Int = CarAppTheme.color(this, resource)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    companion object {
        const val ACTION_RECONNECT = "app.qitou.bridge.RECONNECT"
        const val EXTRA_RECONNECT = "reconnect"
        private const val REQUEST_QR = 303
        private const val APP_PREFS = "qitou_app"
        private const val KEY_INITIAL_PERMISSIONS_SHOWN = "initial_permissions_shown"
        private const val KEY_HOME_TAB = "home_tab"
    }

    private enum class PermissionPurpose {
        FIRST_LAUNCH,
        START_BRIDGE,
    }

    private enum class HomeTab {
        PROJECTION,
        SIMULATION,
        LOCAL_CAR_LIFE,
        ABOUT,
    }
}
