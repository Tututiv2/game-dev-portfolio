import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { CORE_MAX_HP } from "../sim/initialState";
import { devSettings } from "./devSettings";
import { PLANET_CENTER_Y, PLANET_RADIUS, surfaceUpComponents, surfaceY } from "../planet/surfaceMath";
import {
  INTERSTELLAR_V_MAX,
  clampToFlightShell,
  innerSoiBlend01,
  isDeepOrbitFlight,
  radialDistanceFromPlanetCenter,
  radialUpAt,
} from "../planet/spaceFlight";
import {
  SKY_DENSE_STARS,
  SKY_MAIN_STARS,
  SKY_TOTAL_PICKABLE,
  buildPickableStarDirections,
  buildStarCatalog,
} from "../planet/skydomeSeed";
import { initialGalacticPositionLy } from "../sim/universe/galacticSpace";
import type { BuildingKind, FlightState, GameState } from "../sim/types";
import { worldDirectionToSector } from "./galaxyToSky";
import { createSimpleSurface } from "./SimpleSurface";

const PLANET_CENTER = new THREE.Vector3(0, PLANET_CENTER_Y, 0);

const COLORS = {
  core: 0x3d8fd1,
  extractor: 0x6bbf59,
  purifier: 0x4a9ba8,
  relay: 0xc9a84a,
  sentry: 0xcc5a4a,
  enemy: 0xd94a4a,
} as const;

/** Centered on the camera each frame: effectively “at infinity” vs local geometry. */
const SKY_R = 1_200_000;
/** World units per light-year for 3D star placement. A 4 ly star appears 2000 units away. */
const STAR_WORLD_SCALE = 500;
/** World-unit size for main (bright) star point sprites with sizeAttenuation. */
const MAIN_STAR_SIZE = 30;
/** World-unit size for dense (dim) star point sprites. */
const DENSE_STAR_SIZE = 8;
const NAV_ARROW_LEN = 12_000;
const LOCAL_Y = new THREE.Vector3(0, 1, 0);

/** `SimpleSurface` main star `PointsMaterial` color; restored after void flight. */
const SKY_MAIN_STAR_COLOR = 0xf2f4ff;
/** Visible in dark void; still cooler than 0xf2f4ff to limit channel blow-out. */
const SKY_MAIN_STAR_COLOR_VOID = 0xa8b8d0;

/** r > 320, or `innerSoiBlend01` is already “thin” — hide the HSL skydome before the hard M1 threshold. */
function voidStreakSoiVfx(
  flight: { active: boolean },
  x: number,
  y: number,
  z: number
): boolean {
  if (!flight.active) return false;
  if (isDeepOrbitFlight(flight, x, y, z)) return true;
  const r = radialDistanceFromPlanetCenter(x, y, z);
  return innerSoiBlend01(r) < 0.32;
}

/**
 * Surface 3D view: skydome, spherical ground patch, sim entities. Uses built-in materials (see {@link createSimpleSurface}).
 * Never mutates {@link GameState}.
 */
