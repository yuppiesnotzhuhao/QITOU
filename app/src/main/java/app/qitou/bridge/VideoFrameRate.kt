package app.qitou.bridge

internal object VideoFrameRate {
    const val MAX_FPS = 60
    const val FRAME_INTERVAL_MS = 16L

    fun intervalMs(fps: Int): Long = 1000L / fps.coerceIn(1, MAX_FPS)
}
