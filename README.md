# Tempowarner

Android-App, die beim Autofahren per GPS die Geschwindigkeit misst und bei Überschreitung
des Limits einen **roten, pulsierenden Leuchtrahmen** am Bildschirmrand zeigt – ganz ohne Ton.

- Läuft als Vordergrunddienst mit eigener Statusbenachrichtigung (wird nicht vom System beendet).
- Der Rahmen ist ein schwebendes Fenster über allen Apps (nicht anklickbar, Touch geht durch).
- Ist **Google Maps** im Vordergrund, wird der Rahmen automatisch ausgeblendet.
- Limit und Toleranz (Standard: 50 km/h, Warnung ab +1 km/h) stellst du in der App ein.
- Button „Leuchtrahmen 3 s testen“ zum Ausprobieren, ohne zu fahren.

## Auf GitHub bauen

1. Inhalt dieses Ordners in ein GitHub-Repository hochladen (inkl. `.github/` und `app/debug.keystore`).
2. Bei jedem Push startet automatisch der Workflow **Build APK** (Reiter *Actions*).
   Manuell geht es auch über *Run workflow*.
3. Nach ca. 2–4 Minuten im fertigen Lauf unter **Artifacts** den Eintrag `Tempowarner-APK`
   herunterladen, entpacken, `app-debug.apk` aufs Handy kopieren und installieren.

Updates: Weil alle Builds mit demselben Debug-Schlüssel (`app/debug.keystore`) signiert sind und die
Versionsnummer mit jedem Lauf steigt, lässt sich eine neue APK einfach über die alte installieren.

## Einrichtung auf dem Handy

1. App öffnen, **Standort** und Benachrichtigungen erlauben.
2. **Anzeige über anderen Apps** erlauben (Pflicht für den Rahmen).
3. **Nutzungszugriff** erlauben (nur nötig, damit der Rahmen in Google Maps verschwindet).
4. „Warner starten“.

Hinweise:

- Bei aus dem Browser installierten Apps blockiert Android 13+ diese beiden Sonderrechte zunächst.
  Dann: *Einstellungen → Apps → Tempowarner → ⋮ (oben rechts) → Eingeschränkte Einstellungen zulassen*.
- Damit der Dienst zuverlässig weiterläuft: *Einstellungen → Apps → Tempowarner → Akku → Unbeschränkt*.
- Die GPS-Geschwindigkeit ist genauer als der Tacho vieler Autos und weicht daher oft nach unten ab.
  Die App ist eine Zusatzhilfe, der Blick auf den Tacho bleibt maßgeblich.

## Projektaufbau

| Datei | Aufgabe |
|---|---|
| `SpeedService.kt` | Vordergrunddienst: GPS, Schwellenwert, Overlay, Maps-Erkennung, Benachrichtigung |
| `GlowView.kt` | Zeichnet den roten Leuchtrahmen |
| `MainActivity.kt` | Einstellungen, Berechtigungen, Start/Stopp |
| `Util.kt` | Gespeichertes Limit, Berechtigungsprüfungen |
| `.github/workflows/build.yml` | Automatischer Build bei jedem Push |
