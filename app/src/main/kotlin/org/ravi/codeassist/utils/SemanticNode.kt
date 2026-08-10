package org.ravi.codeassist.utils

data class SemanticNode(
    val nodeId: String,
    val type: String,
    val text: String?,
    val hint: String?,
    val isClickable: Boolean,
    val isEditable: Boolean
)