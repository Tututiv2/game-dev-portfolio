// §7.3 — the ORDER is VERIFIED (snowman → snow mound → rocky hill → iceberg → the wall) and
// `heightFt: 600` for the wall is VERIFIED, because the I.C.B.P. medal is awarded for flying
// OVER a 600-foot wall — which is both a hard number and a promise that going over it must be
// possible. Test 16 flies a maxed rig over it to prove the collision box kept that promise.
//
// Everything else here is CALIBRATE. `widthFt` is not in any source; it is sized so each
// obstacle reads at the scale of its name.

export const OBSTACLES = [
  { id: 'snowman', name: 'Snowman',    kind: 'snowman', distFt:  180, widthFt:  10, heightFt:  12, hp:    400, needs: ['impact'] },
  { id: 'mound',   name: 'Snow Mound', kind: 'mound',   distFt:  650, widthFt:  46, heightFt:  30, hp:   1800, needs: ['impact'] },
  { id: 'hill',    name: 'Rocky Hill', kind: 'hill',    distFt: 1600, widthFt: 130, heightFt:  90, hp:   7000, needs: ['impact'] },
  { id: 'iceberg', name: 'Iceberg',    kind: 'iceberg', distFt: 3400, widthFt: 180, heightFt: 260, hp:  26000, needs: ['impact', 'bomb'] },
  { id: 'wall',    name: 'The Wall',   kind: 'wall',    distFt: 6000, widthFt:  70, heightFt: 600, hp: 120000, needs: ['bomb'] },
];

/** Where the snow stops and the ocean starts, in feet. Past the wall — §14 lives out here. */
export const FIELD_END_FT = 7200;

export const OBSTACLE_ORDER = OBSTACLES.map((o) => o.id);
