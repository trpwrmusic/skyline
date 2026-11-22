'use strict';

// ---------- Zustand ----------
const S = {};              // Schlüssel wie im Server, z. B. PW, MV, CV:FL, PS:DYNEQ, PS:BAS:n
let conn = { status: 'offline', host: '', demo: false };
let view = (location.hash || '#jetzt').slice(1);
let selSpk = null;
let dragging = false;
let lastSig = '';
let confirmReset = false;

const $ = s => document.querySelector(s);
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const de = (n, d = 1) => n.toLocaleString('de-DE', { minimumFractionDigits: d % 1 || n % 1 ? 1 : 0, maximumFractionDigits: 1 });
const signed = n => (n > 0 ? '+' : n < 0 ? '−' : '') + de(Math.abs(n)) ;

// Denon-Kodierung für Pegel: 50 = "50", 50,5 = "505"
const encVol = v => { const r = Math.round(v * 2) / 2; return r % 1 ? String(Math.floor(r)).padStart(2, '0') + '5' : String(r).padStart(2, '0'); };
const decVol = s => (s && s.length === 3 ? Number(s.slice(0, 2)) + 0.5 : Number(s));
const pad = (n, w) => String(Math.round(n)).padStart(w, '0');

function keyAndValue(line) {
  if (line.startsWith('CV')) {
    if (line === 'CVEND') return null;
    const [ch, v] = line.slice(2).split(' ');
    return ['CV:' + ch, v];
  }
  if (line.startsWith('MVMAX')) return ['MVMAX', line.slice(5).trim()];
  if (line.startsWith('PS')) {
    const body = line.slice(2);
    const m = body.match(/^(TONE CTRL|CINEMA EQ\.|MULTEQ:|MODE:|SP:|FH:|[A-Z0-9]+)/);
    if (!m) return null;
    const name = m[1].replace(/[:.]$/, '');
    const rest = body.slice(m[1].length).trim();
    return ['PS:' + name + (/^\d+$/.test(rest) ? ':n' : ''), rest];
  }
  if (line.startsWith('MNMEN')) return ['MNMEN', line.slice(5).trim()];
  if (line.startsWith('ECO')) return ['ECO', line.slice(3)];
  if (/^(Z2|Z3|NS)/.test(line)) return null;
  return [line.slice(0, 2), line.slice(2).trim()];
}

// ---------- Verbindung zum lokalen Server ----------
function cmd(c) { Conn.enqueue(c); }
const online = () => conn.status === 'connected' || conn.status === 'demo';

function startEvents() {
  Conn.onStatus(next => {
    if (next.status !== conn.status) for (const k in S) delete S[k];
    conn = next;
    paintConn();
    schedule();
  });
  Conn.onLine(line => {
    const kv = keyAndValue(line);
    if (!kv) return;
    S[kv[0]] = kv[1];
    schedule();
  });
}

let raf = 0;
function schedule() { if (!raf) raf = requestAnimationFrame(() => { raf = 0; render(); }); }

// ---------- Daten ----------
const I = {
  sat: '<rect x="3" y="6" width="18" height="11" rx="2"/><path d="M8 20h8M9 3l3 3 3-3"/>',
  disc: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/>',
  game: '<path d="M7 8h10a4 4 0 0 1 4 4v1a3 3 0 0 1-5.5 1.7L14.5 14h-5l-1 .7A3 3 0 0 1 3 13v-1a4 4 0 0 1 4-4z"/><path d="M8 10.5v3M6.5 12h3"/><circle cx="16" cy="11" r=".6"/><circle cx="17.5" cy="13" r=".6"/>',
  aux: '<path d="M4 12h9M13 8v8M13 9h4a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2h-4"/>',
  play: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10 9.5v5l4.5-2.5z"/>',
  tv: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
  cd: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4"/><circle cx="12" cy="12" r=".6"/>',
  phono: '<circle cx="11" cy="13" r="7"/><circle cx="11" cy="13" r="1.5"/><path d="M19 3v8l-3 3"/>',
  radio: '<rect x="3" y="8" width="18" height="12" rx="2"/><path d="M7 8l9-5"/><circle cx="15" cy="14" r="2.5"/><path d="M6 12h4M6 15h4"/>',
  net: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.8 3 2.8 15 0 18M12 3c-2.8 3-2.8 15 0 18"/>',
  bt: '<path d="M7 7l10 10-5 4V3l5 4L7 17"/>',
  usb: '<path d="M12 3v14M9 6l3-3 3 3M7 10v3l5 3M17 9v3l-5 3"/><circle cx="12" cy="19" r="2"/>',
};
const INPUTS = [
  ['SAT/CBL', 'Kabel/Sat', 'sat'], ['BD', 'Blu-ray', 'disc'], ['DVD', 'DVD', 'disc'], ['GAME', 'Spiel', 'game'],
  ['MPLAY', 'Media Player', 'play'], ['TV', 'TV-Ton', 'tv'], ['AUX1', 'AUX', 'aux'], ['CD', 'CD', 'cd'],
  ['PHONO', 'Phono', 'phono'], ['TUNER', 'Radio', 'radio'], ['NET', 'Online-Musik', 'net'], ['BT', 'Bluetooth', 'bt'],
  ['USB/IPOD', 'USB/iPod', 'usb'],
];
const MODES = [['AUTO', 'Automatisch'], ['MOVIE', 'Film'], ['MUSIC', 'Musik'], ['GAME', 'Spiel'], ['STEREO', 'Stereo'], ['DIRECT', 'Direct'], ['PURE DIRECT', 'Pure Direct']];

const SPEAKERS = {
  FL: ['Front L', 25, 19], C: ['Center', 50, 16], FR: ['Front R', 75, 19],
  SW: ['Subwoofer', 10, 34], SW2: ['Subwoofer 2', 90, 34],
  FHL: ['Höhe L', 33, 6, 1], FHR: ['Höhe R', 67, 6, 1],
  FWL: ['Breite L', 9, 49, 1], FWR: ['Breite R', 91, 49, 1],
  SL: ['Surround L', 11, 68], SR: ['Surround R', 89, 68],
  SBL: ['Hinten L', 36, 90], SB: ['Hinten', 50, 90], SBR: ['Hinten R', 64, 90],
};

const inputName = si => (INPUTS.find(i => i[0] === si) || [si, si])[1];
const modeName = ms => {
  const m = MODES.find(x => x[0] === ms);
  if (m) return m[1];
  return ms ? ms.toLowerCase().replace(/\b(dolby|dts|hd|mstr|pl2x?|pl2z|ch|neo|ds|ex|mch|dsd|aac|mpeg2)\b/g, w => w.toUpperCase()).replace(/\b\w/g, c => c.toUpperCase()) : '';
};

// ---------- Kopf und Verbindung ----------
const TITLES = { jetzt: 'Jetzt', einrichtung: 'Einrichtung', lautsprecher: 'Lautsprecher', klang: 'Klang' };

