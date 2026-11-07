package dev.prowsee.permanent.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.prowsee.permanent.util.ProwseeLog
import java.io.File

/**
 * Saves `blob:` and `data:` downloads.
 *
 * Neither scheme can be fetched by DownloadManager: the bytes only exist inside the
 * page's renderer. The injected JS reads the blob, base64-encodes it, and hands it
 * back through this bridge, which writes it to the Downloads collection.
 */
object BlobDownloader {

    private const val JS = """
        (function() {
          if (window.__prowseeBlobHook) return;
          window.__prowseeBlobHook = true;
          window.__prowseeSaveBlob = function(url, name, mime) {
            try {
              var xhr = new XMLHttpRequest();
              xhr.open('GET', url, true);
              xhr.responseType = 'blob';
              xhr.onload = function() {
                if (xhr.status !== 200 && xhr.status !== 0) {
                  ProwseeBlob.onError('fetch failed ' + xhr.status);
                  return;
                }
                var blob = xhr.response;
                var reader = new FileReader();
                reader.onloadend = function() {
                  var dataUrl = reader.result || '';
                  var comma = dataUrl.indexOf(',');
                  ProwseeBlob.save(name || 'download', mime || blob.type || '', dataUrl.substring(comma + 1));
                };
                reader.onerror = function() { ProwseeBlob.onError('read failed'); };
                reader.readAsDataURL(blob);
              };
              xhr.onerror = function() { ProwseeBlob.onError('network error'); };
              xhr.send();
            } catch (e) {
              ProwseeBlob.onError(String(e));
            }
          };
        })();
    """

    private val instrumented = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<WebView, Boolean>(),
    )

    /** Installs the in-page hook once per WebView. */
    @Synchronized
    fun install(webView: WebView, bridgeName: String) {
        if (!instrumented.add(webView)) return
        webView.addJavascriptInterface(Bridge(), bridgeName)
        webView.evaluateJavascript(JS, null)
    }

    /**
     * Asks the page to fetch a blob URL and stream it back through the bridge.
     * Returns false when the page has no hook (e.g. the blob came from another frame).
     */
    fun requestSave(webView: WebView, url: String, fileName: String, mimeType: String): Boolean {
        val name = jsEscape(fileName)
        val mime = jsEscape(mimeType)
        val target = jsEscape(url)
        return runCatching {
            webView.evaluateJavascript(
                "window.__prowseeSaveBlob && window.__prowseeSaveBlob('$target','$name','$mime')",
                null,
            )
            true
        }.getOrDefault(false)
    }

    private fun jsEscape(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

    class Bridge {

        @JavascriptInterface
        fun save(name: String, mimeType: String, base64: String) {
            val ctx = BrowserEngine.appContext()
            if (ctx == null) {
                ProwseeLog.w("blob save: no context")
                return
            }
            runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safeName = sanitize(name, mimeType)
                writeToDownloads(ctx, safeName, mimeType.ifBlank { "application/octet-stream" }, bytes)
                ProwseeLog.d("blob saved: $safeName (${bytes.size} bytes)")
            }.onFailure { ProwseeLog.e("blob save failed", it) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            ProwseeLog.w("blob download failed in page: $message")
        }

        private fun sanitize(name: String, mimeType: String): String {
            val base = name
                .substringBefore('?')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifBlank { "download-${System.currentTimeMillis()}" }
            if (base.contains('.')) return base
            val ext = mimeType.substringAfter('/', "").substringBefore(';').ifBlank { "bin" }
            return "$base.$ext"
        }

        private fun writeToDownloads(context: Context, name: String, mime: String, bytes: ByteArray) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    ProwseeLog.w("blob save: MediaStore insert returned null")
                    return
                }
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
            }
        }
    }
}
