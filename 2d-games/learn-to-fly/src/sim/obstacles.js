// LTF2 destructibles. §7.3.
//
// damage = DAMAGE_SCALE * 0.5 * mass * speed² * penetration  — kinetic energy scaled by what
// you are carrying. A bomb adds a flat blast on contact and destroys your own rig.
//
// Damage PERSISTS BETWEEN DAYS. Chipping the iceberg down over four runs is story mode's
// entire arc, so `hp` lives in the save, not in the run.

export const DAMAGE = { IMPACT: 'impact', BOMB: 'bomb' };

/** Build live obstacle objects from the data table plus whatever hp the save remembers. */
export function makeObstacles(defs, savedHp, T) {
  return defs.map((d) => {
    const w = d.widthFt / T.FT_PER_M;
    const x = d.distFt / T.FT_PER_M;
    const h = d.heightFt / T.FT_PER_M;
    const hp = savedHp && savedHp[d.id] !== undefined ? savedHp[d.id] : d.hp;
    return {
      id: d.id, name: d.name, kind: d.kind || d.id,
      maxHp: d.hp, hp,
      needs: d.needs,
      x0: x - w * 0.5, x1: x + w * 0.5,
      y0: T.SEA_LEVEL_Y, y1: T.SEA_LEVEL_Y + h,
      centreX: x, heightM: h, widthM: w,
      destroyed: hp <= 0,
      hitThisRun: false,
    };
  });
}

/** Serialise just the persistent part. */
export function obstacleHp(obstacles) {
  const out = {};
  for (const o of obstacles) out[o.id] = o.hp;
  return out;
}

export function damageFrom(speed, L, T) {
  return T.DAMAGE_SCALE * 0.5 * L.mass * speed * speed * L.penetration;
}

/**
 * Apply one impact. Mutates the obstacle and the penguin.
 * @returns {{dealt:number, destroyed:boolean, blocked:boolean}}
 */
export function applyHit(o, p, L, T, detonate) {
  const speed = Math.hypot(p.vx, p.vy);
  const kinetic = damageFrom(speed, L, T);
  const accepts = (src) => o.needs.includes(src);

  let dealt = 0;
  if (accepts(DAMAGE.IMPACT)) dealt += kinetic;
  if (detonate && accepts(DAMAGE.BOMB)) dealt += L.blast;

  const blocked = dealt <= 0;
  o.hp = Math.max(0, o.hp - dealt);
  o.hitThisRun = true;
  const destroyed = o.hp <= 0 && !o.destroyed;
  if (destroyed) o.destroyed = true;
  return { dealt, destroyed, blocked, speed };
}

/** 0 = pristine, 1 = cracked, 2 = crumbling. Drives the three damage-state sprites (§8.5). */
export function damageStage(o, T) {
  const frac = o.maxHp <= 0 ? 0 : o.hp / o.maxHp;
  if (frac > T.CRACK_STAGE_1) return 0;
  if (frac > T.CRACK_STAGE_2) return 1;
  return 2;
}
