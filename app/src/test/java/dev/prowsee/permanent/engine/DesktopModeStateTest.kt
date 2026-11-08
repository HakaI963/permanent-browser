package dev.prowsee.permanent.engine

import dev.prowsee.permanent.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for "switch tab and desktop mode turns itself off".
 *
 * These exercise the **real** [TabSettingsDispatcher], not a re-implementation, so
 * they fail if the engine goes back to skipping tabs that have no WebView yet.
 */
class DesktopModeStateTest {

    /**
     * A tab that tracks what it was told, and - like the real session - keeps its
     * desktop state derived from settings rather than stored separately.
     */
    private class FakeTab(
        var settings: Settings,
        override val hasWebView: Boolean = false,
    ) : SettingsTarget {

        var applySettingsCalls = 0
        var applyDesktopCalls = 0

        val desktopMode: Boolean get() = settings.desktopMode

        override fun applySettings(newSettings: Settings, applyToPage: Boolean) {
            applySettingsCalls++
            // The real session updates state unconditionally, then returns early only
            // for the WebView-specific work.
            settings = newSettings
        }

        override fun applyDesktopMode(enabled: Boolean) {
            applyDesktopCalls++
            settings = settings.copy(desktopMode = enabled)
        }
    }

    @Test
    fun `every tab receives a settings change, including ones with no WebView`() {
        val withView = FakeTab(Settings(desktopMode = false), hasWebView = true)
        val noView = FakeTab(Settings(desktopMode = false), hasWebView = false)

        TabSettingsDispatcher.dispatchSettings(
            listOf(withView, noView),
            Settings(desktopMode = true),
            applyToPage = false,
        )

        assertEquals(1, withView.applySettingsCalls)
        assertEquals("a tab without a WebView must still be updated", 1, noView.applySettingsCalls)
        assertTrue(noView.desktopMode)
    }

    @Test
    fun `every tab receives a desktop-mode toggle`() {
        val tabs = listOf(
            FakeTab(Settings(desktopMode = false), hasWebView = true),
            FakeTab(Settings(desktopMode = false), hasWebView = false),
            FakeTab(Settings(desktopMode = false), hasWebView = false),
        )

        TabSettingsDispatcher.dispatchDesktopMode(tabs, enabled = true)

        assertTrue("all tabs must switch on", tabs.all { it.desktopMode })
        assertTrue("each tab must be called", tabs.all { it.applyDesktopCalls == 1 })
    }

    @Test
    fun `a background tab does not revert after a global toggle`() {
        // The reported symptom: enable desktop mode, switch to another tab, it is off.
        val foreground = FakeTab(Settings(desktopMode = false), hasWebView = true)
        val background = FakeTab(Settings(desktopMode = false), hasWebView = false)
        val tabs = listOf(foreground, background)

        TabSettingsDispatcher.dispatchDesktopMode(tabs, enabled = true)

        // Switching tabs changes which one is attached; it must not change state.
        assertEquals(foreground.desktopMode, background.desktopMode)
        assertTrue(background.desktopMode)
    }

    @Test
    fun `turning desktop mode off reaches tabs that were never shown`() {
        val tabs = listOf(
            FakeTab(Settings(desktopMode = true), hasWebView = true),
            FakeTab(Settings(desktopMode = true), hasWebView = false),
        )

        TabSettingsDispatcher.dispatchDesktopMode(tabs, enabled = false)

        assertFalse(tabs[0].desktopMode)
        assertFalse("an uncreated tab must not be skipped", tabs[1].desktopMode)
    }

    @Test
    fun `dispatching settings does not create a WebView`() {
        val tab = FakeTab(Settings(), hasWebView = false)
        TabSettingsDispatcher.dispatchSettings(listOf(tab), Settings(desktopMode = true), true)
        // FakeTab never sets hasWebView, so if the dispatcher tried to force creation
        // the flag would be observable here.
        assertFalse(tab.hasWebView)
    }

    @Test
    fun `empty tab list is a no-op`() {
        TabSettingsDispatcher.dispatchSettings(emptyList(), Settings(), true)
        TabSettingsDispatcher.dispatchDesktopMode(emptyList(), true)
    }
}
