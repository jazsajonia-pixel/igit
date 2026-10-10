package app.igit.git

import app.igit.data.AuthType
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.GpgSignature
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Signer
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.util.FS
import java.io.File

/** Resolved credentials for one container. */
data class GitAuth(
    val type: AuthType,
    val username: String = "",
    val token: String = "",
    val sshPrivateKey: String = "",
    val sshPassphrase: String = "",
    val knownHosts: File? = null
) {
    fun <C : TransportCommand<C, *>> applyTo(command: C): C {
        when (type) {
            AuthType.HTTPS -> if (token.isNotBlank()) {
                command.setCredentialsProvider(UsernamePasswordCredentialsProvider(username.ifBlank { "x-access-token" }, token))
            }
            AuthType.SSH -> {
                require(sshPrivateKey.isNotBlank()) { "Add or generate an SSH key in AUTH first." }
                val factory = InMemoryKeySessionFactory(sshPrivateKey, sshPassphrase.ifBlank { null }, knownHosts)
                command.setTransportConfigCallback { transport -> (transport as? SshTransport)?.sshSessionFactory = factory }
            }
            AuthType.NONE -> Unit
        }
        return command
    }
}

/** JSch session factory that uses a key held in memory and a private known_hosts file (trust on first use). */
private class InMemoryKeySessionFactory(
    private val privateKey: String,
    private val passphrase: String?,
    private val knownHosts: File?
) : JschConfigSessionFactory() {
    override fun configure(hc: OpenSshConfig.Host, session: Session) {
        session.setConfig("StrictHostKeyChecking", if (knownHosts != null) "accept-new" else "no")
        session.setConfig("PreferredAuthentications", "publickey")
    }

    override fun createDefaultJSch(fs: FS): JSch = JSch().apply {
        removeAllIdentity()
        addIdentity("igit", privateKey.toByteArray(Charsets.UTF_8), null, passphrase?.toByteArray(Charsets.UTF_8))
        knownHosts?.let { file ->
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
            setKnownHosts(file.absolutePath)
        }
    }
}

/** JGit [Signer] that produces SSH (SSHSIG) commit signatures with an Ed25519 key — GitSync's "Signed Commits". */
class SshCommitSigner(privateKeyPem: String) : Signer {
    private val key = SshKeys.ed25519Signer(privateKeyPem)

    override fun sign(
        repository: Repository,
        config: GpgConfig,
        data: ByteArray,
        committer: PersonIdent,
        signingKey: String?,
        credentialsProvider: CredentialsProvider?
    ): GpgSignature = GpgSignature(SshKeys.sshSign(key, data).toByteArray(Charsets.US_ASCII))

    override fun canLocateSigningKey(
        repository: Repository,
        config: GpgConfig,
        committer: PersonIdent,
        signingKey: String?,
        credentialsProvider: CredentialsProvider?
    ): Boolean = true
}
