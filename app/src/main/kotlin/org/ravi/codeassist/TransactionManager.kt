package org.ravi.codeassist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object TransactionManager {
    data class TransactionResult(
        val success: Boolean,
        val logs: String,
        val modifiedPaths: List<String> = emptyList()
    )

    private fun commandPathSummary(command: CodeCommand): String = when (command) {
        is CodeCommand.Patch -> command.path
        is CodeCommand.Create -> command.path
        is CodeCommand.Delete -> command.path
        is CodeCommand.Move -> "${command.oldPath} -> ${command.newPath}"
        else -> "-"
    }

    /**
     * Bounded per-batch observation: post-write verification of every
     * successfully touched file (current line counts + top-level symbols via
     * outline) plus the enclosing repo state, so the model can structurally
     * confirm its own edits instead of only reading human-readable logs.
     */
    private suspend fun workspaceSnapshot(rootFile: File, paths: List<String>): String {
        val out = StringBuilder()
        val distinctPaths = paths.distinct().filter { it != "-" && it.isNotBlank() }
        val verification = org.ravi.codeassist.utils.WorkspaceScope.outlineFor(rootFile, distinctPaths)
        if (verification.isNotBlank()) out.appendLine(verification)
        GitManager.repositorySnapshot(rootFile)?.let { out.appendLine(it) }
        return out.toString().trimEnd()
    }

    suspend fun executeBatch(
        context: Context,
        commands: List<CodeCommand>,
        workspaceRoot: String,
        fallbackCommitMessage: String = "Automated CodeAssist Execution"
    ): TransactionResult = withContext(Dispatchers.IO) {
        val sharedPref = context.getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val rootFile = File(workspaceRoot)
        val executableCommands = commands.filter { it !is CodeCommand.Done && it !is CodeCommand.Plan }
        val attemptedPaths = executableCommands.flatMap { 
            when (it) {
                is CodeCommand.Patch -> listOf(it.path)
                is CodeCommand.Create -> listOf(it.path)
                is CodeCommand.Delete -> listOf(it.path)
                is CodeCommand.Move -> listOf(it.oldPath, it.newPath)
                else -> emptyList()
            }
        }.distinct()
        val hasModifications = attemptedPaths.isNotEmpty()

        val authorName = sharedPref.getString("GIT_AUTHOR_NAME", "CodeAssist AI") ?: "CodeAssist AI"
        val authorEmail = sharedPref.getString("GIT_AUTHOR_EMAIL", "ai@codeassist.local") ?: "ai@codeassist.local"

        var commitMessage = fallbackCommitMessage
        if (hasModifications) {
            GitManager.initGit(rootFile, authorName, authorEmail)
            
            val firstMod = executableCommands.firstOrNull { it.isMutating }
            val explicitContext = when (firstMod) {
                is CodeCommand.Patch -> firstMod.context
                is CodeCommand.Create -> firstMod.context
                is CodeCommand.Delete -> firstMod.context
                is CodeCommand.Move -> firstMod.context
                else -> ""
            }

            if (explicitContext.isNotBlank()) {
                commitMessage = explicitContext
            } else {
                commands.filterIsInstance<CodeCommand.Done>().firstOrNull()?.let {
                    commitMessage = it.message
                } ?: run {
                    commitMessage = when (firstMod) {
                        is CodeCommand.Patch -> "Patch ${File(firstMod.path).name}"
                        is CodeCommand.Create -> "Create ${File(firstMod.path).name}"
                        is CodeCommand.Delete -> "Delete ${File(firstMod.path).name}"
                        is CodeCommand.Move -> "Move to ${File(firstMod.newPath).name}"
                        else -> fallbackCommitMessage
                    }
                }
            }
        }

        var successCount = 0
        val finalClipboardFeedback = java.lang.StringBuilder()

        // One-shot session checkpoint: snapshotted before the first mutating
        // batch so "Undo Session" can hard-reset the whole session away.
        if (hasModifications && org.ravi.codeassist.agent.AgentOrchestrator.sessionCheckpointRef == null) {
            org.ravi.codeassist.agent.AgentOrchestrator.sessionCheckpointRef =
                GitManager.createCheckpoint(rootFile, "session-start")
            GitManager.tagCurrentHead(rootFile, "codeassist-session-start")
        }

        val policyRules = org.ravi.codeassist.agent.AgentPolicy.rulesFor(sharedPref)
        val policyBlocked = executableCommands.filter {
            org.ravi.codeassist.agent.AgentPolicy.effectFor(policyRules, it) == org.ravi.codeassist.agent.AgentPolicy.Effect.DENY
        }
        val runnableCommands = executableCommands - policyBlocked.toSet()

        val modifiedPaths = mutableListOf<String>()
        val fileStatuses = mutableListOf<String>()
        val executionFailures = mutableListOf<Pair<CodeCommand, String>>()

        policyBlocked.forEach { command ->
            val detail = org.ravi.codeassist.agent.AgentPolicy.targetPath(command)
                ?.let { "on $it" } ?: ""
            executionFailures.add(Pair(command, "Policy: blocked by DENY rule${if (detail.isNotEmpty()) " $detail" else ""}."))
            fileStatuses.add("[BLOCKED] ${command.javaClass.simpleName} ${commandPathSummary(command)} | Policy DENY")
            finalClipboardFeedback.append("[${command.javaClass.simpleName} BLOCKED] Policy DENY$detail.\n")
        }

        for (command in runnableCommands) {
            val validationError = CommandExecutor.validate(command, workspaceRoot)
            if (validationError != null) {
                executionFailures.add(Pair(command, "Validation: $validationError"))
                fileStatuses.add("[FAILED] ${command.javaClass.simpleName} ${commandPathSummary(command)} | Validation: $validationError")
                finalClipboardFeedback.append("[${command.javaClass.simpleName} FAILED] Validation: $validationError\n")
                continue
            }

            val result = CommandExecutor.execute(command, workspaceRoot)
            if (result.success) {
                successCount++
                fileStatuses.add("[SUCCESS] ${command.javaClass.simpleName} ${commandPathSummary(command)}")
                if (result.outputToClipboard != null) {
                    finalClipboardFeedback.append(result.outputToClipboard).append("\n")
                } else {
                    finalClipboardFeedback.append("[${command.javaClass.simpleName}] ").append(result.logMsg).append("\n")
                }
                when (command) {
                    is CodeCommand.Patch -> modifiedPaths.add(command.path)
                    is CodeCommand.Create -> modifiedPaths.add(command.path)
                    is CodeCommand.Delete -> modifiedPaths.add(command.path)
                    is CodeCommand.Move -> {
                        modifiedPaths.add(command.oldPath)
                        modifiedPaths.add(command.newPath)
                    }
                    else -> {}
                }
            } else {
                executionFailures.add(Pair(command, result.logMsg))
                fileStatuses.add("[FAILED] ${command.javaClass.simpleName} ${commandPathSummary(command)} | ${result.logMsg}")
                finalClipboardFeedback.append("[${command.javaClass.simpleName} FAILED] ").append(result.logMsg).append("\n")
                continue
            }
        }

        if (hasModifications && successCount > 0) {
            val detailedMessage = buildString {
                appendLine(commitMessage)
                appendLine("\nOperations:")
                runnableCommands.forEach { cmd ->
                    when (cmd) {
                        is CodeCommand.Patch -> {
                            appendLine("- Patched: ${cmd.path}")
                            if (cmd.context.isNotBlank()) appendLine("  Rationale: ${cmd.context}")
                        }
                        is CodeCommand.Create -> {
                            appendLine("- Created: ${cmd.path}")
                            if (cmd.context.isNotBlank()) appendLine("  Rationale: ${cmd.context}")
                        }
                        is CodeCommand.Delete -> {
                            appendLine("- Deleted: ${cmd.path}")
                            if (cmd.context.isNotBlank()) appendLine("  Rationale: ${cmd.context}")
                        }
                        is CodeCommand.Move -> {
                            appendLine("- Moved: ${cmd.oldPath} -> ${cmd.newPath}")
                            if (cmd.context.isNotBlank()) appendLine("  Rationale: ${cmd.context}")
                        }
                        else -> {}
                    }
                }
            }
            GitManager.commitChanges(rootFile, detailedMessage.trim(), modifiedPaths.distinct(), authorName, authorEmail)
            GitManager.tagCurrentHead(rootFile, "codeassist-round-${org.ravi.codeassist.agent.AgentOrchestrator.currentRound()}")
        }

        commands.filterIsInstance<CodeCommand.Done>().firstOrNull()?.let {
            finalClipboardFeedback.append("\nHALT_DONE: ${it.message}")
        }

        if (executionFailures.isNotEmpty()) {
            val snapshot = workspaceSnapshot(rootFile, modifiedPaths)
            val errorPayload = buildString {
                appendLine(":::CODE_ASSIST_TRANSACTION_ERROR:::")
                appendLine("STATUS: PARTIAL_BATCH_FAILURE")
                appendLine("WORKSPACE_ROOT: $workspaceRoot")
                if (successCount > 0) {
                    appendLine("\n--- SUCCESSFUL OPERATIONS (Already Committed) ---")
                    modifiedPaths.forEach { appendLine("  - $it") }
                    appendLine("INSTRUCTION: Do NOT regenerate the successful commands above.")
                }
                appendLine("\n--- FAILED OPERATIONS ---")
                executionFailures.forEach { (cmd, msg) ->
                    appendLine("  - [${cmd.javaClass.simpleName}]: $msg")
                }
                appendLine("\nBATCH SUMMARY: ${executableCommands.size} commands total ($successCount succeeded, ${executableCommands.size - successCount} failed), ${modifiedPaths.distinct().size} file(s) touched.")
                if (snapshot.isNotBlank()) {
                    appendLine("\n--- STATE SNAPSHOT (after partially-applied batch) ---")
                    appendLine(snapshot)
                }
                appendLine("\nINSTRUCTION: Review the failed operations above, correct the parameters, and re-emit ONLY the failed commands.")
                appendLine(":::END_TRANSACTION_ERROR:::")
            }
            org.ravi.codeassist.database.ExecutionHistory.record(context, false, executableCommands.size, errorPayload, workspaceRoot)
            return@withContext TransactionResult(false, errorPayload, modifiedPaths)
        }

        val successLog = if (finalClipboardFeedback.isNotEmpty()) {
            val snapshot = workspaceSnapshot(rootFile, modifiedPaths)
            buildString {
                appendLine(finalClipboardFeedback.toString().trim())
                appendLine()
                appendLine("--- FILE STATUS ---")
                if (fileStatuses.isEmpty()) {
                    appendLine("  (no file operations)")
                } else {
                    fileStatuses.forEach { appendLine("  $it") }
                }
                appendLine()
                appendLine("--- BATCH SUMMARY ---")
                appendLine("  Commands executed: ${executableCommands.size} ($successCount succeeded, ${executableCommands.size - successCount} failed)")
                appendLine("  Files touched: ${modifiedPaths.distinct().size}")
                if (snapshot.isNotBlank()) {
                    appendLine()
                    appendLine("--- STATE SNAPSHOT ---")
                    appendLine("  " + snapshot.replace("\n", "\n  "))
                }
            }.trim()
        } else {
            if (executableCommands.isEmpty()) {
                "No file mutations in this batch (plan/state update only)."
            } else {
                "Successfully executed $successCount commands and committed changes."
            }
        }

        org.ravi.codeassist.database.ExecutionHistory.record(context, true, executableCommands.size, successLog, workspaceRoot)
        return@withContext TransactionResult(true, successLog, modifiedPaths)
    }
}