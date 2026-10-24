import "./style.css";
import { EventBus } from "./core/eventBus";
import { FixedStepper } from "./core/loop";
import {
  deserializeState,
  loadFromLocalStorageOrDefault,
  saveToLocalStorage,
  serializeState,
  STORAGE_KEY,
} from "./core/save";
import { BUILDING_ORDER, buildingById, nextBuildingId, type BuildingId } from "./content/buildings";
import { createInitialState, CORE_MAX_HP, START_FERRIUM } from "./sim/initialState";
import { simStep } from "./sim/tick";
import { spaceCombatStep } from "./sim/spaceCombatTick";
import { countExtractors } from "./sim/buildingCounts";
import { tryPlaceBuilding } from "./sim/placeBuilding";
import { WorldView } from "./render/WorldView";
import { SolarView } from "./render/SolarView";
import { GalaxyMap2D } from "./render/GalaxyMap2D";
import type { GameState, ViewMode } from "./sim/types";
import { surfaceVisualSeed } from "./sim/universe/hash";
import {
  openGalaxy,
  openSolar,
  returnToSurface,
  selectBody,
  tryWarpTo,
} from "./sim/universe/travel";
import { WARP_ENERGY } from "./sim/universe/galaxyModel";
import {
  pickStarIndexAlongLook,
  pickStarIndexBestLook,
  pickableStarUnitDirection,
} from "./planet/skydomeSeed";
import {
  getStarDisplayName,
  getStarDistanceToPlayerLy,
  starMayHavePlanets,
} from "./planet/starCatalog";
import {
  addGposStepTowardPointLy,
  addWorldDisplacementMetersToGpos,
  DEEP_GPOS_TRAVEL_SCALE,
  getStarCatalogPositionLy,
  initialGalacticPositionLy,
} from "./sim/universe/galacticSpace";
import {
  placeStarPeek,
  setStarPeekPinnedHighlight,
  setStarPeekVisible,
  updateStarPeekContent,
} from "./ui/starPeek";
import { logisticsCap, TIER_LABELS } from "./content/tech";
import { devSettings } from "./render/devSettings";
import { mountDevMenu } from "./ui/devMenu";
import {
  FLIGHT_BASE_SPEED,
  INTERSTELLAR_V_MAX,
  isDeepOrbitFlight,
  liftPointFromSurface,
  projectOntoPlanetExterior,
  soiHudLabel,
  stepInterstellarVelocity,
} from "./planet/spaceFlight";

/** ~6°: skydome star under the **mouse** ray (slightly wide for comfortable hover). */
const STAR_PICK_MAX_RAD = (6 * Math.PI) / 180;

const DEFAULT_SEED = 42_069;

function getEl(id: string): HTMLElement {
  const n = document.getElementById(id);
  if (!n) throw new Error(`#${id} missing`);
  return n;
}

const params = new URLSearchParams(window.location.search);
const seedParam = params.get("seed");
const startSeed = seedParam != null && seedParam !== "" ? Number.parseInt(seedParam, 10) : DEFAULT_SEED;
const useFresh = params.get("new") === "1";

const canvas = document.getElementById("game") as HTMLCanvasElement;
const solarCanvas = document.getElementById("solar") as HTMLCanvasElement;
const galCanvas = document.getElementById("galaxy") as HTMLCanvasElement;
if (!canvas || !solarCanvas || !galCanvas) throw new Error("canvas missing");

const bus = new EventBus();
let state: GameState = useFresh ? createInitialState(startSeed) : loadFromLocalStorageOrDefault(startSeed);
const world = new WorldView(canvas);
const solar = new SolarView(solarCanvas);
const galaxy = new GalaxyMap2D(galCanvas);

let starPointerX = 0;
let starPointerY = 0;
function syncStarPointerFromCanvasCenter(): void {
  const r = canvas.getBoundingClientRect();
  starPointerX = r.left + r.width * 0.5;
  starPointerY = r.top + r.height * 0.5;
}
syncStarPointerFromCanvasCenter();
canvas.addEventListener("pointermove", (e) => {
  starPointerX = e.clientX;
  starPointerY = e.clientY;
});
window.addEventListener("resize", () => {
  if (state.universe.viewMode === "surface" && !state.flight.active) {
    syncStarPointerFromCanvasCenter();
  }
});

