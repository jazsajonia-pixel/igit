package app.igit.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.igit.data.AuthType
import app.igit.data.DEFAULT_SYNC_MESSAGE
import app.igit.data.DEFAULT_TIME_FORMAT
import app.igit.data.GitHubRepo
import app.igit.data.Validation
import app.igit.git.SshKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RepositorySettingsScreen(vm: IgitViewModel) {
    val c = vm.active
    var syncMessage by remember(c.id) { mutableStateOf(c.syncMessage) }
    var timeFormat by remember(c.id) { mutableStateOf(c.syncMessageTimeFormat) }
    var authorName by remember(c.id) { mutableStateOf(c.authorName) }
    var authorEmail by remember(c.id) { mutableStateOf(c.authorEmail) }
    var postFooter by remember(c.id) { mutableStateOf(c.postFooter) }
    var gitignore by remember(c.id) { mutableStateOf<String?>(null) }
    var exclude by remember(c.id) { mutableStateOf<String?>(null) }
    var confirmSsl by remember { mutableStateOf(false) }
    var importKey by remember { mutableStateOf(false) }
    LaunchedEffect(c.id, vm.cloned) {
        if (vm.cloned) { gitignore = vm.readIgnore(false); exclude = vm.readIgnore(true) }
    }

    PageScaffold("Repository Settings", onBack = { vm.back() }) {
        IgitCard(modifier = Modifier.fillMaxWidth()) {
            SettingSwitch("Sync messages", "Show a small popup message when a sync happens", c.showSyncMessages) { on -> vm.update { it.copy(showSyncMessages = on) } }
            HorizontalDivider()
            Text("Default to", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = c.mode == app.igit.data.RepoMode.SYNC, onClick = { vm.setMode(app.igit.data.RepoMode.SYNC) }, label = { Text("Sync Mode") })
                FilterChip(selected = c.mode == app.igit.data.RepoMode.CLIENT, onClick = { vm.setMode(app.igit.data.RepoMode.CLIENT) }, label = { Text("Client Mode") })
            }
        }

        IgitCard(modifier = Modifier.fillMaxWidth()) {
            SettingSwitch("Signed Commits", "sign commits to verify your identity (SSH Ed25519 keys)", c.signedCommits) { on -> vm.update { it.copy(signedCommits = on) } }
            if (c.signedCommits) {
                SettingCheck("Use AUTH Key for Commit Signing", c.signingUsesAuthKey) { on -> vm.update { it.copy(signingUsesAuthKey = on) } }
                if (!c.signingUsesAuthKey) {
                    OutlinedButton(onClick = { importKey = true }) { Text(if (vm.hasSigningKey()) "Key Imported — replace" else "Import Key") }
                }
                Text("Add the public key on GitHub as a Signing key (Settings → SSH and GPG keys) so commits show as Verified.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        IgitCard(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(syncMessage, { syncMessage = it }, label = { Text("Sync Message") }, supportingText = { Text("use %s for the date and time") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(timeFormat, { timeFormat = it }, label = { Text("Sync Message Time Format") }, supportingText = {
                Text("Preview: " + Validation.formatSyncMessage(syncMessage, timeFormat))
            }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(authorName, { authorName = it }, label = { Text("author name") }, placeholder = { Text("JohnSmith12") }, supportingText = { Text("used to identify you in commit history") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text("author email") }, placeholder = { Text("john12@smith.com") }, supportingText = { Text("attached to your commits for attribution") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(postFooter, { postFooter = it }, label = { Text("post footer") }, supportingText = { Text("appended to issues, comments, and pull requests you create") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.update { it.copy(syncMessage = syncMessage.ifBlank { DEFAULT_SYNC_MESSAGE }, syncMessageTimeFormat = timeFormat.ifBlank { DEFAULT_TIME_FORMAT }, authorName = authorName.trim(), authorEmail = authorEmail.trim(), postFooter = postFooter) }
                    vm.toast("Settings saved")
                }) { Text("Save") }
                TextButton(onClick = { syncMessage = DEFAULT_SYNC_MESSAGE; timeFormat = DEFAULT_TIME_FORMAT }) { Text("Reset message") }
            }
        }

        IgitCard(modifier = Modifier.fillMaxWidth()) {
            Text("default remote", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (vm.remotes.map { it.name }.ifEmpty { listOf(c.defaultRemote) }).forEach { name ->
                    FilterChip(selected = c.defaultRemote == name, onClick = { vm.update { it.copy(defaultRemote = name) } }, label = { Text(name, fontFamily = FontFamily.Monospace) })
                }
            }
        }

        if (vm.cloned) {
            IgitCard(modifier = Modifier.fillMaxWidth()) {
                Text(".gitignore", style = MaterialTheme.typography.titleMedium)
                Text("list files or folders to ignore on all devices", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(gitignore.orEmpty(), { gitignore = it }, placeholder = { Text(".trash/\n./…") }, minLines = 4, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.writeIgnore(false, gitignore.orEmpty()) }) { Text("Save .gitignore") }
                HorizontalDivider()
                Text(".git/info/exclude", style = MaterialTheme.typography.titleMedium)
                Text("list files or folders to ignore on this device", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(exclude.orEmpty(), { exclude = it }, placeholder = { Text(".trash/\n./…") }, minLines = 4, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.writeIgnore(true, exclude.orEmpty()) }) { Text("Save exclude") }
            }
        }

        IgitCard(modifier = Modifier.fillMaxWidth()) {
            SettingSwitch("Disable SSL", "Disable secure connection for HTTP repositories", c.disableSsl) { on ->
                if (on) confirmSsl = true else vm.update { it.copy(disableSsl = false) }
            }
            SettingSwitch("Optimised Sync", "Intelligently reduce overall sync operations", c.optimisedSync) { on -> vm.update { it.copy(optimisedSync = on) } }
        }
    }

    if (confirmSsl) ConfirmDialog("Disable SSL?", "Certificate verification protects you from man-in-the-middle attacks. Only disable it for self-hosted servers with self-signed certificates. Proceed anyway?", "Disable",
        danger = true, onConfirm = { vm.update { it.copy(disableSsl = true) } }, onDismiss = { confirmSsl = false })
    if (importKey) KeyImportDialog("Import Private Key", passphraseField = false, onImport = { key, _ ->
        vm.importSigningKey(key).fold({ importKey = false; vm.toast("Key Imported") }, { vm.toast(it.message ?: "Invalid key") })
    }, onDismiss = { importKey = false })
}

@Composable
fun AuthScreen(vm: IgitViewModel) {
    val context = LocalContext.current
    val c = vm.active
    var type by remember(c.id) { mutableStateOf(if (c.authType == AuthType.NONE) Validation.authFor(c.remoteUrl.ifBlank { "https://x/y" }) else c.authType) }
    var user by remember(c.id) { mutableStateOf(vm.httpsUser()) }
    var token by remember { mutableStateOf("") }
    var ghToken by remember { mutableStateOf("") }
    var publicKey by remember(c.id) { mutableStateOf(vm.sshPublicKey()) }
    var importing by remember { mutableStateOf(false) }
    var copyFrom by remember { mutableStateOf(false) }
    var confirmCopyPrivate by remember { mutableStateOf(false) }

    PageScaffold("Authenticate", onBack = { vm.back() }) {
        Text("Select your git provider and authenticate", color = IgitColors.Secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == AuthType.HTTPS, onClick = { type = AuthType.HTTPS }, label = { Text("HTTP/S") })
            FilterChip(selected = type == AuthType.SSH, onClick = { type = AuthType.SSH }, label = { Text("SSH") })
        }
        if (vm.containers.size > 1) TextButton(onClick = { copyFrom = true }) { Text("Copy from Container") }

        if (type == AuthType.HTTPS) {
            IgitCard(modifier = Modifier.fillMaxWidth()) {
                Text("Works with GitHub, GitLab, Gitea, Codeberg, Bitbucket and self-hosted servers. Use a personal access token as the password.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(user, { user = it }, label = { Text("user") }, placeholder = { Text("JohnSmith12") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text(if (vm.hasHttpsToken()) "token (leave blank to keep)" else "token") }, placeholder = { Text("ghp_1234abcd5678efgh") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("GitHub: a fine-grained token with Contents read & write is enough to sync; add Issues, Pull requests and Actions (read) for provider tools.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    if (token.isBlank() && !vm.hasHttpsToken()) vm.toast("Field cannot be empty")
                    else { vm.saveHttpsAuth(user, token.ifBlank { null } ?: vm.runner.vault.get(app.igit.data.Secrets.token(c.id)).orEmpty()); token = "" }
                }, modifier = Modifier.fillMaxWidth()) { Text("login") }
            }
        } else {
            IgitCard(modifier = Modifier.fillMaxWidth()) {
                if (publicKey != null) {
                    Text("pub key", style = MaterialTheme.typography.titleMedium)
                    Monospace(publicKey!!)
                    Text(SshKeys.fingerprint(publicKey!!), color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("public key", publicKey))
                            vm.toast("Copied text!")
                        }) { Text("Copy pub key") }
                        TextButton(onClick = { confirmCopyPrivate = true }) { Text("Copy priv key") }
                    }
                    Text("Add this public key to your provider (GitHub: Settings → SSH and GPG keys → New SSH key).", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { publicKey = vm.generateSshKey(); vm.toast("Ed25519 key generated") }) { Text(if (publicKey == null) "generate keys" else "regenerate") }
                    OutlinedButton(onClick = { importing = true }) { Text("Import Private Key") }
                }
            }
        }

        IgitCard(modifier = Modifier.fillMaxWidth()) {
            Text("GitHub provider tools", style = MaterialTheme.typography.titleMedium)
            Text("Issues, pull requests and Actions for github.com remotes. Uses your HTTP/S token automatically; add a token here when you authenticate with SSH.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(ghToken, { ghToken = it }, label = { Text(if (vm.hasGitHubToken()) "GitHub token (saved — enter to replace)" else "GitHub token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.saveGitHubToken(ghToken); ghToken = "" }, enabled = ghToken.isNotBlank()) { Text("Save") }
                if (vm.hasGitHubToken()) TextButton(onClick = { vm.saveGitHubToken("") }) { Text("Remove", color = IgitColors.Danger) }
            }
        }
        Text("OAuth sign-in isn't available in igit; personal access tokens and SSH keys work with every provider.", color = IgitColors.Muted, style = MaterialTheme.typography.bodySmall)
    }

    if (importing) KeyImportDialog("Import Private Key", passphraseField = true, onImport = { key, pass ->
        vm.importSshKey(key, pass).fold({ publicKey = it; importing = false; vm.toast("Key Imported") }, { vm.toast(it.message ?: "Invalid key") })
    }, onDismiss = { importing = false })
    if (confirmCopyPrivate) ConfirmDialog("Confirm Private Key Copy", "Your private key gives full access to every repository it's registered with. Only paste it somewhere you trust, and clear your clipboard afterwards.", "Understood",
        danger = true, onConfirm = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("private key", vm.sshPrivateKey().orEmpty()))
        }, onDismiss = { confirmCopyPrivate = false })
    if (copyFrom) AlertDialog(
        onDismissRequest = { copyFrom = false },
        title = { Text("Copy from Container") },
        text = {
            Column {
                vm.containers.filter { it.id != c.id }.forEach { other ->
                    Text(other.alias, modifier = Modifier.fillMaxWidth().clickable { vm.copyAuthFrom(other.id); copyFrom = false; publicKey = vm.sshPublicKey(); user = vm.httpsUser() }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { copyFrom = false }) { Text("Cancel") } }
    )
}

@Composable
private fun KeyImportDialog(title: String, passphraseField: Boolean, onImport: (String, String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("priv key") }, placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY----- …") }, minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                if (passphraseField) OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onImport(key, pass) }, enabled = key.isNotBlank()) { Text("import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CloneScreen(vm: IgitViewModel, onPickFolder: () -> Unit) {
    var url by remember { mutableStateOf(vm.active.remoteUrl) }
    var depth by remember { mutableStateOf(if (vm.active.cloneDepth > 0) vm.active.cloneDepth.toString() else "") }
    var repos by remember { mutableStateOf<List<GitHubRepo>?>(null) }
    var loadingRepos by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var confirmOverwrite by remember { mutableStateOf(false) }
    val token = vm.gitHubToken ?: vm.runner.vault.get(app.igit.data.Secrets.token(vm.active.id))?.takeIf { vm.active.authType == AuthType.HTTPS }

    fun startClone() {
        vm.folderIsEmpty { empty -> if (empty) { vm.clone(url, depth.toIntOrNull() ?: 0); vm.back() } else confirmOverwrite = true }
    }

    PageScaffold("Clone Remote Repository", onBack = { vm.back() }) {
        IgitCard(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(url, { url = it.trim() }, label = { Text("Repository URL") }, placeholder = { Text("https://git.abc/xyz.git") }, singleLine = true,
                isError = url.isNotBlank() && !Validation.isValidRemoteUrl(url), modifier = Modifier.fillMaxWidth())
            Text("Advanced Options", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(depth, { depth = it.filter(Char::isDigit).take(6) }, label = { Text("Shallow Clone (Depth)") }, placeholder = { Text("full") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (vm.active.folderUri == null) {
                Text("No local folder selected — files will stay inside igit until you select one.", color = IgitColors.Warning, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onPickFolder) { Text("Select a folder to clone into") }
            } else Text("Clones into: ${vm.folderName}", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { startClone() }, enabled = Validation.isValidRemoteUrl(url) && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("clone") }
        }

        if (token != null) {
            IgitCard(modifier = Modifier.fillMaxWidth()) {
                Text("Your GitHub repositories", style = MaterialTheme.typography.titleMedium)
                if (repos == null) {
                    OutlinedButton(onClick = {
                        loadingRepos = true
                        vm.listGitHubRepos(token) { r -> loadingRepos = false; r.onSuccess { repos = it }.onFailure { vm.toast(it.message ?: "Could not load repositories") } }
                    }, enabled = !loadingRepos) { Text(if (loadingRepos) "Loading…" else "Load my repositories") }
                } else {
                    OutlinedTextField(search, { search = it }, label = { Text("Search…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(repos!!.filter { search.isBlank() || it.fullName.contains(search, true) }, key = { it.fullName }) { r ->
                            Column(modifier = Modifier.fillMaxWidth().clickable { url = if (vm.active.authType == AuthType.SSH) r.sshUrl else r.cloneUrl }.padding(vertical = 9.dp)) {
                                Text(r.fullName, style = MaterialTheme.typography.bodyLarge)
                                Text("${if (r.privateRepo) "Private" else "Public"} · ${r.branch}", color = IgitColors.Secondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        IgitCard(modifier = Modifier.fillMaxWidth()) {
            Text("Create New Repository", style = MaterialTheme.typography.titleMedium)
            Text("This creates a local repository. Authenticate and add a remote to enable sync. If you enter a URL above, it's added as the remote.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { vm.initLocalRepository(if (Validation.isValidRemoteUrl(url)) url else ""); vm.back() }, enabled = !vm.busy) { Text("Initialize main branch") }
        }
    }

    if (confirmOverwrite) ConfirmDialog("Folder Not Empty",
        "The selected folder already has files. Files with the same names as files in the repository will be overwritten; other files will be added to the repository on the next sync. This action is irreversible.",
        "Overwrite", danger = true, onConfirm = { vm.clone(url, depth.toIntOrNull() ?: 0); vm.back() }, onDismiss = { confirmOverwrite = false })
}

@Composable
fun GlobalSettingsScreen(vm: IgitViewModel) {
    val (baseUrl0, chat0, wand0) = vm.aiSettings()
    var aiOn by remember { mutableStateOf(vm.aiEnabled) }
    var baseUrl by remember { mutableStateOf(baseUrl0) }
    var chatModel by remember { mutableStateOf(chat0) }
    var wandModel by remember { mutableStateOf(wand0) }
    var key by remember { mutableStateOf("") }
    var lineWrap by remember { mutableStateOf(vm.lineWrap) }
    var passwordFor by remember { mutableStateOf<Pair<Boolean, android.net.Uri>?>(null) }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { passwordFor = true to it } }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { passwordFor = false to it } }

    PageScaffold("Global Settings", onBack = { vm.back() }) {
        IgitCard(modifier = Modifier.fillMaxWidth()) {
            Text("AI Features", style = MaterialTheme.typography.titleMedium)
            SettingSwitch(if (aiOn) "Hide AI Features" else "Enable AI Features", "Chat about your repository and the commit-message wand. Bring your own OpenAI-compatible key; requests go directly from this device.", aiOn) { aiOn = it }
            if (aiOn) {
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API base URL (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(chatModel, { chatModel = it }, label = { Text("Chat model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(wandModel, { wandModel = it }, label = { Text("Wand model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text(if (vm.aiKeyStored) "API key (leave blank to keep)" else "API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = {
                runCatching { vm.saveAiSettings(aiOn, baseUrl, chatModel, wandModel, key) }
                    .onSuccess { key = ""; vm.toast("Settings saved") }.onFailure { vm.toast(it.message ?: "Invalid settings") }
            }) { Text("Save") }
        }

        IgitCard(modifier = Modifier.fillMaxWidth()) {
            SettingSwitch("Enable Line Wrap in Editors", null, lineWrap) { lineWrap = it; vm.lineWrap = it }
        }

        IgitCard(modifier = Modifier.fillMaxWidth()) {
            Text("Encrypted Configuration Recovery", style = MaterialTheme.typography.titleMedium)
            Text("Backs up every container, its settings, auth and keys into a password-protected file.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { createBackup.launch("backup_$stamp.igbak") }) { Text("Encrypted Backup") }
                OutlinedButton(onClick = { openBackup.launch(arrayOf("*/*")) }) { Text("Encrypted Restore") }
            }
        }
    }

    passwordFor?.let { (isBackup, uri) ->
        TextPromptDialog(
            "Enter ${if (isBackup) "Backup" else "Restore"} Password", "Password", secret = true,
            validate = { if (it.length < 6) "Use at least 6 characters" else null },
            onConfirm = { pw -> if (isBackup) vm.backup(uri, pw) else vm.restore(uri, pw); passwordFor = null },
            onDismiss = { passwordFor = null }
        )
    }
}
