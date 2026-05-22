package org.ravi.codeassist

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.lib.RepositoryCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object GitManager {
    private val gitMutex = Mutex()
    private const val AUTHOR_NAME = "CodeAssist AI"
    private const val AUTHOR_EMAIL = "ai@codeassist.local"

    /**
     * Checks if a valid Git repository exists at the workspace root.
     */
    fun isGitInitialized(workspaceRoot: File): Boolean {
        return File(workspaceRoot, ".git").exists()
    }

    /**
     * Forcefully purges stale JGit lock files to prevent transactional deadlocks.
     */
    private fun cleanupStaleLocks(workspaceRoot: File) {
        try {
            File(workspaceRoot, ".git/index.lock").apply {
                if (exists() && delete()) {
                    // Stale index lock successfully cleared
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Safely clears internal JGit caches and refreshes reference tracking configurations.
     */
    private fun flushRepositoryCaches(git: Git) {
        try {
            git.repository.refDatabase.refresh()
            RepositoryCache.clear()
        } catch (_: Exception) {}
    }

    /**
     * Initializes a Git repository inside the workspace if one does not already exist.
     */
    suspend fun initGit(workspaceRoot: File, authorName: String = AUTHOR_NAME, authorEmail: String = AUTHOR_EMAIL) = gitMutex.withLock {
        if (isGitInitialized(workspaceRoot)) return@withLock

        try {
            cleanupStaleLocks(workspaceRoot)
            Git.init().setDirectory(workspaceRoot).call().use { git ->
                val gitignore = File(workspaceRoot, ".gitignore")
                if (!gitignore.exists()) {
                    gitignore.writeText("build/\n.gradle/\n.idea/\n*.iml\nlocal.properties\n.codeassist/\n")
                }
                
                git.add().addFilepattern(".").call()
                git.commit()
                    .setMessage("Initial commit before CodeAssist tracking")
                    .setAuthor(authorName, authorEmail)
                    .setCommitter(authorName, authorEmail)
                    .call()
            }
        } catch (_: Exception) {
            cleanupStaleLocks(workspaceRoot)
        }
    }

    /**
     * Commits designated modifications with high performance by bypassing full-tree scans.
     * Implements an O(1) targeted batch stage/removal process instead of O(N) indexing.
     */
    suspend fun commitChanges(workspaceRoot: File, message: String, relativePaths: List<String>, authorName: String = AUTHOR_NAME, authorEmail: String = AUTHOR_EMAIL): String? = gitMutex.withLock {
        if (relativePaths.isEmpty()) return null
        cleanupStaleLocks(workspaceRoot)

        return try {
            Git.open(workspaceRoot).use { git ->
                flushRepositoryCaches(git)
                cleanupStaleLocks(workspaceRoot)

                val addCommand = git.add()
                val rmCommand = git.rm().setCached(false)
                
                var structuralAdds = false
                var structuralRms = false

                for (path in relativePaths) {
                    if (File(workspaceRoot, path).exists()) {
                        addCommand.addFilepattern(path)
                        structuralAdds = true
                    } else {
                        rmCommand.addFilepattern(path)
                        structuralRms = true
                    }
                }

                if (structuralAdds) addCommand.call()
                if (structuralRms) rmCommand.call()

                if (structuralAdds || structuralRms) {
                    cleanupStaleLocks(workspaceRoot)
                    val commitResult = git.commit()
                        .setMessage(message)
                        .setAuthor(authorName, authorEmail)
                        .setCommitter(authorName, authorEmail)
                        .call()
                    return commitResult.name
                }
                null
            }
        } catch (_: EmptyCommitException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            cleanupStaleLocks(workspaceRoot)
        }
    }

    /**
     * Reverts a specific commit using its alphanumeric unique tracking hash.
     */
    suspend fun revertCommit(workspaceRoot: File, commitHash: String): Boolean = gitMutex.withLock {
        if (!isGitInitialized(workspaceRoot)) return false
        cleanupStaleLocks(workspaceRoot)

        return try {
            Git.open(workspaceRoot).use { git ->
                flushRepositoryCaches(git)
                val commitId = git.repository.resolve(commitHash) ?: return false
                git.revert().include(commitId).call()
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            cleanupStaleLocks(workspaceRoot)
        }
    }

    /**
     * Hard rolls back the entire current repository structure to its preceding baseline (HEAD~1).
     */
    suspend fun resetHardToPrevious(workspaceRoot: File): Boolean = gitMutex.withLock {
        if (!isGitInitialized(workspaceRoot)) return false
        cleanupStaleLocks(workspaceRoot)

        return try {
            Git.open(workspaceRoot).use { git ->
                flushRepositoryCaches(git)
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call()
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            cleanupStaleLocks(workspaceRoot)
        }
    }

    /**
     * Purges uncommitted modifications instantly, aligning the project space to the latest clean snapshot.
     */
    suspend fun discardUncommittedChanges(workspaceRoot: File): Boolean = gitMutex.withLock {
        if (!isGitInitialized(workspaceRoot)) return false
        cleanupStaleLocks(workspaceRoot)

        return try {
            Git.open(workspaceRoot).use { git ->
                flushRepositoryCaches(git)
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
                git.clean().setCleanDirectories(true).setForce(true).call()
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            cleanupStaleLocks(workspaceRoot)
        }
    }

    data class CommitInfo(val hash: String, val message: String, val author: String, val time: Long)

    /**
     * Collects all chronological system commit logs mapped directly from the repository internals.
     */
    suspend fun getCommitHistory(workspaceRoot: File): List<CommitInfo> = gitMutex.withLock {
        val historyList = mutableListOf<CommitInfo>()
        if (!isGitInitialized(workspaceRoot)) return historyList
        cleanupStaleLocks(workspaceRoot)

        try {
            Git.open(workspaceRoot).use { git ->
                flushRepositoryCaches(git)
                
                val logs = git.log().all().call()
                for (revCommit in logs) {
                    historyList.add(
                        CommitInfo(
                            hash = revCommit.name,
                            message = revCommit.fullMessage,
                            author = revCommit.authorIdent.name,
                            time = revCommit.commitTime.toLong() * 1000L
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        
        return historyList.sortedWith(compareByDescending<CommitInfo> { it.time }.thenByDescending { it.hash })
    }
}