// THE SCALE LADDER.
//
// "I gotta be able to get to a point where I could do anything a civilization
//  could do ... maybe make insanely huge things that could eat a planet or
//  destroy a planet, bigger ships solar system scale, then maybe some galactic
//  scale, you can harvest/or destroy a galaxy, then multi galaxies etc"
//
// Six tiers: Surface, Orbit, System, Cluster, Galaxy, Galaxies.
//
// THREE RULES HOLD THE WHOLE THING UP.
//
// 1. A TIER IS ENTERED BY BUILDING SOMETHING, never by picking it from a menu.
//    Every tier has exactly one gate -- an object in MEGA with `opens` -- and
//    until it is standing, the tier above does not exist for you. So the ladder
//    is made of the same verb as the rest of the game.
//
// 2. A TIER'S CEILING IS ITS LARDER. Your system holds eight worlds and one
//    star; the cluster holds hundreds; a galaxy holds hundreds of billions.
//    What ends a tier is not a cooldown, it is running out of things to eat --
//    which is what makes going up feel like a need rather than a menu choice.
//
// 3. RESOURCES FLOW BOTH WAYS, and the downward half is the one games forget.
//    Going up costs ground resources at EVERY rung (see `needs.alloy`), because
//    tonnage can be lifted and a crew cannot be printed. Going up also pays the
//    ground back: a Sun Mirror makes everything you planted grow faster, the
//    Maw rains refined debris on your fields. So the walking game is never
//    obsolete and never merely nostalgic -- it is the bottleneck at the top.
//
// Pure: no DOM, no three.js, no Math.random, no Date. The suite greps for all.

import { D } from './bignum.js';

/* ------------------------------------------------------------------ tiers */

// `stock` is what entering the tier puts within reach: worlds, stars, galaxies.
// Nothing can be eaten that is not in the larder.
export const TIERS = [
  {
    id: 'surface', name: 'Surface', reach: 'one planet',
    blurb: 'The ground you can walk across in an afternoon. Everything starts here and nothing stops mattering here.',
    gate: null, stock: {},
  },
  {
    id: 'orbit', name: 'Orbit', reach: 'one planet and the sky over it',
    blurb: 'A line to the top of the air. From up here the coastline you walked is a shape.',
    gate: 'tether', stock: {},
  },
  {
    id: 'system', name: 'System', reach: 'eight worlds and a star',
    blurb: 'Other worlds, close enough to reach and not close enough to matter to anyone but you.',
    gate: 'arkyard', stock: { world: 8, star: 1 },
  },
  {
    id: 'cluster', name: 'Cluster', reach: 'six hundred stars',
    blurb: 'The nearest stars, folded within reach. Your system is now a supply of parts.',
    gate: 'lanegate', stock: { world: 900, star: 620 },
  },
  {
    id: 'galaxy', name: 'Galaxy', reach: 'two hundred billion stars',
    blurb: 'The whole spiral, and the thing at the middle of it that everything else is falling into.',
    gate: 'spiralanchor', stock: { world: 1e12, star: 2.4e11 },
  },
  {
    id: 'galaxies', name: 'Galaxies', reach: 'everything you can see',
    blurb: 'Galaxies as objects. There is no larger word left, so the game stops naming and starts counting.',
    // There is no edge to this larder that anyone has found. It is a number
    // rather than Infinity because a save is JSON, and JSON has no Infinity.
    gate: 'bridge', stock: { galaxy: 1e300 },
  },
];

export const TIER_IDS = TIERS.map(t => t.id);
export function tierSpec(id) { return TIERS.find(t => t.id === id); }
export function tierIndex(id) { return TIER_IDS.indexOf(id); }

/* ------------------------------------------------------------ the catalog */

