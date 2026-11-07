package dev.prowsee.permanent

import android.app.Application
import dev.prowsee.permanent.data.BrowsingRepository
import dev.prowsee.permanent.data.ProwseeDatabase
import dev.prowsee.permanent.data.SettingsRepository
import dev.prowsee.permanent.engine.AdBlocker
import dev.prowsee.permanent.engine.BrowserEngine
import dev.prowsee.permanent.engine.BrowserService
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

        // Hydrate engine settings once, then keep them in sync.
        scope.launch {
            settings.settings.collect { s ->
                BrowserEngine.updateSettings(s, applyToPages = false)
                AdBlocker.enabled = s.adBlock
            }
        }

        // Keep the foreground service alive for the whole process lifetime.
        BrowserService.start(this)
    }

    companion object {
        lateinit var instance: ProwseeApp
            private set
    }
}
