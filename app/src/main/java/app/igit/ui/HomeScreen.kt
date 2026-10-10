package app.igit.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.igit.data.ChangeKind
import app.igit.data.CommitInfo
import app.igit.data.RepoMode
import app.igit.data.Validation

@Composable
fun HomeScreen(vm: IgitViewModel, onPickFolder: () -> Unit) {
    val c = vm.active
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        TopBar(vm, onDialog = { dialog = it })

        if (!vm.cloned) {
            CloneCard(vm, onPickFolder)
        } else {
            RecentCommits(vm)
            SyncControls(vm, onDialog = { dialog = it })
            if (c.mode == RepoMode.CLIENT) ClientChanges(vm, onDialog = { dialog = it })
            BranchRow(vm, onDialog = { dialog = it })
            if (vm.gitHubRepo != null && vm.gitHubToken != null) ProviderTools(vm)
        }

        RemoteAndAuth(vm, onDialog = { dialog = it })
        FolderRow(vm, onPickFolder)

        if (vm.cloned) {
            SectionLabel("Auto Sync", Modifier.padding(top = 6.dp))
            AutoSyncSettings(vm)
        }

        Surface(onClick = { vm.navigate(Screen.RepoSettings) }, shape = RoundedCornerShape(17.dp), color = IgitColors.Surface, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = IgitColors.Accent)
                Spacer(Modifier.size(12.dp))
                Text("Repository Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    HomeDialogs(vm, dialog, onDismiss = { dialog = null }, onDialog = { dialog = it })
}

@Composable
private fun TopBar(vm: IgitViewModel, onDialog: (HomeDialog) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { menu = true }.padding(vertical = 6.dp)) {
                Text(vm.active.alias, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Your Containers", tint = IgitColors.Accent)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                vm.containers.forEach { container ->
                    DropdownMenuItem(
                        text = { Text(container.alias + if (container.id == vm.active.id) "  ✓" else "") },
                        onClick = { menu = false; vm.selectContainer(container.id) }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Add Container") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menu = false; onDialog(HomeDialog.AddContainer) })
                DropdownMenuItem(text = { Text("Rename Container") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onDialog(HomeDialog.RenameContainer) })
                DropdownMenuItem(text = { Text("Remove Container", color = IgitColors.Danger) }, onClick = { menu = false; onDialog(HomeDialog.DeleteContainer) })
            }
        }
        SyncStatusIndicator(vm)
        IconButton(onClick = { vm.navigate(Screen.GlobalSettings) }) { Icon(Icons.Default.Settings, contentDescription = "Global Settings", tint = IgitColors.Secondary) }
    }
}

/** GitSync's Sync Status: spinner while working, check when complete, error icon (tap for details). */
@Composable
private fun SyncStatusIndicator(vm: IgitViewModel) {
    var showError by remember { mutableStateOf(false) }
    when {
        vm.busy -> CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(22.dp), strokeWidth = 2.dp, color = IgitColors.Accent)
        vm.lastError != null -> IconButton(onClick = { showError = true }) { Icon(Icons.Default.Error, contentDescription = "Sync error", tint = IgitColors.Danger) }
        vm.merging || vm.conflicts.isNotEmpty() -> IconButton(onClick = { vm.navigate(Screen.Conflicts) }) { Icon(Icons.Default.Warning, contentDescription = "Merge conflict", tint = IgitColors.Warning) }
        vm.lastResult != null -> Icon(Icons.Default.CheckCircle, contentDescription = "Synced", tint = IgitColors.Success, modifier = Modifier.padding(10.dp).size(22.dp))
    }
    if (showError) ConfirmDialog("An Error Occurred!", vm.lastError.orEmpty(), "Dismiss", onConfirm = {}, onDismiss = { showError = false })
}

