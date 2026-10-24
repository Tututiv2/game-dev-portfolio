window.SPRITE_DIRECTIONS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
window.SPRITE_REQUIRED_ANIMATIONS = ["idle", "walk", "attack", "hit", "death"];

window.createDefaultSpriteFrame = function createDefaultSpriteFrame() {
  return {
    x: 0,
    y: 0,
    w: 64,
    h: 64,
    durationMs: 120,
    pivotX: 0,
    pivotY: 0,
    footY: 0,
    collision: { x: 0, y: 0, w: 32, h: 32 },
    teamMask: false,
    shadow: true
  };
};

window.createDefaultSpriteAnimation = function createDefaultSpriteAnimation(name = "idle") {
  const byDirection = {};
  for (const dir of window.SPRITE_DIRECTIONS) {
    byDirection[dir] = [window.createDefaultSpriteFrame()];
  }
  return {
    name,
    byDirection
  };
};

window.createDefaultSpriteProject = function createDefaultSpriteProject() {
  const animations = {};
  for (const animName of window.SPRITE_REQUIRED_ANIMATIONS) {
    animations[animName] = window.createDefaultSpriteAnimation(animName);
  }
  return {
    schemaVersion: 1,
    projectName: "NewSpriteProject",
    namingPattern: "unit_<anim>_<dir>_<frame>",
    archetype: "unit",
    unitType: "militia",
    importPreset: "single_row",
    sourceImageDataUrl: "",
    sourceImageName: "",
    sourceImageWidth: 0,
    sourceImageHeight: 0,
    activeAnimation: "idle",
    activeDirection: "S",
    activeFrameIndex: 0,
    animations,
    validation: {
      lastRunAt: 0,
      errors: [],
      warnings: []
    },
    exported: {
      atlasPath: "",
      manifestPath: "",
      exportedAt: 0
    },
    snapshots: {
      slot1: null,
      slot2: null,
      slot3: null,
      slot4: null
    },
    preview: {
      onionSkin: false,
      ghostFrame: false,
      onionOpacity: 0.35,
      ghostOpacity: 0.25,
      symmetryEnabled: false,
      symmetryAxisNorm: 0.5
    },
    mesh3d: {
      format: "gltf",
      dataUrl: "",
      fileName: "",
      sourceProject: ""
    }
  };
};

window.normalizeSpriteProject = function normalizeSpriteProject(rawProject) {
  const fallback = window.createDefaultSpriteProject();
  const merged = {
    ...fallback,
    ...(rawProject || {})
  };
  merged.projectName = String(merged.projectName || fallback.projectName);
  merged.namingPattern = String(merged.namingPattern || fallback.namingPattern);
  merged.archetype = ["unit", "building", "effect"].includes(merged.archetype) ? merged.archetype : "unit";
  merged.unitType = String(merged.unitType || "militia");
  merged.importPreset = String(merged.importPreset || "single_row");
  merged.sourceImageDataUrl = String(merged.sourceImageDataUrl || "");
  merged.sourceImageName = String(merged.sourceImageName || "");
  merged.sourceImageWidth = Number(merged.sourceImageWidth || 0);
  merged.sourceImageHeight = Number(merged.sourceImageHeight || 0);
  merged.activeAnimation = String(merged.activeAnimation || "idle");
  merged.activeDirection = window.SPRITE_DIRECTIONS.includes(merged.activeDirection) ? merged.activeDirection : "S";
  merged.activeFrameIndex = Math.max(0, Number(merged.activeFrameIndex || 0));
  merged.animations = { ...fallback.animations, ...(merged.animations || {}) };
  for (const [name, anim] of Object.entries(merged.animations)) {
    const safeAnim = {
      name: String(anim?.name || name),
      byDirection: {}
    };
    for (const dir of window.SPRITE_DIRECTIONS) {
      const rawFrames = Array.isArray(anim?.byDirection?.[dir]) ? anim.byDirection[dir] : [window.createDefaultSpriteFrame()];
      safeAnim.byDirection[dir] = rawFrames.map((frame) => ({
        ...window.createDefaultSpriteFrame(),
        ...(frame || {}),
        x: Math.max(0, Number(frame?.x || 0)),
        y: Math.max(0, Number(frame?.y || 0)),
        w: Math.max(1, Number(frame?.w || 64)),
        h: Math.max(1, Number(frame?.h || 64)),
        durationMs: Math.max(20, Number(frame?.durationMs || 120)),
        pivotX: Number(frame?.pivotX || 0),
        pivotY: Number(frame?.pivotY || 0),
        footY: Number(frame?.footY || 0),
        collision: {
          x: Number(frame?.collision?.x || 0),
          y: Number(frame?.collision?.y || 0),
          w: Math.max(1, Number(frame?.collision?.w || 32)),
          h: Math.max(1, Number(frame?.collision?.h || 32))
        },
        teamMask: !!frame?.teamMask,
        shadow: frame?.shadow !== false
      }));
    }
    merged.animations[name] = safeAnim;
  }
  merged.validation = {
    lastRunAt: Number(merged.validation?.lastRunAt || 0),
    errors: Array.isArray(merged.validation?.errors) ? merged.validation.errors : [],
    warnings: Array.isArray(merged.validation?.warnings) ? merged.validation.warnings : []
  };
  merged.exported = {
    atlasPath: String(merged.exported?.atlasPath || ""),
    manifestPath: String(merged.exported?.manifestPath || ""),
    exportedAt: Number(merged.exported?.exportedAt || 0)
  };
  merged.snapshots = {
    slot1: merged.snapshots?.slot1 || null,
    slot2: merged.snapshots?.slot2 || null,
    slot3: merged.snapshots?.slot3 || null,
    slot4: merged.snapshots?.slot4 || null
  };
  merged.preview = {
    onionSkin: !!merged.preview?.onionSkin,
    ghostFrame: !!merged.preview?.ghostFrame,
    onionOpacity: Math.max(0, Math.min(1, Number(merged.preview?.onionOpacity ?? 0.35))),
    ghostOpacity: Math.max(0, Math.min(1, Number(merged.preview?.ghostOpacity ?? 0.25))),
    symmetryEnabled: !!merged.preview?.symmetryEnabled,
    symmetryAxisNorm: Math.max(0, Math.min(1, Number(merged.preview?.symmetryAxisNorm ?? 0.5)))
  };
  merged.mesh3d = {
    format: String(merged.mesh3d?.format || "gltf"),
    dataUrl: String(merged.mesh3d?.dataUrl || ""),
    fileName: String(merged.mesh3d?.fileName || ""),
    sourceProject: String(merged.mesh3d?.sourceProject || "")
  };
  return merged;
};
