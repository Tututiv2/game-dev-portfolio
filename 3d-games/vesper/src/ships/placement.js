/* ============================================================================
   PLACEMENT — the build cursor. Where a block goes BEFORE it goes there.

   This file is PURE (api.js rule 2): no three, no DOM, no clock, no
   Math.random. It decides nothing about pixels. A renderer draws the ghost from
   the result of aim(); it does not compute it. Nothing here mutates a grid —
   the cursor tells you what WOULD happen, and whoever owns the build tool calls
   grid.addBlock().

   THE THREE QUESTIONS IT ANSWERS

     1. Aiming AT a block        -> which cell, which face, so which cell in front
     2. Aiming at EMPTY SPACE
        next to a grid           -> snap to the neighbouring cell of the nearest
                                    face you are actually looking at
     3. Aiming at NOTHING        -> found a NEW grid at the aim point. Its size
                                    class is a CHOICE the player makes. There is
                                    no default baked in here, and asking without
                                    one is refused with a reason, not silently
                                    turned into a `large` grid.

   WHETHER IT MAY BE PLACED is grid.canPlace()'s answer, always. This file calls
   it; it does not reimplement it. The only thing placement adds on top is REACH
   (SIZE_CLASSES[class].reach — the weld range of a tool of that class) and a
   human-readable reason, and the reason is derived AFTER canPlace has spoken.

   ---------------------------------------------------------------------------
   WHAT THE CONTRACT DOES NOT GIVE ME, AND HOW I WORKED AROUND IT (api.js says
   to write the need down rather than reach into another file):

   a) There is no api.toLocal(ship, v) — only api.toWorld(v), and that is the
      PLANET->WORLD hop. A build cursor needs the PLANET->SHIP-LOCAL hop, which
      is ship state (pos, ori) and not world state, so it is done here with the
      ship's own quaternion. If the api ever grows toLocal(), delete qRotInv.

   b) The contract does not say whether ship.pos is the grid's cell-origin
      corner or its centre of mass. THIS FILE ASSUMES CELL-ORIGIN: grid cell
      (0,0,0) spans [pos, pos + cellM) along the ship's own axes, which is the
      convention centreOfMass() already uses when it puts a cell's centre at
      (c + 0.5) * cellM. If integration picks the other one, the fix is the two
      lines in toLocal() and nothing else.

   c) There is no terrain raycast in the api (altitude() is a scalar under the
      ship, not a hit). Founding a grid on the ground therefore takes an
      OPTIONAL `groundHit: {point, normal}` from whoever owns the terrain ray.
      With no ground hit, the new grid is founded in mid-air along the aim ray,
      which is the correct answer in orbit and a guess on a planet.

   d) There is no player object in the contract, so cursor state (chosen block,
      rotation, chosen size class) lives in a plain JSON-safe object made by
      makeCursor() rather than in ship.data. It is JSON-safe so whoever owns the
      player can drop it straight into the save diff.

   ---------------------------------------------------------------------------
   THE DESIGN LAW. Founding a grid at a size class is a CAPABILITY, so it may
   not come from a menu: pass `allowedClasses` — the classes the physical tool
   in the player's hands can actually weld — and anything outside it is refused.
   A caller that passes nothing gets every class, which is the editor's job and
   not the game's.

   ALL POSITIONS ARE IN THE PLANET'S FRAME unless a name ends in `Local`.
   ========================================================================= */

import * as G from './grid.js';

const EPS = 1e-9;

/* The six face normals, in a FIXED order. Ties in the snap search break by this
   order, so a player aiming into an inside corner gets the same face every
   time rather than whichever one the iteration happened to reach first. */
export const FACES = [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]];

/* ---- small vector helpers (metres, planet frame, unless said otherwise) ---- */
const sub = (a, b) => [a[0]-b[0], a[1]-b[1], a[2]-b[2]];
const add = (a, b) => [a[0]+b[0], a[1]+b[1], a[2]+b[2]];
const mul = (a, k) => [a[0]*k, a[1]*k, a[2]*k];
const dot = (a, b) => a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
const addI = (a, b) => [a[0]+b[0], a[1]+b[1], a[2]+b[2]];
function unit(v) {
  if (!v) return null;
  const l = Math.hypot(v[0], v[1], v[2]);
  return l > EPS ? [v[0]/l, v[1]/l, v[2]/l] : null;
}