let lastVisual = -1;
function syncVisuals(): void {
  const ssv = surfaceVisualSeed(state);
  if (ssv !== lastVisual) {
    world.applyUniverseStyle(ssv);
    lastVisual = ssv;
  }
  solar.setSystemSeed(state.universe.systemSeed);
}
syncVisuals();
world.syncState(state);

const hud: Record<string, HTMLElement> = {
  ferrium: getEl("hud-ferrium"),
  core: getEl("hud-core"),
  tick: getEl("hud-tick"),
  wave: getEl("hud-wave"),
  nextWave: getEl("hud-next"),
  buildCost: getEl("hud-build"),
  log: getEl("hud-log"),
  speed: getEl("hud-speed"),
  pause: getEl("btn-pause"),
  mode: getEl("hud-mode"),
  hostiles: getEl("hud-hostiles"),
  tech: getEl("hud-tech"),
  logistics: getEl("hud-logistics"),
  poll: getEl("hud-poll"),
  nav: getEl("hud-nav"),
  soi: getEl("hud-soi"),
  sc: getEl("hud-sc"),
  flyMul: getEl("hud-fly-mul"),
  flightRow: getEl("flight-hud"),
  cruise: getEl("hud-cruise"),
  velocity: getEl("hud-velocity"),
  velocityVal: getEl("hud-velocity-val"),
  velocityCap: getEl("hud-velocity-cap"),
};

const starPeekEl = getEl("star-peek");

/** `pickStarIndexBestLook` changes any frame the camera twitches — re-lock the chart/HUD to one index until a clearly better one wins. */
let deepTravelStarLock: number | null = null;

function dotAimToStarDir(
  aim: { x: number; y: number; z: number },
  dirs: Float32Array,
  index: number
): number {
  const j = index * 3;
  const al = Math.hypot(aim.x, aim.y, aim.z) || 1e-9;
  return (aim.x * dirs[j]! + aim.y * dirs[j + 1]! + aim.z * dirs[j + 2]!) / al;
}

function updateVelocityOverlay(): void {
  if (state.universe.viewMode !== "surface" || !state.flight.active) {
    (hud.velocity as HTMLElement).hidden = true;
    return;
  }
  (hud.velocity as HTMLElement).hidden = false;
  const mul = Math.max(0.25, state.flight.speedMul);
  hud.velocityCap.textContent = String(Math.round(INTERSTELLAR_V_MAX * mul));
  if (isDeepOrbitFlight(state.flight, state.coreX, state.flight.y, state.coreZ)) {
    const v = Math.hypot(state.flight.vx, state.flight.vy, state.flight.vz);
    hud.velocityVal.textContent = v < 10 ? v.toFixed(1) : v.toFixed(0);
  } else {
    hud.velocityVal.textContent = "0";
  }
}

function updateStarPeekHud(): void {
  if (state.universe.viewMode !== "surface" || !state.flight.active) {
    setStarPeekVisible(starPeekEl, false);
    return;
  }
  const look = world.getLookDirectionFromClient(starPointerX, starPointerY);
  const dirs = world.getPickStarDirections();
  if (!dirs) {
    setStarPeekVisible(starPeekEl, false);
    return;
  }
  const best = pickStarIndexBestLook(look.x, look.y, look.z, dirs);
  const pin = state.universe.pinnedStarIndex;
  // Pinned, else locked interstellar target, else ad-hoc “under look” (inner orbit, or before first lock).
  const idx =
    pin != null
      ? pin
      : deepTravelStarLock != null
        ? deepTravelStarLock
        : best.index;
  const isPinned = pin != null;
  let starScreen: { left: number; top: number; visible: boolean } | null = null;
  if (isPinned) {
    starScreen = world.getStarClientPosition(idx);
  }
  const sk = world.getSkydomeSeed();
  const anchorLy = initialGalacticPositionLy(
    state.universe.galaxyX,
    state.universe.galaxyY,
    state.universe.systemSeed
  );
  const ly = getStarDistanceToPlayerLy(
    idx,
    sk,
    state.seed,
    {
      x: state.universe.gposX,
      y: state.universe.gposY,
      z: state.universe.gposZ,
    },
    anchorLy
  );
  const meta = starMayHavePlanets(idx, state.seed, sk)
    ? "Candidate system · no survey data"
    : "Deep field · uncharted";
  updateStarPeekContent(starPeekEl, getStarDisplayName(idx, state.seed, sk), ly, meta);
  if (isPinned && starScreen && starScreen.visible) {
    placeStarPeek(starPeekEl, starScreen.left, starScreen.top, "star");
    setStarPeekPinnedHighlight(starPeekEl, true);
  } else if (isPinned) {
    // Pinned star may be off-screen during flight — still show distance at cursor
    placeStarPeek(starPeekEl, starPointerX + 16, starPointerY + 14, "cursor");
    setStarPeekPinnedHighlight(starPeekEl, true);
  } else {
    placeStarPeek(starPeekEl, starPointerX + 16, starPointerY + 14, "cursor");
    setStarPeekPinnedHighlight(starPeekEl, false);
  }
  setStarPeekVisible(starPeekEl, true);
}

