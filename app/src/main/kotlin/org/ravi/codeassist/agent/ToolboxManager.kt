package org.ravi.codeassist.agent

import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class AgentTool(
    val id: String,
    val iconResId: Int,
    val title: String,
    val description: String,
    val onExecute: () -> Unit
)

object ToolboxManager {
    private val tools = mutableListOf<AgentTool>()

    init {
        registerTool(
            AgentTool(
                id = "resume_session",
                iconResId = android.R.drawable.ic_media_play,
                title = "Resume Session",
                description = "Resume generation if active, or sync screen state.",
                onExecute = {
                    org.ravi.codeassist.AgentAccessibilityService.instance?.resumeOrSync()
                }
            )
        )
        registerTool(
            AgentTool(
                id = "init_workspace",
                iconResId = android.R.drawable.ic_menu_add,
                title = "Init Workspace",
                description = "Scaffold CodeAssist.md and start project analysis.",
                onExecute = {
                    val service = org.ravi.codeassist.AgentAccessibilityService.instance
                    val prefs = service?.getSharedPreferences("CodeAssistPrefs", android.content.Context.MODE_PRIVATE)
                    val root = prefs?.getString("WORKSPACE_ROOT", null)
                    
                    if (service != null && root != null) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            android.widget.Toast.makeText(service, "Initializing Workspace...", android.widget.Toast.LENGTH_SHORT).show()
                            val file = java.io.File(root, "CodeAssist.md")
                            if (!file.exists()) {
                                file.writeText("# Project Overview\n\n- Project Name: " + root.substringAfterLast("/") + "\n- Tech Stack: Android/Kotlin\n- Architecture: [TBD]\n\n## Insights\n[To be populated by AI]")
                            }
                            android.widget.Toast.makeText(service, "CodeAssist.md created. Analyzing...", android.widget.Toast.LENGTH_LONG).show()
                            org.ravi.codeassist.agent.AgentOrchestrator.startLoop("Read CodeAssist.md in the root directory. Analyze the project structure, explore the codebase, and update CodeAssist.md with your findings and insights. Be concise.")
                        }
                    } else {
                        service?.let { 
                            android.widget.Toast.makeText(it, "No active workspace root found.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        )
        registerTool(
            AgentTool(
                id = "new_session",
                iconResId = android.R.drawable.ic_menu_revert,
                title = "New Session",
                description = "Clear history and prepare for a fresh prompt.",
                onExecute = {
                    org.ravi.codeassist.agent.AgentOrchestrator.resetSession()
                    org.ravi.codeassist.AgentAccessibilityService.instance?.let {
                        android.widget.Toast.makeText(it, "Session history cleared.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        )
        registerTool(
            AgentTool(
                id = "configure_scroll_zone",
                iconResId = android.R.drawable.ic_menu_crop,
                title = "Set Scroll Zone",
                description = "Define relative boundaries for page-up/down scrolling windows.",
                onExecute = {
                    val service = org.ravi.codeassist.AgentAccessibilityService.instance
                    val currentProfile = org.ravi.codeassist.agent.AgentOrchestrator.getActiveProfile()
                    if (service != null && currentProfile != null) {
                        org.ravi.codeassist.agent.AgentOrchestrator.updateState(org.ravi.codeassist.agent.AgentState.SCROLL_CONFIG_ACTIVE)
                        service.updateOverlayStatus("Setting scroll zone...")
                        
                        service.openScrollZonePickerOverlay(
                            profile = currentProfile,
                            onSaveCompleted = { left, top, right, bottom ->
                                org.ravi.codeassist.agent.AgentOrchestrator.updateState(org.ravi.codeassist.agent.AgentState.IDLE)
                                service.updateOverlayStatus("Scroll zone saved")
                                android.widget.Toast.makeText(service, "Scroll boundaries applied!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        service?.let {
                            android.widget.Toast.makeText(it, "Please activate an agent profile first.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        )
    }

    fun registerTool(tool: AgentTool) {
        if (tools.none { it.id == tool.id }) {
            tools.add(tool)
        }
    }

    fun showMenu(context: Context) {
        if (tools.isEmpty()) {
            Toast.makeText(context, "No tools registered yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val themedContext = android.view.ContextThemeWrapper(context, org.ravi.codeassist.R.style.Theme_CodeAssist)
        val dialogView = android.view.LayoutInflater.from(themedContext).inflate(org.ravi.codeassist.R.layout.layout_toolbox_menu, null)
        val container = dialogView.findViewById<android.widget.LinearLayout>(org.ravi.codeassist.R.id.llToolsContainer)
        val btnClose = dialogView.findViewById<android.view.View>(org.ravi.codeassist.R.id.btnCloseToolbox)
        
        val builder = android.app.AlertDialog.Builder(themedContext)
        val dialog = builder.setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

        btnClose?.setOnClickListener { dialog.dismiss() }

        tools.forEach { tool ->
            val itemView = android.view.LayoutInflater.from(themedContext).inflate(org.ravi.codeassist.R.layout.item_toolbox_tool, container, false)
            val ivIcon = itemView.findViewById<android.widget.ImageView>(org.ravi.codeassist.R.id.ivToolIcon)
            val tvTitle = itemView.findViewById<android.widget.TextView>(org.ravi.codeassist.R.id.tvToolTitle)
            val tvDesc = itemView.findViewById<android.widget.TextView>(org.ravi.codeassist.R.id.tvToolDesc)

            ivIcon.setImageResource(tool.iconResId)
            tvTitle.text = tool.title
            tvDesc.text = tool.description

            itemView.setOnClickListener {
                tool.onExecute()
                dialog.dismiss()
            }
            container.addView(itemView)
        }

        dialog.show()
    }
}