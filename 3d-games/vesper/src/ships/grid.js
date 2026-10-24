/* ============================================================================
   GRIDS — what every built thing in VESPER is made of.

   A fighter, a station, a rover, and the world-ship that lays siege to a planet
   in B6 are the SAME data structure at different size classes. That is not an
   elegance argument, it is DESIGN.md's own constraint: "grids need size classes
   from day one — a world-ship is a grid, and hardcoding 2.5 m blocks makes the
   siege a rewrite."

   THIS FILE IS PURE. No three.js, no DOM, no Math.random, no Date. That is the
   same rule sim/ obeys in HOLLOW and GRAFT and sim.js obeys in PYRE, and it is
   what lets the whole model be tested headlessly in milliseconds — and what will
   let eight agents work against it at once without a GPU between them.

   Coordinates are INTEGER CELLS, never metres. A cell becomes metres only by
   multiplying by the grid's size class. Nothing downstream may assume 2.5.
   ========================================================================= */

/* ---- THE SIZE LADDER ------------------------------------------------------
   Each class is 5x the last, so one block of a class is exactly 125 blocks of
   the class below it by volume. That ratio is not cosmetic: B5's gate is "build
   a ship that builds a bigger ship", and a whole-number nesting is what lets a
   shipyard consume one tier and emit the next without a conversion table full of
   rounding. 0.5 and 2.5 match the two classes players already expect from Space
   Engineers; the three above them are what VESPER needs and SE has no answer for.

   `reach` is how far a construction tool of that class can weld — it scales with
   the block, or building a world-ship would be a lifetime of nudging. */
export const SIZE_CLASSES = {
  micro:   { m: 0.1,  reach: 1.5,  name: 'micro',   blurb: 'drones, tools, probes' },
  small:   { m: 0.5,  reach: 6,    name: 'small',   blurb: 'fighters, rovers, pods' },
  large:   { m: 2.5,  reach: 20,   name: 'large',   blurb: 'ships, bases, stations' },
  capital: { m: 12.5, reach: 90,   name: 'capital', blurb: 'capital hulls, yards' },
  world:   { m: 62.5, reach: 400,  name: 'world',   blurb: 'world-ships, rings' },
};
export const SIZE_ORDER = ['micro', 'small', 'large', 'capital', 'world'];

/** metres per cell for a class. Never hardcode this at a call site. */
export const cellM = (sizeClass) => SIZE_CLASSES[sizeClass].m;

/* ---- ORIENTATION ----------------------------------------------------------
   24 of them, and they are here in Wave 0 rather than "later" because a
   thruster has to know which way it pushes and a multi-cell block has to know
   which way it lies. Both are unrecoverable if the model cannot express them:
   you cannot infer a rotation from a placed block afterwards.

   A rotation is an index into ROTS. Each entry is the block's own +Z (forward)
   and +Y (up) expressed in GRID axes, as integers — no trigonometry anywhere in
   the model, so a rotation composed twice is still exact. */
const AXES = [[0,0,1],[0,0,-1],[1,0,0],[-1,0,0],[0,1,0],[0,-1,0]];
const cross = (a, b) => [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]];
const dot3 = (a, b) => a[0]*b[0] + a[1]*b[1] + a[2]*b[2];

export const ROTS = (() => {
  const out = [];
  for (const fwd of AXES) for (const up of AXES) {
    if (dot3(fwd, up) !== 0) continue;          // up must be perpendicular
    out.push({ fwd, up, right: cross(up, fwd) });
  }
  return out;                                    // 6 forwards x 4 ups = 24
})();

/** Turn a vector in BLOCK space into GRID space under rotation `rot`. */
export function rotVec(rot, v) {
  const r = ROTS[rot];
  return [
    r.right[0]*v[0] + r.up[0]*v[1] + r.fwd[0]*v[2],
    r.right[1]*v[0] + r.up[1]*v[1] + r.fwd[1]*v[2],
    r.right[2]*v[0] + r.up[2]*v[1] + r.fwd[2]*v[2],
  ];
}

