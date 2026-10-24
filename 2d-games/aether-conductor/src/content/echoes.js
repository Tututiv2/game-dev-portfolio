// Echo shop (Stage 3): 8 PERMANENT upgrades bought with Echoes (survive Collapse).
// Each is leveled; cost grows geometrically. `mod(m, lvl)` applies the bonus.
//
// Cost formula: cost(lvl) = ceil(base * growth^lvl).

export const ECHO_UPGRADES = [
  { id: "e1", name: "Resonance Memory", desc: "+10% all production / level", base: 1, growth: 1.6, max: 50,
    mod: (m, l) => { m.global *= 1 + 0.10 * l; } },
  { id: "e2", name: "Aether Reserve", desc: "Start each run with +1,000 Aether / level", base: 1, growth: 1.5, max: 50,
    mod: (m, l) => { m.startAether += 1000 * l; } },
  { id: "e3", name: "Essence Affinity", desc: "Essence x1.25 / level", base: 2, growth: 1.7, max: 50,
    mod: (m, l) => { m.essence *= Math.pow(1.25, l); } },
  { id: "e4", name: "Swift Hands", desc: "Manual siphon x2 / level", base: 1, growth: 1.5, max: 30,
    mod: (m, l) => { m.click *= Math.pow(2, l); } },
  { id: "e5", name: "Bargain", desc: "Node costs -5% / level (min 25%)", base: 3, growth: 1.8, max: 15,
    mod: (m, l) => { m.costMult *= Math.max(0.25, 1 - 0.05 * l); } },
  { id: "e6", name: "Extended Surge", desc: "Harmonic Surge +5s / level", base: 2, growth: 1.6, max: 20,
    mod: (m, l) => { m.surgeDur += 5 * l; } },
  { id: "e7", name: "Time Capsule", desc: "Offline cap +4h / level", base: 2, growth: 1.5, max: 12,
    mod: (m, l) => { m.offlineCap += 4 * 3600 * l; } },
  { id: "e8", name: "Echo Cascade", desc: "+1 bonus Echo per Collapse / level", base: 5, growth: 2.0, max: 25,
    mod: (m, l) => { m.echoBonus += l; } },
];

export const ECHO_BY_ID = Object.fromEntries(ECHO_UPGRADES.map((e) => [e.id, e]));

export function echoCost(up, level) {
  return Math.ceil(up.base * Math.pow(up.growth, level));
}
