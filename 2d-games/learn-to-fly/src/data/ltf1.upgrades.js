// The six LTF1 upgrades. §6.2 — the LIST is VERIFIED and exact; every NUMBER is CALIBRATE.
//
// §14 seam 3: the shop renders whatever this file contains. Adding a tier 6, or a seventh
// category, is a row here and nothing else. tests/run-tests.mjs check 3b proves that by
// injecting a nonsense tier at runtime and buying it.

export const UPGRADE_ORDER = [
  'rampHeight', 'acceleration', 'airResistance', 'glider', 'rockets', 'rocketFuel',
];

export const UPGRADES = {
  rampHeight: {
    name: 'Ramp Height', unit: 'ft', blurb: 'Start higher up.', icon: 'ramp',
    tiers: [
      { label: '10 ft',  heightM:  3, price: 0 },
      { label: '26 ft',  heightM:  8, price: 260 },
      { label: '49 ft',  heightM: 15, price: 1700 },
      { label: '79 ft',  heightM: 24, price: 7900 },
      { label: '118 ft', heightM: 36, price: 33000 },
    ],
  },

  acceleration: {
    name: 'Acceleration', unit: 'mph', blurb: 'Slipperier ramp. Leave faster.', icon: 'accel',
    tiers: [
      { label: '27 mph', exitMph: 27, price: 0 },
      { label: '40 mph', exitMph: 40, price: 220 },
      { label: '54 mph', exitMph: 54, price: 1550 },
      { label: '69 mph', exitMph: 69, price: 7500 },
      { label: '89 mph', exitMph: 89, price: 30000 },
    ],
  },

  airResistance: {
    name: 'Air Resistance', unit: '%', blurb: 'Slip through the air. Keep speed longer.', icon: 'air',
    tiers: [
      { label: '—',    cd0Mul: 1.00, price: 0 },
      { label: '-14%', cd0Mul: 0.86, price: 400 },
      { label: '-28%', cd0Mul: 0.72, price: 2100 },
      { label: '-42%', cd0Mul: 0.58, price: 9900 },
      { label: '-55%', cd0Mul: 0.45, price: 39000 },
    ],
  },

  glider: {
    name: 'Glider', unit: '', blurb: 'Drop less. Survive more speed.', icon: 'glider',
    tiers: [
      // vMaxMph 50 / 100 / 225 are VERIFIED. The lift values are CALIBRATE, and two
      // constraints pin them:
      //
      //   STALL SPEED. A wing can only hold the penguin up above sqrt(G / lift). §16's
      //   Cardboard at 0.062 stalls at 42 mph, and tier-1 Acceleration launches at 40 — so
      //   the first glider a player can afford could not fly, and check 17 measured the
      //   "relief" of buying it at 1.19x. At 0.075 it stalls at 38 mph and clears the ramp.
      //
      //   THE DELTA WING IS PINNED AT 0.115 by check 8: §4.5 works its 3-4% resistance
      //   arithmetic with exactly that number, and that reading is the headline feel test.
      //
      // Best-glide L/D is DERIVED from these (lift x 141.19 with the current CD0/K), so
      // check 7's expected values are recomputed here rather than the tolerance loosened —
      // §12.7 is explicit about which of those two is allowed. See CALIBRATION.md C-4.
      { label: 'Flippers',    lift: 0.030, vMaxMph:  35, breakable: false, price: 0 },
      { label: 'Cardboard',   lift: 0.075, vMaxMph:  50, breakable: true,  price: 500 },
      { label: 'Hang Glider', lift: 0.095, vMaxMph: 100, breakable: true,  price: 3500 },
      { label: 'Delta Wing',  lift: 0.115, vMaxMph: 225, breakable: true,  price: 17700 },
      { label: 'Prototype',   lift: 0.145, vMaxMph: 400, breakable: true,  price: 72000 },
    ],
  },

  rockets: {
    name: 'Rockets', unit: '', blurb: 'A shove, exactly when you need one.', icon: 'rocket',
    // CALIBRATED DOWN from §16's 14 / 22 / 32 / 46 m/s². Holding altitude in a best glide
    // costs about G/(L/D) = 1.4 m/s², so §16's smallest rocket was already ten times
    // "hold height" and the largest was a second launch. Measured, the old numbers made the
    // rockets the single biggest distance driver in the game, ahead of the glider — and §6.2
    // is clear that the glider is the one that matters. See CALIBRATION.md C-2.
    tiers: [
      { label: 'None',       thrust:  0, price: 0 },
      { label: 'Firework',   thrust:  6, price: 800 },
      { label: 'Bottle',     thrust: 10, price: 4200 },
      { label: 'Solid Fuel', thrust: 15, price: 19700 },
      { label: 'Turbine',    thrust: 22, price: 77000 },
    ],
  },

  rocketFuel: {
    name: 'Rocket Fuel', unit: 's', blurb: 'Keeps them burning.', icon: 'fuel',
    // CALIBRATED DOWN from §16's 2.5 / 4.5 / 7.5 / 12.0 s. At twelve seconds of turbine a
    // burn is worth more delta-v than the entire ramp, which makes rockets propulsion and
    // collapses the last three days of the game into one — measured, see CALIBRATION.md C-2.
    // §2.5 is explicit that fuel is SMALL and thrust is BIG, and this is that sentence in
    // numbers: every tier is still a save you plan, not a second launch.
    tiers: [
      { label: '0.0 s', seconds: 0.0, price: 0 },
      { label: '1.2 s', seconds: 1.2, price: 600 },
      { label: '2.2 s', seconds: 2.2, price: 3100 },
      { label: '3.5 s', seconds: 3.5, price: 14800 },
      { label: '5.0 s', seconds: 5.0, price: 58000 },
    ],
  },
};

/** Fresh-save tier levels — every category at index 0. */
export function freshLevels() {
  const out = {};
  for (const k of Object.keys(UPGRADES)) out[k] = 0;
  return out;
}

/**
 * Collapse tier levels into the flat loadout the sim actually reads. This is the ONLY
 * place that knows the shape of the upgrade table, which is what keeps sim/ ignorant of it.
 */
export function loadoutFrom(levels, tuning, table = UPGRADES) {
  const at = (k) => {
    const tiers = table[k].tiers;
    const i = Math.max(0, Math.min(tiers.length - 1, levels[k] | 0));
    return tiers[i];
  };
  const g = at('glider');
  return {
    rampHeightM: at('rampHeight').heightM,
    exitMph: at('acceleration').exitMph,
    cd0Mul: at('airResistance').cd0Mul,
    glider: {
      lift: g.lift,
      vMaxMph: g.vMaxMph,
      breakable: g.breakable !== false,
      label: g.label,
    },
    thrust: at('rockets').thrust,
    fuelSeconds: at('rocketFuel').seconds,
    mass: tuning.PENGUIN_MASS,
    penetration: 1,
    blast: 0,
    bomb: false,
    sources: ['impact'],
  };
}

/** Cost of moving `key` from its current level to the next one, or null if maxed. */
export function nextTier(levels, key, table = UPGRADES) {
  const tiers = table[key].tiers;
  const lvl = levels[key] | 0;
  if (lvl >= tiers.length - 1) return null;
  return { index: lvl + 1, ...tiers[lvl + 1] };
}
