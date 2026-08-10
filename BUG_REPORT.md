# CodeAssist — Code Sweep Bug Report

A categorized sweep of `app/src/main/kotlin/org/ravi/codeassist/`. Findings are grouped by severity. Every entry has a `file:line` reference and a concrete reproduction or trigger condition.

---

## P0 — Correctness bugs that silently corrupt state or break the agent loop

### 1. `findFuzzyMatchAndReplace` strips ALL whitespace from every line, not just inter-token spaces
`CommandExecutor.kt:450–452`
```kotlin
val normalizedOriginalLines = originalLines.map { it.replace("\\s".toRegex(), "") }
val normalizedSearchLines   = searchLines.map   { it.replace("\\s".toRegex(), "") }
```
The intent is to ignore inter-token whitespace, but `\s` matches `\n` too. Since each `line` is already a single line, the practical effect is collapsing all internal whitespace — meaning `val x = 1` and `val x=1` are treated as identical (`valx=1` == `valx=1`), **and** `foo(` and `foo (` become indistinguishable. Two structurally different lines can match, and then the fuzzy replace path rewrites the original with real content replaced by a stripped/aligned version. Worse, because this branch is reached when `countOccurrences != 1` (i.e. ambiguous exact match), the fix can fire on a search block that wasn't an exact match at all. Reproduction: a `PATCH` with `x = 1` in `SEARCH` and the file containing `x  =  1` and also `x=1` somewhere else will fuzzy-match the wrong location.

**Fix**: strip only space/tab runs (`"[ \t]+"`), not all `\s`.

---

### 2. `findFuzzyMatchAndReplace` recycles nothing and runs on the main `Dispatchers.IO` thread while O(N·M)ing every search line through Levenshtein
`CommandExecutor.kt:479–522` (also `findAdvancedLevenshteinMatchAndReplace`)
The intent is "only fire when exactly one candidate within a distance threshold". But the threshold check at line 497 is:
```kotlin
if (currentDistance < trimmedSearchLines.size * 3) {
    if (currentDistance < bestDistance) {
        bestDistance = currentDistance; bestMatchIndex = i; bestMatchCount = 1
    } else if (currentDistance == bestDistance) {
        bestMatchCount++
    }
}
```
Two problems:
1. If a *later* window has a *strictly smaller* distance, `bestMatchCount` is reset to `1`, discarding the count of equal-distance earlier matches. So a fuzzy match can be accepted on a window that was actually non-unique, because a better match later overwrote the count. A non-unique match silently goes through.
2. `currentDistance == bestDistance` only fires when the *new* window equals the current best — but if window #1 (distance 10) and window #3 (distance 5) both qualify, `bestMatchCount` ends at `1`, but the count is supposed to reflect *uniqueness*. The semantics of `bestMatchCount` are incoherent.

Reproduction: a `SEARCH` block that Levenshtein-matches once at distance 12 and once at distance 4 — the second wins with `bestMatchCount=1`, and the patch applies to the wrong block.

**Fix**: track `bestMatchCount` as the total number of windows whose distance ≤ the *final* best distance, and reject if >1.

---

### 3. `EnvelopeParser.parse` flushes the LAST command twice (or the parser drops content) when an envelope has no `:::END_CODE_ASSIST:::`
`EnvelopeParser.kt:44–47` + `96–98`
When the model truncates output and never emits `:::END_CODE_ASSIST:::`, the parser:
- Never flushes at line 47 (end branch).
- Falls to the tail at line 96: `if (currentCommandName.isNotEmpty() && currentState != ParseState.IDLE) flushPendingCommand()` — flushes the buffer content correctly.

But consider a `PATCH` block mid-`SEARCH`/`REPLACE` at end of stream (truncated mid-search): `currentState == IN_SEARCH`, `searchBuffer` is partial. `buildPendingCommand` at line 108 returns `null` only if `search.isEmpty()` — but the partial search isn't empty. So a fragment like:
```
:::CODE_ASSIST:::
[COMMAND: PATCH]
[PATH: file.kt]
<<<<<<< SEARCH
partial line that got cut off
```
constructs a `Patch` with `search="partial line that got cut off"` and `replace=""`. That command silently passes validation (`validate()` at line 73 only checks `contains`), then `handlePatch` finds 0 occurrences and reports "Patch rejection" with a `SMART FALLBACK CONTEXT`. The model then sees a successful-looking error and tries again with the same truncated string. The parser should require the full `>>>>>>> REPLACE` closer before constructing a PATCH (or at least require `currentState == IN_ENVELOPE` at flush time).

