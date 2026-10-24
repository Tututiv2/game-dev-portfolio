/* PLACEMENT — the build cursor, headless. No GPU, no browser, no puppeteer.

   Runs the SHIPPING src/ships/placement.js against the SHIPPING src/ships/grid.js.
   Every check is behavioural: where does the cursor say the block goes, and does
   grid.addBlock() then actually accept it.

   Two checks exist to prove the instrument can still fail — this project has had
   an instrument be the broken thing four times (a dead GPU context, daylit
   frames labelled night, ablate.mjs's own noise, a stale auto-buyer), and
   CLAUDE.md's trap 13 is that a test which cannot detect its own death will
   certify the defect.

   node tests/ships_placement.mjs
*/
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SHIPS = path.join(ROOT, 'src', 'ships');
const G = await import(pathToFileURL(path.join(SHIPS, 'grid.js')).href);
const P = await import(pathToFileURL(path.join(SHIPS, 'placement.js')).href);

let pass = 0, fail = 0;
const ok = (cond, name, detail = '') => {
  if (cond) { pass++; console.log(`  PASS  ${name}${detail ? '  ' + detail : ''}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? '  ' + detail : ''}`); }
};
const near = (a, b, tol, name, detail) =>
  ok(Math.abs(a - b) <= tol, name, detail !== undefined ? detail : `${a} vs ${b} (tol ${tol})`);
const eqv = (a, b) => !!a && !!b && a.length === b.length && a.every((x, i) => Math.abs(x - b[i]) < 1e-9);
const s3 = (v) => v ? `[${v.join(',')}]` : 'null';

console.log('\n  PLACEMENT — the build cursor, headless\n');

