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
- **Desktop mode** — a real desktop layout, not just a UA swap: the UA changes *and* the CSS viewport is widened to 980px via document-start JavaScript, which is what actually makes responsive sites render their desktop breakpoint. Horizontal scrolling and pinch-zoom stay enabled so a page that still overflows can be reached.
- **Private tabs** — no cache, no history writes.
- **Ad & tracker blocking** — local hosts blocklist, ~220 domains, parent-domain matching, main frame never blocked.
- **Bookmarks & history** — Room, local only.
- **Downloads** — delegated to `DownloadManager`, so they continue after you leave. `blob:` and `data:` URLs are read in-page through a JS bridge and written to `MediaStore`.
- **File uploads** — full `onShowFileChooser` bridge to the system picker, including multi-select.
- **Camera / mic** — `onPermissionRequest` mapped to real Android runtime permissions.
- **JS dialogs** — alert / confirm / prompt rendered as native Compose dialogs.
- **Popups** — `target="_blank"` opens a real new tab via `WebViewTransport`.
- **Settings** — home page, JS, third-party cookies, text zoom, search engine, keep-alive.
- **Per-app proxy** — `ProxyController`, no VPN service, no root. You supply the server.
- **Root redirect** — iptables UID redirect for rooted devices, still no VPN service.

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
│   ├── DesktopMode.kt         Viewport rewrite for desktop layout
│   ├── PageShims.kt           window.chrome stub for Google sign-in
│   ├── UserAgents.kt          Clean Chrome UA, no "; wv" / "Version/4.0"
│   └── EngineModels.kt        TabInfo / EngineState
├── ui/
│   ├── MainActivity.kt        Compose host + intent/file/permission bridges
│   ├── BrowserScreen.kt       Screen composition, sheets, error state
│   ├── BrowserToolbar.kt      Address bar
│   ├── Sheets.kt              Tabs, menu, bookmarks, history, settings
│   └── Theme.kt
├── proxy/
│   ├── ProxyProfile.kt        Endpoint model + URI parsing
│   ├── WebViewProxy.kt        ProxyController (non-root)
│   ├── RootProxy.kt           iptables UID redirect (root, no VpnService)
│   ├── ProxyManager.kt        Applies + verifies, never lies about state
│   └── SubscriptionParser.kt  Parses user-supplied lists
└── util/
    ├── Urls.kt                Input → URL/search heuristics
    ├── HostMatcher.kt         Pure suffix matching for the blocker
    └── ProwseeLog.kt          Local rotating log
```

## Signing in to Google

Google refuses sign-in from anything that identifies as an embedded WebView. Two
things were needed:

1. **A clean user agent.** The stock WebView UA contains `; wv` and `Version/4.0`;
   both are stripped and a real Chrome UA is built from the device's actual engine
   version (`UserAgents`).
2. **A Chrome-shaped environment.** Google's flow probes `window.chrome`; a bare
   WebView has no such object, which is what produces *"this browser or app may not
   be secure"*. `PageShims` provides the standard stub on every page start.

Third-party cookies are **on by default** because Google, Microsoft and most SSO
providers need them to complete the redirect chain. Turn them off in Settings if you
prefer the stricter default and do not sign in to those services.

If a provider still refuses, open it in the system browser via the menu — that is a
limitation of any WebView-based browser, not of this one.

## Proxy

Two mechanisms, both honest about what they can do:

| Mode | Root | Mechanism | Scope |
| --- | --- | --- | --- |
| Browser only | no | `ProxyController` | this app's WebView requests |
| System-wide | yes | `iptables -t nat ... --uid-owner` | every connection from this app's UID |

**No `BIND_VPN_SERVICE` permission is requested.** Android's `VpnService` is never
used, by design.

### You supply the server

The app ships **no server list** and never downloads one. You add a `host:port`, or
paste a subscription you already have. That is a deliberate choice, not an
oversight: a free proxy list is somebody else's server watching every login you make,
and an auto-updating list is an invitation for a third party to join it and MITM you.

### On "IP spoofing"

It is not possible over TCP, and no app can make it work. A forged source address
means the server's replies are routed to the address you claimed, not to you, so the
three-way handshake never completes and no connection forms. This is why IP spoofing
only appears in one-way DDoS reflection, never in browsing.

What *does* hide your address is a proxy you control, because the request genuinely
originates from the proxy. That is what this implements. Anything advertising
"IP spoofing for browsing" is a proxy with a misleading label.

### What is not implemented, and why

- **Anti-detect / fingerprint evasion.** A WebView leaks TLS/JA3, canvas, WebGL,
  fonts and timing. Spoofing the UA does not change those, so it creates false
  confidence while you remain uniquely identifiable. Worse than doing nothing if you
  are relying on it. Only `DNT: 1` is sent, which is a polite request, not a disguise.
- **Shadowsocks as a live tunnel.** `ss://` URIs are parsed so the endpoint is
  visible, but a Shadowsocks endpoint is not an HTTP proxy; it needs a local bridge.
  The app does not pretend a SOCKS profile will work against it.

## Why desktop mode is two changes, not one

Swapping the user agent is the obvious half and the easy half. It is also the half
that does nothing on its own for most modern sites.

Responsive CSS chooses a layout from the **viewport width**, not from the UA. A page
whose `<meta name="viewport">` still says `width=device-width` renders its mobile
breakpoint no matter what the UA claims. Chrome's "Request desktop site" therefore
also rewrites the viewport meta, and this browser does the same through
`WebViewCompat.addDocumentStartJavaScript` — document-start matters because the
rewrite has to win the race against the page's own scripts.

## Known limits

- **WebView, not Chromium.** It cannot be a full Chrome replacement: no extensions, no built-in sync, and sites are limited by the system WebView version.
- **Some identity providers still detect WebView.** The UA and `window.chrome` fixes cover Google, but a provider that inspects `navigator.userAgentData` or runs a WebView-specific probe can still refuse. There is no reliable workaround short of shipping a real Chromium build.
- **Process death is still possible.** Nothing in userspace prevents it. The session is restored instead.
- **Video in background** depends on the site and the WebView build; the page stays alive but playback may pause itself.
- **`specialUse` foreground service** is the correct Play Store category for this, but Google requires a declaration if you publish there.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

50 JVM tests cover the URL/search heuristics, the blocker's suffix matching, the
user-agent rules, the desktop-viewport hook, proxy URI + subscription parsing, and
desktop-mode propagation across tabs. Several are regression guards for
shipped bugs:

- `doubleclick.net` blocks `static.doubleclick.net` but not `doubleclick.net.example.com`
- the UA never contains `; wv` or `Version/4.0`, the two tokens Google's sign-in
  rejects
- the desktop hook rewrites an existing `width=device-width` meta and creates one
  when the page has none, without pinning `initial-scale`
- every tab receives a desktop-mode change, including tabs whose WebView does not
  exist yet (the "switch tab and desktop mode turns off" bug)

## License

MIT
