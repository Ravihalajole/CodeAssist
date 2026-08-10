package org.ravi.codeassist

import java.io.File
import java.util.regex.Pattern

object CommandExecutorUtils {
    fun countOccurrences(text: String, search: String): Int {
        if (search.isEmpty()) return 0
        var count = 0
        var index = 0
        while (text.indexOf(search, index).also { index = it } != -1) {
            count++
            index += search.length
        }
        return count
    }

    fun isPathSafe(rootDir: File, targetFile: File): Boolean {
        return try {
            val root = rootDir.canonicalPath
            val target = targetFile.canonicalPath
            target == root || target.startsWith(root + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    fun ensureWorkspaceReady(rootDir: File): CommandExecutor.ExecutionResult? {
        if (!rootDir.exists()) {
            if (!rootDir.mkdirs()) return CommandExecutor.ExecutionResult(false, "Critical: Could not create Workspace root.")
        }
        return if (!rootDir.isDirectory) CommandExecutor.ExecutionResult(false, "Workspace root is not a directory.") else null
    }
}