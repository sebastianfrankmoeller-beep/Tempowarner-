package de.basti.speedwarner

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.WindowManager

/** Farben und kleine Zeichenhelfer für das Erscheinungsbild der App. */
object Ui {

    class Palette(
        val dark: Boolean,
        val bg: Int,          // Bildschirmhintergrund
        val surface: Int,     // Karten
        val surfaceHi: Int,   // erhöhte Flächen (Buttons, Eingabefelder)
        val border: Int,      // feine Linien
        val text: Int,
        val muted: Int,
        val accent: Int,
        val onAccent: Int,
        val danger: Int,
        val warn: Int,
        val trackOff: Int     // Schalter "aus"
    )

    private fun c(v: Long) = v.toInt()

    private val DARK = Palette(
        dark = true,
        bg = c(0xFF0B0C0E),
        surface = c(0xFF15171A),
        surfaceHi = c(0xFF212429),
        border = c(0x1AFFFFFF),
        text = c(0xFFF2F3F5),
        muted = c(0xFF8B9098),
        accent = c(0xFF5BE3A8),
        onAccent = c(0xFF06251A),
        danger = c(0xFFFF5A4F),
        warn = c(0xFFF5B544),
        trackOff = c(0xFF3A3F46)
    )

    private val LIGHT = Palette(
        dark = false,
        bg = c(0xFFF3F4F6),
        surface = c(0xFFFFFFFF),
        surfaceHi = c(0xFFECEEF1),
        border = c(0x14000000),
        text = c(0xFF121316),
        muted = c(0xFF6B7078),
        accent = c(0xFF0E9F6E),
        onAccent = c(0xFFFFFFFF),
        danger = c(0xFFE5392F),
        warn = c(0xFFC77700),
        trackOff = c(0xFFC9CDD3)
    )

    /** Wählt die Palette nach Einstellung (Automatisch / Dunkel / Hell). */
    fun palette(ctx: Context): Palette {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (Prefs.theme(ctx)) {
            Prefs.THEME_LIGHT -> LIGHT
            Prefs.THEME_AUTO -> if (night) DARK else LIGHT
            else -> DARK
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun rect(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Fläche mit Berührungs-Welle, die genau der abgerundeten Form folgt. */
    fun pressable(p: Palette, color: Int, radiusPx: Float): RippleDrawable {
        val ripple = if (p.dark) 0x33FFFFFF else 0x1F000000
        return RippleDrawable(
            ColorStateList.valueOf(ripple),
            rect(color, radiusPx),
            rect(0xFFFFFFFF.toInt(), radiusPx)
        )
    }

    /** Färbt Statusleiste, Navigationsleiste und Fensterhintergrund passend zum Design. */
    @Suppress("DEPRECATION")
    fun applySystemBars(a: Activity, p: Palette) {
        val w = a.window
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.statusBarColor = p.bg
        w.navigationBarColor = p.bg
        w.setBackgroundDrawable(ColorDrawable(p.bg))
        var flags = w.decorView.systemUiVisibility
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        flags = if (p.dark) flags and light.inv() else flags or light
        w.decorView.systemUiVisibility = flags
    }
}
