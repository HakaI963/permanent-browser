package dev.prowsee.permanent.engine

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.prowsee.permanent.util.ProwseeLog
import java.util.Collections
import java.util.WeakHashMap

/**
 * Desktop-mode support.
 *
 * Changing the user agent alone does **not** produce a desktop layout on modern
 * sites. Responsive CSS keys off the *viewport width*, not the UA, so a page whose
 * `<meta name="viewport">` still says `width=device-width` renders its mobile
 * breakpoint even when it believes it is talking to Windows.
 *
 * Real Chrome's "Request desktop site" therefore does two things: swaps the UA *and*
 * neutralises the viewport meta.
 *
 * The rewrite is registered as **document-start** JavaScript so it runs before the
 * page's own scripts and before its responsive CSS is evaluated. Injecting from
 * `onPageStarted` races the document and loses on fast pages.
 */
internal object DesktopMode {

    /**
     * Desktop layout width.
     *
     * 980 is the classic desktop viewport width that mobile browsers have used for
     * "request desktop site" for years. 1280 triggered the same media queries but was
     * wide enough that pages with fixed-width containers overflowed a phone screen,
     * and because horizontal scrolling was disabled the right-hand side was
     * unreachable. 980 still hits desktop breakpoints on virtually every site while
     * fitting far more often.
     */
    private const val DESKTOP_WIDTH = 980

    private val HOOK = """
        (function () {
          if (window.__prowseeDesktop) return;
          window.__prowseeDesktop = true;

          var WIDTH = %d;
          // Deliberately NO initial-scale: WebView's overview mode (loadWithOverviewMode
          // + useWideViewPort) zooms the fixed-width page out to fit the screen. Adding
          // initial-scale=1 would pin the zoom and show only the left edge.
          // user-scalable lets the user pinch out when a site still overflows; the
          // WebView then has a way to reach the clipped side.
          var DESIRED = 'width=' + WIDTH + ', user-scalable=yes';

          function fix() {
            var metas = document.querySelectorAll('meta[name="viewport"]');
            if (metas.length === 0) {
              var m = document.createElement('meta');
              m.name = 'viewport';
              m.content = DESIRED;
              (document.head || document.documentElement).appendChild(m);
              return;
            }
            for (var i = 0; i < metas.length; i++) {
              if (metas[i].content !== DESIRED) metas[i].content = DESIRED;
            }
          }

          function start() {
            fix();
            try {
              var observer = new MutationObserver(function (records) {
                for (var i = 0; i < records.length; i++) {
                  var nodes = records[i].addedNodes;
                  for (var j = 0; j < nodes.length; j++) {
                    var n = nodes[j];
                    if (n.nodeType === 1 && n.tagName === 'META' &&
                        (n.name || '').toLowerCase() === 'viewport') {
                      n.content = DESIRED;
                    }
                  }
                }
              });
              observer.observe(document.documentElement, { childList: true, subtree: true });
            } catch (e) {}
          }

          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', start);
          } else {
            start();
          }
        })();
    """.trimIndent()

    /** Live document-start registrations, so they can be removed on toggle-off. */
    private val handlers =
        Collections.synchronizedMap(WeakHashMap<WebView, ScriptHandler>())

    /** Exposed so tests can assert on the generated JavaScript. */
    internal fun hookForTests(): String = HOOK.format(DESKTOP_WIDTH)

    private fun script(): String = HOOK.format(DESKTOP_WIDTH)

    /**
     * Turns the viewport rewrite on or off for a WebView.
     *
     * The document-start API has no "disabled" state, so this registers and removes
     * the handler instead. Called whenever the UA flips.
     */
    fun sync(webView: WebView, enabled: Boolean) {
        val existing = handlers[webView]

        if (enabled) {
            if (existing != null) return
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    handlers[webView] =
                        WebViewCompat.addDocumentStartJavaScript(webView, script(), setOf("*"))
                    ProwseeLog.d("desktop viewport rule registered")
                }.onFailure {
                    ProwseeLog.w("document-start register failed, falling back: ${it.message}")
                    evaluate(webView)
                }
            } else {
                // Older WebView: inject now. Works for the current document only.
                evaluate(webView)
            }
        } else {
            if (existing != null) {
                runCatching { existing.remove() }
                    .onFailure { ProwseeLog.w("document-start remove failed: ${it.message}") }
                handlers.remove(webView)
                ProwseeLog.d("desktop viewport rule removed")
            }
        }
    }

    /** Best-effort injection for engines without document-start script support. */
    private fun evaluate(webView: WebView) {
        runCatching { webView.evaluateJavascript(script(), null) }
    }
}
