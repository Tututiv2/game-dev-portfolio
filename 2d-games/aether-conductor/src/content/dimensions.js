// Dimensions (Stage 4): each Collapse opens into a procedurally rolled Dimension
// whose modifiers bend the rules of the run. Modifiers are data-driven with three
// optional hooks:
//   apply(mods, state)        -> adjust the modifier bundle (steady multipliers)
//   onTick(state, dt)         -> per-tick effects (time-based, random events)
//   onPlace(state, node)      -> reaction when a node is placed
// Each also has describe() text shown in the UI.

import { mulberry32, hashSeed } from "../core/rng.js";

// rarity drives weight + tier contribution + color. 1 common .. 4 mythic.
export const MODIFIERS = {
  // ---- Production multipliers (boons) ----
  rich_void:     { name: "Rich Void", rarity: 1, good: true, desc: "Aether x2", apply: (m) => { m.siphon *= 2; } },
  essence_storm: { name: "Essence Storm", rarity: 2, good: true, desc: "Essence x2", apply: (m) => { m.essence *= 2; } },
  bloom:         { name: "Bloom", rarity: 2, good: true, desc: "All production x1.3", apply: (m) => { m.global *= 1.3; } },
  twin_cores:    { name: "Twin Cores", rarity: 1, good: true, desc: "Rift drip x5", apply: (m) => { m.drip *= 5; } },
  dense_aether:  { name: "Dense Aether", rarity: 1, good: true, desc: "Edge capacity x2", apply: (m) => { m.edgeCap *= 2; } },
  frugal:        { name: "Frugal Lattice", rarity: 1, good: true, desc: "Node costs -30%", apply: (m) => { m.costMult *= 0.7; } },
  swift:         { name: "Swift Current", rarity: 1, good: true, desc: "Manual siphon x5", apply: (m) => { m.click *= 5; } },
  harmonic_field:{ name: "Harmonic Field", rarity: 2, good: true, desc: "Surge duration +20s", apply: (m) => { m.surgeDur += 20; } },
  echoic:        { name: "Echoic", rarity: 3, good: true, desc: "Echoes from Collapse +50%", apply: (m) => { m.echoMult = (m.echoMult || 1) * 1.5; } },
  keymaster:     { name: "Keymaster", rarity: 3, good: true, desc: "+2 Keys when you leave", apply: (m) => { m.keyBonus = (m.keyBonus || 0) + 2; } },
  superconductor:{ name: "Superconductor", rarity: 2, good: true, desc: "No heat is generated", apply: (m) => { m.heatGen *= 0; } },
  cryo:          { name: "Cryogenic", rarity: 2, good: true, desc: "Heat cooling x4", apply: (m) => { m.heatCool *= 4; } },
  overgrown:     { name: "Overgrown", rarity: 3, good: true, desc: "+2 connections per node", apply: (m) => { m.connBonus += 2; } },
  prismatic:     { name: "Prismatic", rarity: 4, good: true, desc: "All production x2.5", apply: (m) => { m.global *= 2.5; } },
  golden_age:    { name: "Golden Age", rarity: 4, good: true, desc: "Aether & Essence x3", apply: (m) => { m.siphon *= 3; m.essence *= 3; } },
  channeled:     { name: "Channeled", rarity: 2, good: true, desc: "Channel/Filter effect x2", apply: (m) => { m.channel *= 2; } },

  // ---- Trade-offs (mixed) ----
  volatile:      { name: "Volatile", rarity: 2, good: false, desc: "Production x1.6 but heat x2.5", apply: (m) => { m.global *= 1.6; m.heatGen *= 2.5; } },
  overcharged:   { name: "Overcharged", rarity: 2, good: false, desc: "Siphon x4 but no drip", apply: (m) => { m.siphon *= 4; m.drip = 0; } },
  miser:         { name: "Miser", rarity: 1, good: false, desc: "Aether x2, Essence x0.5", apply: (m) => { m.siphon *= 2; m.essence *= 0.5; } },
  spendthrift:   { name: "Spendthrift", rarity: 1, good: false, desc: "Essence x2, Aether x0.6", apply: (m) => { m.essence *= 2; m.siphon *= 0.6; } },
  decay:         { name: "Decay", rarity: 1, good: false, desc: "Production x0.85, nodes -40%", apply: (m) => { m.global *= 0.85; m.costMult *= 0.6; } },
  brittle:       { name: "Brittle", rarity: 2, good: false, desc: "Edge capacity x0.5, costs -50%", apply: (m) => { m.edgeCap *= 0.5; m.costMult *= 0.5; } },
  feverish:      { name: "Feverish", rarity: 2, good: false, desc: "Production x2 but heat x4", apply: (m) => { m.global *= 2; m.heatGen *= 4; } },

  // ---- Curses ----
  thin_aether:   { name: "Thin Aether", rarity: 1, good: false, desc: "Edge capacity x0.5", apply: (m) => { m.edgeCap *= 0.5; } },
  expensive:     { name: "Inflation", rarity: 1, good: false, desc: "Node costs x1.8", apply: (m) => { m.costMult *= 1.8; } },
  sluggish:      { name: "Sluggish", rarity: 1, good: false, desc: "Manual siphon x0.2", apply: (m) => { m.click *= 0.2; } },
  furnace:       { name: "Furnace", rarity: 3, good: false, desc: "Heat generation x3", apply: (m) => { m.heatGen *= 3; } },
  dim:           { name: "Dim", rarity: 2, good: false, desc: "All production x0.7", apply: (m) => { m.global *= 0.7; } },

  // ---- Dynamic / event modifiers (onTick) ----
  odd_hours:     { name: "Odd Hours", rarity: 2, good: true, desc: "Production x2 on odd real-time minutes",
                   apply: (m, s) => { if (s && s._oddHour) m.global *= 2; },
                   onTick: (s) => { s._oddHour = (new Date().getMinutes() % 2) === 1; } },
  pulsar:        { name: "Pulsar", rarity: 3, good: true, desc: "Rhythmic x3 production bursts",
                   apply: (m, s) => { if (s && s._pulseOn) m.global *= 3; },
                   onTick: (s, dt) => { s._pulseT = (s._pulseT || 0) + dt; s._pulseOn = (s._pulseT % 10) < 3; } },
  quantum:       { name: "Quantum Doubles", rarity: 3, good: true, desc: "Flow randomly doubles in bursts",
                   apply: (m, s) => { if (s && s._qDouble) m.global *= 2; },
                   onTick: (s, dt) => { s._qT = (s._qT || 0) + dt; if (s._qT > 1) { s._qT = 0; s._qDouble = Math.random() < 0.18; } } },
  gravity_wells: { name: "Gravity Wells", rarity: 2, good: true, desc: "Flow pulled to Core: capacity x1.5",
                   apply: (m) => { m.edgeCap *= 1.5; } },
  windfall:      { name: "Windfall", rarity: 3, good: true, desc: "Occasional Aether windfalls",
                   onTick: (s, dt) => { s._wT = (s._wT || 0) + dt; if (s._wT > 8) { s._wT = 0; const g = s.eps * 12; s.aether += g; s.totalAether += g; s._windfall = g; } } },

  // ---- onPlace reaction ----
  fertile:       { name: "Fertile", rarity: 3, good: true, desc: "Placing a node grants instant Aether",
                   onPlace: (s) => { const g = Math.max(50, s.eps * 5); s.aether += g; s.totalAether += g; } },
};

