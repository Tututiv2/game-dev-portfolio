// HARVESTER — every tunable number in the game lives here.
// If a magic number appears anywhere else, it is a bug.

export const CFG = {
  // ---------- world ----------
  cell: 12,             // wheat cell edge, world px
  chunkCells: 32,       // cells per chunk side  -> 384px chunks
  chunkKeepRadius: 3.2, // chunk canvases kept within this many chunks of camera
  chunkBudget: 4,       // max new chunk canvases generated per frame
  pxPerMetre: 10,

  // ---------- harvester ----------
  hv: {
    // hull boxes in local space (forward = +X, right = +Y)
    body:   { x0: -84, y0: -48, x1: 72,  y1: 48 },
    header: { x0: 72,  y0: -96, x1: 110, y1: 96 },

    maxSpeed: 215,      // px/s at full throttle
    accel: 130,         // px/s^2
    brake: 190,
    throttleRate: 0.62, // throttle units per second while held
    turnRate: 1.45,     // rad/s at a standstill
    turnAtSpeed: 0.42,  // turn-rate multiplier at max speed (heavy machine)

    hullMax: 1200,
    headerDPS: 240,     // damage the cutter bar deals to anything inside it, at full throttle
    // Ramming is a real weapon, so it needs a real price. The fraction of an
    // enemy's dps that lands while it is being milled rises with the throttle
    // SQUARED — impact energy goes as v², and a linear price was not enough:
    // survival fell 1.5x while speed rose 2.2x, so flat-out still won on
    // distance. Squared makes the top of the lever cost more than it pays.
    headerBite: 0.3,
    headerBiteAtSpeed: 2.9,

    grainPerCell: 0.05, // grain per fresh wheat cell cut
    tankVisual: 900,    // grain at which the tank reads full
  },

  // ---------- the dial ----------
  noise: {
    idle: 205,          // rouse radius at throttle 0
    full: 1180,         // rouse radius at throttle 1
    curve: 0.85,        // <1 = the first bit of throttle is loud
    ringPulse: 1.9,     // pulses per second
  },

  // ---------- swarm ----------
  swarm: {
    max: 2200,
    separation: 1.35,   // how hard neighbours shove each other apart
    burnDecay: 0.55,    // burn stacks bleed off per second
    spawnPad: 90,       // spawn this far outside the visible radius
    clusterMin: 6,
    clusterMax: 26,
    clusterSpread: 130,
    basePressure: 1.0,  // clusters per second at reference noise
    refNoise: 620,
    pressureCurve: 3.0, // noise -> pressure exponent. Steep on purpose: the gap
                        // between half and full throttle has to be felt.
    // Escalation is per METRE, not per second. This is load-bearing — it means
    // opening the throttle burns through the difficulty curve faster as well as
    // spawning more, so speed is punished twice and slow play is a real option.
    rampPerMetre: 1 / 800,
    forwardBias: 0.62,  // 0 = spawn evenly around, 1 = all ahead. High, because
                        // driving fast should mean driving INTO what you woke.
    swellEvery: 52,     // seconds
    swellWarn: 4.5,
    swellClusters: 7,
  },

  // ---------- unlocks (metres travelled) ----------
  unlockAt: [0, 240, 620, 1150],

  // ---------- economy ----------
  costGrowth: 1.34,     // each copy of a tower costs this much more than the last
  repairGrainPerSec: 26,
  repairHullPerSec: 34,
  sellRefund: 0.55,

  // ---------- feel ----------
  camLead: 130,         // camera pushes ahead of the machine
  camLerp: 3.4,
  shakeDecay: 5.5,
  maxDpr: 1.5,
};

// Hardpoints: two rails down the deck. Local space, forward = +X.
export const HARDPOINTS = [
  { x: -64, y: -34 }, { x: -34, y: -34 }, { x: -4, y: -34 }, { x: 26, y: -34 }, { x: 54, y: -34 },
  { x: -64, y:  34 }, { x: -34, y:  34 }, { x: -4, y:  34 }, { x: 26, y:  34 }, { x: 54, y:  34 },
];

// The Goldsea holds light; cutting it takes the light out. That is the whole
// palette in one sentence — a glowing amber field, and a dark spent scar
// trailing behind you. Everything that comes for you glows COLD, so it reads
// against the warm field at 12px.
export const COL = {
  field:    '#c8952c',   // goldsea, standing
  fieldHi:  '#f2d178',   // a facet catching light
  fieldLo:  '#94691c',
  spent:    '#3b3142',   // reaped ground — the light is gone out of it
  spentLo:  '#2a2230',
  ember:    '#ff9a3c',   // residual heat at a fresh cut

  hull:     '#4e7a6c',   // verdigris plate
  hullDark: '#2f4d45',
  bronze:   '#a07c3c',
  stone:    '#cfc6ae',
  iron:     '#3a343f',
  ironDark: '#211d26',
  glass:    '#ffd98a',   // lantern-glass, warm

  glowWarm: '#ffd98a',
  glowCold: '#7fe8dc',
  chitin:   '#12101a',
  ui:       '#f3e3c0',
  uiDim:    '#9a8a6a',
  danger:   '#e0533a',
};
