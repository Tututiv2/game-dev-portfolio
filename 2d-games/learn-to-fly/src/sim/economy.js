// Money. §6.3 — the SHAPE is verified (height + distance, with a 1..3 time multiplier);
// the RATES are calibrated.
//
// This is the one file in sim/ that legitimately works in feet: the payout is defined in
// the units on the results panel, and converting here rather than in hud.js is what lets
// test 12 (monotonicity) assert on the number the player actually sees.

/** @returns {{distFt, altFt, base, mult, cash}} */
export function payout(run, T) {
  const distFt = Math.max(0, run.dist) * T.FT_PER_M;
  const altFt = Math.max(0, run.maxAlt) * T.FT_PER_M;
  const base = T.DIST_RATE * distFt + T.ALT_RATE * altFt;
  const raw = 1 + run.airTime / T.TIME_MULT_SCALE;
  const mult = raw < 1 ? 1 : raw > T.MULT_MAX ? T.MULT_MAX : raw;
  return { distFt, altFt, base, mult, cash: Math.round(base * mult) };
}

/** Total spent to reach a given set of tier levels. Used by the progression simulator. */
export function spentOn(levels, table) {
  let total = 0;
  for (const key of Object.keys(levels)) {
    const tiers = table[key].tiers;
    for (let i = 1; i <= (levels[key] | 0) && i < tiers.length; i++) total += tiers[i].price;
  }
  return total;
}

/**
 * Is loadout `b` strictly better than `a`? Every category at least as good, one strictly
 * better. Test 12 sweeps these pairs and asserts cash never goes DOWN across one.
 */
export function strictlyBetter(a, b) {
  let better = false;
  for (const key of Object.keys(a)) {
    if ((b[key] | 0) < (a[key] | 0)) return false;
    if ((b[key] | 0) > (a[key] | 0)) better = true;
  }
  return better;
}