export class WorldView {
  readonly domElement: HTMLCanvasElement;
  readonly camera: THREE.PerspectiveCamera;
  private readonly renderer: THREE.WebGLRenderer;
  private readonly scene: THREE.Scene;
  private readonly controls: OrbitControls;
  private readonly coreMesh: THREE.Mesh;
  private readonly planet: THREE.Mesh;
  private simple: ReturnType<typeof createSimpleSurface>;
  private _skydomeSeed = -1;
  private _pickStarDirs: Float32Array | null = null;
  /** [wx, wy, wz, R_ly] × SKY_TOTAL_PICKABLE — precomputed star catalog for real 3D placement. */
  private _starCatalog: Float32Array | null = null;
  /** Per-frame world-space positions for main stars (SKY_MAIN_STARS × 3 floats). */
  private _mainStarBuf: Float32Array | null = null;
  /** Per-frame world-space positions for dense stars (SKY_DENSE_STARS × 3 floats). */
  private _denseStarBuf: Float32Array | null = null;
  /** Current sector anchor in ly-space (needed for star position math). */
  private _anchorLy: { x: number; y: number; z: number } = { x: 0, y: 0, z: 0 };
  /** Current player gpos in ly-space. */
  private _gposLy: { x: number; y: number; z: number } = { x: 0, y: 0, z: 0 };
  /** Whether star catalog has been built for the current seed (avoids rebuilding). */
  private _catalogBuilt = false;
  private _gameSeed = 0;
  private readonly _starWorld = new THREE.Vector3();
  private buildingGroup = new THREE.Group();
  private enemyGroup = new THREE.Group();
  private pointerDown = { x: 0, y: 0, time: 0 };
  private sunDir: THREE.Vector3;
  private readonly dirLight: THREE.DirectionalLight;
  private readonly hemi: THREE.HemisphereLight;
  private readonly resizeObserver: ResizeObserver;
  private readonly raycaster = new THREE.Raycaster();
  private readonly _ndcMouse = new THREE.Vector2();
  private readonly _up = new THREE.Vector3();
  private readonly _p = new THREE.Vector3();
  private readonly _p2 = new THREE.Vector3();
  private readonly _p3 = new THREE.Vector3();
  private readonly _look = new THREE.Vector3();
  private readonly _fwd = new THREE.Vector3();
  private readonly _right = new THREE.Vector3();
  private readonly _move = new THREE.Vector3();
  private readonly _tmp = new THREE.Vector3();
  /** Previous orbit look-at; used to pan the camera with the command core. */
  private readonly _lastOrbitLookAt = new THREE.Vector3(0, 0, 0);
  private _orbitFollowInited = false;
  private readonly _navGroup = new THREE.Group();
  private _navArrow: THREE.ArrowHelper | null = null;
  private readonly _navDir = new THREE.Vector3();
  private readonly _navOrigin = new THREE.Vector3();
  /** After `applyUniverseStyle` / sun tint; M4 SOI VFX scales from this. */
  private _skyBaseOpacity = 0.22;
  private readonly _hemiBase = 0.4;
  private readonly _ambiBase = 0.32;
  private readonly _galBaseOpacity = 0.5;
  private readonly _perspFov = 55;
  private readonly _ambi: THREE.AmbientLight;
  /** Scratch colors — `render`/`setIndustryPressure` run every frame; avoid per-frame allocations. */
  private readonly _bgScratch = new THREE.Color();
  private readonly _bgBase = new THREE.Color(0x04060c);
  private readonly _bgPolluted = new THREE.Color(0x0a100c);
  private readonly _bgVoid = new THREE.Color(0x01020a);
  /**
   * Shared geometries/materials + per-entity mesh pools. `syncState` runs every frame;
   * creating (and never disposing) fresh geometries/materials per entity leaked GPU
   * resources and thrashed the GC. Dimensions/colors match the original per-frame meshes.
   */
  private readonly _buildingGeos: Record<BuildingKind, THREE.BufferGeometry> = {
    extractor: new THREE.BoxGeometry(1.1, 0.7, 1.1),
    purifier: new THREE.BoxGeometry(1.1, 0.55, 1.1),
    relay: new THREE.BoxGeometry(0.9, 1.05, 0.9),
    sentry: new THREE.CylinderGeometry(0.2, 0.35, 0.9, 10),
  };
  private readonly _buildingMats: Record<BuildingKind, THREE.MeshStandardMaterial> = {
    extractor: new THREE.MeshStandardMaterial({ color: COLORS.extractor, roughness: 0.5, metalness: 0.05 }),
    purifier: new THREE.MeshStandardMaterial({ color: COLORS.purifier, roughness: 0.5, metalness: 0.05 }),
    relay: new THREE.MeshStandardMaterial({ color: COLORS.relay, roughness: 0.5, metalness: 0.15 }),
    sentry: new THREE.MeshStandardMaterial({ color: COLORS.sentry, roughness: 0.4, metalness: 0.25 }),
  };
  /** Offset from the surface point along the outward normal (h * 0.5 + 0.05). */
  private readonly _buildingAlong: Record<BuildingKind, number> = {
    extractor: 0.7 * 0.5 + 0.05,
    purifier: 0.55 * 0.5 + 0.05,
    relay: 1.05 * 0.5 + 0.05,
    sentry: 0.9 * 0.5 + 0.05,
  };
  private readonly _enemyGeoSurface = new THREE.CapsuleGeometry(0.25, 0.4, 4, 8);
  private readonly _enemyGeoAerial = new THREE.CapsuleGeometry(0.2, 0.32, 4, 8);
  private readonly _enemyMatSurface = new THREE.MeshStandardMaterial({
    color: COLORS.enemy,
    emissive: 0x401010,
    roughness: 0.55,
  });
  private readonly _enemyMatAerial = new THREE.MeshStandardMaterial({
    color: 0xcc5050,
    emissive: 0x502020,
    roughness: 0.55,
  });
  private readonly _buildingMeshes = new Map<number, { mesh: THREE.Mesh; type: BuildingKind; stamp: number }>();
  private readonly _enemyMeshes = new Map<number, { mesh: THREE.Mesh; aerial: boolean; stamp: number }>();
  private _syncStamp = 0;

