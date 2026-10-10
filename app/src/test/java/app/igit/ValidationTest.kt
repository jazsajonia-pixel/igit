package app.igit

import app.igit.data.AuthType
import app.igit.data.Validation
import app.igit.git.ConflictText
import app.igit.git.SshKeys
import app.igit.git.shouldPush
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ValidationTest {
    @Test fun acceptsValidRepositoryParts() {
        assertTrue(Validation.isValidRepo("owner-name", "project_1"))
        assertFalse(Validation.isValidRepo("../owner", "repo"))
    }

    @Test fun requiresHttpsAiEndpoints() {
        assertTrue(Validation.isValidAiBaseUrl("https://api.example.com/v1"))
        assertFalse(Validation.isValidAiBaseUrl("http://api.example.com/v1"))
        assertFalse(Validation.isValidAiBaseUrl("https://user:pass@example.com/v1"))
    }

    @Test fun rejectsUnsafeRelativePaths() {
        assertTrue(Validation.isSafeRelativePath("src/main.kt"))
        assertFalse(Validation.isSafeRelativePath("../../secrets"))
        assertFalse(Validation.isSafeRelativePath("/absolute/path"))
    }

    @Test fun onlyPushesWhenLocalBranchDiffersFromRemoteBranch() {
        assertFalse(shouldPush("abc123", "abc123"))
        assertTrue(shouldPush("def456", "abc123"))
        assertTrue(shouldPush("abc123", null))
        assertFalse(shouldPush(null, null))
    }

    @Test fun validatesRemoteUrlsForAnyProvider() {
        assertTrue(Validation.isValidRemoteUrl("https://github.com/owner/repo.git"))
        assertTrue(Validation.isValidRemoteUrl("https://gitlab.example.org/group/sub/repo"))
        assertTrue(Validation.isValidRemoteUrl("git@codeberg.org:owner/repo.git"))
        assertTrue(Validation.isValidRemoteUrl("ssh://git@git.example.com:2222/repo.git"))
        assertFalse(Validation.isValidRemoteUrl("not a url"))
        assertFalse(Validation.isValidRemoteUrl("ftp://example.com/repo"))
    }

    @Test fun picksAuthTypeFromRemoteProtocol() {
        assertEquals(AuthType.SSH, Validation.authFor("git@github.com:o/r.git"))
        assertEquals(AuthType.SSH, Validation.authFor("ssh://git@host/r.git"))
        assertEquals(AuthType.HTTPS, Validation.authFor("https://github.com/o/r"))
    }

    @Test fun extractsGitHubOwnerAndRepo() {
        assertEquals("o" to "r", Validation.gitHubRepo("https://github.com/o/r.git"))
        assertEquals("o" to "r", Validation.gitHubRepo("git@github.com:o/r.git"))
        assertNull(Validation.gitHubRepo("https://gitlab.com/o/r.git"))
        assertEquals("https://gitlab.com/o/r", Validation.browserUrl("git@gitlab.com:o/r.git"))
        assertEquals("repo", Validation.repoNameFromUrl("https://github.com/o/repo.git"))
    }

    @Test fun formatsGitSyncStyleSyncMessages() {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { set(2026, Calendar.MARCH, 4, 15, 7, 9) }
        val now = cal.time
        assertEquals("Last Sync: 2026-03-04 15:07:09 (Mobile)", Validation.formatSyncMessage("Last Sync: %s (Mobile)", "yyyy-MM-dd HH:mm:ss", now, Locale.US))
        assertEquals("sync 04/03/26 03:07 PM", Validation.formatSyncMessage("sync %s", "%d/%m/%y %I:%M %p", now, Locale.US))
        assertEquals("no date", Validation.formatSyncMessage("no date", "yyyy", now, Locale.US))
        assertEquals("yyyy-MM-dd'T'HH:mm", Validation.strftimeToJava("%Y-%m-%dT%H:%M"))
    }

    @Test fun resolvesConflictBlocks() {
        val text = "a\n<<<<<<< HEAD\nmine\n=======\ntheirs\n>>>>>>> origin/main\nb\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> origin/main"
        assertTrue(ConflictText.hasMarkers(text))
        assertEquals(2, ConflictText.blockCount(text))
        assertEquals("a\nmine\nb\nx", ConflictText.resolveAll(text, "current"))
        assertEquals("a\ntheirs\nb\ny", ConflictText.resolveAll(text, "incoming"))
        assertEquals("a\nmine\ntheirs\nb\nx\ny", ConflictText.resolveAll(text, "both"))
        val partial = ConflictText.resolveBlock(text, 0, "incoming")
        assertTrue(partial.startsWith("a\ntheirs\nb\n<<<<<<<"))
        assertEquals(1, ConflictText.blockCount(partial))
    }

    @Test fun generatesKeysAndProducesVerifiableSshSignatures() {
        val pair = SshKeys.generateEd25519("test")
        assertTrue(pair.publicKeyLine.startsWith("ssh-ed25519 "))
        assertTrue(SshKeys.fingerprint(pair.publicKeyLine).startsWith("SHA256:"))
        val key: Ed25519PrivateKeyParameters = SshKeys.ed25519Signer(pair.privateKeyPem)
        val data = "tree abc\nauthor me\n\nmessage\n".toByteArray()
        val armored = SshKeys.sshSign(key, data)
        assertTrue(armored.startsWith("-----BEGIN SSH SIGNATURE-----"))
        val body = armored.lines().filterNot { it.startsWith("-----") }.joinToString("")
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(body)))
        val magic = ByteArray(6).also { input.readFully(it) }
        assertEquals("SSHSIG", String(magic))
        assertEquals(1, input.readInt())
        fun str(s: DataInputStream) = ByteArray(s.readInt()).also { s.readFully(it) }
        str(input); assertEquals("git", String(str(input))); str(input); assertEquals("sha512", String(str(input)))
        val sig = DataInputStream(ByteArrayInputStream(str(input)))
        assertEquals("ssh-ed25519", String(str(sig)))
        val raw = str(sig)
        assertTrue(SshKeys.verify(key.generatePublicKey(), data, raw))
        assertFalse(SshKeys.verify(key.generatePublicKey(), "tampered".toByteArray(), raw))
    }
}
