package org.ravi.codeassist

enum class AutoAllowMode(val displayName: String) {
    NONE("None (Always Ask)"),
    READ_ONLY("Read-Only Operations"),
    READ_WRITE("Read & Write Operations")
}

sealed class CodeCommand {
    abstract val isMutating: Boolean

    /**
     * Destructive mutations (DELETE, MOVE) always require human confirmation,
     * regardless of the configured AutoAllowMode. Non-destructive mutations
     * (PATCH, CREATE) may be auto-approved under READ_WRITE.
     */
    open val isDestructive: Boolean = false

    data class Read(val path: String, val startLine: Int? = null, val endLine: Int? = null) : CodeCommand() { override val isMutating = false }
    data class Grep(val path: String, val pattern: String, val ignoreDirs: List<String> = emptyList()) : CodeCommand() { override val isMutating = false }
    data class Patch(val path: String, val search: String, val replace: String, val replaceAll: Boolean = false, val context: String = "") : CodeCommand() { override val isMutating = true }
    data class Create(val path: String, val content: String, val context: String = "") : CodeCommand() { override val isMutating = true }
    data class Delete(val path: String, val context: String = "") : CodeCommand() { override val isMutating = true; override val isDestructive = true }
    data class Move(val oldPath: String, val newPath: String, val context: String = "") : CodeCommand() { override val isMutating = true; override val isDestructive = true }
    data class Glob(val pattern: String) : CodeCommand() { override val isMutating = false }
    data class Outline(val path: String) : CodeCommand() { override val isMutating = false }

    /**
     * Agent-side plan/task tracking. Declaring a new checklist (via `[CONTENT]`)
     * REPLACES the stored plan; `doneNumbers` mark 1-based items complete on the
     * active plan; `note` records the latest progress note. Fully non-mutating:
     * the backing store lives in [org.ravi.codeassist.agent.AgentOrchestrator]
     * and is re-attached to every feedback round.
     */
    data class Plan(
        val tasks: List<String>,
        val doneNumbers: List<Int> = emptyList(),
        val note: String = ""
    ) : CodeCommand() { override val isMutating = false }
    data class AskUser(val message: String) : CodeCommand() { override val isMutating = false }
    data class Done(val message: String) : CodeCommand() { override val isMutating = false }
}

enum class ParseState {
    IDLE,
    IN_ENVELOPE,
    IN_CONTENT,
    IN_SEARCH,
    IN_REPLACE
}