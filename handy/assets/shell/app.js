'use strict';
/*
 * Skyline Hub: Hülle der App. Startseite mit Gerätekarten, Store für Erweiterungen, Einstellungen, untere Leiste.
 * Erweiterungen laufen in iframes mit eigenem Ursprung (http://<id>.ext.hub.local) und sprechen per postMessage
 * mit dieser Hülle. Die Hülle prüft die Rechte aus dem Manifest und reicht erlaubte Aufrufe an Android weiter.
 */

const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const cssUrl = u => `url('${String(u).replace(/['\\\n]/g, '')}')`;

const CATALOG_URL = 'https://github.com/trpwrmusic/skyline/releases/latest/download/extensions.json';
const DOCS_URL = 'https://github.com/trpwrmusic/skyline/blob/main/EXTENSIONS.md';
const MAX_PINS = 3;

// ---------- Symbole (24er Raster, Linien) ----------
const svg = (d, extra = '') => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ${extra}>${d}</svg>`;
const IC = {
  home: svg('<path d="M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z"/>'),
  homeOn: svg('<path d="M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z" fill="currentColor"/>'),
  store: svg('<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><path d="M16.5 13.5v6M13.5 16.5h6"/>'),
  storeOn: svg('<rect x="4" y="4" width="7" height="7" rx="2" fill="currentColor"/><rect x="13" y="4" width="7" height="7" rx="2" fill="currentColor"/><rect x="4" y="13" width="7" height="7" rx="2" fill="currentColor"/><path d="M16.5 13.5v6M13.5 16.5h6"/>'),
  gear: svg('<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>'),
  back: svg('<path d="M15 5l-7 7 7 7"/>'),
  chev: svg('<path d="M9 6l6 6-6 6"/>', 'class="chev"'),
  power: svg('<path d="M12 3v8"/><path d="M6.3 6.8a8 8 0 1 0 11.4 0"/>'),
  plus: svg('<path d="M12 5v14M5 12h14"/>'),
  minus: svg('<path d="M5 12h14"/>'),
  mute: svg('<path d="M4 9.5v5h3.5L12 18V6L7.5 9.5z"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>'),
  volume: svg('<path d="M4 9.5v5h3.5L12 18V6L7.5 9.5z"/><path d="M16 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11"/>'),
  play: svg('<path d="M7 5v14l11-7z"/>'),
  pause: svg('<path d="M8 5v14M16 5v14"/>'),
  playpause: svg('<path d="M4 6v12l8-6zM15 6v12M20 6v12"/>'),
  remote: svg('<rect x="7" y="2.5" width="10" height="19" rx="5"/><circle cx="12" cy="8" r="2"/><path d="M12 13.5v.01M12 16.5v.01"/>'),
  input: svg('<path d="M10 7V5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-2"/><path d="M3 12h11M10 8l4 4-4 4"/>'),
  back2: svg('<path d="M9 14L4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>'),
  homekey: svg('<path d="M4 10.5 12 4l8 6.5V20H4z"/>'),
  check: svg('<path d="M5 12.5l4.5 4.5L19 7"/>'),
  download: svg('<path d="M12 4v11M7 10.5l5 5 5-5M5 20h14"/>'),
  refresh: svg('<path d="M20 11a8 8 0 0 0-14.3-4.5L4 8.5M4 4v4.5h4.5M4 13a8 8 0 0 0 14.3 4.5l1.7-2M20 20v-4.5h-4.5"/>'),
  link: svg('<path d="M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1"/><path d="M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1"/>'),
  zip: svg('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M11 6h1M11 9h1M11 12h1M11 15v2h1v-2z"/>'),
  code: svg('<path d="M8 8l-4 4 4 4M16 8l4 4-4 4M13.5 5l-3 14"/>'),
  info: svg('<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8v.01"/>'),
  tcp: svg('<path d="M5 8h14M5 8l3-3M5 8l3 3M19 16H5M19 16l-3-3M19 16l-3 3"/>'),
  http: svg('<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.8 3 2.8 15 0 18M12 3c-2.8 3-2.8 15 0 18"/>'),
  scan: svg('<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>'),
  mdns: svg('<path d="M12 12v.01"/><path d="M8.5 15.5a5 5 0 0 1 0-7M15.5 8.5a5 5 0 0 1 0 7M5.6 18.4a9 9 0 0 1 0-12.8M18.4 5.6a9 9 0 0 1 0 12.8"/>'),
  shield: svg('<path d="M12 3l7 3v5c0 4.5-3 8.5-7 10-4-1.5-7-5.5-7-10V6z"/><path d="M9 12l2 2 4-4"/>'),
  projector: svg('<rect x="2.5" y="7" width="19" height="10" rx="3"/><circle cx="15.5" cy="12" r="2.5"/><path d="M6 17v2M18 17v2M6 11h4"/>'),
  speaker: svg('<rect x="6" y="3" width="12" height="18" rx="3"/><circle cx="12" cy="14" r="3"/><path d="M12 7.5v.01"/>'),
  tv: svg('<rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 20h8"/>'),
};

const PERMS = {
  tcp: ['tcp', 'Direkte Verbindungen', 'Spricht mit Geräten im WLAN über ihr Steuerprotokoll, etwa Telnet.'],
  http: ['http', 'Webanfragen', 'Ruft Adressen im WLAN und im Internet ab.'],
  scan: ['scan', 'Geräte im WLAN suchen', 'Prüft die Adressen im eigenen Netz auf einen bestimmten Dienst.'],
  mdns: ['mdns', 'Selbst angekündigte Geräte', 'Findet Geräte, die sich im Netz per Bonjour melden.'],
};
const METHOD_PERM = { 'tcp.open': 'tcp', 'tcp.write': 'tcp', 'tcp.close': 'tcp', http: 'http', 'net.scan': 'scan', 'net.mdns': 'mdns' };
const SOURCE_NAME = { bundled: 'Mitgeliefert', file: 'Aus Datei', dev: 'Entwicklung' };

// ---------- Zustand ----------
const S = {
  token: null, info: {},
  exts: new Map(),         // id -> {id, m, base, frame, card, dev}
  bundled: [],             // Manifeste aus der APK
  remote: null,            // Katalog aus dem Internet
  catalogState: 'idle',    // idle | loading | ok | error
  pins: [],
  view: 'home',
  pages: [],               // offene Unterseiten: {kind, arg}
  installing: {},          // id -> Fortschritt 0..1
  sockOwners: new Map(),
  booted: false,
  update: null,            // neuestes Release: {available, code, name, notes, url, size}
  upd: null,               // laufendes Update: {phase: 'download'|'installing'|'confirm', progress}
};
const store = {
  get(k, d) { try { const v = localStorage.getItem('hub.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('hub.' + k, JSON.stringify(v)); } catch {} },
};

// ---------- Verbindung zu Android ----------
const pending = new Map();
let nseq = 0;
function native(method, args = {}, owner = '') {
  return new Promise((res, rej) => {
    if (!window.HubNative || !S.token) return rej(new Error('App nicht bereit'));
    const id = ++nseq;
    pending.set(id, { res, rej });
    let r;
    try { r = JSON.parse(HubNative.call(S.token, JSON.stringify({ id, method, args, owner }))); }
    catch (e) { pending.delete(id); return rej(e); }
    if (!r.async) { pending.delete(id); r.ok ? res(r.result) : rej(new Error(r.error || 'Fehler')); }
  });
}

const Hub = window.Hub = {
  _native(m) {
    if (m.event) return nativeEvent(m);
    const p = pending.get(m.re);
    if (!p) return;
    pending.delete(m.re);
    m.ok ? p.res(m.result) : p.rej(new Error(m.error || 'Fehler'));
  },
  back: () => goBack(),
  link: url => openLink(url),
  lifecycle(state) {
    for (const e of S.exts.values()) send(e, 'app', { state });
    if (state === 'resume' && S.catalogState === 'error') loadCatalog();
    if (state === 'resume' && S.booted && !S.upd && Date.now() - store.get('updAt', 0) > 6 * 3600e3) checkUpdate(false);
  },
};

function nativeEvent(m) {
  if (m.event === 'tcp.data' || m.event === 'tcp.close') {
    const owner = S.sockOwners.get(m.sock) || m.owner;
    if (m.event === 'tcp.close') S.sockOwners.delete(m.sock);
    const e = S.exts.get(owner);
    if (e) send(e, m.event, { sock: m.sock, data: m.data, error: m.error || null });
  } else if (m.event === 'app.update') {
    updateEvent(m);
  } else if (m.event === 'exts.progress') {
    if (m.id && S.installing[m.id] !== undefined) { S.installing[m.id] = m.progress; paintProgress(m.id); }
  }
}

// ---------- Hilfen ----------
function cmpVer(a, b) {
  const pa = String(a || '0').split(/[.-]/).map(n => parseInt(n, 10) || 0), pb = String(b || '0').split(/[.-]/).map(n => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) { const d = (pa[i] || 0) - (pb[i] || 0); if (d) return d > 0 ? 1 : -1; }
  return 0;
}
const size = n => !n ? '' : n < 1e6 ? Math.max(1, Math.round(n / 1e3)) + ' kB' : (n / 1e6).toFixed(1).replace('.', ',') + ' MB';
const haptic = (kind = 'tap') => native('haptic', { kind }).catch(() => {});
function greeting() {
  const h = new Date().getHours();
  return h < 5 ? 'Gute Nacht' : h < 11 ? 'Guten Morgen' : h < 18 ? 'Guten Tag' : 'Guten Abend';
}
const today = () => new Date().toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' });
const iconOf = e => e.iconData || e.base + (e.m.icon || 'icon.svg');
const tileHtml = (url, cls = '') => `<span class="tile ${cls}"><i style="--icon:${cssUrl(url)}"></i></span>`;

// ---------- Katalog ----------
function catalog() {
  const map = new Map();
  for (const b of S.bundled) {
    map.set(b.id, { ...b, icon: `http://hub.local/bundled/${b.id}/${b.icon || 'icon.svg'}`, bundledVersion: b.version, source: 'bundled' });
  }
  for (const r of (S.remote?.extensions || [])) {
    if (!r || !r.id || !/^[a-z0-9][a-z0-9-]{0,30}$/.test(r.id)) continue;
    const b = map.get(r.id);
    if (!b || cmpVer(r.version, b.version) > 0) map.set(r.id, { ...b, ...r, icon: r.iconUrl || b?.icon || '', bundledVersion: b?.bundledVersion, source: 'store' });
    else if (b && r.url) b.url = b.url || r.url;
  }
  return map;
}

