import './debug-log-panel.js';
import {
  getSpaceBgConfig,
  setSpaceBgConfig,
  defaultSpaceBgConfig,
  onSpaceBgChange,
  installSpaceBgEditorUi,
  hexToRgbFloat,
} from './space-bg-editor.js';
import {
  generateStarCatalog,
  getLayerCounts as getStarLayerCounts,
  findNearestStarInDirection,
} from './star-catalog.js';
import * as THREE from 'three';
import * as BufferGeometryUtils from 'three/addons/utils/BufferGeometryUtils.js';
import * as CANNON from 'cannon-es';
import { OBJLoader } from 'three/addons/loaders/OBJLoader.js';
import { MTLLoader } from 'three/addons/loaders/MTLLoader.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { loadAxeObjAsGroup, disposeAxeObjDeep } from './axe-obj-load.js';
import {
  tryGetPrefabDataForKind,
  deserializePrefabGroup,
  computeWorldAabbCollider,
  removePrefabKindFromStorage,
} from './prefab-serializer.js';
import {
  applyProceduralTreeBranches,
  normalizeTreeBranchSettings,
  hashTreeBranchSeed,
  disposeProceduralBranchHolder,
} from './tree-procedural-branches.js';
import { applyLt2StyleTreeBranches } from './lt2-tree-branches.js';
import { loadLt2Config, loadLt2ConfigForPreset, LT2_TRUNK_SIDE_TEXTURES } from './lt2-tree-config.js';
import { buildInteriorCapMaterial, disposeInteriorCapMaterial, INTERIOR_TEXTURES } from './lt2-tree-interior.js';
import { buildTrunkSideMaterial } from './lt2-tree-textures.js';
import {
  loadNeonSettings,
  createNeonCapCanvasTexture,
  neonEmissiveIntensityFromSlider,
  applyNeonBloomToPass,
  neonAlbedoColorFromPicker,
  NEON_SETTINGS_KEY,
} from './neon-tree-settings.js';
import {
  createNeonBloomPipeline,
  enableNeonBloomOnMesh,
  enableNeonBloomOnCamera,
} from './neon-bloom-pipeline.js';
import { defaultSawmillConvertZoneDims, normalizeSawmillConvertZone } from './sawmill-convert-zone.js';
import { BLUEPRINT_CATALOG } from './blueprint-library.js';
import { createWorldRegistry } from './world-registry.js';
import {
  normalizeMapDocument,
  getWorldSliceFromDocument,
  normalizeUniversePose,
  DEFAULT_BIOMES,
} from './map-world-format.js';
import {
  mergeWorldDefIntoRuntime,
  STORE_DEFAULTS,
  CLERK_DEFAULTS,
  getActiveWorldDefFromSlice,
} from './world-defs.js';
import { migrateSaveToV2, SAVE_FORMAT_VERSION } from './save-migrate.js';
import {
  createInnerSphereShellBody,
  createSolidPlanetBody,
  projectOntoTangentPlane,
  radialUpAt,
  SPHERE_DEFAULT_GRAVITY_STRENGTH,
  sphereLatLonDirToVector3,
  trunkQuaternionYAlongWorldUp,
} from './sphere-physics.js';

globalThis.LT_BLUEPRINT_CATALOG = BLUEPRINT_CATALOG;

const SAVE_KEY = 'LT2_Clone_Save';
/** Session + optional localStorage default — set from main menu before {@link startGame}. */
const TREE_GEN_SESSION_KEY = 'LT2_TreeGenMode';
const TREE_GEN_PREF_KEY = 'LT2_TreeGenModePref';

function getTreeGenerationMode() {
  try {
    const s = sessionStorage.getItem(TREE_GEN_SESSION_KEY);
    if (s === 'lt2' || s === 'classic') return s;
    const p = localStorage.getItem(TREE_GEN_PREF_KEY);
    if (p === 'lt2' || p === 'classic') return p;
  } catch (_) {}
  return 'classic';
}

/** LT2-style trunk proportions — pulled from saved {@link loadLt2Config} so the config sliders change trunks at spawn. */
function lt2TrunkDimensions() {
  const cfg = loadLt2Config();
  return { halfW: cfg.trunkThickness, trunkH: cfg.trunkHeight };
}

function treeDimsForCurrentMode() {
  return getTreeGenerationMode() === 'lt2' ? lt2TrunkDimensions() : randomTreeDimensions();
}

let scene, world, camera, renderer;
/** @type {ReturnType<typeof createWorldRegistry> | null} */
let worldRegistry = null;
/** Active map planet: Three.js root for terrain, map props, plots, sawmills, blueprints, sell FX, map trees. */
let planetRuntimeContentRoot = null;
/** Matches {@link planetRuntimeContentRoot}{@link THREE.Object3D#userData}.planetId and map `defaultPlanetId` at init. */
let runtimePlanetId = 'home';

/** Active planet center in world space. Updated on warp so `stabilizePlayer*` / jump code keep
 *  working in terms of "the planet I'm currently standing on" without every caller having to
 *  look up the closest planet. */
const sphereCenter = new CANNON.Vec3(0, 0, 0);
let spherePhysicsActive = false;
let spherePlayRadius = 2500;
/**
 * @typedef {Object} PlanetPhysicsEntry
 * @property {string} planetId
 * @property {number} radius
 * @property {number} gravityStrength
 * @property {CANNON.Vec3} center            universePose of this planet in world space
 * @property {import('cannon-es').Body} body solid-sphere collider in the physics world
 * @property {THREE.Mesh} surfaceMesh        rendered surface sphere in the scene
 * @property {THREE.Mesh | null} atmosphereMesh translucent halo (null if atmosphere disabled)
 * @property {number} atmosphereRadius       radius out to atmosphere halo (for influence calculations)
 * @property {string} label
 */
/**
 * All planets currently instantiated as real physics + visual bodies. Built once by
 * {@link initMultiPlanetSystemFromMap} on world init; entries persist for the whole session.
 * Warp does NOT add/remove entries — it just teleports the player between them.
 * @type {PlanetPhysicsEntry[]}
 */
const planetPhysicsEntries = [];
/** Convenience lookup into {@link planetPhysicsEntries} by planetId. */
const planetPhysicsById = new Map();

function getActiveWorldEntryFromMap() {
  const doc = getMapEditorData();
  if (!doc?.worlds?.length) return null;
  const pid = resolveEditorMapPlanetId(doc);
  return doc.worlds.find((x) => x && x.planetId === pid) || doc.worlds[0];
}

function computeSpherePhysicsWantedAtInit() {
  try {
    const o = sessionStorage.getItem('LT2_SpherePhysicsOverride');
    if (o === '1') return true;
    if (o === '0') return false;
  } catch (_) {}
  const w = getActiveWorldEntryFromMap();
  return !!(w && w.useSpherePhysics);
}

/**
 * Dispose every {@link PlanetPhysicsEntry}: removes its collider from the physics world, detaches
 * its surface + atmosphere meshes, and disposes geometry/materials. Safe to call even when empty.
 */
function disposePlanetPhysicsEntries() {
  for (const e of planetPhysicsEntries) {
    if (e.body && world) {
      try { world.removeBody(e.body); } catch (_) {}
    }
    if (e.surfaceMesh) {
      e.surfaceMesh.removeFromParent();
      e.surfaceMesh.geometry?.dispose?.();
      e.surfaceMesh.material?.dispose?.();
    }
    if (e.atmosphereMesh) {
      e.atmosphereMesh.removeFromParent();
      e.atmosphereMesh.geometry?.dispose?.();
      e.atmosphereMesh.material?.dispose?.();
    }
  }
  planetPhysicsEntries.length = 0;
  planetPhysicsById.clear();
}

/**
 * Build the full multi-planet system: iterate every world in the map document with
 * `useSpherePhysics`, create a solid-sphere collider + rendered surface + (optional) atmosphere
 * halo at that world's `universePose`, and add all of them to the physics world + scene.
 *
 * After this runs, every planet is a real object with its own gravity — the player sees Xenon
 * floating above Home, can (in principle) reach it, and warping is just `playerBody.position.set()`
 * to put the player on whichever planet's surface.
 *
 * `sphereCenter` / `spherePlayRadius` / `runtimePlanetId` are synced to the *active* planet for
 * the benefit of existing single-planet code paths (save/load, dev HUD, stabilization).
 */
function initSpherePhysicsFromMapIfEnabled() {
  spherePhysicsActive = false;
  disposePlanetPhysicsEntries();
  if (!world) return;
  const wanted = computeSpherePhysicsWantedAtInit();
  const activeWorld = getActiveWorldEntryFromMap();
  console.log('%c[planet] init', 'background:#36c;color:#fff;padding:2px 6px;border-radius:3px', {
    spherePhysicsWanted: wanted,
    activeWorldId: activeWorld?.planetId,
    useSpherePhysics: activeWorld?.useSpherePhysics,
    sphereRadius: activeWorld?.sphereRadius,
    universePose: activeWorld?.universePose,
  });
  if (!wanted) {
    window.__planetDebug = { wanted: false, active: activeWorld };
    return;
  }
  /** Kill world-wide gravity; per-planet pull is applied each frame by {@link applyMultiPlanetGravity}. */
  world.gravity.set(0, 0, 0);
  const doc = getMapEditorData();
  const worlds = Array.isArray(doc?.worlds) ? doc.worlds : [];
  for (const w of worlds) {
    if (!w?.planetId) continue;
    if (!w.useSpherePhysics) continue;
    const entry = createPlanetPhysicsEntry(w);
    if (!entry) continue;
    planetPhysicsEntries.push(entry);
    planetPhysicsById.set(entry.planetId, entry);
  }
  if (!planetPhysicsEntries.length) {
    /** `useSpherePhysics` flag was set but no eligible worlds found — fall back to flat-world. */
    window.__planetDebug = { wanted: true, active: activeWorld, created: 0 };
    return;
  }
  spherePhysicsActive = true;
  const activePid = runtimePlanetId;
  const activeEntry = planetPhysicsById.get(activePid) || planetPhysicsEntries[0];
  spherePlayRadius = activeEntry.radius;
  sphereCenter.set(activeEntry.center.x, activeEntry.center.y, activeEntry.center.z);
  if (gameGroundMesh) gameGroundMesh.visible = false;
  applySphereWorldViewTuning();
  console.log('%c[planet] multi-planet system created', 'background:#093;color:#fff;padding:2px 6px;border-radius:3px', {
    activePlanet: activeEntry.planetId,
    totalPlanets: planetPhysicsEntries.length,
    entries: planetPhysicsEntries.map((e) => ({
      id: e.planetId,
      radius: e.radius,
      center: { x: e.center.x, y: e.center.y, z: e.center.z },
      gravity: e.gravityStrength,
    })),
  });
  window.__planetDebug = {
    wanted: true,
    radius: activeEntry.radius,
    sphereCenter,
    entries: planetPhysicsEntries,
    scene,
    camera,
    playerBody: null,
    get summary() {
      return {
        R: spherePlayRadius,
        activePlanetId: runtimePlanetId,
        playerPos: window.__planetDebug.playerBody?.position,
        cameraPos: camera?.position,
        sphereCenter,
        allPlanets: planetPhysicsEntries.map((e) => ({ id: e.planetId, center: e.center })),
      };
    },
  };
}

/**
 * Paint biome colors onto an already-built sphere geometry as per-vertex colors. Each vertex picks
 * a color based on its latitude: whichever biome's `[latMin, latMax]` range covers it wins. Falls
 * back to `fallbackCol` for latitudes with no biome — typically when `biomes[]` is empty or has
 * gaps.
 *
 * @param {THREE.SphereGeometry} geo
 * @param {Array<{latMin:number,latMax:number,color:string}> | undefined | null} biomes
 * @param {THREE.Color} fallbackCol
 */
function applyBiomeVertexColorsToSphere(geo, biomes, fallbackCol) {
  const biomeList = Array.isArray(biomes) && biomes.length ? biomes : DEFAULT_BIOMES;
  /** Sort by latitude-range SPAN ascending so narrower bands (e.g. beach 6–18°) win over wider
   *  encompassing ones (forest −65–65°). Without this, picking "first match" makes beaches
   *  invisible because forest's range already covers their latitudes. */
  const sorted = biomeList
    .filter(Boolean)
    .slice()
    .sort((a, b) => {
      const spanA = Math.abs((a.latMax ?? 90) - (a.latMin ?? -90));
      const spanB = Math.abs((b.latMax ?? 90) - (b.latMin ?? -90));
      return spanA - spanB;
    });
  const pos = geo.attributes.position;
  const colors = new Float32Array(pos.count * 3);
  const tmp = new THREE.Color();
  const fb = fallbackCol || new THREE.Color('#3d6b4a');
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    const y = pos.getY(i);
    const z = pos.getZ(i);
    const inv = 1 / Math.max(1e-6, Math.hypot(x, y, z));
    const sinLat = y * inv;
    const latDeg = Math.asin(Math.max(-1, Math.min(1, sinLat))) * (180 / Math.PI);
    let chosen = null;
    for (const b of sorted) {
      if (latDeg >= (b.latMin ?? -90) && latDeg <= (b.latMax ?? 90)) {
        chosen = b;
        break;
      }
    }
    if (chosen?.color) tmp.set(chosen.color);
    else tmp.copy(fb);
    colors[i * 3] = tmp.r;
    colors[i * 3 + 1] = tmp.g;
    colors[i * 3 + 2] = tmp.b;
  }
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
}

/**
 * Create a single {@link PlanetPhysicsEntry} (collider + surface mesh + optional atmosphere halo)
 * at the world's `universePose`. Returns null if the world entry is missing required fields.
 */
function createPlanetPhysicsEntry(w) {
  if (!w?.planetId || !scene || !world) return null;
  const up = normalizeUniversePose(w.universePose || {});
  const R = typeof w.sphereRadius === 'number' && w.sphereRadius > 10 ? w.sphereRadius : 2500;
  const gravity = typeof w.gravityStrength === 'number' && w.gravityStrength > 0
    ? w.gravityStrength
    : SPHERE_DEFAULT_GRAVITY_STRENGTH;
  /** Solid collider (CANNON.Sphere) positioned at the universe pose — analytic shape, no tessellation cost. */
  const body = createSolidPlanetBody(R, gameGroundPhyMat ?? undefined);
  body.position.set(up.x, up.y, up.z);
  body.userData = { planetId: w.planetId, isPlanetSurface: true };
  world.addBody(body);
  /** Surface mesh: one sphere per planet, parented directly to the scene in absolute universe space. */
  const surfaceHex = typeof w.surfaceColor === 'string' ? w.surfaceColor : '#3d6b4a';
  const surfaceCol = new THREE.Color(surfaceHex);
  /** Higher tessellation so biome bands read cleanly at the poles / near the equator. */
  const geo = new THREE.SphereGeometry(Math.max(50, R), 96, 64);
  /** Bake biome latitude bands into the sphere as per-vertex colors — the same visual scheme the
   *  universe editor shows, so standing on Home you actually see snow caps, forest belt, beach. */
  applyBiomeVertexColorsToSphere(geo, w.biomes, surfaceCol);
  const mat = new THREE.MeshStandardMaterial({
    /** vertexColors: THREE multiplies material.color × vertex.color — leave material.color white so
     *  the biome colors read at 100% without being tinted by `surfaceColor` again. */
    color: 0xffffff,
    vertexColors: true,
    roughness: 1,
    metalness: 0,
    /** Dim self-illumination: prevents pure-black appearance when the sun is off the player's side of the globe. */
    emissive: surfaceCol.clone().multiplyScalar(0.18),
    /** Planets render against space; distance fog would wash out a globe 8 km away. */
    fog: false,
  });
  const surfaceMesh = new THREE.Mesh(geo, mat);
  surfaceMesh.name = `PlanetSurface_${w.planetId}`;
  surfaceMesh.receiveShadow = true;
  surfaceMesh.frustumCulled = false;
  surfaceMesh.position.set(up.x, up.y, up.z);
  surfaceMesh.userData.planetId = w.planetId;
  scene.add(surfaceMesh);
  /** Atmosphere halo (optional). Kept thin + low-opacity so you can see through to other planets. */
  /** @type {THREE.Mesh | null} */
  let atmosphereMesh = null;
  let atmosphereRadius = R;
  const atm = w.atmosphere;
  if (atm && atm.enabled) {
    const thickness = Math.max(50, Math.min(R * 0.3, Number(atm.thickness) || R * 0.05));
    atmosphereRadius = R + thickness;
    const haloHex = typeof atm.color === 'string' ? atm.color : '#7cb4ff';
    const haloCol = new THREE.Color(haloHex);
    const haloGeo = new THREE.SphereGeometry(atmosphereRadius, 32, 24);
    const haloMat = new THREE.MeshBasicMaterial({
      color: haloCol,
      transparent: true,
      /** Extra-translucent so you can still see stars + other planets through the atmosphere. */
      opacity: 0.06,
      side: THREE.BackSide,
      depthWrite: false,
      fog: false,
    });
    atmosphereMesh = new THREE.Mesh(haloGeo, haloMat);
    atmosphereMesh.name = `PlanetAtm_${w.planetId}`;
    atmosphereMesh.position.set(up.x, up.y, up.z);
    atmosphereMesh.frustumCulled = false;
    atmosphereMesh.userData.planetId = w.planetId;
    scene.add(atmosphereMesh);
  }
  return {
    planetId: String(w.planetId),
    radius: R,
    gravityStrength: gravity,
    center: new CANNON.Vec3(up.x, up.y, up.z),
    body,
    surfaceMesh,
    atmosphereMesh,
    atmosphereRadius,
    label: typeof w.label === 'string' && w.label ? w.label : w.planetId,
  };
}

/**
 * Pick the planet that's currently dominant for the player.
 *
 * Scoring rules, in priority order:
 *   1. If the body is **outside** all planets, the planet whose surface is closest wins.
 *      This is the normal "standing on Home" case — even if a neighbor's center is numerically
 *      closer than Home's (can happen with similar radii), Home's surface is closer so Home wins.
 *   2. If the body is **outside** at least one planet but **inside** others, always prefer the
 *      one it's outside of. Physically: being inside a sphere collider means the solver wants to
 *      push you out — gravitating you toward that planet's center would fight the contact solve
 *      and cause the "sucked into space through a neighbor" bug when two planets overlap.
 *   3. If the body is **inside every planet** (pathological, e.g. two huge overlapping spheres
 *      and you're near the shared center), fall back to the one whose surface is nearest — this
 *      gives the solver the shortest push-out path.
 *
 * @param {{x:number,y:number,z:number}} pos
 * @returns {PlanetPhysicsEntry | null}
 */
function findClosestPlanet(pos) {
  /** @type {PlanetPhysicsEntry | null} */
  let bestOutside = null;
  let bestOutsideSurf = Infinity;
  /** @type {PlanetPhysicsEntry | null} */
  let bestInside = null;
  /** Track the LEAST-negative surface distance (i.e. closest to escape); start at -Infinity so any real value wins. */
  let bestInsideSurf = -Infinity;
  for (const e of planetPhysicsEntries) {
    const dx = pos.x - e.center.x;
    const dy = pos.y - e.center.y;
    const dz = pos.z - e.center.z;
    const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
    const surfDist = dist - e.radius;
    if (surfDist >= 0) {
      if (surfDist < bestOutsideSurf) { bestOutsideSurf = surfDist; bestOutside = e; }
    } else {
      if (surfDist > bestInsideSurf) { bestInsideSurf = surfDist; bestInside = e; }
    }
  }
  return bestOutside || bestInside;
}

/**
 * Per-frame gravity: each dynamic body is pulled toward the *closest* planet with that planet's
 * own `gravityStrength`. Inter-planet space has no net gravity, so jumping high (or being flung)
 * lets bodies drift — until another planet's field captures them. This matches the "leave one
 * atmosphere, drift, enter another" design the user wants.
 *
 * @param {number} dt
 */
function applyMultiPlanetGravity(dt) {
  if (!world || dt <= 0 || !planetPhysicsEntries.length) return;
  const bodies = world.bodies;
  let loggedPlayer = false;
  for (let i = 0; i < bodies.length; i++) {
    const body = bodies[i];
    if (body.type !== CANNON.Body.DYNAMIC || body.mass <= 0) continue;
    if (body.userData?.affectedByPlanetGravity === false) continue;
    /** Player-fly mode: the player body keeps existing so you can still collide with props and
     *  chop trees, but we skip gravity so Space/Ctrl actually lift/drop without fighting pull. */
    if (playerFlyMode && body === playerBody) continue;
    const best = findClosestPlanet(body.position);
    if (!best) continue;
    const dx = body.position.x - best.center.x;
    const dy = body.position.y - best.center.y;
    const dz = body.position.z - best.center.z;
    const lenSq = dx * dx + dy * dy + dz * dz;
    if (lenSq < 1e-10) continue;
    const inv = 1 / Math.sqrt(lenSq);
    const s = best.gravityStrength * dt;
    body.velocity.x -= dx * inv * s;
    body.velocity.y -= dy * inv * s;
    body.velocity.z -= dz * inv * s;
    /** Occasional debug snapshot of the player body so you can run
     *  `window.__planetDebug.lastPlayerGravity` in the console and see which planet is pulling. */
    if (!loggedPlayer && body === playerBody) {
      loggedPlayer = true;
      if (window.__planetDebug) {
        window.__planetDebug.lastPlayerGravity = {
          planetId: best.planetId,
          playerPos: { x: body.position.x, y: body.position.y, z: body.position.z },
          planetCenter: { x: best.center.x, y: best.center.y, z: best.center.z },
          surfaceDistance: Math.sqrt(lenSq) - best.radius,
          pullStrength: best.gravityStrength,
        };
      }
    }
  }
}

/**
 * View tuning for planet-scale worlds. Computes the bounding distance that covers every planet's
 * far-side surface from the active planet and grows `camera.far` to match, then disables fog
 * completely — we want a space-like view where distant planets read crisp against the stars.
 */
function applySphereWorldViewTuning() {
  if (!camera) return;
  let needed = Math.max(spherePlayRadius, 2500) * 1.2;
  for (const e of planetPhysicsEntries) {
    const dx = e.center.x - sphereCenter.x;
    const dy = e.center.y - sphereCenter.y;
    const dz = e.center.z - sphereCenter.z;
    const d = Math.sqrt(dx * dx + dy * dy + dz * dz) + e.radius * 1.2;
    if (d > needed) needed = d;
  }
  /** Add generous padding so the far clip never cuts off the far side of the farthest planet.
   *  Also enforce a large minimum (15 km) so the starfield/nebula shells always render. */
  const far = Math.max(15000, needed * 1.3);
  camera.far = far;
  camera.updateProjectionMatrix();
  /** Kill fog on planet-scale worlds: we want a black space background with visible distant planets, not a blue haze that eats the view at 90 m. */
  if (scene?.fog) {
    scene.fog = null;
  }
  /** Background is the deepest part of space — the stars, nebula and planets
   *  read against it. Read from the space-bg editor config so toggling
   *  presets / dragging the bg color picker updates the clear color live. */
  if (scene) {
    const cfg = getSpaceBgConfig();
    scene.background = new THREE.Color(cfg?.sky?.bg || '#01020a');
  }
  buildSpaceBackground();
}

/**
 * Root group that holds the starfield + nebula meshes. Kept centered on the camera each frame
 * ({@link updateSpaceBackground}) so the stars feel infinite no matter where the player flies.
 * @type {THREE.Group | null}
 */
let spaceBackgroundRoot = null;

/**
 * Parse a `#rrggbb` string from the config into a plain `[r, g, b]` float
 * triple (0..1). Used when mixing colors via `THREE.MathUtils.lerp` inside
 * the galaxy builder where constructing full `THREE.Color` objects per
 * vertex would be wasteful. Falls back to `fallback` if the input is
 * missing or malformed so a hand-typed hex can't crash the rebuild.
 */
function rgbFromHexSafe(hex, fallback) {
  try {
    const rgb = hexToRgbFloat(hex);
    if (Number.isFinite(rgb.r)) return [rgb.r, rgb.g, rgb.b];
  } catch (_) { /* noop */ }
  return fallback.slice();
}

/**
 * Build a multi-layer starfield + nebula dome once per session (idempotent). Uses three star
 * layers — dense dust, medium stars, bright feature stars — plus a faint nebula shell so
 * looking around feels like deep space rather than a blank void.
 *
 * Starfield geometry is in LOCAL space centered on the root; the root follows the camera each
 * frame so stars render at the edge of the view frustum regardless of how far the player has
 * moved through the universe. Everything is drawn with `depthTest:false` + `renderOrder:-1000`
 * so it always paints behind planets / trees / gameplay geometry.
 */
function buildSpaceBackground() {
  if (!scene) return;
  if (spaceBackgroundRoot) return;
  try {
    _buildSpaceBackgroundImpl();
  } catch (err) {
    /** Corrupt config (NaN slider value, bad hex, etc.) must never brick the
     *  game. If the build threw, tear down any partial result, reset the
     *  space-bg config back to defaults, and try once more with known-good
     *  values. If THAT still throws we give up — at least the game stays
     *  playable with just the clear color as the "sky". */
    console.warn('[space-bg] build threw — resetting config + retrying', err);
    if (spaceBackgroundRoot) {
      try { disposeSpaceBackground(); } catch (_) { /* noop */ }
    }
    try {
      localStorage.removeItem('LT2_SpaceBgConfig_v1');
    } catch (_) { /* noop */ }
    try {
      setSpaceBgConfig(defaultSpaceBgConfig());
    } catch (_) { /* noop — fall through to retry */ }
    try {
      _buildSpaceBackgroundImpl();
    } catch (err2) {
      console.error('[space-bg] retry after reset also failed — skipping space bg this session', err2);
      if (spaceBackgroundRoot) {
        try { disposeSpaceBackground(); } catch (_) { /* noop */ }
      }
    }
  }
}

function _buildSpaceBackgroundImpl() {
  if (!scene) return;
  if (spaceBackgroundRoot) return;

  const cfg = getSpaceBgConfig();
  const starsCfg = cfg?.stars || {};
  const densityMul = Number.isFinite(starsCfg.density) ? Math.max(0.05, Math.min(3, starsCfg.density)) : 1.0;
  const sizeMul = Number.isFinite(starsCfg.size) ? Math.max(0.2, Math.min(4, starsCfg.size)) : 1.0;

  const root = new THREE.Group();
  root.name = 'SpaceBackground';
  root.frustumCulled = false;
  root.renderOrder = -1000;
  /** Distance from camera at which we place the starfield — far enough that parallax is negligible
   *  but inside `camera.far` so it renders. We disable depth test anyway so it's mostly cosmetic. */
  const STAR_SHELL = 9000;

  /** ────────────────────────────────────────────────────────────────────
   *   Star layers — driven by the deterministic catalog from
   *   `star-catalog.js`. Every star in the sky is now a real entity with
   *   a stable ID `s-<layer>-<index>`, a spectral class, a color, and a
   *   per-star `systemSeed` that Phase 2 can use to generate an entire
   *   procedural system (sun + planets + resources) on demand.
   *
   *   Visual contract vs. the pre-catalog renderer:
   *     - Same 3-layer structure (dust 6000 / medium 2200 / bright 350)
   *       so `sizeMul` / tint sliders behave the same way.
   *     - Density slider no longer REGENERATES random stars — it renders
   *       only the FIRST `count * density` entries of each layer's catalog
   *       slice. That means IDs stay stable when the player drags the
   *       slider down then back up: `s-2-17` is always the same star at
   *       the same position, just possibly culled.
   *     - Per-star color = spectral-class color × user tint × magnitude.
   *       User tints still behave as palette multipliers on top of the
   *       realistic class palette.
   *
   *   Bookmarks (hand-authored planets) are collected below and passed to
   *   the catalog so they get stable IDs + a lookup slot in `byId`, then
   *   the catalog is cached on `root.userData.catalog` so `_buildSpaceBackgroundImpl`
   *   and the `__starCatalog` facade always share the same instance.
   *   ──────────────────────────────────────────────────────────────────── */
  const tintDust = rgbFromHexSafe(starsCfg.tintDust, [0.9, 0.92, 1.0]);
  const tintMid = rgbFromHexSafe(starsCfg.tintMid, [1.0, 0.95, 0.85]);
  const tintBright = rgbFromHexSafe(starsCfg.tintBright, [1.0, 0.85, 0.75]);

  /** Seed comes from the universe config. Sanitized elsewhere, so we can
   *  trust it's a finite integer — but guard anyway since `_config` can be
   *  hot-patched from the console. */
  const rawSeed = Number(cfg?.universe?.seed);
  const universeSeed = Number.isFinite(rawSeed) ? (Math.floor(rawSeed) >>> 0) : 424242;

  /** Build the bookmark input list: one entry per hand-authored planet
   *  whose universe pose has a non-zero direction from the world origin.
   *  A planet at (0,0,0) has no meaningful direction to render into the
   *  sky, so we silently skip it (typical setup: one planet sits at the
   *  origin and the others are offset above it). */
  const bookmarksInput = [];
  try {
    const doc = getMapEditorData();
    const worlds = Array.isArray(doc?.worlds) ? doc.worlds : [];
    for (const w of worlds) {
      if (!w || !w.planetId) continue;
      const up = normalizeUniversePose(w.universePose || {});
      const lenSq = up.x * up.x + up.y * up.y + up.z * up.z;
      if (lenSq < 1.0) continue;
      const len = Math.sqrt(lenSq);
      bookmarksInput.push({
        planetId: w.planetId,
        label: typeof w.label === 'string' && w.label ? w.label : w.planetId,
        dirX: up.x / len,
        dirY: up.y / len,
        dirZ: up.z / len,
      });
    }
  } catch (e) {
    console.warn('[space-bg] failed to collect bookmarks, continuing without them', e);
  }

  /** Generate the catalog once per build. `generateStarCatalog` is
   *  deterministic: same seed + same bookmarks → identical output. */
  const catalog = generateStarCatalog(universeSeed, bookmarksInput);
  /** Stash on the root so the `__starCatalog` facade + future phases can
   *  pull the *same* instance any time they need it. */
  root.userData.catalog = catalog;
  root.userData.universeSeed = universeSeed;

  const layerDefs = [
    { size: 2.0 * sizeMul, tint: tintDust },
    { size: 3.2 * sizeMul, tint: tintMid },
    { size: 5.2 * sizeMul, tint: tintBright },
  ];
  /** Pre-bucket stars by their `layer` so we don't walk the full 8550-
   *  element array three times. Cheap, and keeps IDs stable across
   *  density changes because we slice the head of each bucket. */
  const starsByLayer = [[], [], []];
  for (const s of catalog.stars) {
    if (s.layer >= 0 && s.layer < 3) starsByLayer[s.layer].push(s);
  }

  for (let layerIdx = 0; layerIdx < layerDefs.length; layerIdx++) {
    const def = layerDefs[layerIdx];
    const bucket = starsByLayer[layerIdx];
    /** `densityMul` renders a HEAD slice of the deterministic bucket so
     *  IDs don't shift when the player drags the density slider. At
     *  density 1.0 we render exactly the original layer count. */
    const count = Math.max(0, Math.min(bucket.length, Math.round(bucket.length * densityMul)));
    if (count <= 0) continue;
    const positions = new Float32Array(count * 3);
    const colors = new Float32Array(count * 3);
    for (let i = 0; i < count; i++) {
      const star = bucket[i];
      const d = star.dir;
      positions[i * 3]     = d[0] * STAR_SHELL;
      positions[i * 3 + 1] = d[1] * STAR_SHELL;
      positions[i * 3 + 2] = d[2] * STAR_SHELL;
      /** Per-star color = spectral class × user tint × magnitude. We fold
       *  the user tint in multiplicatively so the tint sliders behave as
       *  "palette shift" knobs on top of realistic star colors — lowering
       *  `tintDust.r` dims the red channel of every dust star without
       *  erasing the spectral-class variance. */
      const cr = star.color[0] * def.tint[0] * star.magnitude;
      const cg = star.color[1] * def.tint[1] * star.magnitude;
      const cb = star.color[2] * def.tint[2] * star.magnitude;
      colors[i * 3]     = Math.max(0, Math.min(1, cr));
      colors[i * 3 + 1] = Math.max(0, Math.min(1, cg));
      colors[i * 3 + 2] = Math.max(0, Math.min(1, cb));
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    const mat = new THREE.PointsMaterial({
      size: def.size,
      sizeAttenuation: false,
      vertexColors: true,
      transparent: true,
      opacity: 1.0,
      blending: THREE.AdditiveBlending,
      depthTest: true,
      depthWrite: false,
      fog: false,
    });
    const points = new THREE.Points(geo, mat);
    points.frustumCulled = false;
    points.renderOrder = -1000;
    points.name = `StarCatalogLayer_${layerIdx}`;
    points.userData.catalogLayer = layerIdx;
    points.userData.renderedCount = count;
    root.add(points);
  }

  /**
   * ──────────────────────────────────────────────────────────────────────────
   *  Spiral galaxy + supermassive black hole
   * ──────────────────────────────────────────────────────────────────────────
   *
   * Replaces the old "Milky Way band". A spiral galaxy hanging in one fixed
   * patch of the sky reads as MUCH more galactic than a ring of dust around
   * the celestial sphere — you can actually see the arms curl and a dark
   * black hole eye at the center.
   *
   * Layers (back → front), all parented to `root` which tracks the camera
   * each frame so parallax is zero:
   *   1. Soft halo glow disc           (vertex-colored Plane, additive)
   *   2. Spiral arm stars              (Points, additive, log-spiral layout)
   *   3. Dense central bulge stars     (Points, additive, 3D gaussian)
   *   4. Accretion disc                (vertex-colored Ring, additive, hot orange/white)
   *   5. Photon-ring highlight         (thin vertex-colored Ring, additive, white-hot)
   *   6. Black hole silhouette         (CircleGeometry, *normal* blending, pure black)
   *
   * Everything sits on a tilted disc whose center direction + normal are
   * fixed in world space, so the galaxy appears in the same corner of the
   * sky regardless of which planet the player is standing on.
   */
  const gal = cfg?.galaxy || {};
  /** Early-out: if the user disabled the galaxy entirely, skip all of its
   *  layers. The starfield + nebula still render normally. */
  const GALAXY_ENABLED = gal.enabled !== false;
  const GALAXY_DIR = new THREE.Vector3(
    Number.isFinite(gal.dirX) ? gal.dirX : 0.62,
    Number.isFinite(gal.dirY) ? gal.dirY : 0.48,
    Number.isFinite(gal.dirZ) ? gal.dirZ : -0.62,
  );
  if (GALAXY_DIR.lengthSq() < 1e-6) GALAXY_DIR.set(0, 1, 0);
  GALAXY_DIR.normalize();
  /** Disc normal tilted ~45° off the direction of sight so we see the spiral
   *  oblique (face-on would be a perfect circle — boring; edge-on would show
   *  a thin streak — also boring). This angle gives the classic oval look. */
  const GALAXY_NORMAL = new THREE.Vector3(
    Number.isFinite(gal.normX) ? gal.normX : 0.28,
    Number.isFinite(gal.normY) ? gal.normY : 0.88,
    Number.isFinite(gal.normZ) ? gal.normZ : 0.38,
  );
  if (GALAXY_NORMAL.lengthSq() < 1e-6) GALAXY_NORMAL.set(0, 1, 0);
  GALAXY_NORMAL.normalize();
  /** Push just slightly inside `STAR_SHELL` so the galaxy doesn't z-fight with
   *  the general starfield shell during rasterization. */
  const GALAXY_DIST = STAR_SHELL * 0.94;
  const galaxyCenter = GALAXY_DIR.clone().multiplyScalar(GALAXY_DIST);
  /** U / V basis in the galaxy's disc plane. We compute U as perpendicular to
   *  both world up and the disc normal, then V = normal × U. Falls back to a
   *  different seed vector if the disc normal is near-vertical. */
  let galU = new THREE.Vector3().crossVectors(GALAXY_NORMAL, new THREE.Vector3(0, 0, 1));
  if (galU.lengthSq() < 1e-4) galU = new THREE.Vector3(1, 0, 0);
  galU.normalize();
  const galV = new THREE.Vector3().crossVectors(GALAXY_NORMAL, galU).normalize();

  /** Radii are in world units (same scale as STAR_SHELL = 9000). Outer radius
   *  chosen so the galaxy spans ~40° of sky — big enough to read as a major
   *  feature but not so big it overwhelms the rest of the view. All four are
   *  slider-driven via the editor. */
  const GAL_OUTER_R = Number.isFinite(gal.outerR) ? gal.outerR : 3500;
  const GAL_HALO_R = GAL_OUTER_R * 1.25;
  /** Inside `GAL_BH_R` we spawn NO stars — that's the black hole silhouette
   *  region. Accretion/photon rings live between BH_R and ACC_R. */
  const GAL_BH_R = Number.isFinite(gal.blackHoleR) ? gal.blackHoleR : 170;
  const GAL_PHOTON_R = GAL_BH_R * 1.08;
  const GAL_ACC_R = GAL_BH_R * 2.7;
  const GAL_BULGE_R = Number.isFinite(gal.bulgeR) ? gal.bulgeR : 820;
  /** Disc thickness along the galaxy normal — thin at the edge, thicker at
   *  the core; the per-star code scales this by `(1 - rNorm*0.5)`. */
  const GAL_DISC_HALF_THICKNESS = 160;

  /** Helper: put a point in galaxy-local disc coords `(px, py, pz)` into world
   *  space around `galaxyCenter`. `px/py` live in the disc plane, `pz` along
   *  the disc normal. Used by every layer below. */
  const galLocalToWorld = (px, py, pz, out) => {
    out[0] = galaxyCenter.x + galU.x * px + galV.x * py + GALAXY_NORMAL.x * pz;
    out[1] = galaxyCenter.y + galU.y * px + galV.y * py + GALAXY_NORMAL.y * pz;
    out[2] = galaxyCenter.z + galU.z * px + galV.z * py + GALAXY_NORMAL.z * pz;
  };

  /** Layer 1 — Halo glow disc. Builds a single-triangle-fan disc with the
   *  center vertex warm-tinted and the rim vertices at pure zero so the
   *  glow fades completely into the sky — no visible oval edge. We use a
   *  concentric-ring triangulation so the gradient is smooth, plus extra
   *  outer "fade" rings that stay at zero so there's lots of headroom for
   *  the exponential falloff before it visually disappears. Center color is
   *  kept low (additive blending stacks on top of stars + nebula, so small
   *  numbers read correctly against the dark sky).
   *
   *  Config hooks: `galaxy.haloColor` + `galaxy.haloIntensity` let the player
   *  recolor + dim/brighten the glow from the editor. We still clamp the
   *  center fairly low so the glow never washes the full sky even at max
   *  intensity. */
  if (GALAXY_ENABLED) {
    const RINGS = 28;
    const SEGS = 96;
    const haloHex = rgbFromHexSafe(gal.haloColor, [0.18, 0.14, 0.10]);
    const haloIntensity = Number.isFinite(gal.haloIntensity) ? Math.max(0, Math.min(5, gal.haloIntensity)) : 1.0;
    /** Map the picker hex (which typically has brightness ~0.15–0.4) directly
     *  through intensity. Keep a ceiling so dragging intensity to max stays
     *  readable instead of nuking the whole sky with bloom. */
    const centerR = Math.min(0.8, haloHex[0] * haloIntensity);
    const centerG = Math.min(0.8, haloHex[1] * haloIntensity);
    const centerB = Math.min(0.8, haloHex[2] * haloIntensity);
    const positions = [];
    const colors = [];
    const idx = [];
    /** Center vertex (brightest point of the gradient). */
    positions.push(galaxyCenter.x, galaxyCenter.y, galaxyCenter.z);
    colors.push(centerR, centerG, centerB);
    const tmpOut = [0, 0, 0];
    for (let r = 1; r <= RINGS; r++) {
      const rad = (r / RINGS) * GAL_HALO_R;
      /** Steeper exponential: drops to ~1% by `tNorm=0.6`, zero by `tNorm=1.0`.
       *  The `fall - tailClip` subtraction + max(0) guarantees the outermost
       *  rings are *exactly* zero so there's no visible boundary oval. */
      const tNorm = r / RINGS;
      const rawFall = Math.exp(-tNorm * tNorm * 7.0);
      const tailClip = 0.02;
      const fall = Math.max(0, rawFall - tailClip) / (1 - tailClip);
      const colR = centerR * fall;
      const colG = centerG * fall;
      const colB = centerB * fall + 0.02 * fall;
      for (let s = 0; s < SEGS; s++) {
        const ang = (s / SEGS) * Math.PI * 2;
        galLocalToWorld(Math.cos(ang) * rad, Math.sin(ang) * rad, 0, tmpOut);
        positions.push(tmpOut[0], tmpOut[1], tmpOut[2]);
        colors.push(colR, colG, colB);
      }
    }
    /** First ring: fan from center. */
    for (let s = 0; s < SEGS; s++) {
      const a = 0;
      const b = 1 + s;
      const c = 1 + ((s + 1) % SEGS);
      idx.push(a, b, c);
    }
    /** Subsequent rings: quad strip between ring r-1 and ring r. */
    for (let r = 1; r < RINGS; r++) {
      const prevStart = 1 + (r - 1) * SEGS;
      const currStart = 1 + r * SEGS;
      for (let s = 0; s < SEGS; s++) {
        const sn = (s + 1) % SEGS;
        const a = prevStart + s;
        const b = prevStart + sn;
        const c = currStart + s;
        const d = currStart + sn;
        idx.push(a, c, b, b, c, d);
      }
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
    geo.setIndex(idx);
    const mat = new THREE.MeshBasicMaterial({
      vertexColors: true,
      side: THREE.DoubleSide,
      transparent: true,
      opacity: 1.0,
      blending: THREE.AdditiveBlending,
      depthTest: true,
      depthWrite: false,
      fog: false,
    });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.frustumCulled = false;
    mesh.renderOrder = -1002;
    mesh.name = 'GalaxyHalo';
    root.add(mesh);
  }

  /** Layer 2 — Spiral arm stars. Log-spiral: `theta(r) = armBase + ln(r/R0)/k`.
   *  Small `k` → tightly wound; large `k` → loose. `ARM_COUNT=4` looks the most
   *  visually galactic (2 is flat, 3 feels lopsided, 4+ reads as a typical
   *  barred spiral like NGC 1365). Each star is placed on an arm then offset
   *  tangentially + perpendicularly so arms look scattered, not drawn with a
   *  pencil. Radial bias `^1.7` concentrates stars toward the inner disc.
   *
   *  All three of `ARM_STARS`, `ARM_COUNT`, `SPIRAL_K` are live-editable via
   *  the editor panel's "Galaxy — shape" section. */
  const ARM_STARS = Math.max(0, Math.round(Number.isFinite(gal.armStars) ? gal.armStars : 14000));
  const ARM_COUNT = Math.max(2, Math.min(8, Math.round(Number.isFinite(gal.armCount) ? gal.armCount : 4)));
  const SPIRAL_K = Number.isFinite(gal.spiralTightness) ? Math.max(0.1, Math.min(2.5, gal.spiralTightness)) : 0.78;
  const armInner = rgbFromHexSafe(gal.armColorInner, [1.0, 0.92, 0.72]);
  const armOuter = rgbFromHexSafe(gal.armColorOuter, [0.7, 0.82, 1.0]);
  const armAccent1 = rgbFromHexSafe(gal.armAccent1, [1.0, 0.35, 0.5]);
  const armAccent2 = rgbFromHexSafe(gal.armAccent2, [0.95, 0.55, 0.35]);
  if (GALAXY_ENABLED && ARM_STARS > 0) {
    const positions = new Float32Array(ARM_STARS * 3);
    const colors = new Float32Array(ARM_STARS * 3);
    const tmpOut = [0, 0, 0];
    let wi = 0;
    for (let i = 0; i < ARM_STARS; i++) {
      const armIdx = (Math.random() * ARM_COUNT) | 0;
      const armBase = (armIdx / ARM_COUNT) * Math.PI * 2;
      const rNorm = Math.pow(Math.random(), 1.7);
      const r = GAL_BULGE_R * 0.7 + rNorm * (GAL_OUTER_R - GAL_BULGE_R * 0.7);
      const baseTheta = armBase + Math.log(r / (GAL_BULGE_R * 0.3 + 1e-3)) / SPIRAL_K;
      /** Arm-crossing scatter: along-arm jitter + radial jitter. Radial jitter
       *  grows with radius so outer arms look wider / fluffier than the inner
       *  tight coil. */
      const tangJit = (Math.random() - 0.5) * 0.55;
      const radJit = (Math.random() - 0.5) * (80 + r * 0.14);
      const theta = baseTheta + tangJit;
      const rr = r + radJit;
      const px = Math.cos(theta) * rr;
      const py = Math.sin(theta) * rr;
      /** Thickness scatter along galaxy normal — disc is fatter at the core. */
      const thick = GAL_DISC_HALF_THICKNESS * (1 - rNorm * 0.55);
      const pz = (Math.random() - 0.5) * 2 * thick;
      galLocalToWorld(px, py, pz, tmpOut);
      positions[wi] = tmpOut[0];
      positions[wi + 1] = tmpOut[1];
      positions[wi + 2] = tmpOut[2];
      /** Color gradient: inner arms are warm yellow-white, outer arms drift
       *  bluer (hotter young stars live in the outer arms IRL); a fraction
       *  get tagged red-dust or HII-region magenta to break up the palette. */
      const bright = 0.45 + Math.random() * 0.55;
      let cr, cg, cb;
      const tag = Math.random();
      if (tag < 0.06) {
        cr = armAccent1[0]; cg = armAccent1[1]; cb = armAccent1[2];
      } else if (tag < 0.12) {
        cr = armAccent2[0]; cg = armAccent2[1]; cb = armAccent2[2];
      } else {
        cr = THREE.MathUtils.lerp(armInner[0], armOuter[0], rNorm);
        cg = THREE.MathUtils.lerp(armInner[1], armOuter[1], rNorm);
        cb = THREE.MathUtils.lerp(armInner[2], armOuter[2], rNorm);
      }
      colors[wi] = cr * bright;
      colors[wi + 1] = cg * bright;
      colors[wi + 2] = cb * bright;
      wi += 3;
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    const mat = new THREE.PointsMaterial({
      size: 2.0,
      sizeAttenuation: false,
      vertexColors: true,
      transparent: true,
      opacity: 1.0,
      blending: THREE.AdditiveBlending,
      depthTest: true,
      depthWrite: false,
      fog: false,
    });
    const points = new THREE.Points(geo, mat);
    points.frustumCulled = false;
    points.renderOrder = -1000;
    points.name = 'GalaxySpiralStars';
    root.add(points);
  }

  /** Layer 3 — Central bulge. A dense, 3D spherical cluster centered on the
   *  galaxy core, biased heavily toward small radii (Math.pow(u, 2.5)) so the
   *  bulge reads as a bright nucleus rather than a vague smear. We exclude
   *  anything inside GAL_BH_R + a small margin so the black hole silhouette
   *  isn't painted over from behind by bulge stars. */
  const BULGE_STARS = Math.max(0, Math.round(Number.isFinite(gal.bulgeStars) ? gal.bulgeStars : 4500));
  if (GALAXY_ENABLED && BULGE_STARS > 0) {
    const positions = new Float32Array(BULGE_STARS * 3);
    const colors = new Float32Array(BULGE_STARS * 3);
    const tmpOut = [0, 0, 0];
    let wi = 0;
    /** Loop with retry to skip samples that fall inside the black hole. In
     *  practice the early exit only fires a few dozen times for 4500 samples,
     *  so perf is fine. */
    let produced = 0;
    while (produced < BULGE_STARS) {
      const rFrac = Math.pow(Math.random(), 2.5);
      const r = rFrac * GAL_BULGE_R;
      if (r < GAL_BH_R * 1.15) continue;
      /** Uniform direction on unit sphere (Marsaglia). */
      const u = Math.random() * 2 - 1;
      const t = Math.random() * Math.PI * 2;
      const s = Math.sqrt(Math.max(0, 1 - u * u));
      /** Flatten the bulge slightly along the disc normal so it matches the
       *  disc thickness — otherwise the core looks like a perfect orb instead
       *  of an oblate bulge. */
      const px = s * Math.cos(t) * r;
      const py = s * Math.sin(t) * r;
      const pz = u * r * 0.55;
      galLocalToWorld(px, py, pz, tmpOut);
      positions[wi] = tmpOut[0];
      positions[wi + 1] = tmpOut[1];
      positions[wi + 2] = tmpOut[2];
      const bright = 0.55 + Math.random() * 0.45;
      /** Core color: warm yellow-white, very slight pink dust-lane tint. */
      colors[wi] = (1.0 - rFrac * 0.1) * bright;
      colors[wi + 1] = (0.92 - rFrac * 0.18) * bright;
      colors[wi + 2] = (0.75 - rFrac * 0.25) * bright;
      wi += 3;
      produced++;
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    const mat = new THREE.PointsMaterial({
      size: 2.6,
      sizeAttenuation: false,
      vertexColors: true,
      transparent: true,
      opacity: 1.0,
      blending: THREE.AdditiveBlending,
      depthTest: true,
      depthWrite: false,
      fog: false,
    });
    const points = new THREE.Points(geo, mat);
    points.frustumCulled = false;
    points.renderOrder = -999;
    points.name = 'GalaxyBulge';
    root.add(points);
  }

  /** Helper to build a flat ring annulus in the galaxy plane with a radial
   *  color gradient from `innerColor` (at `innerR`) to `outerColor` (at `outerR`).
   *  Additive blending. Used for the accretion disc + photon ring. */
  const buildGalaxyRing = (innerR, outerR, innerColor, outerColor, segs, radialSegs, name, order) => {
    const positions = [];
    const colors = [];
    const idx = [];
    const tmpOut = [0, 0, 0];
    for (let ri = 0; ri <= radialSegs; ri++) {
      const rf = ri / radialSegs;
      const rad = innerR + (outerR - innerR) * rf;
      const cr = innerColor[0] + (outerColor[0] - innerColor[0]) * rf;
      const cg = innerColor[1] + (outerColor[1] - innerColor[1]) * rf;
      const cb = innerColor[2] + (outerColor[2] - innerColor[2]) * rf;
      for (let si = 0; si <= segs; si++) {
        const ang = (si / segs) * Math.PI * 2;
        galLocalToWorld(Math.cos(ang) * rad, Math.sin(ang) * rad, 0, tmpOut);
        positions.push(tmpOut[0], tmpOut[1], tmpOut[2]);
        colors.push(cr, cg, cb);
      }
    }
    const cols = segs + 1;
    for (let ri = 0; ri < radialSegs; ri++) {
      for (let si = 0; si < segs; si++) {
        const a = ri * cols + si;
        const b = a + 1;
        const c = (ri + 1) * cols + si;
        const d = c + 1;
        idx.push(a, c, b, b, c, d);
      }
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
    geo.setIndex(idx);
    const mat = new THREE.MeshBasicMaterial({
      vertexColors: true,
      side: THREE.DoubleSide,
      transparent: true,
      opacity: 1.0,
      blending: THREE.AdditiveBlending,
      depthTest: true,
      depthWrite: false,
      fog: false,
    });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.frustumCulled = false;
    mesh.renderOrder = order;
    mesh.name = name;
    root.add(mesh);
  };

  /** Layer 4 — Accretion disc. Glowing hot plasma orbiting the black hole,
   *  bright yellow-white at the inner edge fading to deep orange at the outer
   *  edge. Drawn *under* the black hole in render order so the BH silhouette
   *  cleanly cuts the inner disc hole. Colors come from the editor so the
   *  player can swap the default orange plasma for icy blue or purple.
   *  We boost each RGB by 1.8× (inner) / 1.2× (outer) so the picker's mid-
   *  brightness hex still reads bright against the dark sky under additive
   *  blending. */
  if (GALAXY_ENABLED) {
    const accInner = rgbFromHexSafe(gal.accretionInner, [1.0, 0.78, 0.5]);
    const accOuter = rgbFromHexSafe(gal.accretionOuter, [0.35, 0.1, 0.0]);
    buildGalaxyRing(
      GAL_BH_R * 1.02,
      GAL_ACC_R,
      [accInner[0] * 1.8, accInner[1] * 1.8, accInner[2] * 1.8],
      [accOuter[0] * 1.2, accOuter[1] * 1.2, accOuter[2] * 1.2],
      128,
      10,
      'GalaxyAccretionDisc',
      -998
    );

    /** Layer 5 — Photon ring. Razor-thin bright white ring right at the event
     *  horizon (in reality it's light bent around the BH). Makes the silhouette
     *  pop and gives the classic "Interstellar" look. Color is live-editable
     *  via `galaxy.photonColor`; we boost ×2.5 so it still blows out like a
     *  lightsaber under additive blending. */
    const photon = rgbFromHexSafe(gal.photonColor, [1.0, 0.95, 0.88]);
    buildGalaxyRing(
      GAL_PHOTON_R,
      GAL_PHOTON_R * 1.04,
      [photon[0] * 2.5, photon[1] * 2.5, photon[2] * 2.5],
      [photon[0] * 2.2, photon[1] * 2.2, photon[2] * 2.2],
      128,
      2,
      'GalaxyPhotonRing',
      -997
    );
  }

  /** Layer 6 — Black hole silhouette. Pure opaque black circle with NORMAL
   *  blending (not additive) so it paints over the galaxy glow / bulge / stars
   *  that would otherwise shine through. Faces along the galaxy normal so it
   *  lines up with the disc plane. renderOrder is high-but-still-negative so
   *  it draws AFTER all the additive galaxy layers but BEFORE any gameplay. */
  if (GALAXY_ENABLED) {
    const SEGS = 96;
    const positions = [];
    const idx = [];
    const tmpOut = [0, 0, 0];
    galLocalToWorld(0, 0, 0, tmpOut);
    positions.push(tmpOut[0], tmpOut[1], tmpOut[2]);
    for (let i = 0; i < SEGS; i++) {
      const ang = (i / SEGS) * Math.PI * 2;
      galLocalToWorld(Math.cos(ang) * GAL_BH_R, Math.sin(ang) * GAL_BH_R, 0, tmpOut);
      positions.push(tmpOut[0], tmpOut[1], tmpOut[2]);
    }
    for (let i = 0; i < SEGS; i++) {
      const a = 0;
      const b = 1 + i;
      const c = 1 + ((i + 1) % SEGS);
      idx.push(a, b, c);
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geo.setIndex(idx);
    const mat = new THREE.MeshBasicMaterial({
      color: 0x000000,
      side: THREE.DoubleSide,
      transparent: false,
      opacity: 1.0,
      blending: THREE.NormalBlending,
      depthTest: true,
      depthWrite: false,
      fog: false,
    });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.frustumCulled = false;
    mesh.renderOrder = -996;
    mesh.name = 'GalaxyBlackHole';
    root.add(mesh);
  }

  /** Two faint nebula shells in complementary colors — gives the sky color + depth instead of
   *  flat black between stars. Back-side sphere so the player sees them from inside; additive
   *  so they layer with the stars. Intentionally large-scale soft vertex gradient.
   *
   *  Colors and intensity are editor-driven. Intensity scales opacity so the
   *  player can push nebulae from "barely there" haze up to full-on
   *  gas-cloud drama in real time. */
  const nebCfg = cfg?.nebula || {};
  const nebIntensity = Number.isFinite(nebCfg.intensity) ? Math.max(0, Math.min(3, nebCfg.intensity)) : 1.0;
  const nebulas = [
    { color: new THREE.Color(nebCfg.color1 || '#3a2a7a').getHex(), scale: 1.0, opacity: 0.12 * nebIntensity },
    { color: new THREE.Color(nebCfg.color2 || '#7a2a5a').getHex(), scale: 1.05, opacity: 0.08 * nebIntensity },
  ];
  for (const neb of nebulas) {
    const geo = new THREE.SphereGeometry(STAR_SHELL * neb.scale, 32, 20);
    /** Per-vertex noise-ish color gradient — fake a patchy nebula without loading a texture. */
    const vertexCount = geo.attributes.position.count;
    const colors = new Float32Array(vertexCount * 3);
    const baseCol = new THREE.Color(neb.color);
    for (let i = 0; i < vertexCount; i++) {
      /** Use vertex position to derive a pseudo-noise multiplier so the nebula has visible patches. */
      const x = geo.attributes.position.getX(i) / STAR_SHELL;
      const y = geo.attributes.position.getY(i) / STAR_SHELL;
      const z = geo.attributes.position.getZ(i) / STAR_SHELL;
      const n =
        Math.sin(x * 3.1) * Math.cos(y * 2.7) * Math.sin(z * 2.3) * 0.5 +
        Math.sin(x * 7 + y * 5) * 0.25 +
        0.5;
      const k = Math.max(0, Math.min(1, n));
      colors[i * 3] = baseCol.r * k;
      colors[i * 3 + 1] = baseCol.g * k;
      colors[i * 3 + 2] = baseCol.b * k;
    }
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    const mat = new THREE.MeshBasicMaterial({
      vertexColors: true,
      side: THREE.BackSide,
      transparent: true,
      opacity: neb.opacity,
      blending: THREE.AdditiveBlending,
      /** Nebula is a giant shell behind everything; depth test ON so planets + stars occlude it correctly. */
      depthTest: true,
      depthWrite: false,
      fog: false,
    });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.frustumCulled = false;
    mesh.renderOrder = -1001;
    root.add(mesh);
  }

  /** ────────────────────────────────────────────────────────────────────────
   *   Featured nebula cloud
   *  ────────────────────────────────────────────────────────────────────────
   *  A dense, patchy emission-nebula-style cluster hanging in a specific
   *  corner of the sky — the classic "Pillars of Creation" / Carina look.
   *  Implemented as a 3-D gaussian blob of additively-blended soft point
   *  sprites, with per-point color interpolated from `colorCore → colorMid
   *  → colorEdge` based on distance from the cluster center.
   *
   *  We build a tiny on-the-fly canvas texture (radial-gradient disc) so
   *  every point reads as a soft glow blob rather than a hard square. All
   *  params are live-editable via the "Nebula cloud (featured)" editor
   *  section. Keeping the density ≤ 4000 so even worst-case rebuild cost
   *  stays under ~10 ms. */
  const cloudCfg = cfg?.nebulaCloud || {};
  if (cloudCfg.enabled !== false) {
    const cloudDensity = Math.max(50, Math.min(6000, Math.round(Number.isFinite(cloudCfg.density) ? cloudCfg.density : 1400)));
    const cloudSize = Math.max(200, Math.min(6000, Number.isFinite(cloudCfg.size) ? cloudCfg.size : 2200));
    const cloudIntensity = Number.isFinite(cloudCfg.intensity) ? Math.max(0, Math.min(3, cloudCfg.intensity)) : 1.0;
    const dirVec = new THREE.Vector3(
      Number.isFinite(cloudCfg.dirX) ? cloudCfg.dirX : -0.72,
      Number.isFinite(cloudCfg.dirY) ? cloudCfg.dirY : 0.28,
      Number.isFinite(cloudCfg.dirZ) ? cloudCfg.dirZ : 0.62,
    );
    if (dirVec.lengthSq() < 1e-6) dirVec.set(0, 1, 0);
    dirVec.normalize();
    /** Center the cloud on a shell slightly closer than the starfield so stars
     *  punch through it rather than sitting behind a hazy wall. */
    const cloudCenter = dirVec.multiplyScalar(STAR_SHELL * 0.9);

    const colCore = rgbFromHexSafe(cloudCfg.colorCore, [1.0, 0.46, 0.85]);
    const colMid = rgbFromHexSafe(cloudCfg.colorMid, [0.65, 0.42, 1.0]);
    const colEdge = rgbFromHexSafe(cloudCfg.colorEdge, [0.22, 0.38, 1.0]);

    const positions = new Float32Array(cloudDensity * 3);
    const colors = new Float32Array(cloudDensity * 3);
    /** Per-point size variation — big soft blobs for the "core" of the
     *  cluster, smaller tighter specks at the fringe. Encoded in a separate
     *  attribute so a single PointsMaterial size acts as the max; we scale
     *  via vertex color alpha *visually* since PointsMaterial doesn't read
     *  per-vertex sizes without a custom shader. Instead we simulate size
     *  variation through brightness (brighter = visually bigger under
     *  additive blending). */
    let wi = 0;
    for (let i = 0; i < cloudDensity; i++) {
      /** Sum-of-gaussians for a puffier, less spherical blob. Stacking two
       *  offset gaussians gives the cloud an irregular peanut/heart shape
       *  rather than a perfect orb. */
      const gx1 = (gaussian() + gaussian()) * 0.35;
      const gy1 = (gaussian() + gaussian()) * 0.35;
      const gz1 = (gaussian() + gaussian()) * 0.35;
      const bumpX = Math.cos(i * 0.037) * 0.28;
      const bumpY = Math.sin(i * 0.053) * 0.22;
      const rx = (gx1 + bumpX) * cloudSize;
      const ry = (gy1 + bumpY) * cloudSize * 0.85;
      const rz = gz1 * cloudSize * 0.7;
      positions[wi] = cloudCenter.x + rx;
      positions[wi + 1] = cloudCenter.y + ry;
      positions[wi + 2] = cloudCenter.z + rz;
      /** Normalized distance from the cluster center drives the 3-stop
       *  color gradient: core → mid → edge. */
      const dist = Math.sqrt(rx * rx + ry * ry + rz * rz) / cloudSize;
      const d = Math.min(1, dist * 0.9);
      let cr, cg, cb;
      if (d < 0.5) {
        const t = d / 0.5;
        cr = THREE.MathUtils.lerp(colCore[0], colMid[0], t);
        cg = THREE.MathUtils.lerp(colCore[1], colMid[1], t);
        cb = THREE.MathUtils.lerp(colCore[2], colMid[2], t);
      } else {
        const t = (d - 0.5) / 0.5;
        cr = THREE.MathUtils.lerp(colMid[0], colEdge[0], t);
        cg = THREE.MathUtils.lerp(colMid[1], colEdge[1], t);
        cb = THREE.MathUtils.lerp(colMid[2], colEdge[2], t);
      }
      /** Core points glow brighter, outer points dimmer — carves out a
       *  bright nucleus regardless of color choice. Random flicker keeps
       *  the cloud from looking like a geometric falloff. */
      const coreBoost = 1.0 - d * 0.55;
      const flicker = 0.65 + Math.random() * 0.55;
      const k = cloudIntensity * coreBoost * flicker;
      colors[wi] = cr * k;
      colors[wi + 1] = cg * k;
      colors[wi + 2] = cb * k;
      wi += 3;
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    const mat = new THREE.PointsMaterial({
      size: 64,
      sizeAttenuation: true,
      vertexColors: true,
      transparent: true,
      opacity: 0.75,
      blending: THREE.AdditiveBlending,
      depthTest: true,
      depthWrite: false,
      fog: false,
      map: getSoftDiscTexture(),
      alphaTest: 0.01,
    });
    const points = new THREE.Points(geo, mat);
    points.frustumCulled = false;
    points.renderOrder = -1000;
    points.name = 'FeaturedNebulaCloud';
    root.add(points);
  }

  /**
   * ──────────────────────────────────────────────────────────────────────────
   *  Bookmark sprite layer
   * ──────────────────────────────────────────────────────────────────────────
   *
   * Each hand-authored planet gets a small glowing disc sprite placed at its
   * direction on the celestial shell. Purpose: when the player is in space
   * and looks up, they can pick out "where's home?" / "where's xenon?" with
   * a glance. Distinct color (cyan) so it doesn't blend into the spectral
   * palette of procedural stars.
   *
   * Implementation: single `THREE.Points` using the cached soft-disc texture
   * (`getSoftDiscTexture`) so we don't pay for another CanvasTexture build.
   * Pulled out to its own layer (instead of mixing into the star layers) so
   * we can style it separately (bigger, brighter, in front of the normal
   * star shell) and toggle it off later without touching the catalog loop.
   */
  if (catalog.bookmarks.length > 0) {
    const bmCount = catalog.bookmarks.length;
    const bmPositions = new Float32Array(bmCount * 3);
    const bmColors = new Float32Array(bmCount * 3);
    /** Place bookmarks slightly INSIDE the star shell so they render in
     *  front of the catalog stars and their additive glow reads clearly
     *  over the field behind them. 0.995 = ~45 units inside STAR_SHELL. */
    const BM_SHELL = STAR_SHELL * 0.995;
    for (let i = 0; i < bmCount; i++) {
      const bm = catalog.bookmarks[i];
      bmPositions[i * 3]     = bm.dir[0] * BM_SHELL;
      bmPositions[i * 3 + 1] = bm.dir[1] * BM_SHELL;
      bmPositions[i * 3 + 2] = bm.dir[2] * BM_SHELL;
      /** Multiply bookmark color by its magnitude so Phase 2+ can set
       *  different per-planet brightness (e.g. dim a discovered planet). */
      bmColors[i * 3]     = Math.max(0, Math.min(1, bm.color[0] * bm.magnitude));
      bmColors[i * 3 + 1] = Math.max(0, Math.min(1, bm.color[1] * bm.magnitude));
      bmColors[i * 3 + 2] = Math.max(0, Math.min(1, bm.color[2] * bm.magnitude));
    }
    const bmGeo = new THREE.BufferGeometry();
    bmGeo.setAttribute('position', new THREE.BufferAttribute(bmPositions, 3));
    bmGeo.setAttribute('color', new THREE.BufferAttribute(bmColors, 3));
    const bmMat = new THREE.PointsMaterial({
      /** `8 * sizeMul` = noticeably larger than bright stars (5.2 * sizeMul)
       *  so the player can spot their home planet marker without squinting,
       *  but not SO big that it looks like a nebula. */
      size: 8 * sizeMul,
      sizeAttenuation: false,
      vertexColors: true,
      transparent: true,
      opacity: 1.0,
      blending: THREE.AdditiveBlending,
      depthTest: true,
      depthWrite: false,
      fog: false,
      map: getSoftDiscTexture(),
      alphaTest: 0.01,
    });
    const bmPoints = new THREE.Points(bmGeo, bmMat);
    bmPoints.frustumCulled = false;
    /** Slightly higher render order than stars so we win overlap ties even
     *  if depth values match (same shell). */
    bmPoints.renderOrder = -999;
    bmPoints.name = 'CatalogBookmarkLayer';
    bmPoints.userData.bookmarkCount = bmCount;
    root.add(bmPoints);
  }

  /**
   * ──────────────────────────────────────────────────────────────────────────
   *  window.__starCatalog facade
   * ──────────────────────────────────────────────────────────────────────────
   *
   * Small runtime inspection surface for Phase 1 validation + Phase 2
   * targeting code. Reinstalled on every rebuild so it always reflects the
   * current catalog (after seed changes, density drags, preset swaps).
   *
   * Design: closure over `catalog` + `universeSeed`, so tests can stash the
   * reference and observe it even after the next rebuild disposes the old
   * one (old closure still works with its own snapshot). The facade is
   * intentionally minimal — no mutation helpers. Phase 2 adds the warp /
   * discovery surface.
   */
  if (typeof window !== 'undefined') {
    const capturedCatalog = catalog;
    const capturedSeed = universeSeed;
    window.__starCatalog = {
      /** Look up a single entry by ID (`s-<layer>-<index>` or `bm-<planetId>`). */
      get(id) {
        return capturedCatalog.byId.get(String(id)) || null;
      },
      /** Full catalog star array. Read-only — caller mutations won't persist
       *  past the next rebuild anyway, but avoid them for clarity. */
      all() { return capturedCatalog.stars; },
      /** Hand-authored planet bookmarks. */
      bookmarks() { return capturedCatalog.bookmarks; },
      /**
       * Find the catalog entry closest to the camera's current forward
       * direction. Returns `null` if nothing is within the default cone
       * (~0.6°), or `{ entry, dot, angleRad }` on hit. Use this from the
       * browser console to validate targeting before Phase 2 builds the
       * reticle UI:
       *   `__starCatalog.nearestInAim()`
       */
      nearestInAim(cosThresh) {
        if (!camera) return null;
        const fwd = new THREE.Vector3();
        camera.getWorldDirection(fwd);
        return findNearestStarInDirection(
          capturedCatalog,
          [fwd.x, fwd.y, fwd.z],
          typeof cosThresh === 'number' ? cosThresh : 0.9995,
        );
      },
      /** Current universe seed (read-only snapshot). */
      seed: capturedSeed,
      /** Canonical layer sizes (dust/medium/bright). */
      layerCounts: getStarLayerCounts(),
      /** Timestamp of generation, useful for asserting a rebuild happened. */
      generatedAt: capturedCatalog.metadata.generatedAt,
    };
  }

  scene.add(root);
  spaceBackgroundRoot = root;
}

/** Box-Muller-ish standard-normal sampler. Cheap + good enough for the
 *  nebula point distribution — we don't need a perfect normal. */
function gaussian() {
  const u1 = Math.max(1e-9, Math.random());
  const u2 = Math.random();
  return Math.sqrt(-2 * Math.log(u1)) * Math.cos(Math.PI * 2 * u2);
}

/** Cached soft radial-gradient disc texture for point-sprite nebulae. Built
 *  once on first use because constructing a canvas + texture is expensive
 *  enough that allocating it per rebuild would produce visible stutter on
 *  slider drags. */
let _softDiscTexture = null;
function getSoftDiscTexture() {
  if (_softDiscTexture) return _softDiscTexture;
  const size = 128;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  const grad = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  grad.addColorStop(0.0, 'rgba(255,255,255,1.0)');
  grad.addColorStop(0.25, 'rgba(255,255,255,0.72)');
  grad.addColorStop(0.55, 'rgba(255,255,255,0.22)');
  grad.addColorStop(1.0, 'rgba(255,255,255,0.0)');
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, size, size);
  const tex = new THREE.CanvasTexture(canvas);
  tex.minFilter = THREE.LinearFilter;
  tex.magFilter = THREE.LinearFilter;
  tex.needsUpdate = true;
  _softDiscTexture = tex;
  return tex;
}

/** Per-frame: stick the starfield origin to the camera so stars never parallax with player motion. */
function updateSpaceBackground() {
  if (!spaceBackgroundRoot || !camera) return;
  spaceBackgroundRoot.position.copy(camera.position);
}

/**
 * Per-frame: make sure `camera.far` is large enough to render the farthest planet from the player's
 * *current* position. Without this, flying 30 km off a planet makes all other planets pop out of
 * view at whatever fixed `far` we picked at init.
 *
 * We only grow `far`; shrinking it each frame would cause constant matrix rebuilds. The starfield
 * shell lives at ~9 km from the camera so we also guarantee `far >= 12 km` so stars always render.
 */
function updateCameraFarForSpaceTravel() {
  if (!camera || !spherePhysicsActive || !planetPhysicsEntries.length) return;
  let maxReach = 12000;
  for (const e of planetPhysicsEntries) {
    const dx = camera.position.x - e.center.x;
    const dy = camera.position.y - e.center.y;
    const dz = camera.position.z - e.center.z;
    /** Distance to this planet's *far* surface (the side away from the camera). */
    const d = Math.sqrt(dx * dx + dy * dy + dz * dz) + e.radius;
    if (d > maxReach) maxReach = d;
  }
  const target = maxReach * 1.2;
  /** Only adjust when the gap is meaningful to avoid rebuilding the projection matrix every frame. */
  if (target > camera.far * 1.02 || target < camera.far * 0.5) {
    camera.far = target;
    camera.updateProjectionMatrix();
  }
}

/** Dispose the space background + all of its layer points/meshes (currently only used by hot reloads). */
function disposeSpaceBackground() {
  if (!spaceBackgroundRoot) return;
  spaceBackgroundRoot.traverse((o) => {
    if (o.isPoints || o.isMesh) {
      o.geometry?.dispose?.();
      const m = o.material;
      if (Array.isArray(m)) m.forEach((x) => x?.dispose?.());
      else m?.dispose?.();
    }
  });
  spaceBackgroundRoot.removeFromParent();
  spaceBackgroundRoot = null;
}

/**
 * Tear down and re-create the entire space background using the latest
 * values in the space-bg editor config. Called from the editor whenever
 * the player tweaks a slider / color / preset. Also refreshes the scene
 * clear color so the "Background" swatch updates live. Cheap-ish even at
 * 30k arm stars because we only rebuild on debounced config changes.
 */
function rebuildSpaceBackground() {
  if (!scene) return;
  try {
    disposeSpaceBackground();
    const cfg = getSpaceBgConfig();
    let bg = '#01020a';
    try {
      bg = new THREE.Color(cfg?.sky?.bg || '#01020a').getHexString();
      bg = `#${bg}`;
    } catch (_) { /* bad hex → keep safe fallback */ }
    scene.background = new THREE.Color(bg);
    buildSpaceBackground();
  } catch (err) {
    console.warn('[space-bg] rebuild failed — ignoring', err);
  }
}
/** Infinite grass plane; hidden when map JSON provides generated terrain mesh. */
let gameGroundMesh = null;
let mapTerrainMesh = null;
/** First-person axe mesh (child of {@link fpToolRig}, synced to camera each frame — camera is not in scene). */
let heldAxeViewRoot = null;
/** Follows camera pose in world space so FP tools render in the scene graph. */
let fpToolRig = null;
/** @type {ReturnType<typeof createNeonBloomPipeline> | null} */
let neonBloomPipeline = null;
let playerBody;
/** Set in initWorld; wood logs/trunks use this for stable player–wood contacts. */
let woodPhyMat = null;
/** Same material as the infinite ground plane — reused for land-plot colliders so wood sits on the soil top. */
let gameGroundPhyMat = null;
/** Set in initWorld — reused for map props + placed sawmills. */
let mapPropPhyMaterial = null;
let yaw = 0;
let pitch = 0;
/**
 * World-space tangent forward vector for the player **on a sphere-physics planet**. On flat worlds
 * {@link yaw} + {@link pitch} Euler drive the camera as before; on planets we instead keep this
 * heading vector tangent to the current planet and rotate it around radial-up when the mouse moves.
 *
 * Why a vector instead of an angle: when the player walks around a sphere (e.g. from north pole to
 * equator), the local "up" axis rotates. Keeping yaw as a scalar relative to world +Y would make
 * the camera roll wildly or make forward stop being forward. A heading vector parallel-transports
 * naturally — each frame we just re-project it onto the current tangent plane and it stays valid.
 *
 * @see updatePlayer (builds camera quaternion from heading × radialUp basis)
 * @see getIntendedWalkSphere (derives WASD walk vector from heading + right on the tangent plane)
 */
const playerHeading = new THREE.Vector3(0, 0, -1);
const keys = new Set();
let money = 0;

const HOTBAR_SIZE = 9;
/** @type {(string | null)[]} */
let hotbarSlots = Array(HOTBAR_SIZE).fill(null);
/** −1 = no slot selected (nothing “equipped”). */
let hotbarSelected = -1;
let inventoryPanelOpen = false;
let toolHintUntil = 0;
/** Land plots purchased from Bob (affects price tiers + save). */
let landPlots = 0;
/** Which of the four lots you own (0–3), or null before first purchase (legacy index). */
let landPlotId = null;
/** Stable id from map editor `plotSites[].id` — used to match owned lot after reordering map. */
let landPlotSlotId = null;
/** Resolved from map JSON or built-in defaults; drives Bob UI + meshes. */
let landPlotSites = [];
/** First-time land purchase: camera + UI to pick a lot. */
let bobLandShowcase = false;
let landShowcaseIndex = 0;
/** @type {THREE.Group | null} */
let landPlotsRoot = null;
/** @type {{ group: THREE.Group; soil: THREE.Mesh; ring: THREE.Mesh; slotId: string; plotCollider: import('cannon-es').Body }[]} */
let landPlotEntries = [];
/** Static placed sawmills (not in dynamicPairs). */
let sawmillsRoot = null;
/** Ghost placement for sawmill kit — grid only on owned plot. */
let sawmillPlacementMode = null;
/** Ghost placement for catalog blueprints — same plot rules as sawmill. */
let blueprintPlacementMode = null;
/** Saved structures placed on a plot (construction or done). */
let plotBlueprintsRoot = null;
/** Last line for {@link updateConstructionHudLine} from blueprint feeding. */
let constructionHudLive = null;
const _feedBoxPlank = new THREE.Box3();
const _feedBoxBp = new THREE.Box3();
const _feedCenterPlank = new THREE.Vector3();
const _feedCenterBp = new THREE.Vector3();

function getDynamicMeshForBody(body) {
  const p = dynamicPairs.find((x) => x.body === body);
  return p?.mesh ?? null;
}

function computePlankConstructionUnits(mesh) {
  const g = mesh.geometry?.parameters;
  if (!g) return 1;
  const W = Number(g.width) || 0.05;
  const H = Number(g.height) || 0.05;
  const D = Number(g.depth) || 0.05;
  const vol = W * H * D;
  const longest = Math.max(W, H, D);
  const val = 5 + vol * 420 + longest * 3.5;
  return Math.max(0.35, val / 16);
}

function pickIncompleteBlueprintOverlappingPlank(plankMesh) {
  if (!plotBlueprintsRoot) return null;
  plankMesh.updateMatrixWorld(true);
  _feedBoxPlank.setFromObject(plankMesh);
  let best = null;
  let bestD = Infinity;
  for (const grp of plotBlueprintsRoot.children) {
    if (grp.userData?.planetId != null && grp.userData.planetId !== runtimePlanetId) continue;
    if (grp.userData.bpComplete) continue;
    const bmesh = grp.userData.mainMesh;
    if (!bmesh) continue;
    bmesh.updateMatrixWorld(true);
    _feedBoxBp.setFromObject(bmesh);
    if (!_feedBoxPlank.intersectsBox(_feedBoxBp)) continue;
    _feedBoxPlank.getCenter(_feedCenterPlank);
    _feedBoxBp.getCenter(_feedCenterBp);
    const d = _feedCenterPlank.distanceToSquared(_feedCenterBp);
    if (d < bestD) {
      bestD = d;
      best = grp;
    }
  }
  return best;
}

function consumePlanksRegisteredForBlueprint(grp) {
  const ids = grp.userData.contributingPlankNetIds;
  if (!Array.isArray(ids) || ids.length === 0) return;
  for (const nid of ids) {
    const pair = dynamicPairs.find((p) => p.mesh.userData.netId === nid);
    if (!pair || pair.mesh.userData.kind !== 'plank') continue;
    if (grabTarget === pair.body) endGrab();
    unregisterDynamic(pair.mesh, pair.body);
  }
  grp.userData.contributingPlankNetIds = [];
}

function updatePlotBlueprintGrabFeed(_dt) {
  constructionHudLive = null;
  if (gamePaused || blueprintPlacementMode || !grabTarget || !plotBlueprintsRoot) {
    return;
  }
  const mesh = getDynamicMeshForBody(grabTarget);
  if (!mesh || mesh.userData.kind !== 'plank') return;

  const wasIn = mesh.userData.bpHoverInBp === true;
  const bp = pickIncompleteBlueprintOverlappingPlank(mesh);
  const def = bp?.userData.bpDef;
  if (!bp || !def) {
    mesh.userData.bpHoverInBp = false;
    constructionHudLive =
      'Overlap a sawmilled plank with the ghost — it adds % in one snap (bigger = more). Planks vanish at 100%.';
    return;
  }

  mesh.userData.bpHoverInBp = true;
  const bpInst = bp.userData.bpInstanceId;
  const need = Math.max(0, def.baseWood - bp.userData.woodApplied);
  const totPct = Math.min(
    100,
    Math.round((bp.userData.woodApplied / def.baseWood) * 100)
  );
  constructionHudLive = `Building: ${totPct}% — pull plank out & re-overlap with another plank (each plank snaps once)`;

  const regIds =
    bp.userData.contributingPlankNetIds || (bp.userData.contributingPlankNetIds = []);
  if (mesh.userData.netId != null && regIds.includes(mesh.userData.netId)) {
    return;
  }

  const snapMap = mesh.userData.bpSnapDoneFor || {};
  if (snapMap[bpInst]) {
    return;
  }

  const justEntered = !wasIn;
  if (!justEntered) return;
  if (need <= 0) return;

  const units = computePlankConstructionUnits(mesh);
  const add = Math.min(need, units);
  if (add <= 0) return;

  snapMap[bpInst] = true;
  mesh.userData.bpSnapDoneFor = snapMap;

  const skin = normalizeLogSkin(mesh.userData.logSkin);
  bp.userData.woodApplied += add;
  bp.userData.woodTally[skin] = (bp.userData.woodTally[skin] || 0) + add;

  const nid = mesh.userData.netId;
  if (nid != null && !regIds.includes(nid)) regIds.push(nid);

  refreshPlotBlueprintIncompleteVisual(bp);

  const jumpPct = Math.min(100, Math.round((add / def.baseWood) * 100));
  const newTot = Math.min(
    100,
    Math.round((bp.userData.woodApplied / def.baseWood) * 100)
  );
  constructionHudLive = `Snapped +${jumpPct}% → ${newTot}% total`;

  saveGame();

  if (bp.userData.woodApplied >= def.baseWood - 1e-5) {
    bp.userData.woodApplied = def.baseWood;
    finalizeBlueprintBuildState(bp);
    showClerkBubble(`<strong>${def.label}</strong> finished (${newTot}%).`, 3200);
    saveGame();
  }
}
const _sawGroundPlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
const _sawRayHit = new THREE.Vector3();
let grabTarget = null;
let grabLocal = new CANNON.Vec3();
/** Distance along view ray from camera to hold point (set from click depth). */
let grabHoldDistance = 2.1;
let nextNetId = 1;
let nextTreeId = 1;
let gamePaused = false;
let mouseSensMul = 1;
/** Dev: free-fly (no gravity clamp); toggled from dev menu. */
let devFlyMode = false;
/** Mouse-wheel zoom in freecam cannot go below this snapshot. */
let freecamMinFov = 75;
const freecamPos = new THREE.Vector3();
const freecamBodyAnchor = new THREE.Vector3();
let freecamBodyYawAnchor = 0;
/** Saved with {@link freecamBodyYawAnchor} when entering freecam — ghost-held axe uses FPS pose at the body, not the flying camera. */
let freecamBodyPitchAnchor = 0;
let freecamBodyHasAxeAnchor = false;
let freecamGhostRoot = null;
let freecamGhostAxeHolder = null;
let freecamGhostFallbackBodyRoot = null;
let freecamGhostCharacterRoot = null;
let freecamGhostCharacterBaseScale = 1;
let freecamGhostMixer = null;
let freecamGhostActiveAction = null;
const freecamGhostActions = { idle: null, walk: null, run: null };
const FREECAM_CHARACTER_TUNE_KEY = 'LT2_FreecamCharacterTune';
const FREECAM_CHARACTER_CLIP_HINTS = {
  idle: ['idle', 'stand', 'breath'],
  walk: ['walk'],
  run: ['run', 'sprint'],
};
const FREECAM_CHARACTER_DEFAULT_TUNE = {
  px: 0,
  py: 0,
  pz: 0,
  rx: 0,
  ry: 0,
  rz: 0,
  scale: 1,
  eyeY: 0,
};
const freecamCharacterTune = { ...FREECAM_CHARACTER_DEFAULT_TUNE };

/** View-space offset of first-person held axe root under {@link fpToolRig} (must exist before snapshot helpers reference it). */
const HELD_AXE_VIEW_PLACEMENT = {
  px: 0.36,
  py: -0.38,
  pz: -0.52,
  rx: -0.2,
  ry: 0.36,
  rz: 0.1,
};

const FREECAM_CHARACTER_OBJ_REL = 'models/character/Character.obj';
const FREECAM_CHARACTER_MTL_REL = 'models/character/Character.mtl';
const FREECAM_CHARACTER_RIG_RELS = ['models/character/Character.glb', 'models/character/Character.gltf'];
const FREECAM_MANUAL_ANIM_KEY = 'LT2_FreecamManualAnim';
const FREECAM_MANUAL_ANIM_DEFAULT = {
  duration: 1.2,
  loop: true,
  frames: [
    { t: 0, px: 0, py: 0, pz: 0, rx: 0, ry: 0, rz: 0 },
    { t: 1.2, px: 0, py: 0, pz: 0, rx: 0, ry: 0, rz: 0 },
  ],
};
const freecamManualAnim = JSON.parse(JSON.stringify(FREECAM_MANUAL_ANIM_DEFAULT));
let freecamManualAnimPlaying = false;
let freecamManualAnimTime = 0;
let freecamManualAnimSelectedIdx = 0;
let freecamManualAnimRecording = false;
let freecamManualAnimRecordStart = 0;
let freecamManualAnimLastCapture = -1;
const FREECAM_MANUAL_RECORD_STEP = 0.12;

function candidateAssetUrls(relPath) {
  const out = [];
  try {
    out.push(new URL(relPath, import.meta.url).href);
  } catch (_) {}
  if (typeof window !== 'undefined' && window.location?.href) {
    try {
      out.push(new URL(relPath, window.location.href).href);
    } catch (_) {}
    try {
      out.push(new URL(`/${relPath}`, window.location.origin).href);
    } catch (_) {}
  }
  return [...new Set(out)];
}

async function fetchTextWithSourceUrl(relPath) {
  const urls = candidateAssetUrls(relPath);
  let lastErr = new Error(`No URL candidates for ${relPath}`);
  for (const href of urls) {
    try {
      const res = await fetch(href, { cache: 'no-store' });
      if (res.ok) {
        return { text: await res.text(), href };
      }
      lastErr = new Error(`${relPath} -> HTTP ${res.status} (${href})`);
    } catch (e) {
      lastErr = new Error(`${relPath} fetch failed (${href}): ${e?.message || e}`);
    }
  }
  throw lastErr;
}

function readFreecamCharacterTuneFromStorage() {
  try {
    const raw = localStorage.getItem(FREECAM_CHARACTER_TUNE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    for (const k of ['px', 'py', 'pz', 'rx', 'ry', 'rz', 'scale', 'eyeY']) {
      if (typeof parsed?.[k] === 'number' && Number.isFinite(parsed[k])) freecamCharacterTune[k] = parsed[k];
    }
  } catch (_) {}
}

function writeFreecamCharacterTuneToStorage() {
  try {
    localStorage.setItem(FREECAM_CHARACTER_TUNE_KEY, JSON.stringify(freecamCharacterTune));
  } catch (_) {}
}

function getPlayerCameraYOffset() {
  return PLAYER_CAM_Y_OFFSET + freecamCharacterTune.eyeY;
}

function sanitizeManualFrame(f) {
  if (!f || typeof f !== 'object') return null;
  const out = {
    t: Number(f.t) || 0,
    px: Number(f.px) || 0,
    py: Number(f.py) || 0,
    pz: Number(f.pz) || 0,
    rx: Number(f.rx) || 0,
    ry: Number(f.ry) || 0,
    rz: Number(f.rz) || 0,
  };
  return out;
}

function sortManualFrames() {
  freecamManualAnim.frames.sort((a, b) => a.t - b.t);
}

function clampManualAnimState() {
  if (!Number.isFinite(freecamManualAnim.duration) || freecamManualAnim.duration <= 0.01) {
    freecamManualAnim.duration = 1.2;
  }
  if (!Array.isArray(freecamManualAnim.frames) || freecamManualAnim.frames.length < 2) {
    freecamManualAnim.frames = JSON.parse(JSON.stringify(FREECAM_MANUAL_ANIM_DEFAULT.frames));
  }
  freecamManualAnim.frames = freecamManualAnim.frames
    .map((f) => sanitizeManualFrame(f))
    .filter(Boolean);
  if (freecamManualAnim.frames.length < 2) {
    freecamManualAnim.frames = JSON.parse(JSON.stringify(FREECAM_MANUAL_ANIM_DEFAULT.frames));
  }
  sortManualFrames();
  if (!Number.isFinite(freecamManualAnimSelectedIdx)) freecamManualAnimSelectedIdx = 0;
  freecamManualAnimSelectedIdx = Math.max(0, Math.min(freecamManualAnim.frames.length - 1, freecamManualAnimSelectedIdx));
}

function readFreecamManualAnimFromStorage() {
  try {
    const raw = localStorage.getItem(FREECAM_MANUAL_ANIM_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (typeof parsed?.duration === 'number') freecamManualAnim.duration = parsed.duration;
    if (typeof parsed?.loop === 'boolean') freecamManualAnim.loop = parsed.loop;
    if (Array.isArray(parsed?.frames)) freecamManualAnim.frames = parsed.frames;
  } catch (_) {}
  clampManualAnimState();
}

function writeFreecamManualAnimToStorage() {
  clampManualAnimState();
  try {
    localStorage.setItem(FREECAM_MANUAL_ANIM_KEY, JSON.stringify(freecamManualAnim));
  } catch (_) {}
}

function getFreecamCharacterManualBase() {
  if (!freecamGhostCharacterRoot) return null;
  return freecamGhostCharacterRoot.userData.manualAnimBase || null;
}

function captureCurrentManualPoseAt(t) {
  const base = getFreecamCharacterManualBase();
  if (!freecamGhostCharacterRoot || !base) return null;
  return {
    t: Number(t) || 0,
    px: freecamGhostCharacterRoot.position.x - base.px,
    py: freecamGhostCharacterRoot.position.y - base.py,
    pz: freecamGhostCharacterRoot.position.z - base.pz,
    rx: freecamGhostCharacterRoot.rotation.x - base.rx,
    ry: freecamGhostCharacterRoot.rotation.y - base.ry,
    rz: freecamGhostCharacterRoot.rotation.z - base.rz,
  };
}

function applyManualPose(pose) {
  const base = getFreecamCharacterManualBase();
  if (!freecamGhostCharacterRoot || !base || !pose) return;
  freecamGhostCharacterRoot.position.set(base.px + pose.px, base.py + pose.py, base.pz + pose.pz);
  freecamGhostCharacterRoot.rotation.set(base.rx + pose.rx, base.ry + pose.ry, base.rz + pose.rz);
}

function setCharacterToManualFrame(idx) {
  if (!Array.isArray(freecamManualAnim.frames) || !freecamManualAnim.frames.length) return;
  const i = Math.max(0, Math.min(freecamManualAnim.frames.length - 1, idx));
  freecamManualAnimSelectedIdx = i;
  applyManualPose(freecamManualAnim.frames[i]);
}

function evalManualPoseAt(t) {
  clampManualAnimState();
  const frames = freecamManualAnim.frames;
  if (frames.length === 1) return frames[0];
  const dur = Math.max(0.01, freecamManualAnim.duration);
  let tt = t;
  if (freecamManualAnim.loop) tt = ((tt % dur) + dur) % dur;
  else tt = Math.max(0, Math.min(dur, tt));
  let i1 = frames.length - 1;
  for (let i = 0; i < frames.length - 1; i++) {
    if (tt >= frames[i].t && tt <= frames[i + 1].t) {
      i1 = i;
      break;
    }
  }
  const a = frames[i1];
  const b = frames[Math.min(frames.length - 1, i1 + 1)];
  const span = Math.max(1e-6, b.t - a.t);
  const u = Math.max(0, Math.min(1, (tt - a.t) / span));
  return {
    px: a.px + (b.px - a.px) * u,
    py: a.py + (b.py - a.py) * u,
    pz: a.pz + (b.pz - a.pz) * u,
    rx: a.rx + (b.rx - a.rx) * u,
    ry: a.ry + (b.ry - a.ry) * u,
    rz: a.rz + (b.rz - a.rz) * u,
  };
}

function normalizeGhostCharacterObject(obj, targetHeight = 1.9) {
  obj.position.set(0, 0, 0);
  obj.scale.set(1, 1, 1);
  obj.quaternion.set(0, 0, 0, 1);
  obj.updateMatrixWorld(true);
  const box0 = new THREE.Box3().setFromObject(obj);
  if (box0.isEmpty()) throw new Error('Character object has empty bbox');
  const c0 = box0.getCenter(new THREE.Vector3());
  obj.position.sub(c0);
  obj.updateMatrixWorld(true);
  const box1 = new THREE.Box3().setFromObject(obj);
  const h = Math.max(1e-6, box1.max.y - box1.min.y);
  const scale = targetHeight / h;
  obj.scale.setScalar(scale);
  obj.updateMatrixWorld(true);
  const box2 = new THREE.Box3().setFromObject(obj);
  obj.position.y += -box2.min.y;
  obj.updateMatrixWorld(true);
}

function disposeFreecamCharacterRoot() {
  if (!freecamGhostCharacterRoot) return;
  const old = freecamGhostCharacterRoot;
  freecamGhostCharacterRoot = null;
  freecamGhostCharacterBaseScale = 1;
  if (freecamGhostRoot) freecamGhostRoot.remove(old);
  const matsDone = new Set();
  old.traverse((o) => {
    if (o.geometry) o.geometry.dispose();
    if (o.material) {
      const mats = Array.isArray(o.material) ? o.material : [o.material];
      for (const m of mats) {
        if (!m || matsDone.has(m)) continue;
        matsDone.add(m);
        if (m.map) m.map.dispose();
        if (m.emissiveMap) m.emissiveMap.dispose();
        if (m.alphaMap) m.alphaMap.dispose();
        if (m.dispose) m.dispose();
      }
    }
  });
}

function clearFreecamGhostAnimationState() {
  freecamGhostMixer = null;
  freecamGhostActiveAction = null;
  freecamGhostActions.idle = null;
  freecamGhostActions.walk = null;
  freecamGhostActions.run = null;
}

function applyFreecamCharacterTune() {
  if (freecamGhostCharacterRoot) {
    const s = Math.max(0.05, freecamCharacterTune.scale);
    freecamGhostCharacterRoot.scale.setScalar(freecamGhostCharacterBaseScale * s);
  }
}

function pickAnimationClipByHints(clips, hints) {
  if (!Array.isArray(clips) || !clips.length) return null;
  for (const hint of hints) {
    const found = clips.find((c) => c.name?.toLowerCase().includes(hint));
    if (found) return found;
  }
  return null;
}

function setFreecamGhostAction(name) {
  if (!freecamGhostMixer) return;
  const next = freecamGhostActions[name] || freecamGhostActions.idle;
  if (!next || next === freecamGhostActiveAction) return;
  next.reset().fadeIn(0.2).play();
  if (freecamGhostActiveAction) freecamGhostActiveAction.fadeOut(0.2);
  freecamGhostActiveAction = next;
}

function updateFreecamGhostAnimation(dt) {
  if (freecamManualAnimRecording && devFlyMode) {
    const now = performance.now() / 1000;
    const t = Math.max(0, now - freecamManualAnimRecordStart);
    if (t - freecamManualAnimLastCapture >= FREECAM_MANUAL_RECORD_STEP) {
      const frame = captureCurrentManualPoseAt(t);
      if (frame) {
        freecamManualAnim.frames.push(frame);
        sortManualFrames();
        freecamManualAnim.duration = Math.max(freecamManualAnim.duration, frame.t);
        freecamManualAnimSelectedIdx = freecamManualAnim.frames.length - 1;
        freecamManualAnimLastCapture = t;
        writeFreecamManualAnimToStorage();
      }
    }
  }
  if (freecamManualAnimPlaying) {
    freecamManualAnimTime += dt;
    const pose = evalManualPoseAt(freecamManualAnimTime);
    applyManualPose(pose);
    return;
  }
  if (!freecamGhostMixer) return;
  let next = 'idle';
  if (devFlyMode) {
    const moving = keys.has('KeyW') || keys.has('KeyA') || keys.has('KeyS') || keys.has('KeyD');
    if (moving) next = keys.has('ShiftLeft') || keys.has('ShiftRight') ? 'run' : 'walk';
  }
  setFreecamGhostAction(next);
  freecamGhostMixer.update(dt);
}

function attachFreecamGhostCharacter(obj, sourceLabel, clips = []) {
  if (!freecamGhostRoot) return;
  disposeFreecamCharacterRoot();
  clearFreecamGhostAnimationState();
  if (freecamGhostFallbackBodyRoot) freecamGhostFallbackBodyRoot.visible = false;
  freecamGhostCharacterRoot = obj;
  freecamGhostCharacterBaseScale = obj.scale.x || 1;
  freecamGhostCharacterRoot.userData.manualAnimBase = {
    px: obj.position.x,
    py: obj.position.y,
    pz: obj.position.z,
    rx: obj.rotation.x,
    ry: obj.rotation.y,
    rz: obj.rotation.z,
  };
  freecamGhostRoot.add(obj);
  applyFreecamCharacterTune();
  const box = new THREE.Box3().setFromObject(obj);
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  globalThis.LT_appendDebugLog?.(
    `[freecam ghost] ${sourceLabel} attached center=${center.x.toFixed(2)},${center.y.toFixed(2)},${center.z.toFixed(2)} size=${size.x.toFixed(2)},${size.y.toFixed(2)},${size.z.toFixed(2)}`
  );
  if (clips.length) {
    freecamGhostMixer = new THREE.AnimationMixer(obj);
    const idle = pickAnimationClipByHints(clips, FREECAM_CHARACTER_CLIP_HINTS.idle);
    const walk = pickAnimationClipByHints(clips, FREECAM_CHARACTER_CLIP_HINTS.walk);
    const run = pickAnimationClipByHints(clips, FREECAM_CHARACTER_CLIP_HINTS.run);
    freecamGhostActions.idle = idle ? freecamGhostMixer.clipAction(idle) : null;
    freecamGhostActions.walk = walk ? freecamGhostMixer.clipAction(walk) : null;
    freecamGhostActions.run = run ? freecamGhostMixer.clipAction(run) : null;
    setFreecamGhostAction('idle');
    globalThis.LT_appendDebugLog?.(
      `[freecam ghost] clips idle=${idle?.name || '-'} walk=${walk?.name || '-'} run=${run?.name || '-'}`
    );
  }
  if (!freecamGhostMixer && freecamManualAnim.frames.length) {
    setCharacterToManualFrame(freecamManualAnimSelectedIdx);
  }
  if (devFlyMode) snapshotFreecamAxeFromHeldView();
}

const UI_SETTINGS_KEY = 'LT2_UI_Settings';

const dynamicPairs = [];
/** Static Cannon boxes synced to bark meshes under standing trunks (not in dynamicPairs). */
const treeBranchStaticColliderList = [];
/** Wood sell zone — back loading dock of the store (see createStoreAndBob). Overridden by map `worldDef.store`. */
const STORE = { ...STORE_DEFAULTS };
/** Shop clerk “Rob” (near Bob). Overridden by map `worldDef.clerk`. */
const SHOP_CLERK = { ...CLERK_DEFAULTS };
/** Counter pivot (world): forward toward door + toward shelf wall so it clears Rob. */
const SHOP_COUNTER_PIVOT = { x: -21.45, z: -9.62 };
/** Counter top footprint in local rig space (half-sizes, meters) — see shopCounterRig +Y rotation. */
const SHOP_COUNTER_LOCAL_HALF_X = 1.33;
const SHOP_COUNTER_LOCAL_HALF_Z = 0.58;
/** Wall shelf unit — wide/thick decks for larger boxes (e.g. conveyors later). */
const SHOP_SHELF_FIXTURE = {
  groupX: -24.1,
  groupZ: -8.92,
  boardW: 3.15,
  boardD: 0.56,
  boardThick: 0.14,
  lowY: 1.28,
  highY: 1.88,
  backThick: 0.12,
  backD: 0.58,
  backH: 1.78,
};
const SHOP_ITEMS = {
  tape: { id: 'tape', label: "Builder's tape", price: 45, color: 0xc9a227 },
  gloves: { id: 'gloves', label: 'Work gloves', price: 85, color: 0x6b4423 },
  oil: { id: 'oil', label: 'Chain oil', price: 65, color: 0x3a4a8a },
  axe: { id: 'axe', label: 'Starter axe', price: 100, color: 0x6a5a4a },
  sawmill: { id: 'sawmill', label: 'Simple sawmill kit', price: 950, color: 0x8b6914 },
};
/** Wall shelves near x ≈ -24; restock anchors — y = lower deck top + product half-height. */
const SHOP_SHELF_SLOTS = (() => {
  const f = SHOP_SHELF_FIXTURE;
  const deckTop = f.lowY + f.boardThick / 2;
  const itemY = deckTop + 0.09;
  const upperY = f.highY + f.boardThick / 2 + 0.09;
  const cx = f.groupX;
  const z = f.groupZ;
  return [
    { x: cx - 0.95, y: itemY, z, shopId: 'tape' },
    { x: cx, y: itemY, z, shopId: 'gloves' },
    { x: cx + 0.95, y: itemY, z, shopId: 'oil' },
    { x: cx - 0.55, y: upperY, z, shopId: 'axe', size: { hw: 0.045, hh: 0.025, hd: 0.2 } },
  ];
})();
const SELL_DOCK_DEPTH = 4;
const sellCenter = new THREE.Vector3(
  STORE.x,
  0,
  STORE.z + STORE.D / 2 + SELL_DOCK_DEPTH / 2
);
/** Sell cylinder radius (m); can be overridden by map editor export in localStorage). */
let sellRadius = 3.5;
/**
 * World-space sell point. `sellCenter` is authored in **flat map space**, so on sphere worlds it
 * has to be projected onto the surface before any distance test — otherwise the sell zone sits at
 * the planet core and no drop ever registers. Kept in sync by {@link refreshSellCenterWorld}.
 */
const sellCenterWorld = new THREE.Vector3();
/** Surface-aligned parent for the sell sparkles, so the disc lies flat on the planet. */
let sellParticleAnchor = null;
const MAP_EDITOR_STORAGE_KEY = 'LT2_MapEditor';
/** Dev / P10.5: next full reload should treat this `planetId` as active (must exist in map `worlds[]`). */
const DEV_WARP_PLANET_SESSION_KEY = 'LT2_DevWarpPlanetId';
/** World/build grid step (m) — map editor + in-game placement use 1m snaps. */
const WORLD_GRID = 1;
function snapGrid(v) {
  return Math.round(v / WORLD_GRID) * WORLD_GRID;
}

/** Props + labels from map editor JSON (static meshes + Cannon bodies). */
let mapEditorWorldGroup = null;

/** Belt travel direction (−local Z) in world space + AABB for pushing logs/trunks. */
let conveyorBeltZones = [];
/** Sawmill planks mid-spawn: mesh scales up, then physics is added. */
let sawmillPlankRevealJobs = [];

let sellParticleGroup = null;

const SAWMILL = {
  // Whole sawmill size (tuned vs. screenshot).
  outerW: 2.4,
  outerH: 1.02,
  outerD: 1.8,
  /** Tunnel cross-section width (local Z) */
  // Larger opening despite the smaller body.
  tunnelW: 1.05,
  tunnelH: 1.05,
  /** Tunnel length (local X) — feed/opening toward +X (FRONT) */
  tunnelD: 2.1,
  /** Extra tunnel depth toward −X (BACK) so longer logs fit without moving the belt. */
  tunnelBackExtra: 0.95,
  /** Plank / external belt output (local −X) — “BACK” label */
  intakeZ: -1.02,
  intakeBand: 0.55,
  /** Legacy naming; plank spawn uses −local X (BACK belt). */
  outputZ: 1.02,
  /** Matches belt mesh in {@link buildSawmillModelProcedural} */
  beltLen: 0.9,
  /**
   * Shift the external exit belt (and plank spawn) further along −local X so the belt sits
   * mostly outside the tunnel shell at the BACK, not under the roof.
   */
  beltBackStickOut: 0.82,
  /** Extra −local X past belt center where the plank first appears (along exit direction). */
  plankSpawnPastBeltCenter: 0.38,
  /** Passed to {@link defaultSawmillConvertZoneDims} when no prefab zone is saved (long-log friendly). */
  convertZoneXPad: 1.3,
};

/** Center X of the short external belt in sawmill local space (negative = toward BACK). */
function getSawmillExternalBeltCenterLocalX() {
  const td = SAWMILL.tunnelD;
  const bl = SAWMILL.beltLen;
  const stick = Math.max(0, SAWMILL.beltBackStickOut ?? 0);
  const halfBelt = bl / 2;
  const beltRadiusAlongX = td / 2 + halfBelt - 0.11;
  return -beltRadiusAlongX - stick;
}

/**
 * Gameplay “fully inside sawmill” test (not physics). Loose on X/Z/Y so long logs on the floor
 * anywhere in the bore convert — the old box sat high and tight so only the entrance/belt zone passed.
 * Custom {@link THREE.Group#userData.sawmillConvertZone} from the prefab editor overrides defaults.
 * @param {THREE.Group | null | undefined} sawmillGrp
 */
function getSawmillTunnelAabbLocal(sawmillGrp) {
  const fromPrefab = normalizeSawmillConvertZone(sawmillGrp?.userData?.sawmillConvertZone);
  if (fromPrefab) return fromPrefab;
  return defaultSawmillConvertZoneDims(SAWMILL);
}

/**
 * Half-extents in mesh space for axis-aligned containment (box logs + cylinder logs).
 * @returns {{ hx: number; hy: number; hz: number } | null}
 */
function computeLogHalfExtentsFromMesh(mesh) {
  const sx = Math.abs(mesh.scale?.x ?? 1);
  const sy = Math.abs(mesh.scale?.y ?? 1);
  const sz = Math.abs(mesh.scale?.z ?? 1);
  const gp = mesh.geometry?.parameters || {};
  if (gp.width != null && gp.height != null && gp.depth != null) {
    return {
      hx: (gp.width / 2) * sx,
      hy: (gp.height / 2) * sy,
      hz: (gp.depth / 2) * sz,
    };
  }
  const r = gp.radiusTop ?? gp.radiusBottom ?? gp.radius;
  const h = gp.height;
  if (r != null && h != null && Number.isFinite(r) && Number.isFinite(h)) {
    const rxz = r * Math.max(sx, sz);
    return { hx: rxz, hy: (h / 2) * sy, hz: rxz };
  }
  return null;
}

/**
 * Normalized width × height × depth for sawmill plank sizing (cylinder → square cross-section).
 * @returns {{ width: number; height: number; depth: number } | null}
 */
function getLogSnapDimensionsForSawmill(mesh) {
  const sx = Math.abs(mesh.scale?.x ?? 1);
  const sy = Math.abs(mesh.scale?.y ?? 1);
  const sz = Math.abs(mesh.scale?.z ?? 1);
  const gp = mesh.geometry?.parameters || {};
  if (gp.width != null && gp.height != null && gp.depth != null) {
    return { width: gp.width * sx, height: gp.height * sy, depth: gp.depth * sz };
  }
  const r = gp.radiusTop ?? gp.radiusBottom ?? gp.radius;
  const h = gp.height;
  if (r != null && h != null && Number.isFinite(r) && Number.isFinite(h)) {
    const d = r * 2 * Math.max(sx, sz);
    return { width: d, height: h * sy, depth: d };
  }
  return null;
}

const _sawTmpQ = new THREE.Quaternion();
const _sawTmpQ2 = new THREE.Quaternion();
const _sawTmpM3 = new THREE.Matrix3();
/** Scratch matrices for {@link computeLogSawmillContainment} — it runs per log × per sawmill
 *  per frame (conveyor gate + sawmill intake), so allocating Matrix4s there churns the GC. */
const _sawTmpM4Inv = new THREE.Matrix4();
const _sawTmpM4Rot = new THREE.Matrix4();

function getMapEditorData() {
  /**
   * Always return a valid, normalized map document. When storage is empty (fresh install or a
   * wiped save), we fall back to the default doc — which contains a single `home` world with
   * sane defaults. That way consumers like the dev-warp dropdown, universe editor, and in-game
   * sphere-physics init never end up with an empty world list.
   */
  try {
    const raw = localStorage.getItem(MAP_EDITOR_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    return normalizeMapDocument(parsed && typeof parsed === 'object' ? parsed : null);
  } catch (_) {
    return normalizeMapDocument(null);
  }
}

/** Active planet for map reads: registry id if registered and present in doc, else `defaultPlanetId`. */
function resolveEditorMapPlanetId(doc) {
  if (!doc) return 'home';
  const a = worldRegistry?.getActivePlanetId?.();
  if (typeof a === 'string' && a.length && doc.worlds?.some((w) => w && w.planetId === a)) return a;
  return doc.defaultPlanetId || 'home';
}

/** Map slice (trees/props/plotSites/sellSpot/terrain) for the resolved active planet. */
function getActiveMapWorldSlice() {
  const doc = getMapEditorData();
  if (!doc) return null;
  return getWorldSliceFromDocument(doc, resolveEditorMapPlanetId(doc));
}

/**
 * One-shot dev warp target from sessionStorage (cleared after read).
 * @param {{ worlds?: unknown[] } | null} doc
 * @returns {string | null}
 */
function consumeDevWarpPlanetTarget(doc) {
  try {
    const raw = sessionStorage.getItem(DEV_WARP_PLANET_SESSION_KEY);
    if (!raw) return null;
    sessionStorage.removeItem(DEV_WARP_PLANET_SESSION_KEY);
    const pid = String(raw).trim();
    if (!pid || !doc?.worlds?.some((w) => w && w.planetId === pid)) return null;
    return pid;
  } catch (_) {
    return null;
  }
}

/** P2.1: apply active planet `worldDef` into {@link STORE} / {@link SHOP_CLERK} and reset default sell center before map sell overrides. */
function syncActiveWorldDefFromMap() {
  mergeWorldDefIntoRuntime(STORE, SHOP_CLERK, getActiveMapWorldSlice());
  sellCenter.set(STORE.x, 0, STORE.z + STORE.D / 2 + SELL_DOCK_DEPTH / 2);
  refreshSellCenterWorld();
}

function planetMeshParent() {
  return planetRuntimeContentRoot ?? scene;
}

/** Registers {@link planetRuntimeContentRoot} for the active map planet (P0.4). Call after `scene` + `worldRegistry` exist, before map terrain/props. */
function initRuntimePlanetContentRootFromMap() {
  runtimePlanetId = 'home';
  planetRuntimeContentRoot = null;
  if (!worldRegistry || !scene) return;
  const doc = getMapEditorData();
  const warpPid = consumeDevWarpPlanetTarget(doc);
  if (warpPid) {
    runtimePlanetId = warpPid;
  } else if (doc?.defaultPlanetId && typeof doc.defaultPlanetId === 'string') {
    runtimePlanetId = doc.defaultPlanetId;
  } else if (doc?.worlds?.[0]?.planetId) {
    runtimePlanetId = String(doc.worlds[0].planetId);
  }
  const grp = new THREE.Group();
  grp.name = `Planet_${runtimePlanetId}`;
  grp.userData.planetId = runtimePlanetId;
  planetRuntimeContentRoot = grp;
  worldRegistry.registerPlanet({
    id: runtimePlanetId,
    threeRoot: grp,
    unload() {},
  });
  worldRegistry.setActivePlanetId(runtimePlanetId);
  scene.add(grp);
  const poseSlice = doc ? getWorldSliceFromDocument(doc, runtimePlanetId) : null;
  if (poseSlice?.universePose) {
    const up = normalizeUniversePose(poseSlice.universePose);
    /** Only position carries over to the in-game root. Rotation and scale are editor-proxy-only (they shrink the little planet icons in the universe-editor view) and must never touch the actual play world — physics uses the raw `sphereRadius` and has no knowledge of these transforms. */
    grp.position.set(up.x, up.y, up.z);
    grp.rotation.set(0, 0, 0);
    grp.scale.set(1, 1, 1);
  }
}

/**
 * Sync the cached "active planet" center/radius to the entry that matches {@link runtimePlanetId}.
 * No longer moves colliders — every planet's body is already at its own universePose, created
 * during {@link initSpherePhysicsFromMapIfEnabled}. This just updates the single-planet code
 * paths that still read {@link sphereCenter} and {@link spherePlayRadius}.
 */
function applyUniversePoseToSpherePhysicsIfActive() {
  if (!spherePhysicsActive || !planetPhysicsEntries.length) return;
  const entry = planetPhysicsById.get(runtimePlanetId) || planetPhysicsEntries[0];
  if (!entry) return;
  sphereCenter.set(entry.center.x, entry.center.y, entry.center.z);
  spherePlayRadius = entry.radius;
}

function buildTerrainMeshFromMapData(t) {
  if (!t || typeof t !== 'object') return null;
  const size = Number(t.size) || 200;
  const seg = Math.max(4, Math.min(256, Math.floor(Number(t.segments) || 64)));
  const heights = t.heights;
  if (!Array.isArray(heights) || heights.length !== (seg + 1) * (seg + 1)) return null;
  const geo = new THREE.PlaneGeometry(size, size, seg, seg);
  const pos = geo.attributes.position;
  for (let i = 0; i < pos.count; i++) {
    pos.setZ(i, Number(heights[i]) || 0);
  }
  pos.needsUpdate = true;
  geo.computeVertexNormals();
  const mat = new THREE.MeshStandardMaterial({
    color: typeof t.color === 'number' ? t.color : 0x3d6b3d,
    roughness: 0.95,
    metalness: 0.02,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.name = 'MapTerrain';
  mesh.rotation.x = -Math.PI / 2;
  mesh.receiveShadow = true;
  return mesh;
}

function applyMapTerrainMeshFromStorage() {
  if (!scene) return;
  if (mapTerrainMesh) {
    mapTerrainMesh.removeFromParent();
    mapTerrainMesh.geometry.dispose();
    mapTerrainMesh.material.dispose();
    mapTerrainMesh = null;
  }
  const slice = getActiveMapWorldSlice();
  const t = slice?.terrain;
  const mesh = buildTerrainMeshFromMapData(t);
  if (!mesh) {
    if (gameGroundMesh) gameGroundMesh.visible = true;
    return;
  }
  mapTerrainMesh = mesh;
  mesh.userData.planetId = runtimePlanetId;
  planetMeshParent().add(mesh);
  if (gameGroundMesh) gameGroundMesh.visible = false;
}

const raycaster = new THREE.Raycaster();
/** Last cursor position for UI rays when pointer lock is off. */
let lastPointerClient = { x: 400, y: 300 };
/** Pause → Settings: show shop product name when aiming / pointing. */
let shopItemLabelsEnabled = true;
const tmpV = new THREE.Vector3();
const tmpV2 = new THREE.Vector3();
const tmpV3 = new THREE.Vector3();
const tmpV4 = new THREE.Vector3();
const _branchSplitCenter = new THREE.Vector3();
const _branchSplitQuat = new THREE.Quaternion();
const _splitTrunkKeepQuat = new THREE.Quaternion();
const _procHolderSavedMw = new THREE.Matrix4();
const _procHolderRelToTop = new THREE.Matrix4();
const _branchSplitOff = new THREE.Vector3();
const _compoundInvWorld = new THREE.Matrix4();
const _compoundRel = new THREE.Matrix4();
const _compoundPos = new THREE.Vector3();
const _compoundQuat = new THREE.Quaternion();
const _compoundScl = new THREE.Vector3();
const tmpC = new CANNON.Vec3();
const _beltFwd = new CANNON.Vec3();
const _beltUp = new CANNON.Vec3();
const _beltTan = new CANNON.Vec3();
const BOB_INTERACT_R = 3.5;
let storeGroup = null;
/** Rotated counter group — used for checkout overlap in local space. */
let shopCounterRig = null;
let bobHeadWorld = new THREE.Vector3(STORE.bobX, STORE.bobY + 0.85, STORE.bobZ);
let clerkHeadWorld = new THREE.Vector3(SHOP_CLERK.x, SHOP_CLERK.y + 0.95, SHOP_CLERK.z);
/** performance.now() until which clerk speech bubble stays visible */
let clerkBubbleUntil = 0;
let bobDialogOpen = false;
let bobDialogStep = 0;

const CHOP_SWINGS = 5;
/** ~50% taller than original capsule (was 0.52 / 0.34 / cam +0.65 / spawn 1.2). */
const PLAYER_BODY_HALF_H = 0.78;
const PLAYER_BODY_HALF_W = 0.51;
const PLAYER_CAM_Y_OFFSET = 0.975;
const PLAYER_SPAWN_Y = 1.46;
/** Minimum height (m) for each piece after a cut — keep low so you can keep chopping shorter logs. */
const MIN_TRUNK_PIECE = 0.18;
const CHOP_EDGE_INSET = 0.06;

/** @param {unknown} v @returns {'wood'|'dark'|'void'} */
function normalizeLogSkin(v) {
  if (v === 'dark') return 'dark';
  if (v === 'void') return 'void';
  return 'wood';
}

let chopState = null;
let chopBandMesh = null;
let chopBandGeo = null;
/** Scratch verts for camera-facing chop progress quad (no per-frame alloc). */
const chopBandQuad = {
  a: new THREE.Vector3(),
  b: new THREE.Vector3(),
  c: new THREE.Vector3(),
  d: new THREE.Vector3(),
};
const _chopSurfCenter = new THREE.Vector3();
const _chopAxisY = new THREE.Vector3();
const _chopToCam = new THREE.Vector3();
const _chopNFlat = new THREE.Vector3();
const _chopSurf = new THREE.Vector3();

function allocNetId() {
  return nextNetId++;
}

/** Cannon has no scene graph — match world pose to Three mesh after parenting under the planet group. */
function syncDynamicBodyWorldPoseFromMesh(mesh, body) {
  mesh.updateMatrixWorld(true);
  mesh.getWorldPosition(_compoundPos);
  body.position.set(_compoundPos.x, _compoundPos.y, _compoundPos.z);
  mesh.getWorldQuaternion(_compoundQuat);
  body.quaternion.set(_compoundQuat.x, _compoundQuat.y, _compoundQuat.z, _compoundQuat.w);
}

function syncMeshFromBody(mesh, body) {
  if (!mesh.parent) {
    mesh.position.set(body.position.x, body.position.y, body.position.z);
    mesh.quaternion.set(body.quaternion.x, body.quaternion.y, body.quaternion.z, body.quaternion.w);
    return;
  }
  tmpV.set(body.position.x, body.position.y, body.position.z);
  mesh.parent.worldToLocal(tmpV);
  mesh.position.copy(tmpV);
  mesh.parent.getWorldQuaternion(_branchSplitQuat);
  _splitTrunkKeepQuat.set(body.quaternion.x, body.quaternion.y, body.quaternion.z, body.quaternion.w);
  mesh.quaternion.copy(_branchSplitQuat).invert().multiply(_splitTrunkKeepQuat);
}

/** Materials reused across many meshes — never dispose per-mesh. */
const SHARED_ENGINE_MATERIALS = new Set();

/** Shared canvas “plank” look: brown border + darker inset — used on cut ends (+Y/−Y) only. */
let woodBoardTextureCache = null;
let woodMaterialShared = null;
/** Procedural board texture for default sawmilled planks only (separate from log cut-face material). */
const SAWMILL_PLANK_TEX_REV = 3;
let sawmillPlankBoardTexRev = 0;
let sawmillPlankBoardTextureCache = null;
let sawmillPlankWoodMaterialShared = null;
/** Uniform bark (no inset) for vertical sides — so the “panel” doesn’t read as a hole from outside. */
let woodBarkTextureCache = null;
let woodBarkSideMaterialShared = null;
/** Alternate tree: matte black bark + neon orange cut faces. */
let darkBarkMaterialShared = null;
let neonOrangeCapMaterialShared = null;
let neonOrangeCapTextureCache = null;
/** Void tree: classic bark + fixed purple emissive cut faces (same bloom layer as spook). */
let neonPurpleCapMaterialShared = null;
let neonPurpleCapTextureCache = null;
const VOID_NEON_PURPLE_HEX = '#c084fc';
/** Invalidates neon texture when {@link loadNeonSettings} output changes. */
let neonCapTexSettingsSig = '';

function getWoodBoardTexture() {
  if (woodBoardTextureCache) return woodBoardTextureCache;
  const size = 256;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  const outer = '#8f6f4e';
  const inner = '#3a2615';
  ctx.fillStyle = outer;
  ctx.fillRect(0, 0, size, size);
  const inset = Math.round(size * 0.17);
  ctx.fillStyle = inner;
  ctx.fillRect(inset, inset, size - 2 * inset, size - 2 * inset);
  /** Very light grain so it isn’t perfectly flat plastic. */
  for (let i = 0; i < 900; i++) {
    const px = Math.floor(Math.random() * size);
    const py = Math.floor(Math.random() * size);
    const a = 0.04 + Math.random() * 0.06;
    ctx.fillStyle = `rgba(0,0,0,${a})`;
    ctx.fillRect(px, py, 1, 1);
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  tex.wrapT = THREE.RepeatWrapping;
  tex.needsUpdate = true;
  woodBoardTextureCache = tex;
  return tex;
}

function woodMaterial() {
  if (!woodMaterialShared) {
    woodMaterialShared = new THREE.MeshStandardMaterial({
      color: 0xffffff,
      map: getWoodBoardTexture(),
      roughness: 0.86,
      metalness: 0.05,
    });
    SHARED_ENGINE_MATERIALS.add(woodMaterialShared);
  }
  return woodMaterialShared;
}

/** Full-bleed wood grain for default sawmilled planks — no border / frame. */
function getSawmillPlankBoardTexture() {
  if (sawmillPlankBoardTextureCache && sawmillPlankBoardTexRev === SAWMILL_PLANK_TEX_REV) {
    return sawmillPlankBoardTextureCache;
  }
  if (sawmillPlankBoardTextureCache) {
    sawmillPlankBoardTextureCache.dispose();
    sawmillPlankBoardTextureCache = null;
  }
  const W = 256;
  const H = 256;
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#8f6a4e';
  ctx.fillRect(0, 0, W, H);
  for (let x = 0; x < W; x += 2) {
    const t = 0.72 + Math.random() * 0.34;
    ctx.fillStyle = `rgba(${Math.floor(118 * t)}, ${Math.floor(86 * t)}, ${Math.floor(58 * t)}, 0.28)`;
    ctx.fillRect(x, 0, 1 + (Math.random() * 2.5) | 0, H);
  }
  for (let i = 0; i < 1800; i++) {
    ctx.fillStyle = `rgba(32,20,10,${0.025 + Math.random() * 0.07})`;
    ctx.fillRect((Math.random() * W) | 0, (Math.random() * H) | 0, 1, 2 + ((Math.random() * 5) | 0));
  }
  for (let i = 0; i < 900; i++) {
    ctx.fillStyle = `rgba(255,230,200,${0.02 + Math.random() * 0.06})`;
    ctx.fillRect((Math.random() * W) | 0, (Math.random() * H) | 0, 1, 1);
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  tex.wrapT = THREE.RepeatWrapping;
  tex.needsUpdate = true;
  sawmillPlankBoardTextureCache = tex;
  sawmillPlankBoardTexRev = SAWMILL_PLANK_TEX_REV;
  return sawmillPlankBoardTextureCache;
}

function getSawmillPlankWoodMaterial() {
  const tex = getSawmillPlankBoardTexture();
  if (!sawmillPlankWoodMaterialShared) {
    sawmillPlankWoodMaterialShared = new THREE.MeshStandardMaterial({
      color: 0xffffff,
      map: tex,
      roughness: 0.86,
      metalness: 0.05,
    });
    SHARED_ENGINE_MATERIALS.add(sawmillPlankWoodMaterialShared);
  } else if (sawmillPlankWoodMaterialShared.map !== tex) {
    sawmillPlankWoodMaterialShared.map = tex;
    sawmillPlankWoodMaterialShared.needsUpdate = true;
  }
  return sawmillPlankWoodMaterialShared;
}

function getWoodBarkTexture() {
  if (woodBarkTextureCache) return woodBarkTextureCache;
  const W = 256;
  const H = 256;
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#7a5840';
  ctx.fillRect(0, 0, W, H);
  for (let i = 0; i < 160; i++) {
    const x = Math.random() * W;
    const bw = 0.4 + Math.random() * 1.8;
    ctx.fillStyle = `rgba(32,20,10,${0.06 + Math.random() * 0.12})`;
    ctx.fillRect(x, 0, bw, H);
  }
  for (let i = 0; i < 1400; i++) {
    ctx.fillStyle = `rgba(0,0,0,${0.02 + Math.random() * 0.05})`;
    ctx.fillRect((Math.random() * W) | 0, (Math.random() * H) | 0, 1, 1 + ((Math.random() * 4) | 0));
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  tex.wrapT = THREE.RepeatWrapping;
  tex.needsUpdate = true;
  woodBarkTextureCache = tex;
  return tex;
}

function getWoodBarkSideMaterial() {
  if (!woodBarkSideMaterialShared) {
    woodBarkSideMaterialShared = new THREE.MeshStandardMaterial({
      color: 0xffffff,
      map: getWoodBarkTexture(),
      roughness: 0.88,
      metalness: 0.04,
    });
    SHARED_ENGINE_MATERIALS.add(woodBarkSideMaterialShared);
  }
  return woodBarkSideMaterialShared;
}

function getDarkBarkMaterial() {
  if (!darkBarkMaterialShared) {
    darkBarkMaterialShared = new THREE.MeshStandardMaterial({
      color: 0x0c0c0c,
      roughness: 1,
      metalness: 0,
    });
    SHARED_ENGINE_MATERIALS.add(darkBarkMaterialShared);
  }
  return darkBarkMaterialShared;
}

function getNeonOrangeCapTexture() {
  const s = loadNeonSettings();
  const sig = s.color;
  if (neonOrangeCapTextureCache && neonCapTexSettingsSig === sig) return neonOrangeCapTextureCache;
  if (neonOrangeCapTextureCache) {
    neonOrangeCapTextureCache.dispose();
    neonOrangeCapTextureCache = null;
  }
  neonOrangeCapTextureCache = createNeonCapCanvasTexture(THREE, s.color);
  neonCapTexSettingsSig = sig;
  return neonOrangeCapTextureCache;
}

function getNeonOrangeCapMaterial() {
  const s = loadNeonSettings();
  if (!neonOrangeCapMaterialShared) {
    const capMap = getNeonOrangeCapTexture();
    neonOrangeCapMaterialShared = new THREE.MeshStandardMaterial({
      color: neonAlbedoColorFromPicker(s.color),
      map: capMap,
      emissive: new THREE.Color(s.color),
      emissiveMap: capMap,
      emissiveIntensity: neonEmissiveIntensityFromSlider(s.intensity),
      roughness: 0.28,
      metalness: 0,
      toneMapped: true,
    });
    SHARED_ENGINE_MATERIALS.add(neonOrangeCapMaterialShared);
  } else {
    const capMap = getNeonOrangeCapTexture();
    neonOrangeCapMaterialShared.map = capMap;
    neonOrangeCapMaterialShared.emissiveMap = capMap;
    neonOrangeCapMaterialShared.color.copy(neonAlbedoColorFromPicker(s.color));
    neonOrangeCapMaterialShared.emissive.set(s.color);
    neonOrangeCapMaterialShared.emissiveIntensity = neonEmissiveIntensityFromSlider(s.intensity);
  }
  return neonOrangeCapMaterialShared;
}

function getNeonPurpleCapMaterial() {
  const s = loadNeonSettings();
  if (!neonPurpleCapTextureCache) {
    neonPurpleCapTextureCache = createNeonCapCanvasTexture(THREE, VOID_NEON_PURPLE_HEX);
  }
  if (!neonPurpleCapMaterialShared) {
    neonPurpleCapMaterialShared = new THREE.MeshStandardMaterial({
      color: neonAlbedoColorFromPicker(VOID_NEON_PURPLE_HEX),
      map: neonPurpleCapTextureCache,
      emissive: new THREE.Color(VOID_NEON_PURPLE_HEX),
      emissiveMap: neonPurpleCapTextureCache,
      emissiveIntensity: neonEmissiveIntensityFromSlider(s.intensity),
      roughness: 0.28,
      metalness: 0,
      toneMapped: true,
    });
    SHARED_ENGINE_MATERIALS.add(neonPurpleCapMaterialShared);
  } else {
    neonPurpleCapMaterialShared.emissiveIntensity = neonEmissiveIntensityFromSlider(s.intensity);
  }
  return neonPurpleCapMaterialShared;
}

/** BoxGeometry faces: 0 +X, 1 -X, 2 +Y (end), 3 -Y (end), 4 +Z, 5 -Z — length along local Y. */
function makeBoxLogMaterialArray(logSkin) {
  const skin = normalizeLogSkin(logSkin);
  if (skin === 'dark') {
    const side = getDarkBarkMaterial();
    const cap = getNeonOrangeCapMaterial();
    return [side, side, cap, cap, side, side];
  }
  if (skin === 'void') {
    const side = getWoodBarkSideMaterial();
    const cap = getNeonPurpleCapMaterial();
    return [side, side, cap, cap, side, side];
  }
  const side = getWoodBarkSideMaterial();
  const cap = woodMaterial();
  return [side, side, cap, cap, side, side];
}

/**
 * Cache of trunk side materials keyed by `${texture}::${hexLower}`.
 *
 * `stock::<hex>` entries are clones of the shared bark PNG material with a
 * `.color` tint multiplied over the map. Non-stock entries are fully canvas
 * generated with the tint baked in (see `lt2-tree-textures.js`).
 */
const _tintedBarkMats = new Map();

function normalizedTrunkTexture(tex) {
  return LT2_TRUNK_SIDE_TEXTURES.includes(tex) ? tex : 'stock';
}

/**
 * Returns a side-face bark material for the given (texture, hex). The shared
 * untinted PNG is returned only for the `stock::#ffffff` combination; every
 * other combination returns a cached clone or a generated canvas material.
 */
function getTrunkSideMaterial(texture, hex) {
  const tex = normalizedTrunkTexture(texture);
  const color = (typeof hex === 'string' && /^#[0-9a-fA-F]{6}$/.test(hex)) ? hex.toLowerCase() : '#ffffff';

  if (tex === 'stock' && color === '#ffffff') return getWoodBarkSideMaterial();

  const key = `${tex}::${color}`;
  const existing = _tintedBarkMats.get(key);
  if (existing) return existing;

  let mat;
  if (tex === 'stock') {
    mat = getWoodBarkSideMaterial().clone();
    mat.color = new THREE.Color(color);
  } else {
    /** Canvas textures bake the tint directly so material.color stays white. */
    const tintForCanvas = color === '#ffffff' ? '#77513a' : color;
    mat = buildTrunkSideMaterial({ texture: tex, tintHex: tintForCanvas });
  }
  SHARED_ENGINE_MATERIALS.add(mat);
  _tintedBarkMats.set(key, mat);
  return mat;
}

/**
 * Replace indices 0/1/4/5 (side faces) in `mats` with a trunk side material
 * for (texture, color). Returns null only when the combination is equivalent
 * to the default shared bark (`stock` + `#ffffff`), so callers can short-circuit.
 */
function applyBarkTintToSides(mats, trunkColor, trunkTexture) {
  const tex = normalizedTrunkTexture(trunkTexture);
  const color = (typeof trunkColor === 'string' && /^#[0-9a-fA-F]{6}$/.test(trunkColor)) ? trunkColor.toLowerCase() : '#ffffff';
  if (tex === 'stock' && color === '#ffffff') return null;
  const sideMat = getTrunkSideMaterial(tex, color);
  mats[0] = sideMat;
  mats[1] = sideMat;
  mats[4] = sideMat;
  mats[5] = sideMat;
  return { color, texture: tex, material: sideMat };
}

/**
 * Normalizes any input (flat color/neon args, `interior` object, save record,
 * or mesh userData) into a full `{ color, neon, border, borderThickness, borderColor, texture }`
 * or returns null when no valid interior color is present.
 */
function normalizeInteriorCfg(src) {
  if (!src || typeof src !== 'object') return null;
  const raw = src.color ?? src.interiorColor;
  if (typeof raw !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(raw)) return null;
  const color = raw.toLowerCase();
  const neon = !!(src.neon ?? src.interiorNeon);
  const border = !!(src.border ?? src.interiorBorder);
  const borderThicknessRaw = src.borderThickness ?? src.interiorBorderThickness;
  const borderThicknessN = Number(borderThicknessRaw);
  const borderThickness = Number.isFinite(borderThicknessN)
    ? Math.max(0, Math.min(0.45, borderThicknessN))
    : 0.06;
  const borderColorRaw = src.borderColor ?? src.interiorBorderColor;
  const borderColor = typeof borderColorRaw === 'string' && /^#[0-9a-fA-F]{6}$/.test(borderColorRaw)
    ? borderColorRaw.toLowerCase()
    : '#3a2516';
  const textureRaw = src.texture ?? src.interiorTexture;
  const texture = INTERIOR_TEXTURES.includes(textureRaw) ? textureRaw : 'solid';
  return { color, neon, border, borderThickness, borderColor, texture };
}

/** Read the interior config previously stamped on a mesh (from creation or split inheritance). */
function extractInteriorFromMesh(mesh) {
  if (!mesh?.userData) return null;
  if (mesh.userData.interior) return normalizeInteriorCfg(mesh.userData.interior);
  return normalizeInteriorCfg({
    color: mesh.userData.interiorColor,
    neon: mesh.userData.interiorNeon,
  });
}

/** Build the shared-builder params object that `buildInteriorCapMaterial` expects. */
function interiorToBuilderCfg(interior) {
  return {
    interiorColor: interior.color,
    interiorNeon: interior.neon,
    interiorBorder: interior.border,
    interiorBorderThickness: interior.borderThickness,
    interiorBorderColor: interior.borderColor,
    interiorTexture: interior.texture,
  };
}

/**
 * Swap indices 2/3 of `mats` (end-caps) for LT2 interior cfg.
 * Returns `null` when no valid interior so non-LT2 logs keep the shared cap.
 */
function applyInteriorCapOverride(mats, interior) {
  if (!interior) return null;
  const cap = buildInteriorCapMaterial(interiorToBuilderCfg(interior));
  mats[2] = cap;
  mats[3] = cap;
  return { interior, material: cap };
}

/**
 * Replace end-cap materials in place on an already-created trunk mesh.
 * Disposes a prior override material so repeated LT2 config edits don't leak.
 */
function applyInteriorToExistingTrunk(trunkMesh, interiorInput) {
  if (!trunkMesh || !Array.isArray(trunkMesh.material)) return;
  const interior = normalizeInteriorCfg(interiorInput);
  if (!interior) return;
  const prev = trunkMesh.userData.interiorCapMaterial;
  if (prev) disposeInteriorCapMaterial(prev);
  const override = applyInteriorCapOverride(trunkMesh.material, interior);
  if (!override) return;
  trunkMesh.userData.interior = interior;
  trunkMesh.userData.interiorColor = interior.color;
  trunkMesh.userData.interiorNeon = interior.neon;
  trunkMesh.userData.interiorCapMaterial = override.material;
  if (interior.neon) enableNeonBloomOnMesh(trunkMesh);
  trunkMesh.material.needsUpdate = true;
}

/** Sawmill plank: classic = grain texture ({@link getSawmillPlankBoardTexture}); dark = neon. Never log bark. */
function makePlankSurfaceMaterial(logSkin) {
  const skin = normalizeLogSkin(logSkin);
  if (skin === 'dark') return getNeonOrangeCapMaterial();
  if (skin === 'void') return getNeonPurpleCapMaterial();
  return getSawmillPlankWoodMaterial();
}

function leavesMaterial() {
  return new THREE.MeshStandardMaterial({ color: 0x2d6b2d, roughness: 0.9 });
}

/** Standing trunk mesh that owns `procedural_branches_holder` for a crown/twig mesh. */
function getTrunkHostMeshForProceduralPart(mesh) {
  const p = mesh.parent;
  if (!p) return null;
  if (p.name === 'procedural_branches_holder') return p.parent ?? null;
  if (p.name?.startsWith('crown_')) return p.parent?.parent ?? null;
  return null;
}

function stripBranchCompoundExtraShapes(mesh) {
  if (!mesh?.userData?.hasBranchCompound) return;
  const body = mesh.userData.body;
  if (!body) {
    mesh.userData.hasBranchCompound = false;
    return;
  }
  while (body.shapes.length > 1) {
    body.removeShape(body.shapes[body.shapes.length - 1]);
  }
  mesh.userData.hasBranchCompound = false;
  if (typeof body.updateMassProperties === 'function') body.updateMassProperties();
}

/** Merge procedural bark into the host’s single dynamic Cannon body (avoids static-vs-dynamic spin). */
function rebuildTreeWoodCompound(hostMesh) {
  const body = hostMesh.userData.body;
  const holder = hostMesh.getObjectByName('procedural_branches_holder');
  if (!body || !holder || body.mass <= 1e-6) {
    stripBranchCompoundExtraShapes(hostMesh);
    return;
  }
  /** Standing trees use per-branch static bodies; compound rebuild must drop them or ghosts sync in `treeBranchStaticColliderList` and fight the compound. */
  clearTreeBranchCollidersAttachedTo(hostMesh);
  holder.traverse((o) => {
    if (!o.isMesh || o.geometry?.type !== 'BoxGeometry') return;
    if (o.name.startsWith('branch_tip_leaf')) return;
    const b = o.userData.body;
    if (b && b !== body) {
      removeTreeBranchColliderEntry(o, b);
      world.removeBody(b);
    }
  });
  while (body.shapes.length > 1) {
    body.removeShape(body.shapes[body.shapes.length - 1]);
  }
  hostMesh.userData.hasBranchCompound = false;
  hostMesh.updateMatrixWorld(true);
  _compoundInvWorld.copy(hostMesh.matrixWorld).invert();
  holder.updateMatrixWorld(true);
  const logSkin = hostMesh.userData.logSkin || 'wood';
  const treeId = hostMesh.userData.treeId | 0;
  holder.traverse((o) => {
    if (!o.isMesh || o.geometry?.type !== 'BoxGeometry') return;
    if (o.name.startsWith('branch_tip_leaf')) return;
    o.userData.kind = undefined;
    o.userData.body = undefined;
    o.userData.branchCompoundHost = undefined;
    o.userData.branchHostTrunk = undefined;
  });
  let barkCount = 0;
  holder.traverse((o) => {
    if (!o.isMesh || o.geometry?.type !== 'BoxGeometry') return;
    if (o.name.startsWith('branch_tip_leaf')) return;
    const p = o.geometry.parameters;
    const sx = o.scale.x;
    const sy = o.scale.y;
    const sz = o.scale.z;
    const hx = (sx * p.width) / 2;
    const hy = (sy * p.height) / 2;
    const hz = (sz * p.depth) / 2;
    o.updateMatrixWorld(true);
    _compoundRel.copy(o.matrixWorld).premultiply(_compoundInvWorld);
    _compoundRel.decompose(_compoundPos, _compoundQuat, _compoundScl);
    const offset = new CANNON.Vec3(_compoundPos.x, _compoundPos.y, _compoundPos.z);
    const cq = new CANNON.Quaternion(_compoundQuat.x, _compoundQuat.y, _compoundQuat.z, _compoundQuat.w);
    body.addShape(new CANNON.Box(new CANNON.Vec3(hx, hy, hz)), offset, cq);
    o.userData.kind = 'treeBranch';
    o.userData.body = body;
    o.userData.branchCompoundHost = hostMesh;
    o.userData.branchHostTrunk = hostMesh;
    o.userData.logSkin = logSkin;
    o.userData.treeId = treeId;
    barkCount += 1;
  });
  hostMesh.userData.hasBranchCompound = barkCount > 0;
  if (typeof body.updateMassProperties === 'function') body.updateMassProperties();
}

function registerDynamic(mesh, body, opts = {}) {
  const netId = opts.netId ?? allocNetId();
  mesh.userData.body = body;
  mesh.userData.kind = opts.kind ?? 'wood';
  mesh.userData.netId = netId;
  if (opts.kitId != null) mesh.userData.kitId = opts.kitId;
  body.userData = body.userData || {};
  body.userData.netId = netId;
  if (runtimePlanetId) {
    mesh.userData.planetId = runtimePlanetId;
    body.userData.planetId = runtimePlanetId;
  }
  dynamicPairs.push({ mesh, body });
  return netId;
}

/**
 * @param {object} [opts]
 * @param {boolean} [opts.skipMaterialDispose] Procedural branch meshes may still use this mesh’s materials after detach.
 */
function unregisterDynamic(mesh, body, opts = {}) {
  const skipMat = opts.skipMaterialDispose === true;
  stripBranchCompoundExtraShapes(mesh);
  if (mesh.userData?.branchColliderPairs?.length) clearTreeBranchCollidersAttachedTo(mesh);
  else if (mesh.userData?.branchColliderPairs) mesh.userData.branchColliderPairs = null;
  const i = dynamicPairs.findIndex((p) => p.mesh === mesh);
  if (i !== -1) dynamicPairs.splice(i, 1);
  mesh.removeFromParent();
  world.removeBody(body);
  mesh.geometry?.dispose?.();
  if (!skipMat) {
    const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
    const seen = new Set();
    for (const m of mats) {
      if (!m || SHARED_ENGINE_MATERIALS.has(m) || seen.has(m)) continue;
      seen.add(m);
      const capTex = m.userData?.__lt2InteriorTexture;
      if (capTex && typeof capTex.dispose === 'function') capTex.dispose();
      m.dispose?.();
    }
  }
}

function createCylinderLog({ centerY, height, radius, x, z, quat, regKind = 'log' }) {
  const geo = new THREE.CylinderGeometry(radius, radius, height, 12);
  const mat = getWoodBarkSideMaterial();
  const mesh = new THREE.Mesh(geo, mat);
  mesh.castShadow = true;
  mesh.position.set(x, centerY, z);
  if (quat) {
    mesh.quaternion.copy(quat);
  }
  planetMeshParent().add(mesh);

  const shape = new CANNON.Cylinder(radius, radius, height, 12);
  const body = new CANNON.Body({
    mass: Math.max(0.15, height * radius * radius * 8),
    material: woodPhyMat ?? undefined,
  });
  body.addShape(shape);
  body.position.set(x, centerY, z);
  if (quat) {
    body.quaternion.set(quat.x, quat.y, quat.z, quat.w);
  }
  body.linearDamping = 0.42;
  body.angularDamping = 0.55;
  world.addBody(body);
  syncDynamicBodyWorldPoseFromMesh(mesh, body);
  registerDynamic(mesh, body, { kind: regKind });
  return { mesh, body };
}

function createBoxLog({
  centerY,
  height,
  halfW,
  x,
  z,
  quat,
  regKind = 'log',
  mass,
  logSkin = 'wood',
  woodWeight,
  interior,
  interiorColor,
  interiorNeon,
  trunkColor,
  trunkTexture,
}) {
  logSkin = normalizeLogSkin(logSkin);
  const w = halfW * 2;
  const geo = new THREE.BoxGeometry(w, height, w);
  const mats = makeBoxLogMaterialArray(logSkin);
  /** Bark texture + tint only applies on the wood skin — dark/void skins keep their distinctive bases. */
  const barkTint = logSkin === 'wood' ? applyBarkTintToSides(mats, trunkColor, trunkTexture) : null;
  const resolvedInterior = normalizeInteriorCfg(interior)
    ?? normalizeInteriorCfg({ color: interiorColor, neon: interiorNeon });
  const interiorOverride = applyInteriorCapOverride(mats, resolvedInterior);
  const mesh = new THREE.Mesh(geo, mats);
  mesh.userData.logSkin = logSkin;
  const wMul = Number.isFinite(woodWeight) && woodWeight > 0 ? woodWeight : 1;
  mesh.userData.woodWeight = wMul;
  if (barkTint) {
    mesh.userData.trunkColor = barkTint.color;
    mesh.userData.trunkTexture = barkTint.texture;
  }
  if (interiorOverride) {
    mesh.userData.interior = resolvedInterior;
    mesh.userData.interiorColor = resolvedInterior.color;
    mesh.userData.interiorNeon = resolvedInterior.neon;
    mesh.userData.interiorCapMaterial = interiorOverride.material;
  }
  mesh.castShadow = true;
  mesh.position.set(x, centerY, z);
  if (quat) mesh.quaternion.copy(quat);
  if (logSkin === 'dark' || logSkin === 'void' || resolvedInterior?.neon) enableNeonBloomOnMesh(mesh);
  planetMeshParent().add(mesh);

  const shape = new CANNON.Box(new CANNON.Vec3(halfW, height / 2, halfW));
  const baseMass = mass !== undefined ? mass : Math.max(0.2, height * halfW * halfW * 16);
  const bodyMass = baseMass > 0 ? baseMass * wMul : 0;
  const body = new CANNON.Body({ mass: bodyMass, material: woodPhyMat ?? undefined });
  body.addShape(shape);
  body.position.set(x, centerY, z);
  if (quat) body.quaternion.set(quat.x, quat.y, quat.z, quat.w);
  if (bodyMass > 0) {
    body.linearDamping = 0.42;
    body.angularDamping = 0.55;
  } else {
    body.linearDamping = 0;
    body.angularDamping = 1;
  }
  world.addBody(body);
  syncDynamicBodyWorldPoseFromMesh(mesh, body);
  registerDynamic(mesh, body, { kind: regKind });
  return { mesh, body };
}

/** Caps legacy / bad save data — must match the scale of {@link randomTreeDimensions} standing trunks. */
const MAX_STANDING_TRUNK_HEIGHT = 3.05;

function createTrunkSegment(params, opts = {}) {
  const anchored = opts.anchored ?? true;
  let { bottomY, topY, halfW, x, z, treeId, woodWeight, interior, interiorColor, interiorNeon, trunkColor, trunkTexture } = params;
  if (halfW == null && params.radius != null) {
    halfW = params.radius * 1.12;
  }
  if (halfW == null) halfW = 0.075;
  let height = topY - bottomY;
  if (height > MAX_STANDING_TRUNK_HEIGHT) {
    topY = bottomY + MAX_STANDING_TRUNK_HEIGHT;
    height = MAX_STANDING_TRUNK_HEIGHT;
  }
  if (height < 0.14) return null;
  const centerY = bottomY + height / 2;
  const quat = params.quat ?? null;
  const logSkin = normalizeLogSkin(params.logSkin);
  const { mesh, body } = createBoxLog({
    centerY,
    height,
    halfW,
    x,
    z,
    quat,
    regKind: 'trunk',
    mass: anchored ? 0 : undefined,
    logSkin,
    woodWeight,
    interior,
    interiorColor,
    interiorNeon,
    trunkColor,
    trunkTexture,
  });
  mesh.userData.trunk = { bottomY, topY, halfW, x, z, treeId };
  mesh.userData.treeId = treeId;
  mesh.userData.trunkAnchored = anchored;
  return { mesh, body };
}

/**
 * Random standing-trunk thickness and height (independent draws).
 * Sell payout uses both: {@link computeWoodSellValue} ~ height × halfW², so wider logs are worth more.
 */
function randomTreeDimensions() {
  const u = Math.random();
  const v = Math.random();
  const halfW = 0.045 * 2.5 + u * 0.07;
  /** Ground→crown box height (m) — main trunk only; crown pieces are procedural on top. */
  const trunkH = 1.28 + v * v * 1.48;
  return { halfW, trunkH };
}

/** Sell payout for a box-ish log from height (m) and half-width or radius (m). */
function computeWoodSellValue(height, halfWOrRadius) {
  const w = halfWOrRadius;
  const volume = height * w * w;
  return Math.round(5 + volume * 420 + height * 3.5);
}

function removeTreeBranchColliderEntry(branchMesh, body) {
  const hi = treeBranchStaticColliderList.findIndex((e) => e.branchMesh === branchMesh);
  if (hi >= 0) treeBranchStaticColliderList.splice(hi, 1);
  const trunk = branchMesh.userData.branchHostTrunk;
  const arr = trunk?.userData?.branchColliderPairs;
  if (arr) {
    const pj = arr.findIndex((e) => e.branchMesh === branchMesh);
    if (pj >= 0) arr.splice(pj, 1);
  }
  branchMesh.userData.body = null;
}

function clearTreeBranchCollidersAttachedTo(trunkMesh) {
  const pairs = trunkMesh.userData?.branchColliderPairs;
  if (!pairs || pairs.length === 0) {
    trunkMesh.userData.branchColliderPairs = null;
    return;
  }
  for (const { branchMesh, body } of [...pairs]) {
    removeTreeBranchColliderEntry(branchMesh, body);
    world.removeBody(body);
  }
  trunkMesh.userData.branchColliderPairs = null;
}

/**
 * Extra static Cannon boxes parented in Three to a **dynamic** trunk/log overlap the wood body and
 * the solver applies huge correction impulses → runaway spin. Only use them for grounded static trunks.
 */
function shouldAttachStaticTreeBranchColliders(hostMesh) {
  const body = hostMesh.userData.body;
  if (!body || body.mass > 1e-6) return false;
  if (hostMesh.userData.kind !== 'trunk') return false;
  if (hostMesh.userData.trunkAnchored === false) return false;
  /**
   * Procedural biome-forest trees opt out of per-branch static colliders for perf: every branch
   * would otherwise be its own CANNON.Box, and a full planet forest (120–500 trees) × 10–30
   * branches each = several thousand static bodies, which destroys broad-phase every step.
   * Trunks still collide, chopping still works — players just can't stand on procedural limbs.
   */
  if (hostMesh.userData.skipBranchColliders) return false;
  return true;
}

function attachTreeBranchColliders(trunkMesh) {
  clearTreeBranchCollidersAttachedTo(trunkMesh);
  trunkMesh.userData.hasBranchCompound = false;
  const holder = trunkMesh.getObjectByName('procedural_branches_holder');
  if (!holder) return;
  if (!shouldAttachStaticTreeBranchColliders(trunkMesh)) return;
  const logSkin = trunkMesh.userData.logSkin || 'wood';
  const treeId = trunkMesh.userData.treeId | 0;
  const pairs = [];
  holder.updateMatrixWorld(true);
  holder.traverse((o) => {
    if (!o.isMesh || o.geometry?.type !== 'BoxGeometry') return;
    if (o.name.startsWith('branch_tip_leaf')) return;
    const p = o.geometry.parameters;
    const sx = o.scale.x;
    const sy = o.scale.y;
    const sz = o.scale.z;
    const hx = (sx * p.width) / 2;
    const hy = (sy * p.height) / 2;
    const hz = (sz * p.depth) / 2;
    const body = new CANNON.Body({ mass: 0, material: woodPhyMat ?? undefined });
    body.addShape(new CANNON.Box(new CANNON.Vec3(hx, hy, hz)));
    world.addBody(body);
    o.userData.body = body;
    o.userData.kind = 'treeBranch';
    o.userData.branchHostTrunk = trunkMesh;
    o.userData.logSkin = logSkin;
    o.userData.treeId = treeId;
    syncStaticBodyToMeshWorld(o, body);
    treeBranchStaticColliderList.push({ branchMesh: o, body });
    pairs.push({ branchMesh: o, body });
  });
  trunkMesh.userData.branchColliderPairs = pairs;
}

/** After `procedural_branches_holder` is present on `trunkMesh` (new or reparented), rebuild static boxes or compound bark. */
function syncBranchCollidersOrCompoundForTrunk(trunkMesh) {
  trunkMesh.userData.hasBranchCompound = false;
  if (shouldAttachStaticTreeBranchColliders(trunkMesh)) {
    attachTreeBranchColliders(trunkMesh);
  } else if (trunkMesh.getObjectByName('procedural_branches_holder') && trunkMesh.userData.body?.mass > 1e-6) {
    rebuildTreeWoodCompound(trunkMesh);
  }
}

/**
 * Map (x,z) as tangent offsets on the inner “north cap” (player spawn uses +Y from planet center), optional map `y` as radial tweak.
 * Writes a point on the inner cavity surface in world space.
 */
function sphereTangentXZToInnerSurfaceWorld(xMap, zMap, yMap, outSurf) {
  const px = Number(xMap) || 0;
  /** Place surface points exactly on the planet outer surface; `createRadialStandingTrunkFromSurface` buries the base 0.045 m inward. */
  const pyShell = spherePlayRadius;
  const pz = Number(zMap) || 0;
  let R = pyShell + (Number(yMap) || 0) * 0.35;
  const rMin = spherePlayRadius * 0.25;
  const rMax = spherePlayRadius + 0.5;
  if (R < rMin) R = rMin;
  if (R > rMax) R = rMax;
  const l = Math.hypot(px, pyShell, pz);
  if (l < 1e-8) {
    outSurf.set(sphereCenter.x, sphereCenter.y + R, sphereCenter.z);
    return;
  }
  const s = R / l;
  outSurf.set(sphereCenter.x + px * s, sphereCenter.y + pyShell * s, sphereCenter.z + pz * s);
}

/* ---------------------------------------------------------------------------
 * Surface anchoring — the bridge between flat map space and the planet.
 *
 * Everything in this game was authored in flat map space: `(x, z)` on the ground
 * plane with `+Y` up. `sphereTangentXZToInnerSurfaceWorld` projects a *point* onto
 * the sphere, but until now only trees used it, so the store, props, plots, sell
 * zone and sawmills all rendered at their raw flat coords — i.e. ~2,475 m below the
 * surface, at the planet core.
 *
 * These three helpers fix that generically. Rather than rewriting each subsystem's
 * geometry into planet-local coordinates, we keep the authored flat layout intact and
 * hang it off an *anchor* group carrying the surface pose. A structure authored around
 * `(sx, sz)` then lands tangent to the surface at exactly that map coordinate, upright
 * along the radial normal, with every internal offset preserved.
 * ------------------------------------------------------------------------- */

const _SURF_LOCAL_UP = new THREE.Vector3(0, 1, 0);
const _surfAnchorPos = new THREE.Vector3();
const _surfAnchorQuat = new THREE.Quaternion();
const _surfAnchorUp = new THREE.Vector3();
/** Dedicated scratch for the collider path — must not alias the anchor scratch above. */
const _surfBodyLocal = new THREE.Vector3();
/** Dedicated scratch for land-plot collider poses — must not alias the two sets above. */
const _plotYawQuat = new THREE.Quaternion();
const _plotPoseQuat = new THREE.Quaternion();
const _plotUpVec = new THREE.Vector3();
/** Dedicated scratch for loose-object spawns (shop stock, kit boxes). */
const _shopSpawnPos = new THREE.Vector3();
const _shopSpawnQuat = new THREE.Quaternion();

/**
 * Recompute {@link sellCenterWorld} from the authored flat `sellCenter`. Call after anything
 * that moves the sell spot: world-def load, map-editor override, or a planet switch.
 */
function refreshSellCenterWorld() {
  if (!spherePhysicsActive) {
    sellCenterWorld.copy(sellCenter);
    return;
  }
  sphereTangentXZToInnerSurfaceWorld(sellCenter.x, sellCenter.z, 0, sellCenterWorld);
}

/** Keep the sell sparkles pinned to (and tangent with) the surface point they represent. */
function applySellZoneParticlePose() {
  if (!sellParticleGroup) return;
  if (sellParticleAnchor) {
    if (planetSurfaceAnchorTransform(sellCenter.x, sellCenter.z, _surfAnchorPos, _surfAnchorQuat)) {
      sellParticleAnchor.position.copy(_surfAnchorPos);
      sellParticleAnchor.quaternion.copy(_surfAnchorQuat);
    }
    return;
  }
  sellParticleGroup.position.set(sellCenter.x, 0, sellCenter.z);
}

/**
 * Rigid transform placing flat map space onto the planet surface at tangent `(xMap, zMap)`:
 * local `+Y` becomes the radial normal, local `XZ` stays tangent to the surface.
 *
 * @returns {boolean} `false` when sphere physics is off, so callers keep legacy flat placement.
 */
function planetSurfaceAnchorTransform(xMap, zMap, outPos, outQuat) {
  if (!spherePhysicsActive) return false;
  sphereTangentXZToInnerSurfaceWorld(xMap, zMap, 0, outPos);
  _surfAnchorUp.set(
    outPos.x - sphereCenter.x,
    outPos.y - sphereCenter.y,
    outPos.z - sphereCenter.z
  );
  if (_surfAnchorUp.lengthSq() < 1e-10) _surfAnchorUp.set(0, 1, 0);
  _surfAnchorUp.normalize();
  outQuat.setFromUnitVectors(_SURF_LOCAL_UP, _surfAnchorUp);
  return true;
}

/**
 * Hang a group authored in flat map space off a surface anchor at `(xMap, zMap)`.
 *
 * The group's own geometry is untouched — it is merely offset by `-(xMap, 0, zMap)` inside
 * the anchor, so the map point `(xMap, 0, zMap)` lands exactly on the surface.
 *
 * @returns {THREE.Group|null} the anchor, or `null` in flat mode (group added to `parent` as-is).
 */
function anchorFlatGroupToPlanetSurface(group, xMap, zMap, parent) {
  const host = parent ?? planetMeshParent();
  if (!planetSurfaceAnchorTransform(xMap, zMap, _surfAnchorPos, _surfAnchorQuat)) {
    host.add(group);
    return null;
  }
  const anchor = new THREE.Group();
  anchor.name = `${group.name || 'Group'}_SurfaceAnchor`;
  anchor.position.copy(_surfAnchorPos);
  anchor.quaternion.copy(_surfAnchorQuat);
  group.position.set(-(Number(xMap) || 0), 0, -(Number(zMap) || 0));
  anchor.add(group);
  host.add(anchor);
  anchor.updateMatrixWorld(true);
  return anchor;
}

/**
 * Convert a single flat map point `(xMap, yMap, zMap)` — `yMap` being height above ground — into
 * world space on the planet surface. Use for spawning loose dynamic objects (shop stock, kit
 * boxes) that were authored against the flat layout.
 *
 * @param {THREE.Quaternion} [outQuat] optionally receives the surface orientation at that point.
 * @returns {THREE.Vector3} `outVec`, for chaining.
 */
function flatMapPointToWorld(xMap, yMap, zMap, outVec, outQuat) {
  if (!planetSurfaceAnchorTransform(xMap, zMap, _surfAnchorPos, _surfAnchorQuat)) {
    outVec.set(Number(xMap) || 0, Number(yMap) || 0, Number(zMap) || 0);
    if (outQuat) outQuat.set(0, 0, 0, 1);
    return outVec;
  }
  _surfBodyLocal.set(0, Number(yMap) || 0, 0).applyQuaternion(_surfAnchorQuat);
  outVec.copy(_surfAnchorPos).add(_surfBodyLocal);
  if (outQuat) outQuat.copy(_surfAnchorQuat);
  return outVec;
}

/**
 * Place a group authored around **its own origin** onto the planet surface at map point
 * `(xMap, zMap)`, keeping `yMap` as height above the surface and `ryMap` as a spin about the
 * surface normal.
 *
 * Use this for plots / props / sawmills, whose contents sit at the group origin and are then
 * *positioned* by the map. (Use {@link anchorFlatGroupToPlanetSurface} instead for the store,
 * whose geometry is authored at absolute map coords.)
 *
 * Note: like the existing tree spawn path, anchors are built in world space and parented under
 * `planetMeshParent()`, which assumes the active planet's root is at the origin.
 *
 * @returns {THREE.Group|null} the anchor, or `null` in flat mode (legacy pose applied to `grp`).
 */
function placeOriginGroupOnPlanetSurface(grp, xMap, yMap, zMap, ryMap, parent) {
  const host = parent ?? planetMeshParent();
  if (!planetSurfaceAnchorTransform(xMap, zMap, _surfAnchorPos, _surfAnchorQuat)) {
    grp.position.set(Number(xMap) || 0, Number(yMap) || 0, Number(zMap) || 0);
    grp.rotation.y = Number(ryMap) || 0;
    host.add(grp);
    return null;
  }
  const anchor = new THREE.Group();
  anchor.name = `${grp.name || 'Group'}_SurfaceAnchor`;
  anchor.position.copy(_surfAnchorPos);
  anchor.quaternion.copy(_surfAnchorQuat);
  grp.position.set(0, Number(yMap) || 0, 0);
  grp.rotation.set(0, Number(ryMap) || 0, 0);
  anchor.add(grp);
  host.add(anchor);
  anchor.updateMatrixWorld(true);
  return anchor;
}

/**
 * World pose for a static collider authored at flat map coords `(px, py, pz)`, routed through
 * the surface `anchor` returned by {@link anchorFlatGroupToPlanetSurface}.
 *
 * Pass `anchor = null` (flat mode) to get the legacy behaviour: position as-authored, no rotation.
 */
function applyFlatMapPoseToStaticBody(bodyOut, anchor, xMap, zMap, px, py, pz) {
  if (!anchor) {
    bodyOut.position.set(px, py, pz);
    bodyOut.quaternion.set(0, 0, 0, 1);
    return;
  }
  _surfBodyLocal.set(px - (Number(xMap) || 0), py, pz - (Number(zMap) || 0));
  _surfBodyLocal.applyMatrix4(anchor.matrixWorld);
  bodyOut.position.set(_surfBodyLocal.x, _surfBodyLocal.y, _surfBodyLocal.z);
  bodyOut.quaternion.set(
    anchor.quaternion.x,
    anchor.quaternion.y,
    anchor.quaternion.z,
    anchor.quaternion.w
  );
}

/**
 * Anchored trunk whose local +Y follows planet radial “up” through `surfWorld` (on or slightly outside the shell).
 */
function createRadialStandingTrunkFromSurface(surfWorld, treeId, halfW, trunkH, ry, logSkin) {
  let h = trunkH;
  if (h > MAX_STANDING_TRUNK_HEIGHT) h = MAX_STANDING_TRUNK_HEIGHT;
  if (h < 0.14) return null;
  tmpV.set(surfWorld.x - sphereCenter.x, surfWorld.y - sphereCenter.y, surfWorld.z - sphereCenter.z);
  if (tmpV.lengthSq() < 1e-10) tmpV.set(0, 1, 0);
  tmpV.normalize();
  const inward = 0.045;
  tmpV2.copy(surfWorld).addScaledVector(tmpV, -inward);
  tmpV3.copy(tmpV2).addScaledVector(tmpV, h * 0.5);
  trunkQuaternionYAlongWorldUp(tmpV, ry, _splitTrunkKeepQuat);
  const { mesh, body } = createBoxLog({
    centerY: tmpV3.y,
    height: h,
    halfW,
    x: tmpV3.x,
    z: tmpV3.z,
    quat: _splitTrunkKeepQuat,
    regKind: 'trunk',
    mass: 0,
    logSkin,
  });
  mesh.userData.trunk = { trunkMode: 'oriented', treeId, halfW, x: tmpV3.x, z: tmpV3.z };
  mesh.userData.treeId = treeId;
  mesh.userData.trunkAnchored = true;
  return { mesh, body };
}

function countTrunkMeshesInWorld() {
  let n = 0;
  for (const { mesh } of dynamicPairs) {
    if (mesh.userData.kind === 'trunk') n++;
  }
  return n;
}

/** P1.5: if sphere mode ended up with no trunks (e.g. empty `trees: []`), place one demo tree by lat/lon. */
function ensureSphereWorldHasAtLeastOneTree() {
  if (!spherePhysicsActive || !world) return;
  if (countTrunkMeshesInWorld() > 0) return;
  createSphereRadialTreeByLatLon(0.22, 0.55, 0.35, 'wood');
}

function createSphereRadialTreeByLatLon(lat, lon, ry, logSkin) {
  const treeId = nextTreeId++;
  const { halfW, trunkH } = treeDimsForCurrentMode();
  let Rskin = spherePlayRadius;
  const rMin = spherePlayRadius * 0.25;
  const rMax = spherePlayRadius + 0.5;
  if (Rskin < rMin) Rskin = rMin;
  if (Rskin > rMax) Rskin = rMax;
  sphereLatLonDirToVector3(lat, lon, tmpV);
  tmpV4.set(
    sphereCenter.x + tmpV.x * Rskin,
    sphereCenter.y + tmpV.y * Rskin,
    sphereCenter.z + tmpV.z * Rskin,
  );
  const seg = createRadialStandingTrunkFromSurface(tmpV4, treeId, halfW, trunkH, ry, normalizeLogSkin(logSkin));
  if (!seg) return;
  seg.mesh.userData.sellable = false;
  seg.mesh.userData.leafMesh = null;
  seg.mesh.userData.leafBody = null;
  attachTreeBranchesForGame(seg.mesh, normalizeLogSkin(logSkin), treeId, lat * 10 + treeId * 0.01, lon * 10);
}

/**
 * Tiny deterministic PRNG (mulberry32) so the same `treeSpawn.seed` always yields the same forest
 * — makes biome forests stable across reloads instead of re-rolling every session.
 */
function makeSeededRng(seed) {
  let s = (seed | 0) || 1;
  return function next() {
    s = (s + 0x6D2B79F5) | 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Pick the biome whose latitude range contains `latDeg`, preferring the narrowest band. Matches
 * the biome-color painter in {@link applyBiomeVertexColorsToSphere} so the tree planted at a
 * latitude always matches the color painted on the ground there (e.g. palms on tan beach bands,
 * pines on white polar caps).
 */
function pickBiomeForLatitude(latDeg, biomes) {
  if (!Array.isArray(biomes) || biomes.length === 0) return null;
  let best = null;
  let bestSpan = Infinity;
  for (const b of biomes) {
    if (!b) continue;
    const latMin = b.latMin ?? -90;
    const latMax = b.latMax ?? 90;
    if (latDeg < latMin || latDeg > latMax) continue;
    const span = Math.abs(latMax - latMin);
    if (span < bestSpan) {
      bestSpan = span;
      best = b;
    }
  }
  return best;
}

/**
 * =============================================================================
 * Instanced procedural forest
 * =============================================================================
 *
 * Dense planet-scale forests (1k–50k trees) are orders of magnitude cheaper when
 * rendered via `THREE.InstancedMesh` with **one shared geometry per preset**,
 * instead of a unique mesh tree per trunk. The previous "merge per tree" approach
 * was ~3 draw calls per tree × 10 000 trees = 30 000 draw calls → unplayable.
 * Instancing collapses that to 2 draw calls per preset (bark mesh + leaf mesh)
 * regardless of how many trees share that preset.
 *
 * Trade-offs documented for future callers:
 *   • Every tree of the same preset shares ONE canonical geometry (a single seed,
 *     fixed branch layout). At scene scale the identical clones are invisible; up
 *     close the same preset repeats if you compare neighbors side-by-side.
 *   • Scenery trees have **no physics bodies** and **cannot be chopped**. The
 *     authored per-planet tree list (`slice.trees`) remains fully chopable and is
 *     the intended way to give players wood-gathering targets. Procedural trees
 *     are pure scenery density — the "background forest" effect.
 * =============================================================================
 */

/**
 * 32-bit FNV-1a hash. Used to turn a preset string id into a seed so every preset's canonical
 * branching stays deterministic without relying on insertion order in {@link _proceduralTreePrototypes}.
 */
function _hashStringToInt(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/**
 * Per-preset tree prototype cache. Built lazily on first use and kept for the session.
 * Each entry holds merged `(bark, leaves)` BufferGeometries plus shared materials. A later
 * preset-config edit invalidates nothing automatically — call {@link clearProceduralTreePrototypes}
 * if the user changes a preset's shape via the live editor.
 * @type {Map<string, { presetId: string, barkGeo: THREE.BufferGeometry, barkMat: THREE.Material,
 *   leafGeo: THREE.BufferGeometry | null, leafMat: THREE.Material | null, halfW: number, trunkH: number }>}
 */
const _proceduralTreePrototypes = new Map();

/**
 * Build (or return cached) canonical tree geometry for a preset. Constructs a disposable
 * trunk + branches + leaves tree, walks the resulting hierarchy in local space, bakes each
 * child's local matrix into a cloned geometry, and merges by material category into:
 *   • `barkGeo` = trunk box + all branch boxes, one shared bark material
 *   • `leafGeo` = all leaf boxes, one shared leaf material
 * These two geometries become the `InstancedMesh` sources for the forest.
 */
function buildProceduralTreePrototype(presetId) {
  const cached = _proceduralTreePrototypes.get(presetId);
  if (cached) return cached;
  const cfg = loadLt2ConfigForPreset(presetId);
  const halfW = cfg.trunkThickness;
  const trunkH = cfg.trunkHeight;
  const trunkW = halfW * 2;
  /** Shared, cache-keyed bark material. Matches what `createBoxLog` uses on authored trees
   *  so the planetary forest blends visually with hand-placed ones. */
  const barkMat = getTrunkSideMaterial(cfg.trunkTexture, cfg.trunkColor);
  const trunkBoxGeo = new THREE.BoxGeometry(trunkW, trunkH, trunkW);
  const trunkMesh = new THREE.Mesh(trunkBoxGeo, barkMat);
  const seed = _hashStringToInt(presetId || 'default') || 42;
  /** Merge mode so branches + leaves come out as two pre-merged child meshes we can harvest. */
  applyLt2StyleTreeBranches(trunkMesh, seed, cfg, { mergeMeshes: true });
  trunkMesh.updateMatrixWorld(true);

  const barkGeos = [trunkBoxGeo.clone()];
  const leafGeos = [];
  let leafMat = null;
  const holder = trunkMesh.getObjectByName('procedural_branches_holder');
  if (holder) {
    for (const child of holder.children) {
      if (!child.isMesh || !child.geometry) continue;
      /** Merged branch/leaf meshes live at `holder.position = (0,0,0)` with identity rotation
       *  because {@link applyLt2StyleTreeBranches} bakes world-local into the geometry itself.
       *  Still call `applyMatrix4(child.matrix)` defensively — if a future change adds an
       *  offset we don't silently drop it. (Applying an identity matrix is a no-op so there's
       *  no need to branch on it, and `THREE.Matrix4` has no `isIdentity` helper.) */
      child.updateMatrix();
      const g = child.geometry.clone();
      g.applyMatrix4(child.matrix);
      if (child.name === 'lt2_leaves_merged') {
        leafGeos.push(g);
        leafMat = child.material;
      } else {
        barkGeos.push(g);
      }
      child.geometry.dispose();
    }
  }
  trunkBoxGeo.dispose();

  const barkGeoFinal = BufferGeometryUtils.mergeGeometries(barkGeos, false);
  const leafGeoFinal = leafGeos.length > 0 ? BufferGeometryUtils.mergeGeometries(leafGeos, false) : null;
  for (const g of barkGeos) g.dispose();
  for (const g of leafGeos) g.dispose();

  const proto = {
    presetId,
    barkGeo: barkGeoFinal,
    barkMat,
    leafGeo: leafGeoFinal,
    leafMat,
    halfW,
    trunkH,
  };
  _proceduralTreePrototypes.set(presetId, proto);
  return proto;
}

/**
 * Wipe the prototype cache — call this whenever a preset's shape-driving config changes
 * (branch count, trunk thickness, etc.). Forces the next planet repopulation to rebuild.
 */
function clearProceduralTreePrototypes() {
  for (const proto of _proceduralTreePrototypes.values()) {
    proto.barkGeo?.dispose();
    proto.leafGeo?.dispose();
  }
  _proceduralTreePrototypes.clear();
}
if (typeof window !== 'undefined') {
  window.clearProceduralTreePrototypes = clearProceduralTreePrototypes;
}

/**
 * Per-planet root `THREE.Group` holding that planet's procedural-forest `InstancedMesh` pair
 * (bark + leaves). Separate groups per planet because each planet's trees are positioned in
 * world space around its own center; toggling a group's visibility is how we would hide far
 * planets' forests if we ever needed to (currently we leave them visible — scenery at 20 km
 * is essentially free once instanced).
 * @type {Map<string, THREE.Group>}
 */
const _planetForestGroups = new Map();

/**
 * Remove any existing instanced forest for `planetId` and dispose its InstancedMeshes. Does
 * NOT dispose the shared preset prototype geometries — those stay in the cache for reuse.
 */
function removeInstancedForestForPlanet(planetId) {
  const grp = _planetForestGroups.get(planetId);
  if (!grp) return;
  if (grp.parent) grp.parent.remove(grp);
  for (const child of grp.children) {
    if (child.isInstancedMesh) child.dispose?.();
  }
  _planetForestGroups.delete(planetId);
}

/**
 * Scatter procedural biome-aware trees across the **currently active** planet using
 * {@link THREE.InstancedMesh}. Trees are deterministic from `treeSpawn.seed`, placed via a
 * Fibonacci sphere for even baseline distribution, optionally pulled toward randomly chosen
 * cluster centers ("clustering" slider) for natural-looking forest-and-clearing patterns.
 *
 * For each candidate slot we:
 *   1. Compute the Fibonacci sample direction `(ux, uy, uz)`.
 *   2. If `clustering > 0`, blend the direction toward the nearest cluster center by that
 *      factor — 0 leaves samples untouched, 1 snaps every sample onto a cluster axis.
 *   3. Look up the biome covering the resulting latitude (narrowest-band-wins).
 *   4. Roll `density` to gate spawns (e.g. beaches at 0.45 spawn ~45% of their slots).
 *   5. Pick a random preset from `biome.presetIds`; enqueue under that preset's bucket.
 * After the scan we build one InstancedMesh per preset and populate its matrix buffer.
 *
 * Runs only for sphere-physics worlds when `treeSpawn.enabled` is true.
 */
function spawnProceduralTreesFromBiomes(sliceArg) {
  if (!spherePhysicsActive || !world) return;
  if (getTreeGenerationMode() !== 'lt2') return;
  const slice = sliceArg || getActiveMapWorldSlice();
  if (!slice) return;
  const ts = slice.treeSpawn;
  if (!ts || ts.enabled === false) return;
  const biomes = Array.isArray(slice.biomes) && slice.biomes.length ? slice.biomes : DEFAULT_BIOMES;
  const target = Math.max(0, Math.min(50000, ts.targetCount | 0));
  if (target <= 0) {
    removeInstancedForestForPlanet(runtimePlanetId);
    return;
  }
  const clusteringRaw = Number(ts.clustering);
  const clustering = Number.isFinite(clusteringRaw) ? Math.max(0, Math.min(1, clusteringRaw)) : 0.25;
  const rng = makeSeededRng(ts.seed || 1);
  /** Always wipe the existing forest first — this is the "reseed" path triggered by warp,
   *  map edits, or first-time population. Callers that want to keep the forest intact should
   *  skip this function entirely (see {@link populatePlanetContent} gating). */
  removeInstancedForestForPlanet(runtimePlanetId);

  /**
   * Pre-compute K cluster-center directions on the unit sphere. Higher clustering → more
   * clusters (so you still get spread, just biased); low clustering → few clusters (tight
   * hotspots). 0 clustering skips the cluster lookup entirely for a pure Fibonacci spread.
   */
  const K = clustering > 0 ? Math.max(6, Math.min(200, Math.round(10 + clustering * 80))) : 0;
  const clusters = new Float32Array(K * 3);
  for (let k = 0; k < K; k++) {
    const u = rng() * 2 - 1;
    const t = rng() * Math.PI * 2;
    const s = Math.sqrt(Math.max(0, 1 - u * u));
    clusters[k * 3] = s * Math.cos(t);
    clusters[k * 3 + 1] = u;
    clusters[k * 3 + 2] = s * Math.sin(t);
  }

  const GOLD = Math.PI * (3 - Math.sqrt(5));
  /** Bucket per preset id → array of `{ ux, uy, uz, ry }` entries ready for matrix packing. */
  const perPreset = new Map();

  for (let i = 0; i < target; i++) {
    const y = 1 - (2 * (i + 0.5)) / target;
    const rXZ = Math.sqrt(Math.max(0, 1 - y * y));
    const phi = GOLD * i;
    let nx = Math.cos(phi) * rXZ;
    let ny = y;
    let nz = Math.sin(phi) * rXZ;
    /** Cluster bias: find the closest cluster axis (max dot product) and lerp this sample's
     *  direction toward it by `clustering`. Renormalize after — matters since we interpret it
     *  as a surface-normal direction for positioning. */
    if (K > 0) {
      let bestDot = -2;
      let bestIdx = 0;
      for (let k = 0; k < K; k++) {
        const cx = clusters[k * 3];
        const cy = clusters[k * 3 + 1];
        const cz = clusters[k * 3 + 2];
        const d = cx * nx + cy * ny + cz * nz;
        if (d > bestDot) {
          bestDot = d;
          bestIdx = k;
        }
      }
      const cx = clusters[bestIdx * 3];
      const cy = clusters[bestIdx * 3 + 1];
      const cz = clusters[bestIdx * 3 + 2];
      nx = nx + (cx - nx) * clustering;
      ny = ny + (cy - ny) * clustering;
      nz = nz + (cz - nz) * clustering;
    }
    const invLen = 1 / Math.hypot(nx, ny, nz);
    const ux = nx * invLen;
    const uy = ny * invLen;
    const uz = nz * invLen;
    const latDeg = Math.asin(Math.max(-1, Math.min(1, uy))) * (180 / Math.PI);
    const biome = pickBiomeForLatitude(latDeg, biomes);
    if (!biome || !Array.isArray(biome.presetIds) || biome.presetIds.length === 0) continue;
    const density = Math.max(0, Math.min(1, biome.density ?? 1));
    if (rng() > density) continue;
    const presetId = biome.presetIds[(rng() * biome.presetIds.length) | 0];
    const ry = rng() * Math.PI * 2;
    let bucket = perPreset.get(presetId);
    if (!bucket) {
      bucket = [];
      perPreset.set(presetId, bucket);
    }
    bucket.push(ux, uy, uz, ry);
  }

  /** Build one InstancedMesh pair per preset. Parent everything under a per-planet group so
   *  switching planets or editor-driven resews can drop the whole forest with one remove. */
  const group = new THREE.Group();
  group.name = `InstancedForest_${runtimePlanetId}`;
  group.userData.planetId = runtimePlanetId;
  /** Keep the whole instanced forest unaffected by world-space fog — stars and sky already
   *  handle depth cues, and fading an instanced mesh would only fade uniformly which looks
   *  wrong at planet scale. */
  group.userData.excludeFromPrefabSerialize = true;

  const _upLocal = new THREE.Vector3(0, 1, 0);
  const _vUp = new THREE.Vector3();
  const _vPos = new THREE.Vector3();
  const _scl = new THREE.Vector3(1, 1, 1);
  const _qAlign = new THREE.Quaternion();
  const _qRy = new THREE.Quaternion();
  const _qFinal = new THREE.Quaternion();
  const _mat4 = new THREE.Matrix4();

  let totalSpawned = 0;
  for (const [presetId, raw] of perPreset) {
    const proto = buildProceduralTreePrototype(presetId);
    if (!proto || !proto.barkGeo) continue;
    const N = (raw.length / 4) | 0;
    if (N <= 0) continue;
    const barkMesh = new THREE.InstancedMesh(proto.barkGeo, proto.barkMat, N);
    barkMesh.name = `forest_bark_${presetId}`;
    barkMesh.castShadow = true;
    barkMesh.receiveShadow = true;
    /** Skip per-mesh frustum culling — the "mesh" is the whole forest bounding sphere, which
     *  is essentially the planet. Culling against a planet-sized AABB never helps and costs
     *  an early matrix pass. */
    barkMesh.frustumCulled = false;
    let leafMesh = null;
    if (proto.leafGeo && proto.leafMat) {
      leafMesh = new THREE.InstancedMesh(proto.leafGeo, proto.leafMat, N);
      leafMesh.name = `forest_leaves_${presetId}`;
      leafMesh.castShadow = true;
      leafMesh.receiveShadow = true;
      leafMesh.frustumCulled = false;
    }

    /** Inward bury depth (matches {@link createRadialStandingTrunkFromSurface}) so the base
     *  sinks just below the visual surface — avoids z-fighting with biome-colored ground. */
    const inward = 0.045;
    const lift = proto.trunkH / 2 - inward;
    for (let j = 0; j < N; j++) {
      const ux = raw[j * 4];
      const uy = raw[j * 4 + 1];
      const uz = raw[j * 4 + 2];
      const ry = raw[j * 4 + 3];
      _vUp.set(ux, uy, uz);
      _qAlign.setFromUnitVectors(_upLocal, _vUp);
      _qRy.setFromAxisAngle(_vUp, ry);
      _qFinal.multiplyQuaternions(_qRy, _qAlign);
      _vPos.set(
        sphereCenter.x + ux * (spherePlayRadius + lift),
        sphereCenter.y + uy * (spherePlayRadius + lift),
        sphereCenter.z + uz * (spherePlayRadius + lift),
      );
      _mat4.compose(_vPos, _qFinal, _scl);
      barkMesh.setMatrixAt(j, _mat4);
      if (leafMesh) leafMesh.setMatrixAt(j, _mat4);
    }
    barkMesh.instanceMatrix.needsUpdate = true;
    if (leafMesh) leafMesh.instanceMatrix.needsUpdate = true;
    group.add(barkMesh);
    if (leafMesh) group.add(leafMesh);
    totalSpawned += N;
  }

  scene.add(group);
  _planetForestGroups.set(runtimePlanetId, group);

  console.log(
    '%c[planet] instanced forest',
    'background:#284;color:#fff;padding:2px 6px;border-radius:3px',
    {
      planetId: runtimePlanetId,
      spawned: totalSpawned,
      target,
      presets: perPreset.size,
      clustering,
      drawCalls: group.children.length,
    },
  );
}

/**
 * Parent `procHolder` under `targetMesh` so its world pose matches {@link _procHolderSavedMw}
 * (must be copied from `procHolder.matrixWorld` while it still had a valid parent chain).
 */
function reparentProcHolderPreservingWorld(procHolder, targetMesh) {
  targetMesh.updateMatrixWorld(true);
  _procHolderRelToTop.copy(targetMesh.matrixWorld).invert().multiply(_procHolderSavedMw);
  _procHolderRelToTop.decompose(procHolder.position, procHolder.quaternion, procHolder.scale);
  const sx = Math.abs(procHolder.scale.x);
  const sy = Math.abs(procHolder.scale.y);
  const sz = Math.abs(procHolder.scale.z);
  if (!Number.isFinite(sx) || !Number.isFinite(sy) || !Number.isFinite(sz) || sx + sy + sz < 1e-6) {
    procHolder.scale.set(1, 1, 1);
  } else {
    procHolder.scale.set(Math.max(1e-4, sx), Math.max(1e-4, sy), Math.max(1e-4, sz));
  }
  targetMesh.add(procHolder);
  procHolder.updateMatrixWorld(true);
}

/**
 * @param {THREE.Mesh} trunkMesh
 * @param {string} logSkin
 * @param {number} treeId
 * @param {number} x
 * @param {number} z
 * @param {import('./lt2-tree-config.js').Lt2Config | null} [cfgOverride]
 *   Optional LT2 config to use instead of the **active** preset. Passed by the biome-aware
 *   procedural spawner so a palm-biome spawn uses the palm preset's thickness/colors/leaves
 *   even when the active (gear-icon) preset is something else. If omitted, behaves as before.
 */
function attachTreeBranchesForGame(trunkMesh, logSkin, treeId, x, z, cfgOverride) {
  const seed = hashTreeBranchSeed(treeId, x, z);
  if (getTreeGenerationMode() === 'lt2') {
    const lt2cfg = cfgOverride || loadLt2Config();
    if (Array.isArray(trunkMesh.material) && (trunkMesh.userData.logSkin || 'wood') === 'wood') {
      const tint = applyBarkTintToSides(trunkMesh.material, lt2cfg.trunkColor, lt2cfg.trunkTexture);
      if (tint) {
        trunkMesh.userData.trunkColor = tint.color;
        trunkMesh.userData.trunkTexture = tint.texture;
      }
    }
    /** Apply tint BEFORE branches attach: `barkMaterialFromTrunk` picks `material[0]`, so branches inherit the tint. */
    /**
     * Procedural biome trees merge their branch + leaf geometry into two draw calls total.
     * Authored / hand-placed trees keep the per-branch mesh layout so they're still chop-able
     * limb-by-limb and editable in the prefab editor. The flag is set in
     * {@link spawnProceduralTreesFromBiomes} before this call.
     */
    const mergeMeshes = !!trunkMesh.userData.proceduralBiomeTree;
    applyLt2StyleTreeBranches(trunkMesh, seed, lt2cfg, { mergeMeshes });
    trunkMesh.userData.woodWeight = lt2cfg.woodWeight;
    applyInteriorToExistingTrunk(trunkMesh, {
      color: lt2cfg.interiorColor,
      neon: lt2cfg.interiorNeon,
      border: lt2cfg.interiorBorder,
      borderThickness: lt2cfg.interiorBorderThickness,
      borderColor: lt2cfg.interiorBorderColor,
      texture: lt2cfg.interiorTexture,
    });
    syncBranchCollidersOrCompoundForTrunk(trunkMesh);
    return;
  }
  const skin = normalizeLogSkin(logSkin);
  const prefKey = skin === 'dark' ? 'treeSpook' : skin === 'void' ? 'treeVoid' : 'tree';
  let pdata = tryGetPrefabDataForKind(prefKey);
  if (!pdata?.parts?.length && (prefKey === 'treeSpook' || prefKey === 'treeVoid')) {
    pdata = tryGetPrefabDataForKind('tree');
  }
  const settings = normalizeTreeBranchSettings(pdata?.treeBranches);
  applyProceduralTreeBranches(trunkMesh, settings, seed);
  syncBranchCollidersOrCompoundForTrunk(trunkMesh);
}

/**
 * Tree from map: editor supplies position + yaw + bark type only.
 * Main trunk (ground→crown box) half-width and height use the same rolls as {@link createTree}
 * ({@link randomTreeDimensions}). Foliage is small tips on procedural limb meshes only.
 */
function createTreeFromMapEntry(p) {
  const treeId = nextTreeId++;
  const x = Number(p.x) || 0;
  const z = Number(p.z) || 0;
  const py = Number(p.y) || 0;
  const ry = Number(p.ry) || 0;
  const { halfW, trunkH } = treeDimsForCurrentMode();
  const logSkin = normalizeLogSkin(p.logSkin);

  if (spherePhysicsActive) {
    sphereTangentXZToInnerSurfaceWorld(x, z, py, tmpV4);
    const seg = createRadialStandingTrunkFromSurface(tmpV4, treeId, halfW, trunkH, ry, logSkin);
    if (!seg) return;
    seg.mesh.userData.sellable = false;
    seg.mesh.userData.leafMesh = null;
    seg.mesh.userData.leafBody = null;
    attachTreeBranchesForGame(seg.mesh, logSkin, treeId, x, z);
    return;
  }

  const bottomY = py + 0.05;
  const topY = bottomY + trunkH;
  const quat = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, ry, 0));

  const seg = createTrunkSegment({
    bottomY,
    topY,
    halfW,
    x,
    z,
    treeId,
    quat,
    logSkin,
  });
  if (!seg) return;

  seg.mesh.userData.sellable = false;
  seg.mesh.userData.leafMesh = null;
  seg.mesh.userData.leafBody = null;

  attachTreeBranchesForGame(seg.mesh, logSkin, treeId, x, z);
}

function createTree(x, z) {
  const treeId = nextTreeId++;
  const { halfW, trunkH } = treeDimsForCurrentMode();
  const u = Math.random();
  const logSkin = u < 0.45 ? 'wood' : u < 0.78 ? 'dark' : 'void';
  const ry = Math.random() * Math.PI * 2;

  if (spherePhysicsActive) {
    sphereTangentXZToInnerSurfaceWorld(x, z, 0, tmpV4);
    const seg = createRadialStandingTrunkFromSurface(tmpV4, treeId, halfW, trunkH, ry, logSkin);
    if (!seg) return;
    seg.mesh.userData.sellable = false;
    seg.mesh.userData.leafMesh = null;
    seg.mesh.userData.leafBody = null;
    attachTreeBranchesForGame(seg.mesh, logSkin, treeId, x, z);
    return;
  }

  const bottomY = 0.05;
  const topY = bottomY + trunkH;

  const seg = createTrunkSegment({ bottomY, topY, halfW, x, z, treeId, logSkin });
  if (!seg) return;

  seg.mesh.userData.sellable = false;
  seg.mesh.userData.leafMesh = null;
  seg.mesh.userData.leafBody = null;

  attachTreeBranchesForGame(seg.mesh, logSkin, treeId, x, z);
}

function quatThreeFromBody(body) {
  const q = body.quaternion;
  return new THREE.Quaternion(q.x, q.y, q.z, q.w);
}

function worldPointToBodyLocal(body, wx, wy, wz) {
  const rel = new CANNON.Vec3(wx - body.position.x, wy - body.position.y, wz - body.position.z);
  const inv = new CANNON.Quaternion();
  body.quaternion.inverse(inv);
  inv.vmult(rel, rel);
  return rel;
}

/** Box log length axis (mesh local +Y) mostly vertical in world — standing tree trunks. */
function isWoodLengthAxisVertical(mesh) {
  const ly = new THREE.Vector3(0, 1, 0).applyQuaternion(mesh.quaternion);
  return Math.abs(ly.y) > 0.88;
}

/**
 * Split a fallen / rotated box log along mesh local Y (length axis).
 */
function splitOrientedBoxLog(mesh, body, cutLocalY) {
  const g = mesh.geometry.parameters;
  const H = g.height;
  const halfW = g.width != null ? g.width / 2 : 0.07;
  const halfH = H / 2;
  const lo = -halfH + CHOP_EDGE_INSET;
  const hi = halfH - CHOP_EDGE_INSET;
  const cy = Math.min(Math.max(cutLocalY, lo + 0.03), hi - 0.03);
  if (cy - (-halfH) < MIN_TRUNK_PIECE || halfH - cy < MIN_TRUNK_PIECE) return false;

  const leafMesh = mesh.userData.leafMesh;
  const leafBody = mesh.userData.leafBody;
  const tid = mesh.userData.treeId;
  const logSkin = mesh.userData.logSkin || 'wood';

  mesh.updateMatrixWorld(true);
  mesh.getWorldPosition(_branchSplitCenter);
  mesh.getWorldQuaternion(_branchSplitQuat);
  const hA = cy - (-halfH);
  const hB = halfH - cy;
  _branchSplitOff.set(0, (-halfH + cy) / 2, 0).applyQuaternion(_branchSplitQuat).add(_branchSplitCenter);
  const wAx = _branchSplitOff.x;
  const wAy = _branchSplitOff.y;
  const wAz = _branchSplitOff.z;
  _branchSplitOff.set(0, (cy + halfH) / 2, 0).applyQuaternion(_branchSplitQuat).add(_branchSplitCenter);
  const wBx = _branchSplitOff.x;
  const wBy = _branchSplitOff.y;
  const wBz = _branchSplitOff.z;
  const qThree = _branchSplitQuat.clone();

  const procHolder = mesh.getObjectByName('procedural_branches_holder');
  if (procHolder) {
    procHolder.updateMatrixWorld(true);
    _procHolderSavedMw.copy(procHolder.matrixWorld);
    clearTreeBranchCollidersAttachedTo(mesh);
    mesh.remove(procHolder);
  }

  unregisterDynamic(mesh, body, { skipMaterialDispose: !!procHolder });
  if (leafMesh && leafBody) {
    unregisterDynamic(leafMesh, leafBody);
  }

  const inheritedWoodWeight = mesh.userData.woodWeight;
  const inheritedInterior = extractInteriorFromMesh(mesh);
  const inheritedTrunkColor = mesh.userData.trunkColor;
  const inheritedTrunkTexture = mesh.userData.trunkTexture;
  const bottom = createBoxLog({
    centerY: wAy,
    height: hA,
    halfW,
    x: wAx,
    z: wAz,
    quat: qThree,
    regKind: 'log',
    logSkin,
    woodWeight: inheritedWoodWeight,
    interior: inheritedInterior,
    trunkColor: inheritedTrunkColor,
    trunkTexture: inheritedTrunkTexture,
  });
  bottom.mesh.userData.sellable = true;
  bottom.mesh.userData.treeId = tid;
  bottom.mesh.userData.trunk = { trunkMode: 'oriented', treeId: tid, halfW };

  const top = createBoxLog({
    centerY: wBy,
    height: hB,
    halfW,
    x: wBx,
    z: wBz,
    quat: qThree,
    regKind: 'log',
    logSkin,
    woodWeight: inheritedWoodWeight,
    interior: inheritedInterior,
    trunkColor: inheritedTrunkColor,
    trunkTexture: inheritedTrunkTexture,
  });
  top.mesh.userData.sellable = true;
  top.mesh.userData.treeId = tid;
  top.mesh.userData.trunk = { trunkMode: 'oriented', treeId: tid, halfW };

  if (top && leafMesh && leafBody) {
    top.mesh.userData.leafMesh = leafMesh;
    top.mesh.userData.leafBody = leafBody;
    const up = new THREE.Vector3(0, 1, 0).applyQuaternion(top.mesh.quaternion);
    const leafR = leafMesh.geometry.parameters.radius;
    const hc = top.body.position;
    const lift = hB / 2 + leafR * 0.35;
    leafMesh.position.set(hc.x + up.x * lift, hc.y + up.y * lift, hc.z + up.z * lift);
    leafBody.position.set(leafMesh.position.x, leafMesh.position.y, leafMesh.position.z);
    leafBody.velocity.setZero();
    leafBody.angularVelocity.setZero();
    world.addBody(leafBody);
    planetMeshParent().add(leafMesh);
    dynamicPairs.push({ mesh: leafMesh, body: leafBody });
  }

  if (bottom) bottom.mesh.userData.leafMesh = null;

  if (procHolder) {
    reparentProcHolderPreservingWorld(procHolder, top.mesh);
    rebuildTreeWoodCompound(top.mesh);
  }

  money += 2;
  updateMoneyHud();
  return true;
}

/** Twigs / sub-twigs / tip leaves under a crown column (any depth; excludes crown trunk boxes). */
function collectCrownTwigMeshesUnder(crownRoot) {
  const out = [];
  crownRoot.traverse((c) => {
    if (!c.isMesh || c === crownRoot) return;
    if (c.name.startsWith('crown_twig') || c.name.startsWith('branch_tip_leaf')) out.push(c);
  });
  return out;
}

function releaseTreeBranchPhysicsForMeshes(meshes) {
  for (const m of meshes) {
    if (m.userData.branchCompoundHost) {
      m.userData.body = null;
      m.userData.kind = undefined;
      m.userData.branchCompoundHost = undefined;
      m.userData.branchHostTrunk = undefined;
      continue;
    }
    const b = m.userData.body;
    if (b) {
      removeTreeBranchColliderEntry(m, b);
      world.removeBody(b);
    }
    m.userData.body = null;
    m.userData.kind = undefined;
    m.userData.branchHostTrunk = undefined;
  }
}

/**
 * Split a procedural bark box into two logs. Uses the mesh world pose (not the Cannon body after removal)
 * so twigs / compound hosts never read freed or wrong bodies.
 */
function splitOrientedTreeBranch(mesh, body, cutLocalY) {
  const g = mesh.geometry.parameters;
  const H = g.height;
  const halfW = g.width != null ? g.width / 2 : 0.07;
  const halfH = H / 2;
  const lo = -halfH + CHOP_EDGE_INSET;
  const hi = halfH - CHOP_EDGE_INSET;
  const cy = Math.min(Math.max(cutLocalY, lo + 0.03), hi - 0.03);
  if (cy - (-halfH) < MIN_TRUNK_PIECE || halfH - cy < MIN_TRUNK_PIECE) return false;

  const tid = mesh.userData.treeId | 0;
  const logSkin = mesh.userData.logSkin || 'wood';
  const isCrownUpperSegment = /^crown_\d+_trunk1$/.test(mesh.name);
  const crownRoot = isCrownUpperSegment && mesh.parent?.name?.startsWith('crown_') ? mesh.parent : null;
  const trunkHostForRefresh = getTrunkHostMeshForProceduralPart(mesh);
  const compoundHost = mesh.userData.branchCompoundHost || null;

  mesh.updateMatrixWorld(true);
  mesh.getWorldPosition(_branchSplitCenter);
  mesh.getWorldQuaternion(_branchSplitQuat);
  const hA = cy - (-halfH);
  const hB = halfH - cy;
  _branchSplitOff.set(0, (-halfH + cy) / 2, 0).applyQuaternion(_branchSplitQuat).add(_branchSplitCenter);
  const wAx = _branchSplitOff.x;
  const wAy = _branchSplitOff.y;
  const wAz = _branchSplitOff.z;
  _branchSplitOff.set(0, (cy + halfH) / 2, 0).applyQuaternion(_branchSplitQuat).add(_branchSplitCenter);
  const wBx = _branchSplitOff.x;
  const wBy = _branchSplitOff.y;
  const wBz = _branchSplitOff.z;
  const qThree = _branchSplitQuat.clone();

  if (!compoundHost) {
    removeTreeBranchColliderEntry(mesh, body);
    world.removeBody(body);
  } else {
    mesh.userData.body = null;
    mesh.userData.kind = undefined;
    mesh.userData.branchCompoundHost = undefined;
    mesh.userData.branchHostTrunk = undefined;
  }
  mesh.removeFromParent();
  mesh.geometry?.dispose?.();

  if (compoundHost) {
    rebuildTreeWoodCompound(compoundHost);
  }

  const inheritedWoodWeight = mesh.userData.woodWeight;
  const inheritedInterior = extractInteriorFromMesh(mesh);
  const inheritedTrunkColor = mesh.userData.trunkColor;
  const inheritedTrunkTexture = mesh.userData.trunkTexture;
  const bottom = createBoxLog({
    centerY: wAy,
    height: hA,
    halfW,
    x: wAx,
    z: wAz,
    quat: qThree,
    regKind: 'log',
    logSkin,
    woodWeight: inheritedWoodWeight,
    interior: inheritedInterior,
    trunkColor: inheritedTrunkColor,
    trunkTexture: inheritedTrunkTexture,
  });
  bottom.mesh.userData.sellable = true;
  bottom.mesh.userData.treeId = tid;
  bottom.mesh.userData.trunk = { trunkMode: 'oriented', treeId: tid, halfW };

  const top = createBoxLog({
    centerY: wBy,
    height: hB,
    halfW,
    x: wBx,
    z: wBz,
    quat: qThree,
    regKind: 'log',
    logSkin,
    woodWeight: inheritedWoodWeight,
    interior: inheritedInterior,
    trunkColor: inheritedTrunkColor,
    trunkTexture: inheritedTrunkTexture,
  });
  top.mesh.userData.sellable = true;
  top.mesh.userData.treeId = tid;
  top.mesh.userData.trunk = { trunkMode: 'oriented', treeId: tid, halfW };

  if (crownRoot) {
    const twigAnchorLocalY = halfH - 0.02;
    const useTopPiece = cy < twigAnchorLocalY - 0.06;
    const hostLog = useTopPiece ? top.mesh : bottom.mesh;
    const twigMeshes = collectCrownTwigMeshesUnder(crownRoot);
    releaseTreeBranchPhysicsForMeshes(twigMeshes);
    for (const ch of twigMeshes) {
      hostLog.attach(ch);
    }
  }

  if (trunkHostForRefresh) {
    if (shouldAttachStaticTreeBranchColliders(trunkHostForRefresh)) {
      attachTreeBranchColliders(trunkHostForRefresh);
    } else if (trunkHostForRefresh.userData.body?.mass > 1e-6) {
      rebuildTreeWoodCompound(trunkHostForRefresh);
    }
  }

  money += 2;
  updateMoneyHud();
  return true;
}

/** One dynamic log from a whole procedural branch mesh (cap sever). */
function detachTreeBranchWholeAsLog(mesh, body) {
  const compoundHost = mesh.userData.branchCompoundHost;
  if (compoundHost) {
    mesh.userData.body = null;
    mesh.userData.kind = undefined;
    mesh.userData.branchCompoundHost = undefined;
    mesh.userData.branchHostTrunk = undefined;
  } else {
    removeTreeBranchColliderEntry(mesh, body);
    world.removeBody(body);
  }
  mesh.updateMatrixWorld(true);
  const p = mesh.geometry.parameters;
  const worldPos = new THREE.Vector3();
  const worldQuat = new THREE.Quaternion();
  mesh.getWorldPosition(worldPos);
  mesh.getWorldQuaternion(worldQuat);
  const logSkin = mesh.userData.logSkin || 'wood';
  const tid = mesh.userData.treeId | 0;
  mesh.removeFromParent();
  mesh.geometry?.dispose?.();

  const halfW = p.width / 2;
  const h = p.height;
  const hostTrunk = mesh.userData.branchHostTrunk || compoundHost;
  const inheritedWoodWeight = mesh.userData.woodWeight ?? hostTrunk?.userData?.woodWeight;
  const inheritedInterior = extractInteriorFromMesh(mesh) ?? extractInteriorFromMesh(hostTrunk);
  const inheritedTrunkColor = mesh.userData.trunkColor ?? hostTrunk?.userData?.trunkColor;
  const inheritedTrunkTexture = mesh.userData.trunkTexture ?? hostTrunk?.userData?.trunkTexture;
  const c = createBoxLog({
    centerY: worldPos.y,
    height: h,
    halfW,
    x: worldPos.x,
    z: worldPos.z,
    quat: worldQuat,
    regKind: 'log',
    logSkin,
    woodWeight: inheritedWoodWeight,
    interior: inheritedInterior,
    trunkColor: inheritedTrunkColor,
    trunkTexture: inheritedTrunkTexture,
  });
  c.mesh.userData.sellable = true;
  c.mesh.userData.treeId = tid;
  c.mesh.userData.trunk = { trunkMode: 'oriented', treeId: tid, halfW };
  c.body.velocity.set(0, -0.35, 0);
  if (compoundHost) rebuildTreeWoodCompound(compoundHost);
  return c;
}

/** Chop near the treetop “collar”: drop every procedural limb as loose logs; trunk stays one piece. */
function severAllTreeBranchesFromTrunk(trunkMesh) {
  const holder = trunkMesh.getObjectByName('procedural_branches_holder');
  if (!holder) return false;
  const snapshot = treeBranchStaticColliderList.filter(
    (e) => e.branchMesh.userData.branchHostTrunk === trunkMesh
  );
  for (const { branchMesh, body } of snapshot) {
    detachTreeBranchWholeAsLog(branchMesh, body);
  }
  clearTreeBranchCollidersAttachedTo(trunkMesh);
  stripBranchCompoundExtraShapes(trunkMesh);
  trunkMesh.remove(holder);
  disposeProceduralBranchHolder(holder);
  return true;
}

function splitTrunkAtWorldY(mesh, body, cutWorldY) {
  const t = mesh.userData.trunk;
  if (!t) return false;
  const { bottomY, topY, halfW, x, z } = t;
  const lo = bottomY + CHOP_EDGE_INSET;
  const hi = topY - CHOP_EDGE_INSET;
  const y = Math.min(Math.max(cutWorldY, lo + 0.03), hi - 0.03);
  if (y - bottomY < MIN_TRUNK_PIECE || topY - y < MIN_TRUNK_PIECE) return false;

  const leafMesh = mesh.userData.leafMesh;
  const leafBody = mesh.userData.leafBody;
  const tid = mesh.userData.treeId;

  const logSkin = mesh.userData.logSkin || 'wood';

  _splitTrunkKeepQuat.copy(mesh.quaternion);

  mesh.updateMatrixWorld(true);
  const procHolder = mesh.getObjectByName('procedural_branches_holder');
  if (procHolder) {
    procHolder.updateMatrixWorld(true);
    _procHolderSavedMw.copy(procHolder.matrixWorld);
    clearTreeBranchCollidersAttachedTo(mesh);
    mesh.remove(procHolder);
  }

  unregisterDynamic(mesh, body, { skipMaterialDispose: !!procHolder });
  if (leafMesh && leafBody) {
    unregisterDynamic(leafMesh, leafBody);
  }

  const bottomAnchored = bottomY < 0.22;
  const inheritedWoodWeight = mesh.userData.woodWeight;
  const inheritedInterior = extractInteriorFromMesh(mesh);
  const inheritedTrunkColor = mesh.userData.trunkColor;
  const inheritedTrunkTexture = mesh.userData.trunkTexture;
  const bottom = createTrunkSegment(
    {
      bottomY,
      topY: y,
      halfW,
      x,
      z,
      treeId: tid,
      logSkin,
      quat: _splitTrunkKeepQuat,
      woodWeight: inheritedWoodWeight,
      interior: inheritedInterior,
      trunkColor: inheritedTrunkColor,
      trunkTexture: inheritedTrunkTexture,
    },
    { anchored: bottomAnchored }
  );
  const top = createTrunkSegment(
    {
      bottomY: y,
      topY,
      halfW,
      x,
      z,
      treeId: tid,
      logSkin,
      quat: _splitTrunkKeepQuat,
      woodWeight: inheritedWoodWeight,
      interior: inheritedInterior,
      trunkColor: inheritedTrunkColor,
      trunkTexture: inheritedTrunkTexture,
    },
    { anchored: false }
  );
  if (bottom) bottom.mesh.userData.sellable = true;
  if (top) top.mesh.userData.sellable = true;

  if (top && leafMesh && leafBody) {
    top.mesh.userData.leafMesh = leafMesh;
    top.mesh.userData.leafBody = leafBody;
    const newTopY = top.mesh.userData.trunk.topY;
    const leafR = leafMesh.geometry.parameters.radius;
    leafMesh.position.set(x, newTopY + leafR * 0.35, z);
    leafBody.position.set(leafMesh.position.x, leafMesh.position.y, leafMesh.position.z);
    leafBody.velocity.setZero();
    leafBody.angularVelocity.setZero();
    world.addBody(leafBody);
    planetMeshParent().add(leafMesh);
    dynamicPairs.push({ mesh: leafMesh, body: leafBody });
  }

  if (bottom) bottom.mesh.userData.leafMesh = null;
  if (top) {
    if (!leafMesh || !leafBody) {
      top.mesh.userData.leafMesh = null;
      top.mesh.userData.leafBody = null;
    }
  }
  if (procHolder && !top) disposeProceduralBranchHolder(procHolder);
  if (top) {
    if (procHolder) {
      reparentProcHolderPreservingWorld(procHolder, top.mesh);
      syncBranchCollidersOrCompoundForTrunk(top.mesh);
    } else {
      attachTreeBranchesForGame(top.mesh, logSkin, tid ^ 0x2468ace1, x, z);
    }
    money += 2;
    updateMoneyHud();
  }
  return true;
}

/** Scratch for {@link getCameraRay} — called several times per frame from hover/grab pickers. */
const _camRayOrigin = new THREE.Vector3();
const _camRayDir = new THREE.Vector3();
/** Returns shared scratch vectors — consume immediately (raycaster.set / Ray copy them), don't retain. */
function getCameraRay() {
  camera.getWorldDirection(_camRayDir);
  return { origin: _camRayOrigin.copy(camera.position), dir: _camRayDir };
}

const _uiPointerNdc = new THREE.Vector2();
/** Ray from crosshair when pointer locked, else from mouse over the canvas. */
function setViewRayForUiHover() {
  if (!camera || !renderer) return;
  if (document.pointerLockElement === document.body) {
    const { origin, dir } = getCameraRay();
    raycaster.set(origin, dir);
  } else {
    const rect = renderer.domElement.getBoundingClientRect();
    const x = ((lastPointerClient.x - rect.left) / rect.width) * 2 - 1;
    const y = -((lastPointerClient.y - rect.top) / rect.height) * 2 + 1;
    raycaster.setFromCamera(_uiPointerNdc.set(x, y), camera);
  }
}

function isSawmillKitMesh(mesh) {
  if (!mesh || mesh.userData.kind !== 'kitBox') return false;
  const k = mesh.userData.kitId;
  return k === 'sawmill' || k === undefined;
}

function rayPickKitBoxForUi(maxDist = 5.5) {
  setViewRayForUiHover();
  const meshes = dynamicPairs
    .filter((p) => p.mesh.userData.kind === 'kitBox')
    .map((p) => p.mesh);
  if (meshes.length === 0) return null;
  const hits = raycaster.intersectObjects(meshes, false);
  const h = hits[0];
  if (!h || h.distance > maxDist) return null;
  const body = h.object.userData.body;
  if (!body) return null;
  return { mesh: h.object, body, point: h.point };
}

function rayPickShopItemForHover(maxDist = 6) {
  setViewRayForUiHover();
  const meshes = dynamicPairs
    .filter((p) => p.mesh.userData.kind === 'shopItem')
    .map((p) => p.mesh);
  if (meshes.length === 0) return null;
  const hits = raycaster.intersectObjects(meshes, false);
  const h = hits[0];
  if (!h || h.distance > maxDist) return null;
  const id = h.object.userData.shopId;
  const def = SHOP_ITEMS[id];
  if (!def) return null;
  return { mesh: h.object, def, dist: h.distance };
}

function clampSawmillXY(v) {
  return Math.max(1, Math.min(3, Number(v) || 1));
}

/** Ray hits child meshes; control metadata lives on the arrow {@link THREE.Group}. */
function getSawmillControlFromIntersectedObject(obj) {
  let o = obj;
  while (o) {
    if (o.userData?.sawmillControl) return o.userData.sawmillControl;
    o = o.parent;
  }
  return null;
}

function rayPickSawmillControl(maxDist = 6) {
  if (!sawmillsRoot || !camera || !renderer) return null;
  setViewRayForUiHover();
  const hits = [];
  for (const s of sawmillsRoot.children) {
    const p = s.userData.sawmillSidePanel?.panel;
    if (!p) continue;
    const hh = raycaster.intersectObjects([p], true);
    for (const h of hh) {
      const ctrl = getSawmillControlFromIntersectedObject(h.object);
      if (!ctrl) continue;
      hits.push({ ...h, sawmill: s });
    }
  }
  hits.sort((a, b) => a.distance - b.distance);
  const h = hits[0];
  if (!h || h.distance > maxDist) return null;
  const ctrl = getSawmillControlFromIntersectedObject(h.object);
  if (!ctrl) return null;
  return { sawmill: h.sawmill, ctrl };
}

function tryUseSawmillControlWithE() {
  const pick = rayPickSawmillControl(6);
  if (!pick) return false;
  const { sawmill: s, ctrl } = pick;
  if (ctrl.axis === 'x') s.userData.plankX = clampSawmillXY((s.userData.plankX ?? 1) + ctrl.delta);
  if (ctrl.axis === 'y') s.userData.plankY = clampSawmillXY((s.userData.plankY ?? 1) + ctrl.delta);
  updateSawmillSidePanel(s);
  saveGame();
  return true;
}

function tryClickSawmillSidePanel() {
  if (!sawmillsRoot || !camera || !renderer) return false;
  setViewRayForUiHover();
  const hits = [];
  for (const s of sawmillsRoot.children) {
    if (!s.userData.sawmillSidePanel) continue;
    const p = s.userData.sawmillSidePanel.panel;
    const hh = raycaster.intersectObjects([p], true);
    for (const h of hh) {
      const ctrl = getSawmillControlFromIntersectedObject(h.object);
      if (!ctrl) continue;
      hits.push({ ...h, sawmill: s });
    }
  }
  hits.sort((a, b) => a.distance - b.distance);
  const h = hits[0];
  if (!h || h.distance > 6) return false;
  const ctrl = getSawmillControlFromIntersectedObject(h.object);
  if (!ctrl) return false;
  const s = h.sawmill;
  if (ctrl.axis === 'x') s.userData.plankX = clampSawmillXY((s.userData.plankX ?? 1) + ctrl.delta);
  if (ctrl.axis === 'y') s.userData.plankY = clampSawmillXY((s.userData.plankY ?? 1) + ctrl.delta);
  updateSawmillSidePanel(s);
  saveGame();
  return true;
}

function rayPickWood(maxDist = 8) {
  const { origin, dir } = getCameraRay();
  raycaster.set(origin, dir);
  const meshes = dynamicPairs
    .filter((p) => {
      const k = p.mesh.userData.kind;
      return (
        k === 'log' ||
        k === 'trunk' ||
        k === 'plank' ||
        k === 'shopItem' ||
        k === 'kitBox'
      );
    })
    .map((p) => p.mesh);
  const branchMeshes = treeBranchStaticColliderList.map((e) => e.branchMesh);
  const branchPickSeen = new Set(branchMeshes);
  const compoundBranchMeshes = [];
  for (const p of dynamicPairs) {
    const h = p.mesh.getObjectByName('procedural_branches_holder');
    if (!h) continue;
    h.traverse((o) => {
      if (!o.isMesh || o.geometry?.type !== 'BoxGeometry') return;
      if (o.name.startsWith('branch_tip_leaf')) return;
      if (branchPickSeen.has(o)) return;
      branchPickSeen.add(o);
      compoundBranchMeshes.push(o);
    });
  }
  const all = meshes.concat(branchMeshes).concat(compoundBranchMeshes);
  if (all.length === 0) return null;
  const hits = raycaster.intersectObjects(all, false);
  hits.sort((a, b) => a.distance - b.distance);
  for (const h of hits) {
    if (!h || h.distance > maxDist) break;
    let body = h.object.userData.body;
    if (!body) {
      const host = h.object.userData.branchCompoundHost;
      if (host?.userData?.body) body = host.userData.body;
    }
    if (!body) continue;
    return { mesh: h.object, body, point: h.point };
  }
  return null;
}

function startGrab(pick) {
  if (pick.body.mass < 1e-8) return;
  grabTarget = pick.body;
  grabTarget.wakeUp();
  /** Depth where you clicked — hold stays on the crosshair ray at this distance (no teleport “down”). */
  tmpV.set(
    pick.point.x - camera.position.x,
    pick.point.y - camera.position.y,
    pick.point.z - camera.position.z
  );
  grabHoldDistance = Math.max(0.55, Math.min(9, tmpV.length()));
  const inv = new CANNON.Quaternion();
  inv.copy(grabTarget.quaternion);
  inv.inverse(inv);
  tmpC.set(pick.point.x, pick.point.y, pick.point.z);
  tmpC.vsub(grabTarget.position, tmpC);
  inv.vmult(tmpC, grabLocal);
}

function endGrab() {
  grabTarget = null;
}

const GRAB_RAY_HINT_DIST = 4.5;

/** Kinematic-style carry: grabbed mesh point stays on the view ray at grabHoldDistance. */
function snapGrabbedWood() {
  if (!grabTarget || gamePaused) return;
  grabTarget.wakeUp();
  tmpV.copy(camera.getWorldDirection(tmpV)).multiplyScalar(grabHoldDistance);
  const px = camera.position.x + tmpV.x;
  const py = camera.position.y + tmpV.y;
  const pz = camera.position.z + tmpV.z;

  const worldOff = new CANNON.Vec3();
  grabTarget.quaternion.vmult(grabLocal, worldOff);
  grabTarget.position.set(px - worldOff.x, py - worldOff.y, pz - worldOff.z);
  grabTarget.velocity.set(0, 0, 0);
  grabTarget.angularVelocity.set(0, 0, 0);
}

function applyGrabRotateFromMouse(movementX, movementY) {
  if (!grabTarget || !camera) return;
  const sens = 0.0026 * mouseSensMul;
  const ax = tmpV.set(1, 0, 0).applyQuaternion(camera.quaternion);
  const ay = tmpV2.set(0, 1, 0).applyQuaternion(camera.quaternion);
  const qx = new THREE.Quaternion().setFromAxisAngle(ax, -movementY * sens);
  const qy = new THREE.Quaternion().setFromAxisAngle(ay, -movementX * sens);
  const q = new THREE.Quaternion(
    grabTarget.quaternion.x,
    grabTarget.quaternion.y,
    grabTarget.quaternion.z,
    grabTarget.quaternion.w
  );
  q.premultiply(qy);
  q.premultiply(qx);
  q.normalize();
  grabTarget.quaternion.set(q.x, q.y, q.z, q.w);
}

function updateGrabHint() {
  const ch = document.getElementById('crosshair');
  const hint = document.getElementById('grab-hint');
  if (!ch) return;
  if (sawmillPlacementMode || blueprintPlacementMode) {
    ch.classList.remove('grab-ready');
    if (hint) hint.style.display = 'none';
    return;
  }
  if (gamePaused || document.pointerLockElement !== document.body || grabTarget) {
    ch.classList.remove('grab-ready');
    if (hint) hint.style.display = 'none';
    return;
  }
  const pick = rayPickWood(GRAB_RAY_HINT_DIST);
  if (pick && pick.body.mass > 1e-6) {
    ch.classList.add('grab-ready');
    if (hint) hint.style.display = 'block';
  } else {
    ch.classList.remove('grab-ready');
    if (hint) hint.style.display = 'none';
  }
}

function clearChopState() {
  if (chopBandMesh) {
    chopBandMesh.removeFromParent();
    chopBandMesh.geometry?.dispose();
    chopBandMesh.material?.dispose();
  }
  chopBandMesh = null;
  chopBandGeo = null;
  chopState = null;
}

function ensureChopVisuals() {
  if (!chopBandMesh) {
    chopBandGeo = new THREE.BufferGeometry();
    const pos = new Float32Array(18);
    const nor = new Float32Array(18);
    const posAttr = new THREE.BufferAttribute(pos, 3);
    const norAttr = new THREE.BufferAttribute(nor, 3);
    posAttr.setUsage(THREE.DynamicDrawUsage);
    norAttr.setUsage(THREE.DynamicDrawUsage);
    chopBandGeo.setAttribute('position', posAttr);
    chopBandGeo.setAttribute('normal', norAttr);
    chopBandMesh = new THREE.Mesh(
      chopBandGeo,
      new THREE.MeshBasicMaterial({
        color: 0x000000,
        side: THREE.DoubleSide,
        depthTest: true,
        depthWrite: false,
        toneMapped: false,
        polygonOffset: true,
        polygonOffsetFactor: -10,
        polygonOffsetUnits: -10,
      })
    );
    chopBandMesh.name = 'chop_progress_band';
    chopBandMesh.userData.skipBloomMaterialSwap = true;
    chopBandMesh.frustumCulled = false;
    chopBandMesh.renderOrder = 10;
  }
}

/**
 * Chop progress: black strip on the wood at the cut height, in **mesh local space** (child of the trunk
 * / branch mesh) so it follows the tree and picks the ±X / ±Z side that faces the camera in that space.
 */
function rebuildChopBandGeometry() {
  if (!chopState) return;
  if (gamePaused) {
    if (chopBandMesh) chopBandMesh.visible = false;
    return;
  }
  ensureChopVisuals();
  const mesh = chopState.mesh;
  const committedSwings = chopState.committedSwings | 0;
  if (committedSwings <= 0) {
    if (chopBandMesh) chopBandMesh.visible = false;
    return;
  }

  if (chopBandMesh.parent !== mesh) {
    chopBandMesh.removeFromParent();
    scene.remove(chopBandMesh);
    mesh.add(chopBandMesh);
    chopBandMesh.position.set(0, 0, 0);
    chopBandMesh.quaternion.identity();
    chopBandMesh.scale.set(1, 1, 1);
  }

  const geo = chopBandMesh.geometry;
  const posAttr = geo?.attributes?.position;
  const norAttr = geo?.attributes?.normal;
  if (!posAttr?.array || !norAttr?.array) return;

  const halfW = Number.isFinite(chopState.halfW) && chopState.halfW > 1e-5 ? chopState.halfW : 0.07;
  const bandFrac = Math.min(1, Math.max(1 / CHOP_SWINGS, committedSwings / CHOP_SWINGS));

  mesh.updateMatrixWorld(true);

  const tr = mesh.userData?.trunk;
  const gh = mesh.geometry?.parameters?.height;
  const trunkH = gh != null ? gh : tr ? tr.topY - tr.bottomY : 4;
  const bandH = Math.max(0.04, Math.min(0.1, trunkH / 48));
  const bh = bandH * 0.5;
  const OUT = 0.032;

  let cutLy;
  if (chopState.trunkMode === 'vertical') {
    _chopSurfCenter.set(chopState.x, chopState.cutY, chopState.z);
    mesh.worldToLocal(_chopSurfCenter);
    cutLy = _chopSurfCenter.y;
  } else {
    cutLy = chopState.cutLocalY;
  }

  mesh.worldToLocal(_chopToCam.copy(camera.position));
  _chopNFlat.copy(_chopToCam).sub(_chopSurfCenter.set(0, cutLy, 0));
  if (_chopNFlat.lengthSq() < 1e-10) _chopNFlat.set(0, 0, 1);
  _chopNFlat.normalize();

  const ax = Math.abs(_chopNFlat.x);
  const az = Math.abs(_chopNFlat.z);
  const Q = chopBandQuad;
  const u0 = -halfW;
  const u1 = -halfW + 2 * halfW * bandFrac;
  let lx;
  let ly;
  let lz;

  if (ax >= az) {
    const sx = _chopNFlat.x >= 0 ? 1 : -1;
    lx = sx * (halfW + OUT);
    Q.a.set(lx, cutLy - bh, u0);
    Q.b.set(lx, cutLy + bh, u0);
    Q.c.set(lx, cutLy + bh, u1);
    Q.d.set(lx, cutLy - bh, u1);
    lx = sx;
    ly = 0;
    lz = 0;
  } else {
    const sz = _chopNFlat.z >= 0 ? 1 : -1;
    lz = sz * (halfW + OUT);
    Q.a.set(u0, cutLy - bh, lz);
    Q.b.set(u0, cutLy + bh, lz);
    Q.c.set(u1, cutLy + bh, lz);
    Q.d.set(u1, cutLy - bh, lz);
    lx = 0;
    ly = 0;
    lz = sz;
  }

  const pos = posAttr.array;
  const nor = norAttr.array;
  let i = 0;
  function wv(v, nx, ny, nz) {
    pos[i] = v.x;
    pos[i + 1] = v.y;
    pos[i + 2] = v.z;
    nor[i] = nx;
    nor[i + 1] = ny;
    nor[i + 2] = nz;
    i += 3;
  }
  wv(Q.a, lx, ly, lz);
  wv(Q.b, lx, ly, lz);
  wv(Q.c, lx, ly, lz);
  wv(Q.a, lx, ly, lz);
  wv(Q.c, lx, ly, lz);
  wv(Q.d, lx, ly, lz);

  posAttr.needsUpdate = true;
  norAttr.needsUpdate = true;
  geo.computeBoundingSphere();
  chopBandMesh.visible = true;
}

function updateChopping(dt) {
  if (gamePaused) {
    if (chopBandMesh) chopBandMesh.visible = false;
    return;
  }
  if (!chopState) return;
  if (!toolAllowsChop()) {
    clearChopState();
    return;
  }
  const m = chopState.mesh;
  const compoundHost = m?.userData?.branchCompoundHost;
  const chopTargetValid =
    dynamicPairs.some((p) => p.mesh === m) ||
    (compoundHost && dynamicPairs.some((p) => p.mesh === compoundHost)) ||
    treeBranchStaticColliderList.some((e) => e.branchMesh === m) ||
    (m?.userData?.kind === 'treeBranch' &&
      (compoundHost || m.userData.branchHostTrunk || m.userData.body));
  if (!chopTargetValid) {
    clearChopState();
    return;
  }
  if ((chopState.committedSwings | 0) > 0) {
    rebuildChopBandGeometry();
  }
  if (chopState.committedSwings >= CHOP_SWINGS) {
    if (chopBandMesh) chopBandMesh.removeFromParent();
    if (chopState.treeBranch) {
      splitOrientedTreeBranch(chopState.mesh, chopState.body, chopState.cutLocalY);
    } else if (chopState.trunkMode === 'oriented') {
      splitOrientedBoxLog(chopState.mesh, chopState.body, chopState.cutLocalY);
    } else {
      const tr = chopState.mesh.userData.trunk;
      const trunkSpan = tr?.topY != null && tr?.bottomY != null ? tr.topY - tr.bottomY : 0;
      const capBand =
        !!tr &&
        trunkSpan >= 1.1 &&
        chopState.cutY >= tr.topY - 0.26 &&
        chopState.cutY <= tr.topY - 0.03 &&
        chopState.mesh.getObjectByName('procedural_branches_holder');
      if (capBand) {
        severAllTreeBranchesFromTrunk(chopState.mesh);
        money += 2;
        updateMoneyHud();
      } else {
        splitTrunkAtWorldY(chopState.mesh, chopState.body, chopState.cutY);
      }
    }
    clearChopState();
  }
}

function toolAllowsChop() {
  return hotbarSelected >= 0 && hotbarSlots[hotbarSelected] === 'axe';
}

function pickIsChoppableWood(pick) {
  if (!pick?.mesh) return false;
  const m = pick.mesh;
  if (m.userData?.kind === 'treeBranch') return true;
  const compoundBark = m.geometry?.type === 'BoxGeometry' && !!m.userData.branchCompoundHost;
  if (compoundBark) return true;
  return !!m.userData.trunk;
}

function triggerAxeChopSwing() {
  if (!axeSwingPivot || !toolAllowsChop()) return;
  axeChopSwingT = AXE_CHOP_SWING_DURATION;
}

function updateAxeChopSwing(dt) {
  if (!axeSwingPivot) return;
  if (axeChopSwingT <= 0) {
    axeSwingPivot.rotation.x = 0;
    return;
  }
  axeChopSwingT = Math.max(0, axeChopSwingT - dt);
  const u = 1 - axeChopSwingT / AXE_CHOP_SWING_DURATION;
  axeSwingPivot.rotation.x = Math.sin(u * Math.PI) * 1.18;
}

function showToolHint(html, ms = 4200) {
  const el = document.getElementById('tool-hint');
  if (!el) return;
  el.innerHTML = html;
  el.style.display = 'block';
  toolHintUntil = performance.now() + ms;
}

function updateToolHintVisibility() {
  const el = document.getElementById('tool-hint');
  if (!el || el.style.display === 'none') return;
  if (performance.now() > toolHintUntil) {
    el.style.display = 'none';
    el.textContent = '';
  }
}

function grantShopItemToHotbar(shopId) {
  if (shopId !== 'axe') return true;
  for (let i = 0; i < HOTBAR_SIZE; i++) {
    if (hotbarSlots[i] == null) {
      hotbarSlots[i] = 'axe';
      syncHotbarUi();
      return true;
    }
  }
  showClerkBubble('Your hotbar is full — make an empty slot, then buy again.', 4500);
  return false;
}

function normalizeHotbarFromSave(data) {
  if (!data) return;
  if (Array.isArray(data.hotbar) && data.hotbar.length === HOTBAR_SIZE) {
    hotbarSlots = data.hotbar.map((x) => (x === 'axe' ? 'axe' : null));
  } else {
    hotbarSlots = Array(HOTBAR_SIZE).fill(null);
  }
  const sel = Number(data.hotbarSel);
  if (sel === -1) hotbarSelected = -1;
  else
    hotbarSelected =
      Number.isFinite(sel) && sel >= 0 && sel < HOTBAR_SIZE ? Math.floor(sel) : 0;
  syncHotbarUi();
}

function syncHotbarUi() {
  const root = document.getElementById('hotbar');
  if (!root) return;
  const inv = document.getElementById('inventory-hotbar-mirror');
  if (inv && inv.children.length < HOTBAR_SIZE) {
    inv.innerHTML = '';
    for (let i = 0; i < HOTBAR_SIZE; i++) {
      const d = document.createElement('div');
      d.className = 'inv-slot';
      inv.appendChild(d);
    }
  }
  root.querySelectorAll('.hb-slot').forEach((slot, i) => {
    const id = hotbarSlots[i];
    slot.classList.toggle('hb-selected', i === hotbarSelected && hotbarSelected >= 0);
    slot.classList.toggle('hb-has-axe', id === 'axe');
    slot.classList.toggle('hb-empty', id == null);
    const icon = slot.querySelector('.hb-icon');
    if (icon) {
      icon.textContent = id === 'axe' ? '🪓' : '';
      icon.setAttribute('aria-label', id === 'axe' ? 'Starter axe' : 'Empty');
    }
    const lab = slot.querySelector('.hb-key');
    if (lab) lab.textContent = String(i + 1);
  });
  if (inv) {
    inv.querySelectorAll('.inv-slot').forEach((row, i) => {
      const id = hotbarSlots[i];
      row.textContent = id === 'axe' ? `Slot ${i + 1}: Starter axe` : `Slot ${i + 1}: —`;
    });
  }
}

function setHotbarIndex(i) {
  if (i < -1 || i >= HOTBAR_SIZE) return;
  hotbarSelected = i;
  syncHotbarUi();
  if (devFlyMode && i >= 0 && hotbarSlots[i] === 'axe') snapshotFreecamAxeFromHeldView();
}

function toggleInventoryPanel() {
  inventoryPanelOpen = !inventoryPanelOpen;
  const ov = document.getElementById('inventory-overlay');
  if (ov) {
    ov.classList.toggle('inv-open', inventoryPanelOpen);
    ov.setAttribute('aria-hidden', inventoryPanelOpen ? 'false' : 'true');
  }
  document.getElementById('btn-inv-toggle')?.classList.toggle('inv-active', inventoryPanelOpen);
  if (inventoryPanelOpen) document.exitPointerLock?.();
}

function tryChop() {
  const pick = rayPickWood(6);
  if (!pick) {
    clearChopState();
    return;
  }
  const pickIsCompoundBark =
    pick.mesh.geometry?.type === 'BoxGeometry' && !!pick.mesh.userData.branchCompoundHost;
  if (pick.mesh.userData.kind === 'treeBranch' || pickIsCompoundBark) {
    if (!toolAllowsChop()) {
      clearChopState();
      return;
    }
    endGrab();
    const g = pick.mesh.geometry.parameters;
    if (g.width == null) {
      clearChopState();
      return;
    }
    const H = g.height ?? 1;
    const halfH = H / 2;
    pick.mesh.updateMatrixWorld(true);
    tmpV.copy(pick.point);
    pick.mesh.worldToLocal(tmpV);
    let cutLocalY = tmpV.y;
    const lo = -halfH + CHOP_EDGE_INSET;
    const hi = halfH - CHOP_EDGE_INSET;
    cutLocalY = Math.min(Math.max(cutLocalY, lo + 0.03), hi - 0.03);
    if (cutLocalY - (-halfH) < MIN_TRUNK_PIECE || halfH - cutLocalY < MIN_TRUNK_PIECE) {
      clearChopState();
      return;
    }
    const halfW = g.width / 2;
    const same =
      chopState &&
      chopState.treeBranch &&
      chopState.mesh === pick.mesh &&
      Math.abs(chopState.cutLocalY - cutLocalY) < 0.28;
    if (same) {
      if (chopState.committedSwings >= CHOP_SWINGS) return;
      chopState.committedSwings += 1;
      ensureChopVisuals();
      rebuildChopBandGeometry();
    } else {
      clearChopState();
      chopState = {
        mesh: pick.mesh,
        body: pick.body,
        trunkMode: 'oriented',
        cutLocalY,
        cutY: 0,
        halfW,
        x: 0,
        z: 0,
        committedSwings: 1,
        treeBranch: true,
      };
      ensureChopVisuals();
      rebuildChopBandGeometry();
    }
    return;
  }
  if (!pick.mesh.userData.trunk) {
    clearChopState();
    return;
  }
  if (!toolAllowsChop()) {
    clearChopState();
    const hasAxe = hotbarSlots.some((x) => x === 'axe');
    if (hasAxe && hotbarSelected < 0) {
      showToolHint(
        'Select a hotbar slot with your <strong>axe</strong> (press <kbd>1</kbd>–<kbd>9</kbd> or scroll the wheel).',
        5200
      );
    } else if (hasAxe && hotbarSelected >= 0 && hotbarSlots[hotbarSelected] !== 'axe') {
      showToolHint(
        'Switch to the hotbar slot that has your <strong>starter axe</strong> (press <kbd>1</kbd>–<kbd>9</kbd>).',
        5200
      );
    } else {
      showToolHint(
        'Equip your <strong>starter axe</strong>: buy it from the wall shelf, check out with the clerk, then press <kbd>1</kbd>–<kbd>9</kbd> to select the slot it landed in.',
        6200
      );
    }
    return;
  }
  endGrab();
  const t = pick.mesh.userData.trunk;
  const vertical = isWoodLengthAxisVertical(pick.mesh);
  const hasVerticalBounds = t.bottomY != null && t.topY != null;
  /** World-Y split only for anchored stumps; loose logs carry stale trunk x/z/y and would teleport. */
  const anchored = pick.mesh.userData.trunkAnchored === true;
  const trunkMode = vertical && hasVerticalBounds && anchored ? 'vertical' : 'oriented';

  let cutY = 0;
  let cutLocalY = 0;
  let halfW = t.halfW ?? pick.mesh.geometry.parameters.width / 2;
  let x = t.x ?? pick.mesh.position.x;
  let z = t.z ?? pick.mesh.position.z;

  if (trunkMode === 'vertical') {
    const { bottomY, topY } = t;
    halfW = t.halfW ?? pick.mesh.geometry?.parameters?.width / 2 ?? 0.07;
    x = t.x;
    z = t.z;
    const lo = bottomY + CHOP_EDGE_INSET;
    const hi = topY - CHOP_EDGE_INSET;
    cutY = Math.min(Math.max(pick.point.y, lo + 0.03), hi - 0.03);
    if (cutY - bottomY < MIN_TRUNK_PIECE || topY - cutY < MIN_TRUNK_PIECE) {
      clearChopState();
      return;
    }
  } else {
    const g = pick.mesh.geometry.parameters;
    if (g.width == null && g.radiusTop != null) {
      clearChopState();
      return;
    }
    const H = g.height ?? 1;
    const halfH = H / 2;
    const loc = worldPointToBodyLocal(pick.body, pick.point.x, pick.point.y, pick.point.z);
    cutLocalY = loc.y;
    const lo = -halfH + CHOP_EDGE_INSET;
    const hi = halfH - CHOP_EDGE_INSET;
    cutLocalY = Math.min(Math.max(cutLocalY, lo + 0.03), hi - 0.03);
    if (cutLocalY - (-halfH) < MIN_TRUNK_PIECE || halfH - cutLocalY < MIN_TRUNK_PIECE) {
      clearChopState();
      return;
    }
  }

  const same =
    chopState &&
    !chopState.treeBranch &&
    chopState.mesh === pick.mesh &&
    chopState.trunkMode === trunkMode &&
    (trunkMode === 'vertical'
      ? Math.abs(chopState.cutY - cutY) < 0.28
      : Math.abs(chopState.cutLocalY - cutLocalY) < 0.28);

  if (same) {
    if (chopState.committedSwings >= CHOP_SWINGS) return;
    chopState.committedSwings += 1;
    ensureChopVisuals();
    rebuildChopBandGeometry();
  } else {
    clearChopState();
    chopState = {
      mesh: pick.mesh,
      body: pick.body,
      trunkMode,
      cutY,
      cutLocalY,
      halfW,
      x,
      z,
      committedSwings: 1,
    };
    ensureChopVisuals();
    rebuildChopBandGeometry();
  }
}

function trySellLogs() {
  for (let i = dynamicPairs.length - 1; i >= 0; i--) {
    const { mesh, body } = dynamicPairs[i];
    const pPlanet = mesh.userData.planetId ?? body.userData?.planetId;
    if (pPlanet != null && pPlanet !== runtimePlanetId) continue;
    const k = mesh.userData.kind;
    if (k === 'plank') {
      if (!mesh.userData.sellable) continue;
    } else if (k !== 'log' && !(k === 'trunk' && mesh.userData.sellable)) {
      continue;
    }
    const p = body.position;
    /** `sellCenterWorld` is the surface-projected point; `sellCenter` is flat authoring space. */
    const dx = p.x - sellCenterWorld.x;
    const dy = p.y - sellCenterWorld.y;
    const dz = p.z - sellCenterWorld.z;
    if (spherePhysicsActive) {
      if (dx * dx + dy * dy + dz * dz > sellRadius * sellRadius) continue;
    } else {
      if (dx * dx + dz * dz > sellRadius * sellRadius) continue;
      if (p.y > 2.5) continue;
    }
    if (body.velocity.lengthSquared() > 2.5) continue;
    const g = mesh.geometry.parameters;
    if (k === 'plank') {
      const W = Number(g.width) || 0.05;
      const H = Number(g.height) || 0.05;
      const D = Number(g.depth) || 0.05;
      const vol = W * H * D;
      const longest = Math.max(W, H, D);
      money += Math.round(5 + vol * 420 + longest * 3.5);
    } else {
      const gh = g.height ?? 1;
      let w = 0.07;
      if (g.width != null) w = g.width / 2;
      else if (g.radiusTop != null) w = g.radiusTop;
      const wMul = mesh.userData.woodWeight ?? 1;
      money += Math.round(computeWoodSellValue(gh, w) * wMul);
    }
    updateMoneyHud();
    unregisterDynamic(mesh, body);
  }
}

function formatMoneyShort(n) {
  const v = Math.floor(Math.abs(n));
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
  if (v >= 10_000) return `${(v / 1000).toFixed(1).replace(/\.0$/, '')}K`;
  if (v >= 1000) return `${(v / 1000).toFixed(1).replace(/\.0$/, '')}K`;
  return v.toLocaleString('en-US');
}

const LAND_PRICES = [500, 2500, 8000, 20000, 45000];

/** Four small lots around the world (center XZ). Base footprint 10×10 m (2× prior 5×5). */
const LAND_PLOT_SLOTS = [
  { x: 14, z: -8, label: 'East meadow' },
  { x: -30, z: 2, label: 'West grove' },
  { x: 8, z: 20, label: 'South field' },
  { x: 2, z: -24, label: 'North yard' },
];

const LAND_PLOT_BASE = 10;
const LAND_SCALE_PER_UPGRADE = 0.24;
/** Match {@link createLandPlotsInWorld} soil mesh: BoxGeometry height + local Y center. */
const LAND_SOIL_THICK = 0.07;
const LAND_SOIL_CENTER_Y_LOCAL = 0.045;
/** Tall static prism under each lot so wood meets physics at the same height as the soil top. */
const LAND_PLOT_COLLIDER_BASE_Y = -12;

function resolveLandPlotSitesFromMap() {
  const slice = getActiveMapWorldSlice();
  const sites = slice?.plotSites;
  if (Array.isArray(sites) && sites.length > 0) {
    return sites.map((p, i) => ({
      id: String(p.id || `plot_${i}`),
      label: String(p.label || `Lot ${i + 1}`),
      x: Number(p.x) || 0,
      y: Number(p.y) || 0,
      z: Number(p.z) || 0,
      ry: Number(p.ry) || 0,
      w: Math.max(2, Math.min(48, Number(p.w) || LAND_PLOT_BASE)),
      d: Math.max(2, Math.min(48, Number(p.d) || LAND_PLOT_BASE)),
    }));
  }
  return LAND_PLOT_SLOTS.map((s, i) => ({
    id: `default_${i}`,
    label: s.label,
    x: s.x,
    z: s.z,
    y: 0,
    ry: 0,
    w: LAND_PLOT_BASE,
    d: LAND_PLOT_BASE,
  }));
}

function landPlotEntryOwned(entry, index) {
  if (landPlotSlotId) return entry.slotId === landPlotSlotId;
  return landPlots >= 1 && landPlotId != null && index === landPlotId;
}

function getNextLandPrice() {
  const i = Math.min(landPlots, LAND_PRICES.length - 1);
  return LAND_PRICES[i];
}

function getOwnedLandScale() {
  if (landPlots < 1) return 1;
  return 1 + (landPlots - 1) * LAND_SCALE_PER_UPGRADE;
}

function createLandPlotsInWorld() {
  landPlotSites = resolveLandPlotSitesFromMap();
  landPlotsRoot = new THREE.Group();
  landPlotsRoot.name = 'LandPlots';
  landPlotsRoot.userData.planetId = runtimePlanetId;
  landPlotEntries = [];
  const soilMat = new THREE.MeshStandardMaterial({
    color: 0x6b4423,
    roughness: 0.92,
    metalness: 0.02,
  });
  const ringMat = new THREE.MeshBasicMaterial({
    color: 0x3d2918,
    transparent: true,
    opacity: 0.85,
    depthWrite: false,
  });
  for (let i = 0; i < landPlotSites.length; i++) {
    const slot = landPlotSites[i];
    const w = slot.w;
    const d = slot.d;
    const grp = new THREE.Group();
    grp.name = `LandPlot_${slot.id ?? i}`;
    /** Sphere worlds: sit the plot on the surface at its map coord instead of at the core. */
    placeOriginGroupOnPlanetSurface(grp, slot.x, slot.y, slot.z, slot.ry, landPlotsRoot);
    const soil = new THREE.Mesh(new THREE.BoxGeometry(w, LAND_SOIL_THICK, d), soilMat.clone());
    soil.position.y = LAND_SOIL_CENTER_Y_LOCAL;
    soil.castShadow = true;
    soil.receiveShadow = true;
    const rm = Math.max(w, d) * 0.5;
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(rm * 0.96, rm * 1.04, 48),
      ringMat.clone()
    );
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = 0.09;
    ring.renderOrder = 1;
    grp.add(soil, ring);
    /** `placeOriginGroupOnPlanetSurface` already parented `grp` (directly, or via its anchor). */
    const plotCollider = new CANNON.Body({ mass: 0, material: gameGroundPhyMat ?? undefined });
    plotCollider.userData = plotCollider.userData || {};
    plotCollider.userData.planetId = runtimePlanetId;
    world.addBody(plotCollider);
    landPlotEntries.push({ group: grp, soil, ring, slotId: slot.id, plotCollider });
  }
  planetMeshParent().add(landPlotsRoot);
  syncLandPlotVisuals();
}

function syncLandPlotVisuals() {
  if (!landPlotEntries.length) return;
  for (let i = 0; i < landPlotEntries.length; i++) {
    const entry = landPlotEntries[i];
    const { group, soil, ring } = entry;
    const owned = landPlotEntryOwned(entry, i);
    const showcasePick = bobLandShowcase && landShowcaseIndex === i;
    soil.material.transparent = false;
    soil.material.opacity = 1;
    if (landPlots < 1) {
      group.scale.set(1, 1, 1);
      soil.material.color.setHex(showcasePick ? 0x8b5a2b : 0x6b4423);
      ring.material.color.setHex(showcasePick ? 0xffcc66 : 0x3d2918);
      ring.material.opacity = showcasePick ? 1 : 0.75;
      continue;
    }
    if (owned) {
      const s = getOwnedLandScale();
      group.scale.set(s, 1, s);
      soil.material.color.setHex(0x9a6a2a);
      ring.material.color.setHex(0x5c3d1a);
      ring.material.opacity = 0.9;
    } else {
      group.scale.set(1, 1, 1);
      soil.material.color.setHex(0x4a3528);
      soil.material.transparent = true;
      soil.material.opacity = 0.45;
      ring.material.color.setHex(0x2a1a10);
      ring.material.opacity = 0.35;
    }
  }
  syncLandPlotColliderBodies();
}

function syncLandPlotColliderBodies() {
  if (!world || landPlotEntries.length === 0) return;
  for (let i = 0; i < landPlotEntries.length; i++) {
    const entry = landPlotEntries[i];
    const body = entry.plotCollider;
    const slot = landPlotSites[i];
    if (!body || !slot) continue;
    const owned = landPlotEntryOwned(entry, i);
    const s = landPlots < 1 ? 1 : owned ? getOwnedLandScale() : 1;
    const w = slot.w * s;
    const d = slot.d * s;
    const ry = Number(slot.ry) || 0;
    const soilTop = slot.y + LAND_SOIL_CENTER_Y_LOCAL + LAND_SOIL_THICK * 0.5;
    const pillarTop = soilTop + 0.015;
    const pillarBase = LAND_PLOT_COLLIDER_BASE_Y;
    const cy = (pillarTop + pillarBase) * 0.5;
    const halfY = (pillarTop - pillarBase) * 0.5;
    /** Sphere worlds: the pillar rises along the surface normal at the plot's map coord, not +Y. */
    if (planetSurfaceAnchorTransform(slot.x, slot.z, _surfAnchorPos, _surfAnchorQuat)) {
      _plotYawQuat.setFromAxisAngle(_SURF_LOCAL_UP, ry);
      _plotPoseQuat.copy(_surfAnchorQuat).multiply(_plotYawQuat);
      _plotUpVec.set(0, 1, 0).applyQuaternion(_surfAnchorQuat);
      body.position.set(
        _surfAnchorPos.x + _plotUpVec.x * cy,
        _surfAnchorPos.y + _plotUpVec.y * cy,
        _surfAnchorPos.z + _plotUpVec.z * cy
      );
      body.quaternion.set(_plotPoseQuat.x, _plotPoseQuat.y, _plotPoseQuat.z, _plotPoseQuat.w);
    } else {
      body.position.set(slot.x, cy, slot.z);
      body.quaternion.setFromEuler(0, ry, 0, 'XYZ');
    }
    while (body.shapes.length) body.removeShape(body.shapes[body.shapes.length - 1]);
    body.addShape(new CANNON.Box(new CANNON.Vec3(w * 0.5, halfY, d * 0.5)));
    body.updateMassProperties();
  }
}

function setLandShowcasePanel() {
  const panel = document.getElementById('land-showcase-panel');
  const labelEl = document.getElementById('land-showcase-name');
  const idxEl = document.getElementById('land-showcase-index');
  const priceBtn = document.getElementById('land-purchase-btn');
  if (!panel || !labelEl || !idxEl || !priceBtn) return;
  const slot = landPlotSites[landShowcaseIndex];
  if (!slot) return;
  labelEl.textContent = slot.label;
  idxEl.textContent = `${landShowcaseIndex + 1} / ${landPlotSites.length}`;
  const p = getNextLandPrice();
  priceBtn.textContent = `Purchase — $${p.toLocaleString('en-US')}`;
  priceBtn.disabled = money < p;
}

function enterBobLandShowcase() {
  bobLandShowcase = true;
  clerkBubbleUntil = 0;
  landShowcaseIndex = 0;
  document.exitPointerLock?.();
  const panel = document.getElementById('land-showcase-panel');
  if (panel) {
    panel.style.display = 'flex';
  }
  setLandShowcasePanel();
  syncLandPlotVisuals();
  setNpcBubbleText(
    'Use <strong>&lt;</strong> and <strong>&gt;</strong> to preview each lot. Click <strong>Purchase</strong> to buy the one shown. Press <kbd>E</kbd> to cancel.'
  );
}

function exitBobLandShowcase() {
  bobLandShowcase = false;
  const panel = document.getElementById('land-showcase-panel');
  if (panel) panel.style.display = 'none';
  syncLandPlotVisuals();
}

function tryPurchaseShowcasedPlot() {
  const p = getNextLandPrice();
  if (money < p) {
    setLandShowcasePanel();
    return;
  }
  money -= p;
  const site = landPlotSites[landShowcaseIndex];
  if (!site) return;
  landPlotSlotId = site.id;
  landPlotId = landShowcaseIndex;
  landPlots = 1;
  updateMoneyHud();
  exitBobLandShowcase();
  bobDialogStep = 2;
  setNpcBubbleText(
    `Sold! <strong>${escapeHtml(site.label)}</strong> is yours. Come back when you want to <strong>expand</strong> it!`
  );
  saveGame();
}

function wireLandShowcaseUi() {
  document.getElementById('land-prev')?.addEventListener('click', () => {
    if (!bobLandShowcase) return;
    landShowcaseIndex =
      (landShowcaseIndex - 1 + landPlotSites.length) % landPlotSites.length;
    setLandShowcasePanel();
    syncLandPlotVisuals();
  });
  document.getElementById('land-next')?.addEventListener('click', () => {
    if (!bobLandShowcase) return;
    landShowcaseIndex = (landShowcaseIndex + 1) % landPlotSites.length;
    setLandShowcasePanel();
    syncLandPlotVisuals();
  });
  document.getElementById('land-purchase-btn')?.addEventListener('click', () => {
    if (!bobLandShowcase || landPlots !== 0) return;
    tryPurchaseShowcasedPlot();
  });
}

function playerNearBob() {
  if (!playerBody) return false;
  const dx = playerBody.position.x - STORE.bobX;
  const dz = playerBody.position.z - STORE.bobZ;
  return Math.hypot(dx, dz) < BOB_INTERACT_R;
}

function playerNearClerk() {
  if (!playerBody) return false;
  const dx = playerBody.position.x - SHOP_CLERK.x;
  const dz = playerBody.position.z - SHOP_CLERK.z;
  return Math.hypot(dx, dz) < SHOP_CLERK.interactR;
}

function showClerkBubble(html, ms = 3800) {
  setNpcBubbleText(html);
  clerkBubbleUntil = performance.now() + ms;
  bobDialogOpen = false;
  bobDialogStep = 0;
  exitBobLandShowcase();
}

function countShopItemsWithId(shopId) {
  let n = 0;
  for (const { mesh } of dynamicPairs) {
    if (mesh.userData.kind === 'shopItem' && mesh.userData.shopId === shopId) n += 1;
  }
  return n;
}

/**
 * @param {number} wx
 * @param {number} wy
 * @param {number} wz
 * @param {string} shopId
 * @param {{ hw?: number; hh?: number; hd?: number } | null} [size]
 */
/**
 * Spawn one shop product. Coordinates arrive in **flat map space** (shelf-fixture layout), so on
 * sphere worlds they are projected onto the surface first — otherwise the stock spawns at the
 * planet core and immediately falls out of the world.
 */
function createShopProductAt(wx, wy, wz, shopId, size = null) {
  const def = SHOP_ITEMS[shopId];
  if (!def) return;
  if (countShopItemsWithId(shopId) > 0) return;
  flatMapPointToWorld(wx, wy, wz, _shopSpawnPos, _shopSpawnQuat);
  wx = _shopSpawnPos.x;
  wy = _shopSpawnPos.y;
  wz = _shopSpawnPos.z;
  const hw = size?.hw ?? 0.11;
  const hh = size?.hh ?? 0.09;
  const hd = size?.hd ?? 0.1;
  const geo = new THREE.BoxGeometry(hw * 2, hh * 2, hd * 2);
  const mat = new THREE.MeshStandardMaterial({ color: def.color, roughness: 0.78, metalness: 0.08 });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  mesh.position.set(wx, wy, wz);
  mesh.quaternion.copy(_shopSpawnQuat);
  planetMeshParent().add(mesh);
  const shape = new CANNON.Box(new CANNON.Vec3(hw, hh, hd));
  const body = new CANNON.Body({
    mass: shopId === 'sawmill' ? 0.55 : 0.42,
    material: woodPhyMat ?? undefined,
    linearDamping: 0.35,
    angularDamping: 0.45,
  });
  body.addShape(shape);
  body.position.set(wx, wy, wz);
  /** Rest flat on the shelf: align the box with the local surface, not world +Y. */
  body.quaternion.set(
    _shopSpawnQuat.x,
    _shopSpawnQuat.y,
    _shopSpawnQuat.z,
    _shopSpawnQuat.w
  );
  world.addBody(body);
  registerDynamic(mesh, body, { kind: 'shopItem' });
  mesh.userData.shopId = shopId;
}

/** Sawmill kit box on the counter in front of the clerk (Rob). */
function ensureSawmillCounterStock() {
  if (!shopCounterRig) return;
  if (countShopItemsWithId('sawmill') > 0) return;
  const local = new THREE.Vector3(0.52, 1.11, 0.28);
  shopCounterRig.localToWorld(local);
  createShopProductAt(local.x, local.y, local.z, 'sawmill', {
    hw: 0.17,
    hh: 0.12,
    hd: 0.19,
  });
}

function ensureShopShelfStock() {
  for (const slot of SHOP_SHELF_SLOTS) {
    if (countShopItemsWithId(slot.shopId) < 1) {
      createShopProductAt(slot.x, slot.y, slot.z, slot.shopId, slot.size ?? null);
    }
  }
  ensureSawmillCounterStock();
}

function getShopItemsOnCounter() {
  if (!shopCounterRig) return [];
  shopCounterRig.updateMatrixWorld(true);
  const inv = new THREE.Matrix4().copy(shopCounterRig.matrixWorld).invert();
  const out = [];
  for (const { mesh, body } of dynamicPairs) {
    if (mesh.userData.kind !== 'shopItem') continue;
    const id = mesh.userData.shopId;
    const def = SHOP_ITEMS[id];
    if (!def) continue;
    tmpV.set(body.position.x, body.position.y, body.position.z);
    tmpV.applyMatrix4(inv);
    if (Math.abs(tmpV.x) > SHOP_COUNTER_LOCAL_HALF_X || Math.abs(tmpV.z) > SHOP_COUNTER_LOCAL_HALF_Z) continue;
    if (tmpV.y < 0.95 || tmpV.y > 1.58) continue;
    if (body.velocity.lengthSquared() > 0.55) continue;
    out.push({ mesh, body, def });
  }
  return out;
}

function tryClerkCheckout() {
  if (!playerNearClerk()) return false;
  const items = getShopItemsOnCounter();
  if (items.length === 0) {
    showClerkBubble(
      'Grab something from the shelves along the wall and set it on the counter. When it’s still, press <kbd>E</kbd> to buy.',
      5200
    );
    return true;
  }
  let total = 0;
  for (const it of items) total += it.def.price;
  if (money < total) {
    showClerkBubble(
      `That’s <strong>$${total.toLocaleString('en-US')}</strong> — you need <strong>$${(
        total - money
      ).toLocaleString('en-US')}</strong> more.`,
      4200
    );
    return true;
  }
  money -= total;
  const sawIds = new Set(['sawmill']);
  const sawItems = items.filter((it) => sawIds.has(it.def.id));
  const rest = items.filter((it) => !sawIds.has(it.def.id));
  for (const it of rest) {
    if (!grantShopItemToHotbar(it.def.id)) {
      money += it.def.price;
      updateMoneyHud();
      showClerkBubble('Could not add that to your hotbar — refunded this item.', 4200);
      continue;
    }
    unregisterDynamic(it.mesh, it.body);
  }
  for (const it of sawItems) {
    unregisterDynamic(it.mesh, it.body);
    spawnSawmillKitBoxNearPlayer();
  }
  updateMoneyHud();
  ensureShopShelfStock();
  const names = items.map((it) => it.def.label).join(', ');
  showClerkBubble(`Sold: <strong>${names}</strong>. Thanks!`, 3200);
  return true;
}

function applySawmillPrefabGhostMaterials(g) {
  g.traverse((o) => {
    if (!o.isMesh || !o.material) return;
    const m = o.material;
    m.transparent = true;
    m.opacity = 0.42;
    m.depthWrite = false;
  });
}

function makeTextTexture(text, opts = {}) {
  const pad = opts.pad ?? 14;
  const fontPx = opts.fontPx ?? 24;
  const fg = opts.fg ?? '#111827';
  const bg = opts.bg ?? '#f5d0a4';
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  ctx.font = `800 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
  const t = String(text ?? '');
  const tw = ctx.measureText(t).width;
  let w = Math.ceil(Math.min(512, Math.max(64, tw + pad * 2)));
  let h = Math.ceil(fontPx + pad * 2);
  if (opts.square) {
    const side = Math.max(w, h);
    w = h = side;
  }
  canvas.width = w;
  canvas.height = h;
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = 'rgba(0,0,0,0.18)';
  ctx.lineWidth = 3;
  ctx.strokeRect(2, 2, w - 4, h - 4);
  ctx.fillStyle = fg;
  ctx.font = `800 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
  ctx.textBaseline = 'middle';
  const tx = opts.square ? (w - tw) / 2 : pad;
  ctx.fillText(t, tx, h / 2);
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.needsUpdate = true;
  return { tex, aspect: w / h };
}

function attachSawmillSidePanel(grp) {
  if (!grp || grp.userData.sawmillSidePanel) return;
  const panel = new THREE.Group();
  panel.name = 'saw_panel';
  const plateMat = new THREE.MeshStandardMaterial({ color: 0xd9b07d, roughness: 0.9, metalness: 0.02 });
  const plate = new THREE.Mesh(new THREE.BoxGeometry(0.78, 0.42, 0.03), plateMat);
  plate.position.set(0, SAWMILL.outerH * 0.62, SAWMILL.outerD / 2 + 0.02);
  plate.castShadow = true;
  plate.receiveShadow = true;
  panel.add(plate);

  const title = makeTextTexture('Plank Cross Section', { fontPx: 18, pad: 10 });
  const titlePlaneMat = new THREE.MeshBasicMaterial({
    map: title.tex,
    transparent: true,
    polygonOffset: true,
    polygonOffsetFactor: -2,
    polygonOffsetUnits: -2,
  });
  const titlePlane = new THREE.Mesh(new THREE.PlaneGeometry(0.74, 0.19), titlePlaneMat);
  // "Printed" onto the plate: nearly flush with the plate face.
  titlePlane.position.set(plate.position.x, plate.position.y + 0.16, plate.position.z + 0.016);
  panel.add(titlePlane);

  const mkButton = (label, x, y, axis, delta) => {
    const g = new THREE.Group();
    const ring = new THREE.Mesh(
      new THREE.CylinderGeometry(0.048, 0.048, 0.018, 20),
      new THREE.MeshStandardMaterial({ color: 0x111827, roughness: 0.6, metalness: 0.15 })
    );
    ring.rotation.x = Math.PI / 2;
    ring.position.set(x, y, plate.position.z + 0.02);
    ring.castShadow = true;
    g.add(ring);
    const t = makeTextTexture(label, { fontPx: 20, pad: 10, bg: '#111827', fg: '#ffb366', square: true });
    const tm = new THREE.MeshBasicMaterial({
      map: t.tex,
      transparent: true,
      polygonOffset: true,
      polygonOffsetFactor: -2,
      polygonOffsetUnits: -2,
    });
    const s = new THREE.Mesh(new THREE.PlaneGeometry(0.09, 0.09), tm);
    s.position.set(x, y, plate.position.z + 0.031);
    g.add(s);
    const ctrl = { axis, delta };
    g.userData.sawmillControl = ctrl;
    ring.userData.sawmillControl = ctrl;
    s.userData.sawmillControl = ctrl;
    return g;
  };

  // X row
  const xLab = makeTextTexture('X:', { fontPx: 18, pad: 10, bg: '#d9b07d', fg: '#111827' });
  const xLabSpr = new THREE.Sprite(new THREE.SpriteMaterial({ map: xLab.tex, transparent: true }));
  xLabSpr.position.set(plate.position.x - 0.31, plate.position.y + 0.04, plate.position.z + 0.02);
  xLabSpr.scale.set(0.12, 0.06, 1);
  panel.add(xLabSpr);
  panel.add(mkButton('◀', plate.position.x - 0.18, plate.position.y + 0.04, 'x', -0.2));
  panel.add(mkButton('▶', plate.position.x + 0.18, plate.position.y + 0.04, 'x', 0.2));

  const xValPlane = new THREE.Mesh(
    new THREE.PlaneGeometry(0.16, 0.085),
    new THREE.MeshBasicMaterial({
      color: 0xffffff,
      polygonOffset: true,
      polygonOffsetFactor: -1,
      polygonOffsetUnits: -1,
    })
  );
  xValPlane.position.set(plate.position.x, plate.position.y + 0.04, plate.position.z + 0.016);
  panel.add(xValPlane);

  // Y row
  const yLab = makeTextTexture('Y:', { fontPx: 18, pad: 10, bg: '#d9b07d', fg: '#111827' });
  const yLabSpr = new THREE.Sprite(new THREE.SpriteMaterial({ map: yLab.tex, transparent: true }));
  yLabSpr.position.set(plate.position.x - 0.31, plate.position.y - 0.08, plate.position.z + 0.02);
  yLabSpr.scale.set(0.12, 0.06, 1);
  panel.add(yLabSpr);
  panel.add(mkButton('◀', plate.position.x - 0.18, plate.position.y - 0.08, 'y', -0.2));
  panel.add(mkButton('▶', plate.position.x + 0.18, plate.position.y - 0.08, 'y', 0.2));
  const yValPlane = new THREE.Mesh(
    new THREE.PlaneGeometry(0.16, 0.085),
    new THREE.MeshBasicMaterial({
      color: 0xffffff,
      polygonOffset: true,
      polygonOffsetFactor: -1,
      polygonOffsetUnits: -1,
    })
  );
  yValPlane.position.set(plate.position.x, plate.position.y - 0.08, plate.position.z + 0.016);
  panel.add(yValPlane);

  grp.add(panel);
  grp.userData.sawmillSidePanel = { panel, xValPlane, yValPlane, xTex: null, yTex: null, lastX: null, lastY: null };
}

function updateSawmillSidePanel(grp) {
  const p = grp?.userData?.sawmillSidePanel;
  if (!p) return;
  const rawX = Number(grp.userData.plankX ?? 1);
  const rawY = Number(grp.userData.plankY ?? 1);
  const x = clampSawmillXY(rawX);
  const y = clampSawmillXY(rawY);
  if (rawX !== x) grp.userData.plankX = x;
  if (rawY !== y) grp.userData.plankY = y;
  const fx = String(x.toFixed(1)).replace(/\.0$/, '');
  const fy = String(y.toFixed(1)).replace(/\.0$/, '');
  if (p.lastX !== fx) {
    p.xTex?.dispose?.();
    const t = makeTextTexture(fx, { fontPx: 22, pad: 12, bg: '#e5e7eb', fg: '#111827' });
    p.xValPlane.material.map = t.tex;
    p.xValPlane.material.needsUpdate = true;
    p.xTex = t.tex;
    p.lastX = fx;
  }
  if (p.lastY !== fy) {
    p.yTex?.dispose?.();
    const t = makeTextTexture(fy, { fontPx: 22, pad: 12, bg: '#e5e7eb', fg: '#111827' });
    p.yValPlane.material.map = t.tex;
    p.yValPlane.material.needsUpdate = true;
    p.yTex = t.tex;
    p.lastY = fy;
  }
}

/** Decals: “FRONT” on +X, “BACK” on deep −X (matches extended tunnel). */
function addSawmillFrontBackEndLabels(g, ghost, oh, td, backExtra) {
  const addOne = (text, x, xSign) => {
    const { tex, aspect } = makeTextTexture(text, {
      fontPx: 22,
      pad: 12,
      bg: '#1c1612',
      fg: '#f5ebe0',
    });
    const mat = new THREE.MeshBasicMaterial({
      map: tex,
      transparent: true,
      polygonOffset: true,
      polygonOffsetFactor: -2,
      polygonOffsetUnits: -2,
      opacity: ghost ? 0.42 : 1,
      depthWrite: !ghost,
    });
    const planeW = 0.95;
    const planeH = planeW / aspect;
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(planeW, planeH), mat);
    mesh.name = text === 'FRONT' ? 'saw_label_front' : 'saw_label_back';
    const y = oh * 0.52;
    mesh.position.set(x, y, 0);
    mesh.lookAt(x + xSign * 2.5, y, 0);
    mesh.castShadow = false;
    g.add(mesh);
  };
  addOne('FRONT', td / 2 + 0.055, 1);
  addOne('BACK', -(td / 2 + backExtra + 0.055), -1);
}

/** Procedural sawmill when no prefab is saved in LT2_PrefabEdits. */
function buildSawmillModelProcedural(ghost) {
  const g = new THREE.Group();
  g.name = ghost ? 'SimpleSawmillGhost' : 'SimpleSawmill';
  const wall = new THREE.MeshStandardMaterial({
    color: ghost ? 0x6ab8ff : 0x5c5348,
    roughness: 0.82,
    metalness: 0.06,
    transparent: ghost,
    opacity: ghost ? 0.42 : 1,
    depthWrite: !ghost,
  });
  const roof = new THREE.MeshStandardMaterial({
    color: ghost ? 0x88c4ff : 0x4a4540,
    roughness: 0.88,
    metalness: 0.04,
    transparent: ghost,
    opacity: ghost ? 0.38 : 1,
    depthWrite: !ghost,
  });
  // Hollow frame around a tunnel (wood passes through the center).
  const ow = SAWMILL.outerW;
  const oh = SAWMILL.outerH;
  const od = SAWMILL.outerD;
  const tw = SAWMILL.tunnelW;
  const th = SAWMILL.tunnelH;
  const td = SAWMILL.tunnelD;
  const tExtra = SAWMILL.tunnelBackExtra;
  const tunnelLen = td + tExtra;
  const tCx = -tExtra / 2;
  const sideT = Math.max(0.2, (od - tw) / 2);
  const topT = Math.max(0.2, (oh - th) / 2);

  // Tunnel axis is local X; extra length extends toward −X (BACK); center shifts so +X mouth stays at +td/2.
  const sideGeo = new THREE.BoxGeometry(tunnelLen, oh, sideT);
  const front = new THREE.Mesh(sideGeo, wall);
  front.name = 'saw_side_F';
  front.position.set(tCx, oh / 2, tw / 2 + sideT / 2);
  const back = front.clone();
  back.name = 'saw_side_B';
  back.position.z *= -1;
  const topRail = new THREE.Mesh(new THREE.BoxGeometry(tunnelLen, topT, tw), wall);
  topRail.name = 'saw_top';
  topRail.position.set(tCx, th + topT / 2, 0);
  const botRail = new THREE.Mesh(new THREE.BoxGeometry(tunnelLen, topT, tw), wall);
  botRail.name = 'saw_bottom';
  botRail.position.set(tCx, topT / 2, 0);
  for (const m of [front, back, topRail, botRail]) {
    m.castShadow = !ghost;
    m.receiveShadow = !ghost;
  }

  const roofCap = new THREE.Mesh(new THREE.BoxGeometry(ow + 0.15, 0.12, od + 0.15), roof);
  roofCap.name = 'saw_roof';
  roofCap.position.y = oh + 0.06;
  roofCap.castShadow = !ghost;

  // External conveyor + funnel on −local X (BACK): planks exit here; feed logs from +X (FRONT).
  const beltMat = new THREE.MeshStandardMaterial({
    color: ghost ? 0x88c4ff : 0x6a6f78,
    roughness: 0.82,
    metalness: 0.18,
    transparent: ghost,
    opacity: ghost ? 0.25 : 1,
    depthWrite: !ghost,
  });
  const intakeLen = SAWMILL.beltLen;
  const beltStick = Math.max(0, SAWMILL.beltBackStickOut ?? 0);
  const beltCenterX = getSawmillExternalBeltCenterLocalX();
  const belt = new THREE.Mesh(new THREE.BoxGeometry(intakeLen, 0.16, tw + 0.45), beltMat);
  belt.name = 'saw_intake_belt';
  belt.position.set(beltCenterX, 0.12, 0);
  belt.castShadow = !ghost;
  belt.receiveShadow = !ghost;
  // Simple ribs on the belt.
  const ribMat = new THREE.MeshStandardMaterial({
    color: ghost ? 0x88c4ff : 0x545a66,
    roughness: 0.7,
    metalness: 0.25,
    transparent: ghost,
    opacity: ghost ? 0.18 : 1,
    depthWrite: !ghost,
  });
  for (let i = 0; i < 9; i++) {
    const rib = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.02, tw + 0.28), ribMat);
    rib.name = `saw_belt_rib_${i}`;
    rib.position.set(belt.position.x - intakeLen / 2 + 0.08 + i * (intakeLen / 8), 0.18, 0);
    rib.castShadow = !ghost;
    rib.receiveShadow = !ghost;
    g.add(rib);
  }
  const funnelLen = 0.72;
  const r0 = (tw + 0.45) * 0.55;
  const r1 = tw * 0.52;
  const funnel = new THREE.Mesh(new THREE.CylinderGeometry(r0, r1, funnelLen, 18, 1, true), wall);
  funnel.name = 'saw_funnel';
  funnel.rotation.z = -Math.PI / 2;
  funnel.position.set(
    -td / 2 - tExtra + funnelLen / 2 + 0.03 - beltStick * 0.42,
    th * 0.52 + 0.06,
    0
  );
  funnel.castShadow = !ghost;
  funnel.receiveShadow = !ghost;

  // Internal belt (visual). Flow FRONT (+X) → BACK (−X), same as outside exit belt.
  const inside = new THREE.Mesh(new THREE.BoxGeometry(tunnelLen * 0.72, 0.12, tw * 0.95), beltMat);
  inside.name = 'saw_inside_belt';
  inside.position.set(tCx + 0.05, 0.12, 0);
  inside.castShadow = !ghost;
  inside.receiveShadow = !ghost;
  // Arrow decal (simple plane) to indicate direction.
  const arrowTex = (() => {
    const c = document.createElement('canvas');
    c.width = 128;
    c.height = 64;
    const ctx = c.getContext('2d');
    ctx.fillStyle = 'rgba(0,0,0,0)';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.fillStyle = ghost ? 'rgba(136,196,255,0.55)' : 'rgba(255,180,102,0.85)';
    ctx.beginPath();
    ctx.moveTo(20, 32);
    ctx.lineTo(90, 12);
    ctx.lineTo(90, 24);
    ctx.lineTo(112, 24);
    ctx.lineTo(112, 40);
    ctx.lineTo(90, 40);
    ctx.lineTo(90, 52);
    ctx.closePath();
    ctx.fill();
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.needsUpdate = true;
    return tex;
  })();
  const arrowMat = new THREE.MeshBasicMaterial({
    map: arrowTex,
    transparent: true,
    opacity: ghost ? 0.4 : 0.95,
    depthWrite: false,
  });
  const outsideArrow = new THREE.Mesh(new THREE.PlaneGeometry(intakeLen * 0.55, (tw + 0.45) * 0.4), arrowMat);
  outsideArrow.name = 'saw_belt_arrow_outside';
  outsideArrow.rotation.x = -Math.PI / 2;
  outsideArrow.position.set(belt.position.x, belt.position.y + 0.07, 0);
  // Belt on −X: arrow points −X (planks ride away from the sawmill).
  outsideArrow.rotation.z = 0;
  const insideArrow = new THREE.Mesh(new THREE.PlaneGeometry(td * 0.42, tw * 0.35), arrowMat);
  insideArrow.name = 'saw_belt_arrow_inside';
  insideArrow.rotation.x = -Math.PI / 2;
  insideArrow.position.set(inside.position.x, inside.position.y + 0.07, 0);
  addSawmillFrontBackEndLabels(g, ghost, oh, td, tExtra);
  g.add(front, back, topRail, botRail, roofCap, belt, funnel, inside, outsideArrow, insideArrow);
  if (!ghost) attachSawmillSidePanel(g);
  g.userData.sawmillFootH = 0;
  return g;
}

function buildSawmillModel(ghost) {
  const pdata = tryGetPrefabDataForKind('sawmill');
  if (pdata && Array.isArray(pdata.parts) && pdata.parts.length > 0) {
    const g = deserializePrefabGroup(pdata);
    if (ghost) applySawmillPrefabGhostMaterials(g);
    g.name = ghost ? 'SimpleSawmillGhost' : 'SimpleSawmill';
    g.userData.sawmillFootH = 0;
    return g;
  }
  return buildSawmillModelProcedural(ghost);
}

function spawnSawmillKitBoxNearPlayer() {
  if (!playerBody || !camera) return;
  const def = SHOP_ITEMS.sawmill;
  const dir = new THREE.Vector3();
  camera.getWorldDirection(dir);
  dir.y = 0;
  if (dir.lengthSq() < 0.01) dir.set(0, 0, 1);
  dir.normalize();
  const hw = 0.22;
  const hh = 0.14;
  const hd = 0.18;
  const wx = playerBody.position.x + dir.x * 0.95;
  const wz = playerBody.position.z + dir.z * 0.95;
  const wy = 0.52;
  const geo = new THREE.BoxGeometry(hw * 2, hh * 2, hd * 2);
  const mat = new THREE.MeshStandardMaterial({
    color: 0xc4a574,
    roughness: 0.88,
    metalness: 0.04,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  mesh.position.set(wx, wy, wz);
  planetMeshParent().add(mesh);
  const shape = new CANNON.Box(new CANNON.Vec3(hw, hh, hd));
  const body = new CANNON.Body({
    mass: 0.48,
    material: woodPhyMat ?? undefined,
    linearDamping: 0.35,
    angularDamping: 0.45,
  });
  body.addShape(shape);
  body.position.set(wx, wy, wz);
  world.addBody(body);
  registerDynamic(mesh, body, { kind: 'kitBox', kitId: 'sawmill' });
  mesh.userData.kitLabel = def.label;
  mesh.userData.kitOpenHintName = 'Simple sawmill';
}

function getOwnedPlotSlotIndex() {
  if (landPlots < 1) return -1;
  for (let i = 0; i < landPlotEntries.length; i++) {
    if (landPlotEntryOwned(landPlotEntries[i], i)) return i;
  }
  return -1;
}

/**
 * @returns {{ x: number; z: number; slot: object } | null}
 */
function clampWorldXZToOwnedPlot(wx, wz) {
  const idx = getOwnedPlotSlotIndex();
  if (idx < 0) return null;
  const slot = landPlotSites[idx];
  const s = getOwnedLandScale();
  const hw = (slot.w * s) / 2;
  const hd = (slot.d * s) / 2;
  const dx = wx - slot.x;
  const dz = wz - slot.z;
  const c = Math.cos(-slot.ry);
  const sn = Math.sin(-slot.ry);
  let lx = dx * c - dz * sn;
  let lz = dx * sn + dz * c;
  lx = Math.max(-hw, Math.min(hw, lx));
  lz = Math.max(-hd, Math.min(hd, lz));
  const ox = lx * Math.cos(slot.ry) - lz * Math.sin(slot.ry);
  const oz = lx * Math.sin(slot.ry) + lz * Math.cos(slot.ry);
  return {
    x: snapGrid(slot.x + ox),
    z: snapGrid(slot.z + oz),
    slot,
  };
}

function createPlotGridForPlacement(slot, scale) {
  const w = slot.w * scale;
  const d = slot.d * scale;
  const size = Math.max(w, d);
  const divs = Math.max(1, Math.round(size / WORLD_GRID));
  const grid = new THREE.GridHelper(
    size,
    divs,
    0xff9933,
    0xff6600
  );
  grid.position.set(slot.x, 0.045, slot.z);
  grid.rotation.y = slot.ry;
  grid.traverse((o) => {
    if (o.material) {
      const m = o.material;
      if (Array.isArray(m)) {
        for (const x of m) {
          x.transparent = true;
          x.opacity = 0.65;
        }
      } else {
        m.transparent = true;
        m.opacity = 0.65;
      }
    }
  });
  return grid;
}

function disposeGroupDeep(obj) {
  obj.traverse((o) => {
    if (o.geometry) o.geometry.dispose();
    if (o.material) {
      const m = o.material;
      if (Array.isArray(m)) m.forEach((x) => x.dispose?.());
      else m.dispose?.();
    }
  });
}

function exitSawmillPlacement(cancel) {
  if (!sawmillPlacementMode) return;
  disposeGroupDeep(sawmillPlacementMode.ghost);
  sawmillPlacementMode.ghost.removeFromParent();
  if (sawmillPlacementMode.grid) {
    const gh = sawmillPlacementMode.grid;
    gh.removeFromParent();
    gh.traverse((o) => {
      if (o.geometry) o.geometry.dispose();
      if (o.material) {
        const m = o.material;
        if (Array.isArray(m)) m.forEach((x) => x.dispose?.());
        else m.dispose?.();
      }
    });
  }
  sawmillPlacementMode = null;
  const panel = document.getElementById('placement-panel');
  if (panel) panel.style.display = 'none';
  document.body.classList.remove('sawmill-placing');
  if (cancel) {
    showClerkBubble('Placement cancelled.', 2200);
  }
}

function tryBeginSawmillPlacementFromKit() {
  if (blueprintPlacementMode) exitBlueprintPlacement(true);
  const idx = getOwnedPlotSlotIndex();
  const slot = landPlotSites[idx];
  const s = getOwnedLandScale();
  const ghost = buildSawmillModel(true);
  const grid = createPlotGridForPlacement(slot, s);
  planetMeshParent().add(grid);
  planetMeshParent().add(ghost);
  sawmillPlacementMode = {
    ghost,
    grid,
    yaw: 0,
    slot,
    scale: s,
  };
  const panel = document.getElementById('placement-panel');
  if (panel) panel.style.display = 'flex';
  document.body.classList.add('sawmill-placing');
  document.exitPointerLock?.();
  return true;
}

const _placementRay = new THREE.Ray();
function updateSawmillPlacementGhost() {
  if (!sawmillPlacementMode || !camera || !playerBody) return;
  const { origin, dir } = getCameraRay();
  _placementRay.origin.copy(origin);
  _placementRay.direction.copy(dir);
  if (!_placementRay.intersectPlane(_sawGroundPlane, _sawRayHit)) return;
  const cl = clampWorldXZToOwnedPlot(_sawRayHit.x, _sawRayHit.z);
  if (!cl) return;
  const { ghost, yaw } = sawmillPlacementMode;
  ghost.position.set(cl.x, 0, cl.z);
  ghost.rotation.y = yaw;
}

function finalizeSawmillPlacement() {
  if (!sawmillPlacementMode || !sawmillsRoot) return;
  const { ghost, yaw } = sawmillPlacementMode;
  const x = ghost.position.x;
  const z = ghost.position.z;
  exitSawmillPlacement(false);
  spawnStaticSawmillAt(x, z, yaw);
  saveGame();
  showClerkBubble('<strong>Simple sawmill</strong> placed on your lot.', 2800);
}

function findBlueprintCatalogEntry(bpId) {
  return BLUEPRINT_CATALOG.find((b) => b.id === bpId) ?? null;
}

function dominantSkinFromTally(tally) {
  let best = 'wood';
  let bestN = -1;
  for (const k of ['wood', 'dark', 'void']) {
    const n = tally[k] || 0;
    if (n > bestN) {
      bestN = n;
      best = k;
    }
  }
  if (bestN < 1e-5) return 'wood';
  return best;
}

function makeBlueprintIncompleteMaterial() {
  const m = new THREE.MeshStandardMaterial({
    color: 0xbda078,
    transparent: true,
    opacity: 0.28,
    roughness: 0.88,
    metalness: 0.05,
    depthWrite: false,
  });
  m.userData.isBlueprintIncomplete = true;
  return m;
}

function createBlueprintGhostMesh(def) {
  const mesh = new THREE.Mesh(
    new THREE.BoxGeometry(def.w, def.h, def.d),
    makeBlueprintIncompleteMaterial()
  );
  mesh.userData.isBlueprintGhostPart = true;
  mesh.castShadow = false;
  mesh.receiveShadow = false;
  return mesh;
}

function createBlueprintGhostGroup(def) {
  const g = new THREE.Group();
  g.name = 'BlueprintGhost';
  const mesh = createBlueprintGhostMesh(def);
  g.add(mesh);
  g.userData.bpGhostFootH = def.h * 0.5;
  return g;
}

function exitBlueprintPlacement(cancel) {
  if (!blueprintPlacementMode) return;
  disposeGroupDeep(blueprintPlacementMode.ghost);
  blueprintPlacementMode.ghost.removeFromParent();
  if (blueprintPlacementMode.grid) {
    const gh = blueprintPlacementMode.grid;
    gh.removeFromParent();
    gh.traverse((o) => {
      if (o.geometry) o.geometry.dispose();
      if (o.material) {
        const m = o.material;
        if (Array.isArray(m)) m.forEach((x) => x.dispose?.());
        else m.dispose?.();
      }
    });
  }
  blueprintPlacementMode = null;
  const panel = document.getElementById('blueprint-placement-panel');
  if (panel) panel.style.display = 'none';
  document.body.classList.remove('blueprint-placing');
  if (cancel)
    showClerkBubble('Blueprint placement cancelled.', 2200);
}

function tryBeginBlueprintPlacement(bpId) {
  if (sawmillPlacementMode) exitSawmillPlacement(false);
  const def = findBlueprintCatalogEntry(bpId);
  if (!def) {
    showClerkBubble('Unknown blueprint.', 2000);
    return false;
  }
  if (landPlots < 1) {
    showClerkBubble('Buy land from <strong>Bob</strong> first.', 3800);
    return false;
  }
  if (getOwnedPlotSlotIndex() < 0) {
    showClerkBubble('Could not find your lot. Try saving and reloading.', 3800);
    return false;
  }
  exitBlueprintPlacement(false);
  const slot = landPlotSites[getOwnedPlotSlotIndex()];
  const s = getOwnedLandScale();
  const ghost = createBlueprintGhostGroup(def);
  const grid = createPlotGridForPlacement(slot, s);
  planetMeshParent().add(grid);
  planetMeshParent().add(ghost);
  blueprintPlacementMode = {
    ghost,
    grid,
    yaw: 0,
    slot,
    scale: s,
    bpId: def.id,
    def,
  };
  const panel = document.getElementById('blueprint-placement-panel');
  if (panel) panel.style.display = 'flex';
  document.body.classList.add('blueprint-placing');
  document.exitPointerLock?.();
  return true;
}

function updateBlueprintPlacementGhost() {
  if (!blueprintPlacementMode || !camera || !playerBody) return;
  const { origin, dir } = getCameraRay();
  _placementRay.origin.copy(origin);
  _placementRay.direction.copy(dir);
  if (!_placementRay.intersectPlane(_sawGroundPlane, _sawRayHit)) return;
  const cl = clampWorldXZToOwnedPlot(_sawRayHit.x, _sawRayHit.z);
  if (!cl) return;
  const { ghost, yaw, def } = blueprintPlacementMode;
  ghost.position.set(cl.x, def.h * 0.5, cl.z);
  ghost.rotation.y = yaw;
}

function spawnPlotBlueprintConstructionAt(def, x, z, ry, existing) {
  if (!plotBlueprintsRoot) return null;
  const grp = new THREE.Group();
  grp.name = `PlotBlueprint_${def.id}`;
  grp.userData.kind = 'plotBlueprint';
  grp.userData.bpId = def.id;
  grp.userData.bpDef = def;
  grp.userData.woodApplied = Number(existing?.woodApplied) || 0;
  grp.userData.woodTally = {
    wood: Number(existing?.woodTally?.wood) || 0,
    dark: Number(existing?.woodTally?.dark) || 0,
    void: Number(existing?.woodTally?.void) || 0,
  };
  grp.userData.bpInstanceId = existing?.id ? String(existing.id) : `bp_${allocNetId()}`;
  grp.userData.contributingPlankNetIds = Array.isArray(existing?.contributingPlankNetIds)
    ? existing.contributingPlankNetIds.map((n) => Number(n)).filter((n) => Number.isFinite(n))
    : [];
  grp.userData.bpComplete = false;
  grp.userData.planetId = runtimePlanetId;
  grp.position.set(x, def.h * 0.5, z);
  grp.rotation.y = ry;
  const mesh = new THREE.Mesh(
    new THREE.BoxGeometry(def.w, def.h, def.d),
    makeBlueprintIncompleteMaterial()
  );
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  grp.add(mesh);
  grp.userData.mainMesh = mesh;
  plotBlueprintsRoot.add(grp);

  const done =
    existing?.complete === true || grp.userData.woodApplied >= def.baseWood;
  grp.userData.woodApplied = Math.min(def.baseWood, grp.userData.woodApplied);
  if (done) {
    finalizeBlueprintBuildState(grp);
  } else {
    refreshPlotBlueprintIncompleteVisual(grp);
  }
  return grp;
}

function refreshPlotBlueprintIncompleteVisual(grp) {
  const def = grp.userData.bpDef;
  const mesh = grp.userData.mainMesh;
  if (!mesh || grp.userData.bpComplete) return;
  const t =
    def.baseWood > 0 ? Math.min(1, grp.userData.woodApplied / def.baseWood) : 1;
  mesh.material.opacity = 0.22 + Math.min(0.62, t * 0.85);
  const c = new THREE.Color(0xbda078);
  c.lerp(new THREE.Color(0x7a5c3a), t);
  mesh.material.color.copy(c);
}

function finalizeBlueprintPhysicsForGroup(grp) {
  const def = grp.userData.bpDef;
  if (!def || grp.userData.staticBody) return;
  const body = new CANNON.Body({
    mass: 0,
    material: mapPropPhyMaterial ?? undefined,
  });
  body.addShape(
    new CANNON.Box(new CANNON.Vec3(def.w / 2, def.h / 2, def.d / 2))
  );
  body.position.set(grp.position.x, grp.position.y, grp.position.z);
  body.quaternion.set(
    grp.quaternion.x,
    grp.quaternion.y,
    grp.quaternion.z,
    grp.quaternion.w
  );
  world.addBody(body);
  grp.userData.staticBody = body;
}

function finalizeBlueprintBuildState(grp) {
  const def = grp.userData.bpDef;
  const mesh = grp.userData.mainMesh;
  if (!mesh || !def) return;
  const skin = normalizeLogSkin(dominantSkinFromTally(grp.userData.woodTally));
  grp.userData.bpComplete = true;
  grp.userData.finishSkin = skin;
  if (mesh.material?.userData?.isBlueprintIncomplete) mesh.material.dispose();
  mesh.material = makePlankSurfaceMaterial(skin);
  mesh.material.transparent = false;
  if (mesh.material.opacity != null) mesh.material.opacity = 1;
  mesh.material.depthWrite = true;
  if (skin === 'dark' || skin === 'void') enableNeonBloomOnMesh(mesh);
  finalizeBlueprintPhysicsForGroup(grp);
  consumePlanksRegisteredForBlueprint(grp);
}

function finalizeBlueprintPlacement() {
  if (!blueprintPlacementMode || !plotBlueprintsRoot) return;
  const { ghost, yaw, def } = blueprintPlacementMode;
  const x = ghost.position.x;
  const z = ghost.position.z;
  exitBlueprintPlacement(false);
  spawnPlotBlueprintConstructionAt(def, x, z, yaw, null);
  saveGame();
  showClerkBubble(
    `<strong>${def.label}</strong> placed — <strong>overlap sawmilled planks</strong> with the ghost; each overlap snaps progress (size → %). Pull out & use another plank until 100%.`,
    4200
  );
}

function getPlotBlueprintRootFromObject(obj) {
  let o = obj;
  while (o) {
    if (o.userData?.kind === 'plotBlueprint') return o;
    o = o.parent;
  }
  return null;
}

function clearPlotBlueprintsFromWorld() {
  if (!plotBlueprintsRoot) return;
  for (const child of [...plotBlueprintsRoot.children]) {
    if (child.userData.staticBody) {
      world.removeBody(child.userData.staticBody);
      child.userData.staticBody = null;
    }
    const mesh = child.userData.mainMesh;
    if (mesh?.geometry) mesh.geometry.dispose();
    if (mesh?.material?.userData?.isBlueprintIncomplete) mesh.material.dispose();
    plotBlueprintsRoot.remove(child);
  }
}

function loadPlotBlueprintsFromSave(rows) {
  clearPlotBlueprintsFromWorld();
  if (!Array.isArray(rows) || !plotBlueprintsRoot) return;
  for (const r of rows) {
    if (!r || typeof r !== 'object') continue;
    const bpId = String(r.bpId || '');
    const def = findBlueprintCatalogEntry(bpId);
    if (!def) continue;
    const x = Number(r.x) || 0;
    const z = Number(r.z) || 0;
    const ry = Number(r.ry) || 0;
    spawnPlotBlueprintConstructionAt(def, x, z, ry, r);
  }
}

/** Last text written to the construction HUD line — runs every frame from animate(), so skip
 *  the DOM write (which dirties layout even for identical text) when nothing changed. */
let _lastConstructionHudText = null;
function updateConstructionHudLine() {
  const el = document.getElementById('hud-build-wood');
  if (!el) return;
  const text =
    constructionHudLive ||
    'Construction: overlap planks with the ghost — each entry snaps % once (bigger plank → bigger jump). Planks removed at 100%.';
  if (text === _lastConstructionHudText) return;
  _lastConstructionHudText = text;
  el.textContent = text;
}

/**
 * Hollow shell matching {@link buildSawmillModelProcedural} — one solid AABB filled the tunnel and caused
 * logs/planks to jitter inside the sawmill. Compound boxes leave the tunnel bore open on ±X.
 */
function addSawmillProceduralHullShapes(body) {
  const oh = SAWMILL.outerH;
  const ow = SAWMILL.outerW;
  const od = SAWMILL.outerD;
  const tw = SAWMILL.tunnelW;
  const th = SAWMILL.tunnelH;
  const td = SAWMILL.tunnelD;
  const tExtra = SAWMILL.tunnelBackExtra;
  const tunnelLen = td + tExtra;
  const tCx = -tExtra / 2;
  const sideT = Math.max(0.2, (od - tw) / 2);
  const topT = Math.max(0.2, (oh - th) / 2);
  const intakeLen = SAWMILL.beltLen;
  const beltStick = Math.max(0, SAWMILL.beltBackStickOut ?? 0);
  const beltCenterX = getSawmillExternalBeltCenterLocalX();

  body.addShape(
    new CANNON.Box(new CANNON.Vec3(tunnelLen / 2, oh / 2, sideT / 2)),
    new CANNON.Vec3(tCx, oh / 2, tw / 2 + sideT / 2)
  );
  body.addShape(
    new CANNON.Box(new CANNON.Vec3(tunnelLen / 2, oh / 2, sideT / 2)),
    new CANNON.Vec3(tCx, oh / 2, -(tw / 2 + sideT / 2))
  );
  body.addShape(
    new CANNON.Box(new CANNON.Vec3(tunnelLen / 2, topT / 2, tw / 2)),
    new CANNON.Vec3(tCx, th + topT / 2, 0)
  );
  body.addShape(
    new CANNON.Box(new CANNON.Vec3(tunnelLen / 2, topT / 2, tw / 2)),
    new CANNON.Vec3(tCx, topT / 2, 0)
  );
  body.addShape(
    new CANNON.Box(new CANNON.Vec3((ow + 0.15) / 2, 0.06, (od + 0.15) / 2)),
    new CANNON.Vec3(0, oh + 0.06, 0)
  );
  body.addShape(
    new CANNON.Box(new CANNON.Vec3(intakeLen / 2, 0.08, (tw + 0.45) / 2)),
    new CANNON.Vec3(beltCenterX, 0.12, 0)
  );
  body.addShape(
    new CANNON.Box(new CANNON.Vec3(1.05 / 2, 0.04, 1.55 / 2)),
    new CANNON.Vec3(-(td / 2 + tExtra - 0.62) - beltStick * 0.28, 0.33, 0)
  );
}

function spawnStaticSawmillAt(x, z, yaw) {
  if (!sawmillsRoot) return;
  const g = buildSawmillModel(false);
  g.name = g.name || 'Sawmill';
  g.userData.planetId = runtimePlanetId;
  /** Sphere worlds: stand the mill on the surface at its map coord, `yaw` spinning about the normal. */
  placeOriginGroupOnPlanetSurface(g, x, 0, z, yaw, sawmillsRoot);
  g.userData.plankX = 1;
  g.userData.plankY = 1;
  if (!g.userData.sawmillSidePanel) attachSawmillSidePanel(g);
  updateSawmillSidePanel(g);
  ensureSawmillParticles(g);
  g.updateMatrixWorld(true);
  const body = new CANNON.Body({
    mass: 0,
    material: mapPropPhyMaterial ?? undefined,
  });
  addSawmillProceduralHullShapes(body);
  /** Derive from the WORLD matrix: on sphere worlds the mill hangs off a surface anchor, so its
   *  local pose no longer equals its world pose. */
  syncStaticBodyToMeshWorld(g, body);
  body.userData = body.userData || {};
  body.userData.planetId = runtimePlanetId;
  world.addBody(body);
  g.userData.staticBody = body;
  refreshSawmillFrontConveyorBeltDirections();
}

function clearSawmillsFromWorld() {
  if (!sawmillsRoot) return;
  for (const child of [...sawmillsRoot.children]) {
    if (child.userData.staticBody) {
      world.removeBody(child.userData.staticBody);
      child.userData.staticBody = null;
    }
    disposeGroupDeep(child);
    sawmillsRoot.remove(child);
  }
  refreshSawmillFrontConveyorBeltDirections();
}

function loadSawmillsFromSave(rows) {
  clearSawmillsFromWorld();
  if (!Array.isArray(rows)) return;
  for (const r of rows) {
    if (!r || typeof r !== 'object') continue;
    const x = Number(r.x) || 0;
    const z = Number(r.z) || 0;
    const ry = Number(r.ry) || 0;
    spawnStaticSawmillAt(x, z, ry);
    const last = sawmillsRoot?.children?.[sawmillsRoot.children.length - 1];
    if (last) {
      const px = Number(r.plankX);
      const py = Number(r.plankY);
      if (Number.isFinite(px)) last.userData.plankX = clampSawmillXY(px);
      if (Number.isFinite(py)) last.userData.plankY = clampSawmillXY(py);
    }
  }
}

/** When both Bob and clerk are in range, prefer the closer one for <kbd>E</kbd>. */
function npcInteractPriority() {
  if (!playerBody) return null;
  const nearBob = playerNearBob();
  const nearClerk = playerNearClerk();
  if (!nearBob && !nearClerk) return null;
  if (nearBob && !nearClerk) return 'bob';
  if (!nearBob && nearClerk) return 'clerk';
  const dBob = Math.hypot(playerBody.position.x - STORE.bobX, playerBody.position.z - STORE.bobZ);
  const dClerk = Math.hypot(playerBody.position.x - SHOP_CLERK.x, playerBody.position.z - SHOP_CLERK.z);
  return dBob <= dClerk ? 'bob' : 'clerk';
}

function setNpcBubbleText(html) {
  const el = document.getElementById('npc-bubble-text');
  if (el) el.innerHTML = html;
}

function updateMoneyHud() {
  const el = document.getElementById('money');
  if (el) el.textContent = String(money);
  const g = document.getElementById('money-global');
  if (g) g.textContent = `$${money.toLocaleString('en-US')}`;
  updateLeaderboard();
  updateConstructionHudLine();
  if (bobLandShowcase) setLandShowcasePanel();
}

function updateLeaderboard() {
  const list = document.getElementById('leaderboard-rows');
  if (!list) return;
  list.innerHTML = `<div class="lb-row lb-you"><span class="lb-name">You</span><span class="lb-money">${formatMoneyShort(money)}</span></div>`;
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function updateNpcUi() {
  const bubble = document.getElementById('npc-bubble');
  const hint = document.getElementById('npc-hint');
  if (!bubble || !hint || !camera) return;
  const locked = document.pointerLockElement === document.body;
  const nearBob = playerNearBob() && !gamePaused;
  const nearClerk = playerNearClerk() && !gamePaused;
  if (bobDialogOpen && !nearBob) {
    bobDialogOpen = false;
    bobDialogStep = 0;
    exitBobLandShowcase();
    setNpcBubbleText('');
  }
  if (!locked || gamePaused) {
    hint.style.display = 'none';
  } else if (sawmillPlacementMode || blueprintPlacementMode) {
    hint.style.display = 'none';
  } else if (bobLandShowcase) {
    hint.style.display = 'none';
  } else if (bobDialogOpen && nearBob) {
    hint.style.display = 'none';
  } else if (nearBob && nearClerk) {
    hint.style.display = 'block';
    hint.textContent = 'E — Bob (land) or clerk (shop)';
  } else if (nearBob) {
    hint.style.display = 'block';
    hint.textContent = 'E to talk to Bob';
  } else if (nearClerk) {
    hint.style.display = 'block';
    hint.textContent = 'E — buy items on the counter';
  } else {
    hint.style.display = 'none';
  }

  const clerkBubbleOn = performance.now() < clerkBubbleUntil && !gamePaused;
  if (clerkBubbleOn) {
    bubble.style.display = 'block';
    clerkHeadWorld.set(SHOP_CLERK.x, SHOP_CLERK.y + 1.02, SHOP_CLERK.z);
    tmpV.copy(clerkHeadWorld).project(camera);
    const w = window.innerWidth;
    const h = window.innerHeight;
    const px = (tmpV.x * 0.5 + 0.5) * w;
    const py = (-tmpV.y * 0.5 + 0.5) * h;
    bubble.style.left = `${px}px`;
    bubble.style.top = `${py}px`;
    bubble.style.bottom = 'auto';
    bubble.style.transform = 'translate(-50%, -108%)';
  } else if (bobDialogOpen && nearBob) {
    bubble.style.display = 'block';
    if (bobLandShowcase) {
      bubble.style.left = '50%';
      bubble.style.top = 'auto';
      bubble.style.bottom = '150px';
      bubble.style.transform = 'translate(-50%, 0)';
    } else {
      bobHeadWorld.set(STORE.bobX, STORE.bobY + 0.95, STORE.bobZ);
      tmpV.copy(bobHeadWorld).project(camera);
      const w = window.innerWidth;
      const h = window.innerHeight;
      const px = (tmpV.x * 0.5 + 0.5) * w;
      const py = (-tmpV.y * 0.5 + 0.5) * h;
      bubble.style.left = `${px}px`;
      bubble.style.top = `${py}px`;
      bubble.style.bottom = 'auto';
      bubble.style.transform = 'translate(-50%, -108%)';
    }
  } else {
    bubble.style.display = 'none';
  }
}

function updateKitOpenHint() {
  const kitEl = document.getElementById('kit-open-hint');
  const hint = document.getElementById('npc-hint');
  if (!kitEl || !camera || !renderer) return;
  if (gamePaused || sawmillPlacementMode || blueprintPlacementMode) {
    kitEl.style.display = 'none';
    return;
  }
  const pick = rayPickKitBoxForUi(5.5);
  const ok = pick && isSawmillKitMesh(pick.mesh);
  if (ok) {
    kitEl.style.display = 'block';
    const name =
      pick.mesh.userData.kitOpenHintName ||
      String(pick.mesh.userData.kitLabel || 'Kit').replace(/\s+kit$/i, '');
    kitEl.textContent = `Press E to open ${name}`;
    if (hint) hint.style.display = 'none';
  } else {
    kitEl.style.display = 'none';
  }
}

function updateShopHoverLabel() {
  const el = document.getElementById('shop-hover-label');
  if (!el || !camera || !renderer) return;
  if (gamePaused || sawmillPlacementMode || blueprintPlacementMode || !shopItemLabelsEnabled) {
    el.style.display = 'none';
    return;
  }
  const pick = rayPickShopItemForHover(6);
  if (!pick) {
    el.style.display = 'none';
    return;
  }
  el.style.display = 'block';
  el.textContent = pick.def.label;
  el.style.position = 'fixed';
  el.style.pointerEvents = 'none';
  el.style.zIndex = '61';
  if (document.pointerLockElement === document.body) {
    el.style.left = '50%';
    el.style.top = 'calc(50% - 38px)';
    el.style.transform = 'translate(-50%, -100%)';
  } else {
    el.style.left = `${lastPointerClient.x}px`;
    el.style.top = `${lastPointerClient.y - 8}px`;
    el.style.transform = 'translate(-50%, -100%)';
  }
}

function nearestSawmillToPlayer(maxDist = 6) {
  if (!playerBody || !sawmillsRoot) return null;
  let best = null;
  let bestD = Infinity;
  for (const s of sawmillsRoot.children) {
    const dx = s.position.x - playerBody.position.x;
    const dz = s.position.z - playerBody.position.z;
    const d = Math.hypot(dx, dz);
    if (d < bestD) {
      bestD = d;
      best = s;
    }
  }
  if (best && bestD <= maxDist) return best;
  return null;
}

// Sawmill cross-section controls are now a 3D panel mounted to the sawmill itself.

function onKeyEForBobOrChop() {
  if (bobLandShowcase) {
    exitBobLandShowcase();
    bobDialogOpen = false;
    bobDialogStep = 0;
    setNpcBubbleText('');
    return;
  }
  if (sawmillPlacementMode) {
    finalizeSawmillPlacement();
    return;
  }
  if (blueprintPlacementMode) {
    finalizeBlueprintPlacement();
    return;
  }
  const kitPick = rayPickKitBoxForUi(5.5);
  if (kitPick && isSawmillKitMesh(kitPick.mesh)) {
    endGrab();
    if (landPlots < 1) {
      showClerkBubble(
        'Buy land from <strong>Bob</strong> first — then you can place this.',
        4200
      );
      return;
    }
    if (getOwnedPlotSlotIndex() < 0) {
      showClerkBubble('Could not find your lot. Try saving and reloading.', 3800);
      return;
    }
    unregisterDynamic(kitPick.mesh, kitPick.body);
    tryBeginSawmillPlacementFromKit();
    return;
  }

  // In-world sawmill control panel: aim at arrow and press E (works with crosshair or free mouse).
  if (tryUseSawmillControlWithE()) {
    return;
  }
  const npcWho = npcInteractPriority();
  if (npcWho === 'clerk') {
    tryClerkCheckout();
    return;
  }
  if (npcWho === 'bob') {
    if (!bobDialogOpen) {
      bobDialogOpen = true;
      bobDialogStep = 0;
      setNpcBubbleText(
        "Hey! I'm Bob. Want to buy land? Press <kbd>E</kbd> again to hear the details."
      );
      return;
    }
    if (bobDialogStep === 0) {
      if (landPlots === 0) {
        enterBobLandShowcase();
        bobDialogStep = 1;
        return;
      }
      bobDialogStep = 1;
      const p = getNextLandPrice();
      setNpcBubbleText(
        `Your lot can get bigger! Next expansion: <strong>$${p.toLocaleString(
          'en-US'
        )}</strong>. Press <kbd>E</kbd> again to purchase.`
      );
      return;
    }
    if (bobDialogStep === 1) {
      if (landPlots === 0) return;
      const p = getNextLandPrice();
      if (money >= p) {
        money -= p;
        landPlots += 1;
        updateMoneyHud();
        syncLandPlotVisuals();
        setNpcBubbleText(
          `Your land grew! You're at expansion level <strong>${landPlots}</strong>. Thanks!`
        );
      } else {
        setNpcBubbleText(`You need <strong>$${p.toLocaleString('en-US')}</strong>. Keep chopping!`);
      }
      bobDialogStep = 2;
      return;
    }
    bobDialogOpen = false;
    bobDialogStep = 0;
    setNpcBubbleText('');
    return;
  }
  if (bobDialogOpen) {
    exitBobLandShowcase();
    bobDialogOpen = false;
    bobDialogStep = 0;
    setNpcBubbleText('');
  }
}

/** Scratch for {@link syncStaticBodyToMeshWorld} — it runs for every static branch collider
 *  every frame from the render loop, so per-call allocations add up fast in a forest. */
const _staticSyncPos = new THREE.Vector3();
const _staticSyncQuat = new THREE.Quaternion();
function syncStaticBodyToMeshWorld(mesh, body) {
  mesh.updateMatrixWorld(true);
  mesh.getWorldPosition(_staticSyncPos);
  mesh.getWorldQuaternion(_staticSyncQuat);
  body.position.set(_staticSyncPos.x, _staticSyncPos.y, _staticSyncPos.z);
  body.quaternion.set(_staticSyncQuat.x, _staticSyncQuat.y, _staticSyncQuat.z, _staticSyncQuat.w);
}

function createStoreAndBob() {
  const g = new THREE.Group();
  storeGroup = g;
  const wallMat = new THREE.MeshStandardMaterial({ color: 0x6a6e73, roughness: 0.92, metalness: 0.05 });
  const glassMat = new THREE.MeshStandardMaterial({
    color: 0x88aacc,
    roughness: 0.08,
    metalness: 0.35,
    transparent: true,
    opacity: 0.35,
  });
  const floorMat = new THREE.MeshStandardMaterial({ color: 0x4a4d52, roughness: 0.95 });
  const sx = STORE.x;
  const sz = STORE.z;
  const W = STORE.W;
  const D = STORE.D;
  const H = 4;
  const t = 0.2;

  const floor = new THREE.Mesh(new THREE.BoxGeometry(W, 0.15, D), floorMat);
  floor.position.set(sx, 0.075, sz);
  floor.receiveShadow = true;
  g.add(floor);

  const dockD = SELL_DOCK_DEPTH;
  const dockZ = sz + D / 2 + dockD / 2;
  const loadingDock = new THREE.Mesh(new THREE.BoxGeometry(W, 0.14, dockD), floorMat);
  loadingDock.position.set(sx, 0.07, dockZ);
  loadingDock.receiveShadow = true;
  loadingDock.castShadow = true;
  g.add(loadingDock);

  const roof = new THREE.Mesh(new THREE.BoxGeometry(W + 0.4, 0.25, D + 0.4), wallMat);
  roof.position.set(sx, H + 0.125, sz);
  roof.castShadow = true;
  g.add(roof);

  const backWall = new THREE.Mesh(new THREE.BoxGeometry(W, H, t), wallMat);
  backWall.position.set(sx, H / 2, sz + D / 2);
  backWall.castShadow = true;
  g.add(backWall);

  const sideL = new THREE.Mesh(new THREE.BoxGeometry(t, H, D - 0.2), wallMat);
  sideL.position.set(sx - W / 2 + t / 2, H / 2, sz);
  g.add(sideL);

  const sideR = new THREE.Mesh(new THREE.BoxGeometry(t, H, D - 0.2), wallMat);
  sideR.position.set(sx + W / 2 - t / 2, H / 2, sz);
  g.add(sideR);

  const doorW = 3.2;
  const frontZ = sz - D / 2;
  const sideW = (W - doorW) / 2;
  const fLeft = new THREE.Mesh(new THREE.BoxGeometry(sideW, H, t), wallMat);
  fLeft.position.set(sx - doorW / 2 - sideW / 2, H / 2, frontZ);
  g.add(fLeft);
  const fRight = new THREE.Mesh(new THREE.BoxGeometry(sideW, H, t), wallMat);
  fRight.position.set(sx + doorW / 2 + sideW / 2, H / 2, frontZ);
  g.add(fRight);

  const winGeo = new THREE.PlaneGeometry(3.5, 2.2);
  const winL = new THREE.Mesh(winGeo, glassMat);
  winL.position.set(sx - 3.8, 2.1, frontZ + 0.11);
  g.add(winL);
  const winR = new THREE.Mesh(winGeo, glassMat);
  winR.position.set(sx + 3.8, 2.1, frontZ + 0.11);
  g.add(winR);

  const bobGroup = new THREE.Group();
  bobGroup.position.set(STORE.bobX, 0, STORE.bobZ);
  const body = new THREE.Mesh(
    new THREE.CylinderGeometry(0.38, 0.42, 1.1, 10),
    new THREE.MeshStandardMaterial({ color: 0x3355aa, roughness: 0.75 })
  );
  body.position.y = 0.55;
  body.castShadow = true;
  const head = new THREE.Mesh(
    new THREE.SphereGeometry(0.32, 12, 10),
    new THREE.MeshStandardMaterial({ color: 0xe8c8a8, roughness: 0.65 })
  );
  head.position.y = 1.22;
  head.castShadow = true;
  bobGroup.add(body, head);
  g.add(bobGroup);

  const counterWood = new THREE.MeshStandardMaterial({
    color: 0x4a3528,
    roughness: 0.9,
    metalness: 0.05,
  });
  const clerkGroup = new THREE.Group();
  clerkGroup.position.set(SHOP_CLERK.x, 0, SHOP_CLERK.z);
  const clerkBody = new THREE.Mesh(
    new THREE.CylinderGeometry(0.36, 0.4, 1.08, 10),
    new THREE.MeshStandardMaterial({ color: 0x8b3a62, roughness: 0.76 })
  );
  clerkBody.position.y = 0.54;
  clerkBody.castShadow = true;
  const clerkHead = new THREE.Mesh(
    new THREE.SphereGeometry(0.3, 12, 10),
    new THREE.MeshStandardMaterial({ color: 0xe8c8a8, roughness: 0.65 })
  );
  clerkHead.position.y = 1.18;
  clerkHead.castShadow = true;
  clerkGroup.add(clerkBody, clerkHead);
  g.add(clerkGroup);

  shopCounterRig = new THREE.Group();
  shopCounterRig.position.set(SHOP_COUNTER_PIVOT.x, 0, SHOP_COUNTER_PIVOT.z);
  shopCounterRig.rotation.y = -Math.PI / 2;
  g.add(shopCounterRig);

  const counterTop = new THREE.Mesh(new THREE.BoxGeometry(2.65, 0.09, 1.08), counterWood);
  counterTop.position.set(0, 1.045, 0);
  counterTop.castShadow = true;
  counterTop.receiveShadow = true;
  shopCounterRig.add(counterTop);
  const counterBase = new THREE.Mesh(new THREE.BoxGeometry(2.45, 0.95, 0.52), counterWood);
  counterBase.position.set(0, 0.475, -0.11);
  counterBase.castShadow = true;
  shopCounterRig.add(counterBase);

  const sf = SHOP_SHELF_FIXTURE;
  const wallShelves = new THREE.Group();
  wallShelves.position.set(sf.groupX, 0, sf.groupZ);
  g.add(wallShelves);

  const pdataShelf = tryGetPrefabDataForKind('shelf');
  let shopShelfUsesPrefab = false;
  if (pdataShelf && Array.isArray(pdataShelf.parts) && pdataShelf.parts.length > 0) {
    const shelfPrefab = deserializePrefabGroup(pdataShelf);
    const pb = pdataShelf.base || { w: 1, h: 1, d: 1 };
    const ySpan = sf.highY + sf.boardThick / 2 - (sf.lowY - sf.boardThick / 2);
    shelfPrefab.scale.set(
      sf.boardW / Math.max(0.01, pb.w),
      ySpan / Math.max(0.01, pb.h),
      sf.boardD / Math.max(0.01, pb.d)
    );
    wallShelves.add(shelfPrefab);
    shopShelfUsesPrefab = true;
  } else {
    const shelfLow = new THREE.Mesh(
      new THREE.BoxGeometry(sf.boardW, sf.boardThick, sf.boardD),
      counterWood
    );
    shelfLow.position.set(0, sf.lowY, 0);
    shelfLow.castShadow = true;
    shelfLow.receiveShadow = true;
    const shelfHigh = new THREE.Mesh(
      new THREE.BoxGeometry(sf.boardW, sf.boardThick, sf.boardD),
      counterWood
    );
    shelfHigh.position.set(0, sf.highY, 0);
    shelfHigh.castShadow = true;
    shelfHigh.receiveShadow = true;
    const shelfBack = new THREE.Mesh(
      new THREE.BoxGeometry(sf.backThick, sf.backH, sf.backD),
      counterWood
    );
    shelfBack.position.set(-(sf.boardW / 2 + sf.backThick / 2), sf.backH / 2, 0);
    shelfBack.castShadow = true;
    wallShelves.add(shelfLow, shelfHigh, shelfBack);
  }

  /** Sphere worlds: hang the whole store off a surface anchor at its map coord so it sits
   *  ON the planet instead of at the core. Flat worlds keep the legacy `scene.add`. */
  g.name = 'Store';
  const storeAnchor = anchorFlatGroupToPlanetSurface(g, sx, sz, spherePhysicsActive ? null : scene);
  g.updateMatrixWorld(true);

  const st = new CANNON.Material('storeWall');
  const hw = (a, b, c) => new CANNON.Box(new CANNON.Vec3(a, b, c));
  const addWall = (px, py, pz, hx, hy, hz) => {
    const b = new CANNON.Body({ mass: 0, material: st });
    b.addShape(hw(hx, hy, hz));
    applyFlatMapPoseToStaticBody(b, storeAnchor, sx, sz, px, py, pz);
    world.addBody(b);
  };
  addWall(sx, H / 2, sz + D / 2, W / 2, H / 2, t / 2);
  addWall(sx - W / 2 + t / 2, H / 2, sz, t / 2, H / 2, D / 2 - 0.1);
  addWall(sx + W / 2 - t / 2, H / 2, sz, t / 2, H / 2, D / 2 - 0.1);
  addWall(sx - doorW / 2 - sideW / 2, H / 2, frontZ, sideW / 2, H / 2, t / 2);
  addWall(sx + doorW / 2 + sideW / 2, H / 2, frontZ, sideW / 2, H / 2, t / 2);

  const cbTop = new CANNON.Body({ mass: 0, material: st });
  cbTop.addShape(new CANNON.Box(new CANNON.Vec3(2.65 / 2, 0.045, 1.08 / 2)));
  world.addBody(cbTop);
  syncStaticBodyToMeshWorld(counterTop, cbTop);

  const cbBase = new CANNON.Body({ mass: 0, material: st });
  cbBase.addShape(new CANNON.Box(new CANNON.Vec3(2.45 / 2, 0.475, 0.52 / 2)));
  world.addBody(cbBase);
  syncStaticBodyToMeshWorld(counterBase, cbBase);

  if (shopShelfUsesPrefab) {
    g.updateMatrixWorld(true);
    const col = computeWorldAabbCollider(wallShelves);
    const shShelf = new CANNON.Body({ mass: 0, material: st });
    shShelf.addShape(
      new CANNON.Box(new CANNON.Vec3(col.half.x, col.half.y, col.half.z))
    );
    shShelf.position.set(col.center.x, col.center.y, col.center.z);
    world.addBody(shShelf);
  } else {
    const shLo = new CANNON.Body({ mass: 0, material: st });
    shLo.addShape(
      new CANNON.Box(new CANNON.Vec3(sf.boardW / 2, sf.boardThick / 2, sf.boardD / 2))
    );
    applyFlatMapPoseToStaticBody(shLo, storeAnchor, sx, sz, sf.groupX, sf.lowY, sf.groupZ);
    world.addBody(shLo);
    const shHi = new CANNON.Body({ mass: 0, material: st });
    shHi.addShape(
      new CANNON.Box(new CANNON.Vec3(sf.boardW / 2, sf.boardThick / 2, sf.boardD / 2))
    );
    applyFlatMapPoseToStaticBody(shHi, storeAnchor, sx, sz, sf.groupX, sf.highY, sf.groupZ);
    world.addBody(shHi);
    const shBack = new CANNON.Body({ mass: 0, material: st });
    shBack.addShape(
      new CANNON.Box(new CANNON.Vec3(sf.backThick / 2, sf.backH / 2, sf.backD / 2))
    );
    applyFlatMapPoseToStaticBody(
      shBack,
      storeAnchor,
      sx,
      sz,
      sf.groupX - (sf.boardW / 2 + sf.backThick / 2),
      sf.backH / 2,
      sf.groupZ
    );
    world.addBody(shBack);
  }
}

function applyMapEditorSellFromStorage() {
  const slice = getActiveMapWorldSlice();
  if (!slice) return;
  if (slice.sellSpot && typeof slice.sellSpot === 'object') {
    const s = slice.sellSpot;
    if (typeof s.x === 'number') sellCenter.x = s.x;
    if (typeof s.z === 'number') sellCenter.z = s.z;
    if (typeof s.r === 'number' && s.r > 0.5 && s.r < 50) sellRadius = s.r;
    refreshSellCenterWorld();
  }
}

const MAP_PROP_KINDS = {
  slab: { baseW: 2, baseH: 0.12, baseD: 2, color: 0x8a8e94 },
  block: { baseW: 1, baseH: 1, baseD: 1, color: 0x5c636a },
  strip: { baseW: 5, baseH: 0.22, baseD: 0.4, color: 0x7a8090 },
  ramp: { baseW: 2, baseH: 0.15, baseD: 3, rise: 0.35, color: 0x8b7355 },
  conveyor: { baseW: 0.9, baseH: 0.16, baseD: 4, color: 0x4a4d55 },
};

function applyRampMeshTiltScaledGame(mesh, def, sx, sy, sz) {
  const rise = def.rise != null ? def.rise : 0.35;
  mesh.rotation.x = -Math.atan2(rise * sy, def.baseD * sz);
  mesh.position.y = (def.baseH * sy) / 2;
  mesh.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(mesh);
  mesh.position.y -= box.min.y;
}

/** Map-editor conveyor: rollers along X, length along local Z, side rails — matches map-editor.js. */
function buildMapEditorConveyorGroup(worldW, worldH, worldD) {
  const g = new THREE.Group();
  const L = Math.max(0.45, worldD);
  const W = Math.max(0.28, worldW);
  const rollerR = Math.min(0.095, W * 0.12);
  const gap = Math.max(0.04, Math.min(0.12, L * 0.03));
  const pitch = rollerR * 2 + gap;
  const n = Math.max(3, Math.min(80, Math.floor(L / pitch)));
  const rollerLen = W * 0.94;
  const matRoller = new THREE.MeshStandardMaterial({
    color: 0x54545e,
    roughness: 0.7,
    metalness: 0.3,
  });
  const matRail = new THREE.MeshStandardMaterial({
    color: 0x2c303a,
    roughness: 0.88,
    metalness: 0.08,
  });
  const zStart = -L / 2 + rollerR + 0.02;
  for (let i = 0; i < n; i++) {
    const zp = zStart + i * pitch;
    if (zp > L / 2 - rollerR - 0.03) break;
    const cyl = new THREE.Mesh(
      new THREE.CylinderGeometry(rollerR, rollerR, rollerLen, 12),
      matRoller
    );
    cyl.rotation.z = Math.PI / 2;
    cyl.position.set(0, rollerR + 0.015, zp);
    cyl.castShadow = true;
    cyl.receiveShadow = true;
    g.add(cyl);
  }
  const railH = Math.max(0.05, Math.min(worldH * 0.55, 0.14));
  const railT = Math.max(0.03, W * 0.048);
  const railGeo = new THREE.BoxGeometry(railT, railH, L);
  const railL = new THREE.Mesh(railGeo, matRail);
  railL.position.set(-W / 2 - railT / 2, railH / 2 + rollerR * 0.45, 0);
  railL.castShadow = true;
  const railR = railL.clone();
  railR.position.x = W / 2 + railT / 2;
  g.add(railL, railR);
  g.userData.base = { w: worldW, h: worldH, d: worldD };
  g.userData.mapPropKind = 'conveyor';
  return g;
}

function hexToRgbGame(hex) {
  const h = String(hex || '#888888').replace('#', '');
  return {
    r: parseInt(h.slice(0, 2), 16),
    g: parseInt(h.slice(2, 4), 16),
    b: parseInt(h.slice(4, 6), 16),
  };
}

function buildLabelSpriteTextureForGame(text, bgHex, fgHex) {
  const pad = 20;
  const lineGap = 8;
  const fontPx = 28;
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  ctx.font = `600 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
  const lines = String(text || ' ').split('\n');
  let maxW = 120;
  for (const line of lines) {
    maxW = Math.max(maxW, ctx.measureText(line).width);
  }
  const lineH = fontPx + lineGap;
  const W = Math.ceil(Math.min(1024, maxW + pad * 2));
  const H = Math.ceil(Math.min(512, lines.length * lineH + pad * 2));
  canvas.width = W;
  canvas.height = H;
  const g0 = hexToRgbGame(bgHex || '#2563eb');
  const grd = ctx.createLinearGradient(0, 0, W, H);
  grd.addColorStop(0, `rgb(${g0.r},${g0.g},${g0.b})`);
  grd.addColorStop(
    1,
    `rgb(${Math.min(255, g0.r + 40)},${Math.min(255, g0.g + 35)},${Math.min(255, g0.b + 50)})`
  );
  ctx.fillStyle = grd;
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = 'rgba(255,255,255,0.4)';
  ctx.lineWidth = 3;
  ctx.strokeRect(2, 2, W - 4, H - 4);
  const fg = hexToRgbGame(fgHex || '#ffffff');
  ctx.fillStyle = `rgb(${fg.r},${fg.g},${fg.b})`;
  ctx.font = `600 ${fontPx}px system-ui, "Segoe UI", sans-serif`;
  ctx.shadowColor = 'rgba(0,0,0,0.45)';
  ctx.shadowBlur = 4;
  ctx.shadowOffsetX = 1;
  ctx.shadowOffsetY = 1;
  let y = pad + fontPx;
  for (const line of lines) {
    ctx.fillText(line, pad, y);
    y += lineH;
  }
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.needsUpdate = true;
  return { tex, aspect: W / H };
}

function clampConveyorBeltPushMul(m) {
  const x = Number(m);
  if (!Number.isFinite(x)) return 1;
  return Math.min(4, Math.max(0.25, x));
}

function registerConveyorBeltZone(grp) {
  grp.updateMatrixWorld(true);
  if (grp.userData.planetId == null) grp.userData.planetId = runtimePlanetId;
  const col = computeWorldAabbCollider(grp);
  const fwd = new THREE.Vector3(0, 0, -1);
  fwd.transformDirection(grp.matrixWorld).normalize();
  const mul = grp.userData?.beltPushMul;
  conveyorBeltZones.push({
    planetId: grp.userData.planetId,
    center: new CANNON.Vec3(col.center.x, col.center.y, col.center.z),
    half: new CANNON.Vec3(col.half.x, col.half.y, col.half.z),
    forward: new CANNON.Vec3(fwd.x, fwd.y, fwd.z),
    forwardSign: 1,
    beltPushMul: clampConveyorBeltPushMul(mul),
  });
}

const _sawBackBeltRefLocal = new THREE.Vector3();
const _sawBackBeltRefWorld = new THREE.Vector3();
const _sawWorldOrigin = new THREE.Vector3();
const _sawBackOutDirWorld = new THREE.Vector3();

/**
 * Invert belt push on the map conveyor closest to each sawmill’s BACK output (−local X, plank side).
 * Safe to call whenever sawmills or conveyor zones change.
 */
function refreshSawmillFrontConveyorBeltDirections() {
  for (const belt of conveyorBeltZones) {
    belt.forwardSign = 1;
  }
  if (!sawmillsRoot || sawmillsRoot.children.length === 0 || conveyorBeltZones.length === 0) return;

  _sawBackBeltRefLocal.set(getSawmillExternalBeltCenterLocalX(), 0.18, 0);

  for (const saw of sawmillsRoot.children) {
    if (saw.userData?.planetId != null && saw.userData.planetId !== runtimePlanetId) continue;
    saw.updateMatrixWorld(true);
    saw.getWorldPosition(_sawWorldOrigin);
    _sawBackBeltRefWorld.copy(_sawBackBeltRefLocal).applyMatrix4(saw.matrixWorld);
    _sawBackOutDirWorld.set(-1, 0, 0).transformDirection(saw.matrixWorld).normalize();

    let best = null;
    let bestD = 1e9;
    for (const belt of conveyorBeltZones) {
      if (belt.planetId != null && saw.userData?.planetId != null && belt.planetId !== saw.userData.planetId) continue;
      const vx = belt.center.x - _sawWorldOrigin.x;
      const vy = belt.center.y - _sawWorldOrigin.y;
      const vz = belt.center.z - _sawWorldOrigin.z;
      const along = vx * _sawBackOutDirWorld.x + vy * _sawBackOutDirWorld.y + vz * _sawBackOutDirWorld.z;
      if (along < 0.12) continue;
      const dx = belt.center.x - _sawBackBeltRefWorld.x;
      const dy = belt.center.y - _sawBackBeltRefWorld.y;
      const dz = belt.center.z - _sawBackBeltRefWorld.z;
      const d = dx * dx + dy * dy + dz * dz;
      if (d < bestD) {
        bestD = d;
        best = belt;
      }
    }
    if (best && bestD < 20) {
      best.forwardSign = -1;
    }
  }
}

function applyConveyorBelts(dt) {
  if (gamePaused || conveyorBeltZones.length === 0) return;
  const pushBase = 28;
  for (const { mesh, body } of dynamicPairs) {
    const k = mesh.userData.kind;
    if (k !== 'log' && k !== 'trunk' && k !== 'plank') continue;
    if (grabTarget === body) continue;
    if (logIsFullyInsideAnySawmillTunnel(mesh, body)) continue;
    const itemPlanet = mesh.userData.planetId ?? body.userData?.planetId;
    if (itemPlanet != null && itemPlanet !== runtimePlanetId) continue;
    const p = body.position;
    for (const belt of conveyorBeltZones) {
      if (belt.planetId != null && belt.planetId !== runtimePlanetId) continue;
      if (belt.planetId != null && itemPlanet != null && belt.planetId !== itemPlanet) continue;
      const { center, half, forward } = belt;
      const fs = belt.forwardSign ?? 1;
      const mul = belt.beltPushMul ?? 1;
      if (Math.abs(p.x - center.x) > half.x + 0.12) continue;
      if (Math.abs(p.y - center.y) > half.y + 0.2) continue;
      if (Math.abs(p.z - center.z) > half.z + 0.12) continue;
      const push = pushBase * mul;
      let fx = forward.x;
      let fy = forward.y;
      let fz = forward.z;
      if (spherePhysicsActive) {
        _beltFwd.set(forward.x, forward.y, forward.z);
        radialUpAt(sphereCenter, p, _beltUp);
        projectOntoTangentPlane(_beltFwd, _beltUp, _beltTan);
        const t2 = _beltTan.lengthSquared();
        if (t2 > 1e-10) {
          const inv = 1 / Math.sqrt(t2);
          fx = _beltTan.x * inv;
          fy = _beltTan.y * inv;
          fz = _beltTan.z * inv;
        }
        body.velocity.x += fx * fs * push * dt;
        body.velocity.y += fy * fs * push * dt;
        body.velocity.z += fz * fs * push * dt;
      } else {
        body.velocity.x += fx * fs * push * dt;
        body.velocity.z += fz * fs * push * dt;
        body.velocity.y += fy * fs * push * dt * 0.06;
      }
      body.wakeUp();
      break;
    }
  }
}

function orientedBoxExtentsInFrame(hx, hy, hz, rotM3, out) {
  const e = rotM3.elements;
  const r00 = Math.abs(e[0]),
    r01 = Math.abs(e[1]),
    r02 = Math.abs(e[2]);
  const r10 = Math.abs(e[3]),
    r11 = Math.abs(e[4]),
    r12 = Math.abs(e[5]);
  const r20 = Math.abs(e[6]),
    r21 = Math.abs(e[7]),
    r22 = Math.abs(e[8]);
  out.x = r00 * hx + r01 * hy + r02 * hz;
  out.y = r10 * hx + r11 * hy + r12 * hz;
  out.z = r20 * hx + r21 * hy + r22 * hz;
  return out;
}

function computeLogSawmillContainment(saw, mesh, body) {
  const half = computeLogHalfExtentsFromMesh(mesh);
  if (!half) return null;
  const { hx, hy, hz } = half;

  saw.updateMatrixWorld(true);
  const inv = _sawTmpM4Inv.copy(saw.matrixWorld).invert();
  tmpV.set(body.position.x, body.position.y, body.position.z).applyMatrix4(inv);

  _sawTmpQ.set(saw.quaternion.x, saw.quaternion.y, saw.quaternion.z, saw.quaternion.w);
  _sawTmpQ2.set(body.quaternion.x, body.quaternion.y, body.quaternion.z, body.quaternion.w);
  _sawTmpQ.invert();
  _sawTmpQ.multiply(_sawTmpQ2);
  _sawTmpM3.setFromMatrix4(_sawTmpM4Rot.makeRotationFromQuaternion(_sawTmpQ));
  const ext = orientedBoxExtentsInFrame(hx, hy, hz, _sawTmpM3, tmpV2);

  const tunnel = getSawmillTunnelAabbLocal(saw);
  const margin = -0.02;
  const inside =
    tmpV.x + ext.x <= tunnel.xMax - margin &&
    tmpV.x - ext.x >= tunnel.xMin + margin &&
    Math.abs(tmpV.z) + ext.z <= tunnel.halfZ - margin &&
    Math.abs(tmpV.y - tunnel.centerY) + ext.y <= tunnel.halfY - margin;

  return { inside, tmpV, ext };
}

function logIsFullyInsideAnySawmillTunnel(mesh, body) {
  if (!sawmillsRoot || !sawmillsRoot.children.length) return false;
  const logPlanet = mesh.userData.planetId ?? body.userData?.planetId;
  for (const saw of sawmillsRoot.children) {
    if (logPlanet != null && saw.userData?.planetId != null && saw.userData.planetId !== logPlanet) continue;
    const c = computeLogSawmillContainment(saw, mesh, body);
    if (c?.inside) return true;
  }
  return false;
}

/** Sawdust cloud: many small points — count × point size tuned together. */
const SAWMILL_FOG_PARTICLE_COUNT = 8400;
const SAWMILL_FOG_POINT_SIZE = 0.056;

let _sawmillFogParticleTextureShared = null;

/** Soft circular sprite — warm amber/yellow sawdust (higher-res canvas so tiny billboards stay smooth). */
function createSawmillFogParticleTexture() {
  if (_sawmillFogParticleTextureShared) return _sawmillFogParticleTextureShared;
  const c = document.createElement('canvas');
  const sz = 128;
  c.width = c.height = sz;
  const ctx = c.getContext('2d');
  const h = sz * 0.5;
  const grd = ctx.createRadialGradient(h, h, 0, h, h, h);
  grd.addColorStop(0, 'rgba(255, 228, 140, 0.72)');
  grd.addColorStop(0.28, 'rgba(255, 190, 70, 0.38)');
  grd.addColorStop(0.62, 'rgba(230, 150, 40, 0.14)');
  grd.addColorStop(1, 'rgba(255, 200, 80, 0)');
  ctx.fillStyle = grd;
  ctx.fillRect(0, 0, sz, sz);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.minFilter = THREE.LinearFilter;
  tex.magFilter = THREE.LinearFilter;
  tex.needsUpdate = true;
  _sawmillFogParticleTextureShared = tex;
  return tex;
}

/** Local-space tunnel box for sawdust spawn + motion (matches {@link ensureSawmillParticles}). */
function getSawmillFogTunnelLocalParams() {
  const hw = SAWMILL.tunnelW / 2;
  const hh = SAWMILL.tunnelH / 2;
  const tExtra = SAWMILL.tunnelBackExtra;
  const hl = (SAWMILL.tunnelD + tExtra) / 2;
  const tCx = -tExtra / 2;
  const yMid = 0.52;
  const mx = hl * 0.98;
  const my = hh * 0.98;
  const mz = hw * 0.98;
  return { tCx, yMid, mx, my, mz };
}

function randomSawmillFogLocalPos(out, p) {
  out[0] = p.tCx + (Math.random() * 2 - 1) * p.mx;
  out[1] = p.yMid + (Math.random() * 2 - 1) * p.my;
  out[2] = (Math.random() * 2 - 1) * p.mz;
}

function randomSawmillFogVelocity(out) {
  /** Drift with the machine: mostly −local X (toward blade / exit), a bit of rise and wobble — not orbiting the whole group. */
  out[0] = -0.35 + (Math.random() * 2 - 1) * 0.55;
  out[1] = 0.08 + (Math.random() * 2 - 1) * 0.35;
  out[2] = (Math.random() * 2 - 1) * 0.45;
}

function ensureSawmillParticles(grp) {
  if (!grp || grp.userData.sawmillParticles) return;
  const n = SAWMILL_FOG_PARTICLE_COUNT;
  const pos = new Float32Array(n * 3);
  const vel = new Float32Array(n * 3);
  const col = new Float32Array(n * 3);
  const p = getSawmillFogTunnelLocalParams();
  const tmp = [0, 0, 0];
  for (let i = 0; i < n; i++) {
    randomSawmillFogLocalPos(tmp, p);
    pos[i * 3] = tmp[0];
    pos[i * 3 + 1] = tmp[1];
    pos[i * 3 + 2] = tmp[2];
    randomSawmillFogVelocity(tmp);
    vel[i * 3] = tmp[0];
    vel[i * 3 + 1] = tmp[1];
    vel[i * 3 + 2] = tmp[2];
    const u = Math.random();
    const v = Math.random();
    /** Sawdust: golden yellow with a little orange spread (multiplies soft sprite map). */
    col[i * 3] = 0.92 + u * 0.08;
    col[i * 3 + 1] = 0.52 + v * 0.38;
    col[i * 3 + 2] = 0.06 + (u + v) * 0.22;
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  const mat = new THREE.PointsMaterial({
    map: createSawmillFogParticleTexture(),
    size: SAWMILL_FOG_POINT_SIZE,
    sizeAttenuation: true,
    vertexColors: true,
    transparent: true,
    opacity: 0.3,
    depthWrite: false,
    blending: THREE.NormalBlending,
    alphaTest: 0.008,
  });
  const pts = new THREE.Points(geo, mat);
  pts.name = 'sawmillFog';
  pts.userData.sawmillParticleVel = vel;
  grp.add(pts);
  grp.userData.sawmillParticles = pts;
}

const _sawFogTmp = [0, 0, 0];

function updateSawmillFogParticles(sawmillGrp, dt) {
  const pts = sawmillGrp.userData.sawmillParticles;
  if (!pts?.geometry?.attributes?.position) return;
  const vel = pts.userData.sawmillParticleVel;
  if (!vel) return;
  const posAttr = pts.geometry.attributes.position;
  const pos = posAttr.array;
  const n = pos.length / 3;
  const p = getSawmillFogTunnelLocalParams();
  const xMin = p.tCx - p.mx;
  const xMax = p.tCx + p.mx;
  const yMin = p.yMid - p.my;
  const yMax = p.yMid + p.my;
  const zMin = -p.mz;
  const zMax = p.mz;
  const margin = 0.08;
  const brownian = 1.25;
  const biasX = -0.18;
  const biasY = 0.05;
  const damp = Math.exp(-2.4 * dt);
  const vMax = 1.15;

  for (let i = 0; i < n; i++) {
    const ix = i * 3;
    let vx = vel[ix];
    let vy = vel[ix + 1];
    let vz = vel[ix + 2];
    vx += biasX * dt + (Math.random() * 2 - 1) * brownian * dt;
    vy += biasY * dt + (Math.random() * 2 - 1) * brownian * dt;
    vz += (Math.random() * 2 - 1) * brownian * dt;
    vx *= damp;
    vy *= damp;
    vz *= damp;
    const sp = Math.hypot(vx, vy, vz);
    if (sp > vMax) {
      const k = vMax / sp;
      vx *= k;
      vy *= k;
      vz *= k;
    }
    vel[ix] = vx;
    vel[ix + 1] = vy;
    vel[ix + 2] = vz;
    let px = pos[ix] + vx * dt;
    let py = pos[ix + 1] + vy * dt;
    let pz = pos[ix + 2] + vz * dt;
    if (
      px < xMin - margin ||
      px > xMax + margin ||
      py < yMin - margin ||
      py > yMax + margin ||
      pz < zMin - margin ||
      pz > zMax + margin
    ) {
      randomSawmillFogLocalPos(_sawFogTmp, p);
      px = _sawFogTmp[0];
      py = _sawFogTmp[1];
      pz = _sawFogTmp[2];
      randomSawmillFogVelocity(_sawFogTmp);
      vel[ix] = _sawFogTmp[0];
      vel[ix + 1] = _sawFogTmp[1];
      vel[ix + 2] = _sawFogTmp[2];
    }
    pos[ix] = px;
    pos[ix + 1] = py;
    pos[ix + 2] = pz;
  }
  posAttr.needsUpdate = true;
}

/**
 * @param {object} snap width/height/depth + logSkin — from log geometry before unregister.
 * @returns {object | null}
 */
function computeSawmillPlankSpec(snap, sawmillGrp) {
  const w = Number(snap.width);
  const h = Number(snap.height);
  const d = Number(snap.depth);
  if (!Number.isFinite(w) || !Number.isFinite(h) || !Number.isFinite(d)) return null;
  /** Treat log as a box; volume drives how long the plank is after cutting. */
  const Vlog = Math.max(1e-6, w * h * d);

  const px = clampSawmillXY(Number(sawmillGrp.userData.plankX ?? 1));
  const py = clampSawmillXY(Number(sawmillGrp.userData.plankY ?? 1));
  const sameXY = Math.abs(px - py) < 0.051;
  /**
   * Panel X/Y set cross-section only (thin 1×1 → skinny strip; wider X → thicker cut).
   * Length = volume / area so the same wood yields a longer strip when thin, shorter when wide.
   * ~25% larger cross-section at X=Y=1 than the prior 0.11 scale (user: 1 means 1, not 0.8).
   */
  const unit = 0.11 * 1.25;
  let plankW;
  let plankT;
  if (sameXY) {
    const s = Math.max(0.035, Math.min(0.95, px * unit));
    plankW = s;
    plankT = s;
  } else {
    plankW = Math.max(0.035, Math.min(1.35, px * unit));
    plankT = Math.max(0.035, Math.min(0.55, py * unit));
  }

  const crossArea = Math.max(1e-5, plankW * plankT);
  const yieldMul = 0.9;
  let plankL = (Vlog * yieldMul) / crossArea;
  plankL = Math.max(0.06, Math.min(6.2, plankL));

  const skin = normalizeLogSkin(snap.logSkin);
  sawmillGrp.updateMatrixWorld(true);
  const bl = SAWMILL.beltLen;
  const beltCenterLocalX = getSawmillExternalBeltCenterLocalX();
  const past = SAWMILL.plankSpawnPastBeltCenter ?? 0.35;
  const beltTopY = 0.12 + 0.08;
  const crossHalfUp = 0.5 * Math.max(plankW, plankT);
  const outLocal = new THREE.Vector3(
    beltCenterLocalX - bl * past,
    beltTopY + crossHalfUp + 0.02,
    0
  );
  const outWorld = outLocal.clone().applyMatrix4(sawmillGrp.matrixWorld);
  /** Horizontal exit (−local X): plank length aligns with this, not world up. */
  const exitDirWorld = new THREE.Vector3(-1, 0, 0).transformDirection(sawmillGrp.matrixWorld).normalize();
  const plankQuat = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(1, 0, 0), exitDirWorld);
  const mass = Math.max(0.12, Vlog * 520);
  return { plankW, plankL, plankT, skin, outWorld, exitDirWorld, plankQuat, mass };
}

function attachPlankDynamicBody(meshP, spec) {
  const { plankW, plankL, plankT, mass, outWorld, plankQuat } = spec;
  const shape = new CANNON.Box(new CANNON.Vec3(plankL / 2, plankW / 2, plankT / 2));
  const b = new CANNON.Body({
    mass,
    material: woodPhyMat ?? undefined,
  });
  b.addShape(shape);
  meshP.position.copy(outWorld);
  meshP.quaternion.copy(plankQuat);
  meshP.scale.set(1, 1, 1);
  b.position.set(outWorld.x, outWorld.y, outWorld.z);
  b.quaternion.set(plankQuat.x, plankQuat.y, plankQuat.z, plankQuat.w);
  b.linearDamping = 0.72;
  b.angularDamping = 0.92;
  world.addBody(b);
  registerDynamic(meshP, b, { kind: 'plank' });
  meshP.userData.sellable = true;
  meshP.userData.fromSawmill = true;
  b.velocity.set(0, 0, 0);
  b.angularVelocity.set(0, 0, 0);
  b.wakeUp();
}

/** Instant spawn (e.g. debug); normal sawmill path uses {@link beginSawmillPlankReveal}. */
function spawnPlankFromWoodSnap(snap, sawmillGrp) {
  const spec = computeSawmillPlankSpec(snap, sawmillGrp);
  if (!spec) return;
  const geo = new THREE.BoxGeometry(spec.plankL, spec.plankW, spec.plankT);
  const meshP = new THREE.Mesh(geo, makePlankSurfaceMaterial(spec.skin));
  meshP.userData.logSkin = spec.skin;
  meshP.castShadow = true;
  meshP.receiveShadow = true;
  meshP.position.copy(spec.outWorld);
  meshP.quaternion.copy(spec.plankQuat);
  if (spec.skin === 'dark' || spec.skin === 'void') enableNeonBloomOnMesh(meshP);
  planetMeshParent().add(meshP);
  attachPlankDynamicBody(meshP, spec);
}

function beginSawmillPlankReveal(snap, sawmillGrp) {
  const spec = computeSawmillPlankSpec(snap, sawmillGrp);
  if (!spec) return;
  const geo = new THREE.BoxGeometry(spec.plankL, spec.plankW, spec.plankT);
  const meshP = new THREE.Mesh(geo, makePlankSurfaceMaterial(spec.skin));
  meshP.userData.logSkin = spec.skin;
  meshP.castShadow = true;
  meshP.receiveShadow = true;
  const scale0 = 0.04;
  meshP.quaternion.copy(spec.plankQuat);
  meshP.scale.set(scale0, 1, 1);
  const backFace = spec.outWorld.clone().sub(spec.exitDirWorld.clone().multiplyScalar(spec.plankL / 2));
  meshP.position.copy(backFace).add(spec.exitDirWorld.clone().multiplyScalar(spec.plankL * scale0 * 0.5));
  if (spec.skin === 'dark' || spec.skin === 'void') enableNeonBloomOnMesh(meshP);
  planetMeshParent().add(meshP);
  sawmillPlankRevealJobs.push({
    mesh: meshP,
    spec,
    backFace,
    t: 0,
    duration: 0.9,
  });
}

function easeOutCubic(t) {
  return 1 - Math.pow(1 - t, 3);
}

function updateSawmillPlankReveals(dt) {
  if (gamePaused || sawmillPlankRevealJobs.length === 0) return;
  for (let i = sawmillPlankRevealJobs.length - 1; i >= 0; i--) {
    const job = sawmillPlankRevealJobs[i];
    job.t += dt;
    const u = Math.min(1, job.t / job.duration);
    const e = easeOutCubic(u);
    const xScale = 0.04 + (1 - 0.04) * e;
    job.mesh.scale.set(xScale, 1, 1);
    const L = job.spec.plankL;
    const dir = job.spec.exitDirWorld;
    /** addScaledVector = same math as add(dir.clone().multiplyScalar(...)) without the per-frame alloc. */
    job.mesh.position.copy(job.backFace).addScaledVector(dir, L * xScale * 0.5);
    if (u >= 1) {
      attachPlankDynamicBody(job.mesh, job.spec);
      sawmillPlankRevealJobs.splice(i, 1);
    }
  }
}

function updateSawmills(dt) {
  if (!sawmillsRoot || !sawmillsRoot.children.length) return;
  if (gamePaused) return;

  for (const s of sawmillsRoot.children) {
    /** Sawmills on other planets are hidden by {@link refreshPerPlanetContentVisibility} —
     *  skip their 8400-particle fog sim and side-panel texture upkeep; nothing is visible. */
    if (!s.visible) continue;
    ensureSawmillParticles(s);
    updateSawmillFogParticles(s, dt);
    if (s.userData.sawmillSidePanel) updateSawmillSidePanel(s);
  }

  for (const { mesh, body } of dynamicPairs) {
    const k = mesh.userData.kind;
    if (k !== 'log' && k !== 'trunk') continue;
    if (grabTarget === body) continue;
    if (body.userData?.inSawmill) continue;
    const itemPlanet = mesh.userData.planetId ?? body.userData?.planetId;
    if (itemPlanet != null && itemPlanet !== runtimePlanetId) continue;

    const snapDims = getLogSnapDimensionsForSawmill(mesh);
    if (!snapDims) continue;

    for (const saw of sawmillsRoot.children) {
      if (saw.userData?.planetId != null && itemPlanet != null && saw.userData.planetId !== itemPlanet) continue;
      if (saw.userData?.planetId != null && itemPlanet == null && saw.userData.planetId !== runtimePlanetId) continue;
      const c = computeLogSawmillContainment(saw, mesh, body);
      if (!c || !c.inside) continue;
      body.userData = body.userData || {};
      body.userData.inSawmill = true;
      const snap = {
        width: snapDims.width,
        height: snapDims.height,
        depth: snapDims.depth,
        logSkin: mesh.userData.logSkin || 'wood',
      };
      unregisterDynamic(mesh, body);
      beginSawmillPlankReveal(snap, saw);
      break;
    }
  }
}

function spawnMapEditorPlacements(mapPropPhyMat) {
  const slice = getActiveMapWorldSlice();
  if (!slice) return;

  conveyorBeltZones = [];

  if (mapEditorWorldGroup) {
    mapEditorWorldGroup.removeFromParent();
    mapEditorWorldGroup.traverse((o) => {
      if (o.geometry) o.geometry.dispose();
      if (o.material) {
        const m = o.material;
        if (Array.isArray(m)) m.forEach((x) => x.dispose?.());
        else m.dispose?.();
      }
    });
    mapEditorWorldGroup = null;
  }

  const props = slice.props || [];
  if (props.length === 0) return;

  const root = new THREE.Group();
  root.name = 'MapEditorPlacements';

  for (const p of props) {
    if (!p || !p.kind) continue;
    if (p.kind === 'conveyor') {
      const def = MAP_PROP_KINDS.conveyor;
      const bw = def.baseW;
      const bh = def.baseH;
      const bd = def.baseD;
      const w = p.sx != null && Number(p.sx) > 0 ? Number(p.sx) : bw;
      const h = p.sy != null && Number(p.sy) > 0 ? Number(p.sy) : bh;
      const d = p.sz != null && Number(p.sz) > 0 ? Number(p.sz) : bd;
      const pdata = tryGetPrefabDataForKind('conveyor');
      let grp;
      if (pdata && Array.isArray(pdata.parts) && pdata.parts.length > 0) {
        grp = deserializePrefabGroup(pdata);
        const pb = pdata.base || { w: 1, h: 1, d: 1 };
        grp.scale.set(
          w / Math.max(0.01, pb.w),
          h / Math.max(0.01, pb.h),
          d / Math.max(0.01, pb.d)
        );
      } else {
        grp = buildMapEditorConveyorGroup(w, h, d);
      }
      grp.name = grp.name || 'MapConveyor';
      placeOriginGroupOnPlanetSurface(grp, p.x, p.y, p.z, Number(p.ry) || 0, root);
      grp.rotation.set(Number(p.rx) || 0, Number(p.ry) || 0, Number(p.rz) || 0);
      grp.userData.planetId = runtimePlanetId;
      grp.updateMatrixWorld(true);
      const col = computeWorldAabbCollider(grp);
      const body = new CANNON.Body({ mass: 0, material: mapPropPhyMat });
      body.addShape(new CANNON.Box(new CANNON.Vec3(col.half.x, col.half.y, col.half.z)));
      body.position.set(col.center.x, col.center.y, col.center.z);
      body.userData = body.userData || {};
      body.userData.planetId = runtimePlanetId;
      world.addBody(body);
      registerConveyorBeltZone(grp);
      continue;
    }

    if (p.kind === 'label') {
      const text = String(p.text ?? 'Hi');
      const bg = p.bg;
      const fg = p.fg;
      const { tex, aspect } = buildLabelSpriteTextureForGame(text, bg, fg);
      const mat = new THREE.SpriteMaterial({
        map: tex,
        transparent: true,
        depthWrite: false,
        opacity: 0.98,
      });
      const sprite = new THREE.Sprite(mat);
      const baseH = 1.35;
      sprite.scale.set(baseH * aspect, baseH, 1);
      sprite.position.set(0, 1.25, 0);
      const grp = new THREE.Group();
      grp.add(sprite);
      grp.name = 'MapLabel';
      placeOriginGroupOnPlanetSurface(grp, p.x, p.y, p.z, Number(p.ry) || 0, root);
      const sx = p.sx != null ? Number(p.sx) : 1;
      const sy = p.sy != null ? Number(p.sy) : 1;
      grp.scale.set(Math.max(0.15, sx), Math.max(0.15, sy), 1);
      continue;
    }

    if (p.kind === 'ramp') {
      const def = MAP_PROP_KINDS.ramp;
      const bw = def.baseW;
      const bh = def.baseH;
      const bd = def.baseD;
      const sx = (p.sx != null ? Number(p.sx) : bw) / bw;
      const sy = (p.sy != null ? Number(p.sy) : bh) / bh;
      const sz = (p.sz != null ? Number(p.sz) : bd) / bd;
      const geo = new THREE.BoxGeometry(def.baseW, def.baseH, def.baseD);
      const mesh = new THREE.Mesh(
        geo,
        new THREE.MeshStandardMaterial({
          color: def.color,
          roughness: 0.88,
          metalness: 0.06,
        })
      );
      mesh.castShadow = true;
      mesh.receiveShadow = true;
      mesh.scale.set(sx, sy, sz);
      applyRampMeshTiltScaledGame(mesh, def, sx, sy, sz);
      const grp = new THREE.Group();
      grp.add(mesh);
      grp.name = 'MapRamp';
      placeOriginGroupOnPlanetSurface(grp, p.x, p.y, p.z, Number(p.ry) || 0, root);
      grp.rotation.set(Number(p.rx) || 0, Number(p.ry) || 0, Number(p.rz) || 0);
      const w = bw * sx;
      const h = bh * sy;
      const d = bd * sz;
      const body = new CANNON.Body({ mass: 0, material: mapPropPhyMat });
      body.addShape(new CANNON.Box(new CANNON.Vec3(w / 2, h / 2, d / 2)));
      syncStaticBodyToMeshWorld(mesh, body);
      body.userData = body.userData || {};
      body.userData.planetId = runtimePlanetId;
      world.addBody(body);
      continue;
    }

    const def = MAP_PROP_KINDS[p.kind];
    if (!def) continue;
    const bw = def.baseW;
    const bh = def.baseH;
    const bd = def.baseD;
    const sx = (p.sx != null ? Number(p.sx) : bw) / bw;
    const sy = (p.sy != null ? Number(p.sy) : bh) / bh;
    const sz = (p.sz != null ? Number(p.sz) : bd) / bd;
    const w = bw * sx;
    const h = bh * sy;
    const d = bd * sz;
    const geo = new THREE.BoxGeometry(w, h, d);
    const mesh = new THREE.Mesh(
      geo,
      new THREE.MeshStandardMaterial({
        color: def.color,
        roughness: 0.88,
        metalness: 0.06,
      })
    );
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    const grp = new THREE.Group();
    grp.add(mesh);
    mesh.position.y = h / 2;
    grp.name = `MapProp_${p.kind}`;
    placeOriginGroupOnPlanetSurface(grp, p.x, p.y, p.z, Number(p.ry) || 0, root);
    grp.rotation.set(Number(p.rx) || 0, Number(p.ry) || 0, Number(p.rz) || 0);

    const hx = w / 2;
    const hy = h / 2;
    const hz = d / 2;
    const body = new CANNON.Body({ mass: 0, material: mapPropPhyMat });
    body.addShape(new CANNON.Box(new CANNON.Vec3(hx, hy, hz)));
    syncStaticBodyToMeshWorld(mesh, body);
    body.userData = body.userData || {};
    body.userData.planetId = runtimePlanetId;
    world.addBody(body);
  }

  root.userData.planetId = runtimePlanetId;
  planetMeshParent().add(root);
  mapEditorWorldGroup = root;
  refreshSawmillFrontConveyorBeltDirections();
}

function spawnDefaultStarterTrees() {
  const positions = [
    [-6, -4],
    [5, -6],
    [-10, 6],
    [8, 4],
    [0, -12],
    [-14, -10],
    [12, -2],
  ];
  for (const [x, z] of positions) createTree(x, z);
}

function removeAllTreesAndLeavesFromWorld() {
  const pairs = [...dynamicPairs];
  for (const { mesh, body } of pairs) {
    const k = mesh.userData.kind;
    if (k === 'leaves' || k === 'trunk') {
      unregisterDynamic(mesh, body);
    }
  }
  /** Also nuke every planet's instanced procedural forest — applies when the user flips tree
   *  generation mode (lt2 ↔ classic) or invokes a full world wipe via {@link applyTreeGenerationModeAfterLoad}. */
  const planetIds = [..._planetForestGroups.keys()];
  for (const pid of planetIds) removeInstancedForestForPlanet(pid);
}

/**
 * Like {@link removeAllTreesAndLeavesFromWorld} but only wipes trunks/leaves tagged with
 * `planetId`. Used during per-planet repopulation on warp so we don't nuke neighboring planets'
 * forests when re-seeding the destination.
 */
function removeTreesAndLeavesForPlanet(planetId) {
  if (!planetId) return;
  const pairs = [...dynamicPairs];
  for (const { mesh, body } of pairs) {
    const k = mesh.userData.kind;
    if (k !== 'leaves' && k !== 'trunk') continue;
    if (mesh.userData.planetId !== planetId) continue;
    unregisterDynamic(mesh, body);
  }
  /** Also drop this planet's instanced procedural forest so {@link spawnProceduralTreesFromBiomes}
   *  has a clean slate to rebuild. Shared prototype geometry stays cached — cheap to reuse. */
  removeInstancedForestForPlanet(planetId);
}

/** After a save load, classic trunks may exist — swap to LT2 canopy at the same map placements. */
function applyTreeGenerationModeAfterLoad() {
  if (getTreeGenerationMode() !== 'lt2') return;
  removeAllTreesAndLeavesFromWorld();
  const doc = getMapEditorData();
  const slice = doc ? getActiveMapWorldSlice() : null;
  if (slice && Array.isArray(slice.trees) && slice.trees.length > 0) {
    spawnTreesFromMapRows(slice.trees);
  } else if (doc == null || slice == null || slice.trees === undefined) {
    spawnDefaultStarterTrees();
  }
}

function spawnTreesFromMapRows(rows) {
  if (!Array.isArray(rows)) return;
  for (const row of rows) {
    if (Array.isArray(row) && row.length >= 2) {
      createTreeFromMapEntry({ x: row[0], z: row[1] });
    } else if (
      row &&
      typeof row === 'object' &&
      !Array.isArray(row) &&
      typeof row.x === 'number' &&
      typeof row.z === 'number'
    ) {
      createTreeFromMapEntry(row);
    }
  }
}

/** New game only: map trees if present, else classic starters. */
function spawnMapTreesOrDefault() {
  const doc = getMapEditorData();
  if (doc == null) {
    spawnDefaultStarterTrees();
    return;
  }
  const slice = getActiveMapWorldSlice();
  if (slice == null || slice.trees === undefined) {
    spawnDefaultStarterTrees();
    return;
  }
  if (Array.isArray(slice.trees)) {
    if (slice.trees.length === 0) return;
    spawnTreesFromMapRows(slice.trees);
    return;
  }
  spawnDefaultStarterTrees();
}

/**
 * Map editor JSON (localStorage) is authoritative for tree layout + logSkin.
 * Saved games still restore money/logs/player, but trunks/leaves from the save are
 * replaced whenever the map blob includes a `trees` array (even empty). Only wipes + respawns
 * the **currently active** planet's trees so neighboring populated planets keep their forests.
 */
function replaceTreesFromMapEditorStorage() {
  const doc = getMapEditorData();
  if (!doc) return;
  const slice = getActiveMapWorldSlice();
  if (!slice || slice.trees === undefined || !Array.isArray(slice.trees)) return;
  removeTreesAndLeavesForPlanet(runtimePlanetId);
  spawnTreesFromMapRows(slice.trees);
}

/**
 * Toggle visibility of plots / sawmills / blueprints so only the **active** planet's
 * gameplay objects show. Trees stay visible on every planet so distant forests still read in the
 * sky — they're cheap now that procedural trees skip branch colliders. Called after any change
 * to `runtimePlanetId` (warp, init).
 *
 * - `landPlotsRoot` is authored at init time for one planet only, so we toggle the whole root
 *   based on its stored `planetId`. Per-plot tagging isn't needed because there's only ever one
 *   plot set per runtime.
 * - `sawmillsRoot` / `plotBlueprintsRoot` both parent multiple planet-tagged children, so we
 *   walk their children and flip each one's `.visible` flag independently. A sawmill placed on
 *   Home gets `planetId='home'`, so when the player warps to Xenon it vanishes (and its static
 *   CANNON body gets skipped by raycasts because it's far away anyway — but we leave the body
 *   registered so returning to Home restores the scene cleanly without reshaping the world).
 */
function refreshPerPlanetContentVisibility() {
  if (!spherePhysicsActive) return;
  const active = runtimePlanetId;
  if (landPlotsRoot) {
    const pid = landPlotsRoot.userData?.planetId;
    landPlotsRoot.visible = !pid || pid === active;
  }
  if (sawmillsRoot) {
    for (const child of sawmillsRoot.children) {
      const pid = child.userData?.planetId;
      child.visible = !pid || pid === active;
    }
  }
  if (plotBlueprintsRoot) {
    for (const child of plotBlueprintsRoot.children) {
      const pid = child.userData?.planetId;
      child.visible = !pid || pid === active;
    }
  }
}

/**
 * Tracks which planets have had their authored + procedural trees seeded this session.
 * Prevents re-spawning the forest every time the user warps back to a planet they've already
 * visited — if they chopped trees there, those stay chopped. First visit seeds, later visits
 * no-op. `force: true` lets the init path (re-)seed even if the set already lists the id.
 * @type {Set<string>}
 */
const populatedPlanetIds = new Set();

/**
 * Seed a specific planet with its authored + biome-procedural trees. Safely retargets the
 * sphere-helper globals (`runtimePlanetId` / `sphereCenter` / `spherePlayRadius`) for the
 * duration of the spawn so tree math uses THIS planet's center, then restores them so the
 * caller (e.g. the main physics loop) sees no change. Does nothing if the planet is already
 * populated this session unless `opts.force` is set.
 */
function populatePlanetContent(planetId, opts) {
  if (!planetId || !spherePhysicsActive) return;
  const force = !!(opts && opts.force);
  if (populatedPlanetIds.has(planetId) && !force) return;
  const entry = planetPhysicsById.get(planetId);
  if (!entry) return;
  const doc = getMapEditorData();
  const slice = doc ? getWorldSliceFromDocument(doc, planetId) : null;

  const savedPid = runtimePlanetId;
  const savedCx = sphereCenter.x;
  const savedCy = sphereCenter.y;
  const savedCz = sphereCenter.z;
  const savedR = spherePlayRadius;
  runtimePlanetId = planetId;
  sphereCenter.set(entry.center.x, entry.center.y, entry.center.z);
  spherePlayRadius = entry.radius;
  try {
    /** Force re-seed: wipe only THIS planet's trees (not neighbors' forests) before respawn. */
    removeTreesAndLeavesForPlanet(planetId);
    /** Authored trees: whatever the map editor saved for this planet (may be empty or undefined). */
    if (slice && Array.isArray(slice.trees) && slice.trees.length > 0) {
      spawnTreesFromMapRows(slice.trees);
    } else if (!slice || slice.trees === undefined) {
      /** No authored list at all → starter trees (only meaningful on "Home" first load). */
      spawnDefaultStarterTrees();
    }
    /** Biome-aware procedural forest for this specific planet. */
    if (slice) spawnProceduralTreesFromBiomes(slice);
  } finally {
    runtimePlanetId = savedPid;
    sphereCenter.set(savedCx, savedCy, savedCz);
    spherePlayRadius = savedR;
  }
  populatedPlanetIds.add(planetId);
  console.log(
    '%c[planet] populated',
    'background:#248;color:#fff;padding:2px 6px;border-radius:3px',
    { planetId, forced: force, totalPopulated: populatedPlanetIds.size },
  );
}

function makeParticleSpriteTexture() {
  const c = document.createElement('canvas');
  c.width = 64;
  c.height = 64;
  const ctx = c.getContext('2d');
  const grd = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
  grd.addColorStop(0, 'rgba(255,255,255,1)');
  grd.addColorStop(0.35, 'rgba(255,255,255,0.5)');
  grd.addColorStop(1, 'rgba(255,255,255,0)');
  ctx.fillStyle = grd;
  ctx.fillRect(0, 0, 64, 64);
  const tex = new THREE.CanvasTexture(c);
  tex.needsUpdate = true;
  return tex;
}

function createSellZoneParticles() {
  if (sellParticleGroup) {
    sellParticleGroup.removeFromParent();
    let sharedMap = null;
    sellParticleGroup.traverse((o) => {
      if (o.isPoints && o.material) {
        if (o.material.map && !sharedMap) sharedMap = o.material.map;
        o.geometry?.dispose();
        o.material.dispose();
      }
    });
    if (sharedMap) sharedMap.dispose();
    sellParticleGroup = null;
  }

  const spriteTex = makeParticleSpriteTexture();
  const group = new THREE.Group();
  group.name = 'SellZoneParticles';

  function fillLayer(n, rMul, yMin, yMax, pointSize, bright) {
    const pos = new Float32Array(n * 3);
    const col = new Float32Array(n * 3);
    const R = sellRadius * rMul;
    for (let i = 0; i < n; i++) {
      const a = Math.random() * Math.PI * 2;
      const t = Math.random();
      const rad = Math.sqrt(t) * R;
      pos[i * 3] = Math.cos(a) * rad;
      pos[i * 3 + 1] = yMin + Math.random() * (yMax - yMin);
      pos[i * 3 + 2] = Math.sin(a) * rad;
      const g1 = 0.25 + Math.random() * 0.55;
      const g2 = bright ? 0.65 + Math.random() * 0.35 : 0.35 + Math.random() * 0.4;
      col[i * 3] = 0.02 + Math.random() * 0.12;
      col[i * 3 + 1] = g1 * g2;
      col[i * 3 + 2] = 0.08 + Math.random() * 0.35;
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
    const mat = new THREE.PointsMaterial({
      size: pointSize,
      vertexColors: true,
      map: spriteTex,
      transparent: true,
      depthWrite: false,
      opacity: bright ? 0.75 : 0.55,
      blending: THREE.AdditiveBlending,
      sizeAttenuation: true,
    });
    const pts = new THREE.Points(geo, mat);
    pts.name = 'sellPts';
    group.add(pts);
  }

  /** Dense tiny sparkles */
  fillLayer(2200, 1.02, 0.04, 2.4, 0.065, false);
  /** Larger soft motes */
  fillLayer(160, 0.95, 0.1, 2.0, 0.22, true);

  group.userData.planetId = runtimePlanetId;
  refreshSellCenterWorld();
  /** Sphere worlds: park the sparkles under a surface-aligned anchor so the disc lies flat on the
   *  planet and the group itself stays free to spin about its own (now radial) Y axis. */
  if (planetSurfaceAnchorTransform(sellCenter.x, sellCenter.z, _surfAnchorPos, _surfAnchorQuat)) {
    const anchor = new THREE.Group();
    anchor.name = 'SellZoneAnchor';
    anchor.position.copy(_surfAnchorPos);
    anchor.quaternion.copy(_surfAnchorQuat);
    anchor.userData.planetId = runtimePlanetId;
    anchor.add(group);
    planetMeshParent().add(anchor);
    sellParticleAnchor = anchor;
  } else {
    group.position.set(sellCenter.x, 0, sellCenter.z);
    planetMeshParent().add(group);
    sellParticleAnchor = null;
  }
  sellParticleGroup = group;
}

/**
 * @param {string} [currentPlanetIdForMeta] If set (e.g. dev warp), written as `currentPlanetId` while dynamics still save under {@link runtimePlanetId}.
 */
function saveGame(currentPlanetIdForMeta) {
  const trees = [];
  for (const { mesh } of dynamicPairs) {
    if (mesh.userData.kind === 'trunk' && mesh.userData.trunk) {
      trees.push({
        ...mesh.userData.trunk,
        logSkin: mesh.userData.logSkin || 'wood',
        woodWeight: mesh.userData.woodWeight ?? 1,
        interior: mesh.userData.interior ?? null,
        interiorColor: mesh.userData.interiorColor ?? null,
        interiorNeon: !!mesh.userData.interiorNeon,
        trunkColor: mesh.userData.trunkColor ?? null,
        trunkTexture: mesh.userData.trunkTexture ?? null,
      });
    }
  }
  const logs = [];
  for (const { mesh, body } of dynamicPairs) {
    if (mesh.userData.kind !== 'log') continue;
    const g = mesh.geometry.parameters;
    const entry = {
      x: body.position.x,
      y: body.position.y,
      z: body.position.z,
      q: [body.quaternion.x, body.quaternion.y, body.quaternion.z, body.quaternion.w],
      h: g.height,
      logSkin: mesh.userData.logSkin || 'wood',
      woodWeight: mesh.userData.woodWeight ?? 1,
      interior: mesh.userData.interior ?? null,
      interiorColor: mesh.userData.interiorColor ?? null,
      interiorNeon: !!mesh.userData.interiorNeon,
      trunkColor: mesh.userData.trunkColor ?? null,
      trunkTexture: mesh.userData.trunkTexture ?? null,
    };
    if (g.width != null) entry.halfW = g.width / 2;
    else entry.r = g.radiusTop ?? g.radius;
    logs.push(entry);
  }
  const sawmills = [];
  if (sawmillsRoot) {
    for (const c of sawmillsRoot.children) {
      sawmills.push({
        x: c.position.x,
        z: c.position.z,
        ry: c.rotation.y,
        plankX: c.userData.plankX ?? 1,
        plankY: c.userData.plankY ?? 1,
      });
    }
  }
  const plotBlueprints = [];
  if (plotBlueprintsRoot) {
    for (const c of plotBlueprintsRoot.children) {
      const wt = c.userData.woodTally || {};
      plotBlueprints.push({
        id: String(c.userData.bpInstanceId || `bp_${c.position.x}_${c.position.z}`),
        bpId: String(c.userData.bpId || ''),
        x: c.position.x,
        y: c.position.y,
        z: c.position.z,
        ry: c.rotation.y,
        woodApplied: c.userData.woodApplied ?? 0,
        woodTally: { wood: wt.wood ?? 0, dark: wt.dark ?? 0, void: wt.void ?? 0 },
        complete: !!c.userData.bpComplete,
        contributingPlankNetIds: [...(c.userData.contributingPlankNetIds || [])],
      });
    }
  }
  const slicePid = String(runtimePlanetId || 'home');
  const metaPlanetId = String(currentPlanetIdForMeta || slicePid);
  let prev = null;
  try {
    const rawPrev = localStorage.getItem(SAVE_KEY);
    if (rawPrev) prev = migrateSaveToV2(JSON.parse(rawPrev));
  } catch (_) {}

  const worlds =
    prev?.worlds && typeof prev.worlds === 'object'
      ? JSON.parse(JSON.stringify(prev.worlds))
      : {};
  worlds[slicePid] = { trees, logs, sawmills, plotBlueprints };

  const poses =
    prev?.poses && typeof prev.poses === 'object' ? JSON.parse(JSON.stringify(prev.poses)) : {};
  poses[slicePid] = {
    x: playerBody.position.x,
    y: playerBody.position.y,
    z: playerBody.position.z,
    yaw,
    pitch,
  };

  const data = {
    saveFormatVersion: SAVE_FORMAT_VERSION,
    money,
    landPlots,
    landPlotId,
    landPlotSlotId,
    currentPlanetId: metaPlanetId,
    worlds,
    poses,
    nextNetId,
    nextTreeId,
    hotbar: [...hotbarSlots],
    hotbarSel: hotbarSelected,
  };
  try {
    localStorage.setItem(SAVE_KEY, JSON.stringify(data));
  } catch (err) {
    /** Quota exceeded / privacy mode: losing one autosave beats throwing out of gameplay paths —
     *  saveGame is called from the F key, sawmill controls, blueprint snaps and planet warps. */
    console.warn('[save] localStorage write failed (quota/privacy mode?) — save skipped', err);
  }
}

function loadGame() {
  const raw = localStorage.getItem(SAVE_KEY);
  if (!raw) return false;
  let data;
  try {
    data = JSON.parse(raw);
  } catch {
    return false;
  }
  const migrated = migrateSaveToV2(data);
  if (!migrated) return false;

  money = migrated.money ?? 0;
  landPlots = migrated.landPlots ?? 0;
  landPlotId = migrated.landPlotId != null ? migrated.landPlotId : null;
  landPlotSlotId = migrated.landPlotSlotId != null ? String(migrated.landPlotSlotId) : null;
  if (landPlots > 0 && landPlotId == null) landPlotId = 0;
  if (
    !landPlotSlotId &&
    landPlotId != null &&
    Number.isFinite(landPlotId) &&
    landPlotSites.length
  ) {
    const idx = Math.floor(landPlotId);
    if (idx >= 0 && idx < landPlotSites.length) landPlotSlotId = landPlotSites[idx].id;
  }
  nextNetId = migrated.nextNetId ?? 1;
  nextTreeId = migrated.nextTreeId ?? 1;

  const pid = String(runtimePlanetId || 'home');
  const pose = migrated.poses?.[pid];
  /**
   * When sphere physics is active, a saved pose is only trusted if it's within reasonable
   * radial bounds of the planet. Otherwise (e.g. saved during a broken physics session that
   * flung the player into deep space) we respawn at the pole instead of persisting the bug.
   */
  const poseIsValid = (px, py, pz) => {
    if (!spherePhysicsActive) return true;
    /** Only trust a saved pose if it's *close to* the active planet's surface (within 20 m below
     *  or 50 m above). This prevents a corrupted-save scenario where the player is e.g. halfway
     *  between Home and Xenon — gravity would yank them to whichever planet won the surface-
     *  distance check, looking like they get "sucked into the sky". Safer to respawn at pole. */
    const dx = px - sphereCenter.x;
    const dy = py - sphereCenter.y;
    const dz = pz - sphereCenter.z;
    const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
    const surfDist = dist - spherePlayRadius;
    return surfDist >= -20 && surfDist <= 50;
  };
  const applyPoleSpawn = () => {
    if (spherePhysicsActive) {
      const r = spherePlayRadius + 0.05 + PLAYER_BODY_HALF_H;
      playerBody.position.set(sphereCenter.x, sphereCenter.y + r, sphereCenter.z);
    } else {
      playerBody.position.set(0, PLAYER_SPAWN_Y, 8);
    }
    yaw = 0;
    pitch = 0;
    if (spherePhysicsActive) initPlayerHeadingOnPlanet();
  };
  if (pose && typeof pose.x === 'number' && poseIsValid(pose.x, pose.y ?? 0, pose.z ?? 0)) {
    playerBody.position.set(pose.x, pose.y ?? PLAYER_SPAWN_Y, pose.z ?? 5);
    yaw = pose.yaw ?? 0;
    pitch = pose.pitch ?? 0;
    if (spherePhysicsActive) initPlayerHeadingOnPlanet();
  } else if (
    data.player &&
    typeof data.player === 'object' &&
    poseIsValid(data.player.x ?? 0, data.player.y ?? 0, data.player.z ?? 0)
  ) {
    playerBody.position.set(data.player.x ?? 0, data.player.y ?? PLAYER_SPAWN_Y, data.player.z ?? 5);
    yaw = data.player.yaw ?? 0;
    pitch = data.player.pitch ?? 0;
    if (spherePhysicsActive) initPlayerHeadingOnPlanet();
  } else {
    if (pose || data.player) {
      console.warn('[planet] discarded stale save pose (too far from planet surface); respawning at pole.');
    }
    applyPoleSpawn();
  }
  /** Kill any residual velocity — an old save may have recorded orbital momentum. */
  playerBody.velocity.set(0, 0, 0);
  playerBody.angularVelocity.set(0, 0, 0);
  /** Final safety: whatever position we wound up with, verify that the *active* planet's gravity
   *  actually wins the closest-surface check at that position. If a neighbor's surface is closer,
   *  respawning at the active pole is the only way to avoid being yanked off the moment physics
   *  runs. Covers the edge case where the neighbor was just moved closer in the universe editor
   *  between save and load. */
  if (spherePhysicsActive && planetPhysicsEntries.length > 1) {
    const activeEntry = planetPhysicsById.get(runtimePlanetId);
    const best = findClosestPlanet(playerBody.position);
    if (activeEntry && best && best.planetId !== activeEntry.planetId) {
      console.warn(
        `[planet] spawn position would be captured by ${best.planetId}'s gravity (not active ${activeEntry.planetId}); forcing pole respawn.`,
      );
      applyPoleSpawn();
      playerBody.velocity.set(0, 0, 0);
      playerBody.angularVelocity.set(0, 0, 0);
    }
  }
  normalizeHotbarFromSave(migrated);

  while (dynamicPairs.length) {
    const { mesh, body } = dynamicPairs[dynamicPairs.length - 1];
    unregisterDynamic(mesh, body);
  }

  clearSawmillsFromWorld();

  function trunkGroupKey(t) {
    if (t.treeId != null && t.treeId !== '') return `id_${t.treeId}`;
    const xr = Math.round((t.x ?? 0) * 20);
    const zr = Math.round((t.z ?? 0) * 20);
    return `xz_${xr}_${zr}`;
  }

  const groups = new Map();
  const planetDyn = migrated.worlds[pid] ?? {
    trees: [],
    logs: [],
    sawmills: [],
    plotBlueprints: [],
  };
  const treesFromSave = planetDyn.trees ?? [];

  for (const t of treesFromSave) {
    const k = trunkGroupKey(t);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(t);
  }

  for (const [, segs] of groups) {
    let topSeg = null;
    let maxTop = -Infinity;
    const createdSegs = [];
    const onePieceTree = segs.length === 1;
    for (const t of segs) {
      const anchored =
        onePieceTree || (t.bottomY != null && t.bottomY < 0.22);
      const seg = createTrunkSegment(t, { anchored });
      if (!seg) continue;
      createdSegs.push(seg);
      if (t.topY > maxTop) {
        maxTop = t.topY;
        topSeg = seg;
      }
    }
    if (createdSegs.length > 1) {
      for (const s of createdSegs) s.mesh.userData.sellable = true;
    }
    if (!topSeg) continue;
    const tr = topSeg.mesh.userData.trunk;
    topSeg.mesh.userData.leafMesh = null;
    topSeg.mesh.userData.leafBody = null;
    attachTreeBranchesForGame(
      topSeg.mesh,
      topSeg.mesh.userData.logSkin || 'wood',
      tr.treeId,
      tr.x,
      tr.z
    );
  }

  for (const L of planetDyn.logs ?? []) {
    const q = new THREE.Quaternion(L.q[0], L.q[1], L.q[2], L.q[3]);
    let mesh;
    let body;
    if (L.halfW != null) {
      ({ mesh, body } = createBoxLog({
        centerY: L.y,
        height: L.h,
        halfW: L.halfW,
        x: L.x,
        z: L.z,
        quat: q,
        regKind: 'log',
        logSkin: normalizeLogSkin(L.logSkin),
        woodWeight: L.woodWeight,
        interior: L.interior ?? undefined,
        interiorColor: L.interiorColor ?? undefined,
        interiorNeon: L.interiorNeon,
        trunkColor: L.trunkColor ?? undefined,
        trunkTexture: L.trunkTexture ?? undefined,
      }));
    } else {
      ({ mesh, body } = createCylinderLog({
        centerY: L.y,
        height: L.h,
        radius: L.r,
        x: L.x,
        z: L.z,
        quat: q,
        regKind: 'log',
      }));
    }
    body.position.set(L.x, L.y, L.z);
    body.quaternion.set(L.q[0], L.q[1], L.q[2], L.q[3]);
    mesh.position.copy(body.position);
    mesh.quaternion.copy(body.quaternion);
  }

  updateMoneyHud();
  syncLandPlotVisuals();
  loadSawmillsFromSave(planetDyn.sawmills);
  loadPlotBlueprintsFromSave(planetDyn.plotBlueprints);
  return true;
}

/**
 * Instant warp: teleport the player's physics body to the surface of another planet.
 * No reload, no scene rebuild — the other planet is already a real body with its own gravity
 * from {@link initSpherePhysicsFromMapIfEnabled}, so we just move the player and update the
 * cached `sphereCenter` / `spherePlayRadius` / `runtimePlanetId` so single-planet code paths
 * (saves, stabilization snap, dev HUD) treat the new planet as active.
 *
 * Caller is expected to have validated that `planetId` is different from {@link runtimePlanetId}.
 * Returns `true` on success.
 */
function warpPlayerToPlanet(planetId) {
  const pid = String(planetId || '').trim();
  if (!pid) return false;
  const entry = planetPhysicsById.get(pid);
  if (!entry || !playerBody) {
    console.warn('[planet] warpPlayerToPlanet: no physics entry for', pid, '— rebuild required?');
    return false;
  }
  /** Persist dynamics on the planet we're leaving before we change runtime state. */
  try {
    saveGame(runtimePlanetId);
  } catch (_) {}
  /** Re-point the single-planet globals at the target planet. */
  runtimePlanetId = pid;
  spherePlayRadius = entry.radius;
  sphereCenter.set(entry.center.x, entry.center.y, entry.center.z);
  worldRegistry?.setActivePlanetId?.(pid);
  /** Choose a spawn direction: straight "up" (+Y) from the planet center works for any pose,
   *  and lands us at the planet's north pole — same convention as first-time spawn. */
  const feetY = entry.radius + 0.05 + PLAYER_BODY_HALF_H;
  playerBody.position.set(entry.center.x, entry.center.y + feetY, entry.center.z);
  playerBody.velocity.set(0, 0, 0);
  playerBody.angularVelocity.set(0, 0, 0);
  /** Reset camera pitch so you don't end up staring into the dirt after arriving at a new pole. */
  pitch = 0;
  /** Rebuild the tangent heading at the new location so "forward" on the arrival planet is a real
   *  tangent direction (not whatever stale heading pointed through the previous planet's core). */
  initPlayerHeadingOnPlanet();
  /**
   * Seed the destination's forest on first visit (authored + biome-procedural). Subsequent warps
   * back to the same planet skip this via `populatedPlanetIds`, so any trees the player chopped
   * there stay chopped for the session. This is the per-planet-forest feature in action.
   */
  populatePlanetContent(pid);
  /** Update dev HUD + save-slot so the UI reflects reality immediately. */
  refreshDevWarpPlanetList();
  refreshPerPlanetContentVisibility();
  try { saveGame(pid); } catch (_) {}
  /** Quick visual "whoosh": white flash overlay that auto-fades, so the teleport has feedback. */
  flashWarpEffect(entry.label || pid);
  console.log('%c[planet] warp', 'background:#906;color:#fff;padding:2px 6px;border-radius:3px', {
    to: pid,
    pos: { x: playerBody.position.x, y: playerBody.position.y, z: playerBody.position.z },
    radius: entry.radius,
  });
  return true;
}

/**
 * Tiny ~220ms white flash across the screen to punctuate a teleport. Pure UI effect — cheap,
 * non-blocking, skips itself if already mid-flash so rapid warps don't stack.
 */
function flashWarpEffect(label) {
  const existing = document.getElementById('lt2-warp-flash');
  if (existing) return;
  const el = document.createElement('div');
  el.id = 'lt2-warp-flash';
  el.style.cssText = [
    'position:fixed',
    'inset:0',
    'background:radial-gradient(circle at center, rgba(230,240,255,0.95) 0%, rgba(180,200,230,0.7) 40%, rgba(10,14,30,0) 85%)',
    'pointer-events:none',
    'z-index:40000',
    'opacity:1',
    'transition:opacity 320ms ease',
    'font:14px system-ui,sans-serif',
    'color:#e8ecff',
    'display:flex',
    'align-items:center',
    'justify-content:center',
    'text-align:center',
    'text-shadow:0 0 6px rgba(0,0,0,0.5)',
  ].join(';');
  el.textContent = label ? `Warping to ${label}` : '';
  document.body.appendChild(el);
  requestAnimationFrame(() => {
    el.style.opacity = '0';
    setTimeout(() => el.remove(), 360);
  });
}

/**
 * Rebuild the dev-menu warp dropdown from the current map-editor document.
 * Safe to call repeatedly; it preserves the currently selected option value when possible.
 */
function refreshDevWarpPlanetList() {
  const warpSel = document.getElementById('dev-planet-warp');
  if (!warpSel) return;
  const doc = getMapEditorData();
  const worlds = Array.isArray(doc?.worlds) ? doc.worlds : [];
  const previousValue = warpSel.value;
  warpSel.innerHTML = '';
  for (const w of worlds) {
    if (!w?.planetId) continue;
    const opt = document.createElement('option');
    opt.value = String(w.planetId);
    const lab = typeof w.label === 'string' && w.label ? w.label : w.planetId;
    opt.textContent = `${lab} (${w.planetId})`;
    warpSel.appendChild(opt);
  }
  if (warpSel.options.length) {
    const preferred = [previousValue, runtimePlanetId, doc?.defaultPlanetId, warpSel.options[0].value].find(
      (v) => v && [...warpSel.options].some((o) => o.value === v),
    );
    warpSel.value = preferred || warpSel.options[0].value;
  }
  /** Show "Currently on: X" so you can tell which planet you're actually standing on, even when two planets look alike. */
  const currentLabel = document.getElementById('dev-current-planet');
  if (currentLabel) {
    const w = worlds.find((x) => x && x.planetId === runtimePlanetId);
    const lab = typeof w?.label === 'string' && w.label ? w.label : runtimePlanetId;
    currentLabel.textContent = `Currently on: ${lab} (${runtimePlanetId})`;
  }
}

function setupDevMenuUi() {
  const root = document.getElementById('dev-menu');
  const toggle = document.getElementById('dev-menu-toggle');
  const posEl = document.getElementById('dev-pos');
  const flyCb = document.getElementById('dev-fly');

  toggle?.addEventListener('click', () => {
    root?.classList.toggle('dev-menu-open');
    if (root?.classList.contains('dev-menu-open')) {
      document.exitPointerLock();
      /** Rebuild the warp list every open so newly-added planets from the universe editor appear without requiring a page reload. */
      refreshDevWarpPlanetList();
    }
  });

  document.getElementById('dev-add-money')?.addEventListener('click', () => {
    money += 1000;
    updateMoneyHud();
  });
  document.getElementById('dev-spawn-tree')?.addEventListener('click', () => {
    if (!playerBody) return;
    createTree(playerBody.position.x, playerBody.position.z);
  });
  document.getElementById('dev-save')?.addEventListener('click', () => {
    saveGame();
  });
  document.getElementById('dev-wipe-save')?.addEventListener('click', () => {
    if (!confirm('Delete saved game (money, logs, land) and reload? Map editor data is not removed.')) return;
    try {
      localStorage.removeItem(SAVE_KEY);
    } catch (_) {}
    location.reload();
  });
  flyCb?.addEventListener('change', () => {
    setDevFlyMode(!!flyCb.checked);
  });
  /** Player-fly (body attached): independent of freecam. If the user turns freecam on and then
   *  player-fly, we keep freecam — it's the visibly-active one. */
  const playerFlyCb = /** @type {HTMLInputElement | null} */ (document.getElementById('dev-player-fly'));
  if (playerFlyCb) {
    playerFlyCb.checked = playerFlyMode;
    playerFlyCb.addEventListener('change', () => {
      playerFlyMode = !!playerFlyCb.checked;
      if (playerBody && playerFlyMode) {
        /** Zero the velocity as we enter fly mode so a falling player stops mid-air. */
        playerBody.velocity.set(0, 0, 0);
        playerBody.angularVelocity.set(0, 0, 0);
      }
    });
  }
  /** Walk speed slider (sprint is fixed at 1.5× walk). */
  const walkSlider = /** @type {HTMLInputElement | null} */ (document.getElementById('dev-walk-speed'));
  const walkVal = document.getElementById('dev-walk-speed-val');
  if (walkSlider) {
    walkSlider.value = String(Math.round(WALK_SPEED));
    if (walkVal) walkVal.textContent = String(Math.round(WALK_SPEED));
    walkSlider.addEventListener('input', () => {
      const v = Math.max(1, Math.min(200, Number(walkSlider.value) || 6));
      WALK_SPEED = v;
      SPRINT_SPEED = v * 1.5;
      if (walkVal) walkVal.textContent = String(Math.round(v));
      saveMovePrefs();
    });
  }
  /** Fly speed slider for player-fly mode (and reused as a scale for freecam too). */
  const flySlider = /** @type {HTMLInputElement | null} */ (document.getElementById('dev-fly-speed'));
  const flyVal = document.getElementById('dev-fly-speed-val');
  if (flySlider) {
    flySlider.value = String(Math.round(PLAYER_FLY_SPEED));
    if (flyVal) flyVal.textContent = String(Math.round(PLAYER_FLY_SPEED));
    flySlider.addEventListener('input', () => {
      const v = Math.max(1, Math.min(2000, Number(flySlider.value) || 40));
      PLAYER_FLY_SPEED = v;
      if (flyVal) flyVal.textContent = String(Math.round(v));
      saveMovePrefs();
    });
  }
  const sphereCb = document.getElementById('dev-sphere-physics');
  if (sphereCb) {
    try {
      const o = sessionStorage.getItem('LT2_SpherePhysicsOverride');
      if (o === '1') sphereCb.checked = true;
      else if (o === '0') sphereCb.checked = false;
      else sphereCb.checked = !!getActiveWorldEntryFromMap()?.useSpherePhysics;
    } catch (_) {
      sphereCb.checked = false;
    }
    sphereCb.addEventListener('change', () => {
      try {
        sessionStorage.setItem('LT2_SpherePhysicsOverride', sphereCb.checked ? '1' : '0');
      } catch (_) {}
      location.reload();
    });
  }

  const warpSel = document.getElementById('dev-planet-warp');
  const warpGo = document.getElementById('dev-planet-warp-go');
  if (warpSel && warpGo) {
    refreshDevWarpPlanetList();
    warpGo.addEventListener('click', () => {
      const pid = String(warpSel.value || '').trim();
      if (!pid) return;
      /** No-op if we're already on this planet. */
      if (pid === runtimePlanetId) return;
      warpPlayerToPlanet(pid);
    });
  }

  document.getElementById('dev-copy-pos')?.addEventListener('click', async () => {
    if (!playerBody) return;
    const t = `${playerBody.position.x.toFixed(2)}, ${playerBody.position.y.toFixed(2)}, ${playerBody.position.z.toFixed(2)}`;
    try {
      await navigator.clipboard.writeText(t);
    } catch (_) {}
  });

  syncDevFlyUi();

  const devBpPick = document.getElementById('dev-bp-pick');
  if (devBpPick) {
    devBpPick.innerHTML = BLUEPRINT_CATALOG.map(
      (b) =>
        `<option value="${escapeHtml(b.id)}">${escapeHtml(b.label)} (${b.baseWood} wood)</option>`
    ).join('');
  }
  document.getElementById('dev-bp-start-place')?.addEventListener('click', () => {
    const sel = document.getElementById('dev-bp-pick');
    const id = sel && sel.value ? String(sel.value) : 'floor_tile';
    tryBeginBlueprintPlacement(id);
  });

  function tickPos() {
    if (posEl && playerBody) {
      const p = playerBody.position;
      let extra = '';
      /** When sphere physics is active, append the current gravity target + surface distance so
       *  the user can immediately see whether another planet has stolen gravity from the active
       *  one (i.e. "why am I getting pulled up?"). */
      if (spherePhysicsActive) {
        const best = findClosestPlanet(p);
        if (best) {
          const dx = p.x - best.center.x;
          const dy = p.y - best.center.y;
          const dz = p.z - best.center.z;
          const surfDist = Math.sqrt(dx * dx + dy * dy + dz * dz) - best.radius;
          const tag = best.planetId === runtimePlanetId ? '' : ' [!]';
          extra = `  | g→${best.planetId}${tag} @ ${surfDist.toFixed(1)}m`;
        }
      }
      posEl.textContent = `x ${p.x.toFixed(1)}  y ${p.y.toFixed(1)}  z ${p.z.toFixed(1)}${extra}`;
    }
    requestAnimationFrame(tickPos);
  }
  tickPos();
}

function syncDevFlyUi() {
  const flyCb = document.getElementById('dev-fly');
  const flyHint = document.getElementById('dev-fly-hint');
  const freecamBtn = document.getElementById('btn-freecam');
  if (flyCb) flyCb.checked = devFlyMode;
  if (flyHint) flyHint.style.display = devFlyMode ? 'block' : 'none';
  if (freecamBtn) {
    freecamBtn.textContent = devFlyMode ? 'Freecam: ON' : 'Freecam: OFF';
    freecamBtn.setAttribute('aria-pressed', String(devFlyMode));
  }
}

function setDevFlyMode(on) {
  const next = !!on;
  if (next === devFlyMode) {
    syncDevFlyUi();
    return;
  }
  devFlyMode = next;
  if (playerBody) {
    freecamBodyAnchor.set(playerBody.position.x, playerBody.position.y, playerBody.position.z);
  }
  if (camera) {
    if (devFlyMode) {
      freecamMinFov = camera.fov;
      freecamPos.copy(camera.position);
      freecamBodyYawAnchor = yaw;
      freecamBodyPitchAnchor = pitch;
      freecamBodyHasAxeAnchor = hotbarSelected >= 0 && hotbarSlots[hotbarSelected] === 'axe';
      // Match freecam ghost axe pose to first-person held axe (what you tuned on screen).
      snapshotFreecamAxeFromHeldView();
    } else {
      camera.fov = freecamMinFov;
      camera.updateProjectionMatrix();
      clearFreecamAxeSnapshot();
    }
  }
  syncDevFlyUi();
}

const _matInvGhost = new THREE.Matrix4();
const _matHeld = new THREE.Matrix4();
/** Matches camera euler order ({@link euler}.set(pitch, yaw, 0, 'YXZ')) so view offset combines correctly with {@link fpToolRig}. */
const _heldAxeViewOffsetEuler = new THREE.Euler(0, 0, 0, 'YXZ');
const _fpBodyAnchorEuler = new THREE.Euler(0, 0, 0, 'YXZ');

/** Places FP tool rig where the character’s eyes/hands are (body anchor + look at freecam entry), not the detached freecam camera. */
function syncFpToolRigToBodyAnchoredView() {
  if (!fpToolRig || !camera) return;
  fpToolRig.position.set(
    freecamBodyAnchor.x,
    freecamBodyAnchor.y + getPlayerCameraYOffset(),
    freecamBodyAnchor.z
  );
  _fpBodyAnchorEuler.set(freecamBodyPitchAnchor, freecamBodyYawAnchor, 0, 'YXZ');
  fpToolRig.quaternion.setFromEuler(_fpBodyAnchorEuler);
  if (heldAxeViewRoot) {
    heldAxeViewRoot.position.set(HELD_AXE_VIEW_PLACEMENT.px, HELD_AXE_VIEW_PLACEMENT.py, HELD_AXE_VIEW_PLACEMENT.pz);
    _heldAxeViewOffsetEuler.set(
      HELD_AXE_VIEW_PLACEMENT.rx,
      HELD_AXE_VIEW_PLACEMENT.ry,
      HELD_AXE_VIEW_PLACEMENT.rz
    );
    heldAxeViewRoot.quaternion.setFromEuler(_heldAxeViewOffsetEuler);
  }
}

function snapshotFreecamAxeFromHeldView() {
  if (!freecamGhostRoot || !freecamGhostAxeHolder || !heldAxeViewRoot) return;
  syncFpToolRigToBodyAnchoredView();
  heldAxeViewRoot.updateMatrixWorld(true);
  fpToolRig?.updateMatrixWorld(true);
  const heldHost = heldAxeViewRoot.getObjectByName('HeldAxeMesh');
  if (!heldHost) return;
  heldHost.updateMatrixWorld(true);
  const inner = heldAxeObjTuneTarget;
  if (inner && inner !== heldHost) {
    inner.updateMatrixWorld(true);
    _matHeld.copy(inner.matrixWorld);
  } else {
    _matHeld.copy(heldHost.matrixWorld);
  }
  freecamGhostRoot.position.set(
    freecamBodyAnchor.x + freecamCharacterTune.px,
    freecamBodyAnchor.y + freecamCharacterTune.py,
    freecamBodyAnchor.z + freecamCharacterTune.pz
  );
  freecamGhostRoot.rotation.set(
    freecamCharacterTune.rx,
    freecamBodyYawAnchor + freecamCharacterTune.ry,
    freecamCharacterTune.rz
  );
  freecamGhostRoot.updateMatrixWorld(true);
  _matInvGhost.copy(freecamGhostRoot.matrixWorld).invert();
  const local = new THREE.Matrix4().multiplyMatrices(_matInvGhost, _matHeld);
  freecamGhostAxeHolder.userData.snapLocalMatrix = local;
  freecamGhostAxeHolder.matrixAutoUpdate = false;
  freecamGhostAxeHolder.matrix.copy(local);
  freecamGhostAxeHolder.matrixWorldNeedsUpdate = true;
}

function reapplyFreecamAxeSnapshotIfAny() {
  const m = freecamGhostAxeHolder?.userData?.snapLocalMatrix;
  if (!m || !freecamGhostAxeHolder) return;
  freecamGhostAxeHolder.matrixAutoUpdate = false;
  freecamGhostAxeHolder.matrix.copy(m);
  freecamGhostAxeHolder.matrixWorldNeedsUpdate = true;
}

function clearFreecamAxeSnapshot() {
  if (!freecamGhostAxeHolder) return;
  delete freecamGhostAxeHolder.userData.snapLocalMatrix;
  freecamGhostAxeHolder.matrixAutoUpdate = true;
}

/** One-time: drop saved <code>axe</code> prefab so the held mesh uses <code>axe.obj</code> (older builds used prefab here). */
function migrateRemoveStoredAxePrefabOnce() {
  try {
    if (localStorage.getItem('LT2_HeldAxeObjMigrationDone') === '1') return;
    removePrefabKindFromStorage('axe');
    localStorage.setItem('LT2_HeldAxeObjMigrationDone', '1');
  } catch (_) {}
}

function disposeHeldAxeSubtree(root) {
  if (!root) return;
  while (root.children.length) {
    const ch = root.children[0];
    root.remove(ch);
    const matsDone = new Set();
    ch.traverse((x) => {
      if (x.geometry) x.geometry.dispose();
      if (x.material) {
        const mats = Array.isArray(x.material) ? x.material : [x.material];
        for (const m of mats) {
          if (!m || matsDone.has(m)) continue;
          matsDone.add(m);
          if (m.map) m.map.dispose();
          if (m.dispose) m.dispose();
        }
      }
    });
  }
}

/** Clears only the mesh holder inside {@link AxeSwingPivot} (keeps swing + tuner layout). */
function disposeHeldAxeMeshContent() {
  const holder = heldAxeViewRoot?.getObjectByName('HeldAxeMesh');
  if (!holder) {
    if (heldAxeViewRoot) disposeHeldAxeSubtree(heldAxeViewRoot);
    return;
  }
  while (holder.children.length) {
    const ch = holder.children[0];
    holder.remove(ch);
    const matsDone = new Set();
    ch.traverse((x) => {
      if (x.geometry) x.geometry.dispose();
      if (x.material) {
        const mats = Array.isArray(x.material) ? x.material : [x.material];
        for (const m of mats) {
          if (!m || matsDone.has(m)) continue;
          matsDone.add(m);
          if (m.map) m.map.dispose();
          if (m.dispose) m.dispose();
        }
      }
    });
  }
}

function applyHeldAxeViewPlacement(g) {
  g.position.set(HELD_AXE_VIEW_PLACEMENT.px, HELD_AXE_VIEW_PLACEMENT.py, HELD_AXE_VIEW_PLACEMENT.pz);
  _heldAxeViewOffsetEuler.set(
    HELD_AXE_VIEW_PLACEMENT.rx,
    HELD_AXE_VIEW_PLACEMENT.ry,
    HELD_AXE_VIEW_PLACEMENT.rz
  );
  g.quaternion.setFromEuler(_heldAxeViewOffsetEuler);
  if (devFlyMode && freecamGhostRoot && freecamGhostAxeHolder && heldAxeViewRoot) {
    snapshotFreecamAxeFromHeldView();
  }
}

const heldAxeObjTune = {
  px: -0.22,
  py: 0.42,
  pz: 0.26,
  rx: 0.2373,
  ry: 5.9341,
  rz: 0.05,
};
let heldAxeObjTuneTarget = null;
/** Pitches for chop swing (radians); mesh parent stays yaw-only with camera. */
let axeSwingPivot = null;
let axeChopSwingT = 0;
const AXE_CHOP_SWING_DURATION = 0.24;

function applyHeldAxeObjTune() {
  const { px, py, pz, rx, ry, rz } = heldAxeObjTune;
  if (heldAxeObjTuneTarget) {
    heldAxeObjTuneTarget.position.set(px, py, pz);
    heldAxeObjTuneTarget.rotation.set(rx, ry, rz);
  }
  if (devFlyMode && freecamGhostRoot && freecamGhostAxeHolder && heldAxeViewRoot) {
    snapshotFreecamAxeFromHeldView();
  }
}

function initHeldAxeTunerUi() {
  const btn = document.createElement('button');
  btn.id = 'lt-held-axe-tuner-toggle';
  btn.type = 'button';
  btn.textContent = 'Axe tuner';
  Object.assign(btn.style, {
    position: 'fixed',
    right: '12px',
    bottom: '56px',
    zIndex: '10010',
    padding: '7px 12px',
    borderRadius: '8px',
    border: '1px solid rgba(255,255,255,0.24)',
    background: 'rgba(28,30,36,0.94)',
    color: '#e8eaed',
    fontSize: '12px',
    cursor: 'pointer',
  });
  document.body.appendChild(btn);

  const panel = document.createElement('div');
  panel.id = 'lt-held-axe-tuner-panel';
  panel.hidden = true;
  panel.setAttribute('aria-label', 'Held axe transform tuner');
  Object.assign(panel.style, {
    position: 'fixed',
    right: '12px',
    bottom: '96px',
    width: 'min(360px, 92vw)',
    zIndex: '10009',
    padding: '10px',
    borderRadius: '10px',
    border: '1px solid rgba(140,190,255,0.28)',
    background: 'rgba(8,12,18,0.96)',
    color: '#d7e8ff',
    fontSize: '12px',
    lineHeight: '1.35',
    boxShadow: '0 10px 34px rgba(0,0,0,0.55)',
  });
  document.body.appendChild(panel);

  const title = document.createElement('div');
  title.innerHTML = '<strong>Held axe transform</strong> (live)';
  title.style.marginBottom = '8px';
  panel.appendChild(title);

  const modeWrap = document.createElement('div');
  modeWrap.style.cssText = 'display:flex; align-items:center; gap:8px; margin-bottom:8px;';
  const modeLabel = document.createElement('div');
  modeLabel.textContent = 'Target:';
  modeLabel.style.opacity = '0.9';
  const modeSel = document.createElement('select');
  modeSel.innerHTML = '<option value="view">Hold offset (in use)</option><option value="obj">Model tune (in use)</option>';
  modeSel.style.cssText =
    'padding:4px 8px; border-radius:6px; border:1px solid #557; background:#202734; color:#e8eaed;';
  modeWrap.append(modeLabel, modeSel);
  panel.appendChild(modeWrap);

  const info = document.createElement('div');
  info.style.cssText = 'font-family: ui-monospace, Consolas, monospace; white-space: pre-wrap; margin-bottom:8px;';
  panel.appendChild(info);

  const mkRow = (label, addNeg, addPos) => {
    const row = document.createElement('div');
    row.style.cssText = 'display:flex; align-items:center; gap:8px; margin:4px 0;';
    const l = document.createElement('div');
    l.textContent = label;
    l.style.cssText = 'width:40px; opacity:0.9;';
    const n = document.createElement('button');
    n.type = 'button';
    n.textContent = '−';
    n.style.cssText = 'padding:4px 8px; border-radius:6px; border:1px solid #557; background:#2b3340; color:#e8eaed; cursor:pointer;';
    const p = document.createElement('button');
    p.type = 'button';
    p.textContent = '+';
    p.style.cssText = n.style.cssText;
    n.addEventListener('click', addNeg);
    p.addEventListener('click', addPos);
    row.append(l, n, p);
    panel.appendChild(row);
  };

  const activeTune = () =>
    modeSel.value === 'view'
      ? HELD_AXE_VIEW_PLACEMENT
      : heldAxeObjTune;

  const applyActive = () => {
    applyHeldAxeObjTune();
    if (heldAxeViewRoot) applyHeldAxeViewPlacement(heldAxeViewRoot);
  };

  const updateInfo = () => {
    const t = activeTune();
    const deg = (r) => (r * 180 / Math.PI);
    info.textContent =
      `target: ${modeSel.value === 'view' ? 'HELD_AXE_VIEW_PLACEMENT' : 'heldAxeObjTune'}\n` +
      `pos: (${t.px.toFixed(3)}, ${t.py.toFixed(3)}, ${t.pz.toFixed(3)})\n` +
      `rot(rad): (${t.rx.toFixed(3)}, ${t.ry.toFixed(3)}, ${t.rz.toFixed(3)})\n` +
      `rot(deg): (${deg(t.rx).toFixed(1)}, ${deg(t.ry).toFixed(1)}, ${deg(t.rz).toFixed(1)})`;
  };

  const stepPos = 0.01;
  const stepRot = Math.PI / 36; // 5°
  mkRow('X', () => { const t = activeTune(); t.px -= stepPos; applyActive(); updateInfo(); }, () => { const t = activeTune(); t.px += stepPos; applyActive(); updateInfo(); });
  mkRow('Y', () => { const t = activeTune(); t.py -= stepPos; applyActive(); updateInfo(); }, () => { const t = activeTune(); t.py += stepPos; applyActive(); updateInfo(); });
  mkRow('Z', () => { const t = activeTune(); t.pz -= stepPos; applyActive(); updateInfo(); }, () => { const t = activeTune(); t.pz += stepPos; applyActive(); updateInfo(); });
  mkRow('Rx', () => { const t = activeTune(); t.rx -= stepRot; applyActive(); updateInfo(); }, () => { const t = activeTune(); t.rx += stepRot; applyActive(); updateInfo(); });
  mkRow('Ry', () => { const t = activeTune(); t.ry -= stepRot; applyActive(); updateInfo(); }, () => { const t = activeTune(); t.ry += stepRot; applyActive(); updateInfo(); });
  mkRow('Rz', () => { const t = activeTune(); t.rz -= stepRot; applyActive(); updateInfo(); }, () => { const t = activeTune(); t.rz += stepRot; applyActive(); updateInfo(); });

  const actions = document.createElement('div');
  actions.style.cssText = 'display:flex; gap:8px; margin-top:8px; flex-wrap:wrap;';
  const resetBtn = document.createElement('button');
  resetBtn.type = 'button';
  resetBtn.textContent = 'Reset selected';
  const copyBtn = document.createElement('button');
  copyBtn.type = 'button';
  copyBtn.textContent = 'Copy selected';
  for (const b of [resetBtn, copyBtn]) {
    b.style.cssText = 'padding:6px 10px; border-radius:6px; border:1px solid #557; background:#2b3340; color:#e8eaed; cursor:pointer;';
  }
  actions.append(resetBtn, copyBtn);
  panel.appendChild(actions);

  resetBtn.addEventListener('click', () => {
    if (modeSel.value === 'view') {
      HELD_AXE_VIEW_PLACEMENT.px = 0.36;
      HELD_AXE_VIEW_PLACEMENT.py = -0.38;
      HELD_AXE_VIEW_PLACEMENT.pz = -0.52;
      HELD_AXE_VIEW_PLACEMENT.rx = -0.2;
      HELD_AXE_VIEW_PLACEMENT.ry = 0.36;
      HELD_AXE_VIEW_PLACEMENT.rz = 0.1;
    } else {
      heldAxeObjTune.px = -0.22;
      heldAxeObjTune.py = 0.42;
      heldAxeObjTune.pz = 0.26;
      heldAxeObjTune.rx = 0.2373;
      heldAxeObjTune.ry = 5.9341;
      heldAxeObjTune.rz = 0.05;
    }
    applyActive();
    updateInfo();
  });

  copyBtn.addEventListener('click', async () => {
    const t = activeTune();
    const key = modeSel.value === 'view' ? 'HELD_AXE_VIEW_PLACEMENT' : 'heldAxeObjTune';
    const text =
      `${key} = {\n` +
      `  px: ${t.px.toFixed(4)}, py: ${t.py.toFixed(4)}, pz: ${t.pz.toFixed(4)},\n` +
      `  rx: ${t.rx.toFixed(4)}, ry: ${t.ry.toFixed(4)}, rz: ${t.rz.toFixed(4)}\n` +
      `};`;
    try {
      await navigator.clipboard.writeText(text);
      globalThis.LT_appendDebugLog?.(`[held axe] copied ${key} to clipboard`);
    } catch (e) {
      globalThis.LT_appendDebugLog?.('[held axe] clipboard copy failed: ' + (e?.message || e), 'error');
    }
  });

  modeSel.addEventListener('change', updateInfo);

  btn.addEventListener('click', () => {
    panel.hidden = !panel.hidden;
  });

  updateInfo();
}

function initFreecamCharacterTunerUi() {
  const btn = document.createElement('button');
  btn.id = 'lt-freecam-char-tuner-toggle';
  btn.type = 'button';
  btn.textContent = 'Char tuner';
  Object.assign(btn.style, {
    position: 'fixed',
    left: '12px',
    bottom: '56px',
    zIndex: '10010',
    padding: '7px 12px',
    borderRadius: '8px',
    border: '1px solid rgba(255,255,255,0.24)',
    background: 'rgba(28,30,36,0.94)',
    color: '#e8eaed',
    fontSize: '12px',
    cursor: 'pointer',
  });
  document.body.appendChild(btn);

  const panel = document.createElement('div');
  panel.id = 'lt-freecam-char-tuner-panel';
  panel.hidden = true;
  panel.setAttribute('aria-label', 'Freecam character transform tuner');
  Object.assign(panel.style, {
    position: 'fixed',
    left: '12px',
    bottom: '96px',
    width: 'min(380px, 92vw)',
    zIndex: '10009',
    padding: '10px',
    borderRadius: '10px',
    border: '1px solid rgba(140,190,255,0.28)',
    background: 'rgba(8,12,18,0.96)',
    color: '#d7e8ff',
    fontSize: '12px',
    lineHeight: '1.35',
    boxShadow: '0 10px 34px rgba(0,0,0,0.55)',
  });
  document.body.appendChild(panel);

  const title = document.createElement('div');
  title.innerHTML = '<strong>Freecam character + eye tune</strong>';
  title.style.marginBottom = '8px';
  panel.appendChild(title);

  const info = document.createElement('div');
  info.style.cssText = 'font-family: ui-monospace, Consolas, monospace; white-space: pre-wrap; margin-bottom:8px;';
  panel.appendChild(info);

  const mkRow = (label, addNeg, addPos) => {
    const row = document.createElement('div');
    row.style.cssText = 'display:flex; align-items:center; gap:8px; margin:4px 0;';
    const l = document.createElement('div');
    l.textContent = label;
    l.style.cssText = 'width:48px; opacity:0.9;';
    const n = document.createElement('button');
    n.type = 'button';
    n.textContent = '−';
    n.style.cssText = 'padding:4px 8px; border-radius:6px; border:1px solid #557; background:#2b3340; color:#e8eaed; cursor:pointer;';
    const p = document.createElement('button');
    p.type = 'button';
    p.textContent = '+';
    p.style.cssText = n.style.cssText;
    n.addEventListener('click', addNeg);
    p.addEventListener('click', addPos);
    row.append(l, n, p);
    panel.appendChild(row);
  };

  const applyAndSave = () => {
    applyFreecamCharacterTune();
    writeFreecamCharacterTuneToStorage();
    updateInfo();
  };

  const updateInfo = () => {
    const deg = (r) => (r * 180 / Math.PI);
    info.textContent =
      `ghost pos: (${freecamCharacterTune.px.toFixed(3)}, ${freecamCharacterTune.py.toFixed(3)}, ${freecamCharacterTune.pz.toFixed(3)})\n` +
      `ghost rot(rad): (${freecamCharacterTune.rx.toFixed(3)}, ${freecamCharacterTune.ry.toFixed(3)}, ${freecamCharacterTune.rz.toFixed(3)})\n` +
      `ghost rot(deg): (${deg(freecamCharacterTune.rx).toFixed(1)}, ${deg(freecamCharacterTune.ry).toFixed(1)}, ${deg(freecamCharacterTune.rz).toFixed(1)})\n` +
      `modelScale: ${freecamCharacterTune.scale.toFixed(3)}\n` +
      `eyeOffsetY: ${freecamCharacterTune.eyeY.toFixed(3)} (cameraY=${getPlayerCameraYOffset().toFixed(3)})`;
  };

  const stepPos = 0.02;
  const stepRot = Math.PI / 36; // 5°
  const stepScale = 0.02;
  const stepEye = 0.01;
  mkRow('X', () => { freecamCharacterTune.px -= stepPos; applyAndSave(); }, () => { freecamCharacterTune.px += stepPos; applyAndSave(); });
  mkRow('Y', () => { freecamCharacterTune.py -= stepPos; applyAndSave(); }, () => { freecamCharacterTune.py += stepPos; applyAndSave(); });
  mkRow('Z', () => { freecamCharacterTune.pz -= stepPos; applyAndSave(); }, () => { freecamCharacterTune.pz += stepPos; applyAndSave(); });
  mkRow('Rx', () => { freecamCharacterTune.rx -= stepRot; applyAndSave(); }, () => { freecamCharacterTune.rx += stepRot; applyAndSave(); });
  mkRow('Ry', () => { freecamCharacterTune.ry -= stepRot; applyAndSave(); }, () => { freecamCharacterTune.ry += stepRot; applyAndSave(); });
  mkRow('Rz', () => { freecamCharacterTune.rz -= stepRot; applyAndSave(); }, () => { freecamCharacterTune.rz += stepRot; applyAndSave(); });
  mkRow('Scale', () => { freecamCharacterTune.scale = Math.max(0.05, freecamCharacterTune.scale - stepScale); applyAndSave(); }, () => { freecamCharacterTune.scale += stepScale; applyAndSave(); });
  mkRow('EyeY', () => { freecamCharacterTune.eyeY -= stepEye; applyAndSave(); }, () => { freecamCharacterTune.eyeY += stepEye; applyAndSave(); });

  const actions = document.createElement('div');
  actions.style.cssText = 'display:flex; gap:8px; margin-top:8px; flex-wrap:wrap;';
  const resetBtn = document.createElement('button');
  resetBtn.type = 'button';
  resetBtn.textContent = 'Reset';
  const copyBtn = document.createElement('button');
  copyBtn.type = 'button';
  copyBtn.textContent = 'Copy tune';
  for (const b of [resetBtn, copyBtn]) {
    b.style.cssText = 'padding:6px 10px; border-radius:6px; border:1px solid #557; background:#2b3340; color:#e8eaed; cursor:pointer;';
  }
  actions.append(resetBtn, copyBtn);
  panel.appendChild(actions);

  resetBtn.addEventListener('click', () => {
    Object.assign(freecamCharacterTune, FREECAM_CHARACTER_DEFAULT_TUNE);
    applyAndSave();
  });

  copyBtn.addEventListener('click', async () => {
    const text =
      `freecamCharacterTune = {\n` +
      `  px: ${freecamCharacterTune.px.toFixed(4)}, py: ${freecamCharacterTune.py.toFixed(4)}, pz: ${freecamCharacterTune.pz.toFixed(4)},\n` +
      `  rx: ${freecamCharacterTune.rx.toFixed(4)}, ry: ${freecamCharacterTune.ry.toFixed(4)}, rz: ${freecamCharacterTune.rz.toFixed(4)},\n` +
      `  scale: ${freecamCharacterTune.scale.toFixed(4)}, eyeY: ${freecamCharacterTune.eyeY.toFixed(4)}\n` +
      `};`;
    try {
      await navigator.clipboard.writeText(text);
      globalThis.LT_appendDebugLog?.('[freecam ghost] copied character tune to clipboard');
    } catch (e) {
      globalThis.LT_appendDebugLog?.('[freecam ghost] tune clipboard copy failed: ' + (e?.message || e), 'error');
    }
  });

  btn.addEventListener('click', () => {
    panel.hidden = !panel.hidden;
  });

  updateInfo();
}

function initFreecamAnimationEditorUi() {
  const btn = document.createElement('button');
  btn.id = 'lt-freecam-anim-editor-toggle';
  btn.type = 'button';
  btn.textContent = 'Anim editor';
  Object.assign(btn.style, {
    position: 'fixed',
    left: '108px',
    bottom: '56px',
    zIndex: '10010',
    padding: '7px 12px',
    borderRadius: '8px',
    border: '1px solid rgba(255,255,255,0.24)',
    background: 'rgba(28,30,36,0.94)',
    color: '#e8eaed',
    fontSize: '12px',
    cursor: 'pointer',
  });
  document.body.appendChild(btn);

  const panel = document.createElement('div');
  panel.id = 'lt-freecam-anim-editor-panel';
  panel.hidden = true;
  panel.setAttribute('aria-label', 'Freecam animation editor');
  Object.assign(panel.style, {
    position: 'fixed',
    left: '12px',
    bottom: '96px',
    width: 'min(460px, 96vw)',
    zIndex: '10009',
    padding: '10px',
    borderRadius: '10px',
    border: '1px solid rgba(180,160,255,0.34)',
    background: 'rgba(10,11,16,0.97)',
    color: '#ede8ff',
    fontSize: '12px',
    lineHeight: '1.35',
    boxShadow: '0 10px 34px rgba(0,0,0,0.55)',
  });
  document.body.appendChild(panel);

  const title = document.createElement('div');
  title.innerHTML = '<strong>Manual animation editor</strong> (whole-character keyframes)';
  title.style.marginBottom = '8px';
  panel.appendChild(title);

  const info = document.createElement('div');
  info.style.cssText = 'font-family: ui-monospace, Consolas, monospace; white-space: pre-wrap; margin-bottom:8px;';
  panel.appendChild(info);

  const frameSel = document.createElement('select');
  frameSel.size = 6;
  frameSel.style.cssText =
    'width:100%; margin-bottom:8px; background:#1b2130; color:#e8eaed; border:1px solid #557; border-radius:8px; padding:6px;';
  panel.appendChild(frameSel);

  const mkBtn = (text) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = text;
    b.style.cssText =
      'padding:6px 10px; border-radius:6px; border:1px solid #557; background:#2b3340; color:#e8eaed; cursor:pointer;';
    return b;
  };
  const row = document.createElement('div');
  row.style.cssText = 'display:flex; gap:8px; flex-wrap:wrap; margin-bottom:8px;';
  const addFrameBtn = mkBtn('Add frame');
  const updateFrameBtn = mkBtn('Update frame');
  const delFrameBtn = mkBtn('Delete frame');
  const clearBtn = mkBtn('Clear');
  row.append(addFrameBtn, updateFrameBtn, delFrameBtn, clearBtn);
  panel.appendChild(row);

  const durRow = document.createElement('div');
  durRow.style.cssText = 'display:flex; align-items:center; gap:8px; margin-bottom:8px; flex-wrap:wrap;';
  const durLabel = document.createElement('span');
  durLabel.textContent = 'Duration';
  const durInput = document.createElement('input');
  durInput.type = 'number';
  durInput.min = '0.1';
  durInput.step = '0.05';
  durInput.style.cssText = 'width:84px; padding:4px 6px; border-radius:6px; border:1px solid #557; background:#1b2130; color:#e8eaed;';
  const loopLabel = document.createElement('label');
  loopLabel.style.cssText = 'display:flex; align-items:center; gap:6px;';
  const loopCb = document.createElement('input');
  loopCb.type = 'checkbox';
  loopLabel.append(loopCb, document.createTextNode('Loop'));
  const playBtn = mkBtn('Play');
  const stopBtn = mkBtn('Stop');
  const recStartBtn = mkBtn('Start rec');
  const recStopBtn = mkBtn('Stop rec');
  const copyBtn = mkBtn('Copy anim');
  durRow.append(durLabel, durInput, loopLabel, playBtn, stopBtn, recStartBtn, recStopBtn, copyBtn);
  panel.appendChild(durRow);

  const mkAdjustRow = (label, key, step) => {
    const r = document.createElement('div');
    r.style.cssText = 'display:flex; align-items:center; gap:8px; margin:3px 0;';
    const l = document.createElement('div');
    l.textContent = label;
    l.style.cssText = 'width:44px; opacity:0.9;';
    const n = mkBtn('−');
    const p = mkBtn('+');
    n.addEventListener('click', () => {
      const f = freecamManualAnim.frames[freecamManualAnimSelectedIdx];
      if (!f) return;
      f[key] -= step;
      if (key === 't') f[key] = Math.max(0, f[key]);
      sortManualFrames();
      clampManualAnimState();
      writeFreecamManualAnimToStorage();
      setCharacterToManualFrame(freecamManualAnimSelectedIdx);
      refresh();
    });
    p.addEventListener('click', () => {
      const f = freecamManualAnim.frames[freecamManualAnimSelectedIdx];
      if (!f) return;
      f[key] += step;
      sortManualFrames();
      clampManualAnimState();
      writeFreecamManualAnimToStorage();
      setCharacterToManualFrame(freecamManualAnimSelectedIdx);
      refresh();
    });
    r.append(l, n, p);
    panel.appendChild(r);
  };

  mkAdjustRow('Time', 't', 0.05);
  mkAdjustRow('X', 'px', 0.02);
  mkAdjustRow('Y', 'py', 0.02);
  mkAdjustRow('Z', 'pz', 0.02);
  mkAdjustRow('Rx', 'rx', Math.PI / 36);
  mkAdjustRow('Ry', 'ry', Math.PI / 36);
  mkAdjustRow('Rz', 'rz', Math.PI / 36);

  const refresh = () => {
    clampManualAnimState();
    frameSel.innerHTML = '';
    freecamManualAnim.frames.forEach((f, i) => {
      const opt = document.createElement('option');
      opt.value = String(i);
      opt.textContent =
        `${i}. t=${f.t.toFixed(2)}  ` +
        `p(${f.px.toFixed(2)},${f.py.toFixed(2)},${f.pz.toFixed(2)}) ` +
        `r(${f.rx.toFixed(2)},${f.ry.toFixed(2)},${f.rz.toFixed(2)})`;
      frameSel.appendChild(opt);
    });
    frameSel.value = String(freecamManualAnimSelectedIdx);
    durInput.value = String(freecamManualAnim.duration.toFixed(2));
    loopCb.checked = !!freecamManualAnim.loop;
    info.textContent =
      `frames=${freecamManualAnim.frames.length}  duration=${freecamManualAnim.duration.toFixed(2)}  ` +
      `playing=${freecamManualAnimPlaying ? 'yes' : 'no'}  recording=${freecamManualAnimRecording ? 'yes' : 'no'}`;
  };

  frameSel.addEventListener('change', () => {
    freecamManualAnimSelectedIdx = Number(frameSel.value) || 0;
    setCharacterToManualFrame(freecamManualAnimSelectedIdx);
    refresh();
  });

  addFrameBtn.addEventListener('click', () => {
    const t = freecamManualAnim.frames.length ? freecamManualAnim.frames[freecamManualAnim.frames.length - 1].t + 0.1 : 0;
    const cap = captureCurrentManualPoseAt(t) || { t, px: 0, py: 0, pz: 0, rx: 0, ry: 0, rz: 0 };
    freecamManualAnim.frames.push(cap);
    sortManualFrames();
    freecamManualAnimSelectedIdx = freecamManualAnim.frames.length - 1;
    freecamManualAnim.duration = Math.max(freecamManualAnim.duration, cap.t);
    writeFreecamManualAnimToStorage();
    refresh();
  });

  updateFrameBtn.addEventListener('click', () => {
    const f = freecamManualAnim.frames[freecamManualAnimSelectedIdx];
    if (!f) return;
    const cap = captureCurrentManualPoseAt(f.t);
    if (!cap) return;
    Object.assign(f, cap);
    writeFreecamManualAnimToStorage();
    refresh();
  });

  delFrameBtn.addEventListener('click', () => {
    if (freecamManualAnim.frames.length <= 2) return;
    freecamManualAnim.frames.splice(freecamManualAnimSelectedIdx, 1);
    freecamManualAnimSelectedIdx = Math.max(0, freecamManualAnimSelectedIdx - 1);
    writeFreecamManualAnimToStorage();
    refresh();
  });

  clearBtn.addEventListener('click', () => {
    freecamManualAnim.frames = JSON.parse(JSON.stringify(FREECAM_MANUAL_ANIM_DEFAULT.frames));
    freecamManualAnim.duration = FREECAM_MANUAL_ANIM_DEFAULT.duration;
    freecamManualAnimSelectedIdx = 0;
    freecamManualAnimPlaying = false;
    freecamManualAnimRecording = false;
    writeFreecamManualAnimToStorage();
    setCharacterToManualFrame(0);
    refresh();
  });

  durInput.addEventListener('change', () => {
    freecamManualAnim.duration = Math.max(0.1, Number(durInput.value) || freecamManualAnim.duration);
    writeFreecamManualAnimToStorage();
    refresh();
  });

  loopCb.addEventListener('change', () => {
    freecamManualAnim.loop = !!loopCb.checked;
    writeFreecamManualAnimToStorage();
    refresh();
  });

  playBtn.addEventListener('click', () => {
    freecamManualAnimPlaying = true;
    freecamManualAnimTime = 0;
    refresh();
  });
  stopBtn.addEventListener('click', () => {
    freecamManualAnimPlaying = false;
    setCharacterToManualFrame(freecamManualAnimSelectedIdx);
    refresh();
  });
  recStartBtn.addEventListener('click', () => {
    freecamManualAnimRecording = true;
    freecamManualAnimRecordStart = performance.now() / 1000;
    freecamManualAnimLastCapture = -1;
    refresh();
  });
  recStopBtn.addEventListener('click', () => {
    freecamManualAnimRecording = false;
    writeFreecamManualAnimToStorage();
    refresh();
  });
  copyBtn.addEventListener('click', async () => {
    const text = JSON.stringify(
      {
        duration: freecamManualAnim.duration,
        loop: freecamManualAnim.loop,
        frames: freecamManualAnim.frames,
      },
      null,
      2
    );
    try {
      await navigator.clipboard.writeText(text);
      globalThis.LT_appendDebugLog?.('[freecam anim] copied animation JSON');
    } catch (e) {
      globalThis.LT_appendDebugLog?.('[freecam anim] copy failed: ' + (e?.message || e), 'error');
    }
  });

  btn.addEventListener('click', () => {
    panel.hidden = !panel.hidden;
    if (!panel.hidden) refresh();
  });
  refresh();
}

function loadObjHeldAxeIntoRoot() {
  const root = heldAxeViewRoot;
  if (!root) return;
  globalThis.LT_appendDebugLog?.('[held axe] loading models/files/axe.obj …');
  void loadAxeObjAsGroup({
    targetMaxDim: 0.52,
    castShadow: false,
    receiveShadow: false,
    tryTexture: false,
    skipBloomMaterialSwap: true,
  })
    .then((obj) => {
      if (heldAxeViewRoot !== root) {
        disposeAxeObjDeep(obj);
        return;
      }
      const holder = root.getObjectByName('HeldAxeMesh');
      if (!holder) {
        disposeAxeObjDeep(obj);
        return;
      }
      disposeHeldAxeMeshContent();
      heldAxeObjTuneTarget = obj;
      holder.add(obj);
      applyHeldAxeObjTune();
      if (devFlyMode) snapshotFreecamAxeFromHeldView();
      globalThis.LT_appendDebugLog?.('[held axe] axe.obj OK (mesh added to view)');
    })
    .catch((err) => {
      console.warn('Axe OBJ load failed.', err);
      globalThis.LT_appendDebugLog?.('[held axe] FAILED: ' + (err?.message || err), 'error');
    });
}

function initFreecamGhost() {
  if (!scene) return;
  const root = new THREE.Group();
  root.name = 'FreecamGhost';
  root.visible = false;

  const bodyRoot = new THREE.Group();
  bodyRoot.name = 'FreecamGhostBody';

  const bodyMat = new THREE.MeshStandardMaterial({ color: 0x4f6478, roughness: 0.9, metalness: 0.03 });
  const headMat = new THREE.MeshStandardMaterial({ color: 0xd7b597, roughness: 0.95, metalness: 0.01 });
  const body = new THREE.Mesh(new THREE.CapsuleGeometry(0.28, 0.95, 6, 12), bodyMat);
  body.position.set(0, 0.92, 0);
  body.castShadow = true;
  body.receiveShadow = false;
  body.userData.skipBloomMaterialSwap = true;

  const head = new THREE.Mesh(new THREE.SphereGeometry(0.2, 18, 14), headMat);
  head.position.set(0, 1.75, 0);
  head.castShadow = true;
  head.receiveShadow = false;
  head.userData.skipBloomMaterialSwap = true;
  bodyRoot.add(body, head);
  freecamGhostFallbackBodyRoot = bodyRoot;

  const axeHolder = new THREE.Group();
  axeHolder.name = 'FreecamGhostAxe';
  axeHolder.position.set(0.28, 1.1, -0.04);
  axeHolder.rotation.set(0.3, -0.5, -0.9);
  axeHolder.renderOrder = 10;
  axeHolder.frustumCulled = false;
  freecamGhostAxeHolder = axeHolder;
  {
    const phWood = new THREE.MeshStandardMaterial({
      color: 0x5a3d28,
      roughness: 0.92,
      metalness: 0.02,
    });
    const phIron = new THREE.MeshStandardMaterial({
      color: 0x6d737c,
      roughness: 0.42,
      metalness: 0.52,
    });
    const phHandle = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.58, 0.07), phWood);
    phHandle.position.set(0.1, -0.07, -0.04);
    phHandle.rotation.set(0.22, 0, -0.42);
    const phHead = new THREE.Mesh(new THREE.BoxGeometry(0.24, 0.08, 0.14), phIron);
    phHead.position.set(-0.07, 0.05, -0.03);
    phHead.rotation.set(0, 0, -0.12);
    const phGroup = new THREE.Group();
    phGroup.name = 'FreecamGhostAxePlaceholder';
    phGroup.userData.freecamGhostAxePlaceholder = true;
    for (const m of [phHandle, phHead]) {
      m.castShadow = false;
      m.receiveShadow = false;
      m.frustumCulled = false;
      m.userData.skipBloomMaterialSwap = true;
    }
    phGroup.add(phHandle, phHead);
    axeHolder.add(phGroup);
  }

  root.add(bodyRoot, axeHolder);
  scene.add(root);
  freecamGhostRoot = root;

  void loadAxeObjAsGroup({
    targetMaxDim: 0.52,
    castShadow: false,
    receiveShadow: false,
    tryTexture: false,
    skipBloomMaterialSwap: true,
  })
    .then((obj) => {
      if (!freecamGhostAxeHolder || freecamGhostAxeHolder !== axeHolder) {
        disposeAxeObjDeep(obj);
        return;
      }
      while (axeHolder.children.length) {
        const ch = axeHolder.children[0];
        axeHolder.remove(ch);
      }
      axeHolder.add(obj);
      if (devFlyMode) snapshotFreecamAxeFromHeldView();
      else reapplyFreecamAxeSnapshotIfAny();
    })
    .catch((err) => {
      globalThis.LT_appendDebugLog?.('[freecam ghost] axe.obj failed: ' + (err?.message || err), 'warn');
    });

  const gltfLoader = new GLTFLoader();
  const loadRigged = async () => {
    for (const rel of FREECAM_CHARACTER_RIG_RELS) {
      const urls = candidateAssetUrls(rel);
      for (const href of urls) {
        try {
          const gltf = await gltfLoader.loadAsync(href);
          const g = gltf.scene || gltf.scenes?.[0];
          if (!g) throw new Error('No scene in GLTF');
          g.name = 'FreecamGhostCharacterRig';
          g.traverse((o) => {
            if (!o.isMesh) return;
            o.castShadow = true;
            o.receiveShadow = false;
            o.frustumCulled = false;
            o.userData.skipBloomMaterialSwap = true;
            const matsArr = Array.isArray(o.material) ? o.material : [o.material];
            for (const mat of matsArr) {
              if (!mat) continue;
              mat.side = THREE.DoubleSide;
              if (mat.map) mat.map.colorSpace = THREE.SRGBColorSpace;
              mat.needsUpdate = true;
            }
          });
          normalizeGhostCharacterObject(g, 1.9);
          attachFreecamGhostCharacter(g, `rigged ${rel}`, gltf.animations || []);
          return true;
        } catch (e) {
          globalThis.LT_appendDebugLog?.(`[freecam ghost] rigged load miss ${rel} (${href}): ${e?.message || e}`, 'warn');
        }
      }
    }
    return false;
  };

  const loadObjMtl = async () => {
    const [{ text: objText }, { text: mtlText, href: mtlHref }] = await Promise.all([
      fetchTextWithSourceUrl(FREECAM_CHARACTER_OBJ_REL),
      fetchTextWithSourceUrl(FREECAM_CHARACTER_MTL_REL),
    ]);
    const mtlBase = mtlHref.slice(0, mtlHref.lastIndexOf('/') + 1);
    const mtlNormalized = mtlText.replace(/\\/g, '/');
    const mats = new MTLLoader().parse(mtlNormalized, mtlBase);
    mats.preload();
    const objLoader = new OBJLoader();
    objLoader.setMaterials(mats);
    const obj = objLoader.parse(objText);
    obj.name = 'FreecamGhostCharacterObj';
    const meshList = [];
    obj.traverse((o) => {
      if (!o.isMesh) return;
      meshList.push(o);
      o.castShadow = true;
      o.receiveShadow = false;
      o.frustumCulled = false;
      o.userData.skipBloomMaterialSwap = true;
      const matsArr = Array.isArray(o.material) ? o.material : [o.material];
      for (const mat of matsArr) {
        if (!mat) continue;
        mat.side = THREE.DoubleSide;
        if (mat.map) mat.map.colorSpace = THREE.SRGBColorSpace;
        if (mat.emissiveMap) mat.emissiveMap.colorSpace = THREE.SRGBColorSpace;
        mat.transparent = false;
        mat.opacity = 1;
        mat.alphaTest = 0;
        if (mat.alphaMap) mat.alphaMap = null;
        mat.depthWrite = true;
        mat.needsUpdate = true;
      }
    });
    if (meshList.length === 0) throw new Error('Character.obj parsed with no meshes');
    obj.position.set(0, 0, 0);
    obj.scale.set(1, 1, 1);
    obj.quaternion.set(0, 0, 0, 1);
    obj.updateMatrixWorld(true);
    const box = new THREE.Box3().setFromObject(obj);
    if (box.isEmpty()) throw new Error('Character.obj has empty bbox');
    const worldCenter = box.getCenter(new THREE.Vector3());
    meshList.forEach((m) => {
      const g = m.geometry;
      if (!g) return;
      const inv = new THREE.Matrix4().copy(m.matrixWorld).invert();
      const localDelta = worldCenter.clone().applyMatrix4(inv);
      g.translate(-localDelta.x, -localDelta.y, -localDelta.z);
      if (g.attributes.position) g.attributes.position.needsUpdate = true;
      g.computeBoundingBox();
      g.computeBoundingSphere();
    });
    normalizeGhostCharacterObject(obj, 1.9);
    attachFreecamGhostCharacter(obj, 'Character.obj + Character.mtl');
  };

  void (async () => {
    try {
      const rigOk = await loadRigged();
      if (!rigOk) await loadObjMtl();
    } catch (err) {
      globalThis.LT_appendDebugLog?.('[freecam ghost] character load failed (using fallback): ' + (err?.message || err), 'warn');
      if (freecamGhostFallbackBodyRoot) freecamGhostFallbackBodyRoot.visible = true;
    }
  })();
}

/** First-person axe: placeholder until OBJ loads; main game always uses <code>axe.obj</code> (not the prefab editor <code>axe</code> JSON). */
function buildHeldAxeViewModel() {
  const g = new THREE.Group();
  g.name = 'HeldAxeView';
  const swing = new THREE.Group();
  swing.name = 'AxeSwingPivot';
  axeSwingPivot = swing;
  const meshHolder = new THREE.Group();
  meshHolder.name = 'HeldAxeMesh';
  g.add(swing);
  swing.add(meshHolder);
  heldAxeObjTuneTarget = meshHolder;
  const wood = new THREE.MeshStandardMaterial({
    color: 0x5a3d28,
    roughness: 0.92,
    metalness: 0.02,
  });
  const iron = new THREE.MeshStandardMaterial({
    color: 0x6d737c,
    roughness: 0.42,
    metalness: 0.52,
  });
  const handle = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.58, 0.07), wood);
  handle.name = 'held_axe_handle';
  handle.position.set(0.1, -0.07, -0.04);
  handle.rotation.set(0.22, 0, -0.42);
  const head = new THREE.Mesh(new THREE.BoxGeometry(0.24, 0.08, 0.14), iron);
  head.name = 'held_axe_head';
  head.position.set(-0.07, 0.05, -0.03);
  head.rotation.set(0, 0, -0.12);
  for (const m of [handle, head]) {
    m.castShadow = false;
    m.receiveShadow = false;
    m.userData.skipBloomMaterialSwap = true;
  }
  meshHolder.add(handle, head);
  applyHeldAxeObjTune();
  applyHeldAxeViewPlacement(g);
  return g;
}

function syncFpToolRigToCamera() {
  if (!fpToolRig || !camera) return;
  fpToolRig.position.copy(camera.position);
  fpToolRig.quaternion.copy(camera.quaternion);
  /** View offset in camera YXZ space (same convention as look) — avoids default XYZ euler + camera quat flipping the mesh out of view. */
  if (heldAxeViewRoot) {
    heldAxeViewRoot.position.set(HELD_AXE_VIEW_PLACEMENT.px, HELD_AXE_VIEW_PLACEMENT.py, HELD_AXE_VIEW_PLACEMENT.pz);
    _heldAxeViewOffsetEuler.set(
      HELD_AXE_VIEW_PLACEMENT.rx,
      HELD_AXE_VIEW_PLACEMENT.ry,
      HELD_AXE_VIEW_PLACEMENT.rz
    );
    heldAxeViewRoot.quaternion.setFromEuler(_heldAxeViewOffsetEuler);
  }
}

function updateHeldAxeVisibility() {
  if (!heldAxeViewRoot || !fpToolRig) return;
  const show =
    !gamePaused &&
    !bobLandShowcase &&
    !devFlyMode &&
    !inventoryPanelOpen &&
    hotbarSelected >= 0 &&
    hotbarSlots[hotbarSelected] === 'axe';
  heldAxeViewRoot.visible = !!show;
  fpToolRig.visible = !!show;
  if (!show && axeSwingPivot) {
    axeChopSwingT = 0;
    axeSwingPivot.rotation.x = 0;
  }
}

function updateFreecamGhostVisibility() {
  if (!freecamGhostRoot) return;
  if (!devFlyMode) {
    freecamGhostRoot.visible = false;
    return;
  }
  freecamGhostRoot.visible = true;
  freecamGhostRoot.position.set(
    freecamBodyAnchor.x + freecamCharacterTune.px,
    freecamBodyAnchor.y + freecamCharacterTune.py,
    freecamBodyAnchor.z + freecamCharacterTune.pz
  );
  freecamGhostRoot.rotation.set(
    freecamCharacterTune.rx,
    freecamBodyYawAnchor + freecamCharacterTune.ry,
    freecamCharacterTune.rz
  );
  const showGhostAxe =
    !inventoryPanelOpen &&
    hotbarSelected >= 0 &&
    hotbarSlots[hotbarSelected] === 'axe';
  if (freecamGhostAxeHolder) freecamGhostAxeHolder.visible = showGhostAxe;
  if (showGhostAxe && freecamGhostAxeHolder && !freecamGhostAxeHolder.userData.snapLocalMatrix) {
    snapshotFreecamAxeFromHeldView();
  }
  if (freecamGhostAxeHolder?.userData?.snapLocalMatrix) reapplyFreecamAxeSnapshotIfAny();
}

function initWorld() {
  scene = new THREE.Scene();
  worldRegistry = createWorldRegistry();
  scene.background = new THREE.Color(0x87b8e8);
  scene.fog = new THREE.Fog(0x87b8e8, 30, 90);

  world = new CANNON.World({ gravity: new CANNON.Vec3(0, -24, 0) });
  world.defaultContactMaterial.friction = 0.55;
  world.defaultContactMaterial.restitution = 0;
  world.solver.iterations = 22;
  world.solver.tolerance = 0.0001;
  /**
   * Sweep-and-prune broadphase: O(n log n) for n bodies vs. the default naive O(n²). We scatter
   * potentially 50,000 static tree colliders across a planet, and without SAP the broadphase
   * alone would eat the entire frame budget long before anything draws. SAP handles sparse
   * static geometry (forests, voxel chunks) beautifully because it only pairs bodies whose AABBs
   * actually overlap along the sweep axis.
   */
  world.broadphase = new CANNON.SAPBroadphase(world);
  world.broadphase.autoDetectAxis = true;

  camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.12, 220);
  camera.rotation.order = 'YXZ';

  fpToolRig = new THREE.Group();
  fpToolRig.name = 'FpToolRig';
  fpToolRig.visible = false;
  scene.add(fpToolRig);
  readFreecamCharacterTuneFromStorage();
  readFreecamManualAnimFromStorage();
  initHeldAxeTunerUi();
  initFreecamCharacterTunerUi();
  initFreecamAnimationEditorUi();
  migrateRemoveStoredAxePrefabOnce();
  heldAxeViewRoot = buildHeldAxeViewModel();
  heldAxeViewRoot.visible = false;
  fpToolRig.add(heldAxeViewRoot);
  loadObjHeldAxeIntoRoot();
  initFreecamGhost();

  renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.shadowMap.enabled = true;
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ReinhardToneMapping;
  renderer.toneMappingExposure = 1;
  document.body.appendChild(renderer.domElement);
  try {
    neonBloomPipeline = createNeonBloomPipeline(renderer, scene, camera);
    enableNeonBloomOnCamera(camera);
  } catch (err) {
    console.warn('Neon bloom pipeline failed; using direct render.', err);
    neonBloomPipeline = null;
  }
  lastPointerClient.x = window.innerWidth / 2;
  lastPointerClient.y = window.innerHeight / 2;

  const hemi = new THREE.HemisphereLight(0xcfe8ff, 0x3a4a30, 0.55);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xffffff, 0.85);
  sun.position.set(20, 40, 12);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.near = 2;
  sun.shadow.camera.far = 120;
  sun.shadow.camera.left = -40;
  sun.shadow.camera.right = 40;
  sun.shadow.camera.top = 40;
  sun.shadow.camera.bottom = -40;
  scene.add(sun);

  const groundGeo = new THREE.PlaneGeometry(200, 200);
  const groundMat = new THREE.MeshStandardMaterial({ color: 0x3d6b3d, roughness: 1 });
  gameGroundMesh = new THREE.Mesh(groundGeo, groundMat);
  gameGroundMesh.rotation.x = -Math.PI / 2;
  gameGroundMesh.receiveShadow = true;
  gameGroundMesh.name = 'GroundPlane';
  scene.add(gameGroundMesh);
  initRuntimePlanetContentRootFromMap();
  applyMapTerrainMeshFromStorage();

  const groundPhyMat = new CANNON.Material('ground');
  gameGroundPhyMat = groundPhyMat;
  const playerPhyMat = new CANNON.Material('player');
  woodPhyMat = new CANNON.Material('wood');

  initSpherePhysicsFromMapIfEnabled();
  applyUniversePoseToSpherePhysicsIfActive();

  const groundBody = new CANNON.Body({ mass: 0, material: groundPhyMat });
  groundBody.addShape(new CANNON.Plane());
  groundBody.quaternion.setFromEuler(-Math.PI / 2, 0, 0);
  if (!spherePhysicsActive) {
    world.addBody(groundBody);
  }

  syncActiveWorldDefFromMap();
  applyMapEditorSellFromStorage();
  createStoreAndBob();
  createSellZoneParticles();

  playerBody = new CANNON.Body({ mass: 64, material: playerPhyMat });
  /** Box feet (not a rolling sphere) — much less vertical chatter when strafing on planes/boxes. */
  playerBody.addShape(
    new CANNON.Box(new CANNON.Vec3(PLAYER_BODY_HALF_W, PLAYER_BODY_HALF_H, PLAYER_BODY_HALF_W)),
    new CANNON.Vec3(0, PLAYER_BODY_HALF_H, 0)
  );
  let uxo = 0;
  let uyo = 0;
  let uzo = 0;
  const _spawnSlice = getMapEditorData() ? getWorldSliceFromDocument(getMapEditorData(), runtimePlanetId) : null;
  if (_spawnSlice?.universePose) {
    const up = normalizeUniversePose(_spawnSlice.universePose);
    uxo = up.x;
    uyo = up.y;
    uzo = up.z;
  }
  if (spherePhysicsActive) {
    /** Stand on the **outside** of the planet: surface at y = R, feet just above. */
    const r = spherePlayRadius + 0.05 + PLAYER_BODY_HALF_H;
    playerBody.position.set(uxo, r + uyo, uzo);
  } else {
    playerBody.position.set(uxo, PLAYER_SPAWN_Y + uyo, 8 + uzo);
  }
  /** Low damping: we drive horizontal speed each frame; high damping fights the solver and causes pops. */
  playerBody.linearDamping = 0.02;
  playerBody.fixedRotation = true;
  world.addBody(playerBody);
  /** Seed the tangent heading now that the player body exists — otherwise the first frame's
   *  camera orientation will use the default `(0,0,-1)` world heading, which is wrong on a planet
   *  whose center isn't at the world origin. */
  if (spherePhysicsActive) initPlayerHeadingOnPlanet();
  if (window.__planetDebug) {
    window.__planetDebug.playerBody = playerBody;
    console.log('%c[planet] player spawned', 'background:#930;color:#fff;padding:2px 6px;border-radius:3px', {
      position: playerBody.position.clone(),
      spherePhysicsActive,
      spherePlayRadius,
    });
  }

  world.addContactMaterial(
    new CANNON.ContactMaterial(playerPhyMat, groundPhyMat, {
      friction: 0,
      restitution: 0,
      contactEquationStiffness: 4e5,
      contactEquationRelaxation: 6,
    })
  );

  world.addContactMaterial(
    new CANNON.ContactMaterial(playerPhyMat, woodPhyMat, {
      friction: 0.2,
      restitution: 0,
      contactEquationStiffness: 2e5,
      contactEquationRelaxation: 10,
    })
  );

  mapPropPhyMaterial = new CANNON.Material('mapProp');
  world.addContactMaterial(
    new CANNON.ContactMaterial(playerPhyMat, mapPropPhyMaterial, {
      friction: 0.25,
      restitution: 0,
      contactEquationStiffness: 2e5,
      contactEquationRelaxation: 10,
    })
  );

  world.addContactMaterial(
    new CANNON.ContactMaterial(woodPhyMat, groundPhyMat, {
      friction: 0.55,
      restitution: 0.02,
      contactEquationStiffness: 2e5,
      contactEquationRelaxation: 12,
    })
  );
  world.addContactMaterial(
    new CANNON.ContactMaterial(woodPhyMat, mapPropPhyMaterial, {
      friction: 0.45,
      restitution: 0.05,
      contactEquationStiffness: 2e5,
      contactEquationRelaxation: 10,
    })
  );

  spawnMapEditorPlacements(mapPropPhyMaterial);

  createLandPlotsInWorld();

  sawmillsRoot = new THREE.Group();
  sawmillsRoot.name = 'Sawmills';
  sawmillsRoot.userData.planetId = runtimePlanetId;
  planetMeshParent().add(sawmillsRoot);

  plotBlueprintsRoot = new THREE.Group();
  plotBlueprintsRoot.name = 'PlotBlueprints';
  plotBlueprintsRoot.userData.planetId = runtimePlanetId;
  planetMeshParent().add(plotBlueprintsRoot);

  const hadSave = loadGame();
  if (!hadSave) {
    money = Math.max(money, 150);
    updateMoneyHud();
  } else {
    syncLandPlotVisuals();
  }
  /**
   * Seed the active planet's content (authored trees + biome-procedural forest) through the
   * per-planet pipeline. `force: true` so a save-loaded session still refreshes to match the
   * current map editor layout instead of keeping stale trunks from the save blob.
   */
  populatePlanetContent(runtimePlanetId, { force: true });
  ensureSphereWorldHasAtLeastOneTree();
  refreshPerPlanetContentVisibility();
  syncHotbarUi();
  ensureShopShelfStock();

  document.getElementById('place-sawmill-btn')?.addEventListener('click', () => {
    if (sawmillPlacementMode) finalizeSawmillPlacement();
  });
  document.getElementById('rotate-sawmill-left')?.addEventListener('click', () => {
    if (sawmillPlacementMode) sawmillPlacementMode.yaw -= Math.PI / 2;
  });
  document.getElementById('rotate-sawmill-right')?.addEventListener('click', () => {
    if (sawmillPlacementMode) sawmillPlacementMode.yaw += Math.PI / 2;
  });
  document.getElementById('cancel-sawmill-place')?.addEventListener('click', () => {
    if (sawmillPlacementMode) exitSawmillPlacement(true);
  });

  document.getElementById('place-blueprint-btn')?.addEventListener('click', () => {
    if (blueprintPlacementMode) finalizeBlueprintPlacement();
  });
  document.getElementById('rotate-blueprint-left')?.addEventListener('click', () => {
    if (blueprintPlacementMode) blueprintPlacementMode.yaw -= Math.PI / 2;
  });
  document.getElementById('rotate-blueprint-right')?.addEventListener('click', () => {
    if (blueprintPlacementMode) blueprintPlacementMode.yaw += Math.PI / 2;
  });
  document.getElementById('cancel-blueprint-place')?.addEventListener('click', () => {
    if (blueprintPlacementMode) exitBlueprintPlacement(true);
  });

  // Sawmill X/Y controls are now on the sawmill model (3D panel), not the HUD.

  document.getElementById('lb-close')?.addEventListener('click', () => {
    const p = document.getElementById('leaderboard-panel');
    if (p) p.classList.toggle('lb-hidden');
  });

  window.addEventListener('resize', onResize);
  document.addEventListener('keydown', (e) => keys.add(e.code));
  document.addEventListener('keyup', (e) => keys.delete(e.code));

  function readMouseSensFromStorage() {
    try {
      const j = JSON.parse(localStorage.getItem(UI_SETTINGS_KEY) || '{}');
      if (typeof j.mouseSens === 'number') return j.mouseSens;
    } catch (_) {}
    return 1;
  }

  function readShopItemLabelsFromStorage() {
    try {
      const j = JSON.parse(localStorage.getItem(UI_SETTINGS_KEY) || '{}');
      if (typeof j.shopItemLabels === 'boolean') return j.shopItemLabels;
    } catch (_) {}
    return true;
  }

  function writeMouseSensToStorage(mul) {
    let j = {};
    try {
      j = JSON.parse(localStorage.getItem(UI_SETTINGS_KEY) || '{}');
    } catch (_) {}
    j.mouseSens = mul;
    localStorage.setItem(UI_SETTINGS_KEY, JSON.stringify(j));
  }

  function writeShopItemLabelsToStorage(on) {
    let j = {};
    try {
      j = JSON.parse(localStorage.getItem(UI_SETTINGS_KEY) || '{}');
    } catch (_) {}
    j.shopItemLabels = !!on;
    localStorage.setItem(UI_SETTINGS_KEY, JSON.stringify(j));
  }

  mouseSensMul = readMouseSensFromStorage();
  shopItemLabelsEnabled = readShopItemLabelsFromStorage();

  function syncPointerLockUi() {
    const locked = document.pointerLockElement === document.body;
    document.body.classList.toggle('pointer-locked', locked);
    const freeBtn = document.getElementById('btn-free-mouse');
    if (freeBtn) freeBtn.style.display = locked ? 'block' : 'none';
  }

  const pauseMenu = document.getElementById('pause-menu');
  const pauseSettingsPanel = document.getElementById('pause-settings-panel');
  const pauseMouseSens = document.getElementById('pause-mouse-sens');
  const pauseMouseSensVal = document.getElementById('pause-mouse-sens-val');
  let pauseSettingsOpen = false;

  function setPauseSettingsOpen(open) {
    pauseSettingsOpen = open;
    if (pauseSettingsPanel) pauseSettingsPanel.style.display = open ? 'block' : 'none';
  }

  function setPaused(paused) {
    gamePaused = paused;
    if (paused) {
      endGrab();
      if (sawmillPlacementMode) exitSawmillPlacement(true);
      if (blueprintPlacementMode) exitBlueprintPlacement(true);
      clearChopState();
      exitBobLandShowcase();
      bobDialogOpen = false;
      bobDialogStep = 0;
      clerkBubbleUntil = 0;
      setNpcBubbleText('');
      if (inventoryPanelOpen) {
        inventoryPanelOpen = false;
        document.getElementById('inventory-overlay')?.classList.remove('inv-open');
      }
    }
    if (pauseMenu) {
      pauseMenu.style.display = paused ? 'flex' : 'none';
      pauseMenu.setAttribute('aria-hidden', paused ? 'false' : 'true');
    }
    if (!paused) setPauseSettingsOpen(false);
    if (paused) document.exitPointerLock();
  }

  function togglePause() {
    setPaused(!gamePaused);
  }

  document.getElementById('pause-resume')?.addEventListener('click', () => setPaused(false));
  document.getElementById('pause-settings')?.addEventListener('click', () => {
    setPauseSettingsOpen(!pauseSettingsOpen);
  });
  document.getElementById('pause-leave')?.addEventListener('click', () => {
    window.LumberNet?.disconnect?.();
    setPaused(false);
  });

  if (pauseMouseSens && pauseMouseSensVal) {
    pauseMouseSens.value = String(Math.round(mouseSensMul * 100));
    pauseMouseSensVal.textContent = pauseMouseSens.value;
    pauseMouseSens.addEventListener('input', () => {
      const pct = Number(pauseMouseSens.value);
      pauseMouseSensVal.textContent = String(pct);
      mouseSensMul = pct / 100;
      writeMouseSensToStorage(mouseSensMul);
    });
  }

  const pauseShopLabels = document.getElementById('pause-shop-item-labels');
  if (pauseShopLabels) {
    pauseShopLabels.checked = shopItemLabelsEnabled;
    pauseShopLabels.addEventListener('change', () => {
      shopItemLabelsEnabled = !!pauseShopLabels.checked;
      writeShopItemLabelsToStorage(shopItemLabelsEnabled);
    });
  }

  document.getElementById('btn-free-mouse')?.addEventListener('click', () => {
    document.exitPointerLock();
  });
  document.getElementById('btn-freecam')?.addEventListener('click', () => {
    setDevFlyMode(!devFlyMode);
  });

  document.addEventListener('click', (e) => {
    if (e.target.closest('#mp-panel')) return;
    if (e.target.closest('#dev-menu')) return;
    if (e.target.closest('#pause-menu')) return;
    if (e.target.closest('#btn-free-mouse')) return;
    if (e.target.closest('#btn-freecam')) return;
    if (e.target.closest('#hud')) return;
    if (e.target.closest('#placement-panel')) return;
    if (e.target.closest('#blueprint-placement-panel')) return;
    if (e.target.closest('#land-showcase-panel')) return;
    if (e.target.closest('#lt-held-axe-tuner-toggle') || e.target.closest('#lt-held-axe-tuner-panel')) return;
    if (
      e.target.closest('#lt-freecam-char-tuner-toggle') ||
      e.target.closest('#lt-freecam-char-tuner-panel')
    ) {
      return;
    }
    if (
      e.target.closest('#lt-freecam-anim-editor-toggle') ||
      e.target.closest('#lt-freecam-anim-editor-panel')
    ) {
      return;
    }
    if (e.target.closest('#lt-debug-log-toggle') || e.target.closest('#lt-debug-log-panel')) return;
    /** Clicks on the Space BG editor (button + panel) must NOT re-engage pointer
     *  lock — otherwise the cursor vanishes every time the player drags a slider
     *  or hits a preset, which is maddening. */
    if (e.target.closest('#lt-space-bg-toggle') || e.target.closest('#lt-space-bg-panel')) return;
    if (bobLandShowcase) return;
    if (inventoryPanelOpen) return;
    if (gamePaused) return;
    if (document.pointerLockElement !== document.body) document.body.requestPointerLock();
  });

  document.addEventListener('pointerlockchange', syncPointerLockUi);
  syncPointerLockUi();

  document.addEventListener('mousedown', (e) => {
    if (gamePaused || document.pointerLockElement !== document.body) return;
    if (sawmillPlacementMode) return;
    if (blueprintPlacementMode) return;
    if (devFlyMode) return;
    if (e.button === 0) {
      if (tryClickSawmillSidePanel()) return;
      if (toolAllowsChop()) {
        // Always play swing feedback on click when axe is equipped.
        triggerAxeChopSwing();
        const chopRay = rayPickWood(6);
        if (chopRay && pickIsChoppableWood(chopRay)) {
          tryChop();
        }
        return;
      }
      const pick = rayPickWood();
      if (pick) startGrab(pick);
    }
  });
  document.addEventListener('mouseup', (e) => {
    if (e.button === 0) endGrab();
  });

  document.addEventListener('mousemove', (e) => {
    lastPointerClient.x = e.clientX;
    lastPointerClient.y = e.clientY;
    if (bobLandShowcase) return;
    if (
      (sawmillPlacementMode || blueprintPlacementMode) &&
      document.pointerLockElement !== document.body
    ) {
      const s = 0.0022 * mouseSensMul;
      yaw -= e.movementX * s;
      pitch -= e.movementY * s;
      pitch = Math.max(-1.35, Math.min(1.35, pitch));
      return;
    }
    if (document.pointerLockElement !== document.body) return;
    if (
      grabTarget &&
      !gamePaused &&
      (keys.has('ShiftLeft') || keys.has('ShiftRight'))
    ) {
      applyGrabRotateFromMouse(e.movementX, e.movementY);
      return;
    }
    const s = 0.0022 * mouseSensMul;
    /** On sphere-physics worlds, mouse X rotates the tangent heading vector around the *local*
     *  radial-up (not world Y), so the first-person view feels like turning while standing on a
     *  planet — keeps "forward" tangent to the surface instead of drifting up/down as you walk. */
    if (spherePhysicsActive && !devFlyMode) {
      const up = getPlayerPlanetUp(_tmpSphereUp);
      if (up) {
        reprojectHeadingOntoTangent(up);
        playerHeading.applyAxisAngle(up, -e.movementX * s);
      }
    } else {
      yaw -= e.movementX * s;
    }
    pitch -= e.movementY * s;
    pitch = Math.max(-1.35, Math.min(1.35, pitch));
  });

  document.addEventListener('keydown', (e) => {
    if (e.code === 'Escape') {
      e.preventDefault();
      if (inventoryPanelOpen) {
        toggleInventoryPanel();
        return;
      }
      if (sawmillPlacementMode) {
        exitSawmillPlacement(true);
        return;
      }
      if (blueprintPlacementMode) {
        exitBlueprintPlacement(true);
        return;
      }
      togglePause();
      return;
    }
    if (e.code === 'Backquote' && !e.repeat) {
      e.preventDefault();
      const panel = document.getElementById('dev-menu');
      const open = panel?.classList.toggle('dev-menu-open');
      if (open) document.exitPointerLock();
      return;
    }
    if (gamePaused) return;
    if (sawmillPlacementMode) {
      if (!e.repeat && e.code === 'ArrowLeft') {
        sawmillPlacementMode.yaw -= Math.PI / 2;
        return;
      }
      if (!e.repeat && e.code === 'ArrowRight') {
        sawmillPlacementMode.yaw += Math.PI / 2;
        return;
      }
      if (!e.repeat && e.code === 'Space') {
        e.preventDefault();
        finalizeSawmillPlacement();
        return;
      }
    }
    if (blueprintPlacementMode) {
      if (!e.repeat && e.code === 'KeyR') {
        blueprintPlacementMode.yaw -= Math.PI / 2;
        return;
      }
      if (!e.repeat && e.code === 'ArrowLeft') {
        blueprintPlacementMode.yaw -= Math.PI / 2;
        return;
      }
      if (!e.repeat && e.code === 'ArrowRight') {
        blueprintPlacementMode.yaw += Math.PI / 2;
        return;
      }
      if (!e.repeat && e.code === 'Space') {
        e.preventDefault();
        finalizeBlueprintPlacement();
        return;
      }
    }
    if (e.code >= 'Digit1' && e.code <= 'Digit9' && !e.repeat) {
      const n = Number(e.code.slice(5));
      if (n >= 1 && n <= HOTBAR_SIZE) {
        const idx = n - 1;
        if (idx === hotbarSelected) setHotbarIndex(-1);
        else setHotbarIndex(idx);
      }
      return;
    }
    if (e.code === 'KeyE') {
      if (e.repeat) return;
      onKeyEForBobOrChop();
    }
    if (e.code === 'KeyR') endGrab();
    if (e.code === 'KeyF') saveGame();
    if (e.code === 'Space' && document.pointerLockElement === document.body) {
      e.preventDefault();
      if (
        !bobLandShowcase &&
        !devFlyMode &&
        !playerFlyMode &&
        !sawmillPlacementMode &&
        !blueprintPlacementMode &&
        playerBody
      ) {
        if (spherePhysicsActive) {
          const dx = playerBody.position.x - sphereCenter.x;
          const dy = playerBody.position.y - sphereCenter.y;
          const dz = playerBody.position.z - sphereCenter.z;
          const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
          const target = spherePlayRadius + 0.02 + PLAYER_BODY_HALF_H;
          if (
            dist > 1e-3 &&
            dist < target + 0.35 &&
            dist > target - 0.25 &&
            Math.abs(playerBody.velocity.x * dx + playerBody.velocity.y * dy + playerBody.velocity.z * dz) /
              dist <
              0.65
          ) {
            const inv = 1 / dist;
            const ux = dx * inv;
            const uy = dy * inv;
            const uz = dz * inv;
            playerBody.velocity.x += ux * 9;
            playerBody.velocity.y += uy * 9;
            playerBody.velocity.z += uz * 9;
          }
        } else if (
          playerBody.position.y < PLAYER_BODY_HALF_H &&
          Math.abs(playerBody.velocity.y) < 0.55
        ) {
          playerBody.velocity.y = 9;
        }
      }
    }
  });

  document.body.addEventListener(
    'wheel',
    (e) => {
      if (gamePaused || inventoryPanelOpen || bobLandShowcase) return;
      if (document.pointerLockElement !== document.body) return;
      e.preventDefault();
      if (devFlyMode && camera) {
        if (e.deltaY > 0) {
          camera.fov = Math.min(110, camera.fov + 2);
        } else if (e.deltaY < 0) {
          camera.fov = Math.max(freecamMinFov, camera.fov - 2);
        }
        camera.updateProjectionMatrix();
        return;
      }
      const d = Math.sign(e.deltaY);
      if (hotbarSelected < 0) {
        setHotbarIndex(d > 0 ? 0 : HOTBAR_SIZE - 1);
      } else {
        setHotbarIndex((hotbarSelected + d + HOTBAR_SIZE) % HOTBAR_SIZE);
      }
    },
    { passive: false }
  );

  document.getElementById('btn-inv-toggle')?.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleInventoryPanel();
  });
  document.getElementById('inventory-close')?.addEventListener('click', () => {
    if (inventoryPanelOpen) toggleInventoryPanel();
  });
  document.getElementById('inventory-overlay')?.addEventListener('click', (e) => {
    if (e.target.id === 'inventory-overlay' && inventoryPanelOpen) toggleInventoryPanel();
  });
  document.getElementById('inventory-panel')?.addEventListener('click', (e) => e.stopPropagation());
  document.querySelectorAll('#hotbar .hb-slot').forEach((el) => {
    el.addEventListener('click', (ev) => {
      ev.stopPropagation();
      const i = Number(el.dataset.i);
      if (!Number.isFinite(i)) return;
      if (i === hotbarSelected) setHotbarIndex(-1);
      else setHotbarIndex(i);
    });
  });

  wireLandShowcaseUi();
  setupDevMenuUi();
}

/**
 * Multi-planet aware player stabilization: find whichever planet the player is currently closest
 * to, and snap / damp against that planet's surface. This is what makes gravity "swap" between
 * planets feel natural — whichever ball is closest wins the contact & the upward axis.
 */
function stabilizePlayerInnerSphere() {
  if (!playerBody || !spherePhysicsActive) return;
  const best = findClosestPlanet(playerBody.position);
  if (!best) return;
  const dx = playerBody.position.x - best.center.x;
  const dy = playerBody.position.y - best.center.y;
  const dz = playerBody.position.z - best.center.z;
  const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
  if (dist < 1e-4) return;
  const target = best.radius + 0.02 + PLAYER_BODY_HALF_H;
  /** Only snap when the player has penetrated the planet — don't clamp upward motion like jumps or falls. */
  if (dist < target - 0.02) {
    const s = target / dist;
    playerBody.position.x = best.center.x + dx * s;
    playerBody.position.y = best.center.y + dy * s;
    playerBody.position.z = best.center.z + dz * s;
  }
  /** Kill any residual inward velocity when near/at surface (mimics flat-ground contact). */
  const inv = 1 / dist;
  const ux = dx * inv;
  const uy = dy * inv;
  const uz = dz * inv;
  const vr = playerBody.velocity.x * ux + playerBody.velocity.y * uy + playerBody.velocity.z * uz;
  if (vr < -0.02 && dist <= target + 0.08) {
    playerBody.velocity.x -= vr * ux;
    playerBody.velocity.y -= vr * uy;
    playerBody.velocity.z -= vr * uz;
  }
}

function stabilizePlayerGroundContact() {
  if (!playerBody || devFlyMode || playerFlyMode) return;
  if (spherePhysicsActive) {
    stabilizePlayerInnerSphere();
    return;
  }
  if (playerBody.position.y < 0) {
    playerBody.position.y = 0;
    if (playerBody.velocity.y < 0) playerBody.velocity.y = 0;
  }
}

/** Walk is applied as position delta, not XZ velocity — avoids solver explosions vs. forced horizontal speed.
 *  Mutable so the dev menu's "Walk speed" slider can tune them at runtime; sprint is always 1.5× walk. */
const MOVE_PREF_KEY = 'LT2_MovePrefs';
let WALK_SPEED = 6;
let SPRINT_SPEED = 9;
/** "Player fly" mode: separate from freecam — the player **body** keeps existing (and the camera
 *  stays attached to it) but gravity + ground stabilization are skipped so you can fly upward
 *  with Space and downward with Ctrl. Walk speed below controls the fly-XY speed. Great for
 *  traversing a 2.5 km planet without a 30-minute equator walk. */
let playerFlyMode = false;
let PLAYER_FLY_SPEED = 40;

function loadMovePrefs() {
  try {
    const raw = localStorage.getItem(MOVE_PREF_KEY);
    if (!raw) return;
    const data = JSON.parse(raw);
    if (typeof data?.walk === 'number' && Number.isFinite(data.walk)) {
      WALK_SPEED = Math.max(1, Math.min(200, data.walk));
      SPRINT_SPEED = WALK_SPEED * 1.5;
    }
    if (typeof data?.fly === 'number' && Number.isFinite(data.fly)) {
      PLAYER_FLY_SPEED = Math.max(1, Math.min(2000, data.fly));
    }
  } catch (_) {}
}
function saveMovePrefs() {
  try {
    localStorage.setItem(
      MOVE_PREF_KEY,
      JSON.stringify({ walk: WALK_SPEED, fly: PLAYER_FLY_SPEED }),
    );
  } catch (_) {}
}
loadMovePrefs();

/**
 * Planet whose radial axis is currently driving the camera + walk frame. Distinct from
 * {@link runtimePlanetId} (which is the *gameplay* world whose dynamics are spawned in-scene) and
 * from the per-frame closest planet used by gravity (which follows physical reality). We want the
 * camera to behave differently: once you're oriented to Home, stay oriented to Home while flying
 * upward through empty space, and only *rotate the horizon* when you're genuinely about to land
 * on a new planet. Without this, the camera "flips upside down" at the midpoint between two
 * planets which feels like the world swapped out from under you.
 *
 * Initialized on spawn / warp / load. See {@link pickCameraAnchorPlanet}.
 * @type {string | null}
 */
let cameraAnchorPlanetId = null;

/**
 * Smoothed camera-up vector. Each frame this lerps toward the anchor planet's radial-up using
 * {@link updateSmoothCameraUp}. During normal walking the angular delta per frame is tiny and the
 * smoother catches up within one step, so you don't feel it. When the anchor planet *changes*
 * (e.g. you land on Xenon after flying there from Home), this vector rotates toward Xenon's
 * radial over ~0.8 s — Space-Engineers style "gravity roll-in" instead of a single-frame flip.
 * Also: {@link playerHeading} is rotated by the same quaternion each step so it stays tangent,
 * which means the forward direction you were looking stays forward relative to the ground as the
 * horizon rolls.
 */
const smoothCameraUp = new THREE.Vector3(0, 1, 0);
let smoothCameraUpInitialized = false;
/** Radians/second for the gravity roll-in. π/0.8 ≈ 225°/s → a full 180° flip smooths over 0.8 s. */
const CAMERA_UP_ROTATE_SPEED = Math.PI / 0.8;
const _tmpAxis = new THREE.Vector3();
const _tmpRollQ = new THREE.Quaternion();

/**
 * Hysteresis-based planet picker for the camera/walk frame. Rules:
 *   1. If we have no anchor yet, pick the current closest planet and set that as the anchor.
 *   2. Otherwise, only swap the anchor if:
 *        a. Another planet's surface is within **half its radius** (we're clearly about to land), OR
 *        b. The current anchor's surface is more than **2.5× its radius** away AND some other
 *           planet's surface is nearer than the anchor's (we've drifted far from "home" and a new
 *           planet is now the natural reference).
 *   3. Otherwise, keep the current anchor — we're cruising in space, no reason to flip the view.
 *
 * @returns {PlanetPhysicsEntry | null}
 */
function pickCameraAnchorPlanet() {
  if (!spherePhysicsActive || !playerBody || !planetPhysicsEntries.length) return null;
  const closest = findClosestPlanet(playerBody.position);
  /** First-call bootstrap: whatever is closest becomes the anchor. */
  let anchor = cameraAnchorPlanetId ? planetPhysicsById.get(cameraAnchorPlanetId) : null;
  if (!anchor) {
    anchor = closest;
    cameraAnchorPlanetId = anchor?.planetId ?? null;
    return anchor || null;
  }
  if (!closest || closest.planetId === anchor.planetId) return anchor;
  const anchorSurf = surfaceDistTo(playerBody.position, anchor);
  const closestSurf = surfaceDistTo(playerBody.position, closest);
  const nearNewLanding = closestSurf < closest.radius * 0.5;
  const anchorFarAway = anchorSurf > anchor.radius * 2.5 && closestSurf < anchorSurf;
  if (nearNewLanding || anchorFarAway) {
    cameraAnchorPlanetId = closest.planetId;
    return closest;
  }
  return anchor;
}

/** Positive = outside, negative = inside. Handy for the hysteresis checks above. */
function surfaceDistTo(pos, entry) {
  const dx = pos.x - entry.center.x;
  const dy = pos.y - entry.center.y;
  const dz = pos.z - entry.center.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz) - entry.radius;
}

/**
 * Fill `out` with the **smoothed** camera-up vector. The target is the player's radial-up relative
 * to the current camera anchor planet, but we slerp toward it over time so gravity changes (e.g.
 * landing on a new planet) feel like a gentle roll-in rather than a single-frame world flip.
 *
 * Callers should invoke this once per frame; updating it at multiple cadences would make the
 * smoothing unstable. In the animation loop, {@link updatePlayer} calls this first and
 * {@link applyPlayerHorizontalKinematic} then reads it via {@link getPlayerPlanetUp} (which just
 * copies the current smoothed value), so both camera and walk direction stay in sync.
 */
const _tmpSphereUp = new THREE.Vector3();
const _tmpTargetUp = new THREE.Vector3();
function updateCameraUpForFrame(dt) {
  if (!spherePhysicsActive || !playerBody) {
    smoothCameraUpInitialized = false;
    return null;
  }
  const anchor = pickCameraAnchorPlanet();
  if (!anchor) return null;
  const dx = playerBody.position.x - anchor.center.x;
  const dy = playerBody.position.y - anchor.center.y;
  const dz = playerBody.position.z - anchor.center.z;
  const len = Math.hypot(dx, dy, dz);
  if (len < 1e-4) return null;
  _tmpTargetUp.set(dx / len, dy / len, dz / len);
  if (!smoothCameraUpInitialized) {
    smoothCameraUp.copy(_tmpTargetUp);
    smoothCameraUpInitialized = true;
    return smoothCameraUp;
  }
  /** Shortest rotation from current smoothed up to the target up. */
  _tmpAxis.crossVectors(smoothCameraUp, _tmpTargetUp);
  const sinMag = _tmpAxis.length();
  const cosMag = smoothCameraUp.dot(_tmpTargetUp);
  if (sinMag < 1e-6) {
    if (cosMag > 0) {
      /** Already aligned — snap to avoid tiny drift accumulating. */
      smoothCameraUp.copy(_tmpTargetUp);
      return smoothCameraUp;
    }
    /** Exactly antipodal (180° flip). Fall back to the current tangent forward as the roll axis
     *  so the player rolls **over the horizon they're looking at** — intuitive for a pilot. */
    _tmpAxis.copy(playerHeading).normalize();
    if (_tmpAxis.lengthSq() < 1e-6) _tmpAxis.set(1, 0, 0);
  } else {
    _tmpAxis.multiplyScalar(1 / sinMag);
  }
  const totalAngle = Math.atan2(sinMag, cosMag);
  const stepAngle = Math.min(totalAngle, CAMERA_UP_ROTATE_SPEED * Math.max(0, dt));
  if (stepAngle > 1e-6) {
    _tmpRollQ.setFromAxisAngle(_tmpAxis, stepAngle);
    smoothCameraUp.applyQuaternion(_tmpRollQ).normalize();
    /** Rotate the tangent heading by the same amount so "forward" rolls with the horizon. */
    playerHeading.applyQuaternion(_tmpRollQ).normalize();
  }
  return smoothCameraUp;
}

/**
 * Read the current smoothed up vector. Does NOT recompute — call {@link updateCameraUpForFrame}
 * once per frame before anyone reads via this. Returns null if sphere physics inactive.
 */
function getPlayerPlanetUp(out) {
  if (!spherePhysicsActive || !playerBody || !smoothCameraUpInitialized) return null;
  out.copy(smoothCameraUp);
  return out;
}

/**
 * Reproject {@link playerHeading} onto the tangent plane of `up` and renormalize. This is a cheap
 * discrete parallel transport: as the player walks from one point on the sphere to a neighbor, the
 * local up axis rotates slightly, so the stored heading develops a tiny radial component that
 * would otherwise grow each frame. Subtracting the component along `up` keeps it tangent.
 *
 * If heading ends up essentially parallel to up (degenerate: e.g. player teleported to a pole with
 * a heading that used to be horizontal), fall back to a perpendicular reference so the camera
 * doesn't freeze facing into the ground.
 */
function reprojectHeadingOntoTangent(up) {
  const d = playerHeading.x * up.x + playerHeading.y * up.y + playerHeading.z * up.z;
  playerHeading.x -= up.x * d;
  playerHeading.y -= up.y * d;
  playerHeading.z -= up.z * d;
  if (playerHeading.lengthSq() < 1e-4) {
    /** Pick any axis not colinear with up, project to tangent plane. */
    playerHeading.set(1, 0, 0);
    const d2 = playerHeading.x * up.x + playerHeading.y * up.y + playerHeading.z * up.z;
    playerHeading.x -= up.x * d2;
    playerHeading.y -= up.y * d2;
    playerHeading.z -= up.z * d2;
    if (playerHeading.lengthSq() < 1e-4) {
      playerHeading.set(0, 0, 1);
      const d3 = playerHeading.x * up.x + playerHeading.y * up.y + playerHeading.z * up.z;
      playerHeading.x -= up.x * d3;
      playerHeading.y -= up.y * d3;
      playerHeading.z -= up.z * d3;
    }
  }
  playerHeading.normalize();
}

/**
 * WASD walk vector for sphere-physics worlds. Returns a **full world-space tangent vector** (x/y/z)
 * rather than a flat XZ pair — the caller uses it directly as a position delta without needing a
 * separate tangent projection. Scaled by the active walk/sprint speed.
 */
/**
 * Reset {@link playerHeading} to a sane tangent direction at the player's current planet position.
 * Called after spawn / warp / respawn so the camera isn't left pointing into the ground or into
 * space. Picks world +Z as the reference "forward" and projects it onto the tangent plane; if that
 * collapses (player at a pole aligned with Z) it falls back to +X.
 */
function initPlayerHeadingOnPlanet() {
  /** Reset the camera anchor so the first frame doesn't keep an anchor from a previous life (e.g.
   *  after a warp from Home to Xenon, we want Xenon to become the anchor immediately, not to
   *  keep Home's axis). {@link pickCameraAnchorPlanet} will re-seed from the current closest. */
  cameraAnchorPlanetId = null;
  /** Reset the smooth-up so the first frame after spawn/warp **snaps** instead of rolling — we
   *  don't want a 0.8 s roll-in every time you load a save. {@link updateCameraUpForFrame} will
   *  copy the target radial directly because `smoothCameraUpInitialized` is false. */
  smoothCameraUpInitialized = false;
  if (!spherePhysicsActive || !playerBody) {
    playerHeading.set(0, 0, -1);
    return;
  }
  /** Compute the raw radial-up immediately (bypassing the smoothing path, which isn't primed yet)
   *  so we can seed a tangent heading at spawn. */
  const anchor = pickCameraAnchorPlanet();
  if (!anchor) {
    playerHeading.set(0, 0, -1);
    return;
  }
  const dx = playerBody.position.x - anchor.center.x;
  const dy = playerBody.position.y - anchor.center.y;
  const dz = playerBody.position.z - anchor.center.z;
  const len = Math.hypot(dx, dy, dz);
  if (len < 1e-4) {
    playerHeading.set(0, 0, -1);
    return;
  }
  _tmpSphereUp.set(dx / len, dy / len, dz / len);
  /** Seed the smoothed-up so reads from `getPlayerPlanetUp` immediately return the right axis. */
  smoothCameraUp.copy(_tmpSphereUp);
  smoothCameraUpInitialized = true;
  playerHeading.set(0, 0, -1);
  reprojectHeadingOntoTangent(_tmpSphereUp);
}

const _tmpWalkRight = new THREE.Vector3();
function getIntendedWalkSphere(up) {
  if (!playerBody) return null;
  const speed = keys.has('ShiftLeft') || keys.has('ShiftRight') ? SPRINT_SPEED : WALK_SPEED;
  let fx = 0;
  let fz = 0;
  if (keys.has('KeyW')) fz -= 1;
  if (keys.has('KeyS')) fz += 1;
  if (keys.has('KeyA')) fx -= 1;
  if (keys.has('KeyD')) fx += 1;
  if (fx === 0 && fz === 0) return null;
  const invLen = 1 / Math.hypot(fx, fz);
  fx *= invLen;
  fz *= invLen;
  _tmpWalkRight.crossVectors(playerHeading, up).normalize();
  /** fz = -1 means "forward" (W) → direction = +heading. fx = +1 means "right" (D) → +right. */
  return {
    x: (playerHeading.x * -fz + _tmpWalkRight.x * fx) * speed,
    y: (playerHeading.y * -fz + _tmpWalkRight.y * fx) * speed,
    z: (playerHeading.z * -fz + _tmpWalkRight.z * fx) * speed,
  };
}

/**
 * After physics: move on XZ only. Horizontal motion is not driven by body.velocity (only gravity/jump use vy).
 */
function applyPlayerHorizontalKinematic(dt) {
  if (!playerBody || gamePaused) return;
  if (bobLandShowcase) return;
  if (devFlyMode) {
    // Detached freecam: local body stays frozen at toggle point.
    playerBody.position.set(freecamBodyAnchor.x, freecamBodyAnchor.y, freecamBodyAnchor.z);
    playerBody.velocity.set(0, 0, 0);
    playerBody.angularVelocity.set(0, 0, 0);
    return;
  }
  if (playerFlyMode) {
    /** Body-attached flight: velocity is force-cleared each frame, then WASD + Space/Ctrl write
     *  position deltas directly. On sphere worlds, up/down is along radial-up (feels like
     *  jetpacking straight off the ground). On flat worlds, up/down is along world Y. */
    playerBody.velocity.set(0, 0, 0);
    playerBody.angularVelocity.set(0, 0, 0);
    const sprint = keys.has('ShiftLeft') || keys.has('ShiftRight');
    const flySpeed = PLAYER_FLY_SPEED * (sprint ? 1.8 : 1);
    let upAmt = 0;
    if (keys.has('Space')) upAmt += 1;
    if (keys.has('ControlLeft') || keys.has('ControlRight')) upAmt -= 1;
    if (spherePhysicsActive) {
      const up = getPlayerPlanetUp(_tmpSphereUp);
      if (up) {
        reprojectHeadingOntoTangent(up);
        /** Horizontal walk along tangent heading/right, matching the on-foot direction conventions. */
        let fx = 0;
        let fz = 0;
        if (keys.has('KeyW')) fz -= 1;
        if (keys.has('KeyS')) fz += 1;
        if (keys.has('KeyA')) fx -= 1;
        if (keys.has('KeyD')) fx += 1;
        if (fx !== 0 || fz !== 0) {
          const invLen = 1 / Math.hypot(fx, fz);
          fx *= invLen;
          fz *= invLen;
          _tmpWalkRight.crossVectors(playerHeading, up).normalize();
          playerBody.position.x += (playerHeading.x * -fz + _tmpWalkRight.x * fx) * flySpeed * dt;
          playerBody.position.y += (playerHeading.y * -fz + _tmpWalkRight.y * fx) * flySpeed * dt;
          playerBody.position.z += (playerHeading.z * -fz + _tmpWalkRight.z * fx) * flySpeed * dt;
        }
        if (upAmt !== 0) {
          playerBody.position.x += up.x * upAmt * flySpeed * dt;
          playerBody.position.y += up.y * upAmt * flySpeed * dt;
          playerBody.position.z += up.z * upAmt * flySpeed * dt;
        }
      }
    } else {
      const walk = getIntendedWalkXZ();
      if (walk) {
        /** `walk` is already scaled by WALK_SPEED/SPRINT_SPEED — renormalize to our fly speed. */
        const baseSpeed = sprint ? SPRINT_SPEED : WALK_SPEED;
        const scale = baseSpeed > 0 ? flySpeed / baseSpeed : 1;
        playerBody.position.x += walk.x * scale * dt;
        playerBody.position.z += walk.z * scale * dt;
      }
      playerBody.position.y += upAmt * flySpeed * dt;
    }
    return;
  }
  if (spherePhysicsActive) {
    /** Use the planet-local heading + radial-up frame so WASD moves tangent to the planet regardless
     *  of where on the sphere the player is. Previous path projected a world-XZ walk onto the
     *  tangent plane which worked on top of the planet but caused a "fall off" at the equator:
     *  yaw was measured from world +Y so "forward" slowly drifted off-tangent as you walked. */
    const up = getPlayerPlanetUp(_tmpSphereUp);
    if (up) {
      reprojectHeadingOntoTangent(up);
      const walk = getIntendedWalkSphere(up);
      if (walk) {
        playerBody.position.x += walk.x * dt;
        playerBody.position.y += walk.y * dt;
        playerBody.position.z += walk.z * dt;
      }
    }
    return;
  }
  const walk = getIntendedWalkXZ();
  if (walk) {
    playerBody.position.x += walk.x * dt;
    playerBody.position.z += walk.z * dt;
  }
  playerBody.velocity.x = 0;
  playerBody.velocity.z = 0;
}

function clearPlayerHorizontalVelocity() {
  if (!playerBody || spherePhysicsActive) return;
  playerBody.velocity.x = 0;
  playerBody.velocity.z = 0;
}

function onResize() {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
  neonBloomPipeline?.setSize(window.innerWidth, window.innerHeight);
}

const euler = new THREE.Euler(0, 0, 0, 'YXZ');
const remoteQuat = new THREE.Quaternion();
/** Scratch objects for the per-frame sphere-planet camera basis rebuild. Hoisted to module scope
 *  so we don't allocate 5 THREE objects every animate() tick. */
const _tmpCamRight = new THREE.Vector3();
const _tmpCamUp = new THREE.Vector3();
const _tmpCamFwd = new THREE.Vector3();
const _tmpPitchQ = new THREE.Quaternion();
const _tmpCamMat = new THREE.Matrix4();

/** World-space XZ from WASD + yaw; null if no movement keys. */
function getIntendedWalkXZ() {
  if (!playerBody) return null;
  const speed = keys.has('ShiftLeft') || keys.has('ShiftRight') ? SPRINT_SPEED : WALK_SPEED;
  let fx = 0;
  let fz = 0;
  if (keys.has('KeyW')) fz -= 1;
  if (keys.has('KeyS')) fz += 1;
  if (keys.has('KeyA')) fx -= 1;
  if (keys.has('KeyD')) fx += 1;
  if (fx === 0 && fz === 0) return null;
  const len = Math.hypot(fx, fz);
  fx /= len;
  fz /= len;
  const sin = Math.sin(yaw);
  const cos = Math.cos(yaw);
  return {
    x: (fx * cos + fz * sin) * speed,
    z: (-fx * sin + fz * cos) * speed,
  };
}

function updatePlayer(dt) {
  /** Advance the smoothed camera-up vector ONCE per frame, before anything reads it. Camera basis
   *  rebuild and the walk kinematic both consume the result via {@link getPlayerPlanetUp}. */
  updateCameraUpForFrame(dt);

  if (bobLandShowcase && landPlotSites[landShowcaseIndex]) {
    const slot = landPlotSites[landShowcaseIndex];
    const dist = 17;
    const elev = 10;
    const ang = Math.PI * 0.2;
    const tx = slot.x + Math.sin(ang) * dist;
    const tz = slot.z + Math.cos(ang) * dist;
    tmpV3.set(tx, elev, tz);
    camera.position.lerp(tmpV3, Math.min(1, dt * 2.6));
    camera.lookAt(slot.x, 0.25, slot.z);
    return;
  }

  /** On sphere-physics planets, build the camera basis directly from the planet-local (heading,
   *  up, right) frame so looking "up" always points away from the planet's center and walking
   *  around the equator doesn't make the player fall off (see {@link playerHeading} doc). */
  if (spherePhysicsActive && !devFlyMode && playerBody) {
    const up = getPlayerPlanetUp(_tmpSphereUp);
    if (up) {
      reprojectHeadingOntoTangent(up);
      _tmpCamRight.crossVectors(playerHeading, up).normalize();
      /** Rotate both the forward heading and the up axis around `right` by `pitch` so pitching up
       *  tilts the camera's up axis forward rather than just rotating within a fixed world frame. */
      _tmpPitchQ.setFromAxisAngle(_tmpCamRight, pitch);
      _tmpCamFwd.copy(playerHeading).applyQuaternion(_tmpPitchQ);
      _tmpCamUp.copy(up).applyQuaternion(_tmpPitchQ);
      /** Basis columns = (right, up, back). In Three.js, camera looks down -Z, so the third column
       *  is -forward. Negate in place (makeBasis copies the values; _tmpCamFwd is scratch). */
      _tmpCamMat.makeBasis(_tmpCamRight, _tmpCamUp, _tmpCamFwd.negate());
      camera.quaternion.setFromRotationMatrix(_tmpCamMat);
    } else {
      euler.set(pitch, yaw, 0, 'YXZ');
      camera.quaternion.setFromEuler(euler);
    }
  } else {
    euler.set(pitch, yaw, 0, 'YXZ');
    camera.quaternion.setFromEuler(euler);
  }

  if (devFlyMode) {
    const walk = getIntendedWalkXZ();
    /** Freecam speed tracks the Fly-speed slider too; sprint multiplies by 1.8× like player-fly. */
    const sprint = keys.has('ShiftLeft') || keys.has('ShiftRight');
    const flySpeed = PLAYER_FLY_SPEED * (sprint ? 1.8 : 1);
    let up = 0;
    if (keys.has('Space')) up += 1;
    if (keys.has('ControlLeft') || keys.has('ControlRight')) up -= 1;
    if (walk) {
      /** `walk` is already scaled by WALK_SPEED/SPRINT_SPEED — renormalize to our freecam speed. */
      const baseSpeed = sprint ? SPRINT_SPEED : WALK_SPEED;
      const scale = baseSpeed > 0 ? flySpeed / baseSpeed : 1;
      freecamPos.x += walk.x * dt * scale;
      freecamPos.z += walk.z * dt * scale;
    }
    freecamPos.y += up * flySpeed * dt;
    camera.position.copy(freecamPos);
    return;
  }

  if (gamePaused) {
    if (!spherePhysicsActive) {
      playerBody.velocity.x = 0;
      playerBody.velocity.z = 0;
    }
    camera.position.set(
      playerBody.position.x,
      playerBody.position.y + getPlayerCameraYOffset(),
      playerBody.position.z
    );
    return;
  }

  camera.position.set(
    playerBody.position.x,
    playerBody.position.y + getPlayerCameraYOffset(),
    playerBody.position.z
  );
}

let last = performance.now();
/**
 * Cached neon bloom intensity so animate() doesn't hit localStorage + JSON.parse every frame.
 * The only runtime writer is the prefab editor (a separate page); a cross-tab `storage` event
 * invalidates the cache so live tweaks from that tab still apply within a frame.
 */
let _neonIntensityCache = null;
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (!e || e.key === null || e.key === NEON_SETTINGS_KEY) _neonIntensityCache = null;
  });
}
function animate(now) {
  requestAnimationFrame(animate);
  const dt = Math.min(0.05, (now - last) / 1000);
  last = now;

  updatePlayer(dt);
  syncFpToolRigToCamera();
  updateAxeChopSwing(dt);
  updateSawmillPlacementGhost();
  updateBlueprintPlacementGhost();
  updateSawmills(dt);
  updateSawmillPlankReveals(dt);
  updateChopping(dt);
  if (sellParticleGroup) {
    applySellZoneParticlePose();
    sellParticleGroup.rotation.y += dt * 0.11;
  }
  clearPlayerHorizontalVelocity();
  if (grabTarget && !gamePaused) {
    grabTarget.velocity.set(0, 0, 0);
    grabTarget.angularVelocity.set(0, 0, 0);
  }
  applyConveyorBelts(dt);
  if (spherePhysicsActive && world) {
    /** Multi-planet gravity: each dynamic body is pulled toward whichever planet is closest. */
    applyMultiPlanetGravity(dt);
  }
  world.step(1 / 60, dt, 5);
  snapGrabbedWood();
  stabilizePlayerGroundContact();
  applyPlayerHorizontalKinematic(dt);

  for (const { mesh, body } of dynamicPairs) {
    syncMeshFromBody(mesh, body);
    const leafMesh = mesh.userData.leafMesh;
    const leafBody = mesh.userData.leafBody;
    if (leafMesh && leafBody && (mesh.userData.kind === 'trunk' || mesh.userData.kind === 'log')) {
      const leafR = leafMesh.geometry.parameters.radius;
      const halfH = (mesh.geometry.parameters.height ?? 1) * 0.5;
      const ly = tmpV2.set(0, 1, 0).applyQuaternion(mesh.quaternion);
      const lift = halfH + leafR * 0.35;
      leafMesh.position.set(
        mesh.position.x + ly.x * lift,
        mesh.position.y + ly.y * lift,
        mesh.position.z + ly.z * lift
      );
      leafBody.position.set(leafMesh.position.x, leafMesh.position.y, leafMesh.position.z);
      leafBody.velocity.setZero();
      leafBody.angularVelocity.setZero();
      leafBody.quaternion.set(0, 0, 0, 1);
      leafMesh.quaternion.set(0, 0, 0, 1);
    }
  }
  updatePlotBlueprintGrabFeed(dt);
  updateConstructionHudLine();
  for (const { branchMesh, body } of treeBranchStaticColliderList) {
    syncStaticBodyToMeshWorld(branchMesh, body);
  }

  trySellLogs();
  updateNpcUi();
  updateKitOpenHint();
  updateToolHintVisibility();
  updateShopHoverLabel();
  updateGrabHint();
  updateFreecamGhostVisibility();
  updateHeldAxeVisibility();
  updateFreecamGhostAnimation(dt);
  /** Keep the starfield centered on the camera so stars feel infinitely far away regardless of
   *  how far the player has traveled through the universe. */
  updateSpaceBackground();
  /** Grow `camera.far` each frame so planets stay visible even when the player is flung far from
   *  any of them (otherwise a 2500 m sphere 30 km away would just pop out of view). */
  updateCameraFarForSpaceTravel();
  if (neonBloomPipeline) {
    if (_neonIntensityCache == null) _neonIntensityCache = loadNeonSettings().intensity;
    applyNeonBloomToPass(neonBloomPipeline.bloomPass, _neonIntensityCache);
    neonBloomPipeline.render();
  } else {
    renderer.render(scene, camera);
  }
  updateFpsCounter(now);
}

/**
 * Rolling-average FPS display driven by the render loop's `performance.now()` timestamps.
 * Uses a 500ms window so the number doesn't jitter frame-to-frame but still reacts fast enough
 * to let the user see the effect of density/graphics tweaks in real time.
 */
const _fpsState = {
  el: /** @type {HTMLElement | null} */ (null),
  frames: 0,
  windowStart: 0,
  lastDisplayedFps: -1,
};
function updateFpsCounter(now) {
  if (!_fpsState.el) {
    _fpsState.el = document.getElementById('fps-counter');
    if (!_fpsState.el) return;
    _fpsState.windowStart = now;
  }
  _fpsState.frames++;
  const dt = now - _fpsState.windowStart;
  if (dt >= 500) {
    const fps = Math.round((_fpsState.frames * 1000) / dt);
    _fpsState.frames = 0;
    _fpsState.windowStart = now;
    if (fps !== _fpsState.lastDisplayedFps) {
      _fpsState.lastDisplayedFps = fps;
      _fpsState.el.textContent = `FPS ${fps}`;
      /** Green ≥55, amber ≥30, red otherwise — a quick glance tells you whether the forest
       *  density / effects you just changed are actually affordable. */
      _fpsState.el.style.color = fps >= 55 ? '#7f7' : fps >= 30 ? '#fd7' : '#f77';
    }
  }
}

const remoteTarget = { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 };
/** @type {THREE.Mesh | null} */
let remotePlayer = null;

function mountRemotePlayerAndHud() {
  remotePlayer = new THREE.Mesh(
    new THREE.CapsuleGeometry(0.45, 0.9, 6, 12),
    new THREE.MeshStandardMaterial({ color: 0xff8866, roughness: 0.6 })
  );
  remotePlayer.visible = false;
  remotePlayer.castShadow = true;
  scene.add(remotePlayer);

  window.LumberGame = {
  getPlayerState() {
    return {
      x: playerBody.position.x,
      y: playerBody.position.y,
      z: playerBody.position.z,
      yaw,
      pitch,
    };
  },
  applyRemotePlayer(state) {
    if (!state) {
      remotePlayer.visible = false;
      return;
    }
    remotePlayer.visible = true;
    remoteTarget.x = state.x;
    remoteTarget.y = state.y;
    remoteTarget.z = state.z;
    remoteTarget.yaw = state.yaw ?? 0;
    remoteTarget.pitch = state.pitch ?? 0;
  },
  getLogStates() {
    const out = [];
    for (const { mesh, body } of dynamicPairs) {
      const k = mesh.userData.kind;
      if (k !== 'log' && k !== 'trunk') continue;
      out.push({
        id: mesh.userData.netId,
        kind: k,
        x: body.position.x,
        y: body.position.y,
        z: body.position.z,
        q: [body.quaternion.x, body.quaternion.y, body.quaternion.z, body.quaternion.w],
        trunk: mesh.userData.trunk ?? null,
      });
    }
    return out;
  },
  applyLogStates(states, isHost) {
    if (isHost) return;
    if (!Array.isArray(states)) return;
    for (const s of states) {
      const pair = dynamicPairs.find((p) => p.mesh.userData.netId === s.id);
      if (!pair) continue;
      pair.body.position.set(s.x, s.y, s.z);
      pair.body.quaternion.set(s.q[0], s.q[1], s.q[2], s.q[3]);
      pair.body.velocity.setZero();
      pair.body.angularVelocity.setZero();
      syncMeshFromBody(pair.mesh, pair.body);
    }
  },
  getRemoteMesh() {
    return remotePlayer;
  },
  getRuntimePlanetId() {
    return runtimePlanetId;
  },
  getPlanetContentRoot() {
    return planetRuntimeContentRoot;
  },
  getWorldRegistry() {
    return worldRegistry;
  },
  getSpherePhysicsActive() {
    return spherePhysicsActive;
  },
  getActiveWorldDef() {
    return getActiveWorldDefFromSlice(getActiveMapWorldSlice());
  },
  /**
   * Dev / P10.5: save current planet dynamics, set next active map planet, full reload.
   * @param {string} planetId must exist in `LT2_MapEditor` `worlds[]`.
   * @returns {boolean}
   */
  requestDevWarpToPlanet(planetId) {
    const pid = String(planetId || '').trim();
    if (!pid) return false;
    if (pid === runtimePlanetId) return false;
    return warpPlayerToPlanet(pid);
  },
  devAddMoney(amount) {
    money += Math.floor(amount);
    updateMoneyHud();
  },
  devSpawnTreeAt(x, z) {
    createTree(x, z);
  },
  devSetFlyMode(on) {
    setDevFlyMode(on);
  },
  devGetFlyMode() {
    return devFlyMode;
  },
};
}

export function startGame() {
  if (globalThis.__ltLumberStarted) return;
  globalThis.__ltLumberStarted = true;
  /** Defensive wrapper: if anything in init throws (corrupt save, bad space-bg
   *  config, etc.), we MUST clear the `lt-game-running` body class — otherwise
   *  the main menu stays hidden by CSS and the player has no way to recover
   *  even after a page reload. We also unset `__ltLumberStarted` so a second
   *  Play click can retry with the fallback config the catch handler installs. */
  try {
    initWorld();
    mountRemotePlayerAndHud();
    /** Floating "Space BG" button (bottom-left) + B hotkey for the live
     *  starfield / galaxy editor. Must run after `initWorld` because that's
     *  what first creates `scene` — if we installed the listener before that,
     *  the first rebuild would silently no-op. */
    installSpaceBgEditorUi();
    onSpaceBgChange(() => rebuildSpaceBackground());
    animate(performance.now());
  } catch (err) {
    console.error('[startGame] init crashed — resetting space-bg config + unlocking main menu', err);
    try {
      localStorage.removeItem('LT2_SpaceBgConfig_v1');
    } catch (_) { /* noop */ }
    document.body.classList.remove('lt-game-running');
    globalThis.__ltLumberStarted = false;
    try {
      const msg = document.createElement('div');
      msg.textContent = 'Startup error — space background settings reset. Click Play again.';
      Object.assign(msg.style, {
        position: 'fixed',
        top: '12px',
        left: '50%',
        transform: 'translateX(-50%)',
        zIndex: '100000',
        padding: '10px 16px',
        background: 'rgba(40,0,0,0.92)',
        color: '#ffd6d6',
        border: '1px solid rgba(255,120,120,0.6)',
        borderRadius: '8px',
        fontFamily: 'system-ui, sans-serif',
        fontSize: '13px',
      });
      document.body.appendChild(msg);
      setTimeout(() => msg.remove(), 6000);
    } catch (_) { /* noop */ }
    return;
  }
  (function remoteInterp() {
    requestAnimationFrame(remoteInterp);
    if (!remotePlayer) return;
    remotePlayer.position.lerp(
      tmpV.set(remoteTarget.x, remoteTarget.y + 0.5, remoteTarget.z),
      0.22
    );
    euler.set(remoteTarget.pitch, remoteTarget.yaw, 0, 'YXZ');
    remoteQuat.setFromEuler(euler);
    remotePlayer.quaternion.slerp(remoteQuat, 0.22);
  })();
}
