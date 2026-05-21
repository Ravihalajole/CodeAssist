package org.ravi.codeassist

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.File

object GitManager {
    fun isGitInitialized(workspaceRoot: File): Boolean {
        return File(workspaceRoot, ".git").exists()
    }

    fun initGit(workspaceRoot: File) {
        try {
            if (!isGitInitialized(workspaceRoot)) {
                Git.init().setDirectory(workspaceRoot).call().use { git ->
                    // Create a robust default .gitignore to avoid committing build artifacts
                    val gitignore = File(workspaceRoot, ".gitignore")
                    if (!gitignore.exists()) {
                        gitignore.writeText("build/\n.gradle/\n.idea/\n*.iml\nlocal.properties\n.codeassist/\n")
                    }

                    git.add().addFilepattern(".").call()
                    git.commit()
                        .setMessage("Initial commit before CodeAssist")
                        .setAuthor("CodeAssist AI", "ai@codeassist.local")
                        .setCommitter("CodeAssist AI", "ai@codeassist.local")
                        .call()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git init failed", e)
        }
    }

    fun commitChanges(workspaceRoot: File, message: String): String? {
        try {
            Git.open(workspaceRoot).use { git ->
                // Stage new and modified files
                git.add().addFilepattern(".").call()
                
                // Stage deleted files
                git.add().setUpdate(true).addFilepattern(".").call()
                
                val status = git.status().call()
                
                if (!status.isClean) {
                    val commit = git.commit()
                        .setMessage(message)
                        .setAuthor("CodeAssist AI", "ai@codeassist.local")
                        .setCommitter("CodeAssist AI", "ai@codeassist.local")
                        .call()
                    return commit.name
                }
                return null
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git commit failed", e)
            return null
        }
    }

    fun revertCommit(workspaceRoot: File, commitHash: String): Boolean {
        try {
            Git.open(workspaceRoot).use { git ->
                val commitId = git.repository.resolve(commitHash) ?: return false
                git.revert().include(commitId).call()
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git revert failed", e)
            return false
        }
    }

    fun discardUncommittedChanges(workspaceRoot: File): Boolean {
        try {
            Git.open(workspaceRoot).use { git ->
                // Hard reset to HEAD (restores deleted/modified files)
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
                // Clean untracked files and directories (removes newly created files/folders)
                git.clean().setCleanDirectories(true).setForce(true).call()
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git discard failed", e)
            return false
        }
    }
}