/** Rotate v by a unit quaternion [x,y,z,w]. Same maths as api.toWorld's body —
    duplicated rather than imported because that one is hard-wired to the
    PLANET's quaternion and this one takes the SHIP's. */
export function qRot(q, v) {
  const [x, y, z] = v, [qx, qy, qz, qw] = q;
  const ix =  qw*x + qy*z - qz*y;
  const iy =  qw*y + qz*x - qx*z;
  const iz =  qw*z + qx*y - qy*x;
  const iw = -qx*x - qy*y - qz*z;
  return [ix*qw + iw*-qx + iy*-qz - iz*-qy,
          iy*qw + iw*-qy + iz*-qx - ix*-qz,
          iz*qw + iw*-qz + ix*-qy - iy*-qx];
}
export const qRotInv = (q, v) => qRot([-q[0], -q[1], -q[2], q[3]], v);

/* ---- ROTATION, AND WHY IT IS NOT AN INDEX++ -------------------------------
   grid.ROTS is generated as "for each forward, for each perpendicular up", so
   its index order is an artefact of that loop: ROTS[0] is forward +Z with up
   +X, which is a block lying on its side, and ROTS[1] is up -X — a 180 degree
   flip. Stepping the raw index is therefore a shuffle: press rotate twice and
   you are somewhere you could not have predicted.

   So rotation is a QUARTER TURN ABOUT A GRID AXIS. Integer matrices, no
   trigonometry, so composing is exact and four presses is always exactly where
   you started. Two presses is always 180 degrees. That is the whole promise. */

const ROT_INDEX = (() => {
  const m = new Map();
  G.ROTS.forEach((r, i) => m.set(r.fwd.join(',') + '|' + r.up.join(','), i));
  return m;
})();

/** Index of the orientation with this forward and up, or -1. */
export function rotIndex(fwd, up) {
  const i = ROT_INDEX.get(fwd.join(',') + '|' + up.join(','));
  return i === undefined ? -1 : i;
}

/** The un-rotated orientation: forward +Z, up +Y. NOT index 0 — see above. */
export const IDENTITY_ROT = rotIndex([0, 0, 1], [0, 1, 0]);

/** Grid axes, by the index rotateBy() takes: 0 = X (pitch), 1 = Y (yaw),
    2 = Z (roll). Named so a key binding can read as what it does. */
export const AXIS = { X: 0, Y: 1, Z: 2, pitch: 0, yaw: 1, roll: 2 };

/* One counter-clockwise quarter turn about a grid axis, on integers. */
function turn(axis, v) {
  const [x, y, z] = v;
  if (axis === 0) return [x, -z, y];
  if (axis === 1) return [z, y, -x];
  return [-y, x, z];
}

/** Rotate an orientation by `quarters` x 90 degrees about a GRID axis.
    Total, exact, and closed over the 24: the result is always a valid index. */
export function rotateBy(rot, axis, quarters = 1) {
  const r = G.ROTS[rot];
  if (!r) throw new Error(`rotateBy: no such orientation ${rot}`);
  if (axis !== 0 && axis !== 1 && axis !== 2) throw new Error(`rotateBy: axis ${axis}`);
  let q = ((quarters % 4) + 4) % 4;
  let fwd = r.fwd, up = r.up;
  for (let i = 0; i < q; i++) { fwd = turn(axis, fwd); up = turn(axis, up); }
  const idx = rotIndex(fwd, up);
  if (idx < 0) throw new Error('rotateBy left the 24 — grid.ROTS is not closed');
  return idx;
}

/* ---- REACH ---------------------------------------------------------------- */
/** How far a construction tool of this size class can weld, in metres.
    Never a literal: a world-ship's cursor reaches 400 m and a drone's 1.5. */
export const reachOf = (sizeClass) => G.SIZE_CLASSES[sizeClass].reach;

/* ---- THE RAY ---------------------------------------------------------------
   Amanatides & Woo over the cell lattice, clipped to the grid's own bounds
   expanded by one cell (the extra shell is where an "empty, but touching"
   placement lands, so it has to be inside the traversal).

   `oCells` is the ray origin in CELL units — metres divided by cellM — and `d`
   is a UNIT direction in metres space. Because the two share a direction, t
   comes out in cell units and metres is t * cellM. Distance is never computed
   from a hardcoded cell size anywhere in this file. */