@Composable
private fun RecentCommits(vm: IgitViewModel) {
    IgitCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Recent Commits")
        if (vm.merging || vm.conflicts.isNotEmpty()) {
            Surface(onClick = { vm.navigate(Screen.Conflicts) }, shape = RoundedCornerShape(13.dp), color = Color(0xFF3A2020)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(13.dp)) {
                    Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null, tint = IgitColors.Danger)
                    Spacer(Modifier.size(10.dp))
                    Text("There is a merge conflict! Tap to resolve", color = IgitColors.Danger, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (vm.commits.isEmpty()) {
            Text("No commits found…", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            vm.commits.take(4).forEach { commit -> CommitItem(commit) { vm.navigate(Screen.CommitDetail(commit)) } }
            if (vm.commits.size > 4) {
                var expanded by remember { mutableStateOf(false) }
                if (expanded) vm.commits.drop(4).forEach { commit -> CommitItem(commit) { vm.navigate(Screen.CommitDetail(commit)) } }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show ${vm.commits.size - 4} more commits") }
            }
        }
    }
}

@Composable
fun CommitItem(commit: CommitInfo, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(13.dp), color = IgitColors.SurfaceHigh) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(commit.message, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Surface(shape = RoundedCornerShape(7.dp), color = if (commit.pushed) Color(0xFF3A3D40) else Color(0xFF3A3220)) {
                    Text(commit.shortSha, color = if (commit.pushed) Color(0xFFD2D6D9) else IgitColors.Warning, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${commit.author} committed ${Validation.relativeTime(commit.timeMillis)}", modifier = Modifier.weight(1f), color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text("${commit.additions} ++", color = IgitColors.Success, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.size(8.dp))
                Text("${commit.deletions} --", color = Color(0xFFFFA6A6), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private data class SyncOption(val label: String, val icon: ImageVector, val action: () -> Unit)

@Composable
private fun SyncControls(vm: IgitViewModel, onDialog: (HomeDialog) -> Unit) {
    val client = vm.active.mode == RepoMode.CLIENT
    var menu by remember { mutableStateOf(false) }
    val remote = vm.active.defaultRemote
    val options = buildList {
        add(SyncOption("Manual Sync", Icons.Default.Tune) { vm.showManualSync = true; vm.loadChanges() })
        if (client) {
            add(SyncOption("Fetch $remote", Icons.Default.KeyboardArrowDown) { vm.fetchRemote() })
            add(SyncOption("Pull Changes", Icons.Default.CloudDownload) { vm.pullChanges() })
            add(SyncOption("Stage & Commit", Icons.Default.Edit) { vm.showManualSync = true; vm.loadChanges() })
            add(SyncOption("Push Changes", Icons.Default.CloudUpload) { vm.pushChanges() })
            add(SyncOption("Force Pull", Icons.Default.KeyboardDoubleArrowDown) { onDialog(HomeDialog.ForcePull) })
            add(SyncOption("Force Push", Icons.Default.KeyboardDoubleArrowUp) { onDialog(HomeDialog.ForcePush) })
        } else {
            add(SyncOption("Download Changes", Icons.Default.CloudDownload) { vm.downloadChanges() })
            add(SyncOption("Upload Changes", Icons.Default.CloudUpload) { vm.uploadChanges() })
            add(SyncOption("Download + Overwrite", Icons.Default.KeyboardDoubleArrowDown) { onDialog(HomeDialog.ForcePull) })
            add(SyncOption("Upload + Overwrite", Icons.Default.KeyboardDoubleArrowUp) { onDialog(HomeDialog.ForcePush) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = vm::syncNow,
            enabled = !vm.busy,
            modifier = Modifier.weight(1f).height(58.dp),
            shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF202326), contentColor = Color.White, disabledContainerColor = Color(0xFF202326), disabledContentColor = Color(0xFF91999F))
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, tint = IgitColors.Accent)
            Spacer(Modifier.size(10.dp))
            Text(
                when {
                    vm.busy -> vm.operation.orEmpty().uppercase()
                    client -> "SYNC ALL CHANGES"
                    else -> "SYNC CHANGES"
                },
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !vm.busy, modifier = Modifier.size(58.dp).background(Color(0xFF202122), RoundedCornerShape(17.dp))) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "more sync options", tint = Color.White)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, leadingIcon = { Icon(option.icon, null, tint = IgitColors.Accent) }, onClick = { menu = false; option.action() })
                }
            }
        }
    }
    // Sync / Client mode toggle
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ModeChip("Sync Mode", "Automated syncing", !client, Modifier.weight(1f)) { vm.setMode(RepoMode.SYNC) }
        ModeChip("Client Mode", "Expanded Git UI", client, Modifier.weight(1f)) { vm.setMode(RepoMode.CLIENT) }
    }
}