function paintConn() {
  const map = {
    connected: ['ok', 'Verbunden'],
    demo: ['demo', 'Demo'],
    connecting: ['wait', 'Verbinde'],
    offline: ['bad', conn.detail === 'pausiert' ? 'Pausiert' : 'Getrennt'],
    setup: ['', 'Einrichten'],
  };
  const [cls, t] = map[conn.status] || map.offline;
  $('#connMobile .dot').className = 'dot ' + cls;
  $('#connMobileText').textContent = t;
  $('#connTitle').textContent = conn.demo ? 'Demo-Receiver' : 'Receiver';
  $('#title').textContent = conn.name || (conn.demo ? 'AVR-X2100W' : conn.host ? 'Denon-Receiver' : 'Denon');
  publishSoon();
}

function paintTop() {
  const p = $('#power');
  p.classList.toggle('on', S.PW === 'ON');
  p.hidden = !(online() && S.PW);
  document.querySelectorAll('.nav-items a').forEach(a => a.classList.toggle('on', a.dataset.view === view));
}

function offlineCard() {
  if (conn.status === 'setup') {
    return `<div class="card empty">
      <h2>Receiver verbinden</h2>
      <p>Die App spricht direkt mit Denon- und Marantz-Receivern im WLAN. Am Receiver muss dafür unter Netzwerk die Netzwerksteuerung an sein.</p>
      <div class="step-actions" style="margin-top:6px">
        <button class="btn primary" data-act="settings">Im WLAN suchen</button>
        <button class="btn ghost" data-act="demo">Demo ansehen</button>
      </div>
    </div>`;
  }
  if (conn.status === 'connecting') {
    return `<div class="card empty"><h2>Verbinde mit ${esc(conn.name || conn.host)}</h2><p>Einen Moment.</p></div>`;
  }
  return `<div class="card empty">
    <h2>Receiver nicht erreichbar</h2>
    <p>Gesucht wird unter ${esc(conn.host)}. Die App versucht es alle fünf Sekunden erneut.</p>
    <ul>
      <li>Er steht im Standby und IP Control ist nicht auf „Always On“. Dann einmal am Gerät einschalten.</li>
      <li>Eine andere App, etwa Denon AVR Remote, hält gerade die Verbindung. Der Receiver erlaubt nur eine.</li>
      <li>Er hat eine neue IP-Adresse. Dann im WLAN neu suchen.</li>
    </ul>
    <div class="step-actions" style="margin-top:6px">
      <button class="btn primary" data-act="retry">Erneut versuchen</button>
      <button class="btn" data-act="settings">Im WLAN suchen</button>
      <button class="btn ghost" data-act="demo">Demo ansehen</button>
    </div>
  </div>`;
}

// ---------- Ansichten ----------
function vJetzt() {
  if (!online() || !S.PW) return offlineCard();
  if (S.PW !== 'ON') {
    return `<section class="hero off"><div class="now"><small>Receiver</small><h2>Standby</h2></div>
      <div><button class="btn primary" data-cmd="PWON">Einschalten</button></div></section>`;
  }
  const tiles = INPUTS.map(([k, l, ic]) =>
    `<button class="tile" data-si="${esc(k)}" data-cmd="SI${esc(k)}"><svg viewBox="0 0 24 24">${I[ic]}</svg>${esc(l)}</button>`).join('');
  return `
  <section class="hero">
    <div class="now">
      <small>Läuft gerade</small>
      <h2 data-bind="input"></h2>
      <span class="mode" data-bind="mode"></span>
    </div>
    <div class="vol">
      <div class="vol-num"><b data-bind="vol"></b><span data-bind="voldb"></span></div>
      <div class="vol-ctl">
        <button class="round" data-cmd="MVDOWN" aria-label="Leiser">−</button>
        <input type="range" id="volSlider" min="0" max="80" step="0.5" aria-label="Lautstärke">
        <button class="round" data-cmd="MVUP" aria-label="Lauter">+</button>
        <button class="round" id="mute" aria-label="Stumm"><svg viewBox="0 0 24 24"><path d="M4 9v6h4l5 4V5L8 9z"/><path d="M17 9l4 6M21 9l-4 6"/></svg></button>
      </div>
    </div>
  </section>
  <h2 class="section-title">Eingang</h2>
  <div class="tiles">${tiles}</div>
  <h2 class="section-title">Klangmodus</h2>
  <div class="seg" data-seg="MS">${MODES.map(([k, l]) => `<button data-v="${esc(k)}" data-cmd="MS${esc(k)}">${esc(l)}</button>`).join('')}</div>`;
}

const STEPS = [
  {
    id: 'ip', title: 'Netzwerk-Steuerung dauerhaft an', sub: 'Damit diese Seite den Receiver auch aus dem Standby einschaltet.',
    path: ['Netzwerk', 'IP Control', 'Always On'],
    list: ['Menü öffnen und Netzwerk wählen.', 'IP Control auf „Always On“ stellen. Je nach Menüsprache heißt es IP-Steuerung und Immer ein.'],
  },
  {
    id: 'audyssey', title: 'Lautsprecher einmessen', sub: 'Audyssey misst Abstände, Pegel und Trennfrequenzen mit dem Mikrofon.',
    path: ['Lautsprecher', 'Audyssey Setup'],
    list: [
      'Das mitgelieferte Mikrofon vorn in die Buchse SETUP MIC stecken.',
      'Mikrofon auf Ohrhöhe an den Hauptplatz stellen, am besten auf ein Stativ statt auf ein weiches Polster.',
      'Am Subwoofer die Lautstärke auf die Mitte drehen und den Frequenzregler ganz aufdrehen.',
      'Während der Messung bleibt es im Raum still. Der Receiver führt durch die Positionen.',
    ],
    extra: '<button class="btn" data-go="lautsprecher">Pegel danach prüfen</button>',
  },
  {
    id: 'inputs', title: 'Eingänge zuordnen', sub: 'Welches Gerät an welcher HDMI-Buchse hängt.',
    path: ['Eingänge', 'Eingangszuordnung'],
    list: ['Für jede Quelle die belegte HDMI-Buchse wählen.', 'Unbenutzte Quellen unter Eingänge → Quellen ausblenden verstecken.'],
  },
  {
    id: 'arc', title: 'Fernseher-Ton über HDMI', sub: 'Der Ton vom Fernseher läuft über den Receiver, eine Fernbedienung reicht.',
    path: ['Video', 'HDMI-Setup', 'HDMI-Steuerung: Ein'],
    list: [
      'HDMI-Steuerung einschalten, darunter ARC ebenfalls einschalten.',
      'Am Fernseher HDMI-CEC aktivieren (Samsung Anynet+, LG SimpLink, Sony Bravia Sync).',
      'Das HDMI-Kabel am Fernseher in die Buchse mit der Aufschrift ARC stecken.',
    ],
  },
  {
    id: 'sound', title: 'Klang abstimmen', sub: 'Gute Grundwerte für Filme bei Zimmerlautstärke.',
    list: ['MultEQ auf Referenz, Dynamic EQ an, Referenzpegel 0 dB.', 'Klingt es zu basslastig, den Referenzpegel auf 5 oder 10 dB stellen.'],
    noMenu: true,
    extra: '<button class="btn primary" data-act="soundpreset">Diese Werte setzen</button><button class="btn" data-go="klang">Zum Klang</button>',
  },
];

