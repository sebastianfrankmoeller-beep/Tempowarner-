package de.basti.speedwarner

import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ermittelt das Tempolimit der aktuellen Straße aus OpenStreetMap-Daten (Tag "maxspeed").
 *
 * Ablauf:
 *  1. Über die Overpass-API werden alle befahrbaren Straßen im Umkreis von ca. 1,3 km geladen
 *     und im Speicher gehalten (nur ein Abruf pro ca. 700 m Fahrstrecke bzw. alle 30 Minuten).
 *  2. Bei jedem GPS-Fix wird die nächstgelegene Straße gesucht, deren Richtung zur Fahrtrichtung passt.
 *  3. Ein neuer Wert wird erst übernommen, wenn er in 3 Fixes in Folge erkannt wurde (Entprellung).
 *
 * Ergebnis von [limitFor]:
 *  - null      → Limit unbekannt (keine Straße gefunden oder kein Limit eingetragen)
 *  - NO_LIMIT  → ausdrücklich kein Limit (z. B. Autobahn ohne Beschränkung)
 *  - sonst     → Limit in km/h
 *
 * Kartendaten: © OpenStreetMap-Mitwirkende (ODbL).
 */
class LimitProvider {

    companion object {
        const val NO_LIMIT = -1

        private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val USER_AGENT = "Tempowarner/1.0 (privater Android-Tempowarner)"

        private const val BOX_DEG = 0.012               // ca. 1,3 km um die Abrufposition
        private const val REFETCH_DISTANCE_M = 700.0
        private const val MAX_AGE_MS = 30 * 60 * 1000L
        private const val BACKOFF_MS = 30_000L

        private const val MAX_MATCH_DISTANCE_M = 30.0
        private const val MIN_HEADING_SPEED_KMH = 8f
        private const val MAX_HEADING_DIFF_DEG = 50.0
        private const val CONFIRM_FIXES = 3

        private val RE_NUMBER = Regex("^\\d{1,3}$")
        private val RE_MPH = Regex("^(\\d{1,3}) ?mph$")
    }

    private class Way(
        val maxspeed: Int?,
        val forward: Int?,
        val backward: Int?,
        val lat: DoubleArray,
        val lon: DoubleArray
    )

    @Volatile private var ways: List<Way> = emptyList()
    @Volatile private var hasData = false
    @Volatile private var centerLat = 0.0
    @Volatile private var centerLon = 0.0
    @Volatile private var fetchedAt = 0L
    @Volatile private var backoffUntil = 0L

    private val fetching = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    private var shown: Int? = null
    private var pending: Int? = null
    private var pendingCount = 0

    fun close() {
        executor.shutdownNow()
    }

    // ------------------------------------------------------------ Abruf

    /** Lädt bei Bedarf (erste Position, > 700 m vom Abrufzentrum, Daten älter als 30 min) neue Straßendaten. */
    fun fetchIfNeeded(lat: Double, lon: Double) {
        val now = SystemClock.elapsedRealtime()
        if (fetching.get() || now < backoffUntil) return
        val stale = !hasData ||
            now - fetchedAt > MAX_AGE_MS ||
            distanceM(centerLat, centerLon, lat, lon) > REFETCH_DISTANCE_M
        if (!stale) return
        if (!fetching.compareAndSet(false, true)) return

        try {
            executor.execute {
                try {
                    val parsed = download(lat, lon)
                    ways = parsed
                    centerLat = lat
                    centerLon = lon
                    fetchedAt = SystemClock.elapsedRealtime()
                    hasData = true
                } catch (_: Exception) {
                    // kein Netz, Server überlastet o. Ä.: 30 s Pause, dann erneut versuchen
                    backoffUntil = SystemClock.elapsedRealtime() + BACKOFF_MS
                } finally {
                    fetching.set(false)
                }
            }
        } catch (_: Exception) {
            fetching.set(false)
        }
    }

