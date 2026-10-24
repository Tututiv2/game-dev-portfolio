// THE FLIGHT MODEL. Everything else is UI around this file.
//
// PURE: no DOM, no canvas, no rAF, no localStorage, no Math.random. Check 1 in the test
// suite greps for those and fails the whole run if one appears — because that grep is what
// keeps `node tests/run-tests.mjs` able to fly the real model with no browser, and losing
// that means losing the ability to verify feel at all.
//
// Units are metres, seconds, radians. ALWAYS. Feet and mph exist only in hud.js (§4.2).

import { clamp, wrapPi, crossDown, crossUp } from './mathx.js';
import { SURFACE } from './world.js';

export const STATE = {
  RAMP: 'ramp',
  FLIGHT: 'flight',
  TUMBLE: 'tumble',
  WATER: 'water',           // floating on the surface, bleeding off the last of the speed
  SUBMERGED: 'submerged',
  GROUND: 'ground',
  REST: 'rest',
};

// ── aero primitives (exported so tests and the debug overlay read the SAME code) ────────

/** Cl = CL_MAX * sin(2a). NOT a linear curve — see §4.3 and landmine 6. */
export function liftCoeff(alpha, T) { return T.CL_MAX * Math.sin(2 * alpha); }

/** Cd = cd0 + K * a². */
export function dragCoeff(alpha, cd0Eff, T) { return cd0Eff + T.K_INDUCED * alpha * alpha; }

/**
 * The angle of attack that maximises sin(2a) / (cd0 + K a²) — i.e. best glide.
 * Bisection on the derivative, which is monotone over the search interval.
 * Depends only on cd0/K, so it is the SAME angle for every glider (§12.7).
 */
export function bestGlideAlpha(cd0Eff, T) {
  const g = (a) => 2 * Math.cos(2 * a) * (cd0Eff + T.K_INDUCED * a * a)
                 - 2 * T.K_INDUCED * a * Math.sin(2 * a);
  let lo = T.AP_ALPHA_LO, hi = T.AP_ALPHA_HI;
  for (let i = 0; i < T.AP_SEARCH_ITERS; i++) {
    const mid = (lo + hi) * 0.5;
    if (g(mid) > 0) lo = mid; else hi = mid;
  }
  return (lo + hi) * 0.5;
}

/** Best-glide lift-to-drag ratio for a loadout. Test 7 asserts 4.2/8.8/12.4/16.2/19.8. */
export function glideRatio(L, T) {
  const cd0Eff = T.CD0 * L.cd0Mul;
  const a = bestGlideAlpha(cd0Eff, T);
  return (L.glider.lift * liftCoeff(a, T)) / dragCoeff(a, cd0Eff, T);
}

/** Terminal velocity of a clean nose-down dive: sqrt(G / cd0). Test 5. */
export function terminalSpeed(L, T) { return Math.sqrt(T.G / (T.CD0 * L.cd0Mul)); }

// ── state ──────────────────────────────────────────────────────────────────────────────

export function createState(L, T, opts = {}) {
  return {
    x: 0, y: L.rampHeightM,
    vx: 0, vy: 0,
    theta: T.RAMP_ANGLE,
    omega: 0,
    fuel: L.fuelSeconds,
    gliderHp: 1,
    state: STATE.RAMP,

    // per-step scratch the HUD and the debug overlay read
    speed: 0, alpha: 0, cd: 0, cl: 0, resistance: 0, thrusting: false, stressed: false,

    // run stats
    t: 0, rampT: 0, restTimer: 0, endTimer: 0,
    prevX: 0, prevY: L.rampHeightM,
    bounces: 0, groundBounces: 0,
    maxAlt: L.rampHeightM, maxSpeed: 0, airTime: 0, dist: 0,
    reverseM: 0, diveM: 0, maxDepth: 0,
    destroyed: [], hits: 0, bombUsed: false,
    finished: false, endReason: null,

    collectEvents: opts.collectEvents !== false,
    events: [],
  };
}

function emit(p, e) { if (p.collectEvents) p.events.push(e); }

function end(p, reason, T) {
  if (p.finished) return;
  p.finished = true;
  p.state = STATE.REST;
  p.endReason = reason;
  p.vx = 0; p.vy = 0;
  emit(p, { type: 'run:end', reason });
}

// ── the ramp (§4.7 — exit speed is SET, not integrated) ─────────────────────────────────
//
// The path is two pieces walked backwards from the top: a slope at RAMP_ANGLE down to sea
// level, then a flat run-up. Splitting it this way is what stops a tier-1 ramp with tier-5
// acceleration from burying its own base under the ocean.