/* ---- purity -------------------------------------------------------------- */
{
  const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const src = strip(fs.readFileSync(path.join(SHIPS, 'placement.js'), 'utf8'));
  const banned = [/\bfrom ['"]three['"]/, /\bdocument\b/, /\bwindow\b/,
                  /Math\.random/, /\bnew Date\b/, /Date\.now/];
  const bad = banned.filter(re => re.test(src)).map(String);
  ok(bad.length === 0, 'placement.js is pure', bad.join(' · '));
  ok(!/2\.5/.test(src), 'and nowhere hardcodes 2.5 — cellM() decides the metre');
}

/* ---- ROTATION: predictable, or it is not a rotate key -------------------- */
{
  const r0 = G.ROTS[P.IDENTITY_ROT];
  ok(eqv(r0.fwd, [0,0,1]) && eqv(r0.up, [0,1,0]),
     'IDENTITY_ROT is forward +Z, up +Y', `fwd ${s3(r0.fwd)} up ${s3(r0.up)}`);
  ok(P.IDENTITY_ROT !== 0,
     '...and it is NOT index 0 — which is why ++ is not a rotate key',
     `identity is ${P.IDENTITY_ROT}, ROTS[0] is up ${s3(G.ROTS[0].up)}`);

  let cycles = true, halves = true, bijective = true;
  for (const ax of [P.AXIS.X, P.AXIS.Y, P.AXIS.Z]) {
    const img = new Set();
    for (let r = 0; r < 24; r++) {
      let x = r;
      for (let i = 0; i < 4; i++) x = P.rotateBy(x, ax, 1);
      if (x !== r) cycles = false;
      if (P.rotateBy(r, ax, 2) !== P.rotateBy(P.rotateBy(r, ax, 1), ax, 1)) halves = false;
      img.add(P.rotateBy(r, ax, 1));
    }
    if (img.size !== 24) bijective = false;
  }
  ok(cycles, 'four presses of a rotate key is exactly where you started (all 24 x 3 axes)');
  ok(halves, '...and two presses is exactly 180 degrees, on every axis');
  ok(bijective, '...and one press is a bijection of the 24 — nothing is unreachable');

  // every orientation must be reachable from the identity by the three keys
  const seen = new Set([P.IDENTITY_ROT]), stack = [P.IDENTITY_ROT];
  while (stack.length) {
    const r = stack.pop();
    for (const ax of [0, 1, 2]) {
      const n = P.rotateBy(r, ax, 1);
      if (!seen.has(n)) { seen.add(n); stack.push(n); }
    }
  }
  ok(seen.size === 24, 'all 24 orientations are reachable from the identity by the 3 keys',
     `${seen.size}/24`);

  // and rotation must actually move a multi-cell block's footprint
  const a = G.cellsOf('thruster', [0,0,0], P.IDENTITY_ROT).map(c => c.join()).sort().join(' ');
  const b = G.cellsOf('thruster', [0,0,0], P.rotateBy(P.IDENTITY_ROT, P.AXIS.X, 1)).map(c => c.join()).sort().join(' ');
  ok(a !== b, 'a quarter turn really moves a 1x1x2 footprint', `${a}  vs  ${b}`);

  let threw = false;
  try { P.rotateBy(P.IDENTITY_ROT, 5, 1); } catch { threw = true; }
  ok(threw, 'an axis that is not 0/1/2 throws rather than guessing');
}

/* ---- a 3x3x3 test hull --------------------------------------------------- */
const cube = (cls = 'large') => {
  const g = G.makeGrid(cls, 'cube');
  for (let z = 0; z < 3; z++) for (let y = 0; y < 3; y++) for (let x = 0; x < 3; x++)
    G.addBlock(g, 'hull', [x, y, z], P.IDENTITY_ROT);
  return g;
};
const shipOf = (g, pos = [0,0,0], ori = [0,0,0,1]) => ({ id: 'sh', grid: g, pos, ori });
const S = G.cellM('large');                       // never written as a literal
const MID = 1.5 * S;                              // centre line of the middle column

/* ---- 1. AIMING AT A BLOCK ------------------------------------------------ */
{
  const g = cube();
  const r = P.aim({ origin: [MID, MID, -10], dir: [0, 0, 1], ship: shipOf(g), typeId: 'hull' });
  ok(r.kind === 'block', 'a ray into a hull reports a block hit', r.kind);
  ok(eqv(r.hitCell, [1,1,0]), '...naming the cell it hit', s3(r.hitCell));
  ok(eqv(r.face, [0,0,-1]), '...and the face it came in through', s3(r.face));
  ok(eqv(r.cell, [1,1,-1]), '...so the block goes in the cell in FRONT of that face', s3(r.cell));
  near(r.dist, 10, 1e-6, '...at the true distance in metres', `${r.dist.toFixed(4)} m`);
  ok(r.ok && r.reason === 'ok', '...and canPlace agrees it may go there', r.reason);
  ok(r.remove === G.blockAt(g, 1, 1, 0), '...while the grind target is the block you hit');

  // the cursor is not lying: the grid actually takes it
  ok(G.addBlock(g, 'hull', r.cell, r.rot) !== null, 'and grid.addBlock() accepts that cell');
  const r2 = P.aim({ origin: [MID, MID, -10], dir: [0, 0, 1], ship: shipOf(g), typeId: 'hull' });
  ok(eqv(r2.cell, [1,1,-2]), 'after which the cursor has moved one cell out', s3(r2.cell));

  // side faces
  const g2 = cube();
  const rx = P.aim({ origin: [-10, MID, MID], dir: [1,0,0], ship: shipOf(g2), typeId: 'hull' });
  ok(eqv(rx.face, [-1,0,0]) && eqv(rx.cell, [-1,1,1]), 'a ray along +X lands on the -X face',
     `face ${s3(rx.face)} cell ${s3(rx.cell)}`);
  const ry = P.aim({ origin: [MID, 40, MID], dir: [0,-1,0], ship: shipOf(g2), typeId: 'hull' });
  ok(eqv(ry.face, [0,1,0]) && eqv(ry.cell, [1,3,1]), 'a ray straight down lands on the +Y face',
     `face ${s3(ry.face)} cell ${s3(ry.cell)}`);
}

/* ---- 1b. THE CHECK MUST BE ABLE TO FAIL ---------------------------------- */
{
  // Same ray, same everything — but with the whole z=0 layer ground away. If the
  // ray check still said [1,1,-1] it would be reading nothing at all.
  const g = cube();
  for (let y = 0; y < 3; y++) for (let x = 0; x < 3; x++) G.removeBlock(g, x, y, 0);
  const r = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(g), typeId: 'hull' });
  ok(!eqv(r.cell, [1,1,-1]) && eqv(r.cell, [1,1,0]),
     'CAN-FAIL: grind the face away and the same ray answers differently',
     `now ${r.kind} at ${s3(r.cell)}`);

  // and with the grid gone entirely it must not invent a hit
  const empty = G.makeGrid('large', 'empty');
  const r2 = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(empty), typeId: 'hull' });
  ok(r2.kind !== 'block', 'CAN-FAIL: an empty grid produces no block hit', r2.kind);
}