  constructor(canvas: HTMLCanvasElement) {
    this.domElement = canvas;
    this.sunDir = new THREE.Vector3(0.35, 0.88, 0.32).normalize();
    this.camera = new THREE.PerspectiveCamera(55, 1, 0.1, 2_500_000);
    this.camera.position.set(20, 16, 20);

    this.renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: true,
      alpha: false,
      powerPreference: "high-performance",
      failIfMajorPerformanceCaveat: false,
    });
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.NoToneMapping;
    this.renderer.toneMappingExposure = 1.0;
    if (import.meta.env.DEV) {
      const gl = this.renderer.getContext();
      if (gl) console.info("[Cosmic Forges] WebGL:", gl.getParameter(gl.VERSION));
      else console.error("[Cosmic Forges] WebGL context is null — 3D view will be blank.");
    }

    this.scene = new THREE.Scene();
    this.scene.fog = null;
    const baseBg = 0x04060c;
    this.scene.background = new THREE.Color(baseBg);
    this.renderer.setClearColor(baseBg, 1);

    this.simple = createSimpleSurface(this.sunDir, SKY_R, 0);
    this._skydomeSeed = 0;
    this._pickStarDirs = buildPickableStarDirections(0);
    this.scene.add(this.simple.sky);
    this.scene.add(this.simple.galactic);
    this.scene.add(this.simple.stars);
    this.scene.add(this.simple.denseStars);
    this.scene.add(this._navGroup);
    this._navGroup.renderOrder = 1;
    this.simple.applyStarDev(devSettings.sky);

    const planetMat = new THREE.MeshStandardMaterial({
      color: 0x3d5268,
      roughness: 0.88,
      metalness: 0.08,
    });
    this.planet = new THREE.Mesh(
      new THREE.SphereGeometry(PLANET_RADIUS, 72, 48),
      planetMat
    );
    this.planet.position.set(0, PLANET_CENTER_Y, 0);
    this.planet.name = "Planet";
    this.scene.add(this.planet);

    this.hemi = new THREE.HemisphereLight(0x4a5a6a, 0x0a0a0c, 0.4);
    this.scene.add(this.hemi);
    this._ambi = new THREE.AmbientLight(0x4a4a5a, this._ambiBase);
    this.scene.add(this._ambi);
    this.dirLight = new THREE.DirectionalLight(0xfff0e0, 1.15);
    this.dirLight.position.set(this.sunDir.x * 200, this.sunDir.y * 200, this.sunDir.z * 200);
    this.scene.add(this.dirLight);

    this.coreMesh = new THREE.Mesh(
      new THREE.CylinderGeometry(0.4, 0.55, 0.5, 20),
      new THREE.MeshStandardMaterial({
        color: COLORS.core,
        emissive: 0x0a2a40,
        emissiveIntensity: 0.6,
        roughness: 0.4,
        metalness: 0.2,
        fog: false,
      })
    );
    this.coreMesh.castShadow = false;
    this.domElement.tabIndex = 0;
    this.domElement.setAttribute("aria-label", "Surface view — click to focus for keyboard");
    this.scene.add(this.coreMesh);

    this.scene.add(this.buildingGroup);
    this.scene.add(this.enemyGroup);

    this.controls = new OrbitControls(this.camera, this.domElement);
    this.controls.target.set(0, 0, 0);
    this.controls.update();

    this.resize();
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(this.domElement);
    if (this.domElement.parentElement) {
      this.resizeObserver.observe(this.domElement.parentElement);
    }
    this.domElement.addEventListener("pointerdown", () => {
      this.domElement.focus();
    });
    window.addEventListener("resize", () => this.resize());
    requestAnimationFrame(() => this.resize());
  }

  /**
   * Third-person free flight: W/S and A/D use the camera’s basis; Q/E move along the planet’s outward normal (in / out).
   */
  integrateSpaceFlight(
    x: number,
    y: number,
    z: number,
    fwd: number,
    strafe: number,
    climb: number,
    speed: number,
    delta: number
  ): { x: number; y: number; z: number } {
    const m = this.camera.matrixWorld.elements;
    this._look.set(-m[8]!, -m[9]!, -m[10]!);
    this._right.set(m[0]!, m[1]!, m[2]!);
    this._p.set(x, y, z);
    this._tmp.subVectors(this._p, PLANET_CENTER).normalize();
    this._move.set(0, 0, 0);
    this._move.addScaledVector(this._look, fwd);
    this._move.addScaledVector(this._right, strafe);
    this._move.addScaledVector(this._tmp, climb);
    if (this._move.lengthSq() < 1e-12) {
      return { x, y, z };
    }
    this._move.setLength(speed * delta);
    this._p2.copy(this._p).add(this._move);
    return clampToFlightShell(this._p2.x, this._p2.y, this._p2.z);
  }

  /**
   * Move the command core along the surface in the direction of the camera view (NMS-style), then reproject onto the planet.
   * `fwd` / `strafe` are in [-1,1] (e.g. W/S, A/D).
   */
  moveCoreByCameraTangents(
    coreX: number,
    coreZ: number,
    fwd: number,
    strafe: number,
    speed: number,
    delta: number
  ): { x: number; z: number } {
    this._p.set(coreX, surfaceY(coreX, coreZ), coreZ);
    this._up.subVectors(this._p, PLANET_CENTER).normalize();
    this.camera.getWorldDirection(this._look);
    this._tmp.copy(this._up).multiplyScalar(this._look.dot(this._up));
    this._fwd.subVectors(this._look, this._tmp);
    if (this._fwd.lengthSq() < 1e-8) {
      this._tmp.set(0, 1, 0);
      this._fwd.crossVectors(this._tmp, this._up);
      if (this._fwd.lengthSq() < 1e-8) {
        this._fwd.set(1, 0, 0);
      }
    }
    this._fwd.normalize();
    this._right.crossVectors(this._fwd, this._up);
    this._right.normalize();
    this._move
      .copy(this._fwd)
      .multiplyScalar(fwd)
      .addScaledVector(this._right, strafe);
    if (this._move.lengthSq() < 1e-10) {
      return { x: coreX, z: coreZ };
    }
    this._move.setLength(speed * delta);
    this._p2.addVectors(this._p, this._move);
    this._p3.subVectors(this._p2, PLANET_CENTER).setLength(PLANET_RADIUS);
    this._p3.add(PLANET_CENTER);
    return { x: this._p3.x, z: this._p3.z };
  }

  /** Place an object with local +Y along planet outward normal; `along` is offset from surface point along that normal. */
  private placeOnPlanet(obj: THREE.Object3D, x: number, z: number, along: number): void {
    const y = surfaceY(x, z);
    const n = surfaceUpComponents(x, z);
    this._up.set(n.ux, n.uy, n.uz);
    obj.position.set(x + n.ux * along, y + n.uy * along, z + n.uz * along);
    obj.quaternion.setFromUnitVectors(LOCAL_Y, this._up);
  }

  /** Command core: standing on a patch, or a free “thruster” pose off the ground. */
  private placeCommandCore(flight: FlightState, coreX: number, coreZ: number): void {
    if (!flight.active) {
      this.placeOnPlanet(this.coreMesh, coreX, coreZ, 0.25);
      return;
    }
    const y = flight.y;
    const n = radialUpAt(coreX, y, coreZ);
    this._up.set(n.ux, n.uy, n.uz);
    this.coreMesh.position.set(
      coreX + n.ux * 0.25,
      y + n.uy * 0.25,
      coreZ + n.uz * 0.25
    );
    this.coreMesh.quaternion.setFromUnitVectors(LOCAL_Y, this._up);
  }

  private readDisplaySize(): { w: number; h: number } {
    const r = this.domElement.getBoundingClientRect();
    const p = this.domElement.parentElement;
    const pr = p ? p.getBoundingClientRect() : { width: 0, height: 0 };
    let w = Math.max(r.width, pr.width, this.domElement.clientWidth, p?.clientWidth ?? 0);
    let h = Math.max(r.height, pr.height, this.domElement.clientHeight, p?.clientHeight ?? 0);
    if (w < 8 || h < 8) {
      w = Math.max(w, window.innerWidth || 800);
      h = Math.max(h, Math.floor((window.innerHeight || 600) * 0.5));
    }
    w = Math.max(2, Math.floor(w));
    h = Math.max(2, Math.floor(h));
    return { w, h };
  }

  private resize(): void {
    let { w, h } = this.readDisplaySize();
    const maxSide = 2560;
    if (w > maxSide) {
      h = (h * maxSide) / w;
      w = maxSide;
    }
    if (h > maxSide) {
      w = (w * maxSide) / h;
      h = maxSide;
    }
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    const dpr0 = window.devicePixelRatio || 1;
    let pr = Math.min(2, dpr0);
    pr = Math.min(pr, 4094 / w, 4094 / h);
    pr = Math.max(0.5, pr);
    this.renderer.setPixelRatio(pr);
    this.renderer.setSize(w, h, false);
  }

  /** Sync visual parameters from sim (e.g. after body change). */
  applyUniverseStyle(surfaceSeed: number): void {
    this.sunDir.set(
      (Math.sin(surfaceSeed * 0.0001) * 0.5 + 0.5) * 2 - 1,
      0.55 + (Math.sin(surfaceSeed * 0.0002) * 0.2),
      (Math.cos(surfaceSeed * 0.00015) * 0.5 + 0.5) * 2 - 1
    );
    this.sunDir.normalize();
    this.rebuildSkydome(surfaceSeed);
    this.simple.updateSunLight(this.sunDir);
    this._skyBaseOpacity = (this.simple.sky.material as THREE.MeshBasicMaterial).opacity;
    this.dirLight.position.set(this.sunDir.x * 200, this.sunDir.y * 200, this.sunDir.z * 200);
  }

  private rebuildSkydome(skydomeSeed: number): void {
    if (skydomeSeed === this._skydomeSeed && this._catalogBuilt) return;
    const disposePoints = (p: THREE.Points): void => {
      p.geometry.dispose();
      (p.material as THREE.Material).dispose();
    };
    const disposeMesh = (m: THREE.Mesh): void => {
      m.geometry.dispose();
      (m.material as THREE.Material).dispose();
    };
    disposeMesh(this.simple.sky);
    disposePoints(this.simple.stars);
    disposePoints(this.simple.denseStars);
    disposePoints(this.simple.galactic);
    this.scene.remove(this.simple.sky, this.simple.galactic, this.simple.stars, this.simple.denseStars);
    this.simple = createSimpleSurface(this.sunDir, SKY_R, skydomeSeed);
    this._skydomeSeed = skydomeSeed;
    this._pickStarDirs = buildPickableStarDirections(skydomeSeed);
    this.buildDynamicStars();
    this.scene.add(this.simple.sky);
    this.scene.add(this.simple.galactic);
    this.scene.add(this.simple.stars);
    this.scene.add(this.simple.denseStars);
    this.simple.updateSunLight(this.sunDir);
    this.simple.applyStarDev(devSettings.sky);
    this._catalogBuilt = true;
  }

  /** Build star catalog and create THREE.Points with dynamic world-space positions. */
  private buildDynamicStars(): void {
    if (this._gameSeed === 0) return;
    this._starCatalog = buildStarCatalog(this._skydomeSeed, this._gameSeed);
    this._mainStarBuf = new Float32Array(SKY_MAIN_STARS * 3);
    this._denseStarBuf = new Float32Array(SKY_DENSE_STARS * 3);

    // Main stars: dynamic world positions, sizeAttenuation so they grow as you approach
    const mainGeo = this.simple.stars.geometry;
    mainGeo.setAttribute("position", new THREE.BufferAttribute(this._mainStarBuf, 3));
    (this.simple.stars.material as THREE.PointsMaterial).sizeAttenuation = true;
    (this.simple.stars.material as THREE.PointsMaterial).size = MAIN_STAR_SIZE;

    // Dense stars: same treatment
    const denseGeo = this.simple.denseStars.geometry;
    denseGeo.setAttribute("position", new THREE.BufferAttribute(this._denseStarBuf, 3));
    (this.simple.denseStars.material as THREE.PointsMaterial).sizeAttenuation = true;
    (this.simple.denseStars.material as THREE.PointsMaterial).size = DENSE_STAR_SIZE;
    this.simple.denseStars.visible = true;
  }

  getPickStarDirections(): Float32Array | null {
    return this._pickStarDirs;
  }

  getSkydomeSeed(): number {
    return this._skydomeSeed;
  }

  getSkyRadius(): number {
    return SKY_R;
  }

  /** Camera forward, right, and radial “up” from world center for 3D flight integration. */
  getSpaceFlightBasis(
    x: number,
    y: number,
    z: number
  ): { look: { x: number; y: number; z: number }; right: { x: number; y: number; z: number }; radial: { x: number; y: number; z: number } } {
    this.controls.update();
    const m = this.camera.matrixWorld.elements;
    this._look.set(-m[8]!, -m[9]!, -m[10]!);
    this._right.set(m[0]!, m[1]!, m[2]!);
    this._p.set(x, y, z);
    this._tmp.subVectors(this._p, PLANET_CENTER).normalize();
    return {
      look: { x: this._look.x, y: this._look.y, z: this._look.z },
      right: { x: this._right.x, y: this._right.y, z: this._right.z },
      radial: { x: this._tmp.x, y: this._tmp.y, z: this._tmp.z },
    };
  }

  /**
   * Screen position (client pixels) of a skydome star for floating labels. `z` in projected vector must be in 0..1 in front of camera.
   */
  getStarClientPosition(starIndex: number): { left: number; top: number; visible: boolean } | null {
    const catalog = this._starCatalog;
    if (!catalog || starIndex < 0 || starIndex >= SKY_TOTAL_PICKABLE) return null;
    const j = starIndex * 4;
    const wx = catalog[j]!;
    const wy = catalog[j + 1]!;
    const wz = catalog[j + 2]!;
    const R = catalog[j + 3]!;
    // Compute star's real world position from ly-space
    const sx = this._anchorLy.x + wx * R;
    const sy = this._anchorLy.y + wy * R;
    const sz = this._anchorLy.z + wz * R;
    const dx = sx - this._gposLy.x;
    const dy = sy - this._gposLy.y;
    const dz = sz - this._gposLy.z;
    const dist = Math.hypot(dx, dy, dz) || 1e-9;
    this._p3.set(
      this.camera.position.x + (dx / dist) * dist * STAR_WORLD_SCALE,
      this.camera.position.y + (dy / dist) * dist * STAR_WORLD_SCALE,
      this.camera.position.z + (dz / dist) * dist * STAR_WORLD_SCALE
    );
    this.camera.getWorldDirection(this._tmp);
    this._starWorld.subVectors(this._p3, this.camera.position);
    const inFront = this._starWorld.dot(this._tmp) > 0.01;
    this._starWorld.copy(this._p3);
    this._starWorld.project(this.camera);
    const ndcX = this._starWorld.x;
    const ndcY = this._starWorld.y;
    const inFrustum = inFront && Math.abs(ndcX) < 1.05 && Math.abs(ndcY) < 1.05;
    const rect = this.domElement.getBoundingClientRect();
    const left = rect.left + (ndcX * 0.5 + 0.5) * rect.width;
    const top = rect.top + (-ndcY * 0.5 + 0.5) * rect.height;
    return { left, top, visible: inFrustum };
  }

  /**
   * Phase 1: denser, greener fog as pollution rises; tech tier throttles the worst of it.
   */
  setIndustryPressure(pollution: number, techTier: number): void {
    const p = Math.max(0, Math.min(220, Number.isFinite(pollution) ? pollution : 0));
    const mit = 1.0 - Math.min(0.4, techTier * 0.12);
    const c = this._bgScratch.copy(this._bgBase).lerp(this._bgPolluted, p * 0.0004 * mit);
    this.scene.background = c;
    this.renderer.setClearColor(c, 1);
  }

  setCoreEmissive(ratio: number): void {
    const m = this.coreMesh.material as THREE.MeshStandardMaterial;
    m.emissiveIntensity = 0.2 + 0.8 * Math.max(0, Math.min(1, ratio));
  }

  /**
   * M4: in flight, dim airglow and brighten starfield in deep SOI; restore when landed.
   */
  private applyFlightSoiVfx(state: GameState, baseStarOpacity: number): void {
    const skyMat = this.simple.sky.material as THREE.MeshBasicMaterial;
    const starMat = this.simple.stars.material as THREE.PointsMaterial;
    const galMat = this.simple.galactic.material as THREE.PointsMaterial;
    if (!state.flight.active) {
      this.simple.sky.visible = true;
      this.simple.galactic.visible = true;
      skyMat.opacity = this._skyBaseOpacity;
      starMat.color.setHex(SKY_MAIN_STAR_COLOR);
      starMat.size = MAIN_STAR_SIZE;
      starMat.opacity = baseStarOpacity;
      galMat.opacity = this._galBaseOpacity;
      this.hemi.intensity = this._hemiBase;
      this._ambi.intensity = this._ambiBase;
      return;
    }
    const r = radialDistanceFromPlanetCenter(state.coreX, state.flight.y, state.coreZ);
    const air = innerSoiBlend01(r);
    /** r>320 *or* thin inner SOI: the HSL skydome + additive “milky” fill the camera — treat as void. */
    const voidStreak = voidStreakSoiVfx(state.flight, state.coreX, state.flight.y, state.coreZ);
    /**
     * The skydome is a HSL-tinted BackSide **full** sphere. Any visible opacity tints every pixel;
     * beyond ~DEEP it must be **fully off** (not just `opacity`), same for additive galactic.
     */
    this.simple.sky.visible = !voidStreak;
    skyMat.opacity = voidStreak ? 0 : this._skyBaseOpacity * Math.max(0.12, 0.2 + 0.58 * air);
    this.simple.galactic.visible = !voidStreak;
    if (voidStreak) {
      starMat.size = MAIN_STAR_SIZE * 1.45;
      starMat.color.setHex(SKY_MAIN_STAR_COLOR_VOID);
      const starOp = Math.min(0.55, baseStarOpacity * 0.78);
      starMat.opacity = starOp;
      galMat.opacity = 0;
    } else {
      starMat.size = MAIN_STAR_SIZE;
      starMat.color.setHex(SKY_MAIN_STAR_COLOR);
      starMat.opacity = baseStarOpacity * (0.9 + 0.1 * (1 - air));
      galMat.opacity = this._galBaseOpacity * Math.max(0.2, 0.3 + 0.7 * air);
    }
    this.hemi.intensity = this._hemiBase * (0.62 + 0.38 * air) * (voidStreak ? 0.25 : 1);
    this._ambi.intensity = this._ambiBase * (voidStreak ? 0.12 : 1);
  }

  /**
   * Updates orbit controls then returns the camera forward (reticle / star-pick direction).
   */
  getCruiseLookDirection(): { x: number; y: number; z: number } {
    this.controls.update();
    const m = this.camera.matrixWorld.elements;
    const x = -m[8]!;
    const y = -m[9]!;
    const z = -m[10]!;
    const l = Math.hypot(x, y, z) || 1e-6;
    return { x: x / l, y: y / l, z: z / l };
  }

  /**
   * Unit direction through the **pixel** (client/screen) — for star-pick with the **mouse** instead of the screen center.
   */
  getLookDirectionFromClient(clientX: number, clientY: number): { x: number; y: number; z: number } {
    this.controls.update();
    this.camera.updateProjectionMatrix();
    const r = this.domElement.getBoundingClientRect();
    const w = Math.max(1, r.width);
    const h = Math.max(1, r.height);
    this._ndcMouse.x = ((clientX - r.left) / w) * 2 - 1;
    this._ndcMouse.y = -((clientY - r.top) / h) * 2 + 1;
    this.raycaster.setFromCamera(this._ndcMouse, this.camera);
    const d = this.raycaster.ray.direction;
    const len = Math.hypot(d.x, d.y, d.z) || 1e-9;
    return { x: d.x / len, y: d.y / len, z: d.z / len };
  }

  /**
   * Deep interstellar speed: FOV and star layers respond to velocity (replaces old cruise-FTL charge VFX).
   */
  private applyInterstellarSpeedVfx(
    state: GameState,
    baseStarSize: number,
    baseDenseSize: number
  ): void {
    const deep = isDeepOrbitFlight(state.flight, state.coreX, state.flight.y, state.coreZ);
    const sp = state.flight.active
      ? Math.hypot(state.flight.vx, state.flight.vy, state.flight.vz)
      : 0;
    const starMat = this.simple.stars.material as THREE.PointsMaterial;
    const denseMat = this.simple.denseStars.material as THREE.PointsMaterial;
    if (!deep) {
      this.camera.fov = this._perspFov;
      this.camera.updateProjectionMatrix();
      starMat.size = baseStarSize;
      denseMat.size = baseDenseSize;
      denseMat.opacity = 0;
      this.simple.denseStars.visible = false;
      this.simple.stars.position.set(0, 0, 0);
      this.simple.denseStars.position.set(0, 0, 0);
      return;
    }
    /**
     * Mild speed readout: FOV + star size grow with |v| (capped).
     * Stars are at absolute world positions so we must NOT scale the object — only adjust material size.
     */
    const c = Math.min(1, sp / (INTERSTELLAR_V_MAX * 0.875));
    this.camera.fov = this._perspFov + 5 * c;
    this.camera.updateProjectionMatrix();
    starMat.size = baseStarSize * (1 + 0.12 * c);
    denseMat.size = baseDenseSize;
    denseMat.opacity = 0;
    this.simple.denseStars.visible = false;
  }

  syncState(state: GameState): void {
    // Track gpos and anchorLy for dynamic star rendering
    this._gposLy = { x: state.universe.gposX, y: state.universe.gposY, z: state.universe.gposZ };
    this._anchorLy = initialGalacticPositionLy(
      state.universe.galaxyX, state.universe.galaxyY, state.universe.systemSeed
    );
    // Build the star catalog once the game seed is known, and rebuild when the seed
    // changes (new run / save import). Note: `rebuildSkydome` runs before the first
    // `syncState`, while `_gameSeed` is still 0, so `_catalogBuilt` alone would leave
    // the catalog permanently unbuilt (static skydome stars, no pinned-star labels).
    if (state.seed !== 0 && (this._starCatalog === null || this._gameSeed !== state.seed)) {
      this._gameSeed = state.seed;
      this.buildDynamicStars();
    }
    this.setCoreEmissive(state.coreHp / CORE_MAX_HP);
    const cx = state.coreX;
    const cz = state.coreZ;
    this.placeCommandCore(state.flight, cx, cz);
    const n = state.flight.active
      ? radialUpAt(cx, state.flight.y, cz)
      : surfaceUpComponents(cx, cz);
    const sy = state.flight.active ? state.flight.y : surfaceY(cx, cz);
    this._p.set(cx + n.ux * 0.4, sy + n.uy * 0.4, cz + n.uz * 0.4);
    if (this._orbitFollowInited) {
      this._tmp.subVectors(this._p, this._lastOrbitLookAt);
      this.camera.position.add(this._tmp);
    } else {
      this._orbitFollowInited = true;
    }
    this._lastOrbitLookAt.copy(this._p);
    this.controls.target.copy(this._p);
    // Incremental entity sync: reuse one pooled mesh per entity id (shared geometry /
    // material per kind) instead of rebuilding every mesh each frame.
    this._syncStamp += 1;
    const stamp = this._syncStamp;
    for (const b of state.buildings) {
      let entry = this._buildingMeshes.get(b.id);
      if (entry && entry.type !== b.type) {
        this.buildingGroup.remove(entry.mesh);
        this._buildingMeshes.delete(b.id);
        entry = undefined;
      }
      if (!entry) {
        entry = {
          mesh: new THREE.Mesh(this._buildingGeos[b.type], this._buildingMats[b.type]),
          type: b.type,
          stamp,
        };
        this.buildingGroup.add(entry.mesh);
        this._buildingMeshes.set(b.id, entry);
      }
      entry.stamp = stamp;
      this.placeOnPlanet(entry.mesh, b.x, b.z, this._buildingAlong[b.type]);
    }
    for (const [id, entry] of this._buildingMeshes) {
      if (entry.stamp !== stamp) {
        this.buildingGroup.remove(entry.mesh);
        this._buildingMeshes.delete(id);
      }
    }
    for (const e of state.enemies) {
      const aerial = e.y != null;
      let entry = this._enemyMeshes.get(e.id);
      if (entry && entry.aerial !== aerial) {
        this.enemyGroup.remove(entry.mesh);
        this._enemyMeshes.delete(e.id);
        entry = undefined;
      }
      if (!entry) {
        entry = {
          mesh: new THREE.Mesh(
            aerial ? this._enemyGeoAerial : this._enemyGeoSurface,
            aerial ? this._enemyMatAerial : this._enemyMatSurface
          ),
          aerial,
          stamp,
        };
        this.enemyGroup.add(entry.mesh);
        this._enemyMeshes.set(e.id, entry);
      }
      entry.stamp = stamp;
      if (aerial) {
        const up = radialUpAt(e.x, e.y!, e.z);
        this._up.set(up.ux, up.uy, up.uz);
        entry.mesh.position.set(e.x + up.ux * 0.12, e.y! + up.uy * 0.12, e.z + up.uz * 0.12);
        entry.mesh.quaternion.setFromUnitVectors(LOCAL_Y, this._up);
      } else {
        this.placeOnPlanet(entry.mesh, e.x, e.z, 0.45);
      }
    }
    for (const [id, entry] of this._enemyMeshes) {
      if (entry.stamp !== stamp) {
        this.enemyGroup.remove(entry.mesh);
        this._enemyMeshes.delete(id);
      }
    }
  }

  render(_state: GameState, _delta: number, gameOver: boolean): void {
    const { width: cw, height: ch } = this.domElement.getBoundingClientRect();
    if (cw < 8 || ch < 8) this.resize();
    this.setIndustryPressure(_state.pollution, _state.techTier);
    if (voidStreakSoiVfx(_state.flight, _state.coreX, _state.flight.y, _state.coreZ)) {
      this.scene.background = this._bgVoid;
      this.renderer.setClearColor(this._bgVoid, 1);
    }
    this.simple.applyStarDev(devSettings.sky);
    // Update dynamic star positions based on current gpos
    this.updateStarPositions();
    const starBaseOp = (this.simple.stars.material as THREE.PointsMaterial).opacity;
    this.applyFlightSoiVfx(_state, starBaseOp);
    this.applyInterstellarSpeedVfx(_state, MAIN_STAR_SIZE, DENSE_STAR_SIZE);
    // Sky dome and galactic band still camera-centered (decorative backdrop)
    this.simple.sky.position.copy(this.camera.position);
    this.simple.galactic.position.copy(this.camera.position);
    // Stars are at absolute world positions - DON'T re-center on camera
    this.simple.stars.position.set(0, 0, 0);
    this.simple.denseStars.position.set(0, 0, 0);
    this.updateNavArrow(_state);
    this.controls.update();
    if (gameOver) {
      (this.coreMesh.material as THREE.MeshStandardMaterial).color.setHex(0x666666);
    }
    this.renderer.render(this.scene, this.camera);
  }

  /** Compute each star's world-space position from its ly-space position and current gpos. */
  private updateStarPositions(): void {
    const catalog = this._starCatalog;
    const mainBuf = this._mainStarBuf;
    const denseBuf = this._denseStarBuf;
    if (!catalog || !mainBuf || !denseBuf) return;
    const gx = this._gposLy.x;
    const gy = this._gposLy.y;
    const gz = this._gposLy.z;
    const ax = this._anchorLy.x;
    const ay = this._anchorLy.y;
    const az = this._anchorLy.z;
    const camX = this.camera.position.x;
    const camY = this.camera.position.y;
    const camZ = this.camera.position.z;
    const scale = STAR_WORLD_SCALE;

    // Update main stars
    for (let i = 0; i < SKY_MAIN_STARS; i++) {
      const j = i * 4;
      const wx = catalog[j]!;
      const wy = catalog[j + 1]!;
      const wz = catalog[j + 2]!;
      const R = catalog[j + 3]!;
      // Star ly position: anchorLy + wu * R
      const sx = ax + wx * R;
      const sy = ay + wy * R;
      const sz = az + wz * R;
      // Vector from gpos to star in ly-space
      const dx = sx - gx;
      const dy = sy - gy;
      const dz = sz - gz;
      const dist = Math.hypot(dx, dy, dz) || 1e-9;
      // World position: camera + direction * distance * scale
      const wi = i * 3;
      mainBuf[wi] = camX + (dx / dist) * dist * scale;
      mainBuf[wi + 1] = camY + (dy / dist) * dist * scale;
      mainBuf[wi + 2] = camZ + (dz / dist) * dist * scale;
    }
    this.simple.stars.geometry.attributes.position.needsUpdate = true;

    // Update dense stars
    for (let i = 0; i < SKY_DENSE_STARS; i++) {
      const j = (SKY_MAIN_STARS + i) * 4;
      const wx = catalog[j]!;
      const wy = catalog[j + 1]!;
      const wz = catalog[j + 2]!;
      const R = catalog[j + 3]!;
      const sx = ax + wx * R;
      const sy = ay + wy * R;
      const sz = az + wz * R;
      const dx = sx - gx;
      const dy = sy - gy;
      const dz = sz - gz;
      const dist = Math.hypot(dx, dy, dz) || 1e-9;
      const wi = i * 3;
      denseBuf[wi] = camX + (dx / dist) * dist * scale;
      denseBuf[wi + 1] = camY + (dy / dist) * dist * scale;
      denseBuf[wi + 2] = camZ + (dz / dist) * dist * scale;
    }
    this.simple.denseStars.geometry.attributes.position.needsUpdate = true;
  }

  /** M2: arrow from orbit look-at toward the chosen galactic nav sector (in-world hint). */
  private updateNavArrow(state: GameState): void {
    const u = state.universe;
    const target = u.navTarget;
    const needHint = Boolean(target) && (target!.x !== u.galaxyX || target!.y !== u.galaxyY);
    if (!needHint) {
      this._navGroup.visible = false;
      return;
    }
    this._navGroup.visible = true;
    this._navDir.copy(worldDirectionToSector(u.galaxyX, u.galaxyY, target!.x, target!.y));
    this._navOrigin.copy(this.controls.target);
    if (!this._navArrow) {
      this._navArrow = new THREE.ArrowHelper(
        this._navDir,
        this._navOrigin,
        NAV_ARROW_LEN,
        0x5ec8ff,
        NAV_ARROW_LEN * 0.07,
        NAV_ARROW_LEN * 0.045
      );
      this._navArrow.line.renderOrder = 2;
      this._navArrow.cone.renderOrder = 2;
      this._navGroup.add(this._navArrow);
    } else {
      this._navArrow.setDirection(this._navDir);
      this._navArrow.position.copy(this._navOrigin);
      this._navArrow.setLength(NAV_ARROW_LEN, NAV_ARROW_LEN * 0.07, NAV_ARROW_LEN * 0.045);
    }
  }

  getRayIntersectionXZ(clientX: number, clientY: number): { x: number; z: number } | null {
    const rect = this.domElement.getBoundingClientRect();
    const x = ((clientX - rect.left) / rect.width) * 2 - 1;
    const y = -((clientY - rect.top) / rect.height) * 2 + 1;
    const ndc = new THREE.Vector2(x, y);
    this.raycaster.setFromCamera(ndc, this.camera);
    const hits = this.raycaster.intersectObject(this.planet, false);
    if (hits.length > 0) {
      const p = hits[0]!.point;
      return { x: p.x, z: p.z };
    }
    return null;
  }

  wasClickIntent(clientX: number, clientY: number, upTime: number): boolean {
    const d = Math.hypot(clientX - this.pointerDown.x, clientY - this.pointerDown.y);
    return d < 6 && upTime - this.pointerDown.time < 400;
  }

  onPointerDown(clientX: number, clientY: number, time: number): void {
    this.pointerDown = { x: clientX, y: clientY, time };
  }
}