const done = (() => { try { return JSON.parse(localStorage.getItem('denon-steps') || '{}'); } catch { return {}; } })();
const saveDone = () => { try { localStorage.setItem('denon-steps', JSON.stringify(done)); } catch {} };
let openStep = STEPS.find(s => !done[s.id])?.id || STEPS[0].id;

function vEinrichtung() {
  const steps = STEPS.map((s, i) => `
    <article class="card step ${done[s.id] ? 'done' : ''} ${openStep === s.id ? 'open' : ''}" data-step="${s.id}">
      <button class="step-head" data-toggle-step="${s.id}">
        <span class="step-num">${done[s.id] ? '<svg viewBox="0 0 24 24" style="width:18px;height:18px"><path d="M5 12.5l4 4L19 7"/></svg>' : i + 1}</span>
        <span><h3>${esc(s.title)}</h3><p>${esc(s.sub)}</p></span>
        <svg class="chev" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>
      </button>
      <div class="step-body">
        ${s.path ? `<div class="path">${s.path.map(p => `<span>${esc(p)}</span>`).join('<i>›</i>')}</div>` : ''}
        <ol>${s.list.map(l => `<li>${esc(l)}</li>`).join('')}</ol>
        <div class="step-actions">
          ${s.noMenu ? '' : '<button class="btn primary" data-cmd="MNMEN ON">Menü am Fernseher öffnen</button>'}
          ${s.extra || ''}
          <button class="btn ghost" data-done="${s.id}">${done[s.id] ? 'Wieder offen' : 'Erledigt'}</button>
        </div>
      </div>
    </article>`).join('');

  return `
  ${online() && S.PW ? '' : offlineCard() + '<div style="height:18px"></div>'}
  <div class="setup">
    <div class="steps">${steps}</div>
    <aside class="card remote">
      <h2>Fernseher-Menü</h2>
      <p class="state small muted" data-bind="menu"></p>
      <div class="dpad">
        <button class="u" data-cmd="MNCUP" aria-label="Hoch"><svg viewBox="0 0 24 24"><path d="M6 15l6-6 6 6"/></svg></button>
        <button class="l" data-cmd="MNCLT" aria-label="Links"><svg viewBox="0 0 24 24"><path d="M15 6l-6 6 6 6"/></svg></button>
        <button class="ok" data-cmd="MNENT">OK</button>
        <button class="r" data-cmd="MNCRT" aria-label="Rechts"><svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg></button>
        <button class="d" data-cmd="MNCDN" aria-label="Runter"><svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>
      </div>
      <div class="remote-row">
        <button class="btn" data-cmd="MNRTN">Zurück</button>
        <button class="btn" data-cmd="MNOPT">Option</button>
      </div>
      <div class="remote-row"><button class="btn" id="menuToggle"></button></div>
      <p class="small muted" style="margin:0;text-align:center">Pfeiltasten, <span class="kbd">Enter</span> und <span class="kbd">⌫</span> gehen auch.</p>
    </aside>
  </div>`;
}

// ---------- Aufstellung (Drag & Drop) ----------
let spkMode = (() => { try { return localStorage.getItem('denon-spkmode') || 'pegel'; } catch { return 'pegel'; } })();
let layout = { w: 4.5, l: 5, seat: null, spk: {} };
let dragKey = null;
let selPlace = null;
const DEFAULT_SET = ['FL', 'C', 'FR', 'SW', 'SL', 'SR'];
// empfohlene Winkel vom Hörplatz aus (0° = geradeaus zum Bild), Dolby 5.1
const ANGLES = { FL: [22, 30], FR: [22, 30], C: [0, 5], SL: [90, 110], SR: [90, 110], SBL: [135, 150], SBR: [135, 150], SB: [170, 180] };

try { const d = JSON.parse(localStorage.getItem('denon-layout') || 'null'); if (d && d.w) layout = { ...layout, ...d }; } catch {}
let saveTimer = 0;

// gemeldete Kanäle plus bereits aufgestellte; ein vorübergehend nicht gemeldeter Lautsprecher verschwindet so nicht
const placeKeys = () => {
  const keys = Object.keys(SPEAKERS).filter(k => S['CV:' + k] !== undefined || layout.spk[k]?.x !== undefined);
  return keys.length ? keys : DEFAULT_SET;
};
// Höhe = Messpunkt über dem Boden: Hochtöner (Standbox), Gehäusemitte (Regal, Center, Subwoofer)
const EAR = 1.1;
const DEFAULT_KIND = { FL: 'tower', FR: 'tower', C: 'center', SW: 'sub', SW2: 'sub' };
const KIND_NAME = { tower: 'Standbox', shelf: 'Regal oder Wand', center: 'Center', sub: 'Subwoofer' };
const kindOf = k => layout.spk[k]?.kind || DEFAULT_KIND[k] || 'shelf';
const defH = (k, kind = kindOf(k)) => ({ tower: 0.9, sub: 0.19, center: 0.45 }[kind] ?? (k[0] === 'F' ? 1.0 : 1.2));
const H_MIN = 0.15, H_MAX = 2.6;
const clampH = h => Math.round(Math.max(H_MIN, Math.min(H_MAX, h)) * 20) / 20;

function ensureLayout(keys) {
  if (!layout.seat) layout.seat = { x: layout.w / 2, y: layout.l * 0.62 };
  // Umstellung auf Höhe = Messpunkt für jeden Lautsprecher (ältere Stände hatten sie nur bei Regalboxen)
  if (layout.v !== 2) {
    for (const k in layout.spk) {
      const kind = kindOf(k);
      if (kind === 'tower' || kind === 'sub' || layout.spk[k].h === undefined) layout.spk[k].h = defH(k);
    }
    layout.v = 2;
  }
  for (const k of keys) {
    if (!layout.spk[k] || layout.spk[k].x === undefined) {
      const [, px, py] = SPEAKERS[k];
      // Subwoofer startet vorn rechts neben Front R, damit sich die Schilder nicht überdecken
      const [x, y] = k === 'SW' ? [90, 10] : k === 'SW2' ? [10, 10] : [px, Math.max(py, 8)];
      layout.spk[k] = { ...layout.spk[k], x: +(x / 100 * layout.w).toFixed(2), y: +(y / 100 * layout.l).toFixed(2) };
    }
    if (layout.spk[k].h === undefined) layout.spk[k].h = defH(k);
  }
}
const distOf = k => Math.hypot(layout.spk[k].x - layout.seat.x, layout.spk[k].y - layout.seat.y, layout.spk[k].h - EAR);
const angleOf = k => Math.round(Math.atan2(layout.spk[k].x - layout.seat.x, layout.seat.y - layout.spk[k].y) * 180 / Math.PI);
const m1 = v => v.toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' m';
const m2 = v => v.toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' m';
const angleState = k => {
  const r = ANGLES[k];
  if (!r) return null;
  const a = Math.abs(angleOf(k));
  return { a, r, ok: a >= r[0] - 2 && a <= r[1] + 2 };
};

