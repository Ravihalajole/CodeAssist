package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
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
    private var orbView: CommandOrbView? = null
    private var radialOverlay: CommandRadialOverlay? = null
    private var isGenerating = false
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: kotlinx.coroutines.Job? = null

    val isShowing: Boolean get() = overlayView != null

    fun showOverlay(stateFlow: StateFlow<AgentState>, onStop: () -> Unit) {
        if (overlayView != null) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.layout_agent_overlay, null)
        overlayView = view
        orbView = view.findViewById(R.id.orbView)

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
                    longPressRunnable?.let { view.removeCallbacks(it) }
                    val task = Runnable {
                        longPressFired = true
                        openRadial()
                    }
                    longPressRunnable = task
                    view.postDelayed(task, 400)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        dragged = true
                        longPressRunnable?.let { view.removeCallbacks(it) }
                        longPressRunnable = null
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
                    longPressRunnable?.let { view.removeCallbacks(it) }
                    longPressRunnable = null
                    sharedPref.edit().putInt("OVERLAY_POS_X", layoutParams.x).putInt("OVERLAY_POS_Y", layoutParams.y).apply()
                    if (!dragged && !longPressFired) {
                        if (isGenerating) {
                            onStop()
                        } else {
                            org.ravi.codeassist.AgentAccessibilityService.instance?.resumeOrSync()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    radialOverlay?.dismiss()
                    true
                }
                else -> false
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

    private fun openRadial() {
        if (radialOverlay?.isShowing == true) return
        val lp = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return
        val fallback = (80 * context.resources.displayMetrics.density).toInt()
        val winW = (overlayView?.width ?: 0).takeIf { it > 0 } ?: fallback
        val winH = (overlayView?.height ?: 0).takeIf { it > 0 } ?: fallback
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

    private fun overlayUiFor(state: AgentState): Pair<Int, Boolean> {
        return when (state) {
            is AgentState.IDLE -> Pair(ContextCompat.getColor(context, R.color.state_cyan), false)
            is AgentState.ANALYZING_SCREEN -> Pair(ContextCompat.getColor(context, R.color.state_amber), true)
            is AgentState.AWAITING_LLM -> Pair(ContextCompat.getColor(context, R.color.state_amber), true)
            is AgentState.EXECUTING_ACTION -> Pair(ContextCompat.getColor(context, R.color.brand_mint), true)
            is AgentState.WAITING_FOR_MUTATION -> Pair(ContextCompat.getColor(context, R.color.state_amber), true)
            is AgentState.WAITING_FOR_USER -> Pair(ContextCompat.getColor(context, R.color.state_blue), false)
            is AgentState.ERROR -> Pair(ContextCompat.getColor(context, R.color.state_red), false)
            is AgentState.TOOLBOX_OPEN -> Pair(ContextCompat.getColor(context, R.color.state_violet), false)
            is AgentState.SCROLL_CONFIG_ACTIVE -> Pair(ContextCompat.getColor(context, R.color.state_cyan), false)
        }
    }

    private fun updateStatusInternal(state: AgentState) {
        val orb = orbView ?: return
        val (color, generating) = overlayUiFor(state)
        isGenerating = generating
        orb.applyUi(color, generating, state is AgentState.WAITING_FOR_USER || state is AgentState.ERROR)
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
    }

    fun destroy() {
        hideOverlay()
        scope.cancel()
    }
}
