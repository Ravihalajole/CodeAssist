package org.ravi.codeassist

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object GitManager {
    private val gitMutex = Mutex()

    fun isGitInitialized(workspaceRoot: File): Boolean {
        return File(workspaceRoot, ".git").exists()
    }

    private fun cleanupStaleLocks(workspaceRoot: File) {
        val indexLock = File(workspaceRoot, ".git/index.lock")
        if (indexLock.exists()) {
            indexLock.delete()
        }
    }

    suspend fun initGit(workspaceRoot: File) = gitMutex.withLock {
        try {
            if (!isGitInitialized(workspaceRoot)) {
                cleanupStaleLocks(workspaceRoot)
                Git.init().setDirectory(workspaceRoot).call().use { git ->
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
            // Safe fallback logging omitted for clean runtime production compliance
        }
    }

    suspend fun commitChanges(workspaceRoot: File, message: String): String? = gitMutex.withLock {
        try {
            cleanupStaleLocks(workspaceRoot)
            Git.open(workspaceRoot).use { git ->
                git.add().addFilepattern(".").call()
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
            return null
        }
    }

    suspend fun revertCommit(workspaceRoot: File, commitHash: String): Boolean = gitMutex.withLock {
        try {
            Git.open(workspaceRoot).use { git ->
                val commitId = git.repository.resolve(commitHash) ?: return false
                git.revert().include(commitId).call()
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }

    suspend fun resetHardToPrevious(workspaceRoot: File): Boolean = gitMutex.withLock {
        try {
            Git.open(workspaceRoot).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }

    suspend fun discardUncommittedChanges(workspaceRoot: File): Boolean = gitMutex.withLock {
        try {
            Git.open(workspaceRoot).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
                git.clean().setCleanDirectories(true).setForce(true).call()
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }

    suspend fun getStatusString(workspaceRoot: File): String = gitMutex.withLock {
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
            return "Error getting status: ${e.message}"
        }
    }

    suspend fun stashChanges(workspaceRoot: File): Boolean = gitMutex.withLock {
        try {
            Git.open(workspaceRoot).use { git ->
                val rev = git.stashCreate().setIncludeUntracked(true).setWorkingDirectoryMessage("Manual CodeAssist Stash").call()
                return rev != null
            }
        } catch (e: Exception) {
            return false
        }
    }

    suspend fun popStash(workspaceRoot: File): Boolean = gitMutex.withLock {
        try {
            Git.open(workspaceRoot).use { git ->
                val stashes = git.stashList().call()
                if (stashes.isNullOrEmpty()) return false
                
                git.stashApply().call()
                git.stashDrop().setStashRef(0).call()
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }

    data class CommitInfo(val hash: String, val message: String, val author: String, val time: Long)

    suspend fun getCommitHistory(workspaceRoot: File): List<CommitInfo> = gitMutex.withLock {
        val commits = mutableListOf<CommitInfo>()
        try {
            if (!isGitInitialized(workspaceRoot)) return commits
            
            Git.open(workspaceRoot).use { git ->
                org.eclipse.jgit.lib.RepositoryCache.clear()
                git.repository.refDatabase.refresh()
                
                val logs = git.log().all().call()
                for (rev in logs) {
                    val info = CommitInfo(
                        hash = rev.name,
                        message = rev.fullMessage,
                        author = rev.authorIdent.name,
                        time = rev.commitTime.toLong() * 1000L
                    )
                    commits.add(info)
                }
            }
        } catch (e: Exception) {
            // Unhandled context safety block
        }
        return commits.sortedWith(compareByDescending<CommitInfo> { it.time }.thenByDescending { it.hash })
    }
}