package org.ravi.codeassist

import android.content.Context
import java.io.File
import java.util.regex.Pattern

object CommandExecutor {

    private var appContextRef: java.lang.ref.WeakReference<Context>? = null

    fun registerContext(context: Context) {
        if (appContextRef?.get() == null) {
            appContextRef = java.lang.ref.WeakReference(context.applicationContext)
        }
    }

    fun getAppContext(): Context? = appContextRef?.get()

    data class ExecutionResult(
        val success: Boolean,
        val logMsg: String,
        val outputToClipboard: String? = null,
        val backupPath: String? = null
    )

    fun validate(command: CodeCommand, workspaceRoot: String): String? {
        val rootDir = File(workspaceRoot)
        if (!rootDir.exists() || !rootDir.isDirectory) return "Workspace root invalid."

        return try {
            val targetPath = when(command) {
                is CodeCommand.Read -> command.path
                is CodeCommand.Grep -> command.path
                is CodeCommand.Patch -> command.path
                is CodeCommand.Create -> command.path
                is CodeCommand.Delete -> command.path
                is CodeCommand.Move -> command.oldPath
                is CodeCommand.Outline -> command.path
                is CodeCommand.Glob, is CodeCommand.AskUser, is CodeCommand.Done -> return null
            }
            val targetFile = File(rootDir, targetPath)
            
            if (targetPath.isNotEmpty() && !CommandExecutorUtils.isPathSafe(rootDir, targetFile)) {
                return "Path Issue: Target outside workspace. Please correct the relative path."
            }

            when (command) {
                is CodeCommand.Move -> {
                    if (!File(rootDir, command.oldPath).exists()) return "Source file does not exist."
                    if (!CommandExecutorUtils.isPathSafe(rootDir, File(rootDir, command.newPath))) return "Security Error: Destination path traversal blocked."
                    null
                }
                is CodeCommand.Read, is CodeCommand.Outline -> {
                    if (!targetFile.exists() || !targetFile.isFile) return "Error: File does not exist -> $targetPath"
                    null
                }
                is CodeCommand.Delete -> {
                    if (!targetFile.exists()) return "Error: File to delete does not exist -> $targetPath"
                    null
                }
                is CodeCommand.Patch -> {
                    if (!targetFile.exists() || !targetFile.isFile) {
                        "Patch failed: File does not exist -> ${command.path}"
                    } else if (targetFile.length() > 2 * 1024 * 1024L) {
                        "Patch failed: Target file exceeds memory-safe processing limits (2MB)."
                    } else if (command.replaceAll) {
                        val normalizedOriginal = targetFile.readText().replace("\r\n", "\n")
                        val normalizedSearch = command.search.replace("\r\n", "\n")
                        if (!normalizedOriginal.contains(normalizedSearch)) {
                            "Patch rejection: Search block not found for REPLACE_ALL."
                        } else {
                            null
                        }
                    } else {
                        val normalizedOriginal = targetFile.readText().replace("\r\n", "\n")
                        val normalizedSearch = command.search.replace("\r\n", "\n")
                        val isWildcardPatch = normalizedSearch.lines().any { val t = it.trim(); t == "// ..." || t == "/* ... */" }
                        
                        if (isWildcardPatch) {
                            val (wildcardSuccess, _) = findWildcardMatchAndReplace(normalizedOriginal, normalizedSearch, command.replace)
                            if (wildcardSuccess) null else "Patch rejection: Wildcard search block not uniquely identified."
                        } else {
                            val occurrences = CommandExecutorUtils.countOccurrences(normalizedOriginal, normalizedSearch)
                            if (occurrences != 1) {
                                val (floatingSuccess, _) = findFloatingIndentMatchAndReplace(normalizedOriginal, normalizedSearch, command.replace)
                                if (floatingSuccess) null else {
                                    val fallbackLine = normalizedSearch.lines().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: ""
                                    val snippet = if (fallbackLine.isNotEmpty()) {
                                        val originalLines = normalizedOriginal.lines()
                                        val bestIdx = originalLines.indexOfFirst { it.contains(fallbackLine, ignoreCase = true) }
                                        if (bestIdx != -1) {
                                            val start = maxOf(0, bestIdx - 5)
                                            val end = minOf(originalLines.size, bestIdx + 15)
                                            originalLines.subList(start, end).mapIndexed { i, l -> "${start + i + 1} | $l" }.joinToString("\n")
                                        } else "Could not locate a similar anchor line."
                                    } else "Empty search block."

                                    "Patch rejection: Exact matching block not found.\n--- SMART FALLBACK CONTEXT ---\n[WARNING: Line numbers 'N | ' are for reference only. DO NOT include them in your SEARCH block]\n$snippet\n\nPlease correct your SEARCH block to match the target file exactly, preserving original indentation."
                                }
                            } else null
                        }
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            "Validation Error: ${e.message}"
        }
    }

    fun execute(command: CodeCommand, workspaceRoot: String): ExecutionResult {
        val rootDir = File(workspaceRoot)
        val setupResult = CommandExecutorUtils.ensureWorkspaceReady(rootDir)
        if (setupResult != null) return setupResult

        return try {
            when (command) {
                is CodeCommand.Read -> handleRead(rootDir, command.path, command.startLine, command.endLine)
                is CodeCommand.Grep -> handleGrep(rootDir, command.path, command.pattern, command.ignoreDirs)
                is CodeCommand.Patch -> handlePatch(rootDir, command.path, command.search, command.replace, command.replaceAll)
                is CodeCommand.Create -> handleCreate(rootDir, command.path, command.content)
                is CodeCommand.Delete -> handleDelete(rootDir, command.path)
                is CodeCommand.Move -> handleMove(rootDir, command.oldPath, command.newPath)
                is CodeCommand.Glob -> handleGlob(rootDir, command.pattern)
                is CodeCommand.Outline -> handleOutline(rootDir, command.path)
                is CodeCommand.AskUser -> ExecutionResult(true, "HALT_FOR_USER: ${command.message}")
                is CodeCommand.Done -> ExecutionResult(true, "HALT_DONE: ${command.message}")
            }
        } catch (e: Exception) {
            ExecutionResult(false, "System Error: ${e.localizedMessage}")
        }
    }

    /**
     * Non-mutating preview of the patched file content for a PATCH command.
     * Runs the exact same strategy cascade as [handlePatch] (wildcard, exact,
     * floating-indent, fuzzy, Levenshtein) so the diff reflects what will
     * actually be written; returns null if no strategy matches or the file
     * cannot be trusted. Never writes to disk.
     */
    fun previewPatch(workspaceRoot: String, command: CodeCommand.Patch): Pair<String, String>? {
        val rootDir = File(workspaceRoot)
        val targetFile = File(rootDir, command.path)
        if (!CommandExecutorUtils.isPathSafe(rootDir, targetFile)) return null
        if (!targetFile.exists() || !targetFile.isFile) return null
        return try {
            val originalText = targetFile.readText()
            val normalizedOriginal = originalText.replace("\r\n", "\n")
            val resolution = resolvePatchText(
                normalizedOriginal,
                command.search.replace("\r\n", "\n"),
                command.replace.replace("\r\n", "\n"),
                command.replaceAll
            )
            if (resolution.success && resolution.resultText != null) {
                Pair(normalizedOriginal, resolution.resultText)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun handleRead(rootDir: File, relativePath: String, startLine: Int?, endLine: Int?): ExecutionResult {
        val targetFile = File(rootDir, relativePath)
        if (!CommandExecutorUtils.isPathSafe(rootDir, targetFile)) return ExecutionResult(false, "Security Error: Path traversal attempt blocked.")
        if (!targetFile.exists() || !targetFile.isFile) return ExecutionResult(false, "File not found: $relativePath")

        val start = maxOf(1, startLine ?: 1)
        val end = endLine ?: Int.MAX_VALUE
        // Safe headroom limit: 120,000 chars ensures this graceful pagination triggers BEFORE 
        // the system-level AgentAccessibilityService emergency chop (200,000 chars).
        val maxChars = 120_000

        val contentBuilder = StringBuilder()
        var currentLineNum = 0
        var charCount = 0
        var stoppedAtLine = -1
        // True only when we appended a partial fragment of line `stoppedAtLine`
        // (the single-long-line branch). The pagination instruction must tell
        // the model to resume at the NEXT line, otherwise it re-emits the same
        // long line and the first `maxChars` characters get duplicated.
        var partialLineIncluded = false

        targetFile.useLines { lines ->
            for (line in lines) {
                currentLineNum++

                if (currentLineNum < start) continue
                if (currentLineNum > end) break

                // Edge case: single line exceeds limit (e.g. minified JS)
                if (line.length > maxChars && charCount == 0) {
                    contentBuilder.append(line.take(maxChars))
                    stoppedAtLine = currentLineNum
                    partialLineIncluded = true
                    break
                }

                if (charCount + line.length > maxChars) {
                    stoppedAtLine = currentLineNum
                    break
                }

                contentBuilder.append(line).append("\n")
                charCount += line.length + 1
            }
        }

        val lastFullyIncludedLine = when {
            stoppedAtLine == -1 -> null
            partialLineIncluded -> stoppedAtLine
            else -> stoppedAtLine - 1
        }
        val displayEnd = lastFullyIncludedLine?.toString() ?: if (end == Int.MAX_VALUE) "EOF" else end.toString()
        val header = "--- [READ] $relativePath (Lines $start to $displayEnd) ---\n"

        var rawContent = contentBuilder.toString()

        if (stoppedAtLine != -1) {
            // Resume at the first line not yet fully included. For a single-line
            // overflow that was partially emitted, the rest of that line must be
            // re-read, so resume one line further to avoid duplicating the
            // already-delivered prefix.
            val resumeLine = if (partialLineIncluded) stoppedAtLine + 1 else stoppedAtLine
            val truncationWarning = "\n\n... [CONTENT TRUNCATED. Last fully included line: $lastFullyIncludedLine]\n" +
                    ">>> SYSTEM INSTRUCTION: The file is large and has been paginated. You must read the remaining lines from line $resumeLine to end of file by emitting a new READ command with [START_LINE: $resumeLine]."
            rawContent += truncationWarning
        }

        return ExecutionResult(true, "Successfully read file: $relativePath", header + rawContent)
    }

    private fun handleGrep(rootDir: File, relativePath: String, patternStr: String, ignoreDirs: List<String>): ExecutionResult {
        val targetFile = if (relativePath.isEmpty()) rootDir else File(rootDir, relativePath)
        if (!CommandExecutorUtils.isPathSafe(rootDir, targetFile)) return ExecutionResult(false, "Security Error: Path traversal blocked.")
        if (!targetFile.exists()) return ExecutionResult(false, "Path not found for Grep: $relativePath")

        val pattern = try { Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE) } catch (_: Exception) { Pattern.compile(Pattern.quote(patternStr), Pattern.CASE_INSENSITIVE) }
        val matchedLines = StringBuilder()
        var matchCount = 0
        val baseIgnore = listOf("build", ".gradle", ".git", ".idea", "node_modules", "outputs") + ignoreDirs

        fun searchFile(f: File) {
            if (matchCount >= 100) return
            f.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (line.length > 2000) return@forEachIndexed // Prevent Regex DOS
                    if (matchCount < 100 && pattern.matcher(line).find()) {
                        matchedLines.append("${f.relativeTo(rootDir).path}:${index + 1}: $line\n")
                        matchCount++
                    }
                }
            }
        }

        if (targetFile.isFile) {
            searchFile(targetFile)
        } else {
            targetFile.walkTopDown().onEnter { it.name !in baseIgnore }.forEach { f ->
                if (matchCount >= 100) return@forEach
                if (f.isFile && f.length() < 2 * 1024 * 1024L) searchFile(f)
            }
        }
        
        val resultText = matchedLines.toString().ifEmpty { "No matches found for pattern: $patternStr" }
        val suffix = if (matchCount >= 100) "\n(Output capped at 100 matches)" else ""
        return ExecutionResult(true, "Grep complete.", resultText + suffix)
    }

    private fun handleGlob(rootDir: File, patternStr: String): ExecutionResult {
        val ignoreList = listOf("build", ".gradle", ".git", ".idea", "node_modules", "outputs", "tmp")

        // Convert Glob to Regex. The sentinel must not contain regex metacharacters
        // or glob wildcards, otherwise an earlier `.replace("*", ...)` pass would
        // corrupt it before it can be substituted back. Using a control character
        // guarantees no collision with user input or intermediate regex fragments.
        val doubleStarSentinel = "\u0001"
        var regexStr = patternStr
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("+", "\\+")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("|", "\\|")
            .replace("**", doubleStarSentinel)
            .replace("*", "[^/]*")
            .replace("?", ".")
            .replace(doubleStarSentinel, ".*")

        // If the pattern doesn't contain a path separator, search globally across all directories
        if (!patternStr.contains("/")) {
            regexStr = ".*(/|^)$regexStr$"
        } else {
            // Otherwise, anchor it to match the exact relative path
            regexStr = "^$regexStr$"
        }

        val regexPattern = regexStr.toRegex(RegexOption.IGNORE_CASE)

        val matches = mutableListOf<String>()

        rootDir.walkTopDown().onEnter { it.name !in ignoreList }.forEach { f ->
            if (matches.size >= 150) return@forEach
            val relativePath = f.relativeTo(rootDir).path
            if (f.isFile && regexPattern.matches(relativePath)) {
                matches.add(relativePath)
            }
        }

        val result = matches.joinToString("\n").ifEmpty { "No files matched pattern: '$patternStr'." }
        val suffix = if (matches.size >= 150) "\n(Results capped at 150)" else ""
        return ExecutionResult(true, "Glob search complete.", result + suffix)
    }

    private fun handleOutline(rootDir: File, relativePath: String): ExecutionResult {
        val targetFile = File(rootDir, relativePath)
        if (!CommandExecutorUtils.isPathSafe(rootDir, targetFile)) return ExecutionResult(false, "Security Error.")
        if (!targetFile.exists() || !targetFile.isFile) return ExecutionResult(false, "File not found.")

        val outlineText = org.ravi.codeassist.utils.OutlineExtractor.generateOutline(targetFile)
        return ExecutionResult(true, "Outline generated.", outlineText)
    }

    private fun handleCreate(rootDir: File, relativePath: String, content: String): ExecutionResult {
        val targetFile = File(rootDir, relativePath)
        if (!CommandExecutorUtils.isPathSafe(rootDir, targetFile)) return ExecutionResult(false, "Security Error: Path traversal blocked.")
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(content)
        return ExecutionResult(true, "Successfully created file: $relativePath")
    }

    private fun handlePatch(rootDir: File, relativePath: String, search: String, replace: String, replaceAll: Boolean): ExecutionResult {
        val targetFile = File(rootDir, relativePath)
        if (!CommandExecutorUtils.isPathSafe(rootDir, targetFile)) return ExecutionResult(false, "Security Error: Path traversal attempt blocked.")
        val originalText = targetFile.readText()
        val isWindowsLineEndings = originalText.contains("\r\n")

        val resolution = resolvePatchText(
            originalText,
            search,
            replace,
            replaceAll
        )
        if (!resolution.success) {
            return ExecutionResult(false, resolution.errorMessage ?: "Patch failed.")
        }

        val updatedText = if (isWindowsLineEndings) {
            resolution.resultText!!.replace("\n", "\r\n")
        } else {
            resolution.resultText!!
        }
        targetFile.writeText(updatedText)
        return ExecutionResult(true, resolution.successMessage(relativePath))
    }

    /**
     * Resolves a PATCH against normalized file content using the same strategy
     * cascade as the executor: REPLACE_ALL, wildcard, exact single occurrence,
     * floating-indent, fuzzy whitespace-insensitive, then Levenshtein. Never
     * touches disk, so it is safe to call for live previews. The returned text
     * uses \n line endings; callers must re-apply the original \r\n style.
     */
    private fun resolvePatchText(originalText: String, search: String, replace: String, replaceAll: Boolean): PatchResolution {
        val normalizedOriginal = originalText.replace("\r\n", "\n")
        val normalizedSearch = search.replace("\r\n", "\n")
        val normalizedReplace = replace.replace("\r\n", "\n")

        if (replaceAll) {
            return PatchResolution(
                success = true,
                resultText = normalizedOriginal.replace(normalizedSearch, normalizedReplace),
                strategy = PatchStrategy.REPLACE_ALL
            )
        }

        val isWildcardPatch = normalizedSearch.lines().any { val t = it.trim(); t == "// ..." || t == "/* ... */" }
        if (isWildcardPatch) {
            val (wildcardSuccess, wildcardText) = findWildcardMatchAndReplace(normalizedOriginal, normalizedSearch, normalizedReplace)
            if (wildcardSuccess) {
                return PatchResolution(success = true, resultText = wildcardText, strategy = PatchStrategy.WILDCARD)
            }
            return PatchResolution(false, errorMessage = "Patch rejected: Wildcard search block must appear exactly once.")
        }

        val occurrences = CommandExecutorUtils.countOccurrences(normalizedOriginal, normalizedSearch)
        if (occurrences != 1) {
            val (floatingSuccess, floatingText) = findFloatingIndentMatchAndReplace(normalizedOriginal, normalizedSearch, normalizedReplace)
            if (floatingSuccess) {
                return PatchResolution(success = true, resultText = floatingText, strategy = PatchStrategy.FLOATING)
            }
            val (fuzzySuccess, fuzzyText) = findFuzzyMatchAndReplace(normalizedOriginal, normalizedSearch, normalizedReplace)
            if (fuzzySuccess) {
                return PatchResolution(success = true, resultText = fuzzyText, strategy = PatchStrategy.FUZZY)
            }
            val (levSuccess, levText) = findAdvancedLevenshteinMatchAndReplace(normalizedOriginal, normalizedSearch, normalizedReplace)
            if (levSuccess) {
                return PatchResolution(success = true, resultText = levText, strategy = PatchStrategy.LEVENSHTEIN)
            }
            return PatchResolution(false, errorMessage = buildFallbackErrorMessage(normalizedOriginal, normalizedSearch))
        }
        return PatchResolution(
            success = true,
            resultText = normalizedOriginal.replaceFirst(normalizedSearch, normalizedReplace),
            strategy = PatchStrategy.EXACT
        )
    }

    private fun buildFallbackErrorMessage(normalizedOriginal: String, normalizedSearch: String): String {
        val fallbackLine = normalizedSearch.lines().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: ""
        val snippet = if (fallbackLine.isNotEmpty()) {
            val originalLines = normalizedOriginal.lines()
            val bestIdx = originalLines.indexOfFirst { it.contains(fallbackLine, ignoreCase = true) }
            if (bestIdx != -1) {
                val start = maxOf(0, bestIdx - 5)
                val end = minOf(originalLines.size, bestIdx + 15)
                originalLines.subList(start, end).mapIndexed { i, l -> "${start + i + 1} | $l" }.joinToString("\n")
            } else "Could not locate a similar anchor line."
        } else "Empty search block."
        return "Patch rejected: Exact matching block not found.\n--- SMART FALLBACK CONTEXT ---\n[WARNING: Line numbers 'N | ' are for reference only. DO NOT include them in your SEARCH block]\n$snippet\n\nPlease correct your SEARCH block."
    }

    private enum class PatchStrategy { REPLACE_ALL, WILDCARD, EXACT, FLOATING, FUZZY, LEVENSHTEIN }

    private data class PatchResolution(
        val success: Boolean,
        val errorMessage: String? = null,
        val resultText: String? = null,
        val strategy: PatchStrategy? = null
    ) {
        fun successMessage(relativePath: String): String = when (strategy) {
            PatchStrategy.REPLACE_ALL -> "Successfully applied global REPLACE_ALL patch to: $relativePath"
            PatchStrategy.WILDCARD -> "Successfully applied wildcard patch to: $relativePath"
            else -> "Successfully applied file patch modifications to: $relativePath"
        }
    }

    private fun findFloatingIndentMatchAndReplace(original: String, search: String, replace: String): Pair<Boolean, String> {
        val originalLines = original.split("\n")
        val searchLines = search.split("\n")
        if (searchLines.isEmpty() || searchLines.all { it.trim().isEmpty() }) return Pair(false, original)

        val searchBaseIndentLen = searchLines.filter { it.trim().isNotEmpty() }.minOfOrNull { it.takeWhile { c -> c == ' ' || c == '\t' }.length } ?: 0
        val normalizedSearchLines = searchLines.map { line ->
            if (line.trim().isEmpty()) "" else if (line.length >= searchBaseIndentLen && line.take(searchBaseIndentLen).all { it == ' ' || it == '\t' }) line.substring(searchBaseIndentLen) else line.trimStart()
        }

        var matchCount = 0
        var matchIndex = -1
        var matchedTargetIndentString = ""

        for (i in 0..originalLines.size - searchLines.size) {
            var isMatch = true
            var targetBaseIndentLen = -1
            var targetBaseIndentStr = ""

            for (j in searchLines.indices) {
                val sLine = normalizedSearchLines[j]
                val oLine = originalLines[i + j]

                if (sLine.isEmpty()) {
                    if (oLine.trim().isNotEmpty()) { isMatch = false; break }
                    continue
                }

                if (targetBaseIndentLen == -1) {
                    targetBaseIndentStr = oLine.takeWhile { c -> c == ' ' || c == '\t' }
                    targetBaseIndentLen = targetBaseIndentStr.length
                }

                if (oLine != targetBaseIndentStr + sLine) { isMatch = false; break }
            }
            if (isMatch) { matchCount++; matchIndex = i; matchedTargetIndentString = targetBaseIndentStr }
        }

        if (matchCount == 1) {
            val replaceLines = replace.split("\n")
            val replaceBaseIndentLen = replaceLines.filter { it.trim().isNotEmpty() }.minOfOrNull { it.takeWhile { c -> c == ' ' || c == '\t' }.length } ?: 0

            val alignedReplaceLines = replaceLines.map { line ->
                if (line.trim().isEmpty()) "" else {
                    val stripped = if (line.length >= replaceBaseIndentLen && line.take(replaceBaseIndentLen).all { it == ' ' || it == '\t' }) line.substring(replaceBaseIndentLen) else line.trimStart()
                    matchedTargetIndentString + stripped
                }
            }

            val sbBefore = originalLines.take(matchIndex).joinToString("\n")
            val sbAfter = originalLines.drop(matchIndex + searchLines.size).joinToString("\n")
            return Pair(true, (if(sbBefore.isNotEmpty()) "$sbBefore\n" else "") + alignedReplaceLines.joinToString("\n") + (if(sbAfter.isNotEmpty()) "\n$sbAfter" else ""))
        }
        return Pair(false, original)
    }

    private fun findWildcardMatchAndReplace(original: String, search: String, replace: String): Pair<Boolean, String> {
        val searchLines = search.split("\n")
        val regexPatternBuilder = java.lang.StringBuilder()
        for (i in searchLines.indices) {
            val t = searchLines[i].trim()
            if (t == "// ..." || t == "/* ... */") {
                regexPatternBuilder.append("(.*?)")
                if (i < searchLines.size - 1) regexPatternBuilder.append("\\n?")
            } else {
                regexPatternBuilder.append(Pattern.quote(searchLines[i]))
                if (i < searchLines.size - 1) regexPatternBuilder.append("\\n")
            }
        }
        val matcher = Pattern.compile(regexPatternBuilder.toString(), Pattern.DOTALL).matcher(original)
        var matchCount = 0
        var matchStart = -1
        var matchEnd = -1
        val matchedGroups = mutableListOf<String>()
        while (matcher.find()) {
            matchCount++
            if (matchCount == 1) {
                matchStart = matcher.start(); matchEnd = matcher.end()
                for (i in 1..matcher.groupCount()) matchedGroups.add(matcher.group(i) ?: "")
            }
        }
        if (matchCount == 1) {
            val replaceLines = replace.split("\n")
            val replaceBuilder = java.lang.StringBuilder()
            var currentGroup = 0
            for (i in replaceLines.indices) {
                val t = replaceLines[i].trim()
                if (t == "// ..." || t == "/* ... */") {
                    if (currentGroup < matchedGroups.size) { replaceBuilder.append(matchedGroups[currentGroup]); currentGroup++ }
                } else replaceBuilder.append(replaceLines[i])
                if (i < replaceLines.size - 1) replaceBuilder.append("\n")
            }
            return Pair(true, original.substring(0, matchStart) + replaceBuilder.toString() + original.substring(matchEnd))
        }
        return Pair(false, original)
    }

    private fun findFuzzyMatchAndReplace(original: String, search: String, replace: String): Pair<Boolean, String> {
        val originalLines = original.split("\n")
        val searchLines = search.split("\n")
        // ONLY collapse runs of spaces/tabs. Previously this used "\\s" which also
        // matches newlines and other whitespace classes; on already-split single
        // lines that turned "foo(" and "foo (" into the same key, so structurally
        // different lines could fuzzy-match and replace the wrong block.
        val normalizer = "[ \t]+".toRegex()
        val normalizedOriginalLines = originalLines.map { it.replace(normalizer, "") }
        val normalizedSearchLines = searchLines.map { it.replace(normalizer, "") }
        if (normalizedSearchLines.isEmpty() || normalizedSearchLines.all { it.isEmpty() }) return Pair(false, original)

        var matchCount = 0
        var matchIndex = -1
        for (i in 0..normalizedOriginalLines.size - normalizedSearchLines.size) {
            var linesMatch = true
            for (j in normalizedSearchLines.indices) {
                if (normalizedOriginalLines[i + j] != normalizedSearchLines[j]) { linesMatch = false; break }
            }
            if (linesMatch) { matchCount++; if (matchIndex == -1) matchIndex = i }
        }

        // Only accept a unique match — multiple matches are ambiguous and we'd
        // risk patching the wrong location.
        if (matchCount == 1) {
            val sbBefore = originalLines.take(matchIndex).joinToString("\n")
            val sbAfter = originalLines.drop(matchIndex + searchLines.size).joinToString("\n")
            val firstNonEmptyIdx = searchLines.indexOfFirst { it.trim().isNotEmpty() }.takeIf { it >= 0 } ?: 0
            val searchIndent = searchLines[firstNonEmptyIdx].takeWhile { it == ' ' || it == '\t' }
            val actualIndent = originalLines[matchIndex + firstNonEmptyIdx].takeWhile { it == ' ' || it == '\t' }

            val alignedReplace = replace.split("\n").joinToString("\n") { line ->
                if (line.trim().isEmpty()) "" else if (line.startsWith(searchIndent)) actualIndent + line.removePrefix(searchIndent) else actualIndent + line.trimStart()
            }
            return Pair(true, (if(sbBefore.isNotEmpty()) "$sbBefore\n" else "") + alignedReplace + (if(sbAfter.isNotEmpty()) "\n$sbAfter" else ""))
        }
        return Pair(false, original)
    }

    private fun findAdvancedLevenshteinMatchAndReplace(original: String, search: String, replace: String): Pair<Boolean, String> {
        val originalLines = original.lines()
        val searchLines = search.lines()
        
        val trimmedSearchLines = searchLines.dropWhile { it.trim().isEmpty() }.dropLastWhile { it.trim().isEmpty() }
        if (trimmedSearchLines.isEmpty()) return Pair(false, original)

        var bestMatchIndex = -1
        var bestDistance = Int.MAX_VALUE
        var bestMatchCount = 0

        for (i in 0..originalLines.size - trimmedSearchLines.size) {
            var currentDistance = 0
            for (j in trimmedSearchLines.indices) {
                val oLine = originalLines[i + j].trim()
                val sLine = trimmedSearchLines[j].trim()
                currentDistance += org.ravi.codeassist.utils.FuzzyMatcher.levenshtein(oLine, sLine)
            }
            if (currentDistance < trimmedSearchLines.size * 3) {
                if (currentDistance < bestDistance) {
                    // New strict best. Reset the count: any earlier windows with
                    // the previous best distance are now non-competitive and
                    // must NOT contribute to the uniqueness check.
                    bestDistance = currentDistance
                    bestMatchIndex = i
                    bestMatchCount = 1
                } else if (currentDistance == bestDistance) {
                    bestMatchCount++
                }
            }
        }

        // Reject ambiguous fuzzy matches: only accept when there is exactly one
        // window at the final best distance. Previously the eager "bestMatchCount = 1"
        // reset on every new best could drop the count of earlier equal-distance
        // windows, allowing a non-unique match to be silently accepted.
        if (bestMatchCount == 1 && bestMatchIndex != -1) {
            val sbBefore = originalLines.take(bestMatchIndex).joinToString("\n")
            val sbAfter = originalLines.drop(bestMatchIndex + trimmedSearchLines.size).joinToString("\n")
            
            val firstNonEmptyIdx = searchLines.indexOfFirst { it.trim().isNotEmpty() }.takeIf { it >= 0 } ?: 0
            val searchIndent = searchLines[firstNonEmptyIdx].takeWhile { it == ' ' || it == '\t' }
            val actualIndent = originalLines[bestMatchIndex].takeWhile { it == ' ' || it == '\t' }

            val alignedReplace = replace.lines().joinToString("\n") { line ->
                if (line.trim().isEmpty()) "" else if (line.startsWith(searchIndent)) actualIndent + line.removePrefix(searchIndent) else actualIndent + line.trimStart()
            }
            return Pair(true, (if(sbBefore.isNotEmpty()) "$sbBefore\n" else "") + alignedReplace + (if(sbAfter.isNotEmpty()) "\n$sbAfter" else ""))
        }
        return Pair(false, original)
    }

    private fun handleDelete(rootDir: File, relativePath: String): ExecutionResult {
        val targetFile = File(rootDir, relativePath)
        if (!CommandExecutorUtils.isPathSafe(rootDir, targetFile)) return ExecutionResult(false, "Security Error.")
        return if (targetFile.exists()) {
            if (targetFile.isFile) { targetFile.delete(); ExecutionResult(true, "Deleted file: $relativePath") } 
            else ExecutionResult(false, "Target is a directory.")
        } else ExecutionResult(true, "File already absent: $relativePath")
    }

    private fun handleMove(rootDir: File, oldRelativePath: String, newRelativePath: String): ExecutionResult {
        val oldFile = File(rootDir, oldRelativePath)
        val newFile = File(rootDir, newRelativePath)

        if (!oldFile.exists() || !oldFile.isFile) return ExecutionResult(false, "Source file not found: $oldRelativePath")
        if (newFile.exists()) return ExecutionResult(false, "Destination file already exists: $newRelativePath. Delete it first.")

        newFile.parentFile?.mkdirs()
        val success = if (oldFile.renameTo(newFile)) {
            true
        } else {
            // renameTo returns false across filesystem mount points and on some
            // OEM FUSE-backed paths on Android. Fall back to copy + delete, which
            // works across mounts at the cost of an extra I/O pass.
            try {
                oldFile.copyTo(newFile, overwrite = false)
                val deleted = oldFile.delete()
                if (!deleted) {
                    // Roll back: the move is not atomic, so if we cannot remove the
                    // source we must undo the destination copy to avoid duplicating
                    // the file. Only remove the copy we just created.
                    if (newFile.exists()) newFile.delete()
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
        return if (success) ExecutionResult(true, "Successfully moved file from $oldRelativePath to $newRelativePath")
        else ExecutionResult(false, "Failed to move file. Ensure no system process is locking it.")
    }
}