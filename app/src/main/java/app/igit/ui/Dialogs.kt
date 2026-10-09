package app.igit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.igit.data.GitHubRepo
import app.igit.data.GitHubTicket
import app.igit.data.RepoConfig

@Composable
fun ConnectRepoDialog(
    existingToken: String,
    repositories: List<GitHubRepo>,
    loading: Boolean,
    error: String?,
    onLoad: (String) -> Unit,
    onChoose: (String, GitHubRepo) -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf(existingToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use a GitHub personal access token. Read-only access can download repositories. To upload local edits, grant Contents: read and write for the selected repository. For private repositories, limit access to the repos you need.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { onLoad(token) }, enabled = token.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Load my repositories")
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (repositories.isNotEmpty()) {
                    Text("Choose a repository", style = MaterialTheme.typography.labelLarge)
                    LazyColumn(modifier = Modifier.height(210.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(repositories, key = { it.fullName }) { repo ->
                            Surface(
                                onClick = { onChoose(token, repo) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(11.dp)) {
                                    Text(repo.fullName, style = MaterialTheme.typography.bodyLarge)
                                    Text(if (repo.privateRepo) "Private · ${repo.branch}" else "Public · ${repo.branch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun SettingsDialog(
    baseUrl: String,
    model: String,
    interval: Long,
    scheduled: Boolean,
    aiEnabled: Boolean,
    keyStored: Boolean,
    onSave: (String, String, String, Long, Boolean, Boolean) -> Unit,
    onForgetGitHub: () -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(baseUrl) }
    var modelName by remember { mutableStateOf(model) }
    var apiKey by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(interval.toString()) }
    var isScheduled by remember { mutableStateOf(scheduled) }
    var aiOn by remember { mutableStateOf(aiEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Sync Settings") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(480.dp)) {
                item {
                    Text("AI · bring your own key", style = MaterialTheme.typography.titleMedium)
                    Text("Requests go directly from this device to your configured OpenAI-compatible endpoint. The key is encrypted on device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI features", modifier = Modifier.weight(1f))
                        Switch(checked = aiOn, onCheckedChange = { aiOn = it })
                    }
                }
                item { OutlinedTextField(url, { url = it }, label = { Text("API base URL (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(modelName, { modelName = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        apiKey,
                        { apiKey = it },
                        label = { Text(if (keyStored) "Replace API key (leave blank to keep)" else "API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item { Spacer(Modifier.height(4.dp)); Text("Scheduled sync", style = MaterialTheme.typography.titleMedium) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync periodically")
                            Text("Android enforces a 15-minute minimum.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = isScheduled, onCheckedChange = { isScheduled = it })
                    }
                }
                item {
                    OutlinedTextField(
                        minutes,
                        { minutes = it.filter(Char::isDigit).take(5) },
                        label = { Text("Interval in minutes (15–10080)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    TextButton(onClick = onForgetGitHub) { Text("Forget GitHub token") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(url, modelName, apiKey, minutes.toLongOrNull() ?: 60L, isScheduled, aiOn) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun BranchDialog(
    branch: String,
    branches: List<String>,
    loading: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newBranch by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branches") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(branches, key = { it }) { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSelect(item) }.padding(vertical = 10.dp)) {
                            Text(item, modifier = Modifier.weight(1f))
                            if (item == branch) Text("CURRENT", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedTextField(newBranch, { newBranch = it }, label = { Text("New branch name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (newBranch.isNotBlank()) onCreate(newBranch.trim()) }) { Text("Create & switch") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun ConflictResolutionDialog(
    paths: List<String>,
    onResolve: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit
) {
    val choices = remember(paths) { mutableStateMapOf<String, Boolean>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resolve sync conflicts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a version for each file. Local means the current device branch; GitHub means the fetched remote version. Nothing is pushed until you finish.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(modifier = Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(paths, key = { it }) { path ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(path, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { choices[path] = true }) { Text(if (choices[path] == true) "✓ Keep local" else "Keep local") }
                                TextButton(onClick = { choices[path] = false }) { Text(if (choices[path] == false) "✓ Use GitHub" else "Use GitHub") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(choices.toMap()) }, enabled = paths.isNotEmpty() && choices.size == paths.size) { Text("Resolve files") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

@Composable
fun TicketsDialog(
    panel: RepositoryPanel,
    items: List<GitHubTicket>,
    loading: Boolean,
    error: String?,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(panel.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                if (!loading && items.isEmpty() && error == null) Text("No open ${panel.title.lowercase()}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(modifier = Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(items, key = { it.number }) { item ->
                        Surface(onClick = { if (item.url.isNotBlank()) onOpen(item.url) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(11.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("#${item.number} · ${item.title}", style = MaterialTheme.typography.bodyMedium)
                                    Text("${item.author} · ${item.state}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.OpenInNew, contentDescription = "Open on GitHub", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCreate) { Text(if (panel == RepositoryPanel.ISSUES) "New issue" else "New pull request") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun CreateRecordDialog(panel: RepositoryPanel, onSubmit: (String, String, String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var head by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("main") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (panel == RepositoryPanel.ISSUES) "Create issue" else "Create pull request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("Description") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (panel == RepositoryPanel.PULL_REQUESTS) {
                    OutlinedTextField(head, { head = it }, label = { Text("Head branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(base, { base = it }, label = { Text("Base branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (title.isNotBlank()) onSubmit(title.trim(), body, head, base) }) { Text("Create on GitHub") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FilterDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var patterns by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Git Filters") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One relative path or glob per line. These patterns are stored as app settings for the selected working copy.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(patterns, { patterns = it }, label = { Text("Patterns") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(patterns) }) { Text("Save filters") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