/* ---- BLOCK TYPES ----------------------------------------------------------
   A registry, not a class hierarchy — the same reason objectKinds.js is data.
   `size` is in CELLS and is the block's footprint in its OWN axes before
   rotation. `mass` is kg per block AT THE `large` CLASS and is scaled by volume
   for other classes, so one steel cube is one entry rather than five. */
export const BLOCK_TYPES = {};
export function defineBlock(def) {
  if (!def.id) throw new Error('block type needs an id');
  if (BLOCK_TYPES[def.id]) throw new Error(`duplicate block type ${def.id}`);
  BLOCK_TYPES[def.id] = {
    size: [1, 1, 1], mass: 100, hp: 300, thrust: 0, power: 0,
    cockpit: false, ...def,
  };
  return BLOCK_TYPES[def.id];
}

/* A deliberately small starter set. Wave 1 agents add their own; the point of
   Wave 0 is that adding one is a table entry and not a code change. */
defineBlock({ id: 'hull',     name: 'hull plate',   mass: 100, hp: 300 });
defineBlock({ id: 'cockpit',  name: 'cockpit',      mass: 260, hp: 400,
              size: [1, 1, 2], cockpit: true, power: -5 });
defineBlock({ id: 'thruster', name: 'thruster',     mass: 320, hp: 250,
              size: [1, 1, 2], thrust: 12000, power: -30 });
defineBlock({ id: 'reactor',  name: 'reactor',      mass: 900, hp: 500,
              size: [2, 2, 2], power: 300 });
defineBlock({ id: 'gyro',     name: 'gyro',         mass: 300, hp: 250, power: -8 });

/* ---- THE GRID ------------------------------------------------------------- */
export const key = (x, y, z) => `${x},${y},${z}`;

export function makeGrid(sizeClass = 'large', id = 'g0') {
  if (!SIZE_CLASSES[sizeClass]) throw new Error(`unknown size class ${sizeClass}`);
  return { id, sizeClass, blocks: new Map() };
}

/** Every cell a block occupies, in grid space, given its footprint and rotation. */
export function cellsOf(typeId, cell, rot) {
  const t = BLOCK_TYPES[typeId];
  if (!t) throw new Error(`unknown block type ${typeId}`);
  const out = [];
  for (let z = 0; z < t.size[2]; z++)
    for (let y = 0; y < t.size[1]; y++)
      for (let x = 0; x < t.size[0]; x++) {
        const d = rotVec(rot, [x, y, z]);
        out.push([cell[0] + d[0], cell[1] + d[1], cell[2] + d[2]]);
      }
  return out;
}

export const blockAt = (grid, x, y, z) => grid.blocks.get(key(x, y, z)) || null;

/** Can this block go here — every cell free, and (unless first) touching one? */
export function canPlace(grid, typeId, cell, rot) {
  const cells = cellsOf(typeId, cell, rot);
  for (const c of cells) if (grid.blocks.has(key(...c))) return false;
  if (grid.blocks.size === 0) return true;
  // must touch the existing grid on a FACE, not a corner
  for (const c of cells)
    for (const d of AXES)
      if (grid.blocks.has(key(c[0]+d[0], c[1]+d[1], c[2]+d[2]))) return true;
  return false;
}

export function addBlock(grid, typeId, cell, rot = 0) {
  if (!canPlace(grid, typeId, cell, rot)) return null;
  const b = { type: typeId, cell: [...cell], rot, hp: BLOCK_TYPES[typeId].hp };
  // Every occupied cell maps to the SAME block object, so a multi-cell block is
  // one thing that happens to be reachable from several places.
  for (const c of cellsOf(typeId, cell, rot)) grid.blocks.set(key(...c), b);
  return b;
}

export function removeBlock(grid, x, y, z) {
  const b = blockAt(grid, x, y, z);
  if (!b) return null;
  for (const c of cellsOf(b.type, b.cell, b.rot)) grid.blocks.delete(key(...c));
  return b;
}

/** The distinct blocks, once each — `blocks` holds a multi-cell block N times. */
export function uniqueBlocks(grid) {
  return [...new Set(grid.blocks.values())];
}

/* ---- DERIVED PHYSICAL QUANTITIES ------------------------------------------
   Mass scales with the CUBE of the size class, because a block is a volume.
   `large` is the reference, so a small hull plate is 100 * (0.5/2.5)^3 = 0.8 kg
   and a world one is 100 * 25^3 = 1,562,500 kg. Getting this wrong is how a
   world-ship ends up flying like a fighter. */
