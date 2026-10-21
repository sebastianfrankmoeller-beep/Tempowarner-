package de.basti.speedwarner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.notification.StatusBarNotification

/**
 * Autostart mit Google Maps: Erkennung der Navigations-Benachrichtigung, Start des Warners
 * und die Rückfall-Benachrichtigung "Tempowarner starten?".
 */
object AutoStart {

    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    private const val CHANNEL_ID = "speedwarner_autostart"
    private const val PROMPT_ID = 2

    /**
     * true, wenn die Benachrichtigung die laufende Navigation von Google Maps ist:
     * Kategorie "navigation" oder eine dauerhafte Benachrichtigung aus einem Navigations-Kanal.
     */
    fun isMapsNavigation(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != MAPS_PACKAGE) return false
        val n = sbn.notification ?: return false
        if (n.category == "navigation") return true
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val channel = n.channelId?.lowercase() ?: ""
        return ongoing && channel.contains("nav")
    }

    /** Hat der Nutzer den Benachrichtigungszugriff für den Autostart erlaubt? */
    fun hasNotificationAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        val me = ComponentName(ctx, MapsNavListener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    /** Versucht, den Warner automatisch zu starten. Blockiert Android das, erscheint die Nachfrage. */
    fun start(ctx: Context) {
        val intent = Intent(ctx, SpeedService::class.java).setAction(SpeedService.ACTION_AUTO_START)
        try {
            ctx.startForegroundService(intent)
        } catch (_: Exception) {
            // z. B. ForegroundServiceStartNotAllowedException (Start aus dem Hintergrund verboten)
            showPrompt(ctx)
        }
    }

    /** Benachrichtigung "Tempowarner starten?" – Antippen startet den Warner (das erlaubt Android immer). */
    fun showPrompt(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID, "Autostart mit Google Maps", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Nachfrage, wenn der Tempowarner nicht automatisch starten darf"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)

        val tap = PendingIntent.getForegroundService(
            ctx, 2,
            Intent(ctx, SpeedService::class.java)
                .setAction(SpeedService.ACTION_AUTO_START)
                .putExtra(SpeedService.EXTRA_FROM_PROMPT, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Tempowarner starten?")
            .setContentText("Navigation in Google Maps erkannt. Zum Starten antippen.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), "Starten", tap).build()
            )
            .build()
        try {
            nm.notify(PROMPT_ID, n)
        } catch (_: Exception) {
        }
    }

    fun cancelPrompt(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(PROMPT_ID)
    }
}
