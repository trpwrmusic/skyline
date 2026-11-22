// 3D-Aufstellung mit three.js. Koordinaten: x = Breite, z = Tiefe (0 = Leinwandwand), y = Höhe, alles in Metern.
// Die Szene liest alles über die api (app.js) und meldet Änderungen zurück; sie speichert selbst nichts.
import * as THREE from 'three';
import { OrbitControls } from './vendor/OrbitControls.js';

const EAR = 1.1;
// Gehäusemaße [Breite, Höhe, Tiefe]; die Standbox wird in der Höhe gestreckt
const SIZE = { tower: [0.22, 1.0, 0.3], center: [0.52, 0.18, 0.24], sub: [0.36, 0.38, 0.36], shelf: [0.18, 0.3, 0.22] };
// Chassis: [x, y, Radius]; Standbox: y = Abstand von der Oberkante, sonst y relativ zur Gehäusemitte
const DRIVERS = {
  tower: [[0, 0.12, 0.035], [0, 0.32, 0.075], [0, 0.54, 0.075]],
  center: [[-0.15, 0, 0.06], [0, 0, 0.03], [0.15, 0, 0.06]],
  sub: [[0, 0, 0.13]],
  shelf: [[0, 0.07, 0.03], [0, -0.05, 0.06]],
};

let st = null;
const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';

function roundedBox(w, h, d, r, mat) {
  const s = new THREE.Shape();
  const x = -w / 2, y = -h / 2;
  r = Math.min(r, w / 2, h / 2);
  s.moveTo(x + r, y); s.lineTo(x + w - r, y); s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r); s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h); s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r); s.quadraticCurveTo(x, y, x + r, y);
  const g = new THREE.ExtrudeGeometry(s, { depth: d, bevelEnabled: true, bevelThickness: 0.01, bevelSize: 0.01, bevelSegments: 3, curveSegments: 6 });
  g.translate(0, 0, -d / 2);
  return new THREE.Mesh(g, mat);
}

function makeSpeaker(kind) {
  const [w, h, d] = SIZE[kind];
  const g = new THREE.Group();
  const body = roundedBox(w, h, d, 0.03, st.mats.body);
  body.castShadow = true;
  g.add(body);
  const parts = [];
  for (const [dx, dy, r] of DRIVERS[kind]) {
    const ring = new THREE.Mesh(new THREE.CylinderGeometry(r, r, 0.012, 32), st.mats.driver);
    ring.rotation.x = Math.PI / 2;
    const cone = new THREE.Mesh(new THREE.SphereGeometry(r * 0.45, 16, 12), st.mats.cone);
    cone.scale.z = 0.4;
    g.add(ring, cone);
    parts.push({ ring, cone, dx, dy, z: d / 2 });
  }
  const stand = new THREE.Mesh(new THREE.CylinderGeometry(0.025, 0.025, 1, 12), st.mats.stand);
  stand.castShadow = true;
  g.add(stand);
  Object.assign(g.userData, { kind, body, parts, stand, bodyH: h });
  return g;
}

function makeSofa() {
  const g = new THREE.Group();
  const m = st.mats.sofa;
  // Ohrpunkt liegt bei (0, EAR, 0), das Sofa sitzt darunter
  const seat = roundedBox(1.9, 0.42, 0.9, 0.08, m); seat.position.set(0, 0.21, 0.15);
  const back = roundedBox(1.9, 0.5, 0.22, 0.08, m); back.position.set(0, 0.62, 0.51);
  const armL = roundedBox(0.2, 0.3, 0.9, 0.06, m); armL.position.set(-0.95, 0.5, 0.15);
  const armR = armL.clone(); armR.position.x = 0.95;
  for (const p of [seat, back, armL, armR]) { p.castShadow = true; g.add(p); }
  const head = new THREE.Mesh(new THREE.SphereGeometry(0.1, 24, 16), st.mats.head);
  head.position.set(0, EAR, 0);
  g.add(head);
  return g;
}

