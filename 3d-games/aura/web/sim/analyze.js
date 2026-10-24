// Finds the drop and the tempo of a song you dropped into music/. Pure: takes
// mono samples, returns seconds. Works in the browser and under node.
//
// A drop is not "the loudest part" -- a song can be loud from its first second.
// It is the biggest RISE: the point where the next few seconds carry far more
// low end than the few seconds before. Bass is what a phonk drop adds, so the
// energy is taken through a low-pass first.

const HOP = 0.01; // 10 ms analysis hop

function lowEnergy(samples, sr, cutoff = 160) {
  const hop = Math.max(1, Math.round(sr * HOP));
  const n = Math.floor(samples.length / hop);
  const out = new Float32Array(n);
  // one-pole low-pass, run twice (12 dB/oct) so hats and cowbells barely count
  const a = Math.exp((-2 * Math.PI * cutoff) / sr);
  let y1 = 0, y2 = 0;
  for (let i = 0; i < n; i++) {
    let acc = 0;
    for (let j = i * hop, e = j + hop; j < e; j++) {
      y1 = (1 - a) * samples[j] + a * y1;
      y2 = (1 - a) * y1 + a * y2;
      acc += y2 * y2;
    }
    out[i] = acc / hop;
  }
  return out;
}

function fullEnergy(samples, sr) {
  const hop = Math.max(1, Math.round(sr * HOP));
  const n = Math.floor(samples.length / hop);
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let acc = 0;
    for (let j = i * hop, e = j + hop; j < e; j++) acc += samples[j] * samples[j];
    out[i] = Math.sqrt(acc / hop);
  }
  return out;
}

function prefix(arr) {
  const p = new Float64Array(arr.length + 1);
  for (let i = 0; i < arr.length; i++) p[i + 1] = p[i] + arr[i];
  return p;
}

// Phonk almost always CUTS to dead silence right before the drop, and the 808 lands on the
// downbeat just after. Find those cuts and take the first bass hit after one that is followed by
// the most low end. The owner's reel fooled the window method (it said 11.23 s; the drop is
// 13.034 s, one bar later, right after a 120 ms digital-silence cut).
function cutDrop(samples, sr, minT) {
  const H = Math.max(1, Math.round(sr * 0.005));
  const n = Math.floor(samples.length / H);
  const full = new Float32Array(n), low = new Float32Array(n);
  const a = Math.exp((-2 * Math.PI * 120) / sr);
  let y1 = 0, y2 = 0;
  for (let i = 0; i < n; i++) {
    let f = 0, l = 0;
    for (let j = i * H, e = j + H; j < e; j++) {
      const s = samples[j];
      f += s * s;
      y1 = (1 - a) * s + a * y1; y2 = (1 - a) * y1 + a * y2;
      l += y2 * y2;
    }
    full[i] = Math.sqrt(f / H); low[i] = l / H;
  }
  const P = prefix(low);
  const lowMean = P[n] / n + 1e-12;
  const cands = [];
  let run = 0;
  for (let i = 0; i < n; i++) {
    if (full[i] < 1e-3) { run++; continue; }         // under -60 dBFS: a cut, not a quiet passage
    const w = Math.round(3 / 0.005);
    // at least 50 ms of it, with a full 3 s of song after (a short tail inflated the average once)
    if (run >= 10 && i * H / sr >= minT && i + 90 + w <= n) {
      // ...and it must CUT something: the music was playing in the half second before. The
      // silence between isolated clicks is not a cut.
      const g0 = i - run;
      let playing = 0;
      for (let k = Math.max(0, g0 - 100); k < g0; k++) if (full[k] >= 1e-3) playing++;
      if (playing >= 90) {   // continuous: the gap between two staccato kicks is not a cut
        // the bass onset: first frame within 0.45 s whose low end reaches half its level 0.1-0.6 s later
        const ref = (P[i + 120] - P[i + 20]) / 100;
        let b = i;
        while (b < i + 90 && low[b] < ref * 0.5) b++;
        const after = (P[b + w] - P[b]) / w;
        cands.push({ t: b * H / sr, score: after / lowMean });
      }
    }
    run = 0;
  }
  if (!cands.length) return null;
  const best = Math.max(...cands.map((c) => c.score));
  if (best < 1.2) return null;                       // the cuts are not followed by a real drop
  const first = cands.find((c) => c.score >= best * 0.8);
  return { time: first.t, confidence: Math.min(1, first.score / 3), via: 'cut' };
}

