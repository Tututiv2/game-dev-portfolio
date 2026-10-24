// Resonance: detects a harmonic chain in the flow graph and drives Harmonic Surge.
// A chain qualifies when a directed path Siphon -> ... -> Core passes through nodes
// whose tiers include {2, 3, 5} (Channel, Filter, Condenser) — the 2:3:5 chord.

import { NODE_DEFS } from "./nodes.js";
import { CORE_ID } from "./graph.js";
import { SURGE_DURATION, SURGE_COOLDOWN } from "./state.js";

// DFS from each producer; track tiers seen; succeed if {2,3,5} all collected on a
// path that terminates at the Core.
export function hasResonance(state) {
  const out = new Map(); // id -> [toId]
  for (const e of state.edges) {
    if (!out.has(e.from)) out.set(e.from, []);
    out.get(e.from).push(e.to);
  }
  const tiers = new Map(); // id -> tier (O(1) lookups instead of a find per step)
  for (const n of state.nodes) tiers.set(n.id, NODE_DEFS[n.def].tier || 0);
  const tierOf = (id) => tiers.get(id) || 0;

  const NEED = 0b111; // bits for tiers 2,3,5
  const bitFor = (t) => (t === 2 ? 1 : t === 3 ? 2 : t === 5 ? 4 : 0);

  let found = false;
  const seen = new Set(); // memoize (node, mask) states — the graph is a DAG,
                          // so a failed visit never needs re-exploring
  const visit = (id, mask, depth) => {
    if (found || depth > 64) return;
    const memo = id + ":" + mask;
    if (seen.has(memo)) return;
    seen.add(memo);
    const nexts = out.get(id) || [];
    for (const to of nexts) {
      if (to === CORE_ID) {
        if (mask === NEED) { found = true; return; }
        continue;
      }
      const nm = mask | bitFor(tierOf(to));
      visit(to, nm, depth + 1);
      if (found) return;
    }
  };

  for (const n of state.nodes) {
    const def = NODE_DEFS[n.def];
    if (def.role !== "producer") continue;
    visit(n.id, bitFor(def.tier), 0);
    if (found) return true;
  }
  return false;
}

// Advance surge timers; start a surge when resonance is present and ready.
// Returns true on the frame a surge begins (for FX).
export function tickResonance(state, dt) {
  const s = state.surge;
  let started = false;

  if (s.active) {
    s.timeLeft -= dt;
    if (s.timeLeft <= 0) {
      s.active = false;
      s.timeLeft = 0;
      s.cooldown = SURGE_COOLDOWN;
    }
  } else {
    if (s.cooldown > 0) s.cooldown -= dt;
    if (s.cooldown <= 0 && hasResonance(state)) {
      s.active = true;
      s.timeLeft = SURGE_DURATION + (state.mods ? state.mods.surgeDur : 0);
      s.cooldown = 0;
      s.pulses++;
      started = true;
    }
  }
  return started;
}
