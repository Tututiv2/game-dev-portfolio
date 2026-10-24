/* ============================================================================
   DAMAGE — blocks taking hits, dying, and the grid coming apart.

   B2's gate is "cut it in half — both halves behave". This file owns the CUT:
   a block loses hp, reaches zero, leaves the grid, and then the grid is asked
   whether it is still one thing. If it is not, splitIslands() runs and the
   pieces are handed out — as real ships — through api.emit('split', ...) and
   the onSplit(parent, children, api) hook. What the halves then DO is flight's
   problem and autopilot's problem. This file only guarantees that the split
   happens, that no block is lost or duplicated by it, and that mass is
   conserved across the pieces to the last kilogram.

   PURE. No three, no DOM, no clock, no Math.random. Every roll comes from
   api.rand(ship), which is seeded from the world seed and the ship id, because
   VESPER's save format is a seed plus a sparse diff and anything rolled from
   the global would desync silently on the next reload.

   ---------------------------------------------------------------------------
   WHAT THE CONTRACT DID NOT GIVE ME, AND HOW I WORKED AROUND IT

   1. `api` (the object handed to every hook) has no makeShip, no systems() and
      no removeShip. Splitting a grid MUST create ships — a piece that is not a
      ship is never ticked by flight, so "both halves behave" is unreachable —
      and the onSplit hook must be dispatched to every system, which needs the
      registry. Both are exported from api.js as MODULE functions, so this file
      imports them by name rather than reaching around the contract. If the API
      object ever gains `makeShip` / `systems`, switch to them and delete the
      imports; nothing else changes.

   2. There is no removeShip, so a ship whose grid reaches zero blocks cannot be
      taken out of api.ships(). It is flagged `ship.data.damage.dead = true` and
      'shipDestroyed' is emitted; whoever owns the ship list should sweep them.

   3. grid.js sets a fresh block's hp to BLOCK_TYPES[type].hp with no scaling,
      while mass scales by cells x class^3. An unscaled hp makes a world-class
      hull plate — 1,562 tonnes of it — die to the same shot as a 100 kg one,
      which is the same class of mistake DESIGN.md warns about for hardcoding
      2.5 m. So hp is normalised HERE, on first touch, to the same
      cells x class^3 rule mass uses, and the result is cached on the block as
      `hpMax`. Nothing else in the layer needs to know: read hp through
      hpOf()/maxHpOf() and it is always consistent.

   ---------------------------------------------------------------------------
   THE DESIGN LAW

   Nothing here is a menu. Damage arrives from a physical thing hitting another
   physical thing; a destroyed block emits 'blockDestroyed' carrying its type
   and cell so that salvage can be a real object you pick up rather than a
   number a screen awards you. repair() exists for a welder block to call — it
   can never resurrect a destroyed block, because the block is gone and the only
   path back is building it again out of material.

   An optional per-block-type field is honoured if another system defines one:

     defineBlock({ id: 'armour', hp: 900, resist: 0.4 })

   `resist` is a fraction of incoming damage shrugged off, clamped to 0..0.95 so
   that nothing can ever be invulnerable. Absent, it is 0 and every existing
   block type behaves exactly as before.
   ========================================================================= */

import * as GRID from './grid.js';
import { registerSystem, API, systems, systemOrder, makeShip } from './api.js';

/* ---- helpers -------------------------------------------------------------- */

const gridOf = (api) => (api && api.grid) || GRID;

/** Systems in dependency order where possible, registry order otherwise. A
    half-registered layer must still get its onSplit call — a dispatch that
    throws because a fourth system has not loaded yet would lose the event. */
function allSystems() {
  try { return systemOrder(); } catch { return systems(); }
}

/** Per-ship state. JSON-safe by construction: numbers, booleans and a queue of
    plain objects, nothing else. */
export function state(ship) {
  if (!ship.data.damage)
    ship.data.damage = { destroyed: 0, splits: 0, pieces: 0, dead: false, pending: [] };
  const d = ship.data.damage;
  if (!Array.isArray(d.pending)) d.pending = [];
  return d;
}

/** Full hp of one block, scaled the same way grid.js scales mass: per cell, and
    by the CUBE of the size class. Never hardcodes a cell size. */
export function maxHpOf(grid, b, G = GRID) {
  const t = G.BLOCK_TYPES[b.type];
  if (!t) throw new Error(`unknown block type ${b.type}`);
  const s = G.cellM(grid.sizeClass) / G.SIZE_CLASSES.large.m;
  const cells = t.size[0] * t.size[1] * t.size[2];
  return t.hp * cells * s * s * s;
}

/** Give a block its scaled hp the first time anything looks at it. Idempotent:
    a block that already carries hpMax keeps whatever damage it has taken. */
export function ensureHp(grid, b, G = GRID) {
  if (b.hpMax === undefined) {
    b.hpMax = maxHpOf(grid, b, G);
    b.hp = b.hpMax;
  }
  return b;
}

export function hpOf(grid, b, G = GRID) { ensureHp(grid, b, G); return b.hp; }

