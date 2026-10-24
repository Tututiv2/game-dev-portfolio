// Content packs — the small app that downloads its own bulk.
//
// THE RULE THIS MODULE EXISTS TO PROTECT: a pack only ever ADDS. With nothing
// installed, with a 404 index, with the phone in aeroplane mode, GRAFT plays
// exactly as it does with no packs at all. So:
//   - nothing here runs on the game's start path,
//   - nothing here ever throws at the caller,
//   - install() RESOLVES with a result object even when it fails, because
//     main.js turns an unhandled rejection into a full-screen fatal, and a
//     download that could not finish is not a game that crashed.
//
// The layout is deliberately boring so a static file server is enough:
//
//   packs/packs.json          the index: one summary record per pack
//   packs/<id>/pack.json      that pack's file list, with sizes and hashes
//   packs/<id>/<path>         the files themselves
//
// tools/make_pack.py writes all three from a directory.

export const PACK_PATH = 'packs/';                    // sw.js matches this too
export const PACK_PREFIX = 'graft-pack-';             // Cache API name prefix
// A download must not be answered from the pack cache the download is about to
// replace: version 2 of a file lives at the same URL as version 1, so a
// cache-first service worker would hand back the old bytes and the new pack
// would fail its own hash check. This header tells sw.js to stay out of it.
export const DOWNLOAD_HEADER = 'x-graft-download';
export const DEFAULT_INDEX = PACK_PATH + 'packs.json';
export const MANIFEST_NAME = 'pack.json';

// A pack is verified one FILE at a time and the whole file is held in memory to
// hash it, so file size is the memory bound, not pack size. make_pack.py warns
// above this; a 70 GB pack is thousands of files, not one.
export const MAX_FILE_BYTES = 64 * 1024 * 1024;

export function packCacheName(id, version) {
  return PACK_PREFIX + id + '@' + version;
}
export function packAssetUrl(id, path) {
  return PACK_PATH + id + '/' + String(path).replace(/^\/+/, '');
}
export function packManifestUrl(id) {
  return packAssetUrl(id, MANIFEST_NAME);
}

/* ------------------------------------------------------------ plain words */
// Every failure a person can hit gets a sentence that says what happened and
// what it means for them. "TypeError: Failed to fetch" is not a sentence.
function human(e, what) {
  const m = String((e && e.message) || e || '');
  if (e && e.kind === 'nostore') return 'This browser cannot store downloads, so content packs are off. The game plays fine without them.';
  if (e && e.kind === 'cancelled') return 'Download stopped. Nothing was lost, it picks up where it left off.';
  if (e && e.kind === 'http') {
    if (e.status === 404) return '404: ' + what + ' is not on the server. The game plays fine without it.';
    return 'The server refused ' + what + ' (' + e.status + '). Try again later.';
  }
  if (e && e.kind === 'size') return what + ' arrived the wrong size, so it was thrown away. Nothing was installed.';
  if (e && e.kind === 'hash') return what + ' arrived damaged, so it was thrown away. Nothing was installed.';
  if (e && e.kind === 'space') return 'There is no room left on this device for ' + what + '. Delete a pack and try again.';
  if (e && e.kind === 'shape') return what + ' is not a content pack this version understands.';
  if (/quota|storage/i.test(m)) return 'There is no room left on this device for ' + what + '. Delete a pack and try again.';
  return 'Could not reach ' + what + '. Check your connection, the game still works offline.';
}
function err(kind, msg, extra) {
  const e = new Error(msg);
  e.kind = kind;
  if (extra) Object.assign(e, extra);
  return e;
}

/* ------------------------------------------------------------------ bytes */
function concat(chunks, total) {
  if (chunks.length === 1 && chunks[0].length === total) return chunks[0];
  const out = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) { out.set(c, at); at += c.length; }
  return out;
}
function hex(buf) {
  const b = new Uint8Array(buf);
  let s = '';
  for (let i = 0; i < b.length; i++) s += b[i].toString(16).padStart(2, '0');
  return s;
}

/* -------------------------------------------------------------- the store */
// Everything the module touches from outside is injected, which is what lets
// the whole downloader run headless in the test suite with a fake cache.
function environment(o) {
  const g = globalThis;
  return {
    fetch: o.fetch || (g && typeof g.fetch === 'function' ? g.fetch.bind(g) : null),
    caches: o.caches || (g && g.caches) || null,
    subtle: o.subtle || (g && g.crypto && g.crypto.subtle) || null,
    index: o.index || DEFAULT_INDEX,
  };
}

