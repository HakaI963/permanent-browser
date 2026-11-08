package dev.prowsee.permanent.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyProfileTest {

    @Test
    fun `parses bare host and port`() {
        val p = ProxyProfile.parse("proxy.example.com:8080")
        assertNotNull(p)
        val parsed = p!!
        assertEquals("proxy.example.com", parsed.host)
        assertEquals(8080, parsed.port)
        assertEquals(ProxyScheme.HTTP, parsed.scheme)
        assertNull(parsed.username)
    }

    @Test
    fun `parses scheme and credentials`() {
        val p = ProxyProfile.parse("socks5://alice:s3cret@10.0.0.5:1080")
        assertNotNull(p)
        val parsed = p!!
        assertEquals(ProxyScheme.SOCKS5, parsed.scheme)
        assertEquals("10.0.0.5", parsed.host)
        assertEquals(1080, parsed.port)
        assertEquals("alice", parsed.username)
        assertEquals("s3cret", parsed.password)
    }

    @Test
    fun `defaults the port from the scheme`() {
        assertEquals(443, ProxyProfile.parse("https://proxy.example.com")!!.port)
        assertEquals(1080, ProxyProfile.parse("socks5://proxy.example.com")!!.port)
        assertEquals(8080, ProxyProfile.parse("proxy.example.com")!!.port)
    }

    @Test
    fun `rejects junk instead of guessing`() {
        assertNull(ProxyProfile.parse(""))
        assertNull(ProxyProfile.parse("   "))
        assertNull(ProxyProfile.parse("not a proxy at all !!"))
    }

    @Test
    fun `builds a proxy url webview accepts`() {
        val p = ProxyProfile.parse("socks5://alice:pw@10.0.0.5:1080")!!
        val url = p.toProxyUrl()
        assertTrue(url.startsWith("socks5://"))
        assertTrue(url.contains("alice:pw@"))
        assertTrue(url.endsWith("10.0.0.5:1080"))
    }

    @Test
    fun `encodes credentials that would break the uri`() {
        val p = ProxyProfile(
            id = "x", label = "x", scheme = ProxyScheme.HTTP,
            host = "h", port = 1, username = "a@b", password = "p:w",
        )
        val url = p.toProxyUrl()
        assertFalse("raw @ must not leak into userinfo", url.contains("a@b"))
        assertTrue(url.contains("%40"))
    }

    @Test
    fun `host port form is what iptables needs`() {
        val p = ProxyProfile.parse("10.0.0.5:1080")!!
        assertEquals("10.0.0.5:1080", p.toHostPort())
    }
}

class SubscriptionParserTest {

    @Test
    fun `parses a plain list of servers`() {
        val result = SubscriptionParser.parse(
            """
            # my servers
            1.2.3.4:8080
            socks5://user:pass@5.6.7.8:1080
            """.trimIndent(),
        )
        assertEquals(2, result.profiles.size)
        assertEquals(0, result.skipped.size)
    }

    @Test
    fun `parses a base64 subscription blob`() {
        val plain = "1.2.3.4:8080\n5.6.7.8:3128"
        val encoded = java.util.Base64.getEncoder().encodeToString(plain.toByteArray())
        val result = SubscriptionParser.parse(encoded)
        assertEquals(2, result.profiles.size)
    }

    @Test
    fun `deduplicates identical entries`() {
        val result = SubscriptionParser.parse("1.2.3.4:8080\n1.2.3.4:8080")
        assertEquals(1, result.profiles.size)
    }

    @Test
    fun `skips protocols that cannot be a webview proxy`() {
        val result = SubscriptionParser.parse(
            """
            1.2.3.4:8080
            vmess://eyJhZGQiOiJ4In0=
            vless://uuid@host:443
            trojan://pw@host:443
            """.trimIndent(),
        )
        assertEquals(1, result.profiles.size)
        assertEquals(3, result.skipped.size)
    }

    @Test
    fun `parses a shadowsocks uri into a socks profile`() {
        // ss://base64(method:password)@host:port
        val creds = java.util.Base64.getEncoder().encodeToString("aes-256-gcm:pw".toByteArray())
        val result = SubscriptionParser.parse("ss://$creds@9.9.9.9:8388")
        assertEquals(1, result.profiles.size)
        assertEquals("9.9.9.9", result.profiles[0].host)
        assertEquals(8388, result.profiles[0].port)
        assertEquals(ProxyScheme.SOCKS5, result.profiles[0].scheme)
    }

    @Test
    fun `garbage lines are reported not silently dropped`() {
        val result = SubscriptionParser.parse("1.2.3.4:8080\nthis is not a proxy")
        assertEquals(1, result.profiles.size)
        assertEquals(1, result.skipped.size)
    }
}
