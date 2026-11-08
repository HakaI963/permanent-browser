package dev.prowsee.permanent

import android.app.Application
import dev.prowsee.permanent.data.BrowsingRepository
import dev.prowsee.permanent.data.ProwseeDatabase
import dev.prowsee.permanent.data.SettingsRepository
import dev.prowsee.permanent.engine.AdBlocker
import dev.prowsee.permanent.engine.BrowserEngine
import dev.prowsee.permanent.proxy.ProxyManager
import dev.prowsee.permanent.proxy.ProxyMode
import dev.prowsee.permanent.proxy.ProxyProfile
import dev.prowsee.permanent.util.ProwseeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProwseeApp : Application() {

    lateinit var settings: SettingsRepository
        private set
    lateinit var repository: BrowsingRepository
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        ProwseeLog.init(cacheDir)
        AdBlocker.load(this)

        settings = SettingsRepository(this)
        repository = BrowsingRepository(ProwseeDatabase.get(this))

        BrowserEngine.HistoryWriter.sink = { url, title ->
            scope.launch { runCatching { repository.recordVisit(url, title) } }
        }

        installCrashGuard()

        // A previous process may have died while iptables rules were installed.
        // Those rules would silently blackhole traffic, so clear them first thing.
        ProxyManager.recoverOnStartup(this)

        // Hydrate engine settings once, then keep them in sync. Tabs are restored
        // only after the first settings emission so they pick up the right UA/JS state.
        // updateSettings() hops to the main thread itself, so collecting on IO is safe.
        scope.launch {
            settings.settings.collect { s ->
                BrowserEngine.updateSettings(s, applyToPages = false)
                AdBlocker.enabled = s.adBlock
                BrowserEngine.ensureTabs()
            }
        }

        // Re-apply a saved proxy on cold start, but only after recovery cleared any
        // stale kernel rules, and never silently: the sheet shows the real result.
        scope.launch {
            val saved = settings.current()
            if (saved.proxyMode != ProxyMode.OFF && saved.proxyUri.isNotBlank()) {
                ProxyProfile.parse(saved.proxyUri)?.let { profile ->
                    ProxyManager.addProfile(profile)
                    ProxyManager.apply(profile, saved.proxyMode)
                }
            }
        }

        // NOTE: the foreground service is started by MainActivity, not here.
        // Application.onCreate can run because of a background trigger, and on
        // Android 12+ starting an FGS from the background throws. The service is
        // declared START_STICKY, so the system restores it after a process kill.
    }

    /**
     * Last-resort guard: log an unexpected main-thread crash instead of taking the
     * process down silently. The tab is restored from the persisted session on next
     * launch, so a single bad page cannot leave the user with a dead app.
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                ProwseeLog.e("UNCAUGHT on ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var instance: ProwseeApp
            private set
    }
}
