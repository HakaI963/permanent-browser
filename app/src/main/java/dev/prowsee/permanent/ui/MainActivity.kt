package dev.prowsee.permanent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.prowsee.permanent.ProwseeApp
import dev.prowsee.permanent.engine.BrowserEngine
import dev.prowsee.permanent.engine.BrowserService
import dev.prowsee.permanent.engine.BrowserSession
import dev.prowsee.permanent.util.ProwseeLog
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), BrowserEngine.ActivityIntents {

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenContainer: FrameLayout? = null
    private var pendingPermission: PermissionRequest? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback
            fileCallback = null
            if (callback == null) return@registerForActivityResult
            if (result.resultCode != RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }
            val data = result.data
            val uris: Array<Uri>? = when {
                data == null -> null
                data.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingPermission
            pendingPermission = null
            if (request == null) return@registerForActivityResult
            val granted = request.resources.filter { resource ->
                val needed = when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    else -> null
                }
                needed == null ||
                    grants[needed] == true ||
                    ContextCompat.checkSelfPermission(this, needed) == PackageManager.PERMISSION_GRANTED
            }
            if (granted.isEmpty()) {
                request.deny()
            } else {
                request.grant(granted.toTypedArray())
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProwseeLog.d("MainActivity onCreate")

        val app = application as ProwseeApp
        BrowserEngine.start(app)
        BrowserEngine.activityIntents = this
        if (BrowserEngine.currentSettings().keepAlive) {
            BrowserService.start(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)

        setContent {
            ProwseeTheme {
                BrowserScreen(
                    settingsRepository = app.settings,
                    repository = app.repository,
                    onExitRequested = { finishAndRemoveTask() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null) {
            val target = data.toString()
            if (target.startsWith("http")) {
                BrowserEngine.loadUrl(target)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BrowserEngine.activityIntents = this
    }

    override fun onResume() {
        super.onResume()
        BrowserEngine.activityIntents = this
    }

    override fun onPause() {
        super.onPause()
        // The Activity is going away but the pages are not: the engine keeps them.
        ProwseeLog.d("MainActivity onPause - pages stay live")
    }

    override fun onDestroy() {
        if (BrowserEngine.activityIntents === this) {
            BrowserEngine.activityIntents = null
        }
        super.onDestroy()
    }

    // ------------------------------------------------- BrowserEngine.ActivityIntents

    override fun launchIntent(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { ProwseeLog.w("no activity for ${intent.action}: ${it.message}") }
    }

    override fun launchFileChooser(params: BrowserSession.FileChooserParams) {
        fileCallback?.onReceiveValue(null)
        fileCallback = params.callback

        val chooser = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            val accepts = params.acceptTypes.filter { it.isNotBlank() }
            if (accepts.isNotEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, accepts.toTypedArray())
            }
            if (params.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        runCatching { fileChooserLauncher.launch(chooser) }
            .onFailure {
                fileCallback?.onReceiveValue(null)
                fileCallback = null
            }
    }

    override fun requestWebPermissions(request: PermissionRequest) {
        val wanted = mutableListOf<String>()
        request.resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    if (!hasPerm(Manifest.permission.CAMERA)) wanted += Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    if (!hasPerm(Manifest.permission.RECORD_AUDIO)) wanted += Manifest.permission.RECORD_AUDIO
            }
        }
        if (wanted.isEmpty()) {
            val granted = request.resources.filter {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
            }
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
            return
        }
        pendingPermission = request
        permissionLauncher.launch(wanted.toTypedArray())
    }

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Fullscreen <video>: the WebView hands us its own custom view. It must be added to
     * a real window or nothing renders, so we mount it in an overlay FrameLayout above
     * the Compose content.
     */
    override fun onFullscreen(view: View?) {
        if (view == null) {
            fullscreenContainer?.removeAllViews()
            fullscreenContainer?.visibility = View.GONE
            WindowCompat.setDecorFitsSystemWindows(window, true)
            return
        }
        val overlay = fullscreenContainer ?: FrameLayout(this).also {
            it.setBackgroundColor(android.graphics.Color.BLACK)
            addContentView(
                it,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            fullscreenContainer = it
        }
        overlay.removeAllViews()
        overlay.visibility = View.VISIBLE
        overlay.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.bringToFront()
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun showJsDialog(dialog: BrowserSession.JsDialog) {
        // Routed through Compose via the engine's shared state in BrowserScreen.
        pendingDialog = dialog
        dialogBus.tryEmit(dialog)
    }

    private fun hasPerm(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        var pendingDialog: BrowserSession.JsDialog? = null
        val dialogBus = kotlinx.coroutines.flow.MutableSharedFlow<BrowserSession.JsDialog>(
            extraBufferCapacity = 4,
        )
    }
}
