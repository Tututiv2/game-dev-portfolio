/* ============================================================================
   CONSTRUCTION — weld and grind, with costs.

   A block is not placed finished. It is placed as a FRAMEWORK holding no
   material at all, and welded up from 0 to full integrity over time, eating
   components out of an inventory as it goes. Grinding runs the same machinery
   backwards and returns a FRACTION of what went in — never all of it.

   THE ONE RULE THIS FILE EXISTS TO ENFORCE:
   ------------------------------------------------------------------------
   RECLAIM < 1, always. If grinding returned everything, weld -> grind -> weld
   would be a free lunch and the economy would have a printer in it. Two other
   projects in this portfolio have been bitten by exactly that (an idle
   game's layer and PYRE's scrap economy — a loop that pays back what it costs stops
   being a decision). It is asserted in tests/ships_construction.mjs, and that
   assertion is itself proved able to fail.

   INTEGRITY IS ONE NUMBER AND IT IS DERIVED, NOT PARALLEL.
   ------------------------------------------------------------------------
   integrity = block.hp / BLOCK_TYPES[type].hp. There is deliberately no second
   "built" field, because two numbers that mean the same thing drift the first
   time a combat system touches one of them. Consequences, all of them wanted:
     - welding IS repairing, and costs the same material either way;
     - damage destroys material, because a damaged block grinds back to less;
     - a partly welded block survives any save that stores hp.
   A framework sits at hp 0. It is flagged `frame: true` while it has never
   held material, so a damage system that culls hp<=0 blocks can tell "not
   built yet" from "shot to pieces". See THE CONTRACT GAPS below.

   SCALE. Welding rate and reach both come from the SIZE CLASS, never from a
   constant here. Rate scales with the cube of the cell size, exactly as mass
   and cost do, so one block takes the same wall time at every class *with the
   right tool* — and a small tool on a world grid is 15,625x too slow, which is
   the whole reason to build the bigger welder. Building a world-ship one nudge
   at a time is not a design.

   THE DESIGN LAW. "There always has to be a path in game." Welding is not a
   verb the player has; it is what a physical welder does:
     - a WELDER BLOCK on a grid (`toolFromBlock`) — which must itself be fully
       welded and the grid must be making power, so you cannot bootstrap a
       welder with itself;
     - a HAND WELDER (`handTool`) — which is refused unless the inventory
       actually holds a `welder` item. No item, no welding. The first one comes
       out of the drop pod in B3, not out of a menu.
   There is no function in this file that builds anything without a tool.

   THE CONTRACT GAPS I had to work around (api.js changes, not mine to make):
     1. There is no api.gridToPlanet(ship, gridLocalMetres). api.toWorld() only
        does planet -> world. Reach has to be measured somewhere, so this file
        rotates grid-local metres by ship.ori and adds ship.pos, assuming
        ship.pos IS the grid's cell origin. If that assumption is ever wrong,
        every reach test in this file is wrong with it. ASK FOR
        api.gridToPlanet() and delete `blockPos`/`qrot` below.
     2. grid.mass()/thrustAlong()/power() do not know about integrity, so a
        grid of frameworks weighs and pushes as if it were finished. This file
        exports `builtMass(grid)` and `isFunctional(block)`; flight and power
        should use them. It cannot be fixed from here without editing grid.js.
     3. There is no inventory system yet. Any plain object of
        {component: units} works as one; ships keep a placeholder stock at
        ship.data.cons.stock so this layer is testable today. When B3's
        inventory lands, pass it as the `inv` argument and delete the default.
     4. defineBlock() throws on a duplicate id, and eight agents are adding
        block types at once. `ensureBlock` below defines 'welder' only if
        nobody else already has.
   ========================================================================= */

import * as G from './grid.js';
import { registerSystem } from './api.js';

/* ---- constants ------------------------------------------------------------
   All rates are quoted AT THE `large` CLASS and scaled by volume from there,
   the same way grid.js quotes mass. */
export const WELD_KG_S = 50;      // kg of components per second, large class
export const GRIND_MULT = 1.6;    // grinding is quicker than welding
export const RECLAIM = 0.7;       // < 1. Load-bearing. See the header.

const vol = (sizeClass) => {
  const s = G.cellM(sizeClass) / G.SIZE_CLASSES.large.m;
  return s * s * s;
};

