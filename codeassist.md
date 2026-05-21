# CodeAssist Context

## Project Overview
CodeAssist is an Android-native developer execution engine operating via a clipboard-based loop. It parses specific markup to execute file system modifications deterministically.

## Architecture & Components
- **Language**: Kotlin
- **Package Path**: `app/src/main/kotlin/org/ravi/codeassist`
- **Core Engine**: `CommandExecutor.kt`, `EnvelopeParser.kt`, `CodeCommand.kt`
- **UI Components**: `MainActivity.kt`, `ClipboardActivity.kt`, `ConfirmationBottomSheet.kt`
- **System Integrations**: `CodeAssistTileService.kt` (Quick Settings), `FloatingBubbleService.kt`
- **Data/State**: `GitManager.kt` (version control), `LogDatabaseHelper.kt`, `LogsAdapter.kt`
- **Build System**: Gradle Kotlin DSL (`build.gradle.kts`)

## Configuration Details
- **Permissions**: `MANAGE_EXTERNAL_STORAGE` (All Files Access), `SYSTEM_ALERT_WINDOW` (Floating Bubble).
- **SDKs**: Min 23, Target 34, Compile 36. Java 17.
- **Dependencies**: JGit, Material Components (1.14.0), ViewBinding enabled.
- **Manifest Components**: `MainActivity`, `CodeAssistTileService` (QS Tile), `ClipboardActivity` (Transparent Clipboard Bridge), `FloatingBubbleService`.

## Core Engine Details
- **CommandExecutor**: Executes valid commands with strict security boundaries (`isPathSafe` traversal checks) and auto-generates workspace root if missing. Normalizes line endings to prevent patch mismatches.
- **EnvelopeParser**: Uses a deterministic state machine (`ParseState`) to accurately extract commands and preserve raw multiline whitespace for file content and patch blocks.

## State
- Core configuration mapped.
- Core engine analyzed.
- Next step: Analyze UI components and state integrations.
- **Update**: Migrated logging system from SQLite to native Git commits with auto-refreshing UI.
- **Fix**: Added stale `index.lock` cleanup to `GitManager` to prevent intermittent commit failures.