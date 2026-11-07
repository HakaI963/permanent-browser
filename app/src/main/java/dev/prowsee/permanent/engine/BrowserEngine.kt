package dev.prowsee.permanent.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import androidx.core.content.edit
import dev.prowsee.permanent.data.Settings
import dev.prowsee.permanent.util.ProwseeLog
import dev.prowsee.permanent.util.Urls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns every live [BrowserSession] and the currently attached WebView.
 *
 * Deliberately an object (process-wide singleton) rather than an Activity-scoped
 * ViewModel: the pages must not be tied to the Activity's lifecycle. The foreground
 * service keeps this process alive, so this object stays warm across
 * backgrounding and swipe-away.
 */
object BrowserEngine : BrowserSession.Listener {

    private const val PREFS = "prowsee_session"
    private const val KEY_TABS = "tabs"
    private const val KEY_ACTIVE = "active_tab"

    private val main = Handler(Looper.getMainLooper())

    private val sessions = LinkedHashMap<String, BrowserSession>()
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var settings: Settings = Settings()
    private var container: ViewGroup? = null
    private var started = false

    /** Set by MainActivity so the engine can launch intents it cannot launch itself. */
    @Volatile var activityIntents: ActivityIntents? = null

    interface ActivityIntents {
        fun launchIntent(intent: Intent)
        fun launchFileChooser(params: BrowserSession.FileChooserParams)
        fun requestWebPermissions(request: PermissionRequest)
        fun requestNotifications()
        fun onFullscreen(view: View?)
        fun showJsDialog(dialog: BrowserSession.JsDialog)
    }

    /** Called from the Activity and from BrowserService.onCreate. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (started) return
        started = true
        appContext = app
        ProwseeLog.init(app.cacheDir)
        AdBlocker.load(app)
        ProwseeLog.d("engine start")
        publish()
    }

    /** Called once settings have been read, so restored tabs use the right UA/JS state. */
    fun ensureTabs() {
        if (sessions.isNotEmpty()) return
        val ctx = appContext ?: return
        restoreSession(ctx)
        publish()
    }

    fun updateSettings(newSettings: Settings, applyToPages: Boolean = true) {
        val keepAliveChanged = newSettings.keepAlive != settings.keepAlive
        settings = newSettings
        AdBlocker.enabled = newSettings.adBlock
        sessions.values.filter { it.isCreated }.forEach { it.updateSettings(newSettings, applyToPages) }
        if (keepAliveChanged) applyKeepAlive(newSettings.keepAlive)
        publish()
    }

    /** Turning the setting off stops the foreground service; pages then behave like a normal browser. */
    private fun applyKeepAlive(enabled: Boolean) {
        val ctx = appContext ?: return
        if (enabled) {
            BrowserService.start(ctx)
        } else {
            ctx.stopService(Intent(ctx, BrowserService::class.java))
            setServiceRunning(false)
        }
        ProwseeLog.d("keepAlive=$enabled")
    }

    fun currentSettings(): Settings = settings

    // ------------------------------------------------------------------- tabs

    fun newTab(url: String = settings.homepage, incognito: Boolean = false, select: Boolean = true): String {
        val ctx = appContext ?: return ""
        val session = BrowserSession(
            appContext = ctx,
            incognito = incognito,
            initialSettings = settings,
            listener = this,
        )
        sessions[session.id] = session
        if (select) {
            detachAll()
            _state.value = _state.value.copy(activeTabId = session.id)
        }
        session.loadUrl(url, fromUser = false)
        publish()
        persistSession()
        return session.id
    }

    /** Used by `onCreateWindow` to satisfy `target="_blank"` without losing the page. */
    fun openWindowTab(incognito: Boolean): BrowserSession {
        val ctx = appContext ?: error("engine not started")
        val session = BrowserSession(
            appContext = ctx,
            incognito = incognito,
            initialSettings = settings,
            listener = this,
        )
        sessions[session.id] = session
        detachAll()
        _state.value = _state.value.copy(activeTabId = session.id)
        publish()
        persistSession()
        return session
    }

    fun selectTab(id: String) {
        if (!sessions.containsKey(id) || _state.value.activeTabId == id) return
        _state.value = _state.value.copy(activeTabId = id)
        syncAttachment()
        publish()
        persistSession()
    }

    fun closeTab(id: String) {
        val session = sessions.remove(id) ?: return
        if (session.isCreated) session.detach()
        session.destroy()
        val remaining = sessions.keys.toList()
        if (_state.value.activeTabId == id) {
            _state.value = _state.value.copy(activeTabId = remaining.firstOrNull())
        }
        if (sessions.isEmpty()) {
            newTab(settings.homepage)
        } else {
            syncAttachment()
        }
        publish()
        persistSession()
    }

