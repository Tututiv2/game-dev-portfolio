/* ============================================================================
   THE SHIP LAYER — the contract.

   This file exists for one reason: so that several people (or agents) can build
   the ship layer AT THE SAME TIME without guessing at each other's interfaces.
   It is modelled directly on GRAFT's `sim/events/api.js`, which is the only
   thing in this portfolio that has actually been built that way — seven cosmic
   events, seven agents, one file each, no collisions. What made that work was
   not the agents. It was that the contract was written FIRST.

   READ THIS BEFORE WRITING A LINE. If what you need is not here, say so and the
   contract changes — do not reach around it. A system that reads another
   system's internals is the thing this file exists to prevent.

   ---------------------------------------------------------------------------
   THE THREE RULES

   1. YOU OWN ONE FILE, under src/ships/, named after your system. You may add
      block types with defineBlock() and you may add fields to `ship.data`. You
      may not edit grid.js, api.js, or another system's file.

   2. src/ships/ IS PURE unless your file is named *_view.js. No three.js, no
      DOM, no window, no Math.random, no Date. The suite greps for all of them.
      This is the same rule sim/ obeys in HOLLOW and GRAFT, and it is what lets
      every system be tested with no GPU and no browser. Rendering goes in a
      sibling `<system>_view.js`, which may import three and may NOT contain
      game logic.

   3. RANDOMNESS COMES FROM THE SHIP, NEVER FROM THE GLOBAL. `api.rand(ship)` is
      seeded from the world seed. VESPER's save format is the seed plus a sparse
      diff, so anything you roll from Math.random cannot survive a reload and
      will silently desync. There is no exception to this.

   ---------------------------------------------------------------------------
   WHAT A SHIP IS

     ship = {
       id,                 // stable, unique, string
       grid,               // from grid.js — the blocks, the size class
       pos:  [x, y, z],    // METRES, in the PLANET'S frame, not world space
       vel:  [x, y, z],    // metres/second, same frame
       ori:  [x, y, z, w], // quaternion, plain array, same frame
       spin: [x, y, z],    // radians/second about each grid axis
       owner,              // 'player' | npc id — who is flying it
       data: {},           // yours. MUST be JSON-safe: it is saved and reloaded
     }

   POSITIONS ARE IN THE PLANET'S FRAME. The planet group is tilted and spun, and
   this project has already lost a day to code that mixed that frame with world
   space — walk.mjs once reported the camera 8 km underground because of it, and
   a distance probe in this same session reported every object 700 m away for the
   same reason. If you need world space, ask the api; do not convert by hand.

   ---------------------------------------------------------------------------
   A SYSTEM DEFINITION

     export default {
       id: 'flight',                  // unique, lowercase, matches your filename
       needs: ['power'],              // other system ids that must tick first
       init(ship, api) {},            // once, when a ship is created or loaded
       tick(ship, dt, api) {},        // every sim step. dt is SECONDS, clamped
       onBlockAdded(ship, block, api) {},
       onBlockRemoved(ship, block, api) {},
       onSplit(parent, children, api) {},  // after a grid falls into islands
       save(ship) { return {}; },     // JSON-safe; merged into the save diff
       load(ship, saved) {},          // the same object back, after a reload
     }

   Every hook is optional. `needs` is a dependency ORDER, not an import — you
   still may not read another system's file.

   ---------------------------------------------------------------------------
   THE API YOU ARE HANDED

     api.rand(ship)          -> deterministic 0..1, seeded from world + ship id
     api.grid                -> the whole of grid.js, re-exported
     api.mass(ship)          -> kg
     api.com(ship)           -> centre of mass, METRES, grid-local
     api.thrust(ship, dir)   -> newtons available along a grid-space direction
     api.power(ship)         -> { produced, drawn, ok }
     api.gravity(ship)       -> [x,y,z] m/s^2 at the ship's position
     api.altitude(ship)      -> metres above the terrain directly below
     api.toWorld(ship, v)    -> planet frame -> world space
     api.ships()             -> every live ship, including NPC-flown ones
     api.emit(evt, payload)  -> tell other systems something happened

   ---------------------------------------------------------------------------
   WHAT IS ALREADY SOLVED, SO YOU DO NOT SOLVE IT AGAIN

   - THE DEPTH PROBLEM. You cannot draw a cockpit 1 m away and a 310 km planet
     with one camera; orbit.js clamps the near plane to 15.5 km in orbit and it
     is right to. view.js now renders in SLICES, split at the geometric mean of
     [0.02 m, camera.near] so both halves carry the same depth ratio at every
     altitude. Put ship-scale meshes in `view.nearScene` and it is handled. Do
     not touch camera.near, and do not add a logarithmic depth buffer —
     planetShader.js is hand-written GLSL and would need the chunks by hand.

   - SIZE CLASSES. grid.js has five, each 5x the last. Never write 2.5 anywhere;
     call cellM(grid.sizeClass). DESIGN.md is explicit that hardcoding the large
     grid makes B6's world-ship a rewrite.

   - ISLAND SPLITTING. grid.js islands()/splitIslands() already does the flood
     fill over face neighbours. B2's gate is "cut it in half and both halves
     behave" — the halves exist, what they DO is flight's problem.

   ---------------------------------------------------------------------------
   THE DESIGN LAW, WHICH APPLIES TO YOU

   CLAUDE.md: "There always has to be a path in game." No capability may come
   from a menu, an unlock or a tech tree. If your system grants the player
   something, it is granted by a physical object they built out of gathered
   material with a machine they also built. If you are writing a "press go"
   button, you have violated the design.
   ========================================================================= */

