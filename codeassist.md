# CodeAssist Architectural Context

## Project Overview
CodeAssist is an elite, Android-native developer execution engine running a localized clipboard processing loop. It parses specialized instruction payloads to execute deterministic file mutations directly inside the physical storage system.

## System Architecture & Component Mapping
- **Language Stack**: 100% Kotlin & Gradle Kotlin DSL (`build.gradle.kts`).
- **Core Staging Engine**: `CommandExecutor.kt`, `EnvelopeParser.kt`, `CodeCommand.kt`.
- **Version Control Layer**: `GitManager.kt` leveraging JGit framework capabilities.
- **Background Interface**: `FloatingBubbleService.kt` (Overlay manager) & `CodeAssistTileService.kt` (Quick Settings Integration).
- **Core Activities & UI**: `MainActivity.kt`, `ClipboardActivity.kt`, and `ConfirmationBottomSheet.kt`.

## Core Performance Configurations & Optimizations
1. **High-Performance Git Pipeline**: Transitions repository updates from expensive full-workspace scans ($O(N)$ full index walks via `git.status()`) down to localized individual file updates ($O(1)$ targeted additions and removals).
2. **Lock-File Resilience Engine**: Protects JGit operations against transient file-system deadlocks using a combination of coroutine-isolated thread synchronization (`Mutex`) and proactive, multi-gate `.git/index.lock` cleanup routines.
3. **Allocation-Free Patching Loops**: Uses structural sliding indices (`indexOf`) instead of standard string parsing arrays to avoid unnecessary Garbage Collection (GC) pressure when processing massive source code payloads.
4. **Resilient Foreground Services**: Employs an explicit `dataSync` service allocation profile under Android 14 target rules to protect the quick-access overlay from systemic Low Memory Killer (LMK) cleaning loops.
5. **Intelligent Directory Pruning**: Prevents path-traversal listing degradation by completely blocking deep file walks into system artifacts (`build/`, `.git/`, `node_modules/`).

## Local Storage Profiles
- **Shared Preferences Workspace**: Keys mapped inside `CodeAssistPrefs` (`WORKSPACE_ROOT`, `BUBBLE_ENABLED`, `AUTO_READ_ENABLED`, `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL`).
- **Target Boundaries**: Minimum SDK 23, Target SDK 34, Compile SDK 36.

## Workspace Synchronization Status
- Platform engine fully optimized.
- Memory leak vectors closed.
- Lifecycle state transitions stabilized.