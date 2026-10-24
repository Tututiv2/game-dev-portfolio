/* SHIPLOD, HEADLESS. No GPU, no browser, no puppeteer.

   It runs the shipping src/ships/shiplod.js against the shipping grid.js and
   api.js. Nothing here renders; the whole point of splitting the decision out
   of the view is that the decision can be measured in milliseconds.

   The thing being tested is a STEP FUNCTION of angular size, so the tests are
   about its shape: that it never runs backwards, that its boundaries are where
   tierRanges() says they are, that the size class is genuinely in the maths
   (a fighter and a world-ship at the same distance must not agree), and that
   the hysteresis actually holds a tier across a boundary.

   Two checks are proved able to FAIL, because this project's own hard-won
   lesson is that a measurement which cannot detect a defect will certify it:
   the monotonicity checker is run against a hand-made broken sequence, and the
   hysteresis check is run against the raw un-hysteretic function.

   node tests/ships_shiplod.mjs
*/
import fs from 'fs';
import path from 'path';
import { execFileSync } from 'child_process';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHIPS = path.join(ROOT, 'src', 'ships');

let pass = 0, fail = 0;
const ok = (cond, name, detail = '') => {
  if (cond) { pass++; console.log(`  PASS  ${name}${detail ? '  ' + detail : ''}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? '  ' + detail : ''}`); }
};
const near = (a, b, tol, name, detail) => ok(Math.abs(a - b) <= tol, name,
  detail !== undefined ? detail : `${a} vs ${b} (tol ${tol})`);

const G = await import(pathToFileURL(path.join(SHIPS, 'grid.js')).href);
const A = await import(pathToFileURL(path.join(SHIPS, 'api.js')).href);
const L = await import(pathToFileURL(path.join(SHIPS, 'shiplod.js')).href);

console.log('\n  SHIPLOD — one metre to a hundred kilometres, headless\n');

/* Three orthogonal beams of hull, N cells long. Bounds is an N-cube; the block
   count is 3N-2 rather than N^3, so a 100-cell world-ship is 298 blocks and the
   sweeps below run in milliseconds. LOD reads bounds, not block count. */
function boxGrid(cls, n) {
  const g = G.makeGrid(cls, `box-${cls}-${n}`);
  G.addBlock(g, 'hull', [0, 0, 0]);
  for (let i = 1; i < n; i++) {
    G.addBlock(g, 'hull', [i, 0, 0]);
    G.addBlock(g, 'hull', [0, i, 0]);
    G.addBlock(g, 'hull', [0, 0, i]);
  }
  return g;
}
const CTX = { radPerPx: L.DEFAULT_RAD_PER_PX };
const px = (rad) => rad / CTX.radPerPx;

