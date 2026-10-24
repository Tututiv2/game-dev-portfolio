/* CONSTRUCTION — weld and grind, headless. No GPU, no browser, no puppeteer.

   The check this file exists for is THE FREE LUNCH one: weld -> grind -> weld
   must strictly lose material. That check is run twice — once against the real
   grind, and once against a mutant grind that hands back 100% — because a
   measurement that cannot detect a defect will certify it, which is this
   project's own most expensive lesson (a lost GPU context once "proved" the
   height field 20% wrong for a week).

   node tests/ships_construction.mjs
*/
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHIPS = path.join(ROOT, 'src', 'ships');
const imp = (f) => import(pathToFileURL(path.join(SHIPS, f)).href);

let pass = 0, fail = 0;
const ok = (cond, name, detail = '') => {
  if (cond) { pass++; console.log(`  PASS  ${name}${detail ? '  ' + detail : ''}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? '  ' + detail : ''}`); }
};
const near = (a, b, tol, name, detail) => ok(Math.abs(a - b) <= tol, name,
  detail !== undefined ? detail : `${a} vs ${b} (tol ${tol})`);

const G = await imp('grid.js');
const A = await imp('api.js');
const C = await imp('construction.js');

console.log('\n  CONSTRUCTION — weld, grind, and the cost of both\n');

/* Helpers -------------------------------------------------------------------- */
const armed = (inv) => ({ welder: 1, ...inv });          // a hand with a tool in it
const kg = (n) => `${n.toFixed(3)} kg`;

/** A ship with a finished reactor + welder arm, and whatever stock is asked. */
function rig(cls = 'large', stock = { steel: 100000, circuit: 100000, alloy: 100000 }) {
  A.clearShips();
  const g = G.makeGrid(cls, 'rig');
  G.addBlock(g, 'hull', [0, 0, 0]);
  G.addBlock(g, 'reactor', [1, 0, 0]);      // 2x2x2, powers the arm
  G.addBlock(g, 'welder', [0, 0, 1]);
  const s = A.makeShip(`ship-${cls}`, g);
  s.data.cons.stock = { ...stock };
  return s;
}