// ---------- Verlauf und Speichern ----------
const undoStack = [];
let saveState = 'idle'; // idle | saving | saved | error
let savedTimer = 0;
function pushHistory() {
  undoStack.push(JSON.stringify(layout));
  if (undoStack.length > 60) undoStack.shift();
}
function undo() {
  if (!undoStack.length) return;
  layout = JSON.parse(undoStack.pop());
  saveLayout();
  window.Room3D?.rebuild();
  lastSig = '';
  schedule();
}
function saveLayout() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    saveState = 'saving'; paintPanel();
    try {
      localStorage.setItem('denon-layout', JSON.stringify(layout));
      saveState = 'saved';
      clearTimeout(savedTimer);
      savedTimer = setTimeout(() => { saveState = 'idle'; paintPanel(); }, 2200);
    } catch { saveState = 'error'; }
    paintPanel();
  }, 350);
}

const room3dApi = {
  get: () => ({
    layout, keys: placeKeys(), selected: selPlace,
    labels: Object.fromEntries(Object.keys(SPEAKERS).map(k => [k, SPEAKERS[k][0]])),
    dist: k => m1(distOf(k)),
    heightText: k => m2(layout.spk[k].h),
  }),
  kindOf,
  begin: pushHistory,
  select: k => { if (k !== 'seat') selPlace = k; roomOpen = false; paintPanel(); window.Room3D.sync(); },
  move: (k, x, z) => { if (k === 'seat') layout.seat = { x, y: z }; else layout.spk[k] = { ...layout.spk[k], x, y: z }; paintPanel(); },
  setHeight: (k, h) => { layout.spk[k].h = clampH(h); paintPanel(); },
  done: moved => { if (moved) saveLayout(); paintPanel(); },
};
window.addEventListener('room3d-ready', () => { lastSig = ''; schedule(); });

let roomOpen = false;

function vAufstellung() {
  ensureLayout(placeKeys());
  return `
  <div class="planner">
    <div class="stage">
      <div class="room3d" id="room3d"></div>
      <div class="stage-bar top-left">
        <button class="chip" data-roomtoggle><svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 9h3M17 4v3"/></svg><span id="roomLabel"></span></button>
        <div class="popover" id="roomPop" hidden></div>
      </div>
      <div class="stage-bar top-right">
        <div class="seg compact" id="viewSeg">
          <button data-r3view="persp">3D</button><button data-r3view="top">Von oben</button>
        </div>
      </div>
      <div class="stage-bar bottom-left" id="stageStatus"></div>
      <div class="inspector" id="inspector" hidden></div>
    </div>
    <aside class="card dist-card" id="bpanel"></aside>
  </div>`;
}

function inspectorHtml() {
  const k = selPlace;
  if (!k || !layout.spk[k]) return '';
  const kind = kindOf(k);
  const an = angleState(k);
  const kindSeg = kind === 'tower' || kind === 'shelf'
    ? `<div class="seg compact full">
         <button class="${kind === 'tower' ? 'on' : ''}" data-kind="tower">Standbox</button>
         <button class="${kind === 'shelf' ? 'on' : ''}" data-kind="shelf">Regal oder Wand</button></div>`
    : '';
  const hLabel = kind === 'tower' ? 'Hochtöner' : 'Höhe';
  return `
    <div class="insp-head">
      <div><h3>${esc(SPEAKERS[k][0])}</h3><p>${m1(distOf(k))} zum Hörplatz</p></div>
      <button class="icon-btn" data-deselect aria-label="Auswahl schließen"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>
    </div>
    ${kindSeg}
    <div class="insp-row">
      <span>${hLabel}</span>
      <div class="stepper"><button data-height="-0.05" aria-label="Tiefer">−</button><output>${m2(layout.spk[k].h)}</output><button data-height="0.05" aria-label="Höher">+</button></div>
    </div>
    ${an ? `<p class="insp-angle ${an.ok ? 'ok' : ''}">${an.ok ? '<svg viewBox="0 0 24 24"><path d="M5 12.5l4 4L19 7"/></svg>' : ''}Winkel ${an.a}°, empfohlen ${an.r[0] === an.r[1] ? an.r[0] : an.r[0] + '–' + an.r[1]}°</p>` : ''}`;
}

function panelHtml() {
  const rows = placeKeys().map(k => `
    <button class="dist-row ${selPlace === k ? 'on' : ''}" data-place="${k}">
      <span>${esc(SPEAKERS[k][0])}</span><b>${m1(distOf(k))}</b>
    </button>`).join('');
  return `
    <h2>Abstände</h2>
    <p class="dist-sub">Vom Lautsprecher zum Kopf am Hörplatz</p>
    <div class="dist-list">${rows}</div>
    <button class="btn primary wide" data-cmd="MNMEN ON">Im Denon eintragen</button>
    <p class="dist-sub center">Lautsprecher › Manuelles Setup › Abstände</p>`;
}

function statusHtml() {
  const undoBtn = `<button class="chip" data-undo ${undoStack.length ? '' : 'disabled'}><svg viewBox="0 0 24 24"><path d="M9 7L4 12l5 5"/><path d="M4 12h11a5 5 0 0 1 0 10h-2"/></svg>Rückgängig</button>`;
  const s = saveState === 'saving' ? '<span class="save">Speichert</span>'
    : saveState === 'saved' ? '<span class="save ok"><svg viewBox="0 0 24 24"><path d="M5 12.5l4 4L19 7"/></svg>Gespeichert</span>'
    : saveState === 'error' ? '<span class="save bad">Nicht gespeichert, der Speicher ist voll.</span><button class="chip" data-saveretry>Erneut speichern</button>'
    : '';
  return undoBtn + s;
}

function roomPopHtml() {
  return `
    <div class="insp-row"><span>Breite</span>
      <div class="stepper"><button data-room="w:-0.5">−</button><output>${m1(layout.w)}</output><button data-room="w:0.5">+</button></div></div>
    <div class="insp-row"><span>Tiefe</span>
      <div class="stepper"><button data-room="l:-0.5">−</button><output>${m1(layout.l)}</output><button data-room="l:0.5">+</button></div></div>
    <p class="dist-sub">Breite ist die Wand mit der Leinwand.</p>`;
}

