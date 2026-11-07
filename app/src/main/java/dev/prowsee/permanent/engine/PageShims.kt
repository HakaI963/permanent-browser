package dev.prowsee.permanent.engine

/**
 * Small page-level patches applied to every document.
 *
 * The important one is the `window.chrome` shim: Google's sign-in flow probes for a
 * Chrome-shaped environment and refuses with "this browser or app may not be secure"
 * when it finds a bare WebView. Providing the object costs nothing and is what a real
 * Chrome build exposes anyway.
 *
 * Nothing here changes how pages behave otherwise - no fingerprint spoofing, no
 * feature flagging, no analytics hooks.
 */
internal object PageShims {

    private const val SHIM = """
        (function () {
          try {
            if (!window.chrome) { window.chrome = {}; }
            var c = window.chrome;
            if (!c.app) {
              c.app = {
                isInstalled: false,
                InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
                RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
              };
            }
            if (!c.runtime) {
              c.runtime = {
                OnInstalledReason: { CHROME_UPDATE: 'chrome_update', INSTALL: 'install', SHARED_MODULE_UPDATE: 'shared_module_update', UPDATE: 'update' },
                OnRestartRequiredReason: { APP_UPDATE: 'app_update', OS_UPDATE: 'os_update', PERIODIC: 'periodic' },
                PlatformArch: { ARM: 'arm', ARM64: 'arm64', X86_32: 'x86-32', X86_64: 'x86-64' },
                PlatformOs: { ANDROID: 'android', CROS: 'cros', LINUX: 'linux', MAC: 'mac', OPENBSD: 'openbsd', WIN: 'win' },
                RequestUpdateCheckStatus: { NO_UPDATE: 'no_update', THROTTLED: 'throttled', UPDATE_AVAILABLE: 'update_available' }
              };
            }
            if (!c.csi) { c.csi = function () { return {}; }; }
            if (!c.loadTimes) { c.loadTimes = function () { return {}; }; }
          } catch (e) {}
        })();
    """

    /**
     * Injected on every page start. `evaluateJavascript` is asynchronous and safe to
     * call before the document is ready, so this does not block navigation.
     */
    fun inject(webView: android.webkit.WebView) {
        runCatching { webView.evaluateJavascript(SHIM, null) }
    }
}