    fun closeAll() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        _state.value = _state.value.copy(activeTabId = null, tabs = emptyList())
        // Leave the browser in a usable state rather than with zero tabs.
        newTab(settings.homepage)
        persistSession()
    }

    fun active(): BrowserSession? = _state.value.activeTabId?.let { sessions[it] }

    // -------------------------------------------------------------- attach/detach

    fun attachTo(target: ViewGroup) {
        container = target
        syncAttachment()
    }

    fun detachFromWindow() {
        detachAll()
        container = null
    }

    /**
     * Idempotent: if the active tab is already parented into the container this does
     * nothing. Re-adding a WebView that is already on screen causes a visible flash,
     * so it matters that tab switches and recompositions both funnel through here.
     */
    private fun syncAttachment() {
        val target = container ?: return
        val session = active() ?: return
        if (session.isCreated && session.webView.parent === target) {
            session.attach(target) // refresh the Activity-backed context only
            return
        }
        detachAll()
        session.attach(target)
    }

    private fun detachAll() {
        // Only touch sessions that actually have a WebView; reading `webView` would
        // create one, which we must not do for tabs the user never opened.
        sessions.values.forEach { if (it.isCreated) it.detach() }
        container?.removeAllViews()
    }

    // --------------------------------------------------------------- navigation

    fun loadUrl(input: String) {
        active()?.loadUrl(input) ?: newTab(input)
    }

    fun reload() = active()?.reload()
    fun hardReload() = active()?.hardReload()
    fun stop() = active()?.stopLoading()

    fun goBack(): Boolean = active()?.goBack() ?: false
    fun goForward(): Boolean = active()?.goForward() ?: false

    fun setDesktopMode(enabled: Boolean) {
        // Only live tabs: touching a session without a WebView would create one.
        sessions.values.filter { it.isCreated }.forEach { it.setDesktopMode(enabled) }
        publish()
    }

    fun setActiveDesktopMode(enabled: Boolean) = active()?.setDesktopMode(enabled)

    fun clearBrowsingData(context: Context) {
        main.post {
            sessions.values.forEach { it.destroy() }
            sessions.clear()
            android.webkit.WebStorage.getInstance().deleteAllData()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            _state.value = _state.value.copy(tabs = emptyList(), activeTabId = null)
            persistSession()
            newTab(settings.homepage)
        }
    }

    // --------------------------------------------------------------- persistence

    private fun persistSession() {
        val ctx = appContext ?: return
        val activeId = _state.value.activeTabId.orEmpty()
        val payload = sessions.values.joinToString("|") { s ->
            listOf(
                s.id,
                s.incognito,
                s.url,
                s.title.replace("|", " ").replace("\u0001", " "),
            ).joinToString("\u0001")
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_TABS, payload)
            putString(KEY_ACTIVE, activeId)
        }
    }

    private fun restoreSession(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TABS, null)
        if (raw.isNullOrBlank()) {
            newTab(settings.homepage)
            return
        }
        val entries = raw.split("|").mapNotNull { line ->
            val parts = line.split("\u0001")
            if (parts.size >= 4) parts else null
        }
        entries.distinctBy { it[0] }.forEach { parts ->
            val url = parts[2]
            if (url.isBlank()) return@forEach
            if (sessions.containsKey(parts[0])) return@forEach
            val session = BrowserSession(
                appContext = ctx,
                id = parts[0],
                incognito = parts[1].toBooleanStrictOrNull() ?: false,
                initialSettings = settings,
                listener = this,
            )
            sessions[session.id] = session
            session.loadUrl(url, fromUser = false)
        }
        if (sessions.isEmpty()) {
            // Cold start: open one tab without overwriting the persisted session.
            val session = BrowserSession(
                appContext = ctx,
                incognito = false,
                initialSettings = settings,
                listener = this,
            )
            sessions[session.id] = session
            session.loadUrl(settings.homepage, fromUser = false)
        }
        val savedActive = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)
        _state.value = _state.value.copy(
            activeTabId = savedActive?.takeIf { sessions.containsKey(it) } ?: sessions.keys.first(),
        )
    }

    // ------------------------------------------------------------------ publish

    private fun publish() {
        val tabs = sessions.values.map { it.snapshot() }
        val active = _state.value.activeTabId?.takeIf { sessions.containsKey(it) }
            ?: tabs.firstOrNull()?.id
        _state.value = _state.value.copy(tabs = tabs, activeTabId = active)
    }

    // ------------------------------------------------------- session callbacks

    override fun onChanged(session: BrowserSession) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onChanged(session) }
            return
        }
        publish()
    }

    override fun onDownload(session: BrowserSession, request: DownloadRequest) {
        DownloadHandler.enqueue(appContext, request, if (session.isCreated) session.webView else null)
    }

    override fun onExternalIntent(session: BrowserSession, intent: Intent) {
        activityIntents?.launchIntent(intent)
    }

    override fun onFileChooser(session: BrowserSession, params: BrowserSession.FileChooserParams) {
        activityIntents?.launchFileChooser(params)
    }

    override fun onPermissionRequest(session: BrowserSession, request: PermissionRequest) {
        activityIntents?.requestWebPermissions(request)
    }

    override fun onJsDialog(session: BrowserSession, dialog: BrowserSession.JsDialog) {
        activityIntents?.showJsDialog(dialog)
    }

    override fun onFullscreenChanged(session: BrowserSession, view: View?) {
        activityIntents?.onFullscreen(view)
    }

    override fun onRenderProcessGone(session: BrowserSession) {
        publish()
    }

    override fun onPageFinished(session: BrowserSession, url: String, title: String) {
        HistoryWriter.record(url, title)
        persistSession()
        updateNotificationText(session.title.ifBlank { Urls.pretty(url) })
    }

    /** Wired by the Application class to avoid a DB dependency inside the engine. */
    object HistoryWriter {
        var sink: ((String, String) -> Unit)? = null

        fun record(url: String, title: String) {
            if (url.isBlank() || !url.startsWith("http")) return
            sink?.invoke(url, title)
        }
    }

    fun setServiceRunning(running: Boolean) {
        _state.value = _state.value.copy(serviceRunning = running)
    }

    fun updateNotificationText(text: String) {
        BrowserService.updateText(appContext, text)
    }

    /** Context accessor for collaborators (blob bridge, downloads) outside this file. */
    internal fun appContext(): Context? = appContext

    fun homeUrl(): String = settings.homepage
    fun searchUrl(query: String): String = Urls.toLoadable(query)
}