/* ---- 2. AIMING AT EMPTY SPACE NEXT TO A GRID ----------------------------- */
{
  const g = cube();
  // a ray running one cell to the -X of the hull, parallel to it: it never
  // touches a block, but it passes through cells that do.
  const r = P.aim({ origin: [-0.5 * S, MID, -10], dir: [0,0,1], ship: shipOf(g), typeId: 'hull' });
  ok(r.kind === 'empty', 'a ray that grazes the hull snaps instead of missing', r.kind);
  ok(eqv(r.cell, [-1,1,0]), '...to the empty cell it is actually in', s3(r.cell));
  ok(eqv(r.face, [-1,0,0]), '...against the face of the block beside it', s3(r.face));
  ok(eqv(r.hitCell, [0,1,0]), '...and names that supporting block', s3(r.hitCell));
  ok(r.ok, '...and it is a legal placement', r.reason);
  ok(G.addBlock(g, 'hull', r.cell, r.rot) !== null, '...which grid.addBlock() takes');

  // the two paths must AGREE: stopping the same ray short of the block gives the
  // snap, and the snap must name the same cell the face hit did.
  const g2 = cube();
  const hit  = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(g2), typeId: 'hull' });
  const shrt = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(g2), typeId: 'hull', maxDist: 9.9 });
  ok(shrt.kind === 'empty' && eqv(shrt.cell, hit.cell),
     'a face hit and a snap agree on the cell', `${s3(hit.cell)} == ${s3(shrt.cell)}`);

  // an inside corner resolves to the face you are LOOKING at, not to iteration order
  const g3 = G.makeGrid('large', 'L');
  G.addBlock(g3, 'hull', [0,0,0], P.IDENTITY_ROT);
  G.addBlock(g3, 'hull', [0,1,0], P.IDENTITY_ROT);   // wall going up in +Y
  G.addBlock(g3, 'hull', [1,0,0], P.IDENTITY_ROT);   // floor going out in +X
  const c = P.aim({ origin: [1.5*S, 1.5*S, 0.5*S], dir: [-1,-1,0], ship: shipOf(g3), typeId: 'hull' });
  ok(c.cell !== null && eqv(c.cell, [1,1,0]),
     'the inside corner cell is offered once, not twice', s3(c.cell));
  ok(c.hitCell !== null && (eqv(c.hitCell, [0,1,0]) || eqv(c.hitCell, [1,0,0])),
     '...braced against one of the two real blocks', s3(c.hitCell));
}

/* ---- 3. REACH ------------------------------------------------------------ */
{
  const g = cube();
  const reach = P.reachOf('large');
  ok(reach === G.SIZE_CLASSES.large.reach, 'reach comes from the size class', `${reach} m`);
  const far = P.aim({ origin: [MID, MID, -(reach + 20)], dir: [0,0,1], ship: shipOf(g), typeId: 'hull' });
  ok(far.kind === 'block' && !far.ok && far.reason === 'out of reach',
     'past the weld range the cursor SEES it and refuses it', `${far.dist.toFixed(1)} m > ${reach}`);
  const close = P.aim({ origin: [MID, MID, -(reach - 2)], dir: [0,0,1], ship: shipOf(g), typeId: 'hull' });
  ok(close.ok, '...and inside it, it does not', `${close.dist.toFixed(1)} m`);

  // reach must scale with the class, or a world-ship is a lifetime of nudging
  ok(P.reachOf('world') > P.reachOf('micro') * 100, 'reach scales with the size ladder',
     `micro ${P.reachOf('micro')} m -> world ${P.reachOf('world')} m`);
}