const setHtml = (el, html) => { if (el && el.dataset.html !== html) { el.innerHTML = html; el.dataset.html = html; } };
function paintPanel() {
  if (!$('#bpanel')) return;
  setHtml($('#bpanel'), panelHtml());
  const insp = $('#inspector');
  const ih = inspectorHtml();
  insp.hidden = !ih;
  setHtml(insp, ih);
  setHtml($('#stageStatus'), statusHtml());
  $('#roomLabel').textContent = `Raum ${m1(layout.w).replace(' m', '')} × ${m1(layout.l)}`;
  const pop = $('#roomPop');
  pop.hidden = !roomOpen;
  if (roomOpen) setHtml(pop, roomPopHtml());
  const vk = window.Room3D?.viewKind?.() || 'persp';
  document.querySelectorAll('#viewSeg button').forEach(b => b.classList.toggle('on', b.dataset.r3view === vk));
}
function paintRoom() {
  const el = $('#room3d');
  if (!el || !window.Room3D) return;
  if (!window.Room3D.mounted(el)) window.Room3D.mount(el, room3dApi);
  else window.Room3D.sync();
}

function vLautsprecher() {
  const tabs = `<div class="seg mode-tabs">
    <button class="${spkMode === 'pegel' ? 'on' : ''}" data-spkmode="pegel">Pegel</button>
    <button class="${spkMode === 'aufstellung' ? 'on' : ''}" data-spkmode="aufstellung">Aufstellung</button></div>`;
  if (spkMode === 'aufstellung') return tabs + vAufstellung();
  return tabs + vPegel();
}

function vPegel() {
  if (!online() || !S.PW) return offlineCard();
  const present = Object.keys(SPEAKERS).filter(k => S['CV:' + k] !== undefined);
  if (!present.length) {
    return `<div class="card empty"><h2>Keine Lautsprecher gemeldet</h2>
      <p>Der Receiver meldet seine Lautsprecher nur, wenn er eingeschaltet ist und eine Quelle läuft.</p>
      <button class="btn primary" data-act="refresh">Neu abfragen</button></div>`;
  }
  if (!present.includes(selSpk)) selSpk = present[0];
  const nodes = present.map(k => {
    const [l, x, y, small] = SPEAKERS[k];
    return `<button class="spk ${small ? 'small' : ''}" data-spk="${k}" style="left:${x}%;top:${y}%"><b>${esc(l)}</b><span data-bind="cv:${k}"></span></button>`;
  }).join('');
  return `
  <div class="grid two">
    <div class="room"><div class="tv"></div><div class="seat"></div>${nodes}</div>
    <div class="card level-panel">
      <div class="level-big"><h2 style="margin:0" data-bind="spkname"></h2><b data-bind="spkdb"></b></div>
      <input type="range" id="cvSlider" min="38" max="62" step="0.5" aria-label="Pegel">
      <div class="row">
        <span class="muted small">Schritte von 0,5 dB, Bereich ±12 dB</span>
        <div class="stepper"><button data-cvstep="-0.5">−</button><output data-bind="spkdb2"></output><button data-cvstep="0.5">+</button></div>
      </div>
      <div class="row" style="margin-top:auto">
        <span class="muted small">Die Anordnung meldet der Receiver aus seiner Lautsprecher-Konfiguration.</span>
        <button class="btn ${confirmReset ? 'primary' : ''}" data-act="cvreset">${confirmReset ? 'Wirklich alle auf 0 dB?' : 'Alle auf 0 dB'}</button>
      </div>
    </div>
  </div>`;
}

// Einstell-Zeilen nur zeigen, wenn der Receiver die Einstellung gemeldet hat
const has = k => S[k] !== undefined;
const seg = (key, opts, cmdPrefix) =>
  `<div class="seg" data-seg="${key}">${opts.map(([v, l]) => `<button data-v="${esc(v)}" data-cmd="${esc(cmdPrefix + v)}">${esc(l)}</button>`).join('')}</div>`;
const sw = (key, onCmd, offCmd) => `<button class="switch" role="switch" data-sw="${key}" data-on-cmd="${esc(onCmd)}" data-off-cmd="${esc(offCmd)}"></button>`;
const stepper = (key, o) =>
  `<div class="stepper" data-stepper="${key}" data-min="${o.min}" data-max="${o.max}" data-step="${o.step}" data-fmt="${o.fmt}" data-cmd="${esc(o.cmd)}" data-enc="${o.enc}">
    <button data-d="-1">−</button><output></output><button data-d="1">+</button></div>`;
const row = (show, label, small, ctl, stack) => show ? `<div class="row ${stack ? 'stack' : ''}"><span class="label"><b>${label}</b>${small ? `<small>${small}</small>` : ''}</span>${ctl}</div>` : '';

function vKlang() {
  if (!online() || !S.PW) return offlineCard();
  const audyssey = [
    row(has('PS:MULTEQ'), 'MultEQ', '', seg('PS:MULTEQ', [['AUDYSSEY', 'Referenz'], ['BYP.LR', 'L/R Bypass'], ['FLAT', 'Flat'], ['OFF', 'Aus']], 'PSMULTEQ:'), true),
    row(has('PS:DYNEQ'), 'Dynamic EQ', 'Hält Bass und Raumklang bei leiser Lautstärke voll', sw('PS:DYNEQ', 'PSDYNEQ ON', 'PSDYNEQ OFF')),
    row(has('PS:REFLEV:n'), 'Referenzpegel', 'Wirkt nur mit Dynamic EQ', seg('PS:REFLEV:n', [['0', '0 dB'], ['5', '5 dB'], ['10', '10 dB'], ['15', '15 dB']], 'PSREFLEV '), true),
    row(has('PS:DYNVOL'), 'Dynamic Volume', 'Gleicht laute und leise Stellen an', seg('PS:DYNVOL', [['OFF', 'Aus'], ['LIT', 'Leicht'], ['MED', 'Mittel'], ['HEV', 'Stark']], 'PSDYNVOL '), true),
  ].join('');
  const tone = [
    row(has('PS:TONE CTRL'), 'Klangregelung', 'Bass und Höhen gehen nur ohne Dynamic EQ', sw('PS:TONE CTRL', 'PSTONE CTRL ON', 'PSTONE CTRL OFF')),
    row(has('PS:BAS:n'), 'Bass', '', stepper('PS:BAS:n', { min: 44, max: 56, step: 1, fmt: 'db50', cmd: 'PSBAS ', enc: 'int2' })),
    row(has('PS:TRE:n'), 'Höhen', '', stepper('PS:TRE:n', { min: 44, max: 56, step: 1, fmt: 'db50', cmd: 'PSTRE ', enc: 'int2' })),
    row(has('PS:SWL'), 'Subwoofer-Anpassung', 'Zusätzlich zum eingemessenen Pegel', sw('PS:SWL', 'PSSWL ON', 'PSSWL OFF')),
    row(has('PS:SWL:n'), 'Subwoofer-Pegel', '', stepper('PS:SWL:n', { min: 38, max: 62, step: 0.5, fmt: 'db50', cmd: 'PSSWL ', enc: 'vol' })),
    row(has('PS:LFE:n'), 'LFE-Pegel', 'Tieftonkanal von Filmen', stepper('PS:LFE:n', { min: 0, max: 10, step: 1, fmt: 'dbneg', cmd: 'PSLFE ', enc: 'int2' })),
    row(has('PS:SWR'), 'Subwoofer bei Direct und Stereo', '', sw('PS:SWR', 'PSSWR ON', 'PSSWR OFF')),
  ].join('');
  const more = [
    row(has('PS:CINEMA EQ'), 'Cinema EQ', 'Dämpft schrille Höhen in Kinomischungen', sw('PS:CINEMA EQ', 'PSCINEMA EQ.ON', 'PSCINEMA EQ.OFF')),
    row(has('PS:DIL'), 'Dialog anheben', '', sw('PS:DIL', 'PSDIL ON', 'PSDIL OFF')),
    row(has('PS:DIL:n'), 'Dialog-Pegel', '', stepper('PS:DIL:n', { min: 38, max: 62, step: 0.5, fmt: 'db50', cmd: 'PSDIL ', enc: 'vol' })),
    row(has('PS:DELAY:n'), 'Audio-Verzögerung', 'Wenn der Ton dem Bild vorausläuft', stepper('PS:DELAY:n', { min: 0, max: 200, step: 5, fmt: 'ms', cmd: 'PSDELAY ', enc: 'int3' })),
    row(has('PS:DRC'), 'Dynamikkompression', 'Für Dolby TrueHD und DTS', seg('PS:DRC', [['AUTO', 'Auto'], ['LOW', 'Niedrig'], ['MID', 'Mittel'], ['HI', 'Hoch'], ['OFF', 'Aus']], 'PSDRC '), true),
    row(has('PS:RSTR'), 'Restorer', 'Für komprimierte Musik wie MP3', seg('PS:RSTR', [['OFF', 'Aus'], ['LOW', 'Leicht'], ['MED', 'Mittel'], ['HI', 'Stark']], 'PSRSTR '), true),
    row(has('ECO'), 'Eco-Modus', '', seg('ECO', [['ON', 'Ein'], ['AUTO', 'Auto'], ['OFF', 'Aus']], 'ECO'), true),
  ].join('');
  const card = (t, body) => body ? `<section class="card"><h2>${t}</h2><div class="rows">${body}</div></section>` : '';
  const all = card('Audyssey', audyssey) + card('Bass und Höhen', tone) + card('Wiedergabe', more);
  return all ? `<div class="grid two">${all}</div>` : `<div class="card empty"><h2>Noch keine Werte</h2><p>Der Receiver hat noch nichts gemeldet.</p><button class="btn primary" data-act="refresh">Neu abfragen</button></div>`;
}

