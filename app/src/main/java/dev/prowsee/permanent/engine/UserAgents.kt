package dev.prowsee.permanent.engine

import android.os.Build

/**
 * User-agent construction.
 *
 * Google (and a few other identity providers) refuse to complete sign-in when the
 * user agent looks like an embedded WebView. The giveaways are the `; wv` token and
 * the `Version/4.0` product. We therefore strip both and present a clean Chrome
 * UA built from the device's real WebView version, which is what a standalone
 * browser looks like.
 *
 * The mobile UA is captured from the first WebView we create so the Chrome major
 * version always matches the engine actually rendering the page - hardcoding a
 * version makes sites serve features the engine cannot run.
 */
internal object UserAgents {

    private const val DESKTOP_TEMPLATE =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/%s Safari/537.36"

    @Volatile
    private var chromeVersion: String = "124.0.0.0"

    @Volatile
    private var captured = false

    /** Extract the Chrome version from the WebView's own UA, once. */
    fun capture(rawUserAgent: String?) {
        if (captured || rawUserAgent.isNullOrBlank()) return
        val match = Regex("Chrome/([0-9.]+)").find(rawUserAgent)
        if (match != null) chromeVersion = match.groupValues[1]
        captured = true
    }

    /**
     * A mobile UA that reads as Chrome rather than as a WebView.
     *
     * `Version/4.0` and `; wv` are the exact tokens Google's "this browser may not be
     * secure" check keys on, so they are removed. The result is still an honest
     * description of the engine: same Chrome version, same Android build.
     */
    fun mobile(): String {
        val model = Build.MODEL.orEmpty()
            .replace(Regex("[^A-Za-z0-9 ._-]"), "")
            .trim()
            .ifBlank { "Android" }
        val build = Build.VERSION.RELEASE.orEmpty()
            .replace(Regex("[^0-9.]"), "")
            .ifBlank { "14" }
        return "Mozilla/5.0 (Linux; Android $build; $model) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVersion Mobile Safari/537.36"
    }

    fun desktop(): String = DESKTOP_TEMPLATE.format(chromeVersion)

    /** True when the given UA is one of ours, so we can restore it after a reload. */
    fun isOurs(ua: String?): Boolean =
        ua != null && (ua == desktop() || ua == mobile())

    fun version(): String = chromeVersion

    /** Test-only: return the object to its initial state. */
    internal fun resetForTests() {
        chromeVersion = "124.0.0.0"
        captured = false
    }
}
