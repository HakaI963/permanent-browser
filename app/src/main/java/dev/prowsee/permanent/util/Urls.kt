package dev.prowsee.permanent.util

import android.net.Uri

/**
 * Turns whatever the user typed into something loadable, and back into something pretty.
 */
object Urls {

    const val HOME_URL = "https://duckduckgo.com/"

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$")
    private val DOMAIN = Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(:\\d+)?$")

    /** Search engine used for non-URL input. */
    private const val SEARCH = "https://duckduckgo.com/?q="

    fun toLoadable(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return HOME_URL
        if (raw.startsWith("about:") || raw.startsWith("data:") || raw.startsWith("javascript:")) return raw
        if (SCHEME.containsMatchIn(raw)) return raw

        val hostPart = raw.substringBefore('/').substringBefore('?')
        return when {
            IPV4.matches(hostPart) -> "http://$raw"
            DOMAIN.matches(hostPart) -> "https://$raw"
            // localhost:3000 or a bare intranet host
            hostPart.equals("localhost", ignoreCase = true) -> "http://$raw"
            hostPart.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> SEARCH + Uri.encode(raw)
        }
    }

    fun host(url: String): String = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")

    /** Compact form for the address bar: drop https:// and a trailing slash. */
    fun pretty(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("data:")) return "data: page"
        if (url.startsWith("about:")) return url
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
    }

    fun isHttp(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /** Best-effort favicon endpoint, used when the page does not supply one. */
    fun faviconUrl(url: String): String? {
        val host = host(url)
        if (host.isEmpty()) return null
        return "https://$host/favicon.ico"
    }
}
