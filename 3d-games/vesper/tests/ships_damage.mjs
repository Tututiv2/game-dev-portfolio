/* DAMAGE — headless. No GPU, no browser, no puppeteer.

   B2's gate is "cut it in half — both halves behave". This suite proves the
   CUT: a block dies, leaves the grid, the grid is asked whether it is still one
   thing, and when it is not, the pieces come out as real ships with no block
   and no kilogram lost between them.

   The project's own hard-won lesson (CLAUDE.md trap 13) is that a measurement
   which cannot detect a defect will certify it. So three checks here are run
   against deliberately broken input to prove they can still FAIL: the purity
   grep, the mass-conservation comparator, and the determinism comparator.

   node tests/ships_damage.mjs
*/
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import * as G from '../src/ships/grid.js';
import * as A from '../src/ships/api.js';
import * as D from '../src/ships/damage.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let pass = 0, fail = 0;
const ok = (cond, name, detail = '') => {
  if (cond) { pass++; console.log(`  PASS  ${name}${detail ? '  ' + detail : ''}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? '  ' + detail : ''}`); }
};
const near = (a, b, tol, name, detail) => ok(Math.abs(a - b) <= tol, name,
  detail !== undefined ? detail : `${a} vs ${b} (tol ${tol})`);

console.log('\n  DAMAGE — blocks dying and grids coming apart, headless\n');

/* ---- a spy system, so the hooks and the events can be observed ----------- */
const log = { events: [], splits: [], removed: [] };
const resetLog = () => { log.events = []; log.splits = []; log.removed = []; };
A.registerSystem({
  id: '_spy',
  onEvent: (evt, payload) => log.events.push({ evt, payload }),
  onSplit: (parent, children) => log.splits.push({ parent, children }),
  onBlockRemoved: (ship, block) => log.removed.push({ ship, block }),
});
A.setWorldSeed('VESPER-1');

const api = A.API;
const beam = (n, cls = 'large', id = 'g') => {
  const g = G.makeGrid(cls, id);
  for (let x = 0; x < n; x++) G.addBlock(g, 'hull', [x, 0, 0]);
  return g;
};
const hpVec = (grid) => G.uniqueBlocks(grid).map(b => b.hp);

