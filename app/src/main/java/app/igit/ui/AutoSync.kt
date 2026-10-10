package app.igit.ui

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.igit.R
import app.igit.data.QuickSyncTarget
import app.igit.sync.AppSyncAccessibilityService
import app.igit.sync.ManualSyncWidgetProvider
import app.igit.sync.SyncTileService
import app.igit.sync.SyncWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val presetIntervals = listOf(15L to "15 min", 30L to "30 min", 60L to "1 hour", 360L to "6 hours", 720L to "12 hours", 1440L to "1 day", 10080L to "1 week")

@Composable
fun AutoSyncSettings(vm: IgitViewModel) {
    val c = vm.active
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotifications() { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // ----- App Sync
    var showDisclosure by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var serviceOn by remember { mutableStateOf(AppSyncAccessibilityService.isEnabled(context)) }
    LaunchedEffect(vm.active) { serviceOn = AppSyncAccessibilityService.isEnabled(context) }
    ExpandableCard("App Sync Settings", "Automatically syncs when your selected app is opened or closed") {
        if (!serviceOn) {
            Text("Requires the igit App Sync accessibility service.", color = IgitColors.Warning, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { showDisclosure = true }) { Text("Enable accessibility service") }
        }
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (c.appSyncPackages.isEmpty()) "Select App(s)" else "Selected (${c.appSyncPackages.size})")
        }
        SettingSwitch("Sync on app(s) opened", null, c.syncOnAppOpened) { on -> vm.update { it.copy(syncOnAppOpened = on) }; if (on) askNotifications() }
        SettingSwitch("Sync on app(s) closed", null, c.syncOnAppClosed) { on -> vm.update { it.copy(syncOnAppClosed = on) }; if (on) askNotifications() }
    }
    if (showDisclosure) ConfirmDialog(
        "Accessibility Service Disclosure",
        "igit uses an accessibility service only to detect which app is in the foreground, so it can sync when the apps you selected are opened or closed. It does not read screen content, what you type, or any personal data, and nothing is sent anywhere.",
        "Open Settings",
        onConfirm = {
            vm.a11yDisclosureAccepted = true
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onDismiss = { showDisclosure = false }
    )
    if (showPicker) AppPickerDialog(c.appSyncPackages.toSet(), onSave = { picked -> vm.update { it.copy(appSyncPackages = picked.toList()) }; showPicker = false }, onDismiss = { showPicker = false })

    // ----- Scheduled Sync
    ExpandableCard("Scheduled Sync Settings", "Automatically syncs periodically in the background") {
        SettingSwitch("Scheduled Sync", if (c.scheduledSync) "every ${label(c.scheduledMinutes)}" else null, c.scheduledSync) { on ->
            vm.update { it.copy(scheduledSync = on) }; if (on) askNotifications()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presetIntervals.forEach { (minutes, text) ->
                FilterChip(selected = c.scheduledMinutes == minutes, onClick = { vm.update { it.copy(scheduledMinutes = minutes) } }, label = { Text(text) })
            }
        }
        var custom by remember(c.id) { mutableStateOf(if (presetIntervals.none { it.first == c.scheduledMinutes }) c.scheduledMinutes.toString() else "") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(5) }, label = { Text("Custom (minutes)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            TextButton(onClick = { custom.toLongOrNull()?.let { m -> vm.update { it.copy(scheduledMinutes = m.coerceIn(15, 10080)) } } }) { Text("Set") }
        }
        Text("Android runs background work no more often than every 15 minutes and may delay it to save battery.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
    }

    // ----- Quick Sync
    ExpandableCard("Quick Sync Settings", "Sync using customizable quick tiles, shortcuts, or widgets") {
        QuickSyncTarget.entries.forEach { target ->
            SettingCheck(target.label, vm.quickTarget(target)) { vm.setQuickTarget(target, it) }
        }
        Text("Add the Sync / Manual Sync tiles from Quick Settings edit mode, long-press the igit icon for shortcuts, or add a widget from your home screen.", color = IgitColors.Secondary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { requestTile(context) }) { Text("Add Sync tile") }
            OutlinedButton(onClick = { requestWidget(context, manual = false) }) { Text("Add widget") }
            OutlinedButton(onClick = { requestWidget(context, manual = true) }) { Text("Manual widget") }
        }
    }

    // ----- Other (intent)
    val index = vm.containers.indexOfFirst { it.id == c.id }
    ExpandableCard("Other Sync Settings", "Android intents for automation apps (Tasker, Automate, MacroDroid)") {
        val doc = """Action: INTENT_SYNC
Package: app.igit
Target: app.igit.sync.IntentSyncReceiver (broadcast)
   or: app.igit.sync.IntentSyncService (service)
Extras (optional):
  index  (int)    = $index   ← this container
  message (string) = commit message for this sync"""
        Monospace(doc)
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("igit intent", doc))
            vm.toast("Copied text!")
        }) { Text("Copy") }
    }
}

private fun label(minutes: Long): String = presetIntervals.firstOrNull { it.first == minutes }?.second ?: when {
    minutes % 1440 == 0L -> "${minutes / 1440} day(s)"
    minutes % 60 == 0L -> "${minutes / 60} hour(s)"
    else -> "$minutes minute(s)"
}

private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, SyncTileService::class.java), "Sync",
        Icon.createWithResource(context, R.drawable.ic_sync), context.mainExecutor
    ) { }
}

private fun requestWidget(context: Context, manual: Boolean) {
    val manager = context.getSystemService(AppWidgetManager::class.java)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(context, if (manual) ManualSyncWidgetProvider::class.java else SyncWidgetProvider::class.java), null, null)
    }
}

private data class LaunchableApp(val label: String, val packageName: String)

@Composable
private fun AppPickerDialog(initial: Set<String>, onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select application(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val list = apps
                if (list == null) LoadingBox() else LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(list.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName
                        }.padding(vertical = 2.dp)) {
                            Checkbox(checked = app.packageName in selected, onCheckedChange = null)
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = IgitColors.Secondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
