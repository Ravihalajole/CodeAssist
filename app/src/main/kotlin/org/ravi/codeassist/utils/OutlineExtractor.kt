package org.ravi.codeassist.utils

import java.io.File

object OutlineExtractor {
    fun generateOutline(file: File): String {
        val extension = file.extension.lowercase()
        val outline = java.lang.StringBuilder("--- Outline for ${file.name} ---\n")
        
        val rules = getRulesForExtension(extension)
        if (rules.isEmpty()) {
            return outline.append("No specific language rules found for .$extension. Showing top-level structure only.\n").toString()
        }

        file.useLines { lines ->
            lines.forEachIndexed { index, line ->
                for (rule in rules) {
                    if (rule.regex.containsMatchIn(line)) {
                        outline.append("${rule.indentPrefix}L${index + 1}: ${line.trim()}\n")
                        break // Apply only the first matching rule per line
                    }
                }
            }
        }
        return outline.toString()
    }

    private data class OutlineRule(val regex: Regex, val indentPrefix: String)

    private fun getRulesForExtension(extension: String): List<OutlineRule> {
        return when (extension) {
            "kt", "kts" -> listOf(
                OutlineRule(Regex("^\\s*(class|interface|object|enum\\s+class)\\s+([A-Za-z0-9_]+)"), ""),
                // Accept the full Kotlin modifier prefix set (public/private/internal/protected,
                // open/final/abstract/sealed, override/operator/infix/inline/tailrec/external/suspend,
                // const/lateinit) before `fun` or `val`/`var`. Previously only
                // (private|protected|internal)?(suspend)?fun was matched, so
                // `override fun`, `public inline fun <T> bar()`, `open suspend fun`,
                // and `operator fun` were all silently omitted from the outline.
                OutlineRule(Regex("^\\s*(?:(?:public|private|protected|internal|open|final|abstract|sealed|override|operator|infix|inline|external|tailrec|suspend|const|lateinit)\\s+)*fun\\s+([A-Za-z0-9_]+)"), "  "),
                OutlineRule(Regex("^\\s*(?:(?:public|private|protected|internal|open|final|abstract|override|const|lateinit)\\s+)*(val|var)\\s+([A-Za-z0-9_]+)"), "  ")
            )
            "java" -> listOf(
                OutlineRule(Regex("^\\s*(public|protected|private)?\\s*(static)?\\s*(final)?\\s*(class|interface|enum)\\s+([A-Za-z0-9_]+)"), ""),
                OutlineRule(Regex("^\\s*(public|protected|private)?\\s*(static)?\\s*(final)?\\s*([A-Za-z0-9_<>\\[\\]]+)\\s+([A-Za-z0-9_]+)\\s*\\("), "  ")
            )
            "py" -> listOf(
                OutlineRule(Regex("^\\s*class\\s+([A-Za-z0-9_]+)"), ""),
                OutlineRule(Regex("^\\s*def\\s+([A-Za-z0-9_]+)"), "  ")
            )
            "js", "ts", "jsx", "tsx" -> listOf(
                OutlineRule(Regex("^\\s*(export\\s+)?(default\\s+)?class\\s+([A-Za-z0-9_]+)"), ""),
                OutlineRule(Regex("^\\s*(export\\s+)?(default\\s+)?function\\s+([A-Za-z0-9_]+)"), "  "),
                OutlineRule(Regex("^\\s*(export\\s+)?(const|let|var)\\s+([A-Za-z0-9_]+)\\s*=\\s*(\\(.*\\)|async\\s*\\(.*\\))\\s*=>"), "  ")
            )
            "dart" -> listOf(
                OutlineRule(Regex("^\\s*(abstract\\s+)?class\\s+([A-Za-z0-9_]+)"), ""),
                OutlineRule(Regex("^\\s*([A-Za-z0-9_<>\\[\\]]+)\\s+([A-Za-z0-9_]+)\\s*\\("), "  ")
            )
            "cpp", "h", "hpp", "c" -> listOf(
                OutlineRule(Regex("^\\s*(class|struct)\\s+([A-Za-z0-9_]+)"), ""),
                OutlineRule(Regex("^\\s*([A-Za-z0-9_<>:]+)\\s+([A-Za-z0-9_:]+)\\s*\\("), "  ")
            )
            else -> emptyList()
        }
    }
}