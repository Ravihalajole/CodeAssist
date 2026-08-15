package org.ravi.codeassist.utils

import org.ravi.codeassist.CodeCommand

/**
 * Structural (schema) validation for parsed commands, checked before any
 * filesystem validation so malformed commands fail fast with a precise,
 * actionable message instead of a confusing "file not found". Path-specific
 * policy and existence checks are handled by CommandExecutor.
 */
object CommandValidator {

    fun schemaError(command: CodeCommand): String? = when (command) {
        is CodeCommand.Patch -> when {
            command.path.isBlank() -> "PATCH requires [PATH: ...]."
            command.search.isBlank() -> "PATCH requires a non-empty SEARCH block."
            command.replace.isBlank() && !command.replaceAll -> "PATCH requires a non-empty REPLACE block."
            else -> null
        }
        is CodeCommand.Create -> when {
            command.path.isBlank() -> "CREATE requires [PATH: ...]."
            command.content.isBlank() -> "CREATE requires a non-empty [CONTENT] block."
            else -> null
        }
        is CodeCommand.Delete -> if (command.path.isBlank()) "DELETE requires [PATH: ...]." else null
        is CodeCommand.Move -> when {
            command.oldPath.isBlank() -> "MOVE requires [PATH: source]."
            command.newPath.isBlank() -> "MOVE requires [DESTINATION: target]."
            else -> null
        }
        is CodeCommand.Read -> if (command.path.isBlank()) "READ requires [PATH: ...]." else null
        is CodeCommand.Outline -> if (command.path.isBlank()) "OUTLINE requires [PATH: ...]." else null
        is CodeCommand.Grep -> if (command.pattern.isBlank()) "GREP requires [PATTERN: ...]." else null
        is CodeCommand.Glob -> if (command.pattern.isBlank()) "GLOB requires [PATTERN: ...]." else null
        else -> null
    }
}
