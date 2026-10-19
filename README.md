# Tempowarner

Android-App, die beim Autofahren per GPS die Geschwindigkeit misst und bei Überschreitung
des Limits einen **roten, pulsierenden Leuchtrahmen** am Bildschirmrand zeigt – ganz ohne Ton.

- Läuft als Vordergrunddienst mit eigener Statusbenachrichtigung (wird nicht vom System beendet).
- Der Rahmen ist ein schwebendes Fenster über allen Apps (nicht anklickbar, Touch geht durch).
- Zusätzlich zeigt ein **kleines Geschwindigkeits-Fenster** über allen Apps die aktuelle Geschwindigkeit
  und das Limit. Es lässt sich mit dem Finger verschieben, am Dreieck unten rechts ziehen oder mit
  zwei Fingern spreizen (Größe ändern) und wird bei Überschreitung rot.
- Ist **Google Maps** im Vordergrund, wird das Geschwindigkeits-Fenster ausgeblendet; dort leuchtet
  nur der Bildschirmrand.
- **Tempolimit wird automatisch erkannt** (siehe unten), alternativ stellst du es manuell ein.
- Toleranz (Standard: Warnung ab +1 km/h über dem Limit) stellst du in der App ein.
- Button „Leuchtrahmen 3 s testen“ zum Ausprobieren, ohne zu fahren.

## Automatische Limit-Erkennung

Die App lädt per Internet die Straßen rund um deine Position aus **OpenStreetMap** (Overpass-API)
und sucht an jedem GPS-Punkt die Straße, die zu Position und Fahrtrichtung passt. Deren Eintrag
`maxspeed` ist das erkannte Limit. Es steht in der Benachrichtigung („Limit 50 (erkannt)“).

- Es wird nur etwa alle 700 m Fahrstrecke neu geladen (Zwischenspeicher, wenig Datenvolumen).
- Ein neues Limit gilt erst, wenn es 3 Sekunden in Folge erkannt wurde (kein Flackern an Kreuzungen).
- Steht für eine Straße kein Limit in den Daten oder ist kein Netz da, gilt dein **manuelles Limit**
  als Rückfall (Benachrichtigung: „Straße unbekannt“).
- Steht für eine Straße ausdrücklich „kein Limit“ (z. B. Autobahn ohne Beschränkung), gibt es keine Warnung.
- Nicht erfasst werden Baustellen, Wechselverkehrszeichen und zeitabhängige Limits.
- Die Automatik lässt sich in der App ausschalten, dann gilt immer das manuelle Limit.

Kartendaten: © OpenStreetMap-Mitwirkende (ODbL).

## Auf GitHub bauen

1. Bei jedem Push startet automatisch der Workflow **Build APK** (Reiter *Actions*).
   Manuell geht es auch über *Run workflow*.
2. Nach ca. 2–4 Minuten im fertigen Lauf unter **Artifacts** den Eintrag `Tempowarner-APK`
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
  Die App ist eine Zusatzhilfe, der Blick auf den Tacho und die Schilder bleibt maßgeblich.

## Projektaufbau

| Datei | Aufgabe |
|---|---|
| `SpeedService.kt` | Vordergrunddienst: GPS, Schwellenwert, Overlay, Maps-Erkennung, Benachrichtigung |
| `LimitProvider.kt` | Tempolimit aus OpenStreetMap: Abruf, Zwischenspeicher, Straßenzuordnung |
| `GlowView.kt` | Zeichnet den roten Leuchtrahmen |
| `MainActivity.kt` | Einstellungen, Berechtigungen, Start/Stopp |
| `Util.kt` | Gespeicherte Einstellungen, Berechtigungsprüfungen |
| `.github/workflows/build.yml` | Automatischer Build bei jedem Push |
