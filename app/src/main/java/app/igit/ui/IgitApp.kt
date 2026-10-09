package app.igit.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.igit.data.AppPreferences
import app.igit.data.CommitSummary
import app.igit.data.GitHubApi
import app.igit.data.GitHubRepo
import app.igit.data.GitHubTicket
import app.igit.data.RepoConfig
import app.igit.data.Validation
import app.igit.git.GitSyncEngine
import app.igit.security.SecretVault
import app.igit.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Composable
fun IgitApp(context: Context, selectedFolder: Uri?, onPickFolder: () -> Unit) {
    val prefs = remember(context) { AppPreferences(context) }
    val vault = remember(context) { SecretVault(context) }
    val scope = rememberCoroutineScope()
    var realRepo by remember { mutableStateOf(prefs.getRepo() != null) }
    var repo by remember { mutableStateOf(prefs.getRepo() ?: RepoConfig("demo", "field-notes", "main", "https://github.com/demo/field-notes.git")) }
    var githubToken by remember { mutableStateOf(vault.get(SyncWorker.GITHUB_TOKEN).orEmpty()) }
    var aiEnabled by remember { mutableStateOf(prefs.isAiEnabled()) }
    var aiKeyStored by remember { mutableStateOf(!vault.get(SyncWorker.AI_API_KEY).isNullOrBlank()) }
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var branch by remember { mutableStateOf(repo.defaultBranch) }
    var commits by remember { mutableStateOf(if (realRepo) emptyList() else sampleCommits) }
    var issues by remember { mutableStateOf(if (realRepo) emptyList() else sampleIssues) }
    var pulls by remember { mutableStateOf(if (realRepo) emptyList() else samplePulls) }
    var syncing by remember { mutableStateOf(false) }
    var panelLoading by remember { mutableStateOf(false) }
    var panelError by remember { mutableStateOf<String?>(null) }
    var showConnect by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBranch by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var syncConflicts by remember { mutableStateOf(emptyList<String>()) }
    var showTickets by remember { mutableStateOf(false) }
    var showCreateRecord by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var selectedPanel by remember { mutableStateOf(RepositoryPanel.ISSUES) }
    var connectionLoading by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var availableRepos by remember { mutableStateOf(emptyList<GitHubRepo>()) }
    var branches by remember { mutableStateOf(listOf(branch)) }
    var branchLoading by remember { mutableStateOf(false) }
    var branchError by remember { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf(context.getSharedPreferences("igit_preferences", Context.MODE_PRIVATE).getString("git_filters", "*.tmp\n.DS_Store").orEmpty()) }

    val folderName = remember(selectedFolder) {
        selectedFolder?.let { DocumentFile.fromTreeUri(context, it)?.name ?: "Selected folder" }
    }
    val connected = realRepo && githubToken.isNotBlank()

    suspend fun refreshRepository() {
        if (!connected) return
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val api = GitHubApi(githubToken)
                val newCommits = api.commits(repo.owner, repo.name, branch)
                val newIssues = api.issues(repo.owner, repo.name)
                val newPulls = api.pullRequests(repo.owner, repo.name)
                Triple(newCommits, newIssues, newPulls)
            }
        }
        result.onSuccess { (newCommits, newIssues, newPulls) ->
            commits = newCommits
            issues = newIssues
            pulls = newPulls
        }.onFailure { showToast(context, it.message ?: "Could not refresh repository data.") }
    }

    LaunchedEffect(connected, repo.fullName, branch) {
        if (connected) refreshRepository()
    }

    fun loadRepositories(token: String) {
        connectionLoading = true
        connectionError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { GitHubApi(token.trim()).listRepos() } }
            result.onSuccess { availableRepos = it.ifEmpty { emptyList() } }
                .onFailure { connectionError = it.message ?: "Could not load repositories." }
            connectionLoading = false
        }
    }

    fun scheduleSync(enabled: Boolean, interval: Long, credentialsAvailable: Boolean = connected) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC_SYNC_NAME)
        prefs.setScheduledSync(enabled)
        prefs.setSyncIntervalMinutes(interval)
        if (enabled && credentialsAvailable) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(PERIODIC_SYNC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    fun startSync() {
        if (syncConflicts.isNotEmpty()) {
            showConflictDialog = true
            return
        }
        if (!realRepo || githubToken.isBlank()) {
            showConnect = true
            showToast(context, "Connect a GitHub repository before syncing.")
            return
        }
        if (selectedFolder == null) {
            showToast(context, "Choose the local folder you want to sync first.")
            onPickFolder()
            return
        }
        syncing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GitSyncEngine(context).sync(repo, githubToken, selectedFolder, filters = filters) }
            }
            result.onSuccess { outcome ->
                branch = outcome.branch
                if (outcome.conflicts.isNotEmpty()) {
                    syncConflicts = outcome.conflicts
                    showConflictDialog = true
                } else showToast(context, outcome.message)
                refreshRepository()
            }.onFailure { showToast(context, it.message ?: "Sync failed.") }
            syncing = false
        }
    }

    fun loadBranches() {
        showBranch = true
        branchLoading = true
        branchError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { GitSyncEngine(context).branches(repo) } }
            result.onSuccess { branches = (it + branch).distinct().ifEmpty { listOf(branch) } }
                .onFailure { branchError = it.message }
            branchLoading = false
        }
    }

    fun changeBranch(value: String, create: Boolean = false) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (create) {
                        val token = githubToken
                        if (token.isBlank()) error("Connect GitHub first.")
                    }
                    GitSyncEngine(context).checkout(repo, value)
                }
            }
            result.onSuccess {
                branch = it
                showBranch = false
                if (connected) refreshRepository()
            }.onFailure { branchError = it.message }
        }
    }

    fun loadTickets(target: RepositoryPanel) {
        selectedPanel = target
        showTickets = true
        panelError = null
        if (!connected) return
        panelLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = GitHubApi(githubToken)
                    if (target == RepositoryPanel.ISSUES) api.issues(repo.owner, repo.name) else api.pullRequests(repo.owner, repo.name)
                }
            }
            result.onSuccess { if (target == RepositoryPanel.ISSUES) issues = it else pulls = it }
                .onFailure { panelError = it.message ?: "Could not load ${target.title.lowercase()}." }
            panelLoading = false
        }
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = { IgitBottomBar(tab) { tab = it } }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.HOME -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    HomeScreen(
                    repo = if (realRepo) repo else null,
                    connected = connected,
                    folderLabel = folderName,
                    branch = branch,
                    commits = commits,
                    issues = issues,
                    pulls = pulls,
                    syncing = syncing,
                    onConnect = { showConnect = true; connectionError = null },
                    onSync = ::startSync,
                    onSettings = { showSettings = true },
                    onPickFolder = onPickFolder,
                    onBranch = ::loadBranches,
                    onPanel = ::loadTickets,
                        onFilters = { showFilters = true }
                    )
                }
                AppTab.CHAT -> ChatScreen(
                    context = context,
                    repo = if (realRepo) repo else null,
                    aiEnabled = aiEnabled,
                    hasApiKey = aiKeyStored,
                    branch = branch,
                    commits = commits,
                    onOpenSettings = { showSettings = true }
                )
                AppTab.FILES -> FilesScreen(context, selectedFolder, onPickFolder)
            }
        }
    }

    if (showConnect) ConnectRepoDialog(
        existingToken = githubToken,
        repositories = availableRepos,
        loading = connectionLoading,
        error = connectionError,
        onLoad = ::loadRepositories,
        onChoose = { token, selected ->
            if (!Validation.isValidRepo(selected.owner, selected.name)) {
                connectionError = "The selected repository name is invalid."
            } else {
                githubToken = token.trim()
                vault.put(SyncWorker.GITHUB_TOKEN, githubToken)
                repo = RepoConfig(selected.owner, selected.name, selected.branch, selected.cloneUrl)
                prefs.saveRepo(repo)
                realRepo = true
                branch = selected.branch
                commits = emptyList()
                issues = emptyList()
                pulls = emptyList()
                showConnect = false
                if (prefs.isScheduledSyncEnabled()) {
                    scheduleSync(true, prefs.getSyncIntervalMinutes(), credentialsAvailable = true)
                }
            }
        },
        onDismiss = { showConnect = false }
    )

    if (showSettings) SettingsDialog(
        baseUrl = prefs.getAiBaseUrl(),
        model = prefs.getAiModel(),
        interval = prefs.getSyncIntervalMinutes(),
        scheduled = prefs.isScheduledSyncEnabled(),
        aiEnabled = aiEnabled,
        keyStored = aiKeyStored,
        onSave = { url, modelName, key, interval, scheduled, aiEnabledValue ->
            if (!Validation.isValidAiBaseUrl(url)) {
                showToast(context, "Enter a valid HTTPS API base URL.")
            } else {
                prefs.setAiBaseUrl(url)
                prefs.setAiModel(modelName)
                prefs.setAiEnabled(aiEnabledValue)
                aiEnabled = aiEnabledValue
                if (key.isNotBlank()) {
                    vault.put(SyncWorker.AI_API_KEY, key)
                    aiKeyStored = true
                }
                scheduleSync(scheduled, interval)
                showSettings = false
            }
        },
        onForgetGitHub = {
            vault.remove(SyncWorker.GITHUB_TOKEN)
            githubToken = ""
            realRepo = prefs.getRepo() != null
            scheduleSync(false, prefs.getSyncIntervalMinutes(), credentialsAvailable = false)
            showToast(context, "GitHub token removed from this device.")
        },
        onDismiss = { showSettings = false }
    )

    if (showBranch) BranchDialog(
        branch = branch,
        branches = branches,
        loading = branchLoading,
        error = branchError,
        onSelect = { changeBranch(it) },
        onCreate = { changeBranch(it, create = true) },
        onDismiss = { showBranch = false }
    )

    if (showConflictDialog && syncConflicts.isNotEmpty()) ConflictResolutionDialog(
        paths = syncConflicts,
        onResolve = { selections ->
            syncing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { GitSyncEngine(context).resolveConflicts(repo, selections, selectedFolder) }
                }
                result.onSuccess { outcome ->
                    syncConflicts = emptyList()
                    showConflictDialog = false
                    branch = outcome.branch
                    if (outcome.exportError != null) showToast(context, "Merge saved locally. Folder export will retry during sync: ${outcome.exportError}")
                    startSync()
                }.onFailure { showToast(context, it.message ?: "Could not resolve conflicts."); showConflictDialog = true }
                syncing = false
            }
        },
        onDismiss = { showConflictDialog = false }
    )

    if (showTickets) TicketsDialog(
        panel = selectedPanel,
        items = if (selectedPanel == RepositoryPanel.ISSUES) issues else pulls,
        loading = panelLoading,
        error = panelError,
        onCreate = { showCreateRecord = true },
        onOpen = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        onDismiss = { showTickets = false }
    )

    if (showCreateRecord) CreateRecordDialog(
        panel = selectedPanel,
        onSubmit = { title, body, head, base ->
            if (!connected) {
                showToast(context, "Connect GitHub before creating a record.")
            } else {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val api = GitHubApi(githubToken)
                            if (selectedPanel == RepositoryPanel.ISSUES) api.createIssue(repo.owner, repo.name, title, body)
                            else api.createPullRequest(repo.owner, repo.name, title, body, head, base)
                        }
                    }
                    result.onSuccess { showToast(context, "Created #${it.number} on GitHub."); showCreateRecord = false; loadTickets(selectedPanel) }
                        .onFailure { showToast(context, it.message ?: "Could not create the record.") }
                }
            }
        },
        onDismiss = { showCreateRecord = false }
    )

    if (showFilters) FilterDialog(
        current = filters,
        onSave = {
            filters = it
            context.getSharedPreferences("igit_preferences", Context.MODE_PRIVATE).edit().putString("git_filters", it).apply()
            showFilters = false
        },
        onDismiss = { showFilters = false }
    )
}

private fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private const val PERIODIC_SYNC_NAME = "igit-periodic-sync"
