package org.ravi.codeassist.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private val handler = Handler(Looper.getMainLooper())
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
    private val density = context.resources.displayMetrics.density

    private var container: FrameLayout? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var teleText: TextView? = null
    private var confirmBar: LinearLayout? = null
    private var confirmTimer: Runnable? = null
    private var onExitAction: (() -> Unit)? = null

    var onDismissed: (() -> Unit)? = null

    class ToolSpec(
        val label: String,
        val iconRes: Int,
        val accent: Int,
        val action: () -> Unit
    )

    val isShowing: Boolean get() = container != null

    fun show(
        centerX: Int,
        pillTop: Int,
        accent: Int,
        status: String,
        tele: String,
        tools: List<ToolSpec>,
        onClose: () -> Unit,
        onExit: () -> Unit
    ) {
        dismiss()

        val sheetW = dp(300)
        val root = FrameLayout(themedContext).apply {
            alpha = 0f
            translationY = dp(16).toFloat()
        }

        val rim = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, 0x00FFFFFF.toInt())).apply {
            cornerRadius = dp(22f).toFloat()
        }
        val glass = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.ovl_pill_glass))
            cornerRadius = dp(21f).toFloat()
        }
        val bg = android.graphics.drawable.LayerDrawable(arrayOf(rim, glass)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
        }
        root.background = bg

        val content = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }
        root.addView(content)

        val grip = View(themedContext).apply {
            background = GradientDrawable().apply {
                setColor(0x24FFFFFF.toInt())
                cornerRadius = dp(2f).toFloat()
            }
        }
        content.addView(grip, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val header = buildHeader(accent, status, tele, onClose)
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
        })

        val grid = buildGrid(tools)
        content.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        })

        val confirm = buildConfirmBar(onExit)
        confirmBar = confirm
        onExitAction = onExit
        content.addView(confirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })

        root.measure(
            View.MeasureSpec.makeMeasureSpec(sheetW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
        )
        val sheetH = root.measuredHeight.coerceAtLeast(1)

        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            sheetW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (centerX - sheetW / 2).coerceIn(0, (metrics.widthPixels - sheetW).coerceAtLeast(0))
            y = (pillTop - dp(12) - sheetH).coerceIn(dp(8), (metrics.heightPixels - sheetH).coerceAtLeast(0))
        }

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_OUTSIDE -> {
                    dismiss()
                    true
                }
                MotionEvent.ACTION_DOWN -> {
                    dismiss()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(root, params)
        container = root

        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun buildHeader(accent: Int, status: String, tele: String, onClose: () -> Unit): LinearLayout {
        val mid = ContextCompat.getColor(context, R.color.text_mid)
        val hi = ContextCompat.getColor(context, R.color.text_hi)

        statusDot = View(themedContext).apply {
            background = GradientDrawable().apply {
                setColor(accent)
                shape = GradientDrawable.OVAL
            }
        }
        statusText = TextView(themedContext).apply {
            text = status
            setTextColor(hi)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        teleText = TextView(themedContext).apply {
            text = tele
            setTextColor(mid)
            textSize = 10f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val infoCol = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(teleText)
        }

        val close = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_close)
            iconSize = dp(12)
            iconTint = ColorStateList.valueOf(mid)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surf_high))
            strokeWidth = dp(0)
            cornerRadius = dp(13)
            insetLeft = 0
            insetTop = 0
            insetRight = 0
            insetBottom = 0
            minWidth = 0
            minHeight = 0
            contentDescription = "Close"
            setOnClickListener { onClose() }
        }

        return LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(4))
            addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(10)
            })
            addView(infoCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            })
            addView(close, LinearLayout.LayoutParams(dp(26), dp(26)))
        }
    }

    private fun buildGrid(tools: List<ToolSpec>): LinearLayout {
        val grid = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        val cellW = dp(84)
        val gap = dp(6)
        tools.chunked(3).forEachIndexed { rowIdx, rowTools ->
            val row = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowTools.forEachIndexed { colIdx, spec ->
                val cell = buildTool(spec)
                val lp = LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (colIdx > 0) marginStart = gap
                }
                row.addView(cell, lp)
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (rowIdx > 0) topMargin = gap
            })
        }
        return grid
    }

    private fun buildTool(spec: ToolSpec): LinearLayout {
        val isExit = spec.label == "Exit"
        val accent = spec.accent

        val chipBg = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(accent, 36))
            cornerRadius = dp(8f).toFloat()
        }
        val chip = FrameLayout(themedContext).apply {
            background = chipBg
        }
        val icon = ImageView(themedContext).apply {
            setImageResource(spec.iconRes)
            setColorFilter(accent)
            isClickable = false
            isFocusable = false
        }
        chip.addView(icon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))

        val label = TextView(themedContext).apply {
            text = spec.label
            setTextColor(accent)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            setSingleLine(true)
        }

        val cellBg = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.surf_raised))
            cornerRadius = dp(14f).toFloat()
            setStroke(dp(1), ContextCompat.getColor(context, R.color.line_strong))
        }
        val cell = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cellBg
            isClickable = true
            setPadding(0, dp(10), 0, dp(8))
            addView(chip, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            })
        }

        val onToolTap = {
            if (isExit) {
                handleExitTap()
            } else {
                dismiss()
                spec.action()
            }
        }
        chip.setOnClickListener { onToolTap() }
        cell.setOnClickListener { onToolTap() }
        return cell
    }

    private fun buildConfirmBar(onExit: () -> Unit): LinearLayout {
        val red = ContextCompat.getColor(context, R.color.state_red)
        val mid = ContextCompat.getColor(context, R.color.text_mid)

        val title = TextView(themedContext).apply {
            text = "Really exit? The agent session stops."
            setTextColor(red)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
        }

        val yes = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Exit"
            setTextColor(ContextCompat.getColor(context, R.color.text_hi))
            backgroundTintList = ColorStateList.valueOf(red)
            cornerRadius = dp(8)
            insetLeft = dp(2)
            insetTop = 0
            insetRight = dp(2)
            insetBottom = 0
            minWidth = 0
            minHeight = 0
            setOnClickListener {
                dismiss()
                onExit()
            }
        }
        val keep = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Keep"
            setTextColor(mid)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surf_high))
            cornerRadius = dp(8)
            insetLeft = dp(2)
            insetTop = 0
            insetRight = dp(2)
            insetBottom = 0
            minWidth = 0
            minHeight = 0
            setOnClickListener { hideConfirmBar() }
        }

        val barBg = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(red, 30))
            cornerRadius = dp(12f).toFloat()
            setStroke(dp(1), ColorUtils.setAlphaComponent(red, 90))
        }

        return LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBg
            visibility = View.GONE
            setPadding(dp(10), dp(7), dp(10), dp(7))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
            addView(yes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                marginEnd = dp(6)
            })
            addView(keep, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        }
    }

    private fun handleExitTap() {
        val bar = confirmBar ?: return
        if (bar.visibility == View.VISIBLE) {
            val action = onExitAction
            dismiss()
            action?.invoke()
            return
        }
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.animate()
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
        bar.post {
            val h = bar.measuredHeight
            val lp = container?.layoutParams as? WindowManager.LayoutParams ?: return@post
            lp.y = (lp.y - h - dp(2)).coerceAtLeast(dp(8))
            try {
                windowManager.updateViewLayout(container, lp)
            } catch (_: Exception) {}
        }
        val timer = Runnable { hideConfirmBar() }
        confirmTimer = timer
        handler.postDelayed(timer, 5000)
    }

    private fun hideConfirmBar() {
        confirmTimer?.let { handler.removeCallbacks(it) }
        confirmTimer = null
        val bar = confirmBar ?: return
        if (bar.visibility != View.VISIBLE) return
        bar.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                bar.visibility = View.GONE
                val h = bar.measuredHeight
                val lp = container?.layoutParams as? WindowManager.LayoutParams ?: return@withEndAction
                lp.y += h + dp(2)
                try {
                    windowManager.updateViewLayout(container, lp)
                } catch (_: Exception) {}
            }
            .start()
    }

    fun updateStatus(accent: Int, status: String, tele: String) {
        statusDot?.background?.setTint(accent)
        statusText?.text = status
        teleText?.text = tele
    }

    fun dismiss() {
        confirmTimer?.let { handler.removeCallbacks(it) }
        confirmTimer = null
        val root = container ?: return
        container = null
        confirmBar = null
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {}
        onDismissed?.invoke()
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun dp(v: Float): Int = (v * density).toInt()
}