package de.basti.speedwarner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Schwebendes Geschwindigkeits-Fenster: große Zahl mit "km/h" darunter und rechts ein rundes
 * Limit-Schild. Dunkle Glasfläche im Normalfall, rot bei Überschreitung.
 *
 * Ein roter Schildrand heißt: Limit aus den Kartendaten erkannt. Ein grauer Rand heißt:
 * Limit unbekannt, es gilt dein manuell eingestellter Wert.
 *
 * Die Größe lässt sich über [setScale] ändern (Faktor 0,6 bis 3,0). Unten rechts sind zwei
 * kleine Ziehstriche als Griff eingezeichnet, siehe [inGrip].
 */
class SpeedChip(context: Context) : LinearLayout(context) {

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 3.0f
    }

    private val density = resources.displayMetrics.density
    private val tvSpeed = TextView(context)
    private val tvCaption = TextView(context)
    private val column = LinearLayout(context)
    private val badge = LimitBadge(context)
    private val bg = GradientDrawable()
    private var lastOver: Boolean? = null

    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x73FFFFFF
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
    }

    var chipScale = 1f
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = bg

        tvSpeed.setTextColor(0xFFFFFFFF.toInt())
        tvSpeed.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tvSpeed.includeFontPadding = false
        tvSpeed.gravity = Gravity.START

        tvCaption.setTextColor(0xB3FFFFFF.toInt())
        tvCaption.includeFontPadding = false
        tvCaption.letterSpacing = 0.04f

        column.orientation = VERTICAL
        column.addView(tvSpeed)
        column.addView(tvCaption)

        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        setScale(1f)
        update("--", "km/h", "–", false, false)
    }

    /** Ändert die Größe von Zahl, Beschriftung, Schild und Abständen. */
    fun setScale(s: Float) {
        val scale = s.coerceIn(MIN_SCALE, MAX_SCALE)
        chipScale = scale
        tvSpeed.textSize = 38f * scale
        tvSpeed.minWidth = (46 * density * scale).toInt()
        tvCaption.textSize = 11f * scale
        tvCaption.setPadding(0, (3 * density * scale).toInt(), 0, 0)
        setPadding(
            (16 * density * scale).toInt(),
            (10 * density * scale).toInt(),
            (14 * density * scale).toInt(),
            (12 * density * scale).toInt()
        )
        bg.cornerRadius = 24 * density * scale
        badge.sizePx = (46 * density * scale).toInt()
        val lp = badge.layoutParams as LayoutParams
        lp.leftMargin = (14 * density * scale).toInt()
        badge.layoutParams = lp
        invalidate()
    }

    /** Größe der Griff-Zone (Berührungsfläche) in Pixeln. */
    private fun gripZone(): Float = 36 * density

    /** true, wenn der Punkt (in Fenster-Koordinaten) im Ziehgriff unten rechts liegt. */
    fun inGrip(x: Float, y: Float): Boolean {
        val g = gripZone()
        return x >= width - g && y >= height - g
    }

    /**
     * @param speed Anzeige der Geschwindigkeit, z. B. "47"
     * @param caption Beschriftung unter der Zahl, z. B. "km/h" oder "kein GPS"
     * @param limitLabel Zahl im Schild, "∞" für "kein Limit"
     * @param detected true, wenn das Limit aus Kartendaten erkannt wurde (roter Schildrand)
     * @param over true bei Überschreitung (Fenster wird rot)
     */
    fun update(speed: String, caption: String, limitLabel: String, detected: Boolean, over: Boolean) {
        tvSpeed.text = speed
        tvCaption.text = caption
        badge.set(limitLabel, detected)
        if (over != lastOver) {
            bg.setColor(if (over) 0xF2D92D20.toInt() else 0xD9111214.toInt())
            bg.setStroke(
                (1 * density).toInt().coerceAtLeast(1),
                if (over) 0x59FFFFFF else 0x24FFFFFF
            )
            lastOver = over
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // zwei kurze Diagonalstriche unten rechts als Hinweis auf den Ziehgriff
        val s = 9 * density
        val m = 10 * density
        val r = width - m
        val b = height - m
        canvas.drawLine(r - s, b, r, b - s, gripPaint)
        canvas.drawLine(r - s * 0.45f, b, r, b - s * 0.45f, gripPaint)
    }
}

/** Rundes Limit-Schild: weiße Scheibe, farbiger Rand, Zahl in der Mitte. */
class LimitBadge(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = 0xFF111111.toInt()
    }
    private var label = "–"
    private var detected = false

    var sizePx: Int = (46 * resources.displayMetrics.density).toInt()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    fun set(newLabel: String, newDetected: Boolean) {
        if (newLabel == label && newDetected == detected) return
        label = newLabel
        detected = newDetected
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val s = sizePx.toFloat()
        val c = s / 2
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF7F7F7.toInt()
        canvas.drawCircle(c, c, c, paint)

        val ring = s * 0.12f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        paint.color = if (detected) 0xFFE5392F.toInt() else 0xFF9AA0A6.toInt()
        canvas.drawCircle(c, c, c - ring / 2, paint)

        textPaint.textSize = s * (if (label.length >= 3) 0.30f else 0.40f)
        val y = c - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, c, y, textPaint)
    }
}
