package dev.prowsee.permanent.engine

import android.graphics.Bitmap

/** Immutable snapshot of one tab, safe to hand to Compose. */
data class TabInfo(
    val id: String,
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val favicon: Bitmap? = null,
    val incognito: Boolean = false,
    val desktopMode: Boolean = false,
    val errorText: String? = null,
    /** Increments whenever the page finishes a navigation. Used for history writes. */
    val navigationSeq: Long = 0,
)

data class EngineState(
    val tabs: List<TabInfo> = emptyList(),
    val activeTabId: String? = null,
    val serviceRunning: Boolean = false,
    val lastCrashNote: String? = null,
) {
    val active: TabInfo? get() = tabs.firstOrNull { it.id == activeTabId }
}

/** A download the page asked for. */
data class DownloadRequest(
    val url: String,
    val userAgent: String? = null,
    val contentDisposition: String? = null,
    val mimeType: String? = null,
    val contentLength: Long = -1L,
    val referer: String? = null,
)