export const MOD_IDS = Object.keys(MODIFIERS);

// Rarity meta for color + naming.
export const RARITY = {
  1: { name: "Common", color: "#9fb4d8", weight: 50 },
  2: { name: "Charged", color: "#36e6ff", weight: 26 },
  3: { name: "Resonant", color: "#ff4fd8", weight: 14 },
  4: { name: "Mythic", color: "#ffd166", weight: 5 },
};

const ADJ = ["Shimmering", "Hollow", "Fractal", "Veiled", "Radiant", "Sundered", "Whispering",
  "Frozen", "Molten", "Gilded", "Spectral", "Abyssal", "Verdant", "Crystalline", "Drifting", "Eternal"];
const NOUN = ["Fork", "Expanse", "Mirror", "Chord", "Lattice", "Rift", "Spiral", "Bloom",
  "Cradle", "Threshold", "Cascade", "Halo", "Vault", "Maw", "Veil", "Engine"];
const HZ = [128, 174, 256, 285, 396, 417, 432, 440, 528, 639, 741, 852, 963];

// Weighted pick of a modifier id using a seeded RNG, avoiding duplicates in `taken`.
function pickModifier(rng, taken) {
  // build weighted list once per call (small pool)
  const pool = [];
  for (const id of MOD_IDS) {
    if (taken.has(id)) continue;
    const w = RARITY[MODIFIERS[id].rarity].weight;
    for (let i = 0; i < w; i++) pool.push(id);
  }
  if (pool.length === 0) return null;
  return pool[Math.floor(rng() * pool.length)];
}

// Roll a full Dimension from a numeric seed. Deterministic for a given seed.
export function rollDimension(seed) {
  const rng = mulberry32(seed >>> 0);
  const taken = new Set();
  const mods = [];
  const count = 3;
  for (let i = 0; i < count; i++) {
    const id = pickModifier(rng, taken);
    if (id) { mods.push(id); taken.add(id); }
  }
  const adj = ADJ[Math.floor(rng() * ADJ.length)];
  const noun = NOUN[Math.floor(rng() * NOUN.length)];
  const hz = HZ[Math.floor(rng() * HZ.length)];
  const tier = mods.reduce((t, id) => t + MODIFIERS[id].rarity, 0); // 3..12
  return { seed: seed >>> 0, name: `${adj} ${noun} ${hz}Hz`, hz, mods, tier };
}

// Produce N distinct dimension choices for a given base seed (collapse + reroll).
export function rollChoices(baseSeed, n = 3) {
  const out = [];
  for (let i = 0; i < n; i++) out.push(rollDimension((baseSeed + i * 2654435761) >>> 0));
  return out;
}

export function dimensionColor(dim) {
  if (!dim || !Array.isArray(dim.mods)) return "#9fb4d8";
  const maxR = Math.max(1, ...dim.mods.map((id) => (MODIFIERS[id] ? MODIFIERS[id].rarity : 1)));
  return RARITY[Math.min(4, maxR)].color;
}

export function seedFromString(str) {
  return hashSeed(String(str));
}
