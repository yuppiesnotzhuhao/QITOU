package app.qitou.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Locale

class QrScanActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var zoomLabel: TextView
    private var camera: Camera? = null
    private lateinit var scaleDetector: ScaleGestureDetector
    private var minZoom = 1f
    private var maxZoom = 1f
    private var currentZoom = 1f
    private var zoomRangeLogged = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L
    private val executor = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            showCameraPermissionRecovery()
        }
    }
    @Volatile private var busy = false
    @Volatile private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // scaleFactor is incremental relative to the previous gesture event.
                setZoom(currentZoom * detector.scaleFactor)
                return true
            }
        })
        previewView.setOnTouchListener(::handlePreviewTouch)
        status = TextView(this).apply {
            text = "对准仪表上的配对二维码\n支持双指缩放；点击画面可对焦"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minHeight = dp(92)
        }
        lateinit var backButton: Button
        lateinit var zoomControls: LinearLayout
        fun zoomButton(label: String, delta: Float) = Button(this).apply {
            text = label
            textSize = 28f
            isAllCaps = false
            stateListAnimator = null
            elevation = 0f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            setBackground(CarAppTheme.rounded(Color.TRANSPARENT, dp(12)))
            setOnClickListener { setZoom(currentZoom + delta) }
        }
        val zoomIn = zoomButton("＋", 0.5f)
        val zoomOut = zoomButton("－", -0.5f)
        zoomLabel = TextView(this).apply {
            text = "1.0×"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }
        zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackground(
                CarAppTheme.rounded(
                    Color.argb(175, 12, 18, 26),
                    dp(20),
                    Color.argb(55, 255, 255, 255),
                ),
            )
            addView(zoomIn, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(zoomLabel, LinearLayout.LayoutParams(dp(48), dp(34)))
            addView(zoomOut, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val scanGuide = View(this).apply {
            isClickable = false
            isFocusable = false
            setBackground(
                CarAppTheme.rounded(
                    Color.TRANSPARENT,
                    dp(24),
                    Color.argb(190, 255, 255, 255),
                    dp(2),
                ),
            )
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(
                scanGuide,
                FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER).apply {
                    bottomMargin = dp(52)
                },
            )
            backButton = Button(this@QrScanActivity).apply {
                text = "返回"
                textSize = 15f
                isAllCaps = false
                stateListAnimator = null
                elevation = 0f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(12), 0, dp(12), 0)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackground(
                    CarAppTheme.rounded(
                        Color.argb(175, 12, 18, 26),
                        dp(14),
                        Color.argb(50, 255, 255, 255),
                    ),
                )
                setOnClickListener { finish() }
            }
            addView(
                backButton,
                FrameLayout.LayoutParams(dp(92), dp(44), Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(16)
                    topMargin = dp(48)
                },
            )
            addView(
                zoomControls,
                FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    rightMargin = dp(14)
                },
            )
            addView(
                status,
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    bottomMargin = dp(12)
                },
            )
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int
            val bottomInset: Int
            val leftInset: Int
            val rightInset: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                topInset = safe.top
                bottomInset = safe.bottom
                leftInset = safe.left
                rightInset = safe.right
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                leftInset = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                rightInset = insets.systemWindowInsetRight
            }
            backButton.layoutParams = (backButton.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = leftInset + dp(16)
                topMargin = topInset + dp(12)
            }
            zoomControls.layoutParams = (zoomControls.layoutParams as FrameLayout.LayoutParams).apply {
                rightMargin = rightInset + dp(14)
            }
            status.layoutParams = (status.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bottomInset + dp(12)
            }
            insets
        }
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            CarDialogs.builder(this)
                .setTitle("需要相机权限")
                .setMessage("相机只用于扫描仪表上的 MotoPlay 配对二维码。")
                .setNegativeButton("取消") { _, _ -> finish() }
                .setPositiveButton("继续") { _, _ ->
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                .show()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showCameraPermissionRecovery() {
        val permanent = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        CarDialogs.builder(this)
            .setTitle("无法扫描二维码")
            .setMessage("请授予相机权限，或返回应用使用“手动 Wi‑Fi”连接。")
            .setNegativeButton("返回") { _, _ -> finish() }
            .setPositiveButton(if (permanent) "打开应用设置" else "重新授权") { _, _ ->
                if (permanent) {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                    finish()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            .show()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    minZoom = state.minZoomRatio
                    maxZoom = state.maxZoomRatio
                    currentZoom = state.zoomRatio.coerceIn(minZoom, maxZoom)
                    zoomLabel.text = String.format(Locale.US, "%.1f×", currentZoom)
                    if (!zoomRangeLogged) {
                        zoomRangeLogged = true
                        MiniState.log(
                            "[QR] camera zoom range=${String.format(Locale.US, "%.1f", minZoom)}x" +
                                "..${String.format(Locale.US, "%.1f", maxZoom)}x",
                        )
                    }
                }
            } catch (error: Exception) {
                status.text = "相机启动失败：${error.message ?: error.javaClass.simpleName}"
            }
        }, mainExecutor)
    }

    private fun setZoom(ratio: Float) {
        val control = camera?.cameraControl ?: return
        val target = ratio.coerceIn(minZoom, maxZoom)
        currentZoom = target
        zoomLabel.text = String.format(Locale.US, "%.1f×", target)
        control.setZoomRatio(target)
    }

    private fun handlePreviewTouch(view: android.view.View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchDownAt = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val shortTap = System.currentTimeMillis() - touchDownAt < 500L
                if (shortTap && !scaleDetector.isInProgress && dx * dx + dy * dy < dp(24) * dp(24)) {
                    focusAt(event.x, event.y)
                    view.performClick()
                }
            }
        }
        return true
    }

    private fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        control.startFocusAndMetering(action)
        status.text = "正在对焦，请保持二维码清晰"
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(image: ImageProxy) {
        if (busy || saving) {
            image.close()
            return
        }
        val media = image.image ?: run {
            image.close()
            return
        }
        busy = true
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                var rejected: String? = null
                for (barcode in barcodes) {
                    val raw = barcode.rawValue ?: continue
                    val vehicle = MotoVehicle.parse(raw)
                    if (vehicle == null) {
                        rejected = raw
                        continue
                    }
                    saving = true
                    val appContext = applicationContext
                    executor.execute {
                        val saved = MotoVehiclePrefs.save(appContext, vehicle)
                        runOnUiThread {
                            if (saved) {
                                MiniState.update("已读取 ${vehicle.ssid}")
                                setResult(RESULT_OK)
                                finish()
                            } else {
                                saving = false
                                busy = false
                                status.text = "无法安全保存车辆信息，请检查系统 KeyStore 后重试。"
                            }
                        }
                    }
                    return@addOnSuccessListener
                }
                if (rejected != null) {
                    status.text = "这不是 MotoPlay 配对码。请打开仪表上的“手机互联 / MotoPlay”二维码页面。"
                }
            }
            .addOnFailureListener {
                status.text = "识别失败：${it.message ?: "请重新对准二维码"}"
            }
            .addOnCompleteListener {
                image.close()
                if (!saving) busy = false
            }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        scanner.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

}
