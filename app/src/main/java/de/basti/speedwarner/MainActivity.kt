package de.basti.speedwarner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var tvLocation: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvUsage: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnTest: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "Tempowarner"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Roter Leuchtrahmen bei Überschreitung des Tempolimits – ganz ohne Ton."
            setPadding(0, dp(4), 0, dp(20))
        })

        // --- Einstellungen
        root.addView(Switch(this).apply {
            text = "Tempolimit automatisch erkennen (OpenStreetMap, braucht Internet)"
            isChecked = Prefs.auto(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAuto(this@MainActivity, checked) }
        })
        root.addView(TextView(this).apply {
            text = "Ist für eine Straße kein Limit hinterlegt, gilt das manuelle Limit unten."
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(label("Manuelles Tempolimit bzw. Rückfall (km/h)"))
        root.addView(numberField(Prefs.limit(this)) { v ->
            Prefs.setLimit(this, v.coerceIn(5, 300))
        })
        root.addView(label("Warnung ab … km/h über dem Limit"))
        root.addView(numberField(Prefs.tolerance(this)) { v ->
            Prefs.setTolerance(this, v.coerceIn(0, 50))
        })

        // --- Berechtigungen
        root.addView(label("Berechtigungen").apply { setPadding(0, dp(24), 0, 0) })

        tvLocation = statusLine()
        root.addView(tvLocation)
        root.addView(button("Standort erlauben") {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), 1)
        })

        tvOverlay = statusLine()
        root.addView(tvOverlay)
        root.addView(button("Anzeige über anderen Apps erlauben") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        tvUsage = statusLine()
        root.addView(tvUsage)
        root.addView(button("Nutzungszugriff erlauben (für Google Maps)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        // --- Start / Test
        btnToggle = button("") {
            if (SpeedService.running) {
                startService(Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_STOP))
            } else {
                startForegroundService(Intent(this, SpeedService::class.java))
            }
            it.postDelayed({ refresh() }, 500)
        }
        btnToggle.setPadding(0, dp(16), 0, dp(16))
        (btnToggle.layoutParams as LinearLayout.LayoutParams).topMargin = dp(32)
        root.addView(btnToggle)

        btnTest = button("Leuchtrahmen 3 s testen") {
            startService(Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_TEST))
        }
        root.addView(btnTest)

        // Quellenhinweis (Pflicht bei Nutzung von OpenStreetMap-Daten)
        root.addView(TextView(this).apply {
            text = "Tempolimit-Daten: © OpenStreetMap-Mitwirkende (ODbL), " +
                "abgerufen über die Overpass-API. Die Daten können unvollständig oder veraltet sein."
            textSize = 12f
            setPadding(0, dp(24), 0, 0)
        })
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

        tvLocation.text = (if (loc) "✓ " else "✗ ") + "Standort (GPS)"
        tvOverlay.text = (if (ovl) "✓ " else "✗ ") + "Anzeige über anderen Apps"
        tvUsage.text = (if (use) "✓ " else "– ") + "Nutzungszugriff (optional, blendet Rahmen in Google Maps aus)"

        val running = SpeedService.running
        btnToggle.text = if (running) "Warner stoppen" else "Warner starten"
        btnToggle.isEnabled = running || (loc && ovl)
        btnTest.isEnabled = running
    }

    // ------------------------------------------------------------ UI-Helfer

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun statusLine() = TextView(this).apply {
        setPadding(0, dp(12), 0, 0)
    }

    private fun button(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick(it) }
    }

    private fun numberField(initial: Int, onValue: (Int) -> Unit) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(initial.toString())
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.toIntOrNull()?.let(onValue)
            }
        })
    }
}
