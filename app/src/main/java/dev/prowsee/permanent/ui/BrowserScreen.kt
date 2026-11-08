package dev.prowsee.permanent.ui

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.prowsee.permanent.data.BrowsingRepository
import dev.prowsee.permanent.data.SettingsRepository
import dev.prowsee.permanent.engine.BrowserEngine
import dev.prowsee.permanent.engine.BrowserSession
import dev.prowsee.permanent.proxy.ProxyMode
import dev.prowsee.permanent.util.Urls

private enum class Sheet { None, Tabs, Menu, Bookmarks, History, Settings, Proxy }

@Composable
fun BrowserScreen(
    settingsRepository: SettingsRepository,
    repository: BrowsingRepository,
    onExitRequested: () -> Unit,
) {
    val context = LocalContext.current
    val state by BrowserEngine.state.collectAsStateWithLifecycle()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = BrowserEngine.currentSettings(),
    )
    val bookmarks by repository.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by repository.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val proxyStatus by dev.prowsee.permanent.proxy.ProxyManager.status.collectAsStateWithLifecycle()
    val proxyProfiles by dev.prowsee.permanent.proxy.ProxyManager.profiles.collectAsStateWithLifecycle()

    var sheet by remember { mutableStateOf(Sheet.None) }
    var jsDialog by remember { mutableStateOf<BrowserSession.JsDialog?>(null) }
    val scope = rememberCoroutineScope()

    val active = state.active
    val density = LocalDensity.current
    val statusBarPadding = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    }
    val navBarPadding = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    }

    // Stable container: the live WebView is parented into this and nothing else.
    val container = remember { FrameLayout(context) }

    DisposableEffect(Unit) {
        onDispose { BrowserEngine.detachFromWindow() }
    }

    // Re-attach whenever the active tab changes.
    LaunchedEffect(state.activeTabId) {
        BrowserEngine.attachTo(container)
    }

    LaunchedEffect(Unit) {
        BrowserEngine.ensureTabs()
        BrowserEngine.attachTo(container)
    }

    LaunchedEffect(Unit) {
        MainActivity.dialogBus.collect { jsDialog = it }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding),
        ) {
            BrowserToolbar(
                tab = active,
                tabCount = state.tabs.size.coerceAtLeast(1),
                incognito = active?.incognito == true,
                onNavigate = { input ->
                    if (active == null) BrowserEngine.newTab(input) else BrowserEngine.loadUrl(input)
                },
                onReload = { BrowserEngine.reload() },
                onStop = { BrowserEngine.stop() },
                onOpenTabs = { sheet = Sheet.Tabs },
                onMenu = { sheet = Sheet.Menu },
            )

            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = { container },
                    modifier = Modifier.fillMaxSize(),
                )

                if (active?.errorText != null) {
                    ErrorState(
                        message = active.errorText!!,
                        url = active.url,
                        onRetry = { BrowserEngine.reload() },
                    )
                }
            }

            BottomBar(
                canGoBack = active?.canGoBack == true,
                canGoForward = active?.canGoForward == true,
                onBack = { BrowserEngine.goBack() },
                onForward = { BrowserEngine.goForward() },
                onHome = { BrowserEngine.loadUrl(settings.homepage) },
                onBookmark = {
                    val tab = active ?: return@BottomBar
                    if (tab.url.startsWith("http")) {
                        // Fire and forget; the flow updates the icon.
                        scope.launch { repository.toggleBookmark(tab.url, tab.title) }
                    }
                },
                isBookmarked = bookmarks.any { it.url == active?.url },
                onBookmarks = { sheet = Sheet.Bookmarks },
                padding = navBarPadding,
            )
        }
    }

    // ------------------------------------------------------------------ sheets

    if (sheet == Sheet.Tabs) {
        TabsSheet(
            tabs = state.tabs,
            activeId = state.activeTabId,
            onSelect = { BrowserEngine.selectTab(it); sheet = Sheet.None },
            onClose = { BrowserEngine.closeTab(it) },
            onNewTab = { BrowserEngine.newTab(settings.homepage); sheet = Sheet.None },
            onNewIncognito = { BrowserEngine.newTab(settings.homepage, incognito = true); sheet = Sheet.None },
            onDismiss = { sheet = Sheet.None },
        )
    }

    if (sheet == Sheet.Menu) {
        MenuSheet(
            settings = settings,
            tab = active,
            onDismiss = { sheet = Sheet.None },
            onReload = { BrowserEngine.reload(); sheet = Sheet.None },
            onHardReload = { BrowserEngine.hardReload(); sheet = Sheet.None },
            onDesktopToggle = {
                // Apply to the open tab immediately; persisting the choice also keeps it
                // as the default for new tabs.
                val next = !settings.desktopMode
                BrowserEngine.setDesktopMode(next)
                scope.launch { settingsRepository.setDesktopMode(next) }
                sheet = Sheet.None
            },
            onAdBlockToggle = {
                scope.launch { settingsRepository.setAdBlock(!settings.adBlock) }
            },
            onNewTab = { BrowserEngine.newTab(settings.homepage); sheet = Sheet.None },
            onNewIncognito = { BrowserEngine.newTab(settings.homepage, incognito = true); sheet = Sheet.None },
            onBookmarks = { sheet = Sheet.Bookmarks },
            onHistory = { sheet = Sheet.History },
            onSettings = { sheet = Sheet.Settings },
            onProxy = { sheet = Sheet.Proxy },
            onCopyLink = {
                val tab = active
                if (tab != null) {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("url", tab.url),
                    )
                }
                sheet = Sheet.None
            },
            onShare = {
                val tab = active
                if (tab != null) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, tab.url)
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(send, "Share link"),
                    )
                }
                sheet = Sheet.None
            },
            onAddBookmark = {
                val tab = active
                if (tab != null && tab.url.startsWith("http")) {
                    scope.launch { repository.addBookmark(tab.url, tab.title) }
                }
                sheet = Sheet.None
            },
            onClearData = {
                BrowserEngine.clearBrowsingData(context)
                sheet = Sheet.None
            },
            onExit = {
                sheet = Sheet.None
                onExitRequested()
            },
        )
    }

    if (sheet == Sheet.Bookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            onOpen = { url -> BrowserEngine.loadUrl(url); sheet = Sheet.None },
            onDelete = { url ->
                scope.launch { repository.removeBookmark(url) }
            },
            onDismiss = { sheet = Sheet.None },
        )
    }

    if (sheet == Sheet.History) {
        HistorySheet(
            history = history,
            onOpen = { url -> BrowserEngine.loadUrl(url); sheet = Sheet.None },
            onClear = {
                scope.launch { repository.clearHistory() }
            },
            onDismiss = { sheet = Sheet.None },
        )
    }

    if (sheet == Sheet.Settings) {
        SettingsSheet(
            settings = settings,
            onHomepageChange = { v -> scope.launch { settingsRepository.setHomepage(Urls.toLoadable(v)) } },
            onJavaScriptChange = { v -> scope.launch { settingsRepository.setJavaScript(v) } },
            onAdBlockChange = { v -> scope.launch { settingsRepository.setAdBlock(v) } },
            onThirdPartyChange = { v -> scope.launch { settingsRepository.setThirdPartyCookies(v) } },
            onKeepAliveChange = { v -> scope.launch { settingsRepository.setKeepAlive(v) } },
            onDesktopChange = { v -> scope.launch { settingsRepository.setDesktopMode(v) } },
            onTextZoomChange = { v -> scope.launch { settingsRepository.setTextZoom(v) } },
            onSearchEngineChange = { v -> scope.launch { settingsRepository.setSearchEngine(v) } },
            adBlockerHosts = dev.prowsee.permanent.engine.AdBlocker.size(),
            onDismiss = { sheet = Sheet.None },
        )
    }

    if (sheet == Sheet.Proxy) {
        ProxySheet(
            status = proxyStatus,
            profiles = proxyProfiles,
            activeProfile = dev.prowsee.permanent.proxy.ProxyManager.activeProfile(),
            activeMode = dev.prowsee.permanent.proxy.ProxyManager.activeMode(),
            onApply = { profile, mode ->
                dev.prowsee.permanent.proxy.ProxyManager.apply(profile, mode)
                scope.launch {
                    settingsRepository.setProxy(profile.toProxyUrl(), mode)
                }
            },
            onDisable = {
                dev.prowsee.permanent.proxy.ProxyManager.clear()
                scope.launch { settingsRepository.setProxy("", ProxyMode.OFF) }
            },
            onAddProfiles = { list ->
                list.forEach { dev.prowsee.permanent.proxy.ProxyManager.addProfile(it) }
            },
            onRemoveProfile = { dev.prowsee.permanent.proxy.ProxyManager.removeProfile(it) },
            onDismiss = { sheet = Sheet.None },
        )
    }

    jsDialog?.let { dialog ->
        JsDialogHost(dialog = dialog, onDismiss = { jsDialog = null })
    }
}

