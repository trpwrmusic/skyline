'use strict';
/*
 * Skyline Beamer: Fernbedienung als Erweiterung der Skyline-App.
 * Spricht über hub.http mit der Web-Schnittstelle des Beamers (Port 8080), findet Beamer per mDNS („_skyline._tcp“)
 * und per Suche im eigenen Netz, und legt eine Karte auf die Startseite der App.
 */
const $ = s => document.querySelector(s);
const buzz = (kind = 'tick') => hub.ui.haptic(kind).catch(() => {});
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const PORT = 8080;

// ---------- Beamer (gemerkte Geräte) ----------
const load = (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } };
const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} };
let devices = load('devices', []);          // [{host: 'ip:8080', name}]
let cur = devices.find(d => d.host === load('current', '')) || devices[0] || null;
let online = false, lastState = null, searching = false;

const base = () => 'http://' + cur.host;
const normHost = h => { h = String(h).trim().replace(/^https?:\/\//, '').replace(/\/.*$/, ''); return h.includes(':') ? h : h + ':' + PORT; };

function remember(host, name) {
  const i = devices.findIndex(d => d.host === host);
  if (i >= 0) devices[i].name = name || devices[i].name; else devices.push({ host, name: name || 'Beamer' });
  save('devices', devices);
}
function use(host) {
  cur = devices.find(d => d.host === host);
  save('current', host);
  online = false; lastState = null;
  paintHead();
  load_();
}

// ---------- Anfragen an den Beamer ----------
async function api(path, method = 'GET', body) {
  const r = await hub.http.request(base() + '/api/' + path, { method, body: method === 'POST' ? (body || '') : undefined, timeout: 7000,
    headers: method === 'POST' ? { 'Content-Type': 'text/plain;charset=UTF-8' } : undefined });
  if (r.status >= 400) throw new Error('HTTP ' + r.status);
  return JSON.parse(r.body || '{}');
}
const getJ = p => api(p);
const postJ = (p, body) => api(p, 'POST', body);

// Befehle strikt der Reihe nach senden (sonst überholen sich Pfeile)
let chain = Promise.resolve();
function post(path, body) {
  if (!cur) return Promise.resolve();
  chain = chain.then(() => postJ(path, body).then(j => { setOnline(true); return j; }).catch(() => setOnline(false)));
  return chain;
}
const key = code => post('key/' + code);

function setOnline(ok) {
  if (online === ok) return;
  online = ok;
  paintHead();
  publish();
}

// ---------- Kopf, Zustände ----------
function paintHead() {
  $('#devName').textContent = cur ? cur.name : (searching ? 'Suche …' : 'Kein Beamer');
  const pill = $('#pill');
  pill.className = 'h-pill ' + (online ? 'ok' : cur ? '' : 'plain');
  pill.textContent = online ? 'Verbunden' : cur ? (lastState === null ? 'Verbinde' : 'Getrennt') : '';
  pill.hidden = !cur;
  const showMain = !!cur && online;
  $('#main').hidden = !showMain;
  $('#none').hidden = showMain;
  if (!showMain) paintNone();
}

function paintNone() {
  document.body.classList.toggle('searching', searching);
  const t = $('#noneTitle'), p = $('#noneText'), b = $('#searchBtn');
  if (searching) { t.textContent = 'Suche im WLAN'; p.textContent = 'Beamer mit Skyline melden sich von selbst. Das dauert ein paar Sekunden.'; }
  else if (cur && lastState === null) { t.textContent = 'Verbinde mit ' + cur.name; p.textContent = cur.host.replace(':' + PORT, ''); }
  else if (cur) { t.textContent = cur.name + ' antwortet nicht'; p.textContent = 'Ist er eingeschaltet und im selben WLAN wie dieses Handy? Schläft er, weckt ihn die Fernbedienung am Gerät.'; }
  else { t.textContent = 'Beamer verbinden'; p.textContent = 'Die App sucht im WLAN nach Beamern mit Skyline. Am Beamer geht es auch per QR-Code unter „Handy-App“.'; }
  b.disabled = searching;
  b.textContent = searching ? 'Sucht …' : cur && lastState !== null ? 'Erneut verbinden' : 'Im WLAN suchen';
}

// ---------- Suche ----------
async function search() {
  if (searching) return;
  searching = true; paintHead();
  $('#found').innerHTML = '';
  $('#searchStatus').textContent = '';
  const found = new Map();
  const add = (host, name) => { if (!found.has(host)) { found.set(host, name || 'Beamer'); paintFound(found); } };
  const viaMdns = hub.net.mdns('_skyline._tcp', { timeout: 3500 }).then(list => list.forEach(s => add(s.host + ':' + s.port, s.name))).catch(() => {});
  const viaScan = hub.net.scan({ port: PORT, path: '/api/state', contains: '"helper"', timeout: 350 }).then(list => list.forEach(h => {
    let name = 'Beamer'; try { name = JSON.parse(h.body).name || name; } catch (e) {}
    add(h.ip + ':' + PORT, name);
  })).catch(err => { $('#searchStatus').textContent = err.message === 'Kein WLAN' ? 'Das Handy ist nicht mit einem WLAN verbunden.' : ''; });
  await Promise.all([viaMdns, viaScan]);
  searching = false;
  if (found.size === 1 && !cur) { const [h, n] = [...found][0]; remember(h, n); use(h); hub.ui.toast(n + ' verbunden'); return; }
  if (!found.size && !$('#searchStatus').textContent) $('#searchStatus').textContent = 'Kein Beamer gefunden. Ist er eingeschaltet?';
  paintHead();
  paintFound(found);
}
function paintFound(found) {
  $('#found').innerHTML = [...found].map(([h, n]) => `<button data-host="${esc(h)}"><span class="t">${esc(n)}<small>${esc(h.replace(':' + PORT, ''))}</small></span></button>`).join('');
  $('#found').querySelectorAll('button').forEach(b => b.addEventListener('click', () => { buzz(); remember(b.dataset.host, found.get(b.dataset.host)); use(b.dataset.host); closeDev(); }));
}
$('#searchBtn').addEventListener('click', () => { buzz(); if (cur && lastState !== null && !searching) { lastState = null; paintHead(); load_(); setTimeout(() => { if (!online) search(); }, 2500); } else search(); });
$('#hostGo').addEventListener('click', connectManual);
$('#hostIn').addEventListener('keydown', e => { if (e.key === 'Enter') connectManual(); });
async function connectManual() {
  const h = normHost($('#hostIn').value);
  if (h.length < 6) return;
  buzz();
  $('#searchStatus').textContent = 'Verbinde mit ' + h.replace(':' + PORT, '') + ' …';
  try {
    const r = await hub.http.request('http://' + h + '/api/state', { timeout: 4000 });
    const s = JSON.parse(r.body);
    if (!('helper' in s)) throw new Error();
    remember(h, s.name || 'Beamer'); use(h);
    $('#searchStatus').textContent = '';
  } catch (e) { $('#searchStatus').textContent = 'Unter ' + h.replace(':' + PORT, '') + ' antwortet kein Skyline-Beamer.'; }
}

// Koppeln per QR-Code am Beamer: skyline://pair?host=192.168.0.20:8080&name=…
hub.on('link', ({ url }) => {
  try {
    const u = new URL(url.replace(/^skyline:\/\//, 'http://x/'));
    const host = normHost(u.searchParams.get('host') || '');
    if (host.length < 6) return;
    remember(host, u.searchParams.get('name') || 'Beamer');
    use(host);
    hub.ui.toast((u.searchParams.get('name') || 'Beamer') + ' gekoppelt');
  } catch (e) {}
});

// ---------- Beamer-Wahl ----------
function openDev() {
  buzz();
  $('#devList').innerHTML = devices.map(d => `<button data-host="${esc(d.host)}" class="${cur && d.host === cur.host ? 'cur' : ''}"><span class="t">${esc(d.name)}<small>${esc(d.host.replace(':' + PORT, ''))}</small></span>
    ${cur && d.host === cur.host ? '<svg class="ck" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7"/></svg>' : ''}</button>`).join('')
    || '<p class="note">Noch kein Beamer gemerkt.</p>';
  $('#devList').querySelectorAll('button').forEach(b => b.addEventListener('click', () => { buzz(); use(b.dataset.host); closeDev(); }));
  $('#devForget').hidden = !cur;
  $('#devSheet').hidden = false;
}
const closeDev = () => { $('#devSheet').hidden = true; };
$('#devBtn').addEventListener('click', openDev);
$('#devClose').addEventListener('click', closeDev);
$('#devSheet').addEventListener('click', e => { if (e.target.id === 'devSheet') closeDev(); });
$('#devSearch').addEventListener('click', () => { closeDev(); cur = null; online = false; paintHead(); search(); });
$('#devForget').addEventListener('click', () => {
  if (!cur) return;
  const gone = cur;
  devices = devices.filter(d => d.host !== gone.host); save('devices', devices);
  cur = devices[0] || null; save('current', cur ? cur.host : '');
  closeDev(); online = false; lastState = null; paintHead(); publish();
  if (cur) load_();
  hub.ui.toast(gone.name + ' vergessen', { action: 'Rückgängig' }).then(undo => { if (undo) { remember(gone.host, gone.name); use(gone.host); } });
});

// ---------- Zurück-Taste: offene Blätter zuerst schließen ----------
hub.onBack(() => {
  if (!$('#devSheet').hidden) { closeDev(); return true; }
  if (!$('#sheet').hidden) { closeSheet(); return true; }
  if (!$('#main').hidden && currentTab !== 'remote') { showTab('remote'); return true; }
  return false;
});

// ---------- Touchpad ----------
const pad = $('#pad'), touch = $('#touch');
const STEP = 64, CODES = { up: 19, down: 20, left: 21, right: 22 };
let sx, sy, ox, oy, moved, holdTimer, holding, repeatTimer, active = false;

function flash(dir) {
  const e = pad.querySelector('.edge.' + dir);
  e.classList.add('flash'); requestAnimationFrame(() => requestAnimationFrame(() => e.classList.remove('flash')));
}
function showTouch(x, y) { const r = pad.getBoundingClientRect(); touch.style.left = (x - r.left) + 'px'; touch.style.top = (y - r.top) + 'px'; }

// Maus-Modus: Zeiger bewegen, tippen = Klick, zwei Finger = wischen
let mouse = load('mode', 'keys') === 'mouse';
function setMode(m) {
  mouse = m; save('mode', m ? 'mouse' : 'keys');
  $('#modeKeys').classList.toggle('on', !m); $('#modeMouse').classList.toggle('on', m);
  $('#padHint').textContent = m ? 'Bewegen · Tippen = Klick · Halten = lang · 2 Finger = wischen' : 'Wischen · Tippen = OK · Halten = lang OK';
  if (!m && cur && online) post('ptr/hide');
}
$('#modeKeys').onclick = () => { buzz(); setMode(false); };
$('#modeMouse').onclick = () => { buzz(); setMode(true); };
setMode(mouse);

let pend = null;   // Bewegungen sammeln, solange noch eine unterwegs ist
function ptrMove(kind, dx, dy) {
  if (pend && pend.kind === kind) { pend.dx += dx; pend.dy += dy; return; }
  const p = pend = { kind, dx, dy };
  chain = chain.then(() => {
    if (pend === p) pend = null;
    const x = Math.round(p.dx), y = Math.round(p.dy);
    if (!x && !y) return;
    return postJ('ptr/' + kind + '/' + x + '/' + y).catch(() => {});
  });
}
const accel = d => d * (2.4 + Math.min(Math.abs(d) * .09, 3.2));
const pts = new Map();
let mDown = 0, mMoved = false, mLong = false, mTwo = false, mHold;

function mouseDown(e) {
  pad.setPointerCapture(e.pointerId);
  pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
  pad.classList.add('active'); showTouch(e.clientX, e.clientY);
  if (pts.size === 1) {
    mDown = Date.now(); mMoved = false; mLong = false; mTwo = false; sx = e.clientX; sy = e.clientY;
    mHold = setTimeout(() => { if (!mMoved && !mTwo) { mLong = true; buzz('long'); post('ptr/long'); } }, 550);
  } else if (pts.size === 2 && !mTwo) {
    mTwo = true; clearTimeout(mHold); post('ptr/dragstart');
  }
}
function mouseMove(e) {
  const p = pts.get(e.pointerId); if (!p) return;
  const dx = e.clientX - p.x, dy = e.clientY - p.y;
  p.x = e.clientX; p.y = e.clientY;
  showTouch(e.clientX, e.clientY);
  if (Math.hypot(e.clientX - sx, e.clientY - sy) > 8) { mMoved = true; clearTimeout(mHold); }
  if (mTwo) ptrMove('dragmove', dx * 1.6, dy * 1.6);
  else if (!mLong) ptrMove('move', accel(dx), accel(dy));
}
function mouseUp(e) {
  if (!pts.has(e.pointerId)) return;
  pts.delete(e.pointerId);
  if (pts.size > 0) return;
  clearTimeout(mHold);
  pad.classList.remove('active');
  if (mTwo) { post('ptr/dragend'); return; }
  if (!mMoved && !mLong && Date.now() - mDown < 400) { buzz('tap'); post('ptr/tap'); }
}
pad.addEventListener('pointerdown', e => { if (mouse) { e.stopImmediatePropagation(); mouseDown(e); } }, true);
pad.addEventListener('pointermove', e => { if (mouse) { e.stopImmediatePropagation(); mouseMove(e); } }, true);
pad.addEventListener('pointerup', e => { if (mouse) { e.stopImmediatePropagation(); mouseUp(e); } }, true);
pad.addEventListener('pointercancel', e => { if (mouse) { e.stopImmediatePropagation(); mouseUp(e); } }, true);

pad.addEventListener('pointerdown', e => {
  pad.setPointerCapture(e.pointerId);
  active = true; moved = false; holding = false;
  sx = ox = e.clientX; sy = oy = e.clientY;
  pad.classList.add('active'); showTouch(e.clientX, e.clientY);
  holdTimer = setTimeout(() => {           // langes OK: echte Taste halten (DOWN, Wiederholung, später UP)
    holding = true; pad.classList.add('holding'); buzz('long');
    post('down/23'); post('repeat/23/1');
    let n = 2; repeatTimer = setInterval(() => post('repeat/23/' + (n++)), 120);
  }, 520);
});
pad.addEventListener('pointermove', e => {
  if (!active) return;
  showTouch(e.clientX, e.clientY);
  if (Math.hypot(e.clientX - sx, e.clientY - sy) > 12 && !holding) { moved = true; clearTimeout(holdTimer); }
  if (holding) return;
  const dx = e.clientX - ox, dy = e.clientY - oy;
  if (Math.max(Math.abs(dx), Math.abs(dy)) >= STEP) {
    const dir = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
    key(CODES[dir]); flash(dir); buzz();
    ox = e.clientX; oy = e.clientY;
  }
});
function end(e) {
  if (!active) return;
  active = false; clearTimeout(holdTimer); clearInterval(repeatTimer);
  pad.classList.remove('active', 'holding');
  if (holding) { post('up/23'); return; }
  if (!moved) { key(23); buzz('tap'); return; }
  const dx = e.clientX - ox, dy = e.clientY - oy;
  if (Math.max(Math.abs(dx), Math.abs(dy)) >= 24 && Math.hypot(e.clientX - sx, e.clientY - sy) < STEP) {
    const dir = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
    key(CODES[dir]); flash(dir); buzz();
  }
}
pad.addEventListener('pointerup', end);
pad.addEventListener('pointercancel', end);

// ---------- Tasten (Lautstärke: gedrückt halten wiederholt) ----------
document.querySelectorAll('.btn').forEach(b => {
  let t, r;
  b.addEventListener('pointerdown', () => {
    buzz('tap'); key(b.dataset.key);
    if (b.hasAttribute('data-repeat')) t = setTimeout(() => { r = setInterval(() => key(b.dataset.key), 140); }, 400);
  });
  const stop = () => { clearTimeout(t); clearInterval(r); };
  b.addEventListener('pointerup', stop); b.addEventListener('pointerleave', stop); b.addEventListener('pointercancel', stop);
});
document.querySelectorAll('.chip').forEach(c => c.addEventListener('click', () => { buzz('tap'); post('action/' + c.dataset.action); }));

// ---------- Tastatur ----------
function sendText() { const v = $('#text').value; if (!v) return; post('text', v); $('#text').value = ''; }
$('#send').addEventListener('click', sendText);
$('#text').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); if ($('#text').value) sendText(); else key(66); } });
$('#del').addEventListener('click', () => { buzz(); key(67); });