import * as G from './grid.js';

const REGISTRY = new Map();

/** Register a system. Called once per file at import time. */
export function registerSystem(def) {
  if (!def || !def.id) throw new Error('a system needs an id');
  if (REGISTRY.has(def.id)) throw new Error(`duplicate ship system ${def.id}`);
  REGISTRY.set(def.id, def);
  return def;
}

export const systems = () => [...REGISTRY.values()];

/** Systems in dependency order. Throws on a cycle rather than picking an order
    that happens to work today and stops working when a fourth system lands. */
export function systemOrder() {
  const out = [], mark = new Map();
  const visit = (id, trail) => {
    if (mark.get(id) === 'done') return;
    if (mark.get(id) === 'open')
      throw new Error(`ship system cycle: ${[...trail, id].join(' -> ')}`);
    const s = REGISTRY.get(id);
    if (!s) throw new Error(`system "${id}" needs "${id}", which is not registered`);
    mark.set(id, 'open');
    for (const n of s.needs || []) visit(n, [...trail, id]);
    mark.set(id, 'done');
    out.push(s);
  };
  for (const id of REGISTRY.keys()) visit(id, []);
  return out;
}

/* ---- deterministic per-ship randomness ------------------------------------
   A 32-bit integer hash of (worldSeed, ship.id, a counter kept on the ship), so
   the same ship rolls the same numbers after a reload. Math.random cannot do
   that and the save format is a seed plus a diff, so it would desync in a way
   that only shows up hours later. */
let WORLD_SEED = 0;
export function setWorldSeed(seedStr) {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < String(seedStr).length; i++) {
    h ^= String(seedStr).charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  WORLD_SEED = h;
}

export function rand(ship) {
  ship.data._r = (ship.data._r || 0) + 1;
  let h = WORLD_SEED ^ 0x9e3779b9;
  for (let i = 0; i < ship.id.length; i++) {
    h ^= ship.id.charCodeAt(i); h = Math.imul(h, 16777619) >>> 0;
  }
  h ^= ship.data._r; h = Math.imul(h, 2654435761) >>> 0;
  h ^= h >>> 15; h = Math.imul(h, 2246822507) >>> 0; h ^= h >>> 13;
  return (h >>> 0) / 4294967296;
}

/* ---- ships ---------------------------------------------------------------- */
let SHIPS = [];