async function loadCatalog() {
  S.catalogState = 'loading';
  paintStoreIfOpen();
  const cached = store.get('catalog', null);
  if (cached && !S.remote) S.remote = cached.data;
  try {
    const r = await native('http', { url: store.get('catalogUrl', '') || CATALOG_URL, timeout: 10000 });
    if (r.status !== 200) throw new Error('HTTP ' + r.status);
    const data = JSON.parse(r.body);
    if (!Array.isArray(data.extensions)) throw new Error('Katalog ohne Erweiterungen');
    S.remote = data;
    store.set('catalog', { at: Date.now(), data });
    S.catalogState = 'ok';
  } catch (e) {
    S.catalogState = 'error';
    S.catalogError = e.message;
  }
  renderAll();
}

const updateFor = e => { if (e.dev) return null; const c = catalog().get(e.id); return c && cmpVer(c.version, e.m.version) > 0 ? c : null; };

// ---------- Erweiterungen laden ----------
function addExt(m, reload, dev) {
  let e = S.exts.get(m.id);
  if (!e) { e = { id: m.id, card: null, dev: dev || null }; S.exts.set(m.id, e); }
  e.m = m;
  if (dev) e.dev = dev;
  e.base = e.dev ? e.dev.url : `http://${m.id}.ext.hub.local/`;
  const src = e.base + (m.entry || 'index.html');
  // Entwicklungsserver schicken meist keinen CORS-Header, den die Masken-Grafik bräuchte: Symbol über Android holen
  if (e.dev && !e.iconData) {
    native('http', { url: e.base + (m.icon || 'icon.svg'), timeout: 4000 }).then(r => {
      if (r.status !== 200 || !/<svg/i.test(r.body)) return;
      e.iconData = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(r.body);
      renderAll();
    }).catch(() => {});
  }
  if (!e.frame) {
    const f = document.createElement('iframe');
    f.className = 'view';
    f.dataset.ext = m.id;
    f.setAttribute('allow', 'autoplay; fullscreen; clipboard-write');
    f.setAttribute('title', m.name);
    f.src = src;
    $('#frames').appendChild(f);
    e.frame = f;
  } else if (reload) {
    e.card = null;
    e.frame.src = src + (src.includes('?') ? '&' : '?') + 'v=' + Date.now();
  }
  return e;
}

function dropExt(id) {
  const e = S.exts.get(id);
  if (!e) return;
  native('tcp.closeOwner', { owner: id }).catch(() => {});
  for (const [s, o] of S.sockOwners) if (o === id) S.sockOwners.delete(s);
  e.frame?.remove();
  S.exts.delete(id);
  if (S.view === 'ext:' + id) showView('home');
}

