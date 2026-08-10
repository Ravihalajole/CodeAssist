package org.ravi.codeassist

import java.util.Locale

object EnvelopeParser {

    fun parse(text: String): List<CodeCommand> {
        val commands = mutableListOf<CodeCommand>()
        val lines = text.lines()

        var currentState = ParseState.IDLE
        var currentCommandName = ""
        var currentPath = ""
        var currentPattern = ""
        var currentMessage = ""
        var currentReplaceAll = false
        var currentStartLine: Int? = null
        var currentEndLine: Int? = null
        var currentIgnoreDirs = emptyList<String>()
        var currentDestination = ""
        var currentContext = ""
        var pendingCommandClosed = false
        
        val contentBuffer = StringBuilder()
        val searchBuffer = StringBuilder()
        val replaceBuffer = StringBuilder()

        fun flushPendingCommand() {
            // Only emit if the command actually finished — i.e. we saw its proper
            // closing token. A truncated response (stream cut mid-SEARCH or
            // mid-CONTENT) lands here with pendingCommandClosed = false and is
            // dropped, preventing half-formed PATCH/CREATE commands from being
            // constructed and silently written to the workspace.
            if (!pendingCommandClosed) return
            buildPendingCommand(
                currentCommandName, currentPath, currentPattern, currentMessage, 
                currentReplaceAll, currentStartLine, currentEndLine, 
                currentIgnoreDirs, currentDestination, currentContext, contentBuffer, searchBuffer, replaceBuffer
            )?.let { commands.add(it) }
            pendingCommandClosed = false
        }

        for (line in lines) {
            val trimmedLine = line.trim()

            when (currentState) {
                ParseState.IDLE -> {
                    if (trimmedLine == ":::CODE_ASSIST:::") currentState = ParseState.IN_ENVELOPE
                }
                ParseState.IN_ENVELOPE -> {
                    when {
                        trimmedLine == ":::END_CODE_ASSIST:::" -> {
                            // Command was fully finished by the envelope close.
                            pendingCommandClosed = true
                            flushPendingCommand()
                            currentState = ParseState.IDLE
                        }
                        trimmedLine.startsWith("[COMMAND:") -> {
                            // A new [COMMAND:] line implies the previous command's
                            // attribute block ended here (READ/GREP/DELETE/etc.
                            // don't have a body closer). PATCH/CREATE that were
                            // still mid-body when a new command appeared were
                            // never closed; pendingCommandClosed stays false and
                            // flushPendingCommand() drops the half-formed command.
                            flushPendingCommand()
                            currentCommandName = trimmedLine.substringAfter("[COMMAND:").substringBefore("]").trim().uppercase(Locale.US)
                            currentPath = ""
                            currentPattern = ""
                            currentMessage = ""
                            currentReplaceAll = false
                            currentStartLine = null
                            currentEndLine = null
                            currentIgnoreDirs = emptyList()
                            currentDestination = ""
                            currentContext = ""
                            contentBuffer.setLength(0)
                            searchBuffer.setLength(0)
                            replaceBuffer.setLength(0)
                            // Default non-body commands to closed; PATCH/CREATE
                            // will flip back to false when they enter IN_SEARCH /
                            // IN_CONTENT, and flip to true on their closer.
                            pendingCommandClosed = true
                        }
                        trimmedLine.startsWith("[REPLACE_ALL:") -> currentReplaceAll = trimmedLine.substringAfter("[REPLACE_ALL:").substringBefore("]").trim().equals("true", ignoreCase = true)
                        trimmedLine.startsWith("[MESSAGE:") -> currentMessage = trimmedLine.substringAfter("[MESSAGE:").substringBefore("]").trim()
                        trimmedLine.startsWith("[CONTEXT:") -> currentContext = trimmedLine.substringAfter("[CONTEXT:").substringBefore("]").trim()
                        trimmedLine.startsWith("[PATH:") -> currentPath = trimmedLine.substringAfter("[PATH:").substringBefore("]").trim()
                        trimmedLine.startsWith("[DESTINATION:") -> currentDestination = trimmedLine.substringAfter("[DESTINATION:").substringBefore("]").trim()
                        trimmedLine.startsWith("[PATTERN:") -> currentPattern = trimmedLine.substringAfter("[PATTERN:").substringBefore("]").trim()
                        trimmedLine.startsWith("[START_LINE:") -> currentStartLine = trimmedLine.substringAfter("[START_LINE:").substringBefore("]").trim().toIntOrNull()
                        trimmedLine.startsWith("[END_LINE:") -> currentEndLine = trimmedLine.substringAfter("[END_LINE:").substringBefore("]").trim().toIntOrNull()
                        trimmedLine.startsWith("[IGNORE:") -> currentIgnoreDirs = trimmedLine.substringAfter("[IGNORE:").substringBefore("]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        trimmedLine.startsWith("[CONTENT]") -> {
                            // CREATE body started; not closed until [END_CONTENT].
                            pendingCommandClosed = false
                            currentState = ParseState.IN_CONTENT
                        }
                        trimmedLine.startsWith("<<<<<<< SEARCH") -> {
                            // PATCH body started; not closed until >>>>>>> REPLACE.
                            pendingCommandClosed = false
                            currentState = ParseState.IN_SEARCH 
                        }
                    }
                }
                ParseState.IN_CONTENT -> {
                    if (trimmedLine.startsWith("[END_CONTENT]")) {
                        // CREATE body fully received; OK to flush.
                        pendingCommandClosed = true
                        currentState = ParseState.IN_ENVELOPE
                    } else contentBuffer.append(line).append("\n")
                }
                ParseState.IN_SEARCH -> {
                    if (trimmedLine.startsWith("=======")) currentState = ParseState.IN_REPLACE
                    else searchBuffer.append(line).append("\n")
                }
                ParseState.IN_REPLACE -> {
                    if (trimmedLine.startsWith(">>>>>>> REPLACE")) {
                        // PATCH SEARCH/REPLACE block fully received.
                        pendingCommandClosed = true
                        flushPendingCommand()
                        searchBuffer.setLength(0)
                        replaceBuffer.setLength(0)
                        currentState = ParseState.IN_ENVELOPE
                    }
                    else replaceBuffer.append(line).append("\n")
                }
            }
        }
        // Tail flush at end-of-stream. flushPendingCommand() refuses to emit a
        // command that never saw its closing token (e.g. a PATCH truncated
        // mid-SEARCH/REPLACE or a CREATE truncated mid-CONTENT), so truncated
        // LLM output can no longer produce half-formed commands.
        flushPendingCommand()
        return commands
    }

