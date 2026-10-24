// Flow-graph engine for Stage 2. Directed edges carry Aether from Siphons toward
// the central Core (a virtual sink). Edges have capacity; nodes transform flow
// (multipliers, splitting, merging). Flow is computed in steady state via forward
// topological propagation — O(V+E), run on graph change (dirty flag), not per tick.
//
// Model summary:
//   - Siphons inject baseOutput. If a Siphon has NO outgoing edges it auto-delivers
//     to the Core (idle-friendly "direct" flow). If wired, its supply enters the net.
//   - Each node multiplies throughput by def.mult, then distributes it across its
//     outgoing edges (evenly), capped by each edge's capacity.
//   - Flow arriving at the Core counts as EPS. Dead-end flow is lost (learnable).
//   - Acyclic enforced: edges that would create a cycle are rejected.

import { NODE_DEFS } from "./nodes.js";
import { hexDistance } from "./grid.js";
import { CONDENSER_ESSENCE } from "./state.js";

export const CORE_ID = "core";

export function edgeKey(fromId, toId) {
  return fromId + ">" + toId;
}

function nodeById(state, id) {
  return state.nodes.find((n) => n.id === id) || null;
}

// Position of a node id (or the Core at axial 0,0).
function posOf(state, id) {
  if (id === CORE_ID) return { q: 0, r: 0 };
  const n = nodeById(state, id);
  return n ? { q: n.q, r: n.r } : null;
}

function connCount(state, id) {
  let c = 0;
  for (const e of state.edges) if (e.from === id || e.to === id) c++;
  return c;
}

// Can flow from `start` reach `goal` following existing outgoing edges?
function reaches(state, start, goal) {
  const stack = [start];
  const seen = new Set();
  while (stack.length) {
    const cur = stack.pop();
    if (cur === goal) return true;
    if (seen.has(cur)) continue;
    seen.add(cur);
    for (const e of state.edges) if (e.from === cur) stack.push(e.to);
  }
  return false;
}

// Validate a proposed directed edge fromId -> toId. Returns {ok, reason}.
export function canConnect(state, fromId, toId) {
  if (fromId === toId) return { ok: false, reason: "same node" };
  if (toId === CORE_ID && fromId === CORE_ID) return { ok: false, reason: "invalid" };

  const fromNode = fromId === CORE_ID ? null : nodeById(state, fromId);
  if (!fromNode) return { ok: false, reason: "no source" };
  const fromDef = NODE_DEFS[fromNode.def];
  if (fromDef.maxOut <= 0) return { ok: false, reason: `${fromDef.name} has no output` };

  let toDef = null;
  if (toId !== CORE_ID) {
    const toNode = nodeById(state, toId);
    if (!toNode) return { ok: false, reason: "no target" };
    toDef = NODE_DEFS[toNode.def];
    if (toDef.maxConns <= 0) return { ok: false, reason: `${toDef.name} accepts no links` };
  }

  // duplicate
  for (const e of state.edges) {
    if (e.from === fromId && e.to === toId) return { ok: false, reason: "already linked" };
  }

  // range: max range of either endpoint
  const a = posOf(state, fromId);
  const b = posOf(state, toId);
  const dist = hexDistance(a.q, a.r, b.q, b.r);
  const maxRange = Math.max(fromDef.range, toDef ? toDef.range : 1);
  if (dist > maxRange) return { ok: false, reason: "out of range" };

  // outgoing-edge cap: routers split across up to maxOut outputs, everything
  // else feeds a single downstream target
  let outCount = 0;
  for (const e of state.edges) if (e.from === fromId) outCount++;
  if (outCount >= fromDef.maxOut) return { ok: false, reason: "source outputs full" };

  // connection caps (+ research/echo bonus)
  const connBonus = state.mods ? state.mods.connBonus : 0;
  if (connCount(state, fromId) >= fromDef.maxConns + connBonus) return { ok: false, reason: "source full" };
  if (toId !== CORE_ID && connCount(state, toId) >= toDef.maxConns + connBonus) {
    return { ok: false, reason: "target full" };
  }

  // acyclic: would `to` already reach `from`?
  if (toId !== CORE_ID && reaches(state, toId, fromId)) {
    return { ok: false, reason: "would loop" };
  }

  return { ok: true };
}

export function addEdge(state, fromId, toId) {
  const v = canConnect(state, fromId, toId);
  if (!v.ok) return v;
  state.edges.push({ from: fromId, to: toId });
  state.flowDirty = true;
  return { ok: true };
}

export function removeEdge(state, fromId, toId) {
  const i = state.edges.findIndex((e) => e.from === fromId && e.to === toId);
  if (i === -1) return false;
  state.edges.splice(i, 1);
  state.flowDirty = true;
  return true;
}

// Drop every edge touching a node (called when the node is deleted).
export function removeEdgesTouching(state, id) {
  const before = state.edges.length;
  state.edges = state.edges.filter((e) => e.from !== id && e.to !== id);
  if (state.edges.length !== before) state.flowDirty = true;
}