function send(e, event, data) {
  try { e.frame?.contentWindow?.postMessage({ hub: 1, event, data: data || {} }, '*'); } catch {}
}
function reply(e, id, ok, result, error) {
  try { e.frame?.contentWindow?.postMessage({ hub: 1, re: id, ok, result: result ?? null, error }, '*'); } catch {}
}

window.addEventListener('message', ev => {
  let e = null;
  for (const x of S.exts.values()) if (x.frame && x.frame.contentWindow === ev.source) { e = x; break; }
  if (!e) return;
  const m = ev.data;
  if (!m || m.hub !== 1 || typeof m.method !== 'string') return;
  Promise.resolve().then(() => handleExt(e, m)).then(
    r => { if (m.id) reply(e, m.id, true, r); },
    err => { if (m.id) reply(e, m.id, false, null, err?.message || String(err)); },
  );
});

function ownSock(e, sock) { if (S.sockOwners.get(sock) !== e.id) throw new Error('Fremde Verbindung'); }

async function handleExt(e, m) {
  const a = m.args || {};
  const need = METHOD_PERM[m.method];
  if (need && !(e.m.permissions || []).includes(need)) throw new Error(`Das Recht „${need}“ fehlt im Manifest`);
  switch (m.method) {
    case 'hello':
      e.ready = true;
      return { api: 1, ext: { id: e.id, name: e.m.name, version: e.m.version }, app: S.info, visible: S.view === 'ext:' + e.id, foreground: !document.hidden };
    case 'home.set': e.card = cleanCard(a); scheduleHome(); return true;
    case 'home.clear': e.card = null; scheduleHome(); return true;
    case 'nav.open': showView('ext:' + e.id); return true;
    case 'nav.store': showView('store'); if (a.id) openPage('detail', String(a.id)); return true;
    case 'ui.toast': return toastP(String(a.text || '').slice(0, 200), a.action ? String(a.action).slice(0, 24) : null);
    case 'ui.haptic': haptic(String(a.kind || 'tap')); return true;
    case 'ui.openUrl':
      if (!/^(https?|mailto|tel):/i.test(String(a.url))) throw new Error('Nur http, https, mailto und tel');
      return native('openUrl', { url: String(a.url) });
    case 'ui.share': return native('share', { text: String(a.text || '') });
    case 'back.result': backResult(a); return null;
    case 'tcp.open': {
      const r = await native('tcp.open', { host: String(a.host), port: a.port | 0, timeout: a.timeout || 4000, encoding: a.encoding || 'latin1' }, e.id);
      S.sockOwners.set(r.sock, e.id);
      return r;
    }
    case 'tcp.write': ownSock(e, a.sock); return native('tcp.write', { sock: a.sock, data: String(a.data) }, e.id);
    case 'tcp.close': ownSock(e, a.sock); S.sockOwners.delete(a.sock); return native('tcp.close', { sock: a.sock }, e.id);
    case 'http': return native('http', a, e.id);
    case 'net.scan': return native('net.scan', a, e.id);
    case 'net.mdns': return native('net.mdns', a, e.id);
    case 'net.info': return native('net.info');
    default: throw new Error('Unbekannter Aufruf: ' + m.method);
  }
}

function cleanCard(a) {
  const str = (v, n) => (v == null ? '' : String(v).slice(0, n));
  const img = typeof a.image === 'string' && /^(https?:|data:image\/)/.test(a.image) ? a.image : '';
  return {
    title: str(a.title, 80), subtitle: str(a.subtitle, 140), image: img,
    state: a.state && a.state.text ? { text: str(a.state.text, 40), tone: ['ok', 'warn', 'bad', 'off'].includes(a.state.tone) ? a.state.tone : 'off' } : null,
    value: a.value && a.value.text != null ? { text: str(a.value.text, 12), label: str(a.value.label, 30) } : null,
    actions: (Array.isArray(a.actions) ? a.actions : []).slice(0, 4).map(x => ({
      id: str(x.id, 40), label: str(x.label, 24), icon: IC[x.icon] ? x.icon : '', primary: !!x.primary, on: !!x.on, repeat: !!x.repeat,
    })).filter(x => x.id),
  };
}

// ---------- Navigation ----------
function showView(v) {
  if (v.startsWith('ext:') && !S.exts.has(v.slice(4))) v = 'home';
  const prev = S.view;
  if (S.pages.length) closeAllPages();
  S.view = v;
  document.querySelectorAll('#views > section.view:not(.page)').forEach(s => s.classList.toggle('on', s.dataset.view === v));
  for (const e of S.exts.values()) {
    const on = v === 'ext:' + e.id;
    e.frame.classList.toggle('on', on);
    if (on !== (prev === 'ext:' + e.id)) send(e, 'visibility', { visible: on });
  }
  if (v === 'store') { renderStore(); if (S.catalogState === 'idle' || S.catalogState === 'error') loadCatalog(); }
  if (v === 'home') renderHome();
  const el = v.startsWith('ext:') ? null : document.querySelector(`[data-view="${v}"]`);
  if (el && prev !== v) { el.classList.remove('fade'); void el.offsetWidth; el.classList.add('fade'); }
  renderNav();
}

function renderNav() {
  const items = [{ key: 'home', label: 'Start', icon: S.view === 'home' ? IC.homeOn : IC.home }];
  for (const id of S.pins) {
    const e = S.exts.get(id);
    if (!e) continue;
    items.push({ key: 'ext:' + id, label: e.m.tab?.label || e.m.name, url: iconOf(e) });
  }
  const updates = [...S.exts.values()].some(updateFor);
  items.push({ key: 'store', label: 'Store', icon: S.view === 'store' ? IC.storeOn : IC.store, dot: updates });
  const html = items.map(it => `
    <button class="nav-item ${S.view === it.key ? 'on' : ''}" data-nav="${esc(it.key)}" aria-label="${esc(it.label)}">
      <span class="nav-ind">${it.url ? `<i class="nav-ic" style="--icon:${cssUrl(it.url)}"></i>` : it.icon}${it.dot ? '<span class="nav-dot"></span>' : ''}</span>
      <span class="nav-label">${esc(it.label)}</span>
    </button>`).join('');
  const nav = $('#navbar');
  if (nav.dataset.html !== html) { nav.innerHTML = html; nav.dataset.html = html; }
}

