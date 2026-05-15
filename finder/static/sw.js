// Pump-Finder service worker.
// Strategy:
//   • App shell (/, manifest, icons) — cache-first, so the home-screen icon
//     opens instantly even before the network is back.
//   • /api/scan — network-first with a 4s timeout, fall back to last cached
//     payload. Means you always see the freshest data when online and the
//     last known scan when offline.
//
// Bump CACHE_VERSION to invalidate old shells on the next visit.
const CACHE_VERSION = 'pf-v1';
const SHELL_CACHE = `${CACHE_VERSION}-shell`;
const API_CACHE = `${CACHE_VERSION}-api`;

const SHELL_URLS = [
  '/',
  '/static/manifest.webmanifest',
  '/static/icons/icon-192.png',
  '/static/icons/icon-512.png',
  '/static/icons/icon-maskable-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL_URLS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(
      keys.filter((k) => !k.startsWith(CACHE_VERSION)).map((k) => caches.delete(k))
    )).then(() => self.clients.claim())
  );
});

const networkWithTimeout = (request, timeoutMs) => {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), timeoutMs);
    fetch(request).then(
      (res) => { clearTimeout(timer); resolve(res); },
      (err) => { clearTimeout(timer); reject(err); }
    );
  });
};

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  if (url.pathname === '/api/scan') {
    event.respondWith((async () => {
      try {
        const res = await networkWithTimeout(req, 4000);
        if (res.ok) {
          const cache = await caches.open(API_CACHE);
          cache.put(req, res.clone());
        }
        return res;
      } catch (_) {
        const cached = await caches.match(req);
        if (cached) return cached;
        return new Response(
          JSON.stringify({ asOf: 0, source: 'offline', count: 0, candidates: [] }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
      }
    })());
    return;
  }

  // App shell + static — cache-first, revalidate in background.
  if (SHELL_URLS.includes(url.pathname) || url.pathname.startsWith('/static/')) {
    event.respondWith((async () => {
      const cached = await caches.match(req);
      const networked = fetch(req).then((res) => {
        if (res.ok) caches.open(SHELL_CACHE).then((c) => c.put(req, res.clone()));
        return res;
      }).catch(() => null);
      return cached || (await networked) || new Response('', { status: 504 });
    })());
  }
});