/* ---- 4. canPlace IS THE VERDICT, INCLUDING FOR MULTI-CELL BLOCKS --------- */
{
  const g = cube();
  const at = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(g), typeId: 'hull' });
  // a 1x1x2 thruster laid so its second cell runs INTO the hull
  const into = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(g),
                       typeId: 'thruster', rot: P.IDENTITY_ROT });
  ok(!into.ok && into.reason === 'those cells are occupied',
     'a 1x1x2 block whose far cell is inside the hull is refused, with the reason',
     into.reason);
  ok(G.addBlock(g, 'thruster', into.cell, into.rot) === null,
     '...and grid.addBlock() refuses it too — the cursor did not lie');

  const out = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(g),
                      typeId: 'thruster', rot: P.rotateBy(P.IDENTITY_ROT, P.AXIS.X, 2) });
  ok(out.ok, 'turned 180 degrees it points away from the hull and fits', out.reason);
  ok(G.addBlock(g, 'thruster', out.cell, out.rot) !== null, '...and is accepted', s3(out.cell));

  const bad = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(cube()), typeId: 'nonsense' });
  ok(!bad.ok && bad.reason === 'unknown block type', 'an unknown block type is named, not thrown');
  const none = P.aim({ origin: [MID, MID, -10], dir: [0,0,1], ship: shipOf(cube()) });
  ok(!none.ok && none.reason === 'no block type selected', 'and an empty cursor says so');
}

/* ---- 5. FRAMES — the bug this project has paid for twice ----------------- */
{
  const g = cube();
  const pos = [1000, -50, 7];
  const ori = [0, 1, 0, 0];                 // 180 degrees about Y
  // the SAME physical ray, expressed in the planet frame
  const oL = [MID, MID, -10], dL = [0, 0, 1];
  const oP = [pos[0] + P.qRot(ori, oL)[0], pos[1] + P.qRot(ori, oL)[1], pos[2] + P.qRot(ori, oL)[2]];
  const dP = P.qRot(ori, dL);

  const moved = P.aim({ origin: oP, dir: dP, ship: shipOf(g, pos, ori), typeId: 'hull' });
  ok(eqv(moved.cell, [1,1,-1]),
     'a moved and rotated ship gives the same CELL for the same physical ray', s3(moved.cell));
  near(moved.dist, 10, 1e-6, '...and the same distance', `${moved.dist.toFixed(4)} m`);
  const pt = moved.point;   // may be null if the implementation is broken — say FAIL, do not throw
  ok(!!pt && Math.abs(Math.hypot(pt[0]-oP[0], pt[1]-oP[1], pt[2]-oP[2]) - 10) < 1e-6,
     '...and the hit point comes back in the PLANET frame', pt ? s3(pt.map(x => +x.toFixed(2))) : 'null');

  // CAN-FAIL: skip the frame hop (pretend the ship sits at the origin) and the
  // answer must change. If it did not, the frame test above would prove nothing.
  const naive = P.aim({ origin: oP, dir: dP, ship: shipOf(g, [0,0,0], [0,0,0,1]), typeId: 'hull' });
  ok(!eqv(naive.cell, [1,1,-1]),
     'CAN-FAIL: drop the frame hop and the same test answers wrongly',
     `${naive.kind} ${s3(naive.cell)}`);
}

