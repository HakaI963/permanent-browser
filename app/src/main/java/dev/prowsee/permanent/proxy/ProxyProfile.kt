package dev.prowsee.permanent.proxy

import java.net.URI

/**
 * A proxy endpoint the user configured.
 *
 * Deliberately *not* a scraped free-server list. The app never invents a server:
 * you supply one, either by typing `host:port` or by pasting a subscription URI
 * from a provider you trust.
 */
data class ProxyProfile(
    val id: String,
    val label: String,
    val scheme: ProxyScheme,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val enabled: Boolean = true,
) {
    /** `scheme://[user:pass@]host:port` as accepted by WebView's proxy override. */
    fun toProxyUrl(): String {
        val credentials = if (!username.isNullOrBlank()) {
            val user = encodeCredential(username)
            val pass = password?.let { encodeCredential(it) } ?: ""
            "$user:$pass@"
        } else {
            ""
        }
        return "${scheme.uriName}://$credentials$host:$port"
    }

    /** Host and port only - what iptables needs. */
    fun toHostPort(): String = "$host:$port"

    fun display(): String = "$host:$port"

    private fun encodeCredential(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        /**
         * Parses `host:port`, `scheme://host:port`, or a full URI with credentials.
         * Returns null rather than guessing, so the UI can show a real error.
         */
        fun parse(input: String, label: String? = null): ProxyProfile? {
            val raw = input.trim()
            if (raw.isEmpty()) return null

            val withScheme = if (raw.contains("://")) raw else "http://$raw"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null

            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) uri.port else defaultPort(uri.scheme)
            val scheme = ProxyScheme.fromUriScheme(uri.scheme) ?: return null

            val userInfo = uri.userInfo
            val user = userInfo?.substringBefore(':')?.takeIf { it.isNotBlank() }
            val pass = userInfo?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }

            return ProxyProfile(
                id = "$host:$port",
                label = label ?: "$host:$port",
                scheme = scheme,
                host = host,
                port = port,
                username = user?.let { java.net.URLDecoder.decode(it, "UTF-8") },
                password = pass?.let { java.net.URLDecoder.decode(it, "UTF-8") },
            )
        }

        private fun defaultPort(scheme: String?): Int = when (scheme?.lowercase()) {
            "https" -> 443
            "socks", "socks5" -> 1080
            "socks4" -> 1080
            else -> 8080
        }
    }
}

enum class ProxyScheme(val uriName: String) {
    HTTP("http"),
    HTTPS("https"),
    SOCKS("socks"),
    SOCKS4("socks4"),
    SOCKS5("socks5");

    companion object {
        fun fromUriScheme(value: String?): ProxyScheme? = when (value?.lowercase()) {
            "http" -> HTTP
            "https" -> HTTPS
            "socks", "socks5" -> SOCKS5
            "socks4" -> SOCKS4
            else -> null
        }
    }
}

/** How the proxy is applied to the app's traffic. */
enum class ProxyMode(val label: String, val description: String) {
    OFF(
        "Off",
        "Traffic goes straight out. No proxy.",
    ),
    WEBVIEW(
        "Browser only",
        "Routes this browser's pages through the proxy. No root, no system VPN.",
    ),
    ROOT_REDIRECT(
        "System-wide (root)",
        "Redirects every connection from this app through the proxy with iptables. " +
            "Bypasses the Android VPN service entirely. Requires root.",
    ),
}

/**
 * Result of applying or clearing the proxy, so the UI can show the truth instead of
 * a green dot that means nothing.
 */
sealed interface ProxyStatus {
    data object Off : ProxyStatus
    data object Applying : ProxyStatus

    data class Active(
        val profile: ProxyProfile,
        val mode: ProxyMode,
        val verified: Boolean,
    ) : ProxyStatus

    data class Failed(
        val reason: String,
        val hint: String? = null,
    ) : ProxyStatus
}