// ---------- Status, Apps, Startseiten-Karte ----------
let appsSig = '', loadTimer = 0, fails = 0;
async function load_() {
  clearTimeout(loadTimer);
  if (!cur) { paintHead(); publish(); return; }
  const host = cur.host;
  try {
    const s = await getJ('state');
    if (!cur || cur.host !== host) return;
    lastState = s;
    fails = 0;
    if (s.name && s.name !== cur.name) { remember(host, s.name); cur = devices.find(d => d.host === host); }
    online = true;
    const sig = (s.apps || []).map(a => a.pkg).join();
    if (sig !== appsSig) {
      appsSig = sig;
      $('#apps').innerHTML = (s.apps || []).map(a =>
        `<button class="app" data-pkg="${esc(a.pkg)}"><img loading="lazy" src="${base()}/api/icon/${esc(a.pkg)}.png" alt=""><span>${esc(a.name)}</span></button>`).join('');
      document.querySelectorAll('.app').forEach(a => a.addEventListener('click', () => { buzz('tap'); post('launch/' + a.dataset.pkg); }));
    }
    try { const m = await getJ('media'); playing = (m.items || []).find(x => x.playing) || null; } catch (e) { playing = null; }
  } catch (e) {
    console.warn('Beamer ' + host + ' antwortet nicht: ' + (e && e.message));
    if (!cur || cur.host !== host) return;
    // Ein einzelner Aussetzer im WLAN zählt noch nicht als getrennt
    if (online && ++fails < 2) { loadTimer = setTimeout(load_, 3000); return; }
    lastState = lastState || false;
    online = false;
  }
  paintHead();
  publish();
  if (hub.foreground) loadTimer = setTimeout(load_, online ? 20000 : 6000);
}
let playing = null;