function traceCells(grid, oCells, d, tEndCells) {
  const b = G.bounds(grid);
  if (!b) return null;                                  // an empty grid is nothing to aim at
  const lo = [b.lo[0]-1, b.lo[1]-1, b.lo[2]-1];
  const hi = [b.hi[0]+2, b.hi[1]+2, b.hi[2]+2];         // exclusive

  let t0 = 0, t1 = tEndCells;
  for (let i = 0; i < 3; i++) {
    if (Math.abs(d[i]) < EPS) {
      if (oCells[i] < lo[i] || oCells[i] >= hi[i]) return null;
      continue;
    }
    let a = (lo[i] - oCells[i]) / d[i], c = (hi[i] - oCells[i]) / d[i];
    if (a > c) { const t = a; a = c; c = t; }
    if (a > t0) t0 = a;
    if (c < t1) t1 = c;
    if (t0 > t1) return null;
  }

  const start = Math.max(t0, 0);
  const p = [oCells[0] + d[0]*(start + 1e-7),
             oCells[1] + d[1]*(start + 1e-7),
             oCells[2] + d[2]*(start + 1e-7)];
  const c = [Math.floor(p[0]), Math.floor(p[1]), Math.floor(p[2])];
  const step = [0, 0, 0], tNext = [Infinity, Infinity, Infinity], tDelta = [Infinity, Infinity, Infinity];
  for (let i = 0; i < 3; i++) {
    if (d[i] > EPS)       { step[i] =  1; tNext[i] = (c[i] + 1 - oCells[i]) / d[i]; tDelta[i] = 1 / d[i]; }
    else if (d[i] < -EPS) { step[i] = -1; tNext[i] = (c[i]     - oCells[i]) / d[i]; tDelta[i] = -1 / d[i]; }
  }

  let tEnter = start, face = null, snap = null, guard = 0;
  while (guard++ < 200000) {
    if (c[0] < lo[0] || c[0] >= hi[0] ||
        c[1] < lo[1] || c[1] >= hi[1] ||
        c[2] < lo[2] || c[2] >= hi[2]) break;

    const blk = G.blockAt(grid, c[0], c[1], c[2]);
    if (blk) return { kind: 'block', cell: [...c], face, hitCell: [...c], block: blk, t: tEnter };

    /* Empty cell: is it touching the grid on a face? The face we want is the
       one we are LOOKING AT, i.e. the neighbour most aligned with the ray, so
       an inside corner resolves the way the eye expects. */
    if (!snap) {
      let bestF = null, bestD = -Infinity, bestB = null;
      for (const f of FACES) {
        const n = [c[0]+f[0], c[1]+f[1], c[2]+f[2]];
        const nb = G.blockAt(grid, n[0], n[1], n[2]);
        if (!nb) continue;
        const al = dot(d, f);
        if (al > bestD + 1e-12) { bestD = al; bestF = f; bestB = nb; }
      }
      if (bestF) snap = {
        kind: 'empty', cell: [...c],
        face: [-bestF[0], -bestF[1], -bestF[2]],
        hitCell: [c[0]+bestF[0], c[1]+bestF[1], c[2]+bestF[2]],
        block: bestB, t: tEnter,
      };
    }

    let axis = 0;
    if (tNext[1] < tNext[axis]) axis = 1;
    if (tNext[2] < tNext[axis]) axis = 2;
    const cross = tNext[axis];
    if (!(cross <= t1) || !isFinite(cross)) break;
    c[axis] += step[axis];
    face = axis === 0 ? [-step[0], 0, 0] : axis === 1 ? [0, -step[1], 0] : [0, 0, -step[2]];
    tEnter = cross;
    tNext[axis] += tDelta[axis];
  }
  return snap;   // null if the ray never came within one cell of the grid
}

/** Trace ONE grid. `origin`/`dir` are in the SHIP's local metres, not planet
    metres. Exposed so a test (or an editor with no ship) can use it directly. */
export function aimGrid(grid, originLocal, dir, maxDistM) {
  const d = unit(dir);
  if (!d) return null;
  const s = G.cellM(grid.sizeClass);
  const r = traceCells(grid, [originLocal[0]/s, originLocal[1]/s, originLocal[2]/s], d, maxDistM / s);
  if (!r) return null;
  r.dist = r.t * s;
  return r;
}

