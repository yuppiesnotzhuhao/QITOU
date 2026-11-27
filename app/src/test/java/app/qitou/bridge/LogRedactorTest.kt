package app.qitou.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun redactsPasswordFields() {
        val redacted = LogRedactor.redact("ssid=CFMOTO&pwd=secret&psk:another")

        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("another"))
        assertTrue(redacted.contains("pwd=***"))
    }

    @Test
    fun redactsLegacyWifiPairingPassword() {
        val redacted = LogRedactor.redact("Wifi=CFMOTO#12345678#dc0d30da1b6c")

        assertFalse(redacted.contains("12345678"))
        assertTrue(redacted.contains("Wifi=***#***#dc0d30da1b6c"))
    }
}