function wetterText(w) {
  if (!w) return '';
  const m = String(w).match(/^([^(]*)\(([^)]*)\)/);
  const parts = (m ? m[1] : w).split(',').map(x => x.trim()).filter(Boolean);
  const nice = parts.slice(0, 2).map(p => p.charAt(0).toUpperCase() + p.slice(1)).join(', ');
  return nice + (m ? ' · ' + m[2].trim() : '');
}

function publish() {
  if (!cur) {
    hub.home.set({ title: 'Beamer verbinden', subtitle: 'Sucht im WLAN nach Beamern mit Skyline.', image: new URL('card.jpg', location.href).href,
      actions: [{ id: 'search', label: 'Beamer suchen', primary: true }] }).catch(() => {});
    return;
  }
  if (!online) {
    hub.home.set({ title: cur.name, subtitle: lastState === null ? 'Verbinde …' : 'Antwortet nicht. Ist er eingeschaltet?',
      state: { text: lastState === null ? 'Verbinde' : 'Getrennt', tone: 'off' },
      actions: [{ id: 'retry', label: 'Erneut verbinden' }] }).catch(() => {});
    return;
  }
  const img = playing && !playing.video ? `${base()}/api/thumb/${encodeURIComponent(playing.name)}?s=${playing.size}` : new URL('card.jpg', location.href).href;
  hub.home.set({
    title: cur.name,
    subtitle: wetterText(lastState && lastState.wetter) || 'Bereit',
    state: { text: 'Verbunden', tone: 'ok' },
    image: playing ? `${base()}/api/thumb/${encodeURIComponent(playing.name)}?s=${playing.size}` : img,
    actions: [
      { id: 'remote', label: 'Fernbedienung', icon: 'remote', primary: true },
      { id: 'homekey', icon: 'homekey', label: '' },
      { id: 'playpause', icon: 'playpause', label: '' },
    ],
  }).catch(() => {});
}