// Shaped like an entry in catalog.js -- id, name, verb, blurb, needs, stages --
// plus what a megastructure has that a tree does not:
//
//   tier      the tier you must already be in to build it
//   opens     this is that tier's GATE; finishing it puts you in the next tier
//   makes     per second, for ever, once it is standing   { mass, energy }
//   eats      { what, every, count, mass } -- consumes the larder for mass
//   boon      what it does for the GROUND   { growth, alloy, shard, biomass }
//   wasteful  it works, and it burns far more of the larder than it returns
//
// `needs.alloy/shard/biomass` come off the walking game. They stay small in
// absolute terms all the way to the top, and that is the design: at galactic
// scale a thousand alloy is a rounding error in tonnage and an absolute wall
// in practice, because nothing above the ground makes it.
export const MEGA = [
  /* ---------------------------------------------------------- surface --- */
  {
    id: 'foundry', name: 'Deep Foundry', verb: 'sink', tier: 'surface',
    blurb: 'A shaft under your best town that goes down until the rock is warm. It makes nothing you can point at; it makes the things that make things.',
    unlock: 'town',
    needs: { alloy: 40, shard: 90, biomass: 120 },
    stages: [
      { name: 'shaft', at: 0 },
      { name: 'lined', at: 90 },
      { name: 'hot', at: 120 },
    ],
    boon: { alloy: 0.22, shard: 0.30 },
  },
  {
    id: 'tether', name: 'Anchor Line', verb: 'raise', tier: 'surface', opens: 'orbit',
    blurb: 'A cable to nowhere until the day it reaches the top. After that, everything you will ever make goes up it.',
    unlock: 'town',
    needs: { alloy: 90, shard: 120, biomass: 170 },
    stages: [
      { name: 'anchor', at: 0 },
      { name: 'climbing', at: 150 },
      { name: 'made fast', at: 150 },
    ],
    makes: { mass: 2200 },
    boon: { alloy: 0.10 },
  },

  /* ------------------------------------------------------------ orbit --- */
  {
    id: 'ring', name: 'Orbital Ring', verb: 'lay', tier: 'orbit',
    blurb: 'A hoop of iron all the way round the sky, hung on nothing but its own speed. Metal comes down the line faster than it ever went up.',
    needs: { mass: 2.0e5, alloy: 120 },
    stages: [{ name: 'arc', at: 0 }, { name: 'closed', at: 150 }],
    makes: { mass: 2.4e4 },
    boon: { alloy: 0.6 },
  },
  {
    id: 'skyfarm', name: 'Sun Mirror', verb: 'unfurl', tier: 'orbit',
    blurb: 'Twelve square kilometres of foil that gives the night side a second morning. Everything you ever planted notices.',
    needs: { mass: 2.4e6, shard: 160 },
    stages: [{ name: 'folded', at: 0 }, { name: 'open', at: 180 }],
    boon: { growth: 1.8, biomass: 1.4 },
  },
  {
    id: 'sling', name: 'Mass Driver Field', verb: 'build', tier: 'orbit',
    blurb: 'A hundred kilometres of rail that throws rock into orbit without burning anything. The planet gets a little lighter every day.',
    needs: { mass: 4.0e6, alloy: 200 },
    stages: [{ name: 'rails', at: 0 }, { name: 'firing', at: 180 }],
    makes: { mass: 4.8e5 },
  },
  {
    id: 'arkyard', name: 'Ark Yard', verb: 'commission', tier: 'orbit', opens: 'system',
    blurb: 'A dock big enough to hold something that will never land. The first thing you build that is not for you.',
    needs: { mass: 9.0e7, alloy: 320, biomass: 400 },
    stages: [{ name: 'slips', at: 0 }, { name: 'crewed', at: 240 }],
    makes: { mass: 2.0e6 },
  },

  /* ----------------------------------------------------------- system --- */
  {
    id: 'shipworks', name: 'Deep Range Yards', verb: 'lay down', tier: 'system',
    blurb: 'Hulls that will never see this planet again, and the first crews who sign knowing it.',
    needs: { mass: 2.5e8, alloy: 380 },
    stages: [{ name: 'slipways', at: 0 }, { name: 'launching', at: 240 }],
    makes: { mass: 4.0e7 },
    boon: { alloy: 3.0, biomass: 2.0 },
  },
  {
    id: 'lifter', name: 'Star Lifter', verb: 'thread', tier: 'system',
    blurb: 'Magnetic funnels at both poles of the star, drinking off the top of it. A star is mostly fuel and it does not need all of it.',
    needs: { mass: 6.4e9, alloy: 500 },
    stages: [{ name: 'funnels', at: 0 }, { name: 'drawing', at: 300 }],
    makes: { mass: 6.0e9, energy: 4.0e9 },
    boon: { shard: 2.0 },
  },
  {
    id: 'dyson', name: 'Dyson Swarm', verb: 'seed', tier: 'system',
    blurb: 'Not a shell — a cloud of mirrors, one at a time, until the star is a lamp you own. Some of that light gets pointed home.',
    needs: { mass: 1.2e12, alloy: 700 },
    stages: [{ name: 'first panels', at: 0 }, { name: 'dimming', at: 300 }, { name: 'enclosed', at: 180 }],
    makes: { energy: 3.8e17, mass: 8.0e11 },
    boon: { growth: 2.6, biomass: 5.0 },
  },
  {
    id: 'cracker', name: 'World Cracker', verb: 'aim', tier: 'system',
    blurb: 'A world every twenty-five seconds, and you keep seven per cent of it. The rest leaves as light. For when you need mass NOW and can spare the worlds — and in your own system you cannot.',
    needs: { mass: 3.0e13, energy: 2.0e14, alloy: 700 },
    stages: [{ name: 'array', at: 0 }, { name: 'charged', at: 240 }],
    eats: { what: 'world', every: 25, count: 1, mass: 4.2e23 },
    wasteful: true,
  },
  {
    id: 'maw', name: 'the Maw', verb: 'open', tier: 'system',
    blurb: 'It takes a world apart from the crust down and keeps all of it. Four minutes a planet, and it rains the leavings on your fields.',
    needs: { mass: 2.0e14, energy: 6.0e15, alloy: 900, shard: 800 },
    stages: [{ name: 'jaws', at: 0 }, { name: 'turning', at: 240 }, { name: 'feeding', at: 180 }],
    eats: { what: 'world', every: 240, count: 1, mass: 5.97e24 },
    boon: { shard: 6.0, alloy: 3.0 },
  },
  {
    id: 'lanegate', name: 'Lane Weaver', verb: 'weave', tier: 'system', opens: 'cluster',
    blurb: 'It does not move you. It moves the distance. The nearest six hundred stars stop being far away.',
    // Costs LESS than one world on purpose. The Maw pays in lumps of 5.97e24
    // every four minutes, so a price a hair above one serving quietly costs a
    // whole extra serving -- four minutes of standing about for 0.5%.
    needs: { mass: 5.0e24, energy: 4.0e19, alloy: 1400 },
    stages: [{ name: 'first thread', at: 0 }, { name: 'taut', at: 360 }],
    makes: { mass: 4.0e21 },
  },

  /* ---------------------------------------------------------- cluster --- */
  {
    id: 'swarmyards', name: 'Swarm Yards', verb: 'scatter', tier: 'cluster',
    blurb: 'Yards at forty stars at once, all building the same thing, none of them told why. The first thing you make that you cannot see the whole of.',
    needs: { mass: 6.0e24, alloy: 1600 },
    stages: [{ name: 'first forty', at: 0 }, { name: 'in step', at: 360 }],
    makes: { mass: 1.0e23, energy: 4.0e18 },
    boon: { alloy: 20, shard: 14 },
  },
  {
    id: 'choir', name: 'the Harvest Choir', verb: 'sing', tier: 'cluster',
    blurb: 'Nine hundred worlds unmade at once, politely, in order. Nobody who lived on them was asked, and there is nowhere left to file the objection.',
    needs: { mass: 1.0e25, energy: 4.0e20, alloy: 2000, biomass: 1600 },
    stages: [{ name: 'assembled', at: 0 }, { name: 'in voice', at: 420 }],
    eats: { what: 'world', every: 20, count: 1, mass: 5.97e24 },
    boon: { growth: 3.2 },
  },
  {
    id: 'novalance', name: 'Nova Lance', verb: 'level', tier: 'cluster',
    blurb: 'You do not harvest a star with this. You end it, and stand in the shell it leaves. Two thousand billion billion billion kilograms, and a light that keeps arriving for centuries.',
    needs: { mass: 1.6e26, energy: 8.0e20, alloy: 1800 },
    stages: [{ name: 'lance', at: 0 }, { name: 'aligned', at: 480 }],
    eats: { what: 'star', every: 300, count: 1, mass: 1.99e30 },
    makes: { energy: 1.0e23 },
  },
  {
    id: 'lanenet', name: 'the Lane Net', verb: 'spin', tier: 'cluster',
    blurb: 'Every star in the cluster tied to every other. Nothing here is more than a thought apart now, which is a sentence that would have been a religion once.',
    needs: { mass: 1.0e30, alloy: 2200 },
    stages: [{ name: 'strung', at: 0 }, { name: 'humming', at: 420 }],
    makes: { mass: 2.0e27, energy: 2.0e22 },
    boon: { alloy: 200, shard: 140, biomass: 160 },
  },
  {
    id: 'spiralanchor', name: 'Spiral Anchor', verb: 'drive', tier: 'cluster', opens: 'galaxy',
    blurb: 'Driven into the gravity of the arm itself, so that the whole spiral turns around something you put there.',
    needs: { mass: 5.0e30, energy: 6.0e24, alloy: 2600 },
    stages: [{ name: 'sunk', at: 0 }, { name: 'holding', at: 480 }],
    makes: { mass: 4.0e28 },
  },

  /* ----------------------------------------------------------- galaxy --- */
  {
    id: 'armring', name: 'Arm Rings', verb: 'hoop', tier: 'galaxy',
    blurb: 'A ring around each arm of the spiral, so nothing falls inward without passing a toll. Four of them, and they take a century to see each other.',
    needs: { mass: 1.0e31, alloy: 3000 },
    stages: [{ name: 'first hoop', at: 0 }, { name: 'closed', at: 360 }],
    makes: { mass: 2.0e29, energy: 1.0e24 },
    boon: { alloy: 600, shard: 400 },
  },
  {
    id: 'spindle', name: 'the Spindle', verb: 'wind', tier: 'galaxy',
    blurb: 'A galaxy harvester. Twenty million stars a second off the arms, from the outside in, and the sky it leaves behind gets darker while you watch it.',
    needs: { mass: 1.3e31, alloy: 3200 },
    stages: [{ name: 'hub', at: 0 }, { name: 'arms', at: 300 }, { name: 'winding', at: 240 }],
    eats: { what: 'star', every: 1, count: 2.0e7, mass: 1.99e30 },
    makes: { energy: 4.0e25 },
    boon: { alloy: 400, shard: 300, biomass: 400 },
  },
  {
    id: 'coreengine', name: 'Core Engine', verb: 'yoke', tier: 'galaxy',
    blurb: 'The black hole at the middle was always going to eat this galaxy. You have simply put a wheel on it.',
    needs: { mass: 8.0e39, energy: 1.0e28, alloy: 3400 },
    stages: [{ name: 'yoke', at: 0 }, { name: 'spun up', at: 480 }],
    makes: { mass: 4.0e34, energy: 1.0e40 },
    boon: { growth: 4.0 },
  },
  {
    id: 'darkmill', name: 'Halo Mill', verb: 'string', tier: 'galaxy',
    blurb: 'Most of a galaxy is the part nobody can see. This is the machine that finally weighs it.',
    needs: { mass: 2.0e40, energy: 1.0e34, alloy: 3800 },
    stages: [{ name: 'strung', at: 0 }, { name: 'grinding', at: 480 }],
    makes: { mass: 6.0e36 },
    boon: { alloy: 1200, growth: 1.5 },
  },
  {
    id: 'bridge', name: 'the Bridge', verb: 'throw', tier: 'galaxy', opens: 'galaxies',
    blurb: 'Two and a half million light years of nothing, and you are about to make it a commute.',
    needs: { mass: 2.4e40, energy: 1.0e38, alloy: 4200, biomass: 3000 },
    stages: [{ name: 'far end', at: 0 }, { name: 'open', at: 540 }],
    makes: { mass: 2.0e38 },
  },

  /* --------------------------------------------------------- galaxies --- */
  {
    id: 'atlas', name: 'the Atlas', verb: 'open', tier: 'galaxies',
    blurb: 'Somebody has to write down which ones are gone. It turns out to be the last job left that a person does.',
    needs: { mass: 4.8e40, alloy: 5000 },
    stages: [{ name: 'first page', at: 0 }, { name: 'current', at: 420 }],
    makes: { mass: 5.0e38 },
    boon: { alloy: 3000, shard: 2400, biomass: 2600 },
  },
  {
    id: 'weave', name: 'the Weave', verb: 'cast', tier: 'galaxies',
    blurb: 'A galaxy a minute. You stopped naming them about four hundred ago.',
    needs: { mass: 8.0e40, alloy: 5200 },
    stages: [{ name: 'first strand', at: 0 }, { name: 'casting', at: 600 }],
    eats: { what: 'galaxy', every: 60, count: 1, mass: 1.5e42 },
    boon: { growth: 6.0, alloy: 4000 },
  },
  {
    id: 'shepherd', name: 'Shepherd Moons', verb: 'set', tier: 'galaxies',
    blurb: 'Galaxies herded into a line so that the Weave never waits. The largest thing in this game that is only a convenience.',
    needs: { mass: 9.0e42, energy: 1.0e40, alloy: 5400 },
    stages: [{ name: 'set', at: 0 }, { name: 'herding', at: 540 }],
    makes: { mass: 2.0e40 },
    boon: { alloy: 12000, shard: 9000 },
  },
  {
    id: 'unmaking', name: 'the Long Unmaking', verb: 'begin', tier: 'galaxies',
    blurb: 'Forty galaxies a second, and it teaches itself, so tomorrow it is more. There is no tier above this one: past here the ladder is only the number, and the number stops fitting in a computer.',
    needs: { mass: 3.0e43, energy: 1.0e42, alloy: 6000, shard: 5000, biomass: 5000 },
    stages: [{ name: 'begun', at: 0 }, { name: 'under way', at: 900 }],
    // The one thing on the ladder that COMPOUNDS. Everything else is a fixed
    // rate; this one grows with what it has already eaten, which is why the
    // ledger has to be a mantissa and an exponent and cannot be a float.
    eats: { what: 'galaxy', every: 1, count: 40, mass: 1.5e42, compound: 1e-3 },
    boon: { growth: 8.0, alloy: 40000 },
  },
];