export function createPacks(opts) {
  const env = environment(opts || {});
  let indexRecords = null;      // last index we managed to read
  let lastError = null;
  const cancelling = new Set();

  const available = () => !!(env.fetch && env.caches);

  async function json(url, what) {
    let res;
    try {
      res = await env.fetch(url, { cache: 'no-store' });
    } catch (e) {
      throw err('net', 'fetch failed: ' + url);
    }
    if (!res || !res.ok) throw err('http', 'http ' + (res ? res.status : 0), { status: res ? res.status : 0 });
    let body;
    try { body = await res.json(); } catch (e) { throw err('shape', what + ' is not JSON'); }
    return body;
  }

  function normalise(rec) {
    if (!rec || typeof rec !== 'object') return null;
    const id = String(rec.id || '');
    if (!/^[a-z0-9][a-z0-9._-]*$/i.test(id)) return null;    // it becomes a URL and a cache name
    return {
      id,
      name: String(rec.name || id),
      description: String(rec.description || ''),
      version: String(rec.version == null ? '1' : rec.version),
      bytes: Number(rec.bytes) || 0,
      count: Number(rec.count) || (Array.isArray(rec.files) ? rec.files.length : 0),
      url: String(rec.url || packManifestUrl(id)),
      files: Array.isArray(rec.files) ? rec.files : null,
      required: !!rec.required,
    };
  }

  function normaliseFiles(list) {
    // null, not [] -- "no file list given" and "a pack with no files in it" are
    // different answers, and returning an empty array for the first meant the
    // index record's missing list looked like a finished (empty) pack.
    if (!Array.isArray(list)) return null;
    const out = [];
    for (const f of list || []) {
      if (!f || typeof f.path !== 'string' || !f.path) return null;
      if (f.path.includes('..')) return null;                 // never escape the pack folder
      const bytes = Number(f.bytes);
      if (!isFinite(bytes) || bytes < 0) return null;
      out.push({ path: f.path, bytes, sha256: typeof f.sha256 === 'string' ? f.sha256.toLowerCase() : null });
    }
    return out;
  }

  /* ------------------------------------------------------------- reading */
  async function readIndex() {
    if (!env.fetch) { lastError = 'This browser cannot download packs. The game plays fine without them.'; return []; }
    try {
      const body = await json(env.index, 'the pack list');
      const raw = Array.isArray(body) ? body : (body && Array.isArray(body.packs) ? body.packs : null);
      if (!raw) throw err('shape', 'the pack list has no packs');
      indexRecords = raw.map(normalise).filter(Boolean);
      lastError = null;
      return indexRecords;
    } catch (e) {
      lastError = human(e, 'the pack list');
      // Offline is not empty: what is already on the device is still listed.
      const local = await installed();
      indexRecords = local.map(r => normalise({ ...r, files: null }));
      return indexRecords;
    }
  }

  async function listPacks(o) {
    const refresh = !o || o.refresh !== false;
    let recs = indexRecords;
    if (refresh || !recs) recs = await readIndex();
    const have = await installed();
    const byId = new Map(have.map(r => [r.id, r]));
    return recs.map(r => {
      const got = byId.get(r.id);
      return {
        ...r,
        state: got ? (got.version === r.version ? 'ready' : 'outdated') : 'none',
        installedVersion: got ? got.version : null,
        installedBytes: got ? got.bytes : 0,
      };
    });
  }

  /* ------------------------------------------------------------- storage */
  async function packCaches() {
    if (!env.caches) return [];
    let keys = [];
    try { keys = await env.caches.keys(); } catch (e) { return []; }
    const out = [];
    for (const k of keys) {
      if (typeof k !== 'string' || !k.startsWith(PACK_PREFIX)) continue;
      const rest = k.slice(PACK_PREFIX.length);
      const at = rest.lastIndexOf('@');
      if (at <= 0) continue;
      out.push({ cache: k, id: rest.slice(0, at), version: rest.slice(at + 1) });
    }
    return out;
  }

  // The pack's own manifest is written into the cache LAST, so its presence is
  // the only thing that means "this pack finished". There is no separate
  // bookkeeping to fall out of step with the bytes.
  async function readRecord(name, id) {
    try {
      const cache = await env.caches.open(name);
      const res = await cache.match(packManifestUrl(id));
      if (!res) return null;
      const body = await res.json();
      const rec = normalise(body);
      if (!rec) return null;
      rec.files = normaliseFiles(body.files) || [];
      return rec;
    } catch (e) { return null; }
  }

  async function installed() {
    if (!env.caches) return [];
    const out = [];
    for (const c of await packCaches()) {
      const rec = await readRecord(c.cache, c.id);
      if (!rec) continue;
      out.push({
        id: rec.id, name: rec.name, version: c.version, bytes: rec.bytes,
        count: rec.files.length, cache: c.cache,
      });
    }
    out.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    return out;
  }

  async function status(id) {
    if (!env.caches) return 'none';
    let any = false;
    for (const c of await packCaches()) {
      if (c.id !== id) continue;
      any = true;
      if (await readRecord(c.cache, c.id)) return 'ready';
    }
    return any ? 'partial' : 'none';
  }

  async function open(id, path) {
    if (!env.caches) return null;
    try {
      for (const c of await packCaches()) {
        if (c.id !== id) continue;
        const cache = await env.caches.open(c.cache);
        const res = await cache.match(packAssetUrl(id, path));
        if (res) return res;
      }
    } catch (e) {}
    return null;
  }

  /* ----------------------------------------------------------- downloads */
  async function download(url, want, onBytes, isCancelled) {
    let res;
    const init = { cache: 'no-store', headers: { [DOWNLOAD_HEADER]: '1' } };
    try { res = await env.fetch(url, init); }
    catch (e) { throw err('net', 'fetch failed: ' + url); }
    if (!res || !res.ok) throw err('http', 'http ' + (res ? res.status : 0), { status: res ? res.status : 0 });

    const chunks = [];
    let got = 0;
    const body = res.body;
    if (body && typeof body.getReader === 'function') {
      const reader = body.getReader();
      for (;;) {
        const step = await reader.read();
        if (step.done) break;
        const value = step.value;
        chunks.push(value);
        got += value.length;
        if (got > want + MAX_FILE_BYTES) { try { await reader.cancel(); } catch (e) {} throw err('size', 'runaway body'); }
        if (isCancelled()) { try { await reader.cancel(); } catch (e) {} throw err('cancelled', 'cancelled'); }
        onBytes(got);
      }
    } else {
      const buf = new Uint8Array(await res.arrayBuffer());
      chunks.push(buf); got = buf.length;
      onBytes(got);
    }
    return concat(chunks, got);
  }

  async function sha256(bytes) {
    if (!env.subtle) return null;                 // insecure context: length only
    try { return hex(await env.subtle.digest('SHA-256', bytes)); } catch (e) { return null; }
  }

  async function install(id, onProgress) {
    const report = (p) => { try { if (onProgress) onProgress(p); } catch (e) {} };
    const fail = (e, what) => {
      const msg = human(e, what);
      lastError = msg;
      report({ id, phase: 'error', percent: 0, message: msg });
      return { ok: false, id, error: msg, bytes: 0, files: 0 };
    };

    if (!available()) return fail(err('nostore', 'no storage'), 'downloads');
    cancelling.delete(id);
    const cancelled = () => cancelling.has(id);

    // 1. which pack
    let rec = (indexRecords || []).find(r => r.id === id);
    if (!rec) { await readIndex(); rec = (indexRecords || []).find(r => r.id === id); }
    if (!rec) return fail(err('http', 'no such pack', { status: 404 }), 'pack called "' + id + '"');

    // 2. its file list
    report({ id, phase: 'index', percent: 0, message: 'Checking ' + rec.name + '...' });
    let files = normaliseFiles(rec.files);
    let detail = rec;
    if (!files) {
      try {
        detail = normalise(await json(rec.url, 'the ' + rec.name + ' file list')) || rec;
        detail.id = rec.id;
        files = normaliseFiles(detail.files);
      } catch (e) { return fail(e, 'the ' + rec.name + ' file list'); }
    }
    if (!files) return fail(err('shape', 'bad file list'), 'the ' + rec.name + ' file list');
    if (!files.length) return fail(err('shape', 'empty pack'), 'the ' + rec.name + ' file list');

    const version = String(detail.version || rec.version);
    const totalBytes = files.reduce((s, f) => s + f.bytes, 0) || 1;
    const name = packCacheName(id, version);

    let cache;
    try { cache = await env.caches.open(name); } catch (e) { return fail(e, rec.name); }

    // 3. the files, skipping whatever a previous run already stored
    let done = 0, fetched = 0, resumed = 0;
    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      const url = packAssetUrl(id, f.path);
      const base = done;
      const tick = (got) => report({
        id, phase: 'download', file: f.path, index: i, count: files.length,
        bytes: base + Math.min(got, f.bytes), totalBytes,
        percent: Math.min(100, ((base + Math.min(got, f.bytes)) / totalBytes) * 100),
        message: 'Downloading ' + rec.name + ' (' + (i + 1) + ' of ' + files.length + ')',
      });

      let already = null;
      try { already = await cache.match(url); } catch (e) {}
      if (already) {
        // Resume: a file already in this version's cache was verified when it
        // was put there, so it is never fetched twice.
        resumed++;
        done += f.bytes;
        tick(f.bytes);
        continue;
      }

      let bytes;
      try { bytes = await download(url, f.bytes, tick, cancelled); }
      catch (e) { return fail(e, f.path); }

      if (f.bytes && bytes.length !== f.bytes) return fail(err('size', 'size mismatch'), f.path);
      if (f.sha256) {
        report({
          id, phase: 'verify', file: f.path, index: i, count: files.length,
          bytes: base + f.bytes, totalBytes, percent: Math.min(100, ((base + f.bytes) / totalBytes) * 100),
          message: 'Checking ' + f.path,
        });
        const got = await sha256(bytes);
        if (got && got !== f.sha256) return fail(err('hash', 'hash mismatch'), f.path);
      }

      try {
        await cache.put(url, new Response(bytes, {
          headers: { 'content-type': contentType(f.path), 'content-length': String(bytes.length) },
        }));
      } catch (e) { return fail(e, rec.name); }

      fetched++;
      done += f.bytes;
      tick(f.bytes);
    }

    // 4. the manifest goes in LAST: writing it is what makes the pack "ready"
    const manifest = {
      id, name: rec.name, description: rec.description, version,
      bytes: totalBytes, count: files.length, files,
    };
    try {
      await cache.put(packManifestUrl(id), new Response(JSON.stringify(manifest), {
        headers: { 'content-type': 'application/json' },
      }));
    } catch (e) { return fail(e, rec.name); }

    // 5. an older version of the same pack is now dead weight
    for (const c of await packCaches()) {
      if (c.id === id && c.version !== version) { try { await env.caches.delete(c.cache); } catch (e) {} }
    }

    lastError = null;
    report({
      id, phase: 'done', percent: 100, bytes: totalBytes, totalBytes,
      count: files.length, message: rec.name + ' is ready.',
    });
    return { ok: true, id, version, bytes: totalBytes, files: files.length, fetched, resumed, error: null };
  }

  function cancel(id) { cancelling.add(id); }

  async function remove(id) {
    if (!env.caches) return { ok: false, id, freed: 0, error: 'Nothing is stored on this device.' };
    let freed = 0, hit = false;
    try {
      for (const c of await packCaches()) {
        if (c.id !== id) continue;
        const rec = await readRecord(c.cache, c.id);
        if (rec) freed += rec.bytes;
        await env.caches.delete(c.cache);
        hit = true;
      }
    } catch (e) {
      const msg = human(e, id);
      lastError = msg;
      return { ok: false, id, freed, error: msg };
    }
    if (!hit) return { ok: false, id, freed: 0, error: 'That pack is not on this device.' };
    return { ok: true, id, freed, error: null };
  }

  return {
    available, listPacks, install, remove, installed, status, open, cancel,
    lastError: () => lastError,
    assetUrl: packAssetUrl,
    cacheName: packCacheName,
    indexUrl: env.index,
  };
}

