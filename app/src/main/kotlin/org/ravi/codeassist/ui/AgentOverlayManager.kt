package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    private var rootView: FrameLayout? = null
    private var wrapper: FrameLayout? = null
    private var pillView: CommandPillView? = null
    private var sheetView: View? = null
    private var isSheetShowing = false
    private var isGenerating = false
    private var lastUi = OverlayUi(0, false, "Idle", "Tap for tools")

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: kotlinx.coroutines.Job? = null

    val isShowing: Boolean get() = rootView != null

    data class OverlayUi(val accent: Int, val generating: Boolean, val label: String, val sub: String)

    fun showOverlay(stateFlow: StateFlow<AgentState>, onStop: () -> Unit) {
        if (rootView != null) return
        val themed = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val density = context.resources.displayMetrics.density
        val defaultBottom = (120 * density).toInt()

        // Single fixed-size window — no WRAP_CONTENT height animation, so no WindowManager relayout flicker (SO 79775525)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        val root = FrameLayout(themed).apply {
            isClickable = false
            isFocusable = false
            // Let touches outside wrapper fall through to app (sheet closed only via X/pill)
        }
        rootView = root

        val prefs = context.getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val savedX = prefs.getInt("OVERLAY_POS_X", 0)
        val savedY = prefs.getInt("OVERLAY_POS_Y", defaultBottom)

        val wrapper = FrameLayout(themed).apply {
            // Wrapper is WRAP_CONTENT but inside MATCH_PARENT window, so showing sheet doesn't resize window
            isClickable = false
        }
        this.wrapper = wrapper
        val wrapperLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = savedY
        }
        wrapper.translationX = savedX.toFloat()
        root.addView(wrapper, wrapperLp)

        // Sheet
        val sheet = createSheetView(themed) { dismissSheet() }
        sheet.visibility = View.GONE
        sheet.alpha = 0f
        sheet.scaleX = 0.96f
        sheet.scaleY = 0.96f
        sheet.translationY = 12 * density
        wrapper.addView(sheet, FrameLayout.LayoutParams(
            context.resources.getDimension(R.dimen.sheet_width).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = (56 * density).toInt() })
        sheetView = sheet

        val inflater = LayoutInflater.from(themed)
        val pill = inflater.inflate(R.layout.layout_agent_overlay, wrapper, false) as CommandPillView
        wrapper.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        pillView = pill

        // Drag — via wrapper translation, not WindowManager (avoids relayout flicker)
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var initTx = 0f; var initTy = 0f; var downX = 0f; var downY = 0f; var dragged = false
        var lastTap = 0L; val cooldown = 220L

        pill.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragged = false
                    initTx = wrapper.translationX
                    // bottomMargin is not translation, use wrapper translationY for vertical drag
                    initTy = wrapper.translationY
                    downX = ev.rawX; downY = ev.rawY
                    pillView?.setPressFeedback(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        if (!dragged) { if (isSheetShowing) dismissSheet(); dragged = true; pillView?.setPressFeedback(false) }
                        val m = context.resources.displayMetrics
                        // Clamp wrapper inside screen
                        val w = wrapper.width.coerceAtLeast(1)
                        val h = wrapper.height.coerceAtLeast(1)
                        val maxX = (m.widthPixels - w) / 2f
                        val newTx = (initTx + dx).coerceIn(-maxX, maxX)
                        // Vertical: wrapper bottomMargin + translationY controls Y. Use translationY for drag.
                        val maxUp = m.heightPixels - h - 40*density
                        val newTy = (initTy - dy).coerceIn(-maxUp, 200*density)
                        wrapper.translationX = newTx
                        wrapper.translationY = newTy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Save position
                    prefs.edit().putInt("OVERLAY_POS_X", wrapper.translationX.toInt()).putInt("OVERLAY_POS_Y", (defaultBottom - wrapper.translationY.toInt()).coerceAtLeast(0)).apply()
                    if (!dragged) {
                        pillView?.setPressFeedback(false)
                        pill.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTap < cooldown) return@setOnTouchListener true
                        lastTap = now
                        toggleSheet()
                    } else pillView?.setPressFeedback(false)
                    true
                }
                else -> false
            }
        }

        val morph = pill.findViewById<com.google.android.material.button.MaterialButton>(R.id.morphButton)
        morph.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (isSheetShowing) dismissSheet()
            else {
                if (isGenerating) onStop()
                else org.ravi.codeassist.AgentAccessibilityService.instance?.resumeOrSync()
            }
        }

        // Restore translation from prefs (already set via wrapper.translationX)
        windowManager.addView(root, params)
        stateJob?.cancel()
        stateJob = scope.launch { stateFlow.collect { updateStatusInternal(it) } }
    }

    private fun createSheetView(themed: Context, onClose: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        val card = com.google.android.material.card.MaterialCardView(themed).apply {
            radius = context.resources.getDimension(R.dimen.ovl_corner_xl)
            strokeWidth = context.resources.getDimension(R.dimen.ovl_stroke).toInt()
            strokeColor = ContextCompat.getColor(context, R.color.ovl_stroke)
            cardElevation = 16 * density
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.ovl_pill_glass))
            isClickable = true; isFocusable = true
            // Prevent touches on card from falling through to underlying app
        }
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12*density).toInt(), (8*density).toInt(), (12*density).toInt(), (10*density).toInt())
        }
        card.addView(content)
        val header = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = android.widget.TextView(themed).apply {
            text = "Tools"; setTextColor(ContextCompat.getColor(context, R.color.text_hi)); textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.02f
        }
        val close = com.google.android.material.button.MaterialButton(themed, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_close)
            iconSize = (12*density).toInt()
            iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_mid))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surf_high))
            strokeWidth = 0; cornerRadius = (10*density).toInt()
            insetLeft=0; insetTop=0; insetRight=0; insetBottom=0; minimumWidth=0; minimumHeight=0
            layoutParams = LinearLayout.LayoutParams((24*density).toInt(), (24*density).toInt())
            setOnClickListener { onClose() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)
        content.addView(header)
        val grid = buildGridView(themed)
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10*density).toInt() })
        val hint = android.widget.TextView(themed).apply {
            text = "Tap pill to close"; setTextColor(ContextCompat.getColor(context, R.color.text_mid)); textSize = 10f; gravity = Gravity.CENTER; alpha = 0.7f
            setPadding(0, (8*density).toInt(), 0, 0)
        }
        content.addView(hint)
        return card
    }

    private data class ToolDef(val label: String, val iconRes: Int, val accent: Int, val action: () -> Unit)

    private fun buildGridView(themed: Context): LinearLayout {
        val mint = ContextCompat.getColor(context, R.color.brand_mint)
        val violet = ContextCompat.getColor(context, R.color.state_violet)
        val amber = ContextCompat.getColor(context, R.color.state_amber)
        val mid = ContextCompat.getColor(context, R.color.text_mid)
        val red = ContextCompat.getColor(context, R.color.state_red)
        val tools = listOf(
            ToolDef("Resume", R.drawable.ic_stroke_play, mint) { org.ravi.codeassist.agent.ToolboxManager.getTool("resume_session")?.onExecute() },
            ToolDef("Init", R.drawable.ic_play, mint) { org.ravi.codeassist.agent.ToolboxManager.getTool("init_workspace")?.onExecute() },
            ToolDef("Bounds", R.drawable.ic_target_crosshair, violet) { org.ravi.codeassist.agent.ToolboxManager.getTool("configure_scroll_zone")?.onExecute() },
            ToolDef("Reset", R.drawable.ic_tool_reset, amber) { org.ravi.codeassist.agent.ToolboxManager.getTool("new_session")?.onExecute() },
            ToolDef("App", R.drawable.ic_nav_settings, mid) {
                val intent = android.content.Intent(context, org.ravi.codeassist.MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
            },
            ToolDef("Exit", R.drawable.ic_close, red) { showExitConfirm() },
        )
        val grid = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        val density = context.resources.displayMetrics.density
        val gap = (6*density).toInt()
        val cellW = context.resources.getDimension(R.dimen.sheet_tool_cell_w).toInt()
        tools.chunked(3).forEachIndexed { rowIdx, row ->
            val rowView = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEachIndexed { colIdx, tool ->
                val cell = buildToolCell(themed, tool.label, tool.iconRes, tool.accent, tool.action)
                val lp = LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (colIdx>0) marginStart = gap }
                rowView.addView(cell, lp)
            }
            grid.addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (rowIdx>0) topMargin = gap })
        }
        return grid
    }

    private fun buildToolCell(themed: Context, label: String, iconRes: Int, accent: Int, action: ()->Unit): LinearLayout {
        val density = context.resources.displayMetrics.density
        val chip = android.widget.FrameLayout(themed).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 30))
                cornerRadius = 10*density
            }
        }
        val icon = android.widget.ImageView(themed).apply { setImageResource(iconRes); setColorFilter(accent) }
        chip.addView(icon, android.widget.FrameLayout.LayoutParams((16*density).toInt(), (16*density).toInt(), Gravity.CENTER))
        val labelView = android.widget.TextView(themed).apply {
            text = label; setTextColor(ContextCompat.getColor(context, R.color.text_mid)); textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; isSingleLine = true
        }
        val cardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.surf_raised)); cornerRadius = 12*density
            setStroke((1*density).toInt(), ContextCompat.getColor(context, R.color.line_subtle))
        }
        val cell = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = cardBg
            isClickable = true; isFocusable = true
            setPadding(0, (10*density).toInt(), 0, (8*density).toInt())
            addView(chip, LinearLayout.LayoutParams((28*density).toInt(), (28*density).toInt()))
            addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6*density).toInt() })
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dismissSheet()
                action()
            }
        }
        return cell
    }

    private fun showExitConfirm() {
        val themed = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        try {
            val dlg = android.app.AlertDialog.Builder(themed)
                .setTitle("Exit agent?").setMessage("Current session will be stopped.")
                .setPositiveButton("Exit") { _,_ -> dismissSheet(); org.ravi.codeassist.AgentAccessibilityService.instance?.stopAgentSession() }
                .setNegativeButton("Keep", null).create()
            dlg.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dlg.show()
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(context, R.color.state_red))
        } catch (_:Exception) { org.ravi.codeassist.AgentAccessibilityService.instance?.stopAgentSession() }
    }

    private fun toggleSheet() { if (isSheetShowing) dismissSheet() else showSheet() }
    private fun showSheet() {
        val sheet = sheetView ?: return
        if (isSheetShowing) return
        isSheetShowing = true
        sheet.visibility = View.VISIBLE
        sheet.animate().cancel()
        sheet.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).withLayer()
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }
    private fun dismissSheet() {
        val sheet = sheetView ?: return
        if (!isSheetShowing) return
        isSheetShowing = false
        sheet.animate().cancel()
        sheet.animate().alpha(0f).translationY(12*context.resources.displayMetrics.density).scaleX(0.97f).scaleY(0.97f)
            .setDuration(160).withLayer().setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { sheet.visibility = View.GONE }.start()
    }

    private fun formatElapsed(s: Long): String { val m=s/60; val sec=s%60; return "$m:${sec.toString().padStart(2,'0')}" }
    private fun humanAction(a:String)=when(a){"type_text"->"Typing";"click_send"->"Sending";"read_latest_response"->"Reading";else->"Working"}
    private fun overlayUiFor(state: AgentState): OverlayUi {
        val tele = org.ravi.codeassist.agent.AgentOrchestrator.telemetry()
        val compact = "r${tele.round} · ${formatElapsed(tele.elapsedSeconds)}"
        return when(state){
            is AgentState.IDLE -> OverlayUi(ContextCompat.getColor(context,R.color.state_cyan),false,"Idle",compact)
            is AgentState.ANALYZING_SCREEN -> OverlayUi(ContextCompat.getColor(context,R.color.state_amber),true,"Scanning",compact)
            is AgentState.AWAITING_LLM -> OverlayUi(ContextCompat.getColor(context,R.color.state_amber),true,"Thinking",compact)
            is AgentState.EXECUTING_ACTION -> OverlayUi(ContextCompat.getColor(context,R.color.brand_mint),true,humanAction(state.actionName),compact)
            is AgentState.WAITING_FOR_MUTATION -> OverlayUi(ContextCompat.getColor(context,R.color.state_amber),true,"Writing",compact)
            is AgentState.WAITING_FOR_USER -> OverlayUi(ContextCompat.getColor(context,R.color.state_blue),false,"Paused",compact)
            is AgentState.ERROR -> OverlayUi(ContextCompat.getColor(context,R.color.state_red),false,"Stopped",state.message)
            is AgentState.TOOLBOX_OPEN -> OverlayUi(ContextCompat.getColor(context,R.color.state_violet),false,"Tools",compact)
            is AgentState.SCROLL_CONFIG_ACTIVE -> OverlayUi(ContextCompat.getColor(context,R.color.state_cyan),false,"Scroll zone",compact)
        }
    }
    private fun updateStatusInternal(state: AgentState){
        val pill = pillView ?: return
        val ui = overlayUiFor(state)
        lastUi = ui; isGenerating = ui.generating
        pill.applyUi(ui.accent, ui.generating, state is AgentState.WAITING_FOR_USER || state is AgentState.ERROR, ui.label, ui.sub)
    }
    fun setOverlayVisibility(v:Boolean){
        val view=rootView ?: return
        view.animate().cancel()
        if(v){
            view.visibility=View.VISIBLE; view.alpha=0f
            view.animate().alpha(1f).setDuration(120).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            dismissSheet()
            view.animate().alpha(0f).setDuration(100).withEndAction{ view.visibility=View.GONE; view.alpha=1f }.start()
        }
    }
    private var scrollPickerView: ScrollZonePickerView? = null
    private var scrollPickerContainer: View? = null
    fun showScrollZonePicker(profileId: Long, initialLeft: Float, initialTop: Float, initialRight: Float, initialBottom: Float, onSave: (Float, Float, Float, Float) -> Unit) {
        if (scrollPickerView != null) return
        var currentLeft = initialLeft;var currentTop = initialTop;var currentRight = initialRight;var currentBottom = initialBottom
        val picker = ScrollZonePickerView(context).apply {
            setInitialBounds(initialLeft, initialTop, initialRight, initialBottom)
            boundsListener = object : ScrollZonePickerView.OnBoundsChangedListener {
                override fun onBoundsChanged(leftPct: Float, topPct: Float, rightPct: Float, bottomPct: Float) {
                    currentLeft = leftPct; currentTop = topPct; currentRight = rightPct; currentBottom = bottomPct
                }
            }
        }
        scrollPickerView = picker
        val pickerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val themed = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val container = FrameLayout(themed)
        container.addView(picker)
        scrollPickerContainer = container
        val density = context.resources.displayMetrics.density
        val buttonContainer = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(context, R.color.surf_raised))
            setPadding((16*density).toInt(), (8*density).toInt(), (16*density).toInt(), (8*density).toInt())
        }
        val btnSave = com.google.android.material.button.MaterialButton(themed).apply {
            text = "Save Bounds"; setBackgroundColor(ContextCompat.getColor(context, R.color.brand_mint)); setTextColor(ContextCompat.getColor(context, R.color.brand_on_accent))
            setOnClickListener { onSave(currentLeft, currentTop, currentRight, currentBottom); hideScrollZonePicker() }
        }
        val btnCancel = com.google.android.material.button.MaterialButton(themed, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"; setTextColor(ContextCompat.getColor(context, R.color.text_hi))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply{ marginEnd = (16*density).toInt() }
            setOnClickListener { hideScrollZonePicker() }
        }
        buttonContainer.addView(btnCancel); buttonContainer.addView(btnSave)
        val frameParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply{ gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = (120*density).toInt() }
        container.addView(buttonContainer, frameParams)
        windowManager.addView(container, pickerParams)
    }
    fun hideScrollZonePicker(){ val c=scrollPickerContainer; scrollPickerView=null; scrollPickerContainer=null; if(c!=null) try{windowManager.removeView(c)}catch(_:Exception){}}
    fun hideOverlay(){ stateJob?.cancel(); stateJob=null; dismissSheet(); hideScrollZonePicker(); rootView?.let{try{windowManager.removeView(it)}catch(_:Exception){}}; rootView=null; pillView=null; sheetView=null; wrapper=null }
    fun destroy(){ hideOverlay(); scope.cancel() }
    private fun dp(v:Float)=v*context.resources.displayMetrics.density
}