export const weldRate  = (sizeClass) => WELD_KG_S * vol(sizeClass);
export const grindRate = (sizeClass) => WELD_KG_S * GRIND_MULT * vol(sizeClass);
export const reachOf   = (sizeClass) => G.SIZE_CLASSES[sizeClass].reach;

/* ---- what a block is made of ----------------------------------------------
   Fractions BY MASS, summing to 1. The absolute cost is the block's own mass
   from grid.js, so cost scales with the size class for free and a world hull
   plate costs 15,625 large ones without a second table.

   A block type nobody listed here costs plain steel. That is on purpose: an
   agent adding a block type in another file must not have to edit this one. */
export const MIX = {
  hull:     { steel: 1 },
  cockpit:  { steel: 0.6, circuit: 0.4 },
  thruster: { steel: 0.7, alloy: 0.3 },
  reactor:  { steel: 0.5, alloy: 0.2, circuit: 0.3 },
  gyro:     { steel: 0.6, alloy: 0.4 },
  welder:   { steel: 0.6, circuit: 0.4 },
};
export const DEFAULT_MIX = { steel: 1 };
export const mixOf = (typeId) => MIX[typeId] || DEFAULT_MIX;

/* ---- the welder, which is a block you build -------------------------------
   Defined defensively: several agents are writing to BLOCK_TYPES right now and
   defineBlock() throws on a duplicate. */
function ensureBlock(def) {
  return G.BLOCK_TYPES[def.id] || G.defineBlock(def);
}
export const WELDER = ensureBlock({
  id: 'welder', name: 'welder arm', mass: 400, hp: 300, power: -40, weld: true,
});

/* ---- integrity ------------------------------------------------------------ */
export const maxHp = (block) => G.BLOCK_TYPES[block.type].hp;

/** 0 = a framework holding no material, 1 = finished. */
export function integrity(block) {
  const m = maxHp(block);
  if (!(m > 0)) return 1;
  return Math.min(1, Math.max(0, (block.hp || 0) / m));
}

/** Only a finished block does its job. grid.js cannot know this — gap 2. */
export const isFunctional = (block) => integrity(block) >= 1;

/** The material actually committed to this grid so far, in kg. A grid of
    frameworks is weightless; grid.mass() reports it as finished (gap 2). */
export function builtMass(grid) {
  let m = 0;
  for (const b of G.uniqueBlocks(grid)) m += G.blockMass(grid, b) * integrity(b);
  return m;
}

/* ---- costs ---------------------------------------------------------------- */
/** Total kg of components a block needs from empty to finished. */
export const fullCost = (grid, block) => G.blockMass(grid, block);

/** The same thing split into components: {steel: kg, circuit: kg, ...}. */
export function costOf(grid, block) {
  const mix = mixOf(block.type), total = fullCost(grid, block), out = {};
  for (const k in mix) out[k] = mix[k] * total;
  return out;
}

/** kg still owed on this block. */
export const remaining = (grid, block) => (1 - integrity(block)) * fullCost(grid, block);

/* ---- inventories ----------------------------------------------------------
   A plain object of {component: units}. Nothing here mutates anything else. */
export const stockOf = (ship) => consData(ship).stock;

function take(inv, mix, kg) {
  for (const k in mix) inv[k] = (inv[k] || 0) - mix[k] * kg;
}
function give(inv, mix, kg) {
  for (const k in mix) inv[k] = (inv[k] || 0) + mix[k] * kg;
}
/** The largest number of kg this inventory can pay for at this mix. */
function affordable(inv, mix) {
  let best = Infinity;
  for (const k in mix) {
    if (mix[k] <= 0) continue;
    best = Math.min(best, (inv[k] || 0) / mix[k]);
  }
  return Math.max(0, best === Infinity ? 0 : best);
}

/* ---- frames: planet-frame position of a block (gap 1) --------------------- */
function qrot(q, v) {
  const [x, y, z] = v, [qx, qy, qz, qw] = q;
  const ix =  qw * x + qy * z - qz * y;
  const iy =  qw * y + qz * x - qx * z;
  const iz =  qw * z + qx * y - qy * x;
  const iw = -qx * x - qy * y - qz * z;
  return [ix * qw + iw * -qx + iy * -qz - iz * -qy,
          iy * qw + iw * -qy + iz * -qx - ix * -qz,
          iz * qw + iw * -qz + ix * -qy - iy * -qx];
}

/** The centre of a block, in METRES, in the PLANET'S frame. Never world space
    — call api.toWorld() on the result if that is what you want. */
