# igit — implementation plan

## Product direction

Build an original, local-first Android Git companion inspired by the supplied GitSync screenshot and its publicly documented capability categories. The app is not a copy of GitSync's source, name, icon, or exact interface. Android is the initial target; an iOS version is outside this first implementation.

## Required product behavior

- Connect to GitHub and let the user select repositories; store the GitHub token locally in encrypted storage.
- Select a user-approved Android folder through the Storage Access Framework and retain its URI grant.
- Clone/fetch/pull/commit/push a repository, show recent commits and branch state, expose branch switching/creation, and surface conflicts rather than silently overwrite files.
- Provide a manual sync action and an optional periodic background-sync setting.
- Surface GitHub issues and pull requests for the selected repository.
- Provide a local file browser, repository filters/ignore settings, repository and sync settings, and bottom navigation to Chat, Home, and Files.
- Support bring-your-own AI credentials through a user-configurable OpenAI-compatible endpoint/model. Make requests directly from the device and protect the key with Android Keystore-backed encryption; do not send credentials to an app backend.
- Keep destructive or externally visible GitHub actions explicit and user initiated; AI assistance must not silently push or create records.

## Implementation approach

Use native Kotlin and Jetpack Compose for the Android UI. JGit performs Git operations against an app-private working tree. Android's Storage Access Framework grants access to the user's selected folder; a mirror adapter imports/exports folder contents so the app does not request broad storage access. GitHub REST and OpenAI-compatible requests run on background dispatchers. WorkManager hosts periodic sync. A Keystore AES-GCM vault protects the GitHub and AI tokens; non-secret preferences store sync intervals and repository metadata.

## Design system (confirmed)

- **Design Read:** portrait Android Git workspace dashboard; audience is developers syncing working folders; visual variance 3/10, motion 2/10, information density 8/10, asset dependence 1/10, reference fidelity 7/10.
- **Visual language:** compact, tactile dark developer-tool UI, using the screenshot's information density and dark palette as inspiration without copying its lettering, logo, or exact card treatment.
- **Color philosophy:** near-black `#111211` canvas and charcoal `#1D1F20`/`#292B2D` surfaces keep the focus on repository state; cyan-blue `#80C7FF` marks actions and navigation; mint `#7DE2A2` communicates successful sync; warm red is reserved for conflict/error.
- **Typography:** system rounded sans-serif for primary labels; system monospace for hashes, paths, branches, and diff metadata. Keep body text legible at phone viewing distance.
- **Spacing and shape:** 8dp base spacing; 12–20dp surface radii; restrained separators and elevation, avoiding excessive card nesting.
- **Layout:** one-handed portrait dashboard with grouped repository activity, branch/issues/PR controls, sync action, remote/folder connection, settings, then a persistent three-destination bottom bar.
- **Motion:** short 140–220ms state transitions and progress feedback; no decorative looping motion.
- **Brand:** working name **igit**, positioned as a calm pocket Git workspace; personality: practical, clear, dependable. A distinct linked-commit mark is used instead of GitSync branding.

## Project structure

- `app/src/main/java/app/igit/MainActivity.kt` — app shell, navigation, connection/setup dialogs, folder picker, Compose dashboard and tabs.
- `app/src/main/java/app/igit/data/` — GitHub REST client, AI client, repository/settings models, and persistence.
- `app/src/main/java/app/igit/security/` — Android Keystore-backed encrypted secret vault.
- `app/src/main/java/app/igit/git/` — JGit clone/sync/branch operations and SAF folder-mirror logic.
- `app/src/main/java/app/igit/worker/` — WorkManager periodic sync worker.
- `app/src/main/res/` — app theme, launcher identity, and Android resources.
- `app/src/test/` — unit tests for deterministic parsing/validation helpers.

## Source references

- GitSync public feature overview: https://github.com/ViscousPot/GitSync
- GitSync public wiki overview: https://gitsync.viscouspotenti.al/wiki/
- Android Gradle Plugin 9.4 compatibility: https://developer.android.com/build/releases/agp-9-4-0-release-notes
- Jetpack Compose setup and compatibility: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
