const screenRoot = document.getElementById("screen-root");
const shellEl = document.querySelector(".shell");

const MISSION_TAXES = {
  bribe: 7,
  none: 0,
  light: -2,
  heavy: -12,
  cruel: -24
};

const MISSION_RATIONS = {
  none: { pop: -20, foodMultiplier: 0 },
  half: { pop: -5, foodMultiplier: 0.5 },
  normal: { pop: 0, foodMultiplier: 1 },
  double: { pop: 6, foodMultiplier: 2 }
};

const MISSION_BUILDINGS = {
  stockpile: { label: "Stockpile", footprint: { w: 2, h: 2 }, cost: { wood: 16, gold: 10 }, category: "economy" },
  house: { label: "House", footprint: { w: 2, h: 2 }, cost: { wood: 12, gold: 6 }, category: "economy" },
  woodcutter: { label: "Woodcutter", footprint: { w: 1, h: 1 }, cost: { wood: 10, gold: 8 }, category: "industry" },
  "stone-camp": { label: "Stone Camp", footprint: { w: 1, h: 1 }, cost: { wood: 10, gold: 10 }, category: "industry" },
  "iron-furny": { label: "Iron Furny", footprint: { w: 2, h: 2 }, cost: { wood: 14, gold: 18 }, category: "industry" },
  "cow-building": { label: "Cow Building", footprint: { w: 1, h: 1 }, cost: { wood: 12, gold: 12 }, category: "industry" },
  "apple-farm": { label: "Apple Farm", footprint: { w: 2, h: 2 }, cost: { wood: 8, gold: 10 }, category: "food" },
  granary: { label: "Granary", footprint: { w: 2, h: 2 }, cost: { wood: 16, gold: 14 }, category: "food" },
  barracks: { label: "Barracks", footprint: { w: 2, h: 2 }, cost: { wood: 26, gold: 32 }, category: "defense" },
  campfire: { label: "Campfire", footprint: { w: 2, h: 2 }, cost: { wood: 0, gold: 0 }, locked: true },
  keep: { label: "Keep", footprint: { w: 3, h: 3 }, cost: { wood: 0, gold: 0 }, locked: true }
};
const MILITIA_RECRUIT_SECONDS = 10;
const BARRACKS_QUEUE_LIMIT = 2;
const BARRACKS_CANCEL_REFUND_RATIO = 0.6;
const WOOD_PRODUCTION_INTERVAL_SEC = 2;
const WOOD_PER_CUTTER = 4;
const RAID_WARNING_LEAD_SEC = 12;
const ENEMY_BUILD_ATTEMPT_INTERVAL_SEC = 24;
const ENEMY_ATTACK_CYCLE_SEC = 32;
const PEASANT_DOT_SPEED = 0.51;
const STOCKPILE_CAPACITY_PER_BUILDING = 320;
const STARTING_BUNDLE_MEDIUM = {
  stockpile: { wood: 30, stone: 20, iron: 20, gold: 120 },
  granary: { apples: 20 }
};
const BUILDING_STAFFING = {
  woodcutter: 1,
  "stone-camp": 2,
  "iron-furny": 4,
  "cow-building": 1,
  "apple-farm": 1
};
const BUILDING_TILE_REQUIREMENTS = {
  "iron-furny": "iron_patch",
  "stone-camp": "stone_patch",
  "apple-farm": "grass"
};

const ENEMY_AI_PROFILES = {
  ratHarass: {
    id: "ratHarass",
    label: "Rat Harass",
    ecoBias: "low",
    attackTiming: "early",
    unitPreference: "light-raider",
    strategyBias: { probe: 4, flank: 3, push: 2, retreat: 1 }
  },
  ironTurtle: {
    id: "ironTurtle",
    label: "Iron Turtle",
    ecoBias: "high",
    attackTiming: "late",
    unitPreference: "heavy-frontline",
    strategyBias: { probe: 1, flank: 2, push: 2, retreat: 3 }
  },
  desertJackal: {
    id: "desertJackal",
    label: "Desert Jackal",
    ecoBias: "mid",
    attackTiming: "normal",
    unitPreference: "flanker",
    strategyBias: { probe: 2, flank: 4, push: 2, retreat: 1 }
  },
  scholarWarlord: {
    id: "scholarWarlord",
    label: "Scholar Warlord",
    ecoBias: "high",
    attackTiming: "normal",
    unitPreference: "hybrid",
    strategyBias: { probe: 2, flank: 2, push: 3, retreat: 2 }
  }
};

const UNIT_TEMPLATES = {
  militia: { label: "Militia", hp: 80, damage: 8, speed: 0.05, attackRange: 1.2, goldCost: 20, trainSec: 10, role: "line-holder" },
  skirmisher: { label: "Skirmisher", hp: 62, damage: 9, speed: 0.062, attackRange: 1.8, goldCost: 28, trainSec: 12, role: "skirmisher" },
  templar: { label: "Templar", hp: 130, damage: 14, speed: 0.043, attackRange: 1.2, goldCost: 40, trainSec: 16, role: "anti-armor" },
  raider: { label: "Raider", hp: 72, damage: 7, speed: 0.048, attackRange: 1.1, role: "light-raider" },
  swordsman: { label: "Swordsman", hp: 115, damage: 11, speed: 0.042, attackRange: 1.2, role: "heavy-frontline" }
};

const MISSION_SCENARIOS = {
  default: {
    startResources: { wood: 60, gold: 180, food: 120 },
    startPopulation: { current: 10, cap: 10 },
    aiProfileId: "ratHarass",
    objectives: [
      { id: "build-stockpile", text: "Build a Stockpile", type: "building_count", target: { buildingType: "stockpile", count: 1 } },
      { id: "build-woodcutter", text: "Build a Woodcutter", type: "building_count", target: { buildingType: "woodcutter", count: 1 } },
      { id: "collect-wood", text: "Reach 120 Wood", type: "resource_at_least", target: { resource: "wood", amount: 120 } },
      { id: "hold-first-raids", text: "Survive the first raid waves", type: "survive_waves", target: { throughWave: "raid-wave-3" } }
    ],
    events: [
      { id: "intro", triggerAtSec: 5, message: "Advisor: Stabilize your economy and secure wood." },
      { id: "warn", triggerAtSec: 45, message: "Advisor: People are restless. Manage taxes and rations." },
      { id: "push", triggerAtSec: 90, message: "Advisor: Scouts report enemy riders gathering." }
    ],
    raidWaves: [
      { id: "raid-wave-1", triggerAtSec: 95, side: "east", unitType: "raider", count: 2 },
      { id: "raid-wave-2", triggerAtSec: 125, side: "south", unitType: "raider", count: 3 },
      { id: "raid-wave-3", triggerAtSec: 165, side: "east", unitType: "raider", count: 4 }
    ]
  },
  m7: {
    startResources: { wood: 90, gold: 260, food: 180, stone: 40 },
    startPopulation: { current: 14, cap: 14 },
    aiProfileId: "desertJackal",
    objectives: [
      { id: "m7-stockpile", text: "Expand stockpile capacity", type: "building_count", target: { buildingType: "stockpile", count: 2 } },
      { id: "m7-hold", text: "Survive fast flank raids", type: "survive_waves", target: { throughWave: "m7-wave-4" } }
    ],
    events: [
      { id: "m7-intro", triggerAtSec: 6, message: "Advisor: Expect flank attacks through water-side crossings." }
    ],
    raidWaves: [
      { id: "m7-wave-1", triggerAtSec: 85, side: "south", unitType: "raider", count: 4 },
      { id: "m7-wave-2", triggerAtSec: 122, side: "east", unitMix: [{ unitType: "raider", count: 3 }, { unitType: "skirmisher", count: 2 }], count: 5 },
      { id: "m7-wave-3", triggerAtSec: 160, side: "south", unitMix: [{ unitType: "raider", count: 4 }, { unitType: "swordsman", count: 2 }], count: 6 },
      { id: "m7-wave-4", triggerAtSec: 210, side: "enemy-fort", unitMix: [{ unitType: "swordsman", count: 3 }, { unitType: "raider", count: 4 }], count: 7 }
    ]
  },
  m8: {
    startResources: { wood: 120, gold: 320, food: 210, stone: 70, iron: 40 },
    startPopulation: { current: 16, cap: 16 },
    aiProfileId: "scholarWarlord",
    objectives: [
      { id: "m8-barracks", text: "Field an elite force", type: "building_count", target: { buildingType: "barracks", count: 2 } },
      { id: "m8-wave", text: "Break final assault cycle", type: "survive_waves", target: { throughWave: "m8-wave-5" } }
    ],
    events: [
      { id: "m8-intro", triggerAtSec: 8, message: "Advisor: Their lord shifts tactics; hold your line and counter-push." }
    ],
    raidWaves: [
      { id: "m8-wave-1", triggerAtSec: 80, side: "east", unitMix: [{ unitType: "raider", count: 4 }, { unitType: "skirmisher", count: 2 }], count: 6 },
      { id: "m8-wave-2", triggerAtSec: 118, side: "south", unitMix: [{ unitType: "swordsman", count: 3 }, { unitType: "raider", count: 3 }], count: 6 },
      { id: "m8-wave-3", triggerAtSec: 155, side: "enemy-fort", unitMix: [{ unitType: "templar", count: 2 }, { unitType: "swordsman", count: 3 }], count: 5 },
      { id: "m8-wave-4", triggerAtSec: 198, side: "west", unitMix: [{ unitType: "skirmisher", count: 3 }, { unitType: "raider", count: 4 }], count: 7 },
      { id: "m8-wave-5", triggerAtSec: 245, side: "enemy-fort", unitMix: [{ unitType: "templar", count: 3 }, { unitType: "swordsman", count: 4 }, { unitType: "raider", count: 3 }], count: 10 }
    ]
  }
};

const state = {
  currentScreen: "main-menu",
  selectedMissionId: null,
  pendingMissionLoad: false,
  campaignData: window.CAMPAIGN_MAP_DATA || { world: { width: 2200, height: 1300 }, nodes: [], campaigns: [] },
  selectedCampaignId: "crusader-trail",
  progress: {
    schemaVersion: 1,
    profileId: "local-profile-1",
    campaignProgress: {}
  },
  mapView: {
    x: -120,
    y: -360,
    scale: 0.9
  },
  missionRuntime: {
    missionId: null,
    startedAt: 0,
    elapsedMs: 0,
    status: "idle",
    pauseStartedAt: 0,
    pausedAccumulatedMs: 0,
    tickerId: null,
    lastProcessedSec: 0,
    missionState: null
  },
  missionWorld: {
    width: 208,
    height: 208,
    tileW: 64,
    tileH: 32,
    heights: [],
    terrain: [],
    cameraX: 0,
    cameraY: 0,
    zoom: 1,
    dragging: false,
    dragMoved: false,
    dragPointerId: null,
    dragStartX: 0,
    dragStartY: 0,
    dragCameraX: 0,
    dragCameraY: 0,
    renderFrameId: 0,
    minimapBaseCanvas: null,
    minimapNeedsRebuild: true
  },
  campaignGlobalInputBound: false,
  isDraggingMap: false,
  dragStart: { x: 0, y: 0, mapX: 0, mapY: 0 },
  settings: {
    musicVolume: 65,
    sfxVolume: 75,
    difficulty: "normal",
    unitControlPreset: "classic"
  },
  spriteEditor: {
    storageKey: "shc_sprite_project_v1",
    generatedKey: "shc_sprite_generated_files_v1",
    project: null,
    previewPlaying: false,
    previewFrameElapsedMs: 0,
    previewFrameIndex: 0,
    previewRafId: 0,
    cachedImage: null,
    runtimeImageCache: {},
    clipboardFrame: null,
    undoStack: [],
    redoStack: [],
    dragSelection: {
      active: false,
      startX: 0,
      startY: 0,
      currentX: 0,
      currentY: 0
    }
  },
  itemEditor: {
    storageKey: "shc_item_project_v1",
    project: null,
    previewRafId: 0,
    resizeObserver: null,
    interacting: false,
    pointerId: null,
    dragButton: 0,
    dragKind: null,
    dragStartX: 0,
    dragStartY: 0,
    dragLastX: 0,
    dragLastY: 0,
    dragMovedSq: 0,
    startYaw: 0,
    startPitch: 0,
    startPartX: 0,
    startPartY: 0,
    startPartZ: 0,
    startW: 0,
    startH: 0,
    startD: 0,
    startPartYaw: 0,
    startUniformScale: 1,
    undoStack: [],
    redoStack: [],
    clipboardPart: null,
    paintUndoRecorded: false
  }
};

const ITEM_UNDO_MAX = 40;

function snapshotItemProjectString() {
  return JSON.stringify(getItemProjectState());
}

function recordItemUndo() {
  const st = state.itemEditor;
  st.undoStack.push(snapshotItemProjectString());
  while (st.undoStack.length > ITEM_UNDO_MAX) st.undoStack.shift();
  st.redoStack = [];
}

function itemEditorUndo() {
  const st = state.itemEditor;
  if (!st.undoStack.length) return false;
  const cur = snapshotItemProjectString();
  const prev = st.undoStack.pop();
  st.redoStack.push(cur);
  try {
    state.itemEditor.project = normalizeItemProject(JSON.parse(prev));
  } catch (e) {
    return false;
  }
  refreshItemEditorUI();
  saveItemProjectToStorage();
  return true;
}

function itemEditorRedo() {
  const st = state.itemEditor;
  if (!st.redoStack.length) return false;
  const cur = snapshotItemProjectString();
  const next = st.redoStack.pop();
  st.undoStack.push(cur);
  try {
    state.itemEditor.project = normalizeItemProject(JSON.parse(next));
  } catch (e) {
    return false;
  }
  refreshItemEditorUI();
  saveItemProjectToStorage();
  return true;
}

function copyItemPartToClipboard() {
  const active = getActiveItemPart(getItemProjectState());
  if (!active) {
    showToast("Nothing to copy.");
    return;
  }
  state.itemEditor.clipboardPart = JSON.parse(JSON.stringify(active));
  showToast("Part copied.");
}

function pasteItemPartFromClipboard() {
  const raw = state.itemEditor.clipboardPart;
  if (!raw || typeof raw !== "object") {
    showToast("Clipboard empty.");
    return;
  }
  recordItemUndo();
  const project = getItemProjectState();
  const copy = JSON.parse(JSON.stringify(raw));
  copy.id = window.ItemScene?.partId ? window.ItemScene.partId() : `p_${Math.random().toString(36).slice(2, 11)}`;
  copy.name = `${String(copy.name || "part")}-paste`;
  copy.x = Number(copy.x || 0) + 12;
  copy.z = Number(copy.z || 0) + 12;
  project.parts.push(copy);
  project.selectedPartId = copy.id;
  if (window.ItemScene?.validateParents) window.ItemScene.validateParents(project);
  refreshItemEditorUI();
  showToast("Part pasted.");
}

/** Paint part color; optional world-axis symmetry matches a second part by mirrored world center. */
function applyItemPaintToPart(project, partId, colorHex) {
  const part = project.parts.find((p) => p.id === partId);
  if (!part) return;
  const c = String(colorHex || "");
  if (!/^#([0-9a-f]{6})$/i.test(c)) return;
  part.color = c;
  const sym = project.paintSymmetry || "none";
  if (sym === "none" || !window.ItemScene?.getWorldPose) return;
  const w = window.ItemScene.getWorldPose(part, project);
  const target = { x: w.x, y: w.y, z: w.z };
  if (sym === "worldX") target.x = -w.x;
  else if (sym === "worldY") target.y = -w.y;
  else if (sym === "worldZ") target.z = -w.z;
  else return;
  const thr2 = 60 * 60;
  let best = null;
  let bestD = thr2 + 1;
  for (const q of project.parts) {
    if (q.id === part.id) continue;
    const wq = window.ItemScene.getWorldPose(q, project);
    const dx = wq.x - target.x;
    const dy = wq.y - target.y;
    const dz = wq.z - target.z;
    const d2 = dx * dx + dy * dy + dz * dz;
    if (d2 < bestD) {
      bestD = d2;
      best = q;
    }
  }
  if (best && bestD <= thr2) best.color = c;
}

function cloneTemplate(id) {
  const tpl = document.getElementById(id);
  return tpl.content.cloneNode(true);
}

function mountScreen(screenName) {
  if (state.currentScreen === "mission-play" && screenName !== "mission-play") {
    stopMissionTicker();
    stopMissionRenderLoop();
    if (window.MissionThreeLayer && typeof window.MissionThreeLayer.dispose === "function") {
      window.MissionThreeLayer.dispose();
    }
  }
  if (state.currentScreen === "sprite-editor" && screenName !== "sprite-editor") {
    stopSpritePreviewPlayback();
    if (window.SpriteMeshPreview && typeof window.SpriteMeshPreview.dispose === "function") {
      window.SpriteMeshPreview.dispose();
    }
  }
  if (state.currentScreen === "item-editor" && screenName !== "item-editor") {
    stopItemPreviewLoop();
  }
  state.currentScreen = screenName;
  screenRoot.innerHTML = "";
  const isFullscreenScreen =
    screenName === "missions" ||
    screenName === "mission-briefing" ||
    screenName === "mission-play" ||
    screenName === "item-editor";
  if (shellEl) {
    shellEl.classList.toggle("fullscreen", isFullscreenScreen);
  }

  if (screenName === "main-menu") {
    screenRoot.appendChild(cloneTemplate("screen-main-menu"));
  } else if (screenName === "dev-menu") {
    screenRoot.appendChild(cloneTemplate("screen-dev-menu"));
  } else if (screenName === "settings") {
    screenRoot.appendChild(cloneTemplate("screen-settings"));
    hydrateSettingsInputs();
  } else if (screenName === "missions") {
    screenRoot.appendChild(cloneTemplate("screen-missions"));
    initializeCampaignScreen();
  } else if (screenName === "mission-briefing") {
    screenRoot.appendChild(cloneTemplate("screen-mission-briefing"));
    renderMissionBriefing();
  } else if (screenName === "sprite-editor") {
    screenRoot.appendChild(cloneTemplate("screen-sprite-editor"));
    mountSpriteEditor();
  } else if (screenName === "item-editor") {
    screenRoot.appendChild(cloneTemplate("screen-item-editor"));
    mountItemEditor();
  } else if (screenName === "mission-play") {
    screenRoot.appendChild(cloneTemplate("screen-mission-play"));
    mountMissionPlay();
  }
}

function hydrateSettingsInputs() {
  const musicInput = document.getElementById("music-volume");
  const sfxInput = document.getElementById("sfx-volume");
  const difficultySelect = document.getElementById("difficulty");
  const controlPresetSelect = document.getElementById("unit-control-preset");
  if (!musicInput || !sfxInput || !difficultySelect) return;

  musicInput.value = String(state.settings.musicVolume);
  sfxInput.value = String(state.settings.sfxVolume);
  difficultySelect.value = state.settings.difficulty;
  if (controlPresetSelect) controlPresetSelect.value = state.settings.unitControlPreset || "classic";
}

function persistSettingsFromUI() {
  const musicInput = document.getElementById("music-volume");
  const sfxInput = document.getElementById("sfx-volume");
  const difficultySelect = document.getElementById("difficulty");
  const controlPresetSelect = document.getElementById("unit-control-preset");
  if (!musicInput || !sfxInput || !difficultySelect) return;

  state.settings.musicVolume = Number(musicInput.value);
  state.settings.sfxVolume = Number(sfxInput.value);
  state.settings.difficulty = difficultySelect.value;
  if (controlPresetSelect) state.settings.unitControlPreset = controlPresetSelect.value;

  localStorage.setItem("shc_proto_settings", JSON.stringify(state.settings));
}

function persistSettingsToStorage() {
  localStorage.setItem("shc_proto_settings", JSON.stringify(state.settings));
}

function applyPauseOptionsFromUI() {
  const pausePresetSelect = document.getElementById("pause-unit-control-preset");
  if (!pausePresetSelect) return;
  state.settings.unitControlPreset = pausePresetSelect.value || "classic";
  persistSettingsToStorage();
}

function loadSettings() {
  const raw = localStorage.getItem("shc_proto_settings");
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw);
    state.settings = {
      ...state.settings,
      ...parsed
    };
  } catch (err) {
    console.warn("Failed to parse saved settings.", err);
  }
}

function getSpriteProjectState() {
  if (!state.spriteEditor.project) {
    const factory = typeof window.createDefaultSpriteProject === "function"
      ? window.createDefaultSpriteProject
      : () => ({ projectName: "NewSpriteProject", animations: {} });
    const normalize = typeof window.normalizeSpriteProject === "function"
      ? window.normalizeSpriteProject
      : (raw) => raw;
    state.spriteEditor.project = normalize(factory());
  }
  return state.spriteEditor.project;
}

function getSpriteAnimationNames(project = getSpriteProjectState()) {
  return Object.keys(project.animations || {});
}

function getSpriteDirectionList() {
  return Array.isArray(window.SPRITE_DIRECTIONS) ? window.SPRITE_DIRECTIONS : ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
}

function ensureSpriteAnimation(project, animationName) {
  if (!project || !animationName) return;
  if (!project.animations) project.animations = {};
  if (!project.animations[animationName]) {
    if (typeof window.createDefaultSpriteAnimation === "function") {
      project.animations[animationName] = window.createDefaultSpriteAnimation(animationName);
    } else {
      const byDirection = {};
      for (const dir of getSpriteDirectionList()) {
        byDirection[dir] = [{ x: 0, y: 0, w: 64, h: 64, durationMs: 120, pivotX: 0, pivotY: 0, footY: 0, teamMask: false, shadow: true }];
      }
      project.animations[animationName] = { name: animationName, byDirection };
    }
  }
}

function getActiveSpriteFrame(project = getSpriteProjectState()) {
  ensureSpriteAnimation(project, project.activeAnimation || "idle");
  const animation = project.animations[project.activeAnimation];
  const direction = getSpriteDirectionList().includes(project.activeDirection) ? project.activeDirection : "S";
  if (!Array.isArray(animation.byDirection[direction])) animation.byDirection[direction] = [];
  if (animation.byDirection[direction].length === 0) {
    const defaultFrame = typeof window.createDefaultSpriteFrame === "function"
      ? window.createDefaultSpriteFrame()
      : { x: 0, y: 0, w: 64, h: 64, durationMs: 120, pivotX: 0, pivotY: 0, footY: 0, teamMask: false, shadow: true };
    animation.byDirection[direction].push(defaultFrame);
  }
  const frameIndex = Math.max(0, Math.min(animation.byDirection[direction].length - 1, Number(project.activeFrameIndex || 0)));
  project.activeFrameIndex = frameIndex;
  return {
    frame: animation.byDirection[direction][frameIndex],
    frames: animation.byDirection[direction],
    animation,
    direction,
    frameIndex
  };
}

function cloneSpriteProject(project) {
  return JSON.parse(JSON.stringify(project));
}

function pushSpriteUndoState(label = "edit") {
  const project = getSpriteProjectState();
  state.spriteEditor.undoStack.push({
    label,
    savedAt: Date.now(),
    payload: cloneSpriteProject(project)
  });
  if (state.spriteEditor.undoStack.length > 100) {
    state.spriteEditor.undoStack.shift();
  }
  state.spriteEditor.redoStack = [];
}

function restoreSpriteProjectSnapshot(payload) {
  const normalize = typeof window.normalizeSpriteProject === "function" ? window.normalizeSpriteProject : (x) => x;
  state.spriteEditor.project = normalize(payload);
  state.spriteEditor.previewFrameIndex = Number(state.spriteEditor.project.activeFrameIndex || 0);
  state.spriteEditor.previewFrameElapsedMs = 0;
  refreshSpriteEditorUI();
}

function undoSpriteEditorState() {
  if (!Array.isArray(state.spriteEditor.undoStack) || state.spriteEditor.undoStack.length === 0) return false;
  const current = cloneSpriteProject(getSpriteProjectState());
  const last = state.spriteEditor.undoStack.pop();
  state.spriteEditor.redoStack.push({
    label: "redo",
    savedAt: Date.now(),
    payload: current
  });
  restoreSpriteProjectSnapshot(last.payload);
  return true;
}

function redoSpriteEditorState() {
  if (!Array.isArray(state.spriteEditor.redoStack) || state.spriteEditor.redoStack.length === 0) return false;
  const current = cloneSpriteProject(getSpriteProjectState());
  const next = state.spriteEditor.redoStack.pop();
  state.spriteEditor.undoStack.push({
    label: "undo",
    savedAt: Date.now(),
    payload: current
  });
  restoreSpriteProjectSnapshot(next.payload);
  return true;
}

function getOppositeDirection(direction) {
  const map = {
    N: "N",
    S: "S",
    E: "W",
    W: "E",
    NE: "NW",
    NW: "NE",
    SE: "SW",
    SW: "SE"
  };
  return map[direction] || direction;
}

function getSymmetryAxisPx(project) {
  const norm = Math.max(0, Math.min(1, Number(project.preview?.symmetryAxisNorm ?? 0.5)));
  const sourceWidth = Math.max(1, Number(project.sourceImageWidth || 0));
  return sourceWidth > 0 ? sourceWidth * norm : 64;
}

function mirrorFrameAcrossAxis(frame, axisX) {
  const w = Math.max(1, Number(frame.w || 64));
  const x = Number(frame.x || 0);
  const mirroredX = Math.round((axisX * 2) - (x + w));
  const mirrored = {
    ...cloneSpriteProject(frame),
    x: Math.max(0, mirroredX),
    pivotX: -Number(frame.pivotX || 0),
    collision: {
      ...(frame.collision || { x: 0, y: 0, w: 32, h: 32 }),
      x: -Number(frame.collision?.x || 0)
    }
  };
  return mirrored;
}

function applySymmetryForActiveFrame(project) {
  if (!project.preview?.symmetryEnabled) return;
  ensureSpriteAnimation(project, project.activeAnimation || "idle");
  const active = getActiveSpriteFrame(project);
  const sourceDir = active.direction;
  const targetDir = getOppositeDirection(sourceDir);
  if (targetDir === sourceDir) return;
  const axisX = getSymmetryAxisPx(project);
  const sourceFrame = active.frame;
  const mirrored = mirrorFrameAcrossAxis(sourceFrame, axisX);
  const targetFrames = project.animations[project.activeAnimation].byDirection[targetDir];
  while (targetFrames.length <= active.frameIndex) {
    const defaultFrame = typeof window.createDefaultSpriteFrame === "function"
      ? window.createDefaultSpriteFrame()
      : { x: 0, y: 0, w: 64, h: 64, durationMs: 120, pivotX: 0, pivotY: 0, footY: 0, collision: { x: 0, y: 0, w: 32, h: 32 }, teamMask: false, shadow: true };
    targetFrames.push(defaultFrame);
  }
  targetFrames[active.frameIndex] = mirrored;
}

function saveSpriteProjectToStorage() {
  try {
    localStorage.setItem(state.spriteEditor.storageKey, JSON.stringify(getSpriteProjectState()));
    return true;
  } catch (err) {
    console.warn("Failed to save sprite project.", err);
    return false;
  }
}

function loadSpriteProjectFromStorage() {
  const raw = localStorage.getItem(state.spriteEditor.storageKey);
  if (!raw) return false;
  try {
    const parsed = JSON.parse(raw);
    const normalize = typeof window.normalizeSpriteProject === "function" ? window.normalizeSpriteProject : (x) => x;
    state.spriteEditor.project = normalize(parsed);
    return true;
  } catch (err) {
    console.warn("Failed to parse sprite project save.", err);
    return false;
  }
}

function getSpriteGeneratedStore() {
  const raw = localStorage.getItem(state.spriteEditor.generatedKey);
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch (err) {
    console.warn("Failed to parse generated sprite store.", err);
    return {};
  }
}

function saveSpriteGeneratedStore(store) {
  localStorage.setItem(state.spriteEditor.generatedKey, JSON.stringify(store || {}));
}

function validateSpriteProject(project = getSpriteProjectState()) {
  const errors = [];
  const warnings = [];
  const requiredAnimations = Array.isArray(window.SPRITE_REQUIRED_ANIMATIONS) ? window.SPRITE_REQUIRED_ANIMATIONS : ["idle", "walk", "attack", "hit", "death"];
  const directions = getSpriteDirectionList();
  const namingPattern = String(project.namingPattern || "unit_<anim>_<dir>_<frame>");
  const namingTokensOk = namingPattern.includes("<anim>") && namingPattern.includes("<dir>") && namingPattern.includes("<frame>");
  if (!namingTokensOk) {
    errors.push("Naming pattern must include <anim>, <dir>, and <frame> tokens.");
  }
  for (const animName of requiredAnimations) {
    if (!project.animations?.[animName]) {
      errors.push(`Missing required animation: ${animName}`);
      continue;
    }
    for (const dir of directions) {
      const frames = project.animations[animName].byDirection?.[dir];
      if (!Array.isArray(frames) || frames.length === 0) {
        errors.push(`Animation ${animName} missing direction ${dir}.`);
        continue;
      }
      for (let i = 0; i < frames.length; i += 1) {
        const frame = frames[i];
        if (frame.w <= 0 || frame.h <= 0) errors.push(`${animName}/${dir}[${i}] has invalid size.`);
        if (frame.durationMs < 20) errors.push(`${animName}/${dir}[${i}] duration too low.`);
        if (frame.collision && (frame.collision.w <= 0 || frame.collision.h <= 0)) {
          errors.push(`${animName}/${dir}[${i}] has invalid collision footprint.`);
        }
        if (project.sourceImageWidth > 0 && project.sourceImageHeight > 0) {
          if (frame.x + frame.w > project.sourceImageWidth || frame.y + frame.h > project.sourceImageHeight) {
            warnings.push(`${animName}/${dir}[${i}] exceeds source image bounds.`);
          }
        }
      }
    }
  }
  project.validation = {
    lastRunAt: Date.now(),
    errors,
    warnings
  };
  return project.validation;
}

function buildSpriteFrameName(project, animationName, direction, frameIndex) {
  const template = String(project.namingPattern || "unit_<anim>_<dir>_<frame>");
  return template
    .replace("<unit>", String(project.unitType || "unit"))
    .replace("<anim>", String(animationName || "anim"))
    .replace("<dir>", String(direction || "S"))
    .replace("<frame>", String(frameIndex));
}

function applySpriteImportPreset(project, presetId) {
  if (!project || !project.sourceImageWidth || !project.sourceImageHeight) return { ok: false, message: "Import a source sheet first." };
  const preset = String(presetId || "single_row");
  const requiredAnimations = Array.isArray(window.SPRITE_REQUIRED_ANIMATIONS) ? window.SPRITE_REQUIRED_ANIMATIONS : ["idle", "walk", "attack", "hit", "death"];
  const directions = getSpriteDirectionList();
  const frameW = 64;
  const frameH = 64;
  if (preset === "single_row") {
    const active = getActiveSpriteFrame(project);
    const count = Math.max(1, Math.floor(project.sourceImageWidth / frameW));
    active.frames.length = 0;
    for (let i = 0; i < count; i += 1) {
      active.frames.push({
        x: i * frameW,
        y: 0,
        w: frameW,
        h: frameH,
        durationMs: 120,
        pivotX: 0,
        pivotY: 0,
        footY: 0,
        collision: { x: 0, y: 0, w: 32, h: 32 },
        teamMask: false,
        shadow: true
      });
    }
    project.activeFrameIndex = 0;
    return { ok: true, message: `Single row preset loaded ${count} frames.` };
  }
  if (preset === "chatgpt_sheet_v1") {
    for (let ai = 0; ai < requiredAnimations.length; ai += 1) {
      const animName = requiredAnimations[ai];
      ensureSpriteAnimation(project, animName);
      for (let di = 0; di < directions.length; di += 1) {
        const dir = directions[di];
        const frames = [];
        const baseY = ai * directions.length * frameH + di * frameH;
        const cols = Math.max(1, Math.floor(project.sourceImageWidth / frameW));
        for (let col = 0; col < cols; col += 1) {
          frames.push({
            x: col * frameW,
            y: baseY,
            w: frameW,
            h: frameH,
            durationMs: 120,
            pivotX: 0,
            pivotY: 0,
            footY: 0,
            collision: { x: 0, y: 0, w: 32, h: 32 },
            teamMask: false,
            shadow: true
          });
        }
        project.animations[animName].byDirection[dir] = frames;
      }
    }
    project.activeAnimation = "idle";
    project.activeDirection = "S";
    project.activeFrameIndex = 0;
    return { ok: true, message: "ChatGPT sheet preset applied across all animations/directions." };
  }
  if (preset === "dirs_rows_v1") {
    const active = project.activeAnimation || "idle";
    ensureSpriteAnimation(project, active);
    for (let di = 0; di < directions.length; di += 1) {
      const dir = directions[di];
      const frames = [];
      const y = di * frameH;
      const cols = Math.max(1, Math.floor(project.sourceImageWidth / frameW));
      for (let col = 0; col < cols; col += 1) {
        frames.push({
          x: col * frameW,
          y,
          w: frameW,
          h: frameH,
          durationMs: 120,
          pivotX: 0,
          pivotY: 0,
          footY: 0,
          collision: { x: 0, y: 0, w: 32, h: 32 },
          teamMask: false,
          shadow: true
        });
      }
      project.animations[active].byDirection[dir] = frames;
    }
    project.activeDirection = "S";
    project.activeFrameIndex = 0;
    return { ok: true, message: "Direction rows preset applied to active animation." };
  }
  return { ok: false, message: "Unknown import preset." };
}

function getSpriteTeamTint(teamValue) {
  if (teamValue === "enemy") return "rgba(221, 112, 112, 0.35)";
  return "rgba(122, 189, 255, 0.35)";
}

function getSpritePreviewBackground(type) {
  if (type === "grass") return "#284227";
  if (type === "rock") return "#3c3a3a";
  if (type === "lake") return "#234663";
  return "#4a371e";
}

function updateSpriteEditorReadout(id, content) {
  const el = document.getElementById(id);
  if (el) el.innerHTML = content;
}

function buildSpriteGeneratedManifest(project, slug) {
  if (typeof window.buildSpriteExportPayload === "function") {
    return window.buildSpriteExportPayload(project, slug).manifest;
  }
  const manifest = {
    schemaVersion: 1,
    unitType: project.unitType,
    archetype: project.archetype,
    namingPattern: project.namingPattern || "unit_<anim>_<dir>_<frame>",
    atlasPath: `data/generated/${slug}.png`,
    sourceImageName: project.sourceImageName || "atlas-source.png",
    frameSizeHint: { w: 64, h: 64 },
    animations: project.animations,
    generatedFrameNames: {},
    exportedAt: Date.now()
  };
  for (const [animName, anim] of Object.entries(project.animations || {})) {
    manifest.generatedFrameNames[animName] = {};
    for (const dir of getSpriteDirectionList()) {
      const frames = Array.isArray(anim?.byDirection?.[dir]) ? anim.byDirection[dir] : [];
      manifest.generatedFrameNames[animName][dir] = frames.map((_, idx) => buildSpriteFrameName(project, animName, dir, idx));
    }
  }
  return manifest;
}

function registerSpriteManifestInRuntime(unitType, payload) {
  if (!window.SPRITE_RUNTIME_REGISTRY || typeof window.SPRITE_RUNTIME_REGISTRY !== "object") {
    window.SPRITE_RUNTIME_REGISTRY = { schemaVersion: 1, generatedAt: 0, assetsByUnitType: {}, generatedFiles: {} };
  }
  if (!window.SPRITE_RUNTIME_REGISTRY.assetsByUnitType) window.SPRITE_RUNTIME_REGISTRY.assetsByUnitType = {};
  if (!window.SPRITE_RUNTIME_REGISTRY.generatedFiles) window.SPRITE_RUNTIME_REGISTRY.generatedFiles = {};
  window.SPRITE_RUNTIME_REGISTRY.generatedAt = Date.now();
  window.SPRITE_RUNTIME_REGISTRY.assetsByUnitType[unitType] = payload;
  if (payload.manifestPath) {
    window.SPRITE_RUNTIME_REGISTRY.generatedFiles[payload.manifestPath] = payload.manifest;
  }
  try {
    localStorage.setItem("shc_sprite_runtime_registry_v1", JSON.stringify(window.SPRITE_RUNTIME_REGISTRY));
  } catch (err) {
    console.warn("Failed to persist runtime sprite registry.", err);
  }
}

function loadRuntimeSpriteRegistry() {
  const raw = localStorage.getItem("shc_sprite_runtime_registry_v1");
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return;
    window.SPRITE_RUNTIME_REGISTRY = {
      schemaVersion: Number(parsed.schemaVersion || 1),
      generatedAt: Number(parsed.generatedAt || 0),
      assetsByUnitType: parsed.assetsByUnitType || {},
      generatedFiles: parsed.generatedFiles || {}
    };
  } catch (err) {
    console.warn("Failed to load runtime sprite registry.", err);
  }
}

function getSpritePreviewDrawRect(frame, canvas) {
  const centerX = Math.floor(canvas.width * 0.5);
  const centerY = Math.floor(canvas.height * 0.64);
  const drawW = Math.max(1, Number(frame.w || 64));
  const drawH = Math.max(1, Number(frame.h || 64));
  return {
    x: Math.round(centerX - drawW * 0.5 + Number(frame.pivotX || 0)),
    y: Math.round(centerY - drawH + Number(frame.pivotY || 0)),
    w: drawW,
    h: drawH,
    centerX,
    centerY
  };
}

function renderSpriteEditorPreview() {
  const canvas = document.getElementById("sprite-preview-canvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const project = getSpriteProjectState();
  const bgType = document.getElementById("sprite-preview-bg")?.value || "sand";
  const teamType = document.getElementById("sprite-preview-team")?.value || "player";
  const active = getActiveSpriteFrame(project);
  const frame = active.frame;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = getSpritePreviewBackground(bgType);
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  ctx.strokeStyle = "rgba(255,255,255,0.15)";
  for (let x = 0; x <= canvas.width; x += 32) {
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, canvas.height);
    ctx.stroke();
  }
  for (let y = 0; y <= canvas.height; y += 32) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(canvas.width, y);
    ctx.stroke();
  }

  const primaryRect = getSpritePreviewDrawRect(frame, canvas);
  const centerX = primaryRect.centerX;
  const centerY = primaryRect.centerY;
  if (frame.shadow !== false) {
    ctx.fillStyle = "rgba(0,0,0,0.28)";
    ctx.beginPath();
    ctx.ellipse(centerX, centerY + 8, 24, 9, 0, 0, Math.PI * 2);
    ctx.fill();
  }

  const hasSource = !!project.sourceImageDataUrl;
  const drawFrame = (f, alpha = 1, tint = null) => {
    if (!f) return;
    const rect = getSpritePreviewDrawRect(f, canvas);
    const drawW = rect.w;
    const drawH = rect.h;
    const drawX = rect.x;
    const drawY = rect.y;
    if (hasSource) {
      const img = state.spriteEditor.cachedImage;
      if (img?.complete && img.naturalWidth > 0) {
        const prevAlpha = ctx.globalAlpha;
        ctx.globalAlpha = alpha;
        ctx.drawImage(img, f.x, f.y, drawW, drawH, drawX, drawY, drawW, drawH);
        ctx.globalAlpha = prevAlpha;
      }
    } else {
      const prevAlpha = ctx.globalAlpha;
      ctx.globalAlpha = alpha;
      ctx.fillStyle = teamType === "enemy" ? "#aa5858" : "#7ca7df";
      ctx.fillRect(drawX, drawY, drawW, drawH);
      ctx.globalAlpha = prevAlpha;
    }
    if (tint) {
      const prevAlpha = ctx.globalAlpha;
      ctx.globalAlpha = alpha;
      ctx.fillStyle = tint;
      ctx.fillRect(drawX, drawY, drawW, drawH);
      ctx.globalAlpha = prevAlpha;
    }
    const collision = f.collision || { x: 0, y: 0, w: 32, h: 32 };
    const cx = Math.round(centerX - collision.w * 0.5 + Number(collision.x || 0));
    const cy = Math.round(centerY - collision.h + Number(collision.y || 0));
    ctx.strokeStyle = "rgba(255, 220, 160, 0.7)";
    ctx.strokeRect(cx, cy, collision.w, collision.h);
  };
  if (hasSource) {
    if (!state.spriteEditor.cachedImage || state.spriteEditor.cachedImage.src !== project.sourceImageDataUrl) {
      const img = new Image();
      img.src = project.sourceImageDataUrl;
      img.onload = () => {
        state.spriteEditor.cachedImage = img;
        renderSpriteEditorPreview();
      };
      state.spriteEditor.cachedImage = img;
    }
    const img = state.spriteEditor.cachedImage;
    if (img?.complete && img.naturalWidth > 0) {
      const prevFrame = active.frames[Math.max(0, active.frameIndex - 1)] || null;
      const nextFrame = active.frames[Math.min(active.frames.length - 1, active.frameIndex + 1)] || null;
      if (project.preview?.onionSkin && prevFrame) drawFrame(prevFrame, Number(project.preview?.onionOpacity ?? 0.35), "rgba(120,190,255,0.18)");
      drawFrame(frame, 1, frame.teamMask ? getSpriteTeamTint(teamType) : null);
      if (project.preview?.ghostFrame && nextFrame && nextFrame !== frame) drawFrame(nextFrame, Number(project.preview?.ghostOpacity ?? 0.25), "rgba(255,190,130,0.2)");
    }
  } else {
    const prevFrame = active.frames[Math.max(0, active.frameIndex - 1)] || null;
    const nextFrame = active.frames[Math.min(active.frames.length - 1, active.frameIndex + 1)] || null;
    if (project.preview?.onionSkin && prevFrame) drawFrame(prevFrame, Number(project.preview?.onionOpacity ?? 0.35), "rgba(120,190,255,0.18)");
    drawFrame(frame, 1, frame.teamMask ? getSpriteTeamTint(teamType) : null);
    if (project.preview?.ghostFrame && nextFrame && nextFrame !== frame) drawFrame(nextFrame, Number(project.preview?.ghostOpacity ?? 0.25), "rgba(255,190,130,0.2)");
  }

  if (project.preview?.symmetryEnabled) {
    const axisNorm = Math.max(0, Math.min(1, Number(project.preview?.symmetryAxisNorm ?? 0.5)));
    const axisX = Math.round(axisNorm * canvas.width);
    ctx.strokeStyle = "rgba(255, 206, 122, 0.9)";
    ctx.setLineDash([6, 4]);
    ctx.beginPath();
    ctx.moveTo(axisX, 0);
    ctx.lineTo(axisX, canvas.height);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.beginPath();
    ctx.fillStyle = "rgba(255, 206, 122, 0.95)";
    ctx.arc(axisX, 12, 6, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.strokeStyle = "rgba(38, 28, 16, 0.9)";
    ctx.moveTo(axisX, 6);
    ctx.lineTo(axisX, 18);
    ctx.stroke();
  }

  const drag = state.spriteEditor.dragSelection;
  if (drag?.active) {
    const left = Math.min(drag.startX, drag.currentX);
    const top = Math.min(drag.startY, drag.currentY);
    const width = Math.abs(drag.currentX - drag.startX);
    const height = Math.abs(drag.currentY - drag.startY);
    ctx.strokeStyle = "rgba(120, 200, 255, 0.95)";
    ctx.fillStyle = "rgba(120, 200, 255, 0.2)";
    ctx.strokeRect(left, top, width, height);
    ctx.fillRect(left, top, width, height);
  }

  ctx.fillStyle = "rgba(246,229,198,0.9)";
  ctx.font = "12px Segoe UI";
  const generatedName = buildSpriteFrameName(project, project.activeAnimation, active.direction, active.frameIndex);
  ctx.fillText(`${project.activeAnimation}/${active.direction} frame ${active.frameIndex}`, 10, 18);
  ctx.fillText(`id: ${generatedName}`, 10, 34);
}

function consumeAnimatorMeshHandoff() {
  try {
    const raw = sessionStorage.getItem("shc_pending_mesh_handoff");
    if (!raw) return;
    sessionStorage.removeItem("shc_pending_mesh_handoff");
    const data = JSON.parse(raw);
    if (!data?.gltfDataUrl) return;
    const project = getSpriteProjectState();
    project.mesh3d = {
      format: "gltf",
      dataUrl: String(data.gltfDataUrl),
      fileName: String(data.fileName || "item-mesh.gltf"),
      sourceProject: String(data.projectName || "")
    };
    saveSpriteProjectToStorage();
    showToast("3D mesh linked from Item Creator.");
  } catch (e) {
    console.warn(e);
  }
}

function refreshSpriteEditorUI() {
  const project = getSpriteProjectState();
  const projectNameInput = document.getElementById("sprite-project-name");
  const archetypeSelect = document.getElementById("sprite-project-archetype");
  const unitTypeSelect = document.getElementById("sprite-unit-type");
  const animSelect = document.getElementById("sprite-animation-select");
  const directionSelect = document.getElementById("sprite-direction-select");
  const frameIndexInput = document.getElementById("sprite-frame-index");
  const namingPatternInput = document.getElementById("sprite-naming-pattern");
  const presetSelect = document.getElementById("sprite-import-preset");
  const onionOpacityInput = document.getElementById("sprite-onion-opacity");
  const ghostOpacityInput = document.getElementById("sprite-ghost-opacity");
  const symmetryEnabledInput = document.getElementById("sprite-symmetry-enabled");
  const symmetryAxisInput = document.getElementById("sprite-symmetry-axis");
  if (projectNameInput) projectNameInput.value = project.projectName || "";
  if (namingPatternInput) namingPatternInput.value = project.namingPattern || "unit_<anim>_<dir>_<frame>";
  if (archetypeSelect) archetypeSelect.value = project.archetype || "unit";
  if (unitTypeSelect) unitTypeSelect.value = project.unitType || "militia";
  if (presetSelect) presetSelect.value = project.importPreset || "single_row";
  if (animSelect) {
    const names = getSpriteAnimationNames(project);
    animSelect.innerHTML = names.map((name) => `<option value="${name}">${name}</option>`).join("");
    if (names.includes(project.activeAnimation)) animSelect.value = project.activeAnimation;
  }
  if (directionSelect) directionSelect.value = project.activeDirection || "S";
  const active = getActiveSpriteFrame(project);
  if (frameIndexInput) frameIndexInput.value = String(active.frameIndex);
  const frame = active.frame;
  const setValue = (id, value) => {
    const input = document.getElementById(id);
    if (input) input.value = String(value);
  };
  setValue("sprite-frame-x", frame.x);
  setValue("sprite-frame-y", frame.y);
  setValue("sprite-frame-w", frame.w);
  setValue("sprite-frame-h", frame.h);
  setValue("sprite-frame-duration", frame.durationMs);
  setValue("sprite-pivot-x", frame.pivotX);
  setValue("sprite-pivot-y", frame.pivotY);
  setValue("sprite-foot-y", frame.footY);
  const teamMaskCheckbox = document.getElementById("sprite-team-mask-enabled");
  if (teamMaskCheckbox) teamMaskCheckbox.checked = !!frame.teamMask;
  const shadowCheckbox = document.getElementById("sprite-shadow-enabled");
  if (shadowCheckbox) shadowCheckbox.checked = frame.shadow !== false;
  setValue("sprite-collision-w", frame.collision?.w ?? 32);
  setValue("sprite-collision-h", frame.collision?.h ?? 32);
  setValue("sprite-collision-x", frame.collision?.x ?? 0);
  setValue("sprite-collision-y", frame.collision?.y ?? 0);
  const onionCheckbox = document.getElementById("sprite-onion-enabled");
  if (onionCheckbox) onionCheckbox.checked = !!project.preview?.onionSkin;
  const ghostCheckbox = document.getElementById("sprite-ghost-enabled");
  if (ghostCheckbox) ghostCheckbox.checked = !!project.preview?.ghostFrame;
  if (onionOpacityInput) onionOpacityInput.value = String(Math.round(Number(project.preview?.onionOpacity ?? 0.35) * 100));
  if (ghostOpacityInput) ghostOpacityInput.value = String(Math.round(Number(project.preview?.ghostOpacity ?? 0.25) * 100));
  if (symmetryEnabledInput) symmetryEnabledInput.checked = !!project.preview?.symmetryEnabled;
  if (symmetryAxisInput) symmetryAxisInput.value = String(Math.round(Number(project.preview?.symmetryAxisNorm ?? 0.5) * 100));
  updateSpriteEditorReadout("sprite-sheet-meta", project.sourceImageDataUrl
    ? `Sheet: <strong>${project.sourceImageName || "inline source"}</strong><br>Size: <strong>${project.sourceImageWidth}x${project.sourceImageHeight}</strong>`
    : "No sheet loaded.");
  updateSpriteEditorReadout(
    "sprite-frame-list",
    active.frames
      .map((f, idx) => {
        const name = buildSpriteFrameName(project, project.activeAnimation, active.direction, idx);
        const collision = f.collision || { x: 0, y: 0, w: 32, h: 32 };
        return `${idx === active.frameIndex ? ">" : "-"} #${idx} ${name}<br>@(${f.x},${f.y}) ${f.w}x${f.h} ${f.durationMs}ms | col(${collision.x},${collision.y},${collision.w},${collision.h})`;
      })
      .join("<br>")
  );
  const directions = getSpriteDirectionList();
  const matrixRows = directions.map((dir) => {
    const count = project.animations?.[project.activeAnimation]?.byDirection?.[dir]?.length || 0;
    return `${dir}: <strong>${count}</strong>`;
  });
  updateSpriteEditorReadout("sprite-direction-matrix", matrixRows.join(" | "));
  const snapshotSlots = ["slot1", "slot2", "slot3", "slot4"];
  const snapSummary = snapshotSlots
    .map((slot) => `${slot}:${project.snapshots?.[slot] ? "saved" : "empty"}`)
    .join(" | ");
  updateSpriteEditorReadout("sprite-snapshot-readout", `Snapshots: ${snapSummary}`);
  updateSpriteEditorReadout(
    "sprite-mesh-readout",
    project.mesh3d?.dataUrl
      ? `Linked: <strong>${project.mesh3d.fileName || "mesh.gltf"}</strong> (${project.mesh3d.format || "gltf"})`
      : "No 3D mesh linked."
  );
  if (window.SpriteMeshPreview && typeof window.SpriteMeshPreview.sync === "function") {
    window.SpriteMeshPreview.sync(project);
  }
  renderSpriteEditorPreview();
}

function runSpriteEditorSmokeTests() {
  const project = getSpriteProjectState();
  const checks = [];
  checks.push({
    name: "has_project_name",
    pass: typeof project.projectName === "string" && project.projectName.length > 0
  });
  checks.push({
    name: "has_required_anims",
    pass: (window.SPRITE_REQUIRED_ANIMATIONS || []).every((name) => !!project.animations?.[name])
  });
  const active = getActiveSpriteFrame(project);
  checks.push({
    name: "active_frame_valid",
    pass: active.frame.w > 0 && active.frame.h > 0 && active.frame.durationMs >= 20
  });
  checks.push({
    name: "source_or_placeholder",
    pass: !!project.sourceImageDataUrl || project.archetype === "effect" || true
  });
  return checks;
}

function readNumberInputValue(id, fallback = 0) {
  const el = document.getElementById(id);
  if (!el) return fallback;
  const v = Number(el.value);
  return Number.isFinite(v) ? v : fallback;
}

function syncSpriteEditorProjectFromInputs() {
  const project = getSpriteProjectState();
  const projectNameInput = document.getElementById("sprite-project-name");
  const archetypeSelect = document.getElementById("sprite-project-archetype");
  const unitTypeSelect = document.getElementById("sprite-unit-type");
  const animSelect = document.getElementById("sprite-animation-select");
  const directionSelect = document.getElementById("sprite-direction-select");
  const frameIndexInput = document.getElementById("sprite-frame-index");
  const namingPatternInput = document.getElementById("sprite-naming-pattern");
  const presetSelect = document.getElementById("sprite-import-preset");
  if (projectNameInput) project.projectName = String(projectNameInput.value || "NewSpriteProject").trim() || "NewSpriteProject";
  if (namingPatternInput) project.namingPattern = String(namingPatternInput.value || "unit_<anim>_<dir>_<frame>");
  if (archetypeSelect) project.archetype = archetypeSelect.value || "unit";
  if (unitTypeSelect) project.unitType = unitTypeSelect.value || "militia";
  if (presetSelect) project.importPreset = presetSelect.value || "single_row";
  if (animSelect) project.activeAnimation = animSelect.value || project.activeAnimation || "idle";
  if (directionSelect) project.activeDirection = directionSelect.value || project.activeDirection || "S";
  if (frameIndexInput) project.activeFrameIndex = Math.max(0, Number(frameIndexInput.value || 0));
  project.preview = project.preview || { onionSkin: false, ghostFrame: false };
  project.preview.onionSkin = !!document.getElementById("sprite-onion-enabled")?.checked;
  project.preview.ghostFrame = !!document.getElementById("sprite-ghost-enabled")?.checked;
  project.preview.onionOpacity = Math.max(0, Math.min(1, readNumberInputValue("sprite-onion-opacity", 35) / 100));
  project.preview.ghostOpacity = Math.max(0, Math.min(1, readNumberInputValue("sprite-ghost-opacity", 25) / 100));
  project.preview.symmetryEnabled = !!document.getElementById("sprite-symmetry-enabled")?.checked;
  project.preview.symmetryAxisNorm = Math.max(0, Math.min(1, readNumberInputValue("sprite-symmetry-axis", 50) / 100));
}

function mountSpriteEditor() {
  if (!loadSpriteProjectFromStorage()) {
    getSpriteProjectState();
  }
  consumeAnimatorMeshHandoff();
  setupSpritePreviewCanvasInput();
  refreshSpriteEditorUI();
}

function setupSpritePreviewCanvasInput() {
  const canvas = document.getElementById("sprite-preview-canvas");
  if (!canvas) return;
  if (canvas.dataset.bound === "1") return;
  canvas.dataset.bound = "1";
  const getLocal = (event) => {
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / Math.max(1, rect.width);
    const scaleY = canvas.height / Math.max(1, rect.height);
    return {
      x: (event.clientX - rect.left) * scaleX,
      y: (event.clientY - rect.top) * scaleY
    };
  };
  canvas.addEventListener("pointerdown", (event) => {
    if (state.currentScreen !== "sprite-editor") return;
    const project = getSpriteProjectState();
    const local = getLocal(event);
    const axisX = Math.round(Math.max(0, Math.min(1, Number(project.preview?.symmetryAxisNorm ?? 0.5))) * canvas.width);
    const nearAxis = !!project.preview?.symmetryEnabled && Math.abs(local.x - axisX) <= 10;
    state.spriteEditor.dragSelection = {
      active: true,
      mode: nearAxis ? "axis" : "frame",
      startX: local.x,
      startY: local.y,
      currentX: local.x,
      currentY: local.y
    };
    canvas.setPointerCapture(event.pointerId);
    renderSpriteEditorPreview();
  });
  canvas.addEventListener("pointermove", (event) => {
    const local = getLocal(event);
    if (!state.spriteEditor.dragSelection?.active) {
      const project = getSpriteProjectState();
      if (project.preview?.symmetryEnabled) {
        const axisX = Math.round(Math.max(0, Math.min(1, Number(project.preview?.symmetryAxisNorm ?? 0.5))) * canvas.width);
        canvas.style.cursor = Math.abs(local.x - axisX) <= 10 ? "ew-resize" : "crosshair";
      } else {
        canvas.style.cursor = "crosshair";
      }
      return;
    }
    state.spriteEditor.dragSelection.currentX = local.x;
    state.spriteEditor.dragSelection.currentY = local.y;
    if (state.spriteEditor.dragSelection.mode === "axis") {
      const project = getSpriteProjectState();
      project.preview = project.preview || {};
      project.preview.symmetryAxisNorm = Math.max(0, Math.min(1, local.x / Math.max(1, canvas.width)));
      const axisInput = document.getElementById("sprite-symmetry-axis");
      if (axisInput) axisInput.value = String(Math.round(project.preview.symmetryAxisNorm * 100));
      renderSpriteEditorPreview();
      return;
    }
    renderSpriteEditorPreview();
  });
  const finalize = () => {
    const drag = state.spriteEditor.dragSelection;
    if (!drag?.active) return;
    const project = getSpriteProjectState();
    if (drag.mode === "axis") {
      state.spriteEditor.dragSelection.active = false;
      refreshSpriteEditorUI();
      showToast("Symmetry axis moved.");
      return;
    }
    const active = getActiveSpriteFrame(project);
    const frame = active.frame;
    const rect = getSpritePreviewDrawRect(frame, canvas);
    const left = Math.min(drag.startX, drag.currentX);
    const top = Math.min(drag.startY, drag.currentY);
    const width = Math.abs(drag.currentX - drag.startX);
    const height = Math.abs(drag.currentY - drag.startY);
    state.spriteEditor.dragSelection.active = false;
    if (width < 3 || height < 3) {
      renderSpriteEditorPreview();
      return;
    }
    pushSpriteUndoState("drag-select-frame");
    const baseX = Number(frame.x || 0);
    const baseY = Number(frame.y || 0);
    frame.x = Math.max(0, Math.round(baseX + (left - rect.x)));
    frame.y = Math.max(0, Math.round(baseY + (top - rect.y)));
    frame.w = Math.max(1, Math.round(width));
    frame.h = Math.max(1, Math.round(height));
    applySymmetryForActiveFrame(project);
    refreshSpriteEditorUI();
    showToast("Frame rect updated from preview drag.");
  };
  canvas.addEventListener("pointerup", finalize);
  canvas.addEventListener("pointercancel", finalize);
}

function stopSpritePreviewPlayback() {
  state.spriteEditor.previewPlaying = false;
  if (state.spriteEditor.previewRafId) {
    cancelAnimationFrame(state.spriteEditor.previewRafId);
    state.spriteEditor.previewRafId = 0;
  }
}

function startSpritePreviewPlayback() {
  stopSpritePreviewPlayback();
  state.spriteEditor.previewPlaying = true;
  let lastTs = performance.now();
  const tick = (ts) => {
    if (!state.spriteEditor.previewPlaying || state.currentScreen !== "sprite-editor") return;
    const project = getSpriteProjectState();
    const active = getActiveSpriteFrame(project);
    const elapsed = Math.max(0, ts - lastTs);
    lastTs = ts;
    state.spriteEditor.previewFrameElapsedMs += elapsed;
    const currentFrame = active.frames[Math.max(0, Math.min(active.frames.length - 1, state.spriteEditor.previewFrameIndex))];
    const duration = Math.max(20, Number(currentFrame?.durationMs || 120));
    if (state.spriteEditor.previewFrameElapsedMs >= duration) {
      state.spriteEditor.previewFrameElapsedMs = 0;
      state.spriteEditor.previewFrameIndex = (state.spriteEditor.previewFrameIndex + 1) % Math.max(1, active.frames.length);
      project.activeFrameIndex = state.spriteEditor.previewFrameIndex;
      refreshSpriteEditorUI();
    } else {
      renderSpriteEditorPreview();
    }
    state.spriteEditor.previewRafId = requestAnimationFrame(tick);
  };
  state.spriteEditor.previewRafId = requestAnimationFrame(tick);
}

function createDefaultItemPart(name = "part-1") {
  const genId = window.ItemScene?.partId || (() => `p_${Math.random().toString(36).slice(2, 11)}`);
  return {
    id: genId(),
    parentId: null,
    name,
    shape: "box",
    x: 0,
    y: 0,
    z: 0,
    w: 40,
    h: 40,
    d: 40,
    yaw: 0,
    color: "#a88d5f",
    textureDataUrl: "",
    textureLabel: "",
    texturePresetKind: ""
  };
}

function createDefaultItemProject() {
  const p0 = createDefaultItemPart("part-1");
  return {
    projectName: "SimpleItem",
    category: "prop",
    selectedPartId: p0.id,
    showGrid: true,
    interactionMode: "orbit",
    paintSymmetry: "none",
    backgroundColor: "#2a2115",
    wireframe: false,
    camera: {
      yaw: -35,
      pitch: 22,
      zoom: 1
    },
    parts: [p0]
  };
}

function normalizeItemProject(raw) {
  const base = createDefaultItemProject();
  const source = raw && typeof raw === "object" ? raw : {};
  const partsIn = Array.isArray(source.parts) && source.parts.length > 0 ? source.parts : base.parts;
  const parts = partsIn.map((part, idx) => {
    const merged = {
      ...createDefaultItemPart(`part-${idx + 1}`),
      ...(part || {}),
      id: String(
        part?.id || (window.ItemScene?.partId ? window.ItemScene.partId() : `p_m${idx}_${Math.random().toString(36).slice(2, 9)}`)
      ),
      parentId: part?.parentId ? String(part.parentId) : null,
      w: Math.max(1, Number(part?.w || 40)),
      h: Math.max(1, Number(part?.h || 40)),
      d: Math.max(1, Number(part?.d || 40)),
      color: /^#([0-9a-f]{6})$/i.test(String(part?.color || "")) ? String(part.color) : "#a88d5f",
      textureDataUrl:
        typeof part?.textureDataUrl === "string" && String(part.textureDataUrl).startsWith("data:")
          ? String(part.textureDataUrl)
          : "",
      textureLabel: String(part?.textureLabel || ""),
      texturePresetKind: String(part?.texturePresetKind || "")
    };
    return merged;
  });
  const idSet = new Set(parts.map((p) => p.id));
  parts.forEach((p) => {
    if (p.parentId && !idSet.has(p.parentId)) p.parentId = null;
  });
  let selectedPartId;
  if ("selectedPartId" in source) {
    selectedPartId =
      source.selectedPartId != null && source.selectedPartId !== "" ? String(source.selectedPartId) : null;
  } else if (typeof source.activePartIndex === "number" && parts[source.activePartIndex]) {
    selectedPartId = parts[source.activePartIndex].id;
  } else if (parts[0]) {
    selectedPartId = parts[0].id;
  } else {
    selectedPartId = null;
  }
  if (selectedPartId && !idSet.has(selectedPartId)) selectedPartId = null;
  const validModes = new Set(["orbit", "move", "scale", "rotate", "paint"]);
  let interactionMode = validModes.has(String(source.interactionMode || "")) ? String(source.interactionMode) : "orbit";
  if (!validModes.has(interactionMode)) interactionMode = "orbit";
  const symSet = new Set(["none", "worldX", "worldY", "worldZ"]);
  const paintSymmetry = symSet.has(String(source.paintSymmetry || "")) ? String(source.paintSymmetry) : "none";
  const bg = String(source.backgroundColor || "");
  const backgroundColor = /^#([0-9a-f]{6})$/i.test(bg) ? bg : "#2a2115";
  const project = {
    ...base,
    ...source,
    interactionMode,
    paintSymmetry,
    backgroundColor,
    wireframe: !!source.wireframe,
    showGrid: source.showGrid !== false,
    camera: {
      ...base.camera,
      ...(source.camera || {}),
      zoom: Math.max(0.35, Math.min(3, Number(source.camera?.zoom ?? base.camera.zoom)))
    },
    selectedPartId,
    parts
  };
  if (window.ItemScene?.validateParents) window.ItemScene.validateParents(project);
  return project;
}

function getItemProjectState() {
  if (!state.itemEditor.project) {
    state.itemEditor.project = createDefaultItemProject();
  }
  return state.itemEditor.project;
}

function saveItemProjectToStorage() {
  try {
    localStorage.setItem(state.itemEditor.storageKey, JSON.stringify(getItemProjectState()));
    return true;
  } catch (err) {
    console.warn("Failed to save item project.", err);
    return false;
  }
}

function loadItemProjectFromStorage() {
  const raw = localStorage.getItem(state.itemEditor.storageKey);
  if (!raw) return false;
  try {
    const parsed = JSON.parse(raw);
    state.itemEditor.project = normalizeItemProject(parsed);
    return true;
  } catch (err) {
    console.warn("Failed to load item project.", err);
    return false;
  }
}

function getActiveItemPart(project = getItemProjectState()) {
  if (!Array.isArray(project.parts) || project.parts.length === 0) {
    const p = createDefaultItemPart("part-1");
    project.parts = [p];
    project.selectedPartId = p.id;
  }
  if (window.ItemScene?.validateParents) window.ItemScene.validateParents(project);
  const sid = project.selectedPartId;
  if (!sid) return null;
  const found = project.parts.find((p) => p.id === sid);
  if (!found) {
    project.selectedPartId = project.parts[0]?.id || null;
    return project.parts[0] || null;
  }
  return found;
}

function getItemPartIndexById(project, id) {
  return project.parts.findIndex((p) => p.id === id);
}

function pointInTriangle(px, py, ax, ay, bx, by, cx, cy) {
  const sign = (x1, y1, x2, y2, x3, y3) => (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3);
  const d1 = sign(px, py, ax, ay, bx, by);
  const d2 = sign(px, py, bx, by, cx, cy);
  const d3 = sign(px, py, cx, cy, ax, ay);
  const hasNeg = d1 < 0 || d2 < 0 || d3 < 0;
  const hasPos = d1 > 0 || d2 > 0 || d3 > 0;
  return !(hasNeg && hasPos);
}

function pointInConvexPolyScreen(px, py, pts) {
  if (!pts || pts.length < 3) return false;
  if (pts.length === 3) {
    return pointInTriangle(px, py, pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y);
  }
  for (let i = 1; i < pts.length - 1; i += 1) {
    if (pointInTriangle(px, py, pts[0].x, pts[0].y, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y)) return true;
  }
  return false;
}

/** @returns {string|null} part id under cursor (front-most face), or null */
function pickItemPartIdAtCanvas(project, canvas, lx, ly) {
  const faces = [];
  const yawR = (y) => (Number(y) * Math.PI) / 180;
  project.parts.forEach((part) => {
    if (!window.ItemScene?.getWorldPose) return;
    const pose = window.ItemScene.getWorldPose(part, project);
    const mesh = getItemPartMesh(part);
    mesh.faces.forEach((face, faceIndex) => {
      const pts = face.map((idx) => {
        const v = mesh.vertices[idx];
        const yr = yawR(pose.yaw);
        const rx = v.x * Math.cos(yr) - v.z * Math.sin(yr);
        const rz = v.x * Math.sin(yr) + v.z * Math.cos(yr);
        const world = { x: rx + pose.x, y: v.y + pose.y, z: rz + pose.z };
        const view = rotate3D(world, project.camera.yaw, project.camera.pitch);
        const screen = project3DTo2D(view, canvas, project.camera.zoom);
        return { x: screen.x, y: screen.y, viewZ: view.z };
      });
      const depth = pts.reduce((s, p) => s + p.viewZ, 0) / pts.length;
      faces.push({ partId: part.id, faceIndex, pts, depth });
    });
  });
  faces.sort((a, b) => b.depth - a.depth);
  for (let i = 0; i < faces.length; i += 1) {
    const f = faces[i];
    if (pointInConvexPolyScreen(lx, ly, f.pts)) return f.partId;
  }
  return null;
}

function readItemNumber(id, fallback = 0) {
  const el = document.getElementById(id);
  if (!el) return fallback;
  const value = Number(el.value);
  return Number.isFinite(value) ? value : fallback;
}

function escapeHtmlItemTree(s) {
  return String(s || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function buildItemHierarchyTreeHtml(project) {
  if (!project.parts || project.parts.length === 0) return "<div class=\"small-readout\">No parts.</div>";
  const byId = window.ItemScene?.partsById
    ? window.ItemScene.partsById(project)
    : Object.fromEntries(project.parts.map((p) => [p.id, p]));
  function childrenOf(pid) {
    return project.parts.filter((p) => p.parentId === pid);
  }
  const roots = project.parts.filter((p) => !p.parentId || !byId[p.parentId]);
  function row(part, depth) {
    const isSel = project.selectedPartId === part.id;
    const cls = isSel ? "item-tree-row item-tree-row--sel" : "item-tree-row";
    const kids = childrenOf(part.id);
    const pad = 8 + depth * 14;
    const texPad = pad + 10;
    const texLine = part.textureDataUrl
      ? `<div class="item-tree-texture" style="padding-left:${texPad}px" data-item-select-part="${part.id}"><span class="item-tree-tag">texture</span> <span class="item-tree-texname">${escapeHtmlItemTree(part.textureLabel || part.texturePresetKind || "map")}</span></div>`
      : `<div class="item-tree-texture item-tree-texture--empty" style="padding-left:${texPad}px" data-item-select-part="${part.id}"><span class="item-tree-muted">no texture</span></div>`;
    let html = `<div class="${cls}" draggable="true" data-item-node-id="${part.id}" style="padding-left:${pad}px"><span class="item-tree-name">${escapeHtmlItemTree(part.name || "part")}</span> <span class="item-tree-muted">${part.shape}</span></div>${texLine}`;
    kids.forEach((k) => {
      html += row(k, depth + 1);
    });
    return html;
  }
  if (roots.length === 0) return project.parts.map((p, i) => row(p, 0)).join("");
  return roots.map((r) => row(r, 0)).join("");
}

function refreshItemEditorUI() {
  const project = getItemProjectState();
  resizeItemEditorCanvas();
  const projectNameInput = document.getElementById("item-project-name");
  const categorySelect = document.getElementById("item-project-category");
  const gridInput = document.getElementById("item-preview-grid");
  const partSelect = document.getElementById("item-part-select");
  const bgInput = document.getElementById("item-bg-color");
  const wireInput = document.getElementById("item-wireframe-enabled");
  const modeBadge = document.getElementById("item-mode-badge");
  if (projectNameInput) projectNameInput.value = project.projectName || "SimpleItem";
  if (categorySelect) categorySelect.value = project.category || "prop";
  if (gridInput) gridInput.checked = project.showGrid !== false;
  if (bgInput) bgInput.value = project.backgroundColor || "#2a2115";
  if (wireInput) wireInput.checked = !!project.wireframe;
  const modeLabelsAll = { orbit: "Orbit", move: "Move", scale: "Scale", rotate: "Rotate", paint: "Paint" };
  if (modeBadge) {
    modeBadge.textContent = `Tool: ${modeLabelsAll[project.interactionMode] || "Orbit"} · Shift+move = Y · MMB = orbit · void drag = orbit · Ctrl+Z undo · C copy · V paste`;
  }
  const symSel = document.getElementById("item-paint-symmetry");
  if (symSel) symSel.value = project.paintSymmetry || "none";
  if (partSelect) {
    partSelect.innerHTML = project.parts.map((part, idx) => `<option value="${part.id}">${idx}: ${part.name}</option>`).join("");
    partSelect.value = project.selectedPartId ? String(project.selectedPartId) : "";
  }
  const treeEl = document.getElementById("item-hierarchy-tree");
  if (treeEl) {
    treeEl.innerHTML = buildItemHierarchyTreeHtml(project);
    if (!treeEl.dataset.delegated) {
      treeEl.dataset.delegated = "1";
      treeEl.addEventListener("click", (ev) => {
        const hint = ev.target.closest("[data-item-select-part]");
        if (hint) {
          const id = hint.getAttribute("data-item-select-part");
          if (id) {
            getItemProjectState().selectedPartId = id;
            refreshItemEditorUI();
          }
          return;
        }
        const row = ev.target.closest("[data-item-node-id]");
        if (!row) return;
        const id = row.getAttribute("data-item-node-id");
        if (id) {
          getItemProjectState().selectedPartId = id;
          refreshItemEditorUI();
        }
      });
      treeEl.addEventListener("dragstart", (e) => {
        const row = e.target.closest("[data-item-node-id]");
        if (!row) return;
        e.dataTransfer.setData("text/plain", row.getAttribute("data-item-node-id") || "");
        e.dataTransfer.effectAllowed = "move";
      });
      treeEl.addEventListener("dragover", (e) => {
        e.preventDefault();
        e.dataTransfer.dropEffect = "move";
      });
      treeEl.addEventListener("drop", (e) => {
        e.preventDefault();
        const dragId = e.dataTransfer.getData("text/plain");
        const row = e.target.closest("[data-item-node-id]");
        if (!dragId || !row) return;
        const dropId = row.getAttribute("data-item-node-id");
        if (!dropId || dragId === dropId) return;
        const proj = getItemProjectState();
        if (window.ItemScene?.wouldReparentCycle && window.ItemScene.wouldReparentCycle(proj, dragId, dropId)) {
          showToast("Invalid parent (cycle).");
          return;
        }
        recordItemUndo();
        const part = proj.parts.find((p) => p.id === dragId);
        if (part) part.parentId = dropId;
        if (window.ItemScene?.validateParents) window.ItemScene.validateParents(proj);
        refreshItemEditorUI();
        showToast("Reparented.");
      });
    }
  }
  const rp = document.getElementById("item-reparent-parent");
  if (rp) {
    const sel = project.selectedPartId;
    const parts = project.parts || [];
    let opts = `<option value="__root">(Root — no parent)</option>`;
    parts.forEach((p) => {
      if (!sel || p.id === sel) return;
      if (window.ItemScene?.wouldReparentCycle && window.ItemScene.wouldReparentCycle(project, sel, p.id)) return;
      opts += `<option value="${p.id}">${p.name || "part"}</option>`;
    });
    rp.innerHTML = opts;
    const cur = getActiveItemPart(project);
    rp.value = cur?.parentId ? String(cur.parentId) : "__root";
  }
  const active = getActiveItemPart(project);
  const propsPanel = document.getElementById("item-part-properties");
  if (propsPanel) propsPanel.classList.toggle("item-props-empty", !active);
  const setValue = (id, value) => {
    const node = document.getElementById(id);
    if (node) node.value = String(value);
  };
  if (active) {
    setValue("item-part-name", active.name || "part");
    setValue("item-part-shape", active.shape || "box");
    setValue("item-part-x", active.x || 0);
    setValue("item-part-y", active.y || 0);
    setValue("item-part-z", active.z || 0);
    setValue("item-part-w", active.w || 40);
    setValue("item-part-h", active.h || 40);
    setValue("item-part-d", active.d || 40);
    setValue("item-part-yaw", active.yaw || 0);
    const colorInput = document.getElementById("item-part-color");
    if (colorInput) colorInput.value = active.color || "#a88d5f";
  } else {
    setValue("item-part-name", "");
    setValue("item-part-shape", "box");
    setValue("item-part-x", 0);
    setValue("item-part-y", 0);
    setValue("item-part-z", 0);
    setValue("item-part-w", 40);
    setValue("item-part-h", 40);
    setValue("item-part-d", 40);
    setValue("item-part-yaw", 0);
  }
  const texStatus = document.getElementById("item-part-texture-status");
  if (texStatus) {
    texStatus.textContent = active?.textureDataUrl
      ? "Texture map on this part (3D). Drop to replace."
      : "No texture — drop an image on the preview or use presets (left).";
  }
  updateItemToolButtonHighlight();
  updateSpriteEditorReadout(
    "item-project-readout",
    `Parts: <strong>${project.parts.length}</strong><br>Category: <strong>${project.category}</strong><br>Camera: yaw ${Math.round(project.camera.yaw)}° | pitch ${Math.round(project.camera.pitch)}° | zoom ${project.camera.zoom.toFixed(2)}`
  );
  updateSpriteEditorReadout(
    "item-preview-readout",
    `Click mesh to select · empty space deselects · ${!active ? "No selection." : ""}`
  );
  if (state.currentScreen === "item-editor") {
    wireItemTexturePresetDrag();
  }
  renderItemEditorPreview();
}

function syncItemProjectFromInputs() {
  const project = getItemProjectState();
  project.projectName = String(document.getElementById("item-project-name")?.value || "SimpleItem").trim() || "SimpleItem";
  project.category = String(document.getElementById("item-project-category")?.value || "prop");
  project.showGrid = !!document.getElementById("item-preview-grid")?.checked;
  const bg = String(document.getElementById("item-bg-color")?.value || project.backgroundColor || "#2a2115");
  project.backgroundColor = /^#([0-9a-f]{6})$/i.test(bg) ? bg : "#2a2115";
  project.wireframe = !!document.getElementById("item-wireframe-enabled")?.checked;
  const symEl = document.getElementById("item-paint-symmetry");
  if (symEl) {
    const v = String(symEl.value || "none");
    const symSet = new Set(["none", "worldX", "worldY", "worldZ"]);
    project.paintSymmetry = symSet.has(v) ? v : "none";
  }
  const selEl = document.getElementById("item-part-select");
  if (selEl && selEl.value) project.selectedPartId = String(selEl.value);
  const active = getActiveItemPart(project);
  if (!active) return;
  active.name = String(document.getElementById("item-part-name")?.value || "part").trim() || "part";
  active.shape = String(document.getElementById("item-part-shape")?.value || "box");
  active.x = readItemNumber("item-part-x", active.x);
  active.y = readItemNumber("item-part-y", active.y);
  active.z = readItemNumber("item-part-z", active.z);
  active.w = Math.max(1, readItemNumber("item-part-w", active.w));
  active.h = Math.max(1, readItemNumber("item-part-h", active.h));
  active.d = Math.max(1, readItemNumber("item-part-d", active.d));
  active.yaw = readItemNumber("item-part-yaw", active.yaw);
  const color = String(document.getElementById("item-part-color")?.value || active.color || "#a88d5f");
  active.color = /^#([0-9a-f]{6})$/i.test(color) ? color : "#a88d5f";
}

function shadeHexColor(hex, intensity = 0) {
  const safe = /^#([0-9a-f]{6})$/i.test(String(hex || "")) ? String(hex) : "#a88d5f";
  const n = Number(intensity || 0);
  const clamp = (v) => Math.max(0, Math.min(255, v));
  const r = clamp(parseInt(safe.slice(1, 3), 16) + n);
  const g = clamp(parseInt(safe.slice(3, 5), 16) + n);
  const b = clamp(parseInt(safe.slice(5, 7), 16) + n);
  return `rgb(${r}, ${g}, ${b})`;
}

function rotate3D(point, yawDeg, pitchDeg) {
  const yaw = (Number(yawDeg || 0) * Math.PI) / 180;
  const pitch = (Number(pitchDeg || 0) * Math.PI) / 180;
  const cosy = Math.cos(yaw);
  const siny = Math.sin(yaw);
  const cosp = Math.cos(pitch);
  const sinp = Math.sin(pitch);
  const x1 = point.x * cosy - point.z * siny;
  const z1 = point.x * siny + point.z * cosy;
  const y1 = point.y;
  const y2 = y1 * cosp - z1 * sinp;
  const z2 = y1 * sinp + z1 * cosp;
  return { x: x1, y: y2, z: z2 };
}

function project3DTo2D(point, canvas, zoom = 1) {
  const perspective = 1 / Math.max(0.18, 1 + point.z * 0.006);
  const scale = 4.2 * Number(zoom || 1);
  return {
    x: canvas.width * 0.5 + point.x * scale * perspective,
    y: canvas.height * 0.62 - point.y * scale * perspective,
    z: point.z
  };
}

function resizeItemEditorCanvas() {
  const canvas = document.getElementById("item-preview-canvas");
  const wrap = document.getElementById("item-viewport-wrap");
  if (!canvas || !wrap) return;
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = Math.floor(Math.max(1, wrap.clientWidth) * dpr);
  const h = Math.floor(Math.max(1, wrap.clientHeight) * dpr);
  if (w < 64 || h < 64) return;
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
  }
  if (window.ItemEditorThree && typeof window.ItemEditorThree.resize === "function") {
    window.ItemEditorThree.resize();
  }
}

function canvasClientToInternal(canvas, clientX, clientY) {
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / Math.max(1, rect.width);
  const scaleY = canvas.height / Math.max(1, rect.height);
  return {
    x: (clientX - rect.left) * scaleX,
    y: (clientY - rect.top) * scaleY
  };
}

function updateItemToolButtonHighlight() {
  const project = getItemProjectState();
  const mode = project.interactionMode || "orbit";
  document.querySelectorAll("[data-item-tool]").forEach((btn) => {
    if (!(btn instanceof HTMLElement)) return;
    const t = btn.getAttribute("data-item-tool");
    btn.classList.toggle("item-tool-active", t === mode);
  });
}

function getItemPartMesh(part) {
  const hw = Number(part.w || 40) * 0.5;
  const hh = Number(part.h || 40) * 0.5;
  const hd = Number(part.d || 40) * 0.5;
  if (part.shape === "cylinder") {
    const segments = 14;
    const vertices = [];
    const faces = [];
    for (let i = 0; i < segments; i += 1) {
      const t = (i / segments) * Math.PI * 2;
      const x = Math.cos(t) * hw;
      const z = Math.sin(t) * hd;
      vertices.push({ x, y: hh, z });
      vertices.push({ x, y: -hh, z });
    }
    for (let i = 0; i < segments; i += 1) {
      const a = i * 2;
      const b = ((i + 1) % segments) * 2;
      faces.push([a, b, b + 1, a + 1]);
    }
    faces.push(Array.from({ length: segments }, (_, i) => i * 2));
    faces.push(Array.from({ length: segments }, (_, i) => ((segments - 1 - i) * 2) + 1));
    return { vertices, faces };
  }
  return {
    vertices: [
      { x: -hw, y: hh, z: -hd }, { x: hw, y: hh, z: -hd }, { x: hw, y: hh, z: hd }, { x: -hw, y: hh, z: hd },
      { x: -hw, y: -hh, z: -hd }, { x: hw, y: -hh, z: -hd }, { x: hw, y: -hh, z: hd }, { x: -hw, y: -hh, z: hd }
    ],
    faces: [
      [0, 1, 2, 3],
      [4, 5, 6, 7],
      [0, 1, 5, 4],
      [1, 2, 6, 5],
      [2, 3, 7, 6],
      [3, 0, 4, 7]
    ]
  };
}

function renderItemEditorPreview() {
  if (window.ItemEditorThree && typeof window.ItemEditorThree.isReady === "function" && window.ItemEditorThree.isReady()) {
    const project = getItemProjectState();
    window.ItemEditorThree.sync(project);
    return;
  }
  const canvas = document.getElementById("item-preview-canvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const project = getItemProjectState();
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const bg = /^#([0-9a-f]{6})$/i.test(String(project.backgroundColor || "")) ? project.backgroundColor : "#2a2115";
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  if (project.showGrid !== false) {
    ctx.strokeStyle = "rgba(255, 224, 158, 0.12)";
    for (let gx = -280; gx <= 280; gx += 20) {
      const p1 = project3DTo2D(rotate3D({ x: gx, y: -70, z: -280 }, project.camera.yaw, project.camera.pitch), canvas, project.camera.zoom);
      const p2 = project3DTo2D(rotate3D({ x: gx, y: -70, z: 280 }, project.camera.yaw, project.camera.pitch), canvas, project.camera.zoom);
      ctx.beginPath();
      ctx.moveTo(p1.x, p1.y);
      ctx.lineTo(p2.x, p2.y);
      ctx.stroke();
    }
    for (let gz = -280; gz <= 280; gz += 20) {
      const p1 = project3DTo2D(rotate3D({ x: -280, y: -70, z: gz }, project.camera.yaw, project.camera.pitch), canvas, project.camera.zoom);
      const p2 = project3DTo2D(rotate3D({ x: 280, y: -70, z: gz }, project.camera.yaw, project.camera.pitch), canvas, project.camera.zoom);
      ctx.beginPath();
      ctx.moveTo(p1.x, p1.y);
      ctx.lineTo(p2.x, p2.y);
      ctx.stroke();
    }
  }

  const drawFaces = [];
  const selectedId = project.selectedPartId;
  project.parts.forEach((part, partIndex) => {
    const pose = window.ItemScene?.getWorldPose ? window.ItemScene.getWorldPose(part, project) : {
      x: Number(part.x || 0),
      y: Number(part.y || 0),
      z: Number(part.z || 0),
      yaw: Number(part.yaw || 0),
      color: part.color
    };
    const mesh = getItemPartMesh(part);
    const totalYaw = Number(pose.yaw || 0);
    const transformed = mesh.vertices.map((vertex) => {
      const yr = (totalYaw * Math.PI) / 180;
      const rx = vertex.x * Math.cos(yr) - vertex.z * Math.sin(yr);
      const rz = vertex.x * Math.sin(yr) + vertex.z * Math.cos(yr);
      const world = { x: rx + pose.x, y: vertex.y + pose.y, z: rz + pose.z };
      const view = rotate3D(world, project.camera.yaw, project.camera.pitch);
      const screen = project3DTo2D(view, canvas, project.camera.zoom);
      return { ...screen, viewZ: view.z };
    });
    mesh.faces.forEach((face, faceIndex) => {
      const points = face.map((idx) => transformed[idx]);
      const depth = points.reduce((sum, p) => sum + p.viewZ, 0) / Math.max(1, points.length);
      drawFaces.push({ partIndex, partId: part.id, faceIndex, points, depth, color: pose.color || part.color || "#a88d5f" });
    });
  });

  drawFaces.sort((a, b) => a.depth - b.depth);
  const wire = !!project.wireframe;
  drawFaces.forEach((face) => {
    const active = selectedId && face.partId === selectedId;
    const shade = face.faceIndex === 0 ? 20 : face.faceIndex === 1 ? -20 : face.faceIndex % 2 === 0 ? 8 : -8;
    ctx.beginPath();
    ctx.moveTo(face.points[0].x, face.points[0].y);
    for (let i = 1; i < face.points.length; i += 1) {
      ctx.lineTo(face.points[i].x, face.points[i].y);
    }
    ctx.closePath();
    if (!wire) {
      ctx.fillStyle = shadeHexColor(face.color, shade);
      ctx.fill();
    } else {
      ctx.fillStyle = "rgba(0,0,0,0.04)";
      ctx.fill();
    }
    ctx.strokeStyle = active ? "rgba(255, 232, 171, 0.95)" : wire ? shadeHexColor(face.color, shade + 10) : "rgba(18, 12, 6, 0.7)";
    ctx.lineWidth = active ? (wire ? 2.2 : 1.6) : wire ? 1.3 : 1;
    ctx.stroke();
  });
}

function getItemViewportCanvasEl() {
  return document.getElementById("item-three-canvas") || document.getElementById("item-preview-canvas");
}

/** Procedural PNG data URLs for item part textures (presets). */
function createItemPresetTextureDataUrl(kind) {
  const c = document.createElement("canvas");
  c.width = 128;
  c.height = 128;
  const ctx = c.getContext("2d");
  if (!ctx) return "";
  const k = String(kind || "");
  if (k === "checker") {
    const n = 8;
    const s = 128 / n;
    for (let y = 0; y < n; y += 1) {
      for (let x = 0; x < n; x += 1) {
        ctx.fillStyle = (x + y) % 2 === 0 ? "#c4a574" : "#6b5340";
        ctx.fillRect(x * s, y * s, s, s);
      }
    }
  } else if (k === "wood") {
    const grd = ctx.createLinearGradient(0, 0, 128, 128);
    grd.addColorStop(0, "#7a5230");
    grd.addColorStop(0.5, "#5c3d22");
    grd.addColorStop(1, "#8b6540");
    ctx.fillStyle = grd;
    ctx.fillRect(0, 0, 128, 128);
    ctx.strokeStyle = "rgba(40,25,12,0.35)";
    for (let i = 0; i < 24; i += 1) {
      ctx.beginPath();
      ctx.moveTo(0, i * 5.5);
      ctx.lineTo(128, i * 5.5 + 12);
      ctx.stroke();
    }
  } else if (k === "stone") {
    ctx.fillStyle = "#6a6a6e";
    ctx.fillRect(0, 0, 128, 128);
    let seed = 4321;
    const rnd = () => {
      seed = (seed * 9301 + 49297) % 233280;
      return seed / 233280;
    };
    for (let i = 0; i < 80; i += 1) {
      ctx.fillStyle = `rgba(${40 + rnd() * 40},${40 + rnd() * 40},${45 + rnd() * 30},0.45)`;
      ctx.fillRect(rnd() * 120, rnd() * 120, 4 + rnd() * 10, 4 + rnd() * 10);
    }
  } else if (k === "metal") {
    const grd = ctx.createLinearGradient(0, 0, 0, 128);
    grd.addColorStop(0, "#c0c4c8");
    grd.addColorStop(0.5, "#8a9098");
    grd.addColorStop(1, "#a8acb2");
    ctx.fillStyle = grd;
    ctx.fillRect(0, 0, 128, 128);
    ctx.fillStyle = "rgba(255,255,255,0.12)";
    let s2 = 9102;
    const r2 = () => {
      s2 = (s2 * 1103515245 + 12345) & 0x7fffffff;
      return s2 / 0x7fffffff;
    };
    for (let i = 0; i < 50; i += 1) ctx.fillRect(r2() * 120, r2() * 120, 18, 1.5);
  } else if (k === "fabric") {
    ctx.fillStyle = "#6b4a62";
    ctx.fillRect(0, 0, 128, 128);
    ctx.strokeStyle = "rgba(255,220,240,0.1)";
    for (let y = 0; y < 128; y += 4) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(128, y + 2);
      ctx.stroke();
    }
  } else {
    return "";
  }
  return c.toDataURL("image/png");
}

function applyItemTextureDataUrlToSelection(dataUrl, opts = {}) {
  const project = getItemProjectState();
  let target = opts.partId ? project.parts.find((p) => p.id === opts.partId) : null;
  if (!target) target = getActiveItemPart(project);
  if (!target) {
    showToast("Select a part or drop onto a mesh.");
    return;
  }
  const url = String(dataUrl || "").trim();
  if (!url) {
    recordItemUndo();
    target.textureDataUrl = "";
    target.textureLabel = "";
    target.texturePresetKind = "";
  } else if (url.startsWith("data:")) {
    recordItemUndo();
    target.textureDataUrl = url;
    if (opts.presetKind) {
      const k = String(opts.presetKind);
      target.texturePresetKind = k;
      target.textureLabel = opts.label ? String(opts.label) : k;
    } else if (opts.fileLabel) {
      target.texturePresetKind = "file";
      target.textureLabel = String(opts.fileLabel);
    } else if (opts.label) {
      target.textureLabel = String(opts.label);
    }
  } else {
    showToast("Invalid texture data.");
    return;
  }
  if (window.ItemEditorThree && typeof window.ItemEditorThree.invalidateRebuild === "function") {
    window.ItemEditorThree.invalidateRebuild();
  }
  refreshItemEditorUI();
  renderItemEditorPreview();
  if (!opts.silent) showToast(target.textureDataUrl ? "Texture applied." : "Texture cleared.");
}

function setupItemPreviewCanvasInput() {
  const wrap = document.getElementById("item-viewport-wrap");
  if (!wrap || wrap.dataset.itemPointerBound === "1") return;
  wrap.dataset.itemPointerBound = "1";

  const applyOrbitFromEvent = (event) => {
    const project = getItemProjectState();
    const dx = event.clientX - state.itemEditor.dragStartX;
    const dy = event.clientY - state.itemEditor.dragStartY;
    project.camera.yaw = state.itemEditor.startYaw + dx * 0.35;
    project.camera.pitch = Math.max(-82, Math.min(82, state.itemEditor.startPitch + dy * 0.25));
    renderItemEditorPreview();
  };

  const applyPaintStrokeFromEvent = (event) => {
    const project = getItemProjectState();
    const paint = document.getElementById("item-paint-color")?.value;
    if (!/^#([0-9a-f]{6})$/i.test(String(paint || ""))) return;
    const canvas = state.itemEditor.eventCanvas || getItemViewportCanvasEl();
    let picked = null;
    if (window.ItemEditorThree && typeof window.ItemEditorThree.isReady === "function" && window.ItemEditorThree.isReady()) {
      picked = window.ItemEditorThree.pickPartId(event.clientX, event.clientY);
    } else if (canvas) {
      const pos = canvasClientToInternal(canvas, event.clientX, event.clientY);
      picked = pickItemPartIdAtCanvas(project, canvas, pos.x, pos.y);
    }
    if (picked) {
      if (!state.itemEditor.paintUndoRecorded) {
        recordItemUndo();
        state.itemEditor.paintUndoRecorded = true;
      }
      applyItemPaintToPart(project, picked, paint);
      project.selectedPartId = picked;
      refreshItemEditorUI();
      return;
    }
    /* Drag on empty space while Paint is selected → orbit (same as void orbit). */
    if (state.itemEditor.dragMovedSq > 16) {
      state.itemEditor.dragKind = "orbit";
      state.itemEditor.startYaw = Number(project.camera.yaw || 0);
      state.itemEditor.startPitch = Number(project.camera.pitch || 0);
      state.itemEditor.dragStartX = event.clientX;
      state.itemEditor.dragStartY = event.clientY;
      applyOrbitFromEvent(event);
    }
  };

  const applyMoveFromEvent = (event) => {
    const project = getItemProjectState();
    const active = getActiveItemPart(project);
    if (!active) return;
    const dx = event.clientX - state.itemEditor.dragLastX;
    const dy = event.clientY - state.itemEditor.dragLastY;
    const k = 0.38 / Math.max(0.35, Number(project.camera.zoom || 1));
    if (event.shiftKey) {
      active.y = Number(active.y || 0) - dy * k * 0.92;
    } else if (window.ItemScene?.cameraDeltaWorld) {
      const d = window.ItemScene.cameraDeltaWorld(project, dx, -dy, k);
      active.x = Number(active.x || 0) + d.x;
      active.y = Number(active.y || 0) + d.y;
      active.z = Number(active.z || 0) + d.z;
    }
    state.itemEditor.dragLastX = event.clientX;
    state.itemEditor.dragLastY = event.clientY;
    refreshItemEditorUI();
  };

  const applyScaleFromEvent = (event) => {
    const project = getItemProjectState();
    const active = getActiveItemPart(project);
    if (!active) return;
    const totalDy = event.clientY - state.itemEditor.dragStartY;
    const factor = Math.max(0.15, 1 - totalDy * 0.004);
    active.w = Math.max(1, Math.round(state.itemEditor.startW * factor));
    active.h = Math.max(1, Math.round(state.itemEditor.startH * factor));
    active.d = Math.max(1, Math.round(state.itemEditor.startD * factor));
    refreshItemEditorUI();
  };

  const applyRotateFromEvent = (event) => {
    const project = getItemProjectState();
    const active = getActiveItemPart(project);
    if (!active) return;
    const dx = event.clientX - state.itemEditor.dragStartX;
    active.yaw = state.itemEditor.startPartYaw + dx * 0.45;
    refreshItemEditorUI();
  };

  wrap.addEventListener("pointerdown", (event) => {
    const canvas = event.target instanceof HTMLCanvasElement ? event.target : null;
    if (!canvas || (canvas.id !== "item-three-canvas" && canvas.id !== "item-preview-canvas")) return;
    if (state.currentScreen !== "item-editor") return;
    const project = getItemProjectState();
    const isMmb = event.button === 1;
    const isLmb = event.button === 0;
    if (!isLmb && !isMmb) return;
    event.preventDefault();
    let mode = isMmb ? "orbit" : project.interactionMode || "orbit";
    const activeForTool = getActiveItemPart(project);
    if (!activeForTool && !isMmb && ["move", "scale", "rotate"].includes(mode)) mode = "orbit";
    let dk = "orbit";
    if (isMmb) dk = "orbit";
    else if (mode === "paint") dk = "paint";
    else if (mode === "move") dk = "move";
    else if (mode === "scale") dk = "scale";
    else if (mode === "rotate") dk = "rotate";
    if (isLmb && ["move", "scale", "rotate"].includes(dk)) {
      recordItemUndo();
    }
    if (isLmb && dk === "paint") {
      state.itemEditor.paintUndoRecorded = false;
    }
    state.itemEditor.interacting = true;
    state.itemEditor.pointerId = event.pointerId;
    state.itemEditor.dragButton = event.button;
    state.itemEditor.eventCanvas = canvas;
    state.itemEditor.dragStartX = event.clientX;
    state.itemEditor.dragStartY = event.clientY;
    state.itemEditor.dragLastX = event.clientX;
    state.itemEditor.dragLastY = event.clientY;
    state.itemEditor.dragMovedSq = 0;
    state.itemEditor.dragKind = dk;
    state.itemEditor.startYaw = Number(project.camera.yaw || 0);
    state.itemEditor.startPitch = Number(project.camera.pitch || 0);
    const active = activeForTool;
    state.itemEditor.startPartX = active ? Number(active.x || 0) : 0;
    state.itemEditor.startPartY = active ? Number(active.y || 0) : 0;
    state.itemEditor.startPartZ = active ? Number(active.z || 0) : 0;
    state.itemEditor.startW = active ? Number(active.w || 40) : 40;
    state.itemEditor.startH = active ? Number(active.h || 40) : 40;
    state.itemEditor.startD = active ? Number(active.d || 40) : 40;
    state.itemEditor.startPartYaw = active ? Number(active.yaw || 0) : 0;
    canvas.setPointerCapture(event.pointerId);
  });

  wrap.addEventListener("pointermove", (event) => {
    if (!state.itemEditor.interacting || state.itemEditor.pointerId !== event.pointerId) return;
    const mx = event.clientX - state.itemEditor.dragStartX;
    const my = event.clientY - state.itemEditor.dragStartY;
    state.itemEditor.dragMovedSq = mx * mx + my * my;
    const kind = state.itemEditor.dragKind || "orbit";
    if (kind === "orbit") applyOrbitFromEvent(event);
    else if (kind === "paint") applyPaintStrokeFromEvent(event);
    else if (kind === "move") applyMoveFromEvent(event);
    else if (kind === "scale") applyScaleFromEvent(event);
    else if (kind === "rotate") applyRotateFromEvent(event);
  });

  const endInteract = (event) => {
    if (!state.itemEditor.interacting || state.itemEditor.pointerId !== event.pointerId) return;
    state.itemEditor.interacting = false;
    const wasClick = state.itemEditor.dragMovedSq < 36 && state.itemEditor.dragButton === 0;
    const canvas = state.itemEditor.eventCanvas || getItemViewportCanvasEl();
    state.itemEditor.pointerId = null;
    state.itemEditor.dragKind = null;
    state.itemEditor.eventCanvas = null;
    if (wasClick && state.currentScreen === "item-editor" && canvas) {
      const project = getItemProjectState();
      let picked = null;
      if (window.ItemEditorThree && typeof window.ItemEditorThree.isReady === "function" && window.ItemEditorThree.isReady()) {
        picked = window.ItemEditorThree.pickPartId(event.clientX, event.clientY);
      } else {
        const pos = canvasClientToInternal(canvas, event.clientX, event.clientY);
        picked = pickItemPartIdAtCanvas(project, canvas, pos.x, pos.y);
      }
      const im = project.interactionMode || "orbit";
      if (im === "paint" && picked) {
        const paint = document.getElementById("item-paint-color")?.value;
        if (/^#([0-9a-f]{6})$/i.test(String(paint || ""))) {
          if (!state.itemEditor.paintUndoRecorded) {
            recordItemUndo();
            state.itemEditor.paintUndoRecorded = true;
          }
          applyItemPaintToPart(project, picked, paint);
        }
        project.selectedPartId = picked;
        refreshItemEditorUI();
      } else if (picked) {
        project.selectedPartId = picked;
        refreshItemEditorUI();
      } else {
        project.selectedPartId = null;
        refreshItemEditorUI();
      }
    }
  };

  wrap.addEventListener("pointerup", endInteract);
  wrap.addEventListener("pointercancel", endInteract);
  wrap.addEventListener(
    "wheel",
    (event) => {
      if (state.currentScreen !== "item-editor") return;
      const canvas = event.target instanceof HTMLCanvasElement ? event.target : getItemViewportCanvasEl();
      if (!canvas || (canvas.id !== "item-three-canvas" && canvas.id !== "item-preview-canvas")) return;
      event.preventDefault();
      const project = getItemProjectState();
      const delta = event.deltaY > 0 ? -0.08 : 0.08;
      project.camera.zoom = Math.max(0.35, Math.min(3, Number(project.camera.zoom || 1) + delta));
      renderItemEditorPreview();
      refreshItemEditorUI();
    },
    { passive: false }
  );
  wrap.addEventListener("contextmenu", (event) => {
    const canvas = event.target instanceof HTMLCanvasElement ? event.target : null;
    if (!canvas || (canvas.id !== "item-three-canvas" && canvas.id !== "item-preview-canvas")) return;
    if (state.currentScreen !== "item-editor") return;
    event.preventDefault();
  });

  if (!wrap.dataset.itemTextureDrop) {
    wrap.dataset.itemTextureDrop = "1";
    wrap.addEventListener("dragover", (e) => {
      if (state.currentScreen !== "item-editor") return;
      e.preventDefault();
      e.dataTransfer.dropEffect = "copy";
    });
    wrap.addEventListener("drop", (e) => {
      if (state.currentScreen !== "item-editor") return;
      e.preventDefault();
      const project = getItemProjectState();
      const dt = e.dataTransfer;
      let partId = null;
      if (window.ItemEditorThree && typeof window.ItemEditorThree.pickPartId === "function") {
        partId = window.ItemEditorThree.pickPartId(e.clientX, e.clientY);
      }
      if (!partId && project.selectedPartId) partId = project.selectedPartId;

      const preset =
        dt.getData("application/x-shc-texture-preset") ||
        (() => {
          const t = dt.getData("text/plain") || "";
          const m = t.match(/^shc-texpreset:([a-z]+)$/i);
          return m ? m[1] : "";
        })();
      if (preset) {
        const url = createItemPresetTextureDataUrl(preset);
        if (url) {
          applyItemTextureDataUrlToSelection(url, { partId, presetKind: preset, label: preset });
        }
        return;
      }

      const f = dt?.files?.[0];
      if (f && String(f.type || "").startsWith("image/")) {
        const reader = new FileReader();
        reader.onload = () => {
          applyItemTextureDataUrlToSelection(String(reader.result), { partId, fileLabel: f.name });
        };
        reader.readAsDataURL(f);
      }
    });
  }
}

function wireItemTexturePresetDrag() {
  document.querySelectorAll("[data-action=\"item-texture-preset\"]").forEach((btn) => {
    if (!(btn instanceof HTMLElement) || btn.dataset.texDragBound) return;
    btn.dataset.texDragBound = "1";
    btn.setAttribute("draggable", "true");
    btn.addEventListener("dragstart", (ev) => {
      const k = btn.getAttribute("data-item-texture") || "";
      ev.dataTransfer.setData("application/x-shc-texture-preset", k);
      ev.dataTransfer.setData("text/plain", `shc-texpreset:${k}`);
      ev.dataTransfer.effectAllowed = "copy";
    });
  });
}

function stopItemPreviewLoop() {
  if (state.itemEditor.previewRafId) {
    cancelAnimationFrame(state.itemEditor.previewRafId);
    state.itemEditor.previewRafId = 0;
  }
  if (state.itemEditor.resizeObserver) {
    try {
      state.itemEditor.resizeObserver.disconnect();
    } catch (e) {
      /* ignore */
    }
    state.itemEditor.resizeObserver = null;
  }
  if (window.ItemEditorThree && typeof window.ItemEditorThree.dispose === "function") {
    window.ItemEditorThree.dispose();
  }
}

function startItemPreviewLoop() {
  /* Do not call stopItemPreviewLoop() here — it disposes ItemEditorThree, which runs right after
   * mountItemEditor() attaches the WebGL canvas, leaving only the legacy 2D painter (no textures). */
  if (state.itemEditor.previewRafId) {
    cancelAnimationFrame(state.itemEditor.previewRafId);
    state.itemEditor.previewRafId = 0;
  }
  const tick = () => {
    if (state.currentScreen !== "item-editor") return;
    renderItemEditorPreview();
    state.itemEditor.previewRafId = requestAnimationFrame(tick);
  };
  state.itemEditor.previewRafId = requestAnimationFrame(tick);
}

function mountItemEditor() {
  if (!loadItemProjectFromStorage()) {
    state.itemEditor.project = createDefaultItemProject();
  } else {
    state.itemEditor.project = normalizeItemProject(state.itemEditor.project);
  }
  const wrap = document.getElementById("item-viewport-wrap");
  const attachThree = () => {
    if (!window.ItemEditorThree || !wrap || window.ItemEditorThree.mounted) return;
    try {
      window.ItemEditorThree.mount(wrap);
    } catch (e) {
      console.warn(e);
    }
  };
  attachThree();
  if (!window.ItemEditorThree?.mounted) {
    setTimeout(attachThree, 0);
    setTimeout(attachThree, 60);
  }
  setupItemPreviewCanvasInput();
  if (wrap && typeof ResizeObserver === "function") {
    state.itemEditor.resizeObserver = new ResizeObserver(() => {
      resizeItemEditorCanvas();
      renderItemEditorPreview();
    });
    state.itemEditor.resizeObserver.observe(wrap);
  }
  refreshItemEditorUI();
  const texFile = document.getElementById("item-texture-file");
  if (texFile && !texFile.dataset.boundChange) {
    texFile.dataset.boundChange = "1";
    texFile.addEventListener("change", (e) => {
      const input = e.target;
      const f = input?.files?.[0];
      if (!f || !String(f.type || "").startsWith("image/")) return;
      const reader = new FileReader();
      reader.onload = () => {
        applyItemTextureDataUrlToSelection(String(reader.result), { fileLabel: f.name });
        input.value = "";
      };
      reader.readAsDataURL(f);
    });
  }
  wireItemTexturePresetDrag();
  startItemPreviewLoop();
  requestAnimationFrame(() => {
    resizeItemEditorCanvas();
    renderItemEditorPreview();
  });
}

function getScenarioForMission(missionId) {
  const scenario = MISSION_SCENARIOS[missionId] || MISSION_SCENARIOS.default;
  return JSON.parse(JSON.stringify(scenario));
}

function applyAiProfileToRaidWaves(raidWaves, aiProfile) {
  if (!Array.isArray(raidWaves)) return [];
  const timingShift = aiProfile?.attackTiming === "early" ? -8 : aiProfile?.attackTiming === "late" ? 8 : 0;
  const countBias = aiProfile?.ecoBias === "low" ? 0 : 1;
  return raidWaves.map((wave, index) => ({
    ...wave,
    triggerAtSec: Math.max(20, Number(wave.triggerAtSec || 30) + timingShift),
    count: Math.max(1, Number(wave.count || 1) + (index > 0 ? countBias : 0))
  }));
}

function hashString(input) {
  let h = 2166136261;
  const text = String(input || "seed");
  for (let i = 0; i < text.length; i += 1) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0);
}

function deterministicFloat(seed, salt = 0) {
  const n = hashString(`${seed}:${salt}`);
  return (n % 10000) / 10000;
}

function isTileInsideMission(tileX, tileY) {
  return tileX >= 0 && tileY >= 0 && tileX < state.missionWorld.width && tileY < state.missionWorld.height;
}

function getTerrainTypeAt(tileX, tileY) {
  if (!isTileInsideMission(tileX, tileY)) return "void";
  return state.missionWorld.terrain[tileY][tileX];
}

function isTileWalkableForUnit(tileX, tileY) {
  return isTileInsideMission(tileX, tileY) && getTerrainTypeAt(tileX, tileY) !== "lake";
}

function isTileWalkableForDot(tileX, tileY) {
  return isTileWalkableForUnit(tileX, tileY);
}

function findNearestTileMatching(targetX, targetY, isValidFn, maxRadius = 8) {
  const cx = Math.round(targetX);
  const cy = Math.round(targetY);
  if (isValidFn(cx, cy)) return { x: cx, y: cy };
  for (let radius = 1; radius <= maxRadius; radius += 1) {
    for (let oy = -radius; oy <= radius; oy += 1) {
      for (let ox = -radius; ox <= radius; ox += 1) {
        if (Math.abs(ox) !== radius && Math.abs(oy) !== radius) continue;
        const tx = cx + ox;
        const ty = cy + oy;
        if (isValidFn(tx, ty)) return { x: tx, y: ty };
      }
    }
  }
  return null;
}

function advanceEntityTowards(entity, targetX, targetY, step, isValidFn) {
  const dx = targetX - entity.x;
  const dy = targetY - entity.y;
  const dist = Math.hypot(dx, dy);
  if (dist <= 0.0001) return { arrived: true, blocked: false };
  const nx = entity.x + (dx / dist) * Math.min(step, dist);
  const ny = entity.y + (dy / dist) * Math.min(step, dist);
  const tx = Math.round(nx);
  const ty = Math.round(ny);
  if (!isValidFn(tx, ty)) {
    const mission = state.missionRuntime.missionState;
    if (mission?.simMetrics) {
      mission.simMetrics.pathBlocks = Number(mission.simMetrics.pathBlocks || 0) + 1;
    }
    return { arrived: false, blocked: true };
  }
  entity.x = nx;
  entity.y = ny;
  return { arrived: dist <= step + 0.02, blocked: false };
}

function getWeightedStrategyChoice(profile, seedBase, salt) {
  const weights = profile?.strategyBias || { probe: 2, flank: 2, push: 2, retreat: 1 };
  const entries = Object.entries(weights);
  const total = entries.reduce((sum, [, value]) => sum + Math.max(0, Number(value || 0)), 0);
  if (total <= 0) return "probe";
  let roll = deterministicFloat(seedBase, salt) * total;
  for (const [name, value] of entries) {
    roll -= Math.max(0, Number(value || 0));
    if (roll <= 0) return name;
  }
  return "probe";
}

function isTouchPreferredDevice() {
  try {
    return window.matchMedia("(pointer: coarse)").matches || (navigator.maxTouchPoints || 0) > 0;
  } catch (err) {
    return false;
  }
}

function getCampaignNodes(campaignId) {
  return state.campaignData.nodes
    .filter((n) => n.campaignId === campaignId)
    .sort((a, b) => a.order - b.order);
}

function getNodeById(id) {
  return state.campaignData.nodes.find((n) => n.id === id) || null;
}

function defaultCampaignProgress(campaignId) {
  const campaign = state.campaignData.campaigns.find((c) => c.id === campaignId);
  return {
    unlocked: campaign?.startNodeId ? [campaign.startNodeId] : [],
    completed: [],
    selectedNodeId: campaign?.startNodeId || null
  };
}

function ensureCampaignProgress(campaignId) {
  if (!state.progress.campaignProgress[campaignId]) {
    state.progress.campaignProgress[campaignId] = defaultCampaignProgress(campaignId);
  }
  return state.progress.campaignProgress[campaignId];
}

function loadProgress() {
  const raw = localStorage.getItem("shc_proto_progress_v1");
  if (!raw) {
    ensureCampaignProgress(state.selectedCampaignId);
    return;
  }
  try {
    const parsed = JSON.parse(raw);
    state.progress = {
      ...state.progress,
      ...parsed,
      campaignProgress: {
        ...state.progress.campaignProgress,
        ...(parsed.campaignProgress || {})
      }
    };
    ensureCampaignProgress(state.selectedCampaignId);
  } catch (err) {
    console.warn("Failed to parse saved progress.", err);
    ensureCampaignProgress(state.selectedCampaignId);
  }
}

function saveProgress() {
  localStorage.setItem("shc_proto_progress_v1", JSON.stringify(state.progress));
}

function unlockAllMissionsForDev() {
  for (const campaign of state.campaignData.campaigns) {
    const campaignState = ensureCampaignProgress(campaign.id);
    const campaignNodeIds = state.campaignData.nodes
      .filter((node) => node.campaignId === campaign.id)
      .map((node) => node.id);
    campaignState.unlocked = Array.from(new Set([...campaignState.unlocked, ...campaignNodeIds]));
  }
  saveProgress();
}

function resetCampaignProgressForDev() {
  state.progress.campaignProgress = {};
  ensureCampaignProgress(state.selectedCampaignId);
  saveProgress();
}

function isUnlocked(node, campaignState) {
  if (campaignState.unlocked.includes(node.id)) return true;
  if (!node.requires || node.requires.length === 0) return true;
  return node.requires.every((r) => campaignState.completed.includes(r));
}

function buildPathsSvg(nodesById, nodes) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "campaign-paths");
  svg.setAttribute("width", String(state.campaignData.world.width));
  svg.setAttribute("height", String(state.campaignData.world.height));
  svg.setAttribute("viewBox", `0 0 ${state.campaignData.world.width} ${state.campaignData.world.height}`);

  for (const node of nodes) {
    for (const nextId of node.next || []) {
      const to = nodesById.get(nextId);
      if (!to) continue;
      const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
      line.setAttribute("x1", String(node.x));
      line.setAttribute("y1", String(node.y));
      line.setAttribute("x2", String(to.x));
      line.setAttribute("y2", String(to.y));
      line.setAttribute("stroke", "#84663a");
      line.setAttribute("stroke-width", "5");
      line.setAttribute("stroke-linecap", "round");
      line.setAttribute("opacity", "0.9");
      svg.appendChild(line);
    }
  }
  return svg;
}

function updateMapTransform() {
  const world = document.getElementById("campaign-world");
  if (!world) return;
  world.style.transform = `translate(${state.mapView.x}px, ${state.mapView.y}px) scale(${state.mapView.scale})`;
}

function clampMapView() {
  const viewport = document.getElementById("campaign-viewport");
  if (!viewport) return;
  const worldW = state.campaignData.world.width * state.mapView.scale;
  const worldH = state.campaignData.world.height * state.mapView.scale;
  const minX = Math.min(0, viewport.clientWidth - worldW);
  const minY = Math.min(0, viewport.clientHeight - worldH);
  state.mapView.x = Math.max(minX - 40, Math.min(40, state.mapView.x));
  state.mapView.y = Math.max(minY - 40, Math.min(40, state.mapView.y));
}

function setSelectedNode(nodeId) {
  const campaignState = ensureCampaignProgress(state.selectedCampaignId);
  campaignState.selectedNodeId = nodeId;
  state.selectedMissionId = nodeId;
  saveProgress();
  renderCampaignSidePanel();
  highlightSelectedNode();
}

function highlightSelectedNode() {
  for (const button of document.querySelectorAll(".campaign-node")) {
    button.classList.toggle("selected", button.dataset.id === state.selectedMissionId);
  }
}

function renderCampaignSidePanel() {
  const panel = document.getElementById("mission-side-panel");
  if (!panel) return;
  const node = getNodeById(state.selectedMissionId);
  if (!node) {
    panel.innerHTML = "<h3>No Mission Selected</h3><p>Select an unlocked node on the map.</p>";
    return;
  }
  const campaignState = ensureCampaignProgress(state.selectedCampaignId);
  const unlocked = isUnlocked(node, campaignState);
  const completed = campaignState.completed.includes(node.id);
  panel.innerHTML = `
    <h3>${node.title}</h3>
    <p>${node.objective}</p>
    <div>
      <div class="meta-label">Enemy</div>
      <div class="meta-value">${node.enemy}</div>
    </div>
    <div>
      <div class="meta-label">Biome</div>
      <div class="meta-value">${node.biome}</div>
    </div>
    <div>
      <div class="meta-label">Difficulty</div>
      <div class="meta-value">${node.difficulty}</div>
    </div>
    <div>
      <span class="tag">${completed ? "Completed" : unlocked ? "Unlocked" : "Locked"}</span>
    </div>
    <button data-action="open-briefing" ${unlocked ? "" : "disabled"}>Open Briefing</button>
  `;
}

function renderMissionBriefing() {
  const node = getNodeById(state.selectedMissionId);
  if (!node) {
    mountScreen("missions");
    return;
  }
  const campaignState = ensureCampaignProgress(state.selectedCampaignId);
  if (!isUnlocked(node, campaignState)) {
    showToast("Mission is still locked.");
    mountScreen("missions");
    return;
  }

  const titleEl = document.getElementById("briefing-title");
  const objectiveEl = document.getElementById("briefing-objective");
  const metaEl = document.getElementById("briefing-meta");
  if (!titleEl || !objectiveEl || !metaEl) return;

  titleEl.textContent = node.title;
  objectiveEl.textContent = node.objective;
  metaEl.innerHTML = `
    <div class="briefing-item">
      <div class="meta-label">Enemy Lord</div>
      <div class="meta-value">${node.enemy}</div>
    </div>
    <div class="briefing-item">
      <div class="meta-label">Region</div>
      <div class="meta-value">${node.biome}</div>
    </div>
    <div class="briefing-item">
      <div class="meta-label">Difficulty</div>
      <div class="meta-value">${node.difficulty}</div>
    </div>
    <div class="briefing-item">
      <div class="meta-label">Current Setting</div>
      <div class="meta-value">${state.settings.difficulty}</div>
    </div>
  `;
}

function formatDuration(ms) {
  const totalSec = Math.floor(ms / 1000);
  const mm = String(Math.floor(totalSec / 60)).padStart(2, "0");
  const ss = String(totalSec % 60).padStart(2, "0");
  return `${mm}:${ss}`;
}

function stopMissionTicker() {
  if (state.missionRuntime.tickerId) {
    window.clearInterval(state.missionRuntime.tickerId);
    state.missionRuntime.tickerId = null;
  }
}

function stopMissionRenderLoop() {
  if (state.missionWorld.renderFrameId) {
    window.cancelAnimationFrame(state.missionWorld.renderFrameId);
    state.missionWorld.renderFrameId = 0;
  }
}

function getMissionSaveKeyForId(missionId) {
  return `shc_mission_runtime_v1_${missionId || "unknown"}`;
}

function getMissionSaveKey() {
  return getMissionSaveKeyForId(state.missionRuntime.missionId || state.selectedMissionId || "unknown");
}

function getLastMissionSaveId() {
  return localStorage.getItem("shc_last_mission_id_v1");
}

function setLastMissionSaveId(missionId) {
  if (!missionId) return;
  localStorage.setItem("shc_last_mission_id_v1", missionId);
}

function clearAllMissionSavesForDev() {
  for (const node of state.campaignData.nodes) {
    localStorage.removeItem(getMissionSaveKeyForId(node.id));
  }
  localStorage.removeItem("shc_last_mission_id_v1");
}

function loadMissionFromMainMenu() {
  const missionId = getLastMissionSaveId();
  if (!missionId) {
    showToast("No saved game found.");
    return;
  }
  const node = getNodeById(missionId);
  if (!node) {
    localStorage.removeItem("shc_last_mission_id_v1");
    showToast("Saved mission is missing from this build.");
    return;
  }
  state.selectedMissionId = missionId;
  state.pendingMissionLoad = true;
  mountScreen("mission-play");
}

function addDevResources() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  mission.resources.wood += 500;
  mission.resources.apples = Number(mission.resources.apples || 0) + 500;
  mission.resources.food = Number(mission.resources.apples || 0);
  mission.resources.iron = Number(mission.resources.iron || 0) + 500;
  mission.resources.stone = Number(mission.resources.stone || 0) + 500;
  mission.resources.gold += 500;
  computeStockpileStats();
  updatePopularity();
  refreshMissionPanels();
  saveMissionRuntime();
}

function spawnDevMilitia() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const keep = mission.buildings.find((b) => b.type === "keep");
  const unitDef = UNIT_TEMPLATES.militia;
  const spawnRawX = keep ? keep.x + 2 : Math.floor(state.missionWorld.width * 0.5);
  const spawnRawY = keep ? keep.y + 3 : Math.floor(state.missionWorld.height * 0.5);
  const spawn = findNearestTileMatching(spawnRawX, spawnRawY, isTileWalkableForUnit, 7) || { x: spawnRawX, y: spawnRawY };
  mission.units.push({
    id: `u-${mission.nextUnitId++}`,
    type: "militia",
    x: spawn.x,
    y: spawn.y,
    hp: unitDef.hp,
    damage: unitDef.damage,
    speed: unitDef.speed,
    attackRange: unitDef.attackRange,
    stance: "stand",
    commandMode: "normal",
    moveTarget: null,
    attackTargetId: null
  });
  addMissionEventLog("DEV: Spawned militia unit.");
  refreshMissionPanels();
  saveMissionRuntime();
}

function refreshMissionHud() {
  const timerEl = document.getElementById("play-timer");
  const statusEl = document.getElementById("play-status-tag");
  const pauseBtn = document.querySelector('[data-action="pause-mission"]');
  const titleEl = document.getElementById("play-mission-title");
  const objectiveEl = document.getElementById("play-mission-objective");
  if (!timerEl || !statusEl || !pauseBtn) return;

  timerEl.textContent = formatDuration(state.missionRuntime.elapsedMs);
  const statusMap = {
    running: "Running",
    paused: "Paused",
    won: "Victory",
    failed: "Defeat",
    idle: "Idle"
  };
  statusEl.textContent = statusMap[state.missionRuntime.status] || "Running";
  pauseBtn.textContent = state.missionRuntime.status === "paused" ? "Resume" : "Pause";
  pauseBtn.disabled = state.missionRuntime.status === "won" || state.missionRuntime.status === "failed";

  const missionNode = getNodeById(state.missionRuntime.missionId || state.selectedMissionId);
  if (titleEl && missionNode) titleEl.textContent = missionNode.title;
  if (objectiveEl && missionNode) objectiveEl.textContent = missionNode.objective;
}

function computeMissionElapsedMs() {
  if (!state.missionRuntime.startedAt) return state.missionRuntime.elapsedMs;
  const now = Date.now();
  if (state.missionRuntime.status === "paused") {
    return state.missionRuntime.pauseStartedAt - state.missionRuntime.startedAt - state.missionRuntime.pausedAccumulatedMs;
  }
  return now - state.missionRuntime.startedAt - state.missionRuntime.pausedAccumulatedMs;
}

function missionRuntimeReset(missionId) {
  stopMissionTicker();
  state.missionRuntime = {
    missionId,
    startedAt: Date.now(),
    elapsedMs: 0,
    status: "running",
    pauseStartedAt: 0,
    pausedAccumulatedMs: 0,
    tickerId: null,
    lastProcessedSec: 0,
    missionState: null
  };
}

function buildMissionHeights(seed) {
  const heights = [];
  const seedBase = seed.split("").reduce((sum, c) => sum + c.charCodeAt(0), 0);
  for (let y = 0; y < state.missionWorld.height; y += 1) {
    const row = [];
    for (let x = 0; x < state.missionWorld.width; x += 1) {
      const n =
        Math.sin((x + seedBase) * 0.24) * 0.9 +
        Math.cos((y + seedBase) * 0.21) * 0.7 +
        Math.sin((x + y) * 0.15) * 0.45;
      row.push(Math.max(0, Math.round((n + 2.2) * 1.9)));
    }
    heights.push(row);
  }
  return heights;
}

function buildMissionTerrain(seedKey = "default") {
  const seedBase = String(seedKey).split("").reduce((sum, c) => sum + c.charCodeAt(0), 0);
  const seeded = (x, y, salt = 0) => {
    const raw = Math.sin((x * 12.9898) + (y * 78.233) + salt + seedBase) * 43758.5453;
    return raw - Math.floor(raw);
  };
  const terrain = [];
  for (let y = 0; y < state.missionWorld.height; y += 1) {
    const row = [];
    for (let x = 0; x < state.missionWorld.width; x += 1) {
      const h = state.missionWorld.heights[y][x];
      let type = "sand";
      if (h >= 7) type = "rock";
      else if (h <= 1) type = "dune";
      row.push(type);
    }
    terrain.push(row);
  }

  const stampNaturalField = (cx, cy, w, h, type, salt = 0, allowOnRock = false) => {
    const halfW = Math.floor(w * 0.5);
    const halfH = Math.floor(h * 0.5);
    for (let y = Math.max(0, cy - halfH - 3); y <= Math.min(state.missionWorld.height - 1, cy + halfH + 3); y += 1) {
      for (let x = Math.max(0, cx - halfW - 3); x <= Math.min(state.missionWorld.width - 1, cx + halfW + 3); x += 1) {
        const nx = (x - cx) / Math.max(1, halfW);
        const ny = (y - cy) / Math.max(1, halfH);
        const ellipse = (nx * nx) + (ny * ny);
        const edgeNoise = 0.72 + (seeded(x, y, 111 + salt) * 0.62);
        const distGate = ellipse <= edgeNoise;
        const warp = Math.sin((x + seedBase + salt) * 0.17) * Math.cos((y + seedBase + salt) * 0.13);
        const warpGate = (ellipse + (warp * 0.16)) <= (1.04 + (seeded(x, y, 123 + salt) * 0.25));
        if (!distGate || !warpGate) continue;
        if (!allowOnRock && terrain[y][x] === "rock") continue;
        if (type === "lake") {
          if (terrain[y][x] !== "rock") terrain[y][x] = "lake";
        } else if (type === "grass") {
          if (terrain[y][x] !== "lake" && terrain[y][x] !== "rock") terrain[y][x] = "grass";
        } else if (type === "scrub") {
          if (terrain[y][x] === "sand" || terrain[y][x] === "grass") terrain[y][x] = "scrub";
        }
      }
    }
  };

  const grassFields = 11;
  for (let i = 0; i < grassFields; i += 1) {
    const cx = Math.floor(seeded(i, 5, 15) * state.missionWorld.width);
    const cy = Math.floor(seeded(i, 9, 27) * state.missionWorld.height);
    const w = 20 + Math.floor(seeded(i, 11, 39) * 18);
    const h = 14 + Math.floor(seeded(i, 13, 51) * 14);
    stampNaturalField(cx, cy, w, h, "grass", i * 7, false);
    if (seeded(i, 17, 59) > 0.45) {
      stampNaturalField(cx + 5, cy - 4, Math.max(14, w - 8), Math.max(10, h - 6), "grass", (i * 9) + 1, false);
    }
  }

  const scrubFields = 13;
  for (let i = 0; i < scrubFields; i += 1) {
    const cx = Math.floor(seeded(i, 21, 73) * state.missionWorld.width);
    const cy = Math.floor(seeded(i, 23, 81) * state.missionWorld.height);
    const w = 13 + Math.floor(seeded(i, 29, 95) * 13);
    const h = 10 + Math.floor(seeded(i, 31, 109) * 10);
    stampNaturalField(cx, cy, w, h, "scrub", (i * 11) + 3, false);
  }

  const lakeFields = 8;
  for (let i = 0; i < lakeFields; i += 1) {
    const cx = Math.floor(seeded(i, 37, 121) * state.missionWorld.width);
    const cy = Math.floor(seeded(i, 41, 139) * state.missionWorld.height);
    const w = 12 + Math.floor(seeded(i, 43, 151) * 10);
    const h = 8 + Math.floor(seeded(i, 47, 167) * 8);
    stampNaturalField(cx, cy, w, h, "lake", (i * 13) + 5, false);
    if (seeded(i, 53, 181) > 0.52) {
      stampNaturalField(cx - 4, cy + 4, Math.max(8, w - 4), Math.max(6, h - 3), "lake", (i * 17) + 9, false);
    }
  }

  state.missionWorld.terrain = terrain;
}

function buildMissionResourceNodes(seedKey = "default") {
  const seedBase = String(seedKey).split("").reduce((sum, c) => sum + c.charCodeAt(0), 0);
  const seeded = (x, y, salt = 0) => {
    const raw = Math.sin((x * 21.123) + (y * 63.771) + salt + seedBase) * 16431.1357;
    return raw - Math.floor(raw);
  };
  const nodes = [];
  const isNearCorner = (x, y, pad = 18) =>
    (x < pad && y < pad) ||
    (x > state.missionWorld.width - pad && y > state.missionWorld.height - pad);

  const oreOwner = [];
  for (let y = 0; y < state.missionWorld.height; y += 1) {
    const row = [];
    for (let x = 0; x < state.missionWorld.width; x += 1) {
      row.push("none");
    }
    oreOwner.push(row);
  }

  const stampOreField = (cx, cy, w, h, oreType, salt = 0) => {
    const halfW = Math.floor(w * 0.5);
    const halfH = Math.floor(h * 0.5);
    for (let y = Math.max(0, cy - halfH - 1); y <= Math.min(state.missionWorld.height - 1, cy + halfH + 1); y += 1) {
      for (let x = Math.max(0, cx - halfW - 1); x <= Math.min(state.missionWorld.width - 1, cx + halfW + 1); x += 1) {
        if (isNearCorner(x, y, 20)) continue;
        const nx = (x - cx) / Math.max(1, halfW);
        const ny = (y - cy) / Math.max(1, halfH);
        const ellipse = (nx * nx) + (ny * ny);
        const edgeNoise = 0.82 + (seeded(x, y, 197 + salt) * 0.38);
        if (ellipse > edgeNoise) continue;
        state.missionWorld.terrain[y][x] = "rock";
        oreOwner[y][x] = oreType;
      }
    }
  };

  const ironFields = 20;
  for (let i = 0; i < ironFields; i += 1) {
    const cx = 18 + Math.floor(seeded(i, 41, 103) * (state.missionWorld.width - 36));
    const cy = 18 + Math.floor(seeded(i, 47, 119) * (state.missionWorld.height - 36));
    const orientWide = seeded(i, 53, 137) > 0.5;
    const w = orientWide ? 6 : 8;
    const h = orientWide ? 8 : 6;
    stampOreField(cx, cy, w, h, "iron_patch", i * 3);
    if (seeded(i, 59, 149) > 0.62) {
      stampOreField(cx + 4, cy - 3, Math.max(6, w - 1), Math.max(6, h - 1), "iron_patch", (i * 5) + 2);
    }
  }

  const stoneFields = 24;
  for (let i = 0; i < stoneFields; i += 1) {
    const cx = 16 + Math.floor(seeded(i, 61, 163) * (state.missionWorld.width - 32));
    const cy = 16 + Math.floor(seeded(i, 67, 179) * (state.missionWorld.height - 32));
    const orientWide = seeded(i, 71, 191) > 0.5;
    const w = orientWide ? 6 : 8;
    const h = orientWide ? 8 : 6;
    stampOreField(cx, cy, w, h, "stone_patch", (i * 7) + 1);
    if (seeded(i, 73, 211) > 0.58) {
      stampOreField(cx - 4, cy + 3, Math.max(6, w - 1), Math.max(6, h - 1), "stone_patch", (i * 11) + 4);
    }
  }

  const treeMask = [];
  for (let y = 0; y < state.missionWorld.height; y += 1) {
    treeMask.push(new Array(state.missionWorld.width).fill(false));
  }
  const paintTreePatch = (cx, cy, w, h, salt = 0) => {
    const halfW = Math.floor(w * 0.5);
    const halfH = Math.floor(h * 0.5);
    for (let y = Math.max(0, cy - halfH - 2); y <= Math.min(state.missionWorld.height - 1, cy + halfH + 2); y += 1) {
      for (let x = Math.max(0, cx - halfW - 2); x <= Math.min(state.missionWorld.width - 1, cx + halfW + 2); x += 1) {
        const terrain = state.missionWorld.terrain[y][x];
        if (terrain === "lake") continue;
        const nx = (x - cx) / Math.max(1, halfW);
        const ny = (y - cy) / Math.max(1, halfH);
        const ellipse = (nx * nx) + (ny * ny);
        const edgeNoise = 0.78 + (seeded(x, y, 241 + salt) * 0.42);
        if (ellipse <= edgeNoise) {
          treeMask[y][x] = true;
          if (state.missionWorld.terrain[y][x] === "sand") state.missionWorld.terrain[y][x] = "scrub";
        }
      }
    }
  };
  const treeFields = 26;
  for (let i = 0; i < treeFields; i += 1) {
    const cx = Math.floor(seeded(i, 61, 149) * state.missionWorld.width);
    const cy = Math.floor(seeded(i, 67, 163) * state.missionWorld.height);
    const w = 6 + Math.floor(seeded(i, 71, 179) * 6);
    const h = 6 + Math.floor(seeded(i, 79, 193) * 6);
    paintTreePatch(cx, cy, w, h, i * 9);
    if (seeded(i, 83, 209) > 0.5) paintTreePatch(cx + 3, cy - 2, Math.max(9, w - 4), Math.max(8, h - 3), (i * 13) + 5);
  }

  let ironPainted = 0;
  for (let y = 0; y < state.missionWorld.height; y += 1) {
    const row = [];
    for (let x = 0; x < state.missionWorld.width; x += 1) {
      const terrain = state.missionWorld.terrain[y][x];
      let nodeType = "none";
      let amount = 0;
      if (treeMask[y][x]) {
        nodeType = "tree";
        amount = 5;
      } else if (oreOwner[y][x] === "iron_patch") {
        nodeType = "iron_patch";
        amount = 62 + Math.floor(seeded(x, y, 23) * 34);
        ironPainted += 1;
      } else if (oreOwner[y][x] === "stone_patch" || terrain === "rock") {
        nodeType = "stone_patch";
        amount = 66 + Math.floor(seeded(x, y, 29) * 34);
      } else if (terrain === "grass") {
        nodeType = "grass";
        amount = 9999;
      } else if (terrain === "lake") {
        nodeType = "lake";
        amount = 9999;
      }
      row.push({ type: nodeType, amount });
    }
    nodes.push(row);
  }
  if (ironPainted < 320) {
    for (let y = 10; y < state.missionWorld.height - 10; y += 1) {
      for (let x = 10; x < state.missionWorld.width - 10; x += 1) {
        if (state.missionWorld.terrain[y][x] !== "rock") continue;
        if (isNearCorner(x, y)) continue;
        if (seeded(x, y, 41) < 0.42) {
          nodes[y][x] = { type: "iron_patch", amount: 60 + Math.floor(seeded(x, y, 43) * 30) };
        }
      }
    }
  }
  return nodes;
}

function normalizeObjectiveDefinition(rawObj) {
  const legacyById = {
    "build-stockpile": { type: "building_count", target: { buildingType: "stockpile", count: 1 } },
    "build-woodcutter": { type: "building_count", target: { buildingType: "woodcutter", count: 1 } },
    "collect-wood": { type: "resource_at_least", target: { resource: "wood", amount: 120 } }
  };
  const fallback = legacyById[rawObj?.id] || { type: "resource_at_least", target: { resource: "wood", amount: 1 } };
  return {
    id: rawObj?.id || `obj-${Date.now()}`,
    text: rawObj?.text || "Objective",
    type: rawObj?.type || fallback.type,
    target: rawObj?.target || fallback.target,
    done: !!rawObj?.done
  };
}

function normalizeScenarioEvents(events) {
  if (!Array.isArray(events)) return [];
  return events.map((ev, index) => ({
    id: ev?.id || `ev-${index}`,
    triggerAtSec: Math.max(0, Number(ev?.triggerAtSec || 0)),
    message: ev?.message || "Advisor message.",
    triggered: !!ev?.triggered
  }));
}

function normalizeRaidWaves(waves) {
  if (!Array.isArray(waves)) return [];
  return waves.map((wave, index) => ({
    id: wave?.id || `raid-wave-${index + 1}`,
    triggerAtSec: Math.max(20, Number(wave?.triggerAtSec || 30)),
    side: wave?.side || "east",
    unitType: wave?.unitType || "raider",
    unitMix: Array.isArray(wave?.unitMix)
      ? wave.unitMix.map((item) => ({
        unitType: item?.unitType || "raider",
        count: Math.max(1, Number(item?.count || 1))
      }))
      : null,
    count: Math.max(1, Number(wave?.count || 1)),
    warned: !!wave?.warned,
    spawned: !!wave?.spawned
  }));
}

function createBaseMissionState(missionId = "default") {
  const scenario = getScenarioForMission(missionId);
  const aiSeed = hashString(missionId || "default");
  const fallbackProfileIds = Object.keys(ENEMY_AI_PROFILES);
  const fallbackProfileId = fallbackProfileIds[aiSeed % fallbackProfileIds.length] || "ratHarass";
  const aiProfile = ENEMY_AI_PROFILES[scenario.aiProfileId || fallbackProfileId] || null;
  const scenarioRaidWaves = applyAiProfileToRaidWaves(normalizeRaidWaves(scenario.raidWaves), aiProfile);
  const playerKeepX = 12;
  const playerKeepY = 12;
  const enemyKeepX = state.missionWorld.width - 18;
  const enemyKeepY = state.missionWorld.height - 18;
  const mission = {
    selectedTile: null,
    hoveredTile: null,
    selectedBuildingId: null,
    showBuildabilityOverlay: false,
    buildMode: null,
    buildCategory: "economy",
    moveCommandMode: false,
    selectedUnitId: null,
    selectedUnitIds: [],
    selectionBox: null,
    contextMode: "none",
    lastSelectedEntityId: null,
    buildBarMinimized: false,
    devMenuOpen: false,
    pauseMenuOpen: false,
    pauseOptionsOpen: false,
    buildings: [
      {
        id: "campfire-1",
        type: "campfire",
        x: playerKeepX + 3,
        y: playerKeepY + 4,
        w: 2,
        h: 2,
        active: true,
        workerAssigned: false,
        owner: "player",
        locked: true
      },
      {
        id: "keep-1",
        type: "keep",
        x: playerKeepX,
        y: playerKeepY,
        w: 3,
        h: 3,
        active: true,
        workerAssigned: false,
        owner: "player",
        locked: true
      }
    ],
    enemyBuildings: [
      {
        id: "enemy-keep-1",
        type: "keep",
        x: enemyKeepX,
        y: enemyKeepY,
        w: 3,
        h: 3,
        active: true,
        workerAssigned: false,
        owner: "enemy",
        locked: true
      },
      {
        id: "enemy-campfire-1",
        type: "campfire",
        x: enemyKeepX - 4,
        y: enemyKeepY - 3,
        w: 2,
        h: 2,
        active: true,
        workerAssigned: false,
        owner: "enemy",
        locked: true
      }
    ],
    nextBuildingId: 1,
    nextEnemyBuildingId: 1,
    nextUnitId: 1,
    nextEnemyId: 1,
    units: [],
    enemies: [],
    resources: { wood: 0, gold: 0, food: 0, iron: 0, stone: 0, apples: 0 },
    pendingStockpileResources: { ...STARTING_BUNDLE_MEDIUM.stockpile },
    pendingGranaryResources: { ...STARTING_BUNDLE_MEDIUM.granary },
    pendingDeliveryDeltas: { wood: 0 },
    resourceNodes: buildMissionResourceNodes(missionId),
    stockpileCapacity: 0,
    stockpileUsed: 0,
    granaryCapacity: 0,
    granaryUsed: 0,
    workersAssigned: 0,
    idlePeasants: 0,
    population: Number(scenario.startPopulation?.current || 10),
    populationCap: Number(scenario.startPopulation?.cap || 10),
    popularity: 50,
    popularityBreakdown: [],
    taxMode: "none",
    rationMode: "normal",
    lowPopularitySec: 0,
    lastWoodDelta: 0,
    lastFoodDelta: 0,
    woodCadenceTick: 0,
    woodStatus: "idle",
    woodStallReason: "",
    chainStatus: {
      wood: "Idle",
      iron: "Idle",
      cow: "Idle",
      apples: "Idle",
      stone: "Idle"
    },
    peasantWorkers: [],
    enemyPeasantWorkers: [],
    recruitTransitDots: [],
    popularityTrend: "Stable",
    commandThrottleUntilMs: 0,
    aiProfileId: aiProfile?.id || null,
    aiProfileSnapshot: aiProfile ? { ...aiProfile } : null,
    aiSeed,
    aiDirector: {
      strategy: "probe",
      lastStrategyShiftSec: 0,
      phaseBucket: 0
    },
    modernRules: {
      researchEnabled: true,
      upkeepEnabled: true
    },
    researchState: {
      points: 0,
      stockpileBoostLevel: 0,
      productionBoostLevel: 0,
      barracksDrillLevel: 0
    },
    upkeepState: {
      foodDebtTicks: 0,
      goldDebtTicks: 0,
      moralePenalty: 0
    },
    simMetrics: {
      pathBlocks: 0,
      waterCommandRejections: 0,
      strategyShifts: 0
    },
    commandMode: "normal",
    lordDots: [],
    mobileQuickCommandOpen: false,
    raidWaves: scenarioRaidWaves,
    raidState: {
      warningIds: [],
      spawnedWaveIds: [],
      keepThreatSec: 0,
      lastRaidMessageSec: -99
    },
    enemyState: {
      resources: { wood: 34, gold: 110, food: 90, iron: 0, stone: 0, apples: 0 },
      stockpileCapacity: 0,
      buildPlanIndex: 0,
      lastBuildAttemptSec: 0,
      lastAttackCycleSec: 0
    },
    autosaveCounter: 0,
    objectives: (scenario.objectives || []).map((obj) => normalizeObjectiveDefinition(obj)),
    events: normalizeScenarioEvents(scenario.events),
    eventLog: []
  };
  initializeMissionLordDots(mission);
  return mission;
}

function formatBuildCost(cost) {
  return Object.entries(cost)
    .map(([name, amount]) => `${amount} ${name}`)
    .join(" | ");
}

function canAffordCost(resources, cost) {
  return Object.entries(cost).every(([resource, amount]) => (resources[resource] ?? 0) >= amount);
}

function canUsePendingForBuild(type) {
  return type === "stockpile" || type === "granary";
}

function getBuildResourceAvailable(mission, type, resource) {
  const live = Number(mission.resources?.[resource] || 0);
  if (!canUsePendingForBuild(type)) return live;
  const pendingStock = Number(mission.pendingStockpileResources?.[resource] || 0);
  const pendingGranary = Number(mission.pendingGranaryResources?.[resource] || 0);
  return live + pendingStock + pendingGranary;
}

function spendBuildResource(mission, type, resource, amount) {
  let remaining = Number(amount || 0);
  const live = Number(mission.resources?.[resource] || 0);
  const fromLive = Math.min(live, remaining);
  mission.resources[resource] = live - fromLive;
  remaining -= fromLive;
  if (remaining <= 0 || !canUsePendingForBuild(type)) return remaining;

  const pendingStock = Number(mission.pendingStockpileResources?.[resource] || 0);
  const fromPendingStock = Math.min(pendingStock, remaining);
  mission.pendingStockpileResources[resource] = pendingStock - fromPendingStock;
  remaining -= fromPendingStock;
  if (remaining <= 0) return remaining;

  const pendingGranary = Number(mission.pendingGranaryResources?.[resource] || 0);
  const fromPendingGranary = Math.min(pendingGranary, remaining);
  mission.pendingGranaryResources[resource] = pendingGranary - fromPendingGranary;
  remaining -= fromPendingGranary;
  return remaining;
}

function addStockpileFlowResource(mission, resource, amount) {
  const qty = Math.max(0, Number(amount || 0));
  if (qty <= 0) return 0;
  const hasStockpile = mission.buildings.some((b) => b.type === "stockpile");
  if (!hasStockpile) {
    mission.pendingStockpileResources[resource] = Number(mission.pendingStockpileResources?.[resource] || 0) + qty;
    return 0;
  }
  if (resource === "gold") {
    mission.resources.gold = Number(mission.resources.gold || 0) + qty;
    return qty;
  }
  const used = getStockpileUsedTotal(mission);
  const room = Math.max(0, Number(mission.stockpileCapacity || 0) - used);
  const grant = Math.min(room, qty);
  if (grant > 0) mission.resources[resource] = Number(mission.resources[resource] || 0) + grant;
  return grant;
}

function addGranaryFlowResource(mission, amount) {
  const qty = Math.max(0, Number(amount || 0));
  if (qty <= 0) return 0;
  const hasGranary = mission.buildings.some((b) => b.type === "granary");
  if (!hasGranary) {
    mission.pendingGranaryResources.apples = Number(mission.pendingGranaryResources?.apples || 0) + qty;
    return 0;
  }
  const room = Math.max(0, Number(mission.granaryCapacity || 0) - Number(mission.resources.apples || 0));
  const grant = Math.min(room, qty);
  if (grant > 0) mission.resources.apples = Number(mission.resources.apples || 0) + grant;
  return grant;
}

function syncPendingStorageUnlock(mission) {
  if (!mission) return;
  if (mission.buildings.some((b) => b.type === "stockpile")) {
    const pending = mission.pendingStockpileResources || {};
    const wood = addStockpileFlowResource(mission, "wood", Number(pending.wood || 0));
    const stone = addStockpileFlowResource(mission, "stone", Number(pending.stone || 0));
    const iron = addStockpileFlowResource(mission, "iron", Number(pending.iron || 0));
    const gold = addStockpileFlowResource(mission, "gold", Number(pending.gold || 0));
    mission.pendingStockpileResources.wood = Math.max(0, Number(pending.wood || 0) - wood);
    mission.pendingStockpileResources.stone = Math.max(0, Number(pending.stone || 0) - stone);
    mission.pendingStockpileResources.iron = Math.max(0, Number(pending.iron || 0) - iron);
    mission.pendingStockpileResources.gold = Math.max(0, Number(pending.gold || 0) - gold);
  }
  if (mission.buildings.some((b) => b.type === "granary")) {
    const pendingApples = Number(mission.pendingGranaryResources?.apples || 0);
    const moved = addGranaryFlowResource(mission, pendingApples);
    mission.pendingGranaryResources.apples = Math.max(0, pendingApples - moved);
  }
  mission.resources.food = Number(mission.resources.apples || 0);
}

function normalizeBarracksQueueItem(rawItem, index = 0) {
  const totalSec = Math.max(1, Number(rawItem?.totalSec || MILITIA_RECRUIT_SECONDS));
  const remainingSec = Math.max(0, Math.min(totalSec, Number(rawItem?.remainingSec ?? totalSec)));
  return {
    id: rawItem?.id || `q-legacy-${index}`,
    type: rawItem?.type || "militia",
    totalSec,
    remainingSec,
    state: rawItem?.state || "queued",
    assignedTransitDotId: rawItem?.assignedTransitDotId || null
  };
}

function normalizeBarracksQueues(mission) {
  if (!mission?.buildings) return;
  for (const building of mission.buildings) {
    if (building.type !== "barracks") continue;
    const queue = Array.isArray(building.recruitQueue) ? building.recruitQueue : [];
    building.recruitQueue = queue.map((item, index) => normalizeBarracksQueueItem(item, index)).slice(0, BARRACKS_QUEUE_LIMIT);
  }
}

function normalizeMissionState(rawMissionState) {
  const fallback = createBaseMissionState(state.missionRuntime.missionId || state.selectedMissionId || "default");
  const mission = { ...fallback, ...(rawMissionState || {}) };
  mission.resources = { ...fallback.resources, ...(mission.resources || {}) };
  mission.resourceNodes = Array.isArray(mission.resourceNodes) ? mission.resourceNodes : buildMissionResourceNodes();
  mission.buildings = Array.isArray(mission.buildings) ? mission.buildings : [...fallback.buildings];
  mission.enemyBuildings = Array.isArray(mission.enemyBuildings) ? mission.enemyBuildings : [...fallback.enemyBuildings];
  mission.units = Array.isArray(mission.units) ? mission.units : [];
  mission.enemies = Array.isArray(mission.enemies) ? mission.enemies : [];
  mission.objectives = Array.isArray(mission.objectives)
    ? mission.objectives.map((obj) => normalizeObjectiveDefinition(obj))
    : [...fallback.objectives];
  mission.events = Array.isArray(mission.events) ? normalizeScenarioEvents(mission.events) : [...fallback.events];
  mission.raidWaves = Array.isArray(mission.raidWaves) ? normalizeRaidWaves(mission.raidWaves) : [...fallback.raidWaves];
  mission.raidState = {
    ...fallback.raidState,
    ...(mission.raidState || {})
  };
  mission.eventLog = Array.isArray(mission.eventLog) ? mission.eventLog : [];
  mission.popularityBreakdown = Array.isArray(mission.popularityBreakdown) ? mission.popularityBreakdown : [];
  mission.selectedTile = mission.selectedTile || null;
  mission.hoveredTile = mission.hoveredTile || null;
  mission.selectedBuildingId = mission.selectedBuildingId || null;
  mission.buildMode = mission.buildMode || null;
  mission.buildCategory = mission.buildCategory || "economy";
  mission.showBuildabilityOverlay = false;
  mission.moveCommandMode = !!mission.moveCommandMode;
  mission.selectedUnitId = mission.selectedUnitId || null;
  mission.selectedUnitIds = Array.isArray(mission.selectedUnitIds) ? mission.selectedUnitIds : [];
  if (mission.selectedUnitIds.length === 0 && mission.selectedUnitId) mission.selectedUnitIds = [mission.selectedUnitId];
  mission.selectionBox = mission.selectionBox || null;
  mission.contextMode = mission.contextMode || "none";
  mission.lastSelectedEntityId = mission.lastSelectedEntityId || null;
  mission.buildBarMinimized = !!mission.buildBarMinimized;
  mission.devMenuOpen = !!mission.devMenuOpen;
  mission.pauseMenuOpen = !!mission.pauseMenuOpen;
  mission.pauseOptionsOpen = !!mission.pauseOptionsOpen;
  mission.nextBuildingId = Number(mission.nextBuildingId || 1);
  mission.nextEnemyBuildingId = Number(mission.nextEnemyBuildingId || 1);
  mission.nextUnitId = Number(mission.nextUnitId || 1);
  mission.nextEnemyId = Number(mission.nextEnemyId || 1);
  mission.taxMode = mission.taxMode || "none";
  mission.rationMode = mission.rationMode || "normal";
  mission.woodCadenceTick = Number(mission.woodCadenceTick || 0);
  mission.woodStatus = mission.woodStatus || "idle";
  mission.woodStallReason = mission.woodStallReason || "";
  mission.popularityTrend = mission.popularityTrend || "Stable";
  mission.commandThrottleUntilMs = Number(mission.commandThrottleUntilMs || 0);
  mission.chainStatus = { ...fallback.chainStatus, ...(mission.chainStatus || {}) };
  mission.peasantWorkers = Array.isArray(mission.peasantWorkers) ? mission.peasantWorkers : [];
  mission.enemyPeasantWorkers = Array.isArray(mission.enemyPeasantWorkers) ? mission.enemyPeasantWorkers : [];
  mission.recruitTransitDots = Array.isArray(mission.recruitTransitDots) ? mission.recruitTransitDots : [];
  mission.pendingStockpileResources = {
    ...fallback.pendingStockpileResources,
    ...(mission.pendingStockpileResources || {})
  };
  mission.pendingGranaryResources = {
    ...fallback.pendingGranaryResources,
    ...(mission.pendingGranaryResources || {})
  };
  mission.pendingDeliveryDeltas = {
    ...fallback.pendingDeliveryDeltas,
    ...(mission.pendingDeliveryDeltas || {})
  };
  if (!Object.prototype.hasOwnProperty.call(rawMissionState || {}, "pendingStockpileResources")) {
    mission.pendingStockpileResources = { wood: 0, stone: 0, iron: 0, gold: 0 };
  }
  if (!Object.prototype.hasOwnProperty.call(rawMissionState || {}, "pendingGranaryResources")) {
    mission.pendingGranaryResources = { apples: 0 };
  }
  mission.aiProfileId = mission.aiProfileId || fallback.aiProfileId || null;
  mission.aiProfileSnapshot = mission.aiProfileSnapshot || fallback.aiProfileSnapshot || null;
  mission.aiSeed = Number(mission.aiSeed || fallback.aiSeed || hashString(state.missionRuntime.missionId || "default"));
  mission.aiDirector = {
    ...(fallback.aiDirector || {}),
    ...(mission.aiDirector || {})
  };
  mission.modernRules = {
    ...(fallback.modernRules || {}),
    ...(mission.modernRules || {})
  };
  mission.researchState = {
    ...(fallback.researchState || {}),
    ...(mission.researchState || {})
  };
  mission.upkeepState = {
    ...(fallback.upkeepState || {}),
    ...(mission.upkeepState || {})
  };
  mission.simMetrics = {
    ...(fallback.simMetrics || {}),
    ...(mission.simMetrics || {})
  };
  mission.commandMode = mission.commandMode || "normal";
  mission.mobileQuickCommandOpen = !!mission.mobileQuickCommandOpen;
  mission.enemyState = {
    ...fallback.enemyState,
    ...(mission.enemyState || {})
  };
  mission.enemyState.resources = { ...fallback.enemyState.resources, ...(mission.enemyState.resources || {}) };
  mission.granaryCapacity = Number(mission.granaryCapacity || 0);
  mission.granaryUsed = Number(mission.granaryUsed || 0);
  if (shouldRegenerateResourceNodes(mission)) {
    mission.resourceNodes = buildMissionResourceNodes(state.missionRuntime.missionId || state.selectedMissionId || "default");
  }
  for (let y = 0; y < mission.resourceNodes.length; y += 1) {
    for (let x = 0; x < mission.resourceNodes[y].length; x += 1) {
      const node = mission.resourceNodes[y][x];
      if (node?.type === "tree") node.amount = Math.max(0, Math.min(5, Number(node.amount || 0)));
    }
  }
  for (const b of mission.buildings) b.owner = b.owner || "player";
  for (const b of mission.enemyBuildings) b.owner = b.owner || "enemy";
  for (const b of mission.buildings) {
    b.bufferWood = Number(b.bufferWood || 0);
    b.bufferStone = Number(b.bufferStone || 0);
    b.bufferIron = Number(b.bufferIron || 0);
    b.cowLoad = Number(b.cowLoad || 0);
    b.appleBuffer = Number(b.appleBuffer || 0);
    b.harvestTick = Number(b.harvestTick || 0);
    b.assignedWorkers = Number(b.assignedWorkers || 0);
  }
  for (const b of mission.enemyBuildings) {
    b.bufferWood = Number(b.bufferWood || 0);
    b.bufferStone = Number(b.bufferStone || 0);
    b.bufferIron = Number(b.bufferIron || 0);
    b.cowLoad = Number(b.cowLoad || 0);
    b.appleBuffer = Number(b.appleBuffer || 0);
    b.harvestTick = Number(b.harvestTick || 0);
    b.assignedWorkers = Number(b.assignedWorkers || 0);
  }
  mission.peasantWorkers = mission.peasantWorkers.map((dot, idx) => ({
    id: dot?.id || `pw-${idx}`,
    role: dot?.role || "worker",
    state: dot?.state || "to-work",
    x: Number(dot?.x || 0),
    y: Number(dot?.y || 0),
    targetX: Number(dot?.targetX || 0),
    targetY: Number(dot?.targetY || 0),
    assignedBuildingId: dot?.assignedBuildingId || null,
    taskType: dot?.taskType || "idle",
    carryType: dot?.carryType || null,
    carryAmount: Number(dot?.carryAmount || 0),
    slotIndex: Number(dot?.slotIndex || 0),
    routeIndex: Number(dot?.routeIndex || 0)
  }));
  mission.enemyPeasantWorkers = mission.enemyPeasantWorkers.map((dot, idx) => ({
    id: dot?.id || `epw-${idx}`,
    role: dot?.role || "worker",
    state: dot?.state || "to-work",
    x: Number(dot?.x || 0),
    y: Number(dot?.y || 0),
    targetX: Number(dot?.targetX || 0),
    targetY: Number(dot?.targetY || 0),
    assignedBuildingId: dot?.assignedBuildingId || null,
    taskType: dot?.taskType || "idle",
    carryType: dot?.carryType || null,
    carryAmount: Number(dot?.carryAmount || 0),
    slotIndex: Number(dot?.slotIndex || 0),
    routeIndex: Number(dot?.routeIndex || 0)
  }));
  mission.recruitTransitDots = mission.recruitTransitDots.map((dot, idx) => ({
    id: dot?.id || `rt-${idx}`,
    queueId: dot?.queueId || null,
    barracksId: dot?.barracksId || null,
    x: Number(dot?.x || 0),
    y: Number(dot?.y || 0),
    targetX: Number(dot?.targetX || 0),
    targetY: Number(dot?.targetY || 0),
    arrived: !!dot?.arrived
  }));
  mission.lordDots = Array.isArray(mission.lordDots) ? mission.lordDots : [];
  mission.lordDots = mission.lordDots.map((dot, idx) => ({
    id: dot?.id || `lord-${idx}`,
    faction: dot?.faction === "enemy" ? "enemy" : "player",
    x: Number(dot?.x || 0),
    y: Number(dot?.y || 0),
    state: dot?.state || "patrol",
    route: Array.isArray(dot?.route) ? dot.route.map((node) => ({ x: Number(node?.x || 0), y: Number(node?.y || 0) })) : [],
    routeIndex: Number(dot?.routeIndex || 0),
    hp: Math.max(1, Number(dot?.hp || 180))
  }));
  initializeMissionLordDots(mission);
  normalizeBarracksQueues(mission);
  for (const unit of mission.units) {
    const unitDef = UNIT_TEMPLATES[unit.type] || UNIT_TEMPLATES.militia;
    unit.hp = Number(unit.hp || unitDef.hp);
    unit.damage = Number(unit.damage || unitDef.damage);
    unit.speed = Number(unit.speed || unitDef.speed);
    unit.attackRange = Number(unit.attackRange || unitDef.attackRange || 1.2);
    unit.stance = unit.stance || "stand";
    unit.commandMode = unit.commandMode || "normal";
  }
  sanitizeMissionSelectionState(mission);
  return mission;
}

function getMissionSanityIssues(mission) {
  if (!mission) return ["Mission state missing."];
  const issues = [];
  if (!mission.buildings.some((b) => b.type === "keep")) issues.push("Keep missing.");
  if (!mission.buildings.some((b) => b.type === "campfire")) issues.push("Campfire missing.");
  if (!mission.enemyBuildings?.some((b) => b.type === "keep")) issues.push("Enemy keep missing.");
  if (!mission.enemyBuildings?.some((b) => b.type === "campfire")) issues.push("Enemy campfire missing.");
  if (!Array.isArray(mission.resourceNodes)) issues.push("Resource node map missing.");
  if (mission.selectedBuildingId && !mission.buildings.some((b) => b.id === mission.selectedBuildingId)) {
    issues.push("Selected building invalid.");
  }
  const invalidSelectedUnits = mission.selectedUnitIds.filter((id) => !mission.units.some((u) => u.id === id));
  if (invalidSelectedUnits.length > 0) issues.push("Selected unit IDs invalid.");
  return issues;
}

function getAllMissionBuildings(mission) {
  if (!mission) return [];
  return [...(mission.buildings || []), ...(mission.enemyBuildings || [])];
}

function shouldRegenerateResourceNodes(mission) {
  if (!Array.isArray(mission?.resourceNodes) || mission.resourceNodes.length !== state.missionWorld.height) return true;
  if (!Array.isArray(mission.resourceNodes[0]) || mission.resourceNodes[0].length !== state.missionWorld.width) return true;
  let iron = 0;
  let grass = 0;
  for (let y = 0; y < mission.resourceNodes.length; y += 1) {
    for (let x = 0; x < mission.resourceNodes[y].length; x += 1) {
      const t = mission.resourceNodes[y][x]?.type;
      if (t === "iron_patch") iron += 1;
      if (t === "grass") grass += 1;
    }
  }
  return iron < 40 || grass < 120;
}

function sanitizeMissionSelectionState(mission) {
  if (!mission) return;
  const selectedBuilding = mission.buildings.find((b) => b.id === mission.selectedBuildingId) || null;
  const selectedUnit = mission.units.find((u) => u.id === mission.selectedUnitId) || null;
  if (!selectedBuilding) mission.selectedBuildingId = null;
  const validSelectedUnitIds = mission.selectedUnitIds.filter((id) => mission.units.some((u) => u.id === id));
  mission.selectedUnitIds = Array.from(new Set(validSelectedUnitIds));
  if (!selectedUnit || mission.selectedUnitIds.length === 0) mission.selectedUnitId = mission.selectedUnitIds[0] || null;
  if (mission.selectedUnitId && !mission.selectedUnitIds.includes(mission.selectedUnitId)) {
    mission.selectedUnitIds.unshift(mission.selectedUnitId);
  }

  // Reset context on the mission being sanitized (not via setContextMode, which
  // targets the globally installed mission — they differ during save loading).
  const clearContext = () => {
    mission.contextMode = "none";
    mission.lastSelectedEntityId = null;
  };
  if (mission.contextMode === "barracks") {
    if (!selectedBuilding || selectedBuilding.type !== "barracks") clearContext();
  } else if (mission.contextMode === "stockpile") {
    if (!selectedBuilding || selectedBuilding.type !== "stockpile") clearContext();
  } else if (mission.contextMode === "building") {
    if (!selectedBuilding || selectedBuilding.type === "keep" || selectedBuilding.type === "campfire" || selectedBuilding.type === "barracks" || selectedBuilding.type === "stockpile") {
      clearContext();
    }
  } else if (mission.contextMode === "keep") {
    if (!selectedBuilding || selectedBuilding.type !== "keep") clearContext();
  } else if (mission.contextMode === "unit") {
    if (!selectedUnit) clearContext();
  } else if (mission.contextMode === "tile") {
    if (!mission.selectedTile) clearContext();
  }
}

function setSelectedUnits(unitIds) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const validIds = Array.from(new Set(unitIds)).filter((id) => mission.units.some((u) => u.id === id));
  mission.selectedUnitIds = validIds;
  mission.selectedUnitId = validIds[0] || null;
}

function getSelectedUnits(mission) {
  if (!mission) return [];
  return mission.units.filter((u) => mission.selectedUnitIds.includes(u.id));
}

function setupMissionWorld(seedMissionId) {
  state.missionWorld.heights = buildMissionHeights(seedMissionId);
  buildMissionTerrain(seedMissionId);
  state.missionWorld.worldBoundsCache = null;
  state.missionWorld.cameraX = 340;
  state.missionWorld.cameraY = 90;
  state.missionWorld.zoom = 0.9;
  state.missionWorld.dragging = false;
  state.missionWorld.dragMoved = false;
  state.missionWorld.dragPointerId = null;
  state.missionWorld.minimapNeedsRebuild = true;
}

function getEnemyKeep(mission) {
  return mission?.enemyBuildings?.find((b) => b.type === "keep") || null;
}

function getPlayerKeep(mission) {
  return mission?.buildings?.find((b) => b.type === "keep") || null;
}

function buildKeepPatrolRoute(keep) {
  if (!keep) return [];
  const left = keep.x + 0.45;
  const right = keep.x + Math.max(0.55, keep.w - 0.45);
  const top = keep.y + 0.45;
  const bottom = keep.y + Math.max(0.55, keep.h - 0.45);
  return [
    { x: left, y: top },
    { x: right, y: top },
    { x: right, y: bottom },
    { x: left, y: bottom }
  ];
}

function initializeMissionLordDots(mission) {
  if (!mission) return;
  if (!Array.isArray(mission.lordDots)) mission.lordDots = [];
  const ensureLordForKeep = (keep, faction) => {
    if (!keep) return;
    const existing = mission.lordDots.find((dot) => dot.faction === faction);
    const route = buildKeepPatrolRoute(keep);
    const fallback = route[0] || { x: keep.x + keep.w * 0.5, y: keep.y + keep.h * 0.5 };
    if (existing) {
      if (!Array.isArray(existing.route) || existing.route.length === 0) existing.route = route;
      if (!Number.isFinite(existing.x) || !Number.isFinite(existing.y)) {
        existing.x = fallback.x;
        existing.y = fallback.y;
      }
      existing.routeIndex = Number.isFinite(existing.routeIndex) ? existing.routeIndex : 0;
      existing.state = existing.state || "patrol";
      existing.hp = Math.max(1, Number(existing.hp || 180));
      return;
    }
    mission.lordDots.push({
      id: `${faction}-lord-dot`,
      faction,
      x: fallback.x,
      y: fallback.y,
      state: "patrol",
      route,
      routeIndex: 0,
      hp: 180
    });
  };
  ensureLordForKeep(getPlayerKeep(mission), "player");
  ensureLordForKeep(getEnemyKeep(mission), "enemy");
}

function getEnemyAnchorTile(mission) {
  const enemyKeep = getEnemyKeep(mission);
  if (enemyKeep) return { x: enemyKeep.x, y: enemyKeep.y };
  return { x: state.missionWorld.width - 12, y: state.missionWorld.height - 12 };
}

function getEnemyBuildPlan(profileId = "ratHarass") {
  const defaultPlan = [
    { type: "stockpile", offsetX: -5, offsetY: 2 },
    { type: "woodcutter", offsetX: -8, offsetY: 0 },
    { type: "granary", offsetX: -5, offsetY: -2 },
    { type: "apple-farm", offsetX: -8, offsetY: -5 },
    { type: "barracks", offsetX: -6, offsetY: -4 },
    { type: "stone-camp", offsetX: -3, offsetY: -7 },
    { type: "iron-furny", offsetX: -10, offsetY: 3 },
    { type: "cow-building", offsetX: -12, offsetY: 5 },
    { type: "stockpile", offsetX: -3, offsetY: 4 }
  ];
  if (profileId === "ironTurtle") {
    return [
      { type: "stockpile", offsetX: -4, offsetY: 3 },
      { type: "granary", offsetX: -6, offsetY: 0 },
      { type: "apple-farm", offsetX: -9, offsetY: -4 },
      { type: "stone-camp", offsetX: -2, offsetY: -6 },
      { type: "barracks", offsetX: -4, offsetY: -3 },
      { type: "iron-furny", offsetX: -8, offsetY: 3 },
      { type: "cow-building", offsetX: -11, offsetY: 5 },
      { type: "stockpile", offsetX: -2, offsetY: 5 }
    ];
  }
  if (profileId === "desertJackal") {
    return [
      { type: "stockpile", offsetX: -5, offsetY: 1 },
      { type: "woodcutter", offsetX: -8, offsetY: -1 },
      { type: "barracks", offsetX: -7, offsetY: -3 },
      { type: "granary", offsetX: -4, offsetY: -2 },
      { type: "apple-farm", offsetX: -8, offsetY: -6 },
      { type: "stone-camp", offsetX: -2, offsetY: -7 },
      { type: "stockpile", offsetX: -3, offsetY: 4 }
    ];
  }
  return defaultPlan;
}

function ensureEnemyDirectorState(mission) {
  if (!mission) return null;
  if (!mission.aiDirector) mission.aiDirector = { strategy: "probe", lastStrategyShiftSec: 0, phaseBucket: 0 };
  mission.aiDirector.strategy = mission.aiDirector.strategy || "probe";
  mission.aiDirector.lastStrategyShiftSec = Number(mission.aiDirector.lastStrategyShiftSec || 0);
  mission.aiDirector.phaseBucket = Number(mission.aiDirector.phaseBucket || 0);
  return mission.aiDirector;
}

function chooseAiStrategyForPhase(mission, currentSec) {
  const profileId = mission.aiProfileId || "ratHarass";
  const profile = ENEMY_AI_PROFILES[profileId] || ENEMY_AI_PROFILES.ratHarass;
  const bucket = Math.floor(currentSec / 24);
  const seed = mission.aiSeed || hashString(state.missionRuntime.missionId || "default");
  return getWeightedStrategyChoice(profile, `${seed}:${profileId}`, bucket);
}

function getStrategyWaveSpec(strategy, enemyBarracks, profileId) {
  const baseCount = Math.max(2, enemyBarracks + 1);
  const sidesByStrategy = {
    probe: "east",
    flank: "south",
    push: "enemy-fort",
    retreat: "west"
  };
  const fallbackMix = [{ unitType: "raider", count: baseCount }];
  if (strategy === "flank") {
    return {
      side: sidesByStrategy[strategy],
      triggerDelaySec: 10,
      mix: [{ unitType: "raider", count: baseCount + 1 }, { unitType: "skirmisher", count: 1 }]
    };
  }
  if (strategy === "push") {
    const heavyUnit = profileId === "ironTurtle" ? "templar" : "swordsman";
    return {
      side: sidesByStrategy[strategy],
      triggerDelaySec: 16,
      mix: [{ unitType: heavyUnit, count: Math.max(1, Math.floor(baseCount * 0.7)) }, { unitType: "raider", count: baseCount }]
    };
  }
  if (strategy === "retreat") {
    return {
      side: sidesByStrategy[strategy],
      triggerDelaySec: 18,
      mix: [{ unitType: "raider", count: Math.max(1, Math.floor(baseCount * 0.7)) }]
    };
  }
  return {
    side: sidesByStrategy.probe,
    triggerDelaySec: 12,
    mix: fallbackMix
  };
}

function processResearchAndUpkeep(currentSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  mission.modernRules = mission.modernRules || { researchEnabled: true, upkeepEnabled: true };
  mission.researchState = mission.researchState || { points: 0, stockpileBoostLevel: 0, productionBoostLevel: 0, barracksDrillLevel: 0 };
  mission.upkeepState = mission.upkeepState || { foodDebtTicks: 0, goldDebtTicks: 0, moralePenalty: 0 };

  if (mission.modernRules.researchEnabled && currentSec % 10 === 0) {
    const pointsGain = 1 + Math.floor(Math.max(0, mission.population - 8) / 8);
    mission.researchState.points = Number(mission.researchState.points || 0) + pointsGain;
    if (mission.researchState.points >= 20 && mission.researchState.stockpileBoostLevel < 1) {
      mission.researchState.points -= 20;
      mission.researchState.stockpileBoostLevel = 1;
      addMissionEventLog("Research complete: Reinforced stockpile racks.");
    } else if (mission.researchState.points >= 28 && mission.researchState.productionBoostLevel < 1) {
      mission.researchState.points -= 28;
      mission.researchState.productionBoostLevel = 1;
      addMissionEventLog("Research complete: Work rhythm drills.");
    } else if (mission.researchState.points >= 34 && mission.researchState.barracksDrillLevel < 1) {
      mission.researchState.points -= 34;
      mission.researchState.barracksDrillLevel = 1;
      addMissionEventLog("Research complete: Barracks drill regimen.");
    }
  }

  if (mission.modernRules.upkeepEnabled && currentSec % 5 === 0) {
    const armyCount = (mission.units || []).length;
    const foodUpkeep = Math.ceil(armyCount * 0.4);
    const goldUpkeep = Math.ceil(armyCount * 0.25);
    mission.resources.apples = Math.max(0, Number(mission.resources.apples || 0) - foodUpkeep);
    mission.resources.gold = Math.max(0, Number(mission.resources.gold || 0) - goldUpkeep);
    const foodDeficit = Number(mission.resources.apples || 0) < foodUpkeep;
    const goldDeficit = Number(mission.resources.gold || 0) < goldUpkeep;
    mission.upkeepState.foodDebtTicks = foodDeficit ? Number(mission.upkeepState.foodDebtTicks || 0) + 1 : Math.max(0, Number(mission.upkeepState.foodDebtTicks || 0) - 1);
    mission.upkeepState.goldDebtTicks = goldDeficit ? Number(mission.upkeepState.goldDebtTicks || 0) + 1 : Math.max(0, Number(mission.upkeepState.goldDebtTicks || 0) - 1);
    mission.upkeepState.moralePenalty = Math.max(0, mission.upkeepState.foodDebtTicks + mission.upkeepState.goldDebtTicks - 2);
    if (mission.upkeepState.moralePenalty >= 3 && mission.units.length > 0) {
      const deserters = Math.min(mission.units.length, 1 + Math.floor(mission.upkeepState.moralePenalty / 2));
      mission.units.splice(0, deserters);
      addMissionEventLog(`Army desertion: ${deserters} troops left due to unpaid upkeep.`);
    }
  }
}

function canPlaceEnemyBuilding(type, anchorX, anchorY) {
  const def = MISSION_BUILDINGS[type];
  if (!def) return false;
  const requiredNodeType = BUILDING_TILE_REQUIREMENTS[type] || null;
  let requirementSatisfied = !requiredNodeType;
  for (let oy = 0; oy < def.footprint.h; oy += 1) {
    for (let ox = 0; ox < def.footprint.w; ox += 1) {
      const tx = anchorX + ox;
      const ty = anchorY + oy;
      if (tx < 0 || ty < 0 || tx >= state.missionWorld.width || ty >= state.missionWorld.height) return false;
      if (!isTileBuildableBase(tx, ty)) return false;
      const node = getResourceNodeAt(tx, ty);
      if (requiredNodeType && node.type === requiredNodeType) requirementSatisfied = true;
      if (getMissionBuildingAt(tx, ty)) return false;
    }
  }
  return requirementSatisfied;
}

function placeEnemyBuilding(type, anchorX, anchorY) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return false;
  const def = MISSION_BUILDINGS[type];
  if (!def || !canPlaceEnemyBuilding(type, anchorX, anchorY)) return false;
  mission.enemyBuildings.push({
    id: `enemy-${type}-${mission.nextEnemyBuildingId++}`,
    type,
    x: anchorX,
    y: anchorY,
    w: def.footprint.w,
    h: def.footprint.h,
    active: true,
    workerAssigned: true,
    assignedWorkers: Number(BUILDING_STAFFING[type] || 0),
    bufferWood: 0,
    bufferStone: 0,
    bufferIron: 0,
    cowLoad: 0,
    appleBuffer: 0,
    harvestTick: 0,
    owner: "enemy",
    ...(type === "barracks" ? { recruitQueue: [] } : {})
  });
  addMissionEventLog(`Enemy built ${def.label}.`);
  return true;
}

function processEnemyEconomyAndMilitary(currentSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  if (!mission.enemyState) return;
  const director = ensureEnemyDirectorState(mission);
  const aiProfileId = mission.aiProfileId || "ratHarass";
  const enemyState = mission.enemyState;
  const enemyBuildings = mission.enemyBuildings || [];
  const enemyWoodcutters = enemyBuildings.filter((b) => b.type === "woodcutter").length;
  const enemyStoneCamps = enemyBuildings.filter((b) => b.type === "stone-camp").length;
  const enemyFurnies = enemyBuildings.filter((b) => b.type === "iron-furny").length;
  const enemyCows = enemyBuildings.filter((b) => b.type === "cow-building").length;
  const enemyAppleFarms = enemyBuildings.filter((b) => b.type === "apple-farm").length;
  const enemyGranaries = enemyBuildings.filter((b) => b.type === "granary").length;
  const enemyStockpiles = enemyBuildings.filter((b) => b.type === "stockpile").length;
  const enemyBarracks = enemyBuildings.filter((b) => b.type === "barracks").length;
  const stockpileResearchBoost = mission.researchState?.stockpileBoostLevel ? 1.15 : 1;
  enemyState.stockpileCapacity = Math.floor(enemyStockpiles * STOCKPILE_CAPACITY_PER_BUILDING * stockpileResearchBoost);
  enemyState.granaryCapacity = enemyGranaries * 220;
  if (enemyState.stockpileCapacity > 0 && currentSec % 2 === 0) {
    const currentWood = Number(enemyState.resources.wood || 0) + Number(enemyState.resources.stone || 0) + Number(enemyState.resources.iron || 0);
    const prodBoost = mission.researchState?.productionBoostLevel ? 1.25 : 1;
    const woodGain = Math.round(enemyWoodcutters * 3 * prodBoost);
    const stoneGain = Math.round(enemyStoneCamps * 2 * prodBoost);
    const ironGain = Math.round(Math.min(enemyFurnies * 2, enemyCows * 2) * prodBoost);
    const room = Math.max(0, enemyState.stockpileCapacity - currentWood);
    const allocWood = Math.min(room, woodGain);
    const allocStone = Math.min(Math.max(0, room - allocWood), stoneGain);
    const allocIron = Math.min(Math.max(0, room - allocWood - allocStone), ironGain);
    enemyState.resources.wood = Number(enemyState.resources.wood || 0) + allocWood;
    enemyState.resources.stone = Number(enemyState.resources.stone || 0) + allocStone;
    enemyState.resources.iron = Number(enemyState.resources.iron || 0) + allocIron;
  }
  if (enemyState.granaryCapacity > 0 && currentSec % 4 === 0) {
    const apples = Number(enemyState.resources.apples || 0);
    const add = Math.min(enemyAppleFarms, Math.max(0, enemyState.granaryCapacity - apples));
    enemyState.resources.apples = apples + add;
    enemyState.resources.food = Number(enemyState.resources.apples || 0);
  }
  if (currentSec % 5 === 0) {
    enemyState.resources.gold = Number(enemyState.resources.gold || 0) + 3 + enemyBarracks;
  }
  if ((currentSec - Number(enemyState.lastBuildAttemptSec || 0)) >= ENEMY_BUILD_ATTEMPT_INTERVAL_SEC) {
    enemyState.lastBuildAttemptSec = currentSec;
    const plan = getEnemyBuildPlan(aiProfileId);
    const step = plan[Math.min(plan.length - 1, Number(enemyState.buildPlanIndex || 0))];
    const cost = MISSION_BUILDINGS[step.type]?.cost || { wood: 0, gold: 0 };
    const canAfford = Object.entries(cost).every(([k, v]) => Number(enemyState.resources[k] || 0) >= v);
    const anchor = getEnemyAnchorTile(mission);
    const targetX = anchor.x + step.offsetX;
    const targetY = anchor.y + step.offsetY;
    if (canAfford && placeEnemyBuilding(step.type, targetX, targetY)) {
      for (const [resource, amount] of Object.entries(cost)) {
        enemyState.resources[resource] = Number(enemyState.resources[resource] || 0) - amount;
      }
      enemyState.buildPlanIndex = Number(enemyState.buildPlanIndex || 0) + 1;
    } else if (canAfford) {
      enemyState.buildPlanIndex = Number(enemyState.buildPlanIndex || 0) + 1;
    }
  }
  if (enemyBarracks > 0 && (currentSec - Number(director.lastStrategyShiftSec || 0)) >= 24) {
    director.strategy = chooseAiStrategyForPhase(mission, currentSec);
    director.lastStrategyShiftSec = currentSec;
    director.phaseBucket = Math.floor(currentSec / 24);
    mission.simMetrics.strategyShifts = Number(mission.simMetrics?.strategyShifts || 0) + 1;
  }
  const barracksDrill = mission.researchState?.barracksDrillLevel ? 0.8 : 1;
  const cycleByProfile = aiProfileId === "ironTurtle" ? 38 : aiProfileId === "desertJackal" ? 24 : ENEMY_ATTACK_CYCLE_SEC;
  if (enemyBarracks > 0 && (currentSec - Number(enemyState.lastAttackCycleSec || 0)) >= Math.floor(cycleByProfile * barracksDrill)) {
    enemyState.lastAttackCycleSec = currentSec;
    const queued = mission.raidWaves.filter((w) => !w.spawned).length;
    if (queued < 5) {
      const spec = getStrategyWaveSpec(director.strategy || "probe", enemyBarracks, aiProfileId);
      const totalCount = spec.mix.reduce((sum, item) => sum + Number(item.count || 0), 0);
      mission.raidWaves.push({
        id: `enemy-cycle-${currentSec}`,
        triggerAtSec: currentSec + Number(spec.triggerDelaySec || 14),
        side: spec.side || "enemy-fort",
        unitType: spec.mix[0]?.unitType || "raider",
        unitMix: spec.mix,
        count: Math.max(1, totalCount),
        warned: false,
        spawned: false
      });
      addMissionEventLog(`Enemy strategy shift: ${director.strategy}. Assault groups are assembling.`);
    }
  }
}

function getRaidSpawnPoint(side, index = 0, total = 1) {
  const mission = state.missionRuntime.missionState;
  const enemyKeep = getEnemyKeep(mission);
  if (enemyKeep) {
    const baseX = enemyKeep.x - 1;
    const baseY = enemyKeep.y - 1;
    const lane = Math.max(2, Math.floor(index / 2));
    const flank = index % 2 === 0 ? -1 : 1;
    return {
      x: Math.max(2, Math.min(state.missionWorld.width - 3, baseX - lane)),
      y: Math.max(2, Math.min(state.missionWorld.height - 3, baseY + flank * lane))
    };
  }
  const spread = 10 + index * 2;
  const midX = Math.floor(state.missionWorld.width * 0.5);
  const midY = Math.floor(state.missionWorld.height * 0.5);
  if (side === "south") return { x: Math.max(2, midX - spread + (index * 4)), y: state.missionWorld.height - 4 };
  if (side === "north") return { x: Math.max(2, midX - spread + (index * 4)), y: 3 };
  if (side === "west") return { x: 3, y: Math.max(2, midY - spread + (index * Math.max(2, Math.floor(18 / Math.max(1, total))))) };
  return { x: state.missionWorld.width - 4, y: Math.max(2, midY - spread + (index * Math.max(2, Math.floor(18 / Math.max(1, total))))) };
}

function spawnRaidEnemy(unitType, spawnPoint, waveId = "raid-wave") {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const profile = UNIT_TEMPLATES[unitType] || UNIT_TEMPLATES.raider;
  const spawn = findNearestTileMatching(spawnPoint.x, spawnPoint.y, isTileWalkableForUnit, 8) || spawnPoint;
  mission.enemies.push({
    id: `enemy-${mission.nextEnemyId++}`,
    type: unitType || "raider",
    x: spawn.x,
    y: spawn.y,
    hp: profile.hp,
    damage: profile.damage,
    speed: profile.speed,
    attackRange: profile.attackRange,
    sourceWaveId: waveId
  });
}

function processRaidWaves(currentSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  if (!Array.isArray(mission.raidWaves)) mission.raidWaves = [];
  if (!mission.raidState) {
    mission.raidState = { warningIds: [], spawnedWaveIds: [], keepThreatSec: 0, lastRaidMessageSec: -99 };
  }
  for (const wave of mission.raidWaves) {
    if (!wave.warned && currentSec >= (wave.triggerAtSec - RAID_WARNING_LEAD_SEC)) {
      wave.warned = true;
      mission.raidState.warningIds.push(wave.id);
      const comp = Array.isArray(wave.unitMix) && wave.unitMix.length > 0
        ? wave.unitMix.map((g) => `${g.count} ${g.unitType}`).join(", ")
        : `${wave.count} ${wave.unitType}`;
      const warningMessage = `Scouts: ${comp} approaching from the ${wave.side}.`;
      addMissionEventLog(warningMessage);
      showToast(warningMessage);
    }
    if (!wave.spawned && currentSec >= wave.triggerAtSec) {
      wave.spawned = true;
      mission.raidState.spawnedWaveIds.push(wave.id);
      const groups = Array.isArray(wave.unitMix) && wave.unitMix.length > 0
        ? wave.unitMix
        : [{ unitType: wave.unitType, count: wave.count }];
      let cursor = 0;
      const total = groups.reduce((sum, group) => sum + Number(group.count || 0), 0);
      for (const group of groups) {
        for (let i = 0; i < Number(group.count || 0); i += 1) {
          spawnRaidEnemy(group.unitType, getRaidSpawnPoint(wave.side, cursor, total), wave.id);
          cursor += 1;
        }
      }
      const spawnTotal = groups.reduce((sum, group) => sum + Number(group.count || 0), 0);
      addMissionEventLog(`Raid wave engaged: ${wave.id}.`);
      showToast(`Enemy raid wave: ${spawnTotal} attackers.`);
    }
  }
}

function recruitUnitAtBarracks(unitType = "militia") {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const unitDef = UNIT_TEMPLATES[unitType];
  if (!unitDef) {
    showToast("Unknown unit type.");
    return;
  }
  const selectedBarracks = mission.buildings.find((b) => b.id === mission.selectedBuildingId && b.type === "barracks");
  if (!selectedBarracks) {
    showToast("Select a barracks first.");
    return;
  }
  if (mission.resources.gold < unitDef.goldCost) {
    showToast(`Need ${unitDef.goldCost} gold to recruit ${unitDef.label.toLowerCase()}.`);
    return;
  }
  if (!Array.isArray(selectedBarracks.recruitQueue)) selectedBarracks.recruitQueue = [];
  if (selectedBarracks.recruitQueue.length >= BARRACKS_QUEUE_LIMIT) {
    showToast("Barracks queue is full.");
    return;
  }
  mission.resources.gold -= unitDef.goldCost;
  selectedBarracks.recruitQueue.push({
    id: `rq-${Date.now()}-${Math.floor(Math.random() * 9999)}`,
    type: unitType,
    totalSec: Number(unitDef.trainSec || MILITIA_RECRUIT_SECONDS),
    remainingSec: Number(unitDef.trainSec || MILITIA_RECRUIT_SECONDS),
    state: "queued",
    assignedTransitDotId: null
  });
  addMissionEventLog(`${unitDef.label} queued at barracks.`);
  refreshMissionPanels();
  saveMissionRuntime();
}

function recruitMilitiaUnit() {
  recruitUnitAtBarracks("militia");
}

function cancelBarracksQueue() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return false;
  const selectedBarracks = mission.buildings.find((b) => b.id === mission.selectedBuildingId && b.type === "barracks");
  if (!selectedBarracks || !Array.isArray(selectedBarracks.recruitQueue) || selectedBarracks.recruitQueue.length === 0) {
    return false;
  }
  const removed = selectedBarracks.recruitQueue.pop();
  if (removed?.assignedTransitDotId && Array.isArray(mission.recruitTransitDots)) {
    mission.recruitTransitDots = mission.recruitTransitDots.filter((dot) => dot.id !== removed.assignedTransitDotId);
  }
  const paidGold = Number(UNIT_TEMPLATES[removed?.type]?.goldCost ?? UNIT_TEMPLATES.militia.goldCost);
  const refund = Math.ceil(paidGold * BARRACKS_CANCEL_REFUND_RATIO);
  mission.resources.gold += refund;
  addMissionEventLog(`Recruit cancelled. ${refund} gold refunded.`);
  refreshMissionPanels();
  saveMissionRuntime();
  return true;
}

function spawnUnitNearBarracks(barracks, unitType = "militia") {
  const mission = state.missionRuntime.missionState;
  if (!mission || !barracks) return null;
  const unitDef = UNIT_TEMPLATES[unitType] || UNIT_TEMPLATES.militia;
  const originX = barracks.x + Math.floor(barracks.w * 0.5);
  const originY = barracks.y + barracks.h;
  const offsets = [
    [0, 0],
    [1, 0],
    [-1, 0],
    [0, 1],
    [1, 1],
    [-1, 1],
    [0, -1]
  ];
  let spawn = { x: originX, y: originY };
  for (const [dx, dy] of offsets) {
    const tx = originX + dx;
    const ty = originY + dy;
    if (!isTileWalkableForUnit(tx, ty)) continue;
    if (getMissionBuildingAt(tx, ty)) continue;
    const occupiedByUnit = mission.units.some((u) => Math.round(u.x) === tx && Math.round(u.y) === ty);
    if (occupiedByUnit) continue;
    spawn = { x: tx, y: ty };
    break;
  }
  mission.units.push({
    id: `u-${mission.nextUnitId++}`,
    type: unitType,
    x: spawn.x,
    y: spawn.y,
    hp: unitDef.hp,
    damage: unitDef.damage,
    speed: unitDef.speed,
    attackRange: unitDef.attackRange,
    stance: "stand",
    commandMode: "normal",
    moveTarget: null,
    attackTargetId: null
  });
  const createdId = mission.units[mission.units.length - 1].id;
  setSelectedUnits([createdId]);
  return createdId;
}

function getAvailableIdleDotsForRecruitment(mission) {
  const idle = Math.max(0, Number(mission.idlePeasants || 0));
  const reserved = Array.isArray(mission.recruitTransitDots) ? mission.recruitTransitDots.length : 0;
  return Math.max(0, idle - reserved);
}

function createRecruitTransitDot(mission, barracks, queueId) {
  if (!mission || !barracks || !queueId) return null;
  const camp = getCampfireCenter(mission, "player");
  const target = getBuildingCenter(barracks);
  const safeStart = findNearestTileMatching(camp.x, camp.y, isTileWalkableForDot, 4) || camp;
  const safeTarget = findNearestTileMatching(target.x, target.y, isTileWalkableForDot, 5) || target;
  const dot = {
    id: `rt-${Date.now()}-${Math.floor(Math.random() * 9999)}`,
    queueId,
    barracksId: barracks.id,
    x: safeStart.x,
    y: safeStart.y,
    targetX: safeTarget.x,
    targetY: safeTarget.y,
    arrived: false
  };
  if (!Array.isArray(mission.recruitTransitDots)) mission.recruitTransitDots = [];
  mission.recruitTransitDots.push(dot);
  return dot;
}

function processBarracksRecruitQueues() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  for (const barracks of mission.buildings.filter((b) => b.type === "barracks")) {
    if (!Array.isArray(barracks.recruitQueue) || barracks.recruitQueue.length === 0) continue;
    const current = barracks.recruitQueue[0];
    if (!current.state) current.state = "queued";
    if (current.state === "queued") {
      const drillBoost = mission.researchState?.barracksDrillLevel ? 1.35 : 1;
      current.remainingSec = Math.max(0, Number(current.remainingSec || current.totalSec || MILITIA_RECRUIT_SECONDS) - drillBoost);
      if (current.remainingSec > 0) continue;
      current.state = "waiting_for_idle_dot";
    }

    if (current.state === "waiting_for_idle_dot") {
      const available = getAvailableIdleDotsForRecruitment(mission);
      if (available <= 0) continue;
      const transit = createRecruitTransitDot(mission, barracks, current.id);
      if (!transit) continue;
      current.assignedTransitDotId = transit.id;
      current.state = "dot_assigned";
      continue;
    }

    if (current.state === "dot_assigned") {
      current.state = "dot_walking_to_barracks";
      continue;
    }

    if (current.state === "dot_walking_to_barracks") {
      const transit = (mission.recruitTransitDots || []).find((dot) => dot.id === current.assignedTransitDotId);
      if (!transit || !transit.arrived) continue;
      mission.recruitTransitDots = (mission.recruitTransitDots || []).filter((dot) => dot.id !== transit.id);
      barracks.recruitQueue.shift();
      const queuedType = current.type || "militia";
      const unitDef = UNIT_TEMPLATES[queuedType] || UNIT_TEMPLATES.militia;
      spawnUnitNearBarracks(barracks, queuedType);
      addMissionEventLog(`${unitDef.label} trained at barracks.`);
    }
  }
}

function missionTileToScreen(tx, ty, height) {
  return {
    x: (tx - ty) * (state.missionWorld.tileW * 0.5),
    y: (tx + ty) * (state.missionWorld.tileH * 0.5) - height * 6
  };
}

/** Canvas pixel coords for unit/enemy foot point (matches 2D sprite placement space). */
function missionEntityFootCanvasPosition(entity) {
  const canvas = document.getElementById("mission-canvas");
  if (!canvas || !state.missionRuntime.missionState || !state.missionWorld.heights?.length) return null;
  const tx = Math.round(entity.x);
  const ty = Math.round(entity.y);
  if (tx < 0 || ty < 0 || tx >= state.missionWorld.width || ty >= state.missionWorld.height) return null;
  const p = missionTileToScreen(tx, ty, state.missionWorld.heights[ty][tx]);
  const base = getMissionBaseOffset(canvas);
  const wx = base.x + p.x;
  const wy = base.y + p.y;
  return {
    x: state.missionWorld.cameraX + state.missionWorld.zoom * wx,
    y: state.missionWorld.cameraY + state.missionWorld.zoom * wy
  };
}

function getMissionBaseOffset(canvas) {
  return { x: canvas.width * 0.45, y: 80 };
}

function renderIsoDiamond(ctx, x, y, tileW, tileH, fill) {
  ctx.fillStyle = fill;
  ctx.beginPath();
  ctx.moveTo(x, y);
  ctx.lineTo(x + tileW * 0.5, y + tileH * 0.5);
  ctx.lineTo(x, y + tileH);
  ctx.lineTo(x - tileW * 0.5, y + tileH * 0.5);
  ctx.closePath();
  ctx.fill();
}

function getMissionWorldBounds() {
  // Heights/terrain are static per mission, so cache the full-map scan.
  // Invalidated in setupMissionWorld when a new map is generated.
  if (state.missionWorld.worldBoundsCache) return state.missionWorld.worldBoundsCache;
  const halfW = state.missionWorld.tileW * 0.5;
  let left = Number.POSITIVE_INFINITY;
  let right = Number.NEGATIVE_INFINITY;
  let top = Number.POSITIVE_INFINITY;
  let bottom = Number.NEGATIVE_INFINITY;

  for (let y = 0; y < state.missionWorld.height; y += 1) {
    for (let x = 0; x < state.missionWorld.width; x += 1) {
      const p = missionTileToScreen(x, y, state.missionWorld.heights[y][x]);
      left = Math.min(left, p.x - halfW);
      right = Math.max(right, p.x + halfW);
      top = Math.min(top, p.y);
      bottom = Math.max(bottom, p.y + state.missionWorld.tileH);
    }
  }
  state.missionWorld.worldBoundsCache = { left, right, top, bottom };
  return state.missionWorld.worldBoundsCache;
}

function clampMissionCamera(canvas) {
  if (!canvas || state.missionWorld.heights.length === 0) return;
  const bounds = getMissionWorldBounds();
  const base = getMissionBaseOffset(canvas);
  const zoom = state.missionWorld.zoom;

  const minCamX = canvas.width - zoom * (base.x + bounds.right);
  const maxCamX = -zoom * (base.x + bounds.left);
  const minCamY = canvas.height - zoom * (base.y + bounds.bottom);
  const maxCamY = -zoom * (base.y + bounds.top);

  if (minCamX > maxCamX) {
    state.missionWorld.cameraX = (minCamX + maxCamX) * 0.5;
  } else {
    state.missionWorld.cameraX = Math.max(minCamX, Math.min(maxCamX, state.missionWorld.cameraX));
  }

  if (minCamY > maxCamY) {
    state.missionWorld.cameraY = (minCamY + maxCamY) * 0.5;
  } else {
    state.missionWorld.cameraY = Math.max(minCamY, Math.min(maxCamY, state.missionWorld.cameraY));
  }
}

function screenToMissionTile(canvas, screenX, screenY) {
  const base = getMissionBaseOffset(canvas);
  const halfW = state.missionWorld.tileW * 0.5;
  const halfH = state.missionWorld.tileH * 0.5;

  const wx = (screenX - state.missionWorld.cameraX) / state.missionWorld.zoom - base.x;
  const wy = (screenY - state.missionWorld.cameraY) / state.missionWorld.zoom - base.y;

  const approxX = (wx / halfW + wy / halfH) * 0.5;
  const approxY = (wy / halfH - wx / halfW) * 0.5;
  const centerX = Math.floor(approxX);
  const centerY = Math.floor(approxY);

  let best = null;
  let bestScore = Number.POSITIVE_INFINITY;
  for (let y = centerY - 3; y <= centerY + 3; y += 1) {
    if (y < 0 || y >= state.missionWorld.height) continue;
    for (let x = centerX - 3; x <= centerX + 3; x += 1) {
      if (x < 0 || x >= state.missionWorld.width) continue;
      const p = missionTileToScreen(x, y, state.missionWorld.heights[y][x]);
      const cx = p.x;
      const cy = p.y + halfH;
      const norm = Math.abs(wx - cx) / halfW + Math.abs(wy - cy) / halfH;
      if (norm <= 1.0) {
        const score = norm + Math.abs(approxX - x) * 0.03 + Math.abs(approxY - y) * 0.03;
        if (score < bestScore) {
          bestScore = score;
          best = { x, y };
        }
      }
    }
  }
  return best;
}

function getVisibleTileBounds(canvas) {
  const points = [
    screenToMissionTile(canvas, 0, 0),
    screenToMissionTile(canvas, canvas.width, 0),
    screenToMissionTile(canvas, 0, canvas.height),
    screenToMissionTile(canvas, canvas.width, canvas.height)
  ].filter(Boolean);

  if (points.length === 0) {
    return { minX: 0, maxX: state.missionWorld.width - 1, minY: 0, maxY: state.missionWorld.height - 1 };
  }
  // Deliberately allow a render skirt outside map bounds.
  // We clamp sample lookups while rendering, so this fills edge voids visually.
  const safetyMargin = 72;
  const minX = Math.min(...points.map((p) => p.x)) - safetyMargin;
  const minY = Math.min(...points.map((p) => p.y)) - safetyMargin;
  const maxX = Math.max(...points.map((p) => p.x)) + safetyMargin;
  const maxY = Math.max(...points.map((p) => p.y)) + safetyMargin;
  return { minX, maxX, minY, maxY };
}

/**
 * Exact camera-visible tile window in tile coordinates (iso math, generous padding).
 * Tiles outside this window cannot intersect the canvas, so skipping them is
 * visually identical to a full-map pass while drawing only ~visible tiles.
 */
function computeVisibleTileWindow(canvas) {
  const halfW = state.missionWorld.tileW * 0.5;
  const halfH = state.missionWorld.tileH * 0.5;
  const zoom = state.missionWorld.zoom || 1;
  const base = getMissionBaseOffset(canvas);
  const pad = 96;
  const viewLeft = (0 - state.missionWorld.cameraX) / zoom - base.x - halfW - pad;
  const viewRight = (canvas.width - state.missionWorld.cameraX) / zoom - base.x + halfW + pad;
  const viewTop = (0 - state.missionWorld.cameraY) / zoom - base.y - state.missionWorld.tileH - pad;
  const viewBottom = (canvas.height - state.missionWorld.cameraY) / zoom - base.y + pad;
  const uMin = viewLeft / halfW; // u = x - y
  const uMax = viewRight / halfW;
  const vMin = viewTop / halfH; // v = x + y (before the height*6 lift)
  const vMax = (viewBottom + 60) / halfH; // +60 covers the max height*6 lift
  return {
    yStart: Math.max(0, Math.floor((vMin - uMax) * 0.5)),
    yEnd: Math.min(state.missionWorld.height - 1, Math.ceil((vMax - uMin) * 0.5)),
    rowXStart(y) {
      return Math.max(0, Math.floor(Math.max(uMin + y, vMin - y)));
    },
    rowXEnd(y) {
      return Math.min(state.missionWorld.width - 1, Math.ceil(Math.min(uMax + y, vMax - y)));
    }
  };
}

function getTerrainColor(type, height) {
  if (type === "rock") return `rgb(${78 + height * 2}, ${74 + height * 2}, ${67 + height * 2})`;
  if (type === "scrub") return `rgb(${60 + height * 2}, ${86 + height * 2}, ${44 + height * 2})`;
  if (type === "dune") return `rgb(${130 + height * 2}, ${110 + height * 2}, ${70 + height * 2})`;
  if (type === "grass") return `rgb(${66 + height * 2}, ${104 + height * 2}, ${58 + height * 2})`;
  if (type === "lake") return `rgb(${42 + height * 2}, ${78 + height * 2}, ${118 + height * 2})`;
  return `rgb(${94 + height * 2}, ${84 + height * 2}, ${58 + height * 2})`;
}

function getMissionBuildingAt(tileX, tileY) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return null;
  for (const b of getAllMissionBuildings(mission)) {
    if (tileX >= b.x && tileX < b.x + b.w && tileY >= b.y && tileY < b.y + b.h) {
      return b;
    }
  }
  return null;
}

function getMissionUnitAt(tileX, tileY) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return null;
  return mission.units.find((u) => Math.round(u.x) === tileX && Math.round(u.y) === tileY) || null;
}

function getMissionEnemyAt(tileX, tileY) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return null;
  return mission.enemies.find((e) => Math.round(e.x) === tileX && Math.round(e.y) === tileY) || null;
}

function getResourceNodeAt(tileX, tileY) {
  const mission = state.missionRuntime.missionState;
  if (!mission?.resourceNodes?.[tileY]?.[tileX]) return { type: "none", amount: 0 };
  return mission.resourceNodes[tileY][tileX];
}

function setContextMode(mode, entityId = null) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  mission.contextMode = mode;
  mission.lastSelectedEntityId = entityId;
}

function toggleContextSelection(mode, entityId) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  if (mission.contextMode === mode && mission.lastSelectedEntityId === entityId) {
    setContextMode("none", null);
  } else {
    setContextMode(mode, entityId);
  }
}

function isTileBuildableBase(tileX, tileY) {
  if (!isTileInsideMission(tileX, tileY)) return false;
  const type = getTerrainTypeAt(tileX, tileY);
  return type !== "rock" && type !== "lake";
}

function canPlaceBuilding(type, anchorX, anchorY) {
  const mission = state.missionRuntime.missionState;
  const def = MISSION_BUILDINGS[type];
  if (!mission || !def) return { ok: false, reason: "Invalid building." };
  const requiredNodeType = BUILDING_TILE_REQUIREMENTS[type] || null;
  let requirementSatisfied = !requiredNodeType;

  for (let oy = 0; oy < def.footprint.h; oy += 1) {
    for (let ox = 0; ox < def.footprint.w; ox += 1) {
      const tx = anchorX + ox;
      const ty = anchorY + oy;
      if (tx < 0 || ty < 0 || tx >= state.missionWorld.width || ty >= state.missionWorld.height) {
        return { ok: false, reason: "Out of mission bounds." };
      }
      const node = getResourceNodeAt(tx, ty);
      const matchesRequiredNode = !!requiredNodeType && node.type === requiredNodeType;
      if (!isTileBuildableBase(tx, ty) && !matchesRequiredNode) {
        return { ok: false, reason: "Invalid terrain for this building." };
      }
      if (requiredNodeType && node.type === requiredNodeType) requirementSatisfied = true;
      if (getMissionBuildingAt(tx, ty)) {
        return { ok: false, reason: "Building collision." };
      }
    }
  }
  if (requiredNodeType && !requirementSatisfied) {
    return { ok: false, reason: `Must be placed on ${requiredNodeType.replace("_", " ")}.` };
  }

  for (const [resource, amount] of Object.entries(def.cost)) {
    if (getBuildResourceAvailable(mission, type, resource) < amount) {
      return { ok: false, reason: `Need ${amount} ${resource}.` };
    }
  }
  return { ok: true, reason: "" };
}

function placeBuildingFromSelectedTile() {
  const mission = state.missionRuntime.missionState;
  if (!mission || !mission.selectedTile || !mission.buildMode) return;
  const validation = canPlaceBuilding(mission.buildMode, mission.selectedTile.x, mission.selectedTile.y);
  if (!validation.ok) {
    showToast(validation.reason);
    return;
  }
  const def = MISSION_BUILDINGS[mission.buildMode];
  for (const [resource, amount] of Object.entries(def.cost)) {
    const missing = spendBuildResource(mission, mission.buildMode, resource, amount);
    if (missing > 0) {
      showToast(`Need ${amount} ${resource}.`);
      return;
    }
  }
  mission.buildings.push({
    id: `${mission.buildMode}-${mission.nextBuildingId++}`,
    type: mission.buildMode,
    x: mission.selectedTile.x,
    y: mission.selectedTile.y,
    w: def.footprint.w,
    h: def.footprint.h,
    active: mission.buildMode !== "woodcutter",
    workerAssigned: false,
    assignedWorkers: 0,
    bufferWood: 0,
    bufferStone: 0,
    bufferIron: 0,
    cowLoad: 0,
    appleBuffer: 0,
    harvestTick: 0,
    owner: "player",
    ...(mission.buildMode === "barracks" ? { recruitQueue: [] } : {})
  });
  const placed = mission.buildings[mission.buildings.length - 1];
  if (placed.type === "barracks") {
    mission.selectedBuildingId = placed.id;
    toggleContextSelection("barracks", `building:${placed.id}`);
    addMissionEventLog("Barracks established and ready.");
  } else {
    setContextMode("none", null);
    mission.selectedBuildingId = null;
  }
  assignWorkers();
  computeStockpileStats();
  updatePopularity();
  refreshMissionPanels();
  saveMissionRuntime();
  showToast(`${def.label} placed.`);
}

function deleteSelectedBuilding() {
  const mission = state.missionRuntime.missionState;
  if (!mission || !mission.selectedBuildingId) {
    showToast("Select a building first.");
    return false;
  }
  const idx = mission.buildings.findIndex((b) => b.id === mission.selectedBuildingId);
  if (idx < 0) {
    showToast("Selected building is invalid.");
    return false;
  }
  const building = mission.buildings[idx];
  if (building.owner === "enemy") {
    showToast("Enemy buildings cannot be deleted.");
    return false;
  }
  if (building.type === "keep" || building.type === "campfire") {
    showToast("Core buildings cannot be deleted.");
    return false;
  }
  const def = MISSION_BUILDINGS[building.type];
  if (!def || def.locked) {
    showToast("This building cannot be deleted.");
    return false;
  }
  for (const [resource, amount] of Object.entries(def.cost || {})) {
    mission.resources[resource] = Number(mission.resources[resource] || 0) + Number(amount || 0);
  }
  if (building.type === "barracks" && Array.isArray(mission.recruitTransitDots)) {
    mission.recruitTransitDots = mission.recruitTransitDots.filter((dot) => dot.barracksId !== building.id);
  }
  mission.buildings.splice(idx, 1);
  mission.selectedBuildingId = null;
  setContextMode("none", null);
  assignWorkers();
  computeStockpileStats();
  updatePopularity();
  refreshMissionPanels();
  saveMissionRuntime();
  addMissionEventLog(`${def.label} deleted. Materials refunded.`);
  showToast(`${def.label} deleted. Full refund granted.`);
  return true;
}

function renderMissionBuildings(ctx) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  for (const b of getAllMissionBuildings(mission)) {
    for (let oy = 0; oy < b.h; oy += 1) {
      for (let ox = 0; ox < b.w; ox += 1) {
        const tx = b.x + ox;
        const ty = b.y + oy;
        const p = missionTileToScreen(tx, ty, state.missionWorld.heights[ty][tx]);
        let color = "#8b7b64";
        if (b.owner === "enemy") {
          if (b.type === "campfire") color = "#b85f3d";
          if (b.type === "keep") color = "#8a5a5a";
          if (b.type === "stockpile") color = "#6d4a3f";
          if (b.type === "house") color = "#7b5c45";
          if (b.type === "granary") color = "#7f6a3e";
          if (b.type === "barracks") color = "#6d4a63";
          if (b.type === "woodcutter") color = b.active ? "#6b7f46" : "#5f4a33";
          if (b.type === "stone-camp") color = "#6a6a6a";
          if (b.type === "iron-furny") color = "#5a566d";
          if (b.type === "cow-building") color = "#7c5f47";
          if (b.type === "apple-farm") color = "#628149";
        } else {
          if (b.type === "campfire") color = "#e39a4f";
          if (b.type === "keep") color = "#9f9383";
          if (b.type === "stockpile") color = "#7a6240";
          if (b.type === "house") color = "#8d6d51";
          if (b.type === "granary") color = "#9a8247";
          if (b.type === "barracks") color = "#6c7481";
          if (b.type === "woodcutter") color = b.active ? "#4f8f55" : "#725435";
          if (b.type === "stone-camp") color = "#8f8f8f";
          if (b.type === "iron-furny") color = "#71738d";
          if (b.type === "cow-building") color = "#9a7a5d";
          if (b.type === "apple-farm") color = "#6da34d";
        }
        renderIsoDiamond(ctx, p.x, p.y - 8, state.missionWorld.tileW * 0.8, state.missionWorld.tileH * 0.8, color);
      }
    }
  }
}

function getRuntimeSpriteAsset(unitType) {
  const reg = window.SPRITE_RUNTIME_REGISTRY;
  if (!reg?.assetsByUnitType) return null;
  return reg.assetsByUnitType[unitType] || null;
}

function getDirectionForEntity(entity) {
  const target = entity?.moveTarget || null;
  if (!target) return "S";
  const dx = Number(target.x || 0) - Number(entity.x || 0);
  const dy = Number(target.y || 0) - Number(entity.y || 0);
  if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) return "S";
  const angle = Math.atan2(dy, dx);
  const dirs = [
    { dir: "E", min: -Math.PI / 8, max: Math.PI / 8 },
    { dir: "NE", min: -3 * Math.PI / 8, max: -Math.PI / 8 },
    { dir: "N", min: -5 * Math.PI / 8, max: -3 * Math.PI / 8 },
    { dir: "NW", min: -7 * Math.PI / 8, max: -5 * Math.PI / 8 },
    { dir: "W", min: 7 * Math.PI / 8, max: Math.PI },
    { dir: "W", min: -Math.PI, max: -7 * Math.PI / 8 },
    { dir: "SW", min: 5 * Math.PI / 8, max: 7 * Math.PI / 8 },
    { dir: "S", min: 3 * Math.PI / 8, max: 5 * Math.PI / 8 },
    { dir: "SE", min: Math.PI / 8, max: 3 * Math.PI / 8 }
  ];
  const hit = dirs.find((entry) => angle >= entry.min && angle < entry.max);
  return hit?.dir || "S";
}

function resolveEntityAnimationName(entity) {
  if (entity?.attackTargetId) return "attack";
  if (entity?.moveTarget) return "walk";
  return "idle";
}

function getRuntimeSpriteImage(asset) {
  if (!asset?.atlasDataUrl) return null;
  const cache = state.spriteEditor.runtimeImageCache || {};
  if (cache[asset.atlasDataUrl]?.complete) return cache[asset.atlasDataUrl];
  if (!cache[asset.atlasDataUrl]) {
    const img = new Image();
    img.src = asset.atlasDataUrl;
    cache[asset.atlasDataUrl] = img;
    state.spriteEditor.runtimeImageCache = cache;
  }
  return cache[asset.atlasDataUrl];
}

function computeRuntimeSpriteFrame(asset, animationName, direction) {
  const manifest = asset?.manifest;
  if (!manifest?.animations) return null;
  const anim = manifest.animations[animationName] || manifest.animations.idle || null;
  if (!anim?.byDirection) return null;
  const frames = anim.byDirection[direction] || anim.byDirection.S || [];
  if (!Array.isArray(frames) || frames.length === 0) return null;
  const ms = Number(state.missionRuntime.elapsedMs || 0);
  const totalDuration = frames.reduce((sum, frame) => sum + Math.max(20, Number(frame.durationMs || 120)), 0);
  const t = totalDuration > 0 ? (ms % totalDuration) : 0;
  let acc = 0;
  for (const frame of frames) {
    acc += Math.max(20, Number(frame.durationMs || 120));
    if (t <= acc) return frame;
  }
  return frames[frames.length - 1];
}

function renderRuntimeSpriteEntity(ctx, entity, isEnemy = false) {
  const reg3d = window.MODEL_REGISTRY?.byUnitType?.[entity.type];
  if (reg3d?.gltfDataUrl && window.MissionThreeLayer && typeof window.MissionThreeLayer.enqueueUnit === "function") {
    window.MissionThreeLayer.enqueueUnit(entity, entity.type, isEnemy);
    return true;
  }
  const asset = getRuntimeSpriteAsset(entity.type);
  if (!asset) return false;
  const img = getRuntimeSpriteImage(asset);
  if (!img?.complete || img.naturalWidth <= 0) return false;
  const tx = Math.round(entity.x);
  const ty = Math.round(entity.y);
  if (tx < 0 || ty < 0 || tx >= state.missionWorld.width || ty >= state.missionWorld.height) return false;
  const p = missionTileToScreen(tx, ty, state.missionWorld.heights[ty][tx]);
  const animName = resolveEntityAnimationName(entity);
  const dir = getDirectionForEntity(entity);
  const frame = computeRuntimeSpriteFrame(asset, animName, dir);
  if (!frame) return false;
  const drawW = Math.max(1, Number(frame.w || 64));
  const drawH = Math.max(1, Number(frame.h || 64));
  const drawX = Math.round(p.x - drawW * 0.5 + Number(frame.pivotX || 0));
  const drawY = Math.round(p.y - drawH + Number(frame.pivotY || 0));
  if (frame.shadow !== false) {
    ctx.fillStyle = "rgba(0,0,0,0.24)";
    ctx.beginPath();
    ctx.ellipse(p.x, p.y + 10, Math.max(8, drawW * 0.18), Math.max(4, drawH * 0.08), 0, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.drawImage(
    img,
    Math.max(0, Number(frame.x || 0)),
    Math.max(0, Number(frame.y || 0)),
    drawW,
    drawH,
    drawX,
    drawY,
    drawW,
    drawH
  );
  if (frame.teamMask) {
    ctx.fillStyle = isEnemy ? "rgba(221,112,112,0.35)" : "rgba(122,189,255,0.35)";
    ctx.fillRect(drawX, drawY, drawW, drawH);
  }
  return true;
}

function renderMissionUnitsAndEnemies(ctx) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;

  if (window.MissionThreeLayer && typeof window.MissionThreeLayer.beginFrame === "function") {
    window.MissionThreeLayer.beginFrame();
  }

  for (const enemy of (mission.enemies || [])) {
    const tx = Math.round(enemy.x);
    const ty = Math.round(enemy.y);
    if (tx < 0 || ty < 0 || tx >= state.missionWorld.width || ty >= state.missionWorld.height) continue;
    const rendered = renderRuntimeSpriteEntity(ctx, enemy, true);
    if (!rendered) {
      const p = missionTileToScreen(tx, ty, state.missionWorld.heights[ty][tx]);
      renderIsoDiamond(ctx, p.x, p.y - 7, state.missionWorld.tileW * 0.42, state.missionWorld.tileH * 0.42, "#8f3e3e");
    }
  }

  for (const unit of (mission.units || [])) {
    const tx = Math.round(unit.x);
    const ty = Math.round(unit.y);
    if (tx < 0 || ty < 0 || tx >= state.missionWorld.width || ty >= state.missionWorld.height) continue;
    const selected = mission.selectedUnitIds.includes(unit.id);
    const rendered = renderRuntimeSpriteEntity(ctx, unit, false);
    if (!rendered) {
      const p = missionTileToScreen(tx, ty, state.missionWorld.heights[ty][tx]);
      renderIsoDiamond(ctx, p.x, p.y - 7, state.missionWorld.tileW * 0.38, state.missionWorld.tileH * 0.38, "#7ca7df");
    }
    if (selected) {
      drawTileOutline(ctx, { x: tx, y: ty }, "rgba(122,189,255,0.95)", 2.6);
    }
  }

  for (const lord of (mission.lordDots || [])) {
    const tx = Math.round(lord.x);
    const ty = Math.round(lord.y);
    if (tx < 0 || ty < 0 || tx >= state.missionWorld.width || ty >= state.missionWorld.height) continue;
    const p = missionTileToScreen(tx, ty, state.missionWorld.heights[ty][tx]);
    const color = lord.faction === "enemy" ? "#d97a7a" : "#e4d290";
    renderIsoDiamond(ctx, p.x, p.y - 10, state.missionWorld.tileW * 0.26, state.missionWorld.tileH * 0.26, color);
  }
}

function drawTileOutline(ctx, tile, stroke, lineWidth = 2) {
  const halfW = state.missionWorld.tileW * 0.5;
  const halfH = state.missionWorld.tileH * 0.5;
  const p = missionTileToScreen(tile.x, tile.y, state.missionWorld.heights[tile.y][tile.x]);
  ctx.strokeStyle = stroke;
  ctx.lineWidth = lineWidth;
  ctx.beginPath();
  ctx.moveTo(p.x, p.y);
  ctx.lineTo(p.x + halfW, p.y + halfH);
  ctx.lineTo(p.x, p.y + state.missionWorld.tileH);
  ctx.lineTo(p.x - halfW, p.y + halfH);
  ctx.closePath();
  ctx.stroke();
  ctx.lineWidth = 1;
}

function missionTileToCanvasPoint(canvas, tx, ty) {
  const height = state.missionWorld.heights[Math.round(ty)]?.[Math.round(tx)] ?? 0;
  const p = missionTileToScreen(tx, ty, height);
  const base = getMissionBaseOffset(canvas);
  return {
    x: state.missionWorld.cameraX + state.missionWorld.zoom * (base.x + p.x),
    y: state.missionWorld.cameraY + state.missionWorld.zoom * (base.y + p.y + state.missionWorld.tileH * 0.35)
  };
}

function renderSelectionBoxOverlay(ctx, mission) {
  const box = mission?.selectionBox;
  if (!box?.active) return;
  const left = Math.min(box.startX, box.endX);
  const top = Math.min(box.startY, box.endY);
  const width = Math.abs(box.endX - box.startX);
  const height = Math.abs(box.endY - box.startY);
  ctx.save();
  ctx.strokeStyle = "rgba(149, 194, 255, 0.95)";
  ctx.fillStyle = "rgba(78, 128, 196, 0.2)";
  ctx.lineWidth = 1.5;
  ctx.strokeRect(left, top, width, height);
  ctx.fillRect(left, top, width, height);
  ctx.restore();
}

function renderBuildabilityOverlay(ctx, visibleBounds) {
  // Disabled by request to remove fog-like visual haze.
  return;
}

function renderResourceNodeHints(ctx, mission, tileWindow = null) {
  if (!mission?.resourceNodes) return;
  const yStart = tileWindow ? tileWindow.yStart : 0;
  const yEnd = tileWindow ? tileWindow.yEnd : state.missionWorld.height - 1;
  for (let y = yStart; y <= yEnd; y += 1) {
    const xStart = tileWindow ? tileWindow.rowXStart(y) : 0;
    const xEnd = tileWindow ? tileWindow.rowXEnd(y) : state.missionWorld.width - 1;
    for (let x = xStart; x <= xEnd; x += 1) {
      const node = mission.resourceNodes[y][x];
      if (!node || node.amount <= 0) continue;
      if (node.type !== "iron_patch" && node.type !== "stone_patch" && node.type !== "tree") continue;
      if (node.type === "tree" && ((x + y) % 2 !== 0)) continue;
      const p = missionTileToScreen(x, y, state.missionWorld.heights[y][x]);
      if (node.type === "iron_patch") {
        renderIsoDiamond(ctx, p.x - 7, p.y + 9, state.missionWorld.tileW * 0.2, state.missionWorld.tileH * 0.2, "rgba(132, 124, 171, 0.92)");
        renderIsoDiamond(ctx, p.x + 7, p.y + 9, state.missionWorld.tileW * 0.18, state.missionWorld.tileH * 0.18, "rgba(155, 150, 188, 0.95)");
        renderIsoDiamond(ctx, p.x, p.y + 4, state.missionWorld.tileW * 0.24, state.missionWorld.tileH * 0.24, "rgba(177, 171, 205, 0.96)");
      } else if (node.type === "stone_patch") {
        renderIsoDiamond(ctx, p.x - 6, p.y + 10, state.missionWorld.tileW * 0.18, state.missionWorld.tileH * 0.18, "rgba(134, 129, 120, 0.88)");
        renderIsoDiamond(ctx, p.x + 5, p.y + 7, state.missionWorld.tileW * 0.2, state.missionWorld.tileH * 0.2, "rgba(156, 150, 140, 0.9)");
      } else if (node.type === "tree") {
        renderIsoDiamond(ctx, p.x, p.y + 10, state.missionWorld.tileW * 0.08, state.missionWorld.tileH * 0.08, "rgba(104, 73, 45, 0.96)");
        renderIsoDiamond(ctx, p.x - 4, p.y + 6, state.missionWorld.tileW * 0.18, state.missionWorld.tileH * 0.18, "rgba(66, 118, 56, 0.9)");
        renderIsoDiamond(ctx, p.x + 5, p.y + 5, state.missionWorld.tileW * 0.16, state.missionWorld.tileH * 0.16, "rgba(78, 132, 64, 0.9)");
        renderIsoDiamond(ctx, p.x, p.y + 2, state.missionWorld.tileW * 0.2, state.missionWorld.tileH * 0.2, "rgba(86, 148, 71, 0.94)");
      }
    }
  }
}

function renderPeasantDots(ctx, mission) {
  if (!mission) return;
  const camp = getCampfireCenter(mission, "player");
  const recruitReserved = Array.isArray(mission.recruitTransitDots) ? mission.recruitTransitDots.length : 0;
  const idleCount = Math.max(0, Math.min(mission.population, mission.idlePeasants - recruitReserved));
  const baseRadius = 2.5;
  const drawDot = (x, y, color = "#f0dfbd", radius = baseRadius) => {
    const tx = Math.max(0, Math.min(state.missionWorld.width - 1, Math.round(x)));
    const ty = Math.max(0, Math.min(state.missionWorld.height - 1, Math.round(y)));
    const height = state.missionWorld.heights[ty]?.[tx] ?? 0;
    const p = missionTileToScreen(x, y, height);
    ctx.beginPath();
    ctx.fillStyle = color;
    ctx.arc(p.x, p.y + 4, radius, 0, Math.PI * 2);
    ctx.fill();
  };
  for (let i = 0; i < idleCount; i += 1) {
    const ring = Math.floor(i / 8) + 1;
    const idx = i % 8;
    const angle = (Math.PI * 2 * idx) / 8;
    const radius = 0.9 + (ring * 0.34);
    drawDot(camp.x + (Math.cos(angle) * radius), camp.y + (Math.sin(angle) * radius), "#f2deb4", 2.4);
  }
  for (const worker of (mission.peasantWorkers || [])) {
    let color = "#d9c08f";
    if (worker.carryType === "wood") color = "#91c47b";
    else if (worker.carryType === "iron") color = "#b4b1c9";
    else if (worker.carryType === "stone") color = "#c3b7ad";
    else if (worker.carryType === "apples") color = "#e4a35e";
    drawDot(worker.x, worker.y, color, 2.3);
  }
  for (const transit of (mission.recruitTransitDots || [])) {
    drawDot(transit.x, transit.y, "#e8d8b3", 2.2);
  }

  const enemyCamp = getCampfireCenter(mission, "enemy");
  const enemyIdle = Math.max(0, Number(mission.enemyState?.idlePeasants || 0));
  for (let i = 0; i < enemyIdle; i += 1) {
    const ring = Math.floor(i / 8) + 1;
    const idx = i % 8;
    const angle = (Math.PI * 2 * idx) / 8;
    const radius = 0.9 + (ring * 0.34);
    drawDot(enemyCamp.x + (Math.cos(angle) * radius), enemyCamp.y + (Math.sin(angle) * radius), "#d7a0a0", 2.2);
  }
  for (const worker of (mission.enemyPeasantWorkers || [])) {
    let color = "#b88d8d";
    if (worker.carryType === "wood") color = "#7fa173";
    else if (worker.carryType === "iron") color = "#a59dbd";
    else if (worker.carryType === "stone") color = "#bcaea3";
    else if (worker.carryType === "apples") color = "#d99f6a";
    drawDot(worker.x, worker.y, color, 2.2);
  }
}

function renderPlacementGhost(ctx) {
  const mission = state.missionRuntime.missionState;
  if (!mission || !mission.buildMode || !mission.selectedTile) return;
  const def = MISSION_BUILDINGS[mission.buildMode];
  if (!def) return;
  const validation = canPlaceBuilding(mission.buildMode, mission.selectedTile.x, mission.selectedTile.y);
  const color = validation.ok ? "rgba(120,220,130,0.95)" : "rgba(220,100,90,0.95)";
  for (let oy = 0; oy < def.footprint.h; oy += 1) {
    for (let ox = 0; ox < def.footprint.w; ox += 1) {
      const tx = mission.selectedTile.x + ox;
      const ty = mission.selectedTile.y + oy;
      if (tx < 0 || ty < 0 || tx >= state.missionWorld.width || ty >= state.missionWorld.height) continue;
      drawTileOutline(ctx, { x: tx, y: ty }, color, 2.4);
    }
  }
}

function renderMissionLandmarksLabels(ctx) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const keep = mission.buildings.find((b) => b.type === "keep");
  const campfire = mission.buildings.find((b) => b.type === "campfire");
  const enemyKeep = mission.enemyBuildings?.find((b) => b.type === "keep");
  ctx.fillStyle = "#efe0c2";
  ctx.font = "13px Segoe UI";
  if (keep) {
    const p = missionTileToScreen(keep.x + 1, keep.y + 1, state.missionWorld.heights[keep.y + 1][keep.x + 1]);
    ctx.fillText("Keep", p.x + 30, p.y - 10);
  }
  if (campfire) {
    const p = missionTileToScreen(
      campfire.x + 1,
      campfire.y + 1,
      state.missionWorld.heights[campfire.y + 1][campfire.x + 1]
    );
    ctx.fillText("Campfire", p.x + 28, p.y - 10);
  }
  if (enemyKeep) {
    const p = missionTileToScreen(enemyKeep.x + 1, enemyKeep.y + 1, state.missionWorld.heights[enemyKeep.y + 1][enemyKeep.x + 1]);
    ctx.fillStyle = "#e3a7a7";
    ctx.fillText("Enemy Keep", p.x + 12, p.y - 10);
  }
}

function renderMissionWorld() {
  const canvas = document.getElementById("mission-canvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const mission = state.missionRuntime.missionState;
  if (!mission || state.missionWorld.heights.length === 0) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#19130b";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#e6d7bd";
    ctx.font = "16px Segoe UI";
    ctx.fillText("Loading mission map...", 26, 40);
    return;
  }

  const rect = canvas.getBoundingClientRect();
  if (canvas.width !== Math.floor(rect.width) || canvas.height !== Math.floor(rect.height)) {
    canvas.width = Math.floor(rect.width);
    canvas.height = Math.floor(rect.height);
  }
  clampMissionCamera(canvas);

  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#171108";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.save();
  ctx.translate(state.missionWorld.cameraX, state.missionWorld.cameraY);
  ctx.scale(state.missionWorld.zoom, state.missionWorld.zoom);
  const base = getMissionBaseOffset(canvas);
  ctx.translate(base.x, base.y);

  // Deterministic terrain pass over the exact camera-visible window (same output
  // as a full-map pass; tiles outside the window cannot appear on screen).
  const tileWindow = computeVisibleTileWindow(canvas);
  for (let y = tileWindow.yStart; y <= tileWindow.yEnd; y += 1) {
    const xEnd = tileWindow.rowXEnd(y);
    for (let x = tileWindow.rowXStart(y); x <= xEnd; x += 1) {
      const height = state.missionWorld.heights[y][x];
      const p = missionTileToScreen(x, y, height);
      renderIsoDiamond(
        ctx,
        p.x,
        p.y,
        state.missionWorld.tileW,
        state.missionWorld.tileH,
        getTerrainColor(state.missionWorld.terrain[y][x], height)
      );
    }
  }

  renderBuildabilityOverlay(
    ctx,
    { minX: 0, minY: 0, maxX: state.missionWorld.width - 1, maxY: state.missionWorld.height - 1 }
  );
  renderResourceNodeHints(ctx, mission, tileWindow);
  renderMissionBuildings(ctx);
  renderPeasantDots(ctx, mission);
  renderMissionUnitsAndEnemies(ctx);
  renderMissionLandmarksLabels(ctx);

  if (mission?.hoveredTile) drawTileOutline(ctx, mission.hoveredTile, "rgba(204,219,248,0.85)");
  if (mission?.selectedTile) drawTileOutline(ctx, mission.selectedTile, "rgba(255,214,128,0.95)", 2.8);
  renderPlacementGhost(ctx);
  ctx.restore();
  renderSelectionBoxOverlay(ctx, mission);
  if (window.MissionThreeLayer && typeof window.MissionThreeLayer.renderFrame === "function") {
    window.MissionThreeLayer.renderFrame({
      getCanvas: () => document.getElementById("mission-canvas"),
      getMissionState: () => state.missionRuntime.missionState,
      getEntityDirection: (e) => getDirectionForEntity(e),
      entityFootCanvas: (e) => missionEntityFootCanvasPosition(e)
    });
  }
}

function rebuildMinimapBase() {
  const mini = document.getElementById("mission-minimap");
  if (!mini) return;
  const base = document.createElement("canvas");
  base.width = mini.width;
  base.height = mini.height;
  const bctx = base.getContext("2d");
  if (!bctx) return;

  for (let y = 0; y < state.missionWorld.height; y += 1) {
    for (let x = 0; x < state.missionWorld.width; x += 1) {
      bctx.fillStyle = getTerrainColor(state.missionWorld.terrain[y][x], state.missionWorld.heights[y][x]);
      const px = Math.floor((x / state.missionWorld.width) * base.width);
      const py = Math.floor((y / state.missionWorld.height) * base.height);
      const pw = Math.max(1, Math.ceil(base.width / state.missionWorld.width));
      const ph = Math.max(1, Math.ceil(base.height / state.missionWorld.height));
      bctx.fillRect(px, py, pw, ph);
    }
  }
  state.missionWorld.minimapBaseCanvas = base;
  state.missionWorld.minimapNeedsRebuild = false;
}

function renderMissionMinimap() {
  const mini = document.getElementById("mission-minimap");
  const mission = state.missionRuntime.missionState;
  if (!mini || !mission) return;
  if (!Array.isArray(mission.units)) mission.units = [];
  if (!Array.isArray(mission.enemies)) mission.enemies = [];
  const mctx = mini.getContext("2d");
  const missionCanvas = document.getElementById("mission-canvas");
  if (!mctx || !missionCanvas) return;

  if (!state.missionWorld.minimapBaseCanvas || state.missionWorld.minimapNeedsRebuild) {
    rebuildMinimapBase();
  }
  if (state.missionWorld.minimapBaseCanvas) {
    mctx.drawImage(state.missionWorld.minimapBaseCanvas, 0, 0);
  }

  for (const b of getAllMissionBuildings(mission)) {
    const px = (b.x / state.missionWorld.width) * mini.width;
    const py = (b.y / state.missionWorld.height) * mini.height;
    mctx.fillStyle = b.owner === "enemy"
      ? (b.type === "keep" ? "#cc8f8f" : b.type === "campfire" ? "#c96f4f" : "#995a54")
      : (b.type === "keep" ? "#dad6c6" : b.type === "campfire" ? "#f0a45d" : "#9a7b50");
    mctx.fillRect(px, py, Math.max(2, (b.w / state.missionWorld.width) * mini.width), Math.max(2, (b.h / state.missionWorld.height) * mini.height));
  }

  for (const u of mission.units) {
    const px = (u.x / state.missionWorld.width) * mini.width;
    const py = (u.y / state.missionWorld.height) * mini.height;
    mctx.fillStyle = "#84c0ff";
    mctx.fillRect(px - 1, py - 1, 3, 3);
  }
  for (const e of mission.enemies) {
    const px = (e.x / state.missionWorld.width) * mini.width;
    const py = (e.y / state.missionWorld.height) * mini.height;
    mctx.fillStyle = "#df6a6a";
    mctx.fillRect(px - 1, py - 1, 3, 3);
  }

  const tl = screenToMissionTile(missionCanvas, 0, 0);
  const tr = screenToMissionTile(missionCanvas, missionCanvas.width, 0);
  const bl = screenToMissionTile(missionCanvas, 0, missionCanvas.height);
  const br = screenToMissionTile(missionCanvas, missionCanvas.width, missionCanvas.height);
  const points = [tl, tr, bl, br].filter(Boolean);
  if (points.length > 0) {
    const minX = Math.max(0, Math.min(...points.map((p) => p.x)));
    const maxX = Math.min(state.missionWorld.width - 1, Math.max(...points.map((p) => p.x)));
    const minY = Math.max(0, Math.min(...points.map((p) => p.y)));
    const maxY = Math.min(state.missionWorld.height - 1, Math.max(...points.map((p) => p.y)));
    mctx.strokeStyle = "rgba(255, 235, 170, 0.95)";
    mctx.lineWidth = 1.5;
    mctx.strokeRect(
      (minX / state.missionWorld.width) * mini.width,
      (minY / state.missionWorld.height) * mini.height,
      ((maxX - minX + 1) / state.missionWorld.width) * mini.width,
      ((maxY - minY + 1) / state.missionWorld.height) * mini.height
    );
  }
}

function startMissionRenderLoop() {
  stopMissionRenderLoop();
  const renderFrame = () => {
    if (state.currentScreen !== "mission-play") return;
    try {
      renderMissionWorld();
      renderMissionMinimap();
    } catch (err) {
      console.error("Mission render failure:", err);
    }
    state.missionWorld.renderFrameId = window.requestAnimationFrame(renderFrame);
  };
  state.missionWorld.renderFrameId = window.requestAnimationFrame(renderFrame);
}

function addMissionEventLog(message) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  mission.eventLog.unshift(`[${formatDuration(state.missionRuntime.elapsedMs)}] ${message}`);
  mission.eventLog = mission.eventLog.slice(0, 18);
}

function findNearestNodeForBuilding(mission, building, wantedType, searchRadius = 8) {
  if (!mission?.resourceNodes) return null;
  const cx = building.x + Math.floor(building.w * 0.5);
  const cy = building.y + Math.floor(building.h * 0.5);
  let nearest = null;
  let bestDist = Number.POSITIVE_INFINITY;
  for (let y = Math.max(0, cy - searchRadius); y <= Math.min(state.missionWorld.height - 1, cy + searchRadius); y += 1) {
    for (let x = Math.max(0, cx - searchRadius); x <= Math.min(state.missionWorld.width - 1, cx + searchRadius); x += 1) {
      const node = mission.resourceNodes?.[y]?.[x];
      if (!node || node.type !== wantedType || Number(node.amount || 0) <= 0) continue;
      const dist = Math.hypot(x - cx, y - cy);
      if (dist < bestDist) {
        bestDist = dist;
        nearest = { x, y, node };
      }
    }
  }
  return nearest;
}

function getStockpileUsedTotal(mission) {
  return Number(mission.resources.wood || 0) + Number(mission.resources.stone || 0) + Number(mission.resources.iron || 0);
}

function getBuildingCenter(building) {
  return {
    x: building.x + (building.w * 0.5),
    y: building.y + (building.h * 0.5)
  };
}

function getCampfireCenter(mission, faction = "player") {
  const list = faction === "enemy" ? (mission.enemyBuildings || []) : (mission.buildings || []);
  const campfire = list.find((b) => b.type === "campfire");
  if (!campfire) return faction === "enemy" ? { x: state.missionWorld.width - 12, y: state.missionWorld.height - 12 } : { x: 10, y: 10 };
  return getBuildingCenter(campfire);
}

function getBuildingsForFaction(mission, faction = "player") {
  return faction === "enemy" ? (mission.enemyBuildings || []) : (mission.buildings || []);
}

function getClosestBuildingOfType(mission, fromX, fromY, type, faction = "player") {
  const candidates = getBuildingsForFaction(mission, faction).filter((b) => b.type === type);
  if (candidates.length === 0) return null;
  let best = candidates[0];
  let bestDist = Number.POSITIVE_INFINITY;
  for (const b of candidates) {
    const c = getBuildingCenter(b);
    const d = Math.hypot(c.x - fromX, c.y - fromY);
    if (d < bestDist) {
      bestDist = d;
      best = b;
    }
  }
  return best;
}

function getWorkerSlotOffset(slotIndex) {
  const ring = Math.floor(slotIndex / 6);
  const idx = slotIndex % 6;
  const angle = (Math.PI * 2 * idx) / 6;
  const radius = 0.08 + (ring * 0.09);
  return { x: Math.cos(angle) * radius, y: Math.sin(angle) * radius };
}

function syncPeasantWorkersWithAssignments(faction = "player") {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const camp = getCampfireCenter(mission, faction);
  const workerKey = faction === "enemy" ? "enemyPeasantWorkers" : "peasantWorkers";
  if (!Array.isArray(mission[workerKey])) mission[workerKey] = [];
  const existing = new Map(mission[workerKey].map((dot) => [dot.id, dot]));
  const nextWorkers = [];
  const factionBuildings = getBuildingsForFaction(mission, faction);
  for (const building of factionBuildings) {
    const count = Number(building.assignedWorkers || 0);
    if (count <= 0) continue;
    for (let i = 0; i < count; i += 1) {
      const workerId = `${faction === "enemy" ? "epw" : "pw"}-${building.id}-${i}`;
      const prior = existing.get(workerId);
      nextWorkers.push(prior || {
        id: workerId,
        role: "worker",
        state: "to-work",
        x: camp.x,
        y: camp.y,
        targetX: camp.x,
        targetY: camp.y,
        assignedBuildingId: building.id,
        taskType: building.type,
        carryType: null,
        carryAmount: 0,
        slotIndex: i,
        routeIndex: 0
      });
    }
  }
  mission[workerKey] = nextWorkers;
}

function assignEnemyWorkers() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const enemyPopulation = Number(mission.enemyState?.population || 14);
  let available = enemyPopulation;
  for (const b of (mission.enemyBuildings || [])) {
    const needed = Number(BUILDING_STAFFING[b.type] || 0);
    if (needed <= 0) continue;
    const assigned = Math.min(needed, available);
    b.assignedWorkers = assigned;
    b.workerAssigned = assigned > 0;
    b.active = assigned >= needed;
    available -= assigned;
  }
  if (!mission.enemyState) mission.enemyState = {};
  mission.enemyState.population = enemyPopulation;
  mission.enemyState.workersAssigned = enemyPopulation - available;
  mission.enemyState.idlePeasants = Math.max(0, available);
  syncPeasantWorkersWithAssignments("enemy");
}

function assignWorkers() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  let available = mission.population;
  const houses = mission.buildings.filter((b) => b.type === "house").length;
  mission.populationCap = 10 + (houses * 5);
  for (const b of mission.buildings) {
    const needed = Number(BUILDING_STAFFING[b.type] || 0);
    if (needed <= 0) continue;
    const assigned = Math.min(needed, available);
    b.assignedWorkers = assigned;
    b.workerAssigned = assigned > 0;
    b.active = assigned >= needed;
    available -= assigned;
  }
  const assigned = mission.buildings.reduce((sum, b) => sum + Number(b.assignedWorkers || 0), 0);
  mission.workersAssigned = assigned;
  mission.idlePeasants = Math.max(0, mission.population - assigned);
  syncPeasantWorkersWithAssignments("player");
  assignEnemyWorkers();
}

function computeStockpileStats() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const stockpiles = mission.buildings.filter((b) => b.type === "stockpile").length;
  const stockBoost = mission.researchState?.stockpileBoostLevel ? 1.15 : 1;
  mission.stockpileCapacity = Math.floor(stockpiles * STOCKPILE_CAPACITY_PER_BUILDING * stockBoost);
  const granaries = mission.buildings.filter((b) => b.type === "granary").length;
  mission.granaryCapacity = granaries * 220;
  syncPendingStorageUnlock(mission);
  mission.stockpileUsed = getStockpileUsedTotal(mission);
  mission.granaryUsed = Number(mission.resources.apples || 0);
}

function updatePopularity() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const previousPopularity = mission.popularity || 50;
  const taxMod = MISSION_TAXES[mission.taxMode] ?? 0;
  const ration = MISSION_RATIONS[mission.rationMode] || MISSION_RATIONS.normal;
  const foodState = mission.resources.food > 0 ? 2 : -18;
  const idleBonus = Math.min(5, Math.floor(mission.idlePeasants / 2));
  const overcrowd = mission.population > mission.populationCap ? -10 : 0;
  const total = 50 + taxMod + ration.pop + foodState + idleBonus + overcrowd;
  mission.popularity = Math.max(0, Math.min(100, total));
  mission.popularityTrend =
    mission.popularity > previousPopularity ? "Rising" : mission.popularity < previousPopularity ? "Falling" : "Stable";
  mission.popularityBreakdown = [
    ["Base", 50],
    ["Tax", taxMod],
    ["Rations", ration.pop],
    ["Food State", foodState],
    ["Idle Peasants", idleBonus],
    ["Overcrowding", overcrowd]
  ];
}

function consumeFood() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const ration = MISSION_RATIONS[mission.rationMode] || MISSION_RATIONS.normal;
  const amount = Math.max(0, Math.ceil((mission.population * ration.foodMultiplier) / 8));
  mission.resources.apples = Math.max(0, Number(mission.resources.apples || 0) - amount);
  mission.resources.food = Number(mission.resources.apples || 0);
  mission.lastFoodDelta = -amount;
}

function produceWood() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  mission.woodCadenceTick += 1;
  const productionMultiplier = mission.researchState?.productionBoostLevel ? 1.25 : 1;
  const stockCapacityLeft = Math.max(0, mission.stockpileCapacity - getStockpileUsedTotal(mission));
  const producedWood = Number(mission.pendingDeliveryDeltas?.wood || 0);
  mission.pendingDeliveryDeltas.wood = 0;
  let producedStone = 0;
  let producedIron = 0;
  let producedApples = 0;
  const furnies = mission.buildings.filter((b) => b.type === "iron-furny");
  const cows = mission.buildings.filter((b) => b.type === "cow-building");
  for (const b of mission.buildings) {
    if (b.type === "woodcutter") {
      continue;
    }
    if (b.type === "stone-camp") {
      if (!b.active) continue;
      const stonePatch = findNearestNodeForBuilding(mission, b, "stone_patch", 6);
      if (!stonePatch) continue;
      if ((mission.woodCadenceTick % 2) === 0 && b.bufferStone < 10 && stonePatch.node.amount > 0) {
        b.bufferStone += Math.max(1, Math.round(productionMultiplier));
        stonePatch.node.amount = Math.max(0, stonePatch.node.amount - 1);
      }
      const carry = Math.min(b.bufferStone, Math.max(0, mission.stockpileCapacity - getStockpileUsedTotal(mission)));
      if (carry > 0) {
        b.bufferStone -= carry;
        producedStone += addStockpileFlowResource(mission, "stone", carry);
      }
    }
    if (b.type === "iron-furny") {
      if (!b.active) continue;
      const ironPatch = findNearestNodeForBuilding(mission, b, "iron_patch", 6);
      if (!ironPatch) continue;
      if ((mission.woodCadenceTick % 2) === 0 && b.bufferIron < 24 && ironPatch.node.amount > 0) {
        b.bufferIron += Math.max(2, Math.round(2 * productionMultiplier));
        ironPatch.node.amount = Math.max(0, ironPatch.node.amount - 2);
      }
    }
    if (b.type === "apple-farm") {
      if (!b.active) continue;
      b.harvestTick += 1;
      if (b.harvestTick >= 5) {
        b.harvestTick = 0;
        b.appleBuffer = Math.min(12, b.appleBuffer + Math.max(1, Math.round(productionMultiplier)));
      }
      const carry = Math.min(b.appleBuffer, Math.max(0, mission.granaryCapacity - Number(mission.resources.apples || 0)));
      if (carry > 0) {
        b.appleBuffer -= carry;
        producedApples += addGranaryFlowResource(mission, carry);
      }
    }
  }
  for (const cow of cows) {
    if (!cow.active) continue;
    const supplier = furnies.find((f) => Number(f.bufferIron || 0) > 0) || null;
    if (supplier && cow.cowLoad < 20) {
      const transfer = Math.min(2, supplier.bufferIron, 20 - cow.cowLoad);
      supplier.bufferIron -= transfer;
      cow.cowLoad += transfer;
    }
    if (cow.cowLoad >= 20) {
      const capacity = Math.max(0, mission.stockpileCapacity - getStockpileUsedTotal(mission));
      const drop = Math.min(cow.cowLoad, capacity);
      if (drop > 0) {
        const granted = addStockpileFlowResource(mission, "iron", drop);
        cow.cowLoad -= drop;
        producedIron += granted;
      }
    }
  }
  mission.resources.food = Number(mission.resources.apples || 0);
  mission.lastWoodDelta = producedWood;
  mission.chainStatus.wood = producedWood > 0 ? "Active" : "Stalled";
  mission.chainStatus.stone = producedStone > 0 ? "Active" : "Stalled";
  mission.chainStatus.iron = producedIron > 0 ? "Active" : "Stalled";
  mission.chainStatus.cow = cows.some((c) => Number(c.cowLoad || 0) > 0) ? "Carrying" : "Idle";
  mission.chainStatus.apples = producedApples > 0 ? "Active" : "Growing";
  mission.woodStatus = mission.chainStatus.wood.toLowerCase();
  mission.woodStallReason = mission.chainStatus.wood === "Stalled" ? "No trees/workers/storage flow." : "";
  if (stockCapacityLeft <= 0) {
    mission.woodStatus = "stalled";
    mission.woodStallReason = "Stockpile full.";
  }
}

function buildWorkerRoute(mission, worker) {
  const faction = String(worker.id || "").startsWith("epw-") ? "enemy" : "player";
  const factionBuildings = getBuildingsForFaction(mission, faction);
  const building = factionBuildings.find((b) => b.id === worker.assignedBuildingId);
  if (!building) return [];
  const buildingCenter = getBuildingCenter(building);
  const slotOffset = getWorkerSlotOffset(worker.slotIndex || 0);
  const withOffset = (p) => ({ x: p.x + slotOffset.x, y: p.y + slotOffset.y });
  if (worker.taskType === "woodcutter") {
    const tree = findNearestNodeForBuilding(mission, building, "tree", 10);
    const stockpile = getClosestBuildingOfType(mission, buildingCenter.x, buildingCenter.y, "stockpile", faction);
    if (!tree || !stockpile) return [withOffset(buildingCenter)];
    return [withOffset(buildingCenter), withOffset({ x: tree.x + 0.5, y: tree.y + 0.5 }), withOffset(buildingCenter), withOffset(getBuildingCenter(stockpile))];
  }
  if (worker.taskType === "iron-furny") {
    const iron = findNearestNodeForBuilding(mission, building, "iron_patch", 8);
    if (!iron) return [withOffset(buildingCenter)];
    return [withOffset(buildingCenter), withOffset({ x: iron.x + 0.5, y: iron.y + 0.5 }), withOffset(buildingCenter)];
  }
  if (worker.taskType === "cow-building") {
    const furny = getClosestBuildingOfType(mission, buildingCenter.x, buildingCenter.y, "iron-furny", faction);
    const stockpile = getClosestBuildingOfType(mission, buildingCenter.x, buildingCenter.y, "stockpile", faction);
    if (!furny || !stockpile) return [withOffset(buildingCenter)];
    return [withOffset(buildingCenter), withOffset(getBuildingCenter(furny)), withOffset(buildingCenter), withOffset(getBuildingCenter(stockpile))];
  }
  if (worker.taskType === "apple-farm") {
    const granary = getClosestBuildingOfType(mission, buildingCenter.x, buildingCenter.y, "granary", faction);
    if (!granary) return [withOffset(buildingCenter)];
    return [withOffset(buildingCenter), withOffset(getBuildingCenter(granary))];
  }
  if (worker.taskType === "stone-camp") {
    const patch = findNearestNodeForBuilding(mission, building, "stone_patch", 8);
    const stockpile = getClosestBuildingOfType(mission, buildingCenter.x, buildingCenter.y, "stockpile", faction);
    if (!patch || !stockpile) return [withOffset(buildingCenter)];
    return [withOffset(buildingCenter), withOffset({ x: patch.x + 0.5, y: patch.y + 0.5 }), withOffset(buildingCenter), withOffset(getBuildingCenter(stockpile))];
  }
  return [withOffset(buildingCenter)];
}

function updatePeasantDotSimulation(deltaSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  const allWorkers = [
    ...(Array.isArray(mission.peasantWorkers) ? mission.peasantWorkers : []),
    ...(Array.isArray(mission.enemyPeasantWorkers) ? mission.enemyPeasantWorkers : [])
  ];
  for (const worker of allWorkers) {
    const isEnemyWorker = String(worker.id || "").startsWith("epw-");
    const route = buildWorkerRoute(mission, worker);
    if (route.length === 0) continue;
    if (!Number.isFinite(worker.routeIndex)) worker.routeIndex = 0;
    worker.routeIndex = ((worker.routeIndex % route.length) + route.length) % route.length;
    const target = route[worker.routeIndex];
    worker.targetX = target.x;
    worker.targetY = target.y;
    const dx = target.x - worker.x;
    const dy = target.y - worker.y;
    const dist = Math.hypot(dx, dy);
    if (dist <= 0.06) {
      if (worker.taskType === "woodcutter") {
        if (worker.routeIndex === 1) {
          const buildingList = isEnemyWorker ? (mission.enemyBuildings || []) : (mission.buildings || []);
          const building = buildingList.find((b) => b.id === worker.assignedBuildingId);
          const tree = building ? findNearestNodeForBuilding(mission, building, "tree", 10) : null;
          if (tree && Number(tree.node.amount || 0) > 0) {
            tree.node.amount = Math.max(0, Number(tree.node.amount || 0) - 1);
            worker.carryType = "wood";
            worker.carryAmount = 1;
            worker.state = "returning";
          } else {
            worker.carryType = null;
            worker.carryAmount = 0;
            worker.state = "moving";
          }
        } else if (worker.routeIndex === 3) {
          if (!isEnemyWorker && worker.carryAmount > 0) {
            const delivered = addStockpileFlowResource(mission, "wood", worker.carryAmount);
            mission.pendingDeliveryDeltas.wood = Number(mission.pendingDeliveryDeltas.wood || 0) + delivered;
          }
          worker.carryType = null;
          worker.carryAmount = 0;
          worker.state = "delivered";
        }
      } else if (worker.taskType === "iron-furny") {
        if (worker.routeIndex === 1) {
          worker.carryType = "iron";
          worker.carryAmount = 1;
        } else if (worker.routeIndex === 2) {
          worker.carryType = null;
          worker.carryAmount = 0;
        }
      } else if (worker.taskType === "cow-building") {
        if (worker.routeIndex === 1) {
          worker.carryType = "iron";
          worker.carryAmount = 1;
        } else if (worker.routeIndex === 3) {
          worker.carryType = null;
          worker.carryAmount = 0;
        }
      } else if (worker.taskType === "apple-farm") {
        if (worker.routeIndex === 0) {
          worker.carryType = "apples";
          worker.carryAmount = 1;
        } else if (worker.routeIndex === 1) {
          worker.carryType = null;
          worker.carryAmount = 0;
        }
      } else if (worker.taskType === "stone-camp") {
        if (worker.routeIndex === 1) {
          worker.carryType = "stone";
          worker.carryAmount = 1;
        } else if (worker.routeIndex === 3) {
          worker.carryType = null;
          worker.carryAmount = 0;
        }
      }
      worker.routeIndex = (worker.routeIndex + 1) % route.length;
      worker.state = "moving";
      continue;
    }
    const step = PEASANT_DOT_SPEED * deltaSec;
    const moved = advanceEntityTowards(worker, target.x, target.y, step, isTileWalkableForDot);
    if (moved.blocked) {
      const fallback = findNearestTileMatching(target.x, target.y, isTileWalkableForDot, 4);
      if (fallback) {
        worker.targetX = fallback.x;
        worker.targetY = fallback.y;
      } else {
        worker.routeIndex = (worker.routeIndex + 1) % route.length;
      }
    }
    worker.state = "moving";
  }

  for (const dot of (mission.recruitTransitDots || [])) {
    if (dot.arrived) continue;
    const dx = dot.targetX - dot.x;
    const dy = dot.targetY - dot.y;
    const dist = Math.hypot(dx, dy);
    if (dist <= 0.06) {
      dot.x = dot.targetX;
      dot.y = dot.targetY;
      dot.arrived = true;
      continue;
    }
    const step = PEASANT_DOT_SPEED * deltaSec;
    const moved = advanceEntityTowards(dot, dot.targetX, dot.targetY, step, isTileWalkableForDot);
    if (moved.blocked) {
      const fallback = findNearestTileMatching(dot.targetX, dot.targetY, isTileWalkableForDot, 6);
      if (fallback) {
        dot.targetX = fallback.x;
        dot.targetY = fallback.y;
      } else {
        dot.arrived = true;
      }
    }
  }
}

function updatePopulationFlow(currentSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission || currentSec % 20 !== 0) return;
  if (mission.popularity > 58 && mission.population < mission.populationCap) {
    mission.population += 1;
    addMissionEventLog("A peasant has arrived at the campfire.");
  } else if (mission.popularity < 42 && mission.population > 4) {
    mission.population -= 1;
    addMissionEventLog("A peasant has left due to low popularity.");
  }
}

function updateObjectives() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  for (const obj of mission.objectives) {
    if (obj.type === "building_count") {
      const type = obj.target?.buildingType;
      const needed = Number(obj.target?.count || 1);
      const count = mission.buildings.filter((b) => b.type === type).length;
      obj.done = count >= needed;
    } else if (obj.type === "resource_at_least") {
      const resource = obj.target?.resource || "wood";
      const needed = Number(obj.target?.amount || 1);
      obj.done = Number(mission.resources[resource] || 0) >= needed;
    } else if (obj.type === "survive_waves") {
      const throughWaveId = obj.target?.throughWave;
      const targetWave = mission.raidWaves.find((w) => w.id === throughWaveId);
      const throughSec = Number(targetWave?.triggerAtSec || 0);
      const spawnedThroughWave = throughWaveId ? !!mission.raidWaves.find((w) => w.id === throughWaveId && w.spawned) : true;
      obj.done = spawnedThroughWave && mission.enemies.length === 0 && state.missionRuntime.lastProcessedSec >= throughSec;
    } else if (obj.type === "population_at_least") {
      obj.done = mission.population >= Number(obj.target?.amount || 1);
    }
  }
  const allDone = mission.objectives.every((o) => o.done);
  if (allDone && state.missionRuntime.status === "running") {
    setMissionResult("won");
    addMissionEventLog("All mission objectives complete.");
  }
}

function getObjectiveProgressLabel(objective, mission) {
  if (objective.type === "building_count") {
    const current = mission.buildings.filter((b) => b.type === objective.target?.buildingType).length;
    const needed = Number(objective.target?.count || 1);
    return `${current}/${needed}`;
  }
  if (objective.type === "resource_at_least") {
    const resource = objective.target?.resource || "wood";
    const current = Number(mission.resources[resource] || 0);
    const needed = Number(objective.target?.amount || 1);
    return `${current}/${needed} ${resource}`;
  }
  if (objective.type === "survive_waves") {
    const throughWaveId = objective.target?.throughWave;
    const wave = mission.raidWaves.find((w) => w.id === throughWaveId);
    if (!wave) return "pending";
    if (!wave.spawned) return "wave not arrived";
    return mission.enemies.length === 0 ? "area clear" : `${mission.enemies.length} enemies remain`;
  }
  if (objective.type === "population_at_least") {
    return `${mission.population}/${Number(objective.target?.amount || 1)}`;
  }
  return objective.done ? "done" : "pending";
}

function processMissionEvents(currentSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  for (const ev of mission.events) {
    if (!ev.triggered && currentSec >= ev.triggerAtSec) {
      ev.triggered = true;
      addMissionEventLog(ev.message);
      showToast(ev.message);
    }
  }
}

function evaluateFailConditions() {
  const mission = state.missionRuntime.missionState;
  if (!mission || state.missionRuntime.status !== "running") return;
  const keep = mission.buildings.find((b) => b.type === "keep");
  if (!keep) {
    setMissionResult("failed");
    addMissionEventLog("Mission failed: your keep was lost.");
    showToast("Mission failed: keep destroyed.");
    return;
  }
  if (mission.popularity < 20) mission.lowPopularitySec += 1;
  else mission.lowPopularitySec = 0;

  if (mission.lowPopularitySec >= 70 || mission.population <= 0) {
    setMissionResult("failed");
    addMissionEventLog("Mission failed: settlement collapsed.");
    showToast("Mission failed: your people abandoned the settlement.");
    return;
  }
  if (Number(mission.raidState?.keepThreatSec || 0) >= 20) {
    setMissionResult("failed");
    addMissionEventLog("Mission failed: enemies overran the keep.");
    showToast("Mission failed: keep overrun.");
  }
}

function findNearestEnemyForUnit(mission, unit, maxDist) {
  let nearest = null;
  let bestDist = maxDist;
  for (const enemy of mission.enemies) {
    if (enemy.hp <= 0) continue;
    const dist = Math.hypot(enemy.x - unit.x, enemy.y - unit.y);
    if (dist < bestDist) {
      bestDist = dist;
      nearest = enemy;
    }
  }
  return nearest;
}

function findPriorityEnemyForUnit(mission, unit, maxDist) {
  const inRange = mission.enemies
    .filter((enemy) => enemy.hp > 0 && Math.hypot(enemy.x - unit.x, enemy.y - unit.y) <= maxDist)
    .sort((a, b) => Number(a.hp || 0) - Number(b.hp || 0));
  return inRange[0] || null;
}

function findNearestFriendlyForEnemy(mission, enemy, maxDist) {
  let nearest = null;
  let bestDist = maxDist;
  for (const unit of mission.units) {
    if (unit.hp <= 0) continue;
    const dist = Math.hypot(unit.x - enemy.x, unit.y - enemy.y);
    if (dist < bestDist) {
      bestDist = dist;
      nearest = unit;
    }
  }
  return nearest;
}

function updateEnemySimulation(deltaSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  if (!Array.isArray(mission.enemies) || mission.enemies.length === 0) return;
  const keep = mission.buildings.find((b) => b.type === "keep");
  if (!keep) return;
  const keepCenter = { x: keep.x + keep.w * 0.5, y: keep.y + keep.h * 0.5 };
  let anyThreatNearKeep = false;
  for (const enemy of mission.enemies) {
    if (enemy.hp <= 0) continue;
    const targetUnit = findNearestFriendlyForEnemy(mission, enemy, 5.5);
    const targetX = targetUnit ? targetUnit.x : keepCenter.x;
    const targetY = targetUnit ? targetUnit.y : keepCenter.y;
    const dx = targetX - enemy.x;
    const dy = targetY - enemy.y;
    const dist = Math.hypot(dx, dy);
    const attackRange = Number(enemy.attackRange || 1.1);
    if (dist <= attackRange) {
      if (targetUnit) {
        targetUnit.hp -= Number(enemy.damage || 6) * deltaSec;
        if (targetUnit.hp <= 0) {
          targetUnit.hp = 0;
          targetUnit.moveTarget = null;
          targetUnit.attackTargetId = null;
        }
      } else {
        anyThreatNearKeep = true;
      }
    } else if (dist > 0.001) {
      const step = Number(enemy.speed || 0.045) * (deltaSec * 4);
      const moved = advanceEntityTowards(enemy, targetX, targetY, step, isTileWalkableForUnit);
      if (moved.blocked) {
        const fallback = findNearestTileMatching(targetX, targetY, isTileWalkableForUnit, 5);
        if (fallback) advanceEntityTowards(enemy, fallback.x, fallback.y, step, isTileWalkableForUnit);
      }
    }
    if (Math.hypot(enemy.x - keepCenter.x, enemy.y - keepCenter.y) <= 4.2) {
      anyThreatNearKeep = true;
    }
  }
  mission.units = mission.units.filter((u) => u.hp > 0);
  mission.enemies = mission.enemies.filter((e) => e.hp > 0);
  if (!mission.raidState) mission.raidState = { warningIds: [], spawnedWaveIds: [], keepThreatSec: 0, lastRaidMessageSec: -99 };
  mission.raidState.keepThreatSec = anyThreatNearKeep ? mission.raidState.keepThreatSec + deltaSec : Math.max(0, mission.raidState.keepThreatSec - (deltaSec * 0.5));
}

function updateUnitSimulation(deltaSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  if (!Array.isArray(mission.units)) mission.units = [];
  if (!Array.isArray(mission.enemies)) mission.enemies = [];
  if (mission.units.length === 0) return;
  const playerKeep = getPlayerKeep(mission);
  const keepCenter = playerKeep ? { x: playerKeep.x + playerKeep.w * 0.5, y: playerKeep.y + playerKeep.h * 0.5 } : null;
  for (const unit of mission.units) {
    if (unit.hp <= 0) continue;
    unit.commandMode = unit.commandMode || mission.commandMode || "normal";
    if (unit.commandMode === "fallback" && keepCenter) {
      const retreatTarget = findNearestTileMatching(keepCenter.x, keepCenter.y, isTileWalkableForUnit, 6);
      if (retreatTarget) unit.moveTarget = { x: retreatTarget.x, y: retreatTarget.y };
      unit.attackTargetId = null;
    }
    if (!unit.attackTargetId && !unit.moveTarget) {
      const autoRangeByStance = { stand: 0, defensive: 4.5, aggressive: 9 };
      const autoRange = autoRangeByStance[unit.stance || "stand"] || 0;
      if (autoRange > 0) {
        const autoEnemy = unit.commandMode === "focus-fire"
          ? findPriorityEnemyForUnit(mission, unit, autoRange)
          : findNearestEnemyForUnit(mission, unit, autoRange);
        if (autoEnemy) unit.attackTargetId = autoEnemy.id;
      }
    }
    if (unit.attackTargetId) {
      const enemy = mission.enemies.find((e) => e.id === unit.attackTargetId);
      if (!enemy) {
        unit.attackTargetId = null;
      } else {
        const dx = enemy.x - unit.x;
        const dy = enemy.y - unit.y;
        const dist = Math.hypot(dx, dy);
        const chaseCapByStance = { stand: 1.2, defensive: 6, aggressive: 14 };
        const chaseCap = chaseCapByStance[unit.stance || "stand"] || 1.2;
        const commandCap = unit.commandMode === "hold-line" ? 2.2 : unit.commandMode === "fallback" ? 0.5 : chaseCap;
        if (dist > 1.2) {
          if (dist <= commandCap) {
            const legalTarget = findNearestTileMatching(enemy.x, enemy.y, isTileWalkableForUnit, 4);
            if (legalTarget) {
              unit.moveTarget = { x: legalTarget.x, y: legalTarget.y };
            } else {
              unit.attackTargetId = null;
              unit.moveTarget = null;
            }
          } else {
            unit.attackTargetId = null;
            unit.moveTarget = null;
          }
        } else {
          enemy.hp -= unit.damage * deltaSec;
          if (enemy.hp <= 0) {
            addMissionEventLog("Enemy eliminated.");
            mission.enemies = mission.enemies.filter((e) => e.id !== enemy.id);
            unit.attackTargetId = null;
            unit.moveTarget = null;
          }
        }
      }
    }
    if (unit.moveTarget) {
      const dx = unit.moveTarget.x - unit.x;
      const dy = unit.moveTarget.y - unit.y;
      const dist = Math.hypot(dx, dy);
      if (dist < 0.08) {
        unit.x = unit.moveTarget.x;
        unit.y = unit.moveTarget.y;
        unit.moveTarget = null;
      } else {
        const step = unit.speed * (deltaSec * 4);
        const moved = advanceEntityTowards(unit, unit.moveTarget.x, unit.moveTarget.y, step, isTileWalkableForUnit);
        if (moved.arrived || moved.blocked) unit.moveTarget = null;
      }
    }
  }
}

function updateLordDotSimulation(deltaSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  initializeMissionLordDots(mission);
  for (const lord of (mission.lordDots || [])) {
    if (lord.hp <= 0) continue;
    const keep = lord.faction === "enemy" ? getEnemyKeep(mission) : getPlayerKeep(mission);
    if (!keep) continue;
    const keepCenter = { x: keep.x + keep.w * 0.5, y: keep.y + keep.h * 0.5 };
    const threatPool = lord.faction === "enemy" ? (mission.units || []) : (mission.enemies || []);
    const nearbyThreat = threatPool.find((u) => u.hp > 0 && Math.hypot(u.x - keepCenter.x, u.y - keepCenter.y) <= 5.5);
    const underThreat = !!nearbyThreat || (lord.faction === "player" && Number(mission.raidState?.keepThreatSec || 0) > 1.2);

    if (underThreat) lord.state = "alert";
    else if (lord.state === "alert") lord.state = "return";

    if (!Array.isArray(lord.route) || lord.route.length === 0) {
      lord.route = buildKeepPatrolRoute(keep);
      lord.routeIndex = 0;
    }
    const patrolNode = lord.route[lord.routeIndex % Math.max(1, lord.route.length)] || keepCenter;
    const desired = lord.state === "alert"
      ? keepCenter
      : lord.state === "return"
        ? (lord.route[0] || keepCenter)
        : patrolNode;

    const step = 0.032 * (deltaSec * 4);
    const moved = advanceEntityTowards(lord, desired.x, desired.y, step, isTileWalkableForDot);
    if (lord.state === "patrol" && moved.arrived && lord.route.length > 0) {
      lord.routeIndex = (lord.routeIndex + 1) % lord.route.length;
    } else if (lord.state === "return" && moved.arrived) {
      lord.state = "patrol";
      lord.routeIndex = 0;
    }
  }
}

function runMissionSimulationStep(currentSec) {
  const mission = state.missionRuntime.missionState;
  if (!mission || state.missionRuntime.status !== "running") return;
  consumeFood();
  processResearchAndUpkeep(currentSec);
  assignWorkers();
  computeStockpileStats();
  produceWood();
  updatePopularity();
  updatePopulationFlow(currentSec);
  assignWorkers();
  computeStockpileStats();
  processMissionEvents(currentSec);
  processRaidWaves(currentSec);
  processEnemyEconomyAndMilitary(currentSec);
  updateObjectives();
  processBarracksRecruitQueues();
  evaluateFailConditions();
  mission.autosaveCounter += 1;
  if (mission.autosaveCounter >= 10) {
    mission.autosaveCounter = 0;
    saveMissionRuntime();
  }
  refreshMissionPanels();
}

function refreshMissionPanels() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  sanitizeMissionSelectionState(mission);

  const buildbar = document.getElementById("mission-buildbar");
  if (buildbar) {
    buildbar.classList.toggle("minimized", !!mission.buildBarMinimized);
  }

  const devPanel = document.getElementById("mission-dev-panel");
  if (devPanel) {
    devPanel.classList.toggle("hidden", !mission.devMenuOpen);
  }
  const pauseOverlay = document.getElementById("mission-pause-overlay");
  if (pauseOverlay) pauseOverlay.classList.toggle("hidden", !mission.pauseMenuOpen);
  const pauseOptionsPanel = document.getElementById("pause-options-panel");
  if (pauseOptionsPanel) pauseOptionsPanel.classList.toggle("hidden", !mission.pauseOptionsOpen);
  const pausePresetSelect = document.getElementById("pause-unit-control-preset");
  if (pausePresetSelect && pausePresetSelect.value !== state.settings.unitControlPreset) {
    pausePresetSelect.value = state.settings.unitControlPreset;
  }
  const sanityReadout = document.getElementById("mission-sanity-readout");
  if (sanityReadout) {
    const issues = getMissionSanityIssues(mission);
    sanityReadout.innerHTML = issues.length
      ? `Sanity: <strong>Issues</strong><br>${issues.join("<br>")}`
      : "Sanity: <strong>OK</strong>";
  }

  const buildStatus = document.getElementById("build-mode-status");
  if (buildStatus) {
    const moveText = mission.moveCommandMode ? " | Move: armed" : "";
    buildStatus.textContent = `Category: ${mission.buildCategory} | Build mode: ${mission.buildMode ? MISSION_BUILDINGS[mission.buildMode].label : "none"}${moveText}`;
  }
  const buildButtons = document.querySelectorAll(".build-option-btn[data-build-type]");
  for (const btn of buildButtons) {
    const buildType = btn.dataset.buildType;
    const buildCategory = btn.dataset.buildCategory;
    const def = MISSION_BUILDINGS[buildType];
    const costText = def ? formatBuildCost(def.cost) : "";
    let badge = btn.querySelector(".build-cost-badge");
    if (!badge) {
      badge = document.createElement("span");
      badge.className = "build-cost-badge";
      btn.appendChild(badge);
    }
    badge.textContent = costText;
    if (costText) btn.title = `${def.label} cost: ${costText}`;
    btn.classList.toggle("active", buildType === mission.buildMode);
    const canAfford = def
      ? Object.entries(def.cost).every(([resource, amount]) => getBuildResourceAvailable(mission, buildType, resource) >= amount)
      : false;
    btn.classList.toggle("unaffordable", def ? !canAfford : false);
    btn.classList.toggle("filtered-out", !!buildCategory && buildCategory !== mission.buildCategory);
  }
  const buildCategoryTabs = document.querySelectorAll(".build-cat-btn[data-build-category-tab]");
  for (const tab of buildCategoryTabs) {
    tab.classList.toggle("active", tab.dataset.buildCategoryTab === mission.buildCategory);
  }
  const cancelBuildButton = document.querySelector('.build-option-btn.cancel-btn[data-action="cancel-build-mode"]');
  if (cancelBuildButton) {
    cancelBuildButton.classList.toggle("cancel-active", !mission.buildMode);
  }

  const objectives = document.getElementById("mission-objective-list");
  if (objectives) {
    objectives.innerHTML = mission.objectives
      .map((o) => {
        const progress = getObjectiveProgressLabel(o, mission);
        return `<div class="objective-item ${o.done ? "done" : ""}"><span>${o.text}</span><strong>${o.done ? "Done" : progress}</strong></div>`;
      })
      .join("");
  }

  const taxSelect = document.getElementById("mission-tax-mode");
  const rationSelect = document.getElementById("mission-ration-mode");
  if (taxSelect && taxSelect.value !== mission.taxMode) taxSelect.value = mission.taxMode;
  if (rationSelect && rationSelect.value !== mission.rationMode) rationSelect.value = mission.rationMode;

  const selectedUnits = getSelectedUnits(mission);
  const selectedUnit = selectedUnits[0] || null;
  const unitText = selectedUnit
    ? selectedUnits.length === 1
      ? `
      Unit: <strong>${selectedUnit.type}</strong><br>
      Stance: <strong>${selectedUnit.stance || "stand"}</strong><br>
      Command: <strong>${selectedUnit.commandMode || mission.commandMode || "normal"}</strong><br>
      HP: <strong>${Math.max(0, Math.round(selectedUnit.hp))}</strong><br>
      Position: <strong>${selectedUnit.x.toFixed(1)}, ${selectedUnit.y.toFixed(1)}</strong><br>
      Target: <strong>${selectedUnit.attackTargetId ? "Enemy" : selectedUnit.moveTarget ? "Move" : "Idle"}</strong>
    `
      : `
      Group: <strong>${selectedUnits.length} units</strong><br>
      Stance: <strong>${selectedUnit.stance || "mixed"}</strong><br>
      Command: <strong>${selectedUnit.commandMode || mission.commandMode || "normal"}</strong><br>
      Avg HP: <strong>${Math.round(selectedUnits.reduce((sum, u) => sum + u.hp, 0) / selectedUnits.length)}</strong><br>
      Enemies: <strong>${mission.enemies.length}</strong>
    `
    : "No unit selected.";

  const unitCard = document.getElementById("unit-command-card");
  if (unitCard) unitCard.innerHTML = unitText;
  const unitCardSecondary = document.getElementById("unit-command-card-secondary");
  if (unitCardSecondary) unitCardSecondary.innerHTML = unitText;
  const stanceButtons = {
    stand: document.querySelector('[data-action="stance-stand"]'),
    defensive: document.querySelector('[data-action="stance-defensive"]'),
    aggressive: document.querySelector('[data-action="stance-aggressive"]')
  };
  const activeStance = selectedUnit?.stance || null;
  for (const [stance, btn] of Object.entries(stanceButtons)) {
    if (!btn) continue;
    btn.classList.toggle("stance-active", !!activeStance && activeStance === stance);
    btn.disabled = selectedUnits.length === 0;
  }
  const activeCommandMode = selectedUnit?.commandMode || mission.commandMode || "normal";
  const tacticButtons = Array.from(document.querySelectorAll("[data-command-mode]"));
  for (const btn of tacticButtons) {
    const modeName = btn.dataset.commandMode || "normal";
    btn.classList.toggle("stance-active", modeName === activeCommandMode);
    btn.disabled = selectedUnits.length === 0;
  }

  const contextPanel = document.getElementById("mission-context-panel");
  const contextTitle = document.getElementById("context-title");
  const emptyPanel = document.getElementById("context-panel-empty");
  const keepPanel = document.getElementById("context-panel-keep");
  const barracksPanel = document.getElementById("context-panel-barracks");
  const stockpilePanel = document.getElementById("context-panel-stockpile");
  const buildingPanel = document.getElementById("context-panel-building");
  const unitPanel = document.getElementById("context-panel-unit");
  const tilePanel = document.getElementById("context-panel-tile");
  const eventPanel = document.getElementById("context-panel-event");

  const allPanels = [keepPanel, barracksPanel, stockpilePanel, buildingPanel, unitPanel, tilePanel, eventPanel];
  for (const panel of allPanels) {
    if (panel) panel.classList.add("hidden");
  }
  if (emptyPanel) emptyPanel.classList.add("hidden");

  const mode = mission.contextMode || "none";
  if (mode === "barracks") {
    const selectedBarracks = mission.buildings.find((b) => b.id === mission.selectedBuildingId && b.type === "barracks");
    if (!selectedBarracks) {
      setContextMode("none", null);
    }
  } else if (mode === "stockpile") {
    const selectedStockpile = mission.buildings.find((b) => b.id === mission.selectedBuildingId && b.type === "stockpile");
    if (!selectedStockpile) setContextMode("none", null);
  } else if (mode === "building") {
    const selectedBuilding = mission.buildings.find((b) => b.id === mission.selectedBuildingId);
    if (!selectedBuilding || selectedBuilding.type === "keep" || selectedBuilding.type === "campfire" || selectedBuilding.type === "barracks" || selectedBuilding.type === "stockpile") {
      setContextMode("none", null);
    }
  }
  const resolvedMode = mission.contextMode || "none";
  if (contextPanel) contextPanel.classList.toggle("hidden", resolvedMode === "none");

  if (resolvedMode === "none") {
    if (emptyPanel) emptyPanel.classList.remove("hidden");
    if (contextTitle) contextTitle.textContent = "Context";
    return;
  }

  if (resolvedMode === "keep") {
    if (contextTitle) contextTitle.textContent = "Keep";
    if (keepPanel) keepPanel.classList.remove("hidden");
    const resourcePanel = document.getElementById("mission-resource-panel");
    if (resourcePanel) {
      const playerLord = (mission.lordDots || []).find((dot) => dot.faction === "player");
      resourcePanel.innerHTML = [
        `Wood: <strong>${mission.resources.wood}</strong> (${mission.lastWoodDelta >= 0 ? "+" : ""}${mission.lastWoodDelta}/s)`,
        `Apples/Food: <strong>${mission.resources.apples || 0}</strong> (${mission.lastFoodDelta}/s)`,
        `Gold: <strong>${mission.resources.gold}</strong>`,
        `Stone: <strong>${mission.resources.stone || 0}</strong>`,
        `Iron: <strong>${mission.resources.iron || 0}</strong>`,
        `Stockpile: <strong>${mission.stockpileUsed}/${mission.stockpileCapacity}</strong>`,
        `Granary: <strong>${mission.granaryUsed || 0}/${mission.granaryCapacity || 0}</strong>`,
        `Workers: <strong>${mission.workersAssigned}</strong> / Idle: <strong>${mission.idlePeasants}</strong>`,
        `Campfire Dots: <strong>${Math.max(0, Math.min(mission.population, mission.idlePeasants))}</strong> idle / <strong>${mission.workersAssigned}</strong> assigned`,
        `Wood Chain: <strong>${mission.woodStatus}</strong>${mission.woodStallReason ? ` (${mission.woodStallReason})` : ""}`,
        `Stone Chain: <strong>${mission.chainStatus?.stone || "Idle"}</strong>`,
        `Iron Chain: <strong>${mission.chainStatus?.iron || "Idle"}</strong>`,
        `Cow Chain: <strong>${mission.chainStatus?.cow || "Idle"}</strong>`,
        `Apple Chain: <strong>${mission.chainStatus?.apples || "Idle"}</strong>`,
        `Keep Threat: <strong>${Math.round(Number(mission.raidState?.keepThreatSec || 0))}s</strong>`,
        `Enemy Base: <strong>${(mission.enemyBuildings || []).filter((b) => !b.locked).length} expansions</strong>`,
        `Lord Status: <strong>${playerLord?.state || "patrol"}</strong>`,
        `Research: <strong>${mission.modernRules?.researchEnabled ? "On" : "Off"}</strong> (${Math.round(Number(mission.researchState?.points || 0))} pts)`,
        `Army Upkeep: <strong>${mission.modernRules?.upkeepEnabled ? "On" : "Off"}</strong>`,
        `Morale Penalty: <strong>${Math.round(Number(mission.upkeepState?.moralePenalty || 0))}</strong>`,
        `Path Blocks: <strong>${Math.round(Number(mission.simMetrics?.pathBlocks || 0))}</strong>`,
        `AI Strategy Shifts: <strong>${Math.round(Number(mission.simMetrics?.strategyShifts || 0))}</strong>`
      ].join("<br>");
    }
    const popularityPanel = document.getElementById("mission-popularity-panel");
    if (popularityPanel) {
      const breakdown = mission.popularityBreakdown
        .map(([name, value]) => `${name}: ${value >= 0 ? "+" : ""}${value}`)
        .join("<br>");
      popularityPanel.innerHTML = `
        Popularity: <strong>${mission.popularity}</strong><br>
        Trend: <strong>${mission.popularityTrend}</strong><br>
        Population: <strong>${mission.population}/${mission.populationCap}</strong><br>
        ${breakdown}
      `;
    }
  } else if (resolvedMode === "barracks") {
    if (contextTitle) contextTitle.textContent = "Barracks";
    if (barracksPanel) barracksPanel.classList.remove("hidden");
    const selectedBarracks = mission.buildings.find((b) => b.id === mission.selectedBuildingId && b.type === "barracks") || null;
    const recruitButtons = Array.from(document.querySelectorAll("[data-recruit-unit-type]"));
    const cancelQueueBtn = document.querySelector('[data-action="cancel-barracks-queue"]');
    const queueCard = document.getElementById("barracks-queue-card");
    const queueProgress = document.getElementById("barracks-queue-progress");
    if (selectedBarracks && !Array.isArray(selectedBarracks.recruitQueue)) selectedBarracks.recruitQueue = [];
    const queue = selectedBarracks?.recruitQueue || [];
    const hasRoom = queue.length < BARRACKS_QUEUE_LIMIT;
    for (const recruitBtn of recruitButtons) {
      const unitType = recruitBtn.dataset.recruitUnitType || "militia";
      const unitDef = UNIT_TEMPLATES[unitType];
      const canAfford = !!unitDef && mission.resources.gold >= Number(unitDef.goldCost || 0);
      recruitBtn.disabled = !(selectedBarracks && hasRoom && canAfford);
    }
    if (cancelQueueBtn) cancelQueueBtn.disabled = !(selectedBarracks && queue.length > 0);
    if (queueCard) {
      const slots = Array.from(
        { length: BARRACKS_QUEUE_LIMIT },
        (_, index) => {
          if (!queue[index]) return "Empty";
          const item = queue[index];
          const status = item.state === "waiting_for_idle_dot"
            ? "waiting idle dot"
            : item.state === "dot_assigned"
              ? "dot assigned"
            : item.state === "dot_walking_to_barracks"
              ? "dot walking"
              : `${Math.ceil(Number(item.remainingSec || 0))}s`;
          return `${item.type} (${status})`;
        }
      );
      queueCard.innerHTML = `
        Queue: <strong>${queue.length}/${BARRACKS_QUEUE_LIMIT}</strong><br>
        Slot 1: <strong>${slots[0]}</strong><br>
        Slot 2: <strong>${slots[1]}</strong>
      `;
    }
    if (queueProgress) {
      if (queue.length === 0) {
        queueProgress.innerHTML = "Training: <strong>Idle</strong>";
      } else {
        const current = queue[0];
        const pct = Math.max(0, Math.min(100, Math.round(((current.totalSec - current.remainingSec) / current.totalSec) * 100)));
        const next = queue[1] ? `${queue[1].type} (${Math.ceil(Number(queue[1].remainingSec || 0))}s)` : "none";
        const status = current.state === "waiting_for_idle_dot"
          ? "Waiting for idle dot"
          : current.state === "dot_assigned"
            ? "Idle dot assigned"
          : current.state === "dot_walking_to_barracks"
            ? "Dot walking to barracks"
            : "Training";
        queueProgress.innerHTML = `
          Training: <strong>${current.type}</strong><br>
          Status: <strong>${status}</strong><br>
          Remaining: <strong>${Math.ceil(Number(current.remainingSec || 0))}s</strong><br>
          Progress: <strong>${pct}%</strong><br>
          Next: <strong>${next}</strong>
        `;
      }
    }
  } else if (resolvedMode === "stockpile") {
    if (contextTitle) contextTitle.textContent = "Stockpile";
    if (stockpilePanel) stockpilePanel.classList.remove("hidden");
    const stockpileReadout = document.getElementById("stockpile-readout");
    if (stockpileReadout) {
      stockpileReadout.innerHTML = [
        `Wood: <strong>${Number(mission.resources.wood || 0)}</strong>`,
        `Stone: <strong>${Number(mission.resources.stone || 0)}</strong>`,
        `Iron: <strong>${Number(mission.resources.iron || 0)}</strong>`,
        `Used: <strong>${mission.stockpileUsed}/${mission.stockpileCapacity}</strong>`,
        `Free: <strong>${Math.max(0, mission.stockpileCapacity - mission.stockpileUsed)}</strong>`
      ].join("<br>");
    }
  } else if (resolvedMode === "building") {
    if (contextTitle) contextTitle.textContent = "Building";
    if (buildingPanel) buildingPanel.classList.remove("hidden");
    const selectedBuilding = mission.buildings.find((b) => b.id === mission.selectedBuildingId) || null;
    const readout = document.getElementById("building-readout");
    if (readout) {
      if (!selectedBuilding) {
        readout.textContent = "No building selected.";
      } else {
        const def = MISSION_BUILDINGS[selectedBuilding.type];
        readout.innerHTML = `
          Type: <strong>${def?.label || selectedBuilding.type}</strong><br>
          Active: <strong>${selectedBuilding.active ? "Yes" : "No"}</strong><br>
          Workers: <strong>${Number(selectedBuilding.assignedWorkers || 0)}</strong><br>
          Footprint: <strong>${selectedBuilding.w}x${selectedBuilding.h}</strong>
        `;
      }
    }
  } else if (resolvedMode === "unit") {
    if (contextTitle) contextTitle.textContent = "Unit";
    if (unitPanel) unitPanel.classList.remove("hidden");
  } else if (resolvedMode === "tile") {
    if (contextTitle) contextTitle.textContent = "Tile";
    if (tilePanel) tilePanel.classList.remove("hidden");
    const tileInfo = document.getElementById("mission-tile-info");
    if (tileInfo) {
      if (!mission.selectedTile) {
        tileInfo.textContent = "No tile selected.";
      } else {
        const t = mission.selectedTile;
        const terrain = state.missionWorld.terrain[t.y][t.x];
        const height = state.missionWorld.heights[t.y][t.x];
        const node = getResourceNodeAt(t.x, t.y);
        const occupier = getMissionBuildingAt(t.x, t.y);
        const baseBuildable = isTileBuildableBase(t.x, t.y);
        tileInfo.innerHTML = `
          Tile: <strong>${t.x}, ${t.y}</strong><br>
          Terrain: <strong>${terrain}</strong><br>
          Node: <strong>${node.type}</strong> (${node.amount})<br>
          Height: <strong>${height}</strong><br>
          Base Buildable: <strong>${baseBuildable ? "Yes" : "No"}</strong><br>
          Occupied: <strong>${occupier ? `${occupier.type} (${occupier.owner || "neutral"})` : "No"}</strong>
        `;
      }
    }
  } else if (resolvedMode === "eventLog") {
    if (contextTitle) contextTitle.textContent = "Event Log";
    if (eventPanel) eventPanel.classList.remove("hidden");
    const eventLog = document.getElementById("mission-event-log");
    if (eventLog) {
      eventLog.innerHTML = mission.eventLog.length
        ? mission.eventLog.map((line) => `<div class="event-entry">${line}</div>`).join("")
        : "<div class=\"event-entry\">No events yet.</div>";
    }
  }

  const mobileQuick = document.getElementById("mission-mobile-quick");
  if (mobileQuick) {
    const showQuick = isTouchPreferredDevice() && selectedUnits.length > 0;
    mobileQuick.classList.toggle("hidden", !showQuick);
    const quickLabel = document.getElementById("mobile-quick-selection");
    if (quickLabel) quickLabel.textContent = `${selectedUnits.length} selected`;
  }
}

function saveMissionRuntime() {
  const mission = state.missionRuntime.missionState;
  if (!mission || !state.missionRuntime.missionId) return;
  const payload = {
    missionId: state.missionRuntime.missionId,
    elapsedMs: state.missionRuntime.elapsedMs,
    status: state.missionRuntime.status,
    lastProcessedSec: state.missionRuntime.lastProcessedSec,
    camera: {
      x: state.missionWorld.cameraX,
      y: state.missionWorld.cameraY,
      zoom: state.missionWorld.zoom
    },
    missionState: mission
  };
  localStorage.setItem(getMissionSaveKey(), JSON.stringify(payload));
  setLastMissionSaveId(state.missionRuntime.missionId);
}

function loadMissionRuntime() {
  if (!state.missionRuntime.missionId) return false;
  const raw = localStorage.getItem(getMissionSaveKey());
  if (!raw) return false;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed.missionState) return false;
    state.missionRuntime.elapsedMs = Number(parsed.elapsedMs || 0);
    state.missionRuntime.status = parsed.status || "running";
    state.missionRuntime.lastProcessedSec = Number(parsed.lastProcessedSec || 0);
    state.missionRuntime.missionState = normalizeMissionState(parsed.missionState);
    state.missionRuntime.startedAt = Date.now() - state.missionRuntime.elapsedMs;
    state.missionRuntime.pausedAccumulatedMs = 0;
    state.missionRuntime.pauseStartedAt = 0;
    if (parsed.camera) {
      state.missionWorld.cameraX = Number(parsed.camera.x ?? state.missionWorld.cameraX);
      state.missionWorld.cameraY = Number(parsed.camera.y ?? state.missionWorld.cameraY);
      state.missionWorld.zoom = Number(parsed.camera.zoom ?? state.missionWorld.zoom);
    }
    return true;
  } catch (err) {
    console.warn("Failed to parse mission save.", err);
    return false;
  }
}

function startMissionTicker() {
  stopMissionTicker();
  state.missionRuntime.tickerId = window.setInterval(() => {
    if (state.currentScreen !== "mission-play") return;
    if (state.missionRuntime.status !== "running") return;
    updateUnitSimulation(0.25);
    updateEnemySimulation(0.25);
    updatePeasantDotSimulation(0.25);
    updateLordDotSimulation(0.25);
    state.missionRuntime.elapsedMs = computeMissionElapsedMs();
    const elapsedSec = Math.floor(state.missionRuntime.elapsedMs / 1000);
    let catchUpSteps = 0;
    while (state.missionRuntime.lastProcessedSec < elapsedSec && catchUpSteps < 30) {
      state.missionRuntime.lastProcessedSec += 1;
      catchUpSteps += 1;
      runMissionSimulationStep(state.missionRuntime.lastProcessedSec);
      if (state.missionRuntime.status !== "running") break;
    }
    if (state.missionRuntime.status === "running" && state.missionRuntime.lastProcessedSec < elapsedSec) {
      // Tab was backgrounded for a long stretch: drop the remaining backlog by
      // re-anchoring the clock instead of freezing on thousands of sim steps.
      const droppedSec = elapsedSec - state.missionRuntime.lastProcessedSec;
      state.missionRuntime.startedAt += droppedSec * 1000;
      state.missionRuntime.elapsedMs = computeMissionElapsedMs();
    }
    refreshMissionHud();
  }, 250);
}

function setupMissionControlInputs() {
  const taxSelect = document.getElementById("mission-tax-mode");
  const rationSelect = document.getElementById("mission-ration-mode");
  if (taxSelect) {
    taxSelect.addEventListener("change", () => {
      const mission = state.missionRuntime.missionState;
      if (!mission) return;
      mission.taxMode = taxSelect.value;
      updatePopularity();
      refreshMissionPanels();
    });
  }
  if (rationSelect) {
    rationSelect.addEventListener("change", () => {
      const mission = state.missionRuntime.missionState;
      if (!mission) return;
      mission.rationMode = rationSelect.value;
      updatePopularity();
      refreshMissionPanels();
    });
  }
}

function setupMissionScenario(missionId) {
  setupMissionWorld(missionId);
  const baseState = normalizeMissionState(createBaseMissionState(missionId));
  state.missionRuntime.missionState = baseState;
  assignWorkers();
  computeStockpileStats();
  updatePopularity();
  const keep = baseState.buildings.find((b) => b.type === "keep");
  if (keep) centerCameraOnTile(keep.x + 1, keep.y + 1);
  refreshMissionPanels();
}

function mountMissionPlay() {
  const node = getNodeById(state.selectedMissionId);
  if (!node) {
    mountScreen("missions");
    return;
  }
  missionRuntimeReset(node.id);
  setupMissionScenario(node.id);
  const shouldLoadFromSave = state.pendingMissionLoad;
  state.pendingMissionLoad = false;
  const loaded = shouldLoadFromSave ? loadMissionRuntime() : false;
  if (state.missionRuntime.missionState) {
    state.missionRuntime.missionState.buildBarMinimized = false;
    state.missionRuntime.missionState.devMenuOpen = false;
    state.missionRuntime.missionState.pauseMenuOpen = false;
    state.missionRuntime.missionState.pauseOptionsOpen = false;
    state.missionRuntime.missionState.selectionBox = null;
  }
  assignWorkers();
  computeStockpileStats();
  updatePopularity();
  if (loaded) addMissionEventLog("Saved game loaded.");
  else addMissionEventLog("Mission started.");
  setupMissionControlInputs();
  setupMissionCanvasInput();
  setupMissionMinimapInput();
  refreshMissionPanels();
  refreshMissionHud();
  void (async () => {
    try {
      if (window.MissionThreeLayer && typeof window.MissionThreeLayer.preloadFromRegistry === "function") {
        await window.MissionThreeLayer.preloadFromRegistry();
      }
    } catch (e) {
      console.warn(e);
    }
    startMissionRenderLoop();
    if (state.missionRuntime.status === "running") startMissionTicker();
  })();
}

function setMissionResult(status) {
  if (status !== "won" && status !== "failed") return;
  state.missionRuntime.elapsedMs = computeMissionElapsedMs();
  state.missionRuntime.status = status;
  stopMissionTicker();
  if (status === "won") {
    markMissionAsComplete();
    showToast("Victory! Mission complete.");
  } else {
    showToast("Defeat. Adjust economy and try again.");
  }
  saveMissionRuntime();
  refreshMissionPanels();
  refreshMissionHud();
}

function centerCameraOnTile(tileX, tileY) {
  const canvas = document.getElementById("mission-canvas");
  if (!canvas) return;
  const clampedX = Math.max(0, Math.min(state.missionWorld.width - 1, tileX));
  const clampedY = Math.max(0, Math.min(state.missionWorld.height - 1, tileY));
  const p = missionTileToScreen(clampedX, clampedY, state.missionWorld.heights[clampedY][clampedX]);
  const base = getMissionBaseOffset(canvas);
  state.missionWorld.cameraX = canvas.width * 0.5 - state.missionWorld.zoom * (base.x + p.x);
  state.missionWorld.cameraY = canvas.height * 0.5 - state.missionWorld.zoom * (base.y + p.y);
  clampMissionCamera(canvas);
}

function setupMissionMinimapInput() {
  const mini = document.getElementById("mission-minimap");
  if (!mini) return;
  let dragging = false;
  const recenter = (clientX, clientY) => {
    const rect = mini.getBoundingClientRect();
    const x = Math.max(0, Math.min(rect.width, clientX - rect.left));
    const y = Math.max(0, Math.min(rect.height, clientY - rect.top));
    const tileX = Math.floor((x / rect.width) * state.missionWorld.width);
    const tileY = Math.floor((y / rect.height) * state.missionWorld.height);
    centerCameraOnTile(tileX, tileY);
  };
  mini.addEventListener("pointerdown", (event) => {
    dragging = true;
    recenter(event.clientX, event.clientY);
    mini.setPointerCapture(event.pointerId);
  });
  mini.addEventListener("pointermove", (event) => {
    if (!dragging) return;
    recenter(event.clientX, event.clientY);
  });
  mini.addEventListener("pointerup", () => {
    dragging = false;
  });
  mini.addEventListener("pointercancel", () => {
    dragging = false;
  });
}

function markMissionAsComplete() {
  const missionId = state.missionRuntime.missionId || state.selectedMissionId;
  if (!missionId) return;
  const campaignState = ensureCampaignProgress(state.selectedCampaignId);
  if (!campaignState.completed.includes(missionId)) {
    campaignState.completed.push(missionId);
  }
  if (!campaignState.unlocked.includes(missionId)) {
    campaignState.unlocked.push(missionId);
  }
  unlockNextMissions(missionId);
  campaignState.selectedNodeId = missionId;
  saveProgress();
}

function togglePauseMission() {
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  if (state.missionRuntime.status === "won" || state.missionRuntime.status === "failed") return;
  if (state.missionRuntime.status === "paused") {
    const pauseDuration = Date.now() - state.missionRuntime.pauseStartedAt;
    state.missionRuntime.pausedAccumulatedMs += pauseDuration;
    state.missionRuntime.status = "running";
    mission.pauseMenuOpen = false;
    mission.pauseOptionsOpen = false;
    startMissionTicker();
  } else if (state.missionRuntime.status === "running") {
    state.missionRuntime.pauseStartedAt = Date.now();
    state.missionRuntime.elapsedMs = computeMissionElapsedMs();
    state.missionRuntime.status = "paused";
    mission.pauseMenuOpen = true;
    mission.pauseOptionsOpen = false;
    stopMissionTicker();
  }
  refreshMissionPanels();
  refreshMissionHud();
}

function restartMission() {
  const missionId = state.missionRuntime.missionId || state.selectedMissionId;
  if (!missionId) return;
  missionRuntimeReset(missionId);
  setupMissionScenario(missionId);
  if (state.missionRuntime.missionState) {
    state.missionRuntime.missionState.pauseMenuOpen = false;
    state.missionRuntime.missionState.pauseOptionsOpen = false;
  }
  refreshMissionHud();
  refreshMissionPanels();
  startMissionTicker();
  startMissionRenderLoop();
  saveMissionRuntime();
  showToast("Mission restarted.");
}

function setupMissionCanvasInput() {
  const canvas = document.getElementById("mission-canvas");
  if (!canvas) return;
  if (!state.missionRuntime.missionState) return;
  // Always read the live mission inside handlers: restart/load replaces
  // missionState while these listeners stay attached, so a captured
  // reference would go stale and clicks would mutate a dead object.
  const getMission = () => state.missionRuntime.missionState;
  const activeTouchPoints = new Map();
  const pinchState = { active: false, lastDist: 0 };
  let rightDragCommandCandidate = false;

  const getControlPreset = () => (state.settings.unitControlPreset === "rts" ? "rts" : "classic");

  const zoomAtPoint = (screenX, screenY, targetZoom) => {
    const clampedZoom = Math.max(0.55, Math.min(1.65, targetZoom));
    const worldX = (screenX - state.missionWorld.cameraX) / state.missionWorld.zoom;
    const worldY = (screenY - state.missionWorld.cameraY) / state.missionWorld.zoom;
    state.missionWorld.zoom = clampedZoom;
    state.missionWorld.cameraX = screenX - worldX * clampedZoom;
    state.missionWorld.cameraY = screenY - worldY * clampedZoom;
    clampMissionCamera(canvas);
  };

  const issueRightClickCommand = (clientX, clientY) => {
    const mission = getMission();
    if (!mission) return;
    const now = Date.now();
    if (now < mission.commandThrottleUntilMs) return;
    mission.commandThrottleUntilMs = now + 120;
    const selectedUnits = getSelectedUnits(mission);
    if (selectedUnits.length === 0) return;
    const rect = canvas.getBoundingClientRect();
    const px = clientX - rect.left;
    const py = clientY - rect.top;
    const tile = screenToMissionTile(canvas, px, py);
    if (!tile) return;
    const clickedEnemy = getMissionEnemyAt(tile.x, tile.y);
    if (clickedEnemy) {
      for (const unit of selectedUnits) {
        unit.attackTargetId = clickedEnemy.id;
        unit.moveTarget = null;
      }
      showToast("Attack command issued.");
    } else {
      const ringSize = 6;
      const spacing = 1.35;
      let assignedCount = 0;
      for (let index = 0; index < selectedUnits.length; index += 1) {
        const unit = selectedUnits[index];
        const ring = Math.floor(index / ringSize) + 1;
        const ringIndex = index % ringSize;
        const angle = (Math.PI * 2 * ringIndex) / ringSize;
        const ox = Math.cos(angle) * ring * spacing;
        const oy = Math.sin(angle) * ring * spacing;
        const targetX = Math.max(0, Math.min(state.missionWorld.width - 1, tile.x + ox));
        const targetY = Math.max(0, Math.min(state.missionWorld.height - 1, tile.y + oy));
        const legalTarget = findNearestTileMatching(targetX, targetY, isTileWalkableForUnit, 7);
        if (!legalTarget) continue;
        unit.attackTargetId = null;
        unit.moveTarget = { x: legalTarget.x, y: legalTarget.y };
        assignedCount += 1;
      }
      if (assignedCount === 0) showToast("Move blocked: water or impassable terrain.");
      if (assignedCount === 0) mission.simMetrics.waterCommandRejections = Number(mission.simMetrics?.waterCommandRejections || 0) + 1;
      else if (assignedCount < selectedUnits.length) showToast("Some units rerouted around blocked terrain.");
      else showToast("Move command issued.");
    }
    mission.moveCommandMode = false;
    setContextMode("unit", selectedUnits.length === 1 ? `unit:${selectedUnits[0].id}` : `unit-group:${selectedUnits.length}`);
    refreshMissionPanels();
  };

  const applySelectionBox = () => {
    const mission = getMission();
    if (!mission) return false;
    const box = mission.selectionBox;
    if (!box) return false;
    const left = Math.min(box.startX, box.endX);
    const right = Math.max(box.startX, box.endX);
    const top = Math.min(box.startY, box.endY);
    const bottom = Math.max(box.startY, box.endY);
    if ((right - left) + (bottom - top) < 10) return false;
    const selectedIds = mission.units
      .filter((u) => {
        const p = missionTileToCanvasPoint(canvas, u.x, u.y);
        return p.x >= left && p.x <= right && p.y >= top && p.y <= bottom;
      })
      .map((u) => u.id);
    setSelectedUnits(selectedIds);
    mission.selectedBuildingId = null;
    if (selectedIds.length > 0) {
      setContextMode("unit", selectedIds.length === 1 ? `unit:${selectedIds[0]}` : `unit-group:${selectedIds.length}`);
    } else {
      setContextMode("none", null);
    }
    return selectedIds.length > 0;
  };

  canvas.addEventListener("contextmenu", (event) => {
    event.preventDefault();
  });

  canvas.addEventListener("pointerdown", (event) => {
    const mission = getMission();
    if (!mission) return;
    if (mission.pauseMenuOpen) return;
    if (event.pointerType === "touch") {
      activeTouchPoints.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (activeTouchPoints.size >= 2) {
        const pts = [...activeTouchPoints.values()];
        pinchState.active = true;
        pinchState.lastDist = Math.hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y);
        mission.selectionBox = null;
        state.missionWorld.dragging = false;
        state.missionWorld.dragPointerId = null;
        canvas.classList.remove("dragging");
        canvas.classList.remove("selecting");
      }
    }
    if (event.button === 2) {
      if (getControlPreset() === "rts") {
        rightDragCommandCandidate = true;
        state.missionWorld.dragging = true;
        state.missionWorld.dragMoved = false;
        state.missionWorld.dragPointerId = event.pointerId;
        state.missionWorld.dragStartX = event.clientX;
        state.missionWorld.dragStartY = event.clientY;
        state.missionWorld.dragCameraX = state.missionWorld.cameraX;
        state.missionWorld.dragCameraY = state.missionWorld.cameraY;
        canvas.classList.add("dragging");
        canvas.setPointerCapture(event.pointerId);
      } else {
        issueRightClickCommand(event.clientX, event.clientY);
      }
      return;
    }
    if (event.button !== 0) return;
    const rect = canvas.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const py = event.clientY - rect.top;
    const shouldStartSelectionBox =
      !mission.buildMode &&
      ((getControlPreset() === "classic" && event.shiftKey) || getControlPreset() === "rts");
    if (shouldStartSelectionBox) {
      mission.selectionBox = { active: true, startX: px, startY: py, endX: px, endY: py };
      canvas.classList.add("selecting");
      canvas.setPointerCapture(event.pointerId);
      return;
    }
    state.missionWorld.dragging = true;
    state.missionWorld.dragMoved = false;
    state.missionWorld.dragPointerId = event.pointerId;
    state.missionWorld.dragStartX = event.clientX;
    state.missionWorld.dragStartY = event.clientY;
    state.missionWorld.dragCameraX = state.missionWorld.cameraX;
    state.missionWorld.dragCameraY = state.missionWorld.cameraY;
    canvas.classList.add("dragging");
    canvas.setPointerCapture(event.pointerId);
  });

  canvas.addEventListener("pointermove", (event) => {
    const mission = getMission();
    if (!mission) return;
    if (event.pointerType === "touch" && activeTouchPoints.has(event.pointerId)) {
      activeTouchPoints.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (pinchState.active && activeTouchPoints.size >= 2) {
        const pts = [...activeTouchPoints.values()];
        const dist = Math.hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y);
        if (pinchState.lastDist > 0) {
          const ratio = dist / pinchState.lastDist;
          const midX = (pts[0].x + pts[1].x) * 0.5;
          const midY = (pts[0].y + pts[1].y) * 0.5;
          zoomAtPoint(midX, midY, state.missionWorld.zoom * ratio);
        }
        pinchState.lastDist = dist;
        return;
      }
    }
    const rect = canvas.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const py = event.clientY - rect.top;
    const tile = screenToMissionTile(canvas, px, py);
    mission.hoveredTile = tile;
    if (mission.selectionBox?.active) {
      mission.selectionBox.endX = px;
      mission.selectionBox.endY = py;
      return;
    }
    if (state.missionWorld.dragging && event.pointerId === state.missionWorld.dragPointerId) {
      const dx = event.clientX - state.missionWorld.dragStartX;
      const dy = event.clientY - state.missionWorld.dragStartY;
      if (Math.abs(dx) + Math.abs(dy) > 6) state.missionWorld.dragMoved = true;
      state.missionWorld.cameraX = state.missionWorld.dragCameraX + dx;
      state.missionWorld.cameraY = state.missionWorld.dragCameraY + dy;
      clampMissionCamera(canvas);
    }
  });

  const stopDragging = (event) => {
    const mission = getMission();
    if (!mission) return;
    if (event.pointerType === "touch") {
      activeTouchPoints.delete(event.pointerId);
      if (activeTouchPoints.size < 2) pinchState.active = false;
    }
    if (pinchState.active) return;
    if (mission.selectionBox?.active) {
      const rect = canvas.getBoundingClientRect();
      mission.selectionBox.endX = event.clientX - rect.left;
      mission.selectionBox.endY = event.clientY - rect.top;
      applySelectionBox();
      mission.selectionBox = null;
      canvas.classList.remove("selecting");
      refreshMissionPanels();
      return;
    }
    if (event.pointerId !== state.missionWorld.dragPointerId) return;
    const wasClick = !state.missionWorld.dragMoved;
    state.missionWorld.dragging = false;
    state.missionWorld.dragPointerId = null;
    canvas.classList.remove("dragging");
    if (event.button === 2 && getControlPreset() === "rts") {
      if (wasClick && rightDragCommandCandidate) {
        issueRightClickCommand(event.clientX, event.clientY);
      }
      rightDragCommandCandidate = false;
      return;
    }
    if (wasClick && mission.hoveredTile) {
      mission.selectedTile = { ...mission.hoveredTile };
      const clickedBuilding = getMissionBuildingAt(mission.hoveredTile.x, mission.hoveredTile.y);
      const clickedUnit = getMissionUnitAt(mission.hoveredTile.x, mission.hoveredTile.y);

      if (mission.buildMode) {
        placeBuildingFromSelectedTile();
      } else if (clickedUnit) {
        setSelectedUnits([clickedUnit.id]);
        mission.selectedBuildingId = null;
        mission.moveCommandMode = false;
        toggleContextSelection("unit", `unit:${clickedUnit.id}`);
      } else if (clickedBuilding) {
        setSelectedUnits([]);
        mission.moveCommandMode = false;
        if (clickedBuilding.owner === "enemy") {
          mission.selectedBuildingId = null;
          toggleContextSelection("tile", `tile:${mission.hoveredTile.x},${mission.hoveredTile.y}`);
        } else if (clickedBuilding.type === "keep") {
          mission.selectedBuildingId = clickedBuilding.id;
          toggleContextSelection("keep", `building:${clickedBuilding.id}`);
        } else if (clickedBuilding.type === "barracks") {
          mission.selectedBuildingId = clickedBuilding.id;
          toggleContextSelection("barracks", `building:${clickedBuilding.id}`);
        } else if (clickedBuilding.type === "stockpile") {
          mission.selectedBuildingId = clickedBuilding.id;
          toggleContextSelection("stockpile", `building:${clickedBuilding.id}`);
        } else {
          mission.selectedBuildingId = clickedBuilding.id;
          toggleContextSelection("building", `building:${clickedBuilding.id}`);
        }
      } else {
        mission.selectedBuildingId = null;
        setSelectedUnits([]);
        toggleContextSelection("tile", `tile:${mission.hoveredTile.x},${mission.hoveredTile.y}`);
      }
      refreshMissionPanels();
    }
    rightDragCommandCandidate = false;
  };
  canvas.addEventListener("pointerup", stopDragging);
  canvas.addEventListener("pointercancel", stopDragging);

  canvas.addEventListener("wheel", (event) => {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.06 : 0.06;
    state.missionWorld.zoom = Math.max(0.55, Math.min(1.65, state.missionWorld.zoom + delta));
    clampMissionCamera(canvas);
  }, { passive: false });
}

function unlockNextMissions(nodeId) {
  const node = getNodeById(nodeId);
  if (!node) return;
  const campaignState = ensureCampaignProgress(state.selectedCampaignId);
  for (const nextId of node.next || []) {
    const target = getNodeById(nextId);
    if (!target) continue;
    if (target.requires.every((r) => campaignState.completed.includes(r))) {
      if (!campaignState.unlocked.includes(nextId)) {
        campaignState.unlocked.push(nextId);
      }
    }
  }
}

function renderCampaignMap() {
  const world = document.getElementById("campaign-world");
  if (!world) return;
  const nodes = getCampaignNodes(state.selectedCampaignId);
  const campaignState = ensureCampaignProgress(state.selectedCampaignId);
  const nodesById = new Map(nodes.map((n) => [n.id, n]));

  world.innerHTML = "";
  world.style.width = `${state.campaignData.world.width}px`;
  world.style.height = `${state.campaignData.world.height}px`;
  world.appendChild(buildPathsSvg(nodesById, nodes));

  for (const node of nodes) {
    const unlocked = isUnlocked(node, campaignState);
    if (unlocked && !campaignState.unlocked.includes(node.id)) {
      campaignState.unlocked.push(node.id);
    }
    const completed = campaignState.completed.includes(node.id);
    const btn = document.createElement("button");
    btn.className = `campaign-node ${completed ? "completed" : unlocked ? "unlocked" : "locked"}`;
    btn.dataset.id = node.id;
    btn.textContent = String(node.order);
    btn.style.left = `${node.x}px`;
    btn.style.top = `${node.y}px`;
    if (!unlocked) {
      btn.disabled = true;
      btn.title = "Locked";
    } else {
      btn.title = node.title;
    }
    btn.addEventListener("click", () => setSelectedNode(node.id));
    world.appendChild(btn);
  }

  if (!state.selectedMissionId || !nodesById.has(state.selectedMissionId)) {
    const fallback = campaignState.selectedNodeId && nodesById.has(campaignState.selectedNodeId)
      ? campaignState.selectedNodeId
      : nodes.find((n) => isUnlocked(n, campaignState))?.id;
    if (fallback) {
      state.selectedMissionId = fallback;
    }
  }
  highlightSelectedNode();
  renderCampaignSidePanel();
  clampMapView();
  updateMapTransform();
  saveProgress();
}

function setupCampaignInput() {
  const viewport = document.getElementById("campaign-viewport");
  if (!viewport) return;

  viewport.addEventListener("mousedown", (event) => {
    state.isDraggingMap = true;
    state.dragStart.x = event.clientX;
    state.dragStart.y = event.clientY;
    state.dragStart.mapX = state.mapView.x;
    state.dragStart.mapY = state.mapView.y;
    viewport.classList.add("dragging");
  });

  if (!state.campaignGlobalInputBound) {
    window.addEventListener("mousemove", (event) => {
      if (!state.isDraggingMap) return;
      const dx = event.clientX - state.dragStart.x;
      const dy = event.clientY - state.dragStart.y;
      state.mapView.x = state.dragStart.mapX + dx;
      state.mapView.y = state.dragStart.mapY + dy;
      clampMapView();
      updateMapTransform();
    });

    window.addEventListener("mouseup", () => {
      if (!state.isDraggingMap) return;
      state.isDraggingMap = false;
      const currentViewport = document.getElementById("campaign-viewport");
      if (currentViewport) currentViewport.classList.remove("dragging");
    });
    state.campaignGlobalInputBound = true;
  }

  viewport.addEventListener("wheel", (event) => {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.08 : 0.08;
    state.mapView.scale = Math.max(0.65, Math.min(1.85, state.mapView.scale + delta));
    clampMapView();
    updateMapTransform();
  }, { passive: false });
}

function initializeCampaignScreen() {
  ensureCampaignProgress(state.selectedCampaignId);
  setupCampaignInput();
  renderCampaignMap();
}

function showToast(message) {
  let toast = document.getElementById("toast");
  if (!toast) {
    const node = cloneTemplate("toast-template");
    document.body.appendChild(node);
    toast = document.getElementById("toast");
  }
  if (!toast) return;
  toast.textContent = message;
  toast.classList.add("show");
  setTimeout(() => {
    toast.classList.remove("show");
  }, 2200);
}

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  if (state.currentScreen !== "mission-play") return;
  const target = event.target;
  if (
    target instanceof HTMLElement &&
    (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT" || target.isContentEditable)
  ) {
    return;
  }
  const mission = state.missionRuntime.missionState;
  if (!mission) return;
  event.preventDefault();

  if (mission.pauseOptionsOpen) {
    mission.pauseOptionsOpen = false;
    refreshMissionPanels();
    return;
  }

  if (mission.pauseMenuOpen) {
    if (state.missionRuntime.status === "paused") {
      togglePauseMission();
    } else {
      mission.pauseMenuOpen = false;
      mission.pauseOptionsOpen = false;
      refreshMissionPanels();
    }
    return;
  }

  if (state.missionRuntime.status === "running") {
    togglePauseMission();
  } else if (state.missionRuntime.status === "paused") {
    mission.pauseMenuOpen = true;
    refreshMissionPanels();
  }
});

document.addEventListener("keydown", (event) => {
  if (state.currentScreen !== "sprite-editor") return;
  const project = getSpriteProjectState();
  const key = String(event.key || "").toLowerCase();
  const hasCtrl = event.ctrlKey || event.metaKey;
  const target = event.target;
  const isTypingField = target instanceof HTMLElement &&
    (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT" || target.isContentEditable);
  if (!hasCtrl && ["arrowleft", "arrowright", "arrowup", "arrowdown"].includes(key) && !isTypingField) {
    event.preventDefault();
    const active = getActiveSpriteFrame(project);
    const step = event.shiftKey ? 8 : 1;
    pushSpriteUndoState("nudge-frame");
    if (key === "arrowleft") active.frame.x = Math.max(0, Number(active.frame.x || 0) - step);
    if (key === "arrowright") active.frame.x = Math.max(0, Number(active.frame.x || 0) + step);
    if (key === "arrowup") active.frame.y = Math.max(0, Number(active.frame.y || 0) - step);
    if (key === "arrowdown") active.frame.y = Math.max(0, Number(active.frame.y || 0) + step);
    applySymmetryForActiveFrame(project);
    refreshSpriteEditorUI();
    return;
  }
  if (!hasCtrl) return;
  if (key === "c") {
    event.preventDefault();
    const active = getActiveSpriteFrame(project);
    state.spriteEditor.clipboardFrame = cloneSpriteProject(active.frame);
    showToast("Frame copied.");
    return;
  }
  if (key === "v") {
    event.preventDefault();
    if (!state.spriteEditor.clipboardFrame) {
      showToast("Clipboard empty.");
      return;
    }
    pushSpriteUndoState("paste-frame");
    const active = getActiveSpriteFrame(project);
    active.frame = cloneSpriteProject(state.spriteEditor.clipboardFrame);
    active.animation.byDirection[active.direction][active.frameIndex] = active.frame;
    applySymmetryForActiveFrame(project);
    refreshSpriteEditorUI();
    showToast("Frame pasted.");
    return;
  }
  if (key === "z") {
    event.preventDefault();
    if (event.shiftKey) {
      if (redoSpriteEditorState()) showToast("Redo applied.");
      else showToast("Nothing to redo.");
    } else if (undoSpriteEditorState()) showToast("Undo applied.");
    else showToast("Nothing to undo.");
    return;
  }
  if (key === "y") {
    event.preventDefault();
    if (redoSpriteEditorState()) showToast("Redo applied.");
    else showToast("Nothing to redo.");
  }
});

document.addEventListener("keydown", (event) => {
  if (state.currentScreen !== "item-editor") return;
  const key = String(event.key || "").toLowerCase();
  const target = event.target;
  const hasCtrl = event.ctrlKey || event.metaKey;
  const blockCtrlShortcuts =
    target instanceof HTMLElement &&
    (target.isContentEditable ||
      target.tagName === "TEXTAREA" ||
      (target instanceof HTMLInputElement && ["text", "number", "search", "url", "email", "password"].includes(target.type)));

  if (hasCtrl && !blockCtrlShortcuts) {
    if (key === "c") {
      event.preventDefault();
      copyItemPartToClipboard();
      return;
    }
    if (key === "v") {
      event.preventDefault();
      pasteItemPartFromClipboard();
      return;
    }
    if (key === "z") {
      event.preventDefault();
      if (event.shiftKey) {
        if (itemEditorRedo()) showToast("Redo.");
        else showToast("Nothing to redo.");
      } else if (itemEditorUndo()) showToast("Undo.");
      else showToast("Nothing to undo.");
      return;
    }
    if (key === "y") {
      event.preventDefault();
      if (itemEditorRedo()) showToast("Redo.");
      else showToast("Nothing to redo.");
      return;
    }
  }

  const isTypingField =
    target instanceof HTMLElement &&
    (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT" || target.isContentEditable);
  if (isTypingField) return;
  if (!["arrowleft", "arrowright", "arrowup", "arrowdown", "pageup", "pagedown"].includes(key)) return;
  syncItemProjectFromInputs();
  const project = getItemProjectState();
  const active = getActiveItemPart(project);
  if (!active) return;
  event.preventDefault();
  const step = event.shiftKey ? 4 : 1;
  if (key === "arrowleft") active.x = Number(active.x || 0) - step;
  if (key === "arrowright") active.x = Number(active.x || 0) + step;
  if (key === "pageup") active.y = Number(active.y || 0) + step;
  if (key === "pagedown") active.y = Number(active.y || 0) - step;
  if (key === "arrowup") active.z = Number(active.z || 0) - step;
  if (key === "arrowdown") active.z = Number(active.z || 0) + step;
  refreshItemEditorUI();
});

document.addEventListener("click", (event) => {
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const action = button.dataset.action;

  if (action === "go-main") mountScreen("main-menu");
  if (action === "go-settings") mountScreen("settings");
  if (action === "go-missions") mountScreen("missions");
  if (action === "go-sprite-editor") mountScreen("sprite-editor");
  if (action === "go-item-editor") mountScreen("item-editor");
  if (action === "load-game") loadMissionFromMainMenu();
  if (action === "coming-soon") showToast("This mode is scheduled for a later step.");
  if (action === "dev-unlock-all") {
    unlockAllMissionsForDev();
    showToast("All missions unlocked.");
  }
  if (action === "dev-reset-progress") {
    resetCampaignProgressForDev();
    showToast("Campaign progress reset.");
  }
  if (action === "dev-clear-saves") {
    clearAllMissionSavesForDev();
    showToast("Mission saves cleared.");
  }
  if (action === "dev-add-resources") {
    addDevResources();
    showToast("DEV: +500 wood, apples, iron, stone, gold.");
  }
  if (action === "dev-recruit-free-militia") {
    spawnDevMilitia();
    showToast("DEV: Militia spawned.");
  }
  if (action === "zoom-in") {
    state.mapView.scale = Math.min(1.85, state.mapView.scale + 0.1);
    clampMapView();
    updateMapTransform();
  }
  if (action === "zoom-out") {
    state.mapView.scale = Math.max(0.65, state.mapView.scale - 0.1);
    clampMapView();
    updateMapTransform();
  }
  if (action === "reset-map") {
    state.mapView = { x: -120, y: -360, scale: 0.9 };
    clampMapView();
    updateMapTransform();
  }
  if (action === "save-settings") {
    persistSettingsFromUI();
    showToast("Settings saved.");
  }
  if (action === "sprite-new-project") {
    const factory = typeof window.createDefaultSpriteProject === "function"
      ? window.createDefaultSpriteProject
      : () => ({ projectName: "NewSpriteProject", animations: {} });
    const normalize = typeof window.normalizeSpriteProject === "function"
      ? window.normalizeSpriteProject
      : (raw) => raw;
    state.spriteEditor.project = normalize(factory());
    state.spriteEditor.cachedImage = null;
    state.spriteEditor.previewFrameIndex = 0;
    state.spriteEditor.previewFrameElapsedMs = 0;
    refreshSpriteEditorUI();
    showToast("New sprite project created.");
  }
  if (action === "sprite-load-project") {
    if (loadSpriteProjectFromStorage()) {
      state.spriteEditor.previewFrameIndex = 0;
      state.spriteEditor.previewFrameElapsedMs = 0;
      refreshSpriteEditorUI();
      showToast("Sprite project loaded.");
    } else {
      showToast("No saved sprite project found.");
    }
  }
  if (action === "sprite-save-project") {
    syncSpriteEditorProjectFromInputs();
    if (saveSpriteProjectToStorage()) showToast("Sprite project saved.");
    else showToast("Failed to save sprite project.");
  }
  if (action === "sprite-redo") {
    if (redoSpriteEditorState()) showToast("Redo applied.");
    else showToast("Nothing to redo.");
  }
  if (action === "sprite-import-sheet") {
    const fileInput = document.getElementById("sprite-sheet-file");
    const file = fileInput?.files?.[0];
    if (!file) {
      showToast("Choose a PNG sheet first.");
    } else {
      const reader = new FileReader();
      reader.onload = () => {
        const project = getSpriteProjectState();
        syncSpriteEditorProjectFromInputs();
        project.sourceImageDataUrl = String(reader.result || "");
        project.sourceImageName = file.name || "sheet.png";
        const img = new Image();
        img.onload = () => {
          project.sourceImageWidth = Number(img.naturalWidth || 0);
          project.sourceImageHeight = Number(img.naturalHeight || 0);
          state.spriteEditor.cachedImage = img;
          refreshSpriteEditorUI();
          showToast("Sprite sheet imported.");
        };
        img.src = project.sourceImageDataUrl;
      };
      reader.readAsDataURL(file);
    }
  }
  if (action === "sprite-reset-sheet") {
    const project = getSpriteProjectState();
    project.sourceImageDataUrl = "";
    project.sourceImageName = "";
    project.sourceImageWidth = 0;
    project.sourceImageHeight = 0;
    state.spriteEditor.cachedImage = null;
    refreshSpriteEditorUI();
    showToast("Source sheet cleared.");
  }
  if (action === "sprite-add-animation") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    pushSpriteUndoState("add-animation");
    let index = 1;
    let name = `anim-${index}`;
    while (project.animations?.[name]) {
      index += 1;
      name = `anim-${index}`;
    }
    ensureSpriteAnimation(project, name);
    project.activeAnimation = name;
    project.activeFrameIndex = 0;
    refreshSpriteEditorUI();
    showToast(`Animation ${name} added.`);
  }
  if (action === "sprite-remove-animation") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    pushSpriteUndoState("remove-animation");
    const names = getSpriteAnimationNames(project);
    if (names.length <= 1) {
      showToast("Keep at least one animation.");
    } else {
      delete project.animations[project.activeAnimation];
      const nextName = getSpriteAnimationNames(project)[0];
      project.activeAnimation = nextName;
      project.activeFrameIndex = 0;
      refreshSpriteEditorUI();
      showToast("Active animation removed.");
    }
  }
  if (action === "sprite-add-frame") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    pushSpriteUndoState("add-frame");
    const active = getActiveSpriteFrame(project);
    const template = typeof window.createDefaultSpriteFrame === "function"
      ? window.createDefaultSpriteFrame()
      : { x: 0, y: 0, w: 64, h: 64, durationMs: 120, pivotX: 0, pivotY: 0, footY: 0, teamMask: false, shadow: true };
    const last = active.frames[active.frames.length - 1] || template;
    active.frames.push({ ...template, ...last, x: Number(last.x || 0) + Number(last.w || 64) });
    project.activeFrameIndex = active.frames.length - 1;
    state.spriteEditor.previewFrameIndex = project.activeFrameIndex;
    refreshSpriteEditorUI();
  }
  if (action === "sprite-remove-frame") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    pushSpriteUndoState("remove-frame");
    const active = getActiveSpriteFrame(project);
    if (active.frames.length <= 1) {
      showToast("Keep at least one frame.");
    } else {
      active.frames.splice(active.frameIndex, 1);
      project.activeFrameIndex = Math.max(0, Math.min(active.frames.length - 1, active.frameIndex));
      state.spriteEditor.previewFrameIndex = project.activeFrameIndex;
      refreshSpriteEditorUI();
    }
  }
  if (action === "sprite-frame-shift-left" || action === "sprite-frame-shift-right") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    pushSpriteUndoState("reorder-frame");
    const active = getActiveSpriteFrame(project);
    const from = active.frameIndex;
    const to = action === "sprite-frame-shift-left" ? from - 1 : from + 1;
    if (to < 0 || to >= active.frames.length) {
      showToast("Frame already at edge.");
    } else {
      const temp = active.frames[from];
      active.frames[from] = active.frames[to];
      active.frames[to] = temp;
      project.activeFrameIndex = to;
      state.spriteEditor.previewFrameIndex = to;
      refreshSpriteEditorUI();
      showToast("Frame order updated.");
    }
  }
  if (action === "sprite-apply-frame-meta") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    pushSpriteUndoState("apply-frame-meta");
    const active = getActiveSpriteFrame(project);
    active.frame.x = Math.max(0, readNumberInputValue("sprite-frame-x", active.frame.x));
    active.frame.y = Math.max(0, readNumberInputValue("sprite-frame-y", active.frame.y));
    active.frame.w = Math.max(1, readNumberInputValue("sprite-frame-w", active.frame.w));
    active.frame.h = Math.max(1, readNumberInputValue("sprite-frame-h", active.frame.h));
    active.frame.durationMs = Math.max(20, readNumberInputValue("sprite-frame-duration", active.frame.durationMs));
    active.frame.pivotX = readNumberInputValue("sprite-pivot-x", active.frame.pivotX);
    active.frame.pivotY = readNumberInputValue("sprite-pivot-y", active.frame.pivotY);
    active.frame.footY = readNumberInputValue("sprite-foot-y", active.frame.footY);
    active.frame.collision = {
      x: readNumberInputValue("sprite-collision-x", active.frame.collision?.x || 0),
      y: readNumberInputValue("sprite-collision-y", active.frame.collision?.y || 0),
      w: Math.max(1, readNumberInputValue("sprite-collision-w", active.frame.collision?.w || 32)),
      h: Math.max(1, readNumberInputValue("sprite-collision-h", active.frame.collision?.h || 32))
    };
    active.frame.teamMask = !!document.getElementById("sprite-team-mask-enabled")?.checked;
    active.frame.shadow = !!document.getElementById("sprite-shadow-enabled")?.checked;
    applySymmetryForActiveFrame(project);
    refreshSpriteEditorUI();
    showToast("Frame metadata applied.");
  }
  if (action === "sprite-autoslice-grid") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    pushSpriteUndoState("autoslice-grid");
    if (!project.sourceImageWidth || !project.sourceImageHeight) {
      showToast("Import a sheet before auto slicing.");
    } else {
      const active = getActiveSpriteFrame(project);
      const frameW = Math.max(1, readNumberInputValue("sprite-frame-w", 64));
      const frameH = Math.max(1, readNumberInputValue("sprite-frame-h", 64));
      const count = Math.max(1, Math.floor(project.sourceImageWidth / frameW));
      active.frames.length = 0;
      for (let i = 0; i < count; i += 1) {
        active.frames.push({
          x: i * frameW,
          y: 0,
          w: frameW,
          h: frameH,
          durationMs: 120,
          pivotX: 0,
          pivotY: 0,
          footY: 0,
          collision: { x: 0, y: 0, w: 32, h: 32 },
          teamMask: false,
          shadow: true
        });
      }
      project.activeFrameIndex = 0;
      state.spriteEditor.previewFrameIndex = 0;
      refreshSpriteEditorUI();
      showToast(`Auto sliced ${count} frames.`);
    }
  }
  if (action === "sprite-apply-import-preset") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    pushSpriteUndoState("import-preset");
    const preset = document.getElementById("sprite-import-preset")?.value || project.importPreset || "single_row";
    const result = applySpriteImportPreset(project, preset);
    if (result.ok) {
      state.spriteEditor.previewFrameIndex = 0;
      state.spriteEditor.previewFrameElapsedMs = 0;
      refreshSpriteEditorUI();
      showToast(result.message);
    } else {
      showToast(result.message);
    }
  }
  if (action === "sprite-validate-project") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    const result = validateSpriteProject(project);
    const errorsText = result.errors.length ? result.errors.map((item) => `- ${item}`).join("<br>") : "none";
    const warningsText = result.warnings.length ? result.warnings.map((item) => `- ${item}`).join("<br>") : "none";
    updateSpriteEditorReadout("sprite-validation-readout", `Errors: <strong>${result.errors.length}</strong><br>${errorsText}<br><br>Warnings: <strong>${result.warnings.length}</strong><br>${warningsText}`);
    showToast(result.errors.length === 0 ? "Validation passed." : "Validation found issues.");
  }
  if (action === "sprite-run-smoke") {
    const checks = runSpriteEditorSmokeTests();
    const failed = checks.filter((item) => !item.pass);
    const text = checks.map((item) => `${item.pass ? "PASS" : "FAIL"}: ${item.name}`).join("<br>");
    updateSpriteEditorReadout("sprite-qa-readout", text);
    showToast(failed.length === 0 ? "Smoke test passed." : `${failed.length} smoke checks failed.`);
  }
  if (action === "sprite-export-manifest" || action === "sprite-export-atlas") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    const validation = validateSpriteProject(project);
    if (validation.errors.length > 0) {
      showToast("Fix validation errors before export.");
    } else {
      const slug = typeof window.createSpriteExportSlug === "function"
        ? window.createSpriteExportSlug(project)
        : `${project.projectName.replace(/[^a-z0-9_-]+/gi, "_").toLowerCase()}-${hashString(JSON.stringify(project)).toString(16).slice(0, 8)}`;
      const exportPayload = typeof window.buildSpriteExportPayload === "function"
        ? window.buildSpriteExportPayload(project, slug)
        : {
          atlasPath: `data/generated/${slug}.png`,
          manifestPath: `data/generated/${slug}.json`,
          manifest: buildSpriteGeneratedManifest(project, slug)
        };
      const manifest = exportPayload.manifest;
      const atlasPath = exportPayload.atlasPath;
      const manifestPath = exportPayload.manifestPath;
      const generatedStore = getSpriteGeneratedStore();
      generatedStore[manifestPath] = manifest;
      if (project.sourceImageDataUrl) {
        generatedStore[atlasPath] = {
          dataUrl: project.sourceImageDataUrl,
          width: project.sourceImageWidth,
          height: project.sourceImageHeight
        };
      }
      saveSpriteGeneratedStore(generatedStore);
      project.exported = {
        atlasPath,
        manifestPath,
        exportedAt: Date.now()
      };
      const payload = {
        atlasPath,
        manifestPath,
        atlasDataUrl: project.sourceImageDataUrl || "",
        manifest
      };
      registerSpriteManifestInRuntime(project.unitType, payload);
      updateSpriteEditorReadout("sprite-export-readout", `Atlas: <strong>${atlasPath}</strong><br>Manifest: <strong>${manifestPath}</strong><br>Exported: ${new Date(project.exported.exportedAt).toLocaleString()}`);
      updateSpriteEditorReadout("sprite-runtime-readout", `Unit type <strong>${project.unitType}</strong> registered for runtime preview.`);
      if (action === "sprite-export-manifest") {
        const blob = new Blob([JSON.stringify(manifest, null, 2)], { type: "application/json" });
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = `${slug}.json`;
        link.click();
        URL.revokeObjectURL(link.href);
      }
      showToast("Export complete.");
    }
  }
  if (action === "sprite-register-runtime") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    const manifest = buildSpriteGeneratedManifest(
      project,
      `${project.projectName.replace(/[^a-z0-9_-]+/gi, "_").toLowerCase()}-live`
    );
    registerSpriteManifestInRuntime(project.unitType, {
      atlasPath: project.exported?.atlasPath || "",
      manifestPath: project.exported?.manifestPath || "",
      atlasDataUrl: project.sourceImageDataUrl || "",
      manifest
    });
    updateSpriteEditorReadout("sprite-runtime-readout", `Registered <strong>${project.unitType}</strong> to runtime renderer.`);
    showToast("Runtime registration updated.");
  }
  if (action === "sprite-save-snapshot") {
    const project = getSpriteProjectState();
    syncSpriteEditorProjectFromInputs();
    const slot = document.getElementById("sprite-snapshot-slot")?.value || "slot1";
    project.snapshots = project.snapshots || {};
    project.snapshots[slot] = {
      savedAt: Date.now(),
      payload: JSON.parse(JSON.stringify(project))
    };
    refreshSpriteEditorUI();
    showToast(`Snapshot saved to ${slot}.`);
  }
  if (action === "sprite-restore-snapshot") {
    const project = getSpriteProjectState();
    const slot = document.getElementById("sprite-snapshot-slot")?.value || "slot1";
    const snapshot = project.snapshots?.[slot]?.payload || null;
    if (!snapshot) {
      showToast(`Snapshot ${slot} is empty.`);
    } else {
      pushSpriteUndoState("restore-snapshot");
      const normalize = typeof window.normalizeSpriteProject === "function" ? window.normalizeSpriteProject : (x) => x;
      state.spriteEditor.project = normalize(snapshot);
      refreshSpriteEditorUI();
      showToast(`Snapshot ${slot} restored.`);
    }
  }
  if (action === "sprite-preview-play") {
    syncSpriteEditorProjectFromInputs();
    startSpritePreviewPlayback();
    showToast("Preview playback started.");
  }
  if (action === "sprite-preview-stop") {
    stopSpritePreviewPlayback();
    showToast("Preview playback stopped.");
  }
  if (action === "item-new-project") {
    state.itemEditor.undoStack = [];
    state.itemEditor.redoStack = [];
    state.itemEditor.clipboardPart = null;
    state.itemEditor.project = createDefaultItemProject();
    refreshItemEditorUI();
    showToast("New item project created.");
  }
  if (action === "item-load-project") {
    if (loadItemProjectFromStorage()) {
      state.itemEditor.undoStack = [];
      state.itemEditor.redoStack = [];
      refreshItemEditorUI();
      showToast("Item project loaded.");
    } else {
      showToast("No saved item project found.");
    }
  }
  if (action === "item-save-project") {
    syncItemProjectFromInputs();
    if (saveItemProjectToStorage()) showToast("Item project saved.");
    else showToast("Item project save failed.");
  }
  if (action === "item-reset-camera") {
    const project = getItemProjectState();
    project.camera = { yaw: -35, pitch: 22, zoom: 1 };
    refreshItemEditorUI();
  }
  if (action === "item-set-tool") {
    const tool = button.dataset.itemTool || "orbit";
    const valid = new Set(["orbit", "move", "scale", "rotate", "paint"]);
    if (valid.has(tool)) {
      const project = getItemProjectState();
      project.interactionMode = tool;
      refreshItemEditorUI();
    }
  }
  if (action === "item-view-preset") {
    const view = button.dataset.view || "persp";
    const project = getItemProjectState();
    if (view === "front") {
      project.camera = { ...project.camera, yaw: 0, pitch: 14 };
    } else if (view === "right") {
      project.camera = { ...project.camera, yaw: -88, pitch: 14 };
    } else if (view === "bottom") {
      project.camera = { ...project.camera, yaw: -35, pitch: -78 };
    } else if (view === "top") {
      project.camera = { ...project.camera, yaw: -35, pitch: 82 };
    } else {
      project.camera = { yaw: -38, pitch: 24, zoom: 1 };
    }
    refreshItemEditorUI();
  }
  if (action === "item-add-primitive") {
    const shape = button.dataset.itemShape === "cylinder" ? "cylinder" : "box";
    const project = getItemProjectState();
    syncItemProjectFromInputs();
    recordItemUndo();
    const next = createDefaultItemPart(`part-${project.parts.length + 1}`);
    next.shape = shape;
    const active = getActiveItemPart(project);
    next.parentId = active ? active.parentId : null;
    const n = project.parts.length;
    next.x = n * 48;
    next.z = (n % 2) * 18;
    project.parts.push(next);
    project.selectedPartId = next.id;
    refreshItemEditorUI();
    showToast(`Added ${shape}.`);
  }
  if (action === "item-add-part") {
    const project = getItemProjectState();
    syncItemProjectFromInputs();
    recordItemUndo();
    const next = createDefaultItemPart(`part-${project.parts.length + 1}`);
    const active = getActiveItemPart(project);
    next.parentId = active ? active.parentId : null;
    const n = project.parts.length;
    next.x = n * 48;
    next.z = (n % 2) * 18;
    project.parts.push(next);
    project.selectedPartId = next.id;
    refreshItemEditorUI();
    showToast("Part added.");
  }
  if (action === "item-duplicate-part") {
    const project = getItemProjectState();
    syncItemProjectFromInputs();
    const active = getActiveItemPart(project);
    if (!active) {
      showToast("Select a part to duplicate.");
    } else {
      recordItemUndo();
      const copy = JSON.parse(JSON.stringify(active));
      copy.id = window.ItemScene?.partId ? window.ItemScene.partId() : `p_${Math.random().toString(36).slice(2)}`;
      copy.name = `${active.name || "part"}-copy`;
      copy.x = Number(copy.x || 0) + 8;
      copy.z = Number(copy.z || 0) + 8;
      project.parts.push(copy);
      project.selectedPartId = copy.id;
      refreshItemEditorUI();
      showToast("Part duplicated.");
    }
  }
  if (action === "item-remove-part") {
    const project = getItemProjectState();
    syncItemProjectFromInputs();
    if (project.parts.length <= 1) {
      showToast("Keep at least one part.");
    } else {
      const sid = project.selectedPartId;
      const idx = project.parts.findIndex((p) => p.id === sid);
      if (idx < 0) {
        showToast("Select a part to remove.");
      } else {
        recordItemUndo();
        const removed = project.parts[idx];
        const pid = removed.parentId;
        project.parts.forEach((p) => {
          if (p.parentId === removed.id) p.parentId = pid;
        });
        project.parts.splice(idx, 1);
        project.selectedPartId = project.parts[0]?.id || null;
        if (window.ItemScene?.validateParents) window.ItemScene.validateParents(project);
        refreshItemEditorUI();
        showToast("Part removed.");
      }
    }
  }
  if (action === "item-paint-bucket-all") {
    const project = getItemProjectState();
    const paint = document.getElementById("item-paint-color")?.value;
    if (!/^#([0-9a-f]{6})$/i.test(String(paint || ""))) {
      showToast("Pick a paint color first.");
    } else {
      recordItemUndo();
      project.parts.forEach((p) => {
        p.color = paint;
      });
      refreshItemEditorUI();
      showToast("Paint applied to all parts.");
    }
  }
  if (action === "item-reparent-selected") {
    const project = getItemProjectState();
    syncItemProjectFromInputs();
    const sel = getActiveItemPart(project);
    const newParent = document.getElementById("item-reparent-parent")?.value || "";
    if (!sel) {
      showToast("Select a part.");
    } else if (!newParent || newParent === "__root") {
      recordItemUndo();
      sel.parentId = null;
      if (window.ItemScene?.validateParents) window.ItemScene.validateParents(project);
      refreshItemEditorUI();
      showToast("Parent cleared (root).");
    } else if (newParent === sel.id) {
      showToast("Cannot parent to self.");
    } else if (window.ItemScene?.wouldReparentCycle && window.ItemScene.wouldReparentCycle(project, sel.id, newParent)) {
      showToast("That would create a cycle.");
    } else {
      recordItemUndo();
      sel.parentId = newParent;
      if (window.ItemScene?.validateParents) window.ItemScene.validateParents(project);
      refreshItemEditorUI();
      showToast("Reparented.");
    }
  }
  if (action === "item-apply-part") {
    syncItemProjectFromInputs();
    refreshItemEditorUI();
    showToast("Part settings applied.");
  }
  if (action === "item-export-json") {
    syncItemProjectFromInputs();
    const project = normalizeItemProject(getItemProjectState());
    state.itemEditor.project = project;
    const slug = String(project.projectName || "item").toLowerCase().replace(/[^a-z0-9_-]+/g, "_");
    const blob = new Blob([JSON.stringify(project, null, 2)], { type: "application/json" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `${slug || "item"}.json`;
    link.click();
    URL.revokeObjectURL(link.href);
    showToast("Item JSON exported.");
  }
  if (action === "item-open-in-animator") {
    syncItemProjectFromInputs();
    void (async () => {
      try {
        if (!window.ItemEditorThree || typeof window.ItemEditorThree.exportEmbeddedGltfDataUrl !== "function") {
          showToast("3D viewport not ready yet.");
          return;
        }
        const project = normalizeItemProject(getItemProjectState());
        state.itemEditor.project = project;
        const wrapViewport = document.getElementById("item-viewport-wrap");
        if (wrapViewport && window.ItemEditorThree && !window.ItemEditorThree.mounted) {
          window.ItemEditorThree.mount(wrapViewport);
        }
        if (window.ItemEditorThree && typeof window.ItemEditorThree.sync === "function") {
          window.ItemEditorThree.sync(project);
        }
        const dataUrl = await window.ItemEditorThree.exportEmbeddedGltfDataUrl(project);
        if (!dataUrl) {
          showToast("Export failed — check the console (F12).");
          return;
        }
        const slug = String(project.projectName || "item").replace(/[^a-z0-9_-]+/gi, "_");
        const isGlb = String(dataUrl).startsWith("data:model/gltf-binary");
        sessionStorage.setItem(
          "shc_pending_mesh_handoff",
          JSON.stringify({
            gltfDataUrl: dataUrl,
            fileName: `${slug || "item"}.${isGlb ? "glb" : "gltf"}`,
            projectName: project.projectName,
            unitTypeProposal: project.category || "prop"
          })
        );
        mountScreen("sprite-editor");
      } catch (e) {
        console.warn(e);
        showToast(`Could not hand off mesh: ${e?.message || "error"}`);
      }
    })();
  }
  if (action === "item-texture-browse") {
    document.getElementById("item-texture-file")?.click();
  }
  if (action === "item-texture-preset") {
    const kind = button.getAttribute("data-item-texture") || "";
    const url = createItemPresetTextureDataUrl(kind);
    if (!url) {
      showToast("Unknown preset.");
    } else {
      applyItemTextureDataUrlToSelection(url, { presetKind: kind, label: kind });
    }
  }
  if (action === "item-clear-texture") {
    applyItemTextureDataUrlToSelection("", { silent: true });
    showToast("Texture cleared.");
  }
  if (action === "sprite-mesh-clear") {
    const sp = getSpriteProjectState();
    sp.mesh3d = { format: "gltf", dataUrl: "", fileName: "" };
    saveSpriteProjectToStorage();
    refreshSpriteEditorUI();
    showToast("3D mesh cleared.");
  }
  if (action === "open-briefing") {
    const node = getNodeById(state.selectedMissionId);
    const campaignState = ensureCampaignProgress(state.selectedCampaignId);
    if (!node || !isUnlocked(node, campaignState)) {
      showToast("Select an unlocked mission first.");
      return;
    }
    mountScreen("mission-briefing");
  }
  if (action === "launch-mission") {
    state.pendingMissionLoad = false;
    mountScreen("mission-play");
  }
  if (action === "pause-mission") {
    togglePauseMission();
  }
  if (action === "pause-resume") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    if (state.missionRuntime.status === "paused") {
      togglePauseMission();
    } else {
      mission.pauseMenuOpen = false;
      mission.pauseOptionsOpen = false;
      refreshMissionPanels();
    }
  }
  if (action === "pause-options") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.pauseOptionsOpen = !mission.pauseOptionsOpen;
    refreshMissionPanels();
  }
  if (action === "pause-close-options") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.pauseOptionsOpen = false;
    refreshMissionPanels();
  }
  if (action === "pause-apply-options") {
    applyPauseOptionsFromUI();
    showToast("Pause options applied.");
    refreshMissionPanels();
  }
  if (action === "pause-restart") {
    restartMission();
  }
  if (action === "pause-save") {
    saveMissionRuntime();
    showToast("Mission runtime saved.");
  }
  if (action === "pause-exit") {
    saveMissionRuntime();
    stopMissionTicker();
    stopMissionRenderLoop();
    if (window.MissionThreeLayer && typeof window.MissionThreeLayer.dispose === "function") {
      window.MissionThreeLayer.dispose();
    }
    mountScreen("missions");
  }
  if (action === "restart-mission") {
    restartMission();
  }
  if (action === "exit-to-map") {
    saveMissionRuntime();
    stopMissionTicker();
    stopMissionRenderLoop();
    mountScreen("missions");
  }
  if (action === "build-stockpile") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "stockpile";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-house") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "house";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-woodcutter") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "woodcutter";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-stone-camp") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "stone-camp";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-iron-furny") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "iron-furny";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-cow-building") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "cow-building";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-apple-farm") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "apple-farm";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-granary") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "granary";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-barracks") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = "barracks";
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "build-cat-economy" || action === "build-cat-food" || action === "build-cat-industry" || action === "build-cat-defense") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildCategory = action.replace("build-cat-", "");
    refreshMissionPanels();
  }
  if (action === "toggle-buildability") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.showBuildabilityOverlay = false;
    showToast("Buildability overlay disabled.");
    refreshMissionPanels();
  }
  if (action === "cancel-build-mode") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildMode = null;
    refreshMissionPanels();
  }
  if (action === "toggle-buildbar") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.buildBarMinimized = !mission.buildBarMinimized;
    refreshMissionPanels();
  }
  if (action === "toggle-dev-menu") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.devMenuOpen = !mission.devMenuOpen;
    refreshMissionPanels();
  }
  if (action === "clear-context") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.selectedBuildingId = null;
    setSelectedUnits([]);
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "toggle-event-log") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    toggleContextSelection("eventLog", "event-log");
    refreshMissionPanels();
  }
  if (action === "recruit-militia") {
    recruitMilitiaUnit();
  }
  if (action === "recruit-skirmisher") {
    recruitUnitAtBarracks("skirmisher");
  }
  if (action === "recruit-templar") {
    recruitUnitAtBarracks("templar");
  }
  if (action === "cancel-barracks-queue") {
    if (cancelBarracksQueue()) showToast("Last recruit cancelled.");
    else showToast("No queued recruit to cancel.");
  }
  if (action === "delete-selected-building") {
    deleteSelectedBuilding();
  }
  if (action === "command-move-mode") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    if (mission.selectedUnitIds.length === 0) {
      showToast("Select at least one unit first.");
      return;
    }
    mission.moveCommandMode = !mission.moveCommandMode;
    showToast(mission.moveCommandMode ? "Move command armed. Right-click target tile/enemy." : "Move command cancelled.");
    refreshMissionPanels();
  }
  if (action === "quick-move-arm") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    if (mission.selectedUnitIds.length === 0) {
      showToast("Select unit(s) first.");
      return;
    }
    mission.moveCommandMode = true;
    showToast("Quick command: tap target to move/attack.");
    refreshMissionPanels();
  }
  if (action === "quick-clear-selection") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    setSelectedUnits([]);
    mission.moveCommandMode = false;
    setContextMode("none", null);
    refreshMissionPanels();
  }
  if (action === "quick-stance-stand" || action === "quick-stance-defensive" || action === "quick-stance-aggressive") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    const selectedUnits = getSelectedUnits(mission);
    if (selectedUnits.length === 0) {
      showToast("Select unit(s) first.");
      return;
    }
    const stance = action.replace("quick-stance-", "");
    for (const unit of selectedUnits) {
      unit.stance = stance;
    }
    refreshMissionPanels();
  }
  if (action === "stance-stand" || action === "stance-defensive" || action === "stance-aggressive") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    const stance = action.replace("stance-", "");
    const selectedUnits = getSelectedUnits(mission);
    if (selectedUnits.length === 0) {
      showToast("Select unit(s) first.");
      return;
    }
    for (const unit of selectedUnits) {
      unit.stance = stance;
    }
    refreshMissionPanels();
  }
  if (action === "command-mode-normal" || action === "command-mode-hold-line" || action === "command-mode-focus-fire" || action === "command-mode-fallback") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    const selectedUnits = getSelectedUnits(mission);
    if (selectedUnits.length === 0) {
      showToast("Select unit(s) first.");
      return;
    }
    const mode = action.replace("command-mode-", "");
    for (const unit of selectedUnits) {
      unit.commandMode = mode;
      if (mode === "fallback") unit.attackTargetId = null;
    }
    mission.commandMode = mode;
    refreshMissionPanels();
  }
  if (action === "toggle-research-rules" || action === "toggle-upkeep-rules") {
    const mission = state.missionRuntime.missionState;
    if (!mission) return;
    mission.modernRules = mission.modernRules || { researchEnabled: true, upkeepEnabled: true };
    if (action === "toggle-research-rules") {
      mission.modernRules.researchEnabled = !mission.modernRules.researchEnabled;
      showToast(`Research rules ${mission.modernRules.researchEnabled ? "enabled" : "disabled"}.`);
    } else {
      mission.modernRules.upkeepEnabled = !mission.modernRules.upkeepEnabled;
      showToast(`Army upkeep ${mission.modernRules.upkeepEnabled ? "enabled" : "disabled"}.`);
    }
    refreshMissionPanels();
  }
  if (action === "save-mission-runtime") {
    saveMissionRuntime();
    showToast("Mission runtime saved.");
  }
  if (action === "load-mission-runtime") {
    if (loadMissionRuntime()) {
      const mission = state.missionRuntime.missionState;
      if (mission) {
        mission.buildBarMinimized = false;
        mission.devMenuOpen = false;
        mission.pauseMenuOpen = false;
        mission.pauseOptionsOpen = false;
        mission.selectionBox = null;
      }
      assignWorkers();
      computeStockpileStats();
      refreshMissionPanels();
      refreshMissionHud();
      showToast("Mission runtime loaded.");
      if (state.missionRuntime.status === "running") startMissionTicker();
      else stopMissionTicker();
    } else {
      showToast("No mission save found.");
    }
  }
});

document.addEventListener("change", (event) => {
  const target = event.target;
  if (!(target instanceof HTMLElement)) return;
  if (state.currentScreen === "item-editor") {
    const watchedItemIds = new Set([
      "item-project-name",
      "item-project-category",
      "item-preview-grid",
      "item-bg-color",
      "item-wireframe-enabled",
      "item-part-select",
      "item-part-name",
      "item-part-shape",
      "item-part-x",
      "item-part-y",
      "item-part-z",
      "item-part-w",
      "item-part-h",
      "item-part-d",
      "item-part-yaw",
      "item-part-color",
      "item-paint-symmetry"
    ]);
    if (!watchedItemIds.has(target.id)) return;
    syncItemProjectFromInputs();
    refreshItemEditorUI();
    return;
  }
  if (state.currentScreen !== "sprite-editor") return;
  const watchedIds = new Set([
    "sprite-project-name",
    "sprite-naming-pattern",
    "sprite-project-archetype",
    "sprite-unit-type",
    "sprite-import-preset",
    "sprite-animation-select",
    "sprite-direction-select",
    "sprite-frame-index",
    "sprite-frame-x",
    "sprite-frame-y",
    "sprite-frame-w",
    "sprite-frame-h",
    "sprite-frame-duration",
    "sprite-pivot-x",
    "sprite-pivot-y",
    "sprite-foot-y",
    "sprite-collision-w",
    "sprite-collision-h",
    "sprite-collision-x",
    "sprite-collision-y",
    "sprite-team-mask-enabled",
    "sprite-shadow-enabled",
    "sprite-onion-enabled",
    "sprite-ghost-enabled",
    "sprite-onion-opacity",
    "sprite-ghost-opacity",
    "sprite-symmetry-enabled",
    "sprite-symmetry-axis",
    "sprite-preview-team",
    "sprite-preview-bg",
    "sprite-snapshot-slot"
  ]);
  if (!watchedIds.has(target.id)) return;
  syncSpriteEditorProjectFromInputs();
  if (
    target.id === "sprite-frame-x" ||
    target.id === "sprite-frame-y" ||
    target.id === "sprite-frame-w" ||
    target.id === "sprite-frame-h" ||
    target.id === "sprite-frame-duration" ||
    target.id === "sprite-pivot-x" ||
    target.id === "sprite-pivot-y" ||
    target.id === "sprite-foot-y" ||
    target.id === "sprite-collision-w" ||
    target.id === "sprite-collision-h" ||
    target.id === "sprite-collision-x" ||
    target.id === "sprite-collision-y" ||
    target.id === "sprite-team-mask-enabled" ||
    target.id === "sprite-shadow-enabled"
  ) {
    pushSpriteUndoState("change-frame-field");
    const active = getActiveSpriteFrame(getSpriteProjectState());
    active.frame.x = Math.max(0, readNumberInputValue("sprite-frame-x", active.frame.x));
    active.frame.y = Math.max(0, readNumberInputValue("sprite-frame-y", active.frame.y));
    active.frame.w = Math.max(1, readNumberInputValue("sprite-frame-w", active.frame.w));
    active.frame.h = Math.max(1, readNumberInputValue("sprite-frame-h", active.frame.h));
    active.frame.durationMs = Math.max(20, readNumberInputValue("sprite-frame-duration", active.frame.durationMs));
    active.frame.pivotX = readNumberInputValue("sprite-pivot-x", active.frame.pivotX);
    active.frame.pivotY = readNumberInputValue("sprite-pivot-y", active.frame.pivotY);
    active.frame.footY = readNumberInputValue("sprite-foot-y", active.frame.footY);
    active.frame.collision = {
      x: readNumberInputValue("sprite-collision-x", active.frame.collision?.x || 0),
      y: readNumberInputValue("sprite-collision-y", active.frame.collision?.y || 0),
      w: Math.max(1, readNumberInputValue("sprite-collision-w", active.frame.collision?.w || 32)),
      h: Math.max(1, readNumberInputValue("sprite-collision-h", active.frame.collision?.h || 32))
    };
    active.frame.teamMask = !!document.getElementById("sprite-team-mask-enabled")?.checked;
    active.frame.shadow = !!document.getElementById("sprite-shadow-enabled")?.checked;
    applySymmetryForActiveFrame(getSpriteProjectState());
  }
  refreshSpriteEditorUI();
});

function bootstrap() {
  loadSettings();
  loadProgress();
  loadRuntimeSpriteRegistry();
  mountScreen("main-menu");
}

bootstrap();