hub.on('home.action', ({ id }) => {
  if (id === 'remote') { showTab('remote'); hub.nav.open(); }
  else if (id === 'homekey') key(3);
  else if (id === 'playpause') key(85);
  else if (id === 'search') { hub.nav.open(); search(); }
  else if (id === 'retry') { lastState = null; publish(); load_(); }
});
hub.on('app', ({ state }) => { if (state === 'resume') load_(); else clearTimeout(loadTimer); });
hub.on('visibility', ({ visible }) => { if (visible && !online) load_(); });

// ---------- Reiter ----------
const formBody = o => Object.entries(o).map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&');
const mb = n => n >= 1e9 ? (n / 1e9).toFixed(1).replace('.', ',') + ' GB' : Math.max(1, Math.round(n / 1e6)) + ' MB';
let toastT;
function toast(msg) { const t = $('#toast'); t.textContent = msg; t.classList.add('show'); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('show'), 2600); }
let currentTab = 'remote';
function showTab(name) {
  currentTab = name;
  document.querySelectorAll('.tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === name));
  document.querySelectorAll('section[id^="tab-"]').forEach(s => s.hidden = s.id !== 'tab-' + name);
  if (name === 'media') loadMedia();
  if (name === 'settings') loadSettings();
  window.scrollTo(0, 0);
}
document.querySelectorAll('.tabs button').forEach(b => b.addEventListener('click', () => { buzz(); showTab(b.dataset.tab); }));

function tagsOf(root) { return [...root.querySelectorAll('.tag.on')].map(t => t.dataset.t).join(' '); }
function setTags(root, s) { const on = new Set(String(s || '').split(/\s+/)); root.querySelectorAll('.tag').forEach(t => t.classList.toggle('on', on.has(t.dataset.t))); }
document.querySelectorAll('.tag').forEach(t => t.addEventListener('click', () => { buzz(); t.classList.toggle('on'); }));

// ---------- Medien ----------
let picked = [];
$('#pick').addEventListener('click', () => $('#files').click());
$('#files').addEventListener('change', () => {
  picked = [...$('#files').files];
  if (!picked.length) return;
  const total = picked.reduce((a, f) => a + f.size, 0);
  $('#upTitle').textContent = (picked.length === 1 ? picked[0].name : picked.length + ' Dateien') + ' · ' + mb(total);
  setTags($('#upCard'), '');
  $('#upWx').open = bgMode === 'weather';
  $('#upStatus').textContent = ''; $('#upBar').hidden = true; $('#upGo').disabled = false;
  $('#upCard').hidden = false;
  $('#upCard').scrollIntoView({ behavior: 'smooth', block: 'center' });
});

// Hochladen direkt zum Beamer (braucht CORS ab Skyline 4.4); große Dateien gehen so ohne Umweg durch die App
function uploadOne(file, tags, onProgress) {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open('POST', base() + '/api/upload?' + formBody({ name: file.name, tags }));
    x.upload.onprogress = e => e.lengthComputable && onProgress(e.loaded);
    x.onload = () => { try { const j = JSON.parse(x.responseText); j.ok ? resolve(j) : reject(new Error(j.error || 'Fehler')); } catch (e) { reject(e); } };
    x.onerror = () => reject(new Error(x.status === 0 ? 'Der Beamer braucht ein Update auf Skyline 4.4, er holt es sich beim nächsten Start' : 'Verbindung abgebrochen'));
    x.send(file);
  });
}

