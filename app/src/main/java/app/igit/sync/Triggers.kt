package app.igit.sync

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.RemoteViews
import app.igit.MainActivity
import app.igit.R
import app.igit.data.ContainerStore
import app.igit.data.QuickSyncTarget

/** Starts a sync for a quick-sync target, or opens Manual Sync in the app. */
internal object QuickSync {
    fun sync(context: Context, target: QuickSyncTarget, message: String? = null) {
        val container = ContainerStore(context).resolveQuickTarget(target) ?: return
        SyncScheduler.enqueue(context, container.id, message)
    }

    fun manualIntent(context: Context, target: QuickSyncTarget): Intent {
        val container = ContainerStore(context).resolveQuickTarget(target)
        return Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_MANUAL_SYNC
            container?.let { putExtra(MainActivity.EXTRA_CONTAINER, it.id) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }
}

/** Quick Settings tile: "Sync". */
class SyncTileService : TileService() {
    override fun onClick() {
        super.onClick()
        QuickSync.sync(this, QuickSyncTarget.TILE_SYNC)
    }
}

/** Quick Settings tile: "Manual Sync" — opens file selection + commit message. */
class ManualSyncTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = QuickSync.manualIntent(this, QuickSyncTarget.TILE_MANUAL)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/** Home screen widget: "Sync Now". */
class SyncWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, SyncWidgetProvider::class.java).setAction(ACTION_WIDGET_SYNC)
        val pending = PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ids.forEach { id ->
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_sync).apply {
                setTextViewText(R.id.widget_label, context.getString(R.string.widget_sync_now))
                setOnClickPendingIntent(R.id.widget_root, pending)
            })
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_SYNC) QuickSync.sync(context, QuickSyncTarget.WIDGET_SYNC)
    }

    private companion object { const val ACTION_WIDGET_SYNC = "app.igit.action.WIDGET_SYNC" }
}

/** Home screen widget: "Manual Sync". */
class ManualSyncWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = PendingIntent.getActivity(context, 3, QuickSync.manualIntent(context, QuickSyncTarget.WIDGET_MANUAL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ids.forEach { id ->
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_sync).apply {
                setTextViewText(R.id.widget_label, context.getString(R.string.widget_manual_sync))
                setOnClickPendingIntent(R.id.widget_root, pending)
            })
        }
    }
}

/** Invisible activity used by the "Sync" launcher shortcut: queues a sync and closes immediately. */
class ShortcutSyncActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickSync.sync(this, QuickSyncTarget.SHORTCUT_SYNC)
        finish()
    }
}

/**
 * Custom intent trigger (advanced), compatible with GitSync's automation setup:
 * action `INTENT_SYNC`, optional int extra `index` (container position, 0-based) and string extra `message`.
 * Available both as a service (GitSync style) and a broadcast receiver (more reliable from Tasker/Automate).
 */
class IntentSyncService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == IntentSync.ACTION) IntentSync.handle(this, intent)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class IntentSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == IntentSync.ACTION || intent.action == IntentSync.ACTION_BROADCAST) IntentSync.handle(context, intent)
    }
}

object IntentSync {
    const val ACTION = "INTENT_SYNC"
    const val ACTION_BROADCAST = "app.igit.INTENT_SYNC"

    fun handle(context: Context, intent: Intent) {
        val store = ContainerStore(context)
        val container = if (intent.hasExtra("index")) store.byIndex(intent.getIntExtra("index", 0)) else store.active()
        container ?: return
        SyncScheduler.enqueue(context, container.id, intent.getStringExtra("message")?.takeIf { it.isNotBlank() })
    }
}

/**
 * Accessibility service that only observes which app is in the foreground, to sync when selected apps are
 * opened or closed (GitSync's App Sync). It reads no window content.
 */
class AppSyncAccessibilityService : AccessibilityService() {
    private var foreground: String? = null
    private val ignored = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        ignored += packageName
        ignored += "com.android.systemui"
        runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { ignored += it.packageName }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored || pkg == foreground) return
        val previous = foreground
        foreground = pkg
        val containers = ContainerStore(this).containers()
        containers.forEach { c ->
            val opened = c.syncOnAppOpened && pkg in c.appSyncPackages
            val closed = c.syncOnAppClosed && previous != null && previous in c.appSyncPackages && pkg !in c.appSyncPackages
            if (opened || closed) SyncScheduler.enqueue(this, c.id)
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, AppSyncAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
