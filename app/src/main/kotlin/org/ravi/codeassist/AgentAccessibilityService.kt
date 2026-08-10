package org.ravi.codeassist

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ravi.codeassist.database.AgentProfile
import org.ravi.codeassist.database.AgentRepository
import org.ravi.codeassist.database.CodeAssistDatabase
import org.ravi.codeassist.database.ElementRole
import org.ravi.codeassist.database.ElementSignature
import org.ravi.codeassist.ui.BoundingBoxView
import org.ravi.codeassist.utils.SignatureExtractor

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibility"
        var instance: AgentAccessibilityService? = null
    }

    private var windowManager: WindowManager? = null
    private var captureOverlayView: View? = null
    private var controlPanelView: View? = null
    private var confirmationView: View? = null
    private var boundingBoxView: BoundingBoxView? = null

    private var activeOverlayConfirmation: org.ravi.codeassist.ui.OverlayConfirmationManager? = null

    private var captureParams: WindowManager.LayoutParams? = null
    private var isCaptureMode = false
    private var currentProfileId: Long = -1
    private var currentTargetPackage: String? = null
    private var preSendBaselineText: String = ""
    private var preSendEnvelopeCount: Int = 0
    
    // Sentinel Architecture State
    private var lastBaselineCacheTime = 0L
    private var lastWindowContentChangeTime = 0L
    private var lastInputTextLength = -1

    private val repository by lazy { AgentRepository(CodeAssistDatabase.getDatabase(this)) }
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val shieldManager by lazy { org.ravi.codeassist.ui.AgentOverlayManager(this) }

    // Calibration State Machine
    private val calibrationSteps = listOf(ElementRole.INPUT_FIELD, ElementRole.SEND_BUTTON, ElementRole.RESPONSE_CONTAINER, ElementRole.STOP_BUTTON)
    private var currentStepIndex = 0
    private val collectedSignatures = mutableListOf<ElementSignature>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "Agent Accessibility Service Connected and Ready")
    }

    fun startAgentSession() {
        shieldManager.showShield(
            onSend = { prompt ->
                org.ravi.codeassist.agent.AgentOrchestrator.startLoop(prompt)
            },
            onStop = {
                org.ravi.codeassist.agent.AgentOrchestrator.updateState(org.ravi.codeassist.agent.AgentState.IDLE)
                shieldManager.updateStatus("IDLE")
            }
        )
    }

    fun injectSystemPrompt() {
        serviceScope.launch {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
            
            // Use clipboard text as goal if reasonable, otherwise use default exploratory goal
            val goal = if (!clipText.isNullOrBlank() && clipText.length < 1000 && !clipText.startsWith(":::CODE_ASSIST")) {
                clipText
            } else {
                "Explore the project, analyze it, and wait for my next instructions."
            }
            
            val prompt = org.ravi.codeassist.agent.AgentOrchestrator.buildSystemPrompt(goal, this@AgentAccessibilityService)
            executeToolCall("type_text", prompt)
            executeToolCall("click_send")
        }
    }

    fun openScrollZonePickerOverlay(profile: AgentProfile, onSaveCompleted: (Float, Float, Float, Float) -> Unit) {
        shieldManager.showScrollZonePicker(
            profileId = profile.id,
            initialLeft = profile.scrollLeftPct,
            initialTop = profile.scrollTopPct,
            initialRight = profile.scrollRightPct,
            initialBottom = profile.scrollBottomPct,
            onSave = { left, top, right, bottom ->
                serviceScope.launch {
                    val updatedProfile = profile.copy(
                        scrollLeftPct = left,
                        scrollTopPct = top,
                        scrollRightPct = right,
                        scrollBottomPct = bottom
                    )
                    repository.insertProfile(updatedProfile)
                    org.ravi.codeassist.agent.AgentOrchestrator.updateActiveProfile(updatedProfile)
                    
                    withContext(Dispatchers.Main) {
                        onSaveCompleted(left, top, right, bottom)
                    }
                }
            }
        )
    }

    fun stopAgentSession() {
        shieldManager.hideShield()
        org.ravi.codeassist.agent.AgentOrchestrator.updateState(org.ravi.codeassist.agent.AgentState.IDLE)
    }

    fun updateShieldStatus(status: String) {
        shieldManager.updateStatus(status)
    }

    fun addShieldMessage(role: String, text: String) {
        shieldManager.addMessage(role, text)
    }

    fun showConfirmationOverlay(commands: List<org.ravi.codeassist.CodeCommand>, workspaceRoot: String) {
        activeOverlayConfirmation?.destroy()
        activeOverlayConfirmation = org.ravi.codeassist.ui.OverlayConfirmationManager(this)
        activeOverlayConfirmation?.show(commands, workspaceRoot) { success, logs ->
            activeOverlayConfirmation?.destroy()
            activeOverlayConfirmation = null
            val hasDone = commands.any { it is org.ravi.codeassist.CodeCommand.Done }
            org.ravi.codeassist.agent.AgentOrchestrator.processExecutionResults(success, logs, hasDone)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopCalibration()
        activeOverlayConfirmation?.destroy()
        activeOverlayConfirmation = null
        shieldManager.destroy()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val currentState = org.ravi.codeassist.agent.AgentOrchestrator.state.value
        if (currentState !is org.ravi.codeassist.agent.AgentState.IDLE && currentState !is org.ravi.codeassist.agent.AgentState.WAITING_FOR_USER) {
            return
        }

        // --- 1. Rolling Baseline Cache ---
        // Continuously caches the response container state so we have a perfect pre-send baseline,
        // without risking the race condition of calculating it AFTER the send event.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val now = System.currentTimeMillis()
            if (now - lastBaselineCacheTime > 500L) { // Debounce baseline caching
                lastBaselineCacheTime = now
                serviceScope.launch {
                    val activeProfile = org.ravi.codeassist.agent.AgentOrchestrator.getActiveProfile()
                    if (activeProfile != null) {
                        val signatures = org.ravi.codeassist.agent.AgentOrchestrator.getActiveSignatures()
                        val containerSig = signatures.find { it.role == ElementRole.RESPONSE_CONTAINER }
                        if (containerSig != null) {
                            val matches = findAllNodesBySignature(containerSig)
                            val currentText = matches.joinToString("\n") { extractAllText(it, 0) }.trim()
                            if (currentText.isNotEmpty()) {
                                preSendBaselineText = currentText
                            }
                            matches.forEach { try { it.recycle() } catch (e: Exception) {} }
                        }
                    }
                }
            }
        }

        // --- 2. State-Transition Sentinel (Detecting the LLM starting to generate) ---
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastWindowContentChangeTime > 250L) { // Debounce DOM scanning
                lastWindowContentChangeTime = now
                
                serviceScope.launch {
                    val activeProfile = org.ravi.codeassist.agent.AgentOrchestrator.getActiveProfile()
                    if (activeProfile != null) {
                        val signatures = org.ravi.codeassist.agent.AgentOrchestrator.getActiveSignatures()
                        var triggerDetected = false
                        
                        // Trigger A: Stop Button Emergence
                        val stopSig = signatures.find { it.role == ElementRole.STOP_BUTTON }
                        if (stopSig != null) {
                            val stopNode = findNodeBySignature(stopSig)
                            if (stopNode != null && stopNode.isVisibleToUser) {
                                Log.i(TAG, "Sentinel: STOP_BUTTON emerged. LLM generation started.")
                                triggerDetected = true
                            }
                            try { stopNode?.recycle() } catch (e: Exception) {}
                        }
                        
                        // Trigger B: Input Field Flush (Fail-safe)
                        if (!triggerDetected) {
                            val inputSig = signatures.find { it.role == ElementRole.INPUT_FIELD }
                            if (inputSig != null) {
                                val inputNode = findNodeBySignature(inputSig)
                                if (inputNode != null) {
                                    val currentInputLength = inputNode.text?.length ?: 0
                                    if (lastInputTextLength > 0 && currentInputLength == 0) {
                                        Log.i(TAG, "Sentinel: Input Field Flushed. LLM generation started.")
                                        triggerDetected = true
                                    }
                                    lastInputTextLength = currentInputLength
                                }
                                try { inputNode?.recycle() } catch (e: Exception) {}
                            }
                        }
                        
                        if (triggerDetected) {
                            autoResumeFromSentinel(signatures)
                        }
                    }
                }
            }
        }
    }

    private fun autoResumeFromSentinel(signatures: List<ElementSignature>) {
        val currentState = org.ravi.codeassist.agent.AgentOrchestrator.state.value
        if (currentState !is org.ravi.codeassist.agent.AgentState.IDLE && currentState !is org.ravi.codeassist.agent.AgentState.WAITING_FOR_USER) {
            return
        }

        // Lock state immediately to prevent duplicate triggers
        org.ravi.codeassist.agent.AgentOrchestrator.updateState(org.ravi.codeassist.agent.AgentState.WAITING_FOR_MUTATION)
        
        serviceScope.launch {
            withContext(Dispatchers.Main) { 
                updateShieldStatus("Auto-Resume: Waiting for LLM...") 
                shieldManager.setShieldVisibility(true)
            }

            val containerSig = signatures.find { it.role == ElementRole.RESPONSE_CONTAINER }
            if (containerSig != null) {
                Log.d(TAG, "Sentinel Auto-Resume: Using Rolling Baseline (${preSendBaselineText.length} chars)")
                val text = waitForMutationAndScrape(containerSig)
                org.ravi.codeassist.agent.AgentOrchestrator.resumeFromText(text)
            } else {
                org.ravi.codeassist.agent.AgentOrchestrator.resumeFromText("Error: RESPONSE_CONTAINER signature missing. Cannot auto-resume.")
            }
        }
    }

    /**
     * Phase 6: O(1) Execution Primitives
     * Dispatches a synthetic tap bypassing the expensive DOM traversal.
     */
    fun performSyntheticTap(x: Float, y: Float): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()
        
        if (x < 0f || y < 0f || x > screenWidth || y > screenHeight) {
            Log.w(TAG, "performSyntheticTap: Coordinates ($x, $y) out of bounds. Rejecting tap to avoid unintended clicks.")
            return false
        }

        val path = android.graphics.Path().apply {
            moveTo(x, y)
        }
        // Using 120L provides a more definitive touch registration on complex custom views
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 120L)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a synthetic swipe gesture for scrolling when DOM-based scrolling fails.
     */
    fun performSyntheticScroll(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val safeStartX = startX.coerceAtLeast(0f)
        val safeStartY = startY.coerceAtLeast(0f)
        val safeEndX = endX.coerceAtLeast(0f)
        val safeEndY = endY.coerceAtLeast(0f)

        val path = android.graphics.Path().apply {
            moveTo(safeStartX, safeStartY)
            lineTo(safeEndX, safeEndY)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300L)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Phase 6: Text Injection
     * Uses Clipboard + ACTION_PASTE to bypass chat apps that ignore ACTION_SET_TEXT.
     */
    suspend fun performSetTextAction(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false

        // Optimized Safe Limit: 200,000 characters (~400KB). Pushing more than this into 
        // AccessibilityNodeInfo.performAction() risks hitting the 1MB Binder transaction limit.
        val safeText = if (text.length > 200_000) {
            text.take(200_000) + "\n\n... [CONTENT TRUNCATED]\n>>> SYSTEM INSTRUCTION: Payload too large. You must use line-numbered READ commands to read smaller chunks.\n:::END_TRANSACTION_RESULT:::\nEvaluate and proceed. Terminate with DONE if completed."
        } else text

        var success = false
        val sharedPref = getSharedPreferences("CodeAssistPrefs", android.content.Context.MODE_PRIVATE)
        val inputMode = sharedPref.getString("PREF_INPUT_MODE", "DIRECT")

        if (inputMode == "DIRECT") {
            try {
                val arguments = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, safeText)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    success = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "performSetTextAction: ACTION_SET_TEXT failed", e)
            }
        }

        if (!success) {
            // Tier 1: Clipboard + PASTE (Fallback or CLIPBOARD mode)
            try {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Agent Input", safeText)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
                clipboard.setPrimaryClip(clip)

                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                kotlinx.coroutines.delay(250) 

                val currentClipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (currentClipText == safeText) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                        success = true
                        if (inputMode == "DIRECT") {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                Toast.makeText(this@AgentAccessibilityService, "Direct injection blocked by OS. Used Clipboard fallback.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "performSetTextAction: Clipboard write blocked. Bypassing ACTION_PASTE.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "performSetTextAction: Paste failed", e)
            }
        }

        if (!success && inputMode != "DIRECT") {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, safeText)
            }
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        // Tier 3: The Gemini TextWatcher Bypass (Hybrid Trigger)
        // Some apps (like Gemini) only reveal the SEND button if a synthetic physical keystroke or secondary edit occurs.
        if (success) {
            kotlinx.coroutines.delay(150)
            // Append a space dynamically to force a TextWatcher state mutation
            val triggerArgs = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$safeText ")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, triggerArgs)
        }
        return success
    }

    /**
     * Scans all valid windows for a node matching the provided ID.
     * Prevents the Glass Shield overlay or System UI from blocking the search.
     */
    /**
     * Pillar 1: Multi-Modal Node Fingerprinting
     * Scans the active window tree using a weighted heuristic scoring matrix instead of rigid IDs.
     */
    private fun scoreNode(node: AccessibilityNodeInfo, sig: ElementSignature): Int {
        var score = 0
        // Primary Identifiers
        if (!sig.resourceId.isNullOrEmpty() && node.viewIdResourceName == sig.resourceId) score += 50
        
        // Semantic attributes are highly resilient to layout shifts, giving them a heavy weight
        val nodeDesc = node.contentDescription?.toString()
        if (!sig.contentDescription.isNullOrEmpty() && nodeDesc != null && nodeDesc.contains(sig.contentDescription, ignoreCase = true)) score += 80
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nodeHint = node.hintText?.toString()
            if (!sig.hintText.isNullOrEmpty() && nodeHint != null && nodeHint.contains(sig.hintText, ignoreCase = true)) score += 80
        }
        
        // Secondary/Supporting Identifier
        if (!sig.className.isNullOrEmpty() && node.className?.toString() == sig.className) score += 25

        return score
    }

    private fun getScoredNodes(sig: ElementSignature): List<Pair<AccessibilityNodeInfo, Int>> {
        val scoredMatches = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        val keepAlive = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        val visited = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()

        fun consider(node: AccessibilityNodeInfo) {
            if (keepAlive.containsKey(node) || visited.containsKey(node)) return
            visited.put(node, true)
            val score = scoreNode(node, sig)
            if (score >= 40) {
                scoredMatches.add(Pair(node, score))
                keepAlive.put(node, true)
            }
        }

        fun searchNode(node: AccessibilityNodeInfo?) {
            if (node == null) return
            consider(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                searchNode(child)
                if (child != null && !keepAlive.containsKey(child)) {
                    try { child.recycle() } catch (_: Exception) {}
                }
            }
        }

        val rootsToScan = mutableListOf<AccessibilityNodeInfo>()
        for (window in windows) {
            val root = window.root
            if (root != null) {
                if (root.packageName?.toString() != packageName) {
                    rootsToScan.add(root)
                } else {
                    try { root.recycle() } catch (_: Exception) {}
                }
            }
        }

        if (rootsToScan.isEmpty()) {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                if (activeRoot.packageName?.toString() != packageName) {
                    rootsToScan.add(activeRoot)
                } else {
                    try { activeRoot.recycle() } catch (_: Exception) {}
                }
            }
        }

        rootsToScan.forEach { root ->
            if (!sig.resourceId.isNullOrEmpty()) {
                val fastMatches = root.findAccessibilityNodeInfosByViewId(sig.resourceId)
                fastMatches.forEach { match ->
                    consider(match)
                    if (!keepAlive.containsKey(match)) {
                        try { match.recycle() } catch (_: Exception) {}
                    }
                }
            }
            searchNode(root)
        }

        rootsToScan.forEach { root ->
            if (!keepAlive.containsKey(root)) {
                try { root.recycle() } catch (_: Exception) {}
            }
        }

        return scoredMatches.toList()
    }

    fun findNodeBySignature(sig: ElementSignature, prioritizeLast: Boolean = true): AccessibilityNodeInfo? {
        val scoredMatches = getScoredNodes(sig)
        if (scoredMatches.isEmpty()) return null

        var finalMatch: AccessibilityNodeInfo? = null
        try {
            val visibleMatches = scoredMatches.filter { it.first.isVisibleToUser }
            val candidateMatches = if (visibleMatches.isNotEmpty()) visibleMatches else scoredMatches

            // Sort by highest score first
            val bestScore = candidateMatches.maxOfOrNull { it.second } ?: return null
            val topCandidates = candidateMatches.filter { it.second == bestScore }.map { it.first }

            if (topCandidates.size == 1) {
                finalMatch = topCandidates.first()
                return finalMatch
            }

            // Tie-breaker 1: Hierarchy Path
            if (!sig.hierarchyPath.isNullOrEmpty()) {
                val pathMatches = topCandidates.filter { org.ravi.codeassist.utils.SignatureExtractor.generateXPath(it) == sig.hierarchyPath }
                if (pathMatches.size == 1) {
                    finalMatch = pathMatches.first()
                    return finalMatch
                }
            }

            // Tie-breaker 2: Spatial Distance (Weighted heavily towards X-axis stability)
            val isKeyboardOpen = windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val bestMatch = topCandidates.minByOrNull {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                val dx = (rect.centerX() - sig.boundsX).toDouble()
                
                // Vertical shifts (banners, notifications) are common. Drastically reduce dy penalty.
                // If keyboard is open, eliminate it entirely.
                val dy = if (isKeyboardOpen) 0.0 else (rect.centerY() - sig.boundsY).toDouble() * 0.1 
                
                (dx * dx) + (dy * dy)
            }

            finalMatch = bestMatch ?: if (prioritizeLast) topCandidates.last() else topCandidates.first()
            return finalMatch
        } finally {
            scoredMatches.forEach {
                if (it.first != finalMatch) {
                    try { it.first.recycle() } catch (e: Exception) {}
                }
            }
        }
    }

    fun findAllNodesBySignature(sig: ElementSignature): List<AccessibilityNodeInfo> {
        val scoredMatches = getScoredNodes(sig)
        val visibleMatches = scoredMatches.filter { it.first.isVisibleToUser }
        val results = if (visibleMatches.isNotEmpty()) visibleMatches else scoredMatches

        val finalNodes = results.sortedByDescending { it.second }.map { it.first }
        
        // Memory Leak Fix: Recycle any nodes from scoredMatches not returned in finalNodes
        scoredMatches.forEach { 
            if (!finalNodes.contains(it.first)) {
                try { it.first.recycle() } catch (e: Exception) {}
            }
        }

        return finalNodes
    }

        /**
     * Phase 6: Executes a requested tool action autonomously.
     */
    private suspend fun dismissKeyboardIfVisible() {
        try {
            val isKeyboardOpen = windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (isKeyboardOpen) {
                Log.d(TAG, "dismissKeyboardIfVisible: Soft keyboard detected. Dispatching GLOBAL_ACTION_BACK to clear UI.")
                performGlobalAction(GLOBAL_ACTION_BACK)
                // Suspend instead of blocking thread to allow UI layout shift
                kotlinx.coroutines.delay(1200) 
            }
        } catch (e: Exception) {
            Log.w(TAG, "dismissKeyboardIfVisible: Failed to check or dismiss keyboard", e)
        }
    }
        
    suspend fun executeToolCall(toolName: String, textArg: String? = null): String {
        val signatures = org.ravi.codeassist.agent.AgentOrchestrator.getActiveSignatures()
        Log.d(TAG, "executeToolCall: Tool=$toolName, TextArg=${textArg?.take(20)}")

        return when (toolName) {
            "type_text" -> {
                val sig = signatures.find { it.role == ElementRole.INPUT_FIELD } ?: return "Error: No INPUT_FIELD signature."
                val node = findNodeBySignature(sig)
                if (node != null && textArg != null) {
                    val success = performSetTextAction(node, textArg)
                    Log.d(TAG, "executeToolCall(type_text): performSetTextAction success=$success")
                    if (success) {
                        // Buffer for Compose UI to swap Mic -> Send Button
                        kotlinx.coroutines.delay(500)
                        "Success: Typed text."
                    } else {
                        "Error: Failed to set text on node."
                    }
                } else {
                    Log.w(TAG, "executeToolCall(type_text): INPUT_FIELD not found. Triggering fallback rescan.")
                    triggerFallbackRescan("INPUT_FIELD not found at expected signature.")
                }
            }
            "click_send" -> {
                dismissKeyboardIfVisible()
                val sig = signatures.find { it.role == ElementRole.SEND_BUTTON } ?: return "Error: No SEND_BUTTON signature."
                
                // Capture baseline text before dispatching click for Stateful String Diffing
                val containerSig = signatures.find { it.role == ElementRole.RESPONSE_CONTAINER }
                preSendBaselineText = if (containerSig != null) {
                    val matches = findAllNodesBySignature(containerSig)
                    matches.joinToString("\n") { extractAllText(it, 0) }.trim()
                } else {
                    ""
                }
                
                val preRoot = rootInActiveWindow
                val globalPreText = extractAllText(preRoot, 0)
                try { preRoot?.recycle() } catch (_: Exception) {}
                preSendEnvelopeCount = globalPreText.split(":::CODE_ASSIST:::").size - 1
                
                Log.d(TAG, "executeToolCall(click_send): Captured baseline length = ${preSendBaselineText.length}, Pre-Envelopes = $preSendEnvelopeCount")

                withContext(Dispatchers.Main) { shieldManager.setShieldVisibility(false) }
                kotlinx.coroutines.delay(350) 
                        
                val node = findNodeBySignature(sig)
                var result = ""
                        
                if (node != null) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        result = "Success: Native DOM click applied to SEND_BUTTON."
                    } else {
                        val liveBounds = android.graphics.Rect()
                        node.getBoundsInScreen(liveBounds)
                        val success = performSyntheticTap(liveBounds.centerX().toFloat(), liveBounds.centerY().toFloat())
                        kotlinx.coroutines.delay(250)
                        result = if (success) "Success: Dynamic tap applied at live coordinates." else "Error: Synthetic tap failed."
                    }
                } else {
                    // Pillar 5: Co-Pilot HUD Override (Human-in-the-Loop Fallback)
                    Log.w(TAG, "executeToolCall(click_send): SEND_BUTTON not found. Triggering Co-Pilot Override.")
                    withContext(Dispatchers.Main) { 
                        shieldManager.setShieldVisibility(true)
                        shieldManager.updateStatus("Co-Pilot Override: Please tap Send manually to resume.")
                    }
                        
                    val inputSig = signatures.find { it.role == ElementRole.INPUT_FIELD }
                    var userTappedSend = false
                    if (inputSig != null) {
                        for (i in 0..45) { // Allow user 45 seconds to manually intervene
                            kotlinx.coroutines.delay(1000)
                            val inputNode = findNodeBySignature(inputSig)
                            // If input field text is wiped, it means the user hit send
                            if (inputNode?.text.isNullOrBlank()) {
                                userTappedSend = true
                                break
                            }
                        }
                    }
                        
                    if (userTappedSend) {
                        result = "Success: User manually intervened and triggered send."
                    } else {
                        result = triggerFallbackRescan("SEND_BUTTON node not found and manual intervention timed out.")
                    }
                }

                withContext(Dispatchers.Main) { shieldManager.setShieldVisibility(true) }
                result
            }
            "read_latest_response" -> {
                val sig = signatures.find { it.role == ElementRole.RESPONSE_CONTAINER } ?: return "Error: No RESPONSE_CONTAINER signature."
                Log.d(TAG, "executeToolCall(read_latest_response): Starting waitForMutationAndScrape")
                val responseText = waitForMutationAndScrape(sig)
                Log.d(TAG, "executeToolCall(read_latest_response): Result length = ${responseText.length}")
                "Success: Scraped response -> $responseText"
            }
            else -> "Error: Unknown tool."
        }
    }

    /**
     * Phase 6.8 & 6.9: Polling Loop and Stabilization Detector
     */
    private suspend fun waitForMutationAndScrape(sig: ElementSignature): String {
        org.ravi.codeassist.agent.AgentOrchestrator.updateState(org.ravi.codeassist.agent.AgentState.WAITING_FOR_MUTATION)
        
        val signatures = org.ravi.codeassist.agent.AgentOrchestrator.getActiveSignatures()
        val stopSig = signatures.find { it.role == ElementRole.STOP_BUTTON }
            
        var currentText = ""
        var hasDiverged = false

        // Phase 1: Cold-Start Buffer (Wait up to 45s for text to diverge OR Stop Button to appear)
        var coldStartElapsed = 0
        var stopNodeExists = false
            
        while (coldStartElapsed < 45000) {
            kotlinx.coroutines.delay(1000)
            coldStartElapsed += 1000

            if (stopSig != null) {
                val stopNode = findNodeBySignature(stopSig)
                if (stopNode != null && stopNode.isVisibleToUser) {
                    stopNodeExists = true
                    hasDiverged = true
                    Log.d(TAG, "waitForMutationAndScrape: STOP_BUTTON appeared after ${coldStartElapsed}ms")
                    try { stopNode.recycle() } catch (e: Exception) {}
                    break
                }
                try { stopNode?.recycle() } catch (e: Exception) {}
            }
                
            val matches = findAllNodesBySignature(sig)
            currentText = matches.joinToString("\n") { extractAllText(it, 0) }.trim()
            matches.forEach { try { it.recycle() } catch (e: Exception) {} }

            if (currentText != preSendBaselineText) {
                hasDiverged = true
                Log.d(TAG, "waitForMutationAndScrape: Text diverged after ${coldStartElapsed}ms")
                break
            }
        }

        if (!hasDiverged) {
            Log.w(TAG, "waitForMutationAndScrape: Timeout (45s) waiting for LLM to start generating.")
            return "Error: LLM Generation Timeout. The screen did not change after clicking send."
        }

        // Phase 2: Generation/Stabilization Loop (With Continuous Stream Stitching)
        var lastSeenText = currentText
        var accumulatedText = currentText
        var stableCount = 0
        var stabilizationElapsed = 0
        var lastScrolledText: String? = null

        val sharedPref = getSharedPreferences("CodeAssistPrefs", android.content.Context.MODE_PRIVATE)

        if (stopNodeExists && stopSig != null) {
            // Rely on STOP_BUTTON disappearance
            var isGenerating = true
            while (isGenerating && stabilizationElapsed < 120000) {
                kotlinx.coroutines.delay(1000)
                stabilizationElapsed += 1000

                val checkNode = findNodeBySignature(stopSig)
                if (checkNode == null || !checkNode.isVisibleToUser) {
                    isGenerating = false
                    Log.d(TAG, "waitForMutationAndScrape: STOP_BUTTON disappeared.")
                } else {
                    // Auto-scrolling feature: scroll response container if Stop Button is still visible
                    val matches = findAllNodesBySignature(sig)
                    val newText = matches.joinToString("\n") { extractAllText(it, 0) }.trim()
                    
                    if (newText == lastSeenText) {
                        stableCount++
                        if (stableCount >= 2 && matches.isNotEmpty() && newText != lastScrolledText) {
                            var scrollTarget: AccessibilityNodeInfo? = matches.last()
                            var bestScrollableNode: AccessibilityNodeInfo? = null
                            var scrolled = false
                            
                            // Walk up the DOM tree until we find the actual scrollable container
                            while (scrollTarget != null) {
                                val hasScrollForward = scrollTarget.actionList?.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id } == true
                                if (scrollTarget.isScrollable || hasScrollForward) {
                                    bestScrollableNode = scrollTarget
                                    scrolled = scrollTarget.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                    if (scrolled) break
                                }
                                val parent = scrollTarget.parent
                                if (scrollTarget != matches.last() && scrollTarget != bestScrollableNode) {
                                    try { scrollTarget.recycle() } catch (e: Exception) {}
                                }
                                scrollTarget = parent
                            }
                            
                            if (!scrolled) {
                                val displayMetrics = resources.displayMetrics
                                val screenX: Float
                                val startY: Float
                                val endY: Float
                                
                                val profile = org.ravi.codeassist.agent.AgentOrchestrator.getActiveProfile()
                                if (profile != null && (profile.scrollLeftPct != 0.0f || profile.scrollTopPct != 0.0f || profile.scrollRightPct != 1.0f || profile.scrollBottomPct != 1.0f)) {
                                    val pLeft = profile.scrollLeftPct * displayMetrics.widthPixels
                                    val pTop = profile.scrollTopPct * displayMetrics.heightPixels
                                    val pRight = profile.scrollRightPct * displayMetrics.widthPixels
                                    val pBottom = profile.scrollBottomPct * displayMetrics.heightPixels
                                    
                                    screenX = (pLeft + pRight) / 2f
                                    startY = pTop + ((pBottom - pTop) * 0.8f)
                                    endY = pTop + ((pBottom - pTop) * 0.2f)
                                    Log.d(TAG, "waitForMutationAndScrape: Using calibrated profile scroll zone boundaries: left=$pLeft, top=$pTop, right=$pRight, bottom=$pBottom")
                                } else if (bestScrollableNode != null) {
                                    val bounds = android.graphics.Rect()
                                    bestScrollableNode.getBoundsInScreen(bounds)
                                    screenX = bounds.centerX().toFloat()
                                    // Start drag at 80% down the container bounds, end at 20%
                                    startY = bounds.top + (bounds.height() * 0.8f)
                                    endY = bounds.top + (bounds.height() * 0.2f)
                                } else {
                                    screenX = displayMetrics.widthPixels / 2f
                                    // ULTRA SAFE ZONE for Tablets/Landscape: 45% to 35% (Upper Middle).
                                    // In landscape, bottom input bars and keyboards can consume up to 60% of the bottom screen.
                                    // Keeping the swipe strictly between 35% and 45% guarantees it avoids top headers and bottom bars.
                                    startY = displayMetrics.heightPixels * 0.45f
                                    endY = displayMetrics.heightPixels * 0.35f
                                }
                                scrolled = performSyntheticScroll(screenX, startY, screenX, endY)
                            }
                            
                            if (bestScrollableNode != null && bestScrollableNode != matches.last()) {
                                try { bestScrollableNode.recycle() } catch (e: Exception) {}
                            }

                            if (scrolled) {
                                lastScrolledText = newText
                                stableCount = 0
                                Log.d(TAG, "waitForMutationAndScrape: Auto-scrolling forward. Success: $scrolled")
                            }
                        }
                    } else {
                        stableCount = 0
                        val overlapIndex = findOverlap(accumulatedText, newText)
                        if (overlapIndex > 0) {
                            accumulatedText += newText.substring(overlapIndex)
                        } else if (!accumulatedText.contains(newText)) {
                            accumulatedText += "\n" + newText
                        }
                        lastSeenText = newText
                    }
                    matches.forEach { try { it.recycle() } catch (e: Exception) {} }
                }
                try { checkNode?.recycle() } catch (e: Exception) {}
            }
            
            // Add a 500ms stabilization buffer after STOP_BUTTON disappears
            kotlinx.coroutines.delay(500)
        } else {
            // Rely on Text Stabilization
            while (stableCount < 3 && stabilizationElapsed < 120000) {
                kotlinx.coroutines.delay(1000)
                stabilizationElapsed += 1000

                val matches = findAllNodesBySignature(sig)
                currentText = matches.joinToString("\n") { extractAllText(it, 0) }.trim()
                matches.forEach { try { it.recycle() } catch (e: Exception) {} }

                if (currentText == lastSeenText) {
                    stableCount++
                    Log.d(TAG, "waitForMutationAndScrape: Text stabilizing... ($stableCount/3)")
                } else {
                    stableCount = 0

                    // Stitch streams to handle auto-scrolling responses where top text disappears
                    val overlapIndex = findOverlap(accumulatedText, currentText)
                    if (overlapIndex > 0) {
                        accumulatedText += currentText.substring(overlapIndex)
                    } else if (!accumulatedText.contains(currentText)) {
                        accumulatedText += "\n" + currentText
                    }

                    lastSeenText = currentText
                    Log.d(TAG, "waitForMutationAndScrape: Text mutating...")
                }
            }
        }

        // Final text pull
        kotlinx.coroutines.delay(1000) // 1s buffer for final UI render
        val finalMatches = findAllNodesBySignature(sig)
        val finalText = finalMatches.joinToString("\n") { extractAllText(it, 0) }.trim()
        finalMatches.forEach { try { it.recycle() } catch (e: Exception) {} }

        val overlapIndex = findOverlap(accumulatedText, finalText)
        if (overlapIndex > 0) {
            accumulatedText += finalText.substring(overlapIndex)
        } else if (!accumulatedText.contains(finalText)) {
            accumulatedText += "\n" + finalText
        }

        // Phase 3: Extract Delta (Stateful String Diffing on Accumulated Stream)
        Log.d(TAG, "waitForMutationAndScrape: Applying String Diffing on Accumulated Stream.")
        val finalResponse = if (preSendBaselineText.isNotEmpty() && accumulatedText.contains(preSendBaselineText)) {
            accumulatedText.replace(preSendBaselineText, "").trim()
        } else {
            // Fallback if baseline completely scrolled out before accumulation began
            accumulatedText
        }

        return finalResponse.ifEmpty { "Error: Scraped delta was empty after stabilization." }
    }

    private fun findOverlap(oldText: String, newText: String): Int {
        val minLen = Math.min(oldText.length, newText.length)
        for (i in minLen downTo 1) {
            if (oldText.endsWith(newText.substring(0, i))) {
                return i
            }
        }
        return 0
    }

    private fun extractAllText(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return ""
        val sb = java.lang.StringBuilder()

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (depth == 0) Log.d(TAG, "extractAllText: Starting extraction at root node.")

        if (!text.isNullOrBlank()) {
            sb.append(text).append("\n")
        } else if (!desc.isNullOrBlank()) {
            sb.append(desc).append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val childText = extractAllText(child, depth + 1)
            if (childText.isNotBlank()) {
                sb.append(childText).append("\n")
            }
            child?.recycle()
        }

        val result = sb.toString().trim()
        if (depth == 0) Log.d(TAG, "extractAllText: Final extracted length = ${result.length}")
        return result
    }

    /**
     * Phase 6.7: Fallback logic for when rigid signatures break.
     */
    private fun triggerFallbackRescan(reason: String): String {
        val targetPackage = org.ravi.codeassist.agent.AgentOrchestrator.getActiveProfile()?.packageName
        var fallbackRoot: AccessibilityNodeInfo? = null
        
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == targetPackage) {
                fallbackRoot = root
                break
            }
        }
        if (fallbackRoot == null) fallbackRoot = rootInActiveWindow
        
        val semanticTree = org.ravi.codeassist.utils.TreeMinimizer.flatten(fallbackRoot)
        
        try {
            fallbackRoot?.recycle()
        } catch (e: Exception) {}
        
        return "Error: $reason\n--- UI FALLBACK STATE ---\n$semanticTree\nInstruction: The layout changed. Use the Semantic Tree above to locate the target element and issue a new tool call."
    }

