package org.ravi.codeassist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ClipboardActivity : AppCompatActivity() {

    private var hasProcessed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GitManager.registerContext(this)
        CommandExecutor.registerContext(this)
    }

    // NOTE: onResume() intentionally does NOT reset hasProcessed. Earlier this
    // activity reset hasProcessed = false in onResume, so a transient focus
    // loss/gain (heads-up notification, IME flash, the bubble overlay briefly
    // grabbing focus) re-armed the guard and onWindowFocusChanged(true)
    // re-executed the SAME clipboard payload — applying the same mutations
    // twice. The flag is now set in onCreate() (one-shot) and never reset
    // for the lifetime of this activity instance, which is itself destroyed
    // as soon as it has dispatched a single payload (finish() at every exit).

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasProcessed) {
            hasProcessed = true
            executeClipboardAction()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Human-readable provenance for the clipboard item, used to warn the user
     * about envelopes placed by other apps. `ClipDescription.getPackageName()`
     * is a hidden/SystemApi method (absent from the public SDK), so the label
     * the source app set is used instead; it falls back to the sensitive flag.
     */
    private fun clipboardSourceLabel(clipboard: ClipboardManager): String {
        val description = clipboard.primaryClip?.description ?: return "Clipboard source: Unknown"
        val owner = description.label?.toString()?.takeIf { it.isNotBlank() }
            ?.let { "Clipboard source: $it" } ?: "Clipboard source: Unknown"
        val sensitive = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) ?: false
        } else {
            false
        }
        return if (sensitive) "$owner (marked sensitive)" else owner
    }

    private fun executeClipboardAction() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val textToParse = clipData.getItemAt(0).text?.toString()

            if (textToParse.isNullOrEmpty() || !textToParse.contains(":::CODE_ASSIST:::")) {
                Toast.makeText(this, "No valid CodeAssist envelope found.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            processClipboardPayload(textToParse, clipboard)
        } else {
            finish()
        }
    }

    private fun processClipboardPayload(payload: String, clipboard: ClipboardManager) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)

        if (workspaceRoot.isNullOrEmpty()) {
            Toast.makeText(this, "Error: Set workspace path first.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val commands = EnvelopeParser.parse(payload)
        
        if (commands.isEmpty()) {
            Toast.makeText(this, "Parsing error: No valid instructions.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val modeStr = sharedPref.getString("AUTO_ALLOW_MODE", org.ravi.codeassist.AutoAllowMode.READ_ONLY.name)
        val autoMode = try {
            org.ravi.codeassist.AutoAllowMode.valueOf(modeStr!!)
        } catch (e: Exception) {
            org.ravi.codeassist.AutoAllowMode.READ_ONLY
        }

        val hasMutating = commands.any { it.isMutating }
        val hasDestructive = commands.any { it.isDestructive }
        val gateArmed = hasMutating && org.ravi.codeassist.agent.AgentOrchestrator.isMutatingApprovalGateArmed()
        val requiresConfirmation = if (gateArmed) {
            // First mutating batch this session/process: always confirm, even
            // if READ_WRITE or "Allow for Session" is on. See AgentOrchestrator.
            true
        } else if (org.ravi.codeassist.agent.AgentOrchestrator.isSessionAutoAllowActive) {
            // Even with session auto-allow, destructive mutations (DELETE/MOVE)
            // always require human confirmation.
            hasDestructive
        } else {
            when (autoMode) {
                org.ravi.codeassist.AutoAllowMode.NONE -> true
                org.ravi.codeassist.AutoAllowMode.READ_ONLY -> hasMutating
                org.ravi.codeassist.AutoAllowMode.READ_WRITE -> hasDestructive
            }
        }

        if (!requiresConfirmation) {
            executeCommands(commands, workspaceRoot, clipboard, payload)
        } else {
            showConfirmationDialog(commands, workspaceRoot, clipboard, payload)
        }
    }

    private fun showConfirmationDialog(commands: List<CodeCommand>, workspaceRoot: String, clipboard: ClipboardManager, originalPayload: String) {
        // Any mutating batch passing through human review satisfies the
        // first-batch gate, so later batches respect the configured auto-allow.
        if (commands.any { it.isMutating }) {
            org.ravi.codeassist.agent.AgentOrchestrator.disarmMutatingApprovalGate()
        }
        val summaryText = org.ravi.codeassist.ui.TransactionSummaryController.generateSummaryText(commands)
        val patchCommands = commands.filterIsInstance<CodeCommand.Patch>()

        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(48, 48, 48, 48)
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val tvClipboardSource = android.widget.TextView(this).apply {
            text = clipboardSourceLabel(clipboard)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ClipboardActivity, R.color.state_red))
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }
        layout.addView(tvClipboardSource)

        val tvSummary = android.widget.TextView(this).apply {
            text = summaryText
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        layout.addView(tvSummary)

        if (patchCommands.isNotEmpty()) {
            val btnViewDiffs = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "View Diffs (${patchCommands.size})"
                setOnClickListener { showDiffsDialog(patchCommands) }
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 32
                    bottomMargin = 16
                }
            }
            layout.addView(btnViewDiffs)
        }
        
        scrollView.addView(layout)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Confirm Execution")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("Approve") { _, _ ->
                executeCommands(commands, workspaceRoot, clipboard, originalPayload)
            }
            .setNegativeButton("Reject") { _, _ ->
                Toast.makeText(this, "Execution Aborted.", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNeutralButton("Allow for Session") { _, _ ->
                org.ravi.codeassist.agent.AgentOrchestrator.isSessionAutoAllowActive = true
                Toast.makeText(this, "Auto-Approve Enabled for this Session.", Toast.LENGTH_SHORT).show()
                executeCommands(commands, workspaceRoot, clipboard, originalPayload)
            }
            .show()
    }

    private fun showDiffsDialog(patchCommands: List<CodeCommand.Patch>) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val workspaceRoot = sharedPref.getString("WORKSPACE_ROOT", null)
        val sb = java.lang.StringBuilder()
        patchCommands.forEach { cmd ->
            sb.append("<b>File: ${cmd.path}</b><br><br>")
            val preview = if (workspaceRoot != null) org.ravi.codeassist.CommandExecutor.previewPatch(workspaceRoot, cmd) else null
            sb.append(if (preview != null) {
                org.ravi.codeassist.ui.TransactionSummaryController.generateSmartDiffHtml(preview.first, preview.second)
            } else {
                org.ravi.codeassist.ui.TransactionSummaryController.generateSmartDiffHtml(cmd.search, cmd.replace)
            })
            sb.append("<br><hr><br>")
        }
        
        val tv = android.widget.TextView(this).apply {
            setPadding(48, 48, 48, 48)
            text = android.text.Html.fromHtml(sb.toString(), android.text.Html.FROM_HTML_MODE_LEGACY)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        
        val scrollView = android.widget.ScrollView(this).apply { 
            addView(tv) 
        }
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Pre-Flight Diff Viewer")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun executeCommands(commands: List<CodeCommand>, workspaceRoot: String, clipboard: ClipboardManager, originalPayload: String?) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CodeAssist::ExecutionLock")
        val wakeTimeoutMs = (2 * 60_000L + commands.size * 30_000L).coerceAtMost(15 * 60_000L)
        wakeLock.acquire(wakeTimeoutMs)

        // Detach execution from the Activity lifecycle to ensure transaction atomicity.
        // The IO job uses applicationContext so a finished (noHistory) activity is not
        // retained for the duration of the batch; the activity is only referenced
        // briefly inside the Main-thread result callback.
        val appContext = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = TransactionManager.executeBatch(appContext, commands, workspaceRoot)

                withContext(Dispatchers.Main) {
                    // Only write feedback back to the clipboard if the user hasn't
                    // copied something else while we were running. Results are
                    // always persisted to in-app execution history, so a skipped
                    // write never loses data.
                    val clipboardChanged = originalPayload != null &&
                        clipboard.primaryClip?.getItemAt(0)?.text?.toString() != originalPayload

                    if (result.success) {
                        if (!clipboardChanged && result.logs.isNotEmpty() && !result.logs.startsWith("Successfully executed")) {
                            val clip = ClipData.newPlainText("CodeAssist Result", result.logs)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                            }
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@ClipboardActivity, "Executed ops. Copied to clipboard!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@ClipboardActivity, "Executed ops successfully. View result in Execution History.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        if (clipboardChanged) {
                            Toast.makeText(this@ClipboardActivity, "Batch failed. Result preserved in Execution History.", Toast.LENGTH_LONG).show()
                        } else {
                            val clip = ClipData.newPlainText("CodeAssist Error", result.logs)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                            }
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@ClipboardActivity, "Batch Failed. Detailed error copied to clipboard.", Toast.LENGTH_LONG).show()
                        }
                    }
                    finish()
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

}
