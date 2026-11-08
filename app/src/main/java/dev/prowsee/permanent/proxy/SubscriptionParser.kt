package dev.prowsee.permanent.proxy

import java.util.Base64

/**
 * Parses a proxy subscription the **user** pasted.
 *
 * This does not fetch a list from the internet and it does not ship a server list.
 * Whatever is imported here, the user chose it, and the app says so plainly: a proxy
 * operator can see every request you make through it.
 *
 * Supported inputs:
 *  - a single `host:port` or `scheme://user:pass@host:port` line
 *  - several such lines
 *  - a base64 blob containing those lines (the common subscription format)
 *  - `ss://` and `socks5://` URIs, converted to a plain proxy profile
 */
object SubscriptionParser {

    private const val MAX_LINES = 500

    data class Result(
        val profiles: List<ProxyProfile>,
        val skipped: List<String>,
    )

    fun parse(raw: String): Result {
        val text = maybeDecodeBase64(raw.trim())
        val profiles = mutableListOf<ProxyProfile>()
        val skipped = mutableListOf<String>()

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .take(MAX_LINES)
            .forEach { line ->
                val profile = parseLine(line)
                if (profile != null) profiles += profile else skipped += line
            }

        // No logging here on purpose: this is pure parsing logic, so it must stay
        // runnable on the JVM without Android's Log. Callers log the summary.
        return Result(profiles.distinctBy { it.id }, skipped)
    }

    private fun parseLine(line: String): ProxyProfile? {
        // Shadowsocks URIs are a different shape; surface them as unsupported rather
        // than silently pretending a SOCKS profile will work.
        if (line.startsWith("ss://", ignoreCase = true)) {
            return parseShadowsocks(line)
        }
        if (line.startsWith("vmess://", true) || line.startsWith("vless://", true) ||
            line.startsWith("trojan://", true) || line.startsWith("wireguard://", true)
        ) {
            // These are tunnelled protocols, not plain proxies; they cannot be handed
            // to WebView's ProxyController.
            return null
        }
        return ProxyProfile.parse(line)
    }

    /**
     * `ss://base64(method:password)@host:port` or `ss://base64(method:password@host:port)`.
     *
     * A Shadowsocks endpoint is not an HTTP proxy, so this is only useful behind a
     * local bridge. We still parse it so the user can see the host/port and knows the
     * app understood the link.
     */
    private fun parseShadowsocks(uri: String): ProxyProfile? {
        val body = uri.removePrefix("ss://").substringBefore('#').substringBefore('?')
        return runCatching {
            val decoded = if (body.contains('@')) {
                val (userInfo, hostPort) = body.split('@', limit = 2)
                val creds = String(Base64.getDecoder().decode(padBase64(userInfo)))
                "$creds@$hostPort"
            } else {
                String(Base64.getDecoder().decode(padBase64(body)))
            }
            val hostPort = decoded.substringAfterLast('@')
            val host = hostPort.substringBeforeLast(':')
            val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
            ProxyProfile(
                id = "ss:$host:$port",
                label = "Shadowsocks $host:$port",
                scheme = ProxyScheme.SOCKS5,
                host = host,
                port = port,
            )
        }.getOrNull()
    }

    private fun maybeDecodeBase64(input: String): String {
        if (input.contains(':') && !input.contains('=')) {
            // Looks like plain text already.
            return input
        }
        return runCatching {
            val decoded = String(Base64.getDecoder().decode(padBase64(input)))
            // Only accept the decode if it actually looks like a list.
            if (decoded.contains(':') || decoded.contains("://")) decoded else input
        }.getOrDefault(input)
    }

    private fun padBase64(value: String): String {
        val clean = value.replace("\n", "").replace("\r", "").trim()
        val remainder = clean.length % 4
        return if (remainder == 0) clean else clean + "=".repeat(4 - remainder)
    }
}
