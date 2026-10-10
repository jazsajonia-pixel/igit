package app.igit.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BrowserEntry(val file: DocumentFile, val name: String, val isDirectory: Boolean, val path: String)

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** Browse & Edit: file explorer with create / rename / delete, code editor, image viewer and ignore + untrack. */
@Composable
fun FilesScreen(vm: IgitViewModel, onPickFolder: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val treeUri = vm.active.folderUri?.let(Uri::parse)
    val root = remember(treeUri) { treeUri?.let { DocumentFile.fromTreeUri(context, it) } }
    var stack by remember(treeUri) { mutableStateOf(listOfNotNull(root)) }
    var entries by remember(treeUri) { mutableStateOf<List<BrowserEntry>?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<BrowserEntry?>(null) }
    var image by remember { mutableStateOf<BrowserEntry?>(null) }
    var menuFor by remember { mutableStateOf<BrowserEntry?>(null) }
    var prompt by remember { mutableStateOf<Pair<String, BrowserEntry?>?>(null) } // "file" | "folder" | "rename"
    var deleting by remember { mutableStateOf<BrowserEntry?>(null) }
    val current = stack.lastOrNull()
    val prefix = stack.drop(1).joinToString("/") { it.name.orEmpty() }

    LaunchedEffect(current, showHidden, reload) {
        val folder = current ?: return@LaunchedEffect
        entries = null
        entries = withContext(Dispatchers.IO) {
            runCatching {
                folder.listFiles().mapNotNull { f ->
                    val name = f.name ?: return@mapNotNull null
                    if (!showHidden && name.startsWith(".")) return@mapNotNull null
                    BrowserEntry(f, name, f.isDirectory, if (prefix.isEmpty()) name else "$prefix/$name")
                }.sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            }.getOrDefault(emptyList())
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Files", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (current != null) {
                IconButton(onClick = { showHidden = !showHidden }) { Icon(if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = "Show hidden files") }
                IconButton(onClick = { prompt = "file" to null }) { Icon(Icons.Default.NoteAdd, contentDescription = "Create a File") }
                IconButton(onClick = { prompt = "folder" to null }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Create a Directory") }
            }
        }
        if (current == null) {
            IgitCard(modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = IgitColors.Accent, modifier = Modifier.size(30.dp))
                Text("Select a local folder", style = MaterialTheme.typography.titleLarge)
                Text("igit only gets access to the folder you choose.", color = IgitColors.Secondary)
                OutlinedButton(onClick = onPickFolder) { Text("Select directory") }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stack.size > 1) IconButton(onClick = { stack = stack.dropLast(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Parent folder") }
            Monospace("/" + listOfNotNull(root?.name).plus(stack.drop(1).mapNotNull { it.name }).joinToString("/"), maxLines = 1)
        }
        val list = entries
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> Text("No files found…", color = IgitColors.Secondary)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.file.uri.toString() }) { entry ->
                    androidx.compose.foundation.layout.Box {
                        Surface(shape = RoundedCornerShape(13.dp), color = IgitColors.Surface, modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = {
                                when {
                                    entry.isDirectory -> stack = stack + entry.file
                                    entry.name.substringAfterLast('.').lowercase() in imageExtensions -> image = entry
                                    else -> editor = entry
                                }
                            },
                            onLongClick = { menuFor = entry }
                        )) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                                val icon = when {
                                    entry.isDirectory -> Icons.Default.Folder
                                    entry.name.substringAfterLast('.').lowercase() in imageExtensions -> Icons.Default.Image
                                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                }
                                Icon(icon, contentDescription = null, tint = IgitColors.Accent)
                                Spacer(Modifier.size(11.dp))
                                Text(entry.name, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
                            }
                        }
                        DropdownMenu(expanded = menuFor == entry, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; prompt = "rename" to entry })
                            DropdownMenuItem(text = { Text("Delete", color = IgitColors.Danger) }, onClick = { menuFor = null; deleting = entry })
                            if (vm.cloned) {
                                DropdownMenuItem(text = { Text(".gitignore + Untrack") }, onClick = { menuFor = null; vm.ignoreAndUntrack(listOf(entry.path), local = false, untrack = true) })
                                DropdownMenuItem(text = { Text(".git/info/exclude + Untrack") }, onClick = { menuFor = null; vm.ignoreAndUntrack(listOf(entry.path), local = true, untrack = true) })
                                DropdownMenuItem(text = { Text("Add to .gitignore Only") }, onClick = { menuFor = null; vm.ignoreAndUntrack(listOf(entry.path), local = false, untrack = false) })
                            }
                        }
                    }
                }
            }
        }
    }

    prompt?.let { (kind, entry) ->
        TextPromptDialog(
            title = when (kind) { "file" -> "Create a File"; "folder" -> "Create a Directory"; else -> "Rename ${if (entry?.isDirectory == true) "Folder" else "File"}" },
            label = when (kind) { "folder" -> "Folder Name"; else -> "File Name" },
            initial = entry?.name.orEmpty(),
            validate = { if (it.isBlank() || it.contains('/') || it == "." || it == "..") "Enter a valid name" else null },
            onConfirm = { name ->
                prompt = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            when (kind) {
                                "file" -> current?.createFile("application/octet-stream", name) != null
                                "folder" -> current?.createDirectory(name) != null
                                else -> entry?.file?.renameTo(name) == true
                            }
                        }.getOrDefault(false)
                    }
                    if (!ok) vm.toast("Could not complete that action") else reload++
                }
            },
            onDismiss = { prompt = null }
        )
    }
    deleting?.let { entry ->
        ConfirmDialog("Confirm Deletion", "Are you sure you want to delete the ${if (entry.isDirectory) "folder" else "file"} \"${entry.name}\"${if (entry.isDirectory) " and its contents" else ""}?", "Delete", danger = true,
            onConfirm = { scope.launch { withContext(Dispatchers.IO) { runCatching { entry.file.delete() } }; reload++ } }, onDismiss = { deleting = null })
    }
    editor?.let { entry -> CodeEditor(vm, entry.file, onClose = { editor = null; reload++ }) }
    image?.let { entry -> ImageViewer(entry.file, onClose = { image = null }) }
}

