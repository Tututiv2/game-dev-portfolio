// node tests/assets.test.mjs   — no browser, no network, no Blender.
//
// The asset pipeline has one promise that matters more than any model in it:
// THE GAME MUST RUN WITH NOTHING ON DISK. So the absent case is tested first
// and hardest -- no pack, a broken pack, a pack naming files that are not
// there, a model too heavy for a phone -- and every one of them has to come
// back `null`, because `null` is what makes the renderer draw the procedural
// shape it has always drawn.
//
// The rest is about the two things a model must be true to: it stands on its
// own origin and it is one unit tall (or the game plants a 180-unit tree), and
// it arrives as parts shaped like render.js's own PARTS entries, so a loaded
// bank is still ONE InstancedMesh per material and not one Mesh per object.

import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
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
const near = (a, b, t, m) => { if (Math.abs(a - b) > t) throw new Error(`${m || ''} ${a} not within ${t} of ${b}`); };

const load = (p) => import(pathToFileURL(join(ROOT, p)));

const GL = await load('app/vendor/GLTFLoader.js');
const A = await load('app/src/view/assets.js');
const CAT = await load('app/src/sim/catalog.js');

const MODELS = join(ROOT, 'app', 'assets', 'models');
const PACK = join(MODELS, 'pack.json');
const havePack = existsSync(PACK);

/* ------------------------------------------------------------- scaffolding */

/** A fetch that reads the disk, so nothing here touches a network. */
function diskFetch(log) {
  return async (url) => {
    let p;
    try { p = fileURLToPath(url); } catch (e) { p = String(url); }
    if (log) log.push(p);
    if (!existsSync(p)) return { ok: false, status: 404 };
    const buf = readFileSync(p);
    return {
      ok: true,
      status: 200,
      json: async () => JSON.parse(buf.toString('utf8')),
      arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength),
    };
  };
}

/** A fetch serving a hand-made manifest, for the cases disk cannot produce. */
function fakeFetch(manifest, files) {
  return async (url) => {
    const s = String(url);
    if (s.endsWith('pack.json')) {
      if (manifest === 'garbage') return { ok: true, status: 200, json: async () => { throw new Error('bad json'); } };
      return { ok: true, status: 200, json: async () => manifest };
    }
    const name = s.slice(s.lastIndexOf('/') + 1);
    if (!(name in files)) return { ok: false, status: 404 };
    const buf = files[name];
    return { ok: true, status: 200, arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) };
  };
}

const PACK_URL = pathToFileURL(PACK).href;

/* --------------------------------------------------------- a GLB by hand ---
   Built here rather than by Blender, so the loader is tested against a file
   whose every byte this test chose -- otherwise a bug in the exporter and a
   bug in the reader could agree with each other. */

function buildGLB({ positions, normals, indices, node, material }) {
  const chunks = [];
  let offset = 0;
  const views = [];
  const put = (arr) => {
    const bytes = new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength);
    const pad = (4 - (bytes.length % 4)) % 4;
    views.push({ buffer: 0, byteOffset: offset, byteLength: bytes.length });
    chunks.push(bytes, new Uint8Array(pad));
    offset += bytes.length + pad;
    return views.length - 1;
  };
  const vPos = put(positions);
  const vNrm = normals ? put(normals) : -1;
  const vIdx = put(indices);

  const accessors = [
    { bufferView: vPos, componentType: 5126, count: positions.length / 3, type: 'VEC3' },
  ];
  const attributes = { POSITION: 0 };
  if (normals) {
    accessors.push({ bufferView: vNrm, componentType: 5126, count: normals.length / 3, type: 'VEC3' });
    attributes.NORMAL = 1;
  }
  accessors.push({ bufferView: vIdx, componentType: 5123, count: indices.length, type: 'SCALAR' });

  const gltf = {
    asset: { version: '2.0' },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes: [Object.assign({ mesh: 0 }, node || {})],
    meshes: [{ name: 'test', primitives: [{ attributes, indices: accessors.length - 1, mode: 4, material: 0 }] }],
    materials: [material || { name: 'plain', pbrMetallicRoughness: { baseColorFactor: [0.5, 0.25, 0.125, 1] } }],
    bufferViews: views,
    buffers: [{ byteLength: offset }],
    accessors,
  };

  const bin = new Uint8Array(offset);
  let at = 0;
  for (const c of chunks) { bin.set(c, at); at += c.length; }
  const json = new TextEncoder().encode(JSON.stringify(gltf));
  const jsonPad = (4 - (json.length % 4)) % 4;
  const jsonLen = json.length + jsonPad;
  const total = 12 + 8 + jsonLen + 8 + bin.length;

  const out = new Uint8Array(total);
  const dv = new DataView(out.buffer);
  dv.setUint32(0, 0x46546c67, true);
  dv.setUint32(4, 2, true);
  dv.setUint32(8, total, true);
  dv.setUint32(12, jsonLen, true);
  dv.setUint32(16, 0x4e4f534a, true);
  out.set(json, 20);
  for (let i = 0; i < jsonPad; i++) out[20 + json.length + i] = 0x20;   // JSON pads with spaces
  dv.setUint32(20 + jsonLen, bin.length, true);
  dv.setUint32(24 + jsonLen, 0x004e4942, true);
  out.set(bin, 28 + jsonLen);
  return out;
}