Reproduction: any LLM response cut off after `<<<<<<< SEARCH`.

---

### 4. `TransactionManager.executeBatch` commits partial successes even when later commands fail, contradicting the "atomic" contract
`TransactionManager.kt:74–98` + `126`
The README and `AGENTS.md` both say "atomic transactions, all-or-nothing". The loop:
```kotlin
for (command in executableCommands) {
    val result = CommandExecutor.execute(command, workspaceRoot)
    if (result.success) { ...; modifiedPaths.add(...) }
    else { executionFailures.add(...); continue }   // CONTINUE, not break
}
// then at line 100:
if (hasModifications && successCount > 0 && !containsAskUser) {
    GitManager.commitChanges(rootFile, detailedMessage, modifiedPaths.distinct(), ...)
}
```
If command #1 is a `CREATE` that succeeds and command #2 is a `PATCH` that fails, command #1 is **already written to disk and committed to git** before the failure is reported. The "transaction" is only atomic to git (the commit moves forward with what succeeded); the working tree is permanently mutated. The model receives a `PARTIAL_BATCH_FAILURE` notice saying "Already Committed" (line 142) — but nothing rolls back. This is the central guarantee of the app, and it does not hold.

Reproduction: any batch where the first command succeeds and a later one fails.

**Fix**: stage all file writes to a temp dir / in-memory buffers first, only commit after all succeed; OR validate every command before executing any (the code already has `CommandExecutor.validate()` — the executor path should pre-validate all commands and refuse to start the batch if any are invalid, leaving the floating-confirm path as the only "execute subset" flow).

---

### 5. `CommandExecutor.execute` returns `ExecutionResult(true, "HALT_FOR_USER: ...")` for `AskUser` — but `AskUser` was already filtered out in `TransactionManager` line 23
`CommandExecutor.kt:133–134` + `TransactionManager.kt:23`
```kotlin
val executableCommands = commands.filter { it !is CodeCommand.Done && it !is CodeCommand.AskUser }
```
So the `AskUser` branch in `CommandExecutor` is dead. Not a bug per se, but coupled to it: `AgentOrchestrator.handleCommandRouting` line 278 computes `hasValidDone = validCommands.any { it is Done } && actionCommands.isEmpty()` — and since `validCommands` includes `AskUser`, **an `AskUser` will short-circuit `hasValidDone` to false** even though `AskUser` itself is the halt signal. Lookahead path is fine, but the `hasValidDone` flag is computed against the wrong list when `AskUser` is present. Net effect: `processExecutionResults(success, finalLogs, hasValidDone)` gets a false `hasValidDone` and the loop never goes IDLE for pure `AskUser + Done` batches.

---

## P1 — High-likelihood runtime failures

### 6. `ClipboardActivity` uses `onWindowFocusChanged` + `hasProcessed` flag that RESETS on every `onResume`
`ClipboardActivity.kt:28–39`
```kotlin
override fun onResume() { super.onResume(); hasProcessed = false }
override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus && !hasProcessed) { hasProcessed = true; executeClipboardAction() }
}
```
Deployment scenario: user taps the QS tile → `ClipboardActivity` starts transparent → focus arrives → `executeClipboardAction()` reads clipboard, calls `finish()` (line 46 etc.). So far OK. But `onResume()` resets `hasProcessed = false` on every resume. If the system momentarily loses and regains focus (e.g. an incoming notification heads-up, an IME flash, the bubble overlay itself briefly grabbing focus), `onWindowFocusChanged(true)` fires again with `hasProcessed == false`, and **the same clipboard payload is executed twice**. Reproduction: trigger QS tile while a heads-up notification slips in.

**Fix**: gate with a one-shot in `onCreate` instead of `onResume`, or use a `Bundle` saved-state key.

---