$('#navbar').addEventListener('click', ev => {
  const b = ev.target.closest('[data-nav]');
  if (!b) return;
  haptic('tick');
  const v = b.dataset.nav;
  if (v === S.view && !S.pages.length) {
    const el = document.querySelector(`[data-view="${v}"]`);
    if (el) el.scrollTo({ top: 0, behavior: 'smooth' });
    return;
  }
  showView(v);
});

// Unterseiten (Details, Einstellungen) liegen über dem aktuellen Bereich
function openPage(kind, arg) {
  S.pages.push({ kind, arg });
  renderPage();
  const p = $('#v-page');
  p.hidden = false;
  p.classList.remove('out');
  p.classList.add('on');
  p.scrollTop = 0;
}
function closePage() {
  S.pages.pop();
  const p = $('#v-page');
  if (S.pages.length) { renderPage(); p.scrollTop = 0; return; }
  p.classList.remove('on');
  p.classList.add('out');
  setTimeout(() => { if (!S.pages.length) { p.classList.remove('out'); p.hidden = true; } }, 180);
  if (S.view === 'store') renderStore();
  if (S.view === 'home') renderHome();
}
function closeAllPages() { S.pages = []; const p = $('#v-page'); p.classList.remove('on', 'out'); p.hidden = true; }

let backWait = null, backSeq = 0;
function goBack() {
  if (!$('#toast').hidden && toastResolve) { hideToast(false); }
  if (S.pages.length) return closePage();
  if (S.view.startsWith('ext:')) {
    const e = S.exts.get(S.view.slice(4));
    const seq = ++backSeq;
    clearTimeout(backWait?.timer);
    backWait = { seq, timer: setTimeout(() => { backWait = null; showView('home'); }, 400) };
    send(e, 'back', { seq });
    return;
  }
  if (S.view !== 'home') return showView('home');
  native('exit').catch(() => {});
}
function backResult(a) {
  if (!backWait || a.seq !== backWait.seq) return;
  clearTimeout(backWait.timer);
  backWait = null;
  if (!a.handled) showView('home');
}

// ---------- Startseite ----------
let homeRaf = 0;
function scheduleHome() { if (!homeRaf) homeRaf = requestAnimationFrame(() => { homeRaf = 0; if (S.view === 'home') renderHome(); }); }

function cardHtml(e) {
  const c = e.card;
  const name = e.m.tab?.label || e.m.name;
  const top = `<div class="dc-top"><span class="dc-ic"><i style="--icon:${cssUrl(iconOf(e))}"></i></span><span class="dc-name">${esc(name)}</span>
    ${c?.state ? `<span class="h-pill ${c.state.tone === 'off' ? '' : c.state.tone}">${esc(c.state.text)}</span>` : ''}</div>`;
  if (!c) {
    return `<article class="dcard dc-skel" data-card="${esc(e.id)}">${top}
      <div class="h-skel" style="width:62%;height:22px"></div><div class="h-skel" style="width:40%;margin-top:10px"></div></article>`;
  }
  const actions = c.actions.map(a => {
    const cls = a.label && !a.icon ? `h-btn ${a.primary ? 'primary' : ''}` : a.label ? `h-btn ${a.primary ? 'primary' : ''}` : `h-round ${a.on ? 'on' : ''}`;
    return `<button class="${cls}" data-act="${esc(a.id)}" ${a.repeat ? 'data-repeat' : ''} aria-label="${esc(a.label || a.id)}">${a.icon ? IC[a.icon] : ''}${a.label ? `<span>${esc(a.label)}</span>` : ''}</button>`;
  }).join('');
  return `<article class="dcard tap ${c.image ? 'photo' : ''}" data-card="${esc(e.id)}" ${c.image ? `style="background-image:${cssUrl(c.image)}"` : ''}>
    ${top}
    <h2 class="dc-title">${esc(c.title)}</h2>
    ${c.subtitle ? `<p class="dc-sub">${esc(c.subtitle)}</p>` : ''}
    ${c.value ? `<div class="dc-value"><b>${esc(c.value.text)}</b><span>${esc(c.value.label)}</span></div>` : ''}
    ${actions ? `<div class="dc-actions">${actions}</div>` : ''}
  </article>`;
}

function renderHome() {
  const el = $('#v-home');
  const exts = [...S.exts.values()].filter(e => e.m.home !== false);
  const ordered = [...S.pins.map(id => S.exts.get(id)).filter(e => e && e.m.home !== false), ...exts.filter(e => !S.pins.includes(e.id))];
  const cat = catalog();
  const suggestions = [...cat.values()].filter(c => !S.exts.has(c.id));
  let body;
  if (!S.booted) {
    body = '<div class="boot"><div class="h-spin"></div></div>';
  } else if (!ordered.length) {
    body = `<div class="welcome">
      <div class="glyphs"><span>${IC.projector}</span><span>${IC.speaker}</span><span>${IC.tv}</span></div>
      <h2>Beamer, Receiver und mehr an einem Ort</h2>
      <p>Jedes Gerät kommt als Erweiterung aus dem Store. Seine Karte erscheint dann hier.</p>
      <button class="h-btn primary" data-go="store">Store öffnen</button></div>`;
  } else {
    body = `<div class="dcards">${ordered.map(cardHtml).join('')}</div>`;
  }
  const sugg = S.booted && suggestions.length ? `
    <h2 class="h-section">Für dein Zuhause</h2>
    <div class="rail">${suggestions.map(c => `
      <div class="sugg" data-detail="${esc(c.id)}">
        ${tileHtml(c.icon, 'small')}
        <b>${esc(c.name)}</b><p>${esc(c.summary || c.description || '')}</p>
        <button class="h-btn small" data-install="${esc(c.id)}">${S.installing[c.id] !== undefined ? 'Lädt …' : 'Hinzufügen'}</button>
      </div>`).join('')}</div>` : '';
  const html = `
    <header class="head">
      <div><h1>${greeting()}</h1><p>${today()}</p></div>
      <button class="h-icon" data-settings aria-label="Einstellungen">${IC.gear}</button>
    </header>${updateCardHtml()}${body}${sugg}`;
  if (el.dataset.html !== html) { el.innerHTML = html; el.dataset.html = html; }
}

// Aktionen auf Karten (gedrückt halten wiederholt, z. B. Lautstärke)
let repeatT = 0, repeatI = 0, repeated = false;
function stopRepeat() { clearTimeout(repeatT); clearInterval(repeatI); }
$('#v-home').addEventListener('pointerdown', ev => {
  const b = ev.target.closest('[data-act][data-repeat]');
  if (!b) return;
  const id = b.closest('[data-card]').dataset.card, act = b.dataset.act;
  repeated = false;
  stopRepeat();
  repeatT = setTimeout(() => { repeated = true; repeatI = setInterval(() => { const e = S.exts.get(id); if (e) { send(e, 'home.action', { id: act, repeat: true }); haptic('tick'); } }, 160); }, 420);
});
['pointerup', 'pointercancel', 'pointerleave'].forEach(t => $('#v-home').addEventListener(t, stopRepeat));

