// A competent player, expressed as a pure function of state.
//
// This is not a convenience. Tests 12 (monotonicity) and 13 (reachability) are claims about
// what a PLAYER can earn, and the only way to make those claims falsifiable is to have a
// player that is the same every time. It also drives tools/calibrate.mjs, and the sandbox
// draws its line so you can see what the model thinks the optimal glide looks like.
//
// Two independent axes, because a real player has both to work out:
//
//   glideMode  'alpha'  hold the best-glide angle of attack, always
//              'path'   zoom-climb off any excess speed, then hold the best-glide FLIGHT
//                       PATH at whatever alpha that costs
//   thrustMode 'hold'   save the fuel for the save
//              'apex'   burn the moment altitude starts to drop — the community's words
//              'launch' dump it off the ramp, where speed is highest
//
// 'path' exists because 'alpha' alone made better gliders read as TRAP UPGRADES in check 12.
// A fixed alpha at above-equilibrium speed generates surplus lift, so the penguin phugoids —
// climb, slow, sink, speed up — and the stronger the wing the more violent the oscillation
// and the more range it costs. Holding the path instead asks for exactly the lift the
// trajectory needs, and a better wing supplies it at a SMALLER alpha, hence less induced
// drag. That is precisely the argument for why a better wing is better, so a player model
// that cannot express it will report the Prototype as a 14% pay cut. Measured; it did.

import { clamp, wrapPi } from './mathx.js';
import { STATE, bestGlideAlpha, glideRatio, liftCoeff } from './physics.js';
import { TUNING } from '../data/tuning.js';

export function makeAutopilot(T, opts = {}) {
  const useRockets = opts.useRockets !== false;
  const glideMode = opts.glideMode || 'path';
  const thrustMode = opts.thrustMode || 'hold';
  const zoomPath = opts.zoomPath === undefined ? T.AP_ZOOM_PATH : opts.zoomPath;
  const entry = opts.entry === undefined ? T.AP_ENTRY_ANGLE : opts.entry;
  let cachedCd0 = -1, cachedAlpha = 0, cachedLd = 0;

  function tuneFor(L) {
    const cd0Eff = T.CD0 * L.cd0Mul;
    if (cd0Eff !== cachedCd0) {
      cachedCd0 = cd0Eff;
      cachedAlpha = bestGlideAlpha(cd0Eff, T);
      cachedLd = glideRatio(L, T);
    }
  }

  // Seconds until this trajectory reaches the water, if nothing changes.
  // Everything near the surface keys off THIS rather than an absolute altitude. An absolute
  // one does not survive the range of flights in this game: AP_FLARE_ALT was 26 m, and a
  // mid-game run peaks at 12-15, so the autopilot spent the ENTIRE flight in the flare and
  // never glided once. Check 12 saw that as a trap upgrade on Air Resistance, because the
  // extra glide ratio it buys was simply never being used.
  function timeToWater(p, alt) {
    return p.vy < 0 ? alt / -p.vy : Infinity;
  }

  function burnNow(p, alt) {
    if (thrustMode === 'launch') return true;
    if (thrustMode === 'apex') return p.vy < 0;
    return timeToWater(p, alt) < T.AP_THRUST_TIME;
  }

  return function autopilot(p, L, T2, world) {
    const out = { left: false, right: false, thrust: false };
    if (p.state !== STATE.FLIGHT) return out;

    const speed = Math.hypot(p.vx, p.vy);
    if (speed <= T.EPS_SPEED) return out;
    tuneFor(L);
    const vAngle = Math.atan2(p.vy, p.vx);
    const alt = p.y - T.SEA_LEVEL_Y;

    // ── the cruise line ──
    let targetAlpha = cachedAlpha;
    if (glideMode === 'path' && L.glider.lift > 0) {
      const clHold = T.G * Math.cos(vAngle) / (L.glider.lift * speed * speed);
      if (clHold <= T.CL_MAX) {
        // ZOOM CLIMB, THEN GLIDE. Excess speed above the best-glide equilibrium is energy in
        // the wrong bank: spend it on altitude at a bounded climb angle, then settle onto the
        // best-glide path. Without this the model has no way to use a fast launch, and check
        // 12 reports MORE ACCELERATION as a trap upgrade — a 69 mph launch with a Prototype
        // wing scored $1,266 against $2,016 at 54 mph, because holding best-glide alpha at
        // that speed is a 3.5 G pull-up that stands the penguin on its tail and stalls it.
        const vBest = Math.sqrt(T.G / (L.glider.lift * liftCoeff(cachedAlpha, T)));
        const gammaWant = speed > vBest * T.AP_ZOOM_MARGIN
          ? zoomPath
          : -Math.atan2(1, cachedLd);
        const clWant = clamp(clHold + T.AP_PATH_GAIN * (gammaWant - vAngle), -T.CL_MAX, T.CL_MAX);
        targetAlpha = clamp(0.5 * Math.asin(clWant / T.CL_MAX),
          -T.AP_PATH_ALPHA_MAX, T.AP_PATH_ALPHA_MAX);
      }
      // else: too slow to hold ANY path — leave targetAlpha at best glide and let the nose
      // drop to trade height for speed. Without this the controller asks for more lift than
      // the wing has, pegs 45 deg of alpha, and turns the wing into a speed brake: the
      // penguin parachutes straight down from its own apex, 291 ft up and 89 ft downrange.
    }
    let wantThrust = false;

    // Alpha needed to steer the flight PATH toward `want`, with bounded authority.
    // Every command in this controller is a path command, because a fixed alpha is not a
    // fixed manoeuvre: the g it buys scales with lift·v², so 0.55 rad is a gentle nudge
    // behind cardboard at 20 m/s and a 7 G snap behind a Delta Wing at 40. Commanding it
    // flat stood the penguin on its tail a quarter-second off the ramp and sent the entire
    // rocket burn straight up — 291 ft of altitude and 89 ft of distance.
    const steer = (want, lo, hi) => clamp(T.AP_PATH_GAIN * (want - vAngle), lo, hi);

    // ── overrides, in priority order ──
    const stressing = L.glider.breakable
      && speed * T.MPH_PER_MS > L.glider.vMaxMph * T.AP_STRESS_MARGIN;

    if (stressing) {
      targetAlpha = T.AP_CLIMB_ALPHA;    // bleed speed before the wing goes — here the DRAG is the point
    } else if (useRockets && p.fuel > 0 && L.thrust > 0 && burnNow(p, alt)) {
      wantThrust = true;
      targetAlpha = steer(T.AP_CLIMB_PATH, 0, T.AP_CLIMB_ALPHA);
    } else if (timeToWater(p, alt) < T.AP_FLARE_TIME) {
      const wantVAngle = -T.SKIP_ANGLE_MAX * entry;
      if (vAngle < wantVAngle) targetAlpha = steer(wantVAngle, targetAlpha, T.AP_CLIMB_ALPHA);
    }

    const targetTheta = clamp(vAngle + targetAlpha, T.THETA_MIN, T.THETA_MAX);
    const err = wrapPi(targetTheta - p.theta);
    if (err > T.AP_DEADZONE) out.left = true;
    else if (err < -T.AP_DEADZONE) out.right = true;
    out.thrust = wantThrust;
    return out;
  };
}

