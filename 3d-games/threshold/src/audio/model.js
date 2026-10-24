// The acoustic model.
//
// Pure maths in, pure numbers out — no Web Audio, no DOM, no three.js. That is deliberate: the
// engine's job is only to push these numbers into AudioParams, which means every claim this
// project makes about how it sounds can be simulated headless in node and asserted on.
//
// Produces, for a listener at a point:
//   diffuse   the non-directional bed bleeding through walls
//   apertures a positional emitter per opening — THE trick (BUILD_PROMPT §3.2)
//   roof      the drumming overhead that only exists indoors (§0)
//   reverb    from the solve
//   wind      + a resonant whistle per cracked opening (§3.3)

import { dbToGain } from '../world/materials.js';
import { roofCoverage, OUTSIDE_ID } from '../world/volumes.js';

/**
 * How far either side of an opening you are "half in each room".
 *
 * It scales with how open the thing is, and the two ends are doing different jobs:
 *
 *   shut (0.35 m)  — narrower than a body, so the band sits inside the door leaf where nobody can
 *                    stand. Without this, leaning on a closed window bled 5 dB of storm into a
 *                    sealed room.
 *   open (1.6 m)   — wide, because at a sprint you cross the plane in a sixth of a second and the
 *                    level has to get all the way across without lurching.
 *
 * Note what is NOT done here: the blend is not multiplied by openness. It reaches 1 at the plane
 * whatever the door is doing, which is precisely what cancels the discrete "which room am I in"
 * flip at s = 0. Gate it and a half-open door clicks. See BUILD_PROMPT §3.6.
 */
const blendRadius = openness => 0.35 + 1.25 * openness;

/**
 * THE MIX. Attenuations in dB applied to whole layers, on top of everything the physics decides.
 *
 * These exist because the physics being right is not the same as the mix being good, and the first
 * version proved it. Measured indoors in a downpour with everything shut, the roof layer sat 16 dB
 * above the storm through the walls and 21 dB above the windows — so the interior was ~95% one
 * looping buffer, and the signature moment of the entire project, cracking the window, moved the
 * total level by 0.8 dB. Every band-limited test passed, because the window's contribution lives in
 * a band the roof does not occupy; what failed was the only measurement that counts, which is a
 * person saying "kinda mid".
 *
 * The thesis still holds — the drumming overhead must be the loudest thing in the room — but
 * "loudest" is a ranking, not a licence to bury everything else under it.
 */
// roofDb 9 comes off a sweep against four numbers at once, not off taste.
//
// At 0 — the version that got "kinda mid" — going inside was 8.2 dB quieter, cracking the window
// while standing at it moved 1.5 dB, and throwing the FRONT DOOR WIDE OPEN onto a downpour moved
// 1.5 dB. You could not hear the difference between a sealed cabin and an open door, because the
// roof loop sat 16 dB over everything else and never changed no matter what you did. Nothing you
// did mattered, which is a fair definition of mid.
//
//   roofDb    inside quieter by    open the door    crack the window    roof over storm
//        0             8.2 dB           1.5 dB            1.5 dB              +14 dB
//        9            15.5 dB           5.1 dB            5.3 dB             +2.5 dB
//       12            17.2 dB           6.3 dB            6.7 dB             -0.5 dB
//
// 12 sounds better on the first three and breaks the thesis on the fourth: the drumming stops being
// the loudest thing in the room, which is the whole corollary the project exists to demonstrate.
// 9 keeps the roof on top with 2.5 dB in hand and still triples every delta a player can act on.
export const DEFAULT_MIX = { roofDb: 9, windDb: 0, portalDb: 0 };

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const smoothstep = (e0, e1, x) => {
  const t = clamp((x - e0) / (e1 - e0 || 1e-9), 0, 1);
  return t * t * (3 - 2 * t);
};
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const len = a => Math.hypot(a[0], a[1], a[2]);

