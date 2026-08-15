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
    private var appContextRef: java.lang.ref.WeakReference<android.content.Context>? = null

    /**
     * Registers an application context safely to dispatch UI Toast notifications without leaking memory.
     */
    fun registerContext(context: android.content.Context) {
        if (appContextRef?.get() == null) {
            appContextRef = java.lang.ref.WeakReference(context.applicationContext)
        }
    }

    /**
     * Walks up from [startDir] and returns the root of the nearest enclosing
     * Git repository (i.e. a directory whose `.git` entry is present). This
     * detects workspaces nested inside an existing repo so JGit never operates
     * on a freshly inited, competing nested repository. Returns null when the
     * workspace stands outside any repository.
     */
    private fun findEnclosingRepo(startDir: File): File? {
        var dir: File? = startDir
        var hops = 0
        while (dir != null && hops < 20) {
            if (File(dir, ".git").exists()) return dir
            val parent = dir.parentFile
            if (parent == null || parent == dir) return null
            dir = parent
            hops++
        }
        return null
    }

    /**
     * Resolves a repository-root directory to operate on: the enclosing repo
     * when the workspace is nested, otherwise the workspace itself.
     */
    private fun repoRootFor(workspaceRoot: File): File? = findEnclosingRepo(workspaceRoot)

    /**
     * Converts an absolute [file] (already known to live under [repoRoot]'s
     * subtree) into the repo-relative path expected by JGit add/rm patterns.
     * Uses string prefixing instead of [java.io.File.toPath] so it stays
     * compatible with minSdk 23.
     */
    private fun toRepoRelative(repoRoot: File, file: File): String {
        if (repoRoot.absolutePath == file.absolutePath) return "."
        val rootPath = repoRoot.absolutePath.trimEnd(File.separatorChar) + File.separatorChar
        val filePath = file.absolutePath
        if (filePath.startsWith(rootPath)) {
            return filePath.substring(rootPath.length).replace(File.separatorChar, '/')
        }
        return "."
    }

    /**
     * Checks whether a Git repository exists at or enclosing the workspace root.
     */
    fun isGitInitialized(workspaceRoot: File): Boolean {
        return findEnclosingRepo(workspaceRoot) != null
    }

    /**
     * Safely purges stale JGit lock files, waiting for active processes to finish.
     * Resolves the effective repo root (enclosing if nested) to find `.git`.
     */
    private suspend fun cleanupStaleLocks(workspaceRoot: File) {
        try {
            val repoRoot = repoRootFor(workspaceRoot) ?: workspaceRoot
            val lockFile = File(repoRoot, ".git/index.lock")
            if (!lockFile.exists()) return

            for (i in 1..3) {
                val age = System.currentTimeMillis() - lockFile.lastModified()
                if (age < 10000) {
                    kotlinx.coroutines.delay(500)
                    if (!lockFile.exists()) return
                } else {
                    break
                }
            }

            if (lockFile.exists() && lockFile.delete()) {
                appContextRef?.get()?.let { context ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context, 
                            "Stale Git index.lock cleared successfully!", 
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
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
     * Initializes a Git repository inside the workspace only when the workspace
     * is not already inside an existing repository — nesting a repo under an
     * enclosing one would fragment the parent repo's tracking. When the
     * workspace is already covered by an enclosing repo, this is a no-op.
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
     * Operates on the enclosing repository (when the workspace is nested), translating
     * workspace-relative paths to repo-relative patterns.
     */
    suspend fun commitChanges(workspaceRoot: File, message: String, relativePaths: List<String>, authorName: String = AUTHOR_NAME, authorEmail: String = AUTHOR_EMAIL): String? = gitMutex.withLock {
        if (relativePaths.isEmpty()) return null
        val repoRoot = repoRootFor(workspaceRoot) ?: return null
        cleanupStaleLocks(workspaceRoot)

        return try {
            Git.open(repoRoot).use { git ->
                flushRepositoryCaches(git)
                cleanupStaleLocks(workspaceRoot)

                val addCommand = git.add()
                val rmCommand = git.rm().setCached(false)
                
                var structuralAdds = false
                var structuralRms = false

                for (path in relativePaths) {
                    val absolute = File(workspaceRoot, path)
                    val repoRelPath = toRepoRelative(repoRoot, absolute)
                    if (absolute.exists()) {
                        addCommand.addFilepattern(repoRelPath)
                        structuralAdds = true
                    } else {
                        rmCommand.addFilepattern(repoRelPath)
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
     * Stages and commits all current workspace modifications (tracked, untracked, and deletions).
     * When the workspace is nested inside an enclosing repo, staging is scoped to the workspace
     * subtree so unrelated files elsewhere in the parent repo are never committed.
     */
    suspend fun commitAllChanges(workspaceRoot: File, message: String, authorName: String = AUTHOR_NAME, authorEmail: String = AUTHOR_EMAIL): String? = gitMutex.withLock {
        val repoRoot = repoRootFor(workspaceRoot) ?: return null
        cleanupStaleLocks(workspaceRoot)
        return try {
            Git.open(repoRoot).use { git ->
                flushRepositoryCaches(git)
                cleanupStaleLocks(workspaceRoot)

                val addCmd = git.add()
                addCmd.addFilepattern(toRepoRelative(repoRoot, workspaceRoot))
                addCmd.call()

                val commitResult = git.commit()
                    .setMessage(message)
                    .setAuthor(authorName, authorEmail)
                    .setCommitter(authorName, authorEmail)
                    .call()
                commitResult.name
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
        val repoRoot = repoRootFor(workspaceRoot) ?: return false
        cleanupStaleLocks(workspaceRoot)

        return try {
            Git.open(repoRoot).use { git ->
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
     * Hard rolls back the entire current repository structure to a specific commit or reference.
     */
    suspend fun resetHardToCommit(workspaceRoot: File, targetRef: String): Boolean = gitMutex.withLock {
        val repoRoot = repoRootFor(workspaceRoot) ?: return false
        cleanupStaleLocks(workspaceRoot)

        return try {
            Git.open(repoRoot).use { git ->
                flushRepositoryCaches(git)
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(targetRef).call()
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            cleanupStaleLocks(workspaceRoot)
        }
    }

    /**
     * Current HEAD commit hash of the enclosing repo, or null when the
     * workspace stands outside any repository.
     */
    suspend fun currentHead(workspaceRoot: File): String? {
        val repoRoot = repoRootFor(workspaceRoot) ?: return null
        return try {
            Git.open(repoRoot).use { git ->
                git.repository.resolve("HEAD")?.name
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Creates a rollback checkpoint by committing every current workspace
     * change, then returning the resulting HEAD. When nothing is dirty the
     * existing HEAD is returned unchanged. Used as the session-start
     * snapshot the "Undo Session" tool resets back to.
     */
    suspend fun createCheckpoint(workspaceRoot: File, label: String): String? {
        val repoRoot = repoRootFor(workspaceRoot) ?: return null
        val committed = commitAllChanges(workspaceRoot, "[CodeAssist checkpoint] $label")
        return committed ?: currentHead(workspaceRoot)
    }

    /**
     * Tags current HEAD (lightweight tag, force-updated) so round/session
     * checkpoints can be listed and hard-reset to by the "Undo Session" tool.
     */
    suspend fun tagCurrentHead(workspaceRoot: File, tagName: String): Boolean = gitMutex.withLock {
        val repoRoot = repoRootFor(workspaceRoot) ?: return false
        cleanupStaleLocks(workspaceRoot)
        return try {
            Git.open(repoRoot).use { git ->
                flushRepositoryCaches(git)
                git.tag().setName(tagName).setForceUpdate(true).call()
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            cleanupStaleLocks(workspaceRoot)
        }
    }

    data class RoundCheckpoint(val tag: String, val round: Int, val message: String, val time: Long)

    /**
     * Lists the `codeassist-round-*` checkpoint tags (plus the session-start
     * tag) newest first, for the undo picker. Reads the underlying commit for
     * the message/timestamp.
     */
    suspend fun listCheckpoints(workspaceRoot: File): List<RoundCheckpoint> = gitMutex.withLock {
        val repoRoot = repoRootFor(workspaceRoot) ?: return emptyList()
        return try {
            Git.open(repoRoot).use { git ->
                git.tagList().call().mapNotNull { ref ->
                    val shortName = ref.name.removePrefix("refs/tags/")
                    val round = when {
                        shortName.startsWith("codeassist-round-") -> shortName.removePrefix("codeassist-round-").toIntOrNull() ?: return@mapNotNull null
                        shortName == "codeassist-session-start" -> 0
                        else -> return@mapNotNull null
                    }
                    val commitId = git.repository.resolve("${ref.name}^{}") ?: ref.objectId ?: return@mapNotNull null
                    val commit = git.repository.parseCommit(commitId)
                    RoundCheckpoint(
                        tag = shortName,
                        round = round,
                        message = commit.shortMessage,
                        time = commit.commitTime.toLong() * 1000L
                    )
                }.sortedWith(compareByDescending<RoundCheckpoint> { it.round }.thenByDescending { it.time })
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class CommitInfo(val hash: String, val message: String, val author: String, val time: Long)

    data class WorkspaceStatus(val isRepo: Boolean, val branch: String?, val isClean: Boolean, val changeCount: Int)

    /**
     * Summarizes the workspace repository state (branch + working-tree cleanliness)
     * for lightweight UI display, e.g. "main · Clean" or "main · 3 changes".
     * Resolves the enclosing repo when the workspace is nested.
     */
    suspend fun getWorkspaceStatus(workspaceRoot: File): WorkspaceStatus {
        val repoRoot = repoRootFor(workspaceRoot) ?: return WorkspaceStatus(false, null, true, 0)
        return try {
            Git.open(repoRoot).use { git ->
                val branch = git.repository.branch
                val status = git.status().call()
                val changeCount = status.uncommittedChanges.size +
                    status.added.size + status.modified.size +
                    status.removed.size + status.missing.size + status.untracked.size
                WorkspaceStatus(true, branch, status.isClean, changeCount)
            }
        } catch (_: Exception) {
            WorkspaceStatus(false, null, true, 0)
        }
    }

    /**
     * One-line repository state for prompt injection, e.g.
     * `git <master> | clean | last commit 5c36ddd`. Null when the workspace
     * stands outside any repository. Lightweight read intended for the
     * per-batch state snapshot the model observes each iteration.
     */
    suspend fun repositorySnapshot(workspaceRoot: File): String? {
        val repoRoot = repoRootFor(workspaceRoot) ?: return null
        return try {
            Git.open(repoRoot).use { git ->
                val branch = git.repository.branch ?: "detached"
                val status = git.status().call()
                val changes = status.modified.size + status.added.size +
                    status.removed.size + status.missing.size + status.untracked.size
                val lastCommit = git.log().setMaxCount(1).call().asSequence().firstOrNull()?.name
                val last = if (lastCommit != null) " | last commit ${lastCommit.take(7)}" else ""
                "git <$branch> | ${if (changes == 0) "clean" else "$changes pending change(s)"}$last"
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Collects all chronological system commit logs mapped directly from the repository internals.
     */
    suspend fun getCommitHistory(workspaceRoot: File): List<CommitInfo> = gitMutex.withLock {
        val historyList = mutableListOf<CommitInfo>()
        val repoRoot = repoRootFor(workspaceRoot) ?: return historyList
        cleanupStaleLocks(workspaceRoot)

        try {
            Git.open(repoRoot).use { git ->
                flushRepositoryCaches(git)
                
                val logs = git.log().setMaxCount(100).call()
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