document.addEventListener('click', ev => {
  const t = ev.target.closest('button, [data-detail], [data-card]');
  if (!t) return;
  if (t.matches('[data-act]')) {
    const e = S.exts.get(t.closest('[data-card]').dataset.card);
    if (!e) return;
    if (t.hasAttribute('data-repeat') && repeated) { repeated = false; return; }
    haptic();
    t.animate([{ transform: 'scale(.9)' }, { transform: 'scale(1)' }], { duration: 180, easing: 'ease-out' });
    send(e, 'home.action', { id: t.dataset.act });
    return;
  }
  if (t.dataset.go) { haptic('tick'); return showView(t.dataset.go); }
  if (t.hasAttribute('data-settings')) { haptic('tick'); return openPage('settings'); }
  if (t.hasAttribute('data-back')) { haptic('tick'); return closePage(); }
  if (t.dataset.install) { ev.stopPropagation(); return install(t.dataset.install); }
  if (t.dataset.update) { ev.stopPropagation(); return install(t.dataset.update); }
  if (t.dataset.open) { ev.stopPropagation(); haptic('tick'); return showView('ext:' + t.dataset.open); }
  if (t.dataset.remove) return remove(t.dataset.remove);
  if (t.dataset.pin) return togglePin(t.dataset.pin);
  if (t.dataset.detail) { haptic('tick'); return openPage('detail', t.dataset.detail); }
  if (t.dataset.card && S.exts.has(t.dataset.card)) {
    const e = S.exts.get(t.dataset.card);
    haptic('tick');
    if (S.pins.includes(e.id) || e.m.tab) return showView('ext:' + e.id);
    return showView('ext:' + e.id);
  }
  if (t.dataset.act2) return pageAction(t.dataset.act2, t);
});

// ---------- Store ----------
function installedRow(e) {
  const up = updateFor(e);
  const busy = S.installing[e.id] !== undefined;
  const src = e.dev ? 'Entwicklung' : 'Version ' + e.m.version;
  const where = S.pins.includes(e.id) ? 'In der Leiste' : e.m.home !== false ? 'Auf der Startseite' : '';
  const btn = busy ? `<button class="h-btn small prog" data-prog="${esc(e.id)}" disabled>Lädt …</button>`
    : up ? `<button class="h-btn small primary" data-update="${esc(e.id)}">Aktualisieren</button>`
    : `<button class="h-btn small" data-open="${esc(e.id)}">Öffnen</button>`;
  return `<div class="item" data-detail="${esc(e.id)}">${tileHtml(iconOf(e))}
    <span class="txt"><b>${esc(e.m.name)}</b><span>${esc([src, where].filter(Boolean).join(' · '))}</span></span>${btn}</div>`;
}

function availRow(c) {
  const busy = S.installing[c.id] !== undefined;
  return `<div class="item" data-detail="${esc(c.id)}">${tileHtml(c.icon)}
    <span class="txt"><b>${esc(c.name)}</b><span>${esc(c.summary || c.description || '')}</span></span>
    ${busy ? `<button class="h-btn small prog" data-prog="${esc(c.id)}" disabled>Lädt …</button>` : `<button class="h-btn small" data-install="${esc(c.id)}">Hinzufügen</button>`}</div>`;
}

function renderStore() {
  const el = $('#v-store');
  const cat = catalog();
  const avail = [...cat.values()].filter(c => !S.exts.has(c.id));
  const installed = [...S.exts.values()].sort((a, b) => a.m.name.localeCompare(b.m.name, 'de'));
  const feat = avail.find(c => c.featured) || avail[0];
  const rest = avail.filter(c => c !== feat);
  const state = S.catalogState === 'error'
    ? `<div class="note">${IC.info}<span>Der Store ist gerade nicht erreichbar. Mitgelieferte Erweiterungen lassen sich trotzdem hinzufügen.</span><button class="h-btn small" data-act2="catalog">Erneut</button></div>`
    : '';
  const html = `
    <header class="head"><div><h1>Store</h1><p>${installed.length ? `${installed.length} ${installed.length === 1 ? 'Erweiterung' : 'Erweiterungen'} hinzugefügt` : 'Erweiterungen für deine Geräte'}</p></div>
      <button class="h-icon" data-settings aria-label="Einstellungen">${IC.gear}</button></header>
    ${feat ? `<div class="feat" data-detail="${esc(feat.id)}">
        <div class="row1">${tileHtml(feat.icon, 'big')}<div><small>${esc(feat.author || 'Skyline')}</small><h2>${esc(feat.name)}</h2></div></div>
        <p>${esc(feat.description || feat.summary || '')}</p>
        ${S.installing[feat.id] !== undefined ? `<button class="h-btn primary wide prog" data-prog="${esc(feat.id)}" disabled>Lädt …</button>` : `<button class="h-btn primary wide" data-install="${esc(feat.id)}">Hinzufügen</button>`}
      </div>` : ''}
    ${state}
    ${rest.length ? `<h2 class="h-section">Weitere</h2><div class="list">${rest.map(availRow).join('')}</div>` : ''}
    ${installed.length ? `<h2 class="h-section">Hinzugefügt</h2><div class="list">${installed.map(installedRow).join('')}</div>` : ''}
    ${!avail.length && S.catalogState !== 'loading' ? `<div class="note">${IC.check}<span>Alle verfügbaren Erweiterungen sind hinzugefügt.</span></div>` : ''}
    ${S.catalogState === 'loading' && !S.remote ? '<div class="note"><div class="h-spin"></div><span>Store wird geladen</span></div>' : ''}`;
  if (el.dataset.html !== html) { el.innerHTML = html; el.dataset.html = html; }
}

function paintStoreIfOpen() { if (S.view === 'store') renderStore(); }
function paintProgress(id) {
  const p = Math.round((S.installing[id] || 0) * 100);
  document.querySelectorAll(`[data-prog="${CSS.escape(id)}"]`).forEach(b => { b.textContent = `Lädt ${p} %`; b.style.setProperty('--p', p + '%'); });
}
function renderAll() {
  renderNav();
  if (S.view === 'home') renderHome();
  if (S.view === 'store') renderStore();
  if (S.pages.length) renderPage();
}

