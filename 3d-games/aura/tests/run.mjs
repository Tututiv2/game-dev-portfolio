// AURA headless checks. `node tests/run.mjs` from the AURA folder (or Test.bat).
// Runs the SHIPPING sim modules -- no browser, no GPU, no audio device.

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, resolve, extname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'web');
const imp = (p) => import(pathToFileURL(join(WEB, p)).href);

const { BeatGrid, gradeParry, ParryInput, WINDOWS } = await imp('sim/beat.js');
const C = await imp('sim/combat.js');
const { Director, T } = await imp('sim/director.js');
const { detectDrop, detectTempo, snapToGrid, analyzeTrack } = await imp('sim/analyze.js');
const S = await imp('sim/skill.js');

let pass = 0, fail = 0;
const results = [];
function check(name, fn) {
  try {
    const r = fn();
    if (r === false) throw new Error('returned false');
    pass++; results.push(`  ok    ${name}${typeof r === 'string' ? '  (' + r + ')' : ''}`);
  } catch (e) {
    fail++; results.push(`  FAIL  ${name}\n        ${e.message}`);
  }
}
const eq = (a, b, tol, what = '') => { if (Math.abs(a - b) > tol) throw new Error(`${what} ${a} != ${b} (tol ${tol})`); };
const ok = (c, msg) => { if (!c) throw new Error(msg); };

// ---------------------------------------------------------------- beat + parry
check('grid: next() lands exactly on beats', () => {
  const g = new BeatGrid(130, 10);
  for (let t = 10; t < 20; t += 0.0371) {
    const n = g.next(t);
    ok(n >= t - 1e-9, `next ${n} before ${t}`);
    eq(g.offset(n), 0, 1e-9, 'offset of a grid point');
  }
});
check('parry: perfect window is early-75ms .. late+60ms', () => {
  ok(gradeParry(0.926, 1).grade === 'perfect', 'early 74ms');
  ok(gradeParry(1.059, 1).grade === 'perfect', 'late 59ms');
  ok(gradeParry(0.9, 1).grade === 'good', 'early 100ms is a block');
  ok(gradeParry(0.79, 1).grade === 'miss', 'early 210ms misses');
  ok(gradeParry(1.07, 1).grade === 'miss', 'late 70ms misses');
});
check('parry: AURA tier is within 22 ms either side, and only there', () => {
  ok(gradeParry(0.99, 1).grade === 'flawless', '-10 ms');
  ok(gradeParry(1.021, 1).grade === 'flawless', '+21 ms');
  ok(gradeParry(0.97, 1).grade === 'perfect', '-30 ms is perfect, not aura');
  ok(gradeParry(1.03, 1).grade === 'perfect', '+30 ms is perfect, not aura');
});
check('parry: mashing never works (every press resets the lock)', () => {
  const p = new ParryInput();
  // mash every 100 ms for 2 s; a hit lands at 1.0
  let accepted = 0;
  for (let t = 0; t <= 2; t += 0.1) if (p.press(t)) accepted++;
  ok(accepted === 1, `only the first press of a mash counts, got ${accepted}`);
  ok(p.resolve(1.0).grade === 'miss', 'a masher does not parry');
});
check('parry: one press takes every blow on the same beat, but not the next beat', () => {
  const p = new ParryInput();
  p.press(0.99);
  ok(p.resolve(1.0).grade === 'flawless', 'first');
  ok(p.resolve(1.02).grade !== 'miss', 'a second blow 20 ms later is the same moment');
  ok(p.resolve(1.2).grade === 'miss', 'a blow 200 ms later needs its own press');
});
check('parry: a triple on 130 BPM beats is parryable with three presses', () => {
  const g = new BeatGrid(130, 0);
  const p = new ParryInput();
  const hits = [g.beatTime(8), g.beatTime(9), g.beatTime(10)];
  for (const h of hits) p.press(h - 0.02);
  const grades = hits.map((h) => p.resolve(h).grade);
  ok(grades.every((x) => x === 'perfect' || x === 'flawless'), grades.join(','));
  return `beat spacing ${(g.beat * 1000).toFixed(0)} ms > lock ${WINDOWS.spamLock * 1000} ms`;
});