function applyStarPinAt(clientX: number, clientY: number): void {
  if (state.universe.viewMode !== "surface" || !state.flight.active) return;
  const look = world.getLookDirectionFromClient(clientX, clientY);
  const dirs = world.getPickStarDirections();
  const pick = dirs ? pickStarIndexAlongLook(look.x, look.y, look.z, dirs, STAR_PICK_MAX_RAD) : null;
  if (pick && state.universe.pinnedStarIndex === pick.index) {
    state.universe.pinnedStarIndex = null;
  } else if (pick) {
    state.universe.pinnedStarIndex = pick.index;
  } else {
    state.universe.pinnedStarIndex = null;
  }
  saveToLocalStorage(state);
}

const flySpeedInput = getEl("fly-speed") as HTMLInputElement;
flySpeedInput.value = String(state.flight.speedMul);
flySpeedInput.addEventListener("input", () => {
  const v = parseFloat(flySpeedInput.value);
  if (Number.isFinite(v)) {
    state.flight.speedMul = v;
    hud.flyMul.textContent = `${v.toFixed(2)}×`;
  }
});
hud.flyMul.textContent = `${state.flight.speedMul.toFixed(2)}×`;

const devPanel = mountDevMenu(getEl("app"));
getEl("btn-dev").addEventListener("click", () => devPanel.toggle());

const buildButtons: Record<BuildingId, HTMLButtonElement> = {
  extractor: getEl("btn-build-extractor") as HTMLButtonElement,
  purifier: getEl("btn-build-purifier") as HTMLButtonElement,
  relay: getEl("btn-build-relay") as HTMLButtonElement,
  sentry: getEl("btn-build-sentry") as HTMLButtonElement,
};

const BUILD_NAME: Record<BuildingId, string> = {
  extractor: "Extractor",
  purifier: "Purifier",
  relay: "Relay",
  sentry: "Sentry",
};

let buildKind: BuildingId = "extractor";

function syncBuildButtonLocks(): void {
  for (const id of BUILDING_ORDER) {
    const def = buildingById(id);
    const tierLock = def ? state.techTier < def.minTier : true;
    buildButtons[id].disabled = tierLock || (state.universe.viewMode === "surface" && state.flight.active);
  }
}

function setBuildKind(k: BuildingId): void {
  syncBuildButtonLocks();
  if (buildButtons[k].disabled) {
    for (const id of BUILDING_ORDER) {
      if (!buildButtons[id].disabled) {
        k = id;
        break;
      }
    }
  }
  buildKind = k;
  for (const id of BUILDING_ORDER) {
    buildButtons[id].classList.toggle("build-btn--active", id === buildKind);
  }
  const def = buildingById(buildKind);
  if (def) {
    hud.buildCost.textContent = `${def.cost} F — ${BUILD_NAME[buildKind]}`;
  }
  syncBuildButtonLocks();
}

for (const id of BUILDING_ORDER) {
  buildButtons[id].addEventListener("click", () => {
    setBuildKind(id);
  });
}

setBuildKind("extractor");
let timeScale = 1;
let paused = false;
let lastUiLog = 0;
function flash(msg: string): void {
  getEl("hud-log").textContent = msg;
  lastUiLog = performance.now();
}

function takeoffSpaceflight(): void {
  if (state.flight.active) return;
  const p = liftPointFromSurface(state.coreX, state.coreZ);
  state.coreX = p.x;
  state.flight.y = p.y;
  state.coreZ = p.z;
  state.flight.active = true;
  deepTravelStarLock = null;
  syncStarPointerFromCanvasCenter();
  state.flight.vx = 0;
  state.flight.vy = 0;
  state.flight.vz = 0;
  flash(
    "Thrusters on — F land · Space out · P pause. Deep: W / Space thrust (use both toward a star), S brake. B pins the star you aim at; distance matches the star under the cursor / pin."
  );
  updateHudText();
  saveToLocalStorage(state);
}