/** Holds one fixed angle of attack forever. Used by the glide-ratio and resistance tests. */
export function makeHoldAlpha(targetAlpha, T) {
  return function holdAlpha(p) {
    const out = { left: false, right: false, thrust: false };
    if (p.state !== STATE.FLIGHT) return out;
    const speed = Math.hypot(p.vx, p.vy);
    if (speed <= T.EPS_SPEED) return out;
    const vAngle = Math.atan2(p.vy, p.vx);
    const err = wrapPi(clamp(vAngle + targetAlpha, T.THETA_MIN, T.THETA_MAX) - p.theta);
    if (err > T.AP_DEADZONE) out.left = true;
    else if (err < -T.AP_DEADZONE) out.right = true;
    return out;
  };
}

/**
 * Every line a competent player might try. `flyBest` runs them all and keeps the money.
 *
 * HOW HARD TO ZOOM-CLIMB is a real decision a player makes and gets better at, not a
 * constant, so it is one of the axes. It also has a mechanical job: the flight ends on
 * discrete water skips, so a single fixed policy makes payout a slightly jagged function of
 * the loadout, and check 12 reads one skip lost as a 3% trap upgrade. A player with three
 * climb angles to pick from routes around that the same way a real one does.
 */
export function buildLines(T) {
  const out = [];
  T.AP_ZOOM_ANGLES.forEach((zoomPath, zi) => {
    for (const entry of T.AP_ENTRY_ANGLES) {
      for (const thrustMode of ['hold', 'apex', 'launch']) {
        out.push({
          glideMode: 'path', zoomPath, entry, thrustMode,
          name: `${T.AP_ZOOM_LABELS[zi]}/${entry > T.AP_ENTRY_ANGLES[0] ? 'flat' : 'steep'}/${thrustMode}`,
        });
      }
    }
  });
  for (const thrustMode of ['hold', 'apex', 'launch']) {
    out.push({ glideMode: 'alpha', thrustMode, name: `alpha/${thrustMode}` });
  }
  return out;
}

export const LINES = buildLines(TUNING);
