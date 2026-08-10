package org.ravi.codeassist.ui

import org.ravi.codeassist.CodeCommand

object TransactionSummaryController {

    fun generateSummaryText(commands: List<CodeCommand>): String {
        return buildString {
            commands.forEachIndexed { index, command ->
                val actionName = command.javaClass.simpleName
                val targetPath = when (command) {
                    is CodeCommand.Read -> command.path
                    is CodeCommand.Grep -> command.path
                    is CodeCommand.Patch -> command.path
                    is CodeCommand.Create -> command.path
                    is CodeCommand.Delete -> command.path
                    is CodeCommand.Move -> "${command.oldPath} -> ${command.newPath}"
                    is CodeCommand.Glob -> command.pattern
                    is CodeCommand.Outline -> command.path
                    is CodeCommand.Plan -> generatePlanSummary(command)
                    is CodeCommand.AskUser -> "Message: ${command.message}"
                    is CodeCommand.Done -> "Message: ${command.message}"
                }
                appendLine("${index + 1}. [$actionName] -> $targetPath")
            }
        }
    }

    fun generateImpactPreview(commands: List<CodeCommand>, workspaceRoot: String): String {
        val root = java.io.File(workspaceRoot)
        return buildString {
            commands.forEachIndexed { index, command ->
                val target = when (command) {
                    is CodeCommand.Patch -> "${command.path}  (patch ${command.search.count { it == '\n' } + 1}→${command.replace.count { it == '\n' } + 1} lines)"
                    is CodeCommand.Create -> "${command.path}  (new)"
                    is CodeCommand.Delete -> "${command.path}  (${org.ravi.codeassist.utils.WorkspaceScope.targetDetail(root, command.path)}, will be deleted)"
                    is CodeCommand.Move -> "${command.oldPath}  →  ${command.newPath}"
                    is CodeCommand.Read -> "read ${command.path}"
                    is CodeCommand.Grep -> "grep ${command.pattern} in ${command.path}"
                    is CodeCommand.Glob -> "glob ${command.pattern}"
                    is CodeCommand.Outline -> "outline ${command.path}"
                    else -> command.javaClass.simpleName
                }
                val current = if (command is CodeCommand.Patch) "  [${org.ravi.codeassist.utils.WorkspaceScope.targetDetail(root, command.path)}]" else ""
                appendLine("${index + 1}. $target$current")
            }
        }
    }

    fun generateSmartDiffHtml(search: String, replace: String): String {
        val searchLines = search.split("\n")
        val replaceLines = replace.split("\n")
        val htmlBuilder = java.lang.StringBuilder()

        var prefixCount = 0
        while (prefixCount < searchLines.size && prefixCount < replaceLines.size && searchLines[prefixCount] == replaceLines[prefixCount]) {
            prefixCount++
        }

        var suffixCount = 0
        while (suffixCount < (searchLines.size - prefixCount) && suffixCount < (replaceLines.size - prefixCount) && 
               searchLines[searchLines.size - 1 - suffixCount] == replaceLines[replaceLines.size - 1 - suffixCount]) {
            suffixCount++
        }

        if (prefixCount > 0) {
            if (prefixCount > 5) {
                htmlBuilder.append("<font color='#62626C'><i>[... Collapsed ${prefixCount - 3} identical context lines ...]</i></font><br>")
                for (i in (prefixCount - 3) until prefixCount) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
            } else {
                for (i in 0 until prefixCount) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
            }
        }

        if (prefixCount < searchLines.size - suffixCount) {
            htmlBuilder.append("<font color='#FF4D5A'>")
            for (i in prefixCount until (searchLines.size - suffixCount)) {
                htmlBuilder.append("- ").append(escapeHtmlString(searchLines[i])).append("<br>")
            }
            htmlBuilder.append("</font>")
        }

        if (prefixCount < replaceLines.size - suffixCount) {
            htmlBuilder.append("<font color='#34E0A1'>")
            for (i in prefixCount until (replaceLines.size - suffixCount)) {
                htmlBuilder.append("+ ").append(escapeHtmlString(replaceLines[i])).append("<br>")
            }
            htmlBuilder.append("</font>")
        }

        if (suffixCount > 0) {
            val suffixStart = searchLines.size - suffixCount
            if (suffixCount > 5) {
                for (i in suffixStart until (suffixStart + 3)) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
                htmlBuilder.append("<font color='#62626C'><i>[... Collapsed ${suffixCount - 3} identical context lines ...]</i></font><br>")
            } else {
                for (i in suffixStart until searchLines.size) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
            }
        }

        return htmlBuilder.toString()
    }

    fun generatePlanSummary(command: CodeCommand.Plan): String = when {
        command.tasks.isNotEmpty() -> "Plan (${command.tasks.size} tasks, ${command.doneNumbers.size} done)"
        command.doneNumbers.isNotEmpty() -> "Plan progress: done ${command.doneNumbers.joinToString(",")}"
        command.note.isNotBlank() -> "Plan note: ${command.note.take(80)}"
        else -> "Plan sync"
    }

    private fun escapeHtmlString(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(" ", "&nbsp;")
            .replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")
    }
}