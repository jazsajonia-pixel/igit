package app.igit.data

import java.net.URI

object Validation {
    private val githubName = Regex("^[A-Za-z0-9_.-]{1,100}$")

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
}