@Composable
private fun BottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onBookmark: () -> Unit,
    isBookmarked: Boolean,
    onBookmarks: () -> Unit,
    padding: androidx.compose.ui.unit.Dp,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = padding)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onBack, enabled = canGoBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (canGoBack) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onForward, enabled = canGoForward) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (canGoForward) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onHome) {
                Icon(Icons.Default.Home, contentDescription = "Home")
            }
            IconButton(onClick = onBookmark) {
                Icon(
                    if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Bookmark",
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onBookmarks) {
                Icon(Icons.Default.Bookmarks, contentDescription = "Bookmarks")
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, url: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = Urls.pretty(url),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun JsDialogHost(dialog: BrowserSession.JsDialog, onDismiss: () -> Unit) {
    val promptValue = remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            when (dialog) {
                is BrowserSession.JsDialog.Alert -> dialog.result.confirm()
                is BrowserSession.JsDialog.Confirm -> dialog.result.cancel()
                is BrowserSession.JsDialog.Prompt -> dialog.result.cancel()
            }
            onDismiss()
        },
        title = { Text(dialog.title.ifBlank { "This page says" }) },
        text = {
            Column {
                Text(dialog.message)
                if (dialog is BrowserSession.JsDialog.Prompt) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = promptValue.value,
                        onValueChange = { promptValue.value = it },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    when (dialog) {
                        is BrowserSession.JsDialog.Alert -> dialog.result.confirm()
                        is BrowserSession.JsDialog.Confirm -> dialog.result.confirm()
                        is BrowserSession.JsDialog.Prompt -> dialog.result.confirm(promptValue.value)
                    }
                    onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = if (dialog is BrowserSession.JsDialog.Alert) null else {
            {
                androidx.compose.material3.TextButton(
                    onClick = {
                        when (dialog) {
                            is BrowserSession.JsDialog.Confirm -> dialog.result.cancel()
                            is BrowserSession.JsDialog.Prompt -> dialog.result.cancel()
                            is BrowserSession.JsDialog.Alert -> dialog.result.cancel()
                        }
                        onDismiss()
                    },
                ) { Text("Cancel") }
            }
        },
    )
}
