package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ravi.codeassist.R
import org.ravi.codeassist.agent.AgentState

class AgentOverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var pillView: CommandPillView? = null
    private var sheetOverlay: CommandSheetOverlay? = null
    private var isGenerating = false
    private var lastUi = OverlayUi(0, false, "Idle", "Tap for tools")

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: kotlinx.coroutines.Job? = null

    val isShowing: Boolean get() = overlayView != null

    data class OverlayUi(
        val accent: Int,
        val generating: Boolean,
        val label: String,
        val sub: String
    )

    fun showOverlay(stateFlow: StateFlow<AgentState>, onStop: () -> Unit) {
        if (overlayView != null) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.layout_agent_overlay, null)
        overlayView = view
        pillView = view.findViewById(R.id.pillView)

        val density = context.resources.displayMetrics.density
        val defaultY = (120 * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = defaultY
        }

        val sharedPref = context.getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)

        fun clampToScreen(rawX: Int, rawY: Int): Pair<Int, Int> {
            val v = overlayView ?: return Pair(rawX, rawY)
            val metrics = context.resources.displayMetrics
            val winW = v.width.coerceAtLeast(1)
            val winH = v.height.coerceAtLeast(1)
            val maxTop = metrics.heightPixels - winH
            val screenTop = (metrics.heightPixels - winH - rawY).coerceIn(0, maxTop)
            val newY = metrics.heightPixels - winH - screenTop
            val screenLeft = ((metrics.widthPixels - winW) / 2f + rawX)
            val maxLeft = metrics.widthPixels - winW
            val clampedLeft = screenLeft.coerceIn(0f, maxLeft.toFloat())
            val newX = (clampedLeft - (metrics.widthPixels - winW) / 2f).toInt()
            return Pair(newX, newY)
        }

        params.x = sharedPref.getInt("OVERLAY_POS_X", 0)
        params.y = sharedPref.getInt("OVERLAY_POS_Y", defaultY)

        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragged = false
        var wasSheetOpen = false

        view.setOnTouchListener { _, event ->
            val layoutParams = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragged = false
                    wasSheetOpen = sheetOverlay?.isShowing == true
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    pillView?.setPressFeedback(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        if (!dragged) {
                            dismissSheet()
                            dragged = true
                            pillView?.setPressFeedback(false)
                        }
                        val (clampedX, clampedY) = clampToScreen(initialX + dx.toInt(), initialY - dy.toInt())
                        layoutParams.x = clampedX
                        layoutParams.y = clampedY
                        try {
                            windowManager.updateViewLayout(overlayView, layoutParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    sharedPref.edit().putInt("OVERLAY_POS_X", layoutParams.x).putInt("OVERLAY_POS_Y", layoutParams.y).apply()
                    if (!dragged) {
                        pillView?.setPressFeedback(false)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (wasSheetOpen) {
                            dismissSheet()
                        } else {
                            openSheet()
                        }
                    } else {
                        pillView?.setPressFeedback(false)
                    }
                    true
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    // The sheet window also trips this listener (it's a separate
                    // overlay), so a tap inside the sheet must not close it —
                    // only touches beyond both windows dismiss it.
                    val sheet = sheetOverlay
                    if (sheet?.hitTest(event.rawX.toInt(), event.rawY.toInt()) != true) {
                        dismissSheet()
                    }
                    pillView?.setPressFeedback(false)
                    true
                }
                else -> false
            }
        }

        val morph = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.morphButton)
        morph.setOnClickListener {
            morph.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            dismissSheet()
            if (isGenerating) {
                onStop()
            } else {
                org.ravi.codeassist.AgentAccessibilityService.instance?.resumeOrSync()
            }
        }

        windowManager.addView(overlayView, params)

        stateJob?.cancel()
        stateJob = scope.launch {
            stateFlow.collect { state ->
                updateStatusInternal(state)
            }
        }
    }

    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun openSheet() {
        if (sheetOverlay?.isShowing == true) return
        val lp = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return
        val fallback = dp(250f)
        // Measure the pill on demand so the sheet always anchors to its real
        // bounds, even if the user taps before the first layout pass.
        var winW = overlayView?.width ?: 0
        var winH = overlayView?.height ?: 0
        if (winW <= 0 || winH <= 0) {
            overlayView?.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            winW = overlayView?.measuredWidth ?: 0
            winH = overlayView?.measuredHeight ?: 0
        }
        if (winW <= 0) winW = fallback
        if (winH <= 0) winH = dp(46f)
        val metrics = context.resources.displayMetrics
        val centerX = (metrics.widthPixels - winW) / 2 + lp.x + winW / 2
        val pillTop = metrics.heightPixels - lp.y - winH

        val mint = ContextCompat.getColor(context, R.color.brand_mint)
        val violet = ContextCompat.getColor(context, R.color.state_violet)
        val amber = ContextCompat.getColor(context, R.color.state_amber)
        val mid = ContextCompat.getColor(context, R.color.text_mid)
        val red = ContextCompat.getColor(context, R.color.state_red)

        val sheet = CommandSheetOverlay(context)
        sheet.onDismissed = { if (sheetOverlay === sheet) sheetOverlay = null }
        sheet.show(
            centerX = centerX,
            pillTop = pillTop,
            pillBottom = pillTop + winH,
            preferAbove = true,
            accent = lastUi.accent,
            status = lastUi.label,
            tele = lastUi.sub,
            tools = listOf(
                CommandSheetOverlay.ToolSpec("Resume", R.drawable.ic_stroke_play, mint) {
                    org.ravi.codeassist.agent.ToolboxManager.getTool("resume_session")?.onExecute()
                },
                CommandSheetOverlay.ToolSpec("Init", R.drawable.ic_play, mint) {
                    org.ravi.codeassist.agent.ToolboxManager.getTool("init_workspace")?.onExecute()
                },
                CommandSheetOverlay.ToolSpec("Bounds", R.drawable.ic_target_crosshair, violet) {
                    org.ravi.codeassist.agent.ToolboxManager.getTool("configure_scroll_zone")?.onExecute()
                },
                CommandSheetOverlay.ToolSpec("Reset", R.drawable.ic_tool_reset, amber) {
                    org.ravi.codeassist.agent.ToolboxManager.getTool("new_session")?.onExecute()
                },
                CommandSheetOverlay.ToolSpec("App", R.drawable.ic_nav_settings, mid) {
                    val intent = android.content.Intent(context, org.ravi.codeassist.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(intent)
                },
                CommandSheetOverlay.ToolSpec("Exit", R.drawable.ic_close, red) {
                    org.ravi.codeassist.AgentAccessibilityService.instance?.stopAgentSession()
                }
            ),
            onClose = { dismissSheet() },
            onExit = { org.ravi.codeassist.AgentAccessibilityService.instance?.stopAgentSession() }
        )
        sheetOverlay = sheet
    }

    private fun dismissSheet() {
        // Don't null sheetOverlay here — the sheet keeps its window (and its
        // isShowing flag) for the 140ms close animation, so openSheet can't
        // spawn a second sheet over the outgoing one. onDismissed (fired when
        // the window is actually removed) nulls the field.
        sheetOverlay?.dismiss()
    }

    private fun formatElapsed(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    private fun humanAction(actionName: String): String = when (actionName) {
        "type_text" -> "Typing"
        "click_send" -> "Sending"
        "read_latest_response" -> "Reading"
        else -> "Working"
    }

    private fun overlayUiFor(state: AgentState): OverlayUi {
        val tele = org.ravi.codeassist.agent.AgentOrchestrator.telemetry()
        val round = tele.round
        val clock = formatElapsed(tele.elapsedSeconds)
        val action = tele.lastAction.ifBlank { "working" }
        return when (state) {
            is AgentState.IDLE -> OverlayUi(ContextCompat.getColor(context, R.color.state_cyan), false, "Idle", "round $round · $clock · tap for tools")
            is AgentState.ANALYZING_SCREEN -> OverlayUi(ContextCompat.getColor(context, R.color.state_amber), true, "Reading screen", "round $round · $clock · $action")
            is AgentState.AWAITING_LLM -> OverlayUi(ContextCompat.getColor(context, R.color.state_amber), true, "Thinking", "round $round · $clock · $action")
            is AgentState.EXECUTING_ACTION -> OverlayUi(ContextCompat.getColor(context, R.color.brand_mint), true, humanAction(state.actionName), "round $round · $clock · $action")
            is AgentState.WAITING_FOR_MUTATION -> OverlayUi(ContextCompat.getColor(context, R.color.state_amber), true, "Applying changes", "round $round · $clock · $action")
            is AgentState.WAITING_FOR_USER -> OverlayUi(ContextCompat.getColor(context, R.color.state_blue), false, "Needs input", "round $round · $clock · tap to resume")
            is AgentState.ERROR -> OverlayUi(ContextCompat.getColor(context, R.color.state_red), false, "Stopped", state.message)
            is AgentState.TOOLBOX_OPEN -> OverlayUi(ContextCompat.getColor(context, R.color.state_violet), false, "Tools", "round $round · $clock")
            is AgentState.SCROLL_CONFIG_ACTIVE -> OverlayUi(ContextCompat.getColor(context, R.color.state_cyan), false, "Scroll zone", "round $round · $clock")
        }
    }

    private fun updateStatusInternal(state: AgentState) {
        val pill = pillView ?: return
        val ui = overlayUiFor(state)
        lastUi = ui
        isGenerating = ui.generating
        pill.applyUi(ui.accent, ui.generating, state is AgentState.WAITING_FOR_USER || state is AgentState.ERROR, ui.label, ui.sub)
        sheetOverlay?.updateStatus(ui.accent, ui.label, ui.sub)
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
            // The pill hides while the agent types/sends; take the sheet down too
            // so the two windows never linger out of sync.
            dismissSheet()
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

        val density = context.resources.displayMetrics.density
        val buttonContainer = android.widget.LinearLayout(themedContext).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(context, R.color.surf_raised))
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }

        val btnSave = com.google.android.material.button.MaterialButton(themedContext).apply {
            text = "Save Bounds"
            setBackgroundColor(ContextCompat.getColor(context, R.color.brand_mint))
            setTextColor(ContextCompat.getColor(context, R.color.brand_on_accent))
            setOnClickListener {
                onSave(currentLeft, currentTop, currentRight, currentBottom)
                hideScrollZonePicker()
            }
        }

        val btnCancel = com.google.android.material.button.MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"
            setTextColor(ContextCompat.getColor(context, R.color.text_hi))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (16 * density).toInt()
            }
            this.layoutParams = lp
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
            bottomMargin = (120 * density).toInt()
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
        stateJob?.cancel()
        stateJob = null
        dismissSheet()
        hideScrollZonePicker()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        pillView = null
    }

    fun destroy() {
        hideOverlay()
        scope.cancel()
    }
}