// Capacity of an edge: source node's edgeCap, boosted by conduits at either end.
export function edgeCapacity(state, edge) {
  const from = nodeById(state, edge.from);
  if (!from) return 0;
  const fromDef = NODE_DEFS[from.def];
  let cap = fromDef.edgeCap;

  const to = edge.to === CORE_ID ? null : nodeById(state, edge.to);
  const toDef = to ? NODE_DEFS[to.def] : null;

  if (fromDef.id === "capacitor" || (toDef && toDef.id === "capacitor")) cap += 120;
  if (fromDef.id === "relay" || (toDef && toDef.id === "relay")) cap = Math.max(cap, 250);
  const capMult = state.mods ? state.mods.edgeCap : 1;
  return cap * capMult;
}

// Forward propagation. Returns a flow report; also caches per-node throughput.
export function computeFlow(state) {
  const nodes = state.nodes;
  const edges = state.edges;

  // adjacency
  const outEdges = new Map(); // id -> [edge]
  const inDeg = new Map();    // id -> count (for Kahn topo, core excluded as sink)
  for (const n of nodes) { outEdges.set(n.id, []); inDeg.set(n.id, 0); }
  for (const e of edges) {
    if (outEdges.has(e.from)) outEdges.get(e.from).push(e);
    if (e.to !== CORE_ID && inDeg.has(e.to)) inDeg.set(e.to, inDeg.get(e.to) + 1);
  }

  // Kahn topological order (sources first). Core is not a node here.
  const queue = [];
  for (const n of nodes) if (inDeg.get(n.id) === 0) queue.push(n.id);
  const order = [];
  const indegWork = new Map(inDeg);
  while (queue.length) {
    const id = queue.shift();
    order.push(id);
    for (const e of outEdges.get(id) || []) {
      if (e.to === CORE_ID) continue;
      indegWork.set(e.to, indegWork.get(e.to) - 1);
      if (indegWork.get(e.to) === 0) queue.push(e.to);
    }
  }
  // If a cycle slipped through, append leftovers so flow still resolves.
  if (order.length < nodes.length) {
    for (const n of nodes) if (!order.includes(n.id)) order.push(n.id);
  }

  const inflow = new Map();      // delivered into a node
  const throughput = new Map();  // after node multiplier
  for (const n of nodes) { inflow.set(n.id, 0); throughput.set(n.id, 0); }

  const edgeFlow = new Map();    // key -> delivered units/sec
  const edgeUtil = new Map();    // key -> demand/capacity (>1 == bottleneck)
  const edgeCap = new Map();

  let networkedFlow = 0;
  let directFlow = 0;
  let essenceRate = 0;

  const mods = state.mods || { siphon: 1, channel: 1, essence: 1 };

  const orderIndex = new Map(order.map((id, i) => [id, i]));
  const sorted = nodes.slice().sort((a, b) => orderIndex.get(a.id) - orderIndex.get(b.id));

  for (const n of sorted) {
    const def = NODE_DEFS[n.def];

    // Melted nodes are offline: no supply, no throughput, incoming flow is lost.
    if (n.melted) {
      throughput.set(n.id, 0);
      n._inflow = 0; n._throughput = 0; n._util = 0; n._essence = 0;
      continue;
    }

    const supply = def.role === "producer" ? def.baseOutput * mods.siphon : 0;
    const totalIn = supply + inflow.get(n.id);
    // scale modifier nodes' bonus by the channel mod; pass-through nodes stay 1
    const effMult = def.mult > 1 ? 1 + (def.mult - 1) * mods.channel : def.mult;
    const out = totalIn * effMult;
    throughput.set(n.id, out);

    // Condensers/Crucibles turn passing flow into Essence (a side product).
    if (def.role === "condenser") {
      const factor = (def.essenceFactor || CONDENSER_ESSENCE) * mods.essence;
      essenceRate += totalIn * factor;
      n._essence = totalIn * factor;
    }
    n._inflow = totalIn;
    n._throughput = out;
    n._util = 0;

    const outs = outEdges.get(n.id) || [];

    if (outs.length === 0) {
      // dead end. Unwired Siphon auto-delivers to Core (idle-friendly).
      if (def.role === "producer") directFlow += supply;
      continue;
    }

    // distribute throughput evenly across outgoing edges
    const share = out / outs.length;
    let nodeUtil = 0;
    for (const e of outs) {
      const cap = edgeCapacity(state, e);
      const key = edgeKey(e.from, e.to);
      const demand = share;
      const delivered = Math.min(demand, cap);
      const util = cap > 0 ? demand / cap : 0;
      edgeFlow.set(key, delivered);
      edgeCap.set(key, cap);
      edgeUtil.set(key, util);
      nodeUtil = Math.max(nodeUtil, util);

      if (e.to === CORE_ID) networkedFlow += delivered;
      else inflow.set(e.to, inflow.get(e.to) + delivered);
    }
    n._util = nodeUtil;
  }

  // find worst (most utilized) edge for bottleneck highlight
  let bottleneck = null;
  let worst = 0;
  for (const [key, util] of edgeUtil) {
    if (util > worst) { worst = util; bottleneck = key; }
  }

  const coreFlow = directFlow + networkedFlow;
  const report = {
    coreFlow, directFlow, networkedFlow, essenceRate,
    edgeFlow, edgeUtil, edgeCap,
    bottleneck, bottleneckUtil: worst,
  };
  state.flow = report;
  state.flowDirty = false;
  return report;
}