function build() {
  const { layout, keys } = st.api.get();
  if (st.room) st.scene.remove(st.room);
  const room = new THREE.Group();
  st.room = room;
  st.scene.add(room);
  const W = layout.w, L = layout.l, H = 2.5;

  const floor = new THREE.Mesh(new THREE.PlaneGeometry(W, L), st.mats.floor);
  floor.rotation.x = -Math.PI / 2; floor.position.set(W / 2, 0, L / 2); floor.receiveShadow = true;
  room.add(floor);
  const pts = [];
  for (let x = 0.5; x < W; x += 0.5) for (let z = 0.5; z < L; z += 0.5) pts.push(x, 0.002, z);
  room.add(new THREE.Points(new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(pts, 3)),
    new THREE.PointsMaterial({ color: st.colors.dot, size: 3, sizeAttenuation: false })));

  const wallBack = new THREE.Mesh(new THREE.PlaneGeometry(W, H), st.mats.wall);
  wallBack.position.set(W / 2, H / 2, 0);
  const wallL = new THREE.Mesh(new THREE.PlaneGeometry(L, H), st.mats.wall);
  wallL.rotation.y = Math.PI / 2; wallL.position.set(0, H / 2, L / 2);
  const wallR = wallL.clone(); wallR.rotation.y = -Math.PI / 2; wallR.position.x = W;
  room.add(wallBack, wallL, wallR);
  st.walls = [wallBack, wallL, wallR];

  const sw = Math.min(W * 0.62, 3.2);
  const screen = new THREE.Mesh(new THREE.PlaneGeometry(sw, sw * 9 / 16), st.mats.screen);
  screen.position.set(W / 2, 0.75 + sw * 9 / 32, 0.01);
  room.add(screen);

  st.items = {};
  st.lines = {};
  for (const k of keys) {
    const g = makeSpeaker(st.api.kindOf(k));
    g.userData.key = k;
    room.add(g);
    st.items[k] = g;
    const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]), st.lineMat);
    room.add(line);
    st.lines[k] = line;
  }
  const sofa = makeSofa();
  sofa.userData.key = 'seat';
  room.add(sofa);
  st.items.seat = sofa;

  st.labelsEl.innerHTML = keys.map(k => `<div class="r3-label" data-l="${k}"><b></b><span></span></div>`).join('')
    + `<button class="r3-handle" type="button" aria-label="Höhe ziehen" hidden>
         <svg viewBox="0 0 24 24"><path d="M12 4v16M8 8l4-4 4 4M8 16l4 4 4-4"/></svg><span></span></button>`;
  st.handle = st.labelsEl.querySelector('.r3-handle');
  bindHandle();
  st.camTarget.set(W / 2, 0.6, L / 2);
  sync();
}

// Höhe am Objekt ziehen: Pixel werden über die Kameraprojektion in Meter umgerechnet
function bindHandle() {
  const h = st.handle;
  h.addEventListener('pointerdown', e => {
    const { selected, layout } = st.api.get();
    if (!selected) return;
    e.preventDefault(); e.stopPropagation();
    h.setPointerCapture(e.pointerId);
    const p = layout.spk[selected];
    const a = new THREE.Vector3(p.x, p.h, p.y).project(st.camera);
    const b = new THREE.Vector3(p.x, p.h + 1, p.y).project(st.camera);
    const pxPerM = Math.max(30, Math.abs(a.y - b.y) / 2 * st.canvas.getBoundingClientRect().height);
    const start = { y: e.clientY, h: p.h };
    st.api.begin();
    h.classList.add('active');
    const move = ev => { st.api.setHeight(selected, start.h - (ev.clientY - start.y) / pxPerM); sync(); };
    const up = () => {
      h.removeEventListener('pointermove', move);
      h.removeEventListener('pointerup', up);
      h.removeEventListener('pointercancel', up);
      h.classList.remove('active');
      st.api.done(true);
    };
    h.addEventListener('pointermove', move);
    h.addEventListener('pointerup', up);
    h.addEventListener('pointercancel', up);
  });
}