    private fun download(lat: Double, lon: Double): List<Way> {
        val dLat = BOX_DEG
        val dLon = BOX_DEG / cos(Math.toRadians(lat))
        val query = "[out:json][timeout:20];" +
            "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
            "living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)\$\"]" +
            "(${lat - dLat},${lon - dLon},${lat + dLat},${lon + dLon});" +
            "out tags geom qt;"

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use {
                it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
            }
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<Way> {
        val json = JSONObject(body)
        // Overpass meldet Zeitüberschreitungen mit HTTP 200 und einem "remark"
        if (json.optString("remark", "").contains("error", ignoreCase = true)) {
            throw IllegalStateException("Overpass-Fehler")
        }
        val elements = json.getJSONArray("elements")
        val out = ArrayList<Way>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val geom = el.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue
            val tags = el.optJSONObject("tags") ?: JSONObject()
            val lat = DoubleArray(geom.length())
            val lon = DoubleArray(geom.length())
            for (j in 0 until geom.length()) {
                val p = geom.getJSONObject(j)
                lat[j] = p.getDouble("lat")
                lon[j] = p.getDouble("lon")
            }
            out.add(
                Way(
                    parseSpeed(tags.optString("maxspeed", "")),
                    parseSpeed(tags.optString("maxspeed:forward", "")),
                    parseSpeed(tags.optString("maxspeed:backward", "")),
                    lat,
                    lon
                )
            )
        }
        return out
    }

    /** Wandelt einen OSM-maxspeed-Wert in km/h um. null = nicht auswertbar. */
    private fun parseSpeed(raw: String?): Int? {
        val v = raw?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        val mph = RE_MPH.matchEntire(v)
        if (mph != null) return (mph.groupValues[1].toInt() * 1.609).roundToInt()
        return when {
            v == "none" || v == "de:motorway" -> NO_LIMIT
            v == "de:urban" -> 50
            v == "de:rural" -> 100
            v == "walk" || v == "de:walk" || v == "de:living_street" -> 7
            v == "de:bicycle_road" -> 30
            RE_NUMBER.matchEntire(v) != null -> v.toInt()
            else -> null // z. B. "signals" oder zeitabhängige Angaben
        }
    }

    // ------------------------------------------------------ Zuordnung

    /**
     * Gibt das Limit der Straße an der aktuellen Position zurück (siehe Klassenbeschreibung).
     * [bearing] ist die Fahrtrichtung in Grad (oder null), wird erst ab ca. 8 km/h verwendet.
     */
    fun limitFor(lat: Double, lon: Double, bearing: Float?, speedKmh: Float): Int? {
        val heading = if (bearing != null && speedKmh >= MIN_HEADING_SPEED_KMH) bearing.toDouble() else null
        val raw = match(lat, lon, heading)

        // Entprellung: neuer Wert erst nach CONFIRM_FIXES gleichen Ergebnissen übernehmen
        if (raw == shown) {
            pendingCount = 0
        } else {
            if (raw == pending) pendingCount++ else {
                pending = raw
                pendingCount = 1
            }
            if (pendingCount >= CONFIRM_FIXES) {
                shown = raw
                pendingCount = 0
            }
        }
        return shown
    }

    private fun match(lat0: Double, lon0: Double, heading: Double?): Int? {
        val list = ways
        if (list.isEmpty()) return null

        val mx = 111_320.0 * cos(Math.toRadians(lat0))
        val my = 110_540.0

        var bestScore = Double.MAX_VALUE
        var best: Way? = null
        var bestForward = true

        for (w in list) {
            for (i in 0 until w.lat.size - 1) {
                val ax = (w.lon[i] - lon0) * mx
                val ay = (w.lat[i] - lat0) * my
                val bx = (w.lon[i + 1] - lon0) * mx
                val by = (w.lat[i + 1] - lat0) * my
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 < 1e-6) continue

                // Abstand des Punktes (0,0) zur Strecke A-B
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val dist = hypot(ax + t * dx, ay + t * dy)
                if (dist > MAX_MATCH_DISTANCE_M) continue

                var score = dist
                var forward = true
                if (heading != null) {
                    val segBearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                    val diff = angleDiff(heading, segBearing)       // 0..180
                    val undirected = min(diff, 180.0 - diff)         // Straße darf in beide Richtungen verlaufen
                    if (undirected > MAX_HEADING_DIFF_DEG) continue
                    score += undirected * 0.3
                    forward = diff < 90.0
                }
                if (score < bestScore) {
                    bestScore = score
                    best = w
                    bestForward = forward
                }
            }
        }

        val way = best ?: return null
        val directional = if (heading != null) {
            if (bestForward) way.forward else way.backward
        } else {
            null
        }
        return directional ?: way.maxspeed
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = (lat2 - lat1) * 110_540.0
        return hypot(x, y)
    }
}
