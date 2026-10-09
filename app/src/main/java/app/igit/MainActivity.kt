package app.igit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.igit.data.AppPreferences
import app.igit.ui.IgitApp
import app.igit.ui.IgitTheme

class MainActivity : ComponentActivity() {
    private var selectedFolder by mutableStateOf<Uri?>(null)
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            AppPreferences(this).saveFolderUri(uri.toString())
            selectedFolder = uri
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFolder = AppPreferences(this).getFolderUri()?.let(Uri::parse)
        setContent {
            IgitTheme {
                IgitApp(
                    context = this,
                    selectedFolder = selectedFolder,
                    onPickFolder = { folderPicker.launch(selectedFolder) }
                )
            }
        }
    }
}
