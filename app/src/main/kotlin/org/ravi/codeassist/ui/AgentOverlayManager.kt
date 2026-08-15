package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ravi.codeassist.R

class AgentOverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isGenerating = false
    private var dotJob: Job? = null
    private var statusOverride: String? = null
    private var pillExpanded = false
    private var wasIdle = true
    private var isCompactMode = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val isShowing: Boolean get() = overlayView != null

    private fun updateMorphingButton() {
        val btnMorphingAction = overlayView?.findViewById<MaterialButton>(R.id.btnMorphingAction) ?: return

        if (isCompactMode) {
            btnMorphingAction.setIconResource(R.drawable.ic_stroke_play)
            btnMorphingAction.backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.brand_mint)
            )
            btnMorphingAction.iconTint = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.brand_on_accent)
            )
        } else if (isGenerating) {
            btnMorphingAction.setIconResource(R.drawable.ic_stroke_stop)
            btnMorphingAction.backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.surf_raised)
            )
            btnMorphingAction.iconTint = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.text_hi)
            )
        } else {
            btnMorphingAction.setIconResource(R.drawable.ic_stroke_play)
            btnMorphingAction.backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.brand_mint)
            )
            btnMorphingAction.iconTint = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.brand_on_accent)
            )
        }
    }

    fun showOverlay(onStop: () -> Unit) {
        if (overlayView != null) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        overlayView = inflater.inflate(R.layout.layout_agent_overlay, null)

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

        val btnMorphingAction = overlayView?.findViewById<MaterialButton>(R.id.btnMorphingAction)
        val btnToolbox = overlayView?.findViewById<MaterialButton>(R.id.btnToolbox)
        val llToolDrawer = overlayView?.findViewById<View>(R.id.llToolDrawer)
        val llCloseConfirmBar = overlayView?.findViewById<View>(R.id.llCloseConfirmBar)

        val btnToolResume = overlayView?.findViewById<View>(R.id.btnToolResume)
        val btnToolInit = overlayView?.findViewById<View>(R.id.btnToolInit)
        val btnToolBounds = overlayView?.findViewById<View>(R.id.btnToolBounds)
        val btnToolNewSession = overlayView?.findViewById<View>(R.id.btnToolNewSession)
        val btnToolSettings = overlayView?.findViewById<View>(R.id.btnToolSettings)
        val btnToolStopSession = overlayView?.findViewById<View>(R.id.btnToolStopSession)
        val btnCloseConfirm = overlayView?.findViewById<View>(R.id.btnCloseConfirm)
        val btnToolClose = overlayView?.findViewById<View>(R.id.btnToolClose)

        fun animateDrawer(drawer: View, show: Boolean) {
            drawer.animate().cancel()
            if (show) {
                drawer.visibility = View.VISIBLE
                drawer.alpha = 0f
                drawer.scaleX = 0.97f
                drawer.scaleY = 0.97f
                drawer.post {
                    drawer.pivotX = drawer.width / 2f
                    drawer.pivotY = drawer.height.toFloat()
                    drawer.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            } else {
                drawer.animate()
                    .alpha(0f)
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(140)
                    .withEndAction { drawer.visibility = View.GONE }
                    .start()
            }
        }

        fun closeDrawer() {
            val drawer = llToolDrawer ?: return
            if (drawer.visibility == View.VISIBLE) {
                animateDrawer(drawer, false)
            }
            llCloseConfirmBar?.visibility = View.GONE
        }

        btnToolClose?.setOnClickListener {
            closeDrawer()
        }

        btnToolbox?.setOnClickListener {
            val drawer = llToolDrawer
            if (drawer != null) {
                if (drawer.visibility == View.VISIBLE) {
                    llCloseConfirmBar?.visibility = View.GONE
                    animateDrawer(drawer, false)
                } else {
                    animateDrawer(drawer, true)
                }
            }
        }

        btnToolResume?.setOnClickListener {
            closeDrawer()
            org.ravi.codeassist.agent.ToolboxManager.getTool("resume_session")?.onExecute()
        }

        btnToolInit?.setOnClickListener {
            closeDrawer()
            org.ravi.codeassist.agent.ToolboxManager.getTool("init_workspace")?.onExecute()
        }

        btnToolBounds?.setOnClickListener {
            closeDrawer()
            org.ravi.codeassist.agent.ToolboxManager.getTool("configure_scroll_zone")?.onExecute()
        }

        btnToolNewSession?.setOnClickListener {
            closeDrawer()
            org.ravi.codeassist.agent.ToolboxManager.getTool("new_session")?.onExecute()
        }

        btnToolSettings?.setOnClickListener {
            closeDrawer()
            val intent = android.content.Intent(context, org.ravi.codeassist.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        btnToolStopSession?.setOnClickListener {
            llCloseConfirmBar?.visibility = View.VISIBLE
        }

        btnCloseConfirm?.setOnClickListener {
            closeDrawer()
            org.ravi.codeassist.AgentAccessibilityService.instance?.stopAgentSession()
        }

        val sharedPref = context.getSharedPreferences("CodeAssistPrefs", android.content.Context.MODE_PRIVATE)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        // Convert gravity-space (BOTTOM|CENTER_HORIZONTAL) x/y into screen
        // coordinates, clamp the pill fully on-screen, then convert back.
        fun clampToScreen(x: Int, y: Int): Pair<Int, Int> {
            val view = overlayView ?: return Pair(x, y)
            val metrics = context.resources.displayMetrics
            val winW = view.width.coerceAtLeast(1)
            val winH = view.height.coerceAtLeast(1)
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

        overlayView?.findViewById<View>(R.id.flPillGradientBorder)?.setOnTouchListener { _, event ->
            val layoutParams = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) {
                        // BOTTOM gravity: increasing y moves the pill up, so a
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
                android.view.MotionEvent.ACTION_UP -> {
                    sharedPref.edit().putInt("OVERLAY_POS_X", layoutParams.x).putInt("OVERLAY_POS_Y", layoutParams.y).apply()
                    false
                }
                else -> false
            }
        }

        btnMorphingAction?.setOnClickListener {
            if (isCompactMode) {
                pillExpanded = true
                scope.launch { updateStatusInternal() }
            } else if (isGenerating) {
                onStop()
            } else {
                org.ravi.codeassist.AgentAccessibilityService.instance?.resumeOrSync()
            }
        }

        windowManager.addView(overlayView, params)
        scope.launch { updateStatusInternal() }

        overlayView?.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                val drawer = llToolDrawer
                if (drawer != null && drawer.visibility == View.VISIBLE) closeDrawer()
                true
            } else {
                false
            }
        }
    }

    private data class OverlayUi(
        val label: String,
        val detail: String?,
        val start: Int,
        val end: Int,
        val dot: Int,
        val generating: Boolean
    )

    private fun overlayUiFor(state: org.ravi.codeassist.agent.AgentState): OverlayUi {
        val executing = Triple(0xFF34E0A1.toInt(), 0xFF17CFC0.toInt(), 0xFF34E0A1.toInt())
        val working = Triple(0xFFFFAD3F.toInt(), 0xFFFF8A3C.toInt(), 0xFFFFAD3F.toInt())
        val failed = Triple(0xFFFF4D5A.toInt(), 0xFFD50000.toInt(), 0xFFFF4D5A.toInt())
        val user = Triple(0xFF4C8DFF.toInt(), 0xFF3568C8.toInt(), 0xFF4C8DFF.toInt())
        val tools = Triple(0xFFA06BF5.toInt(), 0xFF7B3FBF.toInt(), 0xFFA06BF5.toInt())
        val scroll = Triple(0xFF4DD8E7.toInt(), 0xFF2BB3C2.toInt(), 0xFF4DD8E7.toInt())

        return when (state) {
            is org.ravi.codeassist.agent.AgentState.IDLE ->
                OverlayUi("Idle", null, scroll.first, scroll.second, scroll.third, false)
            is org.ravi.codeassist.agent.AgentState.ANALYZING_SCREEN ->
                OverlayUi("Reading screen", null, working.first, working.second, working.third, true)
            is org.ravi.codeassist.agent.AgentState.AWAITING_LLM ->
                OverlayUi("Thinking", null, working.first, working.second, working.third, true)
            is org.ravi.codeassist.agent.AgentState.EXECUTING_ACTION -> {
                val label = when {
                    state.actionName.contains("type_") -> "Typing"
                    state.actionName.contains("click") -> "Tapping"
                    state.actionName.contains("scroll") -> "Scrolling"
                    else -> "Working"
                }
                OverlayUi(label, null, executing.first, executing.second, executing.third, true)
            }
            is org.ravi.codeassist.agent.AgentState.WAITING_FOR_MUTATION ->
                OverlayUi("Waiting for AI", null, working.first, working.second, working.third, true)
            is org.ravi.codeassist.agent.AgentState.WAITING_FOR_USER ->
                OverlayUi("Needs your input", null, user.first, user.second, user.third, false)
            is org.ravi.codeassist.agent.AgentState.ERROR ->
                OverlayUi("Stopped", state.message, failed.first, failed.second, failed.third, false)
            is org.ravi.codeassist.agent.AgentState.TOOLBOX_OPEN ->
                OverlayUi("Tools", null, tools.first, tools.second, tools.third, false)
            is org.ravi.codeassist.agent.AgentState.SCROLL_CONFIG_ACTIVE ->
                OverlayUi("Scroll zone", null, scroll.first, scroll.second, scroll.third, false)
        }
    }

    private fun buildTelemetryLine(): String {
        val t = org.ravi.codeassist.agent.AgentOrchestrator.telemetry()
        val mm = t.elapsedSeconds / 60
        val ss = t.elapsedSeconds % 60
        val plan = if (t.planPending > 0) "  ·  ${t.planPending} plan open" else ""
        return "round ${t.round}  ·  %d:%02d  ·  ${t.lastAction}$plan".format(mm, ss)
    }

    fun updateStatus(status: String) {
        if (overlayView == null) return
        val clipped = if (status.length > 96) status.take(96) + "…" else status
        scope.launch {
            updateStatusInternal(clipped)
        }
    }

    private suspend fun updateStatusInternal(statusOverride: String? = null) {
        val tvStatus = overlayView?.findViewById<TextView>(R.id.tvOverlayStatus) ?: return
        val tvDetail = overlayView?.findViewById<TextView>(R.id.tvOverlayDetail)
        val vIndicator = overlayView?.findViewById<View>(R.id.vAgentStatusIndicator)
        val flGradientBorder = overlayView?.findViewById<View>(R.id.flPillGradientBorder)

        this.statusOverride = statusOverride

        val state = org.ravi.codeassist.agent.AgentOrchestrator.state.value
        val isIdle = state is org.ravi.codeassist.agent.AgentState.IDLE
        if (!wasIdle && isIdle) pillExpanded = false
        wasIdle = isIdle
        isCompactMode = isIdle && !pillExpanded && this.statusOverride == null

        val drawer = overlayView?.findViewById<View>(R.id.llToolDrawer)
        val confirmBar = overlayView?.findViewById<View>(R.id.llCloseConfirmBar)
        val btnToolbox = overlayView?.findViewById<View>(R.id.btnToolbox)

        if (isCompactMode) {
            drawer?.visibility = View.GONE
            confirmBar?.visibility = View.GONE
            fadeView(tvStatus, false)
            fadeView(tvDetail, false)
            fadeView(btnToolbox, false)
        } else {
            fadeView(tvStatus, true)
            fadeView(btnToolbox, true)
        }

        val ui = overlayUiFor(state)
        tvStatus.text = ui.label

        if (tvDetail != null && !isCompactMode) {
            val telemetry = if (ui.generating) buildTelemetryLine() else null
            val text = statusOverride ?: ui.detail ?: telemetry
            if (text.isNullOrEmpty()) {
                tvDetail.visibility = View.GONE
            } else {
                tvDetail.text = text
                tvDetail.visibility = View.VISIBLE
            }
        }

        isGenerating = ui.generating

        if (isGenerating && dotJob == null) {
            dotJob = scope.launch {
                var dim = true
                var tick = 0
                while (isActive) {
                    vIndicator?.animate()?.alpha(if (dim) 0.3f else 1.0f)?.setDuration(500)?.start()
                    flGradientBorder?.animate()?.alpha(if (dim) 0.7f else 1.0f)?.setDuration(500)?.start()
                    dim = !dim
                    tick++
                    if (tick % 2 == 0) refreshTelemetryDetail()
                    kotlinx.coroutines.delay(500)
                }
            }
        } else if (!isGenerating) {
            dotJob?.cancel()
            dotJob = null
            vIndicator?.alpha = 1.0f
            flGradientBorder?.alpha = 1.0f
        }

        updateMorphingButton()

        if (flGradientBorder != null) {
            val gradientDrawable = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(ui.start, ui.end)
            ).apply {
                cornerRadius = 96f
            }
            flGradientBorder.background = gradientDrawable
        }

        vIndicator?.backgroundTintList = android.content.res.ColorStateList.valueOf(ui.dot)
    }

    private fun fadeView(view: View?, show: Boolean) {
        if (view == null) return
        view.animate().cancel()
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate()
                .alpha(1f)
                .setDuration(160)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            view.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                }
                .start()
        }
    }

    private fun refreshTelemetryDetail() {
        val detail = overlayView?.findViewById<TextView>(R.id.tvOverlayDetail) ?: return
        if (!isGenerating) return
        if (statusOverride != null) return
        if (detail.visibility == View.VISIBLE) {
            detail.text = buildTelemetryLine()
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
        dotJob?.cancel()
        dotJob = null
        hideScrollZonePicker()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    fun destroy() {
        hideOverlay()
        scope.cancel()
    }
}