export function megaSpec(id) { return MEGA.find(m => m.id === id); }
export function megaOfTier(id) { return MEGA.filter(m => m.tier === id); }

/** Seconds from commissioning to standing. */
export function buildTime(id) {
  const s = megaSpec(id);
  return s ? s.stages.reduce((a, st) => a + st.at, 0) : 0;
}

/** The gate of a tier: the one thing that opens the tier above it. */
export function gateOf(tier) { return MEGA.find(m => m.tier === tier && m.opens); }

/* ------------------------------------------------------------ the numbers */

// The surface economy the ladder assumes, per second, for a working city. A
// kiln is one alloy every 26s, so this is a handful of them plus gathering.
// The real game supplies its own; this is what `modelGroundRate` estimates
// with, and what the pacing test plays against.
export const GROUND_BASE = { alloy: 0.10, shard: 0.30, biomass: 0.40 };

// What the ladder is MEANT to feel like, in seconds per tier. This is design
// intent, stated before the sim was run, and the pacing test measures the real
// thing against it. A tier that drifts far from its target has stopped being
// the tier it was written as.
export const PACING = {
  surface: 1100,
  orbit: 1100,      // the SHORTEST rung on purpose: it is the one that teaches
  system: 2900,     // the new verb, and a tutorial you cannot leave is a wall
  cluster: 3100,
  galaxy: 3300,
  galaxies: 3300,
};

