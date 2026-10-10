package app.igit.data

import android.content.Context
import app.igit.security.SecretVault
import org.json.JSONArray
import org.json.JSONObject

/** Secret names inside [SecretVault]. Secrets never leave the Keystore-encrypted vault except via encrypted backup. */
object Secrets {
    fun token(id: String) = "c.$id.token"
    fun sshKey(id: String) = "c.$id.ssh_private"
    fun sshPublic(id: String) = "c.$id.ssh_public"
    fun sshPassphrase(id: String) = "c.$id.ssh_passphrase"
    fun signingKey(id: String) = "c.$id.signing_private"
    fun githubToken(id: String) = "c.$id.github_token"
    const val AI_KEY = "ai_api_key"

    fun forContainer(id: String) = listOf(token(id), sshKey(id), sshPublic(id), sshPassphrase(id), signingKey(id), githubToken(id))
}

/** Persists repository containers and app-wide settings. */
class ContainerStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("igit_containers", Context.MODE_PRIVATE)
    private val vault = SecretVault(context)

    init { migrateLegacy() }

    fun containers(): List<RepoContainer> = runCatching {
        val array = JSONArray(prefs.getString(KEY_CONTAINERS, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(RepoContainer::fromJson) }
    }.getOrDefault(emptyList()).ifEmpty {
        val first = RepoContainer()
        saveAll(listOf(first))
        listOf(first)
    }

    fun saveAll(list: List<RepoContainer>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CONTAINERS, array.toString()).apply()
    }

    fun get(id: String?): RepoContainer? = containers().firstOrNull { it.id == id }

    fun byIndex(index: Int): RepoContainer? = containers().getOrNull(index)

    fun upsert(container: RepoContainer) {
        val list = containers().toMutableList()
        val index = list.indexOfFirst { it.id == container.id }
        if (index >= 0) list[index] = container else list += container
        saveAll(list)
    }

    fun remove(id: String) {
        saveAll(containers().filterNot { it.id == id }.ifEmpty { listOf(RepoContainer()) })
        Secrets.forContainer(id).forEach(vault::remove)
        if (activeId() == id) setActiveId(containers().first().id)
        QuickSyncTarget.entries.forEach { if (quickTarget(it) == id) setQuickTarget(it, null) }
    }

    fun activeId(): String = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> containers().any { it.id == id } }
        ?: containers().first().id

    fun setActiveId(id: String) = prefs.edit().putString(KEY_ACTIVE, id).apply()

    fun active(): RepoContainer = get(activeId()) ?: containers().first()

    fun quickTarget(target: QuickSyncTarget): String? = prefs.getString("quick_${target.key}", null)
    fun setQuickTarget(target: QuickSyncTarget, id: String?) = prefs.edit().putString("quick_${target.key}", id).apply()
    /** Falls back to the active container when no explicit target is set, like GitSync's single-container default. */
    fun resolveQuickTarget(target: QuickSyncTarget): RepoContainer? = get(quickTarget(target)) ?: active()

    // App-wide (global) settings
    var aiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", true)
        set(value) = prefs.edit().putBoolean("ai_enabled", value).apply()
    var aiBaseUrl: String
        get() = prefs.getString("ai_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString("ai_base_url", value.trim().trimEnd('/')).apply()
    var aiChatModel: String
        get() = prefs.getString("ai_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString("ai_model", value.trim()).apply()
    var aiWandModel: String
        get() = prefs.getString("ai_wand_model", null)?.takeIf { it.isNotBlank() } ?: aiChatModel
        set(value) = prefs.edit().putString("ai_wand_model", value.trim()).apply()
    var lineWrap: Boolean
        get() = prefs.getBoolean("line_wrap", true)
        set(value) = prefs.edit().putBoolean("line_wrap", value).apply()
    var accessibilityDisclosureAccepted: Boolean
        get() = prefs.getBoolean("a11y_disclosure", false)
        set(value) = prefs.edit().putBoolean("a11y_disclosure", value).apply()

    /** Snapshot for encrypted backup, including secrets. */
    fun exportJson(): JSONObject {
        val list = containers()
        val secrets = JSONObject()
        list.forEach { c -> Secrets.forContainer(c.id).forEach { name -> vault.get(name)?.let { secrets.put(name, it) } } }
        vault.get(Secrets.AI_KEY)?.let { secrets.put(Secrets.AI_KEY, it) }
        val quick = JSONObject()
        QuickSyncTarget.entries.forEach { t -> quickTarget(t)?.let { quick.put(t.key, it) } }
        return JSONObject()
            .put("version", 1)
            .put("containers", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } })
            .put("active", activeId())
            .put("quick", quick)
            .put("global", JSONObject().put("aiEnabled", aiEnabled).put("aiBaseUrl", aiBaseUrl)
                .put("aiModel", aiChatModel).put("aiWandModel", aiWandModel).put("lineWrap", lineWrap))
            .put("secrets", secrets)
    }

    fun importJson(json: JSONObject) {
        val array = json.optJSONArray("containers") ?: error("Backup has no containers.")
        val list = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(RepoContainer::fromJson) }
        require(list.isNotEmpty()) { "Backup has no containers." }
        saveAll(list)
        json.optString("active").takeIf { id -> list.any { it.id == id } }?.let(::setActiveId)
        json.optJSONObject("quick")?.let { q -> QuickSyncTarget.entries.forEach { t -> setQuickTarget(t, q.optString(t.key).ifBlank { null }) } }
        json.optJSONObject("global")?.let { g ->
            aiEnabled = g.optBoolean("aiEnabled", true)
            aiBaseUrl = g.optString("aiBaseUrl", aiBaseUrl)
            aiChatModel = g.optString("aiModel", aiChatModel)
            aiWandModel = g.optString("aiWandModel", aiWandModel)
            lineWrap = g.optBoolean("lineWrap", true)
        }
        json.optJSONObject("secrets")?.let { s ->
            val keys = s.keys()
            while (keys.hasNext()) { val k = keys.next(); vault.put(k, s.optString(k)) }
        }
    }

    /** Converts the single-repository settings used by igit ≤ 0.3 into the first container. */
    private fun migrateLegacy() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacy = context.getSharedPreferences("igit_preferences", Context.MODE_PRIVATE)
        val repoJson = legacy.getString("repo_config", null)
        if (repoJson != null && prefs.getString(KEY_CONTAINERS, null) == null) {
            runCatching {
                val repo = JSONObject(repoJson)
                val owner = repo.optString("owner")
                val name = repo.optString("name")
                val legacyId = "legacy01"
                val container = RepoContainer(
                    id = legacyId,
                    alias = name.ifBlank { "alias" },
                    remoteUrl = repo.optString("remoteUrl").ifBlank { "https://github.com/$owner/$name.git" },
                    folderUri = legacy.getString("folder_uri", null),
                    authType = AuthType.HTTPS,
                    username = "x-access-token",
                    scheduledSync = legacy.getBoolean("scheduled_sync", false),
                    scheduledMinutes = legacy.getLong("sync_interval_minutes", 60L)
                )
                saveAll(listOf(container))
                setActiveId(legacyId)
                vault.get("github_token")?.let {
                    vault.put(Secrets.token(legacyId), it)
                    vault.put(Secrets.githubToken(legacyId), it)
                    vault.remove("github_token")
                }
                // Old worktree folder used the owner-name key; keep using it so no re-clone is needed.
                val oldKey = "${owner.lowercase()}-${name.lowercase()}".replace(Regex("[^a-z0-9._-]"), "-")
                val oldRoot = java.io.File(context.filesDir, "worktrees/$oldKey")
                val newRoot = java.io.File(context.filesDir, "worktrees/$legacyId")
                if (oldRoot.exists() && !newRoot.exists()) {
                    oldRoot.renameTo(newRoot)
                    java.io.File(context.filesDir, "worktrees/$oldKey.mirror.json").renameTo(java.io.File(context.filesDir, "worktrees/$legacyId.mirror.json"))
                }
            }
        }
        aiBaseUrl = legacy.getString("ai_base_url", aiBaseUrl) ?: aiBaseUrl
        aiChatModel = legacy.getString("ai_model", aiChatModel) ?: aiChatModel
        aiEnabled = legacy.getBoolean("ai_enabled", true)
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private companion object {
        const val KEY_CONTAINERS = "containers"
        const val KEY_ACTIVE = "active_container"
        const val KEY_MIGRATED = "migrated_v1"
    }
}
