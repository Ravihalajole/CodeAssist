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

    fun resetHardToPrevious(workspaceRoot: File): Boolean {
        try {
            Git.open(workspaceRoot).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git reset failed", e)
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

    fun getStatusString(workspaceRoot: File): String {
        try {
            if (!isGitInitialized(workspaceRoot)) return "Git is not initialized in this workspace."
            
            Git.open(workspaceRoot).use { git ->
                val status = git.status().call()
                if (status.isClean) return "Working directory is clean.\nNothing to commit."
                
                val sb = StringBuilder()
                if (status.added.isNotEmpty()) sb.append("🚀 Added:\n").append(status.added.joinToString("\n")).append("\n\n")
                if (status.changed.isNotEmpty()) sb.append("📝 Changed (Staged):\n").append(status.changed.joinToString("\n")).append("\n\n")
                if (status.modified.isNotEmpty()) sb.append("✍️ Modified:\n").append(status.modified.joinToString("\n")).append("\n\n")
                if (status.removed.isNotEmpty() || status.missing.isNotEmpty()) {
                    sb.append("🗑️ Removed/Missing:\n")
                    status.removed.forEach { sb.append(it).append("\n") }
                    status.missing.forEach { sb.append(it).append("\n") }
                    sb.append("\n")
                }
                if (status.untracked.isNotEmpty()) sb.append("❓ Untracked:\n").append(status.untracked.joinToString("\n")).append("\n\n")
                
                return sb.toString().trim()
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git status failed", e)
            return "Error getting status: ${e.message}"
        }
    }

    fun stashChanges(workspaceRoot: File): Boolean {
        try {
            Git.open(workspaceRoot).use { git ->
                git.stashCreate().setIncludeUntracked(true).setWorkingDirectoryMessage("Manual CodeAssist Stash").call()
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git stash failed", e)
            return false
        }
    }

    fun popStash(workspaceRoot: File): Boolean {
        try {
            Git.open(workspaceRoot).use { git ->
                // apply and drop
                git.stashApply().call()
                git.stashDrop().call()
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git stash pop failed", e)
            return false
        }
    }

    data class CommitInfo(val hash: String, val message: String, val author: String, val time: Long)

    fun getCommitHistory(workspaceRoot: File): List<CommitInfo> {
        val commits = mutableListOf<CommitInfo>()
        try {
            if (!isGitInitialized(workspaceRoot)) return commits
            Git.open(workspaceRoot).use { git ->
                val logs = git.log().call()
                for (rev in logs) {
                    commits.add(
                        CommitInfo(
                            hash = rev.name,
                            message = rev.fullMessage,
                            author = rev.authorIdent.name,
                            time = rev.commitTime.toLong() * 1000L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CodeAssist", "Git log failed", e)
        }
        return commits
    }
}