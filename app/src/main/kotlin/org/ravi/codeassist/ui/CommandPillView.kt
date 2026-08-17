package org.ravi.codeassist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.ravi.codeassist.R
import kotlin.math.min

class CommandPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dp: (Float) -> Float = { it * resources.displayMetrics.density }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ovl_pill_glass)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pillRect = RectF()
    private val barRect = RectF()
    private var cornerRadius = 0f

    private var accent = ContextCompat.getColor(context, R.color.state_cyan)
    private var generating = false
    private var glow = false
    private var barOffset = 0f
    private var auraAlpha = 0f

    private var barAnim: ValueAnimator? = null
    private var auraAnim: ValueAnimator? = null
    private var dotPulseAnim: ValueAnimator? = null

    private var labelView: TextView? = null
    private var subView: TextView? = null
    private var dotView: View? = null
    private var morphButton: MaterialButton? = null

    init {
        setWillNotDraw(false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        dotView = findViewById(R.id.pillDot)
        labelView = findViewById(R.id.pillLabel)
        subView = findViewById(R.id.pillSub)
        morphButton = findViewById(R.id.morphButton)
        dotView?.background?.setTint(accent)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cornerRadius = dp(22f)
        pillRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val barH = dp(2.5f)
        val margin = dp(8f)
        barRect.set(margin, h - barH - margin, w - margin, h - margin)
        rebuildRim()
    }

    fun applyUi(accentColor: Int, isGenerating: Boolean, isGlow: Boolean, label: String, sub: String) {
        if (accentColor != 0 && accentColor != accent) {
            accent = accentColor
            rebuildRim()
            invalidate()
        }
        dotView?.background?.setTint(accent)
        labelView?.text = label
        subView?.text = sub
        setGenerating(isGenerating)
        setGlow(isGlow)
    }

    private fun rebuildRim() {
        if (pillRect.width() > 0f) {
            rimPaint.shader = LinearGradient(
                pillRect.left, pillRect.top, pillRect.right, pillRect.bottom,
                accent, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
    }

    private fun setGenerating(gen: Boolean) {
        if (generating == gen) return
        generating = gen
        val btn = morphButton
        if (btn != null) {
            btn.icon = ContextCompat.getDrawable(context, if (gen) R.drawable.ic_stop else R.drawable.ic_play)
            if (gen) {
                btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surf_high))
                btn.iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_hi))
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_mint))
                btn.iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_on_accent))
            }
        }
        if (gen) {
            barAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    barOffset = a.animatedValue as Float
                    invalidate()
                }
            }
            barAnim?.start()
            val dot = dotView
            if (dot != null) {
                dotPulseAnim = ValueAnimator.ofFloat(1f, 0.62f).apply {
                    duration = 700
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = LinearInterpolator()
                    addUpdateListener { a ->
                        val s = a.animatedValue as Float
                        dot.scaleX = s
                        dot.scaleY = s
                    }
                }
                dotPulseAnim?.start()
            }
        } else {
            barAnim?.cancel()
            barAnim = null
            barOffset = 0f
            dotPulseAnim?.cancel()
            dotPulseAnim = null
            dotView?.scaleX = 1f
            dotView?.scaleY = 1f
            invalidate()
        }
    }

    private fun setGlow(gl: Boolean) {
        if (glow == gl) return
        glow = gl
        if (gl) {
            auraAnim = ValueAnimator.ofFloat(0.35f, 0.7f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    auraAlpha = a.animatedValue as Float
                    invalidate()
                }
            }
            auraAnim?.start()
        } else {
            auraAnim?.cancel()
            auraAnim = null
            auraAlpha = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (glow && auraAlpha > 0f) {
            val radius = min(width, height).toFloat() * 0.85f
            auraPaint.shader = RadialGradient(
                width / 2f, height / 2f, radius,
                accent, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            auraPaint.alpha = (auraAlpha * 255f).toInt()
            canvas.drawCircle(width / 2f, height / 2f, radius, auraPaint)
            auraPaint.alpha = 255
        }

        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint)
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, rimPaint)

        if (generating) {
            canvas.save()
            canvas.clipRect(barRect)
            canvas.drawRoundRect(barRect, barRect.height() / 2f, barRect.height() / 2f, barTrackPaint)
            val segW = barRect.width() * 0.38f
            val left = barRect.left - segW + barOffset * (barRect.width() + segW)
            barFillPaint.shader = LinearGradient(
                left, 0f, left + segW, 0f,
                ContextCompat.getColor(context, R.color.brand_teal),
                ContextCompat.getColor(context, R.color.brand_mint),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(
                RectF(left, barRect.top, left + segW, barRect.bottom),
                barRect.height() / 2f, barRect.height() / 2f, barFillPaint
            )
            canvas.restore()
        }
    }
}
