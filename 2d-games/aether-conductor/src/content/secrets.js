// Secrets (Stage 3 implements 1-4). Each has a hidden trigger and a small lasting
// bonus + a codex lore fragment revealed only once discovered. Detection is split:
//   detectPassive(state)  -> ids unlockable from current state (time/ratio/symmetry)
//   (Lucky Echo is event-based, checked at Collapse — see prestige.js)

export const SECRETS = {
  golden_relay: {
    id: "golden_relay", name: "Golden Relay",
    lore: "Channels, Filters, Condensers in 2:3:5 — the golden chord. The lattice hums approvingly. (+10% Essence)",
    bonus: (m) => { m.essence *= 1.10; },
  },
  void_click: {
    id: "void_click", name: "Void Click",
    lore: "You waited in the silence for 4:44. Something in the void clicked back. (+1% all production)",
    bonus: (m) => { m.global *= 1.01; },
  },
  mirror_shard: {
    id: "mirror_shard", name: "Mirror Shard",
    lore: "A layout perfectly mirrored through the Core. The reflection pays dividends. (-10% node costs)",
    bonus: (m) => { m.costMult *= 0.90; },
  },
  lucky_echo: {
    id: "lucky_echo", name: "Lucky Echo",
    lore: "Collapsed on exactly 777 Essence. Fortune favors the precise. (+2% Essence)",
    bonus: (m) => { m.essence *= 1.02; },
  },
};

export const TOTAL_SECRETS = 12; // codex shows X / 12 (8 reserved for later stages)

// Returns secret ids that should unlock given current state (excludes already-found).
export function detectPassive(state) {
  const found = new Set(state.codex.secrets);
  const out = [];
  const o = state.owned;

  // Golden Relay: channel:filter:condenser == 2:3:5
  if (!found.has("golden_relay") && o.channel > 0 && o.filter > 0 && o.condenser > 0) {
    const k = o.channel / 2;
    if (Number.isInteger(k) && k >= 1 && o.filter === 3 * k && o.condenser === 5 * k) {
      out.push("golden_relay");
    }
  }

  // Void Click: 4 min 44 sec accumulated with zero nodes placed
  if (!found.has("void_click") && state.noNodeTime >= 284) out.push("void_click");

  // Mirror Shard: >=6 nodes, every node has a 180-degree counterpart through Core
  if (!found.has("mirror_shard") && state.nodes.length >= 6) {
    const set = new Set(state.nodes.map((n) => n.q + "," + n.r));
    let symmetric = true;
    for (const n of state.nodes) {
      if (n.q === 0 && n.r === 0) { symmetric = false; break; }
      if (!set.has((-n.q) + "," + (-n.r))) { symmetric = false; break; }
    }
    if (symmetric) out.push("mirror_shard");
  }

  return out;
}

export function applySecretBonuses(m, state) {
  for (const id of state.codex.secrets) {
    const s = SECRETS[id];
    if (s && s.bonus) s.bonus(m);
  }
}
