// Headless verification. Open  http://localhost:5490/?test  to run it.
//
// The claim this game rests on is "one lever sets your income AND your
// difficulty". That claim is falsifiable: run identical policies at different
// throttle settings and the survival curve should NOT be monotonic. If holding
// the lever wide open is simply best, the design is broken and no amount of
// playing it by hand would tell me as fast as this does.
import { CFG } from './config.js';
import { createWorld, stepSim, autoBuy, NULL_CTRL } from './sim.js';
import { ETYPE } from './swarm.js';
import { setRng, mulberry32 } from './util.js';

// 30Hz for tests: the sim is stable at it, and it halves an already expensive
// sweep. The real game still runs at whatever the display gives it.
const DT = 1 / 30;
const SWEEP = [0.15, 0.45, 0.75, 1.0];
const MAX_SEC = 240;
const TRIALS = 3;          // one sample per throttle flipped the verdict between
const SEEDS = [1337, 90210, 4242];   // identical builds. Three seeded runs do not.

/**
 * @param throttle  target lever position, 0..1
 * @param maxSec    give up after this much simulated time
 * @param buy       mount weapons as grain allows
 * @param repair    spend grain on hull when hurt
 */
export function run({ throttle = 1, maxSec = MAX_SEC, buy = true, repair = true, steer = 0, seed = null } = {}) {
  if (seed !== null) setRng(mulberry32(seed));
  const w = createWorld({});
  const ctrl = { ...NULL_CTRL, throttleTarget: throttle, left: steer < 0, right: steer > 0 };

  let t = 0, buyTimer = 0, peakLive = 0, lowHull = CFG.hv.hullMax;
  const t0 = (typeof performance !== 'undefined' ? performance.now() : Date.now());

  while (t < maxSec && w.hv.hull > 0) {
    ctrl.repair = repair && w.hv.hull < CFG.hv.hullMax * 0.85 && w.grain > 120;
    stepSim(w, DT, ctrl);
    t += DT;

    buyTimer -= DT;
    if (buy && buyTimer <= 0) { if (!autoBuy(w)) buyTimer = 1.0; else buyTimer = 0.35; }

    if (w.swarm.live > peakLive) peakLive = w.swarm.live;
    if (w.hv.hull < lowHull) lowHull = w.hv.hull;
  }

  if (seed !== null) setRng(null);

  return {
    throttle,
    survivedSec: +t.toFixed(1),
    died: w.hv.hull <= 0,
    metres: Math.round(w.hv.dist),
    grainCut: Math.round(w.harvested),
    killed: w.killed,
    towers: w.towers.mounted.filter(Boolean).length,
    peakLive,
    lowHull: Math.round(lowHull),
    finalCounts: [...w.swarm.counts],
    wallMs: Math.round((typeof performance !== 'undefined' ? performance.now() : Date.now()) - t0),
  };
}

/** TRIALS seeded runs at one throttle, averaged. */
export function runAvg(throttle) {
  const rs = SEEDS.slice(0, TRIALS).map((seed) => run({ throttle, seed }));
  const avg = (f) => rs.reduce((s, r) => s + f(r), 0) / rs.length;
  return {
    throttle,
    trials: rs.length,
    survivedSec: +avg((r) => r.survivedSec).toFixed(1),
    deaths: rs.filter((r) => r.died).length,
    died: rs.every((r) => r.died),
    metres: Math.round(avg((r) => r.metres)),
    metresSpread: Math.max(...rs.map((r) => r.metres)) - Math.min(...rs.map((r) => r.metres)),
    grainCut: Math.round(avg((r) => r.grainCut)),
    killed: Math.round(avg((r) => r.killed)),
    towers: Math.round(avg((r) => r.towers)),
    peakLive: Math.round(avg((r) => r.peakLive)),
    lowHull: Math.round(avg((r) => r.lowHull)),
    runs: rs,
  };
}