/* ---- purity ------------------------------------------------------------- */
{
  const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const src = strip(fs.readFileSync(path.join(ROOT, 'src', 'ships', 'damage.js'), 'utf8'));
  const banned = [/\bfrom ['"]three['"]/, /\bdocument\b/, /\bwindow\b/,
                  /Math\.random/, /\bnew Date\b/, /Date\.now/];
  const bad = banned.filter(re => re.test(src)).map(String);
  ok(bad.length === 0, 'damage.js is pure', bad.join(' · '));

  // and the grep can still catch a real one — an instrument that cannot fail
  ok(banned.some(re => re.test('const r = Math.random();')),
     'the purity grep can still detect a real violation');

  // trap: "never hardcode 2.5" — the cell size may not appear in the CODE
  ok(!/(^|[^.\w])2\.5([^\d]|$)/.test(src), 'damage.js never hardcodes 2.5');
}

/* ---- hp is scaled the way mass is ---------------------------------------- */
{
  A.clearShips();
  const lg = G.makeGrid('large', 'a'); G.addBlock(lg, 'hull', [0, 0, 0]);
  const sh = A.makeShip('hp-large', lg);
  near(G.uniqueBlocks(sh.grid)[0].hpMax, 300, 1e-9,
       'a large hull plate is the 300 hp reference');

  const wd = G.makeGrid('world', 'b'); G.addBlock(wd, 'hull', [0, 0, 0]);
  const ws = A.makeShip('hp-world', wd);
  near(G.uniqueBlocks(ws.grid)[0].hpMax, 300 * 15625, 1e-3,
       'a world hull plate is 25^3 of it, exactly as its mass is',
       `${G.uniqueBlocks(ws.grid)[0].hpMax} hp`);

  const tg = G.makeGrid('large', 'c'); G.addBlock(tg, 'thruster', [0, 0, 0], 0);
  const ts = A.makeShip('hp-thruster', tg);
  near(G.uniqueBlocks(ts.grid)[0].hpMax, 500, 1e-9,
       'a 1x1x2 block carries hp per CELL, like its mass', '250 x 2 cells');
  ok(D.integrity(ts, api) === 1, 'a fresh ship is at full integrity');
}

/* ---- one block takes a hit ----------------------------------------------- */
{
  A.clearShips(); resetLog();
  const s = A.makeShip('hit', beam(3));
  const r1 = D.applyDamage(s, [1, 0, 0], 100, api);
  near(r1.hpAfter, 200, 1e-9, 'damage comes off hp', `${r1.hpBefore} -> ${r1.hpAfter}`);
  ok(!r1.destroyed && s.grid.blocks.size === 3, '...and the block is still there');
  near(D.integrity(s, api), 800 / 900, 1e-9, 'integrity tracks it',
       D.integrity(s, api).toFixed(4));

  const r2 = D.applyDamage(s, [1, 0, 0], 200, api);
  ok(r2.destroyed, 'reaching zero destroys it');
  ok(G.blockAt(s.grid, 1, 0, 0) === null, '...and it leaves the grid');
  ok(log.events.filter(e => e.evt === 'blockDestroyed').length === 1,
     "...emitting 'blockDestroyed' exactly once");
  ok(log.removed.length === 1, '...and calling onBlockRemoved exactly once');
  ok(D.applyDamage(s, [9, 9, 9], 100, api) === null, 'damaging empty space is a no-op');
}

/* ---- a multi-cell block dies all at once --------------------------------- */
{
  A.clearShips(); resetLog();
  const g = G.makeGrid('large', 'm');
  G.addBlock(g, 'hull', [0, 0, 0]);
  G.addBlock(g, 'thruster', [1, 0, 0], 0);          // occupies (1,0,0) and (1,0,1)
  const s = A.makeShip('multi', g);
  ok(s.grid.blocks.size === 3, 'the thruster occupies two cells', `${s.grid.blocks.size}`);
  D.applyDamage(s, [1, 0, 1], 10000, api);          // hit it on its SECOND cell
  ok(s.grid.blocks.size === 1, 'destroying it from either cell removes all of it',
     `${s.grid.blocks.size} cells left`);
  ok(log.events.filter(e => e.evt === 'blockDestroyed').length === 1,
     '...and it is one death, not two');
}

/* ---- resistance is honoured, and can never be total ---------------------- */
{
  G.defineBlock({ id: 'test_armour_dmg', name: 'test armour', hp: 400, resist: 0.5 });
  G.defineBlock({ id: 'test_invuln_dmg', name: 'test invuln', hp: 400, resist: 9 });
  A.clearShips();
  const g = G.makeGrid('large', 'r');
  G.addBlock(g, 'test_armour_dmg', [0, 0, 0]);
  G.addBlock(g, 'test_invuln_dmg', [1, 0, 0]);
  const s = A.makeShip('armoured', g);
  const a = D.applyDamage(s, [0, 0, 0], 100, api);
  near(a.damage, 50, 1e-9, 'resist 0.5 halves incoming damage', `${a.damage}`);
  const b = D.applyDamage(s, [1, 0, 0], 100, api);
  ok(b.damage > 0, 'resist is clamped, so nothing is ever invulnerable',
     `resist 9 still took ${b.damage} damage`);
}

/* ---- THE GATE: cut it in half -------------------------------------------- */
{
  A.clearShips(); resetLog();
  const g = G.makeGrid('large', 'cut');
  for (let x = 0; x < 5; x++) G.addBlock(g, 'hull', [x, 0, 0]);
  G.addBlock(g, 'cockpit', [0, 0, 1], 0);            // cells (0,0,1) (0,0,2)
  const s = A.makeShip('cutme', g, { pos: [10, 20, 30], owner: 'player' });
  s.vel = [1, 2, 3];
  const massBefore = G.mass(s.grid);
  const blocksBefore = G.uniqueBlocks(s.grid).length;
  const deadMass = G.blockMass(s.grid, G.blockAt(s.grid, 2, 0, 0));

  ok(G.islands(s.grid).length === 1, 'the ship starts as one island');
  const before = A.ships().length;
  const r = D.applyDamage(s, [2, 0, 0], 10000, api);
  ok(r.destroyed, 'the middle block dies');

  const kids = log.splits.length ? log.splits[0].children : [];
  ok(log.splits.length === 1, 'onSplit fires exactly once', `${log.splits.length}`);
  ok(log.events.filter(e => e.evt === 'split').length === 1,
     "...and 'split' is emitted exactly once");
  ok(kids.length === 1, 'one cut makes one new ship', `${kids.length}`);
  ok(A.ships().length === before + 1, '...which is a real ship in api.ships()',
     `${before} -> ${A.ships().length}`);

  const conserves = (parent, children, before_, dead) => {
    const m = G.mass(parent.grid) + children.reduce((t, c) => t + G.mass(c.grid), 0);
    return Math.abs(m - (before_ - dead)) < 1e-9;
  };
  ok(conserves(s, kids, massBefore, deadMass),
     'mass is conserved across the pieces, minus the block that died',
     `${(G.mass(s.grid) + G.mass(kids[0].grid)).toFixed(1)} kg of ${massBefore.toFixed(1)}`);
  // the conservation check must be able to FAIL
  ok(!conserves(s, kids, massBefore, 0),
     '...and that conservation check can still fail');

  const blocksAfter = G.uniqueBlocks(s.grid).length +
                      kids.reduce((t, c) => t + G.uniqueBlocks(c.grid).length, 0);
  ok(blocksAfter === blocksBefore - 1, 'every surviving block is in exactly one piece',
     `${blocksAfter} of ${blocksBefore - 1}`);
  ok(G.islands(s.grid).length === 1 && G.islands(kids[0].grid).length === 1,
     'both halves are themselves whole');

  ok(kids[0].pos.join() === s.pos.join() && kids[0].pos !== s.pos,
     'the child inherits the position by value, not by reference');
  ok(kids[0].vel.join() === '1,2,3', '...and the parent motion');
  ok(kids[0].grid.sizeClass === 'large', '...and the size class');
  ok(s.grid.id === 'cut', "the parent's grid keeps its id");
  ok(kids[0].grid.id !== 'cut' && kids[0].id !== s.id, '...and the child gets its own');
  ok(kids[0].owner === 'debris', 'a piece with no cockpit is wreckage, not a ship',
     kids[0].owner);
  ok(s.data.damage.splits === 1, 'the split is counted on the parent');
}

/* ---- the parent keeps the piece the pilot is in -------------------------- */
{
  A.clearShips(); resetLog();
  const g = G.makeGrid('large', 'pilot');
  // long tail of hull to the +X side, cockpit alone on the -X end
  G.addBlock(g, 'cockpit', [0, 0, 0], 0);
  for (let x = 1; x <= 6; x++) G.addBlock(g, 'hull', [x, 0, 0]);
  const s = A.makeShip('pilot', g, { owner: 'player' });
  D.applyDamage(s, [1, 0, 0], 10000, api);
  const kids = log.splits[0].children;
  ok(G.uniqueBlocks(s.grid).length === 1 && kids.length === 1,
     'the smaller piece is the one with the cockpit', `parent 1 block, child 5`);
  ok(G.uniqueBlocks(s.grid)[0].type === 'cockpit',
     'the parent keeps it anyway — the pilot is not handed to a child ship');
  ok(G.uniqueBlocks(kids[0].grid).length === 5, 'the child gets the bigger tail',
     `${G.uniqueBlocks(kids[0].grid).length} blocks`);
  ok(kids[0].owner === 'debris', 'and the tail is wreckage');
}

/* ---- a piece that still has a cockpit is still somebody's ship ----------- */
{
  A.clearShips(); resetLog();
  const g = G.makeGrid('large', 'two');
  G.addBlock(g, 'cockpit', [0, 0, 0], 0);            // (0,0,0) (0,0,1)
  G.addBlock(g, 'hull', [0, 0, 2]);
  G.addBlock(g, 'cockpit', [0, 0, 3], 0);            // (0,0,3) (0,0,4)
  const s = A.makeShip('twoheads', g, { owner: 'player' });
  D.applyDamage(s, [0, 0, 2], 10000, api);
  const kids = log.splits[0].children;
  ok(kids.length === 1 && kids[0].owner === 'player',
     'a half that kept a cockpit keeps the owner too', kids[0].owner);
}

/* ---- nothing comes apart when nothing comes apart ------------------------ */
{
  A.clearShips(); resetLog();
  const g = G.makeGrid('large', 'solid');
  for (let x = 0; x < 3; x++) for (let y = 0; y < 2; y++) G.addBlock(g, 'hull', [x, y, 0]);
  const s = A.makeShip('solid', g);
  const before = A.ships().length;
  D.applyDamage(s, [0, 0, 0], 10000, api);           // a corner off a slab
  ok(G.islands(s.grid).length === 1, 'a slab losing a corner is still one island');
  ok(log.splits.length === 0 && log.events.filter(e => e.evt === 'split').length === 0,
     '...so no split is emitted');
  ok(A.ships().length === before, '...and no ship is created');
}

/* ---- the last block ------------------------------------------------------ */
{
  A.clearShips(); resetLog();
  const g = G.makeGrid('large', 'last'); G.addBlock(g, 'hull', [0, 0, 0]);
  const s = A.makeShip('last', g);
  D.applyDamage(s, [0, 0, 0], 10000, api);
  ok(s.grid.blocks.size === 0, 'the last block can die');
  ok(s.data.damage.dead === true, '...and the ship is flagged dead');
  ok(log.events.filter(e => e.evt === 'shipDestroyed').length === 1,
     "...emitting 'shipDestroyed' once");
  D.applyDamage(s, [0, 0, 0], 10000, api);
  ok(log.events.filter(e => e.evt === 'shipDestroyed').length === 1,
     '...and only once, however hard you keep hitting it');
}

/* ---- a blast is measured in METRES, so the size class decides the reach --- */
{
  A.clearShips();
  const mk = (cls) => {
    const g = G.makeGrid(cls, 'b' + cls);
    for (let x = 0; x < 4; x++) G.addBlock(g, 'hull', [x, 0, 0]);
    return A.makeShip('blast-' + cls, g);
  };
  const lg = mk('large'), sm = mk('small');
  const rLg = D.damageSphere(lg, [0, 0, 0], 3.0, 1000, api);
  const rSm = D.damageSphere(sm, [0, 0, 0], 3.0, 1000, api);
  ok(rSm.hits.length > rLg.hits.length,
     'the same 3 m blast reaches more cells on a small grid than a large one',
     `small ${rSm.hits.length} vs large ${rLg.hits.length}`);
  ok(rLg.hits.length > 0, '...and it does reach the large grid at all');

  // falloff: nearer blocks take more
  A.clearShips();
  const g = G.makeGrid('large', 'fall');
  for (let x = 0; x < 4; x++) G.addBlock(g, 'hull', [x, 0, 0]);
  const s = A.makeShip('falloff', g);
  const r = D.damageSphere(s, [1.25, 1.25, 1.25], 9, 200, api);
  const d0 = r.hits.find(h => h.cell[0] === 0).damage;
  const d3 = r.hits.find(h => h.cell[0] === 3).damage;
  ok(d0 > d3 && d3 > 0, 'a blast falls off with distance',
     `${d0.toFixed(1)} at the centre vs ${d3.toFixed(1)} at the rim`);
  const zero = D.damageSphere(s, [0, 0, 0], 0, 1e6, api);
  ok(zero.hits.length === 0, 'a zero-radius blast hits nothing');
}

/* ---- determinism: the same seed, the same wreck -------------------------- */
{
  const run = (seed) => {
    A.clearShips();
    A.setWorldSeed(seed);
    const g = G.makeGrid('large', 'det');
    for (let x = 0; x < 5; x++) G.addBlock(g, 'hull', [x, 0, 0]);
    const s = A.makeShip('det-ship', g);
    D.damageSphere(s, [3, 0, 0], 20, 150, api, { variance: 0.4 });
    return hpVec(s.grid);
  };
  const a1 = run('VESPER-1'), a2 = run('VESPER-1'), a3 = run('OTHER-SEED');
  const same = (x, y) => x.length === y.length && x.every((v, i) => v === y[i]);
  ok(same(a1, a2), 'a variance blast replays identically from the same seed',
     a1.map(v => v.toFixed(2)).join(' '));
  ok(!same(a1, a3), '...and follows the world seed', a3.map(v => v.toFixed(2)).join(' '));
  ok(a1.some((v, i) => v !== a1[(i + 1) % a1.length]),
     '...while variance really did vary the hits');
  // the determinism comparator must be able to fail
  ok(!same([1, 2, 3], [1, 2, 4]), '...and that comparator can still fail');
  A.setWorldSeed('VESPER-1');
}

/* ---- a whole frame of hits costs ONE flood fill -------------------------- */
{
  A.clearShips(); resetLog();
  const g = G.makeGrid('large', 'q');
  for (let x = 0; x < 7; x++) G.addBlock(g, 'hull', [x, 0, 0]);
  const s = A.makeShip('queued', g);
  D.queueDamage(s, [2, 0, 0], 10000);
  D.queueDamage(s, [4, 0, 0], 10000);
  ok(s.data.damage.pending.length === 2, 'hits can be queued for the frame');
  ok(G.islands(s.grid).length === 1, '...and nothing happens until the tick');
  A.step(1 / 60);
  ok(s.data.damage.pending.length === 0, 'the tick drains the queue');
  ok(log.splits.length === 1, 'two cuts in one frame are ONE split event',
     `${log.splits.length}`);
  ok(log.splits[0].children.length === 2, '...carrying both new pieces',
     `${log.splits[0].children.length}`);
  const total = G.uniqueBlocks(s.grid).length +
    log.splits[0].children.reduce((t, c) => t + G.uniqueBlocks(c.grid).length, 0);
  ok(total === 5, 'and the five survivors are split 2 / 2 / 1 with none lost',
     `${total}`);
}

/* ---- repair -------------------------------------------------------------- */
{
  A.clearShips();
  const s = A.makeShip('fix', beam(3, 'large', 'fix'));
  D.applyDamage(s, [0, 0, 0], 100, api);
  D.repair(s, [0, 0, 0], 40, api);
  near(G.blockAt(s.grid, 0, 0, 0).hp, 240, 1e-9, 'repair puts hp back');
  D.repair(s, [0, 0, 0], 1e6, api);
  near(G.blockAt(s.grid, 0, 0, 0).hp, 300, 1e-9, '...clamped at full', 'never above hpMax');
  D.applyDamage(s, [0, 0, 0], 1e6, api);
  ok(D.repair(s, [0, 0, 0], 1e6, api) === null,
     'a destroyed block cannot be repaired — the only path back is building it');
}

/* ---- save / load --------------------------------------------------------- */
{
  A.clearShips();
  const sys = A.systems().find(x => x.id === 'damage');
  const s = A.makeShip('save', beam(4, 'large', 'sv'));
  D.applyDamage(s, [1, 0, 0], 120, api);
  D.applyDamage(s, [2, 0, 0], 10000, api);
  D.queueDamage(s, [3, 0, 0], 7);
  const blob = sys.save(s);
  ok(JSON.stringify(blob) === JSON.stringify(JSON.parse(JSON.stringify(blob))),
     'the save blob is JSON-safe');
  ok(blob.destroyed === 1, '...and records what died', `${blob.destroyed}`);

  // rebuild the same ship from the seed, then apply the diff
  A.clearShips();
  const s2 = A.makeShip('save', beam(4, 'large', 'sv'));
  G.removeBlock(s2.grid, 2, 0, 0);                    // the sparse diff's own job
  sys.load(s2, blob);
  near(G.blockAt(s2.grid, 1, 0, 0).hp, 180, 1e-9, 'a reload restores block hp');
  near(G.blockAt(s2.grid, 0, 0, 0).hp, 300, 1e-9, '...leaving untouched blocks full');
  ok(s2.data.damage.pending.length === 1, '...and the shots still in flight');
  ok(s2.data.damage.destroyed === 1, '...and the counters');
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
