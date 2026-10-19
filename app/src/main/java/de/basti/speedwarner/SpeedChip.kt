package de.basti.speedwarner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Kleines Geschwindigkeits-Fenster: große Zahl (km/h) und darunter das Limit.
 * Dunkel im Normalfall, rot bei Überschreitung.
 *
 * Die Größe lässt sich über [setScale] ändern (Faktor 0,6 bis 3,0). In der unteren rechten Ecke
 * ist ein kleiner Ziehgriff eingezeichnet, siehe [inGrip].
 */
class SpeedChip(context: Context) : LinearLayout(context) {

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 3.0f
    }

    private val tvSpeed = TextView(context)
    private val tvSub = TextView(context)
    private val bg = GradientDrawable()
    private var lastOver: Boolean? = null

    private val density = resources.displayMetrics.density
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private val gripPath = Path()

    var chipScale = 1f
        private set

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        background = bg

        tvSpeed.setTextColor(Color.WHITE)
        tvSpeed.typeface = Typeface.DEFAULT_BOLD
        tvSpeed.gravity = Gravity.CENTER_HORIZONTAL

        tvSub.setTextColor(0xFFDDDDDD.toInt())
        tvSub.gravity = Gravity.CENTER_HORIZONTAL

        addView(tvSpeed)
        addView(tvSub)
        setScale(1f)
        update("--", "km/h", false)
    }

    /** Ändert die Größe von Zahl, Beschriftung und Abständen. */
    fun setScale(s: Float) {
        val scale = s.coerceIn(MIN_SCALE, MAX_SCALE)
        chipScale = scale
        tvSpeed.textSize = 32f * scale
        tvSub.textSize = 12f * scale
        // Rechts und unten etwas mehr Platz, damit der Griff nichts überdeckt
        setPadding(
            (14 * density * scale).toInt(),
            (8 * density * scale).toInt(),
            (14 * density * scale).toInt(),
            (12 * density * scale).toInt()
        )
        bg.cornerRadius = 16 * density * scale
        invalidate()
    }

    /** Größe der Griff-Zone (Berührungsfläche) in Pixeln. */
    private fun gripZone(): Float = 36 * density

    /** true, wenn der Punkt (in Fenster-Koordinaten) im Ziehgriff unten rechts liegt. */
    fun inGrip(x: Float, y: Float): Boolean {
        val g = gripZone()
        return x >= width - g && y >= height - g
    }

    fun update(speed: String, sub: String, over: Boolean) {
        tvSpeed.text = speed
        tvSub.text = sub
        if (over != lastOver) {
            bg.setColor(if (over) 0xF0D32F2F.toInt() else 0xCC202020.toInt())
            lastOver = over
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // kleines Dreieck unten rechts als Hinweis auf den Ziehgriff
        val s = 10 * density
        val m = 6 * density
        val r = width - m
        val b = height - m
        gripPath.reset()
        gripPath.moveTo(r, b - s)
        gripPath.lineTo(r, b)
        gripPath.lineTo(r - s, b)
        gripPath.close()
        canvas.drawPath(gripPath, gripPaint)
    }
}
