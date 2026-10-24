// EVERY magic number in the project. §0.3 — if you are typing a float into a physics
// file, it belongs here instead. tests/run-tests.mjs check 2 enforces that mechanically:
// it greps sim/ for numeric literals outside {0, 1, 2, 0.5, -1} and fails the suite.
//
// §0.4 — tunables are expressed in the unit the PLAYER sees wherever there is a choice.
// Ramp tiers are an exit speed in mph, not an acceleration you integrate over a length.
//
// Values marked CALIBRATE are a complete, self-consistent, playable starting point.
// §11 is the procedure for pulling them onto the original; CALIBRATION.md is the log.

const DEG = Math.PI / 180;

export const TUNING = {
  // ── integration ────────────────────────────────────────────────────────────
  DT: 1 / 120,                  // 120 Hz: at 100 m/s a 60 Hz step travels 1.7 m and tunnels
  MAX_STEPS: 8,                 // spiral-of-death guard
  MAX_FRAME_DT: 0.25,           // alt-tab clamp — without it a 30 s tab-out hangs the browser

  // ── unit conversion (display only; sim is metres/seconds/radians) ──────────
  FT_PER_M: 3.28084,
  MPH_PER_MS: 2.23694,

  // ── world ─────────────────────────────────────────────────────────────────
  G: 22.0,                      // CALIBRATE. m/s². Arcade-exaggerated on purpose; 9.81 feels floaty
  SEA_LEVEL_Y: 0,
  SEGMENT_M: 200,               // world segment width (§14 seam 2)
  SHELF_BACK_M: 120,            // solid ice behind the ramp. Must exceed the tallest ramp's own
                                // horizontal run (36 m / tan 20 deg = 99 m) or the tier-5 ramp
                                // stands in open water. Also what flying BACKWARDS lands on.
  EPS_SPEED: 1e-6,              // below this, velocity has no direction — guards atan2(0,0) (landmine 7)
  EPS_TIME: 1e-9,

  // ── aero ──────────────────────────────────────────────────────────────────
  CD0: 0.0022,                  // CALIBRATE. baseline drag → dive terminal sqrt(G/CD0) = 100 m/s = 224 mph
  K_INDUCED: 0.020,             // CALIBRATE. drag growth with alpha²
  CL_MAX: 1.00,                 // peak of the sin(2a) lift curve
  DRAG_DISPLAY_SCALE: 1.25,     // makes optimal glide read 3.4% on the resistance gauge (§4.5)

  // ── control ───────────────────────────────────────────────────────────────
  ROT_RATE: 70 * DEG,           // CALIBRATE. rad/s. The single most feel-critical number in the game
  THETA_MIN: -Math.PI / 2,
  THETA_MAX: Math.PI / 2,

  // ── launch (§4.7 — exit speed is SET, not integrated) ─────────────────────
  RAMP_ANGLE: 20 * DEG,
  RAMP_SLIDE_TIME: 0.6,

  // ── glider integrity (§4.6) ───────────────────────────────────────────────
  gliderBreaks: true,           // flip to false if reference footage says LTF1 merely caps speed
  GLIDER_STRESS_RATE: 0.55,     // hp/s at 2× the rated max speed → ~1.8 s to snap
  TUMBLE_SPIN: 9.0,             // rad/s imparted when a glider snaps
  TUMBLE_DAMP: 0.35,            // 1/s exponential decay of tumble spin

  // ── water (§4.8) ──────────────────────────────────────────────────────────
  SKIP_ANGLE_MAX: 18 * DEG,     // steeper than this and you submerge
  SKIP_MIN_SPEED: 12,           // m/s
  SKIP_RESTITUTION: 0.55,       // vy flip factor
  SKIP_SPEED_LOSS: 0.88,        // total speed retained per skip
  WATER_DRAG_MUL: 12,           // submerged drag multiplier
  BUOYANCY: 1.6,                // × G, upward — net +0.6 G so a dive ALWAYS resurfaces (test 11)
  WATER_SURFACE_DRAG: 1.1,      // 1/s decay of horizontal speed while floating
  MAX_DIVE_DEPTH_M: 90,         // hard floor of the ocean; below it you bounce off the seabed

  // ── solid ground ──────────────────────────────────────────────────────────
  GROUND_SKIP_ANGLE: 25 * DEG,  // shallower than this and you skid instead of splatting
  GROUND_RESTITUTION: 0.32,
  GROUND_FRICTION: 1.8,         // 1/s decay of horizontal speed while skidding
  GROUND_SKID_LOSS: 0.94,       // horizontal speed kept per shallow skip off snow
  GROUND_SLAM_LOSS: 0.45,       // ...and per steep one
  GROUND_HARD_SPEED: 30,        // m/s — above this, a steep hit tumbles you
  SKULL_CRUSH_SPEED: 55,        // m/s impact for medal 6 (Skull Crusher)
  PLANET_CRUSH_SPEED: 140,      // m/s impact for medal 7 (Planet Crusher)

  // ── run end (§4.10) ───────────────────────────────────────────────────────
  REST_SPEED: 1.5,              // m/s
  REST_TIME: 0.4,               // s below REST_SPEED before the run is over
  RESULTS_DELAY: 0.9,           // s of stillness before the results panel (§2.7)
  DAY_CARD_TIME: 1.2,           // s the "Day 7" card holds (§5)
  MAX_RUN_TIME: 240,            // s — hard backstop so a test or a bug cannot loop forever

  // ── collision ─────────────────────────────────────────────────────────────
  PENGUIN_HALF_W: 0.45,         // m — swept segment is fattened by this (§4.9)
  PENGUIN_HALF_H: 0.45,
  PENGUIN_MASS: 18,             // kg, bare. Payloads add to this.
  PENGUIN_LEN_M: 1.1,           // drawn size, nose to tail

  // ── LTF2 damage (§7.3) ────────────────────────────────────────────────────
  DAMAGE_SCALE: 0.02,           // CALIBRATE. damage = SCALE * 0.5 * m * v² * penetration
  OBSTACLE_PASS_LOSS: 0.55,     // speed retained after smashing through a destroyed obstacle
  OBSTACLE_STOP_LOSS: 0.12,     // speed retained after bouncing off one that survived
  CRACK_STAGE_1: 0.66,          // hp fraction above which an obstacle draws pristine
  CRACK_STAGE_2: 0.33,          // ...and above which it draws cracked rather than crumbling

  // ── economy (§6.3) ────────────────────────────────────────────────────────
  DIST_RATE: 2.12,              // CALIBRATE. $ per foot travelled       — see CALIBRATION.md C-1
  ALT_RATE: 4.53,               // CALIBRATE. $ per foot of max altitude — see CALIBRATION.md C-1
  TIME_MULT_SCALE: 22,          // mult = clamp(1 + airTime/22, 1, 3)
  MULT_MAX: 3,                  // VERIFIED range 1..3
  GOAL_FT: 6000,                // VERIFIED

  // ── camera (§10) ──────────────────────────────────────────────────────────
  LOOKAHEAD_S: 0.35,
  CAM_STIFFNESS: 9.0,           // critically damped spring, not a lerp (landmine 8)
  // Scale is VIEWPORT-RELATIVE, not a fixed px-per-metre: at zoom 1 the canvas shows
  // exactly VIEW_HEIGHT_M metres of world top to bottom, whatever the window size. A fixed
  // constant frames the game differently on every monitor, and on a tall window it makes the
  // penguin a 27-px speck.
  VIEW_HEIGHT_M: 26,
  ZOOM_MAX: 1.0,
  ZOOM_MIN: 0.10,               // most zoomed OUT. 26 m / 0.10 = 260 m of world height, i.e.
                                // the 600 ft wall (183 m) PLUS the penguin above it, at ANY
                                // window size now that the scale is viewport-relative (landmine 13)
  CAM_ALT_ZOOM_M: 60,           // altitude at which zoom has halved
  CAM_SPEED_ZOOM_MS: 45,        // speed at which zoom has halved
  CAM_WALL_MARGIN: 1.25,        // extra headroom when framing something tall
  CAM_HORIZON_BIAS: 0.42,       // keeps the waterline low in frame instead of dead centre
  SHAKE_DECAY: 0.55,            // s for a full-strength shake to die
  SHAKE_MAX_M: 1.1,             // metres of camera offset at full shake
  PENGUIN_MIN_PX: 9,            // never let the star of the game shrink below this
  CAM_LAUNCH_LAG_M: 9,          // §2.1 — the camera starts BEHIND the ramp so the first
                                // half-second of the launch is a lurch, not a smooth pan

  // ── secret shop items that reach into a live flight (§7.7) ────────────────
  PHOENIX_BOOST: 46,            // m/s straight up, on landing, once per run
  ROB_ALT_M: 140,               // altitude at which R.O.B. 64 turns up with the fuel

  // ── autopilot (used by tests 12/13, tools/calibrate.mjs and the sandbox line) ──
  AP_ALPHA_LO: 0.01,            // ternary-search bounds for best-glide alpha
  AP_ALPHA_HI: 0.70,
  AP_SEARCH_ITERS: 64,
  AP_DEADZONE: 0.012,           // rad — stop correcting inside this, or the autopilot chatters
  AP_ENTRY_ANGLE: 0.60,         // × SKIP_ANGLE_MAX — the water entry the autopilot aims for
  AP_FLARE_TIME: 1.2,           // s to impact at which the autopilot starts flattening out.
                                // Seconds, not metres: an absolute altitude does not survive
                                // the range of flights here (see autopilot.js#timeToWater).
  AP_THRUST_TIME: 0.8,          // s to impact at which 'hold' spends its fuel on the save
  AP_STRESS_MARGIN: 0.96,       // fraction of vMax at which the autopilot starts bleeding speed
  AP_CLIMB_ALPHA: 0.55,         // rad — authority limit on a pull-up
  AP_CLIMB_PATH: 40 * DEG,      // flight-path angle held while climbing out on the rockets
  // The lines a competent player picks between (sim/autopilot.js#LINES). Both of these are
  // decisions a real player makes and gets better at, which is why they are axes and not
  // constants — see the comment on LINES.
  AP_ZOOM_ANGLES: [12 * DEG, 22 * DEG, 34 * DEG],
  AP_ZOOM_LABELS: ['12deg', '22deg', '34deg'],   // names only — sim/ may not do rad->deg (check 2)
  AP_ENTRY_ANGLES: [0.45, 0.75],
  AP_ZOOM_PATH: 22 * DEG,       // climb angle held while trading excess speed for altitude
  AP_ZOOM_MARGIN: 1.15,         // x best-glide speed above which there IS excess to trade
  AP_PATH_GAIN: 1.5,            // extra Cl commanded per rad of flight-path error, 'path' mode
  AP_PATH_ALPHA_MAX: 0.45,      // rad — authority limit on that loop. Saturating it at the
                                // sin(2a) peak turns the wing into a speed brake mid-dive.
};

export const DEG_TO_RAD = DEG;
export default TUNING;