const toLocal = (ship, v) => qRotInv(ship.ori || [0,0,0,1], sub(v, ship.pos || [0,0,0]));
const dirLocal = (ship, v) => qRotInv(ship.ori || [0,0,0,1], v);

/* ---- WHY canPlace SAID NO --------------------------------------------------
   canPlace is the verdict. This only turns that verdict into words, using the
   same public blockAt() a renderer would. If the two ever disagree, canPlace
   wins: `ok` is never set from this function. */
function refusal(grid, typeId, cell, rot) {
  let cells;
  try { cells = G.cellsOf(typeId, cell, rot); } catch { return 'unknown block type'; }
  for (const c of cells) if (G.blockAt(grid, c[0], c[1], c[2])) return 'those cells are occupied';
  return 'not touching the grid on a face';
}

/* ---- THE CURSOR ------------------------------------------------------------ */

/** Plain, JSON-safe cursor state. `sizeClass` starts NULL on purpose: founding
    a new grid is a choice, and a default here would be that choice made for the
    player by a programmer. */
export function makeCursor(opts = {}) {
  const rot = opts.rot === undefined ? IDENTITY_ROT : opts.rot;
  if (!G.ROTS[rot]) throw new Error(`makeCursor: no such orientation ${rot}`);
  if (opts.sizeClass && !G.SIZE_CLASSES[opts.sizeClass])
    throw new Error(`makeCursor: unknown size class ${opts.sizeClass}`);
  return {
    typeId: opts.typeId || 'hull',
    rot,
    sizeClass: opts.sizeClass || null,
  };
}

/** One press of a rotate key. Four presses is exactly where you started. */
export function rotateCursor(cur, axis, quarters = 1) {
  cur.rot = rotateBy(cur.rot, axis, quarters);
  return cur.rot;
}

/** Pick the size class of a grid you are about to found. `allowed` is what the
    tool in the player's hands can weld — pass it, and the law is enforced. */
export function chooseSizeClass(cur, cls, allowed = null) {
  if (!G.SIZE_CLASSES[cls]) throw new Error(`unknown size class ${cls}`);
  if (allowed && !allowed.includes(cls)) return false;
  cur.sizeClass = cls;
  return true;
}

/** Step through the classes the tool can weld. From null, forward lands on the
    first one — predictable, and never a class the tool cannot build. */
export function cycleSizeClass(cur, dir = 1, allowed = null) {
  const list = (allowed && allowed.length) ? allowed.filter(c => G.SIZE_CLASSES[c])
                                           : G.SIZE_ORDER.slice();
  if (!list.length) return null;
  const at = list.indexOf(cur.sizeClass);
  const n = at < 0 ? (dir > 0 ? 0 : list.length - 1)
                   : ((at + dir) % list.length + list.length) % list.length;
  cur.sizeClass = list[n];
  return cur.sizeClass;
}

export const saveCursor = (cur) => ({ typeId: cur.typeId, rot: cur.rot, sizeClass: cur.sizeClass });
export function loadCursor(saved) {
  return makeCursor({
    typeId: saved && saved.typeId,
    rot: saved && G.ROTS[saved.rot] ? saved.rot : IDENTITY_ROT,
    sizeClass: saved && G.SIZE_CLASSES[saved.sizeClass] ? saved.sizeClass : null,
  });
}

