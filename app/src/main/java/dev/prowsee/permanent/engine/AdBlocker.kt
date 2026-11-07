package dev.prowsee.permanent.engine

import android.content.Context
import android.util.LruCache
import dev.prowsee.permanent.util.HostMatcher
import dev.prowsee.permanent.util.ProwseeLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts-based ad / tracker blocker.
 *
 * Matching is done on the request hostname against a compact suffix set loaded
 * from `assets/blocklist.txt`. Requests that are blocked are answered with an
 * empty 204 so the page keeps rendering instead of erroring out.
 *
 * The main frame is never blocked - otherwise navigating to a site whose apex
 * domain is in the list would produce a dead tab.
 */
object AdBlocker {

    private const val ASSET = "blocklist.txt"

    private val hosts = ConcurrentHashMap.newKeySet<String>()
    private val allowCache = LruCache<String, Boolean>(512)

    @Volatile
    private var loaded = false

    @Volatile
    var enabled: Boolean = true

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                context.assets.open(ASSET).use { input ->
                    BufferedReader(InputStreamReader(input)).forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                        // Accept both "0.0.0.0 host" hosts-file lines and bare domains.
                        val host = line.split(Regex("\\s+")).last().lowercase()
                        if (host.isNotEmpty() && host != "0.0.0.0" && host != "127.0.0.1") {
                            hosts.add(host)
                        }
                    }
                }
                ProwseeLog.d("AdBlocker loaded ${hosts.size} hosts")
            }.onFailure { ProwseeLog.w("AdBlocker load failed: ${it.message}") }
            loaded = true
        }
    }

    fun isBlocked(url: String): Boolean {
        if (!enabled || hosts.isEmpty()) return false
        val cached = allowCache.get(url)
        if (cached != null) return cached
        val host = HostMatcher.hostOf(url) ?: return false
        val blocked = HostMatcher.matches(host, hosts)
        allowCache.put(url, blocked)
        return blocked
    }

    fun size(): Int = hosts.size
}
