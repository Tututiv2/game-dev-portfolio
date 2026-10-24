// Save/load with versioned schema + migration path, offline progress calc.

import { createState, SAVE_VERSION, OFFLINE_CAP_SECONDS, emptyOwned } from "../sim/state.js";
import { recomputeEps } from "../sim/resources.js";
import { NODE_DEFS } from "../sim/nodes.js";
import { CORE_ID } from "../sim/graph.js";
import { RESEARCH_BY_ID } from "../content/research.js";
import { ECHO_BY_ID } from "../content/echoes.js";
import { MODIFIERS } from "../content/dimensions.js";

// Coerce a possibly-corrupt saved value to a finite number (guards NaN/Infinity
// from hand-edited or truncated saves and from imported strings).
function num(v, d = 0) {
  return typeof v === "number" && Number.isFinite(v) ? v : d;
}

// Validate a saved dimension object; returns a clean copy or null.
function sanitizeDim(dim) {
  if (!dim || typeof dim !== "object" || !Array.isArray(dim.mods)) return null;
  const mods = dim.mods.filter((id) => MODIFIERS[id]);
  return {
    seed: num(dim.seed, 0) >>> 0,
    name: typeof dim.name === "string" ? dim.name : "Unknown Rift",
    hz: num(dim.hz, 432),
    mods,
    tier: num(dim.tier, mods.reduce((t, id) => t + MODIFIERS[id].rarity, 0)),
  };
}

const KEY = "aether-conductor-save";

// Serialize only the persistent fields.
export function serialize(state) {
  return {
    version: SAVE_VERSION,
    seed: state.seed,
    aether: state.aether,
    totalAether: state.totalAether,
    essence: state.essence,
    runEssence: state.runEssence,
    echoes: state.echoes,
    echoShop: state.echoShop,
    collapses: state.collapses,
    keys: state.keys,
    dimension: state.dimension,
    dimChoices: state.dimChoices,
    rerollCount: state.rerollCount,
    highestDimTier: state.highestDimTier,
    nodes: state.nodes.map((n) => ({ id: n.id, def: n.def, q: n.q, r: n.r, heat: n.heat || 0, melted: !!n.melted })),
    edges: state.edges.map((e) => ({ from: e.from, to: e.to })),
    owned: state.owned,
    research: state.research,
    surge: state.surge,
    milestoneTier: state.milestoneTier,
    codex: state.codex,
    playTime: state.playTime,
    noNodeTime: state.noNodeTime,
    settings: state.settings,
    nextNodeId: state.nextNodeId,
    lastSeen: Date.now(),
  };
}

export function save(state) {
  try {
    localStorage.setItem(KEY, JSON.stringify(serialize(state)));
    return true;
  } catch (e) {
    console.warn("Save failed", e);
    return false;
  }
}

// Apply forward migrations to a raw save object. v0/missing -> current.
function migrate(raw) {
  let data = raw;
  const v = data.version || 0;
  if (v < 1) {
    data.version = 1;
  }
  if (v < 2) {
    // Stage 1 -> Stage 2: introduce edges + new node counts. Old Stage 1 layouts
    // had no graph; Siphons become auto-delivering "direct" producers, so saves
    // keep earning without any wiring.
    data.edges = data.edges || [];
    data.version = 2;
  }
  if (v < 3) {
    // Stage 2 -> Stage 3: prestige, research, resonance, codex.
    data.runEssence = data.runEssence || data.essence || 0;
    data.echoes = data.echoes || 0;
    data.echoShop = data.echoShop || {};
    data.collapses = data.collapses || 0;
    data.research = data.research || [];
    data.surge = data.surge || { active: false, timeLeft: 0, cooldown: 0, pulses: 0 };
    data.milestoneTier = data.milestoneTier || 0;
    data.codex = data.codex || { nodes: [], secrets: [] };
    data.playTime = data.playTime || 0;
    data.noNodeTime = data.noNodeTime || 0;
    data.version = 3;
  }
  if (v < 4) {
    // Stage 3 -> Stage 4: dimensions, keys, heat.
    data.keys = data.keys || 0;
    data.dimension = data.dimension || null;
    data.dimChoices = data.dimChoices || null;
    data.rerollCount = data.rerollCount || 0;
    data.highestDimTier = data.highestDimTier || 0;
    if (Array.isArray(data.nodes)) data.nodes = data.nodes.map((n) => ({ ...n, heat: n.heat || 0, melted: !!n.melted }));
    data.version = 4;
  }
  return data;
}

