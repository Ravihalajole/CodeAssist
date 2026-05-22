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
- Core configuration, UI modules, and component lifecycles fully mapped.
- Core engine, envelope parser, and state machine architectures analyzed.
- Native Git-based version control mechanism and automated lock mitigation pipelines verified.
- Refactored GitManager component to leverage pure `.use` AutoCloseable execution contexts, isolated target-file index staging mutations, and transactional cache flushes.
- System integration layers fully synchronized; platform stabilized and ready for functional directives.