// ---------------------------------------------------------------- ogre on the grid
check('ogre: every scheduled impact lands on a beat, after a full windup', () => {
  const g = new BeatGrid(130, 3.3);
  for (const kind of Object.keys(C.OGRE_ATTACKS)) {
    for (let now = 4; now < 30; now += 0.77) {
      const a = C.scheduleAttack(kind, g, now);
      for (const t of a.impacts) eq(g.offset(t), 0, 1e-9, `${kind} impact off-beat`);
      ok(a.impacts[0] - now >= C.OGRE_ATTACKS[kind].windup * g.beat - 1e-9, `${kind} windup too short`);
    }
  }
});
check('ogre: the drop combo can be pinned to a section drop', () => {
  const g = new BeatGrid(130, 0);
  const at = g.beatTime(36);
  const a = C.scheduleAttackAt('triple', g, at);
  eq(a.impacts[0], at, 1e-12);
  eq(a.start, at - 4 * g.beat, 1e-12);
});
check('ogre deck: never the same move twice running, and every move gets thrown', () => {
  let s = 99; const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647);
  const bag = []; let last = null; const seen = new Set();
  for (let i = 0; i < 400; i++) {
    const k = C.drawAttack(bag, last, rnd);
    ok(k !== last, `repeat of ${k} at ${i}`);
    seen.add(k); last = k;
  }
  for (const k of C.OGRE_DECK) ok(seen.has(k), `${k} never drawn`);
  return `${seen.size} moves`;
});
check('arc: cone test respects reach, angle and body radius', () => {
  const o = { x: 0, z: 0 };
  ok(C.inArc(o, 0, { x: 0, z: 2 }, 3, 1), 'straight ahead');
  ok(!C.inArc(o, 0, { x: 0, z: -2 }, 3, 1), 'behind');
  ok(!C.inArc(o, 0, { x: 0, z: 3.5 }, 3, 1), 'too far');
  ok(C.inArc(o, 0, { x: 0, z: 4 }, 3, 1, 1.5), 'far centre but big body in reach');
  ok(C.inArc(o, 0, { x: 2, z: 2 }, 3.1, 1, 1.5), 'off-axis big body clipped by the cone');
});

// ---------------------------------------------------------------- story
function runStory(hitsPerSec, maxT = 120) {
  const d = new Director();
  d.start(false);
  let hp = C.PLAYER.maxHp, z = -20, t = 0, events = [];
  const dt = 1 / 60;
  let hitAcc = 0;
  while (t < maxT && !['getup'].includes(d.phase)) {
    t += dt;
    z += dt * 3;
    if (d.phase === 'weak') {
      hitAcc += hitsPerSec * dt;
      while (hitAcc >= 1) { hitAcc--; hp = C.damagePlayer(hp, C.GOBLIN.dmg, 'weak'); }
    }
    for (const e of d.update(dt, { hp, playerZ: z, audioNow: t, ogreHp: 1 })) events.push([e, +t.toFixed(2), hp]);
  }
  return { d, events, hp, t };
}
check('story: a player who keeps getting hit goes down, not dead', () => {
  const r = runStory(0.6);
  const down = r.events.find((e) => e[0] === 'downed');
  ok(down, 'never went down');
  ok(r.hp >= C.WEAK.hpFloor, `hp ${r.hp} under the floor`);
  ok(r.d.phase === 'getup', `ended in ${r.d.phase}`);
  return `down at ${down[1]}s with ${down[2]} hp`;
});
check('story: a flawless player is still blindsided (the Awakening always comes)', () => {
  const r = runStory(0);
  ok(r.events.some((e) => e[0] === 'blindside'), 'no blindside');
  ok(r.d.phase === 'getup', `ended in ${r.d.phase}`);
});
check('story: hp can never drop below the floor before the Awakening', () => {
  let hp = 100;
  for (let i = 0; i < 50; i++) hp = C.damagePlayer(hp, 99, 'weak');
  ok(hp === C.WEAK.hpFloor, `${hp}`);
});
check('story: the awakening fires ring -> silence -> strike -> colour, in order, on the audio clock', () => {
  const d = new Director();
  d.start(true);
  d.go('getup');
  for (let i = 0; i < T.getUpPresses; i++) d.getUpPress();
  let ev = d.update(0.016, { hp: 50, playerZ: 0, audioNow: 0, ogreHp: 64 });
  ok(ev.includes('awaken'), 'no awaken');
  const beat = 60 / 130;
  const p = d.planAwakening(0.05, 0.05 + 8 * beat, beat);
  const seen = [];
  // deliberately choppy frame clock: audio time is what counts
  for (let a = 0; a < p.colorAt + 0.5; a += 0.043) {
    for (const e of d.update(0.2, { hp: 50, playerZ: 0, audioNow: a, ogreHp: 64 })) seen.push([e, a]);
  }
  ok(seen.map((s) => s[0]).join(',') === 'ring,silence,strike,color', seen.map((s) => s[0]).join(','));
  const strike = seen.find((s) => s[0] === 'strike')[1];
  ok(strike >= p.dropAt && strike - p.dropAt < 0.05, `strike at ${strike} vs drop ${p.dropAt}`);
  ok(d.phase === 'awakened');
});
check('story: lead-in is "a few seconds" -- 2 bars at 130 BPM', () => {
  const lead = 8 * 60 / 130;
  ok(lead > 3 && lead < 5, `${lead}`);
  return `${lead.toFixed(2)} s of cowbell before the drop`;
});

