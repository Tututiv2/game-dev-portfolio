// Solver tests — BUILD_PROMPT §8, checks 9/10/11, plus the parameter-domain half of check 5.
// Pure logic, no rendering and no audio, so this runs headless in node.

import { group, check, eq, near, gt, lt, between } from './harness.mjs';
import { Grid } from '../src/world/grid.js';
import { VolumeGraph, roofAbove, OUTSIDE_ID } from '../src/world/volumes.js';
import { buildCabin, PROBES } from '../src/world/cabin.js';
import { THATCH, AIR, gainToDb } from '../src/world/materials.js';

export function run() {
  const grid = new Grid();
  const { openings } = buildCabin(grid);
  const g = new VolumeGraph(grid);

  const at = p => g.volumeAt(p[0], p[1], p[2]);

  // ---------------------------------------------------------------- geometry
  group('solver — the cabin is a room');

  const outside = at(PROBES.outside);
  const main = at(PROBES.mainRoom);
  const nook = at(PROBES.nook);

  check('a point in the field classifies as outdoors', outside.outside);
  check('the main room is enclosed', main.enclosed && !main.outside);
  check('the nook is enclosed', nook.enclosed && !nook.outside);
  check('the nook is a DIFFERENT volume from the main room', nook.id !== main.id);
  check('the window seat is the main room', at(PROBES.byWindow).id === main.id);

  // Standing ON the threshold: the door cell blocks the fill by design, so volumeAt is null there.
  // That is the one spot in the world where a null would be worst, hence resolveVolume.
  check('a door cell has no volume of its own', at(PROBES.doorway) === null);
  const stood = g.resolveVolume(...PROBES.doorway);
  check('...but standing in the doorway still resolves to somewhere', !!stood);
  check('...to the main room or outdoors, not to nothing',
    stood.id === main.id || stood.outside);
  check('just inside the door is the main room', at(PROBES.justInside).id === main.id);

  // Interior is 8×6×3 = 144, less the nook block (4×4×3) and the hearth (1×2×2).
  between('main room volume is about 92 m³', main.volume, 88, 96);
  eq('the nook is 2×2×2', nook.volume, 8);

  // -------------------------------------------------------- check 10: apertures
  group('solver — aperture enumeration (check 10)');

  const mainOps = main.apertures.map(a => a.opening.name).sort();
  eq('the main room finds exactly its three openings + the nook door', mainOps.join(','),
    ['front door', 'north window', 'nook door', 'west window'].sort().join(','));

  const nookOps = nook.apertures.map(a => a.opening.name);
  eq('the nook finds exactly one opening', nookOps.length, 1);
  eq('...and it is the nook door', nookOps[0], 'nook door');

  const doorAp = main.apertures.find(a => a.opening.name === 'front door');
  eq('the front door leads outdoors', doorAp.otherId, OUTSIDE_ID);
  near('the front door centroid sits in the south wall', doorAp.opening.center[2], 0.5, 0.01);
  near('...centred on x=4.5', doorAp.opening.center[0], 4.5, 0.01);
  eq('the front door is 2 m² of hole', doorAp.opening.area, 2);

  const westAp = main.apertures.find(a => a.opening.name === 'west window');
  eq('the west window leads outdoors', westAp.otherId, OUTSIDE_ID);
  near('the west window is due west (x=0.5)', westAp.opening.center[0], 0.5, 0.01);

  const nookAp = nook.apertures[0];
  eq('the nook door leads to the main room, NOT outdoors', nookAp.otherId, main.id);

  // An opening must actually be a HOLE. The acoustics cannot tell — isSolid() consults the
  // openings map and says "yes, blocked" whether or not the block was ever cleared — so this went
  // unnoticed until the renderer drew a solid plank across every window in the cabin.
  for (const op of grid.openingList) {
    for (const [x, y, z] of op.cells) {
      eq(`the ${op.name} is a hole in the wall, not a block`, grid.get(x, y, z), AIR);
      check(`...but still blocks a flood fill`, grid.isSolid(x, y, z));
    }
  }

  // ------------------------------------------------- check 9: seal it, break it, fix it
  group('solver — sealing (check 9)');

  g.relax();
  check('shut up tight, the main room is sealed', g.isSealed(main));

  openings.windowWest.openness = 0.4;
  g.relax();
  check('cracking the west window unseals it', !g.isSealed(at(PROBES.mainRoom)));
  openings.windowWest.openness = 0;
  g.relax();
  check('shutting it again re-seals it', g.isSealed(at(PROBES.mainRoom)));

  // NB: (2,4,3) not (4,4,3) — the latter sits above the nook's OWN ceiling, so pulling it opens
  // nothing and the check would pass while proving nothing.
  grid.set(2, 4, 3, 0); // punch a hole in the roof, over open floor
  const holed = at(PROBES.mainRoom);
  check('removing one roof block makes the room read as outdoors', holed.outside);
  grid.set(2, 4, 3, THATCH);
  const fixed = at(PROBES.mainRoom);
  check('replacing it makes it a room again', fixed.enclosed && !fixed.outside);
  check('...and it is sealed once more', g.relax().isSealed(fixed));

  // ------------------------------------------------ check 5 (parameter domain)
  group('solver — two walls beat one (check 5)');

  const m2 = at(PROBES.mainRoom);
  const n2 = at(PROBES.nook);
  g.relax();

  const mainDb = gainToDb(m2.transmission.gain);
  const nookDb = gainToDb(n2.transmission.gain);
  gt('the nook is quieter than the main room', nookDb - mainDb, 6,
    `main ${mainDb.toFixed(1)} dB, nook ${nookDb.toFixed(1)} dB`);
  lt('the nook is also duller (lower cutoff)', n2.transmission.cutoff, m2.transmission.cutoff);
  between('main room sits 16–30 dB down from outdoors', mainDb, 16, 30);
  gt('nook sits at least 28 dB down', nookDb, 28);

  // The floor must not leak. If the probe punched through the boards into open air below, the
  // main room would have a suspiciously bright path to outdoors.
  lt('no path leaks up through the floorboards', m2.transmission.cutoff, 1100);

  // --------------------------------------------------------------- apertures open up
  group('solver — opening things changes the numbers');

  // NB `ambient` is the room's REVERBERANT AVERAGE — the whole room's worth of outside sound — so
  // a cracked window moves it by a decibel or two, not by a lot. The dramatic change is near-field
  // and lives on the emitter at the window itself; acoustics.test.mjs asserts that separately.
  const shutGain = m2.ambient.gain;
  openings.windowWest.openness = 0.1;
  g.relax();
  const crackGain = at(PROBES.mainRoom).ambient.gain;
  gt('cracking the window 10% lifts the whole room a little',
    gainToDb(shutGain) - gainToDb(crackGain), 1.2,
    `shut ${gainToDb(shutGain).toFixed(1)} dB, cracked ${gainToDb(crackGain).toFixed(1)} dB`);

  openings.door.openness = 1;
  g.relax();
  const openDoorGain = at(PROBES.mainRoom).ambient.gain;
  gt('throwing the door wide raises it further', gainToDb(crackGain) - gainToDb(openDoorGain), 3);

  // The nook only ever hears the main room, so it must follow it.
  const nookOpen = at(PROBES.nook).ambient.gain;
  gt('the nook gets louder when the front door opens', nookOpen, 0);
  lt('...but is still far quieter than the main room', nookOpen, openDoorGain * 0.5);

  openings.windowWest.openness = 0;
  openings.door.openness = 0;
  g.relax();

  // ---------------------------------------------------------------------- RT60
  group('solver — reverb from the solve');

  const rtMain = at(PROBES.mainRoom).rt60;
  const rtNook = at(PROBES.nook).rt60;
  between('the cabin has a short, warm tail (0.2–0.6 s)', rtMain, 0.2, 0.6);
  lt('the nook is tighter and deader than the cabin', rtNook, rtMain);
  between('the nook tail is 0.05–0.3 s', rtNook, 0.05, 0.3);

  openings.windowWest.openness = 1;
  g.relax();
  lt('opening a window DRIES the room (sound leaves and never comes back)',
    at(PROBES.mainRoom).rt60, rtMain);
  openings.windowWest.openness = 0;
  g.relax();

  // ----------------------------------------------------------------- roof query
  group('solver — the roof query (feeds the drumming layer)');

  const rIn = roofAbove(grid, 2, 1.5, 3);
  const rOut = roofAbove(grid, 4.5, 1.5, -5);
  check('there is roof above you in the main room', rIn.hit && rIn.sky);
  eq('...and it is thatch', rIn.material, THATCH);
  check('there is no roof above you in the field', !rOut.hit);

  const rNook = roofAbove(grid, 4.5, 1.5, 3.5);
  check('the nook has a ceiling', rNook.hit);
  check('...which is NOT the sky-facing roof', !rNook.sky);

  // ------------------------------------------------------- check 11: cost budget
  group('solver — cost (check 11)');

  const g2 = new VolumeGraph(grid);
  const t0 = performance.now();
  g2.volumeAt(...PROBES.mainRoom);
  g2.volumeAt(...PROBES.nook);
  g2.volumeAt(...PROBES.outside);
  g2.relax();
  const cold = performance.now() - t0;
  lt('a cold full solve costs under 40 ms', cold, 40, `${cold.toFixed(2)} ms`);
  lt('...and needed at most 4 fills', g2.solveCount, 5);

  const t1 = performance.now();
  for (let i = 0; i < 2000; i++) g2.volumeAt(2, 1.5, 3);
  const warm = performance.now() - t1;
  lt('2000 warm lookups cost under 10 ms', warm, 10, `${warm.toFixed(2)} ms`);

  openings.door.openness = 1;
  const t2 = performance.now();
  for (let i = 0; i < 200; i++) g2.relax();
  const relaxT = (performance.now() - t2) / 200;
  lt('a relax (runs whenever a door moves) costs under 0.5 ms', relaxT, 0.5,
    `${relaxT.toFixed(3)} ms`);
  openings.door.openness = 0;

  // Doors moving must never trigger a geometry re-solve.
  const before = g2.solveCount;
  openings.door.openness = 0.5;
  g2.relax();
  g2.volumeAt(2, 1.5, 3);
  eq('animating a door does NOT re-fill the world', g2.solveCount, before);
  openings.door.openness = 0;
}
