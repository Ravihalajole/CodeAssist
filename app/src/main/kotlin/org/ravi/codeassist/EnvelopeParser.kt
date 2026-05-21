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
        
        // Dynamic content buffers
        val contentBuffer = StringBuilder()
        val searchBuffer = StringBuilder()
        val replaceBuffer = StringBuilder()

        for (line in lines) {
            val trimmedLine = line.trim()

            when (currentState) {
                ParseState.IDLE -> {
                    if (trimmedLine == ":::CODE_ASSIST:::") {
                        currentState = ParseState.IN_ENVELOPE
                    }
                }

                ParseState.IN_ENVELOPE -> {
                    when {
                        trimmedLine == ":::END_CODE_ASSIST:::" -> {
                            // Flush out the final command if one was pending
                            buildPendingCommand(currentCommandName, currentPath, currentPattern, currentMessage, contentBuffer, searchBuffer, replaceBuffer)?.let {
                                commands.add(it)
                            }
                            currentState = ParseState.IDLE
                        }
                        trimmedLine.startsWith("[COMMAND:") -> {
                            // Flush out previous command block if existing before moving to next command
                            buildPendingCommand(currentCommandName, currentPath, currentPattern, currentMessage, contentBuffer, searchBuffer, replaceBuffer)?.let {
                                commands.add(it)
                            }
                            // Reset state parameters for the new command
                            currentCommandName = trimmedLine.substringAfter("[COMMAND:").substringBefore("]").trim().uppercase(Locale.US)
                            currentPath = ""
                            currentPattern = ""
                            currentMessage = ""
                            contentBuffer.setLength(0)
                            searchBuffer.setLength(0)
                            replaceBuffer.setLength(0)
                        }
                        trimmedLine.startsWith("[MESSAGE:") -> {
                            currentMessage = trimmedLine.substringAfter("[MESSAGE:").substringBefore("]").trim()
                        }
                        trimmedLine.startsWith("[PATH:") -> {
                            currentPath = trimmedLine.substringAfter("[PATH:").substringBefore("]").trim()
                        }
                        trimmedLine.startsWith("[PATTERN:") -> {
                            currentPattern = trimmedLine.substringAfter("[PATTERN:").substringBefore("]").trim()
                        }
                        trimmedLine == "[CONTENT]" -> {
                            currentState = ParseState.IN_CONTENT
                        }
                        trimmedLine == "<<<<<<< SEARCH" -> {
                            currentState = ParseState.IN_SEARCH 
                        }
                    }
                }

                ParseState.IN_CONTENT -> {
                    if (trimmedLine == "[END_CONTENT]") {
                        currentState = ParseState.IN_ENVELOPE
                    } else {
                        // Use original line to preserve raw indentation and whitespace
                        contentBuffer.append(line).append("\n")
                    }
                }

                ParseState.IN_SEARCH -> {
                    if (trimmedLine == "=======") {
                        currentState = ParseState.IN_REPLACE
                    } else {
                        searchBuffer.append(line).append("\n")
                    }
                }

                ParseState.IN_REPLACE -> {
                    if (trimmedLine == ">>>>>>> REPLACE") {
                        currentState = ParseState.IN_ENVELOPE
                    } else {
                        replaceBuffer.append(line).append("\n")
                    }
                }
            }
        }
        return commands
    }

    private fun buildPendingCommand(
        name: String,
        path: String,
        pattern: String,
        message: String,
        content: StringBuilder,
        search: StringBuilder,
        replace: StringBuilder
    ): CodeCommand? {
        if (name.isEmpty()) return null
        if (name != "COMMIT_MESSAGE" && path.isEmpty()) return null

        return try {
            when (name) {
                "COMMIT_MESSAGE" -> CodeCommand.CommitMessage(message.ifEmpty { "Automated Update" })
                "GREP_FILE" -> CodeCommand.GrepFile(path, pattern)
                "READ_FILE" -> CodeCommand.ReadFile(path)
                "LIST_DIR" -> CodeCommand.ListDir(path)
                "CREATE_FILE" -> CodeCommand.CreateFile(path, content.toString().removeSuffix("\n"))
                "PATCH_FILE" -> CodeCommand.PatchFile(path, search.toString().removeSuffix("\n"), replace.toString().removeSuffix("\n"))
                "DELETE_FILE" -> CodeCommand.DeleteFile(path)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
