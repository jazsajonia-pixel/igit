package app.igit.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BrowserEntry(val file: DocumentFile, val name: String, val isDirectory: Boolean)

@Composable
fun FilesScreen(context: Context, treeUri: Uri?, onPickFolder: () -> Unit) {
    var current by remember(treeUri) { mutableStateOf(treeUri?.let { DocumentFile.fromTreeUri(context, it) }) }
    var entries by remember(treeUri) { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var loading by remember(treeUri) { mutableStateOf(false) }
    var editor by remember { mutableStateOf<DocumentFile?>(null) }

    LaunchedEffect(current) {
        val folder = current
        if (folder != null) {
            loading = true
            entries = withContext(Dispatchers.IO) {
                runCatching {
                    folder.listFiles().mapNotNull { f -> f.name?.let { BrowserEntry(f, it, f.isDirectory) } }
                        .sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                }.getOrDefault(emptyList())
            }
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Files", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onPickFolder, shape = RoundedCornerShape(14.dp)) { Text("Choose folder") }
        }
        if (current == null) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    Text("Connect a local folder", style = MaterialTheme.typography.titleLarge)
                    Text("Pick a directory on this device. igit will request access only to the folder you choose.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onPickFolder) { Text("Select directory") }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current?.name ?: "Selected folder", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                if (current?.uri != treeUri) IconButton(onClick = { current = current?.parentFile }) { Icon(Icons.Default.ArrowBack, contentDescription = "Parent folder") }
            }
            if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            if (entries.isEmpty() && !loading) Text("This folder is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.file.uri.toString() }) { entry ->
                    Surface(
                        onClick = { if (entry.isDirectory) current = entry.file else editor = entry.file },
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                            Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(11.dp))
                            Text(entry.name, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(if (entry.isDirectory) "FOLDER" else "OPEN", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    editor?.let { file -> TextFileEditor(context, file, onClose = { editor = null }) }
}

@Composable
private fun TextFileEditor(context: Context, file: DocumentFile, onClose: () -> Unit) {
    var text by remember(file.uri) { mutableStateOf("") }
    var loading by remember(file.uri) { mutableStateOf(true) }
    var error by remember(file.uri) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(file.uri) {
        val result = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }.orEmpty() }
        }
        result.onSuccess { text = it.take(300_000) }.onFailure { error = it.message }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(file.name ?: "Text file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) CircularProgressIndicator()
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && !loading, onClick = {
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(text) } ?: error("Could not save this file") }
                    }
                    result.onFailure { error = it.message }
                    saving = false
                    if (result.isSuccess) onClose()
                }
            }) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
