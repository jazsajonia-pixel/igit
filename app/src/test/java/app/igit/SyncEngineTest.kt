package app.igit

import android.content.ContextWrapper
import app.igit.data.AuthType
import app.igit.data.RepoContainer
import app.igit.git.ConflictText
import app.igit.git.GitAuth
import app.igit.git.GitSyncEngine
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Two "devices" syncing through a local bare repository, end to end through GitSyncEngine. */
class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val none = GitAuth(AuthType.NONE)

    private fun engine(dir: File) = GitSyncEngine(object : ContextWrapper(null) { override fun getFilesDir(): File = dir })

    @Test fun syncsCommitsBetweenDevicesAndResolvesConflicts() = runBlocking {
        val bare = tmp.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        val url = bare.toURI().toString()

        val engineA = engine(tmp.newFolder("deviceA"))
        val a = RepoContainer(id = "a", remoteUrl = url, authorName = "A", authorEmail = "a@x")
        engineA.initRepository(a)
        File(engineA.worktree(a), "notes.md").writeText("line1\n")
        val first = engineA.sync(a, none)
        assertEquals("Repository synced!", first.message)
        assertTrue(engineA.log(a).first().message.startsWith("Last Sync: "))
        assertTrue(engineA.sync(a, none).notRequired)

        // Device B clones the same remote
        val engineB = engine(tmp.newFolder("deviceB"))
        val b = RepoContainer(id = "b", remoteUrl = url, authorName = "B", authorEmail = "b@x")
        Git.cloneRepository().setURI(url).setDirectory(engineB.worktree(b)).call().close()
        assertEquals("line1\n", File(engineB.worktree(b), "notes.md").readText())

        // Non-conflicting edits merge automatically
        File(engineA.worktree(a), "a.txt").writeText("from a\n")
        engineA.sync(a, none, messageOverride = "add a")
        File(engineB.worktree(b), "b.txt").writeText("from b\n")
        val merged = engineB.sync(b, none)
        assertTrue(merged.conflicts.isEmpty())
        assertTrue(File(engineB.worktree(b), "a.txt").exists())
        engineA.sync(a, none)
        assertTrue(File(engineA.worktree(a), "b.txt").exists())

        // Same line edited on both devices -> conflict, resolve, finish, sync
        File(engineA.worktree(a), "notes.md").writeText("from A\n")
        engineA.sync(a, none)
        File(engineB.worktree(b), "notes.md").writeText("from B\n")
        val conflict = engineB.sync(b, none)
        assertEquals(listOf("notes.md"), conflict.conflicts)
        assertTrue(engineB.isMerging(b))
        val text = engineB.readFile(b, "notes.md")
        assertTrue(ConflictText.hasMarkers(text))
        engineB.resolveFile(b, "notes.md", ConflictText.resolveAll(text, "both"))
        engineB.finishMerge(b, null)
        assertEquals("Repository synced!", engineB.sync(b, none).message)
        engineA.sync(a, none)
        assertEquals("from B\nfrom A\n", File(engineA.worktree(a), "notes.md").readText())

        // Force pull discards local-only work
        File(engineA.worktree(a), "notes.md").writeText("local junk\n")
        engineA.forcePull(a, none)
        assertEquals("from B\nfrom A\n", File(engineA.worktree(a), "notes.md").readText())

        // Branches
        engineA.createBranch(a, "feature", null)
        assertTrue("feature" in engineA.branches(a).local)
        engineA.checkout(a, "main")
        assertEquals("main", engineA.branches(a).current)
    }
}
