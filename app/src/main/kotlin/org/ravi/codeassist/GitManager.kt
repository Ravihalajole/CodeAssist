package org.ravi.codeassist

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
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
     * Stat-style summary of the most recent commit (HEAD vs its parent) for the
     * per-batch state snapshot, so the model can verify exactly which lines
     * landed, e.g. `  CodeAssist.md | +14 -2`. Null when there's no repo, no
     * prior commit, or HEAD changed nothing.
     */
    suspend fun lastCommitDiffStat(workspaceRoot: File): String? {
        val repoRoot = repoRootFor(workspaceRoot) ?: return null
        return try {
            Git.open(repoRoot).use outer@{ git ->
                val commit = git.log().setMaxCount(1).call().asSequence().firstOrNull() ?: return@outer null
                val parent = commit.parents.firstOrNull() ?: return@outer null
                val formatter = DiffFormatter(java.io.ByteArrayOutputStream())
                try {
                    formatter.setRepository(git.repository)
                    git.repository.newObjectReader().use { reader ->
                        val oldTree = CanonicalTreeParser(null, reader, parent.tree.id)
                        val newTree = CanonicalTreeParser(null, reader, commit.tree.id)
                        val diff = git.diff().setOldTree(oldTree).setNewTree(newTree).call()
                        if (diff.isEmpty()) return@outer null
                        diff.map { entry ->
                            val edits = formatter.toFileHeader(entry).toEditList()
                            val added = edits.sumOf { it.endB - it.beginB }
                            val removed = edits.sumOf { it.endA - it.beginA }
                            val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                            "  $path | +$added -$removed"
                        }.joinToString("\n", prefix = "\n--- LAST COMMIT DIFF (HEAD) ---\n")
                    }
                } finally {
                    formatter.close()
                }
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

    data class CloneResult(val ok: Boolean, val error: String?, val path: File?)

    /**
     * Clones a repository over HTTPS into [destDir]. A blank or non-positive
     * [depth] performs a full clone (JGit shallow clone requires depth >= 1);
     * -1 is the UI default meaning "full history". [branch] is optional — blank
     * checks out the remote's default branch. Credentials are used only when
     * both [username] and [password] are non-blank (public repos need none).
     * On failure the partially-cloned directory is removed best-effort.
     */
    suspend fun cloneRepository(
        url: String,
        destDir: File,
        branch: String?,
        depth: Int,
        username: String?,
        password: String?
    ): CloneResult = gitMutex.withLock {
        if (destDir.exists() && (destDir.list()?.isNotEmpty() == true)) {
            return@withLock CloneResult(false, "Destination folder already exists and is not empty.", null)
        }
        destDir.parentFile?.mkdirs()
        try {
            val command = Git.cloneRepository()
                .setURI(url)
                .setDirectory(destDir)
                .setTimeout(60)
            if (!branch.isNullOrBlank()) {
                command.setBranch(branch)
            }
            if (depth > 0) {
                command.setDepth(depth)
            }
            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                command.setCredentialsProvider(UsernamePasswordCredentialsProvider(username, password))
            }
            command.call().use { git ->
                git.remoteList().call()
            }
            CloneResult(true, null, destDir)
        } catch (e: Exception) {
            destDir.deleteRecursively()
            CloneResult(false, e.message ?: "Clone failed.", null)
        }
    }

    /**
     * Names of the configured remotes (e.g. origin), for the push picker.
     */
    suspend fun listRemotes(workspaceRoot: File): List<String> = gitMutex.withLock {
        val repoRoot = repoRootFor(workspaceRoot) ?: return@withLock emptyList()
        try {
            Git.open(repoRoot).use { git ->
                git.remoteList().call().map { it.name }.sorted()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Short names of the local branches, for the push picker.
     */
    suspend fun listBranches(workspaceRoot: File): List<String> = gitMutex.withLock {
        val repoRoot = repoRootFor(workspaceRoot) ?: return@withLock emptyList()
        try {
            Git.open(repoRoot).use { git ->
                git.branchList().call().map { it.name.removePrefix("refs/heads/") }.sorted()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Pushes the local [branch] to [remoteOrUrl] (a configured remote name or
     * a full HTTPS URL). Shallow clones (depth > 0) are unshallowed first so
     * the push is not rejected. Returns null on success, or a friendly error
     * message on failure (non-fast-forward, auth, network...).
     */
    suspend fun pushToRemote(
        workspaceRoot: File,
        remoteOrUrl: String,
        branch: String,
        username: String?,
        password: String?
    ): String? = gitMutex.withLock {
        val repoRoot = repoRootFor(workspaceRoot) ?: return@withLock "Workspace is not a Git repository."
        if (branch.isBlank()) return@withLock "Select a branch to push."
        cleanupStaleLocks(workspaceRoot)
        try {
            Git.open(repoRoot).use { git ->
                flushRepositoryCaches(git)
                if (File(repoRoot, ".git/shallow").exists()) {
                    git.fetch().setRemote(remoteOrUrl).setDepth(0).call()
                }
                val command = git.push()
                    .setRemote(remoteOrUrl)
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .setTimeout(60)
                if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                    command.setCredentialsProvider(UsernamePasswordCredentialsProvider(username, password))
                }
                val results = command.call()
                val failures = mutableListOf<String>()
                for (result in results) {
                    for (update in result.remoteUpdates) {
                        if (update.status != RemoteRefUpdate.Status.OK &&
                            update.status != RemoteRefUpdate.Status.UP_TO_DATE
                        ) {
                            failures.add(describePushFailure(update))
                        }
                    }
                }
                if (failures.isEmpty()) null else failures.joinToString("\n")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Push failed."
            when {
                msg.contains("rejected", ignoreCase = true) || msg.contains("non-fast-forward", ignoreCase = true) ->
                    "Push rejected — the remote branch has newer commits. Pull/merge remote changes first."
                msg.contains("auth", ignoreCase = true) || msg.contains("401", ignoreCase = true) ||
                    msg.contains("not authorized", ignoreCase = true) ->
                    "Authentication failed — check your username and token (needs repo:push scope)."
                msg.contains("timeout", ignoreCase = true) ->
                    "Connection timed out — check your network."
                else -> msg
            }
        }
    }

    private fun describePushFailure(update: RemoteRefUpdate): String {
        val status = update.status.toString()
        return when {
            update.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                "Remote branch has newer commits — pull/merge first."
            update.message.isNullOrBlank() -> "Push rejected ($status)."
            else -> "Push rejected ($status): ${update.message}"
        }
    }
}