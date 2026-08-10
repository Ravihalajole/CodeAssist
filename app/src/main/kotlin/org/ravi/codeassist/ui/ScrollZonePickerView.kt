package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class ScrollZonePickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnBoundsChangedListener {
        fun onBoundsChanged(leftPct: Float, topPct: Float, rightPct: Float, bottomPct: Float)
    }

    var boundsListener: OnBoundsChangedListener? = null

    private val zoneRect = RectF()
    private var lastX = 0f
    private var lastY = 0f
    
    private enum class TouchState {
        NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }
    private var touchState = TouchState.NONE

    private val handleRadius = 24f
    private val touchTolerance = 60f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#CC000000") // 80% Dim background
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // High-contrast neon cyan
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val handlePaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // If box is uninitialized, default to a centered region (20% to 80%)
        if (zoneRect.isEmpty) {
            zoneRect.set(w * 0.15f, h * 0.25f, w * 0.85f, h * 0.75f)
            notifyBoundsChanged()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // 1. Draw dim full background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // 2. Clear out selection zone window
        canvas.drawRect(zoneRect, clearPaint)

        // 3. Draw high-contrast border lines
        canvas.drawRect(zoneRect, strokePaint)

        // 4. Draw interactive corner handles
        canvas.drawCircle(zoneRect.left, zoneRect.top, handleRadius, handlePaint)
        canvas.drawCircle(zoneRect.right, zoneRect.top, handleRadius, handlePaint)
        canvas.drawCircle(zoneRect.left, zoneRect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(zoneRect.right, zoneRect.bottom, handleRadius, handlePaint)

        // 5. Draw live dimension ratios
        val pctWidth = ((zoneRect.width() / width) * 100).toInt()
        val pctHeight = ((zoneRect.height() / height) * 100).toInt()
        val labelStr = "Scroll Zone: ${pctWidth}% W x ${pctHeight}% H"
        
        canvas.drawText(labelStr, zoneRect.left + 20f, zoneRect.top - 20f, textPaint)
        
        // Draw directional watermark indicators inside the clear window
        val midX = zoneRect.centerX()
        val midY = zoneRect.centerY()
        canvas.drawText("↑", midX - 10f, midY - 30f, textPaint)
        canvas.drawText("│", midX - 8f, midY, textPaint)
        canvas.drawText("↓", midX - 10f, midY + 40f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                touchState = getTouchStateForCoordinates(x, y)
                return touchState != TouchState.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY

                when (touchState) {
                    TouchState.MOVE -> {
                        zoneRect.offset(dx, dy)
                        // Keep within screen bounds
                        if (zoneRect.left < 0) zoneRect.offset(-zoneRect.left, 0f)
                        if (zoneRect.top < 0) zoneRect.offset(0f, -zoneRect.top)
                        if (zoneRect.right > width) zoneRect.offset(width - zoneRect.right, 0f)
                        if (zoneRect.bottom > height) zoneRect.offset(0f, height - zoneRect.bottom)
                    }
                    TouchState.TOP_LEFT -> {
                        zoneRect.left = (zoneRect.left + dx).coerceIn(0f, zoneRect.right - 100f)
                        zoneRect.top = (zoneRect.top + dy).coerceIn(0f, zoneRect.bottom - 100f)
                    }
                    TouchState.TOP_RIGHT -> {
                        zoneRect.right = (zoneRect.right + dx).coerceIn(zoneRect.left + 100f, width.toFloat())
                        zoneRect.top = (zoneRect.top + dy).coerceIn(0f, zoneRect.bottom - 100f)
                    }
                    TouchState.BOTTOM_LEFT -> {
                        zoneRect.left = (zoneRect.left + dx).coerceIn(0f, zoneRect.right - 100f)
                        zoneRect.bottom = (zoneRect.bottom + dy).coerceIn(zoneRect.top + 100f, height.toFloat())
                    }
                    TouchState.BOTTOM_RIGHT -> {
                        zoneRect.right = (zoneRect.right + dx).coerceIn(zoneRect.left + 100f, width.toFloat())
                        zoneRect.bottom = (zoneRect.bottom + dy).coerceIn(zoneRect.top + 100f, height.toFloat())
                    }
                    else -> {}
                }

                lastX = x
                lastY = y
                invalidate()
                notifyBoundsChanged()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchState = TouchState.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getTouchStateForCoordinates(x: Float, y: Float): TouchState {
        return when {
            abs(x - zoneRect.left) < touchTolerance && abs(y - zoneRect.top) < touchTolerance -> TouchState.TOP_LEFT
            abs(x - zoneRect.right) < touchTolerance && abs(y - zoneRect.top) < touchTolerance -> TouchState.TOP_RIGHT
            abs(x - zoneRect.left) < touchTolerance && abs(y - zoneRect.bottom) < touchTolerance -> TouchState.BOTTOM_LEFT
            abs(x - zoneRect.right) < touchTolerance && abs(y - zoneRect.bottom) < touchTolerance -> TouchState.BOTTOM_RIGHT
            zoneRect.contains(x, y) -> TouchState.MOVE
            else -> TouchState.NONE
        }
    }

    private fun notifyBoundsChanged() {
        if (width > 0 && height > 0) {
            boundsListener?.onBoundsChanged(
                zoneRect.left / width,
                zoneRect.top / height,
                zoneRect.right / width,
                zoneRect.bottom / height
            )
        }
    }

    fun setInitialBounds(leftPct: Float, topPct: Float, rightPct: Float, bottomPct: Float) {
        post {
            if (width > 0 && height > 0) {
                zoneRect.set(leftPct * width, topPct * height, rightPct * width, bottomPct * height)
                invalidate()
            }
        }
    }
}