function contentType(path) {
  const m = /\.([a-z0-9]+)$/i.exec(path);
  const e = m ? m[1].toLowerCase() : '';
  return ({
    json: 'application/json', js: 'text/javascript', css: 'text/css',
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp',
    glb: 'model/gltf-binary', gltf: 'model/gltf+json', bin: 'application/octet-stream',
    ogg: 'audio/ogg', mp3: 'audio/mpeg', wav: 'audio/wav', ktx2: 'image/ktx2',
    txt: 'text/plain', svg: 'image/svg+xml',
  })[e] || 'application/octet-stream';
}

/* ------------------------------------------------------------- the shell */
// One line for main.js. Registration failing is not an error the player needs
// to know about: without a service worker the game is simply an ordinary page,
// which is what it was yesterday.
export async function registerServiceWorker(url) {
  try {
    const nav = globalThis.navigator;
    if (!nav || !nav.serviceWorker) return false;
    await nav.serviceWorker.register(url || 'sw.js', { scope: './' });
    return true;
  } catch (e) { return false; }
}

// "Add to home screen" is a permission the browser only grants once, and only
// through a gesture, so the event has to be caught the moment it fires and the
// button shown afterwards.
export function createInstallPrompt(onChange) {
  let deferred = null;
  const tell = () => { try { if (onChange) onChange(!!deferred); } catch (e) {} };
  try {
    const w = globalThis.addEventListener ? globalThis : null;
    if (w) {
      w.addEventListener('beforeinstallprompt', (e) => {
        if (e && e.preventDefault) e.preventDefault();
        deferred = e; tell();
      });
      w.addEventListener('appinstalled', () => { deferred = null; tell(); });
    }
  } catch (e) {}
  return {
    can: () => !!deferred,
    standalone: () => {
      try {
        const m = globalThis.matchMedia;
        if (m && m('(display-mode: standalone)').matches) return true;
        return !!(globalThis.navigator && globalThis.navigator.standalone);
      } catch (e) { return false; }
    },
    async prompt() {
      if (!deferred) return false;
      try {
        deferred.prompt();
        const r = await deferred.userChoice;
        deferred = null; tell();
        return !!(r && r.outcome === 'accepted');
      } catch (e) { deferred = null; tell(); return false; }
    },
  };
}

// The default instance. Reading globalThis is all that happens at import, so
// this is safe to load in node and safe to load before the game starts.
export const packs = createPacks();
export default packs;