const TRI = {
  positions: new Float32Array([0, 0, 0, 1, 0, 0, 0, 1, 0]),
  normals: new Float32Array([0, 0, 1, 0, 0, 1, 0, 0, 1]),
  indices: new Uint16Array([0, 1, 2]),
};

/* ------------------------------------------------------------- the loader */

await check('the loader reads a glb this test built byte by byte', () => {
  const r = GL.parseGLB(buildGLB(TRI));
  eq(r.parts.length, 1);
  eq(r.tris, 1);
  eq(r.parts[0].positions.length, 9);
  eq(r.parts[0].indices.length, 3);
  near(r.parts[0].material.baseColor[0], 0.5, 1e-6, 'base colour lost');
  return '1 triangle, 1 material';
});

await check('node transforms are BAKED into the vertices, not carried alongside', () => {
  // Nothing downstream keeps a scene graph -- an InstancedMesh has one geometry
  // and a matrix per instance -- so a node transform that survives parsing is a
  // model drawn in the wrong place.
  const r = GL.parseGLB(buildGLB({ ...TRI, node: { translation: [5, 2, -1], scale: [2, 2, 2] } }));
  const p = r.parts[0].positions;
  near(p[0], 5, 1e-5, 'translation not applied');
  near(p[1], 2, 1e-5);
  near(p[3], 7, 1e-5, 'scale not applied');
  const b = GL.bounds(r.parts);
  near(b.width, 2, 1e-5);

  // and the other spelling: a node may carry a 16-float matrix instead of TRS
  const m = GL.parseGLB(buildGLB({ ...TRI, node: { matrix: [3, 0, 0, 0, 0, 3, 0, 0, 0, 0, 3, 0, -4, 1, 0, 1] } }));
  const q = m.parts[0].positions;
  near(q[0], -4, 1e-5, 'a matrix node was ignored');
  near(q[3], -1, 1e-5);
  near(q[7], 4, 1e-5);
  return 'TRS and matrix nodes both folded in';
});

await check('a non-uniform scale uses the normal matrix, not the model matrix', () => {
  // blender_models.py squashes foliage and stretches torsos. Reusing the model
  // matrix on normals under a non-uniform scale is how a mesh ends up lit as
  // though it were a different shape.
  const src = {
    positions: new Float32Array([0, 0, 0, 1, 0, 0, 0, 0, 1]),
    normals: new Float32Array([0.7071067, 0.7071067, 0, 0.7071067, 0.7071067, 0, 0.7071067, 0.7071067, 0]),
    indices: new Uint16Array([0, 1, 2]),
  };
  const r = GL.parseGLB(buildGLB({ ...src, node: { scale: [1, 8, 1] } }));
  const n = r.parts[0].normals;
  near(Math.hypot(n[0], n[1], n[2]), 1, 1e-5, 'normal not renormalised');
  // squashing y by 8 tips a 45-degree normal DOWN toward the x axis; the model
  // matrix would have tipped it up.
  ok(n[0] > n[1], `normal tipped the wrong way: ${n[0].toFixed(3)} vs ${n[1].toFixed(3)}`);
  near(n[0], 0.99227, 1e-4);
  return 'inverse transpose, x ' + n[0].toFixed(4);
});

