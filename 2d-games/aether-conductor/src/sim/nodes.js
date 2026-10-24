// Data-driven node definitions. Shared schema so future stages can add nodes
// without touching the engine.
//
// Schema:
//   id          unique key
//   name        display name
//   desc        short tooltip
//   cost        base purchase cost (Aether)
//   costGrowth  multiplier applied per owned copy (geometric pricing)
//   accent      CSS color used for shop + render glow
//   role        "producer" | "modifier" | "storage" | "router" | "conduit"
//   baseOutput  Aether/sec injected into the network (producers)
//   mult        multiplicative bonus applied to flow PASSING THROUGH this node
//   capacity    storage buffer size (storage) OR edge-capacity bonus (conduit)
//   maxConns    max total connections (in + out)
//   range       connection range in hex distance (1 = adjacent only)
//   edgeCap     capacity of OUTGOING edges from this node (units/sec)
//   maxOut      max outgoing edges used for even splitting (routers)
//   stage       which stage unlocks it (shop gating)
//   tier        harmonic tier for resonance (Channel=2, Filter=3, Condenser=5)

export const NODE_DEFS = {
  siphon: {
    id: "siphon", name: "Siphon", desc: "Draws raw Aether. +2/s into the network.",
    cost: 15, costGrowth: 1.15, accent: "#36e6ff",
    role: "producer", baseOutput: 2, mult: 1, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 50, maxOut: 1, stage: 1, tier: 0,
  },
  channel: {
    id: "channel", name: "Channel", desc: "Focuses flow passing through. x1.25 throughput. (Tier 2)",
    cost: 40, costGrowth: 1.18, accent: "#ff4fd8",
    role: "modifier", baseOutput: 0, mult: 1.25, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 50, maxOut: 1, stage: 1, tier: 2,
  },
  vault: {
    id: "vault", name: "Vault", desc: "Stores overflow Aether. +5k capacity.",
    cost: 100, costGrowth: 1.2, accent: "#ffd166",
    role: "storage", baseOutput: 0, mult: 1, capacity: 5000,
    maxConns: 0, range: 1, edgeCap: 0, maxOut: 0, stage: 1, tier: 0,
  },

  // ---- Stage 2 ----
  splitter: {
    id: "splitter", name: "Splitter", desc: "Splits incoming flow evenly across up to 3 outputs.",
    cost: 220, costGrowth: 1.2, accent: "#7afcff",
    role: "router", baseOutput: 0, mult: 1, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 50, maxOut: 3, stage: 2, tier: 0,
  },
  merger: {
    id: "merger", name: "Merger", desc: "Combines up to 3 inputs into one output stream.",
    cost: 220, costGrowth: 1.2, accent: "#9d7bff",
    role: "router", baseOutput: 0, mult: 1, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 80, maxOut: 1, stage: 2, tier: 0,
  },
  capacitor: {
    id: "capacitor", name: "Capacitor", desc: "Relieves bottlenecks: +120/s capacity to its edges.",
    cost: 400, costGrowth: 1.22, accent: "#ffe08a",
    role: "conduit", baseOutput: 0, mult: 1, capacity: 120,
    maxConns: 4, range: 1, edgeCap: 170, maxOut: 1, stage: 2, tier: 0,
  },
  filter: {
    id: "filter", name: "Filter", desc: "Purifies flow. x1.10 throughput. (Tier 3)",
    cost: 320, costGrowth: 1.2, accent: "#59ffa8",
    role: "modifier", baseOutput: 0, mult: 1.10, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 50, maxOut: 1, stage: 2, tier: 3,
  },
  relay: {
    id: "relay", name: "Relay", desc: "Long-range highway. Range 3, edges carry 250/s.",
    cost: 500, costGrowth: 1.22, accent: "#ff9d5c",
    role: "conduit", baseOutput: 0, mult: 1, capacity: 0,
    maxConns: 5, range: 3, edgeCap: 250, maxOut: 1, stage: 2, tier: 0,
  },

  // ---- Stage 3 ----
  condenser: {
    id: "condenser", name: "Condenser", desc: "Condenses passing flow into Essence. (Tier 5)",
    cost: 600, costGrowth: 1.22, accent: "#c77dff",
    role: "condenser", baseOutput: 0, mult: 1, capacity: 0, essenceFactor: 0.08,
    maxConns: 4, range: 1, edgeCap: 50, maxOut: 1, stage: 3, tier: 5,
  },

  // ---- Stage 4 (dimensional) — unlock after your first Collapse ----
  amplifier: {
    id: "amplifier", name: "Amplifier", desc: "x1.6 throughput. Runs hot.",
    cost: 1500, costGrowth: 1.24, accent: "#ff7ab8",
    role: "modifier", baseOutput: 0, mult: 1.6, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 80, maxOut: 1, stage: 4, tier: 0,
    heatGen: 14, heatCool: 1, heatCap: 100,
  },
  overclocker: {
    id: "overclocker", name: "Overclocker", desc: "x2.4 throughput. Dangerously hot.",
    cost: 4000, costGrowth: 1.26, accent: "#ff5d6c",
    role: "modifier", baseOutput: 0, mult: 2.4, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 80, maxOut: 1, stage: 4, tier: 0,
    heatGen: 30, heatCool: 1, heatCap: 120,
  },
  heatsink: {
    id: "heatsink", name: "Heatsink", desc: "Cools adjacent nodes (-8 heat/s each).",
    cost: 2000, costGrowth: 1.22, accent: "#7afcff",
    role: "conduit", baseOutput: 0, mult: 1, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 80, maxOut: 1, stage: 4, tier: 0,
    heatGen: 0, heatCool: 4, heatCap: 200, heatSink: 8,
  },
  resonator: {
    id: "resonator", name: "Resonator", desc: "x1.35 throughput. (Tier 3 harmonic)",
    cost: 2600, costGrowth: 1.23, accent: "#9d7bff",
    role: "modifier", baseOutput: 0, mult: 1.35, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 80, maxOut: 1, stage: 4, tier: 3,
    heatGen: 4, heatCool: 1, heatCap: 120,
  },
  prism: {
    id: "prism", name: "Prism", desc: "Splits into 2 outputs, each x1.2.",
    cost: 3200, costGrowth: 1.24, accent: "#5cf2c8",
    role: "modifier", baseOutput: 0, mult: 1.2, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 80, maxOut: 2, stage: 4, tier: 0,
    heatGen: 3, heatCool: 1, heatCap: 120,
  },
  crucible: {
    id: "crucible", name: "Crucible", desc: "Premium Condenser: 3x Essence yield. (Tier 5)",
    cost: 5000, costGrowth: 1.25, accent: "#ff9d5c",
    role: "condenser", baseOutput: 0, mult: 1, capacity: 0, essenceFactor: 0.24,
    maxConns: 4, range: 1, edgeCap: 60, maxOut: 1, stage: 4, tier: 5,
    heatGen: 8, heatCool: 1, heatCap: 140,
  },
  transformer: {
    id: "transformer", name: "Transformer", desc: "Heavy highway: edges carry 400/s.",
    cost: 3500, costGrowth: 1.24, accent: "#ffd166",
    role: "conduit", baseOutput: 0, mult: 1, capacity: 0,
    maxConns: 4, range: 2, edgeCap: 400, maxOut: 1, stage: 4, tier: 0,
    heatGen: 5, heatCool: 1, heatCap: 160,
  },
  nexus: {
    id: "nexus", name: "Nexus", desc: "Hub with up to 8 connections.",
    cost: 4200, costGrowth: 1.25, accent: "#36e6ff",
    role: "router", baseOutput: 0, mult: 1, capacity: 0,
    maxConns: 8, range: 2, edgeCap: 120, maxOut: 6, stage: 4, tier: 0,
    heatGen: 2, heatCool: 1, heatCap: 160,
  },
  flux_gate: {
    id: "flux_gate", name: "Flux Gate", desc: "x1.5 throughput, vents its own heat.",
    cost: 2800, costGrowth: 1.23, accent: "#c77dff",
    role: "modifier", baseOutput: 0, mult: 1.5, capacity: 0,
    maxConns: 4, range: 1, edgeCap: 90, maxOut: 1, stage: 4, tier: 0,
    heatGen: 9, heatCool: 4, heatCap: 130,
  },
  pylon: {
    id: "pylon", name: "Pylon", desc: "Long-range relay (range 4), edges carry 300/s.",
    cost: 3000, costGrowth: 1.24, accent: "#ff9d5c",
    role: "conduit", baseOutput: 0, mult: 1, capacity: 0,
    maxConns: 5, range: 4, edgeCap: 300, maxOut: 1, stage: 4, tier: 0,
    heatGen: 0, heatCool: 2, heatCap: 200,
  },
};

export const NODE_ORDER = [
  "siphon", "channel", "vault",
  "splitter", "merger", "capacitor", "filter", "relay",
  "condenser",
  "amplifier", "overclocker", "heatsink", "resonator", "prism",
  "crucible", "transformer", "nexus", "flux_gate", "pylon",
];

// A node is buyable once its stage is unlocked. Stage 4 nodes unlock after the
// first Collapse (you've entered a Dimension).
export function nodeUnlocked(state, id) {
  const def = NODE_DEFS[id];
  if (!def) return false;
  if (def.stage <= 3) return true;
  return (state.collapses || 0) >= 1;
}

export function nodeCost(defId, owned) {
  const def = NODE_DEFS[defId];
  return Math.ceil(def.cost * Math.pow(def.costGrowth, owned));
}

export function nodeRefund(defId, ownedAfterRemoval) {
  const paid = nodeCost(defId, ownedAfterRemoval);
  return Math.floor(paid * 0.5);
}
