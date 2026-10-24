import { PLANET_CENTER_Y, PLANET_RADIUS, surfaceY } from "./surfaceMath";

const CX = 0;
const CY = PLANET_CENTER_Y;
const CZ = 0;

/** Deep-space max speed (m/s) after `speedMul` in {@link stepInterstellarVelocity}. Playtest tuning. */
export const INTERSTELLAR_V_MAX = 250_000;

/** Meters of separation from the surface when thrusters first engage. */
export const FLIGHT_LIFT = 8;

/** Base free-flight speed (world units / second); multiplied by `flight.speedMul` and sim time scale. */
export const FLIGHT_BASE_SPEED = 28;

/** Do not get closer to the planet center than this (keeps the core off the collider / mesh). */
export const FLIGHT_R_MIN = PLANET_RADIUS + 0.4;

/** Far clip for low-orbit shell (inner SOI flight, no interstellar velocity). */
export const FLIGHT_R_MAX = 2800;

/** Deep-space upper bound — allows “overshoot” travel without leaving the local sim shell. */
export const INTERSTELLAR_R_MAX = 10_000_000;

/**
 * M1: if the command core is in spaceflight and farther than this (world units) from the planet
 * center, the surface economy sim is frozen — you can open maps without autolanding.
 * Tuned a bit past low-orbit hops so "near" flight still allows the base to tick.
 */
export const DEEP_ORBIT_R = 320;

function smoothstep01(t: number): number {
  const x = Math.max(0, Math.min(1, t));
  return x * x * (3 - 2 * x);
}

/**
 * M4: 0 in “deep” SOI, 1 in inner (low) orbit — for atmosphere and star VFX.
 * Transitions around {@link DEEP_ORBIT_R}.
 */
export function innerSoiBlend01(r: number): number {
  return 1 - smoothstep01((r - DEEP_ORBIT_R * 0.88) / (DEEP_ORBIT_R * 0.22));
}

/**
 * M4: short HUD string for sphere-of-influence (distance from world center, meters).
 */
export function soiHudLabel(
  flight: { active: boolean },
  x: number,
  y: number,
  z: number
): string {
  if (!flight.active) return "—";
  const r = radialDistanceFromPlanetCenter(x, y, z);
  const m = Math.round(r);
  if (r > FLIGHT_R_MAX * 0.92) return `Rim · ${m}m`;
  if (r > DEEP_ORBIT_R) return `Deep SOI · ${m}m`;
  return `Inner SOI · ${m}m`;
}

/**
 * World-space unit vector from the planet center through `(x, y, z)`.
 * Safe at any distance from the origin.
 */
export function radialUpAt(x: number, y: number, z: number): { ux: number; uy: number; uz: number } {
  const dx = x - CX;
  const dy = y - CY;
  const dz = z - CZ;
  const len = Math.hypot(dx, dy, dz) || 1e-9;
  return { ux: dx / len, uy: dy / len, uz: dz / len };
}

/**
 * If the core drifts into the rock or too far, snap it to the free-flight shell.
 */
export function clampToFlightShell(x: number, y: number, z: number): { x: number; y: number; z: number } {
  return clampToRadiusShell(x, y, z, FLIGHT_R_MAX);
}

/**
 * Interstellar / deep-SOI shell: min inner radius, max {@link INTERSTELLAR_R_MAX}.
 */
export function clampToInterstellarShell(x: number, y: number, z: number): { x: number; y: number; z: number } {
  return clampToRadiusShell(x, y, z, INTERSTELLAR_R_MAX);
}

function clampToRadiusShell(
  x: number,
  y: number,
  z: number,
  rMax: number
): { x: number; y: number; z: number } {
  const dx = x - CX;
  const dy = y - CY;
  const dz = z - CZ;
  const len = Math.hypot(dx, dy, dz) || 1e-9;
  if (len < FLIGHT_R_MIN) {
    const s = FLIGHT_R_MIN / len;
    return { x: CX + dx * s, y: CY + dy * s, z: CZ + dz * s };
  }
  if (len > rMax) {
    const s = rMax / len;
    return { x: CX + dx * s, y: CY + dy * s, z: CZ + dz * s };
  }
  return { x, y, z };
}

/**
 * Deep-orbit: velocity integration with strong brakes (S) and forward thrust (W). Units tuned for sim scale.
 */
