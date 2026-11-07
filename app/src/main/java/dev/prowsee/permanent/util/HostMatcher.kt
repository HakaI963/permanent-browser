package dev.prowsee.permanent.util

/**
 * Suffix-based host matching used by the ad blocker.
 *
 * Kept free of Android imports so it can be unit tested on the JVM. A blocklist
 * entry for `doubleclick.net` must also match `static.doubleclick.net`, but must
 * NOT match `doubleclick.net.example.com` or the bare `example.com`.
 */
object HostMatcher {

    fun matches(host: String, blocklist: Set<String>): Boolean {
        if (host.isEmpty() || blocklist.isEmpty()) return false
        var candidate = host.lowercase()
        while (candidate.contains('.')) {
            if (candidate in blocklist) return true
            candidate = candidate.substringAfter('.')
        }
        return candidate in blocklist
    }

    /** Extracts a lowercase hostname from a URL without pulling in android.net.Uri. */
    fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return null
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        if (hostPort.startsWith("[")) {
            return hostPort.substringBefore(']').removePrefix("[").lowercase()
        }
        return hostPort.substringBefore(':').lowercase().ifEmpty { null }
    }
}
