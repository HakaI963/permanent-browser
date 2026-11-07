package dev.prowsee.permanent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.prowsee.permanent.util.Urls
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prowsee_settings")

/** User-tunable behaviour. Persisted with DataStore. */
data class Settings(
    val homepage: String = Urls.HOME_URL,
    val desktopMode: Boolean = false,
    val adBlock: Boolean = true,
    val javaScript: Boolean = true,
    val thirdPartyCookies: Boolean = false,
    val keepAlive: Boolean = true,
    val textZoom: Int = 100,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
)

enum class SearchEngine(val label: String, val template: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s"),
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val homepage = stringPreferencesKey("homepage")
        val desktopMode = booleanPreferencesKey("desktop_mode")
        val adBlock = booleanPreferencesKey("ad_block")
        val javaScript = booleanPreferencesKey("javascript")
        val thirdParty = booleanPreferencesKey("third_party_cookies")
        val keepAlive = booleanPreferencesKey("keep_alive")
        val textZoom = intPreferencesKey("text_zoom")
        val searchEngine = stringPreferencesKey("search_engine")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            homepage = prefs[Keys.homepage] ?: Urls.HOME_URL,
            desktopMode = prefs[Keys.desktopMode] ?: false,
            adBlock = prefs[Keys.adBlock] ?: true,
            javaScript = prefs[Keys.javaScript] ?: true,
            thirdPartyCookies = prefs[Keys.thirdParty] ?: false,
            keepAlive = prefs[Keys.keepAlive] ?: true,
            textZoom = prefs[Keys.textZoom] ?: 100,
            searchEngine = runCatching {
                SearchEngine.valueOf(prefs[Keys.searchEngine] ?: SearchEngine.DUCKDUCKGO.name)
            }.getOrDefault(SearchEngine.DUCKDUCKGO),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setHomepage(value: String) = edit { it[Keys.homepage] = value }
    suspend fun setDesktopMode(value: Boolean) = edit { it[Keys.desktopMode] = value }
    suspend fun setAdBlock(value: Boolean) = edit { it[Keys.adBlock] = value }
    suspend fun setJavaScript(value: Boolean) = edit { it[Keys.javaScript] = value }
    suspend fun setThirdPartyCookies(value: Boolean) = edit { it[Keys.thirdParty] = value }
    suspend fun setKeepAlive(value: Boolean) = edit { it[Keys.keepAlive] = value }
    suspend fun setTextZoom(value: Int) = edit { it[Keys.textZoom] = value.coerceIn(50, 200) }
    suspend fun setSearchEngine(value: SearchEngine) = edit { it[Keys.searchEngine] = value.name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
