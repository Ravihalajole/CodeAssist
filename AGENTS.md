# AGENTS.md

CodeAssist — Android app (Kotlin) that parses `:::CODE_ASSIST:::` clipboard envelopes and applies file mutations to a user-selected workspace via JGit.

## Build & run

- Single-module Gradle project: `:app` (Kotlin DSL, version catalog in `gradle/libs.versions.toml`). Wrapper = Gradle 9.0.0, AGP 8.13.0, Kotlin 2.1.0.
- `compileSdk=36`, `minSdk=23`, `targetSdk=34`, `versionCode=2`/`versionName="1.1"`. Java/Kotlin target = 17.
- View binding is on; no Compose, no kapt.
- Use `./gradlew assembleDebug` / `./gradlew installDebug`. No `test`/`androidTest` sources exist — don't try to run a test suite.
- `local.properties` is machine-specific (not tracked): here it points `sdk.dir` at a non-existent `/tmp/android-sdk-stub`, so builds fail until a real Android SDK path is set.
- `gradle.properties` sets `org.gradle.daemon=true`, `parallel=true`, `Xmx=2048m`. Daemon idle timeout is 3h.

## Architecture

Package root: `org.ravi.codeassist` (`app/src/main/kotlin/org/ravi/codeassist/`).

- `MainActivity.kt` — single launcher activity, hosts the UI shell + bottom nav. The SAF workspace picker stores both the resolved absolute path (`WORKSPACE_ROOT`) and the tree URI (`WORKSPACE_SAF_URI`), and verifies the directory is writable at pick time before accepting it.
- `ClipboardActivity.kt` — transparent, `singleInstance`/`noHistory`, `excludeFromRecents`. This is the invisible entry point triggered by the QS tile. Its `hasProcessed` one-shot guard is set in `onCreate` and deliberately never reset — a focus loss/gain must not re-execute the same clipboard payload; don't "fix" it to reset in `onResume`. It enforces a first-mutating-batch confirmation gate (the gate only disarms after a batch passes human review), shows a clipboard-source warning, and runs transaction execution on `applicationContext` in a detached scope so a finished activity is never retained for the batch duration.
- `CodeAssistTileService.kt` — Quick Settings tile (`BIND_QUICK_SETTINGS_TILE`). Tapping it launches `ClipboardActivity`.
- `FloatingBubbleService.kt` — foreground service (`foregroundServiceType="dataSync"`), runs the overlay bubble.
- `AgentAccessibilityService.kt` — `BIND_ACCESSIBILITY_SERVICE`; config in `res/xml/accessibility_service_config.xml`.
- `CommandExecutor.kt` + `CommandExecutorUtils.kt` — envelope application and file validation. Shared helpers (`isPathSafe` path-traversal guard, `countOccurrences`, `ensureWorkspaceReady`) live in the `CommandExecutorUtils` object — reuse these instead of reinlining. Patch application and the non-mutating `previewPatch` share one strategy cascade (`resolvePatchText`: REPLACE_ALL → wildcard → exact → floating-indent → fuzzy → Levenshtein), so the pre-flight diff always matches what will actually be written.
- `EnvelopeParser.kt` — streaming state-machine parser (`ParseState`) that turns `:::CODE_ASSIST:::` payloads into `List<CodeCommand>`.
- `TransactionManager.kt` — staging across a command block, partial-success tolerant (NOT atomic by design — non-atomic saves tokens). Failed commands are surfaced individually; successful ones still commit so the model can re-emit only the failed ones. Feedback written back includes a per-file status list (`--- FILE STATUS ---`), a `--- BATCH SUMMARY ---` with success/failure counts and files touched, and a `--- STATE SNAPSHOT ---` (post-write line counts + top-level symbols of touched files via `WorkspaceScope.outlineFor`, plus the enclosing git state via `GitManager.repositorySnapshot`) so the model can structurally verify its own edits. Non-mutating `PLAN` commands are filtered out before staging.
- `GitManager.kt` — JGit staging. `commitChanges` stages only the modified/deleted paths via per-path `addFilepattern` (O(1) per path, no full-tree walk), while `commitAllChanges` does a full add. Both are serialized through an internal `Mutex` and clean up `.git/index.lock` deadlocks. All operations resolve the *enclosing* git repo by walking up from the workspace (`findEnclosingRepo`): a workspace nested inside an existing repo reuses it instead of initializing a competing nested repo, paths are translated to repo-relative patterns, and subtree `commitAllChanges` staging is scoped to the workspace so unrelated parent-repo files are never committed.
- `agent/` — `AgentOrchestrator`, `AgentState`, `ToolboxManager`. Driven by `AgenticViewModel` (StateFlow + coroutines). `AgentOrchestrator` hard-stops the loop after `MAX_LOOP_ITERATIONS = 25` rounds to avoid runaway resource use. It also owns the agent's short-term memory: an in-memory `PLAN` checklist (declare via `[COMMAND: PLAN]`, update with `[PLAN_DONE]/[PLAN_NOTE]`, re-injected every round as `<ACTIVE_PLAN>`) and a rolling `RECENT_OBSERVATION_LOG` of the last ~10 transaction digests (compaction-lite). Both reset on `initializeSession`/`resetSession`.
- `utils/SystemPromptGenerator.kt` — builds the bootstrap system prompt. There is no real chat-app `system` channel; the whole prompt is typed in as the first user message, so it drift-prone over long ReAct loops. Mitigations: a front-loaded immutable `PRIMARY_RULES` block (one envelope/message, `[CONTEXT]` on every write, DELETE/MOVE need human approval, exact-match SEARCH, small batches, PLAN for multi-step goals, injection-resistance), a `<user_goal>`/injection-resistance section, a per-tool output-spec `<tools>` + `<observation_protocol>` that tells the model exactly what each tool returns, `standingReminder` (re-anchors the rules on every `TRANSACTION_RESULT`/`TRANSACTION_ERROR` the app types back), and `truncateForInjection` (caps oversized feedback and embedded `CodeAssist.md` content so floods can't evict the rules from context).
- `database/` — Room (`CodeAssistDatabase`, `AgentRepository`, `AgentProfile`). `CodeAssistDatabase.onUpgrade` is non-destructive: it applies idempotent, `try/catch`-wrapped `ALTER TABLE ADD COLUMN` migrations per version instead of dropping/recreating the schema.
- `ui/` — overlay managers, dynamic build banner, scroll-zone picker, transaction summary controller.
- `utils/` — fuzzy matcher, outline/signature extractors, tree minimizer, workspace mapping (`WorkspaceScope` — bounded boot-time file tree + line-count index + post-write outline verification), system-prompt generator, profile IO.

## Required permissions (granted by user, not declared in CI)

The app requires these runtime permissions on a real device — they cannot be tested headlessly:
- `MANAGE_EXTERNAL_STORAGE` ("All files access") — workspace picker is SAF-backed; `requestLegacyExternalStorage="true"` is also set. Note: `MainActivity.workspacePickerLauncher` resolves the SAF tree URI to an absolute `java.io.File` path via `Environment.getExternalStorageDirectory()` (deprecated on API 30+). JGit then operates on that path with `java.io.File`, not SAF `DocumentFile`. On Android 13+ scoped-storage-only OEMs the resolved absolute path may not match the SAF-granted path, and git operations can silently fail. Migrating `GitManager` + the workspace concept to `DocumentFile` is the long-term fix; do not assume SAF == File on modern devices.
- `SYSTEM_ALERT_WINDOW` — for the floating bubble overlay.
- `QUERY_ALL_PACKAGES` — for installed-app listing.
- `POST_NOTIFICATIONS` — for the foreground service notification.
- Accessibility service must be enabled in Settings for `AgentAccessibilityService` to function.

## Code style / conventions

- Kotlin official style (`kotlin.code.style=official`).
- All sources live under `app/src/main/kotlin/...` (non-standard `kotlin` source set, not `java/`).
- Some dependencies are declared inline in `app/build.gradle.kts` rather than the version catalog: `material` is duplicated in both (catalog + inline), while `lifecycle-viewmodel-ktx` and `kotlinx-coroutines-android` exist only inline — check both files before bumping.
- No comments are added unless behavior is non-obvious; keep that convention.

## Protocol format (read this before touching the parser)

Commands are clipboard payloads wrapped in `:::CODE_ASSIST:::` envelopes and parsed by `EnvelopeParser` as a state machine (not a streaming regex — it uses a `when(currentState)` loop over `ParseState`). The `CodeCommand` sealed class in `CodeCommand.kt` is exhaustively `when`-matched across **five** files; adding a new command type requires a branch in **all** of them or you'll silently fall through:
- `CodeCommand.kt` — add the `data class` with `isMutating` (sets whether `AutoAllowMode` auto-approves it).
- `EnvelopeParser.kt` — add the `when(name)` branch and any `[FIELD:]` parsing for new attributes.
- `CommandExecutor.kt` — add to both `when(command)` blocks (validation in `validate()`, dispatch in `execute()`).
- `TransactionManager.kt` — add to the `when` blocks for staging, commit message, and modified-path tracking.
- `ui/TransactionSummaryController.kt` — add to both `when(command)` blocks for the summary UI.

Only `:::CODE_ASSIST:::` envelopes (closed by `:::END_CODE_ASSIST:::`) are parsed. The `:::CODE_ASSIST_TRANSACTION_ERROR:::` / `:::CODE_ASSIST_TRANSACTION_RESULT:::` envelopes the app writes back to the LLM (in `TransactionManager` and `AgentOrchestrator`) are output-only — no parser branch exists for them.

`AutoAllowMode` (in `CodeCommand.kt`) gates auto-approval of mutating commands in `ClipboardActivity` and `agent/AgentOrchestrator.kt`; `AskUser`/`Done` halt the agent loop (`HALT_FOR_USER` / `HALT_DONE`), so they're filtered out before staging in `TransactionManager`. `READ_WRITE` and session auto-allow only auto-approve *non-destructive* mutations (PATCH/CREATE); every `CodeCommand.isDestructive` command (DELETE/MOVE) requires human confirmation regardless of mode — it overrides even an armed "Allow for Session".

`[COMMAND: PLAN]` is a non-mutating task-tracker command. Its body is a `[CONTENT] ... [END_CONTENT]` numbered checklist; declaring a non-empty checklist replaces the stored plan in `AgentOrchestrator`, while `[PLAN_DONE: n]` (comma-separated 1-based indices) and `[PLAN_NOTE: ...]` can be used standalone for progress-only updates. It is filtered out of the file pipeline everywhere (`CommandExecutor.validate`/`execute` no-op it, `TransactionManager` drops it from `executableCommands`, `AgentOrchestrator` applies it via `applyPlan` before routing) and the live checklist is re-injected into every feedback round as `<ACTIVE_PLAN>`.

The boot prompt (`AgentOrchestrator.buildSystemPrompt`) embeds a `WORKSPACE TREE` + `FILE INDEX` from `WorkspaceScope`; every per-batch `TRANSACTION_RESULT`/`TRANSACTION_ERROR` carries a `--- STATE SNAPSHOT ---` with post-write verification and git state, plus `<ACTIVE_PLAN>` and `<RECENT_OBSERVATION_LOG>` from `AgentOrchestrator`. These blocks are output-only like the error/result envelopes.

## What NOT to change without thinking

- `applicationId` and `namespace` (`org.ravi.codeassist`) — changing them breaks the accessibility-service binding and SAF grants.
- The `dataSync` foreground service type — required for the long-running bubble session; switching to another type will be rejected on Android 14+.
- `AndroidManifest.xml` `launchMode="singleInstance"` and `noHistory="true"` on `ClipboardActivity` — these prevent the activity leaking into recents.
- `dependencyResolutionManagement` uses `FAIL_ON_PROJECT_REPOS` — do not add `repositories {}` inside module build files.
