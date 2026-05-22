package org.ravi.codeassist

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

class ConfirmationBottomSheet(
    private val commands: List<CodeCommand>,
    private val workspaceRoot: String,
    private val onApprove: () -> Unit,
    private val onReject: () -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_confirmation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val tvSummary = view.findViewById<TextView>(R.id.tvCommandSummary)
        val btnApprove = view.findViewById<MaterialButton>(R.id.btnApprove)
        val btnReject = view.findViewById<MaterialButton>(R.id.btnReject)
        val executionProgress = view.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.executionProgress)
        val btnCopyError = view.findViewById<MaterialButton>(R.id.btnCopyError)

        val errors = commands.mapNotNull { CommandExecutor.validate(it, workspaceRoot) }
        val hasErrors = errors.isNotEmpty()

        if (hasErrors) {
            tvTitle?.text = "Pre-flight Validation Failed"
            btnApprove.visibility = View.GONE
            btnCopyError.visibility = View.VISIBLE
            
            btnCopyError.setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val errorPayload = buildModelFriendlyErrorPayload(errors, workspaceRoot)
                val clip = ClipData.newPlainText("CodeAssist Error", errorPayload)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Structured model diagnostics copied!", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
                dismiss()
            }
        }

        // Dynamically add a View Diffs button if there are PatchFile commands
        val patchCommands = commands.filterIsInstance<CodeCommand.PatchFile>()
        if (patchCommands.isNotEmpty()) {
            val rootLayout = view as LinearLayout
            val cardSummary = view.findViewById<View>(R.id.cardSummary)

            val btnViewDiffs = MaterialButton(
                requireContext(), 
                null, 
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "View Diffs (${patchCommands.size})"
                setOnClickListener { showDiffsDialog(patchCommands) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 16
                    bottomMargin = 16
                }
            }
            if (cardSummary != null) {
                rootLayout.addView(btnViewDiffs, rootLayout.indexOfChild(cardSummary) + 1)
            } else {
                rootLayout.addView(btnViewDiffs, 1)
            }
        }

        // Build a readable summary of what the LLM wants to do
        if (hasErrors) {
            val errorSummary = buildString {
                appendLine("The following commands will fail:\n")
                errors.forEachIndexed { index, err -> appendLine("${index + 1}. $err\n") }
            }
            tvSummary.text = errorSummary.trim()
            tvSummary.setTextColor(android.graphics.Color.parseColor("#F44336"))
        } else {
            val summaryText = buildString {
                commands.forEachIndexed { index, command ->
                    val actionName = command.javaClass.simpleName
                    val targetPath = when (command) {
                        is CodeCommand.CreateFile -> command.path
                        is CodeCommand.PatchFile -> command.path
                        is CodeCommand.DeleteFile -> command.path
                        is CodeCommand.ReadFile -> command.path
                        is CodeCommand.GrepFile -> command.path
                        is CodeCommand.ListDir -> command.path
                        is CodeCommand.CommitMessage -> "Message: ${command.message}"
                    }
                    appendLine("${index + 1}. [$actionName]\n   └─ $targetPath\n")
                }
            }
            tvSummary.text = summaryText.trim()
        }

        btnApprove.setOnClickListener {
            // Lock the UI into a loading state
            btnApprove.isEnabled = false
            btnReject.isEnabled = false
            executionProgress.visibility = View.VISIBLE
            btnApprove.text = "Executing Batch..."
            tvTitle?.text = "Applying Changes..."
            
            // Trigger the background execution
            onApprove()
            
            // Note: We intentionally DO NOT call dismiss() here. 
            // The sheet will stay visible until ClipboardActivity finishes its IO job and destroys itself.
        }

        btnReject.setOnClickListener {
            onReject()
            dismiss()
        }
    }

    private fun buildModelFriendlyErrorPayload(errors: List<String>, workspaceRoot: String): String {
        return buildString {
            appendLine(":::CODE_ASSIST_TRANSACTION_ERROR:::")
            appendLine("STATUS: TRANSACTION_ABORTED_AND_ROLLED_BACK")
            appendLine("WORKSPACE_ROOT: $workspaceRoot")
            appendLine("TOTAL_VERIFICATION_FAILURES: ${errors.size}")
            appendLine("\n--- CRITICAL FAILURE DETAILS ---")
            errors.forEachIndexed { index, error ->
                appendLine("FAILURE #${index + 1}:")
                appendLine("  DETAILS: $error")
                
                when {
                    error.contains("appears 0 times") || error.contains("0 times") -> {
                        appendLine("  INFERRED CAUSE: The targeted SEARCH code block could not be located in the destination file. This happens if the text changed, or if there is a hidden formatting, line-ending, or trailing whitespace mismatch.")
                        appendLine("  REQUIRED MODEL ACTION: Read the latest file state, double-check your search lines line-by-line, ensure all brackets match perfectly, and regenerate the exact block.")
                    }
                    error.contains("appears multiple times") || error.contains("times") -> {
                        appendLine("  INFERRED CAUSE: The provided SEARCH block is non-unique and matches multiple code points in the source file.")
                        appendLine("  REQUIRED MODEL ACTION: Expand your SEARCH context block by including 3-5 more lines above or below the target code block to anchor it into a completely unique signature.")
                    }
                    error.contains("does not exist") -> {
                        appendLine("  INFERRED CAUSE: Attempted compilation, mutation, or execution on an unallocated file path.")
                        appendLine("  REQUIRED MODEL ACTION: Verify the package name and directory tree. If you are creating a new component, ensure you emit a CreateFile command block prior to trying to modify it.")
                    }
                    else -> {
                        appendLine("  REQUIRED MODEL ACTION: Re-verify system constraints, correct syntax errors in the modification sequence, and reconstruct the transactional envelope.")
                    }
                }
                appendLine()
            }
            appendLine("GLOBAL ATOMICITY PROTECTION NOTICE:")
            appendLine("  CONTEXT: CodeAssist strictly enforces absolute transactional safety boundaries. Because a single command failed validation, the entire batch operation was discarded simultaneously to protect your build tree from broken states.")
            appendLine("  INSTRUCTION: No file adjustments were committed to the physical drive. You must correct the errors listed above and re-emit the entire modified command block from scratch.")
            appendLine(":::END_TRANSACTION_ERROR:::")
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // Treat tapping outside the sheet as a rejection
        onReject()
    }

    private fun generateSmartDiffHtml(search: String, replace: String): String {
        val searchLines = search.split("\n")
        val replaceLines = replace.split("\n")
        val htmlBuilder = java.lang.StringBuilder()

        var prefixCount = 0
        while (prefixCount < searchLines.size && prefixCount < replaceLines.size && searchLines[prefixCount] == replaceLines[prefixCount]) {
            prefixCount++
        }

        var suffixCount = 0
        while (suffixCount < (searchLines.size - prefixCount) && suffixCount < (replaceLines.size - prefixCount) && 
               searchLines[searchLines.size - 1 - suffixCount] == replaceLines[replaceLines.size - 1 - suffixCount]) {
            suffixCount++
        }

        if (prefixCount > 0) {
            if (prefixCount > 5) {
                htmlBuilder.append("<font color='#777777'><i>[... Collapsed ${prefixCount - 3} identical context lines ...]</i></font><br>")
                for (i in (prefixCount - 3) until prefixCount) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
            } else {
                for (i in 0 until prefixCount) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
            }
        }

        if (prefixCount < searchLines.size - suffixCount) {
            htmlBuilder.append("<font color='#E57373'>")
            for (i in prefixCount until (searchLines.size - suffixCount)) {
                htmlBuilder.append("- ").append(escapeHtmlString(searchLines[i])).append("<br>")
            }
            htmlBuilder.append("</font>")
        }

        if (prefixCount < replaceLines.size - suffixCount) {
            htmlBuilder.append("<font color='#81C784'>")
            for (i in prefixCount until (replaceLines.size - suffixCount)) {
                htmlBuilder.append("+ ").append(escapeHtmlString(replaceLines[i])).append("<br>")
            }
            htmlBuilder.append("</font>")
        }

        if (suffixCount > 0) {
            val suffixStart = searchLines.size - suffixCount
            if (suffixCount > 5) {
                for (i in suffixStart until (suffixStart + 3)) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
                htmlBuilder.append("<font color='#777777'><i>[... Collapsed ${suffixCount - 3} identical context lines ...]</i></font><br>")
            } else {
                for (i in suffixStart until searchLines.size) {
                    htmlBuilder.append(escapeHtmlString(searchLines[i])).append("<br>")
                }
            }
        }

        return htmlBuilder.toString()
    }

    private fun escapeHtmlString(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(" ", "&nbsp;")
            .replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")
    }

    private fun showDiffsDialog(patchCommands: List<CodeCommand.PatchFile>) {
        val sb = java.lang.StringBuilder()
        patchCommands.forEach { cmd ->
            sb.append("<b>File: ${cmd.path}</b><br><br>")
            sb.append(generateSmartDiffHtml(cmd.search, cmd.replace))
            sb.append("<br><hr><br>")
        }
        
        val tv = TextView(requireContext()).apply {
            setPadding(48, 48, 48, 48)
            text = android.text.Html.fromHtml(sb.toString(), android.text.Html.FROM_HTML_MODE_LEGACY)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        
        val scrollView = ScrollView(requireContext()).apply { 
            addView(tv) 
        }
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pre-Flight Diff Viewer")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }
}
