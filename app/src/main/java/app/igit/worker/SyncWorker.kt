package app.igit.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.igit.data.AppPreferences
import app.igit.git.GitSyncEngine
import app.igit.security.SecretVault

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        val repo = prefs.getRepo() ?: return Result.failure()
        val token = SecretVault(applicationContext).get(GITHUB_TOKEN) ?: return Result.failure()
        return runCatching {
            val folder = prefs.getFolderUri()?.let(Uri::parse)
            val filters = applicationContext.getSharedPreferences("igit_preferences", Context.MODE_PRIVATE)
                .getString("git_filters", "").orEmpty()
            GitSyncEngine(applicationContext).sync(repo, token, folder, filters = filters)
        }.fold(
            onSuccess = { outcome -> if (outcome.conflicts.isEmpty()) Result.success() else Result.retry() },
            onFailure = { Result.retry() }
        )
    }

    companion object {
        const val GITHUB_TOKEN = "github_token"
        const val AI_API_KEY = "ai_api_key"
    }
}
