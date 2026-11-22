# Erweiterungen für die Skyline-App

Die Skyline-App für Android ist ein Hub für Heimmedien. Jedes Gerät kommt als **Erweiterung** dazu: der Skyline-Beamer,
ein Denon-Receiver und alles, was du selbst baust. Eine Erweiterung ist eine kleine Web-App (HTML, CSS, JavaScript) mit
einem Manifest. Sie bekommt einen eigenen Platz in der Leiste unten, eine Karte auf der Startseite und über das SDK
Zugriff auf das WLAN, den ein Browser nicht hätte: rohe TCP-Verbindungen, HTTP ohne CORS, Gerätesuche im Netz und mDNS.

## Aufbau

```
meine-erweiterung/
  manifest.json   Pflicht
  index.html      Einstieg (Name frei, siehe "entry")
  icon.svg        einfarbig weiß, 24er Raster
  …               beliebige weitere Dateien, auch Unterordner
```

Ein vollständiges Beispiel liegt unter [`docs/beispiel-erweiterung`](docs/beispiel-erweiterung).

## manifest.json

```json
{
  "id": "hallo",
  "name": "Hallo Welt",
  "version": "0.1.0",
  "hub": 1,
  "author": "Du",
  "summary": "Ein Satz für die Store-Liste.",
  "description": "Ein Absatz für die Detailseite im Store.",
  "entry": "index.html",
  "icon": "icon.svg",
  "tab": { "label": "Hallo" },
  "home": true,
  "permissions": ["scan"],
  "links": ["skyline://hallo"]
}
```

| Feld | Bedeutung |
| --- | --- |
| `id` | Kleinbuchstaben, Ziffern, Bindestrich, höchstens 31 Zeichen. Gleichzeitig der Ordnername. |
| `version` | Wird mit der installierten Fassung verglichen; höher heißt Update im Store. |
| `hub` | API-Stufe des SDK, derzeit `1`. |
| `entry` | Startseite der Erweiterung, Standard `index.html`. |
| `icon` | SVG, einfarbig weiß. Die App färbt es selbst (Leiste, Store, Karte). |
| `tab` | Wenn vorhanden, bekommt die Erweiterung einen Platz in der Leiste unten (bis zu drei Erweiterungen). `label` ist der kurze Name dort. |
| `home` | `false` blendet die Karte auf der Startseite aus. |
| `permissions` | Rechte, siehe unten. Alles andere ist ohne Recht erlaubt. |
| `links` | Anfänge von `skyline://`-Links, die an diese Erweiterung gehen (Ereignis `link`). |
| `featured` | Im Store groß oben zeigen. |
| `preinstall` | Nur für mitgelieferte Erweiterungen: beim ersten Start gleich hinzufügen. |

### Rechte

| Recht | Erlaubt |
| --- | --- |
| `tcp` | `hub.tcp.connect` – rohe TCP-Verbindungen, z. B. Telnet zu einem Receiver |
| `http` | `hub.http.request` – HTTP und HTTPS über die App, ohne CORS-Grenzen |
| `scan` | `hub.net.scan` – Adressen im eigenen /24-Netz auf einen Port prüfen |
| `mdns` | `hub.net.mdns` – Geräte finden, die sich per Bonjour/DNS-SD ankündigen |

Der Store zeigt die Rechte vor dem Hinzufügen in Worten an. Ohne Recht lehnt die App den Aufruf mit einer Fehlermeldung ab.

## Sicherheit

Jede Erweiterung läuft in einem eigenen iframe mit eigenem Ursprung (`http://<id>.ext.hub.local`). Sie hat damit einen
eigenen `localStorage`, kommt nicht an die App oder andere Erweiterungen heran und spricht mit der App nur über
`postMessage`. Die App prüft jeden Aufruf gegen das Manifest, bevor sie ihn an Android weitergibt.

## SDK

```html
<link rel="stylesheet" href="http://hub.local/sdk/hub.css">
<script src="http://hub.local/sdk/hub.js"></script>
```

Alles hängt an `window.hub`. Aufrufe liefern Promises.