function sync() {
  if (!st) return;
  const { layout, keys, selected, labels, dist, heightText } = st.api.get();
  const seat = layout.seat;
  const ear = new THREE.Vector3(seat.x, EAR, seat.y);
  st.items.seat.position.set(seat.x, 0, seat.y);
  for (const k of keys) {
    const g = st.items[k];
    if (!g) continue;
    const p = layout.spk[k];
    const { kind, body, parts, stand, bodyH } = g.userData;
    g.position.set(p.x, 0, p.y);
    g.rotation.y = Math.atan2(seat.x - p.x, seat.y - p.y); // Front (+z) zeigt zum Hörplatz
    let top, bottom;
    if (kind === 'tower') {
      // Hochtöner sitzt 12 cm unter der Oberkante auf der eingestellten Höhe
      const H = Math.max(0.5, p.h + 0.12);
      body.scale.y = H / bodyH;
      body.position.y = H / 2;
      for (const d of parts) { d.ring.position.set(d.dx, H - d.dy, d.z + 0.012); d.cone.position.set(d.dx, H - d.dy, d.z + 0.02); }
      top = H; bottom = 0;
    } else {
      const cy = Math.max(bodyH / 2, p.h);
      body.scale.y = 1;
      body.position.y = cy;
      for (const d of parts) { d.ring.position.set(d.dx, cy + d.dy, d.z + 0.012); d.cone.position.set(d.dx, cy + d.dy, d.z + 0.02); }
      top = cy + bodyH / 2; bottom = cy - bodyH / 2;
    }
    stand.visible = bottom > 0.04;
    stand.scale.y = Math.max(bottom, 0.01);
    stand.position.set(0, bottom / 2, 0);
    g.userData.top = top;
    body.material = k === selected ? st.mats.bodySel : st.mats.body;

    const line = st.lines[k];
    line.geometry.setFromPoints([ear, new THREE.Vector3(p.x, p.h, p.y)]);
    line.computeLineDistances();
    line.material = k === selected ? st.lineSel : st.lineMat;

    const el = st.labelsEl.querySelector(`[data-l="${k}"]`);
    if (el) {
      el.querySelector('b').textContent = labels[k];
      el.querySelector('span').textContent = dist(k);
      el.classList.toggle('sel', k === selected);
    }
  }
  st.handle.hidden = !selected || !st.items[selected];
  if (selected && st.items[selected]) st.handle.querySelector('span').textContent = heightText(selected);
  request();
}

function placeOverlay() {
  const { keys, selected, layout } = st.api.get();
  const r = st.canvas.getBoundingClientRect();
  const v = new THREE.Vector3();
  const toPx = () => [(v.x + 1) / 2 * r.width, (1 - v.y) / 2 * r.height];
  for (const k of keys) {
    const g = st.items[k];
    const el = st.labelsEl.querySelector(`[data-l="${k}"]`);
    if (!g || !el) continue;
    v.set(g.position.x, g.userData.top + 0.12, g.position.z).project(st.camera);
    el.style.display = v.z > 1 ? 'none' : '';
    const [x, y] = toPx();
    el.style.transform = `translate(-50%, -100%) translate(${x}px, ${y}px)`;
  }
  if (selected && st.items[selected] && !st.handle.hidden) {
    const p = layout.spk[selected];
    v.set(p.x, p.h, p.y).project(st.camera);
    const [x, y] = toPx();
    st.handle.style.transform = `translate(0, -50%) translate(${x + 34}px, ${y}px)`;
  }
}

