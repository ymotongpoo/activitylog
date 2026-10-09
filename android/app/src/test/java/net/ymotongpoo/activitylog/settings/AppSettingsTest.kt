package net.ymotongpoo.activitylog.settings

import net.ymotongpoo.activitylog.collect.UrlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    private fun settings(endpoint: String = "", instanceId: String = "", token: String = "") =
        AppSettings(endpoint, instanceId, token, "", "", "", UrlMode.PATH)

    @Test
    fun reportsMissingExportSettings() {
        assertEquals(listOf("endpoint", "instance ID", "token"), settings().missingExportSettings())
        assertEquals(listOf("token"), settings("https://example.com/otlp", "123").missingExportSettings())
        assertFalse(settings("https://example.com/otlp", "123").isExportConfigured)
        assertTrue(settings("https://example.com/otlp", "123", "glc_x").isExportConfigured)
    }
}