// ---------------------------------------------------------------- skill layers
check('flow: on-beat actions climb, one off-beat action resets', () => {
  const g = new BeatGrid(130, 0);
  const f = new S.Flow();
  for (let k = 1; k <= 6; k++) ok(f.act(g.beatTime(k) + 0.03, g).result === 'up', `beat ${k}`);
  ok(f.level === 6 && f.mult > 2, `level ${f.level} mult ${f.mult}`);
  const r = f.act(g.beatTime(7) + g.beat / 2, g); // dead between beats
  ok(r.result === 'break' && f.level === 0, 'offbeat did not break');
  ok(f.best === 6);
});
check('flow: random mashing does not build flow', () => {
  const g = new BeatGrid(130, 0);
  const f = new S.Flow();
  let s = 12345; const rnd = () => ((s = (s * 1103515245 + 12345) % 2147483648) / 2147483648);
  let t = 0;
  for (let i = 0; i < 400; i++) { t += 0.08 + rnd() * 0.4; f.act(t, g); }
  ok(f.best <= 4, `a random presser reached flow ${f.best}`);
  return `random best ${f.best}`;
});
check('flow: three swings inside one beat window climb once, not three times', () => {
  const g = new BeatGrid(130, 0);
  const f = new S.Flow();
  f.act(g.beatTime(4) - 0.04, g); f.act(g.beatTime(4), g); f.act(g.beatTime(4) + 0.04, g);
  ok(f.level === 1, `level ${f.level}`);
});
check('flow: going quiet for two bars lets it lapse', () => {
  const g = new BeatGrid(130, 0);
  const f = new S.Flow();
  f.act(g.beatTime(1), g); f.act(g.beatTime(2), g);
  const r = f.act(g.beatTime(2 + 9), g);
  ok(r.level === 1, `level ${r.level}`);
});
check('flick: left / right / over read from pointer deltas', () => {
  const mk = (dx, dy) => [{ t: 0.98, dx: dx / 2, dy: dy / 2 }, { t: 1.03, dx: dx / 2, dy: dy / 2 }];
  ok(S.readFlick(mk(-40, 5), 1) === 'left');
  ok(S.readFlick(mk(40, -8), 1) === 'right');
  ok(S.readFlick(mk(6, -40), 1) === 'over');
  ok(S.readFlick(mk(3, 2), 1) === null, 'a twitch is not a flick');
  ok(S.readFlick([{ t: 0.5, dx: -80, dy: 0 }], 1) === null, 'old movement does not count');
});
check('directional: right timing, wrong direction is only a block', () => {
  const perfect = { grade: 'perfect' };
  ok(S.judgeParry(perfect, 'left', 'left', true) === 'perfect');
  ok(S.judgeParry(perfect, 'right', 'left', true) === 'good');
  ok(S.judgeParry(perfect, null, 'over', true) === 'good');
  ok(S.judgeParry(perfect, null, 'over', false) === 'perfect', 'layer off = timing only');
  ok(S.judgeParry({ grade: 'miss' }, 'left', 'left', true) === 'miss');
});
check('air: one dash in the air; a touch refills it; the ground refills it', () => {
  const a = new S.AirState();
  a.leave();
  ok(a.canDash()); a.useDash(); ok(!a.canDash(), 'second air dash allowed');
  ok(a.touch() === true, 'touch was not a reset');
  ok(a.canDash(), 'reset did not refill');
  ok(a.touch() === false, 'a touch with a full charge is not a reset');
  a.useDash(); a.land(); ok(a.canDash() && a.chain === 0);
  ok(a.resets === 1 && a.bestChain === 2);
});
check('air: an aura-reset chain is physically reachable (dash + pop gets you back up)', () => {
  // After a reset pop you must have time for another dash before landing.
  const up = S.AIR.popOnReset, g = S.AIR.gravity;
  const airtime = 2 * up / g;
  ok(airtime > S.AIR.dashTime + 0.15, `pop gives ${airtime.toFixed(2)} s`);
  return `${airtime.toFixed(2)} s of air per reset`;
});
check('ray vs sphere (deflected spear)', () => {
  const o = { x: 0, y: 1, z: 0 }, d = { x: 0, y: 0, z: 1 };
  eq(S.raySphere(o, d, { x: 0, y: 1, z: 10 }, 1), 9, 1e-9);
  ok(S.raySphere(o, d, { x: 3, y: 1, z: 10 }, 1) === -1);
  ok(S.raySphere(o, d, { x: 0, y: 1, z: -10 }, 1) === -1, 'behind');
});