let raf = 0;
function request() { if (st && !raf) raf = requestAnimationFrame(frame); }
function frame() {
  raf = 0;
  if (!st) return;
  // Wände ausblenden, die zwischen Kamera und Raum stehen
  const c = st.camera.position, { layout } = st.api.get();
  st.walls[0].visible = c.z > 0;
  st.walls[1].visible = c.x > 0;
  st.walls[2].visible = c.x < layout.w;
  st.renderer.render(st.scene, st.camera);
  placeOverlay();
}

function resize() {
  const r = st.el.getBoundingClientRect();
  st.renderer.setSize(r.width, r.height, false);
  st.camera.aspect = r.width / Math.max(1, r.height);
  st.camera.updateProjectionMatrix();
  request();
}

function setView(kind) {
  const { layout } = st.api.get();
  const W = layout.w, L = layout.l;
  const f = Math.max(1, 1.25 / st.camera.aspect); // hochkant weiter weg, damit der Raum ganz ins Bild passt
  const M = Math.max(W, L) * f;
  if (kind === 'top') st.camera.position.set(W / 2, M * 1.45, L / 2 + 0.01);
  else st.camera.position.set(W / 2 + W * 0.35, M * 0.95, L + M * 0.75);
  st.controls.target.copy(st.camTarget);
  st.controls.update();
  st.viewKind = kind;
  request();
}