/* ---- 6. AIMING AT NOTHING — founding a grid is a CHOICE ------------------ */
{
  const g = cube();
  const away = { origin: [MID, MID, -10], dir: [0, -1, 0], ship: shipOf(g), typeId: 'hull' };

  const nada = P.aim(away);
  ok(nada.kind === 'new', 'a ray at nothing offers to found a new grid', nada.kind);
  ok(!nada.ok && nada.found === null && /size class/.test(nada.reason),
     '...but refuses without a chosen size class — no default is baked in', nada.reason);

  const small = P.aim({ ...away, sizeClass: 'small' });
  ok(small.ok && small.found && small.found.sizeClass === 'small',
     'with a class chosen, it founds one', small.reason);
  const sS = G.cellM('small');
  const cS = [small.found.pos[0] + sS/2, small.found.pos[1] + sS/2, small.found.pos[2] + sS/2];
  near(Math.hypot(cS[0]-away.origin[0], cS[1]-away.origin[1], cS[2]-away.origin[2]),
       P.reachOf('small') * 0.5, 1e-6, '...with cell (0,0,0) centred on the aim point',
       `${sS} m cells`);
  ok(eqv(small.cell, [0,0,0]), '...at cell (0,0,0)', s3(small.cell));
  ok(eqv(small.found.ori, [0,0,0,1]),
     '...axis-aligned, so two identical presses give two identical ships');

  // the class must actually change the geometry — proof cellM is doing the work
  const large = P.aim({ ...away, sizeClass: 'large' });
  const dOff = Math.abs(large.found.pos[1] - small.found.pos[1]);
  ok(dOff > 0, 'a different class founds a different grid', `y offset differs by ${dOff.toFixed(3)} m`);
  const gLarge = G.makeGrid(large.found.sizeClass, 'new');
  ok(G.addBlock(gLarge, 'hull', large.cell, large.rot) !== null,
     '...and the founded grid takes its first block');
  ok(gLarge.sizeClass === 'large' && G.cellM(gLarge.sizeClass) === G.SIZE_CLASSES.large.m,
     '...at the class the player chose', gLarge.sizeClass);

  // THE DESIGN LAW: the tool decides which classes exist, not a menu
  const denied = P.aim({ ...away, sizeClass: 'world', allowedClasses: ['micro', 'small'] });
  ok(!denied.ok && /cannot weld/.test(denied.reason),
     'a class the player\'s tool cannot weld is refused', denied.reason);
  const granted = P.aim({ ...away, sizeClass: 'small', allowedClasses: ['micro', 'small'] });
  ok(granted.ok, '...and one it can is allowed', granted.reason);

  // founding ON the ground, when whoever owns the terrain ray hands us a hit
  const gh = P.aim({ ...away, sizeClass: 'large',
                     groundHit: { point: [10, 20, 30], normal: [0, 2, 0] } });
  const sL = G.cellM('large');
  ok(eqv(gh.found.pos, [10 - sL/2, 20, 30 - sL/2]),
     'a ground hit puts the block ON the surface, not half inside it', s3(gh.found.pos));

  const noDir = P.aim({ origin: [0,0,0], dir: [0,0,0], typeId: 'hull' });
  ok(!noDir.ok && noDir.reason === 'no aim direction', 'a zero-length ray is refused, not divided by');
}

/* ---- 7. THE CURSOR ------------------------------------------------------- */
{
  const cur = P.makeCursor();
  ok(cur.rot === P.IDENTITY_ROT && cur.sizeClass === null,
     'a fresh cursor starts un-rotated with NO size class chosen');
  const start = cur.rot;
  for (let i = 0; i < 4; i++) P.rotateCursor(cur, P.AXIS.Y);
  ok(cur.rot === start, 'four presses of the rotate key returns the cursor');

  const allowed = ['small', 'large'];
  ok(P.cycleSizeClass(cur, 1, allowed) === 'small', 'cycling from nothing picks the first the tool allows');
  ok(P.cycleSizeClass(cur, 1, allowed) === 'large', '...then the next');
  ok(P.cycleSizeClass(cur, 1, allowed) === 'small', '...and wraps');
  ok(P.chooseSizeClass(cur, 'world', allowed) === false && cur.sizeClass === 'small',
     'and it will not choose a class the tool cannot weld');

  const round = P.loadCursor(JSON.parse(JSON.stringify(P.saveCursor(cur))));
  ok(round.typeId === cur.typeId && round.rot === cur.rot && round.sizeClass === cur.sizeClass,
     'cursor state survives a JSON save/load round trip — it is part of the diff');
  const junk = P.loadCursor({ typeId: 'hull', rot: 99, sizeClass: 'enormous' });
  ok(junk.rot === P.IDENTITY_ROT && junk.sizeClass === null,
     'a corrupt save loads as a sane cursor rather than throwing at boot');
}

/* ---- 8. NOTHING PLACEMENT DOES MAY MUTATE A GRID ------------------------- */
{
  const g = cube();
  const before = g.blocks.size;
  for (let i = 0; i < 20; i++)
    P.aim({ origin: [MID, MID, -10 - i], dir: [0,0,1], ship: shipOf(g), typeId: 'thruster',
            rot: i % 24, sizeClass: 'large' });
  ok(g.blocks.size === before, 'aiming is read-only — the cursor never builds anything',
     `${before} cells before and after`);
}

console.log(`\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
