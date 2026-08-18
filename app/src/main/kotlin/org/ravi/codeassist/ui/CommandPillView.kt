package org.ravi.codeassist.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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
        strokeWidth = dp(2f)
    }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val ringArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, dp(1.5f),
            0x14FFFFFF.toInt(), 0x00FFFFFF.toInt(), Shader.TileMode.CLAMP
        )
    }

    private val pillRect = RectF()
    private val pillPath = Path()
    private var cornerRadius = 0f

    private var accent = ContextCompat.getColor(context, R.color.state_cyan)
    private var generating = false
    private var glow = false
    private var ringAngle = 0f
    private var auraAlpha = 0f

    private var ringAnim: ValueAnimator? = null
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
        morphButton?.setOnTouchListener { btn, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    btn.animate().cancel()
                    btn.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70).start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btn.animate().cancel()
                    btn.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(160)
                        .setInterpolator(OvershootInterpolator(0.7f))
                        .start()
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Whole-pill press feedback: a quick scale-down on touch-down, then a springy
     * return on release. The window-level touch listener (AgentOverlayManager)
     * drives this so taps and drags get the same tactile feel. Skipped while the
     * overlay is mid-fade so a press can't cancel the visibility animation.
     * Named to avoid clashing with [View.setPressed]'s pressed-state API.
     */
    fun setPressFeedback(pressed: Boolean) {
        if (alpha < 1f) return
        animate().cancel()
        if (pressed) {
            animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
        } else {
            animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(170)
                .setInterpolator(OvershootInterpolator(0.7f))
                .start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // True capsule: the ends round to half the height.
        cornerRadius = h / 2f
        pillRect.set(0f, 0f, w.toFloat(), h.toFloat())
        pillPath.reset()
        pillPath.addRoundRect(pillRect, cornerRadius, cornerRadius, Path.Direction.CW)
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
            btn.setBackgroundResource(if (gen) R.drawable.bg_morph_stop else R.drawable.bg_morph_play)
            btn.backgroundTintList = null
            btn.iconTint = ColorStateList.valueOf(
                if (gen) ContextCompat.getColor(context, R.color.text_hi)
                else ContextCompat.getColor(context, R.color.brand_on_accent)
            )
        }
        if (gen) {
            ringAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    ringAngle = a.animatedValue as Float
                    invalidate()
                }
            }
            ringAnim?.start()
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
            ringAnim?.cancel()
            ringAnim = null
            ringAngle = 0f
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
        // Strokes are centered on the path, so inset each ring by half its
        // width or the outer half would be clipped at the window edge.
        val borderInset = dp(0.5f)
        canvas.drawRoundRect(
            RectF(pillRect.left + borderInset, pillRect.top + borderInset, pillRect.right - borderInset, pillRect.bottom - borderInset),
            cornerRadius - borderInset, cornerRadius - borderInset, borderPaint
        )
        val rimInset = dp(1f)
        canvas.drawRoundRect(
            RectF(pillRect.left + rimInset, pillRect.top + rimInset, pillRect.right - rimInset, pillRect.bottom - rimInset),
            cornerRadius - rimInset, cornerRadius - rimInset, rimPaint
        )

        canvas.save()
        canvas.clipPath(pillPath)
        canvas.drawRect(pillRect.left, pillRect.top, pillRect.right, pillRect.top + dp(1.5f), highlightPaint)
        canvas.restore()
        if (generating) {
            // Compact activity ring around the morph button: a faint track plus a
            // rotating accent arc. Zero extra footprint on the pill.
            val btn = morphButton ?: return
            val cx = btn.left + btn.width / 2f
            val cy = btn.top + btn.height / 2f
            val radius = btn.width / 2f + dp(3f)
            val ringRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawCircle(cx, cy, radius, ringTrackPaint)
            ringArcPaint.shader = SweepGradient(
                cx, cy, intArrayOf(
                    ContextCompat.getColor(context, R.color.brand_mint),
                    ContextCompat.getColor(context, R.color.brand_teal),
                    ContextCompat.getColor(context, R.color.brand_mint)
                ), null
            )
            canvas.save()
            canvas.rotate(ringAngle, cx, cy)
            canvas.drawArc(ringRect, -90f, 110f, false, ringArcPaint)
            canvas.restore()
        }
    }
}