async function install(id) {
  const c = catalog().get(id);
  if (!c || S.installing[id] !== undefined) return;
  haptic();
  S.installing[id] = 0;
  renderAll();
  const wasInstalled = S.exts.has(id);
  let m;
  try {
    const useStore = c.url && (!c.bundledVersion || cmpVer(c.version, c.bundledVersion) > 0);
    if (useStore) {
      try { m = await native('exts.installUrl', { url: c.url, sha256: c.sha256 || '', id }); }
      catch (err) { if (!c.bundledVersion) throw err; m = await native('exts.installAsset', { id }); }
    } else {
      m = await native('exts.installAsset', { id });
    }
  } catch (err) {
    delete S.installing[id];
    renderAll();
    toast(`${c.name} ließ sich nicht hinzufügen: ${err.message}`, 'Erneut', () => install(id));
    return;
  }
  delete S.installing[id];
  addExt(m, wasInstalled);
  if (!wasInstalled && m.tab && S.pins.length < MAX_PINS && !S.pins.includes(id)) { S.pins.push(id); store.set('pins', S.pins); }
  renderAll();
  haptic('long');
  toast(wasInstalled ? `${m.name} ist jetzt auf Version ${m.version}` : `${m.name} hinzugefügt`, 'Öffnen', () => showView('ext:' + id));
}

async function remove(id) {
  const e = S.exts.get(id);
  if (!e) return;
  const name = e.m.name, pinAt = S.pins.indexOf(id), dev = e.dev;
  haptic();
  try {
    if (dev) store.set('dev', store.get('dev', []).filter(d => d.url !== dev.url));
    else await native('exts.remove', { id });
  } catch (err) { return toast(`${name} ließ sich nicht entfernen: ${err.message}`); }
  dropExt(id);
  if (pinAt >= 0) { S.pins.splice(pinAt, 1); store.set('pins', S.pins); }
  if (S.pages.length && S.pages[S.pages.length - 1].arg === id) closePage();
  renderAll();
  const undo = await toastP(`${name} entfernt`, 'Rückgängig');
  if (!undo) return;
  try {
    if (dev) { store.set('dev', [...store.get('dev', []), dev]); addExt(dev.manifest, false, dev); }
    else addExt(await native('exts.restore', { id }));
    if (pinAt >= 0 && !S.pins.includes(id)) { S.pins.splice(Math.min(pinAt, S.pins.length), 0, id); store.set('pins', S.pins); }
    renderAll();
  } catch (err) { toast(`Wiederherstellen ging nicht: ${err.message}`); }
}

function togglePin(id) {
  haptic('tick');
  const i = S.pins.indexOf(id);
  if (i >= 0) S.pins.splice(i, 1);
  else {
    if (S.pins.length >= MAX_PINS) { toast(`In der Leiste ist Platz für ${MAX_PINS} Erweiterungen. Nimm zuerst eine heraus.`); return; }
    S.pins.push(id);
  }
  store.set('pins', S.pins);
  renderAll();
}

// ---------- Unterseiten ----------
function renderPage() {
  const top = S.pages[S.pages.length - 1];
  if (!top) return;
  const html = top.kind === 'detail' ? detailHtml(top.arg) : settingsHtml();
  const p = $('#v-page');
  if (p.dataset.html !== html) { p.innerHTML = html; p.dataset.html = html; }
}

function detailHtml(id) {
  const e = S.exts.get(id);
  const c = catalog().get(id);
  const m = e ? e.m : c;
  if (!m) return `<div class="back-row"><button class="h-icon" data-back aria-label="Zurück">${IC.back}</button></div><div class="note">${IC.info}<span>Diese Erweiterung gibt es nicht mehr.</span></div>`;
  const icon = e ? iconOf(e) : c.icon;
  const up = e ? updateFor(e) : null;
  const busy = S.installing[id] !== undefined;
  let btns;
  if (busy) btns = `<button class="h-btn primary prog" data-prog="${esc(id)}" disabled>Lädt …</button>`;
  else if (!e) btns = `<button class="h-btn primary" data-install="${esc(id)}">Hinzufügen</button>`;
  else if (up) btns = `<button class="h-btn primary" data-update="${esc(id)}">Auf ${esc(up.version)} aktualisieren</button><button class="h-btn" data-open="${esc(id)}">Öffnen</button>`;
  else btns = `<button class="h-btn primary" data-open="${esc(id)}">Öffnen</button><button class="h-btn danger" data-remove="${esc(id)}">Entfernen</button>`;
  const perms = (m.permissions || []).filter(p => PERMS[p]);
  const src = e ? (e.dev ? 'dev' : e.m.installed) : (c.source === 'store' ? 'store' : 'bundled');
  const srcName = SOURCE_NAME[src] || 'Store';
  return `
    <div class="back-row"><button class="h-icon" data-back aria-label="Zurück">${IC.back}</button></div>
    <div class="det">
      ${tileHtml(icon, 'big')}
      <div><h1>${esc(m.name)}</h1><p class="by">${esc(m.author || 'Skyline')}</p></div>
      <div class="btns">${btns}</div>
      ${up && e ? `<button class="h-btn small danger" data-remove="${esc(id)}">Entfernen</button>` : ''}
      <p class="desc">${esc(m.description || m.summary || '')}</p>
      <div class="facts">
        <div><small>Version</small><b>${esc(e ? e.m.version : m.version)}</b></div>
        <div><small>Quelle</small><b>${esc(srcName)}</b></div>
        ${c?.size ? `<div><small>Größe</small><b>${esc(size(c.size))}</b></div>` : `<div><small>Zugriffe</small><b>${perms.length || 'keine'}</b></div>`}
      </div>
    </div>
    ${e && m.tab ? `<h2 class="h-section">Anzeige</h2><div class="group">
      <button class="set" data-pin="${esc(id)}"><span class="txt"><b>In der Leiste unten</b><span>Eigener Platz neben Start und Store</span></span><span class="h-switch ${S.pins.includes(id) ? 'on' : ''}"></span></button></div>` : ''}
    <h2 class="h-section">Zugriffe</h2>
    <div class="group">${perms.length ? perms.map(p => `<div class="perm"><span class="ic">${IC[PERMS[p][0]]}</span><span><b>${PERMS[p][1]}</b><small>${PERMS[p][2]}</small></span></div>`).join('')
      : `<div class="perm"><span class="ic">${IC.shield}</span><span><b>Keine besonderen Zugriffe</b><small>Läuft für sich, ohne Verbindung zu Geräten.</small></span></div>`}</div>
    <p class="h-muted h-small" style="margin:14px 4px 0">Jede Erweiterung läuft getrennt von der App und von anderen Erweiterungen.</p>`;
}