// The longest a player following the game's own hint may be left with nothing
// under construction. Anything more than this is a dead tier, and the whole
// reason the ladder has five things per rung instead of two.
export const MAX_DEAD_WAIT = 420;

/* -------------------------------------------------------------- the state */

export function createScale() {
  return {
    t: 0,
    tier: 'surface',
    res: { mass: new D(0, 0), energy: new D(0, 0) },
    built: {},               // id -> 1
    building: null,          // { id, endsAt }
    stock: { world: 0, star: 0, galaxy: 0 },
    eaten: { world: 0, star: 0, galaxy: 0 },
    timer: {},               // eater id -> seconds since it last fed
    stalled: {},             // eater id -> true when its larder is empty
    log: [],
  };
}

export function has(sc, id) { return !!sc.built[id]; }
export function reached(sc, tier) { return tierIndex(sc.tier) >= tierIndex(tier); }

/* ---------------------------------------------------------------- income */

/**
 * How many bodies this eater takes per sitting, now. Fixed for everything
 * except the Long Unmaking, which grows with what it has already swallowed.
 */
export function eatCount(sc, m) {
  const e = m.eats;
  if (!e) return 0;
  const base = e.count || 1;
  if (!e.compound) return base;
  const n = base * (1 + (sc.eaten[e.what] || 0) * e.compound);
  return isFinite(n) ? n : 0;         // past a float, the machine simply stops
}