/* ---- the file obeys the hard rules --------------------------------------- */
{
  const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const src = strip(fs.readFileSync(path.join(SHIPS, 'shiplod.js'), 'utf8'));
  const banned = [/\bfrom ['"]three['"]/, /\bdocument\b/, /\bwindow\b/,
                  /Math\.random/, /\bnew Date\b/, /Date\.now/];
  ok(!banned.some(re => re.test(src)), 'shiplod.js is pure — no three, DOM, random or clock');

  // "NEVER HARDCODE 2.5" is a rule with teeth only if something checks it.
  const hard = /(?<![\d.])2\.5(?![\d])/.test(src);
  ok(!hard, 'shiplod.js never hardcodes the large grid — cellM() only');
  ok(/(?<![\d.])2\.5(?![\d])/.test('const cell = 2.5;'),
     '...and that grep can still detect a real 2.5');

  /* CLAUDE.md trap 1: a backtick inside a shader string ends the template
     literal and the rest of the GLSL is parsed as JavaScript. The real parser
     is the only reliable detector, so run it — on the shipping file, and then
     on a copy with a backtick injected into the fragment shader, because a
     lint that cannot fail is not a lint. */
  const viewPath = path.join(SHIPS, 'shiplod_view.js');
  const view = fs.readFileSync(viewPath, 'utf8');
  const parses = (src) => {
    // --input-type=module, or `import` alone is reported as the syntax error.
    try {
      execFileSync(process.execPath, ['--input-type=module', '--check', '-'],
                   { input: src, stdio: 'pipe' });
      return true;
    }
    catch { return false; }
  };
  ok(parses(view), 'shiplod_view.js parses — no stray backtick in a shader string');
  ok(!parses(view.replace('float lam =', 'float lam = ` //')),
     '...and that check still fails when a backtick is injected into the GLSL');
  ok(/toneMapped = false/.test(view),
     'view materials opt out of tone mapping — view.js tone maps once at the end');
}

/* ---- angular size is the whole idea -------------------------------------- */
{
  near(L.angularSize(50, 100000), 2 * 50 / 100000, 1e-9,
       'a 50 m radius at 100 km subtends r/d radians (small angle)',
       `${(L.angularSize(50, 100000) * 1e3).toFixed(4)} mrad`);
  const a1 = L.angularSize(50, 10000), a2 = L.angularSize(50, 20000);
  near(a1 / a2, 2, 1e-3, 'doubling the distance halves the angle');
  ok(L.angularSize(50, 10) === Math.PI, 'inside the bounding sphere it is everywhere');
  ok(L.angularSize(0, 100) === 0, 'an empty grid subtends nothing');
  // the inverse must actually invert, or every boundary in tierRanges is wrong
  const d = L.distanceForAngle(50, 0.01);
  near(L.angularSize(50, d), 0.01, 1e-12, 'distanceForAngle inverts angularSize',
       `${d.toFixed(1)} m`);
}

/* ---- THE SENTENCE THIS SYSTEM EXISTS FOR --------------------------------- */
{
  const worldShip = boxGrid('world', 100);     // 6.25 km across
  const fighter = boxGrid('small', 12);        // 6 m across
  const a = L.lodForGrid(worldShip, 40000, CTX);
  const b = L.lodForGrid(fighter, 40000, CTX);
  ok(a.tier > b.tier, 'a world-ship and a fighter at the SAME 40 km are not the same problem',
     `${a.name} (${a.shipPx.toFixed(0)} px) vs ${b.name} (${b.shipPx.toFixed(2)} px)`);
  ok(b.tier === L.TIER.POINT, '...the fighter is a point of light at 40 km');
  ok(a.tier >= L.TIER.HULL, '...the world-ship still has a shell');
  // and the decision is angular, so an identical ANGLE gives an identical tier
  const rW = L.boundingRadiusM(worldShip), rF = L.boundingRadiusM(fighter);
  const same = L.lodForGrid(fighter, 40000 * rF / rW, CTX);
  ok(same.tier >= L.TIER.HULL,
     'the fighter at the matching ANGLE gets the same treatment',
     `${(40000 * rF / rW).toFixed(1)} m -> ${same.name}`);
}

/* ---- the decision is driven by the CELL where it should be ---------------- */
{
  const g = boxGrid('large', 60);
  const r = L.tierRanges(g, CTX);
  const blocks = L.lodForGrid(g, r.blocks * 0.9, CTX);
  const hull = L.lodForGrid(g, r.blocks * 1.1, CTX);
  ok(blocks.tier === L.TIER.BLOCKS && hull.tier === L.TIER.HULL,
     'the blocks/hull boundary is where tierRanges says');
  ok(blocks.drivenBy === 'cell' && hull.drivenBy === 'cell',
     '...and it is the CELL angle that drove it, not the ship angle',
     `${px(blocks.cellAngle).toFixed(2)} px per cell vs ${px(hull.cellAngle).toFixed(2)}`);
  ok(Math.abs(blocks.angle - blocks.cellAngle) < 1e-12,
     '...so lod.angle reports the angle that made the decision');
}

/* ---- the size class is genuinely in the maths ----------------------------- */
{
  // Same cell COUNT, one class apart: every boundary must move by exactly 5x,
  // because that is the whole ladder. A hardcoded 2.5 fails this outright.
  const a = L.tierRanges(boxGrid('small', 40), CTX);
  const b = L.tierRanges(boxGrid('large', 40), CTX);
  const ratios = ['blocks', 'hull', 'silhouette', 'point'].map(k => b[k] / a[k]);
  ok(ratios.every(x => Math.abs(x - 5) < 1e-9),
     'one step up the size ladder moves every boundary by exactly 5x',
     ratios.map(x => x.toFixed(3)).join(' / '));
  const cellRatio = G.cellM('large') / G.cellM('small');
  near(ratios[0], cellRatio, 1e-9, '...which is cellM(large)/cellM(small)');
}

/* ---- the tiers are ordered, with no inversions, at every size class ------- */
{
  let ordered = true, detail = '';
  for (const cls of G.SIZE_ORDER) {
    for (const n of [1, 2, 8, 40, 200]) {
      const r = L.tierRanges(boxGrid(cls, n), CTX);
      if (!(r.blocks <= r.hull && r.hull <= r.silhouette && r.silhouette <= r.point)) {
        ordered = false; detail = `${cls}/${n}: ${JSON.stringify(r)}`;
      }
    }
  }
  ok(ordered, 'tier ranges are ordered blocks <= hull <= silhouette <= point, all 25 cases', detail);

  // The degenerate case is EMPTY, never inverted: a one-cell drone has no
  // meaningful merged hull — the merge of one block is that block.
  const drone = L.tierRanges(boxGrid('micro', 1), CTX);
  ok(drone.blocks === drone.hull,
     'a one-cell drone has an EMPTY hull band, not a backwards one',
     `${drone.blocks.toFixed(2)} m`);
}

/* ---- THE SWEEP: walk the camera in and the detail may only go up --------- */
function monotonicViolations(seq) {
  let v = 0;
  for (let i = 1; i < seq.length; i++) if (seq[i] < seq[i - 1]) v++;
  return v;
}
{
  // the checker must be able to fail, or it certifies whatever it is given
  ok(monotonicViolations([0, 1, 1, 2, 1, 3]) === 1,
     'the monotonicity checker detects a tier that goes backwards');
  ok(monotonicViolations([0, 1, 2, 3, 4]) === 0, '...and passes a clean sequence');

  const g = boxGrid('world', 100);
  const seq = [], dists = [];
  for (let i = 400; i >= 0; i--) {                    // far -> near, log spaced
    const d = Math.pow(10, 2 + (i / 400) * 7);        // 1e9 m down to 100 m
    dists.push(d);
    seq.push(L.lodForGrid(g, d, CTX).tier);
  }
  ok(monotonicViolations(seq) === 0,
     'over 401 distances from 1e9 m to 100 m the tier never goes backwards');

  const distinct = [];
  for (const t of seq) if (t !== distinct[distinct.length - 1]) distinct.push(t);
  ok(distinct.length === 5 && distinct.join() === '0,1,2,3,4',
     'and it passes through every tier exactly once, in order',
     distinct.map(t => L.tierName(t)).join(' -> '));

  // a step function has exactly one change per boundary — no flicker anywhere
  let changes = 0;
  for (let i = 1; i < seq.length; i++) if (seq[i] !== seq[i - 1]) changes++;
  ok(changes === 4, 'four boundaries, four changes: it is a step function', `${changes}`);
}

/* ---- every non-empty band actually holds its own tier -------------------- */
{
  let bad = [];
  for (const cls of G.SIZE_ORDER) {
    const g = boxGrid(cls, 60);
    const r = L.tierRanges(g, CTX);
    const bands = [
      [L.TIER.BLOCKS, r.blocks * 1e-3, r.blocks],
      [L.TIER.HULL, r.blocks, r.hull],
      [L.TIER.SILHOUETTE, r.hull, r.silhouette],
      [L.TIER.POINT, r.silhouette, r.point],
      [L.TIER.HIDDEN, r.point, r.point * 1e3],
    ];
    for (const [tier, lo, hi] of bands) {
      if (!(hi > lo * 1.001)) continue;              // empty band, legitimately
      const mid = Math.sqrt(lo * hi);                // geometric middle
      const got = L.lodForGrid(g, mid, CTX).tier;
      if (got !== tier) bad.push(`${cls} ${L.tierName(tier)} -> ${L.tierName(got)}`);
    }
  }
  ok(bad.length === 0, 'inside every non-empty band, lodFor returns that band\'s tier',
     bad.join(' · '));
}

/* ---- a point of light must DIM, or it is a permanent dot ------------------ */
{
  const g = boxGrid('small', 12);
  const r = L.tierRanges(g, CTX);
  const a = L.lodForGrid(g, r.silhouette * 1.5, CTX);
  const b = L.lodForGrid(g, r.point * 0.9, CTX);
  ok(a.tier === L.TIER.POINT && b.tier === L.TIER.POINT, 'both samples are in the point band');
  ok(a.pointGain > b.pointGain * 50,
     'a point of light dims with the square of its angular size',
     `${a.pointGain.toExponential(2)} -> ${b.pointGain.toExponential(2)}`);
  ok(a.pointPx >= L.MIN_POINT_PX && b.pointPx >= L.MIN_POINT_PX,
     '...while never shrinking below a drawable sprite');
  ok(b.hdr < 0.95,
     'at the cull the spark is already below view.js\'s bloom threshold — culling is not a lie',
     `hdr ${b.hdr.toExponential(2)} vs threshold 0.95`);
  ok(L.lodForGrid(g, r.silhouette * 0.99, CTX).hdr >= 0.95,
     '...and at the top of the band it is above it, so it blooms');
}

/* ---- frames: the centre of the ship, not the corner of its grid ----------- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const g = boxGrid('large', 40);
  const s = A.makeShip('centre-probe', g, { pos: [0, 0, 0] });
  const c = L.centreOf(s);
  const expect = 40 * G.cellM('large') / 2;
  ok(Math.abs(c[0] - expect) < 1e-9 && Math.abs(c[1] - expect) < 1e-9,
     'a ship is measured from the middle of its hull, not its grid origin',
     `[${c.map(x => x.toFixed(1))}] m`);
  // a 180 degree turn about Y must move that centre, and by a known amount
  s.ori = [0, 1, 0, 0];
  const t = L.centreOf(s);
  near(t[0], -expect, 1e-9, '...and the offset rotates with the ship');
  ok(Math.abs(L.distanceTo(s, [0, 0, 0]) - Math.hypot(expect, expect, expect)) < 1e-9,
     'distanceTo agrees with it');
}

/* ---- HYSTERESIS: a ship parked on a boundary must not flicker ------------- */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const g = boxGrid('large', 60);
  const r = L.tierRanges(g, CTX);
  const off = L.centreLocalM(g);
  const s = A.makeShip('flicker', g, { pos: [0, 0, 0] });
  const put = (d) => { s.pos = [d - off[0], -off[1], -off[2]]; };

  const state = L.makeLodState();
  const held = [], raw = [];
  for (let i = 0; i < 50; i++) {
    const d = r.blocks * (i % 2 ? 1.02 : 0.98);       // straddling the boundary
    put(d);
    held.push(L.updateLod(state, s, [0, 0, 0], CTX).tier);
    raw.push(L.lodFor(s, [0, 0, 0], CTX).tier);
  }
  const flips = (a) => { let n = 0; for (let i = 1; i < a.length; i++) if (a[i] !== a[i - 1]) n++; return n; };
  ok(flips(held) === 0, 'across 50 frames straddling a boundary the tier never changes',
     `${flips(held)} changes`);
  // THE SAME CHECK, WITHOUT THE HYSTERESIS — it must fail, or it proves nothing
  ok(flips(raw) === 49, '...and the same input through the raw function flips every frame',
     `${flips(raw)} changes — the check can fail`);

  // but a real move must still cross: hysteresis holds, it does not stick
  put(r.blocks * 2);
  ok(L.updateLod(state, s, [0, 0, 0], CTX).tier === L.TIER.HULL,
     'a ship that genuinely leaves the band does change tier');
  put(r.blocks * 0.5);
  ok(L.updateLod(state, s, [0, 0, 0], CTX).tier === L.TIER.BLOCKS, '...and back again');
}