function landSpaceflight(): void {
  if (!state.flight.active) return;
  state.enemies = state.enemies.filter((e) => e.y == null);
  const p = projectOntoPlanetExterior(state.coreX, state.flight.y, state.coreZ);
  state.coreX = p.x;
  state.coreZ = p.z;
  state.flight.y = 0;
  state.flight.active = false;
  state.flight.vx = 0;
  state.flight.vy = 0;
  state.flight.vz = 0;
  state.universe.pinnedStarIndex = null;
  deepTravelStarLock = null;
  flash("Landed on surface.");
  updateHudText();
  saveToLocalStorage(state);
}

const modeLabel: Record<ViewMode, string> = {
  surface: "Surface",
  solar: "System map",
  galaxy: "Galaxy",
};

function updateHudText(): void {
  hud.ferrium.textContent = String(state.ferrium);
  hud.core.textContent = `${state.coreHp} / ${CORE_MAX_HP}`;
  hud.tick.textContent = String(state.tick);
  hud.wave.textContent = String(state.waveIndex);
  hud.nextWave.textContent = String(Math.max(0, state.nextWaveAt - state.tick));
  hud.hostiles.textContent = String(state.enemies.length);
  hud.speed.textContent = `${timeScale}×${paused ? " (paused)" : ""}`;
  const base = modeLabel[state.universe.viewMode];
  const extra = state.universe.viewMode === "surface" && state.flight.active ? " · Spaceflight" : "";
  hud.mode.textContent = `${base}${extra}`;
  hud.flyMul.textContent = `${state.flight.speedMul.toFixed(2)}×`;
  hud.flightRow.classList.toggle("flight-hud--active", state.flight.active);
  const cap = logisticsCap(state.techTier);
  const n = countExtractors(state);
  const strain =
    n <= cap
      ? `${n}/${cap} full`
      : `${n} (${cap} @100%, ${(n - cap).toFixed(0)} overflow @45%)`;
  hud.tech.textContent = `${TIER_LABELS[state.techTier] ?? "?"} · ${Math.floor(state.science)} sci`;
  hud.logistics.textContent = strain;
  hud.poll.textContent = state.pollution.toFixed(1);
  const nt = state.universe.navTarget;
  hud.nav.textContent = nt ? `(${nt.x},${nt.y})` : "—";
  hud.soi.textContent = soiHudLabel(state.flight, state.coreX, state.flight.y, state.coreZ);
  hud.sc.textContent = state.spaceCombatEnabled ? "on" : "off";
  if (state.flight.active && isDeepOrbitFlight(state.flight, state.coreX, state.flight.y, state.coreZ)) {
    const v = Math.hypot(state.flight.vx, state.flight.vy, state.flight.vz);
    hud.cruise.textContent = v >= 10 ? `Δv ${v.toFixed(0)} m/s` : `Δv ${v.toFixed(1)} m/s`;
  } else {
    hud.cruise.textContent = "—";
  }
  if (buildButtons[buildKind].disabled) setBuildKind(buildKind);
  if (performance.now() - lastUiLog > 2500) getEl("hud-log").textContent = "";
}
updateHudText();

function refreshSolarHud(): void {
  const { bodies, systemName } = solar.getBodiesWithLabels();
  getEl("solar-title").textContent = systemName;
  const host = getEl("solar-bodies");
  host.textContent = "";
  for (const b of bodies) {
    const el = document.createElement("button");
    el.type = "button";
    el.textContent = `Land: ${b.name} (${b.kind})`;
    el.addEventListener("click", () => {
      selectBody(state, b.id);
      saveToLocalStorage(state);
      showView("surface");
      flash(`Landed on ${b.name}.`);
      syncVisuals();
    });
    host.appendChild(el);
  }
}

function showView(mode: ViewMode): void {
  if (mode === "solar") openSolar(state);
  else if (mode === "galaxy") openGalaxy(state);
  else returnToSurface(state);
  const surf = mode === "surface";
  const sol = mode === "solar";
  const gal = mode === "galaxy";
  canvas.hidden = !surf;
  solarCanvas.hidden = !sol;
  galCanvas.hidden = !gal;
  getEl("solar-hud").hidden = !sol;
  getEl("galaxy-hud").hidden = !gal;
  if (sol) {
    solar.setSystemSeed(state.universe.systemSeed);
    refreshSolarHud();
  }
  if (gal) {
    galaxy.draw(state);
  }
  updateHudText();
}

