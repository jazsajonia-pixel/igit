package app.igit.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.igit.data.CommitSummary
import app.igit.data.GitHubTicket
import app.igit.data.RepoConfig

@Composable
fun HomeScreen(
    repo: RepoConfig?,
    connected: Boolean,
    folderLabel: String?,
    branch: String,
    commits: List<CommitSummary>,
    issues: List<GitHubTicket>,
    pulls: List<GitHubTicket>,
    syncing: Boolean,
    onConnect: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
    onPickFolder: () -> Unit,
    onBranch: () -> Unit,
    onPanel: (RepositoryPanel) -> Unit,
    onFilters: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
            Text("igit", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (!connected) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF34302A)) {
                    Text("LOCAL PREVIEW", color = Color(0xFFFFD28D), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            } else {
                Surface(shape = CircleShape, color = Color(0xFF173024), modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "GitHub connected", tint = Color(0xFF7DE2A2), modifier = Modifier.padding(7.dp).size(19.dp))
                }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFBEC4C8)) }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1F20)), shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("REPOSITORY", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Color(0xFF8F969B))
                        Text(repo?.fullName ?: "Connect a repository", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onConnect) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (connected) "Switch" else "Connect")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val visibleCommits = commits.take(3)
                    if (visibleCommits.isEmpty()) {
                        Surface(color = Color(0xFF292B2D), shape = RoundedCornerShape(13.dp)) {
                            Text("Recent commits will appear here after connecting GitHub.", color = Color(0xFFADB2B6), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(14.dp))
                        }
                    } else visibleCommits.forEach { commit -> CommitCard(commit) }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CURRENT BRANCH", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Color(0xFF8F969B))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onBranch).padding(top = 3.dp)) {
                            Text(branch, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Choose branch", tint = Color(0xFF80C7FF))
                        }
                    }
                    IconButton(onClick = onBranch) {
                        Icon(Icons.Default.Add, contentDescription = "Create or switch branch", tint = Color(0xFF80C7FF))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ShortcutButton("Issues", issues.size, Modifier.weight(1f), onClick = { onPanel(RepositoryPanel.ISSUES) })
                    ShortcutButton("Pull requests", pulls.size, Modifier.weight(1f), onClick = { onPanel(RepositoryPanel.PULL_REQUESTS) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSync,
                enabled = !syncing,
                modifier = Modifier.weight(1f).height(58.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF202326), contentColor = Color.White, disabledContainerColor = Color(0xFF202326), disabledContentColor = Color(0xFF91999F))
            ) {
                Icon(if (syncing) Icons.Default.Sync else Icons.Default.ArrowDownward, contentDescription = null, tint = Color(0xFF80C7FF))
                Spacer(Modifier.size(10.dp))
                Text(if (syncing) "SYNCING…" else "SYNC CHANGES", fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(58.dp).background(Color(0xFF202122), RoundedCornerShape(17.dp))) {
                Icon(Icons.Default.Tune, contentDescription = "Sync options", tint = Color.White)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1F20)), shape = RoundedCornerShape(17.dp), modifier = Modifier.weight(1f).clickable(onClick = onConnect)) {
                Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("REMOTE · ORIGIN", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Color(0xFF8F969B))
                    Text(repo?.remoteUrl?.removePrefix("https://") ?: "github.com/owner/repository.git", maxLines = 1, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
            Surface(onClick = onConnect, shape = RoundedCornerShape(17.dp), color = Color(0xFF1D1F20)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 13.dp, vertical = 17.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = if (connected) Color(0xFF7DE2A2) else Color(0xFF8F969B), modifier = Modifier.size(20.dp))
                    Text(if (connected) "AUTH" else "AUTH", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1F20)), shape = RoundedCornerShape(17.dp), modifier = Modifier.weight(1f).clickable(onClick = onPickFolder)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 15.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("LOCAL FOLDER", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Color(0xFF8F969B))
                        Text(folderLabel ?: "Choose a folder", maxLines = 1, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    Icon(Icons.Default.Folder, contentDescription = "Choose folder", tint = Color(0xFF80C7FF))
                }
            }
        }

        OutlinedButton(onClick = onFilters, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF80C7FF))
            Spacer(Modifier.size(9.dp))
            Text("GIT FILTERS", color = Color(0xFF80C7FF), fontWeight = FontWeight.Medium)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1F20)), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onSettings)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 17.dp)) {
                Text("App Sync Settings", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("⌄", color = Color(0xFFADB2B6))
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1F20)), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onSettings)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 17.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Scheduled Sync", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text("Choose a background interval in Settings", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Color(0xFFADB2B6))
                }
                Text("⌄", color = Color(0xFFADB2B6))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CommitCard(commit: CommitSummary) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF292B2D)), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(commit.message, modifier = Modifier.weight(1f), maxLines = 1, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFF3A3D40)) {
                    Text(commit.sha.take(7), color = Color(0xFFD2D6D9), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${commit.author}  ·  ${commit.relativeTime}", modifier = Modifier.weight(1f), color = Color(0xFFADB2B6), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                Text("${commit.additions} ++", color = Color(0xFF7DE2A2), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Spacer(Modifier.size(9.dp))
                Text("${commit.deletions} --", color = Color(0xFFFFA6A6), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ShortcutButton(title: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(49.dp), shape = RoundedCornerShape(14.dp), color = Color(0xFF142033)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.size(9.dp).background(Color(0xFF80C7FF), CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(title.uppercase(), color = Color(0xFF80C7FF), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(7.dp))
            Text(count.toString(), color = Color(0xFFB9DFFF), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        }
    }
}