export function rampPath(L, T) {
  const exit = L.exitMph / T.MPH_PER_MS;
  const total = 0.5 * exit * T.RAMP_SLIDE_TIME;     // s = ½at² with a = exit/t
  const rise = Math.max(0, L.rampHeightM - T.SEA_LEVEL_Y);
  const slope = rise / Math.sin(T.RAMP_ANGLE);
  const ramp = Math.min(total, slope);
  return { exit, total, ramp, flat: Math.max(0, total - slope), accel: exit / T.RAMP_SLIDE_TIME };
}

/** Position and heading `d` metres back along the ramp path from the top. */
export function rampPointAt(d, L, T, path = rampPath(L, T)) {
  const cx = Math.cos(T.RAMP_ANGLE), cy = Math.sin(T.RAMP_ANGLE);
  if (d <= path.ramp) {
    return { x: -d * cx, y: L.rampHeightM - d * cy, theta: T.RAMP_ANGLE };
  }
  const flat = d - path.ramp;
  return { x: -path.ramp * cx - flat, y: T.SEA_LEVEL_Y, theta: 0 };
}

function stepRamp(p, L, T) {
  const path = rampPath(L, T);
  p.rampT += T.DT;
  const tt = Math.min(p.rampT, T.RAMP_SLIDE_TIME);
  const travelled = 0.5 * path.accel * tt * tt;
  const v = path.accel * tt;
  const pt = rampPointAt(Math.max(0, path.total - travelled), L, T, path);
  p.x = pt.x; p.y = pt.y; p.theta = pt.theta;
  p.vx = v * Math.cos(pt.theta); p.vy = v * Math.sin(pt.theta);

  if (p.rampT >= T.RAMP_SLIDE_TIME) {
    p.state = STATE.FLIGHT;
    p.x = 0; p.y = L.rampHeightM; p.theta = T.RAMP_ANGLE;
    p.vx = path.exit * Math.cos(T.RAMP_ANGLE);
    p.vy = path.exit * Math.sin(T.RAMP_ANGLE);
    emit(p, { type: 'launch', speed: path.exit });
  }
}

// ── surfaces ───────────────────────────────────────────────────────────────────────────

function snapTumble(p, T, dir) {
  p.state = STATE.TUMBLE;
  p.omega = T.TUMBLE_SPIN * dir;
}

function hitWater(p, L, T) {
  p.y = T.SEA_LEVEL_Y;
  const speed = Math.hypot(p.vx, p.vy);
  // Angle below horizontal, correct in both travel directions (flying backwards is a medal).
  const phi = Math.atan2(-p.vy, Math.abs(p.vx));

  if (phi < T.SKIP_ANGLE_MAX && speed > T.SKIP_MIN_SPEED) {
    p.vy = -p.vy * T.SKIP_RESTITUTION;
    p.vx *= T.SKIP_SPEED_LOSS;
    p.vy *= T.SKIP_SPEED_LOSS;
    p.bounces++;
    emit(p, { type: 'water:skip', n: p.bounces, speed, phi });
  } else {
    p.state = STATE.SUBMERGED;
    emit(p, { type: 'water:dive', speed, phi });
  }
}

function floatOn(p, T) {
  p.state = STATE.WATER;
  p.y = T.SEA_LEVEL_Y;
  p.vy = 0;
  p.omega = 0;
}

function hitGround(p, L, T, gy) {
  p.y = gy;
  const speed = Math.hypot(p.vx, p.vy);
  const phi = Math.atan2(-p.vy, Math.abs(p.vx));
  const shallow = phi < T.GROUND_SKIP_ANGLE;
  emit(p, { type: 'ground:impact', speed, phi, shallow });

  if (!shallow) {
    if (speed >= T.PLANET_CRUSH_SPEED) emit(p, { type: 'crash:planet', speed });
    else if (speed >= T.SKULL_CRUSH_SPEED) emit(p, { type: 'crash:skull', speed });
    if (speed > T.GROUND_HARD_SPEED) snapTumble(p, T, p.vx < 0 ? 1 : -1);
  }

  const rebound = -p.vy * T.GROUND_RESTITUTION;
  if (rebound > T.REST_SPEED) {
    p.vy = rebound;
    p.vx *= shallow ? T.GROUND_SKID_LOSS : T.GROUND_SLAM_LOSS;
    if (shallow) p.groundBounces++;
  } else {
    p.vy = 0;
    p.state = STATE.GROUND;
    p.omega = 0;
  }
}