export function detectDrop(samples, sr, { minT = 5, win = 2.5 } = {}) {
  const cut = cutDrop(samples, sr, Math.min(minT, 3));
  if (cut) return cut;
  const E = lowEnergy(samples, sr);
  const P = prefix(E);
  const w = Math.round(win / HOP);
  const n = E.length;
  const mean = (a, b) => (P[b] - P[a]) / Math.max(1, b - a);
  const total = mean(0, n) + 1e-12;
  let best = -1, bestScore = -Infinity;
  for (let i = Math.max(w, Math.round(minT / HOP)); i + w <= n; i++) {
    const before = mean(i - w, i), after = mean(i, i + w);
    // rise ratio, weighted by how loud the after-part is relative to the song
    const score = Math.log((after + total * 0.02) / (before + total * 0.02)) * Math.sqrt(after / total);
    if (score > bestScore) { bestScore = score; best = i; }
  }
  if (best < 0) return { time: null, confidence: 0 };
  // Refine to the FIRST strong jump near the window edge. Not the sharpest: every
  // kick after the drop is an equally sharp jump, and picking the biggest one
  // lands a beat or two late (the test caught exactly that).
  // Look back only a little: builds often have a kick in them, and a 2.5 s
  // look-back picked a build kick as the drop (found by the audio self-test).
  const lo = Math.max(1, best - Math.round(0.6 / HOP)), hi = Math.min(n, best + Math.round(0.5 / HOP));
  let maxJump = 0;
  for (let i = lo; i < hi; i++) maxJump = Math.max(maxJump, E[i] - E[i - 1]);
  const after = mean(best, best + w);
  let jump = best;
  for (let i = lo; i < hi; i++) {
    if (E[i] - E[i - 1] >= 0.5 * maxJump && E[i] > 0.3 * after) { jump = i; break; }
  }
  return { time: jump * HOP, confidence: Math.max(0, Math.min(1, bestScore / 1.5)) };
}

// A comb SCAN over every tempo, not an autocorrelation. Autocorrelation read the owner's reel as
// 109-112 (the melody's rhythm) and, re-weighted toward the bass to fix that, read the code phonk's
// syncopated 3-3-2 808 as 104. Measured from the drop on, a comb over full-band onsets gets both:
// 130.0 and 81.9. Each comb tooth takes the max of three neighbouring hops to forgive 10 ms jitter.
export function detectTempo(samples, sr, { min = 70, max = 180 } = {}) {
  const E = fullEnergy(samples, sr);
  const O = new Float32Array(E.length);
  for (let i = 1; i < E.length; i++) {
    const d = Math.log(1e-4 + E[i]) - Math.log(1e-4 + E[i - 1]);
    O[i] = d > 0 ? d : 0;
  }
  // sharp = false: forgiving teeth (max of 3 hops) to FIND the tempo.
  // sharp = true: interpolated teeth to MEASURE it -- forgiving teeth left 20 ms of drift per 3 bars.
  const comb = (bpm, sharp = false) => {
    const period = 60 / bpm / HOP;
    let best = 0, bestPhase = 0;
    for (let ph = 0; ph < period; ph += sharp ? 0.25 : 0.5) {
      let s = 0, k = 0;
      for (let x = ph; x < O.length - 1; x += period, k++) {
        if (sharp) { const i = Math.floor(x), f = x - i; s += O[i] * (1 - f) + O[i + 1] * f; continue; }
        const i = Math.round(x);
        s += Math.max(O[i - 1] || 0, O[i], O[i + 1] || 0);
      }
      s /= Math.max(1, k);
      if (s > best) { best = s; bestPhase = ph; }
    }
    return { s: best, phase: bestPhase * HOP };
  };
  let bpm = min, top = { s: -1, phase: 0 };
  for (let b = min; b <= max; b += 0.5) { const c = comb(b); if (c.s > top.s) { top = c; bpm = b; } }
  // Half speed scores the same as the real tempo on an even pulse (every other tooth still lands).
  // If double the tempo is nearly as strong, it is the tempo.
  if (bpm * 2 <= max) { const d = comb(bpm * 2); if (d.s >= top.s * 0.9) { top = d; bpm *= 2; } }
  const coarse = bpm;
  top = comb(coarse, true);
  for (let b = coarse - 0.6; b <= coarse + 0.6; b += 0.01) { const c = comb(b, true); if (c.s > top.s) { top = c; bpm = b; } }
  const beat = 60 / bpm;
  return { bpm: Math.round(bpm * 100) / 100, firstBeat: top.phase % beat };
}

// Drop first, THEN tempo from the drop onward. Intros are often free-time (the owner's reel is
// an edit whose first half has no steady pulse and read as 109-112 BPM); the part after the drop
// is the part the fight is played to. And a drop lands on a downbeat, so it anchors the phase.
export function analyzeTrack(samples, sr) {
  const drop = detectDrop(samples, sr);
  const at = drop.time != null ? Math.floor(drop.time * sr) : 0;
  const tail = samples.length - at >= sr * 6 ? samples.subarray(at) : samples;
  const tempo = detectTempo(tail, sr);
  const beat = 60 / tempo.bpm;
  const anchor = drop.time != null ? drop.time : (tail === samples ? 0 : at / sr) + tempo.firstBeat;
  const firstBeat = ((anchor % beat) + beat) % beat;
  return { drop: drop.time, dropConfidence: drop.confidence, via: drop.via || 'window', bpm: tempo.bpm, firstBeat };
}

// The drop should sit on a beat. If the detected drop is within `tol` of the
// grid, snap it there -- a 30 ms error is felt when you parry on it.
export function snapToGrid(t, bpm, firstBeat, tol = 0.12) {
  const beat = 60 / bpm;
  const k = Math.round((t - firstBeat) / beat);
  const s = firstBeat + k * beat;
  return Math.abs(s - t) <= tol ? s : t;
}