/* ---- the budget lowers detail, and never raises it ------------------------ */
{
  A.clearShips(); A.setWorldSeed('VESPER-1');
  const ships = [];
  for (let i = 0; i < 8; i++) {
    const g = boxGrid('large', 60);
    const off = L.centreLocalM(g);
    const d = 100 + i * 10;                            // all well inside BLOCKS
    const s = A.makeShip(`yard-${i}`, g, { pos: [d - off[0], -off[1], -off[2]] });
    ships.push(s);
  }
  const far = A.makeShip('distant', boxGrid('small', 12), { pos: [90000, 0, 0] });
  ships.push(far);

  const lods = L.assignLods(ships, [0, 0, 0], { ...CTX, blockBudget: 3 });
  const atBlocks = ships.filter(s => lods.get(s.id).tier === L.TIER.BLOCKS);
  ok(atBlocks.length === 3, 'a block budget of 3 leaves exactly three ships at full blocks',
     `${atBlocks.length} of 8 eligible`);
  ok(atBlocks.map(s => s.id).sort().join() === 'yard-0,yard-1,yard-2',
     '...and it keeps the three that are LARGEST on screen',
     atBlocks.map(s => s.id).join(' '));
  const demoted = ships.filter(s => lods.get(s.id).demoted);
  ok(demoted.length === 5 && demoted.every(s => lods.get(s.id).tier === L.TIER.HULL),
     'the rest fall exactly one tier, to a merged hull');
  ok(lods.get('distant').tier === L.TIER.POINT && !lods.get('distant').demoted,
     'a ship that was already a point of light is untouched by the budget');
  const uncapped = L.assignLods(ships, [0, 0, 0], { ...CTX, blockBudget: 99 });
  ok([...uncapped.values()].filter(l => l.tier === L.TIER.BLOCKS).length === 8,
     'and with headroom nothing is demoted at all');
}

