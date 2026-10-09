# igit

A local-first, Android-native Git workspace app inspired by the supplied reference. The interface is original; no GitSync source code or branding is included.

## Implemented foundation

- Compose dashboard with GitHub connection, recent commits, branch controls, issue/pull-request surfaces, sync controls, folder selection, filters, settings, and Chat/Home/Files navigation.
- GitHub REST support for listing repositories, reading commits/issues/pull requests, and creating issues/pull requests.
- JGit clone/fetch/pull/local commit/push foundation and branch checkout/create.
- Android Storage Access Framework folder selection, a two-way folder mirror with baseline hashes, and a file list/text editor. Git metadata stays in app-private storage.
- WorkManager periodic sync configuration with a network constraint.
- BYO AI key chat against a configurable OpenAI-compatible HTTPS endpoint; requests are sent directly from the phone to that endpoint.
- GitHub and AI credentials stored with AES-GCM encryption using an Android Keystore key.

This is an early implementation, not yet a production-validated replacement for GitSync. JGit conflicts are surfaced with a per-file choice to keep the local version or accept GitHub's version; verify merge recovery on a physical device before relying on it for important data. OAuth login, multi-remote management, signed commits, and a full editor/diff experience are not complete. Verify Android folder access and WorkManager behavior on a physical device.

## Build

Install JDK 17 or newer, Android SDK Platform 36, Build Tools 36.0.0, and Gradle 9.6.0 or use Android Studio with the current Android Gradle Plugin. The project uses Compose BOM `2026.06.01` so it can build against the SDK platform currently available in the package repository. Then run:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub access

The current connection flow uses a GitHub personal access token entered in-app, rather than OAuth. Use a fine-grained token with access limited to the selected repositories and the permissions needed for contents, issues, and pull requests. The token stays on-device in encrypted storage. Never commit it to the repository.

## AI access

In Settings, choose an HTTPS OpenAI-compatible API base URL and model, then enter your key. The key is encrypted locally and requests are made directly from the phone to the configured endpoint. Chat context includes the repository name, current branch, recent commit titles, and up to 60 local file paths; file contents are not sent. The app does not relay prompts through an igit backend.

## Local folder access

The app uses Android's Storage Access Framework and only receives access to the directory the user selects. Git worktrees are stored in the app-private directory and mirrored to that selected folder. Sync compares folder content with a baseline, imports local changes/deletions, commits them, pulls remote changes, offers local-versus-GitHub choices for conflicts, then exports the checked-out result. On first sync, a different local and remote file with the same path is flagged rather than overwritten. This data path needs real-device testing across Android storage providers.

## Project notes

See [plan.md](plan.md) for the accepted product scope, design system, and architecture. The official feature reference is [GitSync's public project page](https://github.com/ViscousPot/GitSync); this app is an independent implementation.
