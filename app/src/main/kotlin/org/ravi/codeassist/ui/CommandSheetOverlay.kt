package org.ravi.codeassist.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import org.ravi.codeassist.R

class CommandSheetOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
    private val density = context.resources.displayMetrics.density
    private val res get() = context.resources

    private var container: FrameLayout? = null
    private var dismissing = false

    var onDismissed: (() -> Unit)? = null

    class ToolSpec(
        val label: String,
        val iconRes: Int,
        val accent: Int,
        val action: () -> Unit
    )

    val isShowing: Boolean get() = container != null

    fun hitTest(rawX: Int, rawY: Int): Boolean {
        val c = container ?: return false
        val lp = c.layoutParams as? WindowManager.LayoutParams ?: return false
        val w = c.width
        val h = c.height
        if (w <= 0 || h <= 0) return false
        return rawX in lp.x until (lp.x + w) && rawY in lp.y until (lp.y + h)
    }

    fun show(
        centerX: Int,
        pillTop: Int,
        pillBottom: Int,
        preferAbove: Boolean,
        accent: Int,
        status: String,
        tele: String,
        tools: List<ToolSpec>,
        onClose: () -> Unit,
        onExit: () -> Unit
    ) {
        dismiss()
        dismissing = false

        val sheetW = res.getDimension(R.dimen.sheet_width).toInt()
        val root = FrameLayout(themedContext).apply {
            alpha = 0f
            translationY = dp(12f).toFloat()
            scaleX = 0.98f
            scaleY = 0.98f
        }

        val corner = res.getDimension(R.dimen.ovl_corner_xl)
        val glass = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.ovl_pill_glass))
            cornerRadius = corner
            setStroke(res.getDimension(R.dimen.ovl_stroke).toInt(), ContextCompat.getColor(context, R.color.ovl_stroke))
        }

        val spacingSm = res.getDimension(R.dimen.ovl_spacing_sm).toInt()
        val spacingMd = res.getDimension(R.dimen.ovl_spacing_md).toInt()
        val content = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacingMd, spacingSm, spacingMd, spacingSm)
        }
        root.addView(content)

        // Minimal header — only close button, no status/dot
        val header = buildHeader(onClose)
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val grid = buildGrid(tools, onExit)
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = spacingSm
        })

        // Estimate sheet height without pre-measure; rely on WRAP_CONTENT anchor.
        // Use a conservative height for positioning to avoid jump.
        val estimateH = dp(220f)
        val metrics = res.displayMetrics
        val gap = dp(8f)
        val topBound = (metrics.heightPixels - estimateH).coerceAtLeast(0)
        val anchorY = if (preferAbove && (pillTop - gap - estimateH) >= dp(8)) {
            pillTop - gap - estimateH
        } else {
            (pillBottom + gap).coerceIn(dp(8), topBound.coerceAtLeast(dp(8)))
        }
        val params = WindowManager.LayoutParams(
            sheetW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (centerX - sheetW / 2).coerceIn(0, (metrics.widthPixels - sheetW).coerceAtLeast(0))
            y = anchorY
        }

        windowManager.addView(root, params)
        container = root

        root.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .withLayer()
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun buildHeader(onClose: () -> Unit): LinearLayout {
        val mid = ContextCompat.getColor(context, R.color.text_mid)
        val closeSize = res.getDimension(R.dimen.sheet_close_size).toInt()
        val closeIcon = res.getDimension(R.dimen.sheet_close_icon).toInt()
        val closeCorner = dimenDp(R.dimen.ovl_corner_sm)
        val close = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_close)
            iconSize = closeIcon
            iconTint = ColorStateList.valueOf(mid)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surf_high))
            strokeWidth = 0
            cornerRadius = closeCorner
            insetLeft = 0; insetTop = 0; insetRight = 0; insetBottom = 0
            minWidth = 0; minHeight = 0
            contentDescription = "Close"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClose()
            }
        }
        return LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 0, 0, 0)
            addView(close, LinearLayout.LayoutParams(closeSize, closeSize))
        }
    }

    private fun buildGrid(tools: List<ToolSpec>, onExit: () -> Unit): LinearLayout {
        val grid = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }
        val cellW = res.getDimension(R.dimen.sheet_tool_cell_w).toInt()
        val gap = dp(6f)
        tools.chunked(3).forEachIndexed { rowIdx, rowTools ->
            val row = LinearLayout(themedContext).apply { orientation = LinearLayout.HORIZONTAL }
            rowTools.forEachIndexed { colIdx, spec ->
                val cell = buildTool(spec, onExit)
                val lp = LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (colIdx > 0) marginStart = gap
                }
                row.addView(cell, lp)
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIdx > 0) topMargin = gap
            })
        }
        return grid
    }

    private fun buildTool(spec: ToolSpec, onExit: () -> Unit): LinearLayout {
        val accent = spec.accent
        val spacingSm = dp(6f)
        val spacingMd = dp(8f)
        val chipSize = res.getDimension(R.dimen.sheet_tool_icon_chip).toInt()
        val iconSize = res.getDimension(R.dimen.sheet_tool_icon).toInt()
        val chipCorner = res.getDimension(R.dimen.sheet_tool_icon_corner)
        val cellCorner = res.getDimension(R.dimen.ovl_corner_md)

        val chipBg = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(accent, 34))
            cornerRadius = chipCorner
        }
        val chip = FrameLayout(themedContext).apply { background = chipBg }
        val icon = ImageView(themedContext).apply {
            setImageResource(spec.iconRes)
            setColorFilter(accent)
            isClickable = false; isFocusable = false
        }
        chip.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

        val label = TextView(themedContext).apply {
            text = spec.label
            setTextColor(ContextCompat.getColor(context, R.color.text_mid))
            textSize = res.getDimension(R.dimen.ovl_text_sm) / res.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setSingleLine(true)
        }

        val cellBg = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.surf_raised))
            cornerRadius = cellCorner
            setStroke(res.getDimension(R.dimen.ovl_stroke).toInt(), ContextCompat.getColor(context, R.color.line_subtle))
        }
        val cell = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cellBg
            isClickable = true; isFocusable = true
            setPadding(0, spacingMd, 0, spacingSm)
            addView(chip, LinearLayout.LayoutParams(chipSize, chipSize))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        }

        cell.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (spec.label == "Exit") {
                showExitConfirm(onExit)
            } else {
                dismiss()
                spec.action()
            }
        }
        return cell
    }

    private fun showExitConfirm(onExit: () -> Unit) {
        // Use system dialog instead of expanding sheet — no layout shift, no timer flicker.
        try {
            val dialog = android.app.AlertDialog.Builder(themedContext)
                .setTitle("Exit agent?")
                .setMessage("The current session will be stopped.")
                .setPositiveButton("Exit") { _, _ ->
                    dismiss()
                    onExit()
                }
                .setNegativeButton("Keep", null)
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.show()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(context, R.color.state_red))
        } catch (_: Exception) {
            dismiss()
            onExit()
        }
    }

    fun updateStatus(accent: Int, status: String, tele: String) {
        // Header no longer shows status — pill remains the single source of truth.
    }

    fun dismiss() {
        if (dismissing) return
        val root = container ?: return
        dismissing = true
        root.removeCallbacks(null)
        root.animate().cancel()
        root.animate()
            .alpha(0f)
            .translationY(dp(8f).toFloat())
            .scaleX(0.98f).scaleY(0.98f)
            .setDuration(140)
            .withLayer()
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                dismissing = false
                container = null
                try { windowManager.removeView(root) } catch (_: Exception) {}
                onDismissed?.invoke()
            }
            .start()
    }

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun dimenDp(resId: Int): Int = (res.getDimension(resId) / res.displayMetrics.density).toInt()
}
