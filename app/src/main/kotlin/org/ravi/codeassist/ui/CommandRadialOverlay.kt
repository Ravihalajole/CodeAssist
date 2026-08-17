package org.ravi.codeassist.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.ravi.codeassist.R

class CommandRadialOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
    private val density = context.resources.displayMetrics.density

    private var container: FrameLayout? = null
    private var exitButton: MaterialButton? = null
    private var exitLabel: TextView? = null
    private var pendingExit = false
    private var exitReset: Runnable? = null

    private class ToolSpec(
        val label: String,
        val iconRes: Int,
        val accent: Int,
        val action: () -> Unit
    )

    val isShowing: Boolean get() = container != null

    fun show(
        centerX: Int,
        centerY: Int,
        onResume: () -> Unit,
        onInit: () -> Unit,
        onBounds: () -> Unit,
        onNewSession: () -> Unit,
        onSettings: () -> Unit,
        onExit: () -> Unit
    ) {
        dismiss()
        val size = dp(260)
        val center = (size / 2).toFloat()

        val root = FrameLayout(themedContext).apply {
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
        }

        val mint = ContextCompat.getColor(context, R.color.brand_mint)
        val violet = ContextCompat.getColor(context, R.color.state_violet)
        val amber = ContextCompat.getColor(context, R.color.state_amber)
        val mid = ContextCompat.getColor(context, R.color.text_mid)
        val red = ContextCompat.getColor(context, R.color.state_red)

        val tools = listOf(
            ToolSpec("Resume", R.drawable.ic_stroke_play, mint, onResume),
            ToolSpec("Init", R.drawable.ic_play, mint, onInit),
            ToolSpec("Bounds", R.drawable.ic_target_crosshair, violet, onBounds),
            ToolSpec("Reset", R.drawable.ic_tool_reset, amber, onNewSession),
            ToolSpec("App", R.drawable.ic_nav_settings, mid, onSettings),
            ToolSpec("Exit", R.drawable.ic_close, red, onExit)
        )

        val radius = dp(90).toFloat()
        tools.forEachIndexed { i, spec ->
            val angle = (PI.toFloat() / 2f) + (i - tools.size / 2f + 0.5f) * (2f * PI.toFloat() / tools.size)
            val x = cos(angle) * radius
            val y = sin(angle) * radius
            val isExit = i == tools.lastIndex
            val item = buildItem(spec, isExit)
            val lp = FrameLayout.LayoutParams(dp(52), dp(60)).apply {
                leftMargin = (center + x - dp(26f)).toInt()
                topMargin = (center + y - dp(30f)).toInt()
            }
            root.addView(item, lp)
            item.alpha = 0f
            item.animate()
                .alpha(1f)
                .setStartDelay(50 + i * 26L)
                .setDuration(200)
                .start()
        }

        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (centerX - size / 2).coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
            y = (centerY - size / 2).coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
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
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(360)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }

    private fun buildItem(spec: ToolSpec, isExit: Boolean): View {
        val accent = spec.accent
        val raised = ContextCompat.getColor(context, R.color.surf_raised)
        val button = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = ContextCompat.getDrawable(context, spec.iconRes)
            iconSize = dp(18)
            iconTint = ColorStateList.valueOf(accent)
            backgroundTintList = ColorStateList.valueOf(raised)
            strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.line_strong))
            strokeWidth = dp(1)
            cornerRadius = dp(20)
            insetLeft = 0
            insetTop = 0
            insetRight = 0
            insetBottom = 0
            minWidth = 0
            minHeight = 0
            contentDescription = spec.label
        }
        val label = TextView(themedContext).apply {
            text = spec.label
            setTextColor(accent)
            textSize = 9.5f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            letterSpacing = 0.02f
            setSingleLine(true)
            setBackground(android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.ovl_scrim))
                cornerRadius = dp(6f)
            })
            setPadding(dp(7), dp(2), dp(7), dp(2))
        }
        val layout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(button, LinearLayout.LayoutParams(dp(40), dp(40)))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
            addView(label, lp)
        }

        if (isExit) {
            exitButton = button
            exitLabel = label
        }

        button.setOnClickListener {
            if (isExit) {
                handleExitTap(spec.action)
            } else {
                dismiss()
                spec.action()
            }
        }
        return layout
    }

    private fun handleExitTap(onExit: () -> Unit) {
        if (pendingExit) {
            dismiss()
            onExit()
            return
        }
        val button = exitButton ?: return
        val label = exitLabel ?: return
        pendingExit = true
        val red = ContextCompat.getColor(context, R.color.state_red)
        val white = ContextCompat.getColor(context, R.color.text_hi)
        button.backgroundTintList = ColorStateList.valueOf(red)
        button.iconTint = ColorStateList.valueOf(white)
        label.setTextColor(red)
        label.text = "Confirm?"
        val reset = Runnable {
            pendingExit = false
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.surf_raised)
            )
            button.iconTint = ColorStateList.valueOf(red)
            label.setTextColor(red)
            label.text = "Exit"
        }
        exitReset = reset
        handler.postDelayed(reset, 3000)
    }

    fun dismiss() {
        exitReset?.let { handler.removeCallbacks(it) }
        exitReset = null
        val root = container ?: return
        exitButton = null
        exitLabel = null
        pendingExit = false
        root.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                container = null
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {}
            }
            .start()
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun dp(v: Float): Int = (v * density).toInt()
}
