package app.igit.git

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class SshKeyPair(val privateKeyPem: String, val publicKeyLine: String)

/** OpenSSH key helpers: Ed25519 generation (GitSync's "generate keys"), import and SSHSIG commit signatures. */
object SshKeys {
    private const val PEM_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val PEM_END = "-----END OPENSSH PRIVATE KEY-----"

    fun generateEd25519(comment: String = "igit"): SshKeyPair {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val blob = OpenSSHPrivateKeyUtil.encodePrivateKey(privateKey)
        val pem = buildString {
            append(PEM_BEGIN).append('\n')
            Base64.getEncoder().encodeToString(blob).chunked(70).forEach { append(it).append('\n') }
            append(PEM_END).append('\n')
        }
        return SshKeyPair(pem, publicLine(OpenSSHPublicKeyUtil.encodePublicKey(privateKey.generatePublicKey()), comment))
    }

    /** Validates a private key (any type JSch supports) and derives its public key line. */
    fun publicKeyFor(privateKeyPem: String, passphrase: String?): String {
        val pair = KeyPair.load(JSch(), privateKeyPem.toByteArray(Charsets.UTF_8), null)
        try {
            if (pair.isEncrypted) {
                require(!passphrase.isNullOrEmpty()) { "This key is encrypted. Enter its passphrase." }
                require(pair.decrypt(passphrase)) { "Incorrect passphrase for this key." }
            }
            val blob = pair.publicKeyBlob ?: error("Could not read the public key from this private key.")
            return publicLine(blob, "igit")
        } finally {
            pair.dispose()
        }
    }

    fun fingerprint(publicKeyLine: String): String = runCatching {
        val blob = Base64.getDecoder().decode(publicKeyLine.trim().split(Regex("\\s+"))[1])
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }.getOrDefault("")

    /** Signing needs the raw Ed25519 key; only unencrypted OpenSSH Ed25519 keys are supported (as in most mobile clients). */
    fun ed25519Signer(privateKeyPem: String): Ed25519PrivateKeyParameters {
        val body = privateKeyPem.substringAfter(PEM_BEGIN, "").substringBefore(PEM_END, "").replace(Regex("\\s"), "")
        require(body.isNotBlank()) { "Commit signing needs an OpenSSH private key." }
        val params = runCatching { OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(Base64.getDecoder().decode(body)) }
            .getOrElse { throw IllegalArgumentException("Commit signing supports unencrypted Ed25519 keys only.") }
        return params as? Ed25519PrivateKeyParameters
            ?: throw IllegalArgumentException("Commit signing supports Ed25519 keys only.")
    }

    /** Produces an armored SSHSIG signature (namespace "git"), the format `git verify-commit` expects for gpg.format=ssh. */
    fun sshSign(key: Ed25519PrivateKeyParameters, data: ByteArray): String {
        val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(key.generatePublicKey())
        val hash = MessageDigest.getInstance("SHA-512").digest(data)
        val toSign = ByteArrayOutputStream().also { out ->
            out.write("SSHSIG".toByteArray())
            DataOutputStream(out).apply {
                sshString("git".toByteArray()); sshString(ByteArray(0)); sshString("sha512".toByteArray()); sshString(hash)
            }
        }.toByteArray()
        val signer = Ed25519Signer().apply { init(true, key); update(toSign, 0, toSign.size) }
        val rawSignature = signer.generateSignature()
        val signatureBlob = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply { sshString("ssh-ed25519".toByteArray()); sshString(rawSignature) }
        }.toByteArray()
        val envelope = ByteArrayOutputStream().also { out ->
            out.write("SSHSIG".toByteArray())
            DataOutputStream(out).apply {
                writeInt(1)
                sshString(publicBlob); sshString("git".toByteArray()); sshString(ByteArray(0))
                sshString("sha512".toByteArray()); sshString(signatureBlob)
            }
        }.toByteArray()
        return buildString {
            append("-----BEGIN SSH SIGNATURE-----\n")
            Base64.getEncoder().encodeToString(envelope).chunked(70).forEach { append(it).append('\n') }
            append("-----END SSH SIGNATURE-----")
        }
    }

    /** Test hook: verify an Ed25519 SSHSIG produced by [sshSign]. */
    internal fun verify(publicKey: Ed25519PublicKeyParameters, data: ByteArray, rawSignature: ByteArray): Boolean {
        val hash = MessageDigest.getInstance("SHA-512").digest(data)
        val signed = ByteArrayOutputStream().also { out ->
            out.write("SSHSIG".toByteArray())
            DataOutputStream(out).apply {
                sshString("git".toByteArray()); sshString(ByteArray(0)); sshString("sha512".toByteArray()); sshString(hash)
            }
        }.toByteArray()
        return Ed25519Signer().apply { init(false, publicKey); update(signed, 0, signed.size) }.verifySignature(rawSignature)
    }

    private fun publicLine(blob: ByteArray, comment: String): String {
        val type = String(blob, 4, ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff))
        return "$type ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    private fun DataOutputStream.sshString(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
}
