package de.basti.speedwarner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var p: Ui.Palette

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var btnToggle: TextView
    private lateinit var btnTest: TextView
    private lateinit var permLocation: PermRow
    private lateinit var permOverlay: PermRow
    private lateinit var permUsage: PermRow

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Ui.palette(this)
        Ui.applySystemBars(this, p)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        fun add(view: View, bottom: Int) {
            root.addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(bottom) })
        }

        // ---------------------------------------------------------- Kopf
        add(tv("Tempowarner", 32f, p.text, true).apply { setPadding(dp(4), 0, 0, 0) }, 2)
        add(tv("Visuelle Tempowarnung – ganz ohne Ton", 15f, p.muted).apply { setPadding(dp(4), 0, 0, 0) }, 22)

        // ------------------------------------------------- Status und Start
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        statusTitle = tv("", 20f, p.text, true)
        statusSub = tv("", 14f, p.muted).apply { setPadding(dp(20), dp(3), 0, 0) }

        btnToggle = tv("", 17f, p.onAccent, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(17), 0, dp(17))
            setOnClickListener {
                if (SpeedService.running) {
                    startService(Intent(this@MainActivity, SpeedService::class.java).setAction(SpeedService.ACTION_STOP))
                } else {
                    startForegroundService(Intent(this@MainActivity, SpeedService::class.java))
                }
                postDelayed({ refresh() }, 500)
            }
        }
        btnTest = tv("Leuchtrahmen 3 s testen", 15f, p.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = Ui.pressable(p, p.surfaceHi, dpf(26))
            setOnClickListener {
                startService(Intent(this@MainActivity, SpeedService::class.java).setAction(SpeedService.ACTION_TEST))
            }
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rect(p.surface, dpf(24), p.border, 1)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(statusDot)
                addView(statusTitle)
            })
            addView(statusSub)
            addView(btnToggle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
            addView(btnTest, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        add(hero, 26)

        // ------------------------------------------------------ Tempolimit
        add(section("Tempolimit"), 8)
        add(
            card(
                toggleRow(
                    "Automatisch erkennen",
                    "Aus OpenStreetMap-Daten, braucht Internet",
                    Prefs.auto(this)
                ) { Prefs.setAuto(this, it) },
                stepperRow(
                    "Manuelles Limit",
                    "Gilt, wenn die Straße unbekannt ist",
                    Prefs.limit(this), 5, 300, 5
                ) { Prefs.setLimit(this, it) },
                stepperRow(
                    "Toleranz",
                    "Warnung ab … km/h über dem Limit",
                    Prefs.tolerance(this), 0, 50, 1
                ) { Prefs.setTolerance(this, it) }
            ), 26
        )

        // ---------------------------------------------------------- Anzeige
        add(section("Anzeige"), 8)
        add(
            card(
                toggleRow(
                    "Geschwindigkeits-Fenster",
                    "Schwebt über anderen Apps. In Google Maps ausgeblendet, dort leuchtet nur der Bildschirmrand.",
                    Prefs.chip(this)
                ) { Prefs.setChip(this, it) },
                row(
                    textCol("Größe und Position", "Verschieben mit einem Finger, vergrößern an den Ziehstrichen unten rechts oder mit zwei Fingern"),
                    pill("Zurücksetzen", p.surfaceHi, p.text) {
                        Prefs.setChipScale(this, 1f)
                        Prefs.setChipPos(this, Int.MIN_VALUE, Int.MIN_VALUE)
                        if (SpeedService.running) {
                            startService(
                                Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_RESET_CHIP)
                            )
                        }
                    }
                ),
                themeRow()
            ), 26
        )

        // --------------------------------------------------- Berechtigungen
        add(section("Berechtigungen"), 8)
        permLocation = PermRow("Standort", "Für die GPS-Geschwindigkeit") {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), 1)
        }
        permOverlay = PermRow("Anzeige über anderen Apps", "Für Rahmen und Geschwindigkeits-Fenster") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        permUsage = PermRow("Nutzungszugriff", "Optional, erkennt Google Maps im Vordergrund") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        add(card(permLocation.view, permOverlay.view, permUsage.view), 24)

        // ------------------------------------------------------------ Fuß
        add(
            tv(
                "Tempolimit-Daten: © OpenStreetMap-Mitwirkende (ODbL), abgerufen über die Overpass-API. " +
                    "Die Daten können unvollständig oder veraltet sein. " +
                    "Der Blick auf Tacho und Schilder bleibt maßgeblich.",
                12f, p.muted
            ).apply { setPadding(dp(4), 0, dp(4), 0) },
            0
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    private fun refresh() {
        val loc = Perms.hasLocation(this)
        val ovl = Perms.canOverlay(this)
        val use = Perms.hasUsageAccess(this)
        permLocation.set(loc, false)
        permOverlay.set(ovl, false)
        permUsage.set(use, true)

        val running = SpeedService.running
        val ready = loc && ovl

        statusDot.background = Ui.oval(
            when {
                running -> p.accent
                ready -> p.muted
                else -> p.warn
            }
        )
        statusTitle.text = when {
            running -> "Aktiv"
            ready -> "Bereit"
            else -> "Einrichtung nötig"
        }
        statusSub.text = when {
            running -> "Tempo wird überwacht"
            ready -> "Starte den Warner, bevor du losfährst"
            else -> "Erlaube unten Standort und Anzeige über anderen Apps"
        }

        if (running) {
            btnToggle.text = "Warner stoppen"
            btnToggle.setTextColor(p.danger)
            btnToggle.background = Ui.pressable(p, Ui.withAlpha(p.danger, 0x26), dpf(28))
        } else {
            btnToggle.text = "Warner starten"
            btnToggle.setTextColor(p.onAccent)
            btnToggle.background = Ui.pressable(p, p.accent, dpf(28))
        }
        val canToggle = running || ready
        btnToggle.isEnabled = canToggle
        btnToggle.alpha = if (canToggle) 1f else 0.4f

        btnTest.isEnabled = running
        btnTest.alpha = if (running) 1f else 0.4f
    }

    // ------------------------------------------------------------ Bausteine

    private fun tv(text: String, sp: Float, color: Int, medium: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun section(title: String): TextView = tv(title.uppercase(), 12f, p.muted, true).apply {
        letterSpacing = 0.1f
        setPadding(dp(8), 0, 0, 0)
    }

    private fun textCol(title: String, sub: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(12) }
        addView(tv(title, 16f, p.text, true))
        if (sub != null) addView(tv(sub, 13f, p.muted).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        views.forEach { addView(it) }
    }

    private fun card(vararg rows: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rect(p.surface, dpf(20), p.border, 1)
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                addView(View(this@MainActivity).apply {
                    setBackgroundColor(p.border)
                    layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { leftMargin = dp(16) }
                })
            }
            addView(r)
        }
    }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        tv(label, 14f, fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = Ui.pressable(p, bg, dpf(16))
            setOnClickListener { onClick() }
        }

    private fun roundButton(label: String, onClick: () -> Unit): TextView =
        tv(label, 20f, p.text, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = Ui.pressable(p, p.surfaceHi, dpf(18))
            setOnClickListener { onClick() }
        }

    private fun toggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val toggle = ToggleView(this, p.accent, p.trackOff).also {
            it.setState(checked, animate = false)
            it.onChange = onChange
        }
        return row(textCol(title, sub), toggle).apply { setOnClickListener { toggle.toggle() } }
    }

    private fun stepperRow(
        title: String,
        sub: String,
        value: Int,
        min: Int,
        max: Int,
        step: Int,
        onChange: (Int) -> Unit
    ): LinearLayout {
        var current = value
        val valueView = tv(current.toString(), 18f, p.text, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(52)
        }
        fun bump(delta: Int) {
            current = (current + delta).coerceIn(min, max)
            valueView.text = current.toString()
            onChange(current)
        }
        return row(
            textCol(title, sub),
            roundButton("−") { bump(-step) },
            valueView,
            roundButton("+") { bump(step) }
        )
    }

    /** Auswahl Automatisch / Dunkel / Hell. */
    private fun themeRow(): LinearLayout {
        val labels = listOf("Automatisch", "Dunkel", "Hell")
        val current = Prefs.theme(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rect(p.surfaceHi, dpf(14))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        labels.forEachIndexed { i, label ->
            val selected = i == current
            box.addView(
                tv(label, 14f, if (selected) p.onAccent else p.muted, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(9), 0, dp(9))
                    if (selected) background = Ui.rect(p.accent, dpf(11))
                    setOnClickListener {
                        if (i != Prefs.theme(this@MainActivity)) {
                            Prefs.setTheme(this@MainActivity, i)
                            recreate()
                        }
                    }
                },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(tv("Darstellung", 16f, p.text, true))
            addView(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    /** Zeile für eine Berechtigung mit Statussymbol und "Erlauben"-Knopf. */
    private inner class PermRow(title: String, sub: String, onClick: () -> Unit) {
        val icon: TextView = tv("", 14f, p.text, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(14) }
        }
        val action: TextView = pill("Erlauben", p.surfaceHi, p.text, onClick)
        val view: LinearLayout = row(icon, textCol(title, sub), action)

        fun set(granted: Boolean, optional: Boolean) {
            val color = when {
                granted -> p.accent
                optional -> p.muted
                else -> p.warn
            }
            icon.text = when {
                granted -> "✓"
                optional -> "–"
                else -> "!"
            }
            icon.setTextColor(color)
            icon.background = Ui.rect(Ui.withAlpha(color, 0x26), dpf(14))
            action.visibility = if (granted) View.GONE else View.VISIBLE
        }
    }
}