/* ---- AIM -------------------------------------------------------------------
   ctx = {
     origin, dir,            planet frame, metres. dir need not be normalised.
     ships   | ship,         ship-likes: { id, grid, pos, ori }
     typeId, rot,            what is on the cursor
     sizeClass,              chosen class, for founding a NEW grid. May be null.
     allowedClasses,         what the player's tool can weld. null = all.
     groundHit,              optional { point, normal } from the terrain ray.
     foundDist,              optional metres out to found in mid-air.
     maxDist,                optional trace limit; defaults to 4x reach so that
                             "out of reach" can be REPORTED rather than looking
                             identical to empty space.
   }

   Returns, always (never null):
   {
     kind: 'block' | 'empty' | 'new' | 'none',
     ok, reason,
     ship, grid, cell, face, hitCell, block, dist, point,
     typeId, rot,
     found: { sizeClass, pos, ori } | null,
     remove: block | null,        the grind target, which is the block you HIT
   }
*/
export function aim(ctx) {
  const d = unit(ctx.dir);
  const origin = ctx.origin || [0, 0, 0];
  const typeId = ctx.typeId === undefined ? null : ctx.typeId;
  const rot = ctx.rot === undefined ? IDENTITY_ROT : ctx.rot;
  const base = {
    kind: 'none', ok: false, reason: 'nothing in range',
    ship: null, grid: null, cell: null, face: null, hitCell: null, block: null,
    dist: Infinity, point: null, typeId, rot, found: null, remove: null,
  };
  if (!d) return { ...base, reason: 'no aim direction' };

  const shipsIn = ctx.ships || (ctx.ship ? [ctx.ship] : []);
  let best = null;
  for (const ship of shipsIn) {
    const grid = ship && ship.grid;
    if (!grid) continue;
    const reach = reachOf(grid.sizeClass);
    const limit = ctx.maxDist === undefined ? reach * 4 : ctx.maxDist;
    const r = aimGrid(grid, toLocal(ship, origin), dirLocal(ship, d), limit);
    if (!r) continue;
    if (!best || r.dist < best.dist) best = { ...r, ship, grid, reach };
  }

  if (best) {
    const cell = best.kind === 'block'
      ? (best.face ? addI(best.cell, best.face) : null)
      : best.cell;
    const out = {
      ...base,
      kind: best.kind, ship: best.ship, grid: best.grid,
      cell, face: best.face, hitCell: best.hitCell, block: best.block,
      dist: best.dist, point: add(origin, mul(d, best.dist)),
      remove: best.kind === 'block' ? best.block : null,
    };
    if (best.dist > best.reach) return { ...out, ok: false, reason: 'out of reach' };
    if (!typeId) return { ...out, ok: false, reason: 'no block type selected' };
    if (!G.BLOCK_TYPES[typeId]) return { ...out, ok: false, reason: 'unknown block type' };
    if (!cell) return { ...out, ok: false, reason: 'aiming from inside a block' };
    if (!G.canPlace(best.grid, typeId, cell, rot))
      return { ...out, ok: false, reason: refusal(best.grid, typeId, cell, rot) };
    return { ...out, ok: true, reason: 'ok' };
  }

  /* ---- nothing out there: found a new grid ------------------------------- */
  const cls = ctx.sizeClass || null;
  const newBase = { ...base, kind: 'new', cell: [0, 0, 0], face: [0, 1, 0] };
  if (!cls)
    return { ...newBase, reason: 'choose a size class before founding a grid' };
  if (!G.SIZE_CLASSES[cls])
    return { ...newBase, reason: `unknown size class ${cls}` };
  if (ctx.allowedClasses && !ctx.allowedClasses.includes(cls))
    return { ...newBase, reason: `your tool cannot weld a ${cls} grid` };
  if (!typeId) return { ...newBase, reason: 'no block type selected' };
  if (!G.BLOCK_TYPES[typeId]) return { ...newBase, reason: 'unknown block type' };

  const s = G.cellM(cls);
  let centre;
  if (ctx.groundHit && ctx.groundHit.point) {
    const n = unit(ctx.groundHit.normal) || [0, 1, 0];
    centre = add(ctx.groundHit.point, mul(n, s / 2));      // sits ON the surface
  } else {
    const fd = ctx.foundDist === undefined ? reachOf(cls) * 0.5 : ctx.foundDist;
    centre = add(origin, mul(d, fd));
  }
  /* The grid is founded axis-aligned to the planet frame: cell (0,0,0) is the
     corner at `pos`, so its centre lands on the aim point. Rotating a brand new
     grid to the aim ray would make two identical presses give two different
     ships, which is the opposite of predictable. */
  const pos = sub(centre, [s/2, s/2, s/2]);
  return {
    ...newBase, ok: true, reason: 'ok',
    dist: Math.hypot(...sub(centre, origin)), point: centre,
    found: { sizeClass: cls, pos, ori: [0, 0, 0, 1] },
  };
}

export default {
  aim, aimGrid, rotateBy, rotIndex, reachOf,
  makeCursor, rotateCursor, chooseSizeClass, cycleSizeClass, saveCursor, loadCursor,
  IDENTITY_ROT, AXIS, FACES,
};