bus.on((e) => {
  if (e.type === "tech") {
    flash(`Tech tier ${e.tier} — ${TIER_LABELS[e.tier] ?? "upgrade"}!`);
  } else if (e.type === "wave") {
    flash(`Wave ${e.wave} — sector breach!`);
  } else if (e.type === "spaceWave") {
    flash(`Space wave ${e.wave} — hostiles closing!`);
  } else if (e.type === "gameover") {
    flash("Command core lost — game over. Reset or import save.");
  } else if (e.type === "place_fail" && e.reason === "funds") {
    flash("Not enough ferrium.");
  } else if (e.type === "place_fail" && e.reason === "block") {
    flash("Invalid placement (too close to core or another building).");
  } else if (e.type === "place_fail" && e.reason === "tier") {
    flash("That structure needs a higher tech tier first.");
  }
});

hud.pause.addEventListener("click", () => {
  paused = !paused;
  updateHudText();
});
getEl("btn-system").addEventListener("click", () => {
  showView("solar");
});
getEl("btn-galaxy").addEventListener("click", () => {
  showView("galaxy");
  openGalaxy(state);
  galaxy.draw(state);
});
getEl("solar-surface").addEventListener("click", () => {
  returnToSurface(state);
  showView("surface");
});
getEl("solar-open-gal").addEventListener("click", () => {
  openGalaxy(state);
  showView("galaxy");
  galaxy.draw(state);
});
getEl("galaxy-close").addEventListener("click", () => {
  returnToSurface(state);
  showView("surface");
});

galaxy.onNavTarget = (x: number, y: number) => {
  state.universe.navTarget = { x, y };
  saveToLocalStorage(state);
  flash(`Nav target set — sector (${x},${y}). Sky hint on surface.`);
  updateHudText();
  galaxy.draw(state);
};

galaxy.onWarpClick = () => {
  if (!galaxy.pendingWarp) return;
  const { x, y } = galaxy.pendingWarp;
  galaxy.pendingWarp = null;
  if (state.universe.ftlEnergy < WARP_ENERGY) {
    flash("Not enough FTL energy to warp.");
    galaxy.draw(state);
    return;
  }
  if (tryWarpTo(state, x, y)) {
    saveToLocalStorage(state);
    flash(`Warped to sector (${x},${y}) — FTL -${WARP_ENERGY}.`);
    showView("solar");
  }
  syncVisuals();
  galaxy.draw(state);
};

let pointerRight = 0;
/** During spaceflight, short LMB click to pin the star under the cursor (avoids clobbering drag-orbit). */
let starPinLeftDown: { x: number; y: number; t: number } | null = null;
canvas.addEventListener("pointerdown", (ev) => {
  if (state.universe.viewMode === "surface" && state.flight.active && ev.button === 0) {
    starPinLeftDown = { x: ev.clientX, y: ev.clientY, t: ev.timeStamp };
  }
  if (state.universe.viewMode !== "surface" || state.flight.active) return;
  if (ev.button === 2) {
    world.onPointerDown(ev.clientX, ev.clientY, ev.timeStamp);
    pointerRight = 1;
  }
});
canvas.addEventListener("pointerup", (ev) => {
  if (state.universe.viewMode === "surface" && state.flight.active && ev.button === 0 && starPinLeftDown) {
    const a = starPinLeftDown;
    starPinLeftDown = null;
    const d = Math.hypot(ev.clientX - a.x, ev.clientY - a.y);
    if (d < 6 && ev.timeStamp - a.t < 500) {
      applyStarPinAt(ev.clientX, ev.clientY);
    }
    return;
  }
  if (state.universe.viewMode !== "surface" || state.flight.active) return;
  if (ev.button !== 2 || !pointerRight) return;
  pointerRight = 0;
  if (state.gameOver) return;
  if (!world.wasClickIntent(ev.clientX, ev.clientY, ev.timeStamp)) return;
  const hit = world.getRayIntersectionXZ(ev.clientX, ev.clientY);
  if (hit) {
    if (tryPlaceBuilding(state, hit.x, hit.z, buildKind, bus)) {
      saveToLocalStorage(state);
      updateHudText();
    }
  }
  ev.preventDefault();
});
canvas.addEventListener("contextmenu", (e) => e.preventDefault());