/**
 * Listener pose from a yaw/pitch, in a right-handed Y-up world. Shared by the game and the tests
 * so the two can never disagree about which way the window is.
 *
 * right = forward × up, and the order is NOT a matter of taste. It is what three.js computes for
 * the camera (which looks down -Z, so its screen-right +X is forward × up) and it is what the Web
 * Audio spec computes for the listener before taking an azimuth. Those two decide what the player
 * actually sees and actually hears, so anything else here is simply wrong.
 *
 * This was written the other way round — up × forward, i.e. LEFT — on the strength of a geographic
 * argument that does not survive contact with the handedness: if +X is east and +Z is north then
 * facing north puts east on your LEFT, because a right-handed Y-up world is not a map with north
 * up and east right. The model was self-consistent and disagreed with both of the things that
 * matter, which is why it took a human walking around to find it: pan was reported to the wrong
 * side, and the movement code, following the model, strafed the wrong way.
 */
export function makeListener(pos, yaw = 0, pitch = 0) {
  const cp = Math.cos(pitch);
  const forward = [Math.sin(yaw) * cp, Math.sin(pitch), Math.cos(yaw) * cp];
  const up = [0, 1, 0];
  const right = [
    forward[1] * up[2] - forward[2] * up[1],
    forward[2] * up[0] - forward[0] * up[2],
    forward[0] * up[1] - forward[1] * up[0],
  ];
  const rl = Math.hypot(right[0], right[1], right[2]) || 1;
  return { pos, forward, up, right: [right[0] / rl, right[1] / rl, right[2] / rl], yaw, pitch };
}

/**
 * Which volume(s) is the listener in, and by how much?
 *
 * The single most important property here is CONTINUITY. Near an opening the answer is a mix of
 * the two sides, and crucially the far-field term is weighted by (1 - prox) — so at the exact
 * plane of the doorway, where the discrete "which room am I in" answer flips, that term contributes
 * nothing and the flip cancels out. Walk through a door and nothing snaps. See BUILD_PROMPT §3.6.
 *
 * The blend is NOT gated on how open the door is, and that matters: gating it would reintroduce a
 * jump at the plane for any half-open door. The pleasant side effect is that standing with your ear
 * against a shut door lets a little more through, which is exactly what happens in life.
 */
export function volumeWeights(graph, pos) {
  const base = graph.resolveVolume(pos[0], pos[1], pos[2]);
  let weights = new Map([[base.id, 1]]);

  for (const op of graph.grid.openingList) {
    const R = blendRadius(op.openness);
    const s = dot(sub(pos, op.center), op.normal);      // + = outward side
    if (Math.abs(s) > R) continue;
    const along = [
      pos[0] - op.center[0] - s * op.normal[0],
      pos[1] - op.center[1] - s * op.normal[1],
      pos[2] - op.center[2] - s * op.normal[2],
    ];
    const apR = Math.sqrt(op.area) / 2 + 0.35;
    const lateral = smoothstep(apR + 0.9, apR, len(along));
    if (lateral <= 0) continue;

    const prox = lateral * smoothstep(R, 0, Math.abs(s));
    if (prox <= 0) continue;

    const sides = graph.apertureSides(op);
    if (!sides) continue;
    const fOut = smoothstep(-R, R, s);

    const target = new Map();
    target.set(sides.inner.id, (target.get(sides.inner.id) || 0) + (1 - fOut));
    target.set(sides.outer.id, (target.get(sides.outer.id) || 0) + fOut);

    const next = new Map();
    for (const [id, w] of weights) next.set(id, w * (1 - prox));
    for (const [id, w] of target) next.set(id, (next.get(id) || 0) + w * prox);
    weights = next;
  }

  let total = 0;
  for (const w of weights.values()) total += w;
  if (total > 0) for (const [id, w] of weights) weights.set(id, w / total);
  return weights;
}

/**
 * @returns {object} the full acoustic frame for this listener position.
 */
