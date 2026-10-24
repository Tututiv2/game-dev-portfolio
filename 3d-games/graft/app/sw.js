/* GRAFT service worker.
 *
 * Two caches with opposite policies, because they hold opposite things:
 *
 *   the SHELL is NETWORK-FIRST. It is the code, it changes every time anyone
 *   edits a file, and cache-first would serve yesterday's build -- which makes
 *   every change look like a no-op and is the single worst bug a service
 *   worker can have. The cache is only ever the offline fallback.
 *
 *   CONTENT PACKS are CACHE-FIRST. They are immutable and versioned: a pack is
 *   stored under `graft-pack-<id>@<version>`, so a new version is a new cache,
 *   never an overwrite. Going to the network for a file that cannot have
 *   changed is a waste of somebody's data plan.
 *
 * And the whole thing is optional. If this worker never registers, or is
 * unregistered, GRAFT is an ordinary web page and plays exactly the same.
 */

const SHELL = 'graft-shell-v1';
const PACK_PREFIX = 'graft-pack-';        // app/src/view/packs.js agrees
const PACK_PATH = '/packs/';
const DOWNLOAD_HEADER = 'x-graft-download';

// Only the entry points are precached. The module graph grows as systems are
// added (events/, fx/, ...) and a hand-written list of every file goes stale
// silently -- runtime caching picks them up on the first load instead.
const SEED = ['./', 'index.html', 'style.css', 'manifest.webmanifest'];

self.addEventListener('install', (e) => {
  e.waitUntil((async () => {
    const cache = await caches.open(SHELL);
    // allSettled, never all: one missing file must not stop the worker
    // installing, or a typo in this list takes offline play down with it.
    await Promise.allSettled(SEED.map(u => cache.add(new Request(u, { cache: 'reload' }))));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) {
      // NEVER touch a pack cache here. Packs are the gigabytes the player
      // waited for; a code update must not make them download it all again.
      if (k.startsWith(PACK_PREFIX)) continue;
      if (k !== SHELL) await caches.delete(k);
    }
    await self.clients.claim();
  })());
});

self.addEventListener('message', (e) => {
  if (e && e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;

  let url;
  try { url = new URL(req.url); } catch (err) { return; }
  if (url.origin !== self.location.origin) return;    // never cache other people's servers

  // The downloader is fetching a pack file to REPLACE what is cached. Answering
  // it from that cache would hand it the bytes it is trying to supersede.
  if (req.headers.get(DOWNLOAD_HEADER)) return;

  if (url.pathname.includes(PACK_PATH)) e.respondWith(fromPacks(req, url));
  else e.respondWith(networkFirst(req));
});

/* ------------------------------------------------------------------ shell */
async function networkFirst(req) {
  try {
    const res = await fetch(req);
    // A 4xx/5xx is a perfectly good HTTP response and must not be cached as if
    // it were the app -- that is how a tunnel's own 502 page ends up being
    // served as the game.
    if (res && res.ok && res.type !== 'opaque') {
      const copy = res.clone();
      caches.open(SHELL).then(c => c.put(req, copy)).catch(() => {});
    }
    return res;
  } catch (err) {
    const hit = await caches.match(req, { ignoreSearch: true });
    if (hit) return hit;
    if (req.mode === 'navigate') {
      const shell = await caches.match('index.html') || await caches.match('./');
      if (shell) return shell;
    }
    return new Response('offline', { status: 503, headers: { 'content-type': 'text/plain' } });
  }
}

/* ------------------------------------------------------------------ packs */
async function fromPacks(req, url) {
  // The URL packs.js stored is the relative one it downloaded, so match on the
  // path rather than on the exact Request.
  const key = url.pathname.slice(url.pathname.indexOf(PACK_PATH) + 1);
  // caches.keys() is in creation order, so the LAST match is the newest version
  // -- which matters in the moment between a new version being written and the
  // old one being deleted.
  let best = null;
  for (const name of await caches.keys()) {
    if (!name.startsWith(PACK_PREFIX)) continue;
    const cache = await caches.open(name);
    const hit = (await cache.match(key)) || (await cache.match(req, { ignoreSearch: true }));
    if (hit) best = hit;
  }
  if (best) return withRange(req, best);
  try { return await fetch(req); }
  catch (err) {
    return new Response('pack not installed', { status: 404, headers: { 'content-type': 'text/plain' } });
  }
}

// A cached full response handed to <audio> or <video> in answer to a Range
// request makes the element give up, so serve the slice it actually asked for.
async function withRange(req, res) {
  const range = req.headers.get('range');
  if (!range) return res;
  const m = /bytes=(\d*)-(\d*)/.exec(range);
  if (!m) return res;
  const buf = await res.clone().arrayBuffer();
  const total = buf.byteLength;
  const start = m[1] ? parseInt(m[1], 10) : 0;
  const end = m[2] ? Math.min(parseInt(m[2], 10), total - 1) : total - 1;
  if (!(start >= 0 && end >= start && start < total)) {
    return new Response(null, { status: 416, headers: { 'content-range': 'bytes */' + total } });
  }
  return new Response(buf.slice(start, end + 1), {
    status: 206,
    headers: {
      'content-type': res.headers.get('content-type') || 'application/octet-stream',
      'content-length': String(end - start + 1),
      'content-range': 'bytes ' + start + '-' + end + '/' + total,
      'accept-ranges': 'bytes',
    },
  });
}