@Composable
private fun ModeChip(title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = if (selected) IgitColors.AccentContainer else IgitColors.Surface, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(title, color = if (selected) IgitColors.Accent else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Client mode: uncommitted changes with stage/unstage/discard, plus a commit box with the AI wand. */
@Composable
private fun ClientChanges(vm: IgitViewModel, onDialog: (HomeDialog) -> Unit) {
    var message by remember { mutableStateOf("") }
    IgitCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Changes", Modifier.weight(1f))
            TextButton(onClick = vm::loadChanges) { Text("Refresh") }
        }
        if (vm.changes.isEmpty()) {
            Text("No uncommitted changes", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            val staged = vm.changes.filter { it.staged }
            val unstaged = vm.changes.filter { !it.staged }
            Row {
                TextButton(onClick = { vm.stage(unstaged.map { it.path }) }, enabled = unstaged.isNotEmpty()) { Text("Stage All") }
                TextButton(onClick = { vm.unstage(staged.map { it.path }) }, enabled = staged.isNotEmpty()) { Text("Unstage All") }
            }
            vm.changes.take(80).forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Checkbox(checked = file.staged, onCheckedChange = { if (it) vm.stage(listOf(file.path)) else vm.unstage(listOf(file.path)) })
                    Text(
                        when (file.kind) { ChangeKind.ADDED, ChangeKind.UNTRACKED -> "A"; ChangeKind.DELETED -> "D"; ChangeKind.CONFLICT -> "!"; else -> "M" },
                        color = when (file.kind) { ChangeKind.ADDED, ChangeKind.UNTRACKED -> IgitColors.Success; ChangeKind.DELETED -> IgitColors.Danger; ChangeKind.CONFLICT -> IgitColors.Warning; else -> IgitColors.Accent },
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp)
                    )
                    Monospace(file.path, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { onDialog(HomeDialog.Discard(file.path)) }) { Text("Discard", color = IgitColors.Danger) }
                }
            }
            WandTextField(message, { message = it }, "Commit Message", vm.aiEnabled, onWand = { vm.wandCommitMessage(message) { message = it } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.commit(message); message = "" }, enabled = staged.isNotEmpty() && message.isNotBlank() && !vm.busy) { Text("Commit") }
                OutlinedButton(onClick = vm::pushChanges, enabled = !vm.busy) { Text("Push") }
                OutlinedButton(onClick = vm::pullChanges, enabled = !vm.busy) { Text("Pull") }
            }
        }
    }
}

@Composable
private fun BranchRow(vm: IgitViewModel, onDialog: (HomeDialog) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val list = vm.branches
    IgitCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Current Branch")
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { menu = true }.padding(top = 3.dp)) {
                        Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, tint = IgitColors.Muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            when { list.detached -> "Detached Head (${list.current})"; list.current == null -> "Unborn Branch"; else -> list.current },
                            style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Choose branch", tint = IgitColors.Accent)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        list.local.forEach { b ->
                            DropdownMenuItem(text = { Text(b + if (b == list.current) "  ✓" else "", fontFamily = FontFamily.Monospace) }, onClick = {
                                menu = false
                                if (b != list.current) onDialog(HomeDialog.Checkout(b))
                            })
                        }
                        if (list.remote.isNotEmpty()) {
                            HorizontalDivider()
                            list.remote.forEach { b ->
                                DropdownMenuItem(text = { Text("${vm.active.defaultRemote}/$b", fontFamily = FontFamily.Monospace, color = IgitColors.Secondary) }, onClick = { menu = false; onDialog(HomeDialog.Checkout(b)) })
                            }
                        }
                        HorizontalDivider()
                        list.current?.takeIf { !list.detached }?.let { current ->
                            DropdownMenuItem(text = { Text("Rename Branch") }, onClick = { menu = false; onDialog(HomeDialog.RenameBranch(current)) })
                        }
                        list.local.filter { it != list.current }.takeIf { it.isNotEmpty() }?.let {
                            DropdownMenuItem(text = { Text("Delete Branch…", color = IgitColors.Danger) }, onClick = { menu = false; onDialog(HomeDialog.PickDeleteBranch) })
                        }
                    }
                }
            }
            IconButton(onClick = { onDialog(HomeDialog.CreateBranch) }) { Icon(Icons.Default.Add, contentDescription = "add branch", tint = IgitColors.Accent) }
        }
    }
}