/** Invariants that must hold no matter how the numbers get tuned. */
export function checks() {
  const out = [];
  const ok = (name, pass, detail) => out.push({ name, pass, detail });

  // 1. The swarm must be able to catch you. If it cannot, throttle buys safety.
  ok('husks outrun the Gleaner',
     ETYPE[0].spd > CFG.hv.maxSpeed,
     `husk ${ETYPE[0].spd} vs Gleaner ${CFG.hv.maxSpeed}`);

  // One sweep answers every remaining question — no point running the ends twice.
  const sweep = SWEEP.map(runAvg);
  const quiet = sweep[0];
  const loud = sweep[sweep.length - 1];

  // 2. Quiet must buy TIME. Survival has to fall as the lever rises, at every
  //    step — that is the literal claim "noise is what kills you".
  let mono = true, worst = '';
  for (let i = 1; i < sweep.length; i++) {
    if (sweep[i].survivedSec > sweep[i - 1].survivedSec) {
      mono = false;
      worst = `${Math.round(sweep[i].throttle * 100)}% outlived ${Math.round(sweep[i - 1].throttle * 100)}%`;
    }
  }
  ok('quiet buys time (survival falls as the choir rises)', mono,
     worst || sweep.map((s) => s.survivedSec + 's').join(' > '));

  // 3. The quiet option has to be a real breather, not a slower death.
  ok('a silent Gleaner lasts 150s+', quiet.survivedSec >= 150,
     `${quiet.survivedSec}s, ${quiet.metres}m, ${quiet.killed} killed`);

  // 4. Wide open must eventually kill you, or the lever has no downside.
  ok('full choir eventually dies', loud.died,
     `${loud.survivedSec}s, ${loud.metres}m, peak ${loud.peakLive} alive`);

  // 5. The interesting claim: the best distance is at a MIDDLE throttle, not
  //    at either end. This is the whole design in one assertion — if it fails,
  //    the game is "hold W" or "hold nothing" and there is no decision in it.
  const best = sweep.reduce((a, b) => (b.metres > a.metres ? b : a));
  ok('best yield is not at full throttle', best.throttle < 1,
     `best ${best.metres}m at ${Math.round(best.throttle * 100)}%`);
  ok('best yield is not at idle', best.throttle > SWEEP[0],
     `best ${best.metres}m at ${Math.round(best.throttle * 100)}%`);

  // 5. Economy must actually fund a defence.
  ok('economy funds a full deck', loud.towers >= 8, `${loud.towers}/10 mounted`);

  // 6. No slot leak: the swarm must recycle, not grow without bound.
  ok('swarm stays under cap', loud.peakLive < CFG.swarm.max,
     `peak ${loud.peakLive} / cap ${CFG.swarm.max}`);

  return { checks: out, sweep, quiet, loud };
}

export function report() {
  const r = checks();
  const lines = [];
  lines.push('GLEANER — headless self-test');
  lines.push('');
  lines.push(`mean of ${TRIALS} seeded runs per throttle, ${MAX_SEC}s cap`);
  lines.push('');
  lines.push('throttle  survived   deaths    metres  +/-spread    grain    killed  peak-alive');
  for (const s of r.sweep) {
    lines.push(
      String(Math.round(s.throttle * 100) + '%').padStart(7) +
      String(s.survivedSec + 's').padStart(10) +
      String(s.deaths + '/' + s.trials).padStart(9) +
      String(s.metres).padStart(10) +
      String(s.metresSpread).padStart(11) +
      String(s.grainCut).padStart(9) +
      String(s.killed).padStart(10) +
      String(s.peakLive).padStart(12));
  }
  lines.push('');
  let pass = 0;
  for (const c of r.checks) {
    lines.push((c.pass ? '  PASS  ' : '  FAIL  ') + c.name.padEnd(34) + c.detail);
    if (c.pass) pass++;
  }
  lines.push('');
  lines.push(`${pass}/${r.checks.length} checks passed`);
  return { text: lines.join('\n'), pass, total: r.checks.length, data: r };
}