export function stepInterstellarVelocity(
  x: number,
  y: number,
  z: number,
  vx: number,
  vy: number,
  vz: number,
  look: { x: number; y: number; z: number },
  right: { x: number; y: number; z: number },
  radial: { x: number; y: number; z: number },
  fwd: number,
  strafe: number,
  climb: number,
  brake: number,
  speedMul: number,
  timeScale: number,
  delta: number,
  /** Holding W (fwd) or Space/E (climb) increases effective thrust so speed ramps up in a “charged” way. */
  thrustRamps?: { fwdMul?: number; climbMul?: number }
): { x: number; y: number; z: number; vx: number; vy: number; vz: number } {
  const dt = delta * timeScale;
  const mul = Math.max(0.25, speedMul);
  const thrust = 88 * mul;
  const fwdM = Math.max(1, Math.min(3.5, Number(thrustRamps?.fwdMul ?? 1) || 1));
  const climbM = Math.max(1, Math.min(3.5, Number(thrustRamps?.climbMul ?? 1) || 1));
  let ax = 0;
  let ay = 0;
  let az = 0;
  if (fwd !== 0) {
    ax += look.x * fwd * thrust * fwdM;
    ay += look.y * fwd * thrust * fwdM;
    az += look.z * fwd * thrust * fwdM;
  }
  if (strafe !== 0) {
    ax += right.x * strafe * thrust;
    ay += right.y * strafe * thrust;
    az += right.z * strafe * thrust;
  }
  if (climb !== 0) {
    ax += radial.x * climb * thrust * climbM;
    ay += radial.y * climb * thrust * climbM;
    az += radial.z * climb * thrust * climbM;
  }
  let nvx = vx + ax * dt;
  let nvy = vy + ay * dt;
  let nvz = vz + az * dt;
  if (brake > 0) {
    const k = Math.exp(-5.5 * dt * brake);
    nvx *= k;
    nvy *= k;
    nvz *= k;
  } else {
    const sp = Math.hypot(nvx, nvy, nvz);
    const vMax = INTERSTELLAR_V_MAX * mul;
    if (sp > vMax) {
      const s = vMax / sp;
      nvx *= s;
      nvy *= s;
      nvz *= s;
    }
  }
  let nx = x + nvx * dt;
  let ny = y + nvy * dt;
  let nz = z + nvz * dt;
  const p0 = clampToInterstellarShell(nx, ny, nz);
  nx = p0.x;
  ny = p0.y;
  nz = p0.z;
  const rdx = nx - CX;
  const rdy = ny - CY;
  const rdz = nz - CZ;
  const rlen = Math.hypot(rdx, rdy, rdz) || 1e-9;
  const urx = rdx / rlen;
  const ury = rdy / rlen;
  const urz = rdz / rlen;
  const vOut = nvx * urx + nvy * ury + nvz * urz;
  if (rlen > INTERSTELLAR_R_MAX * 0.998 && vOut > 0) {
    nvx -= urx * vOut;
    nvy -= ury * vOut;
    nvz -= urz * vOut;
  }
  return { x: nx, y: ny, z: nz, vx: nvx, vy: nvy, vz: nvz };
}

/**
 * Project onto the exterior of the big sphere (same as gameplay surface shell).
 * Used when cutting thrusters to land on the logical surface grid.
 */
export function projectOntoPlanetExterior(
  x: number,
  y: number,
  z: number
): { x: number; y: number; z: number } {
  const dx = x - CX;
  const dy = y - CY;
  const dz = z - CZ;
  const len = Math.hypot(dx, dy, dz) || 1e-9;
  const s = PLANET_RADIUS / len;
  return { x: CX + dx * s, y: CY + dy * s, z: CZ + dz * s };
}

/**
 * Point just above the ground patch for a standing core (XZ gameplay coordinates).
 * Used to compute the first lift-off point along the local outward normal.
 */
/** Straight-line distance from the planet’s center to a world-space point. */
export function radialDistanceFromPlanetCenter(x: number, y: number, z: number): number {
  return Math.hypot(x - CX, y - CY, z - CZ);
}

/** M1: far enough from the world that the base is treated as in “deep” space (map stays non-destructive). */
export function isDeepOrbitFlight(
  flight: { active: boolean },
  x: number,
  y: number,
  z: number
): boolean {
  return flight.active && radialDistanceFromPlanetCenter(x, y, z) > DEEP_ORBIT_R;
}

export function liftPointFromSurface(x: number, z: number, lift: number = FLIGHT_LIFT): { x: number; y: number; z: number } {
  const y0 = surfaceY(x, z);
  const n = radialUpAt(x, y0, z);
  return clampToFlightShell(
    x + n.ux * lift,
    y0 + n.uy * lift,
    z + n.uz * lift
  );
}