/** 0..1 — how much of this ship's total hp is still standing. For a HUD, and
    for autopilot deciding whether to run. */
export function integrity(ship, api) {
  const G = gridOf(api), grid = ship.grid;
  let hp = 0, max = 0;
  for (const b of G.uniqueBlocks(grid)) {
    ensureHp(grid, b, G);
    hp += Math.max(0, b.hp); max += b.hpMax;
  }
  return max > 0 ? hp / max : 0;
}

function resistOf(b, G) {
  const r = G.BLOCK_TYPES[b.type].resist || 0;
  return Math.min(0.95, Math.max(0, r));
}

const hasCockpit = (grid, G) =>
  G.uniqueBlocks(grid).some(b => G.BLOCK_TYPES[b.type].cockpit);

/* ---- one block takes a hit ------------------------------------------------ */

function destroyBlock(ship, b, api, cause) {
  const G = gridOf(api);
  b.hp = 0;
  G.removeBlock(ship.grid, b.cell[0], b.cell[1], b.cell[2]);
  state(ship).destroyed++;
  for (const sys of allSystems()) sys.onBlockRemoved && sys.onBlockRemoved(ship, b, api);
  api.emit('blockDestroyed', {
    ship, block: b, cell: [...b.cell], type: b.type, cause: cause || 'damage',
  });
}

/** Damage one block without checking connectivity. Internal: several hits in
    one frame should cost ONE flood fill, not one each. */
function hitCell(ship, cell, amount, api, opts) {
  const G = gridOf(api);
  const b = G.blockAt(ship.grid, cell[0], cell[1], cell[2]);
  if (!b) return null;
  ensureHp(ship.grid, b, G);

  let dmg = Math.max(0, amount || 0) * (1 - resistOf(b, G));
  const v = Math.max(0, Math.min(1, opts.variance || 0));
  if (v > 0 && dmg > 0) dmg *= 1 + (api.rand(ship) * 2 - 1) * v;
  if (dmg < 0) dmg = 0;

  const hpBefore = b.hp;
  b.hp = hpBefore - dmg;
  const destroyed = b.hp <= 0;
  if (destroyed) destroyBlock(ship, b, api, opts.cause);
  return {
    block: b, cell: [...cell], type: b.type,
    hpBefore, hpAfter: Math.max(0, b.hp), damage: dmg, destroyed,
  };
}

/* ---- connectivity: the actual gate ---------------------------------------- */

/** Ask the grid whether it is still one thing, and deal with the answer.
    Exported because the build system's grinder removes blocks too, and a ship
    cut apart by a grinder must come apart exactly like one cut apart by a gun.

    Returns the array of NEW child ships, or null if nothing came apart. */
export function settle(ship, api = API, cause = 'damage') {
  const G = gridOf(api);
  const grid = ship.grid;
  const d = state(ship);

  if (grid.blocks.size === 0) {
    if (!d.dead) { d.dead = true; api.emit('shipDestroyed', { ship, cause }); }
    return null;
  }

  const isl = G.islands(grid);
  if (isl.length <= 1) return null;

  const pieces = G.splitIslands(grid);        // largest first, original untouched

  /* The parent keeps the piece the pilot is standing in — a cockpit island,
     whatever its size. Losing the player's own frame of reference because the
     tail happened to weigh more is not a physics result, it is a bug. With no
     cockpit anywhere, the biggest piece is the parent. */
  let keep = pieces.findIndex(p => hasCockpit(p, G));
  if (keep < 0) keep = 0;

  const keptId = grid.id;
  pieces[keep].id = keptId;
  ship.grid = pieces[keep];

  const children = [];
  for (let i = 0; i < pieces.length; i++) {
    if (i === keep) continue;
    d.pieces++;
    const cid = `${ship.id}~${d.pieces}`;
    pieces[i].id = cid;
    const child = makeShip(cid, pieces[i], {
      pos: ship.pos,
      ori: ship.ori,
      /* A piece with a cockpit is still somebody's ship. A piece without one
         is wreckage until someone welds a cockpit onto it — which is a real
         object built from real material, so the law holds. */
      owner: hasCockpit(pieces[i], G) ? ship.owner : 'debris',
    });
    /* Inherit the parent's motion EXACTLY. No separation impulse is applied
       here on purpose: a piece flying apart is a force, forces are flight's,
       and inventing one here would quietly break conservation of momentum in a
       file nobody would think to look in. */
    child.vel = [...ship.vel];
    child.spin = [...ship.spin];
    children.push(child);
  }
  d.splits++;

  api.emit('split', { ship, parent: ship, children, cause });
  for (const sys of allSystems()) sys.onSplit && sys.onSplit(ship, children, api);
  return children;
}

/* ---- the public verbs ----------------------------------------------------- */

/** Damage the block at a grid cell. Resolves connectivity immediately.
      opts.variance  0..1, deterministic jitter drawn from api.rand(ship)
      opts.cause     a string carried through every event, for salvage/logs */
export function applyDamage(ship, cell, amount, api = API, opts = {}) {
  const r = hitCell(ship, cell, amount, api, opts);
  if (r && r.destroyed) settle(ship, api, opts.cause || 'damage');
  return r;
}