const stepper = new FixedStepper(
  () => {
    if (paused || state.universe.viewMode !== "surface") return;
    if (isDeepOrbitFlight(state.flight, state.coreX, state.flight.y, state.coreZ)) {
      if (state.spaceCombatEnabled) {
        spaceCombatStep(state, bus);
        if (state.tick % 30 === 0) saveToLocalStorage(state);
        updateHudText();
      }
      return;
    }
    simStep(state, bus);
    if (state.tick % 30 === 0) saveToLocalStorage(state);
    updateHudText();
  },
  { simHz: 30 }
);

window.addEventListener("keydown", (e) => {
  const uiTarget = (e.target as HTMLElement | null)?.tagName;
  if (e.code === "KeyP" && uiTarget !== "INPUT" && uiTarget !== "TEXTAREA" && uiTarget !== "SELECT") {
    e.preventDefault();
    paused = !paused;
    updateHudText();
  }
  if (e.key === "1") timeScale = 1;
  if (e.key === "2") timeScale = 2;
  if (e.key === "3") timeScale = 4;
  if (e.key === "1" || e.key === "2" || e.key === "3") updateHudText();
  if (e.key === "4") {
    setBuildKind("extractor");
    updateHudText();
  }
  if (e.key === "5") {
    setBuildKind("purifier");
    updateHudText();
  }
  if (e.key === "6") {
    setBuildKind("relay");
    updateHudText();
  }
  if (e.key === "7") {
    setBuildKind("sentry");
    updateHudText();
  }
  if (e.code === "BracketLeft") {
    e.preventDefault();
    setBuildKind(nextBuildingId(buildKind, -1));
    updateHudText();
  }
  if (e.code === "BracketRight") {
    e.preventDefault();
    setBuildKind(nextBuildingId(buildKind, 1));
    updateHudText();
  }
  if (e.key === "g" || e.key === "G") {
    openGalaxy(state);
    showView("galaxy");
    galaxy.draw(state);
  }
  if (e.key === "m" || e.key === "M") {
    showView("solar");
  }
  if (e.key === "Escape") {
    returnToSurface(state);
    showView("surface");
  }
  if (e.code === "KeyF") {
    const t = (e.target as HTMLElement | null)?.tagName;
    if (t === "INPUT" || t === "TEXTAREA" || t === "SELECT") return;
    if (state.universe.viewMode !== "surface" || state.gameOver) return;
    e.preventDefault();
    if (state.flight.active) {
      landSpaceflight();
    } else {
      takeoffSpaceflight();
    }
  }
  if (e.code === "Quote" && uiTarget !== "INPUT" && uiTarget !== "TEXTAREA" && uiTarget !== "SELECT") {
    e.preventDefault();
    state.spaceCombatEnabled = !state.spaceCombatEnabled;
    flash(
      state.spaceCombatEnabled
        ? "Space combat: on (M5 — deep-orbit waves while SC on)"
        : "Space combat: off"
    );
    updateHudText();
    saveToLocalStorage(state);
  }
});

getEl("btn-save").addEventListener("click", () => {
  saveToLocalStorage(state);
  flash("Saved to browser storage.");
});
getEl("btn-export").addEventListener("click", () => {
  const a = document.createElement("a");
  a.href = `data:application/json;charset=utf-8,${encodeURIComponent(serializeState(state))}`;
  a.download = "cosmic-forges-save.json";
  a.click();
  flash("Exported JSON.");
});
getEl("import-file").addEventListener("change", (ev) => {
  const f = (ev.target as HTMLInputElement).files?.[0];
  if (!f) return;
  const r = new FileReader();
  r.onload = () => {
    try {
      const t = r.result as string;
      state = deserializeState(t);
      flySpeedInput.value = String(state.flight.speedMul);
      world.syncState(state);
      lastVisual = -1;
      syncVisuals();
      saveToLocalStorage(state);
      updateHudText();
      showView("surface");
      flash("Save imported.");
    } catch (err) {
      flash("Import failed — invalid file.");
      console.error(err);
    }
  };
  r.readAsText(f);
  (ev.target as HTMLInputElement).value = "";
});
getEl("btn-new").addEventListener("click", () => {
  if (!confirm("Start a new run? Current progress will be lost unless you exported.")) return;
  state = createInitialState((Math.random() * 0xffff_ffff) >>> 0);
  flySpeedInput.value = String(state.flight.speedMul);
  world.syncState(state);
  lastVisual = -1;
  syncVisuals();
  saveToLocalStorage(state);
  updateHudText();
  showView("surface");
  flash("New run — good luck, Engineer.");
});