$('#upGo').addEventListener('click', async () => {
  if (!picked.length) return;
  const tags = tagsOf($('#upCard'));
  const total = picked.reduce((a, f) => a + f.size, 0);
  let done = 0, ok = 0;
  $('#upGo').disabled = true; $('#upBar').hidden = false;
  const bar = $('#upBar i');
  for (let i = 0; i < picked.length; i++) {
    const f = picked[i];
    $('#upStatus').textContent = (picked.length > 1 ? `${i + 1} von ${picked.length}: ` : '') + f.name;
    try {
      await uploadOne(f, tags, n => { bar.style.width = ((done + n) / total * 100).toFixed(1) + '%'; });
      ok++;
    } catch (e) { $('#upStatus').textContent = f.name + ': ' + e.message; }
    done += f.size;
    bar.style.width = (done / total * 100).toFixed(1) + '%';
  }
  if (ok) $('#upStatus').textContent = ok === picked.length ? (ok === 1 ? 'Fertig, läuft jetzt auf dem Beamer' : ok + ' Dateien hochgeladen') : ok + ' von ' + picked.length + ' hochgeladen';
  $('#files').value = ''; picked = [];
  if (ok) setTimeout(() => { $('#upCard').hidden = true; }, 2200);
  else $('#upGo').disabled = false;
  loadMedia(); setTimeout(load_, 1500);
});

