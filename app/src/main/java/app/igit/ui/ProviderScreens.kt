package app.igit.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.igit.data.GitHubApi
import app.igit.data.GitHubComment
import app.igit.data.GitHubTicket
import app.igit.data.TicketFilter
import app.igit.data.WorkflowRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun IgitViewModel.api(): Pair<GitHubApi, Pair<String, String>>? {
    val repo = gitHubRepo ?: return null
    val token = gitHubToken ?: return null
    return GitHubApi(token) to repo
}

private fun IgitViewModel.withFooter(body: String): String =
    if (active.postFooter.isBlank()) body else "$body\n\n${active.postFooter}"

@Composable
fun TicketsScreen(vm: IgitViewModel, pullRequests: Boolean) {
    var filter by remember { mutableStateOf(TicketFilter.OPEN) }
    var items by remember { mutableStateOf<List<GitHubTicket>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(filter, reload) {
        items = null; error = null
        val (api, repo) = vm.api() ?: run { error = "Connect a GitHub token in AUTH."; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            runCatching { if (pullRequests) api.pullRequests(repo.first, repo.second, filter) else api.issues(repo.first, repo.second, filter) }
        }.onSuccess { items = it }.onFailure { error = it.message }
    }
    PageScaffold(if (pullRequests) "Pull Requests" else "Issues", onBack = { vm.back() }, actions = {
        IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        IconButton(onClick = { vm.navigate(Screen.CreateTicket(pullRequests)) }) { Icon(Icons.Default.Add, contentDescription = if (pullRequests) "Create Pull Request" else "Create Issue") }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TicketFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
        }
        when {
            error != null -> Text(error.orEmpty(), color = IgitColors.Danger)
            items == null -> LoadingBox()
            items!!.isEmpty() -> Text(if (pullRequests) "No pull requests found…" else "No issues found…", color = IgitColors.Secondary)
            else -> items!!.forEach { t ->
                IgitCard(modifier = Modifier.fillMaxWidth(), onClick = { vm.navigate(Screen.TicketDetail(t.number, pullRequests)) }) {
                    Text("#${t.number} · ${t.title}", style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(stateColor(t.state))
                        Spacer(Modifier.size(6.dp))
                        Text("${t.state} · ${t.author} · updated ${t.updatedAt}${if (t.comments > 0) " · ${t.comments} comments" else ""}${if (t.draft) " · DRAFT" else ""}",
                            color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun stateColor(state: String): Color = when (state) {
    "open" -> Color(0xFF7DE2A2)
    "merged" -> Color(0xFFC79BFF)
    else -> Color(0xFFFF8585)
}

@Composable
fun TicketDetailScreen(vm: IgitViewModel, number: Int, pullRequest: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ticket by remember { mutableStateOf<GitHubTicket?>(null) }
    var comments by remember { mutableStateOf<List<GitHubComment>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(number, reload) {
        val (api, repo) = vm.api() ?: run { error = "Connect a GitHub token in AUTH."; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            runCatching {
                val t = if (pullRequest) api.pullRequest(repo.first, repo.second, number) else api.issue(repo.first, repo.second, number)
                t to api.comments(repo.first, repo.second, number)
            }
        }.onSuccess { (t, c) -> ticket = t; comments = c }.onFailure { error = it.message }
    }
    PageScaffold("#$number", onBack = { vm.back() }, actions = {
        ticket?.url?.takeIf { it.isNotBlank() }?.let { url ->
            IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Launch in Browser") }
        }
    }) {
        val t = ticket
        when {
            error != null -> Text(error.orEmpty(), color = IgitColors.Danger)
            t == null -> LoadingBox()
            else -> {
                IgitCard(modifier = Modifier.fillMaxWidth()) {
                    Text(t.title, style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(stateColor(t.state)); Spacer(Modifier.size(6.dp))
                        Text("${t.state} · ${t.author}${if (pullRequest) " · ${t.head} → ${t.base}" else ""}", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(t.body.ifBlank { "No description provided" }, style = MaterialTheme.typography.bodyMedium)
                }
                Text("Comments", style = MaterialTheme.typography.titleMedium)
                if (comments.isEmpty()) Text("No comments yet", color = IgitColors.Secondary)
                comments.forEach { c ->
                    IgitCard(modifier = Modifier.fillMaxWidth()) {
                        Text("${c.author} · ${c.createdAt}", color = IgitColors.Secondary, style = MaterialTheme.typography.labelMedium)
                        Text(c.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(reply, { reply = it }, label = { Text("Add a comment…") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val (api, repo) = vm.api() ?: return@Button
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { api.addComment(repo.first, repo.second, number, vm.withFooter(reply)) } }
                                .onSuccess { reply = ""; vm.toast("Comment added"); reload++ }.onFailure { vm.toast(it.message ?: "Failed to add comment") }
                        }
                    }, enabled = reply.isNotBlank()) { Text("Comment") }
                    if (t.state != "merged") OutlinedButton(onClick = {
                        val (api, repo) = vm.api() ?: return@OutlinedButton
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { api.setState(repo.first, repo.second, number, open = t.state != "open") } }
                                .onSuccess { vm.toast("State updated"); reload++ }.onFailure { vm.toast(it.message ?: "Failed to update state") }
                        }
                    }) { Text(if (t.state == "open") (if (pullRequest) "Close" else "Close Issue") else (if (pullRequest) "Reopen" else "Reopen Issue")) }
                }
            }
        }
    }
}

@Composable
fun CreateTicketScreen(vm: IgitViewModel, pullRequest: Boolean) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var head by remember { mutableStateOf(vm.branches.current ?: "") }
    var base by remember { mutableStateOf(vm.branches.all.firstOrNull { it == "main" || it == "master" } ?: "main") }
    var working by remember { mutableStateOf(false) }
    PageScaffold(if (pullRequest) "Create Pull Request" else "Create Issue", onBack = { vm.back() }) {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("Description") }, minLines = 5, modifier = Modifier.fillMaxWidth())
        if (pullRequest) {
            Text("Base ← Compare", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(base, { base = it.trim() }, label = { Text("Base") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(head, { head = it.trim() }, label = { Text("Compare") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("Push the compare branch before creating the pull request.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = {
            val (api, repo) = vm.api() ?: return@Button
            working = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (pullRequest) api.createPullRequest(repo.first, repo.second, title, vm.withFooter(body), head, base)
                        else api.createIssue(repo.first, repo.second, title, vm.withFooter(body))
                    }
                }.onSuccess { vm.toast("Created #${it.number}"); vm.back() }.onFailure { vm.toast(it.message ?: "Failed") }
                working = false
            }
        }, enabled = title.isNotBlank() && !working && (!pullRequest || (head.isNotBlank() && base.isNotBlank())), modifier = Modifier.fillMaxWidth()) {
            Text(if (pullRequest) "Create Pull Request" else "Submit Issue")
        }
    }
}

@Composable
fun ActionsScreen(vm: IgitViewModel) {
    val context = LocalContext.current
    var runs by remember { mutableStateOf<List<WorkflowRun>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("All") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        runs = null; error = null
        val (api, repo) = vm.api() ?: run { error = "Connect a GitHub token in AUTH."; return@LaunchedEffect }
        withContext(Dispatchers.IO) { runCatching { api.workflowRuns(repo.first, repo.second) } }.onSuccess { runs = it }.onFailure { error = it.message }
    }
    PageScaffold("Actions", onBack = { vm.back() }, actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") } }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Success", "Failed").forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
        }
        val list = runs?.filter {
            when (filter) { "Success" -> it.conclusion == "success"; "Failed" -> it.conclusion in setOf("failure", "timed_out", "cancelled"); else -> true }
        }
        when {
            error != null -> Text(error.orEmpty(), color = IgitColors.Danger)
            list == null -> LoadingBox()
            list.isEmpty() -> Text("No actions found…", color = IgitColors.Secondary)
            else -> list.forEach { run ->
                IgitCard(modifier = Modifier.fillMaxWidth(), onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(run.url))) } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(when { run.status != "completed" -> Color(0xFFFFD28D); run.conclusion == "success" -> Color(0xFF7DE2A2); else -> Color(0xFFFF8585) })
                        Spacer(Modifier.size(8.dp))
                        Text(run.title.ifBlank { run.name }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    }
                    Text("${run.name} · ${run.branch} · ${run.event} · ${run.createdAt} · ${if (run.status == "completed") run.conclusion else run.status}",
                        color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