// ---------------------------------------------------------------- analysis (the oracle is a known synthetic song)
function synth({ sr = 22050, secs = 30, bpm = 140, first = 0.23, dropAt = 14.0 }) {
  const out = new Float32Array(sr * secs);
  const beat = 60 / bpm;
  let s = 7; const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647) * 2 - 1;
  for (let k = 0; ; k++) {
    const t0 = first + k * beat;
    if (t0 >= secs) break;
    const i0 = Math.round(t0 * sr);
    const loud = t0 >= dropAt - 1e-6;
    // hat-ish click always; a kick with a 50 Hz body only after the drop
    for (let i = 0; i < sr * 0.03 && i0 + i < out.length; i++) out[i0 + i] += rnd() * 0.3 * Math.exp(-i / (sr * 0.004));
    if (loud) for (let i = 0; i < sr * 0.35 && i0 + i < out.length; i++) {
      const tt = i / sr;
      out[i0 + i] += 0.9 * Math.sin(2 * Math.PI * (50 * tt + 60 * (1 - Math.exp(-tt * 30)) / 30)) * Math.exp(-tt * 6);
    }
  }
  return { out, sr };
}
check('analyze: finds the drop of a synthetic track to within 50 ms', () => {
  const { out, sr } = synth({ dropAt: 14.0 });
  const d = detectDrop(out, sr);
  const beat = 60 / 140, drop = 0.23 + Math.ceil((14 - 0.23) / beat) * beat;
  eq(d.time, drop, 0.05, 'drop');
  return `found ${d.time.toFixed(3)} s, true ${drop.toFixed(3)} s`;
});
check('analyze: a kick in the build-up is not mistaken for the drop', () => {
  const { out, sr } = synth({ dropAt: 14.0 });
  // a lighter kick every other beat through the 4 s build before the drop
  const beat = 60 / 140;
  for (let t0 = 0.23 + Math.ceil((10 - 0.23) / beat) * beat; t0 < 14.0 - 0.01; t0 += 2 * beat) {
    const i0 = Math.round(t0 * sr);
    for (let i = 0; i < sr * 0.3 && i0 + i < out.length; i++) out[i0 + i] += 0.35 * Math.sin(2 * Math.PI * 55 * i / sr) * Math.exp(-i / sr * 8);
  }
  const d = detectDrop(out, sr);
  const drop = 0.23 + Math.ceil((14 - 0.23) / beat) * beat;
  eq(d.time, drop, 0.06, 'drop');
  return `found ${d.time.toFixed(3)} s, true ${drop.toFixed(3)} s`;
});
check('analyze: a phonk cut (dead silence) then the 808 is found as the drop, not the loud bit before it', () => {
  const sr = 22050, secs = 22, out = new Float32Array(sr * secs);
  let s = 3; const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647) * 2 - 1;
  // a LOUD melodic intro with its own dips (the reel's shape), a 120 ms digital-silence cut
  // at 12.70 s, the melody back at 12.82 s, and the 808 on the downbeat at 13.03 s
  for (let i = 0; i < out.length; i++) {
    const t = i / sr;
    if (t >= 12.70 && t < 12.82) continue;
    const dip = (t % 3 > 2.4 && t < 12) ? 0.1 : 1;
    out[i] += 0.25 * dip * Math.sin(2 * Math.PI * 880 * t) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * t));
    out[i] += 0.05 * rnd() * dip;
  }
  for (let t0 = 13.03; t0 < secs; t0 += 0.7326) {
    const i0 = Math.round(t0 * sr);
    for (let i = 0; i < sr * 0.5 && i0 + i < out.length; i++) out[i0 + i] += 0.8 * Math.sin(2 * Math.PI * 48 * i / sr) * Math.exp(-i / sr * 3);
  }
  const d = detectDrop(out, sr);
  eq(d.time, 13.03, 0.03, 'drop');
  return `found ${d.time.toFixed(3)} s via ${d.via || 'window'}`;
});
const REEL = join(ROOT, 'music', 'Aura Edit (your reel).wav');
if (existsSync(REEL)) check("analyze: the owner's reel -- drop at the measured 13.034 s", () => {
  const b = readFileSync(REEL);
  const sr = b.readUInt32LE(24), n = (b.length - 44) >> 1;
  const x = new Float32Array(n); for (let i = 0; i < n; i++) x[i] = b.readInt16LE(44 + i * 2) / 32768;
  const d = detectDrop(x, sr);
  eq(d.time, 13.034, 0.03, 'drop');
  return `found ${d.time.toFixed(3)} s (hand-measured 13.034 s)`;
});
if (existsSync(REEL)) check("analyze: the owner's reel -- tempo at the measured 81.9 BPM", () => {
  const b = readFileSync(REEL);
  const sr = b.readUInt32LE(24), n = (b.length - 44) >> 1;
  const x = new Float32Array(n); for (let i = 0; i < n; i++) x[i] = b.readInt16LE(44 + i * 2) / 32768;
  const r = analyzeTrack(x, sr);
  eq(r.bpm, 81.9, 0.6, 'bpm');
  // every measured bass downbeat must sit on the grid it implies, within 15 ms
  const beat = 60 / r.bpm;
  for (const t of [13.034, 15.968, 18.890, 21.828]) {
    let d = (t - r.firstBeat) / beat; d = (d - Math.round(d)) * beat;
    ok(Math.abs(d) < 0.015, `downbeat ${t} is ${(d * 1000).toFixed(1)} ms off the grid`);
  }
  return `${r.bpm} BPM, drop ${r.drop.toFixed(3)} s via ${r.via}`;
});
check('analyze: finds tempo within 0.5 BPM and phase within 25 ms', () => {
  const { out, sr } = synth({ bpm: 140, first: 0.23 });
  const r = detectTempo(out, sr);
  eq(r.bpm, 140, 0.5, 'bpm');
  const beat = 60 / 140;
  let ph = (r.firstBeat - 0.23) % beat; if (ph > beat / 2) ph -= beat; if (ph < -beat / 2) ph += beat;
  eq(ph, 0, 0.025, 'phase');
  return `${r.bpm} BPM, phase error ${(ph * 1000).toFixed(1)} ms`;
});
check('analyze: snapToGrid pulls a near miss onto the beat and leaves a far one', () => {
  eq(snapToGrid(10.05, 120, 0), 10.0, 1e-9);
  eq(snapToGrid(10.25, 120, 0), 10.25, 1e-9);
});

