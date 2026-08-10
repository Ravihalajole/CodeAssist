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
                    is CodeCommand.AskUser -> "Message: ${command.message}"
                    is CodeCommand.Done -> "Message: ${command.message}"
                }
                appendLine("${index + 1}. [$actionName] -> $targetPath")
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
                htmlBuilder.append("<font color='#777777'><i>[... Collapsed ${prefixCount - 3} identical context lines ...]</i></font><br>")
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
            htmlBuilder.append("<font color='#E57373'>")
            for (i in prefixCount until (searchLines.size - suffixCount)) {
                htmlBuilder.append("- ").append(escapeHtmlString(searchLines[i])).append("<br>")
            }
            htmlBuilder.append("</font>")
        }

        if (prefixCount < replaceLines.size - suffixCount) {
            htmlBuilder.append("<font color='#81C784'>")
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
                htmlBuilder.append("<font color='#777777'><i>[... Collapsed ${suffixCount - 3} identical context lines ...]</i></font><br>")
            } else {
                for (i in suffixStart until searchLines.size) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
            }
        }

        return htmlBuilder.toString()
    }

    private fun escapeHtmlString(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(" ", "&nbsp;")
            .replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")
    }
}