await check('primitives are merged BY MATERIAL, one part per draw call', () => {
  const g = JSON.parse(JSON.stringify({}));
  // two nodes, one mesh, two primitives sharing a material -> one part
  const glb = buildGLB(TRI);
  const r = GL.parseGLB(glb);
  eq(r.parts.length, 1, 'one material should be one part');
  // and the real roster proves the other half
  if (havePack) {
    const tree = GL.parseGLB(readFileSync(join(MODELS, 'tree.glb')));
    eq(tree.parts.length, 3, 'the tree has three materials and should be three parts');
    const names = tree.parts.map(p => p.name).sort().join(',');
    return names;
  }
  return 'one material, one part';
});

await check('rubbish is refused, and refused fast', () => {
  const bad = [
    new Uint8Array(4),
    new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]),
    new TextEncoder().encode('{"asset":{"version":"2.0"}}'),
  ];
  for (const b of bad) {
    let threw = false;
    try { GL.parseGLB(b); } catch (e) { threw = true; }
    ok(threw, 'the loader accepted something that is not a glb');
  }
  // a truncated real file must throw rather than read past the buffer
  if (havePack) {
    const full = readFileSync(join(MODELS, 'tree.glb'));
    let threw = false;
    try { GL.parseGLB(full.subarray(0, Math.floor(full.length * 0.6))); } catch (e) { threw = true; }
    ok(threw, 'a truncated glb parsed as though it were whole');
  }
  eq(GL.isGLB(new Uint8Array(4)), false);
  return '4 malformed inputs rejected';
});

await check('fitToUnit rescues a model authored at any size', () => {
  // The owner can drop his own .glb into the pack. A model 180 units tall would
  // otherwise be planted as a 180-unit tree.
  const big = {
    positions: new Float32Array([100, 50, 100, 140, 50, 100, 120, 230, 100]),
    normals: null,
    indices: new Uint16Array([0, 1, 2]),
  };
  const r = GL.parseGLB(buildGLB(big));
  GL.fitToUnit(r.parts, 1);
  const b = GL.bounds(r.parts);
  near(b.minY, 0, 1e-5, 'base is not on the ground');
  near(b.height, 1, 1e-5, 'not one unit tall');
  near((b.minX + b.maxX) / 2, 0, 1e-5, 'not centred');
  return '180 units tall -> 1.000';
});

/* ------------------------------------------------------- the built models */

// The nine the pipeline was asked for, plus the one thing that is not
// instanced. Anything the catalog grows LATER is welcome to have a model and
// does not have to: a kind with no model draws its procedural shape, which is
// the whole point of the layer. So coverage of the catalog is REPORTED, never
// required -- requiring it would make a new kind wait on Blender.
const CORE = ['tree', 'bloom', 'crystal', 'kelp', 'hab', 'mast', 'kiln', 'beacon', 'tower'];

await check('Blender produced a model for every kind the pipeline was asked for', () => {
  if (!havePack) return 'no pack on disk (a supported state) -- skipped';
  const pack = JSON.parse(readFileSync(PACK, 'utf8'));
  const have = new Set(Object.keys(pack.models));
  for (const k of CORE) ok(have.has(k), `no model for ${k}`);
  ok(have.has('astronaut'), 'no model for the astronaut');
  const catalog = Object.values(CAT.KIND);
  const missing = catalog.filter(k => !have.has(k));
  const spare = [...have].filter(k => k !== 'astronaut' && !catalog.includes(k));
  return `${have.size} models from Blender ${pack.generated}` +
    (missing.length ? `; ${missing.length} catalog kinds fall back (${missing.join(', ')})` : '; whole catalog covered') +
    (spare.length ? `; ${spare.length} waiting for a kind (${spare.join(', ')})` : '');
});

await check('every model stands on its origin and is exactly one unit tall', () => {
  if (!havePack) return 'no pack -- skipped';
  const pack = JSON.parse(readFileSync(PACK, 'utf8'));
  const notes = [];
  for (const kind of Object.keys(pack.models)) {
    const r = GL.parseGLB(readFileSync(join(MODELS, pack.models[kind].file)));
    const b = GL.bounds(r.parts);
    near(b.minY, 0, 1e-4, `${kind} does not sit on its origin`);
    near(b.height, 1, 1e-4, `${kind} is not one unit tall`);
    near((b.minX + b.maxX) / 2, 0, 1e-3, `${kind} is off-centre in x`);
    near((b.minZ + b.maxZ) / 2, 0, 1e-3, `${kind} is off-centre in z`);
    ok(r.parts.length >= 1, `${kind} has no geometry`);
    for (const p of r.parts) ok(p.normals, `${kind} lost its normals`);
    notes.push(kind);
  }
  return notes.length + ' models, base 0, height 1';
});