    private fun buildPendingCommand(
        name: String, path: String, pattern: String, message: String, replaceAll: Boolean, 
        startLine: Int?, endLine: Int?, ignoreDirs: List<String>, destination: String, context: String,
        content: StringBuilder, search: StringBuilder, replace: StringBuilder
    ): CodeCommand? {
        if (name.isEmpty()) return null
        if (name == "PATCH" && search.isEmpty()) return null
        if (name != "DONE" && name != "ASK_USER" && name != "GLOB" && path.isEmpty()) {
            if (name == "GREP" && pattern.isNotEmpty()) {} else return null
        }

        return try {
            when (name) {
                "READ" -> CodeCommand.Read(path, startLine, endLine)
                "GREP" -> CodeCommand.Grep(path, pattern, ignoreDirs)
                "PATCH" -> CodeCommand.Patch(path, search.toString().removeSuffix("\n"), replace.toString().removeSuffix("\n"), replaceAll, context)
                "CREATE" -> CodeCommand.Create(path, content.toString().removeSuffix("\n"), context)
                "DELETE" -> CodeCommand.Delete(path, context)
                "MOVE" -> CodeCommand.Move(path, destination, context)
                "GLOB" -> CodeCommand.Glob(pattern)
                "OUTLINE" -> CodeCommand.Outline(path)
                "ASK_USER" -> CodeCommand.AskUser(message.ifEmpty { "Need clarification." })
                "DONE" -> CodeCommand.Done(message.ifEmpty { "Task completed autonomously." })
                else -> null
            }
        } catch (e: Exception) { null }
    }
}