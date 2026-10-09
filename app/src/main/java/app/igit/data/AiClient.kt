package app.igit.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiClient(private val baseUrl: String, private val apiKey: String, private val model: String) {
    fun ask(prompt: String, repositoryContext: String? = null): String {
        require(Validation.isValidAiBaseUrl(baseUrl)) { "Use a valid HTTPS OpenAI-compatible API base URL." }
        require(apiKey.isNotBlank()) { "Add your AI API key in Settings first." }
        require(model.isNotBlank()) { "Enter a model name in Settings first." }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are a concise assistant for Git and software repositories. Never claim to have performed a repository action. Ask before proposing destructive actions."))
        if (!repositoryContext.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", "Current repository context: $repositoryContext"))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val payload = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.3)

        val connection = URL("${baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
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