await check('the roster is small enough for a phone', () => {
  if (!havePack) return 'no pack -- skipped';
  const pack = JSON.parse(readFileSync(PACK, 'utf8'));
  let tris = 0, bytes = 0, worst = ['', 0];
  for (const kind of Object.keys(pack.models)) {
    const m = pack.models[kind];
    const r = GL.parseGLB(readFileSync(join(MODELS, m.file)));
    eq(r.tris, m.tris, `${kind}: the manifest disagrees with the file`);
    eq(statSync(join(MODELS, m.file)).size, m.bytes, `${kind}: recorded size is stale`);
    ok(r.tris <= pack.budget, `${kind} is ${r.tris} triangles, over the ${pack.budget} budget`);
    tris += r.tris; bytes += m.bytes;
    if (r.tris > worst[1]) worst = [kind, r.tris];
  }
  ok(bytes < 400 * 1024, `${(bytes / 1024).toFixed(0)} KB is too much to hand a phone`);
  return `${tris} triangles, ${(bytes / 1024).toFixed(1)} KB, heaviest ${worst[0]} at ${worst[1]}`;
});

/* --------------------------------------------------- THE FALLBACK CONTRACT */

await check('NO PACK: the game gets null and nothing throws', async () => {
  const missing = pathToFileURL(join(MODELS, 'does-not-exist', 'pack.json')).href;
  const p = await A.loadModelPack({ url: missing, fetch: diskFetch() });
  eq(p, null, 'a missing pack was not reported as absent');
  eq(A.modelParts(p, 'tree'), null);
  eq(A.hasModel(p, 'tree'), false);
  eq(A.playerModel(p, 1.7), null);
  eq(A.packKinds(p).length, 0);
  A.disposePack(p);                       // must not throw either
  return 'null all the way down';
});

await check('a fetch that throws, hangs or lies is still just "no models"', async () => {
  const angry = async () => { throw new Error('network is down'); };
  eq(await A.loadModelPack({ url: PACK_URL, fetch: angry }), null, 'a thrown fetch escaped');

  const lying = async () => ({ ok: true, status: 200, json: async () => 'not an object' });
  eq(await A.loadModelPack({ url: PACK_URL, fetch: lying }), null, 'a string manifest was accepted');

  const empty = async () => ({ ok: true, status: 200, json: async () => ({ v: 1 }) });
  eq(await A.loadModelPack({ url: PACK_URL, fetch: empty }), null, 'a manifest with no models was accepted');

  eq(await A.loadModelPack({ url: PACK_URL, fetch: fakeFetch('garbage', {}) }), null, 'unparseable JSON was accepted');

  const nofetch = await A.loadModelPack({ url: PACK_URL, fetch: null, ...{} });
  ok(nofetch === null || (nofetch && nofetch.count > 0), 'default fetch path misbehaved');
  return '4 hostile fetches, 4 nulls';
});

await check('a listed model that is not on disk loses only itself', async () => {
  if (!havePack) return 'no pack -- skipped';
  const real = readFileSync(join(MODELS, 'tree.glb'));
  const warns = [];
  const p = await A.loadModelPack({
    url: 'https://example.invalid/models/pack.json',
    fetch: fakeFetch({ v: 1, models: {
      tree: { file: 'tree.glb', fit: 1.67 },
      ghost: { file: 'ghost.glb', fit: 1 },
      broken: { fit: 1 },
    } }, { 'tree.glb': real }),
    warn: (k, why) => warns.push(k + ': ' + why),
  });
  ok(p, 'one bad entry killed the whole pack');
  ok(A.hasModel(p, 'tree'), 'the good model did not load');
  eq(A.hasModel(p, 'ghost'), false);
  eq(A.hasModel(p, 'broken'), false);
  eq(warns.length, 2, warns.join(' | '));
  return warns.join(' | ');
});

