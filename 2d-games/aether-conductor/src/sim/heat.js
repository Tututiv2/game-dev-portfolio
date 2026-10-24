// Heat system (Stage 4). Nodes that carry flow build heat; conduits/heatsinks shed
// it. If heat exceeds a node's cap it MELTS (stops functioning) until repaired with
// Essence. Most basic nodes generate little/no heat — heat is a strategic layer
// driven by aggressive nodes (Amplifier/Overclocker) and Dimension modifiers.

import { NODE_DEFS } from "./nodes.js";
import { neighbors, key } from "./grid.js";

export function repairCost(node) {
  const def = NODE_DEFS[node.def];
  return Math.ceil((def.cost || 100) * 0.5);
}

// Advance heat for every node. Returns true if any node melted this tick.
export function tickHeat(state, dt) {
  const mods = state.mods || { heatGen: 1, heatCool: 1 };
  let meltedSomething = false;

  // index for neighbor cooling (Heatsinks cool adjacent nodes)
  const idx = new Map();
  for (const n of state.nodes) idx.set(key(n.q, n.r), n);

  for (const n of state.nodes) {
    const def = NODE_DEFS[n.def];
    const cap = def.heatCap || 100;
    if (n.heat === undefined) n.heat = 0;

    // generation scales with how hard the node is working (throughput)
    const tp = n._throughput || 0;
    const work = Math.min(3, tp / 12 + (tp > 0 ? 0.15 : 0));
    let gen = (def.heatGen || 0) * work * mods.heatGen;

    // cooling: own + adjacent heatsinks
    let cool = (def.heatCool || 1) * mods.heatCool;
    for (const nb of neighbors(n.q, n.r)) {
      const other = idx.get(key(nb.q, nb.r));
      if (other && NODE_DEFS[other.def].heatSink) cool += NODE_DEFS[other.def].heatSink;
    }

    n.heat += (gen - cool) * dt;
    if (n.heat < 0) n.heat = 0;

    if (!n.melted && n.heat >= cap) {
      n.melted = true;
      n.heat = cap;
      meltedSomething = true;
      state._meltedNode = { q: n.q, r: n.r };
      state.epsDirty = true;
      state.flowDirty = true;
    }
    // melted nodes slowly cool but stay offline until repaired
    if (n.melted) n.heat = Math.max(cap * 0.6, n.heat);
  }
  return meltedSomething;
}

// Repair a melted node at (q,r) by spending Essence. Returns {ok, reason}.
export function repairNode(state, q, r) {
  const n = state.nodes.find((nn) => nn.q === q && nn.r === r);
  if (!n) return { ok: false, reason: "empty" };
  if (!n.melted) return { ok: false, reason: "not melted" };
  const cost = repairCost(n);
  if (state.essence < cost) return { ok: false, reason: "need " + cost + " Essence" };
  state.essence -= cost;
  n.melted = false;
  n.heat = 0;
  state.epsDirty = true;
  state.flowDirty = true;
  return { ok: true, cost };
}

// Normalized heat 0..1 for rendering.
export function heatLevel(node) {
  const def = NODE_DEFS[node.def];
  const cap = def.heatCap || 100;
  return Math.min(1, (node.heat || 0) / cap);
}
