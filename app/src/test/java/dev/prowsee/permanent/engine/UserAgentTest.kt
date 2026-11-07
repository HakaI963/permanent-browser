package dev.prowsee.permanent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the user-agent rules that Google's sign-in depends on.
 *
 * These are the exact tokens that trigger "this browser or app may not be secure":
 * `; wv` marks an embedded WebView and `Version/4.0` marks the WebView product.
 * A UA containing either is rejected, so the test asserts they are absent.
 */
class UserAgentTest {

    @Before
    fun reset() {
        UserAgents.resetForTests()
    }

    @Test
    fun `mobile user agent is not a WebView user agent`() {
        val ua = UserAgents.mobile()

        assertFalse("must not advertise WebView", ua.contains("; wv"))
        assertFalse("must not advertise the WebView product", ua.contains("Version/4.0"))
        assertTrue("must look like Chrome", ua.contains("Chrome/"))
        assertTrue("must be a mobile UA", ua.contains("Mobile Safari/537.36"))
        assertTrue("must keep the AppleWebKit token", ua.contains("AppleWebKit/537.36"))
    }

    @Test
    fun `desktop user agent is a plain desktop Chrome UA`() {
        val ua = UserAgents.desktop()

        assertTrue(ua.startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
        assertTrue(ua.contains("Chrome/"))
        assertFalse(ua.contains("Mobile"))
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Version/4.0"))
    }

    @Test
    fun `chrome version is captured from the engine and reused`() {
        UserAgents.capture(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36",
        )
        assertEquals("131.0.6778.135", UserAgents.version())
        assertTrue(UserAgents.mobile().contains("Chrome/131.0.6778.135"))
        assertTrue(UserAgents.desktop().contains("Chrome/131.0.6778.135"))
    }

    @Test
    fun `a later capture does not overwrite the first`() {
        UserAgents.capture("Chrome/131.0.6778.135 Mobile Safari/537.36")
        UserAgents.capture("Chrome/140.0.1.2 Mobile Safari/537.36")
        // capture() is first-wins so the engine version stays consistent per process.
        assertEquals("131.0.6778.135", UserAgents.version())
    }

    @Test
    fun `desktop and mobile are distinguishable`() {
        assertFalse(UserAgents.desktop() == UserAgents.mobile())
        assertTrue(UserAgents.isOurs(UserAgents.desktop()))
        assertTrue(UserAgents.isOurs(UserAgents.mobile()))
        assertFalse(UserAgents.isOurs("Mozilla/5.0 (Linux; Android 14; wv) Version/4.0"))
    }
}
