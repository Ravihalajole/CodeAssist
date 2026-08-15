package org.ravi.codeassist.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
        val size = dp(240)
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

        val radius = dp(84f).toFloat()
        tools.forEachIndexed { i, spec ->
            val angle = (PI.toFloat() / 2f) + (i - tools.size / 2f + 0.5f) * (2f * PI.toFloat() / tools.size)
            val x = cos(angle) * radius
            val y = sin(angle) * radius - 8f * context.resources.displayMetrics.density
            val isExit = i == tools.lastIndex
            val item = buildItem(spec, isExit)
            val lp = FrameLayout.LayoutParams(dp(52), dp(56)).apply {
                leftMargin = (center + x - dp(26f)).toInt()
                topMargin = (center + y - dp(28f)).toInt()
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (centerX - size / 2).coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
            y = (centerY - size / 2 - dp(44)).coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
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
        val stroke = ColorUtils.setAlphaComponent(accent, 0x66)
        val raised = ContextCompat.getColor(context, R.color.surf_raised)
        val button = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = androidx.core.content.ContextCompat.getDrawable(context, spec.iconRes)
            iconSize = dp(18)
            iconTint = ColorStateList.valueOf(accent)
            backgroundTintList = ColorStateList.valueOf(raised)
            strokeColor = ColorStateList.valueOf(stroke)
            strokeWidth = 1
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
        button.backgroundTintList = ColorStateList.valueOf(red)
        button.iconTint = ColorStateList.valueOf(Color.WHITE)
        button.strokeColor = ColorStateList.valueOf(Color.WHITE)
        label.setTextColor(red)
        label.text = "Confirm?"
        val reset = Runnable {
            pendingExit = false
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.surf_raised)
            )
            button.iconTint = ColorStateList.valueOf(red)
            button.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(red, 0x66))
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
        container = null
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
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {}
            }
            .start()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).toInt()
}
