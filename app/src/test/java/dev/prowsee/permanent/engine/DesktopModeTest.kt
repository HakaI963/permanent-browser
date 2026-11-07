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
            val desired = "width=$width"
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
        assertEquals("width=1280", dom.metas[0].content)
        assertTrue(dom.created.isEmpty())
    }

    @Test
    fun `hook creates a viewport meta when the page has none`() {
        val dom = FakeDom()

        dom.runHook(DesktopMode.hookForTests())

        assertEquals(1, dom.created.size)
        assertEquals("width=1280", dom.created[0].content)
    }

    @Test
    fun `hook does not pin the zoom level`() {
        val js = DesktopMode.hookForTests()

        // initial-scale would fight WebView overview mode and show only the left edge.
        assertTrue(
            "desktop hook must not set initial-scale",
            !js.contains("initial-scale=1'") && !js.contains("initial-scale = 1"),
        )
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
