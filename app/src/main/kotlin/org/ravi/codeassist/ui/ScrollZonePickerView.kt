package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import org.ravi.codeassist.R

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

    private val density = context.resources.displayMetrics.density
    private val handleRadius = 14f * density
    private val touchTolerance = 36f * density

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val scrimPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.ovl_scrim_strong)
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.state_cyan)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        isAntiAlias = true
    }

    private val handlePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.state_cyan)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_hi)
        textSize = 18f * density
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(2f * density, 1f * density, 1f * density, Color.BLACK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (zoneRect.isEmpty) {
            zoneRect.set(w * 0.15f, h * 0.25f, w * 0.85f, h * 0.75f)
            notifyBoundsChanged()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(zoneRect, clearPaint)
        canvas.drawRect(zoneRect, strokePaint)

        canvas.drawCircle(zoneRect.left, zoneRect.top, handleRadius, handlePaint)
        canvas.drawCircle(zoneRect.right, zoneRect.top, handleRadius, handlePaint)
        canvas.drawCircle(zoneRect.left, zoneRect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(zoneRect.right, zoneRect.bottom, handleRadius, handlePaint)

        val pctWidth = ((zoneRect.width() / width) * 100).toInt()
        val pctHeight = ((zoneRect.height() / height) * 100).toInt()
        val labelStr = "Scroll Zone: ${pctWidth}% W x ${pctHeight}% H"
        
        canvas.drawText(labelStr, zoneRect.left + 10f * density, zoneRect.top - 10f * density, textPaint)
        
        val midX = zoneRect.centerX()
        val midY = zoneRect.centerY()
        canvas.drawText("\u2191", midX - 5f * density, midY - 15f * density, textPaint)
        canvas.drawText("\u2502", midX - 4f * density, midY, textPaint)
        canvas.drawText("\u2193", midX - 5f * density, midY + 20f * density, textPaint)
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
                val minSize = 50f * density

                when (touchState) {
                    TouchState.MOVE -> {
                        zoneRect.offset(dx, dy)
                        if (zoneRect.left < 0) zoneRect.offset(-zoneRect.left, 0f)
                        if (zoneRect.top < 0) zoneRect.offset(0f, -zoneRect.top)
                        if (zoneRect.right > width) zoneRect.offset(width - zoneRect.right, 0f)
                        if (zoneRect.bottom > height) zoneRect.offset(0f, height - zoneRect.bottom)
                    }
                    TouchState.TOP_LEFT -> {
                        zoneRect.left = (zoneRect.left + dx).coerceIn(0f, zoneRect.right - minSize)
                        zoneRect.top = (zoneRect.top + dy).coerceIn(0f, zoneRect.bottom - minSize)
                    }
                    TouchState.TOP_RIGHT -> {
                        zoneRect.right = (zoneRect.right + dx).coerceIn(zoneRect.left + minSize, width.toFloat())
                        zoneRect.top = (zoneRect.top + dy).coerceIn(0f, zoneRect.bottom - minSize)
                    }
                    TouchState.BOTTOM_LEFT -> {
                        zoneRect.left = (zoneRect.left + dx).coerceIn(0f, zoneRect.right - minSize)
                        zoneRect.bottom = (zoneRect.bottom + dy).coerceIn(zoneRect.top + minSize, height.toFloat())
                    }
                    TouchState.BOTTOM_RIGHT -> {
                        zoneRect.right = (zoneRect.right + dx).coerceIn(zoneRect.left + minSize, width.toFloat())
                        zoneRect.bottom = (zoneRect.bottom + dy).coerceIn(zoneRect.top + minSize, height.toFloat())
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