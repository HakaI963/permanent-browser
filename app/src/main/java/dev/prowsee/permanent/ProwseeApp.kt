package dev.prowsee.permanent

import android.app.Application
import dev.prowsee.permanent.data.BrowsingRepository
import dev.prowsee.permanent.data.ProwseeDatabase
import dev.prowsee.permanent.data.SettingsRepository
import dev.prowsee.permanent.engine.AdBlocker
import dev.prowsee.permanent.engine.BrowserEngine
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
