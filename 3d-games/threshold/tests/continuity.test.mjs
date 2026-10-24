// CHECK 8 — the important one.
//
// If the audio snaps when you cross the doorframe, the whole project is dead: it reads as a bug
// and as cheapness, and no amount of good filtering rescues it. So walk a scripted route in and
// out of the cabin at three speeds, sample every acoustic parameter every frame, and fail on any
// single-frame lurch. This catches the snap automatically, forever.

import { group, check, gt, lt, between } from './harness.mjs';
import { Grid } from '../src/world/grid.js';
import { VolumeGraph } from '../src/world/volumes.js';
import { buildCabin } from '../src/world/cabin.js';
import { gainToDb } from '../src/world/materials.js';
import { computeAcoustics, summarise, makeListener } from '../src/audio/model.js';
import { SmoothedAcoustics } from '../src/audio/smooth.js';
import { Weather } from '../src/weather/weather.js';

// Field → through the front door → across the room → through the nook door → into the nook.
// Routed around the nook block, because walking into a wall is not a test of anything.
const ROUTE = [
  [4.5, 1.5, -4.0],
  [4.5, 1.5, 1.5],
  [2.0, 1.5, 1.5],
  [2.0, 1.5, 3.5],
  [4.8, 1.5, 3.5],
];

const DT = 1 / 60;

function walk(scene, route, speed, smooth = null) {
  const { grid, graph, weather } = scene;
  const samples = [];

  const frameFor = pos => {
    graph.relax();
    return computeAcoustics({
      grid, graph,
      listener: makeListener(pos, 0),
      weather: { rain: weather.rain, wind: weather.windNow },
    });
  };

  if (!smooth) {
    smooth = new SmoothedAcoustics();
    smooth.prime(frameFor(route[0]));
  }
  samples.smooth = smooth;
  samples.frameFor = frameFor;

  for (let seg = 0; seg < route.length - 1; seg++) {
    const a = route[seg], b = route[seg + 1];
    const d = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
    const steps = Math.max(1, Math.ceil(d / (speed * DT)));
    for (let i = 1; i <= steps; i++) {
      const t = i / steps;
      const p = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
      const frame = frameFor(p);
      smooth.update(frame, DT);
      samples.push({
        pos: p, raw: summarise(frame), out: smooth.summarise(),
        params: smooth.params(), level: smooth.level(),
      });
    }
  }
  return samples;
}

const db = g => gainToDb(Math.max(g, 1e-4));
const oct = c => Math.log2(Math.max(c, 20));

/**
 * The worst single-frame lurch across EVERY parameter the engine applies — every gain, every
 * filter cutoff, every whistle — plus the overall level. Only parameters present in both frames
 * are compared; one appearing starts from silence and is caught by its own ramp on later frames.
 */
function worstJump(samples) {
  let worstDb = 0, worstOct = 0, atDb = null, atOct = null, whichDb = '', whichOct = '';
  const note = (d, o, key, pos) => {
    if (d > worstDb) { worstDb = d; atDb = pos; whichDb = key; }
    if (o > worstOct) { worstOct = o; atOct = pos; whichOct = key; }
  };

  // A change is only a jump if you can hear it. The roof layer rises from EXACTLY zero, so its
  // first few frames are enormous in dB (0 → 0.001 is infinite) and silent in fact. Ignore any
  // parameter while it sits more than 40 dB below its own peak — at 1% of its energy it cannot be
  // picked out of a storm. Everything above that line is held to the full 1.5 dB.
  const peak = new Map();
  for (const s of samples) {
    for (const [k, v] of s.params) peak.set(k, Math.max(peak.get(k) || 0, v.gain));
  }

  for (let i = 1; i < samples.length; i++) {
    const prev = samples[i - 1], cur = samples[i];
    for (const [key, c] of cur.params) {
      const p = prev.params.get(key);
      if (!p) continue;
      if (Math.max(p.gain, c.gain) < (peak.get(key) || 0) * 0.01) continue;
      note(Math.abs(db(c.gain) - db(p.gain)), Math.abs(oct(c.cutoff) - oct(p.cutoff)), key, cur.pos);
    }
    note(Math.abs(db(cur.level) - db(prev.level)), 0, 'overall level', cur.pos);
  }
  return { worstDb, worstOct, atDb, atOct, whichDb, whichOct };
}

