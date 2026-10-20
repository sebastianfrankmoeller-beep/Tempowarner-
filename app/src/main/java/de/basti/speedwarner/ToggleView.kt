package de.basti.speedwarner

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Moderner Ein/Aus-Schalter (Pillenform mit gleitendem Knopf). */
class ToggleView(
    context: Context,
    private val onColor: Int,
    private val offColor: Int
) : View(context) {

    var isOn = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val trackW = 52 * d
    private val trackH = 32 * d
    private var pos = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argb = ArgbEvaluator()
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        setOnClickListener { toggle() }
    }

    fun toggle() = setState(!isOn, animate = true, notify = true)

    fun setState(value: Boolean, animate: Boolean, notify: Boolean = false) {
        isOn = value
        anim?.cancel()
        val target = if (value) 1f else 0f
        if (animate) {
            anim = ValueAnimator.ofFloat(pos, target).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    pos = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            pos = target
            invalidate()
        }
        if (notify) onChange?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackW.toInt(), trackH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = argb.evaluate(pos, offColor, onColor) as Int
        canvas.drawRoundRect(0f, 0f, trackW, trackH, trackH / 2, trackH / 2, paint)
        val r = trackH / 2 - 4 * d
        val cx = trackH / 2 + pos * (trackW - trackH)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, trackH / 2, r, paint)
    }
}