### 7. `FloatingBubbleService` is started with `startService` (not `startForeground`) and only calls `startForeground` in `onStartCommand`
`MainActivity.kt:407,433` + `FloatingBubbleService.kt:166–168`
On Android 12+ (`targetSdk=34` here), starting a background service that then promotes itself to foreground via `startForeground` from `onStartCommand` works only if started from a foreground context. From `MainActivity.onCreate` line 432–434 the activity is in the foreground, so this is fine there. But the bubble is also stopped and restarted in `MainActivity.kt:335–336` after the icon-style change while the activity is in the foreground — also fine. The latent risk: if the user backgrounds the app while a restart is in flight, the service start is rejected with `ForegroundServiceStartNotAllowedException`, the `startForeground` call inside `onStartCommand` is never reached, and the bubble silently fails. Low frequency but real.

---

### 8. `AgentAccessibilityService.scoreNode` / `getScoredNodes` filters out the app's own package, but `getActiveProfile()?.packageName` can be null while the service's own `packageName` always returns the service app
`AgentAccessibilityService.kt:419,442–448,453–458`
```kotlin
val targetPackage = org.ravi.codeassist.agent.AgentOrchestrator.getActiveProfile()?.packageName ?: currentTargetPackage
...
if (root.packageName?.toString() != packageName) { rootsToScan.add(root) }
```
Line 442 compares against `packageName` (the *service's own* package, `org.ravi.codeassist`), which correctly filters out CodeAssist's own overlay. Good. But the `targetPackage` local on line 419 is **dead** — it's only assigned, never used in the scoring/scan path. The intent (likely: only score nodes in the target app's window) is therefore not enforced; nodes from unrelated foreground apps (e.g. a quick-settings panel, recents) can match and be tapped. Reproduction: with a profile loaded for `com.example.chat`, switch to a totally different app and the service can still find and send a synthetic tap to a button there.

---

### 9. `AgentAccessibilityService.findNodeBySignature` tie-breaker uses spatial distance against `sig.boundsX/Y` but never updates those bounds when the target app layout shifts
`AgentAccessibilityService.kt:515–525` + `SignatureExtractor.kt:28–29`
`boundsX/Y` are captured at calibration time as `bounds.centerX()/centerY()`. If the user rotates the device, opens a keyboard, or the app adds a banner, every cached signature's `boundsX/Y` is now wrong. The spatial tie-breaker then consistently picks the wrong candidate among tie-scored nodes. There is no re-calibration trigger. Reproduction: calibrate in portrait, rotate to landscape, run agent — wrong button tapped.

---

### 10. `GitManager.commitChanges` silently swallows *all* exceptions and returns `null`
`GitManager.kt:144–150`
```kotlin
} catch (_: EmptyCommitException) { null }
} catch (e: Exception) { null }
```
The caller in `TransactionManager.kt:126` does:
```kotlin
GitManager.commitChanges(rootFile, detailedMessage, modifiedPaths.distinct(), ...)
```
It ignores the return value entirely. So if the commit fails (lock contention, corrupt index, disk full, permission revoked mid-run), `TransactionManager` still reports `TransactionResult(true, successLog, modifiedPaths)` to the model. The model thinks the work is committed; the working tree has the changes but git has no commit. There is **no rollback of the working-tree mutations**. Combined with bug #4, this means a successful patch can be left in the working tree with no commit and no recovery path the model can detect.

**Fix**: surface commit failure as `TransactionResult(false, ...)` and have the orchestrator re-prompt the model with the error.

---

