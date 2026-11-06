// Minimaler Service Worker (nur aktiv, wo der Browser ihn im Heimnetz zulässt): App-Hülle offline vorhalten.
const C = 'skyline-remote-v1';
self.addEventListener('install', e => e.waitUntil(caches.open(C).then(c => c.addAll(['/', '/manifest.webmanifest', '/icon-192.png']))));
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (u.pathname.startsWith('/api/')) return;
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
});
