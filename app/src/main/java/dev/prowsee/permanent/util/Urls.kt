package dev.prowsee.permanent.util

/**
 * Turns whatever the user typed into something loadable, and back into something pretty.
 *
 * Deliberately free of Android dependencies so it can be unit tested on the JVM.
 */
object Urls {

    const val HOME_URL = "https://duckduckgo.com/"

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$")
    private val DOMAIN =
        Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(:\\d+)?$")

    /** Search engine used for non-URL input. */
    private const val SEARCH = "https://duckduckgo.com/?q="

    /**
     * Schemes the WebView handles itself and that must never be rewritten.
     * Kept explicit so `localhost:3000` is not mistaken for a scheme.
     */
    private val PASSTHROUGH = listOf(
        "about:", "data:", "javascript:", "blob:", "file:", "chrome:", "mailto:", "tel:", "intent:",
    )

    fun toLoadable(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return HOME_URL
        if (PASSTHROUGH.any { raw.startsWith(it, ignoreCase = true) }) return raw
        if (SCHEME.containsMatchIn(raw)) return raw

        val hostPart = raw.substringBefore('/').substringBefore('?').substringBefore('#')
        return when {
            IPV4.matches(hostPart) -> "http://$raw"
            DOMAIN.matches(hostPart) -> "https://$raw"
            hostPart.substringBefore(':').equals("localhost", ignoreCase = true) -> "http://$raw"
            // Bare intranet host with a dot, e.g. "router.lan/admin".
            hostPart.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> SEARCH + encode(raw)
        }
    }

    fun host(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        if (withoutScheme == url && !url.contains("://")) {
            // No scheme at all: treat the whole thing as host-ish input.
            return url.substringBefore('/').substringBefore('?').substringBefore('#')
                .substringBefore('@')
                .substringAfterLast('@')
                .lowercase()
        }
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        // Strip IPv6 brackets and ports.
        if (hostPort.startsWith("[")) return hostPort.substringBefore(']').removePrefix("[").lowercase()
        return hostPort.substringBefore(':').lowercase()
    }

    /** Compact form for the address bar: drop the scheme and a trailing slash. */
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
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** Percent-encode a search query for use in a URL query string. */
    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c.isLetterOrDigit() && b < 0x80 || c in "-_.~") {
                out.append(c)
            } else if (c == ' ') {
                out.append('+')
            } else {
                out.append('%')
                out.append("0123456789ABCDEF"[b shr 4])
                out.append("0123456789ABCDEF"[b and 0x0F])
            }
        }
        return out.toString()
    }
}