function resolveSurface(p, L, world, T) {
  const gy = world.groundYAt(p.x);

  if (p.state === STATE.GROUND) {
    if (gy === null) { p.state = STATE.FLIGHT; return; }   // skidded off the shelf edge
    p.y = gy;
    if (p.vy < 0) p.vy = 0;
    return;
  }

  if (p.state === STATE.WATER) {
    if (gy !== null) { p.state = STATE.GROUND; p.y = gy; return; }
    p.y = T.SEA_LEVEL_Y;
    p.vy = 0;
    return;
  }

  if (p.state === STATE.SUBMERGED) {
    // At or above the surface means NOT submerged — deliberately a position test and not a
    // crossing test. A dive entered with a tiny downward velocity (the tail of a skip chain)
    // sits at exactly y = 0, and buoyancy reverses vy before y ever goes negative, so a
    // crossing test never fires and the penguin keeps its +0.6 G and 12x drag while climbing
    // through open air. It reads as a submarine in the sky and it is worth 4,000 ft.
    if (p.y >= T.SEA_LEVEL_Y) {
      p.y = T.SEA_LEVEL_Y;
      if (Math.hypot(p.vx, p.vy) < T.SKIP_MIN_SPEED) floatOn(p, T);
      else { p.state = STATE.FLIGHT; emit(p, { type: 'water:surface' }); }
      return;
    }
    const floorY = T.SEA_LEVEL_Y - T.MAX_DIVE_DEPTH_M;
    if (p.y < floorY) { p.y = floorY; p.vy = Math.abs(p.vy) * T.GROUND_RESTITUTION; }
    if (p.vy < 0) p.diveM -= p.vy * T.DT;
    const depth = T.SEA_LEVEL_Y - p.y;
    if (depth > p.maxDepth) p.maxDepth = depth;
    return;
  }

  // airborne
  if (gy !== null) {
    if (crossDown(p.prevY, p.y, gy) >= 0) hitGround(p, L, T, gy);
    return;
  }
  if (world.surfaceAt(p.x) === SURFACE.WATER && crossDown(p.prevY, p.y, T.SEA_LEVEL_Y) >= 0) {
    hitWater(p, L, T);
  }
}

// ── the step ───────────────────────────────────────────────────────────────────────────