/** { mass, energy } per second, as D. Eaters count at their average rate. */
export function rates(sc) {
  let mass = new D(0, 0), energy = new D(0, 0);
  for (const m of MEGA) {
    if (!has(sc, m.id)) continue;
    if (m.makes) {
      if (m.makes.mass) mass = mass.add(m.makes.mass);
      if (m.makes.energy) energy = energy.add(m.makes.energy);
    }
    if (m.eats && !sc.stalled[m.id]) {
      const per = D.of(m.eats.mass).mul(eatCount(sc, m)).div(m.eats.every);
      mass = mass.add(per);
    }
  }
  return { mass, energy };
}

/**
 * What the ladder gives back to the walking game.
 *   growth  multiplier on how fast everything on the ground grows
 *   flow    resources per second delivered from orbit and above
 * This is rule 3 in the header and it is the half that makes the surface
 * matter at the top instead of being a memory.
 */
export function surfaceBoon(sc) {
  let growth = 1;
  const flow = { alloy: 0, shard: 0, biomass: 0 };
  for (const m of MEGA) {
    if (!has(sc, m.id) || !m.boon) continue;
    if (m.boon.growth) growth *= m.boon.growth;
    for (const k of Object.keys(flow)) if (m.boon[k]) flow[k] += m.boon[k];
  }
  return { growth, flow };
}