export function makeShip(id, grid, opts = {}) {
  const s = {
    id, grid,
    pos: opts.pos ? [...opts.pos] : [0, 0, 0],
    vel: [0, 0, 0],
    ori: opts.ori ? [...opts.ori] : [0, 0, 0, 1],
    spin: [0, 0, 0],
    owner: opts.owner || 'player',
    data: {},
  };
  SHIPS.push(s);
  for (const sys of systemOrder()) sys.init && sys.init(s, API);
  return s;
}

export const ships = () => SHIPS;
export const clearShips = () => { SHIPS = []; };

/** One simulation step across every ship and system, in dependency order.
    dt is CLAMPED: PYPE and Learn to Fly both proved that stepping physics from
    a raw frame delta hands a slow machine a different game. */
export function step(dt) {
  const d = Math.min(Math.max(dt, 0), 1 / 30);
  for (const sys of systemOrder())
    if (sys.tick) for (const s of SHIPS) sys.tick(s, d, API);
}

/* ---- THE WORLD, HANDED IN RATHER THAN IMPORTED ----------------------------
   src/ships/ may not import three or the planet, or it stops being testable
   without a GPU. But flight needs gravity and altitude, and those are facts
   about a planet. So orbit.js ATTACHES the world at boot, passing only plain
   numbers and closures over already-pure functions (heightField.js's groundR is
   pure and takes a def).

   Nothing here holds a three.js object. `quat` is a 4-element array. */
let WORLD = null;

/** orbit.js calls this once per world build. Tests call it with a fake. */
export function attachWorld(w) { WORLD = w; }
export const world = () => WORLD;

const need = (fn) => {
  if (!WORLD) throw new Error(`api.${fn}: no world attached — call attachWorld() first`);
  return WORLD;
};
const len = (v) => Math.hypot(v[0], v[1], v[2]);

/* ---- the object handed to every hook -------------------------------------- */
export const API = {
  grid: G,
  rand,
  ships,
  mass: (ship) => G.mass(ship.grid),
  com: (ship) => G.centreOfMass(ship.grid),
  thrust: (ship, dir) => G.thrustAlong(ship.grid, dir),
  power: (ship) => G.power(ship.grid),

  /* INVERSE SQUARE, from the planet's own radius and density — so a small moon
     is genuinely weaker and orbit is genuinely a thing you can be in. Returns
     m/s^2 in the PLANET's frame, pointing DOWN (toward the centre). */
  gravity(ship) {
    const w = need('gravity');
    const r = len(ship.pos);
    if (r < 1e-6) return [0, 0, 0];
    const g = w.mu / (r * r);
    return [-ship.pos[0] / r * g, -ship.pos[1] / r * g, -ship.pos[2] / r * g];
  },

  /* Metres above the TERRAIN below, not above the datum. planetView.js already
     learned this the hard way: the ground is routinely kilometres from the
     datum, and inside a caldera a datum-relative altitude goes negative. */
  altitude(ship) {
    const w = need('altitude');
    const r = len(ship.pos);
    if (r < 1e-6) return -w.radius;
    const dir = [ship.pos[0] / r, ship.pos[1] / r, ship.pos[2] / r];
    return r - w.groundR(dir);
  },

  /** Planet frame -> world space. A rotation by the planet group's quaternion;
      the ship layer must never do this by hand (see the header note on frames). */
  toWorld(v) {
    const q = need('toWorld').quat;
    const [x, y, z] = v, [qx, qy, qz, qw] = q;
    const ix =  qw * x + qy * z - qz * y;
    const iy =  qw * y + qz * x - qx * z;
    const iz =  qw * z + qx * y - qy * x;
    const iw = -qx * x - qy * y - qz * z;
    return [ix * qw + iw * -qx + iy * -qz - iz * -qy,
            iy * qw + iw * -qy + iz * -qx - ix * -qz,
            iz * qw + iw * -qz + ix * -qy - iy * -qx];
  },

  emit(evt, payload) {
    for (const sys of systemOrder()) sys.onEvent && sys.onEvent(evt, payload, API);
  },
};
