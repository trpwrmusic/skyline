'use strict';
/*
 * Verbindung zum Denon-Receiver direkt aus der App (vorher: Node-Server auf dem PC).
 * Telnet auf Port 23, Zeilen enden mit CR. Der Receiver erlaubt nur eine Verbindung gleichzeitig, deshalb gibt die
 * Erweiterung sie frei, wenn die App in den Hintergrund geht, und baut sie beim Zurückkommen wieder auf.
 * Befehle gehen über eine Warteschlange mit mindestens 70 ms Abstand (1,1 s nach PWON), gleiche Stellbefehle
 * werden zusammengefasst (Regler ziehen).
 */
const Conn = (() => {
  const CFG_KEY = 'denon-config';
  let config = { host: '', demo: false, name: '' };
  try { config = { ...config, ...JSON.parse(localStorage.getItem(CFG_KEY) || '{}') }; } catch (e) {}
  const saveConfig = () => { try { localStorage.setItem(CFG_KEY, JSON.stringify(config)); } catch (e) {} };

  let status = 'offline';     // offline | connecting | connected | demo | setup
  let detail = '';
  let sock = null, buf = '', reconnectTimer = 0, paused = false, gen = 0;
  const statusFns = [], lineFns = [];

  const INITIAL_QUERIES = [
    'PW?', 'ZM?', 'MV?', 'MU?', 'SI?', 'MS?', 'CV?', 'ECO?', 'MNMEN?',
    'PSMULTEQ: ?', 'PSDYNEQ ?', 'PSREFLEV ?', 'PSDYNVOL ?', 'PSTONE CTRL ?', 'PSBAS ?', 'PSTRE ?',
    'PSSWL ?', 'PSLFE ?', 'PSDIL ?', 'PSCINEMA EQ. ?', 'PSDELAY ?', 'PSDRC ?', 'PSRSTR ?', 'PSSWR ?',
  ];

  function setStatus(s, d = '') {
    status = s; detail = d;
    const info = { status, host: config.host, demo: config.demo, name: config.name, detail };
    statusFns.forEach(f => f(info));
  }
  function incoming(line) {
    line = line.trim();
    if (line) lineFns.forEach(f => f(line));
  }

  // ---------- Warteschlange ----------
  const queue = [];
  let busy = false;
  function enqueue(cmd) {
    if (typeof cmd !== 'string' || !/^[\x20-\x7e]{2,27}$/.test(cmd)) return;
    const prefix = cmd.replace(/[\d.]+$/, '');
    if (/\d$/.test(cmd)) {
      const i = queue.findIndex(c => c.replace(/[\d.]+$/, '') === prefix && /\d$/.test(c));
      if (i >= 0) { queue[i] = cmd; return; }
    }
    queue.push(cmd);
    pump();
  }
  function pump() {
    if (busy || !queue.length) return;
    busy = true;
    const cmd = queue.shift();
    send(cmd);
    setTimeout(() => { busy = false; pump(); }, cmd === 'PWON' ? 1100 : 70);
  }
  function send(cmd) {
    if (config.demo) return demo.handle(cmd);
    if (sock && status === 'connected') sock.write(cmd + '\r').catch(() => {});
  }

  // ---------- Echte Verbindung ----------
  async function connect() {
    clearTimeout(reconnectTimer);
    const my = ++gen;
    if (sock) { const old = sock; sock = null; try { old.close(); } catch (e) {} }
    queue.length = 0;
    buf = '';
    if (config.demo) { demo.start(); setStatus('demo'); INITIAL_QUERIES.forEach(enqueue); return; }
    if (!config.host) { setStatus('setup'); return; }
    if (paused) { setStatus('offline', 'pausiert'); return; }
    setStatus('connecting');
    try {
      const s = await hub.tcp.connect(config.host, 23, { timeout: 4000, encoding: 'latin1' });
      if (my !== gen) { s.close(); return; }
      sock = s;
      s.on('data', d => {
        buf += d;
        const parts = buf.split('\r');
        buf = parts.pop();
        parts.forEach(incoming);
      });
      s.on('close', err => {
        if (sock !== s) return;
        sock = null;
        setStatus('offline', err || 'Verbindung beendet');
        if (!paused) reconnectTimer = setTimeout(connect, 5000);
      });
      setStatus('connected');
      INITIAL_QUERIES.forEach(enqueue);
    } catch (err) {
      if (my !== gen) return;
      setStatus('offline', err.message);
      if (!paused) reconnectTimer = setTimeout(connect, 5000);
    }
  }

  function pause() {
    paused = true;
    clearTimeout(reconnectTimer);
    if (sock) { const s = sock; sock = null; s.close(); }
    if (!config.demo && config.host) setStatus('offline', 'pausiert');
  }
  function resume() {
    if (!paused) return;
    paused = false;
    if (!config.demo) connect();
  }

  // ---------- Demo-Receiver ----------
  const demo = {
    st: null,
    start() {
      this.st = {
        PW: 'ON', ZM: 'ON', MV: 45, MU: 'OFF', SI: 'BD', MS: 'MOVIE', ECO: 'AUTO', MNMEN: 'OFF',
        CV: { FL: 50, FR: 50, C: 51.5, SW: 48, SL: 49.5, SR: 49.5 },
        PS: {
          MULTEQ: 'AUDYSSEY', DYNEQ: 'ON', REFLEV: '0', DYNVOL: 'OFF', 'TONE CTRL': 'OFF', BAS: 50, TRE: 50,
          SWL: 'ON', 'SWL:n': 50, LFE: 0, DIL: 'OFF', 'DIL:n': 50, 'CINEMA EQ.': 'OFF', DELAY: 0, DRC: 'AUTO', RSTR: 'OFF', SWR: 'ON',
        },
      };
    },
    vol(v) { const r = Math.round(v * 2) / 2; return (r % 1 ? String(Math.floor(r)).padStart(2, '0') + '5' : String(r).padStart(2, '0')); },
    parseVol(s) { return s.length === 3 ? Number(s.slice(0, 2)) + 0.5 : Number(s); },
    reply(l) { setTimeout(() => incoming(l), 30 + Math.random() * 60); },
    handle(cmd) {
      const st = this.st;
      const r = l => this.reply(l);
      let m;
      if (cmd === 'PW?') return r('PW' + st.PW);
      if ((m = cmd.match(/^PW(ON|STANDBY)$/))) { st.PW = m[1]; st.ZM = m[1] === 'ON' ? 'ON' : 'OFF'; r('PW' + st.PW); return r('ZM' + st.ZM); }
      if (cmd === 'ZM?') return r('ZM' + st.ZM);
      if (st.PW !== 'ON' && !cmd.endsWith('?')) return;
      if (cmd === 'MV?') { r('MV' + this.vol(st.MV)); return r('MVMAX 98'); }
      if (cmd === 'MVUP') st.MV = Math.min(98, st.MV + 0.5);
      else if (cmd === 'MVDOWN') st.MV = Math.max(0, st.MV - 0.5);
      else if ((m = cmd.match(/^MV(\d{2,3})$/))) st.MV = this.parseVol(m[1]);
      if (cmd.startsWith('MV')) { r('MV' + this.vol(st.MV)); return r('MVMAX 98'); }
      if ((m = cmd.match(/^MU(ON|OFF|\?)$/))) { if (m[1] !== '?') st.MU = m[1]; return r('MU' + st.MU); }
      if ((m = cmd.match(/^SI(.+)$/))) { if (m[1] !== '?') st.SI = m[1]; return r('SI' + st.SI); }
      if ((m = cmd.match(/^MS(.+)$/))) { if (m[1] !== '?') st.MS = m[1]; return r('MS' + st.MS); }
      if ((m = cmd.match(/^ECO(.+)$/))) { if (m[1] !== '?') st.ECO = m[1]; return r('ECO' + st.ECO); }
      if ((m = cmd.match(/^MNMEN ?(ON|OFF|\?)$/))) { if (m[1] !== '?') st.MNMEN = m[1]; return r('MNMEN ' + st.MNMEN); }
      if (cmd.startsWith('MN')) return;
      if (cmd === 'CV?') { Object.entries(st.CV).forEach(([k, v]) => r(`CV${k} ${this.vol(v)}`)); return r('CVEND'); }
      if ((m = cmd.match(/^CV([A-Z0-9]+) (UP|DOWN|\d{2,3})$/))) {
        const k = m[1]; if (!(k in st.CV)) return;
        let v = st.CV[k];
        v = m[2] === 'UP' ? v + 0.5 : m[2] === 'DOWN' ? v - 0.5 : this.parseVol(m[2]);
        st.CV[k] = Math.max(38, Math.min(62, v));
        return r(`CV${k} ${this.vol(st.CV[k])}`);
      }
      if (cmd === 'CVZRL') { Object.keys(st.CV).forEach(k => { st.CV[k] = 50; r(`CV${k} 50`); }); return; }
      if (cmd.startsWith('PS')) return this.ps(cmd.slice(2));
    },
    ps(body) {
      const p = this.st.PS;
      const r = l => this.reply('PS' + l);
      const num = (k, w) => String(p[k]).padStart(w, '0');
      let m;
      if ((m = body.match(/^MULTEQ: ?(.+)$/))) { if (m[1] !== '?') p.MULTEQ = m[1]; return r('MULTEQ:' + p.MULTEQ); }
      if ((m = body.match(/^TONE CTRL (ON|OFF|\?)$/))) { if (m[1] !== '?') p['TONE CTRL'] = m[1]; return r('TONE CTRL ' + p['TONE CTRL']); }
      if ((m = body.match(/^CINEMA EQ\. ?(ON|OFF|\?)$/))) { if (m[1] !== '?') p['CINEMA EQ.'] = m[1]; return r('CINEMA EQ.' + p['CINEMA EQ.']); }
      if ((m = body.match(/^(BAS|TRE) (UP|DOWN|\d+|\?)$/))) {
        const k = m[1];
        if (m[2] === 'UP') p[k] = Math.min(56, p[k] + 1); else if (m[2] === 'DOWN') p[k] = Math.max(44, p[k] - 1); else if (m[2] !== '?') p[k] = Math.max(44, Math.min(56, Number(m[2])));
        return r(`${k} ${num(k, 2)}`);
      }
      if ((m = body.match(/^(SWL|DIL) (ON|OFF|UP|DOWN|\d+|\?)$/))) {
        const k = m[1], n = k + ':n';
        if (m[2] === 'ON' || m[2] === 'OFF') p[k] = m[2];
        else if (m[2] === 'UP') p[n] = Math.min(62, p[n] + 0.5);
        else if (m[2] === 'DOWN') p[n] = Math.max(38, p[n] - 0.5);
        else if (m[2] !== '?') p[n] = this.parseVol(m[2]);
        r(`${k} ${p[k]}`); return r(`${k} ${this.vol(p[n])}`);
      }
      if ((m = body.match(/^LFE (UP|DOWN|\d+|\?)$/))) {
        if (m[1] === 'UP') p.LFE = Math.max(0, p.LFE - 1); else if (m[1] === 'DOWN') p.LFE = Math.min(10, p.LFE + 1); else if (m[1] !== '?') p.LFE = Number(m[1]);
        return r('LFE ' + num('LFE', 2));
      }
      if ((m = body.match(/^DELAY (UP|DOWN|\d+|\?)$/))) {
        if (m[1] === 'UP') p.DELAY = Math.min(200, p.DELAY + 1); else if (m[1] === 'DOWN') p.DELAY = Math.max(0, p.DELAY - 1); else if (m[1] !== '?') p.DELAY = Number(m[1]);
        return r('DELAY ' + num('DELAY', 3));
      }
      if ((m = body.match(/^(DYNEQ|REFLEV|DYNVOL|DRC|RSTR|SWR) (.+)$/))) { if (m[2] !== '?') p[m[1]] = m[2]; return r(`${m[1]} ${p[m[1]]}`); }
    },
  };

  // ---------- Suche im WLAN ----------
  // Denon/Marantz-Receiver haben Telnet (23) und eine Weboberfläche mit /goform/…; der Name steht in der XML.
  async function discover() {
    const hits = await hub.net.scan({ port: 23, timeout: 400 });
    const out = [];
    await Promise.all(hits.map(async h => {
      let name = '', denon = false;
      for (const url of [`http://${h.ip}/goform/formMainZone_MainZoneXml.xml`, `http://${h.ip}:8080/goform/formMainZone_MainZoneXml.xml`]) {
        try {
          const r = await hub.http.request(url, { timeout: 2500 });
          if (r.status === 200 && /<item>/i.test(r.body)) {
            denon = true;
            const m = r.body.match(/<FriendlyName>\s*<value>([^<]*)<\/value>/i);
            if (m) name = m[1].trim();
            break;
          }
        } catch (e) {}
      }
      if (denon) out.push({ host: h.ip, name: name || 'Denon-Receiver' });
    }));
    return out.sort((a, b) => a.host.localeCompare(b.host, 'de', { numeric: true }));
  }

  return {
    get status() { return status; },
    get config() { return { ...config }; },
    onStatus(f) { statusFns.push(f); },
    onLine(f) { lineFns.push(f); },
    enqueue,
    refresh() { INITIAL_QUERIES.forEach(enqueue); },
    configure(o) {
      if (typeof o.demo === 'boolean') config.demo = o.demo;
      if (typeof o.host === 'string' && /^[\w.-]{1,253}$/.test(o.host)) config.host = o.host;
      if (typeof o.name === 'string') config.name = o.name.slice(0, 60);
      saveConfig();
      connect();
    },
    connect, pause, resume, discover,
  };
})();
