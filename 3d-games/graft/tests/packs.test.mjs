// node tests/packs.test.mjs   — no browser, no DOM, no three.js.
//
// The app shell and the content-pack downloader. One claim is worth more than
// all the others here and most of these checks exist to defend it:
//
//   A PACK ONLY EVER ADDS.
//
// With nothing installed, with a 404 index, with the phone in aeroplane mode,
// GRAFT plays exactly as it does today. So the failure paths are tested harder
// than the happy one, and the happy one is driven end to end through the real
// tools/make_pack.py rather than through a manifest typed out by hand -- a
// downloader tested against its own idea of a manifest proves nothing about
// the file the packer actually writes.

import { readFileSync, existsSync, mkdtempSync, mkdirSync, writeFileSync, rmSync, readdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
let pass = 0, fail = 0;
const results = [];

async function check(name, fn) {
  try { const n = await fn(); pass++; results.push(['ok  ', name, n || '']); }
  catch (e) { fail++; results.push(['FAIL', name, e.message]); }
}
const eq = (a, b, m) => { if (a !== b) throw new Error(`${m || ''} expected ${b}, got ${a}`); };
const ok = (c, m) => { if (!c) throw new Error(m || 'assertion failed'); };

const load = (p) => import(pathToFileURL(join(ROOT, p)));
const PK = await load('app/src/view/packs.js');
const W = await load('app/src/sim/world.js');
const CAT = await load('app/src/sim/catalog.js');

const MINE = [
  'app/src/view/packs.js', 'app/sw.js', 'app/manifest.webmanifest',
  'tools/make_pack.py', 'tools/mkicons.py', 'tools/APK.md', 'tests/packs.test.mjs',
];

/* ------------------------------------------------------------- scaffolding */
// A Cache API that lives in a Map. Real Response objects, so the streaming
// reader, the JSON parsing and the byte counting are the shipping ones.
function fakeCaches(fetchHook) {
  const store = new Map();
  const order = [];
  const key = (r) => (typeof r === 'string' ? r : r.url);
  return {
    _store: store,
    async keys() { return order.filter(n => store.has(n)); },
    async open(name) {
      if (!store.has(name)) { store.set(name, new Map()); order.push(name); }
      const m = store.get(name);
      return {
        async match(req) {
          const v = m.get(key(req));
          return v ? new Response(v.body, { headers: v.headers }) : undefined;
        },
        async put(req, res) {
          const body = new Uint8Array(await res.arrayBuffer());
          m.set(key(req), { body, headers: Object.fromEntries(res.headers) });
        },
        async delete(req) { return m.delete(key(req)); },
        async keys() { return [...m.keys()]; },
        async add(req) {
          const res = await fetchHook(req);
          if (!res || !res.ok) throw new Error('add failed');
          const body = new Uint8Array(await res.arrayBuffer());
          m.set(key(req), { body, headers: {} });
        },
      };
    },
    async delete(name) { return store.delete(name); },
    async match(req) {
      for (const name of order) {
        const m = store.get(name);
        if (!m) continue;
        const v = m.get(key(req));
        if (v) return new Response(v.body, { headers: v.headers });
      }
      return undefined;
    },
  };
}

/* The worker, actually run. Grepping sw.js for "fetch appears before
 * caches.match" is a measurement that cannot see the defect it is looking for:
 * it would pass on a worker that read the cache in a helper. So sw.js is
 * loaded as the classic script it is, with a fake `self`, and its handlers are
 * dispatched for real. */
const WORKER_BASE = 'https://example.test/';
function runWorker(caches, fetchImpl) {
  const listeners = {};
  const self = {
    location: { origin: 'https://example.test' },
    addEventListener: (t, fn) => { (listeners[t] = listeners[t] || []).push(fn); },
    skipWaiting: async () => {},
    clients: { claim: async () => {} },
  };
  // In a worker a relative URL resolves against the worker's scope; node's
  // Request refuses one outright.
  // a plain function, because sw.js says `new Request(...)` and an arrow is
  // not a constructor
  function Rq(u, init) { return new Request(new URL(typeof u === 'string' ? u : u.url, WORKER_BASE).href, init); }
  const src = readFileSync(join(ROOT, 'app/sw.js'), 'utf8');
  // eslint-disable-next-line no-new-func
  new Function('self', 'caches', 'fetch', 'Response', 'Request', 'URL', src)(
    self, caches, (r, i) => fetchImpl(r, i), Response, Rq, URL);

  return async function dispatch(type, event) {
    const waits = [];
    const e = Object.assign({}, event);
    let answered;
    e.waitUntil = (p) => waits.push(p);
    e.respondWith = (p) => { answered = p; };
    for (const l of (listeners[type] || [])) l(e);
    await Promise.all(waits);
    const out = answered === undefined ? undefined : await answered;
    await settle();
    return out;
  };
}
const settle = () => new Promise(r => setTimeout(r, 0)).then(() => new Promise(r => setTimeout(r, 0)));
const wreq = (url, opts = {}) => ({
  method: opts.method || 'GET', url, mode: opts.mode || 'cors',
  headers: new Headers(opts.headers || {}),
});
const text = async (res) => (res ? await res.text() : null);

// A server in a Map. `hits` counts real requests, which is how resume is
// measured: a resumed file is one that is never asked for twice.
function fakeFetch(files, opts = {}) {
  const hits = new Map();
  const f = async (url, init) => {
    const u = String(url);
    hits.set(u, (hits.get(u) || 0) + 1);
    if (opts.offline) throw new TypeError('Failed to fetch');
    if (opts.fail && opts.fail.has(u)) throw new TypeError('Failed to fetch');
    if (!(u in files)) return new Response('not found', { status: 404 });
    const v = files[u];
    const body = typeof v === 'string' ? new TextEncoder().encode(v) : v;
    return new Response(body, { status: 200, headers: { 'content-length': String(body.length) } });
  };
  f.hits = hits;
  f.headersSeen = [];
  return f;
}

const sha = (b) => createHash('sha256').update(Buffer.from(b)).digest('hex');
const enc = (s) => new TextEncoder().encode(s);

// A small pack laid out exactly the way make_pack.py lays one out.
function samplePack(id = 'demo', version = '1') {
  const blobs = { 'a.txt': enc('alpha'.repeat(20)), 'sub/b.bin': enc('beta'.repeat(50)), 'c.json': enc('{"n":3}') };
  const files = Object.entries(blobs).map(([path, b]) => ({ path, bytes: b.length, sha256: sha(b) }));
  const bytes = files.reduce((s, f) => s + f.bytes, 0);
  const manifest = { id, name: 'Demo pack', description: 'a demo', version, bytes, count: files.length, files };
  const server = {
    'packs/packs.json': JSON.stringify({ packs: [{ id, name: 'Demo pack', description: 'a demo', version, bytes, count: files.length, url: `packs/${id}/pack.json` }] }),
    [`packs/${id}/pack.json`]: JSON.stringify(manifest),
  };
  for (const [path, b] of Object.entries(blobs)) server[`packs/${id}/${path}`] = b;
  return { id, version, blobs, files, bytes, manifest, server };
}

function python(args) {
  for (const exe of [['py', ['-3']], ['python3', []], ['python', []]]) {
    try {
      return execFileSync(exe[0], exe[1].concat(args), { stdio: 'pipe', cwd: ROOT }).toString();
    } catch (e) {
      if (e && e.code === 'ENOENT') continue;
      // verify reports its failures on STDOUT and exits 1, so an error built
      // from stderr alone is an empty string -- which reads as "said nothing".
      const said = String((e && e.stdout) || '') + String((e && e.stderr) || '');
      throw new Error((said || String(e)).slice(0, 400));
    }
  }
  throw new Error('no python on PATH');
}

/* --------------------------------------------------------- the app shell -- */
await check('the manifest makes this an app you can put on a home screen', () => {
  const m = JSON.parse(readFileSync(join(ROOT, 'app/manifest.webmanifest'), 'utf8'));
  eq(m.display, 'standalone', 'display');
  eq(m.orientation, 'portrait', 'orientation');
  eq(m.theme_color, '#060912', 'theme colour must match the game');
  eq(m.background_color, '#060912', 'background colour');
  ok(m.name && m.short_name, 'a home-screen icon needs a name');
  ok(m.start_url && m.scope, 'no start_url/scope');
  const sizes = new Set(m.icons.filter(i => (i.purpose || 'any').includes('any')).map(i => i.sizes));
  ok(sizes.has('192x192') && sizes.has('512x512'), 'Chrome will not offer to install without 192 and 512');
  ok(m.icons.some(i => (i.purpose || '').includes('maskable')), 'no maskable icon: Android crops a square badly');
  return `${m.icons.length} icons, ${m.display}, ${m.orientation}`;
});

await check('every icon the manifest names is a real PNG of the size it claims', () => {
  const m = JSON.parse(readFileSync(join(ROOT, 'app/manifest.webmanifest'), 'utf8'));
  const seen = [];
  for (const i of m.icons) {
    const p = join(ROOT, 'app', i.src);
    ok(existsSync(p), `${i.src} is missing`);
    const b = readFileSync(p);
    eq(b.slice(0, 8).toString('hex'), '89504e470d0a1a0a', `${i.src} is not a PNG`);
    const w = b.readUInt32BE(16), h = b.readUInt32BE(20);
    eq(`${w}x${h}`, i.sizes, `${i.src} is the wrong size`);
    ok(b.length > 500, `${i.src} is too small to be a drawn icon`);
    seen.push(`${i.src.replace('icons/', '')} ${w}px`);
  }
  // and the ones the html links directly, which the manifest does not list
  for (const extra of ['apple-touch-icon.png', 'favicon-32.png']) {
    ok(existsSync(join(ROOT, 'app/icons', extra)), `${extra} is missing`);
  }
  return seen.join(', ');
});

await check('the worker serves an EDIT, not yesterday build, and the cache only when offline', async () => {
  // The whole reason the shell is network-first. A cache-first worker makes
  // every save look like a no-op, which is the worst bug a worker can have.
  const net = { [WORKER_BASE]: 'root', [WORKER_BASE + 'index.html']: 'root',
                [WORKER_BASE + 'style.css']: 'css', [WORKER_BASE + 'manifest.webmanifest']: '{}',
                [WORKER_BASE + 'src/main.js']: 'VERSION ONE' };
  let offline = false;
  const fx = async (r) => {
    if (offline) throw new TypeError('Failed to fetch');
    const u = typeof r === 'string' ? r : r.url;
    if (!(u in net)) return new Response('nope', { status: 404 });
    return new Response(net[u], { status: 200 });
  };
  const caches = fakeCaches(fx);
  const dispatch = runWorker(caches, fx);
  await dispatch('install', {});

  const req = wreq(WORKER_BASE + 'src/main.js');
  eq(await text(await dispatch('fetch', { request: req })), 'VERSION ONE');

  net[WORKER_BASE + 'src/main.js'] = 'VERSION TWO';
  eq(await text(await dispatch('fetch', { request: req })), 'VERSION TWO', 'one save and a refresh did nothing');

  offline = true;
  eq(await text(await dispatch('fetch', { request: req })), 'VERSION TWO', 'offline got nothing at all');
  eq(await text(await dispatch('fetch', { request: wreq(WORKER_BASE + 'index.html') })), 'root', 'the shell is not cached');
  return 'network wins when there is one, cache when there is not';
});

await check('a pack file is served from the cache and never re-downloaded', async () => {
  let hits = 0;
  const fx = async () => { hits++; throw new TypeError('the network was touched'); };
  const caches = fakeCaches(fx);
  const c = await caches.open(PK.packCacheName('demo', '1'));
  await c.put(PK.packAssetUrl('demo', 'models/rock.bin'), new Response('ROCKBYTES'));
  const dispatch = runWorker(caches, fx);

  const got = await dispatch('fetch', { request: wreq(WORKER_BASE + 'packs/demo/models/rock.bin') });
  eq(await text(got), 'ROCKBYTES');
  eq(hits, 0, 'an immutable pack file went to the network anyway');

  // ...unless it is the downloader asking, which is fetching the bytes that are
  // about to REPLACE these. Answering it from this cache would fail its hash.
  const dl = await dispatch('fetch', {
    request: wreq(WORKER_BASE + 'packs/demo/models/rock.bin', { headers: { [PK.DOWNLOAD_HEADER]: '1' } }),
  });
  eq(dl, undefined, 'the worker answered the downloader from the cache it is replacing');
  return 'cache-first, and out of the downloader way';
});

await check('a code update never deletes a downloaded pack', async () => {
  const fx = async () => new Response('x');
  const caches = fakeCaches(fx);
  for (const n of ['graft-shell-v0', 'graft-shell-v1', PK.packCacheName('demo', '1'), 'something-else']) {
    await caches.open(n);
  }
  const dispatch = runWorker(caches, fx);
  await dispatch('activate', {});
  const left = (await caches.keys()).sort();
  ok(left.includes(PK.packCacheName('demo', '1')), 'a code push deleted the gigabytes the player waited for');
  ok(!left.includes('graft-shell-v0'), 'a stale shell cache survived');
  ok(!left.includes('something-else'), 'an unrelated cache survived');
  return left.join(', ');
});

await check('a cached pack file answers a Range request, so audio and video work offline', async () => {
  const fx = async () => { throw new TypeError('offline'); };
  const caches = fakeCaches(fx);
  const c = await caches.open(PK.packCacheName('demo', '1'));
  await c.put(PK.packAssetUrl('demo', 'v.bin'), new Response('0123456789'));
  const dispatch = runWorker(caches, fx);
  const res = await dispatch('fetch', {
    request: wreq(WORKER_BASE + 'packs/demo/v.bin', { headers: { range: 'bytes=2-5' } }),
  });
  eq(res.status, 206, 'a media element asking for a range got the whole file');
  eq(await text(res), '2345');
  eq(res.headers.get('content-range'), 'bytes 2-5/10');
});

await check('the worker and the downloader agree on every shared string', () => {
  const sw = readFileSync(join(ROOT, 'app/sw.js'), 'utf8');
  ok(sw.includes(`'${PK.PACK_PREFIX}'`), `sw.js does not use the cache prefix ${PK.PACK_PREFIX}`);
  ok(sw.includes(`'/${PK.PACK_PATH}'`), `sw.js does not use the pack path ${PK.PACK_PATH}`);
  ok(sw.includes(`'${PK.DOWNLOAD_HEADER}'`), 'sw.js does not know the download bypass header');
  // and the packer's memory ceiling matches the downloader's
  const py = readFileSync(join(ROOT, 'tools/make_pack.py'), 'utf8');
  const m = /MAX_FILE_BYTES = (\d+) \* 1024 \* 1024/.exec(py);
  ok(m, 'make_pack.py has no file-size ceiling');
  eq(Number(m[1]) * 1024 * 1024, PK.MAX_FILE_BYTES, 'the packer and the downloader disagree on the per-file ceiling');
  return `${PK.PACK_PREFIX}<id>@<version>, ${PK.PACK_PATH}<id>/<path>`;
});

/* --------------------------------------------------------- the fallback -- */
await check('THE FALLBACK: with no packs at all, the world is the world', async () => {
  const p = PK.createPacks({ fetch: fakeFetch({}), caches: fakeCaches() });
  eq((await p.installed()).length, 0);
  eq(await p.status('anything'), 'none');
  eq(await p.open('anything', 'a.txt'), null);

  const w = W.createWorld();
  ok(w.objects.length > 400, `only ${w.objects.length} things on a whole planet`);
  for (const o of w.objects) ok(CAT.isCreatable(o.kind), `${o.kind} is scenery you cannot make`);
  return `${w.objects.length} objects with zero downloads`;
});

await check('a 404 pack index is an empty list and a sentence, never a crash', async () => {
  const p = PK.createPacks({ fetch: fakeFetch({}), caches: fakeCaches() });
  const list = await p.listPacks();
  eq(list.length, 0);
  const msg = p.lastError();
  ok(msg && /404/.test(msg), `not a plain sentence: ${msg}`);
  ok(/\.$/.test(msg) && !/TypeError|undefined|\[object/.test(msg), `not plain words: ${msg}`);
  return msg;
});

await check('an offline start is an empty list and a sentence, never a crash', async () => {
  const p = PK.createPacks({ fetch: fakeFetch({}, { offline: true }), caches: fakeCaches() });
  eq((await p.listPacks()).length, 0);
  const msg = p.lastError();
  ok(/offline/.test(msg) && !/TypeError/.test(msg), `not plain words: ${msg}`);
  return msg;
});

await check('install RESOLVES on every failure, because a rejection is a fatal screen', async () => {
  // main.js turns an unhandled rejection into a full-screen "GRAFT could not
  // start". A download that failed is not a game that crashed, so nothing in
  // here is ever allowed to reject.
  const broken = {
    async keys() { throw new Error('boom'); },
    async open() { throw new Error('boom'); },
    async delete() { throw new Error('boom'); },
  };
  const cases = [
    PK.createPacks({ fetch: null, caches: null }).install('demo'),
    PK.createPacks({ fetch: fakeFetch({}), caches: fakeCaches() }).install('nope'),
    PK.createPacks({ fetch: fakeFetch({}, { offline: true }), caches: broken }).install('demo'),
    PK.createPacks({ fetch: fakeFetch({ 'packs/packs.json': 'not json at all' }), caches: fakeCaches() }).install('demo'),
  ];
  const out = await Promise.all(cases);
  for (const r of out) {
    eq(r.ok, false);
    ok(r.error && /\.$/.test(r.error), `not a sentence: ${r.error}`);
    ok(!/TypeError|undefined|\[object|Error:/.test(r.error), `leaked a stack trace: ${r.error}`);
  }
  return out.map(r => r.error.split('.')[0]).join(' | ');
});

await check('a whole install cycle, failure and all, cannot touch your save', async () => {
  const w = W.createWorld();
  W.advance(w, 120);
  const before = W.save(w);
  const s = samplePack();
  const p = PK.createPacks({ fetch: fakeFetch(s.server), caches: fakeCaches() });
  await p.listPacks();
  await p.install(s.id);
  await p.install('does-not-exist');
  await p.remove(s.id);
  eq(W.save(w), before, 'the downloader changed the world');

  // and it imports nothing from the simulation, so it cannot
  const src = readFileSync(join(ROOT, 'app/src/view/packs.js'), 'utf8');
  ok(!/^\s*import\s/m.test(src), 'packs.js imports something: it must stand alone');
  ok(!/localStorage/.test(src), 'packs.js reads the save');
});

/* ------------------------------------------------------------- downloads -- */
await check('a pack downloads, verifies, and reports real progress', async () => {
  const s = samplePack();
  const p = PK.createPacks({ fetch: fakeFetch(s.server), caches: fakeCaches() });
  await p.listPacks();

  const seen = [];
  const r = await p.install(s.id, (e) => seen.push(e));
  eq(r.ok, true, r.error || '');
  eq(r.bytes, s.bytes);
  eq(r.files, 3);
  eq(r.fetched, 3);
  eq(r.resumed, 0);

  const pct = seen.filter(e => typeof e.percent === 'number').map(e => e.percent);
  ok(pct.length >= 4, 'no progress was reported');
  for (let i = 1; i < pct.length; i++) ok(pct[i] >= pct[i - 1] - 1e-9, `progress went backwards: ${pct[i - 1]} -> ${pct[i]}`);
  eq(Math.round(pct[pct.length - 1]), 100);
  ok(seen.some(e => e.phase === 'download' && e.bytes > 0), 'no byte counts');
  ok(seen.some(e => e.phase === 'verify'), 'nothing was verified');
  eq(seen[seen.length - 1].phase, 'done');

  eq(await p.status(s.id), 'ready');
  const have = await p.installed();
  eq(have.length, 1);
  eq(have[0].id, s.id);
  eq(have[0].bytes, s.bytes);

  const got = await p.open(s.id, 'sub/b.bin');
  ok(got, 'the file is not readable after installing');
  eq(new Uint8Array(await got.arrayBuffer()).length, s.blobs['sub/b.bin'].length);

  const list = await p.listPacks();
  eq(list[0].state, 'ready');
  return `${r.files} files, ${r.bytes} bytes, ${pct.length} progress ticks`;
});

await check('a damaged file is thrown away and the pack is never marked ready', async () => {
  const s = samplePack();
  const server = { ...s.server };
  // exactly the right length, wrong bytes, so it is the HASH that catches it
  server[`packs/${s.id}/sub/b.bin`] = enc('betX'.repeat(50));
  const p = PK.createPacks({ fetch: fakeFetch(server), caches: fakeCaches() });
  await p.listPacks();
  const r = await p.install(s.id);
  eq(r.ok, false);
  ok(/damaged/.test(r.error), r.error);
  eq(await p.status(s.id), 'partial', 'a damaged pack was marked ready');
  eq((await p.installed()).length, 0, 'a damaged pack was listed as installed');
  eq(await p.open(s.id, 'sub/b.bin'), null, 'the damaged bytes were kept');
  return r.error;
});

await check('a truncated file is caught on length, before anything is hashed', async () => {
  const s = samplePack();
  const server = { ...s.server };
  server[`packs/${s.id}/a.txt`] = enc('alpha');       // 5 bytes, manifest says 100
  const p = PK.createPacks({ fetch: fakeFetch(server), caches: fakeCaches() });
  await p.listPacks();
  const seen = [];
  const r = await p.install(s.id, e => seen.push(e));
  eq(r.ok, false);
  ok(/wrong size/.test(r.error), r.error);
  ok(!seen.some(e => e.phase === 'verify'), 'it hashed a file it already knew was the wrong size');
  eq((await p.installed()).length, 0);
  return r.error;
});

await check('a part-finished download picks up where it left off', async () => {
  const s = samplePack();
  const caches = fakeCaches();
  const url = `packs/${s.id}/c.json`;

  const f1 = fakeFetch(s.server, { fail: new Set([url]) });
  const p1 = PK.createPacks({ fetch: f1, caches });
  await p1.listPacks();
  const r1 = await p1.install(s.id);
  eq(r1.ok, false);
  eq(await p1.status(s.id), 'partial');

  const f2 = fakeFetch(s.server);
  const p2 = PK.createPacks({ fetch: f2, caches });
  await p2.listPacks();
  const r2 = await p2.install(s.id);
  eq(r2.ok, true, r2.error || '');
  eq(r2.resumed, 2, 'it re-downloaded files it already had');
  eq(r2.fetched, 1, 'it fetched more than the one file that was missing');
  eq(f2.hits.get(`packs/${s.id}/a.txt`), undefined, 'a finished file was asked for twice');
  eq(f2.hits.get(url), 1);
  eq(await p2.status(s.id), 'ready');
  return `${r2.resumed} files resumed, ${r2.fetched} fetched`;
});

await check('a pack can be deleted to get the space back', async () => {
  const s = samplePack();
  const caches = fakeCaches();
  const p = PK.createPacks({ fetch: fakeFetch(s.server), caches });
  await p.listPacks();
  await p.install(s.id);
  eq(caches._store.size, 1);

  const r = await p.remove(s.id);
  eq(r.ok, true);
  eq(r.freed, s.bytes);
  eq(caches._store.size, 0, 'the cache is still on the device');
  eq(await p.status(s.id), 'none');
  eq((await p.installed()).length, 0);

  const again = await p.remove(s.id);
  eq(again.ok, false);
  ok(/not on this device/.test(again.error), again.error);
  return `${r.freed} bytes freed`;
});

await check('a new version is a new cache, and the old one is not left behind', async () => {
  const caches = fakeCaches();
  const v1 = samplePack('demo', '1');
  const p1 = PK.createPacks({ fetch: fakeFetch(v1.server), caches });
  await p1.listPacks();
  eq((await p1.install('demo')).ok, true);
  eq((await caches.keys())[0], PK.packCacheName('demo', '1'));

  const v2 = samplePack('demo', '2');
  const p2 = PK.createPacks({ fetch: fakeFetch(v2.server), caches });
  await p2.listPacks();
  const r = await p2.install('demo');
  eq(r.ok, true, r.error || '');
  eq(r.resumed, 0, 'version 2 reused version 1 bytes');
  const keys = await caches.keys();
  eq(keys.length, 1, `both versions are still stored: ${keys.join(', ')}`);
  eq(keys[0], PK.packCacheName('demo', '2'));
  return keys[0];
});

await check('offline afterwards: the list fails but what you downloaded still works', async () => {
  const s = samplePack();
  const caches = fakeCaches();
  const on = PK.createPacks({ fetch: fakeFetch(s.server), caches });
  await on.listPacks();
  await on.install(s.id);

  const off = PK.createPacks({ fetch: fakeFetch(s.server, { offline: true }), caches });
  const list = await off.listPacks();
  eq(list.length, 1, 'aeroplane mode hid a pack that is on the device');
  eq(list[0].state, 'ready');
  const got = await off.open(s.id, 'a.txt');
  ok(got, 'an installed file was unreadable offline');
  eq(new Uint8Array(await got.arrayBuffer()).length, s.blobs['a.txt'].length);
  ok(/offline|reach/.test(off.lastError()), off.lastError());
});

await check('a pack cannot name a file outside its own folder', async () => {
  const s = samplePack();
  const evil = JSON.parse(s.server[`packs/${s.id}/pack.json`]);
  evil.files.push({ path: '../../index.html', bytes: 10, sha256: 'x'.repeat(64) });
  const server = { ...s.server, [`packs/${s.id}/pack.json`]: JSON.stringify(evil) };
  const p = PK.createPacks({ fetch: fakeFetch(server), caches: fakeCaches() });
  await p.listPacks();
  const r = await p.install(s.id);
  eq(r.ok, false);
  eq((await p.installed()).length, 0);
  return r.error;
});

/* ------------------------------------------------------------- the tool -- */
await check('make_pack.py writes a manifest the downloader accepts, end to end', async () => {
  const tmp = mkdtempSync(join(tmpdir(), 'graft-pack-'));
  try {
    const src = join(tmp, 'src');
    mkdirSync(join(src, 'models'), { recursive: true });
    writeFileSync(join(src, 'readme.txt'), 'hello from a content pack\n');
    writeFileSync(join(src, 'models', 'rock.bin'), Buffer.alloc(5000, 7));
    writeFileSync(join(src, 'models', 'tree.bin'), Buffer.from('tree'.repeat(300)));
    const out = join(tmp, 'packs');

    python(['tools/make_pack.py', 'build', src, '--id', 'scenery', '--name', 'Scenery', '--out', out]);

    const index = JSON.parse(readFileSync(join(out, 'packs.json'), 'utf8'));
    eq(index.packs.length, 1);
    eq(index.packs[0].id, 'scenery');
    eq(index.packs[0].version, '1');
    // the index must point at exactly the URL the downloader would build
    eq(index.packs[0].url, 'packs/scenery/pack.json');

    const man = JSON.parse(readFileSync(join(out, 'scenery', 'pack.json'), 'utf8'));
    eq(man.count, 3);
    for (const f of man.files) {
      const disk = readFileSync(join(out, 'scenery', f.path));
      eq(f.bytes, disk.length, `${f.path} size`);
      eq(f.sha256, sha(disk), `${f.path} hash`);
    }

    // serve that directory through the fake network and install it for real
    const server = { 'packs/packs.json': readFileSync(join(out, 'packs.json'), 'utf8') };
    server['packs/scenery/pack.json'] = readFileSync(join(out, 'scenery', 'pack.json'), 'utf8');
    for (const f of man.files) server['packs/scenery/' + f.path] = new Uint8Array(readFileSync(join(out, 'scenery', f.path)));

    const p = PK.createPacks({ fetch: fakeFetch(server), caches: fakeCaches() });
    const list = await p.listPacks();
    eq(list.length, 1);
    eq(list[0].name, 'Scenery');
    const r = await p.install('scenery');
    eq(r.ok, true, r.error || '');
    eq(r.files, 3);
    eq(r.bytes, man.bytes);
    const got = await p.open('scenery', 'models/rock.bin');
    eq(new Uint8Array(await got.arrayBuffer()).length, 5000);

    // rebuilding unchanged content keeps the version; a real edit bumps it
    python(['tools/make_pack.py', 'build', src, '--id', 'scenery', '--out', out]);
    eq(JSON.parse(readFileSync(join(out, 'scenery', 'pack.json'), 'utf8')).version, '1');
    writeFileSync(join(src, 'readme.txt'), 'hello from a content pack, edited\n');
    python(['tools/make_pack.py', 'build', src, '--id', 'scenery', '--out', out]);
    eq(JSON.parse(readFileSync(join(out, 'scenery', 'pack.json'), 'utf8')).version, '2');

    return `${man.count} files, ${man.bytes} bytes, v1 -> v2 on an edit`;
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});

await check('make_pack verify catches a file edited after it was packed', async () => {
  const tmp = mkdtempSync(join(tmpdir(), 'graft-pack-'));
  try {
    const src = join(tmp, 'src');
    mkdirSync(src, { recursive: true });
    writeFileSync(join(src, 'a.txt'), 'original\n');
    const out = join(tmp, 'packs');
    python(['tools/make_pack.py', 'build', src, '--id', 'thing', '--out', out]);
    ok(/0 bad/.test(python(['tools/make_pack.py', 'verify', '--out', out])), 'a fresh pack did not verify');

    writeFileSync(join(out, 'thing', 'a.txt'), 'tampered\n');
    let said = '';
    try { said = python(['tools/make_pack.py', 'verify', '--out', out]); }
    catch (e) { said = e.message; }
    ok(/does not match its hash|is \d+ bytes/.test(said), `verify did not notice: ${said}`);
    return 'verify fails a tampered pack';
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});

/* -------------------------------------------------------------- hygiene -- */
await check('every file I added parses and holds no control characters', () => {
  for (const f of MINE) {
    const p = join(ROOT, f);
    ok(existsSync(p), `${f} is missing`);
    if (f.endsWith('.js')) execFileSync(process.execPath, ['--check', p], { stdio: 'pipe' });
    // compile(), not py_compile: py_compile litters a __pycache__ next to the
    // source just to answer a yes/no question
    if (f.endsWith('.py')) python(['-c', 'import sys; compile(open(sys.argv[1],encoding="utf-8").read(), sys.argv[1], "exec")', p]);
    if (f.endsWith('.webmanifest')) JSON.parse(readFileSync(p, 'utf8'));
    const t = readFileSync(p, 'utf8');
    for (let i = 0; i < t.length; i++) {
      const c = t.charCodeAt(i);
      if (c < 32 && c !== 10 && c !== 13 && c !== 9) throw new Error(`${f}@${i} is a control character`);
    }
  }
  return `${MINE.length} files clean`;
});

await check('the icons were drawn in code, so they can be drawn again', () => {
  const dir = join(ROOT, 'app/icons');
  const files = readdirSync(dir).filter(f => f.endsWith('.png'));
  ok(files.length >= 6, `only ${files.length} icons`);
  const src = readFileSync(join(ROOT, 'tools/mkicons.py'), 'utf8');
  ok(/zlib\.compress/.test(src) && /struct\.pack/.test(src), 'the icons are not written by hand');
  ok(!/^\s*(import|from)\s+(PIL|numpy|cairo)/m.test(src), 'the icon script needs something installed to run');
  for (const f of files) {
    const b = readFileSync(join(dir, f));
    eq(b.slice(0, 8).toString('hex'), '89504e470d0a1a0a', `${f} is not a PNG`);
  }
  return files.sort().join(', ');
});

/* ---------------------------------------------------------------- report -- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
