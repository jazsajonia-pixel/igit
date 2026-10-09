package app.igit.git

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.igit.data.RepoConfig
import app.igit.data.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File


data class SyncOutcome(
    val message: String,
    val branch: String,
    val conflicts: List<String> = emptyList(),
    val changedFiles: Int = 0
)

data class ConflictResolutionOutcome(val branch: String, val exportError: String? = null)

internal fun shouldPush(localHead: String?, remoteBranchHead: String?): Boolean =
    localHead != null && localHead != remoteBranchHead

class GitSyncEngine(private val context: Context) {
    suspend fun sync(repo: RepoConfig, token: String, folderUri: Uri?, authorName: String = "igit", authorEmail: String = "igit@users.noreply.github.com", filters: String = ""): SyncOutcome = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Add a GitHub token before syncing." }
        val root = File(context.filesDir, "worktrees/${repo.key}")
        root.parentFile?.mkdirs()
        val credentials = UsernamePasswordCredentialsProvider("x-access-token", token)

        if (!File(root, ".git").exists()) {
            if (root.exists()) root.deleteRecursively()
            Git.cloneRepository()
                .setURI(repo.remoteUrl)
                .setDirectory(root)
                .setCredentialsProvider(credentials)
                .call().close()
        }

        val baseline = File(root.parentFile, "${repo.key}.mirror.json")
        Git.open(root).use { git ->
            applyLocalFilters(root, filters)
            val pendingExport = File(root.parentFile, "${repo.key}.mirror-pending")
            if (folderUri != null && pendingExport.isFile) {
                SafFolderMirror(context).export(root, folderUri, baseline)
                pendingExport.delete()
            }
            val imported = folderUri?.let { SafFolderMirror(context).importChanges(root, it, baseline) } ?: 0
            val before = git.status().call()
            if (!before.isClean) {
                git.add().addFilepattern(".").call()
                git.add().addFilepattern(".").setUpdate(true).call()
                val refreshed = git.status().call()
                if (!refreshed.isClean) {
                    git.commit().setMessage("Sync local folder changes")
                        .setAuthor(authorName, authorEmail).call()
                }
            }

            git.fetch().setRemote("origin").setCredentialsProvider(credentials).call()
            val branchName = git.repository.branch
            val remote = git.repository.config.getString("branch", branchName, "remote") ?: "origin"
            val remoteBranch = git.repository.config.getString("branch", branchName, "merge")
                ?.removePrefix("refs/heads/") ?: repo.defaultBranch
            val pull = git.pull().setRemote(remote).setRemoteBranchName(remoteBranch).setCredentialsProvider(credentials)
                .setStrategy(org.eclipse.jgit.merge.MergeStrategy.RECURSIVE).call()
            if (pull.mergeResult?.mergeStatus == MergeResult.MergeStatus.CONFLICTING) {
                val conflicts = git.status().call().conflicting.toList()
                return@withContext SyncOutcome("Merge conflicts need attention", git.repository.branch, conflicts)
            }

            val status = git.status().call()
            if (!status.isClean) {
                val conflicts = git.status().call().conflicting.toList()
                return@withContext SyncOutcome("Working tree has local changes; sync paused", git.repository.branch, conflicts)
            }

            val localHead = git.repository.resolve(Constants.HEAD)?.name
            val remoteBranchHead = git.repository.resolve("refs/remotes/origin/$branchName")?.name
            val pushNeeded = shouldPush(localHead, remoteBranchHead)
            var pushError: String? = null
            if (pushNeeded) {
                runCatching {
                    git.push().setRemote("origin")
                        .setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/$branchName"))
                        .setCredentialsProvider(credentials).call()
                }.onFailure { pushError = it.message ?: "GitHub rejected the upload." }
            }
            val exported = folderUri?.let { SafFolderMirror(context).export(root, it, baseline) } ?: 0
            val message = when {
                pushError != null -> {
                    val permissionHint = if (pushError.orEmpty().contains("git-receive-pack", ignoreCase = true) ||
                        pushError.orEmpty().contains("permission", ignoreCase = true) ||
                        pushError.orEmpty().contains("403")) {
                        " (download succeeded; grant Contents: write to the token to upload local changes)"
                    } else ""
                    "Remote files downloaded; upload failed: $pushError$permissionHint"
                }
                !pushNeeded -> "Sync complete; no upload was needed."
                else -> "Sync complete"
            }
            SyncOutcome(message, git.repository.branch, changedFiles = imported + exported)
        }
    }

    suspend fun resolveConflicts(
        repo: RepoConfig,
        resolutions: Map<String, Boolean>,
        folderUri: Uri?,
        authorName: String = "igit",
        authorEmail: String = "igit@users.noreply.github.com"
    ): ConflictResolutionOutcome = withContext(Dispatchers.IO) {
        require(resolutions.isNotEmpty()) { "Choose a version for each conflicted file." }
        val root = File(context.filesDir, "worktrees/${repo.key}")
        val baseline = File(root.parentFile, "${repo.key}.mirror.json")
        val pendingExport = File(root.parentFile, "${repo.key}.mirror-pending")
        val currentBranch = Git.open(root).use { git ->
            val pending = git.status().call().conflicting
            require(resolutions.keys.all { Validation.isSafeRelativePath(it) && it in pending }) {
                "The conflict list changed. Sync again to refresh it."
            }
            require(resolutions.keys.containsAll(pending)) { "Choose a side for every conflicted file." }
            resolutions.forEach { (path, useLocal) ->
                val stage = if (useLocal) org.eclipse.jgit.api.CheckoutCommand.Stage.OURS
                    else org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS
                git.checkout().setStage(stage).addPath(path).call()
                git.add().addFilepattern(path).call()
            }
            require(git.status().call().conflicting.isEmpty()) { "Some conflicts remain unresolved." }
            git.commit().setMessage("Resolve sync conflicts")
                .setAuthor(authorName, authorEmail).call()
            pendingExport.writeText("pending")
            git.repository.branch
        }
        val exportError = if (folderUri == null) "Choose a local folder to export the resolved files."
        else runCatching {
            SafFolderMirror(context).export(root, folderUri, baseline)
            pendingExport.delete()
        }.exceptionOrNull()?.message
        ConflictResolutionOutcome(currentBranch, exportError)
    }

    suspend fun branches(repo: RepoConfig): List<String> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "worktrees/${repo.key}")
        if (!File(root, ".git").exists()) emptyList() else Git.open(root).use { git ->
            val local = git.branchList().call().map { it.name.removePrefix(Constants.R_HEADS) }
            val remotePrefix = Constants.R_REMOTES + "origin/"
            val remote = git.branchList()
                .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
                .call()
                .mapNotNull { ref -> ref.name.takeIf { it.startsWith(remotePrefix) }?.removePrefix(remotePrefix) }
                .filterNot { it == "HEAD" }
            (local + remote).distinct().sorted()
        }
    }

    suspend fun checkout(repo: RepoConfig, branch: String): String = withContext(Dispatchers.IO) {
        require(org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/$branch")) { "Enter a valid branch name." }
        val root = File(context.filesDir, "worktrees/${repo.key}")
        Git.open(root).use { git ->
            val exists = git.repository.exactRef("refs/heads/$branch") != null
            if (!exists) {
                val remoteRef = git.repository.exactRef("refs/remotes/origin/$branch") != null
                val current = git.repository.branch
                val upstream = git.repository.config.getString("branch", current, "merge")
                    ?.removePrefix("refs/heads/")
                val startPoint = if (remoteRef) "origin/$branch" else upstream?.let { "origin/$it" } ?: "HEAD"
                val create = git.branchCreate().setName(branch).setStartPoint(startPoint)
                if (remoteRef || upstream != null) {
                    create.setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                }
                create.call()
            }
            git.checkout().setName(branch).call()
            git.repository.branch
        }
    }

    suspend fun workingFiles(repo: RepoConfig): List<String> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "worktrees/${repo.key}")
        if (!root.exists()) emptyList() else root.walkTopDown()
            .filter { it.isFile && !it.toPath().startsWith(File(root, ".git").toPath()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .take(300)
            .toList()
    }

    private fun applyLocalFilters(root: File, filters: String) {
        val exclude = File(root, ".git/info/exclude")
        val start = "# igit filters begin"
        val end = "# igit filters end"
        val current = exclude.takeIf { it.isFile }?.readText().orEmpty()
        val prefix = if (current.contains(start) && current.contains(end)) {
            current.substringBefore(start).trimEnd()
        } else current.trimEnd()
        val patterns = filters.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && '\u0000' !in it }
            .toList()
        exclude.parentFile?.mkdirs()
        exclude.writeText(buildString {
            if (prefix.isNotBlank()) append(prefix).append('\n')
            append(start).append('\n')
            patterns.forEach { append(it).append('\n') }
            append(end).append('\n')
        })
    }
}

/** SAF export intentionally omits .git metadata. */
private class FolderMirror(private val context: Context) {
    suspend fun export(source: File, treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("The selected folder is no longer available. Re-select it in Settings.")
        var written = 0
        source.walkTopDown().filter { it.isFile && !it.toPath().startsWith(File(source, ".git").toPath()) }.forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath
            if (relative.startsWith(".git/")) return@forEach
            var parent = root
            val parts = relative.split('/')
            parts.dropLast(1).forEach { part ->
                parent = parent.findFile(part)?.takeIf { it.isDirectory } ?: parent.createDirectory(part)
                    ?: error("Could not create folder $part")
            }
            val name = parts.last()
            val target = parent.findFile(name)?.takeIf { it.isFile } ?: parent.createFile("application/octet-stream", name)
                ?: error("Could not create file $name")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Could not write $relative")
            written++
        }
        written
    }
}
