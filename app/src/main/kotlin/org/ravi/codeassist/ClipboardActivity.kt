package org.ravi.codeassist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ClipboardActivity : AppCompatActivity() {

    private var hasProcessed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasProcessed) {
            hasProcessed = true
            executeClipboardAction()
        }
    }

    private fun executeClipboardAction() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val textToParse = clipData.getItemAt(0).text?.toString()

            if (textToParse.isNullOrEmpty() || !textToParse.contains(":::CODE_ASSIST:::")) {
                Toast.makeText(this, "No valid CodeAssist envelope found.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            processClipboardPayload(textToParse, clipboard)
        } else {
            finish()
        }
    }

    private fun processClipboardPayload(payload: String, clipboard: ClipboardManager) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)

        if (workspaceRoot.isNullOrEmpty()) {
            Toast.makeText(this, "Error: Set workspace path first.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val commands = EnvelopeParser.parse(payload)
        
        if (commands.isEmpty()) {
            Toast.makeText(this, "Parsing error: No valid instructions.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val autoReadEnabled = sharedPref.getBoolean("AUTO_READ_ENABLED", false)
        val isReadOnlyBatch = commands.all { 
            it is CodeCommand.ReadFile || it is CodeCommand.GrepFile || it is CodeCommand.ListDir 
        }

        if (autoReadEnabled && isReadOnlyBatch) {
            // Bypass approval for read-only batches
            executeCommands(commands, workspaceRoot, clipboard)
        } else {
            // === HUMAN-IN-THE-LOOP SAFETY NET INTERCEPTOR ===
            val bottomSheet = ConfirmationBottomSheet(
                commands = commands,
                workspaceRoot = workspaceRoot,
                onApprove = {
                    // User approved the execution! Run the engine.
                    executeCommands(commands, workspaceRoot, clipboard)
                },
                onReject = {
                    // User rejected the changes. Abort safely.
                    Toast.makeText(this, "Execution Aborted.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            )
            
            bottomSheet.show(supportFragmentManager, "ConfirmationSheet")
        }
    }

    private fun executeCommands(commands: List<CodeCommand>, workspaceRoot: String, clipboard: ClipboardManager) {
        lifecycleScope.launch(Dispatchers.IO) {
            val finalClipboardFeedback = java.lang.StringBuilder()
            var successCount = 0
            var commitMessage = "Automated CodeAssist Execution"
            
            val rootFile = File(workspaceRoot)
            val executableCommands = commands.filter { it !is CodeCommand.CommitMessage }
            val hasModifications = executableCommands.any { it is CodeCommand.PatchFile || it is CodeCommand.CreateFile || it is CodeCommand.DeleteFile }

            // Only run baseline checking if we actually intend to write modifications
            if (hasModifications) {
                GitManager.initGit(rootFile)
                commands.filterIsInstance<CodeCommand.CommitMessage>().firstOrNull()?.let {
                    commitMessage = it.message
                }
            }

            for (command in executableCommands) {
                val result = CommandExecutor.execute(command, workspaceRoot)
                if (result.success) {
                    successCount++
                    result.outputToClipboard?.let {
                        finalClipboardFeedback.append(it).append("\n")
                    }
                } else {
                    if (hasModifications) {
                        GitManager.discardUncommittedChanges(rootFile)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ClipboardActivity, "Batch Failed, Rolled Back: ${result.logMsg}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@launch
                }
            }

            if (hasModifications && successCount > 0) {
                val modifiedPaths = mutableListOf<String>()
                val detailedMessage = buildString {
                    appendLine(commitMessage)
                    appendLine("\nOperations:")
                    executableCommands.forEach { cmd ->
                        when (cmd) {
                            is CodeCommand.PatchFile -> {
                                appendLine("- Patched: ${cmd.path}")
                                modifiedPaths.add(cmd.path)
                            }
                            is CodeCommand.CreateFile -> {
                                appendLine("- Created: ${cmd.path}")
                                modifiedPaths.add(cmd.path)
                            }
                            is CodeCommand.DeleteFile -> {
                                appendLine("- Deleted: ${cmd.path}")
                                modifiedPaths.add(cmd.path)
                            }
                            else -> {}
                        }
                    }
                }
                GitManager.commitChanges(rootFile, detailedMessage.trim(), modifiedPaths.distinct())
            }

            withContext(Dispatchers.Main) {
                // Write processing output back to clipboard for LLM evaluation loop
                if (finalClipboardFeedback.isNotEmpty()) {
                    val clip = ClipData.newPlainText("CodeAssist Result", finalClipboardFeedback.toString().trim())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@ClipboardActivity, "Executed $successCount ops. Copied to clipboard!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ClipboardActivity, "Executed $successCount ops successfully.", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

}
