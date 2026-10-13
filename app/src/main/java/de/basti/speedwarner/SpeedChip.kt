package de.basti.speedwarner

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Kleines Geschwindigkeits-Fenster: große Zahl (km/h) und darunter das Limit.
 * Dunkel im Normalfall, rot bei Überschreitung.
 */
class SpeedChip(context: Context) : LinearLayout(context) {

    private val tvSpeed = TextView(context)
    private val tvSub = TextView(context)
    private val bg = GradientDrawable()
    private var lastOver: Boolean? = null

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        val d = resources.displayMetrics.density
        val padH = (14 * d).toInt()
        val padV = (8 * d).toInt()
        setPadding(padH, padV, padH, padV)

        bg.cornerRadius = 16 * d
        background = bg

        tvSpeed.textSize = 32f
        tvSpeed.setTextColor(Color.WHITE)
        tvSpeed.typeface = Typeface.DEFAULT_BOLD
        tvSpeed.gravity = Gravity.CENTER_HORIZONTAL

        tvSub.textSize = 12f
        tvSub.setTextColor(0xFFDDDDDD.toInt())
        tvSub.gravity = Gravity.CENTER_HORIZONTAL

        addView(tvSpeed)
        addView(tvSub)
        update("--", "km/h", false)
    }

    fun update(speed: String, sub: String, over: Boolean) {
        tvSpeed.text = speed
        tvSub.text = sub
        if (over != lastOver) {
            bg.setColor(if (over) 0xF0D32F2F.toInt() else 0xCC202020.toInt())
            lastOver = over
        }
    }
}