function mount(el, api) {
  unmount();
  const canvas = document.createElement('canvas');
  canvas.setAttribute('aria-label', 'Raum in 3D. Lautsprecher und Sofa lassen sich verschieben.');
  const labelsEl = document.createElement('div');
  labelsEl.className = 'r3-labels';
  el.append(canvas, labelsEl);
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(2, devicePixelRatio));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.outputColorSpace = THREE.SRGBColorSpace;

  const dark = matchMedia('(prefers-color-scheme: dark)').matches;
  const accent = new THREE.Color(css('--accent')).getHex();
  const colors = { dot: dark ? 0x3a4250 : 0xc9ced8, line: dark ? 0x8d95a4 : 0x8a93a3 };
  const mats = {
    floor: new THREE.MeshStandardMaterial({ color: dark ? 0x1b2029 : 0xf4f1ec, roughness: 0.95 }),
    wall: new THREE.MeshStandardMaterial({ color: dark ? 0x141820 : 0xe9ecf1, roughness: 1, side: THREE.DoubleSide }),
    screen: new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xdde6ff, emissiveIntensity: dark ? 0.55 : 0.15, roughness: 0.6 }),
    body: new THREE.MeshStandardMaterial({ color: dark ? 0x4a5262 : 0x2a2e36, roughness: 0.45, metalness: 0.1 }),
    bodySel: new THREE.MeshStandardMaterial({ color: accent, roughness: 0.4, metalness: 0.1 }),
    driver: new THREE.MeshStandardMaterial({ color: 0x0d0f13, roughness: 0.7 }),
    cone: new THREE.MeshStandardMaterial({ color: 0x3b414c, roughness: 0.35, metalness: 0.4 }),
    stand: new THREE.MeshStandardMaterial({ color: 0x6b7280, roughness: 0.4, metalness: 0.6 }),
    sofa: new THREE.MeshStandardMaterial({ color: dark ? 0x56607a : 0xb9c2d6, roughness: 0.9 }),
    head: new THREE.MeshStandardMaterial({ color: accent, roughness: 0.5 }),
  };

  const scene = new THREE.Scene();
  scene.add(new THREE.HemisphereLight(0xffffff, dark ? 0x202530 : 0xd8dce4, dark ? 1.4 : 1.8));
  const sun = new THREE.DirectionalLight(0xffffff, dark ? 1.6 : 1.4);
  sun.position.set(3, 7, 6);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -8, right: 8, top: 8, bottom: -8, near: 0.5, far: 25 });
  sun.shadow.radius = 6;
  scene.add(sun);

  const camera = new THREE.PerspectiveCamera(42, 1, 0.05, 100);
  const controls = new OrbitControls(camera, canvas);
  controls.maxPolarAngle = Math.PI * 0.49;
  controls.minDistance = 2;
  controls.maxDistance = 25;
  controls.addEventListener('change', request);

  st = {
    el, canvas, labelsEl, renderer, scene, camera, controls, mats, colors, api, camTarget: new THREE.Vector3(), items: {},
    lineMat: new THREE.LineDashedMaterial({ color: colors.line, dashSize: 0.08, gapSize: 0.08, transparent: true, opacity: 0.8 }),
    lineSel: new THREE.LineBasicMaterial({ color: accent }),
  };
  build();
  sun.target.position.copy(st.camTarget); scene.add(sun.target);
  resize();
  setView('persp');

  // Verschieben auf dem Boden; Klick ins Leere hebt die Auswahl auf
  const ray = new THREE.Raycaster(), ndc = new THREE.Vector2();
  const floorPlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
  const hit = new THREE.Vector3();
  let drag = null, down = null;
  const toNdc = e => { const r = canvas.getBoundingClientRect(); ndc.set(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1); };
  const pick = e => {
    toNdc(e); ray.setFromCamera(ndc, camera);
    const hits = ray.intersectObjects(Object.values(st.items), true);
    let o = hits[0]?.object;
    while (o && !o.userData.key) o = o.parent;
    return o ? o.userData.key : null;
  };
  canvas.addEventListener('pointerdown', e => {
    down = { x: e.clientX, y: e.clientY };
    const k = pick(e);
    if (!k) return;
    e.stopImmediatePropagation();
    controls.enabled = false;
    canvas.setPointerCapture(e.pointerId);
    toNdc(e); ray.setFromCamera(ndc, camera); ray.ray.intersectPlane(floorPlane, hit);
    const { layout } = api.get();
    const p = k === 'seat' ? layout.seat : layout.spk[k];
    drag = { k, dx: p.x - hit.x, dz: p.y - hit.z, moved: false };
    canvas.style.cursor = 'grabbing';
    api.select(k);
  }, true);
  canvas.addEventListener('pointermove', e => {
    if (!drag) { canvas.style.cursor = pick(e) ? 'grab' : ''; return; }
    toNdc(e); ray.setFromCamera(ndc, camera);
    if (!ray.ray.intersectPlane(floorPlane, hit)) return;
    const { layout } = api.get();
    const snap = v => Math.round(v * 20) / 20;
    const x = snap(Math.max(0, Math.min(layout.w, hit.x + drag.dx)));
    const z = snap(Math.max(0, Math.min(layout.l, hit.z + drag.dz)));
    if (!drag.moved) { api.begin(); drag.moved = true; }
    api.move(drag.k, x, z);
    sync();
  });
  canvas.addEventListener('pointerup', e => {
    if (drag) {
      const moved = drag.moved;
      drag = null;
      controls.enabled = true;
      canvas.style.cursor = '';
      try { canvas.releasePointerCapture(e.pointerId); } catch {}
      api.done(moved);
    } else if (down && Math.hypot(e.clientX - down.x, e.clientY - down.y) < 4) {
      api.select(null);
    }
    down = null;
  });
  canvas.addEventListener('pointercancel', () => { drag = null; controls.enabled = true; });

  st.ro = new ResizeObserver(resize);
  st.ro.observe(el);
}

function unmount() {
  if (!st) return;
  st.ro.disconnect();
  st.controls.dispose();
  st.renderer.dispose();
  st.scene.traverse(o => { if (o.geometry) o.geometry.dispose(); });
  st.el.innerHTML = '';
  st = null;
}

window.Room3D = {
  mount, unmount, sync,
  rebuild: () => { if (st) build(); },
  view: k => st && setView(k),
  viewKind: () => st?.viewKind,
  mounted: el => !!st && st.el === el && el.isConnected,
};
window.dispatchEvent(new Event('room3d-ready'));
