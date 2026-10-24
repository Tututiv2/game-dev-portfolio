// Every number the fight runs on. Pure.
//
// The two player tables are the whole story in numbers: the weak one is
// deliberately bad (slow swing, a goblin takes three hits, no dash), because
// the Awakening only feels like anything if the minute before it felt like this.

export const PLAYER = {
  maxHp: 100,
  weak: {
    speed: 3.9, sprint: 5.4, dmg: 1, swing: 0.62, hitAt: 0.34, combo: 2,
    reach: 2.3, arc: 1.2, dash: false, parryStun: 0.45, auraGain: 0,
  },
  awakened: {
    speed: 7.2, sprint: 10.5, dmg: 2, swing: 0.24, hitAt: 0.09, combo: 3,
    reach: 3.1, arc: 1.5, dash: true, dashDist: 6.5, dashTime: 0.18, iframes: 0.26,
    parryStun: 1.6, auraGain: 26,
  },
};

export const GOBLIN = { hp: 3, dmg: 13, reach: 1.9, windup: 0.55, recover: 0.7, speed: 3.6, radius: 0.45 };
export const BRUTE = { hp: 64, dmg: 24, radius: 1.5, speed: 2.6 };

// Weak phase script. You cannot die before the Awakening; you are knocked down.
export const WEAK = {
  hpFloor: 14,        // hp never drops below this before the Awakening
  downAt: 34,         // at or below this, you go down
  forceDownAfter: 55, // seconds of fighting; a flawless player still goes down
  goblinsAlive: 3,    // keep this many on you
};

export const AURA = {
  max: 100,
  perHit: 3,
  perPerfect: 26,
  releaseDmg: 14,
  counterMult: 3,     // hits during a perfect-parry stagger
};

// Ogre attacks are written in BEATS, not seconds, so the song decides when they land.
// windup = beats of telegraph before the first impact. impacts = beat offsets.
export const OGRE_ATTACKS = {
  slam:   { windup: 2, impacts: [0], reach: 5.6, arc: 0.75, dmg: 26 },
  sweep:  { windup: 2, impacts: [0], reach: 5.2, arc: 2.2, dmg: 22 },
  quick:  { windup: 1, impacts: [0], reach: 4.8, arc: 2.2, dmg: 16 },          // a backhand: one beat of warning
  delay:  { windup: 3, impacts: [0], reach: 5.6, arc: 0.75, dmg: 28, hold: true }, // winds up, then HOLDS a beat
  double: { windup: 2, impacts: [0, 1], reach: 5.2, arc: 1.6, dmg: 18 },
  stomp:  { windup: 2, impacts: [0], reach: 7.5, arc: 6.3, dmg: 20, unparryable: true }, // jump it
  triple: { windup: 4, impacts: [0, 1, 2], reach: 5.6, arc: 1.8, dmg: 20 }, // saved for the drop
};

// Where each strike animation's blade actually CONNECTS, measured on the real rig by
// tests/swing_sync.mjs (fitted at 81.9 and 130 BPM): contact = c * strikeDuration + e.
// The strike starts that much BEFORE the beat so the contact frame lands ON it. Without this
// every blow was drawn 69-101 ms late -- "the monster swings aren't timed with the beat".
export const STRIKE = { dur: 0.9, slam: { c: 0.049, e: 0.0564 }, sweep: { c: 0.118, e: 0.0197 } };
export function strikeLead(kind, beat) { const k = STRIKE[kind]; return k.c * STRIKE.dur * beat + k.e; }

// A shuffled deck of moves, so he never throws the same thing twice running.
export const OGRE_DECK = ['slam', 'sweep', 'sweep', 'quick', 'delay', 'double', 'double', 'stomp'];
export function drawAttack(bag, last, rnd) {
  if (!bag.length) {
    bag.push(...OGRE_DECK);
    for (let i = bag.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [bag[i], bag[j]] = [bag[j], bag[i]]; }
  }
  let k = bag.length - 1;
  if (bag[k] === last && bag.length > 1) { const j = Math.floor(rnd() * (bag.length - 1)); [bag[k], bag[j]] = [bag[j], bag[k]]; }
  if (bag[k] === last) { bag.push(...OGRE_DECK.filter((x) => x !== last).slice(0, 1)); k = bag.length - 1; }
  return bag.pop();
}

// Place an attack on the grid: first impact on the first beat that still leaves
// a full windup from `now`. Returns absolute times on the grid's clock.
export function scheduleAttack(kind, grid, now, notBefore = 0) {
  const a = OGRE_ATTACKS[kind];
  const earliest = Math.max(now + a.windup * grid.beat, notBefore);
  const first = grid.next(earliest);
  return {
    kind,
    start: first - a.windup * grid.beat,
    impacts: a.impacts.map((b) => first + b * grid.beat),
    reach: a.reach, arc: a.arc, dmg: a.dmg,
  };
}

// Same, but pinned so the first impact is exactly `at` (used for the drop combo).
export function scheduleAttackAt(kind, grid, at) {
  const a = OGRE_ATTACKS[kind];
  return {
    kind,
    start: at - a.windup * grid.beat,
    impacts: a.impacts.map((b) => at + b * grid.beat),
    reach: a.reach, arc: a.arc, dmg: a.dmg,
  };
}

export function damagePlayer(hp, dmg, phase) {
  const floor = phase === 'weak' ? WEAK.hpFloor : 0;
  return Math.max(floor, hp - dmg);
}

// Is `target` inside an attack cone from `origin` facing `yaw`?
// Positions are {x, z}; yaw 0 faces +z.
export function inArc(origin, yaw, target, reach, arc, targetRadius = 0) {
  const dx = target.x - origin.x, dz = target.z - origin.z;
  const dist = Math.hypot(dx, dz);
  if (dist - targetRadius > reach) return false;
  if (dist < 0.001) return true;
  let d = Math.atan2(dx, dz) - yaw;
  while (d > Math.PI) d -= Math.PI * 2;
  while (d < -Math.PI) d += Math.PI * 2;
  // Big bodies are hit when any part of them is in the cone, not just the centre.
  const slack = targetRadius > 0 ? Math.atan2(targetRadius, dist) : 0;
  return Math.abs(d) <= arc / 2 + slack;
}

export function rank({ perfects, blocks, hitsTaken, seconds, maxChain }) {
  let s = perfects * 10 + maxChain * 12 + blocks * 2 - hitsTaken * 8 - Math.max(0, seconds - 120) * 0.15;
  if (s >= 150) return 'SS';
  if (s >= 105) return 'S';
  if (s >= 70) return 'A';
  if (s >= 40) return 'B';
  return 'C';
}
