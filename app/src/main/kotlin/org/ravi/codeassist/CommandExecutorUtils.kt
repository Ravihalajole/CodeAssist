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

    /**
     * Normalizes chat-app rendering artifacts: smart/curly quotes become
     * straight, unicode spaces (NBSP, figure, narrow, ideographic) become ASCII
     * space, and zero-width chars (ZWSP, ZWNJ, ZWJ, word joiner, BOM) are
     * stripped. Used both for envelope tag/marker dispatch and as the
     * normalized-exact PATCH matching layer. The transform is one-to-one except
     * for the stripped chars, so normalized positions map back to the original.
     */
    fun normalizeForMatch(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            normalizeForMatchChar(ch)?.let { sb.append(it) }
        }
        return sb.toString()
    }

    /**
     * Locates a single occurrence of [search] inside [original] treating both
     * as normalizeForMatch-normalized text, and returns the RAW byte range in
     * [original] (start until end). Returns null when the normalized search
     * appears zero or multiple times, so callers never patch an ambiguous spot.
     */
    fun findNormalizedRange(original: String, search: String): IntRange? {
        if (search.isEmpty()) return null
        val sb = StringBuilder()
        val indexMap = ArrayList<Int>(original.length)
        for (idx in original.indices) {
            val normalized = normalizeForMatchChar(original[idx]) ?: continue
            indexMap.add(idx)
            sb.append(normalized)
        }
        val normSearch = normalizeForMatch(search)
        val normStart = sb.indexOf(normSearch)
        if (normStart < 0) return null
        val normEndExclusive = normStart + normSearch.length
        if (sb.indexOf(normSearch, normStart + 1) != -1) return null
        return indexMap[normStart] until (indexMap[normEndExclusive - 1] + 1)
    }

    private fun normalizeForMatchChar(ch: Char): Char? = when (ch.code) {
        0x2018, 0x2019 -> '\''
        0x201C, 0x201D -> '"'
        0x00A0, 0x2007, 0x202F, 0x3000 -> ' '
        0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF -> null
        else -> ch
    }
}