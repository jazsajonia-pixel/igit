package app.igit.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder


data class GitHubRepo(val owner: String, val name: String, val branch: String, val privateRepo: Boolean) {
    val fullName: String get() = "$owner/$name"
    val cloneUrl: String get() = "https://github.com/$fullName.git"
}

data class CommitSummary(
    val sha: String,
    val message: String,
    val author: String,
    val relativeTime: String,
    val additions: Int = 0,
    val deletions: Int = 0
)

data class GitHubTicket(val number: Int, val title: String, val state: String, val author: String, val url: String)

class GitHubApi(private val token: String) {
    fun listRepos(): List<GitHubRepo> {
        val json = request("GET", "/user/repos?per_page=100&sort=updated") as JSONArray
        return (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val owner = item.optJSONObject("owner")?.optString("login").orEmpty()
            val name = item.optString("name")
            if (owner.isBlank() || name.isBlank()) null else GitHubRepo(
                owner, name, item.optString("default_branch", "main"), item.optBoolean("private")
            )
        }
    }

    fun commits(owner: String, repo: String, branch: String): List<CommitSummary> {
        val json = request("GET", "/repos/${segment(owner)}/${segment(repo)}/commits?sha=${query(branch)}&per_page=20") as JSONArray
        return (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val commit = item.optJSONObject("commit") ?: JSONObject()
            val author = commit.optJSONObject("author") ?: JSONObject()
            val stats = item.optJSONObject("stats") ?: JSONObject()
            CommitSummary(
                sha = item.optString("sha").take(7),
                message = commit.optString("message").lineSequence().firstOrNull().orEmpty(),
                author = author.optString("name", "Contributor"),
                relativeTime = commit.optJSONObject("author")?.optString("date").orEmpty().take(10),
                additions = stats.optInt("additions"),
                deletions = stats.optInt("deletions")
            )
        }
    }

    fun issues(owner: String, repo: String): List<GitHubTicket> = tickets(
        request("GET", "/repos/${segment(owner)}/${segment(repo)}/issues?state=open&per_page=30") as JSONArray
    ).filter { it.url.contains("/issues/") }

    fun pullRequests(owner: String, repo: String): List<GitHubTicket> = tickets(
        request("GET", "/repos/${segment(owner)}/${segment(repo)}/pulls?state=open&per_page=30") as JSONArray
    )

    fun createIssue(owner: String, repo: String, title: String, body: String): GitHubTicket {
        val payload = JSONObject().put("title", title).put("body", body)
        return ticket(request("POST", "/repos/${segment(owner)}/${segment(repo)}/issues", payload) as JSONObject)
    }

    fun createPullRequest(owner: String, repo: String, title: String, body: String, head: String, base: String): GitHubTicket {
        val payload = JSONObject().put("title", title).put("body", body).put("head", head).put("base", base)
        return ticket(request("POST", "/repos/${segment(owner)}/${segment(repo)}/pulls", payload) as JSONObject)
    }

    fun addComment(owner: String, repo: String, issueNumber: Int, body: String) {
        request("POST", "/repos/${segment(owner)}/${segment(repo)}/issues/$issueNumber/comments", JSONObject().put("body", body))
    }

    private fun tickets(array: JSONArray): List<GitHubTicket> = (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.let(::ticket)
    }

    private fun ticket(item: JSONObject) = GitHubTicket(
        number = item.optInt("number"),
        title = item.optString("title"),
        state = item.optString("state", "open"),
        author = item.optJSONObject("user")?.optString("login").orEmpty(),
        url = item.optString("html_url")
    )

    private fun request(method: String, path: String, body: JSONObject? = null): Any {
        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(response).optString("message") }.getOrDefault(response.take(200))
            throw IllegalStateException("GitHub request failed ($status): $detail")
        }
        return if (response.trimStart().startsWith("[")) JSONArray(response) else if (response.isBlank()) JSONObject() else JSONObject(response)
    }

    private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun query(value: String) = URLEncoder.encode(value, "UTF-8")
}