export function blockMass(grid, b) {
  const t = BLOCK_TYPES[b.type];
  const s = cellM(grid.sizeClass) / SIZE_CLASSES.large.m;
  const cells = t.size[0] * t.size[1] * t.size[2];
  return t.mass * cells * s * s * s;
}

export function mass(grid) {
  let m = 0;
  for (const b of uniqueBlocks(grid)) m += blockMass(grid, b);
  return m;
}

/** In METRES, relative to cell origin. Thrust that misses this induces spin. */
export function centreOfMass(grid) {
  let m = 0, cx = 0, cy = 0, cz = 0;
  const s = cellM(grid.sizeClass);
  for (const b of uniqueBlocks(grid)) {
    const bm = blockMass(grid, b);
    const cells = cellsOf(b.type, b.cell, b.rot);
    for (const c of cells) {
      const w = bm / cells.length;
      cx += (c[0] + 0.5) * s * w; cy += (c[1] + 0.5) * s * w; cz += (c[2] + 0.5) * s * w;
      m += w;
    }
  }
  return m > 0 ? [cx / m, cy / m, cz / m] : [0, 0, 0];
}

/** Newtons available along a GRID-space unit direction. Thrusters push along
    their own -Z (out of the nozzle), so a thruster facing +Z accelerates -Z. */
export function thrustAlong(grid, dir) {
  let n = 0;
  const s = cellM(grid.sizeClass) / SIZE_CLASSES.large.m;
  for (const b of uniqueBlocks(grid)) {
    const t = BLOCK_TYPES[b.type];
    if (!t.thrust) continue;
    const push = rotVec(b.rot, [0, 0, -1]);
    const d = dot3(push, dir);
    if (d > 0) n += t.thrust * d * s * s;   // area scales as the square
  }
  return n;
}

export function power(grid) {
  let produced = 0, drawn = 0;
  const s = cellM(grid.sizeClass) / SIZE_CLASSES.large.m;
  for (const b of uniqueBlocks(grid)) {
    const p = BLOCK_TYPES[b.type].power * s * s * s;
    if (p > 0) produced += p; else drawn -= p;
  }
  return { produced, drawn, ok: produced >= drawn };
}

/* ---- ISLANDS — "cut it in half and both halves behave" ---------------------
   B2's gate, and the reason connectivity lives in the model rather than in the
   renderer. A flood fill over FACE neighbours; corner contact is not structure.
   Returns an array of arrays of blocks, largest first. One island means intact. */
export function islands(grid) {
  const seen = new Set(), out = [];
  for (const startKey of grid.blocks.keys()) {
    if (seen.has(startKey)) continue;
    const group = new Set(), stack = [startKey];
    seen.add(startKey);
    while (stack.length) {
      const k = stack.pop();
      const b = grid.blocks.get(k);
      if (b) group.add(b);
      const [x, y, z] = k.split(',').map(Number);
      for (const d of AXES) {
        const nk = key(x + d[0], y + d[1], z + d[2]);
        if (grid.blocks.has(nk) && !seen.has(nk)) { seen.add(nk); stack.push(nk); }
      }
    }
    out.push([...group]);
  }
  return out.sort((a, b) => b.length - a.length);
}

/** Split a grid into one grid per island. The original is left untouched. */
export function splitIslands(grid) {
  return islands(grid).map((group, i) => {
    const g = makeGrid(grid.sizeClass, `${grid.id}.${i}`);
    for (const b of group)
      for (const c of cellsOf(b.type, b.cell, b.rot)) g.blocks.set(key(...c), b);
    return g;
  });
}

/** Inclusive cell bounds, or null for an empty grid. */
export function bounds(grid) {
  if (!grid.blocks.size) return null;
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const k of grid.blocks.keys()) {
    const c = k.split(',').map(Number);
    for (let i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], c[i]); hi[i] = Math.max(hi[i], c[i]); }
  }
  return { lo, hi, sizeM: hi.map((h, i) => (h - lo[i] + 1) * cellM(grid.sizeClass)) };
}
