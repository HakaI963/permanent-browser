package dev.prowsee.permanent.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.prowsee.permanent.R
import dev.prowsee.permanent.ui.MainActivity
import dev.prowsee.permanent.util.ProwseeLog

/**
 * The reason pages survive.
 *
 * This service does not own the WebViews directly - [BrowserEngine] does - but it
 * keeps the *process* alive at foreground priority. Because `stopWithTask` is false
 * and the service is started with `START_STICKY`, swiping the app out of Recents
 * destroys the Activity only: the tabs, their documents and their JS heaps are
 * untouched, and reopening the app re-attaches the exact same WebView.
 */
class BrowserService : Service() {

    companion object {
        const val CHANNEL_ID = "prowsee_session"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "dev.prowsee.permanent.START"
        const val ACTION_RELOAD = "dev.prowsee.permanent.RELOAD"
        const val ACTION_CLOSE_ALL = "dev.prowsee.permanent.CLOSE_ALL"
        const val ACTION_SHUTDOWN = "dev.prowsee.permanent.SHUTDOWN"

        fun start(context: Context) {
            val intent = Intent(context, BrowserService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // Android 12+ refuses background FGS starts. The Activity will retry
                // the next time the user opens the app.
                ProwseeLog.w("service start refused: ${it.javaClass.simpleName}")
            }
        }

        fun updateText(context: Context?, text: String) {
            val ctx = context ?: return
            val nm = NotificationManagerCompat.from(ctx)
            if (!nm.areNotificationsEnabled()) return
            runCatching { nm.notify(NOTIFICATION_ID, build(ctx, text, text)) }
        }

        internal fun build(context: Context, title: String, text: String): Notification {
            ensureChannel(context)

            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val reloadIntent = PendingIntent.getService(
                context,
                1,
                Intent(context, BrowserService::class.java).setAction(ACTION_RELOAD),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val closeIntent = PendingIntent.getService(
                context,
                2,
                Intent(context, BrowserService::class.java).setAction(ACTION_CLOSE_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, context.getString(R.string.action_reload), reloadIntent)
                .addAction(0, context.getString(R.string.action_close), closeIntent)
                .build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        BrowserEngine.start(this)
        BrowserEngine.setServiceRunning(true)
        acquireWakeLock()
        ProwseeLog.d("service created")
    }

    /**
     * A partial wake lock keeps the CPU available so timers, media and websockets in
     * the live pages are not suspended when the screen turns off. It does not keep the
     * screen on and is released with the service.
     */
    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PermanentBrowser:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { ProwseeLog.w("wake lock refused: ${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD -> BrowserEngine.reload()
            ACTION_CLOSE_ALL -> BrowserEngine.closeAll()
            ACTION_SHUTDOWN -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val text = notificationText()
        startInForeground(text)

        // START_STICKY: if the OS does kill the process, the service is recreated and
        // restores the last tabs from the persisted session description.
        return START_STICKY
    }

    private fun startInForeground(text: String) {
        val notification = build(this, getString(R.string.notification_title), text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationText(): String {
        val state = BrowserEngine.state.value
        val count = state.tabs.size
        if (count == 0) return getString(R.string.notification_text_idle)
        val active = state.active
        val label = active?.title?.takeIf { it.isNotBlank() }
            ?: active?.url?.let { dev.prowsee.permanent.util.Urls.pretty(it) }
            ?: getString(R.string.notification_text_idle)
        return if (count == 1) label else "$label  •  $count tabs kept alive"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The user swiped the app away. We deliberately keep running: that is the
        // whole point. The notification stays as the visible affordance.
        ProwseeLog.d("task removed - keeping ${BrowserEngine.state.value.tabs.size} tabs alive")
        runCatching {
            val nm = NotificationManagerCompat.from(this)
            if (nm.areNotificationsEnabled()) {
                nm.notify(NOTIFICATION_ID, build(this, getString(R.string.notification_title), notificationText()))
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        BrowserEngine.setServiceRunning(false)
        ProwseeLog.d("service destroyed")
        super.onDestroy()
    }
}
