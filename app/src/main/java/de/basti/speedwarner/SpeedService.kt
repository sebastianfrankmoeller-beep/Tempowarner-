package de.basti.speedwarner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager

/**
 * Dauerhafter Vordergrunddienst:
 *  - misst per GPS die Geschwindigkeit,
 *  - erkennt (optional) das Tempolimit der Straße aus OpenStreetMap-Daten,
 *  - zeigt ein kleines, verschiebbares Geschwindigkeits-Fenster über anderen Apps,
 *  - lässt bei Überschreitung den Bildschirmrand rot leuchten,
 *  - blendet das Geschwindigkeits-Fenster aus, solange Google Maps im Vordergrund ist
 *    (der Leuchtrahmen bleibt auch dort aktiv).
 */
class SpeedService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "de.basti.speedwarner.STOP"
        const val ACTION_TEST = "de.basti.speedwarner.TEST"
        const val ACTION_RESET_CHIP = "de.basti.speedwarner.RESET_CHIP"

        private const val CHANNEL_ID = "speedwarner_status"
        private const val NOTIF_ID = 1
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val GPS_TIMEOUT_MS = 5_000L

        @Volatile
        var running = false
    }

    private lateinit var nm: NotificationManager
    private lateinit var wm: WindowManager
    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private val limits = LimitProvider()
    private var detectedLimit: Int? = null   // null = unbekannt, NO_LIMIT = kein Limit

    private var started = false
    private var overlay: GlowView? = null          // Leuchtrahmen
    private var chip: SpeedChip? = null            // Geschwindigkeits-Fenster
    private var chipParams: WindowManager.LayoutParams? = null

    private var speedKmh = 0f
    private var lastFixMs = 0L          // SystemClock.elapsedRealtime() des letzten GPS-Fixes
    private var warning = false         // Geschwindigkeit über Schwelle (mit Hysterese)
    private var testUntil = 0L          // erzwingt kurz Rahmen und rotes Fenster (Test-Button)

    private var foregroundPkg: String? = null
    private var lastUsageQuery = 0L
    private var mapsInFront = false

    private var lastNotifText = ""

    private val tick = object : Runnable {
        override fun run() {
            pollForegroundApp()
            evaluate()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        wm = getSystemService(WindowManager::class.java)
        lm = getSystemService(LocationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Tempowarner", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET_CHIP -> {
                // Fenster neu aufbauen, es übernimmt dann die gespeicherten Standardwerte
                hideChip()
                if (started) evaluate()
                return START_STICKY
            }
            ACTION_TEST -> {
                testUntil = SystemClock.elapsedRealtime() + 3000
                if (started) evaluate()
                return START_STICKY
            }
        }
        if (started) return START_STICKY

        try {
            val n = buildNotification("Warte auf GPS …")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
            // z. B. fehlende Berechtigung oder Start aus dem Hintergrund nicht erlaubt
            stopSelf()
            return START_NOT_STICKY
        }

        started = true
        running = true
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
        limits.close()
        hideOverlay()
        hideChip()
        started = false
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- GPS

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        speedKmh = location.speed * 3.6f
        lastFixMs = SystemClock.elapsedRealtime()

        if (Prefs.auto(this)) {
            limits.fetchIfNeeded(location.latitude, location.longitude)
            detectedLimit = limits.limitFor(
                location.latitude,
                location.longitude,
                if (location.hasBearing()) location.bearing else null,
                speedKmh
            )
        } else {
            detectedLimit = null
        }
        evaluate()
    }

    // Ab API 29 optional, auf API 26-28 aber zwingend vorhanden:
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------------------------------------------------------- Auswertung

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val auto = Prefs.auto(this)
        val detected = if (auto) detectedLimit else null
        val noLimit = detected == LimitProvider.NO_LIMIT
        val limit = if (detected != null && !noLimit) detected else Prefs.limit(this)
        val threshold = limit + Prefs.tolerance(this)
        val gpsFresh = now - lastFixMs < GPS_TIMEOUT_MS

        // 0,5 km/h Hysterese gegen Flackern direkt an der Schwelle
        warning = when {
            !gpsFresh || noLimit -> false
            warning -> speedKmh >= threshold - 0.5f
            else -> speedKmh >= threshold
        }
        val alarm = warning || now < testUntil

        // Leuchtrahmen: immer bei Überschreitung, auch in Google Maps
        if (alarm) showOverlay() else hideOverlay()

        // Geschwindigkeits-Fenster: über allen Apps außer Google Maps
        if (Prefs.chip(this) && Perms.canOverlay(this) && !mapsInFront) {
            val speedText = if (gpsFresh) Math.round(speedKmh).toString() else "--"
            val caption = if (gpsFresh) "km/h" else "kein GPS"
            val label = if (noLimit) "∞" else limit.toString()
            showChip()
            chip?.update(speedText, caption, label, detected != null, alarm)
        } else {
            hideChip()
        }

        val limitText = when {
            noLimit -> "kein Limit"
            detected != null -> "Limit $limit (erkannt)"
            auto -> "Limit $limit (Straße unbekannt)"
            else -> "Limit $limit"
        }
        val text = when {
            !Perms.canOverlay(this) -> "Overlay-Berechtigung fehlt"
            !gpsFresh -> "Kein GPS-Signal · $limitText"
            else -> "${Math.round(speedKmh)} km/h · $limitText" +
                if (mapsInFront) " · Maps aktiv" else ""
        }
        if (text != lastNotifText) {
            lastNotifText = text
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ------------------------------------------------- Vordergrund-App (Maps)

    @Suppress("DEPRECATION")
    private fun pollForegroundApp() {
        if (!Perms.hasUsageAccess(this)) {
            mapsInFront = false
            return
        }
        try {
            val usm = getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            // Beim ersten Lauf 24 h zurückschauen, danach nur das neue Fenster (mit 2 s Überlappung)
            val begin = if (lastUsageQuery == 0L) now - 24 * 3600 * 1000L else lastUsageQuery - 2000L
            val events = usm.queryEvents(begin, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foregroundPkg = e.packageName
                }
            }
            lastUsageQuery = now
            mapsInFront = foregroundPkg == MAPS_PACKAGE
        } catch (_: Exception) {
            mapsInFront = false
        }
    }

    // --------------------------------------------------- Overlay: Leuchtrahmen

    private fun showOverlay() {
        if (!Perms.canOverlay(this)) return
        if (overlay == null) {
            val view = GlowView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            // Android 12+ blockiert Berührungen durch Overlays mit Deckkraft über 0,8.
            // Mit 0,8 bleiben die Apps unter dem Rahmen (z. B. Google Maps) bedienbar.
            lp.alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try {
                wm.addView(view, lp)
                overlay = view
            } catch (_: Exception) {
                return
            }
        }
        overlay?.start()
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        v.stop()
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }

    // ----------------------------------------- Overlay: Geschwindigkeits-Fenster

    private fun showChip() {
        if (chip != null) return
        val view = SpeedChip(this)
        val d = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Prefs.chipX(this).takeIf { it != Int.MIN_VALUE } ?: (16 * d).toInt()
        lp.y = Prefs.chipY(this).takeIf { it != Int.MIN_VALUE } ?: (120 * d).toInt()

        view.setScale(Prefs.chipScale(this))

        // Bedienung des Fensters:
        //  - mit einem Finger verschieben,
        //  - am Dreieck unten rechts ziehen oder mit zwei Fingern spreizen/zusammenziehen: Größe ändern.
        // Position und Größe werden gemerkt.
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    view.setScale(view.chipScale * detector.scaleFactor)
                    return true
                }
            }
        )

        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f
            private var startScale = 1f
            private var startWidth = 1
            private var resizing = false     // Ziehgriff unten rechts wird gezogen
            private var gestureLocked = false // nach Zwei-Finger-Geste kein Verschieben bis alle Finger weg sind

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val params = chipParams ?: return false
                scaleDetector.onTouchEvent(e)

                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        downX = e.rawX
                        downY = e.rawY
                        startScale = view.chipScale
                        startWidth = v.width.coerceAtLeast(1)
                        resizing = view.inGrip(e.x, e.y)
                        gestureLocked = false
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        gestureLocked = true
                        resizing = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (scaleDetector.isInProgress || gestureLocked) return true
                        if (resizing) {
                            // Breite des Fensters folgt dem Finger, Größe skaliert mit
                            val newWidth = (startWidth + (e.rawX - downX)).coerceAtLeast(1f)
                            view.setScale(startScale * newWidth / startWidth)
                        } else {
                            val dm = resources.displayMetrics
                            params.x = (startX + (e.rawX - downX).toInt())
                                .coerceIn(0, (dm.widthPixels - v.width).coerceAtLeast(0))
                            params.y = (startY + (e.rawY - downY).toInt())
                                .coerceIn(0, (dm.heightPixels - v.height).coerceAtLeast(0))
                            try {
                                wm.updateViewLayout(v, params)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!gestureLocked && !resizing) {
                            Prefs.setChipPos(this@SpeedService, params.x, params.y)
                        }
                        Prefs.setChipScale(this@SpeedService, view.chipScale)
                        resizing = false
                        gestureLocked = false
                    }
                }
                return true
            }
        })

        try {
            wm.addView(view, lp)
            chip = view
            chipParams = lp
        } catch (_: Exception) {
        }
    }

    private fun hideChip() {
        val v = chip ?: return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
        chip = null
        chipParams = null
    }

    // ------------------------------------------------------- Benachrichtigung

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpeedService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Tempowarner aktiv")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Beenden", stop
                ).build()
            )
            .build()
    }
}
