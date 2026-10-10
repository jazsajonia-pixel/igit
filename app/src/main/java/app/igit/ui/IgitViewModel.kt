package app.igit.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.igit.data.AiClient
import app.igit.data.AuthType
import app.igit.data.Backup
import app.igit.data.BranchList
import app.igit.data.ChangedFile
import app.igit.data.CommitInfo
import app.igit.data.QuickSyncTarget
import app.igit.data.RemoteInfo
import app.igit.data.RepoContainer
import app.igit.data.RepoMode
import app.igit.data.Secrets
import app.igit.data.Validation
import app.igit.git.SafFolderMirror
import app.igit.git.SshKeys
import app.igit.git.SyncOutcome
import app.igit.sync.SyncRunner
import app.igit.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface Screen {
    data object Main : Screen
    data object RepoSettings : Screen
    data object GlobalSettings : Screen
    data object Auth : Screen
    data object Clone : Screen
    data object Conflicts : Screen
    data object Actions : Screen
    data class CommitDetail(val commit: CommitInfo) : Screen
    data class Tickets(val pullRequests: Boolean) : Screen
    data class TicketDetail(val number: Int, val pullRequest: Boolean) : Screen
    data class CreateTicket(val pullRequest: Boolean) : Screen
}

enum class AppTab(val title: String) { CHAT("Chat"), HOME("Home"), FILES("Files") }

class IgitViewModel(app: Application) : AndroidViewModel(app) {
    val runner = SyncRunner(app)
    private val store get() = runner.store
    private val engine get() = runner.engine

    var containers by mutableStateOf(store.containers()); private set
    var active by mutableStateOf(store.active()); private set
    var cloned by mutableStateOf(false); private set
    var branches by mutableStateOf(BranchList(null, emptyList(), emptyList(), false)); private set
    var commits by mutableStateOf(emptyList<CommitInfo>()); private set
    var changes by mutableStateOf(emptyList<ChangedFile>()); private set
    var remotes by mutableStateOf(emptyList<RemoteInfo>()); private set
    var conflicts by mutableStateOf(emptyList<String>()); private set
    var merging by mutableStateOf(false); private set
    var operation by mutableStateOf<String?>(null); private set
    var lastResult by mutableStateOf<String?>(null); private set
    var lastError by mutableStateOf<String?>(null); private set
    var folderName by mutableStateOf<String?>(null); private set
    var tab by mutableStateOf(AppTab.HOME)
    val backStack = mutableStateListOf<Screen>()
    var message by mutableStateOf<String?>(null)
    var showManualSync by mutableStateOf(false)
    var aiEnabled by mutableStateOf(store.aiEnabled); private set
    var aiKeyStored by mutableStateOf(!runner.vault.get(Secrets.AI_KEY).isNullOrBlank()); private set

    val screen: Screen get() = backStack.lastOrNull() ?: Screen.Main
    val busy: Boolean get() = operation != null
    val gitHubRepo: Pair<String, String>? get() = Validation.gitHubRepo(remotes.firstOrNull { it.name == active.defaultRemote }?.url ?: active.remoteUrl)
    val gitHubToken: String? get() = runner.gitHubToken(active)
    val hasAuth: Boolean get() = when (active.authType) {
        AuthType.HTTPS -> !runner.vault.get(Secrets.token(active.id)).isNullOrBlank()
        AuthType.SSH -> !runner.vault.get(Secrets.sshKey(active.id)).isNullOrBlank()
        AuthType.NONE -> false
    }

    init { refresh() }

    fun navigate(screen: Screen) { backStack += screen }
    fun back(): Boolean = if (backStack.isNotEmpty()) { backStack.removeAt(backStack.lastIndex); true } else false

    fun toast(text: String) { message = text }

