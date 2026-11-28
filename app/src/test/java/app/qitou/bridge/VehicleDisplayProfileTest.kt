package app.qitou.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleDisplayProfileTest {
    @Test
    fun defaultsToReportedOutputSize() {
        val profile = VehicleDisplayProfile()

        assertEquals(800 to 386, profile.resolveOutput(800, 386))
        assertFalse(profile.usesCustomOutput)
        assertTrue(profile.sourceFollowsReported)
    }

    @Test
    fun preservesManualCarLifeSourceOverride() {
        val profile = VehicleDisplayProfile(
            sourceWidth = 1280,
            sourceHeight = 720,
            sourceFollowsReported = false,
        )

        assertFalse(profile.copy(mode = DisplayScaleMode.STRETCH).sourceFollowsReported)
        assertEquals("1280×720", profile.sourceRequest.label)
    }

    @Test
    fun resolvesAndAlignsCustomOutputSize() {
        val profile = VehicleDisplayProfile(customWidth = 1001, customHeight = 515)

        assertEquals(1000 to 516, profile.resolveOutput(800, 386))
        assertTrue(profile.usesCustomOutput)
    }

    @Test
    fun ignoresIncompleteCustomOutput() {
        val profile = VehicleDisplayProfile(customWidth = 1024, customHeight = 0)

        assertEquals(1280 to 720, profile.resolveOutput(1280, 720))
    }

    @Test
    fun preservesCustomSourceRequestAndAlignsOddDimensions() {
        val valid = VehicleDisplayProfile(sourceWidth = 1024, sourceHeight = 480)
        val custom = VehicleDisplayProfile(sourceWidth = 800, sourceHeight = 386)
        val odd = VehicleDisplayProfile(sourceWidth = 1001, sourceHeight = 515)

        assertEquals("1024×480", valid.sourceRequest.label)
        assertEquals("800×386", custom.sourceRequest.label)
        assertEquals("1000×516", odd.sourceRequest.label)
    }

    @Test
    fun fallsBackForUnsupportedSourceRequestBounds() {
        val invalid = VehicleDisplayProfile(sourceWidth = 100, sourceHeight = 500)

        assertEquals("1280×480", invalid.sourceRequest.label)
    }

    @Test
    fun localCarLifeUsesLandscapePhoneScreenResolution() {
        assertEquals("3168×1440", LocalCarLifeDisplay.sourceForScreen(1440, 3168).label)
        assertEquals("2400×1080", LocalCarLifeDisplay.sourceForScreen(1080, 2400).label)
    }
}
