package app.igit.git

import android.content.Context
import android.net.Uri
import app.igit.data.BranchList
import app.igit.data.ChangeKind
import app.igit.data.ChangedFile
import app.igit.data.CommitInfo
import app.igit.data.FileDiff
import app.igit.data.RemoteInfo
import app.igit.data.RepoContainer
import app.igit.data.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.lib.Signer
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class SyncOutcome(
    val message: String,
    val branch: String,
    val conflicts: List<String> = emptyList(),
    val changedFiles: Int = 0,
    val notRequired: Boolean = false
)

internal fun shouldPush(localHead: String?, remoteBranchHead: String?): Boolean =
    localHead != null && localHead != remoteBranchHead

class SyncException(message: String, val networkRelated: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/**
 * All Git operations for a container. The Git database lives in app-private storage; files are mirrored to the
 * user-selected Storage Access Framework folder so igit never needs "All files access".
 */
class GitSyncEngine(private val context: Context) {

    fun worktree(c: RepoContainer) = File(context.filesDir, "worktrees/${c.id}")
    fun isCloned(c: RepoContainer) = File(worktree(c), ".git").exists()
    private fun baseline(c: RepoContainer) = File(context.filesDir, "worktrees/${c.id}.mirror.json")
    private fun pendingExport(c: RepoContainer) = File(context.filesDir, "worktrees/${c.id}.mirror-pending")
    private fun knownHosts() = File(context.filesDir, "ssh/known_hosts")
    private fun folder(c: RepoContainer): Uri? = c.folderUri?.let(Uri::parse)

    fun authWithHosts(auth: GitAuth) = auth.copy(knownHosts = knownHosts())

    // ---------------------------------------------------------------- clone / delete

    suspend fun clone(c: RepoContainer, auth: GitAuth, progress: (String) -> Unit = {}): String = locked(c) {
        require(Validation.isValidRemoteUrl(c.remoteUrl)) { "Invalid repository URL!" }
        val root = worktree(c)
        if (root.exists()) root.deleteRecursively()
        root.parentFile?.mkdirs()
        baseline(c).delete()
        progress("Cloning repository…")
        try {
            val git = if (c.disableSsl) cloneWithoutSslVerify(c, auth, root) else {
                val command = Git.cloneRepository().setURI(c.remoteUrl.trim()).setDirectory(root).setRemote(c.defaultRemote.ifBlank { "origin" })
                if (c.cloneDepth > 0) command.setDepth(c.cloneDepth)
                authWithHosts(auth).applyTo(command).call()
            }
            git.use {
                applyRepoConfig(it, c)
                val branch = it.repository.branch
                folder(c)?.let { uri ->
                    progress("Copying files to the selected folder…")
                    SafFolderMirror(context).export(root, uri, baseline(c))
                }
                branch
            }
        } catch (e: Exception) {
            root.deleteRecursively()
            throw wrap(e, "Failed to clone repository!")
        }
    }

    /** Clone path for self-signed servers: init, set http.sslVerify=false, then fetch and checkout. */
    private fun cloneWithoutSslVerify(c: RepoContainer, auth: GitAuth, root: File): Git {
        val remote = c.defaultRemote.ifBlank { "origin" }
        val git = Git.init().setDirectory(root).call()
        git.repository.config.apply {
            setBoolean("http", null, "sslVerify", false)
            save()
        }
        git.remoteAdd().setName(remote).setUri(URIish(c.remoteUrl.trim())).call()
        val fetch = git.fetch().setRemote(remote)
        if (c.cloneDepth > 0) fetch.setDepth(c.cloneDepth)
        authWithHosts(auth).applyTo(fetch).call()
        val head = authWithHosts(auth).applyTo(git.lsRemote().setRemote(remote)).callAsMap()["HEAD"]
        val branch = head?.target?.name?.removePrefix(Constants.R_HEADS)
            ?: git.repository.refDatabase.getRefsByPrefix("refs/remotes/$remote/").firstOrNull()?.name?.substringAfterLast('/')
        if (branch != null && git.repository.exactRef("refs/remotes/$remote/$branch") != null) {
            git.checkout().setCreateBranch(true).setName(branch).setStartPoint("$remote/$branch")
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
        }
        return git
    }

    /** "I have a local repository" / Create New Repository: init an empty repository, optionally with a remote. */
    suspend fun initRepository(c: RepoContainer): String = locked(c) {
        val root = worktree(c)
        root.mkdirs()
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            if (c.remoteUrl.isNotBlank()) {
                git.remoteAdd().setName(c.defaultRemote.ifBlank { "origin" }).setUri(URIish(c.remoteUrl.trim())).call()
            }
            applyRepoConfig(git, c)
            "main"
        }
    }

    fun deleteLocalData(c: RepoContainer) {
        worktree(c).deleteRecursively()
        baseline(c).delete()
        pendingExport(c).delete()
    }

    // ---------------------------------------------------------------- sync-mode operations

    /** Detect → stage → commit → fetch → pull → push → export. GitSync's "Sync Changes". */
    suspend fun sync(
        c: RepoContainer,
        auth: GitAuth,
        signer: Signer? = null,
        messageOverride: String? = null,
        progress: (String) -> Unit = {}
    ): SyncOutcome = locked(c) {
        open(c).use { git ->
            ensureOnBranch(git)
            progress("Detecting changes…")
            val imported = importFolder(c)
            val committed = commitAll(git, c, signer, messageOverride)
            if (!hasRemote(git, c)) {
                val exported = exportFolder(c)
                return@use SyncOutcome(if (committed) "Committed locally (no remote configured)" else "Sync not required!", git.repository.branch, changedFiles = imported + exported, notRequired = !committed)
            }
            progress("Syncing changes…")
            fetch(git, c, auth)
            val branch = git.repository.branch
            val remoteHead = remoteHead(git, c, branch)
            val localHead = git.repository.resolve(Constants.HEAD)?.name

            if (c.optimisedSync && !committed && remoteHead == localHead && !pendingExport(c).isFile) {
                return@use SyncOutcome("Sync not required!", branch, changedFiles = imported, notRequired = true)
            }

            if (remoteHead != null && remoteHead != localHead) {
                val conflicts = pull(git, c, auth)
                if (conflicts.isNotEmpty()) {
                    return@use SyncOutcome("Merge conflicts need attention", branch, conflicts)
                }
            }

            val pushNeeded = shouldPush(git.repository.resolve(Constants.HEAD)?.name, remoteHead(git, c, branch))
            if (pushNeeded) {
                progress("Syncing local changes…")
                push(git, c, auth, force = false)
            }
            val exported = exportFolder(c)
            SyncOutcome(if (!pushNeeded && remoteHead == localHead && !committed) "Sync not required!" else "Repository synced!", branch, changedFiles = imported + exported)
        }
    }

    /** Pull remote changes without uploading local ones. */
    suspend fun downloadChanges(c: RepoContainer, auth: GitAuth): SyncOutcome = locked(c) {
        open(c).use { git ->
            ensureOnBranch(git)
            requireRemote(git, c)
            importFolder(c)
            fetch(git, c, auth)
            val conflicts = try { pull(git, c, auth) } catch (e: Exception) {
                throw SyncException("Pull failed! Please check for uncommitted changes and try again.", cause = e)
            }
            if (conflicts.isNotEmpty()) return@use SyncOutcome("Merge conflicts need attention", git.repository.branch, conflicts)
            val exported = exportFolder(c)
            SyncOutcome("Repository synced!", git.repository.branch, changedFiles = exported)
        }
    }

    /** Commit and upload local changes without pulling. */
    suspend fun uploadChanges(c: RepoContainer, auth: GitAuth, signer: Signer?): SyncOutcome = locked(c) {
        open(c).use { git ->
            ensureOnBranch(git)
            requireRemote(git, c)
            val imported = importFolder(c)
            commitAll(git, c, signer, null)
            fetch(git, c, auth)
            val branch = git.repository.branch
            if (!shouldPush(git.repository.resolve(Constants.HEAD)?.name, remoteHead(git, c, branch))) {
                return@use SyncOutcome("Sync not required!", branch, notRequired = true)
            }
            push(git, c, auth, force = false)
            SyncOutcome("Repository synced!", branch, changedFiles = imported)
        }
    }

    /** "Download + Overwrite": reset local history and files to the remote branch. */
    suspend fun forcePull(c: RepoContainer, auth: GitAuth): SyncOutcome = locked(c) {
        open(c).use { git ->
            ensureOnBranch(git)
            requireRemote(git, c)
            fetch(git, c, auth)
            val branch = git.repository.branch
            val target = remoteRef(git, c, branch) ?: throw SyncException("The remote has no branch named $branch.")
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(target).call()
            git.clean().setCleanDirectories(true).setIgnore(false).call()
            pendingExport(c).writeText("pending")
            val exported = exportFolder(c, overwriteLocal = true)
            SyncOutcome("Repository synced!", branch, changedFiles = exported)
        }
    }

    /** "Upload + Overwrite": commit local changes and force-push over the remote branch. */
    suspend fun forcePush(c: RepoContainer, auth: GitAuth, signer: Signer?): SyncOutcome = locked(c) {
        open(c).use { git ->
            ensureOnBranch(git)
            requireRemote(git, c)
            val imported = importFolder(c)
            commitAll(git, c, signer, null)
            push(git, c, auth, force = true)
            SyncOutcome("Repository synced!", git.repository.branch, changedFiles = imported)
        }
    }

    // ---------------------------------------------------------------- client-mode operations

    suspend fun fetchRemote(c: RepoContainer, auth: GitAuth): String = locked(c) {
        open(c).use { git -> requireRemote(git, c); fetch(git, c, auth); "Fetched ${remoteName(git, c)}" }
    }

    suspend fun pullChanges(c: RepoContainer, auth: GitAuth): SyncOutcome = downloadChanges(c, auth)

    suspend fun pushChanges(c: RepoContainer, auth: GitAuth): String = locked(c) {
        open(c).use { git ->
            ensureOnBranch(git)
            requireRemote(git, c)
            fetch(git, c, auth)
            if (!shouldPush(git.repository.resolve(Constants.HEAD)?.name, remoteHead(git, c, git.repository.branch))) "Sync not required!"
            else { push(git, c, auth, force = false); "Pushed to ${remoteName(git, c)}" }
        }
    }

    /** Uncommitted changes, after importing edits from the selected folder. */
    suspend fun status(c: RepoContainer): List<ChangedFile> = locked(c) {
        if (!isCloned(c)) return@locked emptyList()
        importFolder(c)
        open(c).use { git -> collectStatus(git) }
    }

    suspend fun stage(c: RepoContainer, paths: Collection<String>) = locked(c) {
        open(c).use { git -> stagePaths(git, paths) }
    }

    suspend fun unstage(c: RepoContainer, paths: Collection<String>) = locked(c) {
        open(c).use { git ->
            if (git.repository.resolve(Constants.HEAD) == null) {
                git.rm().setCached(true).apply { paths.forEach { addFilepattern(it) } }.call()
            } else {
                git.reset().apply { paths.forEach { addPath(it) } }.call()
            }
        }
    }

    /** Discard local changes to files (restores HEAD content in both the worktree and the folder). */
    suspend fun discard(c: RepoContainer, paths: Collection<String>) = locked(c) {
        importFolder(c)
        open(c).use { git ->
            val status = git.status().call()
            val untracked = paths.filter { it in status.untracked || it in status.added }
            val tracked = paths - untracked.toSet()
            if (untracked.isNotEmpty()) {
                git.rm().setCached(true).apply { untracked.filter { it in status.added }.forEach { addFilepattern(it) } }
                    .takeIf { untracked.any { it in status.added } }?.call()
                untracked.forEach { File(worktree(c), it).delete() }
            }
            if (tracked.isNotEmpty()) {
                git.reset().apply { tracked.forEach { addPath(it) } }.call()
                git.checkout().apply { tracked.forEach { addPath(it) } }.call()
            }
        }
        exportFolder(c)
        Unit
    }

    suspend fun commit(c: RepoContainer, message: String, signer: Signer?): String = locked(c) {
        require(message.isNotBlank()) { "Field cannot be empty" }
        open(c).use { git ->
            val result = commitCommand(git, c, message, signer).call()
            result.name.take(7)
        }
    }

    /** GitSync's Manual Sync: choose files + message, then commit, pull and push. */
    suspend fun manualSync(c: RepoContainer, auth: GitAuth, signer: Signer?, paths: Collection<String>, message: String): SyncOutcome = locked(c) {
        require(paths.isNotEmpty()) { "Select the files you would like to sync" }
        open(c).use { git ->
            ensureOnBranch(git)
            importFolder(c)
            stagePaths(git, paths)
            commitCommand(git, c, message.ifBlank { Validation.formatSyncMessage(c.syncMessage, c.syncMessageTimeFormat) }, signer).call()
            if (!hasRemote(git, c)) return@use SyncOutcome("Committed locally (no remote configured)", git.repository.branch)
            fetch(git, c, auth)
            val branch = git.repository.branch
            val remoteHead = remoteHead(git, c, branch)
            if (remoteHead != null && remoteHead != git.repository.resolve(Constants.HEAD)?.name) {
                // Pull would fail with unselected local edits in the way; stash-free approach: let JGit decide.
                val conflicts = pull(git, c, auth)
                if (conflicts.isNotEmpty()) return@use SyncOutcome("Merge conflicts need attention", branch, conflicts)
            }
            if (shouldPush(git.repository.resolve(Constants.HEAD)?.name, remoteHead(git, c, branch))) push(git, c, auth, force = false)
            exportFolder(c)
            SyncOutcome("Repository synced!", branch)
        }
    }

    /** Summary of uncommitted changes, used as context for the AI commit-message wand. */
    suspend fun changesSummary(c: RepoContainer, maxChars: Int = 12_000): String = locked(c) {
        open(c).use { git ->
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { f ->
                f.setRepository(git.repository)
                f.setDiffComparator(RawTextComparator.DEFAULT)
                val head = git.repository.resolve("HEAD^{tree}")
                val oldTree: AbstractTreeIterator = if (head == null) EmptyTreeIterator() else git.repository.newObjectReader().use { r -> CanonicalTreeParser(null, r, head) }
                val newTree = org.eclipse.jgit.treewalk.FileTreeIterator(git.repository)
                f.scan(oldTree, newTree).take(80).forEach { entry ->
                    if (out.size() < maxChars) runCatching { f.format(entry) }
                }
            }
            out.toString(Charsets.UTF_8.name()).take(maxChars)
        }
    }

    // ---------------------------------------------------------------- history

    suspend fun log(c: RepoContainer, max: Int = 30): List<CommitInfo> = withContext(Dispatchers.IO) {
        if (!isCloned(c)) return@withContext emptyList()
        open(c).use { git ->
            val head = git.repository.resolve(Constants.HEAD) ?: return@use emptyList()
            val unpushed = unpushedCommits(git, c)
            val commits = git.log().add(head).setMaxCount(max).call().toList()
            DiffFormatter(DisabledOutputStream.INSTANCE).use { f ->
                f.setRepository(git.repository)
                f.setDiffComparator(RawTextComparator.DEFAULT)
                f.isDetectRenames = true
                commits.map { commit ->
                    var add = 0; var del = 0; var files = 0
                    runCatching {
                        val entries = f.scan(parentTree(git.repository, commit), treeOf(git.repository, commit))
                        files = entries.size
                        entries.forEach { e -> f.toFileHeader(e).toEditList().forEach { edit -> add += edit.lengthB; del += edit.lengthA } }
                    }
                    CommitInfo(
                        sha = commit.name,
                        message = commit.shortMessage,
                        fullMessage = commit.fullMessage,
                        author = commit.authorIdent.name,
                        timeMillis = commit.authorIdent.whenAsInstant.toEpochMilli(),
                        additions = add, deletions = del, filesChanged = files,
                        pushed = commit.name !in unpushed
                    )
                }
            }
        }
    }

    suspend fun commitDiff(c: RepoContainer, sha: String): List<FileDiff> = withContext(Dispatchers.IO) {
        open(c).use { git ->
            val commit = RevWalk(git.repository).use { it.parseCommit(ObjectId.fromString(sha)) }
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { f ->
                f.setRepository(git.repository)
                f.setDiffComparator(RawTextComparator.DEFAULT)
                f.isDetectRenames = true
                f.scan(parentTree(git.repository, commit), treeOf(git.repository, commit)).map { entry ->
                    out.reset()
                    var add = 0; var del = 0
                    runCatching {
                        f.format(entry)
                        f.toFileHeader(entry).toEditList().forEach { add += it.lengthB; del += it.lengthA }
                    }
                    val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                    FileDiff(path, entry.changeType.name.lowercase(), out.toString(Charsets.UTF_8.name()).take(200_000), add, del)
                }
            }
        }
    }

    /** Diff of one file across history (GitSync's "File Diff" / file log view). */
    suspend fun fileHistory(c: RepoContainer, path: String, max: Int = 20): List<CommitInfo> = withContext(Dispatchers.IO) {
        open(c).use { git ->
            git.log().addPath(path).setMaxCount(max).call().map {
                CommitInfo(it.name, it.shortMessage, it.fullMessage, it.authorIdent.name, it.authorIdent.whenAsInstant.toEpochMilli(), 0, 0, 0)
            }
        }
    }

    // ---------------------------------------------------------------- branches

    suspend fun branches(c: RepoContainer): BranchList = withContext(Dispatchers.IO) {
        if (!isCloned(c)) return@withContext BranchList(null, emptyList(), emptyList(), false)
        open(c).use { git ->
            val full = git.repository.fullBranch
            val detached = full != null && !full.startsWith(Constants.R_HEADS)
            val local = git.branchList().call().map { it.name.removePrefix(Constants.R_HEADS) }
            val remotePrefix = Constants.R_REMOTES + remoteName(git, c) + "/"
            val remote = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
                .mapNotNull { ref -> ref.name.takeIf { it.startsWith(remotePrefix) }?.removePrefix(remotePrefix) }
                .filterNot { it == "HEAD" || it in local }
            BranchList(if (detached) git.repository.branch?.take(7) else git.repository.branch, local.sorted(), remote.sorted(), detached)
        }
    }

    suspend fun checkout(c: RepoContainer, branch: String): String = locked(c) {
        require(Repository.isValidRefName("refs/heads/$branch")) { "Enter a valid branch name." }
        importFolder(c)
        val result = open(c).use { git ->
            if (git.repository.exactRef("refs/heads/$branch") == null) {
                val remote = remoteName(git, c)
                require(git.repository.exactRef("refs/remotes/$remote/$branch") != null) { "Branch $branch not found." }
                git.branchCreate().setName(branch).setStartPoint("$remote/$branch")
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
            }
            git.checkout().setName(branch).call()
            git.repository.branch
        }
        exportFolder(c)
        result
    }

    suspend fun createBranch(c: RepoContainer, name: String, basedOn: String?): String = locked(c) {
        require(Repository.isValidRefName("refs/heads/$name")) { "Enter a valid branch name." }
        importFolder(c)
        val result = open(c).use { git ->
            val create = git.branchCreate().setName(name)
            if (!basedOn.isNullOrBlank()) {
                val remote = remoteName(git, c)
                val start = when {
                    git.repository.exactRef("refs/heads/$basedOn") != null -> basedOn
                    git.repository.exactRef("refs/remotes/$remote/$basedOn") != null -> "$remote/$basedOn"
                    else -> basedOn
                }
                create.setStartPoint(start)
            }
            create.call()
            git.checkout().setName(name).call()
            git.repository.branch
        }
        exportFolder(c)
        result
    }

    suspend fun renameBranch(c: RepoContainer, from: String, to: String) = locked(c) {
        require(Repository.isValidRefName("refs/heads/$to")) { "Enter a valid branch name." }
        open(c).use { git -> git.branchRename().setOldName(from).setNewName(to).call() }
        Unit
    }

    suspend fun deleteBranch(c: RepoContainer, name: String, auth: GitAuth?, deleteRemote: Boolean) = locked(c) {
        open(c).use { git ->
            require(git.repository.branch != name) { "Checkout another branch before deleting \"$name\"." }
            git.branchDelete().setBranchNames("refs/heads/$name").setForce(true).call()
            if (deleteRemote && auth != null && hasRemote(git, c)) {
                authWithHosts(auth).applyTo(git.push().setRemote(remoteName(git, c)).setRefSpecs(RefSpec(":refs/heads/$name"))).call()
                git.branchDelete().setBranchNames("refs/remotes/${remoteName(git, c)}/$name").setForce(true).call()
            }
        }
        Unit
    }

    // ---------------------------------------------------------------- remotes

    suspend fun remotes(c: RepoContainer): List<RemoteInfo> = withContext(Dispatchers.IO) {
        if (!isCloned(c)) return@withContext emptyList()
        open(c).use { git -> git.remoteList().call().map { RemoteInfo(it.name, it.urIs.firstOrNull()?.toString().orEmpty()) } }
    }

    suspend fun addRemote(c: RepoContainer, name: String, url: String) = locked(c) {
        require(Validation.isValidRemoteUrl(url)) { "Invalid repository URL!" }
        open(c).use { git -> git.remoteAdd().setName(name.trim()).setUri(URIish(url.trim())).call() }
        Unit
    }

    suspend fun setRemoteUrl(c: RepoContainer, name: String, url: String) = locked(c) {
        require(Validation.isValidRemoteUrl(url)) { "Invalid repository URL!" }
        open(c).use { git ->
            if (git.remoteList().call().none { it.name == name }) git.remoteAdd().setName(name).setUri(URIish(url.trim())).call()
            else git.remoteSetUrl().setRemoteName(name).setRemoteUri(URIish(url.trim())).call()
        }
        Unit
    }

    suspend fun deleteRemote(c: RepoContainer, name: String) = locked(c) {
        open(c).use { git -> git.remoteRemove().setRemoteName(name).call() }
        Unit
    }

    suspend fun renameRemote(c: RepoContainer, from: String, to: String) = locked(c) {
        require(to.matches(Regex("^[A-Za-z0-9._-]+$"))) { "Enter a valid remote name." }
        open(c).use { git ->
            val config = git.repository.config
            val urls = config.getStringList("remote", from, "url")
            require(urls.isNotEmpty()) { "Remote $from not found." }
            val fetch = config.getStringList("remote", from, "fetch").map { it.replace("refs/remotes/$from/", "refs/remotes/$to/") }
            config.setStringList("remote", to, "url", urls.toList())
            config.setStringList("remote", to, "fetch", fetch.ifEmpty { listOf("+refs/heads/*:refs/remotes/$to/*") })
            config.unsetSection("remote", from)
            config.getSubsections("branch").forEach { b ->
                if (config.getString("branch", b, "remote") == from) config.setString("branch", b, "remote", to)
            }
            config.save()
            // Move remote-tracking refs.
            git.repository.refDatabase.getRefsByPrefix("refs/remotes/$from/").forEach { ref ->
                val update = git.repository.updateRef("refs/remotes/$to/" + ref.name.removePrefix("refs/remotes/$from/"))
                update.setNewObjectId(ref.objectId); update.forceUpdate()
                git.repository.updateRef(ref.name).apply { isForceUpdate = true }.delete()
            }
        }
        Unit
    }

    // ---------------------------------------------------------------- merge conflicts

    fun isMerging(c: RepoContainer): Boolean = isCloned(c) && runCatching {
        Git.open(worktree(c)).use { it.repository.repositoryState == RepositoryState.MERGING }
    }.getOrDefault(false)

    suspend fun conflicts(c: RepoContainer): List<String> = withContext(Dispatchers.IO) {
        if (!isCloned(c)) emptyList() else open(c).use { it.status().call().conflicting.sorted() }
    }

    suspend fun readFile(c: RepoContainer, path: String): String = withContext(Dispatchers.IO) {
        safeFile(c, path).readText()
    }

    /** Writes the resolved content of a conflicted file and stages it. */
    suspend fun resolveFile(c: RepoContainer, path: String, content: String) = locked(c) {
        require(!ConflictText.hasMarkers(content)) { "Resolve every conflict block before saving." }
        safeFile(c, path).writeText(content)
        open(c).use { git -> git.add().addFilepattern(path).call() }
        Unit
    }

    /** Completes the merge once every file is resolved. */
    suspend fun finishMerge(c: RepoContainer, signer: Signer?): String = locked(c) {
        open(c).use { git ->
            require(git.status().call().conflicting.isEmpty()) { "Some conflicts remain unresolved." }
            val message = git.repository.readMergeCommitMsg()?.takeIf { it.isNotBlank() } ?: "Resolve merge conflicts"
            commitCommand(git, c, message, signer).call()
            pendingExport(c).writeText("pending")
            git.repository.branch
        }
    }

    suspend fun abortMerge(c: RepoContainer) = locked(c) {
        open(c).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
            git.repository.writeMergeHeads(null)
            git.repository.writeMergeCommitMsg(null)
        }
        // Local edits that caused the conflict remain committed locally; the folder gets HEAD again.
        exportFolder(c)
        Unit
    }

    // ---------------------------------------------------------------- repository files

    suspend fun readIgnore(c: RepoContainer, local: Boolean): String = withContext(Dispatchers.IO) {
        val file = if (local) File(worktree(c), ".git/info/exclude") else File(worktree(c), ".gitignore")
        if (file.isFile) file.readText() else ""
    }

    suspend fun writeIgnore(c: RepoContainer, local: Boolean, text: String) = locked(c) {
        if (local) {
            File(worktree(c), ".git/info/exclude").apply { parentFile?.mkdirs(); writeText(text) }
        } else {
            importFolder(c)
            File(worktree(c), ".gitignore").writeText(text)
            exportFolder(c)
        }
        Unit
    }

    /** File explorer action: add paths to .gitignore or .git/info/exclude and stop tracking them. */
    suspend fun ignoreAndUntrack(c: RepoContainer, paths: Collection<String>, local: Boolean, untrack: Boolean) = locked(c) {
        val file = if (local) File(worktree(c), ".git/info/exclude") else File(worktree(c), ".gitignore")
        if (!local) importFolder(c)
        file.parentFile?.mkdirs()
        val existing = if (file.isFile) file.readLines() else emptyList()
        val additions = paths.map { "/$it" }.filterNot { it in existing }
        if (additions.isNotEmpty()) file.writeText((existing + additions).joinToString("\n", postfix = "\n"))
        if (untrack) open(c).use { git -> git.rm().setCached(true).apply { paths.forEach { addFilepattern(it) } }.call() }
        if (!local) exportFolder(c)
        Unit
    }

    suspend fun workingFiles(c: RepoContainer, limit: Int = 300): List<String> = withContext(Dispatchers.IO) {
        val root = worktree(c)
        if (!root.exists()) emptyList() else root.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .take(limit)
            .toList()
    }

    // ---------------------------------------------------------------- internals

    private fun open(c: RepoContainer): Git {
        if (!isCloned(c)) throw SyncException("No repo found… Clone a repository first.")
        val git = Git.open(worktree(c))
        applyRepoConfig(git, c)
        return git
    }

    private fun applyRepoConfig(git: Git, c: RepoContainer) {
        val config = git.repository.config
        var changed = false
        if (config.getBoolean("http", "sslVerify", true) == c.disableSsl) {
            config.setBoolean("http", null, "sslVerify", !c.disableSsl); changed = true
        }
        if (c.authorName.isNotBlank() && config.getString("user", null, "name") != c.authorName) {
            config.setString("user", null, "name", c.authorName); changed = true
        }
        if (c.authorEmail.isNotBlank() && config.getString("user", null, "email") != c.authorEmail) {
            config.setString("user", null, "email", c.authorEmail); changed = true
        }
        if (changed) config.save()
    }

    private fun ident(c: RepoContainer) = PersonIdent(c.authorName.ifBlank { "igit" }, c.authorEmail.ifBlank { "igit@users.noreply.github.com" })

    private fun commitCommand(git: Git, c: RepoContainer, message: String, signer: Signer?) =
        git.commit().setMessage(message).setAuthor(ident(c)).setCommitter(ident(c)).also { cmd ->
            if (signer != null) cmd.setSign(true).setSigner(signer).setGpgConfig(GpgConfig(git.repository.config))
            else cmd.setSign(false)
        }

    private fun commitAll(git: Git, c: RepoContainer, signer: Signer?, messageOverride: String?): Boolean {
        val status = git.status().call()
        if (status.conflicting.isNotEmpty()) throw SyncException("Ongoing merge conflict — resolve it first.")
        if (status.isClean) return false
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        if (git.status().call().let { it.added.isEmpty() && it.changed.isEmpty() && it.removed.isEmpty() }) return false
        val message = messageOverride?.takeIf { it.isNotBlank() } ?: Validation.formatSyncMessage(c.syncMessage, c.syncMessageTimeFormat)
        commitCommand(git, c, message, signer).call()
        return true
    }

    private fun stagePaths(git: Git, paths: Collection<String>) {
        val missing = paths.filter { !File(git.repository.workTree, it).exists() }
        val present = paths - missing.toSet()
        if (present.isNotEmpty()) git.add().apply { present.forEach { addFilepattern(it) } }.call()
        if (missing.isNotEmpty()) git.rm().setCached(true).apply { missing.forEach { addFilepattern(it) } }.call()
    }

    private fun collectStatus(git: Git): List<ChangedFile> {
        val s = git.status().call()
        val out = linkedMapOf<String, ChangedFile>()
        s.conflicting.forEach { out[it] = ChangedFile(it, ChangeKind.CONFLICT, false) }
        s.added.forEach { out.putIfAbsent(it, ChangedFile(it, ChangeKind.ADDED, true)) }
        s.changed.forEach { out.putIfAbsent(it, ChangedFile(it, ChangeKind.MODIFIED, true)) }
        s.removed.forEach { out.putIfAbsent(it, ChangedFile(it, ChangeKind.DELETED, true)) }
        s.modified.forEach { out.putIfAbsent(it, ChangedFile(it, ChangeKind.MODIFIED, false)) }
        s.missing.forEach { out.putIfAbsent(it, ChangedFile(it, ChangeKind.DELETED, false)) }
        s.untracked.forEach { out.putIfAbsent(it, ChangedFile(it, ChangeKind.UNTRACKED, false)) }
        return out.values.sortedBy { it.path }
    }

    private fun ensureOnBranch(git: Git) {
        val full = git.repository.fullBranch
        if (full != null && !full.startsWith(Constants.R_HEADS)) throw SyncException("Detached Head — checkout a branch to sync.")
        if (git.repository.repositoryState == RepositoryState.MERGING) throw SyncException("Ongoing merge conflict — resolve it first.")
    }

    private fun remoteName(git: Git, c: RepoContainer): String {
        val names = git.repository.remoteNames
        return when {
            c.defaultRemote in names -> c.defaultRemote
            "origin" in names -> "origin"
            else -> names.firstOrNull() ?: c.defaultRemote
        }
    }

    private fun hasRemote(git: Git, c: RepoContainer) = git.repository.remoteNames.isNotEmpty()
    private fun requireRemote(git: Git, c: RepoContainer) { if (!hasRemote(git, c)) throw SyncException("No remote configured") }

    private fun upstreamBranch(git: Git, branch: String): String =
        git.repository.config.getString("branch", branch, "merge")?.removePrefix(Constants.R_HEADS) ?: branch

    private fun remoteRef(git: Git, c: RepoContainer, branch: String): String? {
        val ref = "refs/remotes/${remoteName(git, c)}/${upstreamBranch(git, branch)}"
        return ref.takeIf { git.repository.exactRef(it) != null }
    }

    private fun remoteHead(git: Git, c: RepoContainer, branch: String): String? =
        remoteRef(git, c, branch)?.let { git.repository.resolve(it)?.name }

    private fun fetch(git: Git, c: RepoContainer, auth: GitAuth) {
        try {
            authWithHosts(auth).applyTo(git.fetch().setRemote(remoteName(git, c)).setRemoveDeletedRefs(true)).call()
        } catch (e: Exception) { throw wrap(e, "Fetch failed") }
    }

    /** Merges the fetched upstream branch. Returns conflicting paths (empty on success). */
    private fun pull(git: Git, c: RepoContainer, auth: GitAuth): List<String> {
        val branch = git.repository.branch
        val ref = remoteRef(git, c, branch) ?: return emptyList()
        val result = git.merge().include(git.repository.resolve(ref))
            .setMessage("Merge ${remoteName(git, c)}/${upstreamBranch(git, branch)}")
            .setStrategy(org.eclipse.jgit.merge.MergeStrategy.RECURSIVE)
            .setCommit(true)
            .call()
        return when (result.mergeStatus) {
            MergeResult.MergeStatus.CONFLICTING -> git.status().call().conflicting.sorted().also { pendingExport(c).writeText("pending") }
            MergeResult.MergeStatus.FAILED, MergeResult.MergeStatus.CHECKOUT_CONFLICT ->
                throw SyncException("Pull failed! Please check for uncommitted changes and try again.")
            MergeResult.MergeStatus.ABORTED, MergeResult.MergeStatus.NOT_SUPPORTED -> throw SyncException("Pull failed: ${result.mergeStatus}")
            else -> emptyList<String>().also { pendingExport(c).writeText("pending") }
        }
    }

    private fun push(git: Git, c: RepoContainer, auth: GitAuth, force: Boolean) {
        val branch = git.repository.branch
        val remote = remoteName(git, c)
        val target = upstreamBranch(git, branch)
        val results = try {
            authWithHosts(auth).applyTo(git.push().setRemote(remote).setForce(force)
                .setRefSpecs(RefSpec("${if (force) "+" else ""}HEAD:refs/heads/$target"))).call()
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val hint = if (msg.contains("git-receive-pack", true) || msg.contains("403") || msg.contains("permission", true))
                " — the token needs write access (GitHub: Contents read & write)." else ""
            throw wrap(e, "Upload failed$hint")
        }
        results.flatMap { it.remoteUpdates }.firstOrNull { it.status !in setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE) }?.let {
            throw SyncException(when (it.status) {
                RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> "The remote has new changes. Sync or download them first."
                else -> "Upload rejected: ${it.status}${it.message?.let { m -> " ($m)" } ?: ""}"
            })
        }
        if (git.repository.config.getString("branch", branch, "remote") == null) {
            git.repository.config.apply {
                setString("branch", branch, "remote", remote)
                setString("branch", branch, "merge", Constants.R_HEADS + target)
                save()
            }
        }
    }

    private fun unpushedCommits(git: Git, c: RepoContainer): Set<String> = runCatching {
        val branch = git.repository.branch ?: return emptySet()
        RevWalk(git.repository).use { walk ->
            walk.markStart(walk.parseCommit(git.repository.resolve(Constants.HEAD)))
            remoteRef(git, c, branch)?.let { walk.markUninteresting(walk.parseCommit(git.repository.resolve(it))) }
                ?: return emptySet()
            walk.take(200).map { it.name }.toSet()
        }
    }.getOrDefault(emptySet())

    private fun treeOf(repo: Repository, commit: RevCommit): AbstractTreeIterator =
        repo.newObjectReader().use { CanonicalTreeParser(null, it, commit.tree.id) }

    private fun parentTree(repo: Repository, commit: RevCommit): AbstractTreeIterator {
        if (commit.parentCount == 0) return EmptyTreeIterator()
        return RevWalk(repo).use { walk ->
            val parent = walk.parseCommit(commit.getParent(0).id)
            repo.newObjectReader().use { CanonicalTreeParser(null, it, parent.tree.id) }
        }
    }

    private suspend fun importFolder(c: RepoContainer): Int {
        val uri = folder(c) ?: return 0
        // A pending export (after merge/reset) doesn't block import: the baseline only changes on export,
        // so folder edits made since the last export are still detected here.
        return SafFolderMirror(context).importChanges(worktree(c), uri, baseline(c))
    }

    private suspend fun exportFolder(c: RepoContainer, overwriteLocal: Boolean = false): Int {
        val uri = folder(c) ?: return 0
        val written = SafFolderMirror(context).export(worktree(c), uri, baseline(c), overwriteLocal)
        pendingExport(c).delete()
        return written
    }

    private fun safeFile(c: RepoContainer, relative: String): File {
        require(Validation.isSafeRelativePath(relative)) { "Unsafe relative path." }
        val root = worktree(c).canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "Path escapes the repository." }
        return file
    }

    private fun wrap(e: Exception, prefix: String): Exception {
        if (e is SyncException || e is IllegalArgumentException) return e
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
        val network = e is TransportException && (
            text.contains("UnknownHost", true) || text.contains("Unable to resolve host", true) ||
            text.contains("timed out", true) || text.contains("Network is unreachable", true) ||
            text.contains("connection", true))
        return SyncException(if (network) "Network unavailable! igit will retry when reconnected" else "$prefix: $text", network, e)
    }

    private suspend fun <T> locked(c: RepoContainer, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        locks.getOrPut(c.id) { Mutex() }.withLock { block() }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}