// ---------------------------------------------------------------- the blade lands on the beat
const { measure: swingSync } = await import(pathToFileURL(join(ROOT, 'tests', 'swing_sync.mjs')).href);
check('ogre: every blow is DRAWN landing within 12 ms of the beat (was 69-101 ms late)', () => {
  const worst = [];
  for (const bpm of [81.9, 110, 130, 160]) {
    for (const [kind, dir] of [['slam', 'over'], ['sweep', 'left'], ['sweep', 'right']]) {
      const r = swingSync(kind, dir, bpm);
      worst.push(Math.abs(r.offsetMs));
      ok(Math.abs(r.offsetMs) <= 12, `${bpm} BPM ${kind} ${dir}: ${r.offsetMs.toFixed(1)} ms`);
    }
  }
  return `worst ${Math.max(...worst).toFixed(1)} ms across 4 tempos x 3 blows`;
});

// ---------------------------------------------------------------- source hygiene
function walk(dir, out = []) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) { if (n !== 'vendor') walk(p, out); }
    else if (['.js', '.mjs', '.html', '.css', '.py'].includes(extname(n))) out.push(p);
  }
  return out;
}
const SOURCES = [...walk(WEB), ...walk(join(ROOT, 'tests')), join(ROOT, 'server.py'),
  ...readdirSync(ROOT).filter((n) => n.endsWith('.bat')).map((n) => join(ROOT, n))];  // a printf once put a CR into Test.bat
