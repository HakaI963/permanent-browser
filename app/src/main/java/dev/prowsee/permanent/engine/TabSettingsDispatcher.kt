package dev.prowsee.permanent.engine

import dev.prowsee.permanent.data.Settings

/**
 * What a tab has to do when settings change.
 *
 * Extracted so [TabSettingsDispatcher] can be tested against fakes without a WebView,
 * while the engine uses the exact same code path.
 */
interface SettingsTarget {
    /** True once this tab has a live WebView. */
    val hasWebView: Boolean

    /** Apply new settings. Must update state even when [hasWebView] is false. */
    fun applySettings(newSettings: Settings, applyToPage: Boolean)

    /** Apply a desktop-mode change. Must not create a WebView. */
    fun applyDesktopMode(enabled: Boolean)
}

/**
 * Decides *which* tabs receive a settings change.
 *
 * This exists because the original code filtered on `hasWebView`, so a tab that had
 * not been shown yet never learned the new desktop-mode value. Switching to that tab
 * then displayed the old mode - reported as "desktop mode turns itself off when I
 * switch tabs".
 *
 * The rule is deliberately unconditional: every tab is told, always. A tab without a
 * WebView records the value and applies it when the WebView is created.
 */
object TabSettingsDispatcher {

    fun dispatchSettings(
        targets: Collection<SettingsTarget>,
        newSettings: Settings,
        applyToPage: Boolean,
    ) {
        targets.forEach { it.applySettings(newSettings, applyToPage) }
    }

    fun dispatchDesktopMode(targets: Collection<SettingsTarget>, enabled: Boolean) {
        targets.forEach { it.applyDesktopMode(enabled) }
    }
}