export function computeAcoustics(scene) {
  const { grid, graph, listener, weather } = scene;
  const mix = scene.mix || DEFAULT_MIX;
  const pos = listener.pos;

  // Order matters, and getting it wrong is silent. volumeWeights() is what DISCOVERS volumes —
  // it flood-fills on demand — and a freshly filled volume has zero transmission until relax()
  // propagates the outside into it. Relaxing first (the obvious reading) relaxes an empty graph,
  // and every room reads as perfectly silent for one frame. Relax after, always, here, so no
  // caller has to remember.
  const weights = volumeWeights(graph, pos);
  graph.relax();
  const outsideWeight = weights.get(OUTSIDE_ID) || 0;

  // ------------------------------------------------------------ diffuse bed
  // Walls only. Apertures are rendered positionally below and must not be counted twice.
  let dE = 0, dCutE = 0;
  let rt60 = 0, wetSum = 0;
  for (const [id, w] of weights) {
    const v = graph.volumes.get(id);
    if (!v) continue;
    const g = v.outside ? 1 : v.transmission.gain;
    const c = v.outside ? 20000 : v.transmission.cutoff;
    dE += w * g * g;
    dCutE += w * g * g * c;
    rt60 += w * (v.outside ? 0 : v.rt60);
    // A big room is wetter than a cupboard; outdoors is bone dry.
    wetSum += w * (v.outside ? 0.02 : clamp(0.18 + v.rt60 * 0.45, 0.1, 0.7));
  }
  // How much of the rain gets here, as a pure coefficient (1 outdoors), before intensity.
  const transmission = Math.sqrt(dE);
  // ...and how loud it therefore is. Scaling by rain here is not decoration: without it the rain
  // bed played at full level in every weather state, so "calm" was a downpour with the wind turned
  // off. The offline thunder test caught it, because it rendered with rain at zero and still got a
  // face full of rain.
  const diffuse = {
    gain: transmission * weather.rain,
    cutoff: dE > 1e-12 ? dCutE / dE : 20000,
  };

  // ------------------------------------------------------- aperture emitters
  // The whole point: an opening is not "the wall attenuates less", it is its own sound source,
  // sitting at a location, which is why cracking the window puts the noise AT the window.
  // Accumulated PER OPENING, not per (volume, opening) pair.
  //
  // A door belongs to both rooms it joins, so while you are blended across its threshold it turns
  // up in two volumes' aperture lists. Emitting it twice put two entries in the frame that both
  // wrote to the same smoothed emitter, stepping it twice in one frame and doubling its slew rate
  // — a 2.2 dB lurch from a 1.1 dB limit, in exactly one spot in the world.
  const byOpening = new Map();
  const right = listener.right || [1, 0, 0];
  for (const [id, w] of weights) {
    const v = graph.volumes.get(id);
    if (!v || v.outside) continue;
    for (const a of v.apertures) {
      const other = graph.volumes.get(a.otherId);
      if (!other) continue;
      const t = a.opening.transmission();
      // Power radiated through a hole scales with its area, so amplitude scales with the root of
      // it. Without this a shut 1 m² pane was as loud as a whole wall, and the small north window
      // was as present as the door.  Reference is 2 m² — one door.
      const areaScale = Math.sqrt(a.opening.area / 2);
      const gain = other.ambient.gain * dbToGain(t.attenDb + mix.portalDb) * areaScale * w * weather.rain;
      const wh = a.opening.whistle();
      const cutoff = Math.min(other.ambient.cutoff, t.cutoff);

      let e = byOpening.get(a.opening);
      if (!e) {
        const d = sub(a.opening.center, pos);
        const dist = Math.max(0.4, len(d));
        e = {
          name: a.opening.name,
          opening: a.opening,
          pos: a.opening.center.slice(),
          gain: 0,
          cutoff,
          cutNum: 0,
          distance: dist,
          // Signed left/right, for the HUD and for reasoning about stereo with no audio context.
          pan: clamp(dot([d[0] / dist, d[1] / dist, d[2] / dist], right), -1, 1),
          whistleFreq: wh.freq,
          whistleGain: 0,
        };
        byOpening.set(a.opening, e);
      }
      e.gain += gain;
      e.cutNum += gain * cutoff;
      e.whistleGain += wh.gain * weather.wind * w;
    }
  }
  const apertures = [];
  for (const e of byOpening.values()) {
    if (e.gain < 1e-4 && e.whistleGain < 1e-4) continue;
    if (e.gain > 0) e.cutoff = e.cutNum / e.gain;
    delete e.cutNum;
    apertures.push(e);
  }
  // Outdoors, openings still leak room-sound out at you, but there is nothing interesting in a
  // quiet cabin, so those are skipped above — outdoor listeners hear the direct bed instead.

  // ------------------------------------------------------------- roof drumming
  // Inaudible outdoors, by construction: gain is scaled by how much indoors you are.
  const r = roofCoverage(grid, pos[0], pos[1], pos[2]);
  const indoorness = 1 - outsideWeight;
  const roof = {
    gain: weather.rain * indoorness * r.cover * r.prox * dbToGain(mix.roofDb),
    cover: r.cover,
    distance: r.distance,
    material: r.material,
    hit: r.cover > 0.01,
  };

  // ----------------------------------------------------------------- wind
  // Wind is lower in frequency than rain, so it gets through a wall far more easily. Indoors it is
  // the low moan under everything; outdoors it is in your ears.
  // NB this reads `transmission`, not diffuse.gain — the wind has its own intensity and must not
  // fall silent indoors just because it has stopped raining.
  const windCut = Math.min(diffuse.cutoff * 2.2, 20000);
  const wind = {
    gain: weather.wind * dbToGain(mix.windDb)
      * (outsideWeight + (1 - outsideWeight) * Math.min(1, transmission * 2.4)),
    cutoff: windCut,
  };

  return {
    weights,
    outsideWeight,
    volumeId: [...weights.entries()].sort((a, b) => b[1] - a[1])[0][0],
    // How much of ANY outdoor sound reaches here through the walls, as a bare coefficient with no
    // weather intensity in it (1 outdoors). diffuse.gain is this times the rain, which makes it
    // useless to anything that is not rain — thunder in particular, which has to be shapeable by
    // the room during a dry storm.
    transmission,
    diffuse,
    apertures,
    roof,
    wind,
    reverb: { rt60, wet: wetSum },
    weather,
  };
}

