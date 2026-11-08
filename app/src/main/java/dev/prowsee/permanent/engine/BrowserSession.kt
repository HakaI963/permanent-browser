package dev.prowsee.permanent.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.prowsee.permanent.BuildConfig
import dev.prowsee.permanent.R
import dev.prowsee.permanent.data.Settings
import dev.prowsee.permanent.util.ProwseeLog
import dev.prowsee.permanent.util.Urls
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * One live tab.
 *
 * The WebView is created against the **application** context so it can outlive any
 * Activity. It is only temporarily parented into the Activity's view tree while the
 * tab is visible; detaching never pauses or destroys it, so the document, JS heap,
 * scroll position, form state and media all survive backgrounding and swipe-away.
 */
class BrowserSession(
    private val appContext: Context,
    val id: String = UUID.randomUUID().toString(),
    val incognito: Boolean = false,
    initialSettings: Settings,
    private val listener: Listener,
) {

    interface Listener {
        fun onChanged(session: BrowserSession)
        fun onDownload(session: BrowserSession, request: DownloadRequest)
        fun onExternalIntent(session: BrowserSession, intent: Intent)
        fun onFileChooser(session: BrowserSession, params: FileChooserParams)
        fun onPermissionRequest(session: BrowserSession, request: PermissionRequest)
        fun onJsDialog(session: BrowserSession, dialog: JsDialog)
        fun onFullscreenChanged(session: BrowserSession, view: View?)
        fun onRenderProcessGone(session: BrowserSession)
        fun onPageFinished(session: BrowserSession, url: String, title: String)
    }

    data class FileChooserParams(
        val acceptTypes: Array<String>,
        val mode: Int,
        val isCaptureEnabled: Boolean,
        val callback: ValueCallback<Array<Uri>>,
    )

    sealed interface JsDialog {
        val message: String
        val title: String

        data class Alert(override val message: String, override val title: String, val result: JsResult) : JsDialog
        data class Confirm(override val message: String, override val title: String, val result: JsResult) : JsDialog
        data class Prompt(
            override val message: String,
            override val title: String,
            val defaultValue: String,
            val result: JsPromptResult,
        ) : JsDialog
    }

    @Volatile
    var settings: Settings = initialSettings
        private set

    private var _webView: WebView? = null

    /**
     * WebView dialogs (native `<select>`, `<input type="date">`, colour pickers) need a
     * window token, which only an Activity-backed context provides. But holding the
     * Activity outright would leak it and defeat the whole point of this class.
     *
     * [MutableContextWrapper] lets the tab keep one context object for its entire life
     * while the underlying base is swapped: Activity while visible, application context
     * while detached. The document is never touched by the swap.
     */
    private val sessionContext = MutableContextWrapper(
        ContextThemeWrapper(appContext, R.style.Theme_PermanentBrowser_WebView),
    )

    private fun themed(context: Context): Context =
        ContextThemeWrapper(context, R.style.Theme_PermanentBrowser_WebView)

    /**
     * WebView is strictly single-threaded: every call must happen on the thread that
     * created it. Creating and mutating it from the DataStore collector (which runs on
     * Dispatchers.IO) is what crashed the app when a setting changed, so all access
     * funnels through [onMain] and the engine always calls in on the main thread.
     */
    val webView: WebView
        get() {
            assertMainThread()
            return _webView ?: createWebView()
        }

    /** True once the WebView exists. Guards against creating views for unseen tabs. */
    val isCreated: Boolean get() = _webView != null

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebView for tab $id touched off the main thread"
        }
    }

    var url: String = ""
        private set
    var title: String = ""
        private set
    var progress: Int = 0
        private set
    var isLoading: Boolean = false
        private set
    var favicon: Bitmap? = null
        private set
    var errorText: String? = null
        private set
    var navigationSeq: Long = 0
        private set
    /**
     * Desktop mode is derived from [settings] rather than stored separately.
     *
     * A stored copy could drift: a tab created before a toggle kept the old value, and
     * switching to it silently reverted the mode. Deriving it makes drift impossible -
     * there is only one place the value can live.
     */
    val desktopMode: Boolean get() = settings.desktopMode

    private var lastFinishedUrl: String? = null

    @Volatile
    internal var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** Adapter so the engine's dispatcher can drive a real session in tests too. */
    val settingsTarget: SettingsTarget = object : SettingsTarget {
        override val hasWebView: Boolean get() = isCreated
        override fun applySettings(newSettings: Settings, applyToPage: Boolean) =
            updateSettings(newSettings, applyToPage)
        override fun applyDesktopMode(enabled: Boolean) = setDesktopMode(enabled)
    }

    val canGoBack: Boolean get() = _webView?.canGoBack() == true
    val canGoForward: Boolean get() = _webView?.canGoForward() == true

    fun snapshot(): TabInfo = TabInfo(
        id = id,
        url = url,
        title = title.ifBlank { Urls.pretty(url) },
        progress = progress,
        isLoading = isLoading,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        favicon = favicon,
        incognito = incognito,
        desktopMode = desktopMode,
        errorText = errorText,
        navigationSeq = navigationSeq,
    )

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(sessionContext)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        UserAgents.capture(view.settings.userAgentString)
        view.isVerticalScrollBarEnabled = true
        // Horizontal scrolling stays enabled: in desktop mode a page can be wider than
        // the screen, and with this off the right-hand side is simply unreachable.
        view.isHorizontalScrollBarEnabled = true
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.setBackgroundColor(0xFF101114.toInt())
        view.applySettings(settings, desktopMode, incognito)
        if (incognito) {
            view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }
        view.webViewClient = ProwseeWebViewClient()
        view.webChromeClient = ProwseeChromeClient()
        view.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            listener.onDownload(
                this,
                DownloadRequest(
                    url = downloadUrl,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                ),
            )
        }
        BlobDownloader.install(view, "ProwseeBlob")
        // Register the viewport rewrite up front. Doing it only on toggle meant a tab
        // created while desktop mode was already on got a desktop UA with a mobile
        // viewport - the "switch tab and desktop mode is off" bug.
        DesktopMode.sync(view, desktopMode)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        _webView = view
        return view
    }

    fun loadUrl(rawUrl: String, fromUser: Boolean = true) {
        // Use the search engine the user picked, not a hardcoded one.
        val target = if (fromUser) Urls.toLoadable(rawUrl, settings.searchEngine.template) else rawUrl
        ProwseeLog.d("tab $id load $target")
        errorText = null
        url = target
        // DNT is a request header, so it can only be set on the main document here.
        // WebViewClient gives no hook for per-subresource headers, and faking one
        // would be worse than not sending it: this is the honest subset.
        if (settings.doNotTrack) {
            webView.loadUrl(target, mapOf("DNT" to "1"))
        } else {
            webView.loadUrl(target)
        }
        notifyChanged()
    }

    fun reload() {
        errorText = null
        webView.reload()
        notifyChanged()
    }

    fun hardReload() {
        errorText = null
        webView.clearCache(true)
        webView.reload()
        notifyChanged()
    }

    fun stopLoading() = webView.stopLoading()

    fun goBack(): Boolean {
        val wv = _webView ?: return false
        if (!wv.canGoBack()) return false
        wv.goBack()
        return true
    }

    fun goForward(): Boolean {
        val wv = _webView ?: return false
        if (!wv.canGoForward()) return false
        wv.goForward()
        return true
    }

    /**
     * Desktop mode is a per-tab UA override. It only takes effect on the next load, so
     * we reload - but only if this tab already has a page, and only when the value
     * actually changed, so toggling the switch twice does not reload twice.
     */
    fun setDesktopMode(enabled: Boolean) {
        if (desktopMode == enabled) return
        settings = settings.copy(desktopMode = enabled)
        val wv = _webView
        if (wv != null) {
            wv.applySettings(settings, desktopMode, incognito)
            // Pass the new value, not a constant: turning desktop mode off must remove
            // the viewport rewrite as well as restore the mobile UA.
            DesktopMode.sync(wv, desktopMode)
            if (url.isNotBlank() && url != "about:blank") wv.reload()
        }
        notifyChanged()
    }

    /**
     * Applies new global settings to this tab.
     *
     * Never touches the WebView unless it already exists, and never reloads unless the
     * caller asked for it - a settings change must not disturb an open page.
     */
    fun updateSettings(newSettings: Settings, applyToPage: Boolean) {
        val previousDesktop = desktopMode
        // Update the state unconditionally. Returning early when the WebView does not
        // exist yet left the session holding a stale desktopMode, which is exactly how
        // switching to a background tab reverted desktop mode.
        settings = newSettings

        val wv = _webView ?: run {
            notifyChanged()
            return
        }
        wv.applySettings(newSettings, desktopMode, incognito)
        // A UA change only takes effect on the next load, so a desktop-mode change
        // must reload even when the caller asked not to disturb pages.
        val desktopChanged = previousDesktop != desktopMode
        if (desktopChanged) {
            DesktopMode.sync(wv, desktopMode)
            if (url.isNotBlank() && url != "about:blank") wv.reload()
        } else if (applyToPage) {
            if (url.isNotBlank() && url != "about:blank") wv.reload()
        }
        notifyChanged()
    }

    /** Parent the live WebView into a visible container. Never re-creates it. */
    fun attach(container: ViewGroup) {
        val wv = webView
        // Give the WebView an Activity-backed context so its native dialogs can show.
        sessionContext.baseContext = themed(container.context)
        if (wv.parent === container) {
            wv.onResume()
            wv.resumeTimers()
            return
        }
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(
            wv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        wv.onResume()
        wv.resumeTimers()
        ProwseeLog.d("tab $id attached (url=$url)")
    }

    /** Remove from the window but keep the document, JS heap and media alive. */
    fun detach() {
        val wv = _webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        // Drop the Activity reference; the page itself is untouched.
        sessionContext.baseContext = themed(appContext)
        ProwseeLog.d("tab $id detached, still live (url=$url)")
    }

    fun destroy() {
        val wv = _webView ?: return
        _webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        runCatching {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        }.onFailure { ProwseeLog.w("destroy tab $id: ${it.message}") }
        if (incognito) {
            CookieManager.getInstance().flush()
        }
    }

    /** Persist a minimal description so a process restart can rebuild the tabs. */
    fun describe(): Map<String, String> = mapOf(
        "id" to id,
        "url" to url,
        "title" to title,
        "incognito" to incognito.toString(),
    )

    private fun notifyChanged() {
        listener.onChanged(this)
    }

    // ------------------------------------------------------------ client impls

    private inner class ProwseeWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            return handleUrl(view, target, request.isForMainFrame)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrl(view, url, true)

        private fun handleUrl(view: WebView, target: String, mainFrame: Boolean): Boolean {
            if (Urls.isHttp(target) || target.startsWith("about:") || target.startsWith("data:") ||
                target.startsWith("blob:") || target.startsWith("javascript:")
            ) {
                return false // let WebView handle it in-place: no reload, no external hop
            }
            val intent = runCatching { Intent.parseUri(target, Intent.URI_INTENT_SCHEME) }.getOrNull()
            if (intent != null) {
                listener.onExternalIntent(this@BrowserSession, intent)
                return true
            }
            ProwseeLog.w("unhandled scheme: $target")
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            this@BrowserSession.url = url.orEmpty()
            this@BrowserSession.isLoading = true
            this@BrowserSession.progress = 0
            this@BrowserSession.errorText = null
            // Restore the Chrome-shaped environment Google's sign-in expects.
            PageShims.inject(view)
            // Desktop mode is a UA *and* a viewport change; the viewport half has to be
            // re-applied per document because each navigation starts a new one.
            DesktopMode.sync(view, this@BrowserSession.desktopMode)
            notifyChanged()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val finished = url.orEmpty()
            this@BrowserSession.url = finished
            this@BrowserSession.isLoading = false
            this@BrowserSession.progress = 100
            val pageTitle = view.title.orEmpty()
            if (pageTitle.isNotBlank()) this@BrowserSession.title = pageTitle
            navigationSeq++
            if (finished != lastFinishedUrl) {
                lastFinishedUrl = finished
                listener.onPageFinished(this@BrowserSession, finished, pageTitle)
            }
            notifyChanged()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            this@BrowserSession.isLoading = false
            this@BrowserSession.errorText = describeError(error.errorCode)
            notifyChanged()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never silently accept a broken certificate.
            handler.cancel()
            this@BrowserSession.isLoading = false
            this@BrowserSession.errorText = "This site's security certificate is not trusted."
            notifyChanged()
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String,
        ) {
            handler.cancel()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!AdBlocker.enabled) return null
            val target = request.url.toString()
            if (request.isForMainFrame) return null
            if (!AdBlocker.isBlocked(target)) return null
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0)),
            )
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (!url.isNullOrBlank()) this@BrowserSession.url = url
            notifyChanged()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer died (OOM). The WebView handle is dead - rebuild it and
            // restore the same URL so the tab keeps working instead of going blank.
            ProwseeLog.e("render process gone for tab $id, crashed=${detail.didCrash()}")
            val lastUrl = this@BrowserSession.url
            runCatching {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            _webView = null
            listener.onRenderProcessGone(this@BrowserSession)
            if (lastUrl.isNotBlank() && lastUrl != "about:blank") {
                loadUrl(lastUrl, fromUser = false)
            }
            return true
        }

        private fun describeError(code: Int): String = when (code) {
            ERROR_HOST_LOOKUP -> "The site could not be found. Check the address or your connection."
            ERROR_CONNECT -> "Could not connect to the site."
            ERROR_TIMEOUT -> "The connection timed out."
            ERROR_FAILED_SSL_HANDSHAKE -> "Secure connection failed."
            ERROR_UNKNOWN -> "The page failed to load."
            else -> "The page failed to load (error $code)."
        }
    }

    private inner class ProwseeChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            this@BrowserSession.progress = newProgress
            if (newProgress in 1..99) this@BrowserSession.isLoading = true
            notifyChanged()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) {
                this@BrowserSession.title = title
                notifyChanged()
            }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            if (icon != null) {
                this@BrowserSession.favicon = icon
                notifyChanged()
            }
        }

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            listener.onFileChooser(
                this@BrowserSession,
                FileChooserParams(
                    acceptTypes = fileChooserParams.acceptTypes ?: emptyArray(),
                    mode = fileChooserParams.mode,
                    isCaptureEnabled = fileChooserParams.isCaptureEnabled,
                    callback = filePathCallback,
                ),
            )
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            listener.onPermissionRequest(this@BrowserSession, request)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            listener.onJsDialog(
                this@BrowserSession,
                JsDialog.Alert(message, view.title.orEmpty(), result),
            )
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            listener.onJsDialog(
                this@BrowserSession,
                JsDialog.Confirm(message, view.title.orEmpty(), result),
            )
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String,
            result: JsPromptResult,
        ): Boolean {
            listener.onJsDialog(
                this@BrowserSession,
                JsDialog.Prompt(message, view.title.orEmpty(), defaultValue.orEmpty(), result),
            )
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            this@BrowserSession.customViewCallback = callback
            listener.onFullscreenChanged(this@BrowserSession, view)
        }

        override fun onHideCustomView() {
            this@BrowserSession.customViewCallback?.onCustomViewHidden()
            this@BrowserSession.customViewCallback = null
            listener.onFullscreenChanged(this@BrowserSession, null)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            // target="_blank" -> open as a new tab and hand the new WebView back to the page.
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val newTab = BrowserEngine.openWindowTab(incognito)
            transport.webView = newTab.webView
            resultMsg.sendToTarget()
            return true
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applySettings(settings: Settings, desktopMode: Boolean, incognito: Boolean) {
    val s = this.settings
    s.javaScriptEnabled = settings.javaScript
    s.domStorageEnabled = true
    s.databaseEnabled = true
    s.javaScriptCanOpenWindowsAutomatically = true
    s.setSupportMultipleWindows(true)
    s.loadWithOverviewMode = true
    s.useWideViewPort = true
    // Pinch-to-zoom must work: it is the escape hatch when a desktop page is still
    // wider than the viewport. The on-screen +/- buttons stay hidden.
    s.builtInZoomControls = true
    s.displayZoomControls = false
    s.setSupportZoom(true)
    s.mediaPlaybackRequiresUserGesture = false
    s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    s.allowFileAccess = false
    s.allowContentAccess = false
    s.textZoom = settings.textZoom
    s.cacheMode = if (incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT

    // A clean Chrome UA: no "; wv" and no "Version/4.0", which is what makes Google
    // refuse sign-in with "this browser may not be secure".
    val desired = if (desktopMode) UserAgents.desktop() else UserAgents.mobile()
    if (s.userAgentString != desired) s.userAgentString = desired

    CookieManager.getInstance().setAcceptThirdPartyCookies(this, settings.thirdPartyCookies)
    CookieManager.getInstance().setAcceptCookie(true)
}
