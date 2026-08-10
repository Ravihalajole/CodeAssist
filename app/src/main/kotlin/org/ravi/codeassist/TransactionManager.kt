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

    suspend fun executeBatch(
        context: Context,
        commands: List<CodeCommand>,
        workspaceRoot: String,
        fallbackCommitMessage: String = "Automated CodeAssist Execution"
    ): TransactionResult = withContext(Dispatchers.IO) {
        val sharedPref = context.getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val rootFile = File(workspaceRoot)
        val executableCommands = commands.filter { it !is CodeCommand.Done && it !is CodeCommand.AskUser }
        val attemptedPaths = executableCommands.flatMap { 
            when (it) {
                is CodeCommand.Patch -> listOf(it.path)
                is CodeCommand.Create -> listOf(it.path)
                is CodeCommand.Delete -> listOf(it.path)
                is CodeCommand.Move -> listOf(it.oldPath, it.newPath)
                else -> emptyList()
            }
        }.distinct()
        val containsAskUser = commands.any { it is CodeCommand.AskUser }
        val hasModifications = attemptedPaths.isNotEmpty()

        val authorName = sharedPref.getString("GIT_AUTHOR_NAME", "CodeAssist AI") ?: "CodeAssist AI"
        val authorEmail = sharedPref.getString("GIT_AUTHOR_EMAIL", "ai@codeassist.local") ?: "ai@codeassist.local"

        var commitMessage = fallbackCommitMessage
        if (hasModifications && !containsAskUser) {
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
        val modifiedPaths = mutableListOf<String>()
        val fileStatuses = mutableListOf<String>()
        val executionFailures = mutableListOf<Pair<CodeCommand, String>>()

        for (command in executableCommands) {
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

        if (hasModifications && successCount > 0 && !containsAskUser) {
            val detailedMessage = buildString {
                appendLine(commitMessage)
                appendLine("\nOperations:")
                executableCommands.forEach { cmd ->
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
        }

        commands.filterIsInstance<CodeCommand.AskUser>().firstOrNull()?.let {
            finalClipboardFeedback.append("\nHALT_FOR_USER: ${it.message}")
        }
        commands.filterIsInstance<CodeCommand.Done>().firstOrNull()?.let {
            finalClipboardFeedback.append("\nHALT_DONE: ${it.message}")
        }

        if (executionFailures.isNotEmpty()) {
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
                appendLine("\nINSTRUCTION: Review the failed operations above, correct the parameters, and re-emit ONLY the failed commands.")
                appendLine(":::END_TRANSACTION_ERROR:::")
            }
            org.ravi.codeassist.database.ExecutionHistory.record(context, false, executableCommands.size, errorPayload, workspaceRoot)
            return@withContext TransactionResult(false, errorPayload, modifiedPaths)
        }

        val successLog = if (finalClipboardFeedback.isNotEmpty()) {
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
            }.trim()
        } else {
            "Successfully executed $successCount commands and committed changes."
        }

        org.ravi.codeassist.database.ExecutionHistory.record(context, true, executableCommands.size, successLog, workspaceRoot)
        return@withContext TransactionResult(true, successLog, modifiedPaths)
    }
}