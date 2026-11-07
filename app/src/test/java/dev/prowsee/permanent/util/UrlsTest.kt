package dev.prowsee.permanent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {

    @Test
    fun `empty input goes home`() {
        assertEquals(Urls.HOME_URL, Urls.toLoadable(""))
        assertEquals(Urls.HOME_URL, Urls.toLoadable("   "))
    }

    @Test
    fun `explicit scheme is preserved`() {
        assertEquals("https://example.com/a", Urls.toLoadable("https://example.com/a"))
        assertEquals("http://example.com", Urls.toLoadable("http://example.com"))
        assertEquals("about:blank", Urls.toLoadable("about:blank"))
    }

    @Test
    fun `bare domain gets https`() {
        assertEquals("https://example.com", Urls.toLoadable("example.com"))
        assertEquals("https://www.example.co.uk/path", Urls.toLoadable("www.example.co.uk/path"))
    }

    @Test
    fun `ip address gets http`() {
        assertEquals("http://192.168.1.1", Urls.toLoadable("192.168.1.1"))
        assertEquals("http://192.168.1.1:8080/admin", Urls.toLoadable("192.168.1.1:8080/admin"))
    }

    @Test
    fun `localhost gets http`() {
        assertEquals("http://localhost:3000", Urls.toLoadable("localhost:3000"))
    }

    @Test
    fun `plain words become a search`() {
        val result = Urls.toLoadable("how to keep a page alive")
        assertTrue(result.startsWith("https://duckduckgo.com/?q="))
        assertTrue(result.contains("keep"))
    }

    @Test
    fun `selected search engine is honoured`() {
        val google = Urls.toLoadable("android browser", SearchEngine.GOOGLE.template)
        assertTrue(google.startsWith("https://www.google.com/search?q="))
        assertTrue(google.contains("android"))

        val bing = Urls.toLoadable("android browser", SearchEngine.BING.template)
        assertTrue(bing.startsWith("https://www.bing.com/search?q="))

        val brave = Urls.toLoadable("android browser", SearchEngine.BRAVE.template)
        assertTrue(brave.startsWith("https://search.brave.com/search?q="))
    }

    @Test
    fun `changing the engine does not turn a url into a search`() {
        assertEquals(
            "https://example.com/x",
            Urls.toLoadable("example.com/x", SearchEngine.GOOGLE.template),
        )
    }

    @Test
    fun `template without placeholder still works`() {
        assertEquals("https://s.example/?x=hello", Urls.search("https://s.example/?x=", "hello"))
    }

    @Test
    fun `host extraction`() {
        assertEquals("example.com", Urls.host("https://example.com/a/b?c=d"))
        assertEquals("example.com", Urls.host("http://user:pw@example.com:8080/x"))
        assertEquals("192.168.1.1", Urls.host("http://192.168.1.1:8080"))
    }

    @Test
    fun `pretty strips scheme and trailing slash`() {
        assertEquals("example.com/a", Urls.pretty("https://example.com/a"))
        assertEquals("example.com", Urls.pretty("https://example.com/"))
        assertEquals("data: page", Urls.pretty("data:text/html,hi"))
    }

    @Test
    fun `isHttp only matches web schemes`() {
        assertTrue(Urls.isHttp("https://a.com"))
        assertTrue(Urls.isHttp("http://a.com"))
        assertFalse(Urls.isHttp("ftp://a.com"))
        assertFalse(Urls.isHttp("about:blank"))
    }

    @Test
    fun `encode escapes spaces and keeps safe chars`() {
        assertEquals("a+b", Urls.encode("a b"))
        assertEquals("a-b_c.d~e", Urls.encode("a-b_c.d~e"))
        assertEquals("%26", Urls.encode("&"))
    }
}

/** Mirrors the enum in SettingsRepository without pulling Android/DataStore into a JVM test. */
private enum class SearchEngine(val template: String) {
    DUCKDUCKGO("https://duckduckgo.com/?q=%s"),
    GOOGLE("https://www.google.com/search?q=%s"),
    BING("https://www.bing.com/search?q=%s"),
    BRAVE("https://search.brave.com/search?q=%s"),
}
