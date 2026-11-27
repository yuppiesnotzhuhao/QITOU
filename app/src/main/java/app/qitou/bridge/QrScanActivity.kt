package app.qitou.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class QrScanActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }
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
        previewView = PreviewView(this)
        status = TextView(this).apply {
            text = "对准仪表上的 MotoPlay 配对二维码"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(24, 28, 24, 28)
        }
        lateinit var backButton: Button
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            backButton = Button(this@QrScanActivity).apply {
                text = "返回"
                isAllCaps = false
                stateListAnimator = null
                elevation = 0f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(175, 12, 18, 26))
                setOnClickListener { finish() }
            }
            addView(backButton, FrameLayout.LayoutParams(180, 105, Gravity.TOP or Gravity.START).apply {
                topMargin = (48 * resources.displayMetrics.density).toInt()
            })
            addView(status, FrameLayout.LayoutParams(-1, 160, Gravity.BOTTOM))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                topInset = safe.top
                bottomInset = safe.bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }
            backButton.layoutParams = (backButton.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = topInset + (12 * resources.displayMetrics.density).toInt()
            }
            status.layoutParams = (status.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bottomInset
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
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (error: Exception) {
                status.text = "相机启动失败：${error.message ?: error.javaClass.simpleName}"
            }
        }, mainExecutor)
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

}
