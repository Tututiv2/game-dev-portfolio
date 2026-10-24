// Does the ogre's blade VISIBLY land on the beat? Plays the real rig through the exact timing
// game.js uses (windup until strikeAt, strike from strikeAt over 0.9 beat) and finds the frame the
// blade actually connects. Headless: three.js scene graph + actors.js, no WebGL.
//
// The owner's report: "the monster swings aren't timed with the beat so i cant time it".
// Cause: the strike STARTED on the beat and its contact frame sits 18-24% into it, behind easing,
// so the blade was drawn landing ~110 ms after the beat everyone was being graded against.
import { pathToFileURL } from 'node:url';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'web');
const THREE = await import(pathToFileURL(resolve(WEB, 'vendor/three/three.module.js')).href);
const { makeOgre } = await import(pathToFileURL(resolve(WEB, 'actors.js')).href);
const { STRIKE, strikeLead } = await import(pathToFileURL(resolve(WEB, 'sim/combat.js')).href);

export function measure(kind, dir, bpm, { leadOn = true } = {}) {
  const ogre = makeOgre();
  const beat = 60 / bpm, strikeDur = beat * STRIKE.dur;
  const imp = 4.0;                                     // the beat the blow belongs to
  const start = imp - 2 * beat;                        // a 2-beat windup
  const lead = leadOn ? strikeLead(kind, beat) : 0;
  const strikeAt = imp - lead;
  const dt = 1 / 240;
  const tip = new THREE.Vector3(), root = ogre.root;
  root.updateMatrixWorld(true);
  const samples = [];
  for (let t = start - 0.5; t < imp + beat; t += dt) {
    let action = 'idle', k = 0;
    if (t >= start && t < strikeAt) { action = 'windup'; k = Math.min(1, (t - start) / (strikeAt - start)); }
    else if (t >= strikeAt) { action = kind; k = Math.min(1, (t - strikeAt) / strikeDur); }
    ogre.pose(dt, { action, t: k, move: 0, dir, air: false });
    root.updateMatrixWorld(true);
    ogre.sockets.cleaverTip.getWorldPosition(tip);
    samples.push({ t, x: tip.x, y: tip.y, z: tip.z });
  }
  // contact: slam = the blade's lowest point; sweep = it crosses the ogre's front centre line
  const after = samples.filter((s) => s.t >= strikeAt - 0.05 && s.t <= imp + beat * 0.8);
  let hit = null;
  if (kind === 'slam') {
    // NOT the lowest point: the idle pose hangs the cleaver lower than the slam does, so "lowest"
    // measured him relaxing afterwards. Contact = first arrival at the slam's held position
    // (the plateau half-way through the strike).
    const mid = samples.reduce((p, q) => (Math.abs(q.t - (strikeAt + strikeDur * 0.45)) < Math.abs(p.t - (strikeAt + strikeDur * 0.45)) ? q : p));
    hit = after.find((s) => s.t >= strikeAt && Math.hypot(s.x - mid.x, s.y - mid.y, s.z - mid.z) < 0.25) || mid;
  } else {
    for (let i = 1; i < after.length; i++) {
      if (Math.sign(after[i].x) !== Math.sign(after[i - 1].x) && after[i].z > 0.5) { hit = after[i]; break; }
    }
    if (!hit) hit = after.reduce((p, q) => (Math.abs(q.x) < Math.abs(p.x) && q.z > 0.5 ? q : p));
  }
  return { kind, dir, bpm, offsetMs: (hit.t - imp) * 1000 };
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  // Fit contact = c * strikeDur + e from the un-led offsets at two tempos.
  for (const [kind, dir] of [['slam', 'over'], ['sweep', 'left']]) {
    const a = measure(kind, dir, 81.9, { leadOn: false }), b = measure(kind, dir, 130, { leadOn: false });
    const da = 60 / 81.9 * 0.9, db = 60 / 130 * 0.9;
    const c = (a.offsetMs - b.offsetMs) / 1000 / (da - db), e = a.offsetMs / 1000 - c * da;
    console.log(`fit ${kind}: contact ${c.toFixed(3)} of the strike + ${(e * 1000).toFixed(1)} ms of easing`);
  }
  for (const bpm of [81.9, 130]) {
    for (const [kind, dir] of [['slam', 'over'], ['sweep', 'left'], ['sweep', 'right']]) {
      const before = measure(kind, dir, bpm, { leadOn: false });
      const now = measure(kind, dir, bpm);
      console.log(`${bpm} BPM ${kind} ${dir}: blade lands ${before.offsetMs.toFixed(0)} ms after the beat before the fix, ${now.offsetMs.toFixed(0)} ms now`);
    }
  }
}
