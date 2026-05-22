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
        File(workspaceRoot, ".git/index.lock").apply { if (exists()) delete() }
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
        } catch (_: Exception) {}
    }

    suspend fun commitChanges(workspaceRoot: File, message: String): String? = gitMutex.withLock {
        var git: Git? = null
        try {
            cleanupStaleLocks(workspaceRoot)
            git = Git.open(workspaceRoot)
            
            // Force fully clear repository tracking pointers before taking action
            git.repository.refDatabase.refresh()
            org.eclipse.jgit.lib.RepositoryCache.clear()
            cleanupStaleLocks(workspaceRoot)
            
            // Consolidate staging tasks into a single atomic operational unit
            git.add().addFilepattern(".").setUpdate(false).call()
            
            // Instantly evaluate dirty state entries
            val status = git.status().call()
            if (!status.isClean || status.hasUncommittedChanges()) {
                cleanupStaleLocks(workspaceRoot)
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor("CodeAssist AI", "ai@codeassist.local")
                    .setCommitter("CodeAssist AI", "ai@codeassist.local")
                    .call()
                return commit.name
            }
            return null
        } catch (e: Exception) {
            // Log error internally by rethrowing or falling back cleanly
            return null
        } finally {
            // Force fully releasing file handles back to OS environment cleanly
            try {
                git?.repository?.close()
                git?.close()
            } catch (_: Exception) {}
            cleanupStaleLocks(workspaceRoot)
        }
    }

    suspend fun revertCommit(workspaceRoot: File, commitHash: String): Boolean = gitMutex.withLock {
        try {
            Git.open(workspaceRoot).use { git ->
                val commitId = git.repository.resolve(commitHash) ?: return false
                git.revert().include(commitId).call()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun resetHardToPrevious(workspaceRoot: File): Boolean = gitMutex.withLock {
        try {
            Git.open(workspaceRoot).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun discardUncommittedChanges(workspaceRoot: File): Boolean = gitMutex.withLock {
        try {
            Git.open(workspaceRoot).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
                git.clean().setCleanDirectories(true).setForce(true).call()
                true
            }
        } catch (_: Exception) {
            false
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
        } catch (_: Exception) {}
        return commits.sortedWith(compareByDescending<CommitInfo> { it.time }.thenByDescending { it.hash })
    }
}