const where = p => (p ? `at [${p.map(v => v.toFixed(2)).join(', ')}]` : '');

export function run() {
  const grid = new Grid();
  const { openings } = buildCabin(grid);
  const graph = new VolumeGraph(grid);
  const weather = new Weather();
  weather.snap('storm');
  const scene = { grid, graph, weather };

  // Doors open, because the walk goes through them.
  openings.door.openness = 1;
  openings.nookDoor.openness = 1;
  graph.relax();

  group('continuity — walking through the door must not snap (check 8)');

  // 15% of an octave is the spec's cutoff tolerance.
  const OCT_LIMIT = Math.log2(1.15);

  for (const [label, speed] of [['strolling (1.4 m/s)', 1.4], ['jogging (4 m/s)', 4], ['sprinting (8 m/s)', 8]]) {
    const s = walk(scene, ROUTE, speed);
    const w = worstJump(s);
    lt(`${label}: no frame jumps more than 1.5 dB`, w.worstDb, 1.5,
      `worst ${w.worstDb.toFixed(2)} dB on "${w.whichDb}" ${where(w.atDb)}`);
    lt(`${label}: no frame shifts a cutoff more than 15%`, w.worstOct, OCT_LIMIT,
      `worst ${((Math.pow(2, w.worstOct) - 1) * 100).toFixed(1)}% on "${w.whichOct}" ${where(w.atOct)}`);
  }

  // Backwards too. This direction is the one that caught the linear-smoothing bug: falling from
  // loud to quiet lands gently on its own, rising from quiet to loud does not.
  const back = walk(scene, [...ROUTE].reverse(), 8);
  const wb = worstJump(back);
  lt('sprinting back OUT is just as smooth', wb.worstDb, 1.5,
    `worst ${wb.worstDb.toFixed(2)} dB on "${wb.whichDb}" ${where(wb.atDb)}`);
  lt('...in the filters too', wb.worstOct, OCT_LIMIT,
    `worst ${((Math.pow(2, wb.worstOct) - 1) * 100).toFixed(1)}% on "${wb.whichOct}"`);

  group('continuity — the model itself is smooth, not merely smoothed over');

  // The stronger claim. If the raw target were a step function, the smoother would only be
  // hiding it — you would hear a fast fade instead of a click, which is still wrong.
  const raw = walk(scene, ROUTE, 8);
  let rawDb = 0, rawAt = null;
  for (let i = 1; i < raw.length; i++) {
    const d = Math.abs(db(raw[i].raw.storm) - db(raw[i - 1].raw.storm));
    if (d > rawDb) { rawDb = d; rawAt = raw[i].pos; }
  }
  lt('the raw, unsmoothed target never steps more than 3 dB in a frame', rawDb, 3,
    `worst ${rawDb.toFixed(2)} dB ${where(rawAt)}`);

  group('continuity — a half-open door is smooth too');

  // The nasty case: the discrete "which room am I in" answer flips at the door plane no matter how
  // open the door is, so the blend must not be gated on openness.
  openings.door.openness = 0.5;
  graph.relax();
  const half = walk(scene, ROUTE.slice(0, 3), 8);
  const wh = worstJump(half);
  lt('squeezing through a half-open door does not click', wh.worstDb, 1.5,
    `worst ${wh.worstDb.toFixed(2)} dB on "${wh.whichDb}" ${where(wh.atDb)}`);
  openings.door.openness = 1;
  graph.relax();

  group('continuity — the walk actually went somewhere');

  // A perfectly flat line would pass every check above, so prove the route really does cross from
  // a storm into shelter. NB gainToDb reports ATTENUATION, so bigger is quieter.
  // NB every door on the route is wide open, because the walk goes through them — so this is the
  // WEAKEST case, not the strongest. Most of a cabin's isolation is the door being shut, which the
  // next group measures.
  const s = walk(scene, ROUTE, 1.4);
  const first = s[0].out, last = s[s.length - 1].out;
  gt('two rooms deep, the storm is quieter even with every door wide open',
    db(last.storm) - db(first.storm), 5,
    `storm ${db(first.storm).toFixed(1)} → ${db(last.storm).toFixed(1)} dB of attenuation`);
  gt('...and duller', oct(first.stormCutoff) - oct(last.stormCutoff), 0.3);
  between('it starts fully outdoors', first.outsideWeight, 0.99, 1.0);
  lt('and ends fully indoors', last.outsideWeight, 0.01);

  let sawRoof = false;
  for (const x of s) if (x.out.roof > 0.05) sawRoof = true;
  check('somewhere along the way the roof started drumming', sawRoof);

  // Not a per-frame bound — the dB slew already guarantees that. This asserts the drumming SWELLS:
  // that it takes real time to go from inaudible to full, rather than arriving all at once.
  const peakRoof = Math.max(...s.map(x => x.out.roof));
  const firstAudible = s.findIndex(x => x.out.roof > peakRoof * 0.01);
  const nearlyFull = s.findIndex(x => x.out.roof > peakRoof * 0.9);
  gt('the drumming swells in over half a second rather than switching on',
    (nearlyFull - firstAudible) * DT, 0.5,
    `${((nearlyFull - firstAudible) * DT).toFixed(2)} s to rise`);

  group('continuity — walking in and shutting the door behind you');

  // The moment the whole project exists for. It has to be a big change AND a smooth one.
  openings.door.openness = 1;
  openings.nookDoor.openness = 1;
  graph.relax();
  const inbound = walk(scene, ROUTE.slice(0, 3), 1.4); // field → through the door → into the room
  const sm = inbound.smooth;
  const standing = ROUTE[2];

  const atDoorOpen = sm.summarise();
  openings.door.setTarget(0);
  const shutting = [];
  for (let i = 0; i < 240; i++) { // four seconds; the door takes well under one
    openings.door.update(DT);
    sm.update(inbound.frameFor(standing), DT);
    shutting.push(sm.summarise());
  }
  const atDoorShut = shutting[shutting.length - 1];

  gt('shutting the door quietens the room by more than 8 dB',
    db(atDoorShut.storm) - db(atDoorOpen.storm), 8,
    `${db(atDoorOpen.storm).toFixed(1)} → ${db(atDoorShut.storm).toFixed(1)} dB of attenuation`);
  gt('...and darkens it by more than an octave',
    oct(atDoorOpen.stormCutoff) - oct(atDoorShut.stormCutoff), 1);

  let shutJump = 0, shutOct = 0;
  for (let i = 1; i < shutting.length; i++) {
    shutJump = Math.max(shutJump, Math.abs(db(shutting[i].gain) - db(shutting[i - 1].gain)));
    shutOct = Math.max(shutOct, Math.abs(oct(shutting[i].cutoff) - oct(shutting[i - 1].cutoff)));
  }
  lt('the door closing is a sweep, not a click', shutJump, 1.5,
    `worst ${shutJump.toFixed(2)} dB per frame`);
  lt('...in the filter too', shutOct, Math.log2(1.15),
    `worst ${((Math.pow(2, shutOct) - 1) * 100).toFixed(1)}% per frame`);

  gt('and the roof is still up there drumming once the door is shut', atDoorShut.roof, 0.1);
  lt('the room settled — nothing is still drifting four seconds later',
    Math.abs(db(shutting[239].gain) - db(shutting[200].gain)), 0.2);
}
