package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private val chatAdapter = ShieldChatAdapter()
    private var isGenerating = false
    private var dotJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val isShowing: Boolean get() = overlayView != null

    private fun updateMorphingButton() {
        val btnMorphingAction = overlayView?.findViewById<MaterialButton>(R.id.btnMorphingAction) ?: return

        if (isGenerating) {
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

    fun showOverlay(onSend: (String) -> Unit, onStop: () -> Unit) {
        if (overlayView != null) return

        val themedContext = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        overlayView = inflater.inflate(R.layout.layout_agent_shield, null)

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

        val btnToolInit = overlayView?.findViewById<View>(R.id.btnToolInit)
        val btnToolBounds = overlayView?.findViewById<View>(R.id.btnToolBounds)
        val btnToolNewSession = overlayView?.findViewById<View>(R.id.btnToolNewSession)
        val btnToolSettings = overlayView?.findViewById<View>(R.id.btnToolSettings)
        val btnToolStopSession = overlayView?.findViewById<View>(R.id.btnToolStopSession)
        val btnCloseConfirm = overlayView?.findViewById<View>(R.id.btnCloseConfirm)
        val btnToolClose = overlayView?.findViewById<View>(R.id.btnToolClose)

        fun closeDrawer() {
            llToolDrawer?.visibility = View.GONE
            llCloseConfirmBar?.visibility = View.GONE
        }

        btnToolClose?.setOnClickListener {
            closeDrawer()
        }

        btnToolbox?.setOnClickListener {
            val drawer = llToolDrawer
            if (drawer != null) {
                val isVisible = drawer.visibility == View.VISIBLE
                drawer.visibility = if (isVisible) View.GONE else View.VISIBLE
                if (isVisible) {
                    llCloseConfirmBar?.visibility = View.GONE
                }
            }
        }

        btnToolInit?.setOnClickListener {
            closeDrawer()
            org.ravi.codeassist.AgentAccessibilityService.instance?.injectSystemPrompt()
        }

        btnToolBounds?.setOnClickListener {
            closeDrawer()
            org.ravi.codeassist.agent.AgentOrchestrator.getActiveProfile()?.let { profile ->
                org.ravi.codeassist.AgentAccessibilityService.instance?.openScrollZonePickerOverlay(profile) { _, _, _, _ -> }
            }
        }

        btnToolNewSession?.setOnClickListener {
            closeDrawer()
            org.ravi.codeassist.agent.AgentOrchestrator.resetSession()
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

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
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
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY - dy.toInt()
                        try {
                            windowManager.updateViewLayout(overlayView, layoutParams)
                        } catch (e: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }

        btnMorphingAction?.setOnClickListener {
            if (isGenerating) {
                onStop()
            } else {
                org.ravi.codeassist.AgentAccessibilityService.instance?.resumeOrSync()
            }
        }

        windowManager.addView(overlayView, params)
        updateMorphingButton()
    }

    fun showShield(onSend: (String) -> Unit, onStop: () -> Unit) = showOverlay(onSend, onStop)

    private data class ShieldUi(
        val label: String,
        val detail: String?,
        val start: Int,
        val end: Int,
        val dot: Int,
        val generating: Boolean
    )

    private fun shieldUiFor(state: org.ravi.codeassist.agent.AgentState): ShieldUi {
        val executing = Triple(0xFF34E0A1.toInt(), 0xFF17CFC0.toInt(), 0xFF34E0A1.toInt())
        val working = Triple(0xFFFFAD3F.toInt(), 0xFFFF8A3C.toInt(), 0xFFFFAD3F.toInt())
        val failed = Triple(0xFFFF4D5A.toInt(), 0xFFD50000.toInt(), 0xFFFF4D5A.toInt())
        val user = Triple(0xFF4C8DFF.toInt(), 0xFF3568C8.toInt(), 0xFF4C8DFF.toInt())
        val tools = Triple(0xFFA06BF5.toInt(), 0xFF7B3FBF.toInt(), 0xFFA06BF5.toInt())
        val scroll = Triple(0xFF4DD8E7.toInt(), 0xFF2BB3C2.toInt(), 0xFF4DD8E7.toInt())

        return when (state) {
            is org.ravi.codeassist.agent.AgentState.IDLE ->
                ShieldUi("Idle", null, scroll.first, scroll.second, scroll.third, false)
            is org.ravi.codeassist.agent.AgentState.ANALYZING_SCREEN ->
                ShieldUi("Reading screen", null, working.first, working.second, working.third, true)
            is org.ravi.codeassist.agent.AgentState.AWAITING_LLM ->
                ShieldUi("Thinking", null, working.first, working.second, working.third, true)
            is org.ravi.codeassist.agent.AgentState.EXECUTING_ACTION -> {
                val label = when {
                    state.actionName.contains("type_") -> "Typing"
                    state.actionName.contains("click") -> "Tapping"
                    state.actionName.contains("scroll") -> "Scrolling"
                    else -> "Working"
                }
                ShieldUi(label, null, executing.first, executing.second, executing.third, true)
            }
            is org.ravi.codeassist.agent.AgentState.WAITING_FOR_MUTATION ->
                ShieldUi("Waiting for AI", null, working.first, working.second, working.third, true)
            is org.ravi.codeassist.agent.AgentState.WAITING_FOR_USER ->
                ShieldUi("Needs your input", null, user.first, user.second, user.third, false)
            is org.ravi.codeassist.agent.AgentState.ERROR ->
                ShieldUi("Stopped", state.message, failed.first, failed.second, failed.third, false)
            is org.ravi.codeassist.agent.AgentState.TOOLBOX_OPEN ->
                ShieldUi("Tools", null, tools.first, tools.second, tools.third, false)
            is org.ravi.codeassist.agent.AgentState.SCROLL_CONFIG_ACTIVE ->
                ShieldUi("Scroll zone", null, scroll.first, scroll.second, scroll.third, false)
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
        scope.launch {
            updateStatusInternal()
        }
    }

    private suspend fun updateStatusInternal() {
        val tvStatus = overlayView?.findViewById<TextView>(R.id.tvShieldStatus) ?: return
        val tvDetail = overlayView?.findViewById<TextView>(R.id.tvShieldDetail)
        val vIndicator = overlayView?.findViewById<View>(R.id.vAgentStatusIndicator)
        val flGradientBorder = overlayView?.findViewById<View>(R.id.flPillGradientBorder)

        val ui = shieldUiFor(org.ravi.codeassist.agent.AgentOrchestrator.state.value)
        tvStatus.text = ui.label

        if (tvDetail != null) {
            val telemetry = if (ui.generating) buildTelemetryLine() else null
            val text = ui.detail ?: telemetry
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

    private fun refreshTelemetryDetail() {
        val detail = overlayView?.findViewById<TextView>(R.id.tvShieldDetail) ?: return
        if (!isGenerating) return
        if (detail.visibility == View.VISIBLE) {
            detail.text = buildTelemetryLine()
        }
    }

    fun addMessage(role: String, text: String) {
        chatAdapter.addMessage(ShieldMessage(role, text))
    }

    fun bindChat(recyclerView: RecyclerView) {
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.adapter = chatAdapter
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

    fun setShieldVisibility(isVisible: Boolean) = setOverlayVisibility(isVisible)

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

    fun hideShield() = hideOverlay()

    fun destroy() {
        hideOverlay()
        scope.cancel()
    }
}
