package dev.prowsee.permanent.proxy

import android.content.Context
import dev.prowsee.permanent.util.ProwseeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for proxy state.
 *
 * The rule this class enforces: a proxy is only reported as [ProxyStatus.Active]
 * after the underlying mechanism confirms it took effect. No optimistic green dot.
 */
object ProxyManager {

    private val _status = MutableStateFlow<ProxyStatus>(ProxyStatus.Off)
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProxyProfile>>(emptyList())
    val profiles: StateFlow<List<ProxyProfile>> = _profiles.asStateFlow()

    private var activeProfile: ProxyProfile? = null
    private var activeMode: ProxyMode = ProxyMode.OFF

    fun setProfiles(list: List<ProxyProfile>) {
        _profiles.value = list
    }

    fun addProfile(profile: ProxyProfile) {
        val existing = _profiles.value.filterNot { it.id == profile.id }
        _profiles.value = existing + profile
    }

    fun removeProfile(id: String) {
        _profiles.value = _profiles.value.filterNot { it.id == id }
    }

    /**
     * Applies [profile] using [mode].
     *
     * Order matters: the WebView override is applied first because it is the only
     * mechanism that understands SOCKS and proxy auth. Root redirect is applied on
     * top for providers that expose a transparent endpoint.
     */
    fun apply(profile: ProxyProfile, mode: ProxyMode, onDone: (ProxyStatus) -> Unit = {}) {
        _status.value = ProxyStatus.Applying

        when (mode) {
            ProxyMode.OFF -> {
                clear { onDone(_status.value) }
                return
            }

            ProxyMode.WEBVIEW -> {
                WebViewProxy.apply(profile) { result ->
                    val status = result.fold(
                        onSuccess = {
                            activeProfile = profile
                            activeMode = mode
                            ProxyStatus.Active(profile, mode, verified = true)
                        },
                        onFailure = { error ->
                            ProxyStatus.Failed(
                                reason = error.message ?: "Could not apply the proxy.",
                                hint = if (!WebViewProxy.isSupported) {
                                    "Update Android System WebView, or use root redirect mode."
                                } else null,
                            )
                        },
                    )
                    _status.value = status
                    onDone(status)
                }
            }

            ProxyMode.ROOT_REDIRECT -> {
                val root = RootProxy.checkRoot()
                if (!root.available) {
                    val status = ProxyStatus.Failed(
                        reason = root.detail,
                        hint = "Root redirect needs a rooted device. Use \"Browser only\" instead.",
                    )
                    _status.value = status
                    onDone(status)
                    return
                }

                // Clear any stale WebView override before switching mechanisms so the
                // two do not stack and double-proxy the traffic.
                WebViewProxy.clear()

                val result = RootProxy.enable(profile)
                val verified = result.isSuccess && RootProxy.verify()
                val status = if (result.isSuccess) {
                    activeProfile = profile
                    activeMode = mode
                    ProxyStatus.Active(profile, mode, verified = verified)
                } else {
                    ProxyStatus.Failed(
                        reason = result.exceptionOrNull()?.message ?: "iptables redirect failed.",
                        hint = "Some kernels restrict the nat table. Try \"Browser only\".",
                    )
                }
                _status.value = status
                onDone(status)
            }
        }
    }

    fun clear(onDone: (ProxyStatus) -> Unit = {}) {
        RootProxy.disable()
        WebViewProxy.clear {
            activeProfile = null
            activeMode = ProxyMode.OFF
            _status.value = ProxyStatus.Off
            onDone(ProxyStatus.Off)
        }
    }

    /** Called on process start so a killed app never leaves iptables rules behind. */
    fun recoverOnStartup(context: Context) {
        if (_status.value is ProxyStatus.Active) return
        runCatching {
            // Stale rules from a previous process would silently blackhole traffic.
            RootProxy.disable()
            WebViewProxy.clear()
        }.onFailure { ProwseeLog.w("proxy recovery: ${it.message}") }
    }

    fun activeProfile(): ProxyProfile? = activeProfile
    fun activeMode(): ProxyMode = activeMode
}
