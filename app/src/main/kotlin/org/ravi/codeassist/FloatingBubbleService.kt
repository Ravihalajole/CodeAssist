package org.ravi.codeassist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "codeassist_bubble_channel"
        private const val CHANNEL_NAME = "CodeAssist Background Tracking"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val themedContext = ContextThemeWrapper(this, R.style.Theme_CodeAssist)
        floatingView = LayoutInflater.from(themedContext).inflate(R.layout.bubble_view, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        params.x = sharedPref.getInt("BUBBLE_X", 0)
        params.y = sharedPref.getInt("BUBBLE_Y", 100)

        windowManager.addView(floatingView, params)

        val bubbleIconView = floatingView.findViewById<android.widget.ImageView>(R.id.bubbleIcon)
        applyBubbleIconStyle(bubbleIconView)

        bubbleIconView.apply {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false
            var longPressTriggered = false

            setOnLongClickListener {
                longPressTriggered = true
                org.ravi.codeassist.agent.ToolboxManager.showMenu(this@FloatingBubbleService)
                true
            }

            // Observe Agent Orchestrator State to update Bubble visuals
            serviceScope.launch {
                org.ravi.codeassist.agent.AgentOrchestrator.state.collect { state ->
                    when (state) {
                        is org.ravi.codeassist.agent.AgentState.IDLE -> {
                            alpha = 1.0f
                            clearColorFilter()
                        }
                        is org.ravi.codeassist.agent.AgentState.ANALYZING_SCREEN,
                        is org.ravi.codeassist.agent.AgentState.AWAITING_LLM,
                        is org.ravi.codeassist.agent.AgentState.WAITING_FOR_MUTATION -> {
                            // Pulsing/thinking effect
                            alpha = 0.5f
                            setColorFilter(androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.state_amber), android.graphics.PorterDuff.Mode.SRC_ATOP)
                        }
                        is org.ravi.codeassist.agent.AgentState.EXECUTING_ACTION -> {
                            alpha = 1.0f
                            setColorFilter(androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.brand_mint), android.graphics.PorterDuff.Mode.SRC_ATOP)
                        }
                        is org.ravi.codeassist.agent.AgentState.WAITING_FOR_USER -> {
                            alpha = 1.0f
                            setColorFilter(androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.state_blue), android.graphics.PorterDuff.Mode.SRC_ATOP)
                        }
                        is org.ravi.codeassist.agent.AgentState.ERROR -> {
                            alpha = 1.0f
                            setColorFilter(androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.state_red), android.graphics.PorterDuff.Mode.SRC_ATOP)
                        }
                        is org.ravi.codeassist.agent.AgentState.TOOLBOX_OPEN -> {
                            alpha = 1.0f
                            setColorFilter(androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.state_violet), android.graphics.PorterDuff.Mode.SRC_ATOP)
                        }
                        is org.ravi.codeassist.agent.AgentState.SCROLL_CONFIG_ACTIVE -> {
                            alpha = 1.0f
                            setColorFilter(androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.state_cyan), android.graphics.PorterDuff.Mode.SRC_ATOP)
                        }
                    }
                }
            }

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick && !longPressTriggered) {
                            val actIntent = Intent(this@FloatingBubbleService, ClipboardActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            
                            try {
                                val pendingIntent = android.app.PendingIntent.getActivity(
                                    this@FloatingBubbleService,
                                    0,
                                    actIntent,
                                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                                )
                                pendingIntent.send()
                            } catch (e: Exception) {
                                startActivity(actIntent)
                            }
                        }
                        longPressTriggered = false
                        sharedPref.edit().putInt("BUBBLE_X", params.x).putInt("BUBBLE_Y", params.y).apply()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setupForegroundNotification()
        return START_STICKY
    }

    private fun setupForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the overlay interface running reliably in the background."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CodeAssist Engine Active")
            .setContentText("The floating quick-access bubble is running.")
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun applyBubbleIconStyle(imageView: android.widget.ImageView) {
        val sharedPref = getSharedPreferences("CodeAssistPrefs", Context.MODE_PRIVATE)
        val style = sharedPref.getInt("BUBBLE_ICON_STYLE", 0)

        imageView.setImageTintList(null)
        imageView.setBackgroundTintList(null)
        imageView.setPadding(0, 0, 0, 0)

        val density = resources.displayMetrics.density
        val dp12 = (12 * density).toInt()
        val dp8 = (8 * density).toInt()

        when (style) {
            0 -> { // Current Icon
                imageView.setImageResource(R.drawable.ic_qs_tile)
                imageView.setBackgroundResource(R.drawable.bubble_bg)
                imageView.setPadding(dp12, dp12, dp12, dp12)
            }
            1 -> { // Current Icon (Dark Background)
                imageView.setImageResource(R.drawable.ic_qs_tile)
                imageView.setBackgroundResource(R.drawable.bubble_bg)
                imageView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.surf_high)))
                imageView.setImageTintList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.text_hi)))
                imageView.setPadding(dp12, dp12, dp12, dp12)
            }
            2 -> { // Current Icon (Dark Icon)
                imageView.setImageResource(R.drawable.ic_qs_tile)
                imageView.setBackgroundResource(R.drawable.bubble_bg)
                imageView.setImageTintList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.surf_high)))
                imageView.setPadding(dp12, dp12, dp12, dp12)
            }
            3 -> { // App Icon
                imageView.setImageResource(R.mipmap.ic_launcher)
                imageView.setBackgroundResource(android.R.color.transparent)
            }
            4 -> { // App Icon Monochrome Dark
                imageView.setImageResource(R.mipmap.ic_launcher_monochrome)
                imageView.setBackgroundResource(R.drawable.bubble_bg)
                imageView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.text_hi)))
                imageView.setImageTintList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.surf_high)))
                imageView.setPadding(dp8, dp8, dp8, dp8)
            }
            5 -> { // App Icon Monochrome Light
                imageView.setImageResource(R.mipmap.ic_launcher_monochrome)
                imageView.setBackgroundResource(R.drawable.bubble_bg)
                imageView.setBackgroundTintList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.surf_high)))
                imageView.setImageTintList(android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(this, R.color.text_hi)))
                imageView.setPadding(dp8, dp8, dp8, dp8)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::floatingView.isInitialized && floatingView.isAttachedToWindow) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                android.util.Log.e("CodeAssist", "Error removing bubble view", e)
            }
        }
        super.onDestroy()
    }
}