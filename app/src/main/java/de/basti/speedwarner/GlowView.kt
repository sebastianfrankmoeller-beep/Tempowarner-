package de.basti.speedwarner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Roter, pulsierender Leuchtrahmen am Bildschirmrand.
 * Die Mitte bleibt vollständig transparent.
 */
class GlowView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPx = 56f * resources.displayMetrics.density
    private var pulse = 1f

    private val animator = ValueAnimator.ofFloat(0.55f, 1f).apply {
        duration = 600
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val strong = Color.argb((255 * pulse).toInt(), 255, 0, 0)
        val none = Color.argb(0, 255, 0, 0)
        val clamp = Shader.TileMode.CLAMP

        // oben
        paint.shader = LinearGradient(0f, 0f, 0f, glowPx, strong, none, clamp)
        canvas.drawRect(0f, 0f, w, glowPx, paint)
        // unten
        paint.shader = LinearGradient(0f, h, 0f, h - glowPx, strong, none, clamp)
        canvas.drawRect(0f, h - glowPx, w, h, paint)
        // links
        paint.shader = LinearGradient(0f, 0f, glowPx, 0f, strong, none, clamp)
        canvas.drawRect(0f, 0f, glowPx, h, paint)
        // rechts
        paint.shader = LinearGradient(w, 0f, w - glowPx, 0f, strong, none, clamp)
        canvas.drawRect(w - glowPx, 0f, w, h, paint)
    }
}
