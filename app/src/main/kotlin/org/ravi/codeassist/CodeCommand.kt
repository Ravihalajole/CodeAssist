package org.ravi.codeassist

sealed class CodeCommand {
    data class GrepFile(val path: String, val pattern: String) : CodeCommand()
    data class ReadFile(val path: String) : CodeCommand()
    data class ListDir(val path: String) : CodeCommand()
    data class CreateFile(val path: String, val content: String) : CodeCommand()
    data class PatchFile(val path: String, val search: String, val replace: String) : CodeCommand()
    data class DeleteFile(val path: String) : CodeCommand()
}

enum class ParseState {
    IDLE,
    IN_ENVELOPE,
    IN_CONTENT,
    IN_SEARCH,
    IN_REPLACE
}
