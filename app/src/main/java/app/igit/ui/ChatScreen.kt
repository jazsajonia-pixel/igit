package app.igit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.igit.data.AiMessage
import app.igit.data.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ChatLine(val text: String, val fromUser: Boolean)

@Composable
fun ChatScreen(vm: IgitViewModel) {
    val aiEnabled = vm.aiEnabled
    val hasApiKey = vm.aiKeyStored
    val repoName = Validation.repoNameFromUrl(vm.active.remoteUrl).ifBlank { vm.active.alias }
    val branch = vm.branches.current ?: "-"
    val commits = vm.commits
    val onOpenSettings = { vm.navigate(Screen.GlobalSettings) }
    val messages = remember { mutableStateListOf<ChatLine>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Chat", style = MaterialTheme.typography.headlineSmall)
        if (!aiEnabled || !hasApiKey) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (!aiEnabled) "AI is turned off" else "Bring your own AI key", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (!aiEnabled) "Enable AI in Settings to use the chat."
                        else "Add a key and OpenAI-compatible endpoint in Settings to use the chat.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.Button(onClick = onOpenSettings) { Text("Open Settings") }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (messages.isEmpty()) item {
                    Text(
                        "Ask about $repoName. Requests go directly to your chosen AI endpoint and include the repository name, current branch, recent commit titles, and up to 60 local file paths—never file contents.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                items(messages) { line ->
                    Surface(
                        color = if (line.fromUser) ColorTokens.UserBubble else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(if (line.fromUser) 0.88f else 1f)
                    ) { Text(line.text, modifier = Modifier.padding(13.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                if (busy) item { CircularProgressIndicator(modifier = Modifier.size(22.dp)) }
                if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask about your repo…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                )
                IconButton(enabled = input.isNotBlank() && !busy, onClick = {
                    val prompt = input.trim()
                    input = ""
                    val history = messages.map { AiMessage(if (it.fromUser) "user" else "assistant", it.text) }
                    messages += ChatLine(prompt, true)
                    error = null
                    busy = true
                    scope.launch {
                        val answer = withContext(Dispatchers.IO) {
                            runCatching {
                                val filePaths = (if (vm.cloned) vm.runner.engine.workingFiles(vm.active) else emptyList())
                                    .filterNot { path ->
                                        val name = path.substringAfterLast('/').lowercase()
                                        name.startsWith(".env") || name.endsWith(".pem") || name.endsWith(".keystore") || name.contains("secret")
                                    }
                                    .take(60)
                                val repoContext = buildString {
                                    append("Repository: ").append(repoName).append('\n')
                                    append("Current branch: ").append(branch).append('\n')
                                    if (commits.isNotEmpty()) {
                                        append("Recent commits:\n")
                                        commits.take(5).forEach { append("- ").append(it.shortSha).append(" ").append(it.message).append(" (by ").append(it.author).append(")\n") }
                                    }
                                    if (filePaths.isNotEmpty()) {
                                        append("Local file paths (contents not provided):\n")
                                        filePaths.forEach { append("- ").append(it).append('\n') }
                                    }
                                }
                                vm.aiClient().ask(prompt, repoContext, history)
                            }
                        }
                        answer.onSuccess { messages += ChatLine(it, false) }.onFailure { error = it.message ?: "AI request failed." }
                        busy = false
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

private object ColorTokens {
    val UserBubble = androidx.compose.ui.graphics.Color(0xFF142033)
}