/** A blast. `centreM` and `radiusM` are METRES in the grid's own frame — the
    same frame centreOfMass() returns — so a 4 m explosion is 4 m whether it
    lands on a micro drone or a world-ship, and nothing here knows what a cell
    is worth. Linear falloff to zero at the rim. One flood fill for the lot. */
export function damageSphere(ship, centreM, radiusM, damage, api = API, opts = {}) {
  const G = gridOf(api), grid = ship.grid;
  const out = { hits: [], destroyed: 0, children: null };
  if (!(radiusM > 0)) return out;

  const s = G.cellM(grid.sizeClass);
  const targets = [];
  for (const b of G.uniqueBlocks(grid)) {
    let best = Infinity;
    for (const c of G.cellsOf(b.type, b.cell, b.rot)) {
      const dx = (c[0] + 0.5) * s - centreM[0];
      const dy = (c[1] + 0.5) * s - centreM[1];
      const dz = (c[2] + 0.5) * s - centreM[2];
      const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (dist < best) best = dist;
    }
    if (best <= radiusM) targets.push({ cell: b.cell, f: 1 - best / radiusM });
  }
  /* Nearest first, so a blast that destroys a block never has its remaining
     energy re-ordered by Map insertion order. Ties broken by cell for stability
     — determinism is the whole reason api.rand exists, and an unstable sort
     would undo it. */
  targets.sort((a, b) => (b.f - a.f) ||
    (a.cell[0] - b.cell[0]) || (a.cell[1] - b.cell[1]) || (a.cell[2] - b.cell[2]));

  for (const t of targets) {
    if (!grid.blocks.has(G.key(t.cell[0], t.cell[1], t.cell[2]))) continue;
    const r = hitCell(ship, t.cell, damage * t.f, api, opts);
    if (r) { out.hits.push(r); if (r.destroyed) out.destroyed++; }
  }
  out.children = settle(ship, api, opts.cause || 'blast');
  return out;
}

/** Queue a hit for the next tick. Several weapons firing into one frame then
    cost one flood fill between them instead of one each. JSON-safe, so a save
    taken mid-frame reloads with the shots still pending. */
export function queueDamage(ship, cell, amount, opts = {}) {
  state(ship).pending.push({
    c: [cell[0], cell[1], cell[2]],
    a: amount,
    v: opts.variance || 0,
    cause: opts.cause || 'damage',
  });
}

/** Put hp back. Cannot resurrect: a destroyed block is not in the grid, so
    there is nothing to repair and the only path back is building it. */
export function repair(ship, cell, amount, api = API) {
  const G = gridOf(api);
  const b = G.blockAt(ship.grid, cell[0], cell[1], cell[2]);
  if (!b) return null;
  ensureHp(ship.grid, b, G);
  b.hp = Math.min(b.hpMax, b.hp + Math.max(0, amount || 0));
  return b;
}

/* ---- the system ----------------------------------------------------------- */

export default registerSystem({
  id: 'damage',
  needs: [],

  init(ship) {
    state(ship);
    for (const b of GRID.uniqueBlocks(ship.grid)) ensureHp(ship.grid, b, GRID);
  },

  onBlockAdded(ship, block) { ensureHp(ship.grid, block, GRID); },

  tick(ship, dt, api) {
    const d = state(ship);
    if (!d.pending.length) return;
    const q = d.pending;
    d.pending = [];
    let broke = false;
    for (const e of q) {
      const r = hitCell(ship, e.c, e.a, api, { variance: e.v, cause: e.cause });
      if (r && r.destroyed) broke = true;
    }
    if (broke) settle(ship, api, 'damage');
  },

  save(ship) {
    const d = state(ship);
    const hp = {};
    for (const b of GRID.uniqueBlocks(ship.grid)) {
      if (b.hpMax === undefined) continue;
      if (b.hp !== b.hpMax) hp[GRID.key(b.cell[0], b.cell[1], b.cell[2])] = b.hp;
    }
    return {
      hp,
      destroyed: d.destroyed, splits: d.splits, pieces: d.pieces,
      dead: !!d.dead,
      pending: d.pending.map(e => ({ c: [...e.c], a: e.a, v: e.v, cause: e.cause })),
    };
  },

  load(ship, saved) {
    const d = state(ship);
    if (!saved) return;
    d.destroyed = saved.destroyed || 0;
    d.splits = saved.splits || 0;
    d.pieces = saved.pieces || 0;
    d.dead = !!saved.dead;
    d.pending = Array.isArray(saved.pending)
      ? saved.pending.map(e => ({ c: [...e.c], a: e.a, v: e.v, cause: e.cause })) : [];
    for (const b of GRID.uniqueBlocks(ship.grid)) ensureHp(ship.grid, b, GRID);
    const hp = saved.hp || {};
    for (const k of Object.keys(hp)) {
      const c = k.split(',').map(Number);
      const b = GRID.blockAt(ship.grid, c[0], c[1], c[2]);
      if (b) b.hp = hp[k];
    }
  },
});