// ---------- Zeichnen ----------
function render() {
  paintTop();
  const builder = view === 'lautsprecher' && spkMode === 'aufstellung';
  // In der 3D-Aufstellung bleibt die Szene stehen; nur Raumgröße und Lautsprecherliste bauen neu auf
  const sig = builder
    ? [view, spkMode, placeKeys().join(','), layout.w, layout.l, !!window.Room3D].join('|')
    : [view, conn.status, S.PW, Object.keys(S).sort().join(','), openStep, JSON.stringify(done), selSpk, confirmReset, spkMode].join('|');
  if (sig !== lastSig && !dragging) {
    lastSig = sig;
    const fn = { jetzt: vJetzt, einrichtung: vEinrichtung, lautsprecher: vLautsprecher, klang: vKlang }[view] || vJetzt;
    if (window.Room3D) window.Room3D.unmount();
    $('#view').innerHTML = fn();
  }
  if (builder) { paintPanel(); paintRoom(); }
  update();
  publishSoon();
}

function fmtValue(v, fmt) {
  if (fmt === 'db50') return signed(v - 50) + ' dB';
  if (fmt === 'dbneg') return (v ? '−' + v : '0') + ' dB';
  if (fmt === 'ms') return v + ' ms';
  return String(v);
}
const decode = (s, enc) => (enc === 'vol' ? decVol(s) : Number(s));
const encode = (v, enc) => (enc === 'vol' ? encVol(v) : enc === 'int3' ? pad(v, 3) : pad(v, 2));

function update() {
  const root = $('#view');
  const mv = decVol(S.MV);
  const bind = {
    input: () => inputName(S.SI),
    mode: () => modeName(S.MS) || '–',
    vol: () => (S.MV === '00' ? '–' : de(mv)),
    voldb: () => (S.MV ? signed(mv - 80) + ' dB' : ''),
    menu: () => (!online() ? 'Kein Receiver verbunden' : S.MNMEN === 'ON' ? 'Das Setup-Menü ist auf dem Fernseher offen.' : 'Das Setup-Menü ist geschlossen.'),
    spkname: () => (SPEAKERS[selSpk] || [''])[0],
    spkdb: () => (S['CV:' + selSpk] ? signed(decVol(S['CV:' + selSpk]) - 50) + ' dB' : ''),
    spkdb2: () => (S['CV:' + selSpk] ? signed(decVol(S['CV:' + selSpk]) - 50) : ''),
  };
  root.querySelectorAll('[data-bind]').forEach(el => {
    const b = el.dataset.bind;
    const t = b.startsWith('cv:') ? signed(decVol(S['CV:' + b.slice(3)]) - 50) : bind[b] ? bind[b]() : '';
    if (el.textContent !== t) el.textContent = t;
  });
  root.querySelectorAll('[data-si]').forEach(el => el.classList.toggle('on', el.dataset.si === S.SI));
  root.querySelectorAll('[data-seg]').forEach(el => {
    const v = S[el.dataset.seg];
    el.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.v === v || (el.dataset.seg === 'PS:REFLEV:n' && Number(b.dataset.v) === Number(v))));
  });
  root.querySelectorAll('[data-sw]').forEach(el => { const on = S[el.dataset.sw] === 'ON'; el.classList.toggle('on', on); el.setAttribute('aria-checked', on); });
  root.querySelectorAll('[data-stepper]').forEach(el => {
    const v = decode(S[el.dataset.stepper], el.dataset.enc);
    el.querySelector('output').textContent = fmtValue(v, el.dataset.fmt);
  });
  root.querySelectorAll('[data-spk]').forEach(el => el.classList.toggle('sel', el.dataset.spk === selSpk));

  const vs = $('#volSlider');
  if (vs && !dragging && S.MV) {
    vs.max = Math.min(80, decVol(S.MVMAX || '98'));
    vs.value = mv;
    vs.style.setProperty('--p', (mv / vs.max) * 100 + '%');
  }
  const cs = $('#cvSlider');
  if (cs && !dragging && S['CV:' + selSpk]) {
    const v = decVol(S['CV:' + selSpk]);
    cs.value = v;
    cs.style.setProperty('--p', ((v - 38) / 24) * 100 + '%');
  }
  const mute = $('#mute');
  if (mute) mute.classList.toggle('on', S.MU === 'ON');
  const mt = $('#menuToggle');
  if (mt) { mt.textContent = S.MNMEN === 'ON' ? 'Menü schließen' : 'Menü öffnen'; mt.classList.toggle('primary', S.MNMEN !== 'ON'); }
}

// ---------- Bedienung ----------
function send(c, optimistic) {
  if (!online()) return toast('Kein Receiver verbunden');
  cmd(c);
  if (optimistic) { Object.assign(S, optimistic); schedule(); }
}

