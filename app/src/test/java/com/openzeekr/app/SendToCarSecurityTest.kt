package com.openzeekr.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendToCarSecurityTest {

    @Test
    fun testValidGoogleMapsUrls() {
        assertTrue(SendToCarActivity.isTrustedMapsUrl("https://maps.app.goo.gl/abcdef123"))
        assertTrue(SendToCarActivity.isTrustedMapsUrl("https://goo.gl/maps/123456"))
        assertTrue(SendToCarActivity.isTrustedMapsUrl("https://maps.google.com/?q=59.9139,10.7522"))
        assertTrue(SendToCarActivity.isTrustedMapsUrl("https://maps.google.no/?q=Oslo"))
        assertTrue(SendToCarActivity.isTrustedMapsUrl("https://www.google.com/maps/place/Stockholm"))
        assertTrue(SendToCarActivity.isTrustedMapsUrl("https://google.de/maps/@52.5200,13.4050,15z"))
    }

    @Test
    fun testRejectMaliciousOrUntrustedUrls() {
        // Attacker domains pretending to be maps
        assertFalse(SendToCarActivity.isTrustedMapsUrl("https://evil.com/maps"))
        assertFalse(SendToCarActivity.isTrustedMapsUrl("https://evil.com/?goo.gl"))
        assertFalse(SendToCarActivity.isTrustedMapsUrl("https://attacker.google.com.evil.com/maps"))
        assertFalse(SendToCarActivity.isTrustedMapsUrl("https://notgoogle.com/maps"))

        // Intranet / SSRF targets
        assertFalse(SendToCarActivity.isTrustedMapsUrl("http://localhost:8080/maps"))
        assertFalse(SendToCarActivity.isTrustedMapsUrl("http://127.0.0.1/maps"))
        assertFalse(SendToCarActivity.isTrustedMapsUrl("http://192.168.1.1/maps"))
        assertFalse(SendToCarActivity.isTrustedMapsUrl("http://10.0.0.1/maps"))

        // Non-http schemes
        assertFalse(SendToCarActivity.isTrustedMapsUrl("file:///etc/passwd"))
        assertFalse(SendToCarActivity.isTrustedMapsUrl("javascript:alert(1)"))
        assertFalse(SendToCarActivity.isTrustedMapsUrl("data:text/plain,hello"))
    }
}
