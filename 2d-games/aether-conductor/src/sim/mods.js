// Combines every progression source (research, echo shop, milestones, secrets) into
// one modifier bundle on state.mods. Multipliers stack multiplicatively across
// categories; same-category bonuses stack as defined by each source.

import { RESEARCH_BY_ID } from "../content/research.js";
import { ECHO_BY_ID } from "../content/echoes.js";
import { applySecretBonuses } from "../content/secrets.js";
import { applyDimensionMods } from "./dimension.js";

export function baseMods() {
  return {
    global: 1,        // multiplies final EPS and Essence
    siphon: 1,        // Siphon output
    channel: 1,       // scales (mult-1) of modifier nodes
    essence: 1,       // Essence rate
    edgeCap: 1,       // edge capacity
    drip: 1,          // Rift drip
    click: 1,         // manual siphon power
    costMult: 1,      // node purchase cost multiplier
    connBonus: 0,     // +max connections per node
    surgeDur: 0,      // +seconds surge duration
    offlineCap: 0,    // +seconds offline cap
    startAether: 0,   // Aether granted at start of a run
    echoBonus: 0,     // +Echoes per Collapse
    milestoneScale: 1,// scales milestone bonus
    // heat (Stage 4)
    heatGen: 1,       // node heat generation multiplier
    heatCool: 1,      // node heat cooling multiplier
    // dimension extras (Stage 4)
    echoMult: 1,      // multiply Echoes gained
    keyBonus: 0,      // +Keys on leaving a dimension
  };
}

export function recomputeMods(state) {
  const m = baseMods();

  // research
  for (const id of state.research) {
    const r = RESEARCH_BY_ID[id];
    if (r) r.mod(m);
  }
  // echo shop (permanent)
  for (const id in state.echoShop) {
    const up = ECHO_BY_ID[id];
    const lvl = state.echoShop[id] || 0;
    if (up && lvl > 0) up.mod(m, lvl);
  }
  // secrets
  applySecretBonuses(m, state);

  // active dimension (Stage 4)
  applyDimensionMods(m, state);

  // milestones: +2% global per tier, scaled by Insight/etc.
  const milestoneBonus = 1 + state.milestoneTier * 0.02 * m.milestoneScale;
  m.global *= milestoneBonus;

  state.mods = m;
  return m;
}
