package app.igit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.igit.data.ChangeKind
import app.igit.data.Validation

sealed interface HomeDialog {
    data object AddContainer : HomeDialog
    data object RenameContainer : HomeDialog
    data object DeleteContainer : HomeDialog
    data object ForcePull : HomeDialog
    data object ForcePush : HomeDialog
    data object CreateBranch : HomeDialog
    data object PickDeleteBranch : HomeDialog
    data object AddRemote : HomeDialog
    data class Checkout(val branch: String) : HomeDialog
    data class RenameBranch(val branch: String) : HomeDialog
    data class DeleteBranch(val branch: String) : HomeDialog
    data class SetRemoteUrl(val remote: String, val url: String) : HomeDialog
    data class ManageRemote(val remote: String, val url: String) : HomeDialog
    data class RenameRemote(val remote: String) : HomeDialog
    data class Discard(val path: String) : HomeDialog
}

@Composable
fun HomeDialogs(vm: IgitViewModel, dialog: HomeDialog?, onDismiss: () -> Unit, onDialog: (HomeDialog) -> Unit) {
    when (dialog) {
        null -> Unit
        HomeDialog.AddContainer -> TextPromptDialog("Add Container", "alias", message = "Enter an alias for the new repository container", confirm = "Add",
            onConfirm = { vm.addContainer(it); onDismiss() }, onDismiss = onDismiss)
        HomeDialog.RenameContainer -> TextPromptDialog("Rename Container", "alias", initial = vm.active.alias, message = "Enter a new alias for the repository container", confirm = "Rename",
            onConfirm = { vm.renameContainer(it); onDismiss() }, onDismiss = onDismiss)
        HomeDialog.DeleteContainer -> DeleteContainerDialog(vm, onDismiss)
        HomeDialog.ForcePull -> ConfirmDialog("Confirm Force Pull", "This action will overwrite the local history and cannot be undone. Local changes that aren't on the remote will be lost.", "Force Pull", danger = true,
            onConfirm = vm::forcePull, onDismiss = onDismiss)
        HomeDialog.ForcePush -> ConfirmDialog("Confirm Force Push", "This action will overwrite the remote history and cannot be undone. Commits on the remote that aren't on this device will be lost.", "Force Push", danger = true,
            onConfirm = vm::forcePush, onDismiss = onDismiss)
        HomeDialog.CreateBranch -> CreateBranchDialog(vm, onDismiss)
        HomeDialog.PickDeleteBranch -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete Branch") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(vm.branches.local.filter { it != vm.branches.current }) { b ->
                        Text(b, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().clickable { onDialog(HomeDialog.DeleteBranch(b)) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
        is HomeDialog.Checkout -> ConfirmDialog("Checkout Branch?", "Are you sure you want to checkout the branch ${dialog.branch}? Unsaved changes may be lost.", "Checkout",
            onConfirm = { vm.checkout(dialog.branch) }, onDismiss = onDismiss)
        is HomeDialog.RenameBranch -> TextPromptDialog("Rename Branch", "Branch Name", initial = dialog.branch, confirm = "Rename",
            validate = { if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/$it")) "Enter a valid branch name." else null },
            onConfirm = { vm.renameBranch(dialog.branch, it); onDismiss() }, onDismiss = onDismiss)
        is HomeDialog.DeleteBranch -> DeleteBranchDialog(vm, dialog.branch, onDismiss)
        HomeDialog.AddRemote -> AddRemoteDialog(vm, onDismiss)
        is HomeDialog.SetRemoteUrl -> TextPromptDialog("Set Remote URL", "Remote URL", initial = dialog.url, confirm = "Modify",
            validate = { if (!Validation.isValidRemoteUrl(it)) "Invalid repository URL!" else null },
            onConfirm = {
                if (vm.cloned) vm.setRemoteUrl(dialog.remote, it) else vm.update { c -> c.copy(remoteUrl = it) }
                onDismiss()
            }, onDismiss = onDismiss)
        is HomeDialog.ManageRemote -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(dialog.remote) },
            text = { Monospace(dialog.url) },
            confirmButton = {
                Row {
                    TextButton(onClick = { onDialog(HomeDialog.SetRemoteUrl(dialog.remote, dialog.url)) }) { Text("Set URL") }
                    TextButton(onClick = { onDialog(HomeDialog.RenameRemote(dialog.remote)) }) { Text("Rename") }
                    TextButton(onClick = { vm.deleteRemote(dialog.remote); onDismiss() }) { Text("Delete", color = IgitColors.Danger) }
                }
            },
            dismissButton = { TextButton(onClick = { vm.update { it.copy(defaultRemote = dialog.remote) }; onDismiss() }) { Text("Make default") } }
        )
        is HomeDialog.RenameRemote -> TextPromptDialog("Rename Remote", "Remote Name", initial = dialog.remote, confirm = "Rename",
            onConfirm = { vm.renameRemote(dialog.remote, it); onDismiss() }, onDismiss = onDismiss)
        is HomeDialog.Discard -> ConfirmDialog("Discard Changes?", "Are you sure you want to discard all changes to \"${dialog.path}\"? This action cannot be undone.", "Discard Changes", danger = true,
            onConfirm = { vm.discard(listOf(dialog.path)) }, onDismiss = onDismiss)
    }
}

@Composable
private fun DeleteContainerDialog(vm: IgitViewModel, onDismiss: () -> Unit) {
    var alsoLocal by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Container Deletion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Are you sure you want to delete the repository container \"${vm.active.alias}\"? Its auth and settings are removed. Files in your selected folder are not deleted.")
                SettingCheck("Also delete igit's private copy of the repository", alsoLocal) { alsoLocal = it }
            }
        },
        confirmButton = { TextButton(onClick = { vm.deleteContainer(alsoLocal); onDismiss() }) { Text("Delete", color = IgitColors.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CreateBranchDialog(vm: IgitViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(vm.branches.current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Branch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.replace(" ", "-") }, label = { Text("Branch Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Based on", style = MaterialTheme.typography.labelLarge)
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(vm.branches.all) { b ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { base = b }.padding(vertical = 8.dp)) {
                            Text(if (b == base) "● " else "○ ", color = IgitColors.Accent)
                            Text(b, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.createBranch(name.trim(), base); onDismiss() },
                enabled = name.isNotBlank() && org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/${name.trim()}")) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeleteBranchDialog(vm: IgitViewModel, branch: String, onDismiss: () -> Unit) {
    var remote by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Branch?") },
        text = {
            Column {
                Text("Are you sure you want to delete the branch \"$branch\"?")
                SettingCheck("Also delete it on ${vm.active.defaultRemote}", remote) { remote = it }
            }
        },
        confirmButton = { TextButton(onClick = { vm.deleteBranch(branch, remote); onDismiss() }) { Text("Delete", color = IgitColors.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddRemoteDialog(vm: IgitViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Remote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.trim() }, label = { Text("Remote Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Remote URL") }, placeholder = { Text("https://git.abc/xyz.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { vm.addRemote(name, url); onDismiss() }, enabled = name.isNotBlank() && Validation.isValidRemoteUrl(url)) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** GitSync's Manual Sync: "Select the files you would like to sync" + commit message. */
@Composable
fun ManualSyncDialog(vm: IgitViewModel, onDismiss: () -> Unit) {
    val selected = remember { mutableStateOf(setOf<String>()) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(vm.changes) { selected.value = selected.value.filter { p -> vm.changes.any { it.path == p } }.toSet() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select the files you would like to sync", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { selected.value = vm.changes.map { it.path }.toSet() }) { Text("Select All") }
                    TextButton(onClick = { selected.value = emptySet() }) { Text("Deselect All") }
                }
                if (vm.changes.isEmpty()) Text("No uncommitted changes", color = IgitColors.Secondary)
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(vm.changes, key = { it.path }) { file ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            selected.value = if (file.path in selected.value) selected.value - file.path else selected.value + file.path
                        }) {
                            Checkbox(checked = file.path in selected.value, onCheckedChange = null)
                            Text(
                                when (file.kind) { ChangeKind.ADDED, ChangeKind.UNTRACKED -> "A "; ChangeKind.DELETED -> "D "; else -> "M " },
                                fontFamily = FontFamily.Monospace, color = IgitColors.Accent
                            )
                            Monospace(file.path, maxLines = 1)
                        }
                    }
                }
                WandTextField(message, { message = it }, "Commit Message", vm.aiEnabled, onWand = { vm.wandCommitMessage(message) { message = it } })
            }
        },
        confirmButton = { TextButton(onClick = { vm.manualSync(selected.value, message) }, enabled = selected.value.isNotEmpty() && !vm.busy) { Text("Sync") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
