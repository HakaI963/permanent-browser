package dev.prowsee.permanent.engine

import dev.prowsee.permanent.util.HostMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMatcherTest {

    private val blocklist = setOf(
        "doubleclick.net",
        "google-analytics.com",
        "ads.example.com",
        "tracker.io",
    )

    @Test
    fun `exact host matches`() {
        assertTrue(HostMatcher.matches("doubleclick.net", blocklist))
        assertTrue(HostMatcher.matches("tracker.io", blocklist))
    }

    @Test
    fun `subdomain matches its parent entry`() {
        assertTrue(HostMatcher.matches("static.doubleclick.net", blocklist))
        assertTrue(HostMatcher.matches("a.b.c.google-analytics.com", blocklist))
        assertTrue(HostMatcher.matches("x.ads.example.com", blocklist))
    }

    @Test
    fun `unrelated host does not match`() {
        assertFalse(HostMatcher.matches("example.com", blocklist))
        assertFalse(HostMatcher.matches("notdoubleclick.net", blocklist))
        assertFalse(HostMatcher.matches("doubleclick.net.example.com", blocklist))
    }

    @Test
    fun `apex suffix alone does not match a longer entry`() {
        assertFalse(HostMatcher.matches("example.com", blocklist))
        assertFalse(HostMatcher.matches("www.example.com", blocklist))
    }

    @Test
    fun `case is ignored`() {
        assertTrue(HostMatcher.matches("Static.DoubleClick.NET", blocklist))
    }

    @Test
    fun `host extraction handles ports userinfo and ipv6`() {
        assertEquals("example.com", HostMatcher.hostOf("https://example.com/a?b=c"))
        assertEquals("example.com", HostMatcher.hostOf("http://user:pw@example.com:8080/x"))
        assertEquals("192.168.1.1", HostMatcher.hostOf("http://192.168.1.1:8080"))
        assertEquals("::1", HostMatcher.hostOf("http://[::1]:8080/x"))
        assertNull(HostMatcher.hostOf("data:text/html,hi"))
        assertNull(HostMatcher.hostOf("about:blank"))
    }
}
