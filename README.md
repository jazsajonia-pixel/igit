# igit

A local-first, Android-native Git sync app modeled on [GitSync](https://github.com/ViscousPot/GitSync). The interface and code are original Kotlin/Jetpack Compose; no GitSync source code or branding is included.

## Features (0.4.0)

### Repositories
- **Multiple repository containers** with add / rename / delete (optionally deleting the local copy), switchable from the top of Home.
- **Any git provider**: HTTP/S with username + personal access token, or **SSH** (generate an Ed25519 key pair in-app and copy the public key, or import an existing private key with passphrase). "Copy from Container" reuses auth.
- **Clone** any remote URL, optional **shallow clone depth**, a picker for your GitHub repositories, a warning when the target folder isn't empty, or **Create New Repository** (init `main`, optionally with a remote).
- Files are mirrored into a folder you choose (Storage Access Framework); the Git database stays in app-private storage.

### Sync
- **Sync Mode** (one button "Sync Changes" plus a mode menu): Sync, Manual Sync (pick files + commit message), Download Changes, Upload Changes, Download + Overwrite (force pull), Upload + Overwrite (force push) — destructive actions ask for confirmation.
- **Client Mode**: Fetch, Pull, Stage & Commit, Push, plus Force Pull / Force Push, with a staged/unstaged change list, per-file stage/unstage/discard.
- **Merge conflict editor**: per-file resolution with Current / Incoming / Both per block or for the whole file, direct editing, Resolve, Merge, Abort Merge, Resolve Later.
- **Auto sync**
  - App Sync: sync when selected apps are opened and/or closed (accessibility service, with a prominent disclosure).
  - Scheduled Sync: 15 min to 1 week presets or a custom interval (WorkManager, retries on network errors, notifications).
  - Quick Sync: Quick Settings tiles (Sync, Manual Sync), launcher shortcuts and home-screen widgets (Sync Now, Manual Sync), each with selectable target containers.
  - Intents for Tasker/Automate: action `INTENT_SYNC` to `app.igit.sync.IntentSyncReceiver` (broadcast) or `IntentSyncService`, extras `index` (int) and `message` (string).

### History, branches and remotes
- Recent commits with author, relative time, `+/-` stats and an "unpushed" marker; tap for the full per-file diff.
- Branch switcher, create (based on any branch), rename, delete (optionally on the remote).
- Remote list with add, rename, set URL, delete and default remote.

### Repository settings
- Sync messages toggle, **Sync Message** (`%s` = date/time, default `Last Sync: %s (Mobile)`) and **Sync Message Time Format** (Java or strftime patterns).
- Author name / email, **Signed Commits** (SSH Ed25519 signatures, using the auth key or an imported key), post footer.
- `.gitignore` and `.git/info/exclude` editors, **Disable SSL** (with confirmation), **Optimised Sync**, default Sync / Client mode.

### Files, provider tools and AI
- File explorer: create files/folders, rename, delete, hidden files toggle, code editor with line numbers and optional line wrap, image viewer, and ".gitignore + Untrack" actions.
- GitHub: Issues and Pull Requests (Open / Closed / All, details, comments, close/reopen, create), Actions workflow runs.
- Optional AI (bring your own OpenAI-compatible key): repository chat and a commit-message "wand". "Hide AI Features" turns everything off.
- **Encrypted backup / restore** of all containers, settings, tokens and keys (PBKDF2 + AES-GCM, password protected).

## Not supported (yet)
- OAuth sign-in (it requires registered OAuth client IDs per provider) — use tokens or SSH keys.
- Git LFS and git-crypt.
- "All files access" storage mode — igit mirrors into a SAF folder instead.
- Issue/PR tools for GitLab, Gitea and Codeberg (syncing works with every provider).

## Build

Requires JDK 17+, Android SDK Platform 36 and Build Tools 36.0.0 (or Android Studio).

```sh
./gradlew :app:testDebugUnitTest   # unit + end-to-end sync tests (two devices via a local bare repo)
./gradlew :app:assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Security notes
- Tokens, SSH keys and the AI key are encrypted with an Android Keystore AES-GCM key; app backups are disabled.
- SSH host keys are trusted on first use and pinned in app storage.
- AI requests go directly from the phone to your configured endpoint; chat context contains the repository name, branch, recent commit titles and up to 60 file paths, never file contents.

See [plan.md](plan.md) for the architecture and the GitSync parity table.
