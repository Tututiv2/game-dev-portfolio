// Research tree (Stage 3): 12 branching upgrades bought with Essence. Each defines
// a `mod` callback that mutates the modifier bundle. `req` lists prerequisite ids.
// Resets on Collapse (Essence is a run currency).

export const RESEARCH = [
  // Branch A — Flow (raw Aether throughput)
  { id: "a1", branch: "Flow", name: "Stronger Siphons", desc: "Siphon output x1.5", cost: 20, req: [], mod: (m) => { m.siphon *= 1.5; } },
  { id: "a2", branch: "Flow", name: "Wider Conduits", desc: "Edge capacity x1.5", cost: 50, req: ["a1"], mod: (m) => { m.edgeCap *= 1.5; } },
  { id: "a3", branch: "Flow", name: "Overclock", desc: "Siphon output x2", cost: 200, req: ["a2"], mod: (m) => { m.siphon *= 2; } },
  { id: "a4", branch: "Flow", name: "Superflow", desc: "All production x1.15", cost: 800, req: ["a3"], mod: (m) => { m.global *= 1.15; } },

  // Branch B — Focus (multipliers, links, surge, essence)
  { id: "b1", branch: "Focus", name: "Tuned Channels", desc: "Channel/Filter effect +50%", cost: 30, req: [], mod: (m) => { m.channel *= 1.5; } },
  { id: "b2", branch: "Focus", name: "Resonant Filters", desc: "Essence x1.5", cost: 80, req: ["b1"], mod: (m) => { m.essence *= 1.5; } },
  { id: "b3", branch: "Focus", name: "Deep Link", desc: "+1 max connection per node", cost: 150, req: ["b1"], mod: (m) => { m.connBonus += 1; } },
  { id: "b4", branch: "Focus", name: "Harmonic Mastery", desc: "Surge lasts +15s", cost: 500, req: ["b2"], mod: (m) => { m.surgeDur += 15; } },

  // Branch C — Mind (clicks, drip, offline, milestones)
  { id: "c1", branch: "Mind", name: "Quick Hands", desc: "Manual siphon x3", cost: 15, req: [], mod: (m) => { m.click *= 3; } },
  { id: "c2", branch: "Mind", name: "Steady Drip", desc: "Rift drip x3", cost: 40, req: ["c1"], mod: (m) => { m.drip *= 3; } },
  { id: "c3", branch: "Mind", name: "Long Memory", desc: "Offline cap +8h", cost: 60, req: ["c1"], mod: (m) => { m.offlineCap += 8 * 3600; } },
  { id: "c4", branch: "Mind", name: "Insight", desc: "Milestone bonus doubled", cost: 300, req: ["c2"], mod: (m) => { m.milestoneScale *= 2; } },
];

export const RESEARCH_BY_ID = Object.fromEntries(RESEARCH.map((r) => [r.id, r]));

export function canResearch(state, id) {
  const r = RESEARCH_BY_ID[id];
  if (!r) return false;
  if (state.research.includes(id)) return false;
  for (const pre of r.req) if (!state.research.includes(pre)) return false;
  return true;
}
