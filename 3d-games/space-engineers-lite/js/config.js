/** Tunables (dev sliders + defaults). Ported from Space Game / cracker.html */

export const CONFIG = {
  planetRadius: 32000,
  gravityCutoff: 96000,
  gravityForce: 9.8,
  largeGridSize: 2.5,
  smallGridSize: 0.5,
  playerSpeed: 10.0,
  jetpackSpeed: 50.0,
  thrustPower: 300.0,
  shipRotSpeed: 1.0,
  batteryCapacity: 1000.0,
  thrusterDrain: 2.0,
  rechargeRate: 1.0,
  viewDistance: 2000000,
  /** Scales most “per second” gameplay (thrust feel, etc.) — kept for compatibility. */
  timeScale: 1.0,
  /** Multiplier on each planet’s `orbitSpeed` (revolution around the sun). Separate from day length. */
  orbitAroundSunMul: 1.0,
  /** Axial spin in rad/s (day/night) before `planetSpinMul`. */
  planetSpinSpeed: 0.012,
  /** Multiplier on planet day length only (mesh + surface coupling). */
  planetSpinMul: 1.0,
  /**
   * Corotating air: while inside this height above local terrain (m) with jetpack OFF,
   * you spin with the planet like the lower atmosphere — avoids the ground “ramming”
   * you when you jump or short-hop.
   */
  atmosphereThicknessM: 2800,
};

export function updateConfig(key, val) {
  const v = parseFloat(val);
  if (!Number.isFinite(v)) return;
  CONFIG[key] = v;
  const map = {
    thrustPower: "val-thrust",
    gravityForce: "val-grav",
    timeScale: "val-time",
    orbitAroundSunMul: "val-orbit-sun",
    planetSpinMul: "val-planet-spin",
    planetSpinSpeed: "val-planet-spin-base",
    jetpackSpeed: "val-jet",
    atmosphereThicknessM: "val-atm",
  };
  const id = map[key];
  if (id) {
    const el = document.getElementById(id);
    if (el) el.textContent = String(val);
  }
}
