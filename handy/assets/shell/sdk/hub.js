/*
 * Skyline Hub SDK für Erweiterungen (API-Stufe 1).
 * Einbinden mit <script src="http://hub.local/sdk/hub.js"></script>, danach steht window.hub bereit.
 * Die Erweiterung läuft in einem eigenen Ursprung; alle Systemzugriffe gehen als Nachricht an die App,
 * die sie nur erlaubt, wenn das Manifest das passende Recht nennt ("tcp", "http", "scan", "mdns").
 * Anleitung: EXTENSIONS.md im Repository trpwrmusic/skyline.
 */
(() => {
  if (window.hub) return;
  const pending = new Map();
  const handlers = {};
  const socks = new Map();
  let seq = 0;
  const inHub = window.parent !== window;

  const post = m => window.parent.postMessage(Object.assign({ hub: 1 }, m), '*');

  function call(method, args) {
    if (!inHub) return Promise.reject(new Error('Läuft nicht in der Skyline-App'));
    return new Promise((res, rej) => {
      const id = ++seq;
      pending.set(id, { res, rej });
      post({ id, method, args: args || {} });
    });
  }

  function emit(name, data) {
    for (const fn of (handlers[name] || []).slice()) {
      try { fn(data); } catch (e) { console.error(e); }
    }
  }

  class Socket {
    constructor(id) { this.id = id; this.open = true; this._h = { data: [], close: [] }; socks.set(id, this); }
    /** 'data' (Text in der gewählten Kodierung) oder 'close' (Fehlertext oder null) */
    on(ev, fn) { if (this._h[ev]) this._h[ev].push(fn); return this; }
    write(text) { if (!this.open) return Promise.reject(new Error('Verbindung ist geschlossen')); return call('tcp.write', { sock: this.id, data: String(text) }); }
    close() { if (!this.open) return; this.open = false; socks.delete(this.id); call('tcp.close', { sock: this.id }).catch(() => {}); this._fire(null); }
    _data(d) { for (const f of this._h.data) { try { f(d); } catch (e) { console.error(e); } } }
    _fire(err) { const l = this._h.close; this._h.close = []; for (const f of l) { try { f(err); } catch (e) { console.error(e); } } }
    _closed(err) { this.open = false; socks.delete(this.id); this._fire(err || null); }
  }

  window.addEventListener('message', e => {
    if (e.source !== window.parent) return;
    const m = e.data;
    if (!m || m.hub !== 1) return;
    if (m.re) {
      const p = pending.get(m.re);
      if (!p) return;
      pending.delete(m.re);
      if (m.ok) p.res(m.result); else p.rej(new Error(m.error || 'Fehler'));
      return;
    }
    const d = m.data || {};
    if (m.event === 'tcp.data') { const s = socks.get(d.sock); if (s) s._data(d.data); return; }
    if (m.event === 'tcp.close') { const s = socks.get(d.sock); if (s) s._closed(d.error); return; }
    if (m.event === 'back') {
      let handled = false;
      for (const fn of (handlers.back || []).slice().reverse()) { try { if (fn() === true) { handled = true; break; } } catch (err) { console.error(err); } }
      post({ method: 'back.result', id: 0, args: { seq: d.seq, handled } });
      return;
    }
    if (m.event === 'visibility') hub.visible = !!d.visible;
    if (m.event === 'app') hub.foreground = d.state === 'resume';
    emit(m.event, d);
  });

  const hub = window.hub = {
    inHub,
    info: null,
    visible: false,
    foreground: true,
    call,
    /**
     * Ereignisse: 'visibility' {visible}, 'app' {state: 'pause'|'resume'}, 'home.action' {id},
     * 'link' {url}, 'back' (Rückgabe true = selbst erledigt)
     */
    on(name, fn) { (handlers[name] = handlers[name] || []).push(fn); return () => hub.off(name, fn); },
    off(name, fn) { handlers[name] = (handlers[name] || []).filter(f => f !== fn); },
    onBack(fn) { return hub.on('back', fn); },

    http: {
      /** Ohne CORS, über die App. o: {method, headers, body, timeout, responseType: 'text'|'base64'} -> {status, headers, body} */
      request: (url, o = {}) => call('http', Object.assign({ url }, o)),
      async text(url, o) { const r = await hub.http.request(url, o); if (r.status >= 400) throw new Error('HTTP ' + r.status); return r.body; },
      async json(url, o) { return JSON.parse(await hub.http.text(url, o)); },
    },

    tcp: {
      /** Rohe TCP-Verbindung. o: {timeout, encoding: 'latin1'|'utf8'|'base64'} -> Socket */
      async connect(host, port, o = {}) { const r = await call('tcp.open', Object.assign({ host, port }, o)); return new Socket(r.sock); },
    },

    net: {
      /** {ip, subnet, wifi, online} */
      info: () => call('net.info'),
      /** Alle Adressen im eigenen /24-Netz auf einen Port prüfen. o: {port, timeout, path, contains} -> [{ip, port, body?}] */
      scan: o => call('net.scan', o),
      /** mDNS/DNS-SD: z. B. '_skyline._tcp' -> [{name, host, port, txt}] */
      mdns: (type, o = {}) => call('net.mdns', Object.assign({ type }, o)),
    },

    home: {
      /**
       * Karte auf der Startseite. card: {title, subtitle, state: {text, tone: 'ok'|'warn'|'bad'|'off'}, image,
       * value: {text, label}, actions: [{id, label, icon, primary, repeat}]}. Antippen der Karte öffnet die Erweiterung.
       */
      set: card => call('home.set', card),
      clear: () => call('home.clear'),
    },

    ui: {
      /** Kurze Meldung unten. o.action = Knopftext; Ergebnis true, wenn der Knopf gedrückt wurde. */
      toast: (text, o = {}) => call('ui.toast', Object.assign({ text }, o)),
      haptic: (kind = 'tap') => call('ui.haptic', { kind }),
      open: url => call('ui.openUrl', { url }),
      share: text => call('ui.share', { text }),
    },

    nav: {
      /** Die eigene Seite in den Vordergrund holen. */
      open: () => call('nav.open'),
      /** Zum Store wechseln, optional zur Seite einer Erweiterung. */
      store: id => call('nav.store', { id }),
    },
  };

  hub.ready = inHub
    ? call('hello').then(info => { hub.info = info; hub.visible = !!info.visible; return info; })
    : Promise.resolve(null);
})();