let media = [], bgMode = 'random';
const TAGNAME = { klar: 'klar', wolkig: 'wolkig', regen: 'Regen', schnee: 'Schnee', tag: 'Tag', daemmerung: 'Dämmerung', nacht: 'Nacht',
  fruehling: 'Frühling', sommer: 'Sommer', herbst: 'Herbst', winter: 'Winter' };
const tagText = s => String(s || '').split(/\s+/).filter(Boolean).map(t => TAGNAME[t] || t).join(' · ');
async function loadMedia() {
  try { bgMode = (await getJ('settings')).bgMode || 'random'; } catch (e) {}
  try {
    const j = await getJ('media');
    media = j.items;
    $('#mediaGrid').innerHTML = media.map((m, i) => `
      <button class="thumb" data-i="${i}">
        <div class="img" style="background-image:url('${base()}/api/thumb/${encodeURIComponent(m.name)}?s=${m.size}')"></div>
        <span class="kind">${m.video ? 'Video' : 'Foto'}</span>${m.playing ? '<span class="live">Läuft</span>' : ''}
        <div class="meta">${esc(m.ort || m.name.replace(/\.[^.]+$/, '').replace(/[-_]/g, ' '))}<small>${esc(bgMode === 'weather' ? (tagText(m.tags) || 'jedes Wetter') : mb(m.size))}</small></div>
      </button>`).join('');
    $('#mediaNote').textContent = media.length ? 'Frei auf dem Beamer: ' + mb(j.free) + '. Tippen für Details.' : '';
    if (!media.length) $('#mediaGrid').innerHTML = '<div class="empty" style="grid-column:1/-1">Noch keine eigenen Fotos oder Videos.<br>Der Beamer zeigt die eingebauten Bilder.</div>';
    document.querySelectorAll('.thumb').forEach(t => t.addEventListener('click', () => openSheet(media[+t.dataset.i])));
  } catch (e) { $('#mediaNote').textContent = 'Medien lassen sich gerade nicht laden.'; }
}

