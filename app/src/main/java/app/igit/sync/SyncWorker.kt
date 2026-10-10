package app.igit.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.igit.MainActivity
import app.igit.R
import app.igit.data.ContainerStore
import app.igit.data.RepoContainer
import app.igit.git.SyncException
import java.util.concurrent.TimeUnit

/**
 * Background sync. Runs only with network; when the network drops mid-sync it retries automatically once
 * connectivity returns (GitSync's "Retry automatically when the network returns").
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runner = SyncRunner(applicationContext)
        val container = runner.store.get(inputData.getString(KEY_CONTAINER)) ?: return Result.failure()
        if (!runner.engine.isCloned(container)) return Result.failure()
        val message = inputData.getString(KEY_MESSAGE)
        return try {
            runner.toast(container, "Detecting changes…")
            val outcome = runner.sync(container, message)
            if (outcome.conflicts.isNotEmpty()) {
                Notifier.conflict(applicationContext, container)
                runner.toast(container, "Ongoing merge conflict")
            } else {
                runner.toast(container, outcome.message)
                Notifier.clearError(applicationContext, container)
            }
            Result.success()
        } catch (e: SyncException) {
            if (e.networkRelated && runAttemptCount < MAX_RETRIES) {
                if (runAttemptCount == 0) runner.toast(container, "Network unavailable! igit will retry when reconnected")
                Result.retry()
            } else {
                Notifier.error(applicationContext, container, e.message ?: "Sync failed")
                Result.failure()
            }
        } catch (e: Exception) {
            Notifier.error(applicationContext, container, e.message ?: "Sync failed")
            Result.failure()
        }
    }

    companion object {
        const val KEY_CONTAINER = "container"
        const val KEY_MESSAGE = "message"
        private const val MAX_RETRIES = 8
    }
}

object SyncScheduler {
    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Queue a one-off sync for a container (tiles, widgets, shortcuts, intents, app open/close). */
    fun enqueue(context: Context, containerId: String, message: String? = null) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_CONTAINER to containerId, SyncWorker.KEY_MESSAGE to message))
            .addTag("igit-sync")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-$containerId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun schedule(context: Context, c: RepoContainer) {
        val manager = WorkManager.getInstance(context)
        val name = "periodic-${c.id}"
        if (!c.scheduledSync) {
            manager.cancelUniqueWork(name)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(c.scheduledMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_CONTAINER to c.id))
            .build()
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context, containerId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("periodic-$containerId")
        WorkManager.getInstance(context).cancelUniqueWork("sync-$containerId")
    }

    fun rescheduleAll(context: Context) {
        ContainerStore(context).containers().forEach { schedule(context, it) }
    }
}

object Notifier {
    private const val CHANNEL_STATUS = "sync_status"
    private const val CHANNEL_CONFLICT = "merge_conflicts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Sync status updates", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_CONFLICT, "Merge conflict alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun conflict(context: Context, c: RepoContainer) =
        post(context, CHANNEL_CONFLICT, c, "Merge Conflict · ${c.alias}", "There is a merge conflict! Tap to resolve", MainActivity.ACTION_RESOLVE_CONFLICTS)

    fun error(context: Context, c: RepoContainer, message: String) =
        post(context, CHANNEL_STATUS, c, "Sync failed · ${c.alias}", message, null)

    fun clearError(context: Context, c: RepoContainer) {
        NotificationManagerCompat.from(context).cancel(c.id.hashCode())
    }

    private fun post(context: Context, channel: String, c: RepoContainer, title: String, text: String, action: String?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action ?: Intent.ACTION_MAIN
            putExtra(MainActivity.EXTRA_CONTAINER, c.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, c.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(c.id.hashCode(), notification)
    }
}
