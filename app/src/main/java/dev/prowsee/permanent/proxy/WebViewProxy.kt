package dev.prowsee.permanent.proxy

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import dev.prowsee.permanent.util.ProwseeLog
import java.util.concurrent.Executors

/**
 * Applies a proxy to the WebView only.
 *
 * This is the honest non-root path: `ProxyController` is an official AndroidX API
 * that reroutes this app's WebView requests without touching the rest of the device.
 * No VPN service, no root, no global side effects.
 *
 * It applies to the browser's network stack, not to `DownloadManager` or to Java
 * sockets we open ourselves - those need the root path or an in-app HTTP client.
 */
object WebViewProxy {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "prowsee-proxy").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun apply(profile: ProxyProfile, onDone: (Result<Unit>) -> Unit) {
        if (!isSupported) {
            onDone(Result.failure(UnsupportedOperationException(
                "This device's WebView does not support per-app proxying. " +
                    "Update Android System WebView, or use the root redirect mode.",
            )))
            return
        }

        val config = runCatching {
            ProxyConfig.Builder()
                .addProxyRule(profile.toProxyUrl())
                // Local/private hosts should not be tunnelled through a remote proxy.
                .addBypassRule("localhost")
                .addBypassRule("127.0.0.1")
                .addBypassRule("*.local")
                .bypassSimpleHostnames()
                .build()
        }.getOrElse { error ->
            onDone(Result.failure(error))
            return
        }

        runCatching {
            ProxyController.getInstance().setProxyOverride(config, executor) {
                ProwseeLog.d("proxy override applied: ${profile.display()} (${profile.scheme})")
                main.post { onDone(Result.success(Unit)) }
            }
        }.onFailure { error ->
            ProwseeLog.e("proxy override failed", error)
            main.post { onDone(Result.failure(error)) }
        }
    }

    fun clear(onDone: (Result<Unit>) -> Unit = {}) {
        if (!isSupported) {
            onDone(Result.success(Unit))
            return
        }
        runCatching {
            ProxyController.getInstance().clearProxyOverride(executor) {
                ProwseeLog.d("proxy override cleared")
                main.post { onDone(Result.success(Unit)) }
            }
        }.onFailure { error ->
            ProwseeLog.e("proxy clear failed", error)
            main.post { onDone(Result.failure(error)) }
        }
    }
}
