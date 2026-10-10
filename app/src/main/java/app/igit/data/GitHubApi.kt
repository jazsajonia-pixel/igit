package app.igit.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitHubRepo(val owner: String, val name: String, val branch: String, val privateRepo: Boolean, val cloneUrl: String, val sshUrl: String) {
    val fullName: String get() = "$owner/$name"
}

data class GitHubTicket(
    val number: Int,
    val title: String,
    val state: String,
    val author: String,
    val url: String,
    val body: String = "",
    val comments: Int = 0,
    val updatedAt: String = "",
    val head: String = "",
    val base: String = "",
    val draft: Boolean = false
)

data class GitHubComment(val author: String, val body: String, val createdAt: String)

data class WorkflowRun(val id: Long, val name: String, val title: String, val status: String, val conclusion: String, val branch: String, val event: String, val url: String, val createdAt: String)

enum class TicketFilter(val label: String, val api: String) { OPEN("Open", "open"), CLOSED("Closed", "closed"), ALL("All", "all") }

/** Minimal GitHub REST client for the provider tools (issues, pull requests, workflow runs). */
class GitHubApi(private val token: String) {
    fun user(): String = (request("GET", "/user") as JSONObject).optString("login")

    fun listRepos(): List<GitHubRepo> {
        val json = request("GET", "/user/repos?per_page=100&sort=updated") as JSONArray
        return (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val owner = item.optJSONObject("owner")?.optString("login").orEmpty()
            val name = item.optString("name")
            if (owner.isBlank() || name.isBlank()) null else GitHubRepo(
                owner, name, item.optString("default_branch", "main"), item.optBoolean("private"),
                item.optString("clone_url", "https://github.com/$owner/$name.git"),
                item.optString("ssh_url", "git@github.com:$owner/$name.git")
            )
        }
    }

    fun issues(owner: String, repo: String, filter: TicketFilter): List<GitHubTicket> {
        val json = request("GET", "${base(owner, repo)}/issues?state=${filter.api}&per_page=50") as JSONArray
        return (0 until json.length()).mapNotNull { json.optJSONObject(it) }
            .filter { !it.has("pull_request") }
            .map(::ticket)
    }

    fun pullRequests(owner: String, repo: String, filter: TicketFilter): List<GitHubTicket> {
        val json = request("GET", "${base(owner, repo)}/pulls?state=${filter.api}&per_page=50") as JSONArray
        return (0 until json.length()).mapNotNull { json.optJSONObject(it)?.let(::ticket) }
    }

    fun issue(owner: String, repo: String, number: Int): GitHubTicket =
        ticket(request("GET", "${base(owner, repo)}/issues/$number") as JSONObject)

    fun pullRequest(owner: String, repo: String, number: Int): GitHubTicket =
        ticket(request("GET", "${base(owner, repo)}/pulls/$number") as JSONObject)

    fun comments(owner: String, repo: String, number: Int): List<GitHubComment> {
        val json = request("GET", "${base(owner, repo)}/issues/$number/comments?per_page=100") as JSONArray
        return (0 until json.length()).mapNotNull { json.optJSONObject(it) }.map {
            GitHubComment(it.optJSONObject("user")?.optString("login").orEmpty(), it.optString("body"), it.optString("created_at").take(10))
        }
    }

    fun addComment(owner: String, repo: String, number: Int, body: String) {
        request("POST", "${base(owner, repo)}/issues/$number/comments", JSONObject().put("body", body))
    }

    fun setState(owner: String, repo: String, number: Int, open: Boolean) {
        request("PATCH", "${base(owner, repo)}/issues/$number", JSONObject().put("state", if (open) "open" else "closed"))
    }

    fun createIssue(owner: String, repo: String, title: String, body: String): GitHubTicket =
        ticket(request("POST", "${base(owner, repo)}/issues", JSONObject().put("title", title).put("body", body)) as JSONObject)

    fun createPullRequest(owner: String, repo: String, title: String, body: String, head: String, base: String): GitHubTicket {
        val payload = JSONObject().put("title", title).put("body", body).put("head", head).put("base", base)
        return ticket(request("POST", "${base(owner, repo)}/pulls", payload) as JSONObject)
    }

    fun workflowRuns(owner: String, repo: String): List<WorkflowRun> {
        val json = request("GET", "${base(owner, repo)}/actions/runs?per_page=40") as JSONObject
        val runs = json.optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }.map {
            WorkflowRun(
                id = it.optLong("id"), name = it.optString("name"), title = it.optString("display_title"),
                status = it.optString("status"), conclusion = it.optString("conclusion").takeIf { c -> c != "null" }.orEmpty(),
                branch = it.optString("head_branch"), event = it.optString("event"), url = it.optString("html_url"),
                createdAt = it.optString("created_at").take(10)
            )
        }
    }

    private fun base(owner: String, repo: String) = "/repos/${segment(owner)}/${segment(repo)}"

    private fun ticket(item: JSONObject) = GitHubTicket(
        number = item.optInt("number"),
        title = item.optString("title"),
        state = if (item.optBoolean("merged", false) || item.optString("merged_at").let { it.isNotBlank() && it != "null" }) "merged" else item.optString("state", "open"),
        author = item.optJSONObject("user")?.optString("login").orEmpty(),
        url = item.optString("html_url"),
        body = item.optString("body").takeIf { it != "null" }.orEmpty(),
        comments = item.optInt("comments"),
        updatedAt = item.optString("updated_at").take(10),
        head = item.optJSONObject("head")?.optString("ref").orEmpty(),
        base = item.optJSONObject("base")?.optString("ref").orEmpty(),
        draft = item.optBoolean("draft")
    )

    private fun request(method: String, path: String, body: JSONObject? = null): Any {
        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        // HttpURLConnection rejects PATCH; GitHub honours the override header.
        if (method == "PATCH") {
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else connection.requestMethod = method
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
}
