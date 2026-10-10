package app.igit.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How a container authenticates against its remote. Mirrors GitSync's HTTP/S and SSH protocols. */
enum class AuthType { NONE, HTTPS, SSH }

/** GitSync's two in-app experiences: automated syncing vs. an expanded Git client UI. */
enum class RepoMode { SYNC, CLIENT }

const val DEFAULT_SYNC_MESSAGE = "Last Sync: %s (Mobile)"
const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"

/**
 * A repository container: one remote + one local folder + its own auth, sync triggers and
 * repository settings. GitSync uses the same concept to manage multiple repositories.
 */
data class RepoContainer(
    val id: String = UUID.randomUUID().toString().take(8),
    val alias: String = "alias",
    val remoteUrl: String = "",
    val cloneDepth: Int = 0,
    val folderUri: String? = null,
    val authType: AuthType = AuthType.NONE,
    val username: String = "",
    val mode: RepoMode = RepoMode.SYNC,
    // Repository settings
    val showSyncMessages: Boolean = true,
    val signedCommits: Boolean = false,
    val signingUsesAuthKey: Boolean = true,
    val syncMessage: String = DEFAULT_SYNC_MESSAGE,
    val syncMessageTimeFormat: String = DEFAULT_TIME_FORMAT,
    val authorName: String = "",
    val authorEmail: String = "",
    val defaultRemote: String = "origin",
    val disableSsl: Boolean = false,
    val optimisedSync: Boolean = true,
    val postFooter: String = "",
    // Auto sync settings
    val appSyncPackages: List<String> = emptyList(),
    val syncOnAppOpened: Boolean = false,
    val syncOnAppClosed: Boolean = false,
    val scheduledSync: Boolean = false,
    val scheduledMinutes: Long = 60
) {
    val hasRemote: Boolean get() = remoteUrl.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("alias", alias).put("remoteUrl", remoteUrl).put("cloneDepth", cloneDepth)
        .put("folderUri", folderUri ?: JSONObject.NULL).put("authType", authType.name).put("username", username)
        .put("mode", mode.name).put("showSyncMessages", showSyncMessages).put("signedCommits", signedCommits)
        .put("signingUsesAuthKey", signingUsesAuthKey).put("syncMessage", syncMessage)
        .put("syncMessageTimeFormat", syncMessageTimeFormat).put("authorName", authorName)
        .put("authorEmail", authorEmail).put("defaultRemote", defaultRemote).put("disableSsl", disableSsl)
        .put("optimisedSync", optimisedSync).put("postFooter", postFooter)
        .put("appSyncPackages", JSONArray(appSyncPackages)).put("syncOnAppOpened", syncOnAppOpened)
        .put("syncOnAppClosed", syncOnAppClosed).put("scheduledSync", scheduledSync)
        .put("scheduledMinutes", scheduledMinutes)

    companion object {
        fun fromJson(json: JSONObject): RepoContainer {
            val packages = json.optJSONArray("appSyncPackages")
            return RepoContainer(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                alias = json.optString("alias", "alias"),
                remoteUrl = json.optString("remoteUrl"),
                cloneDepth = json.optInt("cloneDepth", 0),
                folderUri = json.optString("folderUri").takeIf { it.isNotBlank() && it != "null" },
                authType = runCatching { AuthType.valueOf(json.optString("authType")) }.getOrDefault(AuthType.NONE),
                username = json.optString("username"),
                mode = runCatching { RepoMode.valueOf(json.optString("mode")) }.getOrDefault(RepoMode.SYNC),
                showSyncMessages = json.optBoolean("showSyncMessages", true),
                signedCommits = json.optBoolean("signedCommits", false),
                signingUsesAuthKey = json.optBoolean("signingUsesAuthKey", true),
                syncMessage = json.optString("syncMessage", DEFAULT_SYNC_MESSAGE),
                syncMessageTimeFormat = json.optString("syncMessageTimeFormat", DEFAULT_TIME_FORMAT),
                authorName = json.optString("authorName"),
                authorEmail = json.optString("authorEmail"),
                defaultRemote = json.optString("defaultRemote", "origin").ifBlank { "origin" },
                disableSsl = json.optBoolean("disableSsl", false),
                optimisedSync = json.optBoolean("optimisedSync", true),
                postFooter = json.optString("postFooter"),
                appSyncPackages = (0 until (packages?.length() ?: 0)).mapNotNull { packages?.optString(it)?.takeIf(String::isNotBlank) },
                syncOnAppOpened = json.optBoolean("syncOnAppOpened", false),
                syncOnAppClosed = json.optBoolean("syncOnAppClosed", false),
                scheduledSync = json.optBoolean("scheduledSync", false),
                scheduledMinutes = json.optLong("scheduledMinutes", 60).coerceIn(15, 10080)
            )
        }
    }
}

/** Which container each quick-sync surface targets (GitSync's "Use for Sync Tile" etc.). */
enum class QuickSyncTarget(val key: String, val label: String) {
    TILE_SYNC("tile_sync", "Use for Sync Tile"),
    TILE_MANUAL("tile_manual", "Use for Manual Sync Tile"),
    SHORTCUT_SYNC("shortcut_sync", "Use for Sync Shortcut"),
    SHORTCUT_MANUAL("shortcut_manual", "Use for Manual Sync Shortcut"),
    WIDGET_SYNC("widget_sync", "Use for Sync Widget"),
    WIDGET_MANUAL("widget_manual", "Use for Manual Sync Widget")
}

data class CommitInfo(
    val sha: String,
    val message: String,
    val fullMessage: String,
    val author: String,
    val timeMillis: Long,
    val additions: Int,
    val deletions: Int,
    val filesChanged: Int,
    val pushed: Boolean = true
) {
    val shortSha: String get() = sha.take(7)
}

enum class ChangeKind { ADDED, MODIFIED, DELETED, UNTRACKED, CONFLICT }

data class ChangedFile(val path: String, val kind: ChangeKind, val staged: Boolean)

data class FileDiff(val path: String, val changeType: String, val patch: String, val additions: Int, val deletions: Int)

data class RemoteInfo(val name: String, val url: String)

data class BranchList(val current: String?, val local: List<String>, val remote: List<String>, val detached: Boolean) {
    val all: List<String> get() = (local + remote).distinct()
}