let curM = null;
function openSheet(m) {
  curM = m; buzz();
  $('#shPrev').style.backgroundImage = `url('${base()}/api/thumb/${encodeURIComponent(m.name)}?s=${m.size}')`;
  $('#shName').textContent = m.name;
  $('#shSub').textContent = (m.video ? 'Video' : 'Foto') + ' · ' + mb(m.size) + (m.playing ? ' · läuft gerade' : '');
  setTags($('#sheet'), m.tags);
  $('#shWx').open = bgMode === 'weather';
  $('#sheet').hidden = false;
}
const closeSheet = () => { $('#sheet').hidden = true; curM = null; };
$('#shClose').addEventListener('click', closeSheet);
$('#sheet').addEventListener('click', e => { if (e.target === $('#sheet')) closeSheet(); });
$('#shShow').addEventListener('click', async () => { if (!curM) return; buzz(); await postJ('media/show/' + encodeURIComponent(curM.name)); toast('Läuft jetzt auf dem Beamer'); closeSheet(); setTimeout(loadMedia, 800); setTimeout(load_, 1200); });
$('#shSave').addEventListener('click', async () => {
  if (!curM) return; buzz();
  const j = await postJ('media/meta/' + encodeURIComponent(curM.name), formBody({ ort: curM.ort || '', tags: tagsOf($('#sheet')) }));
  toast(j.ok ? 'Merkmale gespeichert' : 'Speichern ging nicht'); closeSheet(); loadMedia();
});
$('#shDel').addEventListener('click', async () => {
  if (!curM) return;
  const b = $('#shDel');
  if (b.dataset.sure !== '1') { b.dataset.sure = '1'; b.textContent = 'Wirklich löschen?'; setTimeout(() => { b.dataset.sure = ''; b.textContent = 'Löschen'; }, 3000); return; }
  b.dataset.sure = ''; b.textContent = 'Löschen';
  const j = await postJ('media/delete/' + encodeURIComponent(curM.name));
  toast(j.ok ? 'Gelöscht' : 'Löschen ging nicht'); closeSheet(); loadMedia();
});

