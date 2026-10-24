/* ============================================================================
   HOW MUCH OF A WORLD IS COVERED, AND BY WHICH REGION — CPU only, no GPU.

   "Patches of forest" is a claim about a DISTRIBUTION, and a single ground shot
   cannot test it: land in a clearing and the forest looks broken, land in a
   stand and the clearings look absent. This samples the whole sphere.
   ========================================================================= */
import { makePlanetDef } from '../src/world/planetDef.js';
import { biomeAt } from '../src/world/biome.js';
import { heightNorm, seaNorm } from '../src/world/heightField.js';
import { OBJECT_KINDS } from '../src/world/objectKinds.js';

const N = 20000;
console.log('\n=== biome cover, 20k points per world ===\n');
console.log('  world       land%   bare%  thin%  full%   regionA/B      mean obj/km2');
console.log('  ' + '-'.repeat(76));

const b = { cover: 0, region: 0 };
let worst = null;
for (const key of Object.keys(OBJECT_KINDS)) {
  const def = makePlanetDef('VESPER-1', key);
  const spec = OBJECT_KINDS[key], sea = seaNorm(def);
  let land = 0, bare = 0, thin = 0, full = 0, ra = 0, dens = 0;
  const ga = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < N; i++) {
    const y = 1 - (i / (N - 1)) * 2, r = Math.sqrt(Math.max(0, 1 - y * y)), a = ga * i;
    const d = [Math.cos(a) * r, y, Math.sin(a) * r];
    if (heightNorm(def, d) <= sea) continue;
    land++;
    biomeAt(def, d, b);
    if (b.cover <= 0.002) bare++;
    else if (b.cover < 0.5) thin++;
    else full++;
    if (b.region === 0) ra++;
    dens += b.cover * (b.region === 0 ? spec.perKm2 : spec.alt.perKm2);
  }
  const pc = (x) => ((x / Math.max(land, 1)) * 100).toFixed(0).padStart(5);
  console.log(`  ${key.padEnd(11)}${((land / N) * 100).toFixed(0).padStart(5)}` +
    `${pc(bare)}  ${pc(thin)}  ${pc(full)}   ${((ra / Math.max(land,1))*100).toFixed(0).padStart(3)}/${(100-(ra/Math.max(land,1))*100).toFixed(0).padEnd(3)}` +
    `      ${(dens / Math.max(land, 1)).toFixed(0).padStart(5)}`);
  const bareFrac = bare / Math.max(land, 1);
  if (!worst || bareFrac > worst.f) worst = { key, f: bareFrac };
}
console.log(`\n  bare = nothing grows there at all. Some is the POINT (a forest needs an`);
console.log(`  edge); too much and the world reads as empty. Worst: ${worst.key} at ` +
  `${(worst.f * 100).toFixed(0)}% bare.\n`);
