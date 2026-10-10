package app.igit.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.igit.data.CommitInfo
import app.igit.data.FileDiff
import app.igit.data.Validation
import app.igit.git.ConflictText
import java.text.DateFormat
import java.util.Date

@Composable
fun CommitDetailScreen(vm: IgitViewModel, commit: CommitInfo) {
    val context = LocalContext.current
    var diffs by remember(commit.sha) { mutableStateOf<List<FileDiff>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(commit.sha) { runCatching { vm.commitDiff(commit.sha) }.onSuccess { diffs = it }.onFailure { error = it.message } }

    PageScaffold(commit.shortSha, onBack = { vm.back() }, actions = {
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sha", commit.sha))
            vm.toast("Copied text!")
        }) { Text("Copy SHA") }
    }) {
        IgitCard(modifier = Modifier.fillMaxWidth()) {
            Text(commit.fullMessage.trim(), style = MaterialTheme.typography.bodyLarge)
            Text("${commit.author} · ${DateFormat.getDateTimeInstance().format(Date(commit.timeMillis))}", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
            Text("${commit.filesChanged} file(s) changed  ·  ${commit.additions} ++  ${commit.deletions} --${if (!commit.pushed) "  ·  not pushed" else ""}",
                style = MaterialTheme.typography.labelMedium, color = if (commit.pushed) IgitColors.Secondary else IgitColors.Warning)
        }
        when {
            error != null -> Text(error.orEmpty(), color = IgitColors.Danger)
            diffs == null -> LoadingBox()
            diffs!!.isEmpty() -> Text("No changed files found", color = IgitColors.Secondary)
            else -> diffs!!.forEach { DiffCard(it) }
        }
    }
}

@Composable
private fun DiffCard(diff: FileDiff) {
    var expanded by remember { mutableStateOf(diff.patch.length < 6_000) }
    IgitCard(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Row {
            Monospace(diff.path, modifier = Modifier.weight(1f), maxLines = 2)
            Text("${diff.changeType}  +${diff.additions} -${diff.deletions}", style = MaterialTheme.typography.labelMedium, color = IgitColors.Secondary)
        }
        if (expanded) DiffText(diff.patch)
    }
}

@Composable
fun DiffText(patch: String) {
    val text = remember(patch) {
        buildAnnotatedString {
            patch.lineSequence().filterNot { it.startsWith("diff --git") || it.startsWith("index ") }.take(1500).forEach { line ->
                val color = when {
                    line.startsWith("+++") || line.startsWith("---") -> Color(0xFF8F969B)
                    line.startsWith("+") -> Color(0xFF7DE2A2)
                    line.startsWith("-") -> Color(0xFFFFA6A6)
                    line.startsWith("@@") -> Color(0xFF80C7FF)
                    else -> Color(0xFFD2D6D9)
                }
                withStyle(SpanStyle(color = color)) { append(line) }
                append('\n')
            }
        }
    }
    Surface(color = Color(0xFF151617), shape = RoundedCornerShape(10.dp)) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp), softWrap = false)
    }
}

/**
 * Merge conflict editor: per file, resolve every block with Current / Incoming / Both, step through blocks, or edit
 * the text directly. "Resolve" stages the file; when none remain, the merge commit is made and sync resumes.
 */
@Composable
fun ConflictsScreen(vm: IgitViewModel) {
    var openPath by remember { mutableStateOf<String?>(null) }
    var confirmAbort by remember { mutableStateOf(false) }
    PageScaffold("Merge Conflict", onBack = { vm.back() }) {
        Text("Use the editor to resolve the merge conflicts", color = IgitColors.Secondary)
        if (vm.conflicts.isEmpty()) {
            IgitCard(modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.merging) "All files resolved. Finish the merge to continue syncing." else "No conflicts.", style = MaterialTheme.typography.bodyLarge)
                if (vm.merging) Button(onClick = vm::finishMerge, enabled = !vm.busy) { Text("Merge") }
            }
        } else vm.conflicts.forEach { path ->
            IgitCard(modifier = Modifier.fillMaxWidth(), onClick = { openPath = path }) {
                Monospace(path)
                Text("Tap to resolve", color = IgitColors.Warning, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmAbort = true }, enabled = !vm.busy) { Text("Abort Merge", color = IgitColors.Danger) }
            TextButton(onClick = { vm.back() }) { Text("Resolve Later") }
        }
    }
    openPath?.let { path -> ConflictFileEditor(vm, path, onClose = { openPath = null }) }
    if (confirmAbort) ConfirmDialog("Abort Merge", "Your local commit stays on this device and the remote changes are not merged. Your folder returns to your last local version.", "Abort Merge",
        danger = true, onConfirm = vm::abortMerge, onDismiss = { confirmAbort = false })
}

@Composable
private fun ConflictFileEditor(vm: IgitViewModel, path: String, onClose: () -> Unit) {
    var text by remember(path) { mutableStateOf<String?>(null) }
    var block by remember(path) { mutableStateOf(0) }
    LaunchedEffect(path) { text = runCatching { vm.readFile(path) }.getOrElse { "" } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Monospace(path)
                val current = text
                if (current == null) LoadingBox() else {
                    val count = ConflictText.blockCount(current)
                    Text(if (count == 0) "No conflict markers left" else "Conflict ${minOf(block, count - 1) + 1} of $count", color = IgitColors.Secondary, style = MaterialTheme.typography.labelMedium)
                    if (count > 0) {
                        val b = minOf(block, count - 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { text = ConflictText.resolveBlock(current, b, "current") }) { Text("Current") }
                            TextButton(onClick = { text = ConflictText.resolveBlock(current, b, "incoming") }) { Text("Incoming") }
                            TextButton(onClick = { text = ConflictText.resolveBlock(current, b, "both") }) { Text("Both") }
                            if (count > 1) TextButton(onClick = { block = (b + 1) % count }) { Text("Next") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { text = ConflictText.resolveAll(current, "current") }) { Text("All Current") }
                            TextButton(onClick = { text = ConflictText.resolveAll(current, "incoming") }) { Text("All Incoming") }
                        }
                    }
                    Surface(color = Color(0xFF151617), shape = RoundedCornerShape(10.dp)) {
                        BasicTextField(
                            value = current, onValueChange = { text = it },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(IgitColors.Accent),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp).padding(10.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.resolveFile(path, current) { onClose() } }, enabled = !ConflictText.hasMarkers(current) && !vm.busy) { Text("Resolve") }
                        TextButton(onClick = onClose) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** Small helper for "x ago" labels from ISO dates (GitHub API). */
fun shortDate(iso: String): String = iso.take(10)

@Suppress("unused")
private val keepValidation = Validation
@Suppress("unused")
private fun Modifier.codeBackground() = this.background(Color(0xFF151617))