@Composable
private fun CodeEditor(vm: IgitViewModel, file: DocumentFile, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(file.uri) { mutableStateOf<String?>(null) }
    var original by remember(file.uri) { mutableStateOf("") }
    var error by remember(file.uri) { mutableStateOf<String?>(null) }
    var readOnly by remember(file.uri) { mutableStateOf(false) }
    val wrap = vm.lineWrap
    LaunchedEffect(file.uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.take(8000).any { it == 0.toByte() }) error("Binary file — can't be edited here.")
                val content = String(bytes, Charsets.UTF_8)
                if (content.lines().size > 20_000) { readOnly = true }
                content
            }
        }.onSuccess { text = it; original = it }.onFailure { error = it.message; text = "" }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                    Monospace(file.name.orEmpty(), modifier = Modifier.weight(1f), maxLines = 1)
                    if (readOnly) Text("Read-Only", color = IgitColors.Warning, style = MaterialTheme.typography.labelMedium)
                    TextButton(enabled = text != null && text != original && !readOnly && error == null, onClick = {
                        val value = text ?: return@TextButton
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(value) } ?: error("Could not save this file") }
                            }.onSuccess { original = value; vm.toast("Saved") }.onFailure { vm.toast(it.message ?: "Could not save") }
                        }
                    }) { Text("Save") }
                }
                if (error != null) Text(error.orEmpty(), color = IgitColors.Danger)
                val value = text
                if (value == null) LoadingBox() else if (error == null) {
                    val lines = remember(value) { value.count { it == '\n' } + 1 }
                    val vScroll = rememberScrollState()
                    Row(modifier = Modifier.fillMaxSize().verticalScroll(vScroll)) {
                        Text((1..lines).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = IgitColors.Muted, modifier = Modifier.padding(end = 8.dp))
                        val field = Modifier.fillMaxWidth().heightIn(min = 400.dp)
                        BasicTextField(
                            value = value, onValueChange = { if (!readOnly) text = it }, readOnly = readOnly,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(IgitColors.Accent),
                            modifier = if (wrap) field else field.horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(file: DocumentFile, onClose: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(file.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(file.uri) {
        bitmap = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(file.name.orEmpty()) },
        text = { bitmap?.let { Image(it.asImageBitmap(), contentDescription = file.name, modifier = Modifier.fillMaxWidth()) } ?: Text("Can't display this image.", color = Color(0xFFADB2B6)) },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