export function step(p, input, L, world, T, hooks) {
  if (p.state === STATE.REST) return p;
  const dt = T.DT;
  p.t += dt;
  if (p.t > T.MAX_RUN_TIME) { end(p, 'timeout', T); return p; }

  p.prevX = p.x; p.prevY = p.y;

  if (p.state === STATE.RAMP) { stepRamp(p, L, T); }
  else {
    const airborne = p.state === STATE.FLIGHT || p.state === STATE.TUMBLE;
    const sub = p.state === STATE.SUBMERGED;

    // ── 0. rotation. Direct angular control, not torque (§4.4) ──
    if (p.state === STATE.FLIGHT || sub) {
      if (input.left) p.theta += T.ROT_RATE * dt;
      if (input.right) p.theta -= T.ROT_RATE * dt;
      p.theta = clamp(p.theta, T.THETA_MIN, T.THETA_MAX);
    } else if (p.state === STATE.TUMBLE) {
      p.theta = wrapPi(p.theta + p.omega * dt);
      p.omega -= p.omega * T.TUMBLE_DAMP * dt;
    } else {
      p.theta -= clamp(p.theta, -T.ROT_RATE * dt, T.ROT_RATE * dt);   // settle level at rest
    }

    // ── 1..4. forces, in order. Drag sees velocity BEFORE this step's thrust (§4.3) ──
    const speed = Math.hypot(p.vx, p.vy);
    const moving = speed > T.EPS_SPEED;
    const vAngle = moving ? Math.atan2(p.vy, p.vx) : p.theta;   // landmine 7: never atan2(0,0)
    const alpha = wrapPi(p.theta - vAngle);

    let ax = 0, ay = -T.G;
    if (sub) ay += T.BUOYANCY * T.G;

    const cd0Eff = T.CD0 * L.cd0Mul;
    const cdMul = sub ? T.WATER_DRAG_MUL : 1;
    const cd = dragCoeff(alpha, cd0Eff, T) * cdMul;
    const liftOn = p.state === STATE.FLIGHT && p.gliderHp > 0;
    const cl = liftOn ? liftCoeff(alpha, T) : 0;

    // Aero is applied IN THE VELOCITY FRAME: drag scales the magnitude, lift rotates the
    // direction. Both are the exact solutions of their own ODE over dt, which is why this is
    // not the plain Euler pair §4.3 step 5 describes. The reason is not elegance — Euler's
    // (a·dt)²/2v term makes a perpendicular force ADD energy, and with the Prototype wing that
    // artifact turns positive at ~153 m/s, i.e. 342 mph, which is INSIDE its 400 mph rating.
    // A player holding a hard turn up there would accelerate for free. Rotating the velocity
    // instead means lift cannot change speed at all, ever, at any dt. See CALIBRATION.md D-1.
    if (moving) {
      const vNew = speed / (1 + cd * speed * dt);       // exact for dv/dt = -cd·v²
      const psi = vAngle + L.glider.lift * cl * speed * dt;
      p.vx = vNew * Math.cos(psi);
      p.vy = vNew * Math.sin(psi);
    }

    p.thrusting = false;
    if (input.thrust && p.fuel > 0 && L.thrust > 0 && p.state === STATE.FLIGHT) {
      ax += L.thrust * Math.cos(p.theta);
      ay += L.thrust * Math.sin(p.theta);
      p.fuel = Math.max(0, p.fuel - dt);
      p.thrusting = true;
    }

    if (p.state === STATE.WATER) { ax = -p.vx * T.WATER_SURFACE_DRAG; ay = 0; }
    if (p.state === STATE.GROUND) { ax = -p.vx * T.GROUND_FRICTION; ay = 0; }

    // ── 5. integrate. Semi-implicit Euler: velocity first, position from the NEW velocity ──
    p.vx += ax * dt; p.vy += ay * dt;
    p.x += p.vx * dt; p.y += p.vy * dt;

    // scratch for the HUD / debug overlay / autopilot
    p.speed = speed; p.alpha = alpha; p.cd = cd; p.cl = cl;
    p.resistance = moving && airborne
      ? T.DRAG_DISPLAY_SCALE * (T.K_INDUCED * alpha * alpha * speed * speed) / T.G
      : 0;

    // ── glider structural integrity (§4.6) — speed is a risk you manage, not a clamp ──
    p.stressed = false;
    if (T.gliderBreaks && L.glider.breakable && p.gliderHp > 0 && p.state === STATE.FLIGHT) {
      const over = speed * T.MPH_PER_MS - L.glider.vMaxMph;
      if (over > 0) {
        p.stressed = true;
        p.gliderHp -= (over / L.glider.vMaxMph) * T.GLIDER_STRESS_RATE * dt;
        if (p.gliderHp <= 0) {
          p.gliderHp = 0;
          snapTumble(p, T, p.vx < 0 ? 1 : -1);
          emit(p, { type: 'glider:snap', speed });
        }
      }
    }

    // ── collision. Swept, always (§4.9) ──
    if (hooks && hooks.obstacles) hooks.obstacles(p, L, world, T);
    resolveSurface(p, L, world, T);
  }

  // ── stats ──
  if (p.y > p.maxAlt) p.maxAlt = p.y;
  const sp = Math.hypot(p.vx, p.vy);
  if (sp > p.maxSpeed) p.maxSpeed = sp;
  if (p.x > p.dist) p.dist = p.x;
  if (p.x < p.prevX) p.reverseM += p.prevX - p.x;
  if (p.state === STATE.FLIGHT || p.state === STATE.TUMBLE) p.airTime += dt;

  // ── the run ends by exhaustion, not by a timer (§4.10) ──
  const settled = p.state === STATE.GROUND || p.state === STATE.WATER;
  if (settled && sp < T.REST_SPEED) {
    p.restTimer += dt;
    if (p.restTimer >= T.REST_TIME) end(p, 'rest', T);
  } else {
    p.restTimer = 0;
  }
  return p;
}

/** Roll a whole flight forward headlessly. Used by the tests, the calibrator and replays. */
export function simulate(L, world, T, policy, opts = {}) {
  const p = createState(L, T, opts);
  const hooks = opts.hooks;
  const maxSteps = opts.maxSteps || Math.ceil(T.MAX_RUN_TIME / T.DT);
  const input = { left: false, right: false, thrust: false };
  for (let i = 0; i < maxSteps && !p.finished; i++) {
    const cmd = policy ? policy(p, L, T, world) : null;
    input.left = !!(cmd && cmd.left);
    input.right = !!(cmd && cmd.right);
    input.thrust = !!(cmd && cmd.thrust);
    step(p, input, L, world, T, hooks);
  }
  return p;
}
