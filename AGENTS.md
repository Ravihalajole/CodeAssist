# AGENTS.md

CodeAssist — Android app (Kotlin) that runs an LLM "agent" against a user-selected workspace: it parses `:::CODE_ASSIST:::` clipboard envelopes, applies file mutations via JGit, and (through the accessibility service) can observe and drive other apps on the device.

## Build & run

- Single-module Gradle project: `:app` (Kotlin DSL, version catalog in `gradle/libs.versions.toml`). Wrapper = Gradle 9.0.0, AGP 8.13.0, Kotlin 2.1.0.
- `compileSdk=36`, `minSdk=23`, `targetSdk=34`, `versionCode=2`/`versionName="1.1"`. Java/Kotlin target = 17.
- View binding on; no Compose, no kapt. No `test`/`androidTest` sources exist — don't try to run a test suite.
- `local.properties` is machine-specific and not tracked (`.gitignore`); there is none checked out. Builds need a real SDK path — set `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`. CI (`.github/workflows/build-apk.yml`) writes one and runs `./gradlew assembleDebug` on JDK 17 + SDK 36; `v*` tag pushes attach the APK to a release.
- `gradle.properties` sets `parallel=true`, `Xmx=2048m`, UTF-8. `dependencyResolutionManagement` uses `FAIL_ON_PROJECT_REPOS` — never add `repositories {}` inside module build files.

## Architecture

Package root: `org.ravi.codeassist` (`app/src/main/kotlin/org/ravi/codeassist/`).

- `MainActivity.kt` — single launcher activity, hosts the UI shell. The SAF workspace picker stores both the resolved absolute path (`WORKSPACE_ROOT`) and tree URI (`WORKSPACE_SAF_URI`) and verifies the path is writable before accepting it.
- `ClipboardActivity.kt` — transparent, `singleInstance`/`noHistory`, `excludeFromRecents`; the invisible entry point triggered by the QS tile. Its `hasProcessed` one-shot guard is set in `onCreate` and deliberately never reset — focus loss/gain must not re-execute the same payload; don't "fix" it. It enforces a first-mutating-batch confirmation gate and runs transaction execution on `applicationContext` in a detached scope.
- `CodeAssistTileService.kt` — Quick Settings tile (`BIND_QUICK_SETTINGS_TILE`). Tapping it launches `ClipboardActivity`.
- `FloatingBubbleService.kt` — foreground service (`foregroundServiceType="dataSync"`), runs the overlay bubble.
- `AgentAccessibilityService.kt` — `BIND_ACCESSIBILITY_SERVICE`; config in `res/xml/accessibility_service_config.xml` (`flagRetrieveInteractiveWindows`, `flagReportViewIds`, `canPerformGestures`). This is the agent's eye/hand: capture overlay, control panel, bounding-box highlights, scroll-zone config, gesture dispatch, and element-signature matching.
- `AgenticViewModel.kt` — `AndroidViewModel` + StateFlows that drives `agent/AgentOrchestrator` (the agent loop).
- `CommandExecutor.kt` + `CommandExecutorUtils.kt` — envelope application and file validation. Shared helpers (`isPathSafe` path-traversal guard, `countOccurrences`, `ensureWorkspaceReady`) live in the `CommandExecutorUtils` object — reuse these instead of reinlining. Patch application and the non-mutating `previewPatch` share one strategy cascade (`resolvePatchText`: REPLACE_ALL → wildcard → exact → floating-indent → fuzzy → Levenshtein), so the pre-flight diff always matches what actually gets written.
- `EnvelopeParser.kt` — streaming state-machine parser (`ParseState`) that turns `:::CODE_ASSIST:::` payloads into `List<CodeCommand>`.
- `TransactionManager.kt` — staging across a command block; partial-success tolerant (NOT atomic by design — non-atomic saves tokens). Failed commands surface individually; successful ones still commit so the model re-emits only the failures. Feedback includes a per-file `--- FILE STATUS ---`, a `--- BATCH SUMMARY ---`, and a `--- STATE SNAPSHOT ---` (post-write line counts + top-level symbols via `WorkspaceScope.outlineFor`, plus git state via `GitManager.repositorySnapshot`).
- `GitManager.kt` — JGit staging. `commitChanges` stages only modified/deleted paths via per-path `addFilepattern`; `commitAllChanges` does a full add. Both serialize through an internal `Mutex` and clear `.git/index.lock` deadlocks. All operations resolve the *enclosing* repo by walking up (`findEnclosingRepo`) so a nested workspace reuses the parent repo; subtree staging is scoped to the workspace.
- `agent/` — `AgentOrchestrator`, `AgentState`, `ToolboxManager`. `AgentOrchestrator` hard-stops the loop after `MAX_LOOP_ITERATIONS = 25` rounds. It owns short-term memory: an in-memory `PLAN` checklist (declare via `[COMMAND: PLAN]`, update with `[PLAN_DONE]/[PLAN_NOTE]`, re-injected as `<ACTIVE_PLAN>`) and a rolling `RECENT_OBSERVATION_LOG` of the last ~10 transaction digests (`MAX_OBSERVATION_HISTORY = 10`). Both reset on session init/reset.
- `utils/SystemPromptGenerator.kt` — builds the bootstrap system prompt (`generate`), pulled in by `AgentOrchestrator.buildSystemPrompt`. There is no real chat-app `system` channel; the whole prompt is typed as the first user message, so it drifts on long ReAct loops. Mitigations: an immutable `PRIMARY_RULES` block, `standingReminder` (re-anchors rules on every `TRANSACTION_RESULT`/`TRANSACTION_ERROR`), and `truncateForInjection` (caps oversized feedback and embedded `CodeAssist.md` content, e.g. 12000 chars for project context).
- `database/` — Room (`CodeAssistDatabase`, `AgentRepository`, `AgentProfile`, plus `ElementRole`, `ElementSignature`, `ExecutionHistory` for accessibility capture). `onUpgrade` is non-destructive: idempotent, `try/catch`-wrapped `ALTER TABLE ADD COLUMN` migrations per version, never drop/recreate.
- `ui/` — `AgentOverlayManager`, `OverlayConfirmationManager`, `BoundingBoxView`, `ScrollZonePickerView`, `TransactionSummaryController`.
- `utils/` — fuzzy matcher, outline/signature extractors, tree minimizer, `WorkspaceScope` (bounded boot-time file tree + line-count index + post-write outline verification), `SystemPromptGenerator`, profile IO.

