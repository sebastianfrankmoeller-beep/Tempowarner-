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
import android.view.WindowManager

/**
 * Dauerhafter Vordergrunddienst:
 *  - misst per GPS die Geschwindigkeit,
 *  - erkennt (optional) das Tempolimit der Straße aus OpenStreetMap-Daten,
 *  - zeigt bei Überschreitung einen roten Leuchtrahmen als Overlay,
 *  - blendet den Rahmen aus, solange Google Maps im Vordergrund ist.
 */
class SpeedService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "de.basti.speedwarner.STOP"
        const val ACTION_TEST = "de.basti.speedwarner.TEST"

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
    private var overlay: GlowView? = null

    private var speedKmh = 0f
    private var lastFixMs = 0L          // SystemClock.elapsedRealtime() des letzten GPS-Fixes
    private var warning = false         // Geschwindigkeit über Schwelle (mit Hysterese)
    private var testUntil = 0L          // erzwingt kurz den Rahmen (Test-Button)

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

        val wantGlow = warning || now < testUntil
        if (wantGlow && !mapsInFront) showOverlay() else hideOverlay()

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

    // ------------------------------------------------------------ Overlay

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