/* ---- WHICH SCENE — the fleet must not vanish when you reach orbit -------- */
{
  const g = boxGrid('large', 60);
  const orbit = { ...CTX, nearPlane: 15500 };          // orbit.js's clamp
  ok(L.lodForGrid(g, 40000, orbit).slice === 'far',
     'beyond camera.near a ship is drawn in the FAR slice',
     'nearScene would clip it away and the fleet would silently disappear');
  ok(L.lodForGrid(g, 200, orbit).slice === 'near', 'inside it, in the near slice');
  ok(L.lodForGrid(g, 40000, CTX).slice === 'near',
     'with no near plane handed in, everything is near (the ground case)');
}

/* ---- the silhouette's coarseness ----------------------------------------- */
{
  const small = L.silhouetteCoarsen(boxGrid('large', 8));
  const big = L.silhouetteCoarsen(boxGrid('world', 200));
  ok(small >= 1 && big >= 1, 'coarsening is always at least one cell', `${small} / ${big}`);
  ok(big > small, 'a bigger grid is fused harder', `${small} -> ${big}`);
  const cells = Math.ceil(200 / big);
  ok(2 * 3 * cells * cells <= 200 + 1,
     'and the result meets the face budget it was given',
     `${2 * 3 * cells * cells} faces`);
}

/* ---- an empty grid is not a crash ---------------------------------------- */
{
  const empty = G.makeGrid('large', 'empty');
  const l = L.lodForGrid(empty, 100, CTX);
  ok(l.tier === L.TIER.HIDDEN && l.angle === 0,
     'an empty grid is hidden and subtends nothing, rather than throwing');
  ok(L.boundingRadiusM(empty) === 0, 'and has no radius');
}

/* ---- purity in the sense that matters: no hidden state ------------------- */
{
  const g = boxGrid('large', 60);
  const a = L.lodForGrid(g, 5000, CTX), b = L.lodForGrid(g, 5000, CTX);
  ok(a.tier === b.tier && a.shipAngle === b.shipAngle,
     'the same question twice gives the same answer — lodFor holds no state');
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
