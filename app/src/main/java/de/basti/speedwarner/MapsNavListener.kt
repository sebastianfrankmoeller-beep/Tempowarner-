package de.basti.speedwarner

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Beobachtet die Benachrichtigungen und erkennt, wann in Google Maps eine Navigation läuft.
 *
 *  - Navigation beginnt  → Warner automatisch starten (falls Schalter an und Warner nicht schon läuft).
 *  - Navigation endet    → automatisch gestarteten Warner beenden. Manuell gestartete Sitzungen laufen weiter.
 *
 * Reagiert nur auf Wechsel (Start/Ende), nicht auf jede Aktualisierung der Maps-Benachrichtigung.
 * Wer den Warner während der Navigation von Hand stoppt, bekommt ihn also erst bei der nächsten
 * Navigation wieder automatisch.
 */
class MapsNavListener : NotificationListenerService() {

    companion object {
        /** Maps entfernt und erneuert die Benachrichtigung manchmal kurz (z. B. bei Neuberechnung). */
        private const val END_DELAY_MS = 4_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var navActive = false
    private val endCheck = Runnable { checkEnded() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (hasActiveNavigation() && !navActive) {
            navActive = true
            onNavigationStarted()
        }
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(endCheck)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !AutoStart.isMapsNavigation(sbn)) return
        handler.removeCallbacks(endCheck)
        if (!navActive) {
            navActive = true
            onNavigationStarted()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || !AutoStart.isMapsNavigation(sbn)) return
        handler.removeCallbacks(endCheck)
        handler.postDelayed(endCheck, END_DELAY_MS)
    }

    private fun checkEnded() {
        if (hasActiveNavigation()) return
        if (navActive) {
            navActive = false
            onNavigationEnded()
        }
    }

    private fun hasActiveNavigation(): Boolean = try {
        activeNotifications?.any { AutoStart.isMapsNavigation(it) } == true
    } catch (_: Exception) {
        false
    }

    private fun onNavigationStarted() {
        if (!Prefs.autoMaps(this) || SpeedService.running) return
        AutoStart.start(this)
    }

    private fun onNavigationEnded() {
        AutoStart.cancelPrompt(this)
        SpeedService.instance?.endAutoSession()
    }
}
