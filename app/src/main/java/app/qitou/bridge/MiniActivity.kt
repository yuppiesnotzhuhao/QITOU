package app.qitou.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
    private lateinit var phoneRouteButton: Button
    private lateinit var bikeRouteButton: Button
    private lateinit var preview: TextureView
    private var previewSurface: Surface? = null
    private lateinit var previewHint: TextView
    private lateinit var rowVehicle: TextView
    private lateinit var rowWifi: TextView
    private lateinit var rowCar: TextView
    private lateinit var rowPxc: TextView
    private lateinit var rowAudio: TextView
    private lateinit var rowBluetooth: TextView
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
        setContentView(buildUi())
        MiniState.listener = { runOnUiThread(::render) }
        val vehicle = MotoVehiclePrefs.load(this)
        if (vehicle != null) MiniState.setVehicle(vehicle.displayName)
        render()
        window.decorView.post(::runInitialLaunchAutomation)
    }

    override fun onResume() {
        super.onResume()
        MiniState.listener = { runOnUiThread(::render) }
        attachPreview()
        render()
    }

    override fun onPause() {
        MiniState.listener = null
        detachPreview()
        super.onPause()
    }

    override fun onDestroy() {
        MiniState.listener = null
        super.onDestroy()
    }

    @Deprecated("Activity result for the embedded QR scanner")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_QR && resultCode == RESULT_OK) startWifiThenBridge()
    }

    private fun buildUi(): View {
        val background = gradient()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
            setBackground(background)
        }

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MiniActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = Brand.FULL_NAME
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(LinearLayout(this@MiniActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MiniActivity).apply {
                    text = Brand.CHINESE_NAME
                    setTextColor(color(R.color.text_heading))
                    textSize = 25f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MiniActivity).apply {
                    text = Brand.LATIN_NAME
                    setTextColor(color(R.color.text_body))
                    textSize = 11f
                    letterSpacing = 0.14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
            })
        })

        root.addView(TextView(this).apply {
            text = "手机控制画面"
            setTextColor(color(R.color.text_heading))
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "画面直接显示在应用内，触摸这里即可控制 Car+。"
            setTextColor(color(R.color.text_body))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })

        val previewFrame = FrameLayout(this).apply {
            setBackground(
                rounded(
                    color(R.color.surface_preview),
                    18,
                    stroke = color(R.color.stroke_preview),
                ),
            )
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val previewClip = FrameLayout(this).apply {
            setBackground(rounded(android.graphics.Color.BLACK, 12))
            clipToOutline = true
        }
        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(1280, 720)
                    attachPreview()
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    detachPreview()
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
            setOnTouchListener { view, event -> handlePreviewTouch(view, event) }
        }
        previewClip.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        previewFrame.addView(previewClip, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        previewHint = TextView(this).apply {
            text = "连接后画面显示在这里"
            setTextColor(color(R.color.text_on_video))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        previewFrame.addView(previewHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(previewFrame, linearParams(height = 150, bottom = 10))

        fullscreenButton = secondaryButton("全屏控制画面") {
            if (MiniRuntime.pipeline == null || !MiniState.running) {
                Toast.makeText(this@MiniActivity, "请先启动投送", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this@MiniActivity, ControlActivity::class.java))
            }
        }
        root.addView(fullscreenButton, linearParams(height = 48, bottom = 12))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setBackground(
                rounded(
                    color(R.color.surface_card),
                    16,
                    stroke = color(R.color.stroke_card),
                ),
            )
        }
        statusCard.addView(TextView(this).apply {
            text = "当前状态"
            setTextColor(color(R.color.text_label))
            textSize = 12f
        })
        status = TextView(this).apply {
            setTextColor(color(R.color.text_heading))
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, 0)
        }
        statusCard.addView(status)
        fps = TextView(this).apply {
            setTextColor(color(R.color.success))
            textSize = 12f
            setPadding(0, dp(3), 0, dp(7))
        }
        statusCard.addView(fps)
        addStatusPair(statusCard, "车辆", "未选择", "Wi-Fi", "未连接").let {
            rowVehicle = it.first
            rowWifi = it.second
        }
        addStatusPair(statusCard, "Car+ 画面", "未启动", "MotoPlay", "未启动").let {
            rowCar = it.first
            rowPxc = it.second
        }
        addStatusPair(statusCard, "音频", "未连接", "蓝牙 A2DP", "未连接").let {
            rowAudio = it.first
            rowBluetooth = it.second
        }
        root.addView(statusCard, linearParams(bottom = 14))

        val connectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackground(
                rounded(
                    color(R.color.surface_card),
                    16,
                    stroke = color(R.color.stroke_card),
                ),
            )
        }
        connectionCard.addView(TextView(this).apply {
            text = "连接车机"
            setTextColor(color(R.color.text_heading))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        action = Button(this).apply {
            textSize = 17f
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            setTextColor(color(R.color.accent_on_primary))
            setBackground(rounded(color(R.color.accent_primary), 16))
            setOnClickListener {
                if (MiniState.serviceActive) {
                    CarPlusBridgeService.stop(this@MiniActivity)
                    render()
                } else {
                    requestNotificationThenStart()
                }
            }
        }
        connectionCard.addView(action, linearParams(height = 52, bottom = 7))
        autoConnectSwitch = Switch(this).apply {
            text = "启动时自动连接已保存车辆"
            setTextColor(color(R.color.text_body))
            textSize = 13f
            isChecked = AutoConnectStore.isEnabled(this@MiniActivity)
            isEnabled = MotoVehiclePrefs.load(this@MiniActivity) != null
            setOnCheckedChangeListener { _, checked ->
                AutoConnectStore.setEnabled(this@MiniActivity, checked)
                if (checked) {
                    Toast.makeText(
                        this@MiniActivity,
                        "已开启，下次打开应用后自动连接",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        connectionCard.addView(autoConnectSwitch)
        connectionCard.addView(TextView(this).apply {
            text = "默认关闭；仅在保存过车机且当前未投送时自动连接。"
            setTextColor(color(R.color.text_body))
            textSize = 11f
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(3), 0, 0)
        })
        root.addView(connectionCard, linearParams(bottom = 14))

        val audioCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackground(
                rounded(
                    color(R.color.surface_card),
                    16,
                    stroke = color(R.color.stroke_card),
                ),
            )
        }
        audioSummary = TextView(this).apply {
            setTextColor(color(R.color.success))
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        }
        val audioText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        audioText.addView(TextView(this).apply {
            text = "音量与混音"
            setTextColor(color(R.color.text_heading))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        audioText.addView(audioSummary)
        val audioHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        audioHeader.addView(audioText, weighted())
        audioToggleButton = compactToggleButton("展开") {
            toggleSection(audioBody, audioSummary, audioToggleButton)
        }
        audioHeader.addView(audioToggleButton, LinearLayout.LayoutParams(-2, dp(48)))
        audioCard.addView(audioHeader)

        audioBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        audioBody.addView(TextView(this).apply {
            text = "只调整${Brand.CHINESE_NAME}接收到的 CarLife 音频；摩托车或耳机总音量仍由 A2DP 控制。"
            setTextColor(color(R.color.text_body))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(7))
        })
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaRow.addView(TextView(this).apply {
            text = "音乐"
            setTextColor(color(R.color.text_body))
            textSize = 13f
        }, LinearLayout.LayoutParams(dp(52), -2))
        mediaVolumeSeek = SeekBar(this).apply {
            max = 100
            progress = AudioVolumeStore.current.mediaPercent
            setOnSeekBarChangeListener(simpleSeekListener { value ->
                AudioVolumeStore.setMedia(this@MiniActivity, value)
                mediaVolumeText.text = "$value%"
                renderAudioSummary()
            })
        }
        mediaRow.addView(mediaVolumeSeek, LinearLayout.LayoutParams(0, dp(38), 1f))
        mediaVolumeText = TextView(this).apply {
            text = "${AudioVolumeStore.current.mediaPercent}%"
            setTextColor(color(R.color.success))
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        mediaRow.addView(mediaVolumeText, LinearLayout.LayoutParams(dp(44), -2))
        audioBody.addView(mediaRow)

        val navigationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navigationRow.addView(TextView(this).apply {
            text = "导航"
            setTextColor(color(R.color.text_body))
            textSize = 13f
        }, LinearLayout.LayoutParams(dp(52), -2))
        navigationVolumeSeek = SeekBar(this).apply {
            max = 100
            progress = AudioVolumeStore.current.navigationPercent
            setOnSeekBarChangeListener(simpleSeekListener { value ->
                AudioVolumeStore.setNavigation(this@MiniActivity, value)
                navigationVolumeText.text = "$value%"
                renderAudioSummary()
            })
        }
        navigationRow.addView(navigationVolumeSeek, LinearLayout.LayoutParams(0, dp(38), 1f))
        navigationVolumeText = TextView(this).apply {
            text = "${AudioVolumeStore.current.navigationPercent}%"
            setTextColor(color(R.color.success))
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        navigationRow.addView(navigationVolumeText, LinearLayout.LayoutParams(dp(44), -2))
        audioBody.addView(navigationRow)

        duckSwitch = Switch(this).apply {
            text = "导航播报时自动压低音乐"
            setTextColor(color(R.color.text_body))
            textSize = 13f
            isChecked = AudioVolumeStore.current.duckMediaDuringNavigation
            setPadding(0, dp(3), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                AudioVolumeStore.setAutoDuck(this@MiniActivity, checked)
            }
        }
        audioBody.addView(duckSwitch)
        audioBody.addView(TextView(this).apply {
            text = "声音输出设备"
            setTextColor(color(R.color.text_body))
            textSize = 13f
            setPadding(0, dp(9), 0, dp(5))
        })
        audioBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            phoneRouteButton = routeButton("手机") {
                AudioVolumeStore.setOutputRoute(
                    this@MiniActivity,
                    AudioOutputRoute.PHONE,
                )
                if (!MiniState.serviceActive) {
                    MiniState.setAudio("手机 · 投送未启动", Stage.OFF)
                }
                renderAudioRouteButtons()
            }
            bikeRouteButton = routeButton("车机蓝牙") {
                requestBikeAudioRoute()
            }
            addView(phoneRouteButton, weighted())
            addView(bikeRouteButton, weighted(left = 10))
        }, linearParams(height = 48, bottom = 3))
        audioBody.addView(TextView(this).apply {
            text = "“手机”使用 Android 当前默认媒体输出，可能是有线/蓝牙耳机或扬声器；" +
                "“车机蓝牙”只使用已连接的摩托车 A2DP，未连接时不会自动回退。"
            setTextColor(color(R.color.text_body))
            textSize = 11f
            setLineSpacing(0f, 1.1f)
        })
        audioCard.addView(audioBody)
        root.addView(audioCard, linearParams(bottom = 14))
        renderAudioRouteButtons()

        val advancedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackground(
                rounded(
                    color(R.color.surface_card),
                    16,
                    stroke = color(R.color.stroke_card),
                ),
            )
        }
        val advancedText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        advancedText.addView(TextView(this).apply {
            text = "高级工具"
            setTextColor(color(R.color.text_heading))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        advancedText.addView(TextView(this).apply {
            text = "重新配对、Car+ 恢复、日志与系统设置"
            setTextColor(color(R.color.text_body))
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        })
        val advancedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        advancedHeader.addView(advancedText, weighted())
        advancedToggleButton = compactToggleButton("展开") {
            toggleSection(advancedBody, null, advancedToggleButton)
        }
        advancedHeader.addView(advancedToggleButton, LinearLayout.LayoutParams(-2, dp(48)))
        advancedCard.addView(advancedHeader)

        advancedBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        scanButton = secondaryButton("重新扫码配对") {
            if (MiniState.serviceActive) CarPlusBridgeService.stop(this@MiniActivity)
            startActivityForResult(Intent(this@MiniActivity, QrScanActivity::class.java), REQUEST_QR)
        }
        advancedBody.addView(scanButton, linearParams(height = 48, bottom = 8))

        advancedBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("手动 Wi-Fi") { showManualPairing() }, weighted())
            addView(secondaryButton("蓝牙设置") {
                SystemLinks.openBluetoothSettings(this@MiniActivity)
            }, weighted(left = 10))
        }, linearParams(height = 48, bottom = 8))

        carPlusButton = secondaryButton("重新连接 Car+") {
            CarPlusLauncher.launch(this@MiniActivity, MiniState::log)
        }
        advancedBody.addView(carPlusButton, linearParams(height = 48, bottom = 3))
        advancedBody.addView(TextView(this).apply {
            text = "仅在投送过程中 OPPO Car+ 被关闭时使用"
            setTextColor(color(R.color.text_body))
            textSize = 11f
            setPadding(dp(4), 0, 0, 0)
        }, linearParams(bottom = 8))

        advancedBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(secondaryButton("后台运行设置") {
                showBackgroundRunSettings()
            }, weighted())
            addView(secondaryButton("导出诊断包") { shareLogs() }, weighted(left = 10))
        }, linearParams(height = 48, bottom = 4))
        advancedCard.addView(advancedBody)
        root.addView(advancedCard, linearParams(bottom = 14))

        root.addView(secondaryButton("关于${Brand.CHINESE_NAME} · 版本与开源许可") {
            startActivity(Intent(this@MiniActivity, AboutActivity::class.java))
        }, linearParams(height = 48, bottom = 18))

        root.addView(TextView(this).apply {
            text = "使用步骤\n" +
                "1. 首次点“扫码连接车机”；以后点“连接已保存车辆”或开启启动自动连接\n" +
                "2. 在 OPPO Car+ 中选择 CarLife 无线连接\n" +
                "3. 保持仪表在手机互联页面；断线后会自动重连\n" +
                "4. 在“手机控制画面”或“全屏控制”中操作 Car+"
            setTextColor(color(R.color.text_body))
            textSize = 14f
            setLineSpacing(0f, 1.15f)
        })

        val scroll = ScrollView(this).apply {
            setBackground(background)
            isFillViewport = true
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
        return scroll
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
                MiniState.update(error)
            },
        )
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
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(2), padding, dp(4))
        }
        content.addView(TextView(this).apply {
            text = "建议先允许系统电池优化。只有随后仍出现息屏断连或画面停更时，" +
                "再检查 ColorOS 后台行为。"
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
                        setTextColor(color(R.color.accent_on_primary))
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setBackground(rounded(color(R.color.accent_primary), 13))
                    }, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                        rightMargin = dp(9)
                    })
                    addView(TextView(this@MiniActivity).apply {
                        text = title
                        setTextColor(color(R.color.dialog_title))
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                    }, weighted())
                    addView(TextView(this@MiniActivity).apply {
                        text = status
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

        val colorOsCard = stepCard(
            "2",
            "ColorOS 后台行为",
            "可选",
            color(R.color.text_label),
        )
        colorOsCard.addView(TextView(this).apply {
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
        colorOsCard.addView(LinearLayout(this).apply {
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
            addView(pathText("应用信息"))
            addView(pathText("→").apply {
                setTextColor(color(R.color.dialog_action))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(24), -2))
            addView(pathText("耗电管理"))
            addView(pathText("→").apply {
                setTextColor(color(R.color.dialog_action))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(24), -2))
            addView(pathText("完全允许后台行为", weightedStep = true), weighted())
        })
        colorOsCard.addView(TextView(this).apply {
            text = "这一项不是强制设置；ColorOS 不开放状态读取，只能在需要时手动检查。"
            setTextColor(color(R.color.dialog_hint))
            textSize = 12f
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(colorOsCard, linearParams(bottom = 14))

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

    private fun launchBridge() {
        CarPlusBridgeService.start(this)
        CarPlusLauncher.launch(this, MiniState::log)
        render()
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

    private fun attachPreview() {
        val pipe = MiniRuntime.pipeline ?: return
        if (attached) return
        val texture = preview.surfaceTexture ?: return
        val surface = Surface(texture)
        previewSurface = surface
        pipe.attachPreview(surface, 1280, 720)
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
        val sourceAspect = 1280f / 480f
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
        return (((x - left) * 1280 / drawW).toInt().coerceIn(0, 1279)) to
            (((y - top) * 480 / drawH).toInt().coerceIn(0, 479))
    }

    private fun render() {
        if (!::status.isInitialized) return
        status.text = MiniState.status
        fps.text = if (MiniState.framesPerSecond > 0) {
            "${MiniState.framesPerSecond} fps · 1280×480 控制源 · 800×384 车机输出"
        } else {
            "等待 Car+ 画面"
        }
        fps.setTextColor(
            if (MiniState.serviceActive) color(R.color.success) else color(R.color.text_muted),
        )
        val vehicle = MotoVehiclePrefs.load(this)
        rowVehicle.text = vehicle?.displayName ?: MiniState.vehicleName.ifBlank { "未选择" }
        rowWifi.text = MiniState.wifiText
        rowCar.text = MiniState.carText
        rowPxc.text = MiniState.pxcText
        rowAudio.text = MiniState.audioText
        rowBluetooth.text = MiniState.bluetoothText
        rowWifi.setTextColor(stageColor(MiniState.wifiStage))
        rowCar.setTextColor(stageColor(MiniState.carStage))
        rowPxc.setTextColor(stageColor(MiniState.pxcStage))
        rowAudio.setTextColor(stageColor(MiniState.audioStage))
        rowBluetooth.setTextColor(stageColor(MiniState.bluetoothStage))
        action.text = if (MiniState.serviceActive) "停止投送" else {
            if (vehicle == null) "扫码连接车机" else "连接已保存车辆"
        }
        scanButton.visibility = if (vehicle == null) View.GONE else View.VISIBLE
        rowVehicle.setTextColor(
            if (vehicle == null) color(R.color.status_off) else color(R.color.success),
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
        val carPlusAvailable = MiniState.serviceActive && MiniState.wifiStage == Stage.OK
        carPlusButton.isEnabled = carPlusAvailable
        carPlusButton.alpha = if (carPlusAvailable) 1f else 0.48f
        if (::phoneRouteButton.isInitialized) renderAudioRouteButtons()
        previewHint.visibility = if (attached && MiniState.framesPerSecond > 0) View.GONE else View.VISIBLE
        if (MiniState.running) attachPreview()
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
        val route = if (AudioVolumeStore.current.outputRoute == AudioOutputRoute.PHONE) {
            "手机"
        } else {
            "车机蓝牙"
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
        val phoneSelected = AudioVolumeStore.current.outputRoute == AudioOutputRoute.PHONE
        styleRouteButton(phoneRouteButton, phoneSelected)
        styleRouteButton(bikeRouteButton, !phoneSelected)
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
        bottom: Int = 0,
    ) = LinearLayout.LayoutParams(
        width,
        if (height > 0) dp(height) else height,
    ).apply { bottomMargin = dp(bottom) }

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
        private const val REQUEST_QR = 303
        private const val APP_PREFS = "qitou_app"
        private const val KEY_INITIAL_PERMISSIONS_SHOWN = "initial_permissions_shown"
    }

    private enum class PermissionPurpose {
        FIRST_LAUNCH,
        START_BRIDGE,
    }
}
