// Auto-layout assistant (Stage 4). Suggests 3 valid wirings for the CURRENTLY
// placed nodes and previews each one's EPS. The player picks one to apply — it
// never auto-plays without consent. Layouts always flow toward the Core along
// strictly-decreasing distance, so they are inherently acyclic.

import { NODE_DEFS } from "./nodes.js";
import { hexDistance } from "./grid.js";
import { addEdge, canConnect, CORE_ID } from "./graph.js";
import { recomputeEps } from "./resources.js";

function distToCore(n) { return hexDistance(n.q, n.r, 0, 0); }

// Build one layout into state.edges using a target-preference scorer; returns the
// edge list and the resulting EPS, then restores the original edges.
function buildLayout(state, prefer) {
  const saved = state.edges;
  state.edges = [];

  const nodes = state.nodes
    .filter((n) => !n.melted && (NODE_DEFS[n.def].maxOut || 0) > 0)
    .sort((a, b) => distToCore(b) - distToCore(a));

  for (const n of nodes) {
    const def = NODE_DEFS[n.def];
    const dn = distToCore(n);

    // candidate targets: Core (if reachable) + nodes strictly closer to Core
    const cands = [];
    if (dn <= def.range) cands.push({ id: CORE_ID, def: null, d: 0 });
    for (const m of state.nodes) {
      if (m.id === n.id || m.melted) continue;
      if (distToCore(m) >= dn) continue;
      cands.push({ id: m.id, def: NODE_DEFS[m.def], d: distToCore(m) });
    }
    cands.sort((a, b) => prefer(b) - prefer(a) || a.d - b.d);

    let added = 0;
    for (const c of cands) {
      if (added >= def.maxOut) break;
      if (addEdge(state, n.id, c.id).ok) added++;
    }
  }

  const edges = state.edges.map((e) => ({ from: e.from, to: e.to }));
  recomputeEps(state);
  const eps = state.eps;
  state.edges = saved;
  state.flowDirty = true;
  return { edges, eps };
}

// Scorers (higher = preferred target).
const MULT_ROLES = { modifier: 3, condenser: 4 };
function scoreFunnel(c) { return c.id === CORE_ID ? 5 : 1; }
function scoreRefine(c) { return c.def ? (MULT_ROLES[c.def.role] || 0) + (c.def.mult > 1 ? 2 : 0) : 1; }
function scoreSpread(c) { return c.def ? (c.def.role === "router" ? 4 : c.def.role === "conduit" ? 3 : 1) : 2; }

export function planLayouts(state) {
  const before = state.edges;
  const plans = [
    { name: "Funnel", desc: "Shortest paths straight to the Core.", ...buildLayout(state, scoreFunnel) },
    { name: "Refine", desc: "Route through multipliers for max throughput.", ...buildLayout(state, scoreRefine) },
    { name: "Spread", desc: "Balance across routers and highways.", ...buildLayout(state, scoreSpread) },
  ];
  state.edges = before;
  state.flowDirty = true;
  recomputeEps(state);
  return plans;
}

export function applyLayout(state, edges) {
  state.edges = edges.map((e) => ({ from: e.from, to: e.to }));
  state.flowDirty = true;
  state.epsDirty = true;
}
