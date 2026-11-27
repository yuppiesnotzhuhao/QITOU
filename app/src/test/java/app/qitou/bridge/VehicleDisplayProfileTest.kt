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
    fun normalizesSourceRequestToSupportedCarLifePresets() {
        val valid = VehicleDisplayProfile(sourceWidth = 1024, sourceHeight = 480)
        val invalid = VehicleDisplayProfile(sourceWidth = 1000, sourceHeight = 500)

        assertEquals("1024×480", valid.sourceRequest.label)
        assertEquals("1280×480", invalid.sourceRequest.label)
    }
}
