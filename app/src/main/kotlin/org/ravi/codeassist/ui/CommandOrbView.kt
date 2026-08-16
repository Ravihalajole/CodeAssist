package org.ravi.codeassist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import org.ravi.codeassist.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

class CommandOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackColor = ContextCompat.getColor(context, R.color.ovl_track)
    private val coreFill = ContextCompat.getColor(context, R.color.surf_raised)
    private val coreBorderColor = ContextCompat.getColor(context, R.color.ovl_core_border)
    private val sheenColor = ContextCompat.getColor(context, R.color.ovl_sheen)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = trackColor
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = coreFill
    }
    private val coreBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = coreBorderColor
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val playPath = Path().apply {
        moveTo(8f, 5f)
        lineTo(19f, 12f)
        lineTo(8f, 19f)
        close()
    }
    private val stopPath = Path().apply {
        addRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 2.5f, 2.5f, Path.Direction.CW)
    }
    private val coreClip = Path()

    private val ringRect = RectF()

    private var cx = 0f
    private var cy = 0f
    private var ringRadius = 0f
    private var coreRadius = 0f
    private var auraRadius = 0f
    private var sheenShader: RadialGradient? = null
    private var auraShader: RadialGradient? = null

    private var accent = ContextCompat.getColor(context, R.color.state_cyan)
    private var generating = false
    private var pulse = false
    private var coreScale = 1f
    private var auraAlpha = 0f
    private var sweepStart = -90f
    private var arcSweep = 0f

    private var sweepAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null
    private var colorAnim: ValueAnimator? = null

    init {
        coreClip.addCircle(0f, 0f, 1f, Path.Direction.CW)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        ringRadius = min(w, h) / 2f - dp(10f)
        coreRadius = ringRadius - dp(6f)
        auraRadius = min(w, h) / 2f - dp(2f)
        ringRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        sheenShader = RadialGradient(
            cx - coreRadius * 0.55f,
            cy - coreRadius * 0.55f,
            coreRadius * 1.6f,
            sheenColor,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        sheenPaint.shader = sheenShader
    }

    private fun updateAuraShader() {
        auraShader = RadialGradient(
            cx, cy, auraRadius,
            accent, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }

    fun applyUi(accentColor: Int, isGenerating: Boolean, isPulse: Boolean) {
        if (accentColor != 0 && accentColor != accent) animateAccent(accentColor)
        setGenerating(isGenerating)
        setPulse(isPulse)
        invalidate()
    }

    private fun setGenerating(gen: Boolean) {
        if (generating == gen) return
        generating = gen
        if (gen) {
            sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    sweepStart = -90f + 360f * p
                    arcSweep = 70f + 190f * (0.5f - 0.5f * cos(2f * PI.toFloat() * p))
                    auraAlpha = 0.30f + 0.22f * (0.5f - 0.5f * cos(2f * PI.toFloat() * p + PI.toFloat()))
                    invalidate()
                }
            }
            sweepAnim?.start()
        } else {
            sweepAnim?.cancel()
            sweepAnim = null
            sweepStart = -90f
            arcSweep = 0f
            auraAlpha = 0f
            invalidate()
        }
    }

    private fun setPulse(p: Boolean) {
        if (pulse == p) return
        pulse = p
        if (p) {
            pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val v = a.animatedValue as Float
                    coreScale = 1f + 0.06f * v
                    invalidate()
                }
            }
            pulseAnim?.start()
        } else {
            pulseAnim?.cancel()
            pulseAnim = null
            coreScale = 1f
            invalidate()
        }
    }

    private fun animateAccent(target: Int) {
        colorAnim?.cancel()
        val from = accent
        colorAnim = ValueAnimator.ofArgb(from, target).apply {
            duration = 320
            addUpdateListener { a ->
                accent = a.animatedValue as Int
                arcPaint.color = accent
                iconPaint.color = accent
                updateAuraShader()
                invalidate()
            }
        }
        colorAnim?.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (generating && auraAlpha > 0f) {
            if (auraShader == null) updateAuraShader()
            auraPaint.shader = auraShader
            auraPaint.alpha = (auraAlpha * 255f).toInt()
            canvas.drawCircle(cx, cy, auraRadius, auraPaint)
            auraPaint.alpha = 255
        }

        canvas.save()
        canvas.scale(coreScale, coreScale, cx, cy)

        canvas.drawCircle(cx, cy, ringRadius, trackPaint)
        if (generating) {
            canvas.drawArc(ringRect, sweepStart, arcSweep, false, arcPaint)
        }

        canvas.drawCircle(cx, cy, coreRadius, corePaint)
        canvas.drawCircle(cx, cy, coreRadius, coreBorderPaint)

        coreClip.reset()
        coreClip.addCircle(cx, cy, coreRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(coreClip)
        canvas.drawCircle(cx - coreRadius * 0.55f, cy - coreRadius * 0.55f, coreRadius * 1.6f, sheenPaint)
        canvas.restore()

        val iconScale = dp(18f) / 24f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(iconScale, iconScale)
        canvas.translate(-12f, -12f)
        canvas.drawPath(if (generating) stopPath else playPath, iconPaint)
        canvas.restore()

        canvas.restore()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            sweepAnim?.pause()
            pulseAnim?.pause()
        } else {
            if (generating) sweepAnim?.resume()
            if (pulse) pulseAnim?.resume()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sweepAnim?.cancel()
        pulseAnim?.cancel()
        colorAnim?.cancel()
    }
}
