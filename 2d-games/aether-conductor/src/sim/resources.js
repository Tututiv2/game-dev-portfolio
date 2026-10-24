// Resource simulation: placement, removal, EPS recompute, and per-tick production.
// Stage 2 production model: a directed flow graph (see graph.js). Siphons inject
// Aether; flow travels through nodes/edges to the Core. EPS recomputed on graph
// change (dirty flag), not per tick.

import { NODE_DEFS, NODE_ORDER, nodeCost, nodeRefund, nodeUnlocked } from "./nodes.js";
import { inBounds } from "./grid.js";
import { computeFlow, removeEdgesTouching } from "./graph.js";
import { STARTER_DRIP, SURGE_MULT } from "./state.js";
import { recomputeMods } from "./mods.js";
import { tickResonance } from "./resonance.js";
import { detectPassive } from "../content/secrets.js";
import { canResearch, RESEARCH_BY_ID } from "../content/research.js";
import { tickHeat } from "./heat.js";
import { dimensionTick, dimensionOnPlace } from "./dimension.js";

export function nodeAt(state, q, r) {
  return state.nodes.find((n) => n.q === q && n.r === r) || null;
}

export function nodeById(state, id) {
  return state.nodes.find((n) => n.id === id) || null;
}

// Recompute modifiers + total EPS via the flow graph.
// EPS = (drip + flow reaching the Core) * global. Essence base = condenser output.
export function recomputeEps(state) {
  recomputeMods(state);
  const m = state.mods;
  const report = computeFlow(state);
  const drip = STARTER_DRIP * m.drip;
  state.dripRate = drip;
  state.eps = (drip + report.coreFlow) * m.global;
  state.essenceBaseRate = report.essenceRate * m.global;
  state.epsDirty = false;
  return state.eps;
}

// Effective purchase cost after cost modifiers (Bargain echo, Mirror Shard secret).
export function effectiveCost(state, defId) {
  const base = nodeCost(defId, state.owned[defId] || 0);
  const mult = state.mods ? state.mods.costMult : 1;
  return Math.ceil(base * mult);
}

// Attempt to place the selected node at (q,r). Returns {ok, reason}.
export function placeNode(state, defId, q, r) {
  if (!inBounds(q, r)) return { ok: false, reason: "out of bounds" };
  if (q === 0 && r === 0) return { ok: false, reason: "core" }; // center reserved for the Core
  if (nodeAt(state, q, r)) return { ok: false, reason: "occupied" };
  if (!nodeUnlocked(state, defId)) return { ok: false, reason: "locked" };
  const owned = state.owned[defId] || 0;
  const cost = effectiveCost(state, defId);
  if (state.aether < cost) return { ok: false, reason: "too expensive" };

  state.aether -= cost;
  state.owned[defId] = owned + 1;
  const node = { id: state.nextNodeId++, def: defId, q, r, heat: 0, melted: false };
  state.nodes.push(node);
  discoverNode(state, defId);
  dimensionOnPlace(state, node);
  state.epsDirty = true;
  state.flowDirty = true;
  return { ok: true, cost };
}

// Remove node at (q,r), refund 50%, and clean up any edges touching it.
export function removeNode(state, q, r) {
  const i = state.nodes.findIndex((n) => n.q === q && n.r === r);
  if (i === -1) return { ok: false };
  const n = state.nodes[i];
  const ownedAfter = (state.owned[n.def] || 1) - 1;
  const refund = nodeRefund(n.def, ownedAfter);
  removeEdgesTouching(state, n.id);
  state.nodes.splice(i, 1);
  state.owned[n.def] = Math.max(0, ownedAfter);
  state.aether += refund;
  state.epsDirty = true;
  state.flowDirty = true;
  return { ok: true, refund };
}

// Current overflow capacity from Vaults (Stage 1: cosmetic cap, not enforced as a
// hard limit yet — kept available for Stage 2 burst mechanic).
export function totalCapacity(state) {
  let cap = Infinity; // Stage 1 has no hard cap
  return cap;
}

// One simulation step: production, Essence, surge, milestones, secrets.
export function tickProduction(state, dt) {
  if (state.epsDirty || state.flowDirty) recomputeEps(state);

  // Aether
  const gained = state.eps * dt;
  state.aether += gained;
  state.totalAether += gained;

  // Essence (Stage 3) with Harmonic Surge multiplier
  const surgeMult = state.surge.active ? SURGE_MULT : 1;
  const essGain = (state.essenceBaseRate || 0) * surgeMult * dt;
  if (essGain > 0) { state.essence += essGain; state.runEssence += essGain; }

  // time tracking (Void Click secret accumulates empty-grid time)
  state.playTime += dt;
  if (state.nodes.length === 0) state.noNodeTime += dt;

  // milestones: every 10x EPS grants a permanent +2% (recompute picks it up)
  if (state.eps >= 1) {
    const tier = Math.floor(Math.log10(state.eps));
    if (tier > state.milestoneTier) {
      state.milestoneTier = tier;
      state._newMilestone = tier;
      state.epsDirty = true;
    }
  }

  // dimension hooks (events) + heat
  dimensionTick(state, dt);
  if (tickHeat(state, dt)) state._meltAlert = true;

  // resonance / surge
  if (tickResonance(state, dt)) state._surgeStarted = true;

  // passive secrets
  const newly = detectPassive(state);
  for (const id of newly) {
    if (!state.codex.secrets.includes(id)) {
      state.codex.secrets.push(id);
      state._newSecret = id;
      state.epsDirty = true;
    }
  }
}

// Manual siphon click. Scales with owned siphons and the click modifier.
export function manualSiphon(state) {
  const clickMod = state.mods ? state.mods.click : 1;
  const power = (1 + (state.owned.siphon || 0) * 0.5) * clickMod;
  state.aether += power;
  state.totalAether += power;
  return power;
}

// Record a node type in the codex when first placed.
export function discoverNode(state, defId) {
  if (!state.codex.nodes.includes(defId)) state.codex.nodes.push(defId);
}

// Buy a research upgrade with Essence. Returns {ok, reason}.
export function buyResearch(state, id) {
  const r = RESEARCH_BY_ID[id];
  if (!r) return { ok: false, reason: "unknown" };
  if (!canResearch(state, id)) return { ok: false, reason: "locked" };
  if (state.essence < r.cost) return { ok: false, reason: "not enough Essence" };
  state.essence -= r.cost;
  state.research.push(id);
  recomputeMods(state);
  state.epsDirty = true;
  state.flowDirty = true;
  return { ok: true };
}

export { NODE_ORDER, NODE_DEFS, nodeCost };
