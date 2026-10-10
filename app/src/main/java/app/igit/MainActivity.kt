package app.igit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.igit.ui.IgitApp
import app.igit.ui.IgitTheme
import app.igit.ui.IgitViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: IgitViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) viewModel.handleIntent(intent)
        setContent {
            IgitTheme {
                IgitApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Pick up background syncs that happened while the app was away.
        if (!viewModel.busy) viewModel.refresh()
    }

    companion object {
        const val ACTION_MANUAL_SYNC = IgitViewModel.ACTION_MANUAL_SYNC
        const val ACTION_RESOLVE_CONFLICTS = IgitViewModel.ACTION_RESOLVE_CONFLICTS
        const val EXTRA_CONTAINER = IgitViewModel.EXTRA_CONTAINER
    }
}
