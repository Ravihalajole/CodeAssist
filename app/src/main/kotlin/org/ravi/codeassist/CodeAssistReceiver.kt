package org.ravi.codeassist

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CodeAssistReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "org.ravi.codeassist.ACTION_QUICK_APPROVE") {
            val workspaceRoot = intent.getStringExtra("WORKSPACE_ROOT") ?: return
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(2002)

            val cacheFile = File(context.cacheDir, "pending_envelope.txt")
            if (!cacheFile.exists()) return
            val payload = cacheFile.readText()

            val commands = EnvelopeParser.parse(payload)
            if (commands.isEmpty()) {
                Toast.makeText(context, "Quick Action Error: Unparsable content payload.", Toast.LENGTH_SHORT).show()
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                val rootFile = File(workspaceRoot)
                val executableCommands = commands.filter { it !is CodeCommand.CommitMessage }
                var commitMessage = "Automated Quick Action Execution"
                
                commands.filterIsInstance<CodeCommand.CommitMessage>().firstOrNull()?.let {
                    commitMessage = it.message
                }

                val sharedPref = context.getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
                val authorName = sharedPref.getString("GIT_AUTHOR_NAME", "CodeAssist AI") ?: "CodeAssist AI"
                val authorEmail = sharedPref.getString("GIT_AUTHOR_EMAIL", "ai@codeassist.local") ?: "ai@codeassist.local"
                
                GitManager.initGit(rootFile, authorName, authorEmail)

                var successCount = 0
                val modifiedPaths = mutableListOf<String>()

                for (command in executableCommands) {
                    val result = CommandExecutor.execute(command, workspaceRoot)
                    if (result.success) {
                        successCount++
                        when (command) {
                            is CodeCommand.PatchFile -> modifiedPaths.add(command.path)
                            is CodeCommand.CreateFile -> modifiedPaths.add(command.path)
                            is CodeCommand.DeleteFile -> modifiedPaths.add(command.path)
                            else -> {}
                        }
                    } else {
                        GitManager.discardUncommittedChanges(rootFile)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Quick Action Failed: ${result.logMsg}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }

                if (successCount > 0) {
                    GitManager.commitChanges(rootFile, commitMessage, modifiedPaths.distinct(), authorName, authorEmail)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Quick Actions applied: $successCount updates.", Toast.LENGTH_SHORT).show()
                    }
                    cacheFile.delete()
                }
            }
        }
    }
}