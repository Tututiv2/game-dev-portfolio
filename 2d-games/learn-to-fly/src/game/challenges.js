// §7.5 — CHALLENGES ARE PER-PLAYTHROUGH AND PAY CASH. Medals are account-level and pay
// bonus points. That is the whole distinction, verbatim from the wiki, and getting it right
// in save.js from day one is what medals.js depends on.
//
// Achiever 1 and 2 (medals 28 and 29) are "complete all challenges in one Story / Classic
// game", which is why these have to be per-playthrough and resettable.

export const CHALLENGE_SETS = {
  story: [
    { id: 's_first', name: 'First Blood', desc: 'Destroy the snowman', cash: 500, test: (s) => (s.destroyedKinds.snowman | 0) > 0 },
    { id: 's_mound', name: 'Groundwork', desc: 'Destroy the snow mound', cash: 1200, test: (s) => (s.destroyedKinds.mound | 0) > 0 },
    { id: 's_hill', name: 'Rock Bottom', desc: 'Destroy the rocky hill', cash: 3000, test: (s) => (s.destroyedKinds.hill | 0) > 0 },
    { id: 's_berg', name: 'Cold Revenge', desc: 'Destroy the iceberg', cash: 8000, test: (s) => (s.destroyedKinds.iceberg | 0) > 0 },
    { id: 's_two', name: 'Double Tap', desc: 'Destroy two obstacles in one run', cash: 2500, test: (s) => s.bestRunKills >= 2 },
    { id: 's_over', name: 'Overshoot', desc: 'Fly 400 ft in one run', cash: 900, test: (s) => s.bestDistFt >= 400 },
    { id: 's_high', name: 'Up There', desc: 'Reach 300 ft altitude', cash: 1400, test: (s) => s.bestAltFt >= 300 },
    { id: 's_fast', name: 'Terminal', desc: 'Reach 200 mph', cash: 2200, test: (s) => s.bestSpeedMph >= 200 },
  ],
  classic: [
    { id: 'c_500', name: 'Getting Somewhere', desc: 'Fly 500 ft', cash: 400, test: (s) => s.bestDistFt >= 500 },
    { id: 'c_2k', name: 'Committed', desc: 'Fly 2,000 ft', cash: 1800, test: (s) => s.bestDistFt >= 2000 },
    { id: 'c_6k', name: 'The Old Goal', desc: 'Fly 6,000 ft', cash: 9000, test: (s) => s.bestDistFt >= 6000 },
    { id: 'c_bounce', name: 'Stone Skipper', desc: 'Chain 8 water skips in one run', cash: 1500, test: (s) => s.bestBounces >= 8 },
    { id: 'c_air', name: 'Loiter', desc: 'Stay airborne 40 s in one run', cash: 2000, test: (s) => s.bestAirTime >= 40 },
    { id: 'c_high', name: 'Thin Air', desc: 'Reach 600 ft altitude', cash: 2600, test: (s) => s.bestAltFt >= 600 },
    { id: 'c_fast', name: 'Redline', desc: 'Reach 300 mph', cash: 3200, test: (s) => s.bestSpeedMph >= 300 },
    { id: 'c_dive', name: 'Submariner', desc: 'Submerge 60 ft in one dive', cash: 1100, test: (s) => s.bestDiveFt >= 60 },
  ],
};

/** The per-playthrough stat block challenges are tested against. Lives in save.ltf2. */
export function freshChallengeStats() {
  return {
    destroyedKinds: {}, bestRunKills: 0, bestDistFt: 0, bestAltFt: 0,
    bestSpeedMph: 0, bestBounces: 0, bestAirTime: 0, bestDiveFt: 0, done: {},
  };
}

export function recordRun(stats, run, pay, T) {
  stats.bestDistFt = Math.max(stats.bestDistFt, pay.distFt);
  stats.bestAltFt = Math.max(stats.bestAltFt, pay.altFt);
  stats.bestSpeedMph = Math.max(stats.bestSpeedMph, run.maxSpeed * T.MPH_PER_MS);
  stats.bestBounces = Math.max(stats.bestBounces, run.bounces);
  stats.bestAirTime = Math.max(stats.bestAirTime, run.airTime);
  stats.bestDiveFt = Math.max(stats.bestDiveFt, run.maxDepth * T.FT_PER_M);
  stats.bestRunKills = Math.max(stats.bestRunKills, run.destroyed.length);
  for (const id of run.destroyed) stats.destroyedKinds[id] = (stats.destroyedKinds[id] | 0) + 1;
  return stats;
}

/** @returns {{newly:Array, cash:number, allDone:boolean}} */
export function settle(stats, mode) {
  const set = CHALLENGE_SETS[mode] || [];
  const newly = [];
  let cash = 0;
  for (const c of set) {
    if (stats.done[c.id]) continue;
    let ok = false;
    try { ok = c.test(stats); } catch { ok = false; }
    if (!ok) continue;
    stats.done[c.id] = true;
    newly.push(c);
    cash += c.cash;
  }
  return { newly, cash, allDone: set.length > 0 && set.every((c) => stats.done[c.id]) };
}
