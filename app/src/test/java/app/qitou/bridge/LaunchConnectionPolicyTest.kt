package app.qitou.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchConnectionPolicyTest {
    @Test
    fun requiresExplicitPreference() {
        assertFalse(
            LaunchConnectionPolicy.shouldAutoConnect(
                enabled = false,
                hasSavedVehicle = true,
                serviceActive = false,
            ),
        )
    }

    @Test
    fun requiresSavedVehicle() {
        assertFalse(
            LaunchConnectionPolicy.shouldAutoConnect(
                enabled = true,
                hasSavedVehicle = false,
                serviceActive = false,
            ),
        )
    }

    @Test
    fun doesNotDuplicateRunningService() {
        assertFalse(
            LaunchConnectionPolicy.shouldAutoConnect(
                enabled = true,
                hasSavedVehicle = true,
                serviceActive = true,
            ),
        )
    }

    @Test
    fun connectsWhenAllConditionsAreMet() {
        assertTrue(
            LaunchConnectionPolicy.shouldAutoConnect(
                enabled = true,
                hasSavedVehicle = true,
                serviceActive = false,
            ),
        )
    }
}
