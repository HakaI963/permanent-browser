# Permanent Browser

A browser that **never reloads your pages**.

Chrome, Firefox and every other Android browser drop your page when you leave the app: the tab goes to the background, the renderer gets throttled, and when you come back the page has been killed and re-fetched. You lose scroll position, form input, video position, a half-played game, an unsaved draft.

Permanent Browser is built around one rule: **the page is not owned by the screen.**

```
┌─────────────────────────────────────────────┐
│  MainActivity (Compose UI)                  │  ← disposable, dies on swipe-away
│    └── FrameLayout container                │
│           ▲ attach / detach only            │
├───────────┼─────────────────────────────────┤
│  BrowserEngine (process singleton)          │  ← survives everything but process death
│    ├── BrowserSession #1  → WebView (live)  │
│    ├── BrowserSession #2  → WebView (live)  │
│    └── BrowserSession #n  → WebView (live)  │
├─────────────────────────────────────────────┤
│  BrowserService (foreground, START_STICKY)  │  ← keeps the process alive
└─────────────────────────────────────────────┘
```

## What actually survives what

| Event | Normal browser | Permanent Browser |
| --- | --- | --- |
| Home button / switch app | Page may be throttled | **Live** — WebView never paused |
| Screen off for a long time | Often reloads | **Live** — foreground service holds priority |
| Swipe app from Recents | Tab destroyed, page reloads | **Live** — only the Activity dies |
| Rotate / resize / split screen | Activity recreated | **Live** — `configChanges` + retained WebView |
| Low memory pressure | Renderer killed | **Recovered** — `onRenderProcessGone` rebuilds and reloads the same URL |
| OS kills the process | Page gone | Tabs restored from the saved session (this is a reload) |
| You tap Reload | Reloads | Reloads — by design |

The last two rows are the honest limits. A third-party app cannot be immune to the low-memory killer forever; what we can do is make the process as expensive to kill as Android allows and restore gracefully when it happens.

## Features

- **Multi-tab, all kept alive** — switching tabs re-parents a live WebView, never re-creates it.
- **Foreground session service** — persistent notification with Reload and Close-all actions.
- **Address bar** — URL or search, with a URL/search heuristic (`Urls.toLoadable`).
- **Desktop mode** — per-tab desktop user agent toggle.
- **Private tabs** — no cache, no history writes.
- **Ad & tracker blocking** — local hosts blocklist, ~220 domains, parent-domain matching, main frame never blocked.
- **Bookmarks & history** — Room, local only.
- **Downloads** — delegated to `DownloadManager`, so they continue after you leave. `blob:` and `data:` URLs are read in-page through a JS bridge and written to `MediaStore`.
- **File uploads** — full `onShowFileChooser` bridge to the system picker, including multi-select.
- **Camera / mic** — `onPermissionRequest` mapped to real Android runtime permissions.
- **JS dialogs** — alert / confirm / prompt rendered as native Compose dialogs.
- **Popups** — `target="_blank"` opens a real new tab via `WebViewTransport`.
- **Settings** — home page, JS, third-party cookies, text zoom, search engine, keep-alive.

## Install

Grab an APK from [Releases](../../releases) or the `Build APK` workflow artifacts.

```bash
adb install -r permanent-browser-release.apk
```

`minSdk` 26 (Android 8.0), `targetSdk` 35.

## Build

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # minified, signed with the debug key for sideloading
```

Requires JDK 17+ and an Android SDK with platform 35.

## How the keep-alive actually works

1. `ProwseeApp.onCreate` starts `BrowserService` as a `specialUse` foreground service and never stops it.
2. `BrowserSession` creates its WebView against the **application** context wrapped in a theme — not the Activity — so the view has no dependency on any Activity instance.
3. `BrowserScreen` keeps one stable `FrameLayout` and calls `BrowserEngine.attachTo(it)`. Attaching is `addView`; detaching is `removeView`. Neither calls `onPause()`, `destroy()`, or `loadUrl()`.
4. `BrowserService` is declared with `android:stopWithTask="false"`, so a Recents swipe calls `onTaskRemoved` on the service instead of tearing down the process.
5. Tab URLs are persisted on every navigation. If the process does die, `START_STICKY` recreates the service and the tabs are rebuilt.

## Project layout

```
app/src/main/java/dev/prowsee/permanent/
├── ProwseeApp.kt              Application: settings, DB, service bootstrap
├── data/
│   ├── SettingsRepository.kt  DataStore preferences
│   └── ProwseeDatabase.kt     Room: bookmarks + history
├── engine/
│   ├── BrowserEngine.kt       Process-wide tab registry + attach/detach
│   ├── BrowserSession.kt      One live WebView and its clients
│   ├── BrowserService.kt      Foreground service, notification, START_STICKY
│   ├── AdBlocker.kt           Hosts-based blocker
│   ├── DownloadHandler.kt     DownloadManager bridge
│   ├── BlobDownloader.kt      In-page JS bridge for blob:/data: saves
│   └── EngineModels.kt        TabInfo / EngineState
├── ui/
│   ├── MainActivity.kt        Compose host + intent/file/permission bridges
│   ├── BrowserScreen.kt       Screen composition, sheets, error state
│   ├── BrowserToolbar.kt      Address bar
│   ├── Sheets.kt              Tabs, menu, bookmarks, history, settings
│   └── Theme.kt
└── util/
    ├── Urls.kt                Input → URL/search heuristics
    ├── HostMatcher.kt         Pure suffix matching for the blocker
    └── ProwseeLog.kt          Local rotating log
```

## Known limits

- **WebView, not Chromium.** It cannot be a full Chrome replacement: no extensions, no built-in sync, and sites are limited by the system WebView version.
- **Process death is still possible.** Nothing in userspace prevents it. The session is restored instead.
- **Video in background** depends on the site and the WebView build; the page stays alive but playback may pause itself.
- **`specialUse` foreground service** is the correct Play Store category for this, but Google requires a declaration if you publish there.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

16 JVM tests cover the URL/search heuristics and the blocker's suffix matching,
including the case that matters most: `doubleclick.net` blocks
`static.doubleclick.net` but not `doubleclick.net.example.com`.

## License

MIT
