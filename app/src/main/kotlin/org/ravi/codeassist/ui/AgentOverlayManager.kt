package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ravi.codeassist.R

class AgentOverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var orbView: CommandOrbView? = null
    private var orbHitTarget: View? = null
    private var chipView: View? = null
    private var chipText: TextView? = null
    private var chipJob: Job? = null
    private var radialOverlay: CommandRadialOverlay? = null
    private var isGenerating = false
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val isShowing: Boolean get() = overlayView != null

    fun showOverlay(onStop: () -> Unit) {
        if (overlayView != null) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.layout_agent_overlay, null)
        overlayView = view
        orbView = view.findViewById(R.id.orbView)
        orbHitTarget = view.findViewById(R.id.flOrbContainer)
        chipView = view.findViewById(R.id.llStatusChip)
        chipText = view.findViewById(R.id.tvStatusChip)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        val sharedPref = context.getSharedPreferences("CodeAssistPrefs", android.content.Context.MODE_PRIVATE)

        fun clampToScreen(x: Int, y: Int): Pair<Int, Int> {
            val v = overlayView ?: return Pair(x, y)
            val metrics = context.resources.displayMetrics
            val winW = v.width.coerceAtLeast(1)
            val winH = v.height.coerceAtLeast(1)
            val maxLeft = (metrics.widthPixels - winW).coerceAtLeast(0)
            val maxTop = (metrics.heightPixels - winH).coerceAtLeast(0)
            val screenLeft = ((metrics.widthPixels - winW) / 2f + x).coerceIn(0f, maxLeft.toFloat())
            val screenTop = (metrics.heightPixels - winH - y).coerceIn(0, maxTop)
            val newX = (screenLeft - (metrics.widthPixels - winW) / 2f).toInt()
            val newY = (metrics.heightPixels - winH - screenTop).toInt()
            return Pair(newX, newY)
        }

        params.x = sharedPref.getInt("OVERLAY_POS_X", 0)
        params.y = sharedPref.getInt("OVERLAY_POS_Y", 120)

        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragged = false

        view.setOnTouchListener { _, event ->
            val layoutParams = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragged = false
                    longPressFired = false
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    if (isTouchOnOrb(event.x, event.y)) {
                        longPressRunnable?.let { view.removeCallbacks(it) }
                        val task = Runnable {
                            longPressFired = true
                            openRadial()
                        }
                        longPressRunnable = task
                        view.postDelayed(task, 360)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        dragged = true
                        longPressRunnable?.let { view.removeCallbacks(it) }
                        longPressRunnable = null
                        // BOTTOM gravity: increasing y moves the window up, so a
                        // downward drag (dy > 0) must DECREASE y.
                        val (clampedX, clampedY) = clampToScreen(initialX + dx.toInt(), initialY - dy.toInt())
                        layoutParams.x = clampedX
                        layoutParams.y = clampedY
                        try {
                            windowManager.updateViewLayout(overlayView, layoutParams)
                        } catch (e: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { view.removeCallbacks(it) }
                    longPressRunnable = null
                    sharedPref.edit().putInt("OVERLAY_POS_X", layoutParams.x).putInt("OVERLAY_POS_Y", layoutParams.y).apply()
                    if (!dragged && !longPressFired && isTouchOnOrb(event.x, event.y)) {
                        if (isGenerating) {
                            onStop()
                        } else {
                            org.ravi.codeassist.AgentAccessibilityService.instance?.resumeOrSync()
                        }
                    }
                    false
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    radialOverlay?.dismiss()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
        scope.launch { updateStatusInternal() }
    }

    private fun isTouchOnOrb(x: Float, y: Float): Boolean {
        val target = orbHitTarget ?: return false
        return x >= target.left && x <= target.right && y >= target.top && y <= target.bottom
    }

    private fun openRadial() {
        if (radialOverlay?.isShowing == true) return
        val lp = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return
        val winW = (overlayView?.width ?: 0).takeIf { it > 0 } ?: return
        val winH = (overlayView?.height ?: 0).takeIf { it > 0 } ?: return
        val metrics = context.resources.displayMetrics
        val centerX = (metrics.widthPixels - winW) / 2 + lp.x + winW / 2
        val centerY = metrics.heightPixels - lp.y - winH / 2
        val radial = CommandRadialOverlay(context)
        radial.show(
            centerX = centerX,
            centerY = centerY,
            onResume = { org.ravi.codeassist.agent.ToolboxManager.getTool("resume_session")?.onExecute() },
            onInit = { org.ravi.codeassist.agent.ToolboxManager.getTool("init_workspace")?.onExecute() },
            onBounds = { org.ravi.codeassist.agent.ToolboxManager.getTool("configure_scroll_zone")?.onExecute() },
            onNewSession = { org.ravi.codeassist.agent.ToolboxManager.getTool("new_session")?.onExecute() },
            onSettings = {
                val intent = android.content.Intent(context, org.ravi.codeassist.MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
            },
            onExit = { org.ravi.codeassist.AgentAccessibilityService.instance?.stopAgentSession() }
        )
        radialOverlay = radial
    }

    private data class OverlayUi(
        val start: Int,
        val generating: Boolean,
        val pulse: Boolean
    )

    private fun overlayUiFor(state: org.ravi.codeassist.agent.AgentState): OverlayUi {
        val executing = 0xFF34E0A1.toInt()
        val working = 0xFFFFAD3F.toInt()
        val failed = 0xFFFF4D5A.toInt()
        val user = 0xFF4C8DFF.toInt()
        val tools = 0xFFA06BF5.toInt()
        val scroll = 0xFF4DD8E7.toInt()

        return when (state) {
            is org.ravi.codeassist.agent.AgentState.IDLE -> OverlayUi(scroll, false, false)
            is org.ravi.codeassist.agent.AgentState.ANALYZING_SCREEN -> OverlayUi(working, true, false)
            is org.ravi.codeassist.agent.AgentState.AWAITING_LLM -> OverlayUi(working, true, false)
            is org.ravi.codeassist.agent.AgentState.EXECUTING_ACTION -> OverlayUi(executing, true, false)
            is org.ravi.codeassist.agent.AgentState.WAITING_FOR_MUTATION -> OverlayUi(working, true, false)
            is org.ravi.codeassist.agent.AgentState.WAITING_FOR_USER -> OverlayUi(user, false, true)
            is org.ravi.codeassist.agent.AgentState.ERROR -> OverlayUi(failed, false, true)
            is org.ravi.codeassist.agent.AgentState.TOOLBOX_OPEN -> OverlayUi(tools, false, false)
            is org.ravi.codeassist.agent.AgentState.SCROLL_CONFIG_ACTIVE -> OverlayUi(scroll, false, false)
        }
    }

    private suspend fun updateStatusInternal() {
        val orb = orbView ?: return
        val state = org.ravi.codeassist.agent.AgentOrchestrator.state.value
        val ui = overlayUiFor(state)
        isGenerating = ui.generating
        orb.applyUi(ui.start, ui.generating, ui.pulse)
    }

    fun updateStatus(status: String) {
        if (overlayView == null) return
        val chip = chipView ?: return
        val text = chipText ?: return
        chipJob?.cancel()
        text.text = status
        chip.animate().cancel()
        chip.alpha = 0f
        chip.visibility = View.VISIBLE
        chip.animate().alpha(1f).setDuration(160).start()
        chipJob = scope.launch {
            delay(2800)
            chip.animate().alpha(0f).setDuration(220).withEndAction {
                chip.visibility = View.GONE
            }.start()
        }
    }

    fun setOverlayVisibility(isVisible: Boolean) {
        val view = overlayView ?: return
        view.animate().cancel()
        if (isVisible) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate()
                .alpha(1f)
                .setDuration(120)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            view.animate()
                .alpha(0f)
                .setDuration(100)
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                }
                .start()
        }
    }

    private var scrollPickerView: ScrollZonePickerView? = null
    private var scrollPickerContainer: View? = null

    fun showScrollZonePicker(profileId: Long, initialLeft: Float, initialTop: Float, initialRight: Float, initialBottom: Float, onSave: (Float, Float, Float, Float) -> Unit) {
        if (scrollPickerView != null) return

        var currentLeft = initialLeft
        var currentTop = initialTop
        var currentRight = initialRight
        var currentBottom = initialBottom

        val picker = ScrollZonePickerView(context).apply {
            setInitialBounds(initialLeft, initialTop, initialRight, initialBottom)
            boundsListener = object : ScrollZonePickerView.OnBoundsChangedListener {
                override fun onBoundsChanged(leftPct: Float, topPct: Float, rightPct: Float, bottomPct: Float) {
                    currentLeft = leftPct
                    currentTop = topPct
                    currentRight = rightPct
                    currentBottom = bottomPct
                }
            }
        }
        scrollPickerView = picker

        val pickerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val container = android.widget.FrameLayout(themedContext)
        container.addView(picker)
        scrollPickerContainer = container

        val buttonContainer = android.widget.LinearLayout(themedContext).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.surf_raised))
            setPadding(32, 16, 32, 16)
        }

        val btnSave = MaterialButton(themedContext).apply {
            text = "Save Bounds"
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.brand_mint))
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.brand_on_accent))
            setOnClickListener {
                onSave(currentLeft, currentTop, currentRight, currentBottom)
                hideScrollZonePicker()
            }
        }

        val btnCancel = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_hi))
            strokeColor = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.text_mid)
            )
            val layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 32, 0)
            }
            this.layoutParams = layoutParams
            setOnClickListener {
                hideScrollZonePicker()
            }
        }

        buttonContainer.addView(btnCancel)
        buttonContainer.addView(btnSave)

        val frameParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, 240)
        }
        container.addView(buttonContainer, frameParams)

        windowManager.addView(container, pickerParams)
    }

    fun hideScrollZonePicker() {
        val container = scrollPickerContainer
        scrollPickerView = null
        scrollPickerContainer = null
        if (container != null) {
            try {
                windowManager.removeView(container)
            } catch (_: Exception) {}
        }
    }

    fun hideOverlay() {
        chipJob?.cancel()
        chipJob = null
        radialOverlay?.dismiss()
        radialOverlay = null
        hideScrollZonePicker()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        orbView = null
        orbHitTarget = null
        chipView = null
        chipText = null
    }

    fun destroy() {
        hideOverlay()
        scope.cancel()
    }
}
