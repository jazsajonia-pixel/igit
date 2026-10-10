package app.igit.data

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted configuration backup (GitSync's "Encrypted Backup / Encrypted Restore").
 * Format: JSON envelope {format, salt, iv, data} with PBKDF2-SHA256 + AES-256-GCM.
 */
object Backup {
    private const val ITERATIONS = 210_000
    private const val FORMAT = "igit-backup-v1"

    fun encrypt(payload: JSONObject, password: String): String {
        require(password.length >= 6) { "Use a password of at least 6 characters." }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", FORMAT)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            .toString()
    }

    fun decrypt(text: String, password: String): JSONObject {
        val envelope = runCatching { JSONObject(text) }.getOrElse { error("This is not an igit backup file.") }
        require(envelope.optString("format") == FORMAT) { "This is not an igit backup file." }
        val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val plain = runCatching { cipher.doFinal(data) }.getOrElse { error("Invalid Password") }
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