override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    fun startCalibration(profileId: Long, targetPackage: String) {
        if (captureOverlayView != null) return // Already calibrating
        org.ravi.codeassist.agent.AgentOrchestrator.updateState(org.ravi.codeassist.agent.AgentState.IDLE)
        currentProfileId = profileId
        currentTargetPackage = targetPackage
        isCaptureMode = false
        currentStepIndex = 0
        collectedSignatures.clear()

        setupCaptureOverlay()
        setupControlPanel()
        updateStatusText()
    }

    fun stopCalibration() {
        removeViewSafely(captureOverlayView)
        removeViewSafely(controlPanelView)
        removeViewSafely(confirmationView)

        captureOverlayView = null
        controlPanelView = null
        confirmationView = null
        boundingBoxView = null
        currentProfileId = -1
        currentTargetPackage = null
    }

    private fun removeViewSafely(view: View?) {
        view?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Ignore if view is already detached
            }
        }
    }

    private fun setupCaptureOverlay() {
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        captureOverlayView = inflater.inflate(R.layout.layout_calibration_capture, null)
        boundingBoxView = captureOverlayView?.findViewById(R.id.boundingBoxView)

        captureParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager?.addView(captureOverlayView, captureParams)
    }

    private fun setupControlPanel() {
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        controlPanelView = inflater.inflate(R.layout.layout_calibration_controls, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 150 
        }

        val dragHandle = controlPanelView?.findViewById<View>(R.id.calibrationDragHandle)
        val ivDragTarget = controlPanelView?.findViewById<View>(R.id.ivDragTarget)
        val btnCancel = controlPanelView?.findViewById<View>(R.id.btnCancelCalibration)
        val btnSkip = controlPanelView?.findViewById<View>(R.id.btnSkipCalibrationStep)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        controlPanelView?.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY - (event.rawY - initialTouchY).toInt()
                    try { windowManager?.updateViewLayout(controlPanelView, params) } catch (e: Exception) {}
                    true
                }
                else -> false
            }
        }

        ivDragTarget?.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isCaptureMode = true
                    updateStatusText()
                    boundingBoxView?.targetRect = null
                    removeViewSafely(confirmationView)
                    confirmationView = null
                    updateSpotlightLive(event.rawX.toInt(), event.rawY.toInt())
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    updateSpotlightLive(event.rawX.toInt(), event.rawY.toInt())
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isCaptureMode = false
                    updateStatusText()
                    handleCaptureTouch(event.rawX.toInt(), event.rawY.toInt())
                    true
                }
                else -> false
            }
        }

        btnCancel?.setOnClickListener {
            stopCalibration()
        }

        windowManager?.addView(controlPanelView, params)
    }

    private fun updateStatusText() {
        val tvStatus = controlPanelView?.findViewById<TextView>(R.id.tvCalibrationStatus)
        val tvSubStatus = controlPanelView?.findViewById<TextView>(R.id.tvCalibrationSubStatus)
        val btnSkip = controlPanelView?.findViewById<View>(R.id.btnSkipCalibrationStep)

        btnSkip?.visibility = View.GONE
        
        val currentRole = if (currentStepIndex < calibrationSteps.size) calibrationSteps[currentStepIndex].name else "DONE"
            
        if (isCaptureMode) {
            tvStatus?.text = "Scanning..."
            tvSubStatus?.text = "Release to capture $currentRole"
        } else {
            tvStatus?.text = "Locate $currentRole"
            tvSubStatus?.text = "Drag crosshair to select"
        }
    }

    private fun updateSpotlightLive(x: Int, y: Int) {
        if (confirmationView != null) return
        
        var globalBestNode: AccessibilityNodeInfo? = null
        var globalMinArea = Int.MAX_VALUE

        fun scanRootFast(root: AccessibilityNodeInfo?) {
            if (root == null) return
            val pkg = root.packageName?.toString()
            if (pkg == packageName) return

            val (localBest, localMinArea) = findSmallestNodeAt(root, x, y)
            if (localBest != null && localMinArea < globalMinArea) {
                globalMinArea = localMinArea
                globalBestNode = localBest
            }
        }

        scanRootFast(rootInActiveWindow)
        if (globalBestNode == null) {
            for (window in windows) {
                if (currentTargetPackage != null && window.root?.packageName?.toString() != currentTargetPackage) continue
                scanRootFast(window.root)
            }
        }
        
        globalBestNode?.let { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            boundingBoxView?.targetRect = rect
        }
    }

    private fun handleCaptureTouch(x: Int, y: Int) {
        if (confirmationView != null) return // Already showing confirmation

        var globalBestNode: AccessibilityNodeInfo? = null
        var globalMinArea = Int.MAX_VALUE

        fun scanRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            val pkg = root.packageName?.toString()
            if (pkg == packageName) return // ALWAYS skip our own UI

            val (localBest, localMinArea) = findSmallestNodeAt(root, x, y)
            if (localBest != null && localMinArea < globalMinArea) {
                globalMinArea = localMinArea
                globalBestNode = localBest
            }
        }

        // Phase 1: Strict Target Package Match
        for (window in windows) {
            val root = window.root ?: continue
            if (currentTargetPackage != null && root.packageName?.toString() != currentTargetPackage) continue
            scanRoot(root)
        }

        // Phase 2: Relaxed Fallback (Search all windows except our own)
        if (globalBestNode == null) {
            for (window in windows) {
                scanRoot(window.root)
            }
        }

        // Phase 3: Active Window Fallback
        if (globalBestNode == null) {
            scanRoot(rootInActiveWindow)
        }

        if (globalBestNode != null) {
            inspectNode(globalBestNode)
        } else {
            Toast.makeText(this, "No valid element found at location. Try adjusting your touch.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun inspectNode(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        boundingBoxView?.targetRect = rect

        val role = calibrationSteps[currentStepIndex]
        val signature = SignatureExtractor.extract(node, currentProfileId, role)
        showConfirmationDialog(node, signature)
    }

    private fun showConfirmationDialog(node: AccessibilityNodeInfo, signature: ElementSignature) {
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_CodeAssist)
        val inflater = LayoutInflater.from(themedContext)
        confirmationView = inflater.inflate(R.layout.layout_element_confirmation, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100 // Margin from top
        }

        val tvRoleTitle = confirmationView?.findViewById<TextView>(R.id.tvRoleTitle)
        val tvElementDetails = confirmationView?.findViewById<TextView>(R.id.tvElementDetails)
        val btnRetry = confirmationView?.findViewById<View>(R.id.btnRetry)
        val btnSaveNext = confirmationView?.findViewById<TextView>(R.id.btnSaveNext)
        val btnSelectParent = confirmationView?.findViewById<View>(R.id.btnSelectParent)
        val tvChildrenLabel = confirmationView?.findViewById<TextView>(R.id.tvChildrenLabel)
        val hsvChildren = confirmationView?.findViewById<View>(R.id.hsvChildren)
        val llChildrenList = confirmationView?.findViewById<android.widget.LinearLayout>(R.id.llChildrenList)

        tvRoleTitle?.text = "Confirm: ${signature.role.name}"

        val semanticType = when {
            node.isEditable -> "Input Field (Editable)"
            node.isClickable -> "Button (Clickable)"
            else -> "Static UI / Container"
        }

        val textStr = node.text?.toString()?.take(50)?.let { "Text: \"$it\"\n" } ?: ""
        val descStr = node.contentDescription?.toString()?.take(50)?.let { "Desc: \"$it\"\n" } ?: ""
        val hintStr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString()?.take(50)?.let { "Hint: \"$it\"\n" } ?: ""
        } else ""

        val states = mutableListOf<String>()
        if (node.isEnabled) states.add("Enabled") else states.add("Disabled")
        if (node.isFocusable) states.add("Focusable")
        if (node.isFocused) states.add("Focused")

        tvElementDetails?.text = buildString {
            appendLine("Type: $semanticType")
            appendLine("Class: ${signature.className}")
            appendLine("ID: ${signature.resourceId ?: "None"}")
            appendLine("Bounds: (${signature.boundsX}, ${signature.boundsY})")
            if (textStr.isNotEmpty()) append(textStr)
            if (descStr.isNotEmpty()) append(descStr)
            if (hintStr.isNotEmpty()) append(hintStr)
            appendLine("State: ${states.joinToString(", ")}")
            appendLine("Path: ${signature.hierarchyPath ?: "Unknown"}")
        }.trim()

        val isLastStep = currentStepIndex == calibrationSteps.size - 1
        btnSaveNext?.text = if (isLastStep) "Save Profile" else "Save & Next"

        llChildrenList?.removeAllViews()
        if (node.childCount > 0) {
            tvChildrenLabel?.visibility = View.VISIBLE
            hsvChildren?.visibility = View.VISIBLE

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val btnChild = com.google.android.material.button.MaterialButton(
                    themedContext, 
                    null, 
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    val cName = child.className?.toString()?.substringAfterLast('.') ?: "Node"
                    text = "$cName ($i)"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = 16
                    }
                    setOnClickListener {
                        removeViewSafely(confirmationView)
                        confirmationView = null
                        inspectNode(child)
                    }
                }
                llChildrenList?.addView(btnChild)
            }
        } else {
            tvChildrenLabel?.visibility = View.GONE
            hsvChildren?.visibility = View.GONE
        }

        btnSelectParent?.setOnClickListener {
            val parent = node.parent
            if (parent != null) {
                removeViewSafely(confirmationView)
                confirmationView = null
                inspectNode(parent)
            } else {
                Toast.makeText(this, "No parent element found.", Toast.LENGTH_SHORT).show()
            }
        }

        btnRetry?.setOnClickListener {
            removeViewSafely(confirmationView)
            confirmationView = null
            boundingBoxView?.targetRect = null
        }

        btnSaveNext?.setOnClickListener {
            collectedSignatures.add(signature)
            removeViewSafely(confirmationView)
            confirmationView = null
            boundingBoxView?.targetRect = null

            currentStepIndex++
            if (currentStepIndex < calibrationSteps.size) {
                updateStatusText()
                Toast.makeText(this, "Saved! Now select ${calibrationSteps[currentStepIndex].name}", Toast.LENGTH_SHORT).show()
            } else {
                finalizeCalibration()
            }
        }

        windowManager?.addView(confirmationView, params)
    }

    private fun finalizeCalibration() {
        Toast.makeText(this, "Saving Profile Data...", Toast.LENGTH_SHORT).show()
        val signaturesToSave = collectedSignatures.toList()
        val profileId = currentProfileId

        serviceScope.launch {
            repository.saveSignaturesForProfile(profileId, signaturesToSave)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AgentAccessibilityService, "Calibration Complete!", Toast.LENGTH_LONG).show()
                stopCalibration()
            }
        }
    }

    private fun findSmallestNodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): Pair<AccessibilityNodeInfo?, Int> {
        var best: AccessibilityNodeInfo? = null
        var min = Int.MAX_VALUE
        val candidates = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()

        fun search(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.contains(x, y)) {
                val area = bounds.width() * bounds.height()
                if (area > 0 && area < min) {
                    if (best != null && candidates.put(best!!, true) == null) {
                        // previous best displaced; will be recycled later
                    }
                    min = area
                    best = node
                    candidates.put(node, true)
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                search(child)
                if (child != null && best != child && !candidates.containsKey(child)) {
                    try { child.recycle() } catch (_: Exception) {}
                }
            }
        }

        search(root)
        // root itself is recycled by the caller (handleCaptureTouch / updateSpotlightLive).
        return Pair(best, min)
    }

    fun resumeOrSync() {
        serviceScope.launch {
            val signatures = org.ravi.codeassist.agent.AgentOrchestrator.getActiveSignatures()
            val stopSig = signatures.find { it.role == org.ravi.codeassist.database.ElementRole.STOP_BUTTON }
            
            // Check if stop button is visible (active generation)
            val stopNode = stopSig?.let { findNodeBySignature(it) }
            if (stopNode != null && stopNode.isVisibleToUser) {
                try { stopNode.recycle() } catch (e: Exception) {}
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { updateShieldStatus("Resume: Waiting for LLM generation...") }
                val containerSig = signatures.find { it.role == org.ravi.codeassist.database.ElementRole.RESPONSE_CONTAINER }
                if (containerSig != null) {
                    val text = waitForMutationAndScrape(containerSig)
                    org.ravi.codeassist.agent.AgentOrchestrator.resumeFromText(text)
                }
            } else {
                try { stopNode?.recycle() } catch (e: Exception) {}
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { updateShieldStatus("Resume: Syncing screen...") }
                val containerSig = signatures.find { it.role == org.ravi.codeassist.database.ElementRole.RESPONSE_CONTAINER }
                var scrapedText = ""
                if (containerSig != null) {
                    val matches = findAllNodesBySignature(containerSig)
                    scrapedText = matches.joinToString("\n") { extractAllText(it, 0) }.trim()
                    matches.forEach { try { it.recycle() } catch (e: Exception) {} }
                }
                if (scrapedText.isBlank()) {
                    val tempRoot = rootInActiveWindow
                    scrapedText = extractAllText(tempRoot, 0)
                    tempRoot?.recycle()
                }
                org.ravi.codeassist.agent.AgentOrchestrator.resumeFromText(scrapedText)
            }
        }
    }
}