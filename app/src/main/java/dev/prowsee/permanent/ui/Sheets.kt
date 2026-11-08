package dev.prowsee.permanent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.prowsee.permanent.data.BookmarkEntity
import dev.prowsee.permanent.data.HistoryEntity
import dev.prowsee.permanent.data.SearchEngine
import dev.prowsee.permanent.data.Settings
import dev.prowsee.permanent.engine.TabInfo
import dev.prowsee.permanent.util.Urls

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------- tabs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(
    tabs: List<TabInfo>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewIncognito: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(title = "Tabs kept alive (${tabs.size})", onDismiss = onDismiss) {
        Column {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    val selected = tab.id == activeId
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelect(tab.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Favicon(tab.favicon, Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = tab.title.ifBlank { Urls.pretty(tab.url) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Text(
                                    text = if (tab.incognito) "Private" else Urls.pretty(tab.url),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onClose(tab.id) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close tab",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNewTab, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New tab")
                }
                Button(onClick = onNewIncognito, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Private")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- menu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    settings: Settings,
    tab: TabInfo?,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onHardReload: () -> Unit,
    onDesktopToggle: () -> Unit,
    onAdBlockToggle: () -> Unit,
    onNewTab: () -> Unit,
    onNewIncognito: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onProxy: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    onAddBookmark: () -> Unit,
    onClearData: () -> Unit,
    onExit: () -> Unit,
) {
    SheetScaffold(title = "Menu", onDismiss = onDismiss) {
        Column {
            MenuRow("Reload this page", "Keeps the tab, refreshes content", onClick = onReload)
            MenuRow("Hard reload (bypass cache)", "Use when a page is stale", onClick = onHardReload)
            MenuRow(
                "Request desktop site",
                if (settings.desktopMode) "On" else "Off",
                onClick = onDesktopToggle,
            )
            MenuRow(
                "Block ads & trackers",
                if (settings.adBlock) "On" else "Off",
                onClick = onAdBlockToggle,
            )
            MenuRow("New tab", null, onClick = onNewTab)
            MenuRow("New private tab", null, onClick = onNewIncognito)
            MenuRow("Add bookmark", null, onClick = onAddBookmark)
            MenuRow("Bookmarks", null, onClick = onBookmarks)
            MenuRow("History", null, onClick = onHistory)
            MenuRow("Copy link", null, onClick = onCopyLink)
            MenuRow("Share link", null, onClick = onShare)
            MenuRow("Proxy", "Per-app, no VPN service", onClick = onProxy)
            MenuRow("Settings", null, onClick = onSettings)
            MenuRow("Clear browsing data", "Cookies, cache, all tabs", onClick = onClearData)
            MenuRow("Close app (keeps pages)", "Tabs stay alive in background", onClick = onExit)
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ----------------------------------------------------------------- bookmarks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(title = "Bookmarks", onDismiss = onDismiss) {
        if (bookmarks.isEmpty()) {
            EmptyHint("No bookmarks yet. Tap the star to save a page.")
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(bookmarks, key = { it.url }) { bm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(bm.url) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                bm.title.ifBlank { bm.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                Urls.pretty(bm.url),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(bm.url) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- history

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<HistoryEntity>,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(title = "History", onDismiss = onDismiss) {
        Column {
            if (history.isEmpty()) {
                EmptyHint("Nothing here yet.")
            } else {
                TextButton(onClick = onClear) { Text("Clear history") }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(history, key = { it.id }) { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpen(h.url) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    h.title.ifBlank { h.url },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    Urls.pretty(h.url),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    onHomepageChange: (String) -> Unit,
    onJavaScriptChange: (Boolean) -> Unit,
    onAdBlockChange: (Boolean) -> Unit,
    onThirdPartyChange: (Boolean) -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onDesktopChange: (Boolean) -> Unit,
    onTextZoomChange: (Int) -> Unit,
    onSearchEngineChange: (SearchEngine) -> Unit,
    adBlockerHosts: Int,
    onDismiss: () -> Unit,
) {
    var homepage by remember(settings.homepage) { mutableStateOf(settings.homepage) }

    SheetScaffold(title = "Settings", onDismiss = onDismiss) {
        Column {
            Text(
                "KEEP ALIVE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            ToggleRow(
                "Never reload pages",
                "Hold tabs in a foreground service so they survive backgrounding and swipe-away.",
                settings.keepAlive,
                onKeepAliveChange,
            )

            Text(
                "BROWSING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            ToggleRow("Request desktop site", "Use a desktop user agent by default.", settings.desktopMode, onDesktopChange)
            ToggleRow("Block ads & trackers", "$adBlockerHosts hosts in the local blocklist.", settings.adBlock, onAdBlockChange)
            ToggleRow("Allow JavaScript", "Some sites need this off to stay light.", settings.javaScript, onJavaScriptChange)
            ToggleRow("Third-party cookies", "Off is more private; some logins need it on.", settings.thirdPartyCookies, onThirdPartyChange)

            Text(
                "HOME PAGE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            OutlinedTextField(
                value = homepage,
                onValueChange = { homepage = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL") },
            )
            TextButton(onClick = { onHomepageChange(homepage) }) { Text("Save home page") }

            Text(
                "TEXT SIZE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onTextZoomChange(settings.textZoom - 10) }) { Text("A-") }
                Text("${settings.textZoom}%", modifier = Modifier.weight(1f))
                TextButton(onClick = { onTextZoomChange(settings.textZoom + 10) }) { Text("A+") }
            }

            Text(
                "SEARCH ENGINE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            SearchEngine.entries.forEach { engine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSearchEngineChange(engine) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(engine.label, modifier = Modifier.weight(1f))
                    if (settings.searchEngine == engine) {
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