check('hygiene: no control characters in any source file (the heredoc bug, four times in this portfolio)', () => {
  const bad = [];
  for (const f of SOURCES) {
    const s = readFileSync(f, 'utf8');
    for (let i = 0; i < s.length; i++) {
      const c = s.charCodeAt(i);
      if (c < 32 && c !== 9 && c !== 10 && c !== 13) { bad.push(`${f.slice(ROOT.length)} @${i} (0x${c.toString(16)})`); break; }
    }
  }
  ok(!bad.length, bad.join('; '));
  return `${SOURCES.length} files`;
});
check('hygiene: every relative import in web/ resolves to a file that exists', () => {
  const missing = [];
  const files = walk(WEB).filter((f) => f.endsWith('.js') || f.endsWith('.html'));
  files.push(join(WEB, 'vendor/three/three.module.js'));
  for (const f of files) {
    const s = readFileSync(f, 'utf8');
    for (const m of s.matchAll(/(?:import|export)[^'"]*?from\s*['"](\.{1,2}\/[^'"]+)['"]|import\(\s*['"](\.{1,2}\/[^'"]+)['"]\s*\)|<script[^>]+src=["'](\.{0,2}\/?[^"':]+\.js)["']/g)) {
      const rel = m[1] || m[2] || m[3];
      const p = resolve(dirname(f), rel);
      if (!existsSync(p)) missing.push(`${f.slice(ROOT.length)} -> ${rel}`);
    }
  }
  ok(!missing.length, missing.join('; '));
  return 'incl. three.module.js -> three.core.min.js';
});
check('hygiene: every module parses as a module (catches duplicate declarations node --check misses)', () => {
  const files = walk(WEB).filter((f) => f.endsWith('.js'));
  const bad = [];
  for (const f of files) {
    try { execFileSync(process.execPath, ['--input-type=module', '--check'], { input: readFileSync(f), stdio: ['pipe', 'pipe', 'pipe'] }); }
    catch (e) { bad.push(`${f.slice(ROOT.length)}: ${String(e.stderr).split('\n').find((l) => /Error/.test(l)) || 'error'}`); }
  }
  ok(!bad.length, bad.join('\n        '));
  return `${files.length} modules`;
});
check('hygiene: sim/ stays pure (no DOM, three, audio or Math.random)', () => {
  const bad = [];
  for (const f of walk(join(WEB, 'sim'))) {
    const code = readFileSync(f, 'utf8').split('\n').filter((l) => !/^\s*\/\//.test(l)).join('\n');
    for (const w of ['document.', 'window.', 'THREE', 'AudioContext', 'Math.random', 'requestAnimationFrame']) {
      if (code.includes(w)) bad.push(`${f.slice(ROOT.length)} uses ${w}`);
    }
  }
  ok(!bad.length, bad.join('; '));
});

console.log(`\nAURA checks\n${results.join('\n')}\n\n  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
