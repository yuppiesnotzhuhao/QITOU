package app.qitou.bridge

internal data class ViewportRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Pure viewport geometry for the compositor.
 *
 * OpenGL uses a bottom-left origin. Keeping this calculation outside the EGL renderer makes the
 * Car+ -> instrument scaling rules testable without an emulator or a connected display.
 */
internal fun computeScreenViewport(
    canvasWidth: Int,
    canvasHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    fit: ScreenFit,
    insets: CanvasInsets = CanvasInsets(),
): ViewportRect {
    require(canvasWidth > 0 && canvasHeight > 0) { "Invalid output canvas" }
    require(sourceWidth > 0 && sourceHeight > 0) { "Invalid source size" }

    val safeLeft = insets.left.coerceIn(0, canvasWidth - 1)
    val safeRight = insets.right.coerceIn(0, canvasWidth - safeLeft - 1)
    val safeTop = insets.top.coerceIn(0, canvasHeight - 1)
    val safeBottom = insets.bottom.coerceIn(0, canvasHeight - safeTop - 1)
    val safeWidth = canvasWidth - safeLeft - safeRight
    val safeHeight = canvasHeight - safeTop - safeBottom
    val sourceAspect = sourceWidth.toFloat() / sourceHeight
    val canvasAspect = safeWidth.toFloat() / safeHeight
    val width: Int
    val height: Int

    when (fit) {
        ScreenFit.STRETCH -> {
            width = safeWidth
            height = safeHeight
        }

        ScreenFit.FIT -> {
            if (sourceAspect < canvasAspect) {
                height = safeHeight
                width = Math.round(safeHeight * sourceAspect)
            } else {
                width = safeWidth
                height = Math.round(safeWidth / sourceAspect)
            }
        }

        ScreenFit.FILL -> {
            if (sourceAspect < canvasAspect) {
                width = safeWidth
                height = Math.round(safeWidth / sourceAspect)
            } else {
                height = safeHeight
                width = Math.round(safeHeight * sourceAspect)
            }
        }
    }

    return ViewportRect(
        x = safeLeft + (safeWidth - width) / 2,
        y = safeTop + (safeHeight - height) / 2,
        width = width,
        height = height,
    )
}