/* ---- the ladder: rate and reach come from the size class ------------------- */
{
  near(C.weldRate('world') / C.weldRate('large'), 15625, 1e-9,
       'weld rate scales with the cube of the cell, like mass does',
       `${C.weldRate('large')} -> ${C.weldRate('world')} kg/s`);
  ok(C.reachOf('large') === G.SIZE_CLASSES.large.reach &&
     C.reachOf('world') === G.SIZE_CLASSES.world.reach,
     'reach is SIZE_CLASSES[cls].reach and nothing else',
     `large ${C.reachOf('large')} m · world ${C.reachOf('world')} m`);

  // one block, one tool of its own class -> the same wall time at every class
  const t = [];
  for (const cls of G.SIZE_ORDER) {
    const g = G.makeGrid(cls);
    const b = C.placeFrame({ grid: g, pos: [0,0,0], ori: [0,0,0,1], data: {} }, 'hull', [0,0,0]);
    t.push(C.fullCost(g, b) / C.weldRate(cls));
  }
  ok(t.every(x => Math.abs(x - t[0]) < 1e-9),
     'so one block takes the same time at EVERY class with the right tool',
     `${t.map(x => x.toFixed(2) + 's').join(' · ')}`);

  // ...and the wrong tool is exactly as bad as the volume ratio says
  near(C.weldRate('large') / C.weldRate('small'), 125, 1e-9,
       'and a hand welder on a large grid is 125x slower than the arm',
       'building a world-ship one nudge at a time is not a design');

  // no hardcoded 2.5 anywhere in the file (DESIGN.md: B6 would be a rewrite)
  const src = fs.readFileSync(path.join(SHIPS, 'construction.js'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  ok(!/(^|[^\w.])2\.5([^\d]|$)/.test(src), 'construction.js never hardcodes 2.5');
}

/* ---- what a block costs ---------------------------------------------------- */
{
  let sums = true;
  for (const id in C.MIX) {
    let s = 0; for (const k in C.MIX[id]) s += C.MIX[id][k];
    if (Math.abs(s - 1) > 1e-12) sums = false;
  }
  ok(sums, 'every material mix is fractions by mass summing to 1');
  ok(C.mixOf('a-block-no-one-told-me-about').steel === 1,
     'a block type from another agent costs plain steel rather than throwing');

  const gl = G.makeGrid('large'), gw = G.makeGrid('world');
  const bl = G.addBlock(gl, 'hull', [0, 0, 0]), bw = G.addBlock(gw, 'hull', [0, 0, 0]);
  near(C.fullCost(gl, bl), 100, 1e-9, 'a large hull plate costs its own 100 kg');
  near(C.fullCost(gw, bw) / C.fullCost(gl, bl), 15625, 1e-6,
       'a world one costs 15,625 of them', kg(C.fullCost(gw, bw)));
  const c = C.costOf(gl, G.addBlock(gl, 'cockpit', [1, 0, 0]));
  near(c.steel + c.circuit, 260 * 2, 1e-9,
       'a multi-cell block costs its whole mass, split by mix',
       `steel ${c.steel} + circuit ${c.circuit}`);
}

/* ---- a framework holds nothing --------------------------------------------- */
{
  const s = rig();
  const b = C.placeFrame(s, 'hull', [0, 0, 2]);
  ok(b !== null, 'a framework can be placed');
  ok(C.integrity(b) === 0 && b.hp === 0, 'and it holds no material at all');
  ok(b.frame === true, '...flagged so a damage system can tell it from wreckage');
  ok(!C.isFunctional(b), '...and it does not work yet');
  const built = C.builtMass(s.grid), total = G.mass(s.grid);
  ok(built < total, 'builtMass() knows that; grid.mass() does not (contract gap 2)',
     `${kg(built)} of ${kg(total)}`);
}

/* ---- THE DESIGN LAW: no tool, no capability -------------------------------- */
{
  const s = rig();
  const b = C.placeFrame(s, 'hull', [0, 0, 2]);
  const before = s.data.cons.stock.steel;
  const r = C.weld(s, null, b, 1);
  ok(r.kg === 0 && C.integrity(b) === 0 && s.data.cons.stock.steel === before,
     'welding with no tool builds nothing — there is no empty-handed weld', r.reason);

  ok(C.handTool({ steel: 9999 }, [0, 0, 0]) === null,
     'a hand welder is refused unless the inventory HOLDS a welder item');
  ok(C.handTool(armed({}), [0, 0, 0]) !== null, '...and granted when it does');

  // a welder arm must itself be finished, and the grid must be making power
  const arm = G.blockAt(s.grid, 0, 0, 1);
  ok(C.toolFromBlock(s, arm, A.API) !== null, 'a finished, powered welder arm is a tool');
  arm.hp = C.maxHp(arm) * 0.5;
  ok(C.toolFromBlock(s, arm, A.API) === null,
     '...but a half-welded welder is not — you cannot bootstrap one out of itself');
  arm.hp = C.maxHp(arm);

  A.clearShips();
  const g2 = G.makeGrid('large', 'dark');
  G.addBlock(g2, 'hull', [0, 0, 0]);
  G.addBlock(g2, 'welder', [1, 0, 0]);
  const s2 = A.makeShip('dark', g2);
  ok(C.toolFromBlock(s2, G.blockAt(g2, 1, 0, 0), A.API) === null,
     '...and an unpowered one is not either', `power ok=${G.power(g2).ok}`);
}

/* ---- welding costs exactly what the block is worth ------------------------- */
{
  const s = rig();
  const tool = C.toolFromBlock(s, G.blockAt(s.grid, 0, 0, 1), A.API);
  const b = C.placeFrame(s, 'hull', [0, 0, 2]);
  const start = s.data.cons.stock.steel;
  let t = 0, done = false;
  for (let i = 0; i < 1000 && !done; i++) { done = C.weld(s, tool, b, 0.1).done; t += 0.1; }
  ok(done && C.integrity(b) === 1, 'welding finishes the block', `${t.toFixed(1)} s`);
  near(start - s.data.cons.stock.steel, 100, 1e-9,
       '...having spent exactly its mass in components', kg(start - s.data.cons.stock.steel));
  near(t, 100 / C.weldRate('large'), 0.11, '...at the tool\'s rated speed');
  ok(b.hp === C.maxHp(b), '...and it ends at full hp, exactly');
  ok(C.isFunctional(b), '...and only NOW does it count as working');
}

/* ---- the three things that stop a weld ------------------------------------- */
{
  const s = rig('large', { steel: 0 });
  const tool = C.toolFromBlock(s, G.blockAt(s.grid, 0, 0, 1), A.API);
  const b = C.placeFrame(s, 'hull', [0, 0, 2]);
  const r = C.weld(s, tool, b, 10);
  ok(r.kg === 0 && C.integrity(b) === 0 && (s.data.cons.stock.steel || 0) === 0,
     'an empty stock welds nothing and never goes negative', r.reason);

  const far = { ...tool, pos: [10000, 0, 0] };
  const s2 = rig();
  const b2 = C.placeFrame(s2, 'hull', [0, 0, 2]);
  const r2 = C.weld(s2, far, b2, 10);
  ok(r2.kg === 0, 'a block beyond reach cannot be welded', r2.reason);
  ok(C.distanceTo(s2, tool, b2) < tool.reach,
     '...while the same block within reach can', `${C.distanceTo(s2, tool, b2).toFixed(2)} m`);

  // partial: only pay for what is affordable this step
  const s3 = rig('large', { steel: 30 });
  const t3 = C.toolFromBlock(s3, G.blockAt(s3.grid, 0, 0, 1), A.API);
  const b3 = C.placeFrame(s3, 'hull', [0, 0, 2]);
  C.weld(s3, t3, b3, 10);
  near(C.integrity(b3), 0.3, 1e-9, 'a short stock builds a partial block, not a whole one',
       `${(C.integrity(b3) * 100).toFixed(1)}%`);
  near(s3.data.cons.stock.steel, 0, 1e-9, '...spending all of it and no more');
}

/* ---- grinding returns a FRACTION ------------------------------------------- */
{
  const s = rig();
  const tool = C.toolFromBlock(s, G.blockAt(s.grid, 0, 0, 1), A.API);
  const b = C.placeFrame(s, 'hull', [0, 0, 2]);
  while (!C.weld(s, tool, b, 0.5).done);
  const after = s.data.cons.stock.steel;
  let g = null;
  for (let i = 0; i < 1000 && !(g && g.removed); i++) g = C.grind(s, tool, b, 0.1);
  const back = s.data.cons.stock.steel - after;
  near(back, 100 * C.RECLAIM, 1e-9, 'grinding returns RECLAIM of the material, not all of it',
       `${kg(back)} of 100 · RECLAIM ${C.RECLAIM}`);
  ok(C.RECLAIM < 1, '...and RECLAIM is below 1 by construction');
  ok(g.removed && G.blockAt(s.grid, 0, 0, 2) === null,
     'grinding to nothing takes the block off the grid');

  // a framework that never held material grinds away for free and pays nothing
  const s2 = rig('large', { steel: 0 });
  const t2 = C.toolFromBlock(s2, G.blockAt(s2.grid, 0, 0, 1), A.API);
  C.placeFrame(s2, 'hull', [0, 0, 2]);
  const r = C.grind(s2, t2, G.blockAt(s2.grid, 0, 0, 2), 1);
  ok(r.removed && Object.keys(r.gained).length === 0 && (s2.data.cons.stock.steel || 0) === 0,
     'and a place/remove loop prints nothing at all', 'material only moves in weld/grind');

  // damage destroys material: a half-dead block grinds back to half
  const s3 = rig();
  const t3 = C.toolFromBlock(s3, G.blockAt(s3.grid, 0, 0, 1), A.API);
  const b3 = C.placeFrame(s3, 'hull', [0, 0, 2]);
  while (!C.weld(s3, t3, b3, 0.5).done);
  const mark = s3.data.cons.stock.steel;
  b3.hp = C.maxHp(b3) * 0.5;                       // something shot it
  let gg = null;
  for (let i = 0; i < 1000 && !(gg && gg.removed); i++) gg = C.grind(s3, t3, b3, 0.1);
  near(s3.data.cons.stock.steel - mark, 100 * 0.5 * C.RECLAIM, 1e-9,
       'a damaged block grinds back to less — damage burns material');
}

/* ---- THE FREE LUNCH CHECK, AND PROOF IT CAN FAIL --------------------------- */
{
  /* One detector, two grinds. If the detector cannot flag a 100%-reclaim grind
     then it was never evidence about the real one. */
  function cycles(grindImpl, n = 5) {
    const s = rig('large', { steel: 1000 });
    const tool = C.toolFromBlock(s, G.blockAt(s.grid, 0, 0, 1), A.API);
    const start = s.data.cons.stock.steel;
    const trail = [start];
    for (let c = 0; c < n; c++) {
      const b = C.placeFrame(s, 'hull', [0, 0, 2]);
      while (!C.weld(s, tool, b, 0.5).done);
      let g = null;
      for (let i = 0; i < 1000 && !(g && g.removed); i++) g = grindImpl(s, tool, b, 0.5);
      trail.push(s.data.cons.stock.steel);
    }
    return { start, end: s.data.cons.stock.steel, trail };
  }
  const loses = (r) => r.trail.every((v, i) => i === 0 || v < r.trail[i - 1] - 1e-9);

  const real = cycles(C.grind);
  ok(loses(real), 'weld -> grind -> weld STRICTLY loses material, every cycle',
     real.trail.map(v => v.toFixed(0)).join(' -> '));
  near(real.start - real.end, 5 * 100 * (1 - C.RECLAIM), 1e-9,
       '...by exactly (1 - RECLAIM) of what was committed, five times over');

  /* The mutant: the real grind with the slag handed back. This is the bug the
     check exists to catch, and two other projects in this portfolio shipped it. */
  const mutantGrind = (ship, tool, block, dt, inv) => {
    const r = C.grind(ship, tool, block, dt, inv);
    const store = inv || ship.data.cons.stock;
    for (const k in r.gained) store[k] += r.gained[k] * (1 / C.RECLAIM - 1);
    return r;
  };
  const mutant = cycles(mutantGrind);
  ok(!loses(mutant), 'the same check FAILS against a 100%-reclaim grind — it has teeth',
     mutant.trail.map(v => v.toFixed(0)).join(' -> '));
  near(mutant.end, mutant.start, 1e-9, '...which would have been a printer',
       `${mutant.start} -> ${mutant.end}`);

  // and a PARTIAL cycle is not a loophole either
  const s = rig('large', { steel: 1000 });
  const tool = C.toolFromBlock(s, G.blockAt(s.grid, 0, 0, 1), A.API);
  const b = C.placeFrame(s, 'hull', [0, 0, 2]);
  const before = s.data.cons.stock.steel;
  for (let i = 0; i < 40; i++) { C.weld(s, tool, b, 0.4); C.grind(s, tool, b, 0.2); }
  ok(s.data.cons.stock.steel < before - 1e-9,
     'half-welding and half-grinding on a loop loses too',
     `${before} -> ${s.data.cons.stock.steel.toFixed(2)}`);
}

/* ---- the welder ARM works on its own, because it is a machine -------------- */
{
  const s = rig();
  const b = C.placeFrame(s, 'hull', [0, 0, 2]);
  const start = s.data.cons.stock.steel;
  for (let i = 0; i < 200 && C.integrity(b) < 1; i++) A.step(1 / 30);
  ok(C.integrity(b) === 1, 'a welder arm on the ship builds a framework by itself');
  near(start - s.data.cons.stock.steel, 100, 1e-9, '...paying the same price');

  // no welder -> nothing happens. This is not an "auto-build" button.
  A.clearShips();
  const g = G.makeGrid('large', 'bare');
  G.addBlock(g, 'hull', [0, 0, 0]);
  G.addBlock(g, 'reactor', [1, 0, 0]);
  const s2 = A.makeShip('bare', g);
  s2.data.cons.stock = { steel: 1000 };
  const b2 = C.placeFrame(s2, 'hull', [0, 0, 1]);
  for (let i = 0; i < 200; i++) A.step(1 / 30);
  ok(C.integrity(b2) === 0 && s2.data.cons.stock.steel === 1000,
     'with no welder block, the ship builds nothing — no press-go button exists');

  // no material -> nothing happens, and nothing goes negative
  const s3 = rig('large', { steel: 0 });
  const b3 = C.placeFrame(s3, 'hull', [0, 0, 2]);
  for (let i = 0; i < 100; i++) A.step(1 / 30);
  ok(C.integrity(b3) === 0 && (s3.data.cons.stock.steel || 0) === 0,
     'and with no material it builds nothing, rather than borrowing');
}

/* ---- frames are planet-frame, and use cellM ------------------------------- */
{
  const s = rig();
  s.pos = [1000, 0, 0];
  const b = G.blockAt(s.grid, 0, 0, 0);
  const p = C.blockPos(s, b);
  const half = G.cellM(s.grid.sizeClass) / 2;
  ok(Math.abs(p[0] - (1000 + half)) < 1e-9 && Math.abs(p[1] - half) < 1e-9,
     'a block position is ship.pos + cell centre, in the PLANET frame',
     `[${p.map(x => x.toFixed(2))}]`);

  // rotate the ship 180 degrees about Y: the block must swing to the other side
  s.ori = [0, 1, 0, 0];
  const q = C.blockPos(s, b);
  ok(Math.abs(q[0] - (1000 - half)) < 1e-9 && Math.abs(q[1] - half) < 1e-9,
     '...and the ship\'s orientation actually rotates it', `[${q.map(x => x.toFixed(2))}]`);
}

/* ---- save and load --------------------------------------------------------- */
{
  const s = rig();
  const tool = C.toolFromBlock(s, G.blockAt(s.grid, 0, 0, 1), A.API);
  const b = C.placeFrame(s, 'hull', [0, 0, 2]);
  C.weld(s, tool, b, 0.8);                        // 40% of a 100 kg plate
  const saved = JSON.parse(JSON.stringify(C.def.save(s)));
  const frac = C.integrity(b);
  ok(frac > 0 && frac < 1, 'a half-welded block exists', `${(frac * 100).toFixed(0)}%`);

  b.hp = C.maxHp(b);                              // pretend a reload finished it
  s.data.cons.stock.steel = 7;
  C.def.load(s, saved);
  near(C.integrity(b), frac, 1e-12,
       'load() restores partial integrity — a reload is not a free finish');
  near(s.data.cons.stock.steel, saved.stock.steel, 1e-12, '...and the stock with it');

  const round = JSON.parse(JSON.stringify(s.data));
  ok(JSON.stringify(round) === JSON.stringify(s.data), 'ship.data stays JSON-safe');
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