let tt = 0;
function toast(t) {
  const el = $('#toast');
  el.textContent = t; el.hidden = false;
  clearTimeout(tt); tt = setTimeout(() => (el.hidden = true), 2200);
}

document.addEventListener('click', e => {
  const t = e.target.closest('button, a');
  if (!t) return;

  if (t.dataset.view) { return; } // Hash-Wechsel übernimmt
  if (t.id === 'conn' || t.id === 'connMobile') return openSettings();
  if (t.id === 'power') return send(S.PW === 'ON' ? 'PWSTANDBY' : 'PWON');
  if (t.id === 'mute') return send(S.MU === 'ON' ? 'MUOFF' : 'MUON', { MU: S.MU === 'ON' ? 'OFF' : 'ON' });
  if (t.id === 'menuToggle') return send(S.MNMEN === 'ON' ? 'MNMEN OFF' : 'MNMEN ON', { MNMEN: S.MNMEN === 'ON' ? 'OFF' : 'ON' });

  if (t.dataset.toggleStep) { openStep = openStep === t.dataset.toggleStep ? null : t.dataset.toggleStep; return schedule(); }
  if (t.dataset.done) {
    const id = t.dataset.done;
    done[id] = !done[id]; saveDone();
    if (done[id]) openStep = STEPS.find(s => !done[s.id])?.id || null;
    return schedule();
  }
  if (t.dataset.go) { location.hash = t.dataset.go; return; }
  if (t.dataset.spkmode) {
    spkMode = t.dataset.spkmode;
    try { localStorage.setItem('denon-spkmode', spkMode); } catch {}
    return schedule();
  }
  if (t.dataset.place) { selPlace = selPlace === t.dataset.place ? null : t.dataset.place; roomOpen = false; paintPanel(); window.Room3D?.sync(); return; }
  if (t.dataset.r3view) { window.Room3D?.view(t.dataset.r3view); return paintPanel(); }
  if (t.hasAttribute('data-deselect')) { selPlace = null; paintPanel(); window.Room3D?.sync(); return; }
  if (t.hasAttribute('data-roomtoggle')) { roomOpen = !roomOpen; return paintPanel(); }
  if (t.hasAttribute('data-undo')) return undo();
  if (t.hasAttribute('data-saveretry')) return saveLayout();
  if (t.dataset.kind && selPlace) {
    if (kindOf(selPlace) === t.dataset.kind) return;
    pushHistory();
    layout.spk[selPlace].kind = t.dataset.kind;
    layout.spk[selPlace].h = defH(selPlace, t.dataset.kind); // Messpunkt passt zur neuen Bauform
    saveLayout();
    window.Room3D?.rebuild();
    return paintPanel();
  }
  if (t.dataset.height && selPlace) {
    pushHistory();
    layout.spk[selPlace].h = clampH(layout.spk[selPlace].h + Number(t.dataset.height));
    saveLayout();
    paintPanel(); window.Room3D?.sync();
    return;
  }
  if (t.dataset.room) {
    pushHistory();
    const [dim, d] = t.dataset.room.split(':');
    layout[dim] = Math.max(2, Math.min(12, layout[dim] + Number(d)));
    const clamp = p => ({ ...p, x: Math.min(p.x, layout.w), y: Math.min(p.y, layout.l) });
    layout.seat = clamp(layout.seat);
    for (const k in layout.spk) layout.spk[k] = clamp(layout.spk[k]);
    saveLayout();
    return schedule();
  }
  if (t.dataset.spk) { selSpk = t.dataset.spk; confirmReset = false; return schedule(); }
  if (t.dataset.cvstep) {
    const k = 'CV:' + selSpk;
    const v = Math.max(38, Math.min(62, decVol(S[k]) + Number(t.dataset.cvstep)));
    return send(`CV${selSpk} ${encVol(v)}`, { [k]: encVol(v) });
  }

  const sw = t.dataset.sw;
  if (sw) {
    const on = S[sw] === 'ON';
    return send(on ? t.dataset.offCmd : t.dataset.onCmd, { [sw]: on ? 'OFF' : 'ON' });
  }
  if (t.dataset.d) {
    const st = t.closest('[data-stepper]');
    const k = st.dataset.stepper, enc = st.dataset.enc;
    const v = Math.max(Number(st.dataset.min), Math.min(Number(st.dataset.max), decode(S[k], enc) + Number(t.dataset.d) * Number(st.dataset.step)));
    return send(st.dataset.cmd + encode(v, enc), { [k]: encode(v, enc) });
  }
  if (t.dataset.cmd) {
    const segEl = t.closest('[data-seg]');
    if (segEl) return send(t.dataset.cmd, { [segEl.dataset.seg]: t.dataset.v });
    if (t.dataset.si) return send(t.dataset.cmd, { SI: t.dataset.si });
    return send(t.dataset.cmd);
  }

  const act = t.dataset.act;
  if (act === 'retry') return Conn.connect();
  if (act === 'demo') return Conn.configure({ demo: true });
  if (act === 'settings') return openSettings(true);
  if (act === 'refresh') return Conn.refresh();
  if (act === 'soundpreset') {
    ['PSMULTEQ:AUDYSSEY', 'PSDYNEQ ON', 'PSREFLEV 0'].forEach(c => send(c));
    Object.assign(S, { 'PS:MULTEQ': 'AUDYSSEY', 'PS:DYNEQ': 'ON', 'PS:REFLEV:n': '0' });
    toast('Grundwerte gesetzt');
    return schedule();
  }
  if (act === 'cvreset') {
    if (!confirmReset) { confirmReset = true; return schedule(); }
    confirmReset = false;
    send('CVZRL');
    setTimeout(() => Conn.refresh(), 400);
    return schedule();
  }
});

// Regler: während des Ziehens gedrosselt senden
let lastSend = 0, pendingTimer = 0;
document.addEventListener('input', e => {
  const el = e.target;
  if (el.id !== 'volSlider' && el.id !== 'cvSlider') return;
  dragging = true;
  const v = Number(el.value);
  const min = Number(el.min), max = Number(el.max);
  el.style.setProperty('--p', ((v - min) / (max - min)) * 100 + '%');
  const c = el.id === 'volSlider' ? 'MV' + encVol(v) : `CV${selSpk} ${encVol(v)}`;
  if (el.id === 'volSlider') S.MV = encVol(v); else S['CV:' + selSpk] = encVol(v);
  update();
  const fire = () => { lastSend = Date.now(); cmd(c); };
  clearTimeout(pendingTimer);
  if (Date.now() - lastSend > 150) fire(); else pendingTimer = setTimeout(fire, 150);
});
document.addEventListener('change', e => {
  if (e.target.id === 'volSlider' || e.target.id === 'cvSlider') { dragging = false; schedule(); }
});
document.addEventListener('pointerup', () => { if (dragging) { dragging = false; schedule(); } });

