package de.basti.speedwarner

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings

/** Einstellungen (Limit und Toleranz in km/h, Automatik). */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("speedwarner", Context.MODE_PRIVATE)

    /** Manuelles Limit; bei aktiver Automatik der Rückfall, wenn die Straße unbekannt ist. */
    fun limit(c: Context): Int = sp(c).getInt("limit", 50)
    fun tolerance(c: Context): Int = sp(c).getInt("tolerance", 1)

    /** Tempolimit automatisch aus OpenStreetMap erkennen (Standard: an). */
    fun auto(c: Context): Boolean = sp(c).getBoolean("auto", true)

    fun setLimit(c: Context, v: Int) = sp(c).edit().putInt("limit", v).apply()
    fun setTolerance(c: Context, v: Int) = sp(c).edit().putInt("tolerance", v).apply()
    fun setAuto(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto", v).apply()
}

/** Prüfung der drei Berechtigungen. */
object Perms {
    fun hasLocation(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
