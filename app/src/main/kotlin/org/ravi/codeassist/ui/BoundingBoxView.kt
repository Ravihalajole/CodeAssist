package org.ravi.codeassist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class BoundingBoxView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#99000000") // 60% Black scrim
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint().apply {
        color = Color.parseColor("#34E0A1") // Brand mint spotlight stroke
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    var targetRect: Rect? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (targetRect != null) {
            // Draw dim background over entire screen
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            // Punch out the target rect
            canvas.drawRect(targetRect!!, clearPaint)
            // Draw neon stroke border
            canvas.drawRect(targetRect!!, strokePaint)
        } else {
            // Just draw transparent if no target
            canvas.drawColor(Color.TRANSPARENT)
        }
    }
}