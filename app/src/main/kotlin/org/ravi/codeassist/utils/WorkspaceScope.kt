package org.ravi.codeassist.utils

import java.io.File

/**
 * Builds bounded, compact workspace maps for prompt injection so the model can
 * navigate the project without firing dozens of discovery round-trips:
 * - [buildTree] / [buildFileIndex] feed the boot prompt (what exists up front).
 * - [outlineFor] feeds per-batch state snapshots so the model can see the
 *   concrete result of each mutation (line counts + top symbols).
 *
 * All scans skip build/ignored directories and cap their output so a large
 * workspace can never flood the context window.
 */
object WorkspaceScope {

    private val IGNORED_DIRS = setOf(
        "build", ".gradle", ".git", ".idea", ".codeassist", "outputs", "tmp",
        "node_modules", "venv", ".venv"
    )

    private val SOURCE_EXTENSIONS = setOf(
        "kt", "kts", "java", "xml", "py", "js", "ts", "jsx", "tsx", "dart",
        "c", "cpp", "h", "hpp", "swift", "go", "rs", "rb", "php", "sh",
        "yml", "yaml", "json", "md"
    )

    /** Files longer than this are skipped by full-content readers (line counts). */
    private const val MAX_SNIFF_BYTES = 256 * 1024L

    fun isSourceFile(file: File): Boolean =
        file.isFile && file.extension.lowercase() in SOURCE_EXTENSIONS

    private fun isIgnoredDir(name: String): Boolean =
        name in IGNORED_DIRS || name.startsWith(".")

    /** All non-ignored source files under [root], sorted by relative path. */
    fun sourceFiles(root: File): List<File> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val files = mutableListOf<File>()
        try {
            root.walkTopDown()
                .onEnter { it == root || !isIgnoredDir(it.name) }
                .forEach { if (isSourceFile(it)) files.add(it) }
        } catch (_: Exception) {}
        return files.sortedBy { relativePath(root, it) }
    }

    /** Directory tree constrained to the source files. Bounded to [maxEntries] lines. */
    fun buildTree(root: File, maxEntries: Int = 200): String {
        val files = sourceFiles(root)
        if (files.isEmpty()) return "(no source files found)"
        val out = StringBuilder()
        out.appendLine("/")
        var count = 0
        for (file in files) {
            if (count >= maxEntries) {
                out.appendLine("  ... +${files.size - count} more files")
                break
            }
            val rel = relativePath(root, file)
            val depth = rel.count { it == '/' }
            out.append("  ".repeat(depth + 1)).appendLine(rel.substringAfterLast('/'))
            count++
        }
        return out.toString().trimEnd()
    }

    /**
     * Tab-separated `relative/path\tN lines` index. Files larger than the sniff
     * cap are tagged `large` instead of being read; reading is additionally
     * bounded to [lineLimit] files so big workspaces stay cheap to index.
     */
    fun buildFileIndex(root: File, maxEntries: Int = 150, lineLimit: Int = 120): String {
        val files = sourceFiles(root)
        if (files.isEmpty()) return "(no source files found)"
        val out = StringBuilder()
        var count = 0
        var measured = 0
        for (file in files) {
            if (count >= maxEntries) {
                out.append("... ").append(files.size - count).append(" more files\n")
                break
            }
            val rel = relativePath(root, file)
            out.append(rel).append('\t')
            if (file.length() > MAX_SNIFF_BYTES || measured >= lineLimit) {
                out.append("not indexed\n")
            } else {
                val lines = try { file.useLines { it.count() } } catch (_: Exception) { 0 }
                out.append(lines).append(" lines\n")
                measured++
            }
            count++
        }
        return out.toString().trimEnd()
    }

    /** Cheap per-file detail for the confirmation overlay: "N lines" / "large" /
     *  or "new file" when the target does not exist yet. */
    fun targetDetail(root: File, rel: String): String {
        if (rel.isEmpty()) return ""
        val file = File(root, rel)
        if (!file.exists() || !file.isFile) return "new file"
        return if (file.length() > MAX_SNIFF_BYTES) "large file" else
            try { "${file.useLines { it.count() }} lines" } catch (_: Exception) { "n lines" }
    }

    fun relativePath(root: File, file: File): String {
        val rootPath = root.absolutePath.trimEnd(File.separatorChar) + File.separatorChar
        val abs = file.absolutePath
        return if (abs.startsWith(rootPath)) {
            abs.substring(rootPath.length).replace(File.separatorChar, '/')
        } else {
            abs.replace(File.separatorChar, '/')
        }
    }

    /**
     * Per-file top-level symbols with line numbers (via [OutlineExtractor])
     * plus line counts, capped per file, for post-write verification.
     */
    fun outlineFor(root: File, paths: List<String>, maxLinesPerFile: Int = 12): String {
        val out = StringBuilder()
        for (rel in paths) {
            if (rel.isEmpty()) continue
            val file = File(root, rel)
            if (!file.exists() || !file.isFile) continue
            val lines = try { file.useLines { it.count() } } catch (_: Exception) { 0 }
            out.append(rel).append(" | ").append(lines).append(" lines\n")
            val outline = OutlineExtractor.generateOutline(file)
            val kept = outline.lineSequence()
                .map { it.trimStart() }
                .filter { it.startsWith("L") }
                .take(maxLinesPerFile)
                .toList()
            if (kept.isNotEmpty()) {
                kept.forEach { out.append("  ").append(it).append("\n") }
            } else {
                out.append("  (no top-level symbols detected)\n")
            }
        }
        return out.toString().trimEnd()
    }
}