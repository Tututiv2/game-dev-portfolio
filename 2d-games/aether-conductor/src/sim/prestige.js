// Collapse prestige (Stage 3): reset the run, bank Echoes.
//   Echoes gained = floor(log10(runEssence + 1)) + echoBonus (Echo Cascade) [+7 if Lucky Echo].
// Persists across Collapse: echoes, echoShop, collapses, codex/secrets, settings.
// Resets: grid (nodes/edges), aether, essence, runEssence, owned, research, milestones, surge.

import { emptyOwned } from "./state.js";
import { recomputeMods } from "./mods.js";
import { ECHO_BY_ID, echoCost } from "../content/echoes.js";
import { rollChoices } from "../content/dimensions.js";

export function echoesFromRun(state) {
  const base = Math.floor(Math.log10((state.runEssence || 0) + 1));
  const bonus = state.mods ? state.mods.echoBonus : 0;
  const mult = state.mods ? (state.mods.echoMult || 1) : 1;
  return Math.max(0, Math.floor((base + bonus) * mult));
}

// Preview value shown on the Collapse button.
export function collapsePreview(state) {
  return echoesFromRun(state);
}

export function canCollapse(state) {
  return echoesFromRun(state) >= 1;
}

// Perform a Collapse. Banks Echoes + Keys, then offers 3 Dimension choices (the
// run is NOT reset until the player enters a dimension via enterDimension()).
// `luckyEcho` adds +7 (secret). Returns { gained, keys }.
export function doCollapse(state, luckyEcho = false) {
  let gained = echoesFromRun(state);
  if (luckyEcho) gained += 7;
  state.echoes += gained;
  state.collapses += 1;

  // Keys: +1 base, +1 per 3 milestone tiers reached, + dimension keyBonus
  const keyBonus = state.mods ? state.mods.keyBonus : 0;
  const keysGained = 1 + Math.floor(state.milestoneTier / 3) + keyBonus;
  state.keys += keysGained;

  // roll the choices for the next dimension
  const baseSeed = (state.seed + state.collapses * 0x9e3779b1 + state.rerollCount) >>> 0;
  state.dimChoices = rollChoices(baseSeed, 3);

  return { gained, keys: keysGained };
}

// Reroll the pending dimension choices for 1 Key.
export function rerollChoices(state) {
  if (state.keys < 1 || !state.dimChoices) return false;
  state.keys -= 1;
  state.rerollCount += 1;
  const baseSeed = (state.seed + state.collapses * 0x9e3779b1 + state.rerollCount * 7919) >>> 0;
  state.dimChoices = rollChoices(baseSeed, 3);
  return true;
}

// Enter a chosen dimension: reset the run and activate the dimension's modifiers.
export function enterDimension(state, dim) {
  state.dimension = dim;
  state.dimChoices = null;
  state.highestDimTier = Math.max(state.highestDimTier, dim ? dim.tier : 0);

  recomputeMods(state);
  const startAether = state.mods ? state.mods.startAether : 0;

  state.nodes = [];
  state.edges = [];
  state.owned = emptyOwned();
  state.research = [];
  state.essence = 0;
  state.runEssence = 0;
  state.aether = startAether;
  state.milestoneTier = 0;
  state.surge = { active: false, timeLeft: 0, cooldown: 0, pulses: 0 };
  state.nextNodeId = 1;
  state.epsDirty = true;
  state.flowDirty = true;
  recomputeMods(state);
}

// Buy an echo upgrade level. Returns {ok, reason}.
export function buyEcho(state, id) {
  const up = ECHO_BY_ID[id];
  if (!up) return { ok: false, reason: "unknown" };
  const lvl = state.echoShop[id] || 0;
  if (lvl >= up.max) return { ok: false, reason: "maxed" };
  const cost = echoCost(up, lvl);
  if (state.echoes < cost) return { ok: false, reason: "not enough Echoes" };
  state.echoes -= cost;
  state.echoShop[id] = lvl + 1;
  recomputeMods(state);
  state.epsDirty = true;
  state.flowDirty = true;
  return { ok: true };
}