/**
 * Collapse a frame to scalars, so continuity can be asserted on something meaningful.
 * Emitters are rolled in with inverse-distance falloff, matching the panner's rolloff.
 *
 * `storm` and `total` are reported separately, and the gap between them IS the thesis of the whole
 * project: going inside cuts the storm by more than 20 dB but the total by far less, because the
 * roof hands you a sound you could not hear outdoors at all. An interior that only subtracts is
 * a muffled exterior; one that also adds is shelter. See BUILD_PROMPT §0.
 */
export function summarise(frame) {
  let e = frame.diffuse.gain * frame.diffuse.gain;
  let cutE = e * frame.diffuse.cutoff;
  for (const a of frame.apertures) {
    const g = a.gain / Math.max(1, a.distance);
    e += g * g;
    cutE += g * g * a.cutoff;
  }
  const stormE = e, stormCutE = cutE;
  const roofE = frame.roof.gain * frame.roof.gain * 0.5;
  e += roofE;
  cutE += roofE * 1800;
  return {
    gain: Math.sqrt(e),
    cutoff: e > 1e-12 ? cutE / e : 20000,
    storm: Math.sqrt(stormE),
    stormCutoff: stormE > 1e-12 ? stormCutE / stormE : 20000,
    rt60: frame.reverb.rt60,
    wet: frame.reverb.wet,
    roof: frame.roof.gain,
    outsideWeight: frame.outsideWeight,
  };
}
