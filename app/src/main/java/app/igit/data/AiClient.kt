package app.igit.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiMessage(val role: String, val content: String)

/** Bring-your-own-key client for any OpenAI-compatible endpoint. Requests go straight from the device. */
class AiClient(private val baseUrl: String, private val apiKey: String, private val model: String) {
    fun ask(prompt: String, repositoryContext: String? = null, history: List<AiMessage> = emptyList()): String {
        val messages = mutableListOf(AiMessage("system", "You are a concise assistant for Git and software repositories. Never claim to have performed a repository action. Ask before proposing destructive actions."))
        if (!repositoryContext.isNullOrBlank()) messages += AiMessage("system", "Current repository context: $repositoryContext")
        messages += history.takeLast(12)
        messages += AiMessage("user", prompt)
        return complete(messages, 0.3)
    }

    /** "Wand" completion: drafts a commit message from a summary of the staged diff. */
    fun commitMessage(diffSummary: String, draft: String): String = complete(
        listOf(
            AiMessage("system", "Write a single Git commit message (imperative mood, max 72 character subject, optional short body). Reply with the message only, no quotes or code fences."),
            AiMessage("user", buildString {
                if (draft.isNotBlank()) append("Current draft: ").append(draft).append("\n\n")
                append("Changes:\n").append(diffSummary.take(12_000))
            })
        ),
        0.2
    ).trim().removePrefix("```").removeSuffix("```").trim()

    private fun complete(messages: List<AiMessage>, temperature: Double): String {
        require(Validation.isValidAiBaseUrl(baseUrl)) { "Use a valid HTTPS OpenAI-compatible API base URL." }
        require(apiKey.isNotBlank()) { "Add your AI API key in Global Settings first." }
        require(model.isNotBlank()) { "Enter a model name in Global Settings first." }
        val array = JSONArray()
        messages.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val payload = JSONObject().put("model", model).put("messages", array).put("temperature", temperature)

        val connection = URL("${baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 90_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }
                .getOrNull().orEmpty().ifBlank { "HTTP $status" }
            throw IllegalStateException("AI provider error: $detail")
        }
        val content = JSONObject(response).optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        return content?.takeIf { it.isNotBlank() } ?: "The provider returned an empty response."
    }
}
