package dev.prowsee.permanent.util

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Small rotating log kept on disk so a user can inspect why a page was dropped.
 * Not a crash reporter - deliberately local only.
 */
object ProwseeLog {

    private const val TAG = "PermanentBrowser"
    private const val MAX_BYTES = 256L * 1024L

    @Volatile
    private var file: File? = null

    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, "session.log")
        file = f
        if (f.exists() && f.length() > MAX_BYTES) f.delete()
    }

    fun d(message: String) {
        Log.d(TAG, message)
        append("D", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        append("W", message)
    }

    fun e(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
        append("E", message + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun append(level: String, message: String) {
        val f = file ?: return
        runCatching {
            FileOutputStream(f, true).use { out ->
                out.write("${System.currentTimeMillis()} $level $message\n".toByteArray())
            }
        }
    }

    fun read(): String = runCatching { file?.readText().orEmpty() }.getOrDefault("")

    fun clear() {
        runCatching { file?.delete() }
    }
}
