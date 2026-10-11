# igit

A local-first Git client for Android. igit brings repository management, everyday Git operations, background sync, and conflict resolution into a native Kotlin and Jetpack Compose app.

## What it does

### Repositories and remotes

- Keep multiple repository containers and switch between them from Home.
- Clone a remote URL, optionally use a shallow clone, browse GitHub repositories, or initialize a new repository on `main`.
- Connect over HTTPS with a username and personal access token, or over SSH with a generated/imported key.
- Keep the Git database in app-private storage and mirror working files into a folder selected with Android's Storage Access Framework.

### Git workflow

- Choose a streamlined **Sync** workflow or a more detailed **Client** workflow.
- Fetch, pull, stage, commit, and push; inspect staged and unstaged changes and handle files individually.
- Use explicit overwrite/force-pull/force-push operations when needed; destructive actions require confirmation.
- Resolve merge conflicts block by block, edit the result, continue the merge, or abort it.
- Browse recent commits and per-file diffs; create, switch, rename, and delete branches; manage remotes.

### Automation and companion tools

- Schedule background sync from every 15 minutes up to one week, with network-aware retries and notifications.
- Optionally sync when selected apps open or close. This feature uses an AccessibilityService and presents a clear disclosure.
- Trigger sync from Quick Settings tiles, launcher shortcuts, home-screen widgets, or Tasker/Automate intents.
- Use the built-in file browser/editor, GitHub issue and pull-request tools, and Actions run viewer.
- Optionally connect an OpenAI-compatible endpoint for repository chat and commit-message suggestions.

### Repository settings and portability

- Configure commit author details, sync messages, time formats, `.gitignore`, and `.git/info/exclude`.
- Create SSH-signed commits with an Ed25519 key.
- Export and restore an encrypted, password-protected backup of containers, settings, and credentials.

## Build and test

### Requirements

- JDK 17 or newer
- Android SDK Platform 36 and Build Tools 36, or Android Studio
- Android 8.0 (API 26) or newer for the app

```sh
# Run unit and local-repository sync tests
./gradlew :app:testDebugUnitTest

# Build the debug APK
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Security notes

- Repository tokens, SSH keys, and the optional AI key are encrypted using an Android Keystore AES-GCM key.
- App backups are disabled. SSH host keys are trusted on first use and then pinned locally.
- AI requests go directly from the device to the configured endpoint. Repository chat sends repository/branch context, recent commit titles, and a limited file-path list—not file contents.
- App Sync's accessibility permission is optional and used only for the selected app-open/app-close trigger.

## Current limitations

- Provider OAuth sign-in is not included; connect with a token or SSH key.
- Git LFS and git-crypt are not supported.
- Repository files are mirrored through the Storage Access Framework rather than Android's all-files-access mode.
- GitHub issue/PR/Actions tools are available; equivalent tools for GitLab, Gitea, and Codeberg are not currently included.

See [`plan.md`](plan.md) for implementation details and current scope.
