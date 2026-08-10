package org.ravi.codeassist.database

data class AgentProfile(
    val id: Long = 0,
    val profileName: String,
    val packageName: String,
    val isCalibrated: Boolean = false,
    val scrollLeftPct: Float = 0.35f,
    val scrollTopPct: Float = 0.35f,
    val scrollRightPct: Float = 0.65f,
    val scrollBottomPct: Float = 0.65f
)