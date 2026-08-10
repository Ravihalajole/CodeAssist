package org.ravi.codeassist.database

data class ElementSignature(
    val id: Long = 0,
    val profileId: Long,
    val role: ElementRole,
    val resourceId: String?,
    val contentDescription: String?,
    val hintText: String?,
    val boundsX: Int,
    val boundsY: Int,
    val hierarchyPath: String?,
    val className: String?
)