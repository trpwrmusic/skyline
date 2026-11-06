# Skyline

Ein ruhiger Startbildschirm für Android-TV-Beamer: ein Fenster nach draußen, das zum Wetter passt, ein gläsernes App-Regal,
die Uhr auf Atomzeit und eine Fernbedienung fürs Handy. Entwickelt und getestet auf dem XGIMI XK03H (Android TV 11).
Sollte auf jedem Android-TV-Gerät ab Android 9 laufen.

## Was Skyline kann

- **Eigene Bilder und Videos als Hintergrund:** Hochladen geht übers Handy. Sie laufen zufällig gemischt. Ohne eigene Motive
  zeigt Skyline eingebaute Fotos von New York. Wer mag, gibt Motiven Merkmale wie „Regen“ oder „Nacht“ und stellt auf
  „Nach Wetter“ um: Dann passt das Bild zum Wetter am eigenen Ort (Open-Meteo, ohne Anmeldung).
- **Fenster-Modus:** Blendet alles aus, bis auf das Bild. Dazu kommen echte Fensterrahmen mit Sprossen in mehreren Rastern
  und Farben. Nach 2 Stunden ohne Tastendruck geht der Beamer schlafen.
- **Bildschirmschoner:** Zeigt dasselbe Fenster.
- **Wetteranzeige:** Symbol, Temperatur und Ort. Für Startbildschirm, Fenster-Modus und Bildschirmschoner getrennt
  ein- und ausschaltbar, in einer Ecke deiner Wahl.
- **App-Regal:** Lieblings-Apps zum Sortieren, dazu „Alle Apps“ und ein Medien-Dock für Spotify, YouTube Music und andere.
- **Handy-Fernbedienung:** Touchpad, Maus-Modus, Tastatur, App-Start, eigene **Fotos und Videos hochladen**, Einstellungen.
  Läuft im Browser oder als kleine Android-App, die den Beamer im WLAN selbst findet.
- **Updates von selbst:** Neue Versionen erscheinen hier als Release. Der Beamer installiert sie beim Start oder Aufwachen.

## Installation

Du brauchst einen PC mit [adb](https://developer.android.com/tools/releases/platform-tools) im selben WLAN wie den Beamer.

1. **Netzwerk-Debugging am Beamer einschalten:** Einstellungen → Geräteeinstellungen → Info → 7× auf „Build“ tippen.
   Danach unter Entwickleroptionen „USB-Debugging“ und „Netzwerk-Debugging“ (ADB über Netzwerk) einschalten.
   Die IP-Adresse steht unter Netzwerk & Internet.
2. **Skyline installieren:** `skyline.apk` aus dem [neuesten Release](../../releases/latest) laden, dann:
   ```
   adb connect <ip-des-beamers>:5555
   adb install skyline.apk
   ```
3. **Als Startbildschirm setzen:** Android TV hält am Google-Launcher fest, solange er aktiv ist. Schalte ihn deshalb ab.
   Er bleibt installiert und lässt sich jederzeit zurückholen:
   ```
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   adb shell input keyevent KEYCODE_HOME
   ```
   Zurück zum Original: `adb shell pm enable com.google.android.tvlauncher`, danach Skyline deinstallieren.
4. **Optional, als Bildschirmschoner:**
   ```
   adb shell settings put secure screensaver_components de.wesnik.skyline/.SkylineDream
   ```
5. **Beim ersten Start** fragt der Beamer „USB-Debugging zulassen?“. Wähle „Immer zulassen“. Damit startet Skyline seinen
   Helfer für die Handy-Fernbedienung und die Updates. Netzwerk-Debugging muss dafür eingeschaltet bleiben.

**Fernbedienung:** Unter Android `skyline-remote.apk` aus dem Release installieren. Die App sucht den Beamer selbst im WLAN,
bei mehreren Beamern fragt sie nach. Ohne App, etwa auf dem iPhone, öffnest du `http://<ip-des-beamers>:8080` im Browser
und legst die Seite auf den Home-Bildschirm.

**Hinweis:** Die Fernbedienung hat kein Passwort. Jeder im selben WLAN kann den Beamer steuern.

## Eigene Videos und Fotos

In der Fernbedienung unter **Medien** auf „Fotos oder Videos hinzufügen“ tippen, auswählen, hochladen. Querformat-Videos in
1080p (H.264) laufen am besten. Nahtlose Schleifen baut `tools/make_loop.sh` aus einem eigenen Clip.

Unter **Einstellungen → Hintergrund** wählst du:
- **Zufällig** (Standard): alle eigenen Motive gemischt.
- **Nach Wetter:** Motive mit passenden Merkmalen haben Vorrang. Die Merkmale setzt du beim Hochladen oder später im Detailblatt
  unter „Nur bei bestimmtem Wetter zeigen“ (Wetter, Tageszeit, Jahreszeit).

Skyline liefert keine Videos mit. Was du hochlädst, bleibt auf deinem Beamer. Achte bei fremdem Material selbst auf die Rechte.

Von Hand geht es auch: Dateien nach `/sdcard/Android/data/de.wesnik.skyline/files/videos/` bzw. `…/wallpapers/` legen,
optional mit einer gleichnamigen `.txt` daneben (Zeile 1 Titel, Zeile 2 Nachweis, Zeile 3 Merkmale wie `schnee nacht winter`).

## Updates

Unter **Einstellungen → Updates** stehen die installierte Version und der Stand der letzten Prüfung. Dort lässt sich das
automatische Update abschalten oder sofort prüfen. Skyline installiert nur Releases mit demselben Signaturschlüssel.

## Entwicklung

Gebaut wird ohne Gradle, mit den Android-Build-Tools 35 und Plattform 34:

```
launcher/build.sh      # Beamer-App   -> launcher/skyline.apk
handy/build.sh         # Handy-App    -> launcher/assets/remote/skyline-remote.apk (liefert der Beamer aus)
```

Jeder Push auf `main` startet `.github/workflows/release.yml`. Der Workflow baut beide Apps und signiert sie mit dem
Schlüssel aus den Secrets `SKYLINE_KEYSTORE` (Base64) und `SKYLINE_KEYSTORE_PASS`. Danach veröffentlicht er das Release
`build-<versionCode>`. Die versionCode ist 1000 plus die Laufnummer, der Versionsname kommt aus `launcher/AndroidManifest.xml`.

Aufbau in Kürze:

| Datei | Aufgabe |
| --- | --- |
| `HomeActivity` | Startbildschirm, Regal, Fenster-Modus |
| `Stage` | Video- und Fotobühne, Wetterwahl, Glas-Abbild |
| `RemoteService` | Webserver der Fernbedienung (Port 8080), mDNS `_skyline._tcp`, Update-Auslöser |
| `KeyServer` | Shell-Helfer (über ADB an sich selbst gestartet): Tasten, Touch, Dichte, `pm install` |
| `Updater` | GitHub-Releases prüfen, laden, Signatur prüfen |
| `Weather`, `WeatherBadge` | Wetter von Open-Meteo, Anzeige |
| `Media` | Upload, Liste, Vorschaubilder |

## Nachweise

Die eingebauten Fotos und das Fensterfoto stammen von Wikimedia Commons und stehen unter CC BY-SA.
Urheber, Lizenz und Quelle jedes Bildes stehen in [CREDITS.md](CREDITS.md). Die Wetterdaten kommen von [Open-Meteo](https://open-meteo.com).