/** Helpers for Git conflict markers, used by the merge conflict editor (Current / Incoming / Both). */
object ConflictText {
    data class Block(val current: List<String>, val incoming: List<String>)

    fun hasMarkers(text: String): Boolean = text.lineSequence().any { it.startsWith("<<<<<<< ") || it == "<<<<<<<" } &&
        text.lineSequence().any { it.startsWith(">>>>>>> ") || it == ">>>>>>>" }

    fun blockCount(text: String): Int = text.lineSequence().count { it.startsWith("<<<<<<<") }

    /** Resolves every conflict block with one choice: "current", "incoming" or "both". */
    fun resolveAll(text: String, choice: String): String = resolve(text) { _, block ->
        when (choice) {
            "current" -> block.current
            "incoming" -> block.incoming
            else -> block.current + block.incoming
        }
    }

    /** Resolves only the [index]th block (0-based). */
    fun resolveBlock(text: String, index: Int, choice: String): String = resolve(text) { i, block ->
        if (i != index) null else when (choice) {
            "current" -> block.current
            "incoming" -> block.incoming
            else -> block.current + block.incoming
        }
    }

    private fun resolve(text: String, pick: (Int, Block) -> List<String>?): String {
        val lines = text.split("\n")
        val out = mutableListOf<String>()
        var i = 0
        var blockIndex = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("<<<<<<<")) {
                val start = i
                val current = mutableListOf<String>()
                val incoming = mutableListOf<String>()
                i++
                var inBase = false
                while (i < lines.size && !lines[i].startsWith("=======")) {
                    if (lines[i].startsWith("|||||||")) inBase = true
                    else if (!inBase) current += lines[i]
                    i++
                }
                i++
                while (i < lines.size && !lines[i].startsWith(">>>>>>>")) { incoming += lines[i]; i++ }
                val end = i
                val chosen = pick(blockIndex++, Block(current, incoming))
                if (chosen == null) out += lines.subList(start, minOf(end + 1, lines.size)) else out += chosen
                i = end + 1
            } else {
                out += line; i++
            }
        }
        return out.joinToString("\n")
    }
}