    /** Handles launches from tiles, widgets, shortcuts and notifications. */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CONTAINER)?.let { id -> if (store.get(id) != null) selectContainer(id) }
        when (intent.action) {
            ACTION_MANUAL_SYNC -> { tab = AppTab.HOME; backStack.clear(); showManualSync = true; loadChanges() }
            ACTION_MANUAL_SYNC_SHORTCUT -> {
                store.resolveQuickTarget(QuickSyncTarget.SHORTCUT_MANUAL)?.let { selectContainer(it.id) }
                tab = AppTab.HOME; backStack.clear(); showManualSync = true; loadChanges()
            }
            ACTION_RESOLVE_CONFLICTS -> { tab = AppTab.HOME; backStack.clear(); navigate(Screen.Conflicts) }
        }
    }

    // ------------------------------------------------------------ containers

    fun refresh() {
        containers = store.containers()
        active = store.active()
        folderName = active.folderUri?.let { runCatching { DocumentFile.fromTreeUri(getApplication(), Uri.parse(it))?.name }.getOrNull() ?: "Selected folder" }
        cloned = engine.isCloned(active)
        merging = engine.isMerging(active)
        viewModelScope.launch {
            val c = active
            runCatching {
                branches = engine.branches(c)
                commits = engine.log(c)
                remotes = engine.remotes(c)
                conflicts = engine.conflicts(c)
            }.onFailure { lastError = it.message }
            if (c.mode == RepoMode.CLIENT && cloned) loadChanges()
        }
    }

    fun selectContainer(id: String) {
        store.setActiveId(id)
        changes = emptyList(); commits = emptyList(); lastResult = null; lastError = null
        refresh()
    }

    fun addContainer(alias: String) {
        val container = RepoContainer(alias = alias.ifBlank { "alias" })
        store.upsert(container)
        selectContainer(container.id)
    }

    fun renameContainer(alias: String) = update { it.copy(alias = alias.ifBlank { it.alias }) }

    fun deleteContainer(deleteLocalData: Boolean) {
        val c = active
        SyncScheduler.cancel(getApplication(), c.id)
        if (deleteLocalData) engine.deleteLocalData(c)
        store.remove(c.id)
        refresh()
    }

    fun update(transform: (RepoContainer) -> RepoContainer) {
        val updated = transform(store.active())
        store.upsert(updated)
        SyncScheduler.schedule(getApplication(), updated)
        containers = store.containers()
        active = updated
    }

    fun setFolder(uri: Uri?) {
        update { it.copy(folderUri = uri?.toString()) }
        refresh()
    }

    fun setMode(mode: RepoMode) { update { it.copy(mode = mode) }; if (mode == RepoMode.CLIENT) loadChanges() }

    fun quickTarget(target: QuickSyncTarget): Boolean = (store.quickTarget(target) ?: store.activeId()) == active.id
    fun setQuickTarget(target: QuickSyncTarget, on: Boolean) {
        store.setQuickTarget(target, if (on) active.id else null)
        containers = store.containers()
    }

    // ------------------------------------------------------------ auth

    fun saveHttpsAuth(username: String, token: String) {
        runner.vault.put(Secrets.token(active.id), token.trim())
        update { it.copy(authType = AuthType.HTTPS, username = username.trim()) }
        toast("Authenticated with HTTP/S")
    }

    fun saveGitHubToken(token: String) {
        runner.vault.put(Secrets.githubToken(active.id), token.trim())
        toast(if (token.isBlank()) "GitHub token removed" else "GitHub provider tools enabled")
    }

    fun generateSshKey(): String {
        val pair = SshKeys.generateEd25519("igit-${active.alias}")
        runner.vault.put(Secrets.sshKey(active.id), pair.privateKeyPem)
        runner.vault.put(Secrets.sshPublic(active.id), pair.publicKeyLine)
        runner.vault.remove(Secrets.sshPassphrase(active.id))
        update { it.copy(authType = AuthType.SSH) }
        return pair.publicKeyLine
    }

    fun importSshKey(privateKey: String, passphrase: String): Result<String> = runCatching {
        val public = SshKeys.publicKeyFor(privateKey.trim() + "\n", passphrase.ifBlank { null })
        runner.vault.put(Secrets.sshKey(active.id), privateKey.trim() + "\n")
        runner.vault.put(Secrets.sshPublic(active.id), public)
        runner.vault.put(Secrets.sshPassphrase(active.id), passphrase)
        update { it.copy(authType = AuthType.SSH) }
        public
    }

    fun sshPublicKey(): String? = runner.vault.get(Secrets.sshPublic(active.id))
    fun sshPrivateKey(): String? = runner.vault.get(Secrets.sshKey(active.id))
    fun httpsUser(): String = active.username
    fun hasHttpsToken(): Boolean = !runner.vault.get(Secrets.token(active.id)).isNullOrBlank()
    fun hasGitHubToken(): Boolean = !runner.vault.get(Secrets.githubToken(active.id)).isNullOrBlank()

    fun copyAuthFrom(id: String) {
        val source = store.get(id) ?: return
        Secrets.forContainer(source.id).zip(Secrets.forContainer(active.id)).forEach { (from, to) ->
            runner.vault.get(from)?.let { runner.vault.put(to, it) } ?: runner.vault.remove(to)
        }
        update { it.copy(authType = source.authType, username = source.username) }
        toast("Copied auth from ${source.alias}")
    }

    fun importSigningKey(privateKey: String): Result<Unit> = runCatching {
        SshKeys.ed25519Signer(privateKey.trim() + "\n")
        runner.vault.put(Secrets.signingKey(active.id), privateKey.trim() + "\n")
        update { it.copy(signingUsesAuthKey = false, signedCommits = true) }
    }

    fun hasSigningKey(): Boolean = !runner.vault.get(Secrets.signingKey(active.id)).isNullOrBlank()

    fun listGitHubRepos(token: String, onResult: (Result<List<app.igit.data.GitHubRepo>>) -> Unit) {
        viewModelScope.launch {
            onResult(withContext(Dispatchers.IO) { runCatching { app.igit.data.GitHubApi(token.trim()).listRepos() } })
        }
    }

    // ------------------------------------------------------------ clone

    fun folderIsEmpty(onResult: (Boolean) -> Unit) {
        val uri = active.folderUri?.let(Uri::parse) ?: return onResult(true)
        viewModelScope.launch { onResult(runCatching { SafFolderMirror(getApplication()).isEmpty(uri) }.getOrDefault(true)) }
    }

    fun clone(url: String, depth: Int) {
        if (!Validation.isValidRemoteUrl(url)) { lastError = "Invalid repository URL!"; toast("Invalid repository URL!"); return }
        val alias = if (active.alias == "alias" || active.alias.isBlank()) Validation.repoNameFromUrl(url) else active.alias
        val needed = Validation.authFor(url)
        update { it.copy(remoteUrl = url.trim(), cloneDepth = depth.coerceAtLeast(0), alias = alias) }
        if (active.authType != AuthType.NONE && active.authType != needed) {
            toast(if (needed == AuthType.SSH) "This remote uses SSH. Switch AUTH to SSH." else "This remote uses HTTPS. Switch AUTH to HTTP/S.")
        }
        run("Cloning repository…") { engine.clone(active, runner.auth(active)) { p -> operation = p }; "Repository cloned" }
    }

    fun initLocalRepository(remoteUrl: String) {
        update { it.copy(remoteUrl = remoteUrl.trim()) }
        run("Creating repository…") { engine.initRepository(active); "Local repository created" }
    }

    // ------------------------------------------------------------ sync operations

    fun syncNow() {
        if (conflicts.isNotEmpty() || merging) { navigate(Screen.Conflicts); return }
        runSync("Detecting changes…") { runner.sync(active) { p -> operation = p } }
    }

    fun downloadChanges() = runSync("Syncing changes…") { engine.downloadChanges(active, runner.auth(active)) }
    fun uploadChanges() = runSync("Syncing local changes…") { engine.uploadChanges(active, runner.auth(active), runner.signer(active)) }
    fun forcePull() = runSync("Force pulling…") { engine.forcePull(active, runner.auth(active)) }
    fun forcePush() = runSync("Force pushing…") { engine.forcePush(active, runner.auth(active), runner.signer(active)) }
    fun fetchRemote() = run("Fetching…") { engine.fetchRemote(active, runner.auth(active)) }
    fun pullChanges() = runSync("Pulling…") { engine.pullChanges(active, runner.auth(active)) }
    fun pushChanges() = run("Pushing…") { engine.pushChanges(active, runner.auth(active)) }

    fun manualSync(paths: Collection<String>, message: String) {
        showManualSync = false
        runSync("Syncing selected files…") { engine.manualSync(active, runner.auth(active), runner.signer(active), paths, message) }
    }

    fun loadChanges() {
        if (!engine.isCloned(active)) return
        viewModelScope.launch {
            runCatching { engine.status(active) }.onSuccess { changes = it }.onFailure { lastError = it.message }
        }
    }

    fun stage(paths: Collection<String>) = quiet { engine.stage(active, paths) }
    fun unstage(paths: Collection<String>) = quiet { engine.unstage(active, paths) }
    fun discard(paths: Collection<String>) = run("Discarding…") { engine.discard(active, paths); "Changes discarded" }
    fun commit(message: String) = run("Committing…") { "Committed ${engine.commit(active, message, runner.signer(active))}" }

    // ------------------------------------------------------------ branches & remotes

    fun checkout(branch: String) = run("Checking out…") { "Checked out ${engine.checkout(active, branch)}" }
    fun createBranch(name: String, basedOn: String?) = run("Creating branch…") { "Switched to ${engine.createBranch(active, name, basedOn)}" }
    fun renameBranch(from: String, to: String) = run("Renaming branch…") { engine.renameBranch(active, from, to); "Branch renamed" }
    fun deleteBranch(name: String, remote: Boolean) = run("Deleting branch…") { engine.deleteBranch(active, name, runner.auth(active), remote); "Branch deleted" }
    fun addRemote(name: String, url: String) = run("Adding remote…") { engine.addRemote(active, name, url); "Remote added" }
    fun setRemoteUrl(name: String, url: String) = run("Updating remote…") {
        engine.setRemoteUrl(active, name, url)
        if (name == active.defaultRemote) update { it.copy(remoteUrl = url.trim()) }
        "Remote URL updated"
    }
    fun deleteRemote(name: String) = run("Deleting remote…") { engine.deleteRemote(active, name); "Remote deleted" }
    fun renameRemote(from: String, to: String) = run("Renaming remote…") {
        engine.renameRemote(active, from, to)
        if (active.defaultRemote == from) update { it.copy(defaultRemote = to) }
        "Remote renamed"
    }

    // ------------------------------------------------------------ conflicts

    suspend fun readFile(path: String): String = engine.readFile(active, path)
    fun resolveFile(path: String, content: String, onDone: () -> Unit) = run("Resolving…") {
        engine.resolveFile(active, path, content)
        withContext(Dispatchers.Main) { onDone() }
        "Resolved $path"
    }
    fun finishMerge() = run("Merging…") {
        engine.finishMerge(active, runner.signer(active))
        val outcome = runner.sync(active)
        withContext(Dispatchers.Main) { if (outcome.conflicts.isEmpty() && backStack.lastOrNull() == Screen.Conflicts) back() }
        outcome.message
    }
    fun abortMerge() = run("Aborting merge…") {
        engine.abortMerge(active)
        withContext(Dispatchers.Main) { if (backStack.lastOrNull() == Screen.Conflicts) back() }
        "Merge aborted"
    }

    // ------------------------------------------------------------ repository files

    suspend fun readIgnore(local: Boolean): String = runCatching { engine.readIgnore(active, local) }.getOrDefault("")
    fun writeIgnore(local: Boolean, text: String) = run("Saving…") { engine.writeIgnore(active, local, text); if (local) ".git/info/exclude saved" else ".gitignore saved" }
    fun ignoreAndUntrack(paths: Collection<String>, local: Boolean, untrack: Boolean) =
        run("Updating…") { engine.ignoreAndUntrack(active, paths, local, untrack); "Updated ${if (local) ".git/info/exclude" else ".gitignore"}" }

    suspend fun commitDiff(sha: String) = engine.commitDiff(active, sha)

    // ------------------------------------------------------------ AI

    fun saveAiSettings(enabled: Boolean, baseUrl: String, chatModel: String, wandModel: String, key: String) {
        require(Validation.isValidAiBaseUrl(baseUrl)) { "Enter a valid HTTPS API base URL." }
        store.aiEnabled = enabled; store.aiBaseUrl = baseUrl; store.aiChatModel = chatModel; store.aiWandModel = wandModel
        if (key.isNotBlank()) runner.vault.put(Secrets.AI_KEY, key.trim())
        aiEnabled = enabled
        aiKeyStored = !runner.vault.get(Secrets.AI_KEY).isNullOrBlank()
    }

    fun aiSettings() = listOf(store.aiBaseUrl, store.aiChatModel, store.aiWandModel)
    fun aiClient(chat: Boolean = true) = AiClient(store.aiBaseUrl, runner.vault.get(Secrets.AI_KEY).orEmpty(), if (chat) store.aiChatModel else store.aiWandModel)

    /** Wand: drafts a commit message from the current uncommitted changes. */
    fun wandCommitMessage(draft: String, onResult: (String) -> Unit) {
        if (!aiEnabled || !aiKeyStored) { toast("Set up AI in Global Settings to use the wand."); return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val summary = engine.changesSummary(active).ifBlank { changes.joinToString("\n") { "${it.kind} ${it.path}" } }
                    aiClient(chat = false).commitMessage(summary, draft)
                }
            }
            result.onSuccess(onResult).onFailure { toast(it.message ?: "AI request failed") }
        }
    }

    var lineWrap: Boolean
        get() = store.lineWrap
        set(value) { store.lineWrap = value }

    var a11yDisclosureAccepted: Boolean
        get() = store.accessibilityDisclosureAccepted
        set(value) { store.accessibilityDisclosureAccepted = value }

    // ------------------------------------------------------------ backup

    fun backup(uri: Uri, password: String) = run("Backing up…") {
        val text = Backup.encrypt(store.exportJson(), password)
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("Could not write backup")
        "Encrypted backup saved"
    }

    fun restore(uri: Uri, password: String) = run("Restoring…") {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Could not read backup")
        val json: JSONObject = Backup.decrypt(text, password)
        store.importJson(json)
        SyncScheduler.rescheduleAll(getApplication())
        withContext(Dispatchers.Main) { aiEnabled = store.aiEnabled; aiKeyStored = !runner.vault.get(Secrets.AI_KEY).isNullOrBlank() }
        "Configuration restored"
    }

    // ------------------------------------------------------------ plumbing

    private fun runSync(label: String, block: suspend () -> SyncOutcome) {
        if (!cloned) { toast("Clone a repository first."); return }
        run(label) {
            val outcome = block()
            if (outcome.conflicts.isNotEmpty()) {
                withContext(Dispatchers.Main) { conflicts = outcome.conflicts; navigate(Screen.Conflicts) }
                "Merge conflicts need attention"
            } else outcome.message
        }
    }

    private fun run(label: String, block: suspend () -> String) {
        if (busy) { toast("Sync In Progress"); return }
        operation = label
        lastError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            operation = null
            result.onSuccess { lastResult = it; if (active.showSyncMessages) toast(it) }
                .onFailure { lastError = it.message ?: it.javaClass.simpleName; toast(lastError!!) }
            refresh()
        }
    }

    private fun quiet(block: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { block() } }.onFailure { toast(it.message ?: "Failed") }
            loadChanges()
        }
    }

    companion object {
        const val ACTION_MANUAL_SYNC = "app.igit.action.MANUAL_SYNC"
        const val ACTION_MANUAL_SYNC_SHORTCUT = "app.igit.action.MANUAL_SYNC_SHORTCUT"
        const val ACTION_RESOLVE_CONFLICTS = "app.igit.action.RESOLVE_CONFLICTS"
        const val EXTRA_CONTAINER = "container"
    }
}
