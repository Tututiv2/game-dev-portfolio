/* ============================================================================
   THE ROSTER IS DATA, SO CHECK IT LIKE DATA. No GPU, no browser.

   surfaceObjects.js resolves an object's colour as
   `def.art[spec.colFrom] || def.art.mid` — so a TYPO IN colFrom DOES NOT THROW.
   It silently paints that object in the world's mid tone, which looks like a
   slightly-off art choice rather than a bug, and would survive every visual
   test we have. 26 field names hand-written across 13 worlds is exactly the
   population where one of them is wrong.
   ========================================================================= */
import { growFromCode, isGenome } from '../src/render/treeGeom.js';
import { OBJECT_KINDS } from '../src/world/objectKinds.js';
import { ARCHETYPES, ARCH_KEYS } from '../src/world/archetypes.js';
/* IMPORTED, NEVER RETYPED. These two constants set what "too dense" means
   below, and a copy of them here would go stale the first time either lattice
   is retuned — leaving the harness quietly enforcing last month's limit. */
import { CELL_M, SCAT_CELL_M } from '../src/world/surfaceObjects.js';

const GEOM_FAMILIES = ['spire','column','chimney','stack','hoodoo','shard',
                       'tree','snag','stalk','boulder','spine'];
/* Each tier has its OWN lattice and its own per-cell clip, so it needs its own
   bounds: clutter runs two orders denser than a stand by design, and checking
   it against the stand's 1000/km2 ceiling would fail every world for being
   exactly what it is meant to be. [cell metres, per-cell clip, max perKm2] */
const TIERS = {
  primary: [CELL_M, 6, 1000],
  alt:     [CELL_M, 6, 1000],
  scatter: [SCAT_CELL_M, 8, 60000],
};
let fails = 0;
const grown = [];
const check = (ok, m) => { if (!ok) { console.log('  FAIL  ' + m); fails++; } };

let n = 0;
for (const [key, entry] of Object.entries(OBJECT_KINDS)) {
  const arch = ARCHETYPES[key];
  check(!!arch, `${key}: no such archetype`);
  if (!arch) continue;
  for (const [which, spec] of [['primary', entry], ['alt', entry.alt],
                               ['scatter', entry.scatter]]) {
    if (!spec) { check(which === 'primary', `${key}: no ${which} roster`); continue; }
    n++;
    const tag = `${key}.${which}`;
    const [cellM, clip, maxDens] = TIERS[which];
    // THE ONE THIS FILE EXISTS FOR
    check(arch[spec.colFrom] !== undefined,
      `${tag}: colFrom '${spec.colFrom}' is not a field on that archetype ` +
      `(it would silently fall back to mid)`);
    /* SAME TRAP, SECOND FIELD. colTipFrom and colGlowFrom fall back to the base
       colour when the name is wrong, so a typo renders as a one-tone object —
       which looks like an art choice, not a bug, and is exactly what this file
       exists to catch. Optional, so only checked when present. */
    for (const f of ['colTipFrom', 'colGlowFrom']) {
      check(spec[f] === undefined || arch[spec[f]] !== undefined,
        `${tag}: ${f} '${spec[f]}' is not a field on that archetype ` +
        `(it would silently fall back to the base colour)`);
    }
    /* A kind is EITHER a profile family OR a 68-character genome grown by
       src/render/treeGeom.js. A genome is checked harder than a family name,
       because a name only has to exist while a genome has to GROW: it must
       decode, stay inside the triangle ceiling, and come out normalised, or the
       thing standing on the planet is not the thing that was picked. */
    if (isGenome(spec.kind)) {
      const g = growFromCode(spec.kind);
      check(!!g, `${tag}: genome does not decode`);
      if (g) {
        const pos = g.attributes.position;
        const tris = pos.count / 3;
        let minY = 1e9, maxY = -1e9, maxR = 0;
        for (let i = 0; i < pos.count; i++) {
          const y = pos.getY(i);
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
          const r = Math.hypot(pos.getX(i), pos.getZ(i));
          if (r > maxR) maxR = r;
        }
        check(tris <= 400, `${tag}: ${tris} triangles, over the 400 ceiling`);
        check(Math.abs(minY) < 1e-4 && Math.abs(maxY - 1) < 1e-4 && Math.abs(maxR - 1) < 0.02,
          `${tag}: genome not normalised (y ${minY.toFixed(3)}-${maxY.toFixed(3)}, r ${maxR.toFixed(3)})`);
        check(!!g.attributes.aPart, `${tag}: genome has no aPart attribute`);
        grown.push(`${tag} ${tris}t`);
      }
    } else {
      check(GEOM_FAMILIES.includes(spec.kind),
        `${tag}: kind '${spec.kind}' has no geometry family`);
    }
    check(spec.hMin > 0 && spec.hMax > spec.hMin, `${tag}: bad height range`);
    check(spec.perKm2 > 0 && spec.perKm2 <= maxDens,
      `${tag}: perKm2 ${spec.perKm2} out of range (max ${maxDens} for this tier)`);
    check(spec.clumping >= 0 && spec.clumping <= 1, `${tag}: clumping out of 0..1`);
    check(spec.sizeBias >= 0 && spec.sizeBias <= 1, `${tag}: sizeBias out of 0..1`);
    check(spec.emissive >= 0 && spec.emissive <= 1, `${tag}: emissive out of 0..1`);
    check(spec.tilt >= 0 && spec.tilt < 1.4, `${tag}: tilt out of range`);
    /* The lattice clips at a fixed count per cell. Past that, density is
       silently truncated and the roster is lying about how dense it is. */
    const perCell = spec.perKm2 * (cellM * cellM) / 1e6
                  * (1 - spec.clumping + spec.clumping * 2.2);
    check(perCell <= clip,
      `${tag}: peak ${perCell.toFixed(1)} objects/cell exceeds the clip of ${clip}`);
  }
  check(Array.isArray(entry.regions) && entry.regions.length === 2,
    `${key}: needs two region names`);
  check(!entry.alt || entry.alt.colFrom !== entry.colFrom,
    `${key}: alt shares colFrom '${entry.colFrom}' with the primary — the two ` +
    `regions will read as one palette`);
}
// every solid archetype must have a roster; gas must not
for (const k of ARCH_KEYS) {
  if (k === 'gas') check(!OBJECT_KINDS[k], 'gas must have no roster (no surface)');
  else check(!!OBJECT_KINDS[k], `${k}: has no surface objects at all`);
}
console.log(`\n  ${n} rosters across ${Object.keys(OBJECT_KINDS).length} worlds`);
console.log(fails ? `\n  ${fails} CHECK(S) FAILED\n` : '\n  roster is consistent\n');
process.exit(fails ? 1 : 0);