/**
 * An estimate of total ground income per second, for the HUD and for the
 * pacing test. Growth counts because a plant that matures twice as fast is
 * harvested twice as often -- that is the only route the growth boon has back
 * into the numbers, and it is why the Sun Mirror is worth building.
 */
export function modelGroundRate(sc, base = GROUND_BASE) {
  const b = surfaceBoon(sc);
  const out = {};
  for (const k of Object.keys(base)) out[k] = base[k] * b.growth + (b.flow[k] || 0);
  return out;
}

/* --------------------------------------------------------------- the cost */

export function costOf(id) {
  const s = megaSpec(id);
  return s ? (s.needs || {}) : {};
}

const GROUND_KEYS = ['alloy', 'shard', 'biomass'];

/** Why you cannot commission this, or null. */
export function blockedReason(sc, ground, id, seen = null) {
  const s = megaSpec(id);
  if (!s) return 'no such thing';
  if (has(sc, id)) return 'already standing';
  if (sc.building) return 'already building ' + megaSpec(sc.building.id).name;
  if (!reached(sc, s.tier)) return 'needs ' + tierSpec(s.tier).name.toLowerCase();
  if (s.unlock && seen && !seen[s.unlock]) return 'needs a ' + s.unlock;
  const n = s.needs || {};
  for (const k of GROUND_KEYS) if (n[k] && (ground[k] || 0) < n[k]) return 'not enough ' + k;
  if (n.mass && sc.res.mass.lt(n.mass)) return 'not enough mass';
  if (n.energy && sc.res.energy.lt(n.energy)) return 'not enough energy';
  return null;
}

