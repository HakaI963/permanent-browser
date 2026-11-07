package dev.prowsee.permanent.engine

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import dev.prowsee.permanent.util.ProwseeLog
import java.io.File

/**
 * Hands page-initiated downloads to the platform DownloadManager so they keep
 * running even if the browser UI goes away.
 *
 * `blob:` and `data:` URLs cannot be fetched by DownloadManager, so they are
 * resolved in-page and written to app cache first.
 */
object DownloadHandler {

    fun enqueue(context: Context?, request: DownloadRequest, webView: android.webkit.WebView? = null) {
        val ctx = context ?: return
        val url = request.url
        ProwseeLog.d("download request: $url")

        if (url.startsWith("data:")) {
            DataUrlDownloader.save(ctx, url, request)
            return
        }
        if (url.startsWith("blob:")) {
            // WebView exposes the blob only inside the page; ask the injected hook to
            // read it and post the bytes back through the JS interface.
            val handled = webView?.let { wv ->
                BlobDownloader.requestSave(
                    wv,
                    url,
                    guessFileName(request),
                    request.mimeType ?: "application/octet-stream",
                )
            } ?: false
            if (!handled) ProwseeLog.w("blob download not handled: $url")
            return
        }
        if (!url.startsWith("http")) {
            ProwseeLog.w("unsupported download scheme: $url")
            return
        }

        val fileName = guessFileName(request)
        val mime = request.mimeType ?: "application/octet-stream"

        runCatching {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Permanent Browser download")
                setMimeType(mime)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                request.userAgent?.let { addRequestHeader("User-Agent", it) }
                request.referer?.let { addRequestHeader("Referer", it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            }
            dm.enqueue(req)
        }.onFailure { ProwseeLog.e("download enqueue failed", it) }
    }

    private fun guessFileName(request: DownloadRequest): String {
        val name = URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
        return sanitize(name)
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "download-${System.currentTimeMillis()}" }
    }
}

/** Writes a `data:` URL to the public Downloads folder. */
internal object DataUrlDownloader {

    fun save(context: Context, dataUrl: String, request: DownloadRequest) {
        runCatching {
            val comma = dataUrl.indexOf(',')
            if (comma <= 0) return
            val meta = dataUrl.substring(5, comma)
            val payload = dataUrl.substring(comma + 1)
            val isBase64 = meta.contains("base64")
            val bytes = if (isBase64) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray()
            }
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "")
            dir.mkdirs()
            val name = "download-${System.currentTimeMillis()}.bin"
            File(dir, name).writeBytes(bytes)
            ProwseeLog.d("data url saved: ${File(dir, name).absolutePath}")
        }.onFailure { ProwseeLog.e("data url save failed", it) }
    }
}