```js
await hub.ready;                       // {api, ext, app: {version, device, android}, visible}

// HTTP ohne CORS (Recht "http")
const r = await hub.http.request('http://192.168.0.20/status', { method: 'POST', body: 'x=1', headers: { 'Content-Type': 'text/plain' }, timeout: 6000 });
r.status; r.headers; r.body;           // body als Text; responseType: 'base64' für Binärdaten
const data = await hub.http.json('http://192.168.0.20/info.json');

// TCP (Recht "tcp")
const sock = await hub.tcp.connect('192.168.0.30', 23, { encoding: 'latin1' });   // 'latin1' | 'utf8' | 'base64'
sock.on('data', text => console.log(text));
sock.on('close', err => console.log('zu', err));
await sock.write('PW?\r');
sock.close();

// Netz
await hub.net.info();                  // {ip, subnet, wifi, online}
await hub.net.scan({ port: 23, timeout: 400 });                                    // Recht "scan": [{ip, port}]
await hub.net.scan({ port: 8080, path: '/api/state', contains: '"name"' });         // mit HTTP-Probe: [{ip, port, body}]
await hub.net.mdns('_http._tcp', { timeout: 3000 });                               // Recht "mdns": [{name, host, port, txt}]

// Startseite: eine Karte je Erweiterung
await hub.home.set({
  title: 'Blu-ray',
  subtitle: 'Film · Wohnzimmer',
  state: { text: 'An', tone: 'ok' },               // tone: ok | warn | bad | off
  image: 'http://192.168.0.20/cover.jpg',           // optional: Foto als Hintergrund der Karte
  value: { text: '45', label: 'Lautstärke' },      // optional: große Zahl
  actions: [
    { id: 'down', icon: 'minus', repeat: true },    // repeat: gedrückt halten wiederholt
    { id: 'up', icon: 'plus', repeat: true },
    { id: 'open', label: 'Öffnen', primary: true },
  ],
});
hub.on('home.action', ({ id }) => { /* Knopf auf der Karte */ });
await hub.home.clear();

// Oberfläche
await hub.ui.toast('Gespeichert');
const undo = await hub.ui.toast('Gelöscht', { action: 'Rückgängig' });   // true, wenn gedrückt
hub.ui.haptic('tap');                  // 'tap' | 'tick' | 'long'
hub.ui.open('https://example.com');    // im Browser öffnen
hub.ui.share('Text');
hub.nav.open();                        // eigene Seite nach vorn holen
hub.nav.store('denon');                // Store, optional mit Detailseite
```

Symbole für Kartenknöpfe: `power`, `plus`, `minus`, `mute`, `volume`, `play`, `pause`, `playpause`, `remote`, `input`,
`homekey`, `back2`, `check`, `refresh`.

### Ereignisse

| Ereignis | Daten | Wann |
| --- | --- | --- |
| `visibility` | `{visible}` | Die eigene Seite wird gezeigt oder verlassen. |
| `app` | `{state: 'pause' \| 'resume'}` | Die App geht in den Hintergrund oder kommt zurück. Verbindungen, die ein Gerät nur einmal erlaubt, hier freigeben. |
| `home.action` | `{id, repeat}` | Knopf auf der Startseiten-Karte. |
| `link` | `{url}` | Ein `skyline://`-Link aus dem Manifest wurde geöffnet (z. B. per QR-Code). |
| `back` | – | Zurück-Taste. `hub.onBack(fn)`: `fn` gibt `true` zurück, wenn die Erweiterung selbst etwas geschlossen hat; sonst geht die App zur Startseite. |

Alle Erweiterungen mit Leistenplatz oder Karte werden beim Start der App geladen und laufen im Hintergrund weiter,
damit ihre Karte aktuell bleibt. Abfragen deshalb sparsam takten und bei `app: pause` anhalten.

## Gestaltung

- `hub.css` bringt Farben (`--bg`, `--surface`, `--surface-2/3/4`, `--text`, `--muted`, `--accent`, `--ok`, …), Radien,
  Schatten und Bausteine (`h-btn`, `h-round`, `h-card`, `h-seg`, `h-switch`, `h-field`, `h-pill`). Damit wirkt die
  Erweiterung wie ein Teil der App.
- Keine eigene Leiste unten: die gehört der App. Unterbereiche als Reiter oben (`h-seg`).
- Ein Kopf mit dem Gerätenamen und dem Verbindungszustand als Pille, wie bei Beamer und Denon.
- Jeder Zustand braucht eine Antwort: Suche läuft, nichts gefunden, Gerät antwortet nicht, Demo.

## Entwickeln

1. Ordner mit `manifest.json` anlegen und im WLAN ausliefern, z. B. `python -m http.server 5500` im Ordner.
2. In der App: Start › Einstellungen › Entwicklermodus an › Adresse des Entwicklungsservers, z. B. `http://192.168.0.10:5500/` › Laden.
3. Die Erweiterung läuft direkt von deinem Rechner; nach Änderungen die App neu öffnen oder erneut „Laden“.
4. Fehlersuche: Handy per USB-Debugging verbinden, am PC `chrome://inspect` öffnen. Die App und jede Erweiterung erscheinen dort als eigene Seite.
5. Als Datei weitergeben: den Ordnerinhalt als ZIP packen (manifest.json im Wurzelordner oder in genau einem Unterordner)
   und in der App über „ZIP-Datei installieren“ einspielen.

## In den Store bringen

Der Store liest den Katalog `extensions.json` aus dem jeweils neuesten GitHub-Release dieses Repositorys.
Neue Erweiterung: Ordner unter `extensions/<id>/` anlegen und per Pull Request einreichen. Beim Release packt
`tools/pack_ext.py` jede Erweiterung als `ext-<id>.zip` (mit Prüfsumme) und legt sie zusätzlich in die APK, damit
der Store auch ohne Internet funktioniert. Eine höhere `version` im Manifest erscheint in der App als Update.
Eigene Kataloge (eigenes Repository) lassen sich im Entwicklermodus unter „Adresse des Katalogs“ eintragen; das
Format entspricht `dist/extensions.json` nach einem Lauf von `tools/pack_ext.py`.
