// Central game state. Kept as a plain object for easy serialization. The sim
// mutates fields in place; render reads them. `dirty` flags trigger EPS recompute.

export const SAVE_VERSION = 4;

export function emptyOwned() {
  return {
    siphon: 0, channel: 0, vault: 0, splitter: 0, merger: 0,
    capacitor: 0, filter: 0, relay: 0, condenser: 0,
  };
}

export function createState() {
  return {
    version: SAVE_VERSION,
    seed: (Math.random() * 0xffffffff) >>> 0,

    aether: 0,
    totalAether: 0,   // lifetime
    essence: 0,       // current Essence (Stage 3)
    runEssence: 0,    // Essence earned this Collapse run (drives Echo gain)

    // prestige
    echoes: 0,        // permanent prestige currency
    echoShop: {},     // echo upgrade id -> level
    collapses: 0,

    // dimensions (Stage 4)
    keys: 0,              // spent to reroll dimension choices
    dimension: null,      // active dimension { seed, name, hz, mods, tier }
    dimChoices: null,     // pending 3 choices after a Collapse (null = none)
    rerollCount: 0,
    highestDimTier: 0,

    // node instances placed on the grid: { id, def, q, r }
    nodes: [],
    // directed flow edges: { from: nodeId, to: nodeId|"core" }
    edges: [],
    owned: emptyOwned(),

    // research
    research: [],     // purchased upgrade ids

    // resonance / surge
    surge: { active: false, timeLeft: 0, cooldown: 0, pulses: 0 },

    // milestones / codex / secrets
    milestoneTier: 0,
    codex: { nodes: [], secrets: [] },
    playTime: 0,
    noNodeTime: 0,    // seconds elapsed with zero nodes (Void Click secret)

    eps: 0,
    essenceBaseRate: 0, // Essence/sec before global+surge
    dripRate: 0.5,
    flow: null,
    flowDirty: true,
    mods: null,         // computed modifier bundle (not persisted)

    selectedTool: "place",
    selectedNode: "siphon",

    settings: { showFps: false },

    lastSeen: Date.now(),
    epsDirty: true,
    nextNodeId: 1,
  };
}

export const STARTER_DRIP = 0.5;
export const OFFLINE_CAP_SECONDS = 8 * 3600; // 8 hours base
export const CONDENSER_ESSENCE = 0.08;       // Essence per unit Aether throughput
export const SURGE_DURATION = 30;            // base seconds
export const SURGE_COOLDOWN = 60;            // seconds after a surge ends
export const SURGE_MULT = 3;                  // x3 Essence (+200%) during surge