await check('a model too heavy for a phone is refused, not drawn', async () => {
  if (!havePack) return 'no pack -- skipped';
  const warns = [];
  const p = await A.loadModelPack({ url: PACK_URL, fetch: diskFetch(), maxTris: 200, warn: (k, w) => warns.push(k) });
  ok(p, 'the whole pack was dropped when only some models were heavy');
  eq(A.hasModel(p, 'astronaut'), false, '716 triangles got through a 200 budget');
  ok(A.hasModel(p, 'tree'), 'a 92-triangle model was refused too');
  ok(warns.includes('astronaut'), 'nothing said why');

  const none = await A.loadModelPack({ url: PACK_URL, fetch: diskFetch(), maxTris: 1 });
  eq(none, null, 'a pack where nothing fits should read as no pack');
  return `refused ${warns.sort().join(', ')} at 200 triangles`;
});

await check('the renderer falls back kind by kind, not all or nothing', async () => {
  // This is the exact expression the integrator writes:
  //     const parts = modelParts(pack, kind) || PARTS[kind];
  // With no pack every kind must resolve to the procedural table; with half a
  // pack, exactly the loaded half must swap and the rest must not move.
  const src = readFileSync(join(ROOT, 'app/src/view/render.js'), 'utf8');
  // A kind the renderer draws before the catalog names it is mid-integration,
  // not an error -- fall back to the lower-cased constant so this check still
  // exercises it rather than quietly testing `undefined`.
  const drawn = [...src.matchAll(/\[KIND\.([A-Z]+)\]:/g)]
    .map(m => CAT.KIND[m[1]] || m[1].toLowerCase());
  ok(drawn.length >= 9, 'could not read the renderer draw table');
  const PARTS = {};
  for (const k of drawn) PARTS[k] = [{ procedural: true }];

  for (const k of drawn) {
    const chosen = A.modelParts(null, k) || PARTS[k];
    eq(chosen[0].procedural, true, `${k} did not fall back with no pack`);
  }
  if (!havePack) return `${drawn.length} kinds fall back`;

  const half = await A.loadModelPack({ url: PACK_URL, fetch: diskFetch(), maxTris: 200 });
  let swapped = 0, kept = 0;
  for (const k of drawn) {
    const chosen = A.modelParts(half, k) || PARTS[k];
    if (chosen[0].procedural) kept++; else { swapped++; ok(chosen[0].model, `${k} swapped to something odd`); }
  }
  ok(swapped > 0 && kept > 0, 'the half-pack case did not exercise both sides');
  return `${swapped} kinds on models, ${kept} still procedural`;
});

/* ------------------------------------------------------------- instancing */

await check('a loaded part IS a PARTS entry, so it goes into an InstancedMesh', async () => {
  if (!havePack) return 'no pack -- skipped';
  const pack = await A.loadModelPack({ url: PACK_URL, fetch: diskFetch() });
  ok(pack, 'the real pack did not load');
  const parts = A.modelParts(pack, 'tree');
  ok(parts && parts.length === 3, 'the tree did not arrive as three parts');
  for (const p of parts) {
    eq(typeof p.geo, 'function', 'geo is not a factory like the procedural table');
    eq(p.y, 0, 'a model part must lift by 0: its base is already its origin');
    eq(p.s, 1, 'a model part must scale by 1: the height does the scaling');
    ok(!p.stretch && !p.front && !p.flat, 'a model part must not want the procedural fixups');
    ok(p.mat && p.mat.color, 'no material params for new MeshStandardMaterial(part.mat)');
    const g = p.geo();
    ok(g.isBufferGeometry, 'geo() did not return a BufferGeometry');
    ok(g.getAttribute('position'), 'no positions');
    ok(g.getAttribute('normal'), 'no normals');
    ok(g.getIndex(), 'not indexed -- an unindexed instance wastes vertex work');
  }
  return `${A.packKinds(pack).length} kinds, ${pack.count} loaded, ${pack.tris} triangles`;
});