/** World movement keys. `Space`: ascend (radial out) in spaceflight only. */
const coreKeys: Record<string, boolean> = {
  KeyW: false,
  KeyA: false,
  KeyS: false,
  KeyD: false,
  KeyQ: false,
  KeyE: false,
  Space: false,
};
function resetCoreKeys(): void {
  for (const k of Object.keys(coreKeys)) {
    coreKeys[k] = false;
  }
}
window.addEventListener("blur", () => {
  resetCoreKeys();
});
window.addEventListener("keydown", (e) => {
  if (!(e.code in coreKeys)) return;
  const t = (e.target as HTMLElement | null)?.tagName;
  if (t === "INPUT" || t === "TEXTAREA" || t === "SELECT") return;
  (coreKeys as Record<string, boolean>)[e.code] = true;
  e.preventDefault();
});
window.addEventListener("keyup", (e) => {
  if (!(e.code in coreKeys)) return;
  const t = (e.target as HTMLElement | null)?.tagName;
  if (t === "INPUT" || t === "TEXTAREA" || t === "SELECT") return;
  (coreKeys as Record<string, boolean>)[e.code] = false;
});

window.addEventListener("keydown", (e) => {
  if (e.code !== "KeyB" || e.repeat) return;
  const t = (e.target as HTMLElement | null)?.tagName;
  if (t === "INPUT" || t === "TEXTAREA" || t === "SELECT") return;
  if (state.universe.viewMode !== "surface" || !state.flight.active) return;
  e.preventDefault();
  applyStarPinAt(starPointerX, starPointerY);
});

