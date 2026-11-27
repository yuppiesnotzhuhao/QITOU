package app.qitou.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenViewportTest {
    @Test
    fun fillUsesTheWhole450MtCanvas() {
        val rect = computeScreenViewport(
            canvasWidth = 800,
            canvasHeight = 386,
            sourceWidth = 1280,
            sourceHeight = 480,
            fit = ScreenFit.FILL,
        )

        assertEquals(-114, rect.x)
        assertEquals(0, rect.y)
        assertEquals(1029, rect.width)
        assertEquals(386, rect.height)
    }

    @Test
    fun fitLetterboxesInsteadOfCoveringTheCanvas() {
        val rect = computeScreenViewport(
            canvasWidth = 800,
            canvasHeight = 386,
            sourceWidth = 1280,
            sourceHeight = 480,
            fit = ScreenFit.FIT,
        )

        assertEquals(0, rect.x)
        assertEquals(43, rect.y)
        assertEquals(800, rect.width)
        assertEquals(300, rect.height)
    }

    @Test
    fun fitAdaptsToAnotherBikeCanvasWithoutCropping() {
        val rect = computeScreenViewport(
            canvasWidth = 1024,
            canvasHeight = 600,
            sourceWidth = 1280,
            sourceHeight = 720,
            fit = ScreenFit.FIT,
        )

        assertEquals(0, rect.x)
        assertEquals(12, rect.y)
        assertEquals(1024, rect.width)
        assertEquals(576, rect.height)
    }

    @Test
    fun fitRespectsBottomInstrumentSafeArea() {
        val rect = computeScreenViewport(
            canvasWidth = 800,
            canvasHeight = 480,
            sourceWidth = 1280,
            sourceHeight = 480,
            fit = ScreenFit.FIT,
            insets = CanvasInsets(bottom = 94),
        )

        assertEquals(0, rect.x)
        assertEquals(43, rect.y)
        assertEquals(800, rect.width)
        assertEquals(300, rect.height)
    }

    @Test
    fun stretchExactlyMatchesTheCanvas() {
        val rect = computeScreenViewport(
            canvasWidth = 800,
            canvasHeight = 386,
            sourceWidth = 1280,
            sourceHeight = 480,
            fit = ScreenFit.STRETCH,
        )

        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
        assertEquals(800, rect.width)
        assertEquals(386, rect.height)
    }
}