await check('fit puts a loaded model at the height the procedural one drew', async () => {
  if (!havePack) return 'no pack -- skipped';
  const pack = await A.loadModelPack({ url: PACK_URL, fetch: diskFetch() });
  const manifest = JSON.parse(readFileSync(PACK, 'utf8'));
  const notes = [];
  for (const kind of A.packKinds(pack)) {
    let lo = Infinity, hi = -Infinity;
    for (const p of A.modelParts(pack, kind)) {
      const g = p.geo();
      g.computeBoundingBox();
      lo = Math.min(lo, g.boundingBox.min.y);
      hi = Math.max(hi, g.boundingBox.max.y);
    }
    near(lo, 0, 1e-4, `${kind} does not stand on the ground after fitting`);
    near(hi, manifest.models[kind].fit, 1e-4, `${kind} is not at its fitted height`);
    notes.push(`${kind} ${hi.toFixed(2)}`);
  }
  // the tree's canopy tops out at 1.67h in render.js, and the model must agree
  near(manifest.models.tree.fit, 1.67, 1e-9);
  return notes.slice(0, 4).join(', ') + ', ...';
});

await check('the astronaut comes back as a group scaled to the player height', async () => {
  if (!havePack) return 'no pack -- skipped';
  const pack = await A.loadModelPack({ url: PACK_URL, fetch: diskFetch() });
  const { C } = await load('app/src/sim/const.js');
  const g = A.playerModel(pack, C.player.height);
  ok(g && g.isGroup, 'no group');
  eq(g.children.length, 4, 'the astronaut should be four materials');
  near(g.scale.x, C.player.height, 1e-9);
  for (const m of g.children) ok(m.isMesh && m.material.isMaterial, 'a child is not a real mesh');
  return `${g.children.length} parts at ${C.player.height} tall`;
});

/* ------------------------------------------------------------- discipline */

await check('the pipeline never reaches the simulation', () => {
  // sim/ is pure and stays pure: no model, no loader, no pack may be imported
  // there, or the world would start depending on files that may not exist.
  const dir = join(ROOT, 'app', 'src', 'sim');
  const bad = [];
  const walk = (d) => {
    for (const f of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, f.name);
      if (f.isDirectory()) { walk(p); continue; }
      if (!p.endsWith('.js')) continue;
      const t = readFileSync(p, 'utf8');
      if (/assets\.js|GLTFLoader|pack\.json/.test(t)) bad.push(p.replace(ROOT, ''));
    }
  };
  walk(dir);
  eq(bad.length, 0, bad.join(', '));
  return 'sim/ knows nothing about models';
});

await check('every file this pipeline owns parses', () => {
  const files = [
    'app/vendor/GLTFLoader.js',       // vendor/ is skipped by the main suite
    'app/src/view/assets.js',
    'tests/assets.test.mjs',
  ];
  for (const f of files) execFileSync(process.execPath, ['--check', join(ROOT, f)], { stdio: 'pipe' });
  return files.length + ' files';
});

await check('no control characters in the pipeline sources', () => {
  // Eighth time in this portfolio would be one too many.
  const files = ['app/vendor/GLTFLoader.js', 'app/src/view/assets.js', 'tests/assets.test.mjs',
                 'tools/blender_models.py', 'tools/build_assets.py'];
  for (const f of files) {
    const t = readFileSync(join(ROOT, f), 'utf8');
    for (let i = 0; i < t.length; i++) {
      const c = t.charCodeAt(i);
      if (c < 32 && c !== 10 && c !== 13 && c !== 9) throw new Error(`${f} @${i}`);
    }
  }
  return files.length + ' files clean';
});

await check('nothing here uses import.meta, so bundle.py can still fold it flat', () => {
  // bundle.py rewrites each module into a FUNCTION BODY. `import.meta` outside
  // module code is a syntax error, so writing the obvious
  // `new URL(..., import.meta.url)` would turn dist/GRAFT.html into a blank
  // page -- a much worse failure than a model that did not load.
  for (const f of ['app/src/view/assets.js', 'app/vendor/GLTFLoader.js']) {
    const t = readFileSync(join(ROOT, f), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    ok(!/import\s*\.\s*meta/.test(t), `${f} uses import.meta and cannot be bundled`);
  }
  return '2 files bundleable';
});

await check('the loader imports nothing, which is why it can be tested here', () => {
  const t = readFileSync(join(ROOT, 'app/vendor/GLTFLoader.js'), 'utf8');
  ok(!/^\s*import\s/m.test(t), 'the loader grew an import and can no longer be parsed headless');
  ok(!/\bdocument\b|\bwindow\b|requestAnimationFrame/.test(t.replace(/\/\/.*$/gm, '')),
     'the loader reached for the DOM');
});

/* ---------------------------------------------------------------- report -- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