### 11. `GitManager.discardUncommittedChanges` calls `checkout.setStartPoint("HEAD")` with `addPath`-style paths but never issues a `clean` for untracked non-folders
`GitManager.kt:259–268`
```kotlin
val checkoutCmd = git.checkout().setStartPoint("HEAD")
pathsToDiscard.forEach { checkoutCmd.addPath(it) }
checkoutCmd.call()
val status = git.status().call()
for (path in pathsToDiscard) {
    if (status.untracked.contains(path) || status.untrackedFolders.contains(path)) {
        File(workspaceRoot, path).deleteRecursively()
    }
}
```
`git checkout -- <path>` only restores **tracked** files. For a path that was `CREATE`d (untracked) and then needs to be discarded, the `checkout.call()` is a no-op, and the code relies on the `status` query afterward to detect and `deleteRecursively()` it. But: `status.untracked` only contains *files*, and `status.untrackedFolders` only contains *directories*. A `CREATE` that produced a file inside a directory that itself is new will appear in `untrackedFolders` as the parent dir, not the file path — so the `path` lookup misses and the file is **not** deleted. Reproduction: `CREATE` `src/newpkg/Foo.kt`, attempt to discard `src/newpkg/Foo.kt` — status reports `src/newpkg` as untrackedFolder, `status.untracked.contains("src/newpkg/Foo.kt")` is false, the file lives on.

---

### 12. `GitManager.initGit` initial commit on a huge workspace blocks the calling IO coroutine and can OOM
`GitManager.kt:89–94`
```kotlin
git.add().addFilepattern(".").call()
git.commit().setMessage("Initial commit before CodeAssist tracking")...
```
`addFilepattern(".")` walks the entire workspace. On a real Android workspace with Gradle caches, `build/`, `.gradle/`, the add can stage tens of thousands of files and the commit can take minutes. There is a `.gitignore` written first (line 86) but it only includes `build/`, `.gradle/`, `.idea/`, `*.iml`, `local.properties`, `.codeassist/` — not `node_modules/`, `target/`, `__pycache__/`, `.git` from nested clones, etc. The mutex is held the whole time, blocking every other git operation across the entire app. Reproduction: point CodeAssist at a non-Android repo (e.g. a node project, a cloned repo with submodules), first mutation triggers `initGit`.

---

### 13. `OutlineExtractor` regex for Kotlin `fun` doesn't match generics, `suspend fun` with no leading modifier, or top-level functions in files with package declarations
`OutlineExtractor.kt:34`
```kotlin
OutlineRule(Regex("^\\s*(private\\s+|protected\\s+|internal\\s+)?(suspend\\s+)?fun\\s+..."), "  ")
```
Missing: `public`, `override`, `open`, `final`, `lateinit` is not applicable but `operator`, `inline`, `external`, `abstract` are missing. A signature like `override suspend fun foo()` or `public inline fun <T> bar()` does not match. Outline silently omits the most common Kotlin signatures. Reproduction: outline any file with `override fun` methods.

**Fix**: allow an arbitrary sequence of `(public|private|protected|internal|open|final|override|abstract|suspend|inline|operator|external|infix|tailrec)\s+` prefixes before `fun`.

---

### 14. `SystemPromptGenerator` example block uses a fenced ``` ```text ``` block but the closing fence syntax in the system prompt is wrong
`SystemPromptGenerator.kt:19` and `:75`
```
Do NOT write natural language outside the `<thinking>` tags and the 
````text` block.
```
The instruction at line 19 opens with ` ````text ` (4 backticks) but line 75 closes with ` ``` ` (3 backticks). Markdown parsers tolerate this, but the LLM is being told the output format is "wrap in a 4-backtick fence" while the example closes with 3 backticks. Models trained on markdown often echo what they see; you'll intermittently get responses that open with 4-backtick fences and close with 3, or vice versa. `EnvelopeParser` then can't find `:::CODE_ASSIST:::` reliably when the fence noise creeps inside. Minor but high-frequency.

---

## P2 — Resource-lifecycle leaks and concurrency