export function load() {
  let raw;
  try {
    const txt = localStorage.getItem(KEY);
    if (!txt) return null;
    raw = JSON.parse(txt);
  } catch (e) {
    console.warn("Load failed, starting fresh", e);
    return null;
  }

  const data = migrate(raw);
  const state = createState();

  state.version = SAVE_VERSION;
  state.seed = num(data.seed, state.seed) >>> 0;
  state.aether = Math.max(0, num(data.aether, 0));
  state.totalAether = Math.max(state.aether, num(data.totalAether, state.aether));
  state.essence = Math.max(0, num(data.essence, 0));
  state.runEssence = Math.max(0, num(data.runEssence, 0));
  state.echoes = Math.max(0, num(data.echoes, 0));
  state.collapses = Math.max(0, Math.floor(num(data.collapses, 0)));
  state.keys = Math.max(0, Math.floor(num(data.keys, 0)));
  state.dimension = sanitizeDim(data.dimension);
  if (Array.isArray(data.dimChoices)) {
    const choices = data.dimChoices.map(sanitizeDim).filter(Boolean);
    state.dimChoices = choices.length ? choices : null;
  } else {
    state.dimChoices = null;
  }
  state.rerollCount = Math.max(0, Math.floor(num(data.rerollCount, 0)));
  state.highestDimTier = Math.max(0, num(data.highestDimTier, 0));

  // nodes: drop entries with unknown defs or bad coordinates (crash-proof vs.
  // tampered imports and defs removed in newer versions)
  state.nodes = (Array.isArray(data.nodes) ? data.nodes : [])
    .filter((n) => n && NODE_DEFS[n.def] && Number.isFinite(n.q) && Number.isFinite(n.r))
    .map((n) => ({
      id: n.id, def: n.def, q: n.q, r: n.r,
      heat: Math.max(0, num(n.heat, 0)), melted: !!n.melted,
    }));
  // edges must reference surviving nodes (or the Core as target)
  const nodeIds = new Set(state.nodes.map((n) => n.id));
  state.edges = (Array.isArray(data.edges) ? data.edges : [])
    .filter((e) => e && nodeIds.has(e.from) && (e.to === CORE_ID || nodeIds.has(e.to)))
    .map((e) => ({ from: e.from, to: e.to }));

  // owned counts: known node types only, finite non-negative integers
  state.owned = emptyOwned();
  for (const k in data.owned || {}) {
    if (NODE_DEFS[k]) state.owned[k] = Math.max(0, Math.floor(num(data.owned[k], 0)));
  }
  // echo shop: known upgrades only, levels clamped to [0, max]
  state.echoShop = {};
  for (const k in data.echoShop || {}) {
    const up = ECHO_BY_ID[k];
    if (up) state.echoShop[k] = Math.min(up.max, Math.max(0, Math.floor(num(data.echoShop[k], 0))));
  }
  state.research = Array.isArray(data.research)
    ? data.research.filter((id) => RESEARCH_BY_ID[id])
    : [];
  const ds = data.surge || {};
  state.surge = {
    active: !!ds.active,
    timeLeft: Math.max(0, num(ds.timeLeft, 0)),
    cooldown: Math.max(0, num(ds.cooldown, 0)),
    pulses: Math.max(0, Math.floor(num(ds.pulses, 0))),
  };
  state.milestoneTier = Math.max(0, Math.floor(num(data.milestoneTier, 0)));
  state.codex = data.codex && Array.isArray(data.codex.nodes) && Array.isArray(data.codex.secrets)
    ? { nodes: data.codex.nodes.slice(), secrets: data.codex.secrets.slice() }
    : { nodes: [], secrets: [] };
  state.playTime = Math.max(0, num(data.playTime, 0));
  state.noNodeTime = Math.max(0, num(data.noNodeTime, 0));
  state.settings = Object.assign(state.settings, data.settings || {});
  state.settings.showFps = !!state.settings.showFps;
  // nextNodeId must exceed every existing node id, or new nodes collide
  let maxId = 0;
  for (const n of state.nodes) if (typeof n.id === "number" && n.id > maxId) maxId = n.id;
  state.nextNodeId = Math.max(Math.floor(num(data.nextNodeId, maxId + 1)), maxId + 1);
  state.lastSeen = num(data.lastSeen, Date.now());
  state.epsDirty = true;
  state.flowDirty = true;

  return state;
}

// Compute offline earnings since lastSeen, capped. Returns a breakdown object
// or null if negligible. Mutates state to award the Aether.
export function applyOffline(state) {
  const now = Date.now();
  const elapsedSec = Math.max(0, (now - state.lastSeen) / 1000);
  state.lastSeen = now;

  recomputeEps(state);
  const cap = OFFLINE_CAP_SECONDS + (state.mods ? state.mods.offlineCap : 0);
  const capped = Math.min(elapsedSec, cap);
  if (capped < 5 || (state.eps <= 0 && state.essenceBaseRate <= 0)) return null;

  const gained = state.eps * capped;
  state.aether += gained;
  state.totalAether += gained;

  // Essence accrues offline at base rate (no surge while away)
  const essGained = (state.essenceBaseRate || 0) * capped;
  if (essGained > 0) { state.essence += essGained; state.runEssence += essGained; }

  return {
    elapsedSec,
    cappedSec: capped,
    capped: elapsedSec > cap,
    eps: state.eps,
    essRate: state.essenceBaseRate || 0,
    essGained,
    gained,
  };
}

export function wipe() {
  try { localStorage.removeItem(KEY); } catch (e) { /* ignore */ }
}

// Overwrite the save slot with a raw save object (used by import).
export function writeRaw(raw) {
  localStorage.setItem(KEY, JSON.stringify(raw));
}

export function clearAll() { wipe(); }