export function blockPos(ship, block) {
  const s = G.cellM(ship.grid.sizeClass);
  const cells = G.cellsOf(block.type, block.cell, block.rot);
  let cx = 0, cy = 0, cz = 0;
  for (const c of cells) {
    cx += (c[0] + 0.5) * s; cy += (c[1] + 0.5) * s; cz += (c[2] + 0.5) * s;
  }
  const local = [cx / cells.length, cy / cells.length, cz / cells.length];
  const r = qrot(ship.ori || [0, 0, 0, 1], local);
  const p = ship.pos || [0, 0, 0];
  return [p[0] + r[0], p[1] + r[1], p[2] + r[2]];
}

export function distanceTo(ship, tool, block) {
  const b = blockPos(ship, block), t = tool.pos || [0, 0, 0];
  return Math.hypot(b[0] - t[0], b[1] - t[1], b[2] - t[2]);
}

export const canReach = (ship, tool, block) =>
  distanceTo(ship, tool, block) <= tool.reach + 1e-9;

/* ---- tools, which are physical objects ------------------------------------ */
/** A welder ARM bolted to a grid. Refused unless it is finished and the grid is
    making enough power — you cannot bootstrap a welder out of itself. */
export function toolFromBlock(ship, block, api) {
  if (!block || !G.BLOCK_TYPES[block.type] || !G.BLOCK_TYPES[block.type].weld) return null;
  if (!isFunctional(block)) return null;
  const p = api && api.power ? api.power(ship) : G.power(ship.grid);
  if (!p.ok) return null;
  const cls = ship.grid.sizeClass;
  return {
    kind: 'block', sizeClass: cls, pos: blockPos(ship, block),
    reach: reachOf(cls), weld: weldRate(cls), grind: grindRate(cls),
  };
}

/** The tool in a player's hands. There is no such thing as an empty-handed
    weld: the inventory has to actually hold a welder item, and that item is
    made at an assembler out of gathered material (B3). */
export function handTool(inv, pos, sizeClass = 'small') {
  if (!inv || !(inv.welder >= 1)) return null;
  return {
    kind: 'hand', sizeClass, pos: [...(pos || [0, 0, 0])],
    reach: reachOf(sizeClass), weld: weldRate(sizeClass), grind: grindRate(sizeClass),
  };
}

/* ---- placing a framework --------------------------------------------------
   FREE, and holding nothing. Material only ever moves in weld() and grind(),
   which is the structural reason a place/remove loop cannot print anything. */
export function placeFrame(ship, typeId, cell, rot = 0) {
  const b = G.addBlock(ship.grid, typeId, cell, rot);
  if (!b) return null;
  b.hp = 0;
  b.frame = true;
  return b;
}

/* ---- weld ------------------------------------------------------------------
   Returns { kg, spent, done }. kg is the material actually committed this
   step: it is limited by the tool's rate, by what the block still owes, and by
   what the inventory can pay for — in that order, and it is never negative. */
export function weld(ship, tool, block, dt, inv = null) {
  const out = { kg: 0, spent: {}, done: false, reason: '' };
  if (!tool) { out.reason = 'no tool'; return out; }
  if (!block || !G.BLOCK_TYPES[block.type]) { out.reason = 'no block'; return out; }
  if (!(dt > 0)) { out.reason = 'no time'; return out; }
  if (!canReach(ship, tool, block)) { out.reason = 'out of reach'; return out; }

  const store = inv || stockOf(ship);
  const grid = ship.grid, mix = mixOf(block.type);
  const owed = remaining(grid, block);
  if (owed <= 1e-9) { out.done = true; out.reason = 'already built'; return out; }

  const kg = Math.min(tool.weld * dt, owed, affordable(store, mix));
  if (kg <= 0) { out.reason = 'no material'; return out; }

  take(store, mix, kg);
  for (const k in mix) out.spent[k] = mix[k] * kg;
  out.kg = kg;

  const total = fullCost(grid, block);
  const next = integrity(block) + kg / total;
  block.hp = next >= 1 - 1e-9 ? maxHp(block) : next * maxHp(block);
  if (block.hp > 0) delete block.frame;
  out.done = integrity(block) >= 1;
  return out;
}

/* ---- grind -----------------------------------------------------------------
   Returns { kg, gained, removed }. `kg` is what was DISASSEMBLED; `gained` is
   RECLAIM of it. The difference is slag and it does not come back. Grinding a
   block to nothing removes it from the grid — a framework that never held
   material removes for free and returns nothing, which is what makes the
   place/remove loop worthless rather than merely unprofitable. */
