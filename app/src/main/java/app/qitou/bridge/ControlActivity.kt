package app.qitou.bridge

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Immersive in-app controller. It is deliberately a normal Activity, not an overlay:
 * Car+ stays alive behind our local CarLife head-unit loopback and the Surface remains
 * in our own process when the phone display is turned off.
 */
class ControlActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var fps: TextView
    private var attached = false
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var previewSurface: Surface? = null
    private var lastX = 0
    private var lastY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
                    attach(width, height)
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    surfaceTexture.setDefaultBufferSize(width, height)
                    if (attached) {
                        MiniRuntime.pipeline?.updatePreviewSize(width, height)
                        attachedWidth = width
                        attachedHeight = height
                    } else {
                        attach(width, height)
                    }
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    detach()
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
            setOnTouchListener(::onTouch)
        }

        val exit = Button(this).apply {
            text = "退出全屏"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 18, 24, 32))
            setOnClickListener { finish() }
        }
        fps = TextView(this).apply {
            setTextColor(Color.rgb(126, 230, 181))
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(150, 10, 14, 20))
            setPadding(18, 8, 18, 8)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(
                exit,
                FrameLayout.LayoutParams(dp(96), dp(42), Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(14)
                    topMargin = dp(14)
                },
            )
            addView(
                fps,
                FrameLayout.LayoutParams(dp(180), dp(42), Gravity.TOP or Gravity.END).apply {
                    rightMargin = dp(14)
                    topMargin = dp(14)
                },
            )
        }
        setContentView(root)
        hideSystemUi()
        renderFps()
    }

    override fun onResume() {
        super.onResume()
        attach()
        hideSystemUi()
        MiniState.listener = { runOnUiThread(::renderFps) }
        renderFps()
    }

    override fun onPause() {
        detach()
        MiniState.listener = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun attach(
        width: Int = preview.width,
        height: Int = preview.height,
    ) {
        val pipe = MiniRuntime.pipeline ?: return
        val texture = preview.surfaceTexture ?: return
        if (width <= 0 || height <= 0) return
        if (!attached) {
            texture.setDefaultBufferSize(width, height)
            val surface = Surface(texture)
            previewSurface = surface
            pipe.attachPreview(surface, width, height)
            attached = true
            attachedWidth = width
            attachedHeight = height
        } else if (width != attachedWidth || height != attachedHeight) {
            pipe.updatePreviewSize(width, height)
            attachedWidth = width
            attachedHeight = height
        }
    }

    private fun detach() {
        if (!attached) return
        runCatching { MiniRuntime.pipeline?.clearPreview() }
        runCatching { previewSurface?.release() }
        previewSurface = null
        attached = false
        attachedWidth = 0
        attachedHeight = 0
    }

    private fun onTouch(view: android.view.View, event: MotionEvent): Boolean {
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                MiniRuntime.receiver?.sendTouch(1, lastX, lastY)
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

    private fun renderFps() {
        if (!::fps.isInitialized) return
        fps.text = if (MiniState.framesPerSecond > 0) {
            "${MiniState.framesPerSecond} fps"
        } else {
            "等待画面"
        }
        if (MiniState.framesPerSecond <= 0 && !MiniState.running) {
            finish()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
