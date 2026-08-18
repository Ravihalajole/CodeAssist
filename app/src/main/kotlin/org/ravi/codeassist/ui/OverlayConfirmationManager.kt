package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ravi.codeassist.CodeCommand
import org.ravi.codeassist.R

class OverlayConfirmationManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var confirmationView: View? = null
    private var diffView: View? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun show(commands: List<CodeCommand>, workspaceRoot: String, onResult: (Boolean, String) -> Unit) {
        if (confirmationView != null) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        confirmationView = inflater.inflate(R.layout.layout_overlay_confirmation, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.5f
            width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
        }

        val tvSummary = confirmationView?.findViewById<TextView>(R.id.tvOverlaySummary)
        val btnApprove = confirmationView?.findViewById<MaterialButton>(R.id.btnOverlayApprove)
        val btnReject = confirmationView?.findViewById<MaterialButton>(R.id.btnOverlayReject)
        val progress = confirmationView?.findViewById<View>(R.id.overlayProgress)

        val summaryText = TransactionSummaryController.generateSummaryText(commands).trim()
        val impactText = TransactionSummaryController.generateImpactPreview(commands, workspaceRoot).trim()
        tvSummary?.text = if (impactText.isEmpty()) {
            summaryText
        } else {
            "$summaryText\n\n── IMPACT ──\n$impactText"
        }

        val patchCommands = commands.filterIsInstance<CodeCommand.Patch>()

        val rootCard = btnApprove?.parent?.parent as? LinearLayout
        val innerCardHost = tvSummary?.parent?.parent as? View
        val buttonRow = btnApprove?.parent as? View

        if (rootCard != null) {
            if (patchCommands.isNotEmpty() && innerCardHost != null) {
                val btnViewDiffs = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "View Diffs (${patchCommands.size})"
                    setOnClickListener { showDiffsDialog(patchCommands, workspaceRoot) }
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 16
                    }
                }
                val cardIndex = rootCard.indexOfChild(innerCardHost)
                rootCard.addView(btnViewDiffs, if (cardIndex >= 0) cardIndex + 1 else rootCard.childCount)
            }

            if (buttonRow != null) {
                val btnSessionAllow = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Allow for Session"
                    setOnClickListener {
                        org.ravi.codeassist.agent.AgentOrchestrator.isSessionAutoAllowActive = true
                        android.widget.Toast.makeText(context, "Session Auto-Allow Enabled", android.widget.Toast.LENGTH_SHORT).show()
                        btnApprove?.isEnabled = false
                        btnReject?.isEnabled = false
                        isEnabled = false
                        progress?.visibility = View.VISIBLE
                        executeBatch(commands, workspaceRoot, onResult)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 8
                    }
                }
                val buttonRowIndex = rootCard.indexOfChild(buttonRow)
                rootCard.addView(btnSessionAllow, if (buttonRowIndex >= 0) buttonRowIndex else rootCard.childCount)
            }
        }

        btnReject?.setOnClickListener {
            dismiss()
            onResult(false, "The user rejected the proposed code changes. Please stop and ask the user what specific changes or corrections they would like you to make before trying again.")
        }

        val root = confirmationView?.findViewById<View>(R.id.confirmationRoot)
        root?.setOnClickListener {
            dismiss()
            onResult(false, "The user dismissed the confirmation dialog. Please stop and ask the user for clarification or instructions before trying again.")
        }

        val card = (root as? android.view.ViewGroup)?.getChildAt(0)
        card?.isClickable = true

        btnApprove?.setOnClickListener {
            btnApprove.isEnabled = false
            btnReject?.isEnabled = false
            progress?.visibility = View.VISIBLE
            executeBatch(commands, workspaceRoot, onResult)
        }

        confirmationView?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                onResult(false, "The user dismissed the confirmation dialog. Please stop and ask the user for clarification or instructions before trying again.")
                true
            } else false
        }

        windowManager.addView(confirmationView, params)

        confirmationView?.alpha = 0f
        confirmationView?.scaleX = 0.95f
        confirmationView?.scaleY = 0.95f
        confirmationView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(200)
            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
            ?.start()
    }

    private fun executeBatch(commands: List<CodeCommand>, workspaceRoot: String, onResult: (Boolean, String) -> Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CodeAssist::OverlayExecutionLock")
        val wakeTimeoutMs = (2 * 60_000L + commands.size * 30_000L).coerceAtMost(15 * 60_000L)
        wakeLock.acquire(wakeTimeoutMs)

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    org.ravi.codeassist.TransactionManager.executeBatch(context, commands, workspaceRoot)
                }
                dismiss()
                onResult(result.success, result.logs)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun showDiffsDialog(patchCommands: List<CodeCommand.Patch>, workspaceRoot: String) {
        val sb = java.lang.StringBuilder()
        val colorHi = ContextCompat.getColor(context, R.color.text_hi)
        val colorRed = ContextCompat.getColor(context, R.color.state_red)
        val colorMint = ContextCompat.getColor(context, R.color.brand_mint)
        val colorLow = ContextCompat.getColor(context, R.color.text_low)
        patchCommands.forEach { cmd ->
            sb.append("<b><font color='#${Integer.toHexString(colorHi).removePrefix("FF")}'>File: ${cmd.path}</font></b><br><br>")
            val preview = org.ravi.codeassist.CommandExecutor.previewPatch(workspaceRoot, cmd)
            sb.append(if (preview != null) {
                TransactionSummaryController.generateSmartDiffHtml(preview.first, preview.second)
            } else {
                TransactionSummaryController.generateSmartDiffHtml(cmd.search, cmd.replace)
            })
            sb.append("<br><hr><br>")
        }

        val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)

        val bgColor = resolveColorAttr(com.google.android.material.R.attr.colorSurfaceContainer, ContextCompat.getColor(context, R.color.surf_card))
        val strokeColor = resolveColorAttr(com.google.android.material.R.attr.colorOutlineVariant, ContextCompat.getColor(context, R.color.line_strong))
        val onSurfaceColor = resolveColorAttr(com.google.android.material.R.attr.colorOnSurface, ContextCompat.getColor(context, R.color.text_hi))
        val innerBgColor = resolveColorAttr(com.google.android.material.R.attr.colorSurfaceContainerHigh, ContextCompat.getColor(context, R.color.surf_raised))

        val density = context.resources.displayMetrics.density
        val cornerXl = context.resources.getDimension(R.dimen.ovl_corner_xl)
        val cornerMd = context.resources.getDimension(R.dimen.ovl_corner_md)
        val spacingXl = (48 * density).toInt()
        val spacingLg = (32 * density).toInt()
        val strokeW = context.resources.getDimension(R.dimen.ovl_stroke).toInt()

        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = cornerXl
            setStroke(strokeW, strokeColor)
        }

        val tv = TextView(themedContext).apply {
            setPadding(spacingLg, spacingLg, spacingLg, spacingLg)
            text = android.text.Html.fromHtml(sb.toString(), android.text.Html.FROM_HTML_MODE_LEGACY)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(onSurfaceColor)
            setTextIsSelectable(true)
        }

        val scrollView = android.widget.ScrollView(themedContext).apply {
            addView(tv)
        }

        val dialogView = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            setPadding(spacingXl, spacingXl, spacingXl, spacingXl)

            val title = TextView(themedContext).apply {
                text = "Pre-Flight Diff Viewer"
                textSize = context.resources.getDimension(R.dimen.ovl_text_lg) / context.resources.displayMetrics.scaledDensity
                setTextColor(onSurfaceColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, spacingLg)
            }
            addView(title)

            val innerCard = LinearLayout(themedContext).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(innerBgColor)
                    cornerRadius = cornerMd
                }
                addView(scrollView, LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }

            addView(innerCard, LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            val closeBtn = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Close"
                setTextColor(onSurfaceColor)
            }
            addView(closeBtn, LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.END
                topMargin = spacingLg
            })

            val lp = WindowManager.LayoutParams(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.8).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                dimAmount = 0.5f
            }

            diffView = this
            windowManager.addView(this, lp)
            closeBtn.setOnClickListener { removeDiffView() }
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                    removeDiffView()
                    true
                } else false
            }
        }
    }

    private fun removeDiffView() {
        diffView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        diffView = null
    }

    private fun resolveColorAttr(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.data else fallback
    }

    private fun dismiss() {
        confirmationView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        confirmationView = null
        removeDiffView()
    }

    fun destroy() {
        dismiss()
        scope.cancel()
    }
}
