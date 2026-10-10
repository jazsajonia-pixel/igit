package app.igit.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

@Composable
fun IgitApp(vm: IgitViewModel) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.setFolder(uri)
        }
    }
    val pickFolder = { folderPicker.launch(vm.active.folderUri?.toUri()) }

    LaunchedEffect(vm.message) {
        vm.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.message = null }
    }
    BackHandler(enabled = vm.backStack.isNotEmpty() || vm.tab != AppTab.HOME) {
        if (!vm.back()) vm.tab = AppTab.HOME
    }

    when (val screen = vm.screen) {
        Screen.Main -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { IgitBottomBar(vm.tab) { vm.tab = it } }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (vm.tab) {
                    AppTab.HOME -> HomeScreen(vm, pickFolder)
                    AppTab.CHAT -> ChatScreen(vm)
                    AppTab.FILES -> FilesScreen(vm, pickFolder)
                }
            }
        }
        else -> Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.RepoSettings -> RepositorySettingsScreen(vm)
                    Screen.GlobalSettings -> GlobalSettingsScreen(vm)
                    Screen.Auth -> AuthScreen(vm)
                    Screen.Clone -> CloneScreen(vm, pickFolder)
                    Screen.Conflicts -> ConflictsScreen(vm)
                    Screen.Actions -> ActionsScreen(vm)
                    is Screen.CommitDetail -> CommitDetailScreen(vm, screen.commit)
                    is Screen.Tickets -> TicketsScreen(vm, screen.pullRequests)
                    is Screen.TicketDetail -> TicketDetailScreen(vm, screen.number, screen.pullRequest)
                    is Screen.CreateTicket -> CreateTicketScreen(vm, screen.pullRequest)
                    Screen.Main -> Unit
                }
            }
        }
    }

    if (vm.showManualSync) ManualSyncDialog(vm, onDismiss = { vm.showManualSync = false })
}
