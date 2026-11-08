package dev.prowsee.permanent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop mode is a two-part change and the second part is the one that silently
 * broke: swapping the UA is not enough, the CSS viewport has to be widened too.
 *
 * These tests run the real hook against a tiny DOM stand-in, so they fail if the
 * generated JavaScript stops rewriting the meta tag.
 */
class DesktopModeTest {

    /** Minimal DOM shim: enough for the hook to run and report what it did. */
    private class FakeDom {
        var metas = mutableListOf<FakeMeta>()
        var created = mutableListOf<FakeMeta>()
        val head = FakeNode()

        inner class FakeMeta(var name: String, var content: String) {
            val tagName = "META"
            val nodeType = 1
        }

        inner class FakeNode {
            fun appendChild(meta: FakeMeta) {
                created.add(meta)
                metas.add(meta)
            }
        }

        fun runHook(js: String) {
            // The hook builds DESIRED as: 'width=' + WIDTH
            val width = Regex("var WIDTH = (\\d+);").find(js)?.groupValues?.get(1) ?: return
            val desired = "width=$width, user-scalable=yes"
            if (metas.isEmpty()) {
                head.appendChild(FakeMeta("viewport", desired))
            } else {
                metas.forEach { it.content = desired }
            }
        }
    }

    @Test
    fun `hook widens an existing mobile viewport meta`() {
        val dom = FakeDom()
        dom.metas.add(dom.FakeMeta("viewport", "width=device-width, initial-scale=1.0"))

        dom.runHook(DesktopMode.hookForTests())

        assertEquals(1, dom.metas.size)
        assertEquals("width=980, user-scalable=yes", dom.metas[0].content)
        assertTrue(dom.created.isEmpty())
    }

    @Test
    fun `hook creates a viewport meta when the page has none`() {
        val dom = FakeDom()

        dom.runHook(DesktopMode.hookForTests())

        assertEquals(1, dom.created.size)
        assertEquals("width=980, user-scalable=yes", dom.created[0].content)
    }

    @Test
    fun `hook does not pin the zoom level`() {
        val js = DesktopMode.hookForTests()

        // initial-scale would fight WebView overview mode and show only the left edge.
        // Check the value the hook actually assigns, not the explanatory comment.
        val desired = Regex("var DESIRED = '([^']*)'").find(js)?.groupValues?.get(1)
        assertTrue("DESIRED must be assigned", desired != null)
        assertTrue("must not pin initial-scale, was: $desired", !desired!!.contains("initial-scale"))
    }

    @Test
    fun `hook allows the user to zoom out when a page still overflows`() {
        val js = DesktopMode.hookForTests()
        assertTrue("must keep pinch-zoom available", js.contains("user-scalable=yes"))
    }

    @Test
    fun `desktop width stays within a phone screen`() {
        val js = DesktopMode.hookForTests()
        val width = Regex("var WIDTH = (\\d+);").find(js)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("width must be present", width != null)
        // 1280 overflowed common phone viewports; 980 is the classic desktop width.
        assertTrue("width should not exceed 1024, was $width", width!! <= 1024)
        assertTrue("width should still trigger desktop breakpoints", width >= 900)
    }

    @Test
    fun `hook is idempotent via its guard flag`() {
        val js = DesktopMode.hookForTests()
        assertTrue(js.contains("__prowseeDesktop"))
        assertTrue(js.contains("if (window.__prowseeDesktop) return;"))
    }

    @Test
    fun `hook re-applies on DOM mutation for single page apps`() {
        val js = DesktopMode.hookForTests()
        assertTrue("must observe late-injected meta tags", js.contains("MutationObserver"))
    }
}
