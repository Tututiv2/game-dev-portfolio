// §7.4 — four VERIFIED categories: Sleds, Gliders, Thrusters/Boosters, Payloads.
// Plus the VERIFIED Omega tier, unlocked with BONUS POINTS rather than cash, and the
// VERIFIED secret shop (§7.7).
//
// Same shape as ltf1.upgrades.js on purpose: the shop renders whatever data it is given
// (§14 seam 3), so LTF2's hangar is LTF1's shed with a different table behind it.

export const CATEGORY_ORDER = ['sled', 'glider', 'thruster', 'payload'];

export const CATALOG = {
  sled: {
    name: 'Sled', unit: 'mph', blurb: 'Launch speed and ground handling.',
    tiers: [
      { label: 'Toboggan',   exitMph: 30, price: 0 },
      { label: 'Runner',     exitMph: 45, price: 400 },
      { label: 'Steel Rail', exitMph: 62, price: 2600 },
      { label: 'Rocket Sled', exitMph: 84, price: 12000 },
      { label: 'OMEGA Sled', exitMph: 108, bp: 900, omega: true },
    ],
  },
  glider: {
    name: 'Glider', unit: '', blurb: 'Lift, and how much speed the wing survives.',
    tiers: [
      { label: 'Stubs',      lift: 0.034, vMaxMph: 40, breakable: false, price: 0 },
      { label: 'Kite',       lift: 0.070, vMaxMph: 70, breakable: true, price: 900 },
      { label: 'Sailwing',   lift: 0.100, vMaxMph: 160, breakable: true, price: 4800 },
      { label: 'Deltoid',    lift: 0.126, vMaxMph: 320, breakable: true, price: 19000 },
      { label: 'OMEGA Wing', lift: 0.155, vMaxMph: 720, breakable: true, bp: 1200, omega: true },
    ],
  },
  thruster: {
    name: 'Thruster', unit: '', blurb: 'Thrust, and how long it burns.',
    tiers: [
      { label: 'None',        thrust: 0, seconds: 0, price: 0 },
      { label: 'Bottle Pair', thrust: 8, seconds: 1.6, price: 1200 },
      { label: 'Solid Core',  thrust: 14, seconds: 2.6, price: 5600 },
      { label: 'Twin Turbine', thrust: 22, seconds: 4.0, price: 22000 },
      // CALIBRATED UP from 34. Speedster 2 (500 mph) is VERIFIED, and terminal in a powered
      // dive is sqrt((G + thrust) / cd0), so the medal is a direct constraint on this number:
      // at thrust 34 with Streamlining maxed it caps out at 440 mph and medal 25 is simply
      // impossible. Check 16 measured that. See CALIBRATION.md C-3.
      { label: 'OMEGA Drive', thrust: 58, seconds: 6.0, bp: 1400, omega: true },
    ],
  },
  payload: {
    name: 'Payload', unit: '', blurb: 'Mass and penetration. Bombs are one-use.',
    tiers: [
      { label: 'Empty',      massAdd: 0, penetration: 1.0, blast: 0, bomb: false, price: 0 },
      { label: 'Ballast',    massAdd: 14, penetration: 1.4, blast: 0, bomb: false, price: 700 },
      { label: 'Spike Nose', massAdd: 20, penetration: 2.6, blast: 0, bomb: false, price: 3400 },
      // VERIFIED: bombs are one-use and destroy your rig on detonation. So you carry one
      // specifically to hit the wall, and you accept that the run ends there.
      { label: 'Bomb',       massAdd: 40, penetration: 2.0, blast: 46000, bomb: true, price: 9000 },
      { label: 'OMEGA Charge', massAdd: 52, penetration: 3.4, blast: 96000, bomb: true, bp: 1100, omega: true },
    ],
  },
};

/** Attribute upgrades — bought with BP, not cash (§7.5). Five steps each. */
export const ATTRIBUTES = {
  lift:  { name: 'Aerofoil', blurb: 'Every wing lifts more.', per: 0.06, steps: 5, bp: 120, unit: '%' },
  power: { name: 'Fuel Mix', blurb: 'Every thruster burns longer.', per: 0.14, steps: 5, bp: 140, unit: '%' },
  mass:  { name: 'Ballistics', blurb: 'Impacts hit harder.', per: 0.18, steps: 5, bp: 130, unit: '%' },
  tough: { name: 'Streamlining', blurb: 'Less drag, more speed kept.', per: -0.08, steps: 5, bp: 150, unit: '%' },
};

/** §7.7 — a shady penguin sells things that should not exist. These are the reason to replay. */
export const SECRET_SHOP = [
  { id: 'brick', name: 'The Brick', price: 300,
    desc: 'A "glider" with no lift and enormous mass. Terrible. Destroys obstacles beautifully.' },
  { id: 'phoenix', name: 'Phoenix Feather', price: 450,
    desc: 'On landing, you are relaunched into the air by Zeus. No, we did not ask either.' },
  { id: 'rob', name: 'R.O.B. 64', price: 400,
    desc: 'Appears at high altitude and refuels you mid-flight. Waves.' },
];

export const BRICK = { lift: 0, vMaxMph: 9999, breakable: false, label: 'The Brick', massAdd: 160, penetration: 5.5 };

export function freshGear() { return { sled: 0, glider: 0, thruster: 0, payload: 0 }; }
export function freshAttrs() { return { lift: 0, power: 0, mass: 0, tough: 0 }; }

/**
 * Collapse gear + attributes + secrets into the flat loadout the sim reads. Same contract as
 * ltf1.upgrades.js#loadoutFrom, which is why one physics file serves both games.
 */
export function loadoutFrom(gear, attrs, secrets, tuning, rampHeightM = 10) {
  const at = (k) => CATALOG[k].tiers[Math.max(0, Math.min(CATALOG[k].tiers.length - 1, gear[k] | 0))];
  const sled = at('sled'), thr = at('thruster'), pay = at('payload');
  let g = at('glider');
  const usingBrick = !!(secrets && secrets.brickEquipped);
  if (usingBrick) g = BRICK;

  const liftMul = 1 + (attrs.lift | 0) * ATTRIBUTES.lift.per;
  const powerMul = 1 + (attrs.power | 0) * ATTRIBUTES.power.per;
  const massMul = 1 + (attrs.mass | 0) * ATTRIBUTES.mass.per;
  const dragMul = 1 + (attrs.tough | 0) * ATTRIBUTES.tough.per;

  return {
    rampHeightM,
    exitMph: sled.exitMph,
    cd0Mul: Math.max(0.25, dragMul),
    glider: { lift: g.lift * liftMul, vMaxMph: g.vMaxMph, breakable: g.breakable !== false, label: g.label },
    thrust: thr.thrust,
    fuelSeconds: thr.seconds * powerMul,
    mass: (tuning.PENGUIN_MASS + pay.massAdd + (usingBrick ? BRICK.massAdd : 0)) * massMul,
    penetration: pay.penetration * (usingBrick ? BRICK.penetration : 1),
    blast: pay.blast,
    bomb: !!pay.bomb,
    usingBrick,
    sources: ['impact'],
  };
}

export function nextTier(gear, key) {
  const tiers = CATALOG[key].tiers;
  const lvl = gear[key] | 0;
  if (lvl >= tiers.length - 1) return null;
  return { index: lvl + 1, ...tiers[lvl + 1] };
}