export function grind(ship, tool, block, dt, inv = null) {
  const out = { kg: 0, gained: {}, removed: false, reason: '' };
  if (!tool) { out.reason = 'no tool'; return out; }
  if (!block || !G.BLOCK_TYPES[block.type]) { out.reason = 'no block'; return out; }
  if (!(dt > 0)) { out.reason = 'no time'; return out; }
  if (!canReach(ship, tool, block)) { out.reason = 'out of reach'; return out; }

  const store = inv || stockOf(ship);
  const grid = ship.grid, mix = mixOf(block.type);
  const total = fullCost(grid, block);
  const held = integrity(block) * total;

  const kg = Math.min(tool.grind * dt, held);
  if (kg > 0) {
    give(store, mix, RECLAIM * kg);
    for (const k in mix) out.gained[k] = mix[k] * RECLAIM * kg;
    out.kg = kg;
    const next = (held - kg) / total;
    block.hp = next <= 1e-9 ? 0 : next * maxHp(block);
  }

  /* Nothing left to take apart, and the tool is still on it: the block goes.
     A rate-limited grind reaches this on a later step, never the same one. */
  if (integrity(block) <= 1e-9) {
    G.removeBlock(grid, block.cell[0], block.cell[1], block.cell[2]);
    out.removed = true;
  }
  return out;
}

/* ---- per-ship state -------------------------------------------------------- */
function consData(ship) {
  if (!ship.data.cons) ship.data.cons = { stock: {}, auto: true };
  if (!ship.data.cons.stock) ship.data.cons.stock = {};
  return ship.data.cons;
}

/** Every finished, switched-on welder arm on this grid. */
export function welders(ship) {
  return G.uniqueBlocks(ship.grid)
    .filter((b) => G.BLOCK_TYPES[b.type].weld && isFunctional(b) && b.on !== false)
    .sort((a, b) => G.key(...a.cell) < G.key(...b.cell) ? -1 : 1);
}

/** Unfinished blocks, in a deterministic order — no Map-iteration luck. */
export function frames(ship) {
  return G.uniqueBlocks(ship.grid)
    .filter((b) => integrity(b) < 1)
    .sort((a, b) => G.key(...a.cell) < G.key(...b.cell) ? -1 : 1);
}

/* ---- the system ------------------------------------------------------------
   The tick is what a welder ARM does on its own: a machine the player built,
   drawing on the ship's own stock, working on whatever framework is in front
   of it. It is not an "auto-build" button — with no welder block, no power or
   no material, nothing happens at all. */
export const def = {
  id: 'construction',
  needs: [],

  init(ship) { consData(ship); },

  tick(ship, dt, api) {
    const c = consData(ship);
    if (c.auto === false) return;
    const todo = frames(ship);
    if (!todo.length) return;
    for (const w of welders(ship)) {
      const tool = toolFromBlock(ship, w, api);
      if (!tool) continue;
      let budget = dt;
      for (const b of todo) {
        if (budget <= 0) break;
        if (integrity(b) >= 1) continue;
        if (!canReach(ship, tool, b)) continue;
        const r = weld(ship, tool, b, budget, c.stock);
        if (r.kg <= 0) { if (r.reason === 'no material') return; continue; }
        budget -= r.kg / tool.weld;
      }
    }
  },

  /* JSON-safe. hp is saved per anchor cell because the grid serializer is not
     written yet and a half-welded ship that reloads finished is a printer. */
  save(ship) {
    const c = consData(ship), hp = {};
    for (const b of G.uniqueBlocks(ship.grid))
      if (integrity(b) < 1) hp[G.key(...b.cell)] = b.hp || 0;
    return { stock: { ...c.stock }, auto: c.auto !== false, hp };
  },

  load(ship, saved) {
    const c = consData(ship);
    if (!saved) return;
    c.stock = { ...(saved.stock || {}) };
    c.auto = saved.auto !== false;
    for (const b of G.uniqueBlocks(ship.grid)) {
      const k = G.key(...b.cell);
      if (Object.prototype.hasOwnProperty.call(saved.hp || {}, k)) {
        b.hp = saved.hp[k];
        if (b.hp <= 0) b.frame = true;
      }
    }
  },

  onBlockRemoved(ship, block) {
    if (block) delete block.frame;
  },
};

registerSystem(def);
export default def;