## Required permissions (granted by user, not tested headlessly)

- `MANAGE_EXTERNAL_STORAGE` ("All files access") — `requestLegacyExternalStorage="true"` also set. Note: `MainActivity.workspacePickerLauncher` resolves the SAF tree URI to an absolute `java.io.File` path via `Environment.getExternalStorageDirectory()` (deprecated on API 30+). JGit operates on that path with `java.io.File`, not SAF `DocumentFile`. On scoped-storage-only OEMs the resolved path may not match the SAF grant and git operations can silently fail — `verifyAndSaveWorkspace` catches this at pick time. Long-term fix is migrating to `DocumentFile`; do not assume SAF == File.
- `SYSTEM_ALERT_WINDOW` (bubble overlay), `QUERY_ALL_PACKAGES` (installed-app list), `POST_NOTIFICATIONS` (foreground service), accessibility service enabled in Settings.

## Code style / conventions

- Kotlin official style (`kotlin.code.style=official`).
- All sources live under `app/src/main/kotlin/...` (non-standard `kotlin` source set, not `java/`).
- Some dependencies are declared inline in `app/build.gradle.kts` rather than the catalog: `material` is duplicated (catalog + inline), while `lifecycle-viewmodel-ktx` and `kotlinx-coroutines-android` exist only inline — check both files before bumping.
- No comments unless behavior is non-obvious; keep that convention.

## Protocol format (read before touching the parser)

Commands are clipboard payloads wrapped in `:::CODE_ASSIST:::` envelopes (closed by `:::END_CODE_ASSIST:::`) and parsed by `EnvelopeParser` as a state machine (`when(currentState)` over `ParseState`). The `CodeCommand` sealed class in `CodeCommand.kt` is `when`-matched across **five** files; adding a command type requires a branch in **all** of them or you'll silently fall through:
- `CodeCommand.kt` — add the `data class` with `isMutating`.
- `EnvelopeParser.kt` — add the `when (name)` branch and any `[FIELD:]` parsing.
- `CommandExecutor.kt` — both `when (command)` blocks (validation in `validate()`, dispatch in `execute()`).
- `TransactionManager.kt` — the `when` blocks for staging, commit message, and modified-path tracking.
- `ui/TransactionSummaryController.kt` — both `when (command)` blocks for the summary UI.

Only `:::CODE_ASSIST:::` envelopes are parsed. The `:::CODE_ASSIST_TRANSACTION_ERROR:::` / `:::CODE_ASSIST_TRANSACTION_RESULT:::` envelopes the app writes back to the LLM (in `TransactionManager` and `AgentOrchestrator`) are output-only — no parser branch exists for them.

`AutoAllowMode` (in `CodeCommand.kt`) gates auto-approval in `ClipboardActivity` and `agent/AgentOrchestrator.kt`. `READ_WRITE` and session auto-allow only auto-approve *non-destructive* mutations (PATCH/CREATE); every `isDestructive` command (DELETE/MOVE) requires human confirmation regardless of mode — it overrides even an armed "Allow for Session". `AskUser`/`Done` halt the loop (`HALT_FOR_USER` / `HALT_DONE`) and are filtered out before staging in `TransactionManager`.

`[COMMAND: PLAN]` is a non-mutating task-tracker command: a non-empty `[CONTENT]` checklist replaces the stored plan in `AgentOrchestrator` (`applyPlan`), while `[PLAN_DONE: n]` and `[PLAN_NOTE: ...]` work standalone. It is filtered out of the file pipeline everywhere and the live checklist is re-injected every round as `<ACTIVE_PLAN>`.

The boot prompt embeds a `WORKSPACE TREE` + `FILE INDEX` from `WorkspaceScope`; every batch result/error carries a `--- STATE SNAPSHOT ---` with post-write verification and git state, plus `<ACTIVE_PLAN>` and `<RECENT_OBSERVATION_LOG>`. These blocks are output-only like the error/result envelopes.

## What NOT to change without thinking

- `applicationId` and `namespace` (`org.ravi.codeassist`) — changing them breaks the accessibility-service binding and SAF grants.
- The `dataSync` foreground service type on `FloatingBubbleService` — required for the long-running bubble session; another type is rejected on Android 14+.
- `launchMode="singleInstance"` and `noHistory="true"` on `ClipboardActivity` — these prevent it leaking into recents.
- `ClipboardActivity.hasProcessed` — resetting it on focus/`onResume` would re-execute the same clipboard payload.
- `dependencyResolutionManagement` uses `FAIL_ON_PROJECT_REPOS` — do not add `repositories {}` inside module build files.
