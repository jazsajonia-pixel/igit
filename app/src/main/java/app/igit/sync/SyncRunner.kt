package app.igit.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.igit.data.AuthType
import app.igit.data.ContainerStore
import app.igit.data.RepoContainer
import app.igit.data.Secrets
import app.igit.data.Validation
import app.igit.git.GitAuth
import app.igit.git.GitSyncEngine
import app.igit.git.SshCommitSigner
import app.igit.git.SyncOutcome
import app.igit.security.SecretVault
import org.eclipse.jgit.lib.Signer

/** Resolves credentials/signing for a container and runs syncs. Shared by the UI, worker, tiles and intents. */
class SyncRunner(private val context: Context) {
    val store = ContainerStore(context)
    val vault = SecretVault(context)
    val engine = GitSyncEngine(context)

    fun auth(c: RepoContainer): GitAuth = when (c.authType) {
        AuthType.HTTPS -> GitAuth(AuthType.HTTPS, c.username, vault.get(Secrets.token(c.id)).orEmpty())
        AuthType.SSH -> GitAuth(
            AuthType.SSH,
            sshPrivateKey = vault.get(Secrets.sshKey(c.id)).orEmpty(),
            sshPassphrase = vault.get(Secrets.sshPassphrase(c.id)).orEmpty()
        )
        AuthType.NONE -> GitAuth(AuthType.NONE)
    }

    /** SSH commit signer when Signed Commits is on. Throws a readable error when the key can't sign. */
    fun signer(c: RepoContainer): Signer? {
        if (!c.signedCommits) return null
        val key = if (c.signingUsesAuthKey) vault.get(Secrets.sshKey(c.id)) else vault.get(Secrets.signingKey(c.id))
        require(!key.isNullOrBlank()) { "Signed Commits is on but no signing key is set. Import one in Repository Settings." }
        return SshCommitSigner(key)
    }

    /** GitHub token for provider tools: a dedicated token, or the HTTPS token when the remote is on github.com. */
    fun gitHubToken(c: RepoContainer): String? =
        vault.get(Secrets.githubToken(c.id))?.takeIf { it.isNotBlank() }
            ?: vault.get(Secrets.token(c.id))?.takeIf { it.isNotBlank() && c.authType == AuthType.HTTPS && Validation.gitHubRepo(c.remoteUrl) != null }

    suspend fun sync(c: RepoContainer, message: String? = null, progress: (String) -> Unit = {}): SyncOutcome =
        engine.sync(c, auth(c), signer(c), message, progress)

    fun toast(c: RepoContainer, text: String) {
        if (!c.showSyncMessages) return
        Handler(Looper.getMainLooper()).post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