@Composable
private fun ProviderTools(vm: IgitViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ProviderButton("Issues", Icons.Default.BugReport, Modifier.weight(1f)) { vm.navigate(Screen.Tickets(pullRequests = false)) }
        ProviderButton("Pull Requests", Icons.AutoMirrored.Filled.MergeType, Modifier.weight(1f)) { vm.navigate(Screen.Tickets(pullRequests = true)) }
        ProviderButton("Actions", Icons.Default.PlayCircle, Modifier.weight(1f)) { vm.navigate(Screen.Actions) }
    }
}

@Composable
private fun ProviderButton(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp), color = IgitColors.AccentContainer) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = IgitColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(title.uppercase(), color = IgitColors.Accent, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun RemoteAndAuth(vm: IgitViewModel, onDialog: (HomeDialog) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val remoteUrl = vm.remotes.firstOrNull { it.name == vm.active.defaultRemote }?.url ?: vm.active.remoteUrl
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f)) {
            IgitCard(modifier = Modifier.fillMaxWidth(), onClick = { menu = true }) {
                SectionLabel("Remote · ${vm.active.defaultRemote}")
                Monospace(remoteUrl.ifBlank { "No remote configured" }, maxLines = 1)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Modify Remote URL") }, onClick = { menu = false; onDialog(HomeDialog.SetRemoteUrl(vm.active.defaultRemote, remoteUrl)) })
                Validation.browserUrl(remoteUrl)?.let { url ->
                    DropdownMenuItem(text = { Text("Launch in Browser") }, onClick = { menu = false; runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
                }
                if (vm.cloned) {
                    HorizontalDivider()
                    vm.remotes.forEach { r ->
                        DropdownMenuItem(text = { Text("${r.name} — manage", fontFamily = FontFamily.Monospace) }, onClick = { menu = false; onDialog(HomeDialog.ManageRemote(r.name, r.url)) })
                    }
                    DropdownMenuItem(text = { Text("Add Remote") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menu = false; onDialog(HomeDialog.AddRemote) })
                }
            }
        }
        Surface(onClick = { vm.navigate(Screen.Auth) }, shape = RoundedCornerShape(17.dp), color = IgitColors.Surface) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp)) {
                Icon(if (vm.hasAuth) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, tint = if (vm.hasAuth) IgitColors.Success else IgitColors.Muted, modifier = Modifier.size(20.dp))
                Text("AUTH", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    val needed = if (remoteUrl.isNotBlank()) Validation.authFor(remoteUrl) else null
    if (needed != null && vm.hasAuth && vm.active.authType != needed) {
        Surface(onClick = { vm.navigate(Screen.Auth) }, shape = RoundedCornerShape(12.dp), color = Color(0xFF34302A), modifier = Modifier.fillMaxWidth()) {
            Text(
                "Auth won't work with this remote. " + if (needed == app.igit.data.AuthType.SSH) "This remote uses SSH. Tap to switch" else "This remote uses HTTPS or OAuth. Tap to switch",
                color = IgitColors.Warning, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun FolderRow(vm: IgitViewModel, onPickFolder: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IgitCard(modifier = Modifier.weight(1f), onClick = onPickFolder) {
            SectionLabel("Local Folder")
            Monospace(vm.folderName ?: "Select a folder", maxLines = 1)
        }
        if (vm.active.folderUri != null) {
            Surface(onClick = { vm.tab = AppTab.FILES }, shape = RoundedCornerShape(17.dp), color = IgitColors.Surface) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = IgitColors.Accent)
                    Text("Browse & Edit", style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = { vm.setFolder(null) }) { Text("✕", color = IgitColors.Secondary) }
        }
    }
}

@Composable
private fun CloneCard(vm: IgitViewModel, onPickFolder: () -> Unit) {
    IgitCard(modifier = Modifier.fillMaxWidth()) {
        Text("Set up this container", style = MaterialTheme.typography.titleLarge)
        Text("1. Authenticate with your git provider (AUTH)\n2. Select a local folder\n3. Clone a repository to your device\n4. Configure your sync settings", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.navigate(Screen.Auth) }) { Text("AUTH") }
            OutlinedButton(onClick = onPickFolder) { Text("Select folder") }
        }
        Button(onClick = { vm.navigate(Screen.Clone) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.busy) vm.operation.orEmpty() else "Clone Remote Repository")
        }
        TextButton(onClick = { vm.navigate(Screen.Clone) }) { Text("Create New Repository / I have a local repository") }
    }
}

/** Dot used in compact status rows. */
@Composable
fun StatusDot(color: Color) { Box(Modifier.size(9.dp).background(color, CircleShape)) }
