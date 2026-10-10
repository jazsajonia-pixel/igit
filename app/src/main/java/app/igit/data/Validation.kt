package app.igit.data

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Validation {
    private val githubName = Regex("^[A-Za-z0-9_.-]{1,100}$")
    private val scpLike = Regex("^([A-Za-z0-9._-]+)@([A-Za-z0-9.-]+):(.+)$")

    fun isValidRepoPart(value: String): Boolean = githubName.matches(value.trim()) && value.trim() !in setOf(".", "..")

    fun isValidRepo(owner: String, name: String): Boolean = isValidRepoPart(owner) && isValidRepoPart(name)

    fun isValidAiBaseUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun isSafeRelativePath(value: String): Boolean = value.isNotBlank() &&
        !value.startsWith("/") &&
        value.split('/').none { it == ".." || it == "." || it.isBlank() } &&
        '\u0000' !in value

    /** Accepts https://, http://, ssh:// and scp-like (git@host:owner/repo.git) remotes, like GitSync's clone field. */
    fun isValidRemoteUrl(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank() || v.any { it.isWhitespace() }) return false
        if (scpLike.matches(v)) return true
        return runCatching {
            val uri = URI(v)
            uri.scheme in setOf("https", "http", "ssh", "git") && !uri.host.isNullOrBlank() && !uri.path.isNullOrBlank()
        }.getOrDefault(false)
    }

    fun isSshUrl(value: String): Boolean {
        val v = value.trim()
        return scpLike.matches(v) || v.startsWith("ssh://")
    }

    /** The auth type a remote URL needs (GitSync warns when auth and remote protocol don't match). */
    fun authFor(url: String): AuthType = if (isSshUrl(url)) AuthType.SSH else AuthType.HTTPS

    /** Extracts owner/repo for github.com remotes in either HTTPS or SSH form. */
    fun gitHubRepo(url: String): Pair<String, String>? {
        val v = url.trim()
        val path = scpLike.matchEntire(v)?.let { m ->
            if (!m.groupValues[2].equals("github.com", true)) return null
            m.groupValues[3]
        } ?: runCatching {
            val uri = URI(v)
            if (!uri.host.equals("github.com", true) && !uri.host.equals("www.github.com", true)) return null
            uri.path
        }.getOrNull() ?: return null
        val parts = path.trim('/').removeSuffix(".git").split('/')
        if (parts.size != 2 || !isValidRepo(parts[0], parts[1])) return null
        return parts[0] to parts[1]
    }

    /** Browser URL for a remote (for "Launch in Browser"). */
    fun browserUrl(url: String): String? {
        val v = url.trim()
        scpLike.matchEntire(v)?.let { m -> return "https://${m.groupValues[2]}/${m.groupValues[3].removeSuffix(".git")}" }
        return runCatching {
            val uri = URI(v)
            if (uri.host.isNullOrBlank()) null else "https://${uri.host}${uri.path.removeSuffix(".git")}"
        }.getOrNull()
    }

    fun repoNameFromUrl(url: String): String =
        url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git").ifBlank { "alias" }

    /**
     * Formats GitSync-style sync messages: `%s` is replaced with the current date/time.
     * The time format accepts strftime (`%d/%m/%y %I:%M %p`) or Java patterns (`yyyy-MM-dd HH:mm:ss`).
     */
    fun formatSyncMessage(template: String, timeFormat: String, now: Date = Date(), locale: Locale = Locale.getDefault()): String {
        val message = template.ifBlank { DEFAULT_SYNC_MESSAGE }
        if (!message.contains("%s")) return message
        val pattern = timeFormat.trim().ifBlank { DEFAULT_TIME_FORMAT }
        val formatted = runCatching {
            SimpleDateFormat(if (pattern.contains('%')) strftimeToJava(pattern) else pattern, locale).format(now)
        }.getOrElse { SimpleDateFormat(DEFAULT_TIME_FORMAT, locale).format(now) }
        return message.replace("%s", formatted)
    }

    fun strftimeToJava(format: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        fun flushLiteral() {
            if (literal.isEmpty()) return
            val text = literal.toString()
            if (text.any { it.isLetter() || it == '\'' }) out.append('\'').append(text.replace("'", "''")).append('\'') else out.append(text)
            literal.clear()
        }
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c == '%' && i + 1 < format.length) {
                val token = when (format[i + 1]) {
                    'Y' -> "yyyy"; 'y' -> "yy"; 'm' -> "MM"; 'd' -> "dd"; 'e' -> "d"
                    'H' -> "HH"; 'I' -> "hh"; 'M' -> "mm"; 'S' -> "ss"; 'p' -> "a"
                    'b', 'h' -> "MMM"; 'B' -> "MMMM"; 'a' -> "EEE"; 'A' -> "EEEE"
                    'j' -> "DDD"; 'Z' -> "zzz"; 'z' -> "Z"; 'F' -> "yyyy-MM-dd"; 'T' -> "HH:mm:ss"
                    '%' -> null
                    else -> null
                }
                if (token != null) { flushLiteral(); out.append(token) } else literal.append(if (format[i + 1] == '%') "%" else "%${format[i + 1]}")
                i += 2
            } else {
                literal.append(c); i++
            }
        }
        flushLiteral()
        return out.toString()
    }

    fun relativeTime(timeMillis: Long, now: Long = System.currentTimeMillis()): String {
        val seconds = ((now - timeMillis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            seconds < 2_592_000 -> "${seconds / 86_400}d ago"
            seconds < 31_536_000 -> "${seconds / 2_592_000}mo ago"
            else -> "${seconds / 31_536_000}y ago"
        }
    }
}