/** Pay for it and start the clock. Returns the build record, or null. */
export function begin(sc, ground, id, seen = null) {
  if (blockedReason(sc, ground, id, seen)) return null;
  const s = megaSpec(id), n = s.needs || {};
  for (const k of GROUND_KEYS) if (n[k]) ground[k] -= n[k];
  if (n.mass) sc.res.mass = sc.res.mass.sub(n.mass);
  if (n.energy) sc.res.energy = sc.res.energy.sub(n.energy);
  sc.building = { id, endsAt: sc.t + buildTime(id) };
  sc.log.push({ t: sc.t, k: 'began', id });
  return sc.building;
}

/** Seconds until you could afford it at today's rates. Infinity if never. */
export function timeToAfford(sc, ground, id, groundRate = null) {
  const n = costOf(id);
  const gr = groundRate || modelGroundRate(sc);
  const r = rates(sc);
  let worst = 0;
  for (const k of GROUND_KEYS) {
    if (!n[k]) continue;
    const short = n[k] - (ground[k] || 0);
    if (short <= 0) continue;
    if (!gr[k]) return Infinity;
    worst = Math.max(worst, short / gr[k]);
  }
  for (const [k, cur] of [['mass', sc.res.mass], ['energy', sc.res.energy]]) {
    if (!n[k]) continue;
    const short = D.of(n[k]).sub(cur);
    if (short.lte(0)) continue;
    if (r[k].isZero()) return Infinity;
    worst = Math.max(worst, short.div(r[k]).toNumber());
  }
  return worst;
}

/**
 * What to do next, and why. This is both the game's hint and the policy the
 * pacing test plays with -- so the run the test measures is the run a player
 * following the game's own advice would have.
 *
 * Finish the tier, THEN open the next one: a gate taken early skips content
 * you have already paid the build time for, and a tier you left half-built is
 * a tier you never saw.
 */
export function nextGoal(sc, ground, seen = null) {
  const gr = modelGroundRate(sc);
  let best = null, bestGate = null;
  for (const m of MEGA) {
    if (has(sc, m.id)) continue;
    if (!reached(sc, m.tier)) continue;
    if (m.unlock && seen && !seen[m.unlock]) continue;
    // The hint NEVER recommends a wasteful structure. The World Cracker works;
    // it burns fourteen worlds to do what the Maw does with one, and a larder
    // does not grow back. It stays on the menu because "I need mass now and I
    // will pay for it" is a decision the player is allowed to make -- but the
    // game must not be the one that makes it for them.
    if (m.wasteful) continue;
    const eta = timeToAfford(sc, ground, m.id, gr) + buildTime(m.id);
    if (!isFinite(eta)) continue;
    const slot = m.opens ? 'gate' : 'body';
    const cur = slot === 'gate' ? bestGate : best;
    if (!cur || eta < cur.eta) {
      const rec = { id: m.id, name: m.name, eta, wait: eta - buildTime(m.id) };
      if (slot === 'gate') bestGate = rec; else best = rec;
    }
  }
  return best || bestGate;
}

/* ---------------------------------------------------------------- the clock */

function finish(sc, id) {
  sc.built[id] = 1;
  sc.building = null;
  const s = megaSpec(id);
  sc.log.push({ t: sc.t, k: 'stands', id });
  if (s.opens) {
    sc.tier = s.opens;
    const t = tierSpec(s.opens);
    for (const k of Object.keys(t.stock || {})) sc.stock[k] = (sc.stock[k] || 0) + t.stock[k];
    sc.log.push({ t: sc.t, k: 'tier', id: s.opens });
  }
}

/**
 * Advance the ladder. `ground` is the walking game's resource purse -- this
 * function only ever ADDS the boon flows to it, because world.js is what
 * produces the rest and double-counting it would make the ground free.
 */