// ---------- Einstellungen ----------
const PLACES = [['home', 'Startbildschirm'], ['window', 'Fenster-Modus'], ['dream', 'Bildschirmschoner']];
let SET = null;
async function loadSettings() {
  try { SET = await getJ('settings'); } catch (e) { $('#ortNow').textContent = 'Beamer nicht erreichbar'; return; }
  bgMode = SET.bgMode || 'random';
  $('#bgRandom').classList.toggle('on', bgMode === 'random');
  $('#bgWeather').classList.toggle('on', bgMode === 'weather');
  $('#bgSub').textContent = bgMode === 'weather'
    ? 'Zeigt deine Bilder und Videos passend zu Wetter, Tageszeit und Jahreszeit. Was passt, legst du unter Medien bei jedem Motiv fest.'
    : 'Zeigt deine Bilder und Videos zufällig gemischt. Ohne eigene Motive laufen die eingebauten Fotos.';
  $('#ortNow').textContent = (SET.ort ? SET.ort + (SET.ortFest ? '' : ' (automatisch)') : 'noch kein Ort') + (SET.wetter ? ' · ' + SET.wetter : '');
  $('#wxPlaces').innerHTML = PLACES.map(([k, n]) => `
    <div class="set" data-place="${k}">
      <div class="txt"><b>${n}</b><span>${SET.wx[k].show ? 'wird angezeigt' : 'aus'}</span></div>
      <div class="screen${SET.wx[k].show ? '' : ' off'}">${['tl', 'tr', 'bl', 'br'].map(p =>
        `<button data-pos="${p}" class="${SET.wx[k].pos === p ? 'on' : ''}" ${k === 'home' && p === 'bl' ? 'disabled' : ''} aria-label="${p}"></button>`).join('')}</div>
      <label class="switch"><input type="checkbox" ${SET.wx[k].show ? 'checked' : ''}><i></i></label>
    </div>`).join('');
  document.querySelectorAll('#wxPlaces .set').forEach(row => {
    const k = row.dataset.place;
    row.querySelector('input').addEventListener('change', async e => {
      buzz(); await postJ('settings', formBody({ ['wxshow_' + k]: e.target.checked ? 1 : 0 })); loadSettings();
    });
    row.querySelectorAll('.screen button').forEach(b => b.addEventListener('click', async () => {
      buzz(); row.querySelectorAll('.screen button').forEach(x => x.classList.toggle('on', x === b));
      await postJ('settings', formBody({ ['wxpos_' + k]: b.dataset.pos }));
    }));
  });
  $('#updVer').textContent = 'Installiert: Skyline ' + SET.version;
  $('#updAuto').checked = SET.updAuto;
  $('#updRepo').value = SET.updRepo;
  $('#updToken').placeholder = SET.updToken ? 'gespeichert (leer lassen = behalten)' : 'github_pat_…';
  $('#updStatus').textContent = SET.updStatus || '';
}

async function searchOrt() {
  const q = $('#ortQ').value.trim(); if (!q) return;
  $('#ortRes').innerHTML = '<div class="note">Suche …</div>';
  let r = [];
  try { r = await getJ('geocode?q=' + encodeURIComponent(q)); } catch (e) {}
  $('#ortRes').innerHTML = r.length ? r.map((o, i) => `<button data-i="${i}"><span class="t">${esc(o.name)}<small>${esc([o.region, o.land].filter(Boolean).join(', '))}</small></span></button>`).join('')
    : '<div class="note">Nichts gefunden.</div>';
  $('#ortRes').querySelectorAll('button').forEach(b => b.addEventListener('click', async () => {
    const o = r[+b.dataset.i]; buzz();
    await postJ(`location/${o.lat.toFixed(4)}/${o.lon.toFixed(4)}/${encodeURIComponent(o.name)}`);
    $('#ortRes').innerHTML = ''; $('#ortQ').value = '';
    toast('Ort: ' + o.name);
    setTimeout(loadSettings, 2500);
  }));
}
$('#ortGo').addEventListener('click', searchOrt);
$('#ortQ').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); searchOrt(); } });
$('#ortAuto').addEventListener('click', async () => { buzz(); await postJ('location/auto'); toast('Ort wird automatisch ermittelt'); setTimeout(loadSettings, 4000); });

$('#updAuto').addEventListener('change', e => postJ('settings', formBody({ upd_auto: e.target.checked ? 1 : 0 })));
$('#updSave').addEventListener('click', async () => {
  const o = { upd_repo: $('#updRepo').value.trim() };
  const t = $('#updToken').value.trim(); if (t) o.upd_token = t;
  await postJ('settings', formBody(o)); $('#updToken').value = ''; toast('Quelle gespeichert'); loadSettings();
});
['random', 'weather'].forEach(m => $(m === 'random' ? '#bgRandom' : '#bgWeather').addEventListener('click', async () => {
  buzz(); await postJ('settings', formBody({ bg_mode: m })); loadSettings();
}));

let updPoll;
$('#updCheck').addEventListener('click', async () => {
  buzz(); await postJ('update/check');
  clearInterval(updPoll); let n = 0;
  updPoll = setInterval(async () => {
    try { const s = await getJ('settings'); $('#updStatus').textContent = s.updStatus; $('#updVer').textContent = 'Installiert: Skyline ' + s.version; } catch (e) { $('#updStatus').textContent = 'Beamer startet neu …'; }
    if (++n > 90) clearInterval(updPoll);
  }, 1500);
});

// ---------- Start ----------
hub.ready.then(() => {
  paintHead();
  publish();
  if (cur) load_(); else search();
});