// Tastatur in der Aufstellung: Strg+Z nimmt zurück, Esc schließt Auswahl und Raum-Fenster
document.addEventListener('keydown', e => {
  if (!$('#room3d') || e.target.closest('input, textarea')) return;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); undo(); }
  if (e.key === 'Escape' && (selPlace || roomOpen)) { selPlace = null; roomOpen = false; paintPanel(); window.Room3D?.sync(); }
});

// Tastatur für das Fernseher-Menü
document.addEventListener('keydown', e => {
  if (view !== 'einrichtung' || !online() || e.target.closest('input, textarea') || !$('#settings').hidden) return;
  const map = { ArrowUp: 'MNCUP', ArrowDown: 'MNCDN', ArrowLeft: 'MNCLT', ArrowRight: 'MNCRT', Enter: 'MNENT', Backspace: 'MNRTN' };
  if (map[e.key]) { e.preventDefault(); cmd(map[e.key]); }
});

// ---------- Einstellungen ----------
let demoDraft = false;
let foundList = [];
function openSettings(scan) {
  $('#host').value = conn.host || '';
  demoDraft = !!conn.demo;
  $('#demoSwitch').classList.toggle('on', demoDraft);
  $('#settings').hidden = false;
  if (scan === true || !conn.host) runScan();
}
async function runScan() {
  const b = $('#scanBtn');
  if (b.disabled) return;
  b.disabled = true; b.textContent = 'Sucht im WLAN …';
  $('#found').innerHTML = '';
  try {
    foundList = await Conn.discover();
    $('#found').innerHTML = foundList.length
      ? foundList.map((r, i) => `<button type="button" data-found="${i}"><span>${esc(r.name)}<small>${esc(r.host)}</small></span></button>`).join('')
      : '<p>Kein Receiver gefunden. Ist die Netzwerksteuerung am Receiver an? Sonst die IP-Adresse unten eintragen.</p>';
  } catch (e) {
    $('#found').innerHTML = `<p>${esc(e.message === 'Kein WLAN' ? 'Das Handy ist nicht mit einem WLAN verbunden.' : 'Suche ging nicht: ' + e.message)}</p>`;
  }
  b.disabled = false; b.textContent = 'Erneut suchen';
}
$('#scanBtn').addEventListener('click', () => { hub.ui.haptic('tick'); runScan(); });
$('#found').addEventListener('click', e => {
  const b = e.target.closest('[data-found]');
  if (!b) return;
  const r = foundList[+b.dataset.found];
  hub.ui.haptic();
  Conn.configure({ host: r.host, name: r.name, demo: false });
  $('#settings').hidden = true;
  toast(r.name + ' verbunden');
});
$('#demoSwitch').addEventListener('click', () => { demoDraft = !demoDraft; $('#demoSwitch').classList.toggle('on', demoDraft); });
$('#settingsCancel').addEventListener('click', () => ($('#settings').hidden = true));
$('#settings').addEventListener('click', e => { if (e.target.id === 'settings') $('#settings').hidden = true; });
$('#settingsSave').addEventListener('click', () => {
  const host = $('#host').value.trim();
  if (!demoDraft && !/^[\w.-]+$/.test(host)) return toast('Bitte eine gültige IP-Adresse eingeben');
  Conn.configure({ host, demo: demoDraft, name: host === conn.host ? conn.name : '' });
  $('#settings').hidden = true;
});
document.addEventListener('keydown', e => { if (e.key === 'Escape') $('#settings').hidden = true; });


window.addEventListener('hashchange', () => { view = location.hash.slice(1) || 'jetzt'; confirmReset = false; schedule(); window.scrollTo(0, 0); });

// ---------- Skyline-App: Startseiten-Karte, Hintergrund, Zurück ----------
let pubTimer = 0, lastCard = '';
function publishSoon() { clearTimeout(pubTimer); pubTimer = setTimeout(publish, 120); }
function publish() {
  let card;
  const name = conn.name || (conn.demo ? 'AVR-X2100W (Demo)' : 'Denon-Receiver');
  if (conn.status === 'setup') {
    card = { title: 'Receiver verbinden', subtitle: 'Denon und Marantz im WLAN finden', actions: [{ id: 'setup', label: 'Im WLAN suchen', primary: true }] };
  } else if (!online() || !S.PW) {
    card = { title: name, subtitle: conn.status === 'connecting' ? 'Verbinde …' : conn.detail === 'pausiert' ? 'Verbindet sich beim Öffnen der App' : 'Nicht erreichbar',
      state: { text: conn.status === 'connecting' ? 'Verbinde' : 'Getrennt', tone: 'off' }, actions: [{ id: 'retry', label: 'Erneut verbinden' }] };
  } else if (S.PW !== 'ON') {
    card = { title: name, subtitle: 'Standby', state: { text: conn.demo ? 'Demo' : 'Verbunden', tone: 'ok' }, actions: [{ id: 'power', icon: 'power', label: 'Einschalten', primary: true }] };
  } else {
    const mv = decVol(S.MV);
    card = {
      title: inputName(S.SI) || name,
      subtitle: [modeName(S.MS), name].filter(Boolean).join(' · '),
      state: { text: conn.demo ? 'Demo' : 'An', tone: 'ok' },
      value: { text: S.MU === 'ON' ? 'Stumm' : S.MV ? de(mv) : '–', label: S.MU === 'ON' ? '' : 'Lautstärke' },
      actions: [
        { id: 'voldown', icon: 'minus', repeat: true },
        { id: 'volup', icon: 'plus', repeat: true },
        { id: 'mute', icon: 'mute', on: S.MU === 'ON' },
        { id: 'power', icon: 'power' },
      ],
    };
  }
  const sig = JSON.stringify(card);
  if (sig === lastCard) return;
  lastCard = sig;
  hub.home.set(card).catch(() => {});
}
hub.on('home.action', ({ id }) => {
  if (id === 'setup') { hub.nav.open(); openSettings(true); }
  else if (id === 'retry') Conn.connect();
  else if (id === 'volup') send('MVUP');
  else if (id === 'voldown') send('MVDOWN');
  else if (id === 'mute') send(S.MU === 'ON' ? 'MUOFF' : 'MUON', { MU: S.MU === 'ON' ? 'OFF' : 'ON' });
  else if (id === 'power') send(S.PW === 'ON' ? 'PWSTANDBY' : 'PWON');
});
// Der Receiver erlaubt nur eine Verbindung: im Hintergrund freigeben, damit andere Apps sie bekommen
let pauseTimer = 0;
hub.on('app', ({ state }) => {
  clearTimeout(pauseTimer);
  if (state === 'pause') pauseTimer = setTimeout(() => Conn.pause(), 15000);
  else Conn.resume();
});
hub.onBack(() => {
  if (!$('#settings').hidden) { $('#settings').hidden = true; return true; }
  if (selPlace || roomOpen) { selPlace = null; roomOpen = false; paintPanel(); window.Room3D?.sync(); return true; }
  if (view !== 'jetzt') { location.hash = 'jetzt'; return true; }
  return false;
});

startEvents();
conn = { status: 'offline', ...Conn.config };
paintConn();
render();
hub.ready.then(() => Conn.connect());