let lastT = performance.now();
/** Deep SOI: seconds held for W and Space/E (climb) — ramps thrust in {@link stepInterstellarVelocity}. */
let interstellarHoldFwdS = 0;
let interstellarHoldClimbS = 0;
function frame(now: number): void {
  const delta = Math.min(0.1, (now - lastT) / 1000);
  lastT = now;
  if (state.universe.viewMode === "surface" && !paused && !state.gameOver) {
    if (state.flight.active) {
      if (!isDeepOrbitFlight(state.flight, state.coreX, state.flight.y, state.coreZ)) {
        interstellarHoldFwdS = 0;
        interstellarHoldClimbS = 0;
      }
      const ox = state.coreX;
      const oy = state.flight.y;
      const oz = state.coreZ;
      const wasDeep = isDeepOrbitFlight(state.flight, state.coreX, state.flight.y, state.coreZ);
      let climb = 0;
      if (coreKeys.Space || coreKeys.KeyE) climb += 1;
      if (coreKeys.KeyQ) climb -= 1;
      let strafe = 0;
      if (coreKeys.KeyD) strafe += 1;
      if (coreKeys.KeyA) strafe -= 1;
      if (wasDeep) {
        const basis = world.getSpaceFlightBasis(state.coreX, state.flight.y, state.coreZ);
        const fwd = coreKeys.KeyW ? 1 : 0;
        const brake = coreKeys.KeyS ? 1 : 0;
        if (fwd !== 0) {
          interstellarHoldFwdS = Math.min(12, interstellarHoldFwdS + delta);
        } else {
          interstellarHoldFwdS = 0;
        }
        if (climb !== 0) {
          interstellarHoldClimbS = Math.min(12, interstellarHoldClimbS + delta);
        } else {
          interstellarHoldClimbS = 0;
        }
        const fwdMul = 1 + 0.22 * Math.pow(interstellarHoldFwdS, 1.2);
        const climbMul = 1 + 0.26 * Math.pow(interstellarHoldClimbS, 1.2);
        const r = stepInterstellarVelocity(
          state.coreX,
          state.flight.y,
          state.coreZ,
          state.flight.vx,
          state.flight.vy,
          state.flight.vz,
          basis.look,
          basis.right,
          basis.radial,
          fwd,
          strafe,
          climb,
          brake,
          state.flight.speedMul,
          timeScale,
          delta,
          { fwdMul, climbMul }
        );
        state.coreX = r.x;
        state.flight.y = r.y;
        state.coreZ = r.z;
        state.flight.vx = r.vx;
        state.flight.vy = r.vy;
        state.flight.vz = r.vz;
      } else {
        state.flight.vx = 0;
        state.flight.vy = 0;
        state.flight.vz = 0;
        let fwd = 0;
        if (coreKeys.KeyW) fwd += 1;
        if (coreKeys.KeyS) fwd -= 1;
        if (fwd !== 0 || strafe !== 0 || climb !== 0) {
          const sp = FLIGHT_BASE_SPEED * timeScale * state.flight.speedMul;
          const r = world.integrateSpaceFlight(
            state.coreX,
            state.flight.y,
            state.coreZ,
            fwd,
            strafe,
            climb,
            sp,
            delta
          );
          state.coreX = r.x;
          state.flight.y = r.y;
          state.coreZ = r.z;
        }
      }
      if (wasDeep) {
        const dx = state.coreX - ox;
        const dy = state.flight.y - oy;
        const dz = state.coreZ - oz;
        const g0 = { x: state.universe.gposX, y: state.universe.gposY, z: state.universe.gposZ };
        const anchorLy = initialGalacticPositionLy(
          state.universe.galaxyX,
          state.universe.galaxyY,
          state.universe.systemSeed
        );
        const sk0 = world.getSkydomeSeed();
        let targetIdx: number | null = state.universe.pinnedStarIndex;
        const isPinned = targetIdx != null;
        if (!isPinned) {
          // Same look ray as star-peek / distance (mouse). Only used for auto-lock when unpinned.
          const aim = world.getLookDirectionFromClient(starPointerX, starPointerY);
          const dirs = world.getPickStarDirections();
          if (dirs) {
            const best = pickStarIndexBestLook(aim.x, aim.y, aim.z, dirs);
            if (deepTravelStarLock == null) {
              deepTravelStarLock = best.index;
            } else if (best.index !== deepTravelStarLock) {
              const curDot = dotAimToStarDir(aim, dirs, deepTravelStarLock);
              if (best.dot > curDot + 0.12) {
                deepTravelStarLock = best.index;
              }
            }
            targetIdx = deepTravelStarLock;
          }
        }
        let g: { x: number; y: number; z: number };
        if (isPinned) {
          // Pinned: always close toward that exact star — never free-drift.
          const wu = pickableStarUnitDirection(sk0, state.universe.pinnedStarIndex!);
          const pointLy = getStarCatalogPositionLy(
            state.universe.pinnedStarIndex!,
            sk0,
            state.seed,
            anchorLy,
            wu
          );
          g = addGposStepTowardPointLy(
            g0, dx, dy, dz,
            pointLy, wu, wu /* reticle = star direction when pinned */,
            DEEP_GPOS_TRAVEL_SCALE
          );
        } else if (targetIdx != null) {
          const wu = pickableStarUnitDirection(sk0, targetIdx);
          const aim = world.getLookDirectionFromClient(starPointerX, starPointerY);
          g = addGposStepTowardPointLy(
            g0, dx, dy, dz,
            getStarCatalogPositionLy(targetIdx, sk0, state.seed, anchorLy, wu),
            wu, aim,
            DEEP_GPOS_TRAVEL_SCALE
          );
        } else {
          g = addWorldDisplacementMetersToGpos(g0, dx, dy, dz, DEEP_GPOS_TRAVEL_SCALE);
        }
        state.universe.gposX = g.x;
        state.universe.gposY = g.y;
        state.universe.gposZ = g.z;
      }
    } else {
      interstellarHoldFwdS = 0;
      interstellarHoldClimbS = 0;
      let fwd = 0;
      if (coreKeys.KeyW) fwd += 1;
      if (coreKeys.KeyS) fwd -= 1;
      let strafe = 0;
      if (coreKeys.KeyD) strafe += 1;
      if (coreKeys.KeyA) strafe -= 1;
      if (fwd !== 0 || strafe !== 0) {
        const sp = 11 * timeScale;
        const p = world.moveCoreByCameraTangents(state.coreX, state.coreZ, fwd, strafe, sp, delta);
        state.coreX = p.x;
        state.coreZ = p.z;
      }
    }
  }
  stepper.addFrame(delta, timeScale);
  if (state.universe.viewMode === "surface") {
    world.syncState(state);
    updateStarPeekHud();
    world.render(state, delta, state.gameOver);
  } else if (state.universe.viewMode === "solar") {
    solar.render(devSettings.solar);
  }
  updateVelocityOverlay();
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

if (import.meta.env.DEV) {
  console.info(`[Cosmic Forges] start ferrium: ${START_FERRIUM} · storage: ${STORAGE_KEY}`);
}

showView("surface");