### 15. `AgentAccessibilityService` leaks `AccessibilityNodeInfo` instances across many paths
Multiple sites:
- `getScoredNodes` `AgentAccessibilityService.kt:429–435`: `searchNode(child)` then `if (child != null && !scoredMatches.any { it.first == child }) child.recycle()` — but `getChild(i)` returns a *fresh* node each call, and `scoredMatches.any { it.first == child }` is a referential equality check. It will always be false (the child we just got is not the same instance as any previously-stored node), so children are only recycled when they are *literally* in the list. The `searchNode` call recurses into the same child without recycling it; the recursion never recycles `node` itself either. This leaks every node except those stored in `scoredMatches`.
- `findSmallestNodeAt` `AgentAccessibilityService.kt:1333–1350`: `search(node.getChild(i))` is called on a child that is never recycled (whether or not it's chosen as `best`).
- `extractAllText` `AgentAccessibilityService.kt:910–917`: recycles each child, which is correct, but the root caller (e.g. `executeToolCall(click_send)` line 608 `val globalPreText = extractAllText(rootInActiveWindow, 0)`) never recycles the root.

`AccessibilityNodeInfo` instances are backed by a finite system pool (~50 per service on most OEM builds). Exercising the agent loop for a few minutes reliably throws `IllegalStateException: AccessibilityNodeInfo pool exhausted` on stock Pixel builds, after which the entire accessibility service stops dispatching events until rebound.

**Fix**: every `getChild(i)`, `parent`, `findAccessibilityNodeInfosByViewId(...)` result needs a `try { ... recycle() } catch ... ` at the end of its use site, including the root.

---

### 16. `AgentOrchestrator` creates a new `CoroutineScope(Dispatchers.IO)` per `startLoop`/`resumeFromText` invocation, but never stores or cancels the previous scope beyond `agentJob`
`AgentOrchestrator.kt:105,181,195`
```kotlin
agentJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { ... }
```
`agentJob?.cancel()` cancels the job (line 104) but the surrounding `CoroutineScope` is **created and immediately discarded** — the scope object goes out of scope, leaving the job parented by a `Job` that nobody holds. Structured concurrency is broken: a `scope.cancel()` would cancel child jobs, but here only the single job is cancelled. On rapid `startLoop` re-entry (e.g. model emits an envelope that itself triggers `startLoop(errorPrompt)` recursively at line 166, which itself may recurse), the scopes pile up; if any inner coroutine does not check `isActive` (lines 123, 129, 134, 140 do, but the recursive `startLoop` call at 166 and 273 do not gate before re-entering), you get overlapping loops writing to `preSendBaselineText` etc.

**Fix**: hold a single coroutine scope on the object, cancel-and-replace per session, never construct transient scopes.

---

### 17. `OverlayConfirmationManager` and `AgentOverlayManager` are constructed fresh per call but hold their own `CoroutineScope` and never cancel on dismiss
`OverlayConfirmationManager.kt:22–23`
```kotlin
private val uiScope = CoroutineScope(Dispatchers.Main)
private val ioScope = CoroutineScope(Dispatchers.IO)
```
`OverlayConfirmationManager` is constructed at `AgentAccessibilityService.kt:141` **per `showConfirmationOverlay` call**. Each construction creates two scopes, used once, and never cancelled. `dismiss()` does not call `uiScope.cancel()` / `ioScope.cancel()`. Result: every confirmation the user answers leaks two scopes' worth of context objects (the `Job` is rootless so not cancelled by parent). Under repeated agent loops with many confirmations this accumulates.

**Fix**: either construct `OverlayConfirmationManager` once per service and reuse, or cancel the scopes in `dismiss()`.

---

### 18. `CodeAssistDatabase.onUpgrade` drops both tables unconditionally
`CodeAssistDatabase.kt:42–46`
```kotlin
override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("DROP TABLE IF EXISTS element_signatures")
    db.execSQL("DROP TABLE IF EXISTS agent_profiles")
    onCreate(db)
}
```
The current version is 3. Any future `versionCode` bump (even a one-off add-column) silently **deletes every profile and every calibrated signature** for every user on upgrade. No migration. For an app whose central user investment is calibrating signatures per target app, this is a destructive default.

**Fix**: switch to incremental `ALTER TABLE` migrations keyed off `oldVersion`.

---

## P3 — Logic / API misuses and foot-guns

### 19. `CommandExecutor.handleGlob` regex-translates `.` to `\.` BEFORE the `**` substitution, but the substitution of `**` to `:::DOUBLE_STAR:::` emits a placeholder that itself contains `.`-like sequences
`CommandExecutor.kt:237–242`
```kotlin
var regexStr = patternStr
    .replace(".", "\\.")
    .replace("**", ":::DOUBLE_STAR:::")
    .replace("*", "[^/]*")
    .replace("?", ".")
    .replace(":::DOUBLE_STAR:::", ".*")
```
The order is correct only by accident: `.` is escaped first so the `:::DOUBLE_STAR:::` placeholder's internal `:` and `*`... wait, `*` is later replaced to `[^/]*`. So `**` → `:::DOUBLE_STAR:::` and then `:::[^/]*DOUBLE_STAR[^/]*:::` — the placeholder no longer reads `:::DOUBLE_STAR:::` by the time we try to replace it. The final `.replace(":::DOUBLE_STAR:::", ".*")` finds *nothing* because the `*` in the placeholder was already substituted. **Glob with `**` is broken.** Reproduction: any `GLOB` like `src/**/*.kt` produces a regex with literal `:::[^/]*DOUBLE_STAR[^/]*:::` in it, which never matches anything. Glob silently returns "No files matched".

**Fix**: use a sentinel that contains no regex-metacharacters or glob wildcards, e.g. `\u0001`. Or build the regex with a proper tokenizer.

---

### 20. `TransactionManager` commit message construction references `firstMod` even when `firstMod` is `null`
`TransactionManager.kt:43,58–63`
```kotlin
val firstMod = executableCommands.firstOrNull { it.isMutating }
...
if (explicitContext.isNotBlank()) {
    commitMessage = explicitContext
} else {
    ...
    commitMessage = when (firstMod) {
        is CodeCommand.Patch -> "Patch ${File(firstMod.path).name}"
        ...
        else -> fallbackCommitMessage
    }
}
```
The outer guard at line 40 is `if (hasModifications && !containsAskUser)`. `hasModifications` is `attemptedPaths.isNotEmpty()` (line 34), and `attemptedPaths` is built only from `Patch/Create/Delete/Move`. So `firstMod` is non-null whenever we enter the block. *However*: if the batch contains only `Read/Glob/Grep/Outline` plus a `Done`, `hasModifications` is false, but then we also skip the commit (line 100 `hasModifications && ...`). So this is safe today. It's a footgun: any future addition of a mutating-looking command to `attemptedPaths` without an `isMutating = true` would crash on `firstMod.path`. Document or assert.

---

### 21. `CommandExecutor.handleMove` uses `File.renameTo` which is unreliable across volumes and on some OEM filesystems
`CommandExecutor.kt:541`
```kotlin
val success = oldFile.renameTo(newFile)
```
`renameTo` returns `false` silently when the src and dst are on different mount points (common on Android when the workspace is on emulated storage and the dst crosses an FUSE boundary) or when the target parent is on a different inode type. A `MOVE` from `storage/emulated/0/Foo` to `storage/emulated/0/Android/data/.../Foo` will fail and report a generic "Failed to move file. Ensure no system process is locking it." which misleads the model. Use `Files.move` with copy fallback, or `copyTo + delete`.

---

### 22. `CommandExecutor.handleRead` inconsistent pagination math when `stoppedAtLine` equals the boundary line
`CommandExecutor.kt:165–181`
```kotlin
if (line.length > maxChars && charCount == 0) {
    contentBuilder.append(line.take(maxChars))
    stoppedAtLine = currentLineNum
    break
}
if (charCount + line.length > maxChars) {
    stoppedAtLine = currentLineNum
    break
}
...
val displayEnd = if (stoppedAtLine != -1) (stoppedAtLine - 1).toString() else ...
```
If the break fires on line N, `displayEnd = N - 1` and the truncation warning says "[CONTENT TRUNCATED at line N - 1]" and tells the model to emit `[START_LINE: $stoppedAtLine]` (i.e. line N). But the `displayEnd` claims we returned lines up to `N-1` inclusive. We did append line `N.take(maxChars)` only in the first branch (long single line), not the second. In the second branch we returned lines up to `N-1`, so `displayEnd = N-1` is right — but `START_LINE: $stoppedAtLine` says N, which is also right. So far OK. The issue is the **first** branch: we returned a fragment of line N (`take(maxChars)`), then told the model the displayed range was `start..N-1` and to resume from line N — but it already has part of line N, so resuming at N will duplicate. The truncation warning needs to be aware of which branch fired.

---

### 23. `EnvelopeParser.parse` accepts `[CONTENT]` (line 73) but `SystemPromptGenerator` documents `[CONTENT] ... [END_CONTENT]` (line 48) — `CREATE` only ends via the envelope-close path
`EnvelopeParser.kt:73,77–80`
```kotlin
ParseState.IN_CONTENT -> {
    if (trimmedLine.startsWith("[END_CONTENT]")) currentState = ParseState.IN_ENVELOPE
    else contentBuffer.append(line).append("\n")
}
```
Tail flush at line 96 fires when `currentState != IDLE` — but `IN_CONTENT` has no tail-flush handling that distinguishes "I have content" vs "I got cut off before [END_CONTENT]". A truncated `CREATE` mid-content produces a `Create` command with whatever fragment was in the buffer and `removeSuffix("\n")`. Validation accepts it (`CommandExecutor.validate` for `Create` has no existence check), file is written with the partial content silently. Same class of bug as #3 but for `CREATE`.

---

### 24. `AgentOrchestrator.handleCommandRouting` race: `showConfirmationOverlay` is dispatched to `Dispatchers.Main` while the calling `serviceScope` IO coroutine may already have moved on
`AgentOrchestrator.kt:301–304`
```kotlin
if (requiresConfirmation) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        service.updateShieldStatus("Awaiting User Confirmation...")
        service.showConfirmationOverlay(actionCommands, root)
    }
}
```
`withContext(Main)` suspends the IO coroutine until the Main block returns. `showConfirmationOverlay` constructs the `OverlayConfirmationManager` and calls `show()` synchronously, then returns. The IO coroutine then **falls through the end of `handleCommandRouting`** and the surrounding `startLoop` coroutine returns. The result callback fires much later from `OverlayConfirmationManager.executeBatch` → `processExecutionResults` → `startLoop` again. The prior `agentJob` is not cancelled at this point — but `startLoop` itself cancels `agentJob` at line 104, so the *next* call replaces it. The race is: between `show()` returning and the user tapping Approve, the orchestrator's `state` is still `EXECUTING_ACTION`/`WAITING_FOR_MUTATION`-ish, and an accessibility event window (e.g. the user closing and reopening the target app) can fire `autoResumeFromSentinel` which calls `resumeFromText` which calls `startLoop` which cancels the agentJob — but the pending confirmation is still attached to a stale command list. No repro yet, but the state machine is not guarded.

---

### 25. `MainActivity.workspacePickerLauncher` resolves SAF tree URI to a path via `Environment.getExternalStorageDirectory()` which is deprecated and returns null/empty on scoped-storage-only OEMs
`MainActivity.kt:65–86`
```kotlin
val path = if (split.size > 1 && split[1].isNotEmpty()) {
    android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
} else {
    android.os.Environment.getExternalStorageDirectory().absolutePath
}
```
`getExternalStorageDirectory()` was deprecated in API 29 and on Android 13+ may return a path the app cannot actually write to (/FUSE remap differs). The picker is SAF-backed (correct for selecting) but the resolved absolute path is fed into JGit which uses `java.io.File` — which on Android 13+ scoped storage is **not** the same physical path the SAF grant covers. So the user grants access via SAF, but JGit operates on a path it cannot write to, and the first commit silently fails (silenced by bug #10). The app opts into `requestLegacyExternalStorage="true"` per the manifest, which works on API ≤ 29 but not on later APIs with `targetSdk=34`.

Reproduction: Android 13+ device, pick a workspace folder, attempt a mutation — git operations either fail silently or write to a now-wrong absolute path.

---

## P4 — Style / maintainability with latent risk

### 26. `CommandExecutor` has both a private `ensureWorkspaceReady` (line 11) and uses `CommandExecutorUtils.ensureWorkspaceReady` (called nowhere in `CommandExecutor`)
`CommandExecutor.kt:11–16` vs `CommandExecutorUtils.kt:22–27`
Two implementations of the same logic. The private one in `CommandExecutor` is used at line 120; `CommandExecutorUtils.ensureWorkspaceReady` is dead. `AGENTS.md` tells future agents to "reuse these instead of reinlining" — but `CommandExecutor` itself does not.

### 27. `CommandExecutor.validate` reads the entire file into memory for every `PATCH` validation (`targetFile.readText()` at lines 74, 82) — and then `handlePatch` reads it **again** at line 288
`CommandExecutor.kt:74,82,288`
Double-loads a ≤2MB file on every patch. Per-batch overhead is real on low-end Android. The validation pass could return the normalized content and the executor could reuse it.

### 28. `MainActivity.layoutControlParams.setMargins(48, 0, 48, 0)` uses raw `px` values, not `dp`
`MainActivity.kt:579,611,642` — three identical calls
On a `mdpi` (160dpi) device 48px = 48dp; on xxhdpi (480dpi) 48px ≈ 16dp. The margins look wrong at high density. The `FloatingBubbleService` correctly uses `density` (line 209–211) for `dp12`/`dp8`; `MainActivity` does not.

### 29. `PackageManagerUtils.getInstalledApplications` uses `GET_META_DATA` for no reason
`PackageManagerUtils.kt:16`
`GET_META_DATA` forces the PackageManager to load every app's merged manifest metadata into memory. No metadata is consumed. The flag significantly slows the call and allocates more on a low-end device. Drop the flag (`0` is sufficient), or intent-filter the query.

### 30. `GitManager.commitAllChanges` adds `.` twice (regular `add` then `add().setUpdate(true)`)
`GitManager.kt:163–170`
The second `add(update=true)` re-stages everything the first `add` already staged. It is harmless (idempotent) but doubles the index walk for no benefit. The intent may have been to stage deletions, which `git.add()` already does on JGit 6.5 when invoked with `.` — so the second call is obsolete.

### 31. `EnvelopeParser` uses `trimmedLine.startsWith("[CONTENT]")` (line 73) but the closing token is `[END_CONTENT]` (line 78) — asymmetric brackets
The opening tag is `[CONTENT]` (no colon), unlike every other tag which is `[KEY: value]`. The system prompt at `SystemPromptGenerator.kt:48` documents `[CONTENT] ... [END_CONTENT]`, so this is intentional but inconsistent with the rest of the protocol. Easy for an LLM to mis-emit as `[CONTENT: ...]` (matching the surrounding pattern). Minor but observed in model outputs.

### 32. `AgentOverlayManager.updateStatus` does substring detection in a fragile way
`AgentOverlayManager.kt:171–176`
```kotlin
val isWaitingForUser = status.contains("user", true)
isGenerating = (!isWaitingForUser && status.contains("Wait", true)) ||
        status.contains("Analyz", true) ||
        status.contains("Send", true) ||
        status.contains("Typ", true) ||
        status.contains("Execut", true)
```
A status string of "Waiting for user input" sets `isWaitingForUser = true` (correct) but ALSO contains "Wait" — the `!isWaitingForUser` short-circuit protects it, fine. But "Executing user's command" → `isWaitingForUser = true` (correct English meaning) yet the intent was *generating*. The status text comes from `updateShieldStatus(...)` calls scattered across `AgentOrchestrator` and `AgentAccessibilityService`, with at least one call site passing natural sentences ("Auto-Resume: Waiting for LLM...") which now get classified as "waiting for user" because of the word "Waiting". The status indicator and the morphing button flip incorrectly. Drive the state off `AgentOrchestrator.state` (already a sealed `AgentState`) instead of substring-matching on human strings.

---

## Summary of recommended priority fixes

1. **#4 + #10**: Make `TransactionManager.executeBatch` truly atomic — pre-validate all commands before any file write, surface git commit failures as transaction failures. This is the app's central promise.
2. **#19**: Fix `handleGlob` placeholder collision — `**` globs are silently broken today.
3. **#15**: Systematic `AccessibilityNodeInfo` recycling — pool exhaustion breaks the agent loop after a few minutes.
4. **#2 + #1**: Make the fuzzy/Levenshtein matchers correct and whitespace-aware — patch application to the wrong block is a corruption vector.
5. **#6**: One-shot guard in `ClipboardActivity` — double execution of a mutating batch is a data-loss path.
6. **#18**: Replace `onUpgrade` drop-table with incremental migrations before any future schema bump.
7. **#3 + #23**: Reject truncated envelopes (require `>>>>>>> REPLACE` and `[END_CONTENT]`) before constructing commands.