export function advanceScale(sc, ground, dt) {
  sc.t += dt;

  for (const m of MEGA) {
    if (!has(sc, m.id)) continue;

    if (m.makes) {
      if (m.makes.mass) sc.res.mass = sc.res.mass.add(D.of(m.makes.mass).mul(dt));
      if (m.makes.energy) sc.res.energy = sc.res.energy.add(D.of(m.makes.energy).mul(dt));
    }

    if (m.eats) {
      const e = m.eats;
      let want = eatCount(sc, m);
      let timer = (sc.timer[m.id] || 0) + dt;
      let fed = 0;
      // a coarse step must eat as many times as a fine one, or the ladder
      // changes shape with the frame rate
      while (timer >= e.every && want > 0) {
        if ((sc.stock[e.what] || 0) < want) break;
        sc.stock[e.what] -= want;
        sc.eaten[e.what] = (sc.eaten[e.what] || 0) + want;
        fed += want;
        timer -= e.every;
        want = eatCount(sc, m);            // a compounding eater grows as it goes
      }
      if (fed > 0) sc.res.mass = sc.res.mass.add(D.of(e.mass).mul(fed));
      const empty = want <= 0 || (sc.stock[e.what] || 0) < want;
      if (empty && !sc.stalled[m.id]) {
        sc.stalled[m.id] = true;
        sc.log.push({ t: sc.t, k: 'stalled', id: m.id, what: e.what });
      } else if (!empty && sc.stalled[m.id]) {
        sc.stalled[m.id] = false;
      }
      // an empty larder must not bank time it will spend the instant one arrives
      sc.timer[m.id] = empty ? Math.min(timer, e.every) : timer;
    }
  }

  const b = surfaceBoon(sc);
  for (const k of GROUND_KEYS) if (b.flow[k]) ground[k] = (ground[k] || 0) + b.flow[k] * dt;

  if (sc.building && sc.t >= sc.building.endsAt) finish(sc, sc.building.id);

  if (sc.log.length > 300) sc.log.splice(0, sc.log.length - 300);
  return sc;
}

/* ------------------------------------------------------------- persistence */

export function saveScale(sc) {
  return {
    v: 1, t: sc.t, tier: sc.tier,
    mass: sc.res.mass.toJSON(), energy: sc.res.energy.toJSON(),
    built: sc.built, building: sc.building,
    stock: sc.stock, eaten: sc.eaten, timer: sc.timer, stalled: sc.stalled,
  };
}

export function loadScale(data) {
  const d = typeof data === 'string' ? JSON.parse(data) : data;
  if (!d || d.v !== 1) return null;
  const sc = createScale();
  sc.t = d.t || 0;
  sc.tier = TIER_IDS.includes(d.tier) ? d.tier : 'surface';
  sc.res.mass = D.of(d.mass || 0);
  sc.res.energy = D.of(d.energy || 0);
  for (const k of Object.keys(d.built || {})) if (megaSpec(k)) sc.built[k] = 1;
  sc.building = d.building && megaSpec(d.building.id) ? d.building : null;
  Object.assign(sc.stock, d.stock || {});
  Object.assign(sc.eaten, d.eaten || {});
  Object.assign(sc.timer, d.timer || {});
  Object.assign(sc.stalled, d.stalled || {});
  return sc;
}

/* ------------------------------------------------------------------ readout */

/** One object with everything a HUD wants. Allocates; call it on change only. */
export function report(sc, ground) {
  const t = tierSpec(sc.tier);
  const r = rates(sc);
  const b = surfaceBoon(sc);
  const goal = nextGoal(sc, ground);
  return {
    tier: t.id, tierName: t.name, reach: t.reach,
    mass: sc.res.mass, energy: sc.res.energy,
    massRate: r.mass, energyRate: r.energy,
    growth: b.growth, flow: b.flow,
    building: sc.building ? { id: sc.building.id, name: megaSpec(sc.building.id).name, left: Math.max(0, sc.building.endsAt - sc.t) } : null,
    stock: sc.stock, eaten: sc.eaten,
    next: goal,
  };
}
