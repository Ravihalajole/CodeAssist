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
        val btnCopyError = view.findViewById<MaterialButton>(R.id.btnCopyError)

        // Run pre-flight validation
        val errors = mutableListOf<String>()
        commands.forEach { cmd ->
            val err = CommandExecutor.validate(cmd, workspaceRoot)
            if (err != null) errors.add(err)
        }

        val hasErrors = errors.isNotEmpty()

        if (hasErrors) {
            tvTitle?.text = "Pre-flight Validation Failed"
            btnApprove.visibility = View.GONE
            btnCopyError.visibility = View.VISIBLE
            
            btnCopyError.setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CodeAssist Error", "Validation Errors:\n\n" + errors.joinToString("\n\n"))
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Errors copied to clipboard!", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
                dismiss()
            }
        }

        // Dynamically add a View Diffs button if there are PatchFile commands
        val patchCommands = commands.filterIsInstance<CodeCommand.PatchFile>()
        if (patchCommands.isNotEmpty()) {
            val rootLayout = view as LinearLayout
            val scrollView = tvSummary.parent as View

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
            rootLayout.addView(btnViewDiffs, rootLayout.indexOfChild(scrollView) + 1)
        }

        // Build a readable summary of what the LLM wants to do
        if (hasErrors) {
            val errorSummary = java.lang.StringBuilder("The following commands will fail:\n\n")
            errors.forEachIndexed { index, err ->
                errorSummary.append("${index + 1}. $err\n\n")
            }
            tvSummary.text = errorSummary.toString().trim()
            tvSummary.setTextColor(android.graphics.Color.parseColor("#F44336")) // Red color
        } else {
            val summaryText = java.lang.StringBuilder()
            commands.forEachIndexed { index, command ->
                val actionName = command.javaClass.simpleName
                val targetPath = when (command) {
                    is CodeCommand.CreateFile -> command.path
                    is CodeCommand.PatchFile -> command.path
                    is CodeCommand.DeleteFile -> command.path
                    is CodeCommand.ReadFile -> command.path
                    is CodeCommand.GrepFile -> command.path
                    is CodeCommand.ListDir -> command.path
                }
                summaryText.append("${index + 1}. [$actionName]\n   └─ $targetPath\n\n")
            }
            tvSummary.text = summaryText.toString().trim()
        }

        btnApprove.setOnClickListener {
            onApprove()
            dismiss()
        }

        btnReject.setOnClickListener {
            onReject()
            dismiss()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // Treat tapping outside the sheet as a rejection
        onReject()
    }

    private fun showDiffsDialog(patchCommands: List<CodeCommand.PatchFile>) {
        val sb = java.lang.StringBuilder()
        patchCommands.forEach { cmd ->
            sb.append("<b>File: ${cmd.path}</b><br><br>")
            
            // Format SEARCH block (Red + Strikethrough)
            val formattedSearch = cmd.search.replace("\n", "<br>").replace(" ", "&nbsp;")
            sb.append("<font color='#F44336'><del>$formattedSearch</del></font><br>")
            
            // Format REPLACE block (Green)
            val formattedReplace = cmd.replace.replace("\n", "<br>").replace(" ", "&nbsp;")
            sb.append("<font color='#4CAF50'>$formattedReplace</font><br><br><hr><br>")
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
