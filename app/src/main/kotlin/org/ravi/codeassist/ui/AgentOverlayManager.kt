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
    private var overlayRoot: View? = null
    private var wrapper: LinearLayout? = null
    private var pillView: CommandPillView? = null
    private var sheetView: View? = null
    private var isSheetShowing = false
    private var isGenerating = false
    private var lastUi = OverlayUi(0, false, "Idle", "Tap for tools")

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: kotlinx.coroutines.Job? = null

    val isShowing: Boolean get() = overlayRoot != null

    data class OverlayUi(val accent: Int, val generating: Boolean, val label: String, val sub: String)

    fun showOverlay(stateFlow: StateFlow<AgentState>, onStop: () -> Unit) {
        if (overlayRoot != null) return
        val themed = ContextThemeWrapper(context, R.style.Theme_CodeAssist)
        val density = context.resources.displayMetrics.density
        val defaultY = (120 * density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = defaultY
            x = context.getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE).getInt("OVERLAY_POS_X", 0).also { _ ->
                // y already set, x will be overridden after clamp
            }
        }
        val prefs = context.getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        params.x = prefs.getInt("OVERLAY_POS_X", 0)
        params.y = prefs.getInt("OVERLAY_POS_Y", defaultY)

        val wrapper = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        this.wrapper = wrapper

        // --- Sheet (modern bento, compact, no status) ---
        val sheet = createSheetView(themed, onClose = { dismissSheet() })
        sheet.visibility = View.GONE
        sheet.alpha = 0f
        sheet.scaleX = 0.96f
        sheet.scaleY = 0.96f
        sheet.translationY = 12 * density
        wrapper.addView(sheet, LinearLayout.LayoutParams(
            context.resources.getDimension(R.dimen.sheet_width).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8 * density).toInt() })
        sheetView = sheet

        // --- Pill ---
        val inflater = LayoutInflater.from(themed)
        val pill = inflater.inflate(R.layout.layout_agent_overlay, wrapper, false) as CommandPillView
        wrapper.addView(pill)
        pillView = pill
        overlayRoot = wrapper

        fun clamp(rawX: Int, rawY: Int): Pair<Int,Int> {
            val v = overlayRoot ?: return rawX to rawY
            val m = context.resources.displayMetrics
            val w = v.width.coerceAtLeast(1)
            val h = v.height.coerceAtLeast(1)
            val maxY = m.heightPixels - h
            val newY = (m.heightPixels - h - (m.heightPixels - h - rawY).coerceIn(0, maxY))
            // Simplified clamp: keep window inside screen
            val maxX = (m.widthPixels - w).coerceAtLeast(0)
            val newX = rawX.coerceIn(-maxX/2, maxX/2)
            return newX to rawY.coerceIn(0, maxY)
        }

        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var initX = 0; var initY = 0; var downX = 0f; var downY = 0f; var dragged = false
        var lastTap = 0L; val cooldown = 220L

        wrapper.setOnTouchListener { _, ev ->
            val lp = overlayRoot?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragged = false
                    initX = lp.x; initY = lp.y
                    downX = ev.rawX; downY = ev.rawY
                    pillView?.setPressFeedback(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        if (!dragged) { if (isSheetShowing) dismissSheet(); dragged = true; pillView?.setPressFeedback(false) }
                        val (nx, ny) = clamp((initX + dx).toInt(), (initY - dy).toInt())
                        lp.x = nx; lp.y = ny
                        try { windowManager.updateViewLayout(overlayRoot, lp) } catch (_:Exception){}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("OVERLAY_POS_X", lp.x).putInt("OVERLAY_POS_Y", lp.y).apply()
                    if (!dragged) {
                        pillView?.setPressFeedback(false)
                        wrapper.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
        // Pill tap also toggles (covers tap on pill area)
        pill.setOnClickListener { toggleSheet() }

        val morph = pill.findViewById<com.google.android.material.button.MaterialButton>(R.id.morphButton)
        morph.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (isSheetShowing) dismissSheet()
            else {
                if (isGenerating) onStop()
                else org.ravi.codeassist.AgentAccessibilityService.instance?.resumeOrSync()
            }
        }

        windowManager.addView(wrapper, params)
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
        }
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12*density).toInt(), (8*density).toInt(), (12*density).toInt(), (10*density).toInt())
        }
        card.addView(content)

        // Header: title + close
        val header = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = android.widget.TextView(themed).apply {
            text = "Tools"
            setTextColor(ContextCompat.getColor(context, R.color.text_hi))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
        }
        val close = com.google.android.material.button.MaterialButton(themed, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_close)
            iconSize = (12*density).toInt()
            iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_mid))
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surf_high))
            strokeWidth = 0
            cornerRadius = (10*density).toInt()
            insetLeft=0; insetTop=0; insetRight=0; insetBottom=0
            minimumWidth=0; minimumHeight=0
            layoutParams = LinearLayout.LayoutParams((24*density).toInt(), (24*density).toInt())
            setOnClickListener { onClose() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)
        content.addView(header)

        // Grid
        val grid = buildGridView(themed)
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10*density).toInt() })

        // Subtle footer hint
        val hint = android.widget.TextView(themed).apply {
            text = "Tap pill to close"
            setTextColor(ContextCompat.getColor(context, R.color.text_mid))
            textSize = 10f
            gravity = Gravity.CENTER
            alpha = 0.7f
            setPadding(0, (8*density).toInt(), 0, 0)
        }
        content.addView(hint)

        return card
    }

    private fun buildGridView(themed: Context): LinearLayout {
        val mint = ContextCompat.getColor(context, R.color.brand_mint)
        val violet = ContextCompat.getColor(context, R.color.state_violet)
        val amber = ContextCompat.getColor(context, R.color.state_amber)
        val mid = ContextCompat.getColor(context, R.color.text_mid)
        val red = ContextCompat.getColor(context, R.color.state_red)
        val tools = listOf(
            Triple("Resume", R.drawable.ic_stroke_play to mint) to { org.ravi.codeassist.agent.ToolboxManager.getTool("resume_session")?.onExecute() },
            Triple("Init", R.drawable.ic_play to mint) to { org.ravi.codeassist.agent.ToolboxManager.getTool("init_workspace")?.onExecute() },
            Triple("Bounds", R.drawable.ic_target_crosshair to violet) to { org.ravi.codeassist.agent.ToolboxManager.getTool("configure_scroll_zone")?.onExecute() },
            Triple("Reset", R.drawable.ic_tool_reset to amber) to { org.ravi.codeassist.agent.ToolboxManager.getTool("new_session")?.onExecute() },
            Triple("App", R.drawable.ic_nav_settings to mid) to {
                val intent = android.content.Intent(context, org.ravi.codeassist.MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
            },
            Triple("Exit", R.drawable.ic_close to red) to { showExitConfirm() },
        )
        val grid = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        val density = context.resources.displayMetrics.density
        val gap = (6*density).toInt()
        val cellW = context.resources.getDimension(R.dimen.sheet_tool_cell_w).toInt()
        tools.chunked(3).forEachIndexed { rowIdx, row ->
            val rowView = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEachIndexed { colIdx, (labelIcon, action) ->
                val (label, iconRes) = labelIcon
                // iconRes is Pair(res, color) actually stored as Pair<Int,Int> in second
                // Our tools list above stores Triple with Pair; unwrap:
                val resId = (iconRes as Pair<Int,Int>).first
                val accent = (iconRes as Pair<Int,Int>).second
                val cell = buildToolCell(themed, label, resId, accent, action)
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
        val icon = android.widget.ImageView(themed).apply {
            setImageResource(iconRes); setColorFilter(accent)
        }
        chip.addView(icon, FrameLayout.LayoutParams((16*density).toInt(), (16*density).toInt(), Gravity.CENTER))
        val labelView = android.widget.TextView(themed).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.text_mid))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true
        }
        val cardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.surf_raised))
            cornerRadius = 12*density
            setStroke((1*density).toInt(), ContextCompat.getColor(context, R.color.line_subtle))
        }
        val cell = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBg
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
                .setTitle("Exit agent?")
                .setMessage("Current session will be stopped.")
                .setPositiveButton("Exit") { _,_ ->
                    dismissSheet()
                    org.ravi.codeassist.AgentAccessibilityService.instance?.stopAgentSession()
                }
                .setNegativeButton("Keep", null)
                .create()
            dlg.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dlg.show()
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(context, R.color.state_red))
        } catch (_:Exception) {
            org.ravi.codeassist.AgentAccessibilityService.instance?.stopAgentSession()
        }
    }

    private fun toggleSheet() {
        if (isSheetShowing) dismissSheet() else showSheet()
    }
    private fun showSheet() {
        val sheet = sheetView ?: return
        if (isSheetShowing) return
        isSheetShowing = true
        sheet.visibility = View.VISIBLE
        sheet.animate().cancel()
        sheet.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(220).withLayer()
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
    private fun dismissSheet() {
        val sheet = sheetView ?: return
        if (!isSheetShowing) return
        isSheetShowing = false
        sheet.animate().cancel()
        sheet.animate()
            .alpha(0f).translationY(12*context.resources.displayMetrics.density).scaleX(0.97f).scaleY(0.97f)
            .setDuration(160).withLayer()
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { sheet.visibility = View.GONE }
            .start()
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
        val view=overlayRoot ?: return
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
    fun hideOverlay(){ stateJob?.cancel(); stateJob=null; dismissSheet(); hideScrollZonePicker(); overlayRoot?.let{try{windowManager.removeView(it)}catch(_:Exception){}}; overlayRoot=null; pillView=null; sheetView=null; wrapper=null }
    fun destroy(){ hideOverlay(); scope.cancel() }
    private fun dp(v:Float)=v*context.resources.displayMetrics.density
}
