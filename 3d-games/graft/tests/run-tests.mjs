// node tests/run-tests.mjs   — no browser, no DOM, no three.js.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname, extname } from 'node:path';
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

function stripComments(t) {
  return t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}
function walk(dir, out = []) {
  for (const f of readdirSync(dir)) {
    if (['vendor', 'node_modules', '.git', 'dist', '__pycache__'].includes(f)) continue;
    const p = join(dir, f);
    if (statSync(p).isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}
const SRC = walk(join(ROOT, 'app', 'src')).concat(walk(join(ROOT, 'tests')))
  .filter(p => ['.js', '.mjs'].includes(extname(p)));

const { C } = await import(pathToFileURL(join(ROOT, 'app/src/sim/const.js')));
const P = await import(pathToFileURL(join(ROOT, 'app/src/sim/planet.js')));
const CAT = await import(pathToFileURL(join(ROOT, 'app/src/sim/catalog.js')));
const W = await import(pathToFileURL(join(ROOT, 'app/src/sim/world.js')));

/* ------------------------------------------------------------- the rule --- */
await check('THE RULE: everything in the world is something you can make', async () => {
  const w = W.createWorld();
  const kinds = new Set(w.objects.map(o => o.kind));
  ok(kinds.size >= 3, 'the world is too bare to be a world');
  for (const k of kinds) ok(CAT.isCreatable(k), `${k} is scenery you cannot make`);
  // and it stays true after the world runs itself for a while
  W.advance(w, 3600);
  for (const o of w.objects) ok(CAT.isCreatable(o.kind), `${o.kind} appeared and is not makeable`);
  return [...kinds].sort().join(', ');
});

await check('THE RULE: the renderer draws nothing the catalog does not name', () => {
  // The render table is keyed by KIND. If a kind is drawn that is not in the
  // catalog, or a catalog entry has no way to be drawn, one of them is a lie.
  const src = stripComments(readFileSync(join(ROOT, 'app/src/view/render.js'), 'utf8'));
  const drawn = new Set([...src.matchAll(/\[KIND\.([A-Z]+)\]:/g)].map(m => m[1]));
  const known = new Set(Object.keys(CAT.KIND));
  for (const d of drawn) ok(known.has(d), `render draws ${d}, which is not in the catalog`);
  for (const k of known) ok(drawn.has(k), `${k} can be made but cannot be drawn`);
  return `${drawn.size} kinds, both sides agree`;
});

await check('the world is FULL, not empty', () => {
  const w = W.createWorld();
  ok(w.objects.length > 400, `only ${w.objects.length} things on a whole planet`);
  const by = {};
  for (const o of w.objects) by[o.kind] = (by[o.kind] || 0) + 1;
  ok(by[CAT.KIND.TREE] > 100, 'not enough trees to be a forest');
  return `${w.objects.length} objects: ` + Object.entries(by).map(([k, v]) => `${v} ${k}`).join(', ');
});

/* ---------------------------------------------------------------- ocean --- */
await check('there is an ocean, and it is roughly half the world', () => {
  const s = P.scatter(4000, 0.5);
  const under = s.filter(d => P.heightAt(d) < C.planet.sea).length / s.length;
  ok(under > 0.25 && under < 0.65, `${(under * 100).toFixed(0)}% ocean is not a coastline`);
  return `${(under * 100).toFixed(0)}% water`;
});

await check('only kelp grows under the sea, and kelp grows nowhere else', () => {
  const w = W.createWorld();
  for (const o of w.objects) {
    const under = P.heightAt(o.dir) < C.planet.sea;
    const s = CAT.spec(o.kind);
    if (under) eq(!!s.underwater, true, `${o.kind} is drowning`);
    else eq(!!s.underwater, false, `${o.kind} is out of water`);
  }
  // and the placement rule agrees with world generation
  const wet = P.scatter(600, 0.2).find(d => P.heightAt(d) < C.planet.sea - 1);
  const dry = P.scatter(600, 0.2).find(d => P.heightAt(d) > C.planet.sea + 2 && P.slopeAt(d) < 0.2);
  w.res.biomass = 9999;
  eq(W.blockedReason(w, CAT.KIND.TREE, wet), 'under water');
  eq(W.blockedReason(w, CAT.KIND.KELP, dry), 'needs water');
});

await check('you cannot walk into the sea', () => {
  const w = W.createWorld();
  // aim at the water and keep walking
  let hit = false;
  for (let a = 0; a < 16; a++) {
    const w2 = W.createWorld();
    w2.player.yaw = (a / 16) * Math.PI * 2;
    for (let i = 0; i < 400; i++) W.advance(w2, 0.1, { forward: 1, strafe: 0, turn: 0, run: true });
    ok(P.heightAt(w2.player.dir) >= C.planet.sea, `walked into the water on heading ${a}`);
    hit = true;
  }
  ok(hit);
});

/* -------------------------------------------------------------- terrain --- */
await check('one height function: the ground under you is the ground you see', () => {
  // Nothing may compute its own terrain. The renderer imports heightAt; the
  // grep is what stops a second copy appearing later.
  const rsrc = stripComments(readFileSync(join(ROOT, 'app/src/view/render.js'), 'utf8'));
  ok(/P\.heightAt|P\.groundR/.test(rsrc), 'the renderer does not use the shared height function');
  ok(!/function\s+heightAt/.test(rsrc), 'the renderer defines its own height function');
});

await check('walking stays on the surface and the poles are a non-event', () => {
  const w = W.createWorld();
  // walk a long way in a straight line, over a pole if it comes
  let minR = Infinity, maxR = -Infinity;
  for (let i = 0; i < 4000; i++) {
    W.advance(w, 0.05, { forward: 1, strafe: 0, turn: 0, run: true });
    const l = P.len(w.player.dir);
    minR = Math.min(minR, l); maxR = Math.max(maxR, l);
    ok(isFinite(l), 'position went non-finite');
  }
  near(minR, 1, 1e-9, 'direction stopped being a unit vector');
  near(maxR, 1, 1e-9);
});

await check('sim/ is pure: no DOM, no three, no Math.random, no Date', () => {
  const banned = [/\bdocument\b/, /\bwindow\b/, /Math\.random/, /\bnew Date\b/, /Date\.now/,
                  /requestAnimationFrame/, /localStorage/, /from ['"].*three/];
  const bad = [];
  for (const p of SRC) {
    if (!p.includes(join('src', 'sim'))) continue;
    const t = stripComments(readFileSync(p, 'utf8'));
    for (const b of banned) if (b.test(t)) bad.push(`${p.replace(ROOT, '')} :: ${b}`);
  }
  eq(bad.length, 0, bad.join(' | '));
  return `${SRC.filter(p => p.includes(join('src', 'sim'))).length} files clean`;
});

await check('no control characters in any source file', () => {
  const bad = [];
  const all = SRC.concat(walk(ROOT).filter(f => ['.html', '.css', '.md', '.bat'].includes(extname(f))));
  for (const p of all) {
    const t = readFileSync(p, 'utf8');
    for (let i = 0; i < t.length; i++) {
      const c = t.charCodeAt(i);
      if (c < 32 && c !== 10 && c !== 13 && c !== 9) { bad.push(`${p.replace(ROOT, '')}@${i}`); break; }
    }
  }
  eq(bad.length, 0, bad.join(' | '));
});

await check('every source file actually parses, AS A MODULE', () => {
  // `node --check file.js` is NOT enough, and finding that out cost a broken
  // build: on a .js file it parses in a mode that silently accepts a duplicate
  // declaration the browser refuses outright. The same file checked as a module
  // fails properly. A guard that cannot see the defect will certify it.
  const bad = [];
  for (const f of SRC) {
    const src = readFileSync(f, 'utf8');
    try {
      execFileSync(process.execPath, ['--input-type=module', '--check'], {
        input: src, stdio: ['pipe', 'pipe', 'pipe'],
      });
    } catch (e) {
      const msg = String((e && e.stderr) || e).replace(/\s+/g, ' ').slice(0, 140);
      bad.push(f.replace(ROOT, '') + ': ' + msg);
    }
  }
  eq(bad.length, 0, bad.join(' | '));
  return SRC.length + ' files parse as modules';
});

await check('the parse guard can actually see a duplicate declaration', () => {
  // The guard above is only worth having if it fails on the thing it exists
  // for. This is that check, run against a file that is deliberately wrong.
  const broken = 'export function f(){ let dup = 1; const dup = 2; return dup; }';
  let threw = false;
  try {
    execFileSync(process.execPath, ['--input-type=module', '--check'], {
      input: broken, stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch (e) { threw = true; }
  ok(threw, 'the parse guard passes a file with a duplicate declaration in it');
});

/* --------------------------------------------------------------- growth --- */
await check('a thing you plant grows through its stages on its own', () => {
  const w = W.createWorld();
  w.res.biomass = 500;
  const dir = P.scatter(900, 0.44).find(d => !W.blockedReason(w, CAT.KIND.TREE, d));
  ok(dir, 'nowhere on a whole planet will accept a tree');
  const o = W.plant(w, CAT.KIND.TREE, dir);
  ok(o, 'could not plant');
  eq(o.stage, 0);
  const total = CAT.growTime(CAT.KIND.TREE);
  W.advance(w, total + 5);
  eq(o.stage, CAT.spec(CAT.KIND.TREE).stages.length - 1, 'never became a tree');
  return `sapling to tree in ${total}s`;
});

await check('harvesting pays, and the plant comes back', () => {
  const w = W.createWorld();
  const tree = w.objects.find(o => o.kind === CAT.KIND.TREE);
  ok(tree && W.finalStage(tree));
  const before = w.res.biomass;
  const got = W.harvest(w, tree);
  ok(got, 'harvest refused');
  ok(w.res.biomass > before, 'harvest paid nothing');
  ok(w.objects.includes(tree), 'the tree vanished instead of regrowing');
  ok(!W.finalStage(tree), 'it did not drop back a stage');
  W.advance(w, CAT.growTime(CAT.KIND.TREE) + 5);
  ok(W.finalStage(tree), 'it never grew back');
});

await check('three habs make a village, and the village builds by itself', () => {
  const w = W.createWorld();
  w.res.biomass = 99999; w.res.shard = 99999; w.res.alloy = 99999;
  // The world is full, so founding a village means clearing ground first --
  // which is the point of `clear` and the reason it exists.
  const anchor = P.scatter(900, 0.77).find(d =>
    P.heightAt(d) > C.planet.sea + 2 && P.heightAt(d) < C.planet.alpine && P.slopeAt(d) < 0.18);
  ok(anchor, 'no flat dry ground anywhere');
  const b = P.basis(anchor);
  let placed = 0;
  for (let i = 0; i < 40 && placed < 3; i++) {
    const a = (i / 7) * Math.PI * 2;
    const r = 3.5 + (i % 7) * 1.8;
    const off = P.add(P.mul(b.east, Math.cos(a) * r), P.mul(b.north, Math.sin(a) * r));
    const d = P.norm(P.add(P.mul(anchor, C.planet.radius), off));
    // clear whatever is growing there, but never a neighbour's house
    for (const inTheWay of W.near(w, d, 2.0)) {
      if (CAT.spec(inTheWay.kind).settles > 0) continue;
      W.clear(w, inTheWay);
    }
    if (W.plant(w, CAT.KIND.HAB, d)) placed++;
  }
  eq(placed, 3, 'could not put three habs together even after clearing');
  W.advance(w, 60);
  const st = W.settlements(w).find(s => s.tier);
  ok(st, 'three habs did not become a village');
  eq(st.tier, 'village');
  eq(!!w.seen.village, true, 'the village did not unlock anything');

  // and it grows itself -- slowly enough that walking away is a decision
  const n0 = w.objects.length;
  W.advance(w, C.grow.selfBuild.village * 2.5);
  ok(w.objects.length > n0, 'the village never built anything for itself');
  const after = W.settlements(w).find(s => s.tier);
  ok(['village', 'town'].includes(after.tier), `ran away to ${after.tier}`);

  // an hour unattended must not finish the whole ladder
  W.advance(w, 3600);
  const hour = W.settlements(w).find(s => s.tier);
  ok(hour.count < C.grow.city * 2, `unattended growth exploded to ${hour.count}`);
  return `village -> ${hour.tier} of ${hour.count} after an hour alone`;
});

await check('the beacon is locked until there is a village to put it in', () => {
  const w = W.createWorld();
  w.res.alloy = 9999; w.res.shard = 9999; w.res.biomass = 9999;
  eq(W.unlocked(w, CAT.KIND.BEACON), false);
  const dir = P.scatter(300, 0.9).find(d => P.heightAt(d) > C.planet.sea + 2 && P.slopeAt(d) < 0.2);
  eq(W.blockedReason(w, CAT.KIND.BEACON, dir), 'needs a village');
  w.seen.village = true;
  eq(W.unlocked(w, CAT.KIND.BEACON), true);
});

await check('a kiln works while you are somewhere else', () => {
  const w = W.createWorld();
  w.res.biomass = 9999; w.res.shard = 9999;
  const dir = P.scatter(700, 0.36).find(d =>
    P.heightAt(d) > C.planet.sea + 2 && P.slopeAt(d) < 0.2 && !W.near(w, d, 4).length);
  ok(W.plant(w, CAT.KIND.KILN, dir), 'could not raise a kiln');
  W.advance(w, CAT.growTime(CAT.KIND.KILN) + 5);
  const before = w.res.alloy;
  W.advance(w, 300);
  ok(w.res.alloy > before, 'the kiln produced nothing');
  return `+${Math.round(w.res.alloy - before)} alloy in 5 minutes`;
});

/* ------------------------------------------------------------ mechanics --- */
await check('fixed timestep: one long advance equals many short ones', () => {
  const a = W.createWorld(); W.advance(a, 900);
  const b = W.createWorld(); for (let i = 0; i < 900; i++) W.advance(b, 1);
  eq(a.objects.length, b.objects.length, 'the world diverged with the step size');
  near(a.res.biomass, b.res.biomass, 1e-6);
});

await check('determinism: one seed, one planet', () => {
  const a = W.createWorld(7), b = W.createWorld(7);
  eq(a.objects.length, b.objects.length);
  near(a.player.dir.x, b.player.dir.x, 1e-12);
});

await check('a save round-trips, and cannot smuggle in scenery', () => {
  const w = W.createWorld();
  w.res.biomass = 200;
  W.advance(w, 120);
  const back = W.load(W.save(w));
  eq(back.objects.length, w.objects.length);
  near(back.res.biomass, w.res.biomass, 1e-6);
  near(back.player.dir.y, w.player.dir.y, 1e-6);

  const doctored = JSON.parse(W.save(w));
  doctored.o.push(['monolith', 0, 1, 0, 0, -1, 0]);
  const cleaned = W.load(doctored);
  eq(cleaned.objects.length, w.objects.length, 'a save added something uncreatable');
  eq(W.load('{"v":9}'), null, 'a future save version was accepted');
});

await check('planting costs exactly what it says', () => {
  const w = W.createWorld();
  w.res.biomass = 100; w.res.shard = 100;
  const need = CAT.spec(CAT.KIND.HAB).needs;
  const dir = P.scatter(700, 0.55).find(d =>
    P.heightAt(d) > C.planet.sea + 2 && P.slopeAt(d) < 0.2 && !W.near(w, d, 4).length);
  const b0 = w.res.biomass, s0 = w.res.shard;
  ok(W.plant(w, CAT.KIND.HAB, dir), 'could not raise a hab');
  near(w.res.biomass, b0 - need.biomass, 1e-9);
  near(w.res.shard, s0 - need.shard, 1e-9);
});

await check('you cannot plant what you cannot afford', () => {
  const w = W.createWorld();
  w.res.biomass = 0; w.res.shard = 0; w.res.alloy = 0;
  const dir = P.scatter(300, 0.61).find(d => P.heightAt(d) > C.planet.sea + 2 && P.slopeAt(d) < 0.2);
  eq(W.blockedReason(w, CAT.KIND.HAB, dir), 'not enough');
  eq(W.plant(w, CAT.KIND.HAB, dir), null);
});

await check('you can clear ground, and it gives something back', () => {
  const w = W.createWorld();
  const tree = w.objects.find(o => o.kind === CAT.KIND.TREE);
  const n0 = w.objects.length, b0 = w.res.biomass;
  const back = W.clear(w, tree);
  eq(w.objects.length, n0 - 1, 'the tree is still there');
  ok(w.res.biomass > b0, 'clearing gave nothing back');
  ok(back.biomass < CAT.spec(CAT.KIND.TREE).harvest.biomass, 'clearing pays more than harvesting');
  eq(W.clear(w, tree), null, 'cleared the same thing twice');
  return `+${back.biomass} biomass, and the ground is free`;
});

/* ---------------------------------------------------------------- report -- */
for (const [tag, name, note] of results) console.log(`${tag}  ${name}${note ? '   — ' + note : ''}`);
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
