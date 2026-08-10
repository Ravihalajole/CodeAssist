package org.ravi.codeassist.agent

sealed class AgentState {
    object IDLE : AgentState()
    object ANALYZING_SCREEN : AgentState()
    object AWAITING_LLM : AgentState()
    data class EXECUTING_ACTION(val actionName: String) : AgentState()
    object WAITING_FOR_MUTATION : AgentState()
    object WAITING_FOR_USER : AgentState()
    data class ERROR(val message: String) : AgentState()
    object TOOLBOX_OPEN : AgentState()
    object SCROLL_CONFIG_ACTIVE : AgentState()
}