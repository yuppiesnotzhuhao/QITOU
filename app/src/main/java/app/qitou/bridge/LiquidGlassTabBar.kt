package app.qitou.bridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A floating, translucent navigation capsule with a soft iOS-style glass treatment. */
class LiquidGlassTabBar(context: Context) : FrameLayout(context) {
    data class TabItem(val label: String, val iconRes: Int)

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val shape = Path()
    private val rect = RectF()
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    private val selectionView = IceBlockView(context)
    private val items = mutableListOf<View>()
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()
    private var selectedIndex = -1
    private var listener: ((Int) -> Unit)? = null

    init {
        setWillNotDraw(false)
        elevation = dp(20).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        addView(
            selectionView,
            LayoutParams(dp(80), dp(54)).apply {
                leftMargin = 0
                topMargin = dp(6)
            },
        )
        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun setItems(newItems: List<TabItem>) {
        row.removeAllViews()
        items.clear()
        iconViews.clear()
        labelViews.clear()
        newItems.forEachIndexed { index, item ->
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = item.label
                setPadding(0, dp(3), 0, dp(3))
                setOnClickListener {
                    listener?.invoke(index)
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                text = item.label
                gravity = Gravity.CENTER
                textSize = 10f
                setPadding(0, dp(2), 0, 0)
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL,
                )
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            content.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            content.addView(label, LinearLayout.LayoutParams(-1, -2))
            row.addView(
                content,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            items += content
            iconViews += icon
            labelViews += label
        }
        select(0)
    }

    fun setOnTabSelected(listener: (Int) -> Unit) {
        this.listener = listener
    }

    fun select(index: Int, notify: Boolean = false) {
        if (index !in items.indices) return
        val previousIndex = selectedIndex
        selectedIndex = index
        val dark = CarAppTheme.isNight(context)
        items.forEachIndexed { itemIndex, item ->
            val selected = itemIndex == index
            item.background = null
            val iconColor = when {
                selected -> CarAppTheme.color(context, R.color.accent_primary)
                dark -> Color.argb(185, 220, 230, 240)
                else -> Color.argb(150, 65, 71, 79)
            }
            iconViews[itemIndex].setColorFilter(iconColor)
            labelViews[itemIndex].setTextColor(iconColor)
            item.isSelected = selected
        }
        positionSelection(
            index = index,
            animate = previousIndex >= 0 && previousIndex != index,
        )
        invalidate()
        if (notify) listener?.invoke(index)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionSelection(selectedIndex, animate = false)
    }

    private fun positionSelection(index: Int, animate: Boolean) {
        if (index < 0 || items.isEmpty() || width == 0) return
        val item = items[index]
        if (item.width == 0 || item.height == 0) {
            selectionView.post {
                if (selectedIndex == index) positionSelection(index, animate)
            }
            return
        }
        selectionView.visibility = View.VISIBLE
        val selectedWidth = (item.width - dp(4)).coerceAtLeast(dp(54))
        val params = selectionView.layoutParams as LayoutParams
        if (params.width != selectedWidth || params.height != item.height) {
            params.width = selectedWidth
            params.height = item.height.coerceAtLeast(dp(48))
            params.leftMargin = 0
            params.topMargin = item.top
            selectionView.layoutParams = params
        }
        val target = item.left + (item.width - selectedWidth) / 2f
        selectionView.animate().cancel()
        selectionView.translationX = target
        if (animate) {
            selectionView.alpha = 0.12f
            selectionView.scaleX = 0.78f
            selectionView.scaleY = 0.62f
            selectionView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator(1.7f))
                .start()
            val icon = iconViews[index]
            icon.animate().cancel()
            icon.scaleX = 0.84f
            icon.scaleY = 0.84f
            icon.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(140L)
                .withEndAction {
                    icon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120L)
                        .start()
                }
                .start()
        } else {
            selectionView.alpha = 1f
            selectionView.scaleX = 1f
            selectionView.scaleY = 1f
        }
        iconViews.forEachIndexed { iconIndex, icon ->
            if (iconIndex != index) {
                icon.animate().cancel()
                icon.scaleX = 1f
                icon.scaleY = 1f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height / 2f
        shape.reset()
        shape.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val dark = CarAppTheme.isNight(context)
        backdropPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            if (dark) {
                intArrayOf(
                    Color.argb(254, 37, 43, 51),
                    Color.argb(248, 21, 26, 33),
                    Color.argb(252, 29, 35, 43),
                )
            } else {
                intArrayOf(
                    Color.argb(252, 255, 255, 255),
                    Color.argb(244, 246, 249, 252),
                    Color.argb(250, 255, 255, 255),
                )
            },
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(shape, backdropPaint)

        val save = canvas.save()
        canvas.clipPath(shape)
        backdropPaint.shader = RadialGradient(
            width * 0.22f,
            0f,
            width * 0.75f,
            if (dark) Color.argb(50, 135, 205, 255) else Color.argb(205, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, backdropPaint)
        backdropPaint.shader = RadialGradient(
            width * 0.82f,
            height.toFloat(),
            width * 0.52f,
            if (dark) Color.argb(32, 112, 153, 196) else Color.argb(64, 157, 197, 232),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, backdropPaint)
        canvas.restoreToCount(save)
        backdropPaint.shader = null

        borderPaint.color = if (dark) {
            Color.argb(70, 230, 242, 255)
        } else {
            Color.argb(205, 255, 255, 255)
        }
        rect.inset(0.5f, 0.5f)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private inner class IceBlockView(context: Context) : View(context) {
        private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 0.8f
        }
        private val block = RectF()

        init {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            block.set(0f, 0f, width.toFloat(), height.toFloat())
            val radius = height / 2f
            val dark = CarAppTheme.isNight(context)
            blockPaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                if (dark) {
                    intArrayOf(
                        Color.argb(216, 78, 91, 105),
                        Color.argb(164, 47, 58, 70),
                    )
                } else {
                    intArrayOf(
                        Color.argb(242, 255, 255, 255),
                        Color.argb(174, 240, 247, 255),
                    )
                },
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(block, radius, radius, blockPaint)

            val save = canvas.save()
            canvas.clipPath(Path().apply {
                addRoundRect(block, radius, radius, Path.Direction.CW)
            })
            highlightPaint.shader = RadialGradient(
                width * 0.5f,
                height * 0.16f,
                width * 0.72f,
                if (dark) Color.argb(58, 164, 220, 255)
                else Color.argb(220, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(block, highlightPaint)
            canvas.restoreToCount(save)
            highlightPaint.shader = null
            blockPaint.shader = null

            edgePaint.color = if (dark) {
                Color.argb(76, 230, 242, 255)
            } else {
                Color.argb(220, 255, 255, 255)
            }
            block.inset(0.5f, 0.5f)
            canvas.drawRoundRect(block, radius, radius, edgePaint)
        }
    }
}
