package app.igit.ui

import app.igit.data.CommitSummary
import app.igit.data.GitHubTicket

val sampleCommits = listOf(
    CommitSummary("f820c71", "Add pause and resume overlay", "jas", "6h ago", 128, 11),
    CommitSummary("53488c6", "Keep the traveler inside meadow bounds", "jas", "6h ago", 72, 1),
    CommitSummary("43cb683", "Add directional movement foundation", "jas", "5h ago", 110, 19)
)

val sampleIssues = listOf(
    GitHubTicket(14, "Document setup for mobile", "open", "dev-one", ""),
    GitHubTicket(12, "Add offline sync status", "open", "dev-two", "")
)

val samplePulls = listOf(
    GitHubTicket(8, "Improve branch picker", "open", "dev-three", ""),
    GitHubTicket(6, "Add repository filters", "open", "dev-one", "")
)

enum class AppTab(val title: String) {
    CHAT("Chat"), HOME("Home"), FILES("Files")
}

enum class RepositoryPanel(val title: String) {
    ISSUES("Issues"), PULL_REQUESTS("Pull requests")
}