function settingsHtml() {
  const dev = store.get('devmode', false);
  const pinnable = [...S.exts.values()].filter(e => e.m.tab);
  return `
    <div class="back-row"><button class="h-icon" data-back aria-label="Zurück">${IC.back}</button><h2>Einstellungen</h2></div>
    ${pinnable.length ? `<h2 class="h-section">Leiste unten</h2><div class="group">${pinnable.map(e => `
      <button class="set" data-pin="${esc(e.id)}">${tileHtml(iconOf(e), 'small')}<span class="txt"><b>${esc(e.m.tab?.label || e.m.name)}</b><span>${S.pins.includes(e.id) ? 'Platz ' + (S.pins.indexOf(e.id) + 1) : 'Nur über Start und Store'}</span></span>
      <span class="h-switch ${S.pins.includes(e.id) ? 'on' : ''}"></span></button>`).join('')}</div>
      <p class="h-muted h-small" style="margin:10px 4px 0">Bis zu ${MAX_PINS} Erweiterungen passen neben Start und Store.</p>` : ''}
    <h2 class="h-section">Erweiterungen entwickeln</h2>
    <div class="group">
      <button class="set" data-act2="devmode"><span class="txt"><b>Entwicklermodus</b><span>Erweiterungen von einem eigenen Server oder aus einer ZIP-Datei</span></span><span class="h-switch ${dev ? 'on' : ''}"></span></button>
      ${dev ? `<div class="dev-form">
        <label class="h-label" for="devUrl">Adresse des Entwicklungsservers</label>
        <div class="line"><input class="h-field" id="devUrl" placeholder="http://192.168.0.10:5500/" inputmode="url" autocapitalize="off" autocomplete="off" spellcheck="false" value="${esc(store.get('devLast', ''))}">
        <button class="h-btn" data-act2="devload">Laden</button></div>
        <button class="h-btn" data-act2="zip">${IC.zip}<span>ZIP-Datei installieren</span></button>
        <label class="h-label" for="catUrl" style="margin-top:6px">Adresse des Katalogs</label>
        <div class="line"><input class="h-field" id="catUrl" placeholder="${esc(CATALOG_URL)}" inputmode="url" autocapitalize="off" autocomplete="off" spellcheck="false" value="${esc(store.get('catalogUrl', ''))}">
        <button class="h-btn" data-act2="catsave">Sichern</button></div>
      </div>` : ''}
      <button class="set" data-act2="docs"><span class="txt"><b>Anleitung für Erweiterungen</b><span>Manifest, Rechte und Schnittstelle</span></span>${IC.chev}</button>
    </div>
    <h2 class="h-section">Über</h2>
    <div class="group">
      <div class="set"><span class="txt"><b>Skyline ${esc(S.info.version || '')}</b><span>Build ${esc(S.info.versionCode || '')} · ${esc(S.info.device || '')}</span></span></div>
      ${S.update?.available
        ? `<div class="set"><span class="txt"><b>Neue Version: Build ${esc(S.update.code)}</b><span>${esc(S.update.notes || '')}</span></span><button class="h-btn small primary prog" data-act2="update" data-updbtn ${S.upd ? 'disabled' : ''}>${updLabel()}</button></div>`
        : `<button class="set" data-act2="updcheck"><span class="txt"><b>Nach Updates suchen</b><span>${store.get('updAt', 0) ? 'Zuletzt geprüft ' + new Date(store.get('updAt', 0)).toLocaleString('de-DE', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : 'Prüft beim Start automatisch'}</span></span>${IC.refresh}</button>`}
      <button class="set" data-act2="source"><span class="txt"><b>Quellcode</b><span>github.com/trpwrmusic/skyline</span></span>${IC.chev}</button>
    </div>`;
}

async function pageAction(act, btn) {
  haptic('tick');
  if (act === 'catalog') return loadCatalog();
  if (act === 'update') return runUpdate();
  if (act === 'updcheck') return checkUpdate(true);
  if (act === 'devmode') { store.set('devmode', !store.get('devmode', false)); return renderPage(); }
  if (act === 'docs') return native('openUrl', { url: DOCS_URL });
  if (act === 'source') return native('openUrl', { url: 'https://github.com/trpwrmusic/skyline' });
  if (act === 'catsave') {
    const v = $('#catUrl').value.trim();
    if (v && !/^https?:\/\//.test(v)) return toast('Die Adresse muss mit http:// oder https:// beginnen');
    store.set('catalogUrl', v);
    toast(v ? 'Katalog-Adresse gesichert' : 'Standard-Katalog wird verwendet');
    return loadCatalog();
  }
  if (act === 'zip') {
    try {
      const m = await native('exts.pickZip');
      const was = S.exts.has(m.id);
      addExt(m, was);
      if (!was && m.tab && S.pins.length < MAX_PINS) { S.pins.push(m.id); store.set('pins', S.pins); }
      renderAll();
      toast(`${m.name} ${m.version} installiert`, 'Öffnen', () => showView('ext:' + m.id));
    } catch (err) { if (err.message !== 'Abgebrochen') toast('ZIP-Datei ließ sich nicht installieren: ' + err.message); }
    return;
  }
  if (act === 'devload') {
    let url = $('#devUrl').value.trim();
    if (!/^https?:\/\//.test(url)) url = 'http://' + url;
    if (!url.endsWith('/')) url += '/';
    store.set('devLast', url);
    btn.disabled = true;
    btn.textContent = 'Lädt …';
    try {
      const r = await native('http', { url: url + 'manifest.json', timeout: 5000 });
      if (r.status !== 200) throw new Error('manifest.json fehlt (HTTP ' + r.status + ')');
      const m = JSON.parse(r.body);
      if (!m.id || !/^[a-z0-9][a-z0-9-]{0,30}$/.test(m.id) || !m.name) throw new Error('Manifest braucht id und name');
      const ex = S.exts.get(m.id);
      if (ex && !ex.dev) throw new Error(`„${m.id}“ ist schon installiert. Erst im Store entfernen.`);
      const d = { url, manifest: m };
      store.set('dev', [...store.get('dev', []).filter(x => x.manifest?.id !== m.id), d]);
      addExt(m, !!ex, d);
      if (m.tab && !S.pins.includes(m.id) && S.pins.length < MAX_PINS) { S.pins.push(m.id); store.set('pins', S.pins); }
      renderAll();
      toast(`${m.name} läuft aus ${url}`, 'Öffnen', () => showView('ext:' + m.id));
    } catch (err) {
      toast('Laden ging nicht: ' + err.message);
    }
    btn.disabled = false;
    btn.textContent = 'Laden';
  }
}

// ---------- Selbst-Update der App (GitHub-Releases) ----------
async function checkUpdate(manual) {
  try {
    S.update = await native('app.update.check');
    store.set('updAt', Date.now());
    if (manual && !S.update.available) toast(`Skyline ist aktuell (Build ${S.info.versionCode})`);
  } catch (err) {
    if (manual) toast('Update-Prüfung ging nicht: ' + err.message, 'Erneut', () => checkUpdate(true));
  }
  renderAll();
}

async function runUpdate() {
  if (!S.update?.available || S.upd) return;
  haptic();
  S.upd = { phase: 'download', progress: 0 };
  renderAll();
  try {
    await native('app.update.install', { url: S.update.url });
  } catch (err) {
    S.upd = null;
    renderAll();
    toast('Update ging nicht: ' + err.message, 'Erneut', runUpdate);
  }
}

function updateEvent(m) {
  if (m.phase === 'download' || m.phase === 'installing' || m.phase === 'confirm') {
    S.upd = { phase: m.phase, progress: m.progress || 0 };
    if (m.phase === 'download') return paintUpdate();
  } else if (m.phase === 'aborted') {
    S.upd = null;
    toast('Update abgebrochen. Die bisherige Version läuft weiter.');
  } else if (m.phase === 'error') {
    S.upd = null;
    toast('Update ging nicht: ' + (m.error || 'unbekannter Fehler'), 'Erneut', runUpdate);
  }
  renderAll();
}

const updLabel = () => !S.upd ? 'Aktualisieren'
  : S.upd.phase === 'download' ? `Lädt ${Math.round(S.upd.progress * 100)} %`
  : S.upd.phase === 'installing' ? 'Installiert …' : 'Bitte bestätigen';
function paintUpdate() {
  document.querySelectorAll('[data-updbtn]').forEach(b => { b.textContent = updLabel(); b.style.setProperty('--p', Math.round((S.upd?.progress || 0) * 100) + '%'); });
}
function updateCardHtml() {
  const u = S.update;
  if (!u?.available) return '';
  return `<div class="upd">
    <span class="upd-ic">${IC.download}</span>
    <span class="txt"><b>Neue App-Version</b><span>${esc(u.notes || u.name)}</span></span>
    <button class="h-btn small primary prog" data-act2="update" data-updbtn ${S.upd ? 'disabled' : ''} style="--p:${Math.round((S.upd?.progress || 0) * 100)}%">${updLabel()}</button>
  </div>`;
}

// ---------- Meldung ----------
let toastTimer = 0, toastResolve = null;
function hideToast(result) {
  clearTimeout(toastTimer);
  $('#toast').hidden = true;
  const r = toastResolve;
  toastResolve = null;
  if (r) r(result);
}
function toastP(text, action) {
  return new Promise(res => {
    if (toastResolve) hideToast(false);
    toastResolve = res;
    $('#toastText').textContent = text;
    const b = $('#toastAction');
    b.hidden = !action;
    b.textContent = action || '';
    const t = $('#toast');
    t.hidden = false;
    t.style.animation = 'none'; void t.offsetWidth; t.style.animation = '';
    toastTimer = setTimeout(() => hideToast(false), action ? 6000 : 3500);
  });
}
function toast(text, action, fn) { toastP(text, action).then(ok => { if (ok && fn) fn(); }); }
$('#toastAction').addEventListener('click', () => { haptic('tick'); hideToast(true); });

// ---------- Links von außen (QR-Code am Beamer) ----------
async function openLink(url) {
  const find = () => [...S.exts.values()].find(e => (e.m.links || []).some(p => url.startsWith(p)));
  let e = find();
  if (!e) {
    const c = [...catalog().values()].find(x => (x.links || []).some(p => url.startsWith(p)));
    if (c) { await install(c.id); e = find(); }
  }
  if (!e) return toast('Für diesen Link ist keine Erweiterung da.');
  showView('ext:' + e.id);
  const deliver = () => send(e, 'link', { url });
  if (e.ready) deliver(); else setTimeout(deliver, 900);
}

// ---------- Start ----------
window.HubBoot = async (token, info) => {
  if (S.token) return;
  S.token = token;
  S.info = info || {};
  const pinsStored = store.get('pins', null);
  S.pins = pinsStored || [];
  try {
    S.bundled = await native('exts.bundled');
    let list = await native('exts.list');
    // Erster Start: mitgelieferte Erweiterungen mit "preinstall" gleich hinzufügen
    if (!store.get('init', false)) {
      for (const b of S.bundled.filter(x => x.preinstall)) {
        if (list.some(x => x.id === b.id)) continue;
        try {
          const m = await native('exts.installAsset', { id: b.id });
          list.push(m);
          if (m.tab && S.pins.length < MAX_PINS && !S.pins.includes(m.id)) S.pins.push(m.id);
        } catch (err) { console.warn('Vorinstallation', b.id, err); }
      }
      store.set('init', true);
      store.set('pins', S.pins);
    }
    // Mitgelieferte Erweiterungen mit der App aktualisieren
    for (let i = 0; i < list.length; i++) {
      const x = list[i];
      const b = S.bundled.find(y => y.id === x.id);
      if (b && x.installed === 'bundled' && (cmpVer(b.version, x.version) > 0 || (cmpVer(b.version, x.version) === 0 && b.build && b.build !== x.build))) {
        try { list[i] = await native('exts.installAsset', { id: x.id }); } catch {}
      }
    }
    for (const m of list) addExt(m);
    for (const d of store.get('dev', [])) if (d?.manifest?.id && !S.exts.has(d.manifest.id)) addExt(d.manifest, false, d);
  } catch (err) {
    console.error(err);
  }
  // Noch nie eine Leiste gespeichert (erster Start dieser Fassung): Erweiterungen mit eigener Seite einsortieren
  if (pinsStored === null) for (const e of S.exts.values()) if (e.m.tab && !S.pins.includes(e.id) && S.pins.length < MAX_PINS) S.pins.push(e.id);
  S.pins = S.pins.filter(id => S.exts.has(id));
  store.set('pins', S.pins);
  const cached = store.get('catalog', null);
  if (cached) S.remote = cached.data;
  S.booted = true;
  showView('home');
  setTimeout(loadCatalog, 1200);
  setTimeout(() => checkUpdate(false), 2500);
};

// Erste Zeichnung, bevor Android den Schlüssel liefert
renderHome();
renderNav();
document.querySelector('[data-view="home"]').classList.add('on');
setInterval(() => { if (S.view === 'home' && !S.pages.length) renderHome(); }, 60000);
