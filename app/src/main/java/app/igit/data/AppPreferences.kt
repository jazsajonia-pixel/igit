package app.igit.data

import android.content.Context
import org.json.JSONObject


data class RepoConfig(
    val owner: String,
    val name: String,
    val defaultBranch: String = "main",
    val remoteUrl: String = "https://github.com/$owner/$name.git"
) {
    val fullName: String get() = "$owner/$name"
    val key: String get() = "${owner.lowercase()}-${name.lowercase()}".replace(Regex("[^a-z0-9._-]"), "-")

    fun toJson() = JSONObject()
        .put("owner", owner)
        .put("name", name)
        .put("defaultBranch", defaultBranch)
        .put("remoteUrl", remoteUrl)

    companion object {
        fun fromJson(json: JSONObject) = RepoConfig(
            owner = json.optString("owner"),
            name = json.optString("name"),
            defaultBranch = json.optString("defaultBranch", "main"),
            remoteUrl = json.optString("remoteUrl")
        )
    }
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("igit_preferences", Context.MODE_PRIVATE)

    fun saveRepo(repo: RepoConfig) = prefs.edit().putString(KEY_REPO, repo.toJson().toString()).apply()

    fun getRepo(): RepoConfig? = runCatching {
        prefs.getString(KEY_REPO, null)?.let { RepoConfig.fromJson(JSONObject(it)) }
    }.getOrNull()?.takeIf { it.owner.isNotBlank() && it.name.isNotBlank() }

    fun saveFolderUri(uri: String?) = prefs.edit().putString(KEY_FOLDER, uri).apply()
    fun getFolderUri(): String? = prefs.getString(KEY_FOLDER, null)

    fun setScheduledSync(enabled: Boolean) = prefs.edit().putBoolean(KEY_SCHEDULED, enabled).apply()
    fun isScheduledSyncEnabled(): Boolean = prefs.getBoolean(KEY_SCHEDULED, false)

    fun setSyncIntervalMinutes(minutes: Long) = prefs.edit().putLong(KEY_INTERVAL, minutes.coerceIn(15, 10080)).apply()
    fun getSyncIntervalMinutes(): Long = prefs.getLong(KEY_INTERVAL, 60L)

    fun setAiBaseUrl(value: String) = prefs.edit().putString(KEY_AI_BASE_URL, value.trim().trimEnd('/')).apply()
    fun getAiBaseUrl(): String = prefs.getString(KEY_AI_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"

    fun setAiModel(value: String) = prefs.edit().putString(KEY_AI_MODEL, value.trim()).apply()
    fun getAiModel(): String = prefs.getString(KEY_AI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"

    fun setAiEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()
    fun isAiEnabled(): Boolean = prefs.getBoolean(KEY_AI_ENABLED, true)

    private companion object {
        const val KEY_REPO = "repo_config"
        const val KEY_FOLDER = "folder_uri"
        const val KEY_SCHEDULED = "scheduled_sync"
        const val KEY_INTERVAL = "sync_interval_minutes"
        const val KEY_AI_BASE_URL = "ai_base_url"
        const val KEY_AI_MODEL = "ai_model"
        const val KEY_AI_ENABLED = "ai_enabled"
    }
}
