const THEME_KEY = "shadow-designer.theme";
const STAGE_FOCUS_MODE_KEY = "shadow-designer.stage-focus-mode";
const CLICK_SFX_KEY = "shadow-designer.click-sfx";

const selectedFileLabel = document.getElementById("selected-file");
const themeSelect = document.getElementById("theme-select");
const pickFileButton = document.getElementById("pick-file");
const pickFileButtonEmpty = document.getElementById("pick-file-empty");
const clearFileButton = document.getElementById("clear-file");
const reloadFileButton = document.getElementById("reload-file");
const assetList = document.getElementById("asset-list");
const frameList = document.getElementById("frame-list");
const assetCount = document.getElementById("asset-count");
const frameCount = document.getElementById("frame-count");
const assetSearch = document.getElementById("asset-search");
const importMockAssetButton = document.getElementById("import-mock-asset");
const importImageAssetButton = document.getElementById("import-image-asset");
const importImageInput = document.getElementById("import-image-input");
const playToggle = document.getElementById("play-toggle");
const stepPrev = document.getElementById("step-prev");
const stepNext = document.getElementById("step-next");
const toggleGrid = document.getElementById("toggle-grid");
const playbackState = document.getElementById("playback-state");
const previewMeta = document.getElementById("preview-meta");
const previewCanvas = document.getElementById("preview-canvas");
const fpsSlider = document.getElementById("fps-slider");
const fpsValue = document.getElementById("fps-value");
const frameIndexSlider = document.getElementById("frame-index");
const frameIndexValue = document.getElementById("frame-index-value");
const zoomSlider = document.getElementById("zoom-slider");
const zoomValue = document.getElementById("zoom-value");
const frameWidthInput = document.getElementById("sheet-frame-width");
const frameHeightInput = document.getElementById("sheet-frame-height");
const extractFramesButton = document.getElementById("extract-frames");
const clearExtractionButton = document.getElementById("clear-extraction");
const panX = document.getElementById("pan-x");
const panY = document.getElementById("pan-y");
const chromaColor = document.getElementById("chroma-color");
const chromaTolerance = document.getElementById("chroma-tolerance");
const toggleMaskPreview = document.getElementById("toggle-mask-preview");
const runAutoMask = document.getElementById("run-auto-mask");
const exportSheet = document.getElementById("export-sheet");
const exportZip = document.getElementById("export-zip");
const copyCode = document.getElementById("copy-code");
const exportStatus = document.getElementById("export-status");
const runtimeSnippet = document.getElementById("runtime-snippet");
const sheetMetadata = document.getElementById("sheet-metadata");
const canvasRoot = document.getElementById("canvas");
const stageButtons = Array.from(document.querySelectorAll("[data-stage-button]"));
const stageSummary = document.getElementById("stage-summary");
const stageFocusToggle = document.getElementById("stage-focus-toggle");
const sfxToggle = document.getElementById("sfx-toggle");
const panelAssets = document.getElementById("panel-assets");
const panelPreview = document.getElementById("panel-preview");
const panelControls = document.getElementById("panel-controls");
const panelCode = document.getElementById("panel-code");

const maskCanvas = document.createElement("canvas");
const maskCtx = maskCanvas.getContext("2d");
let sfxAudioContext = null;

const STAGE_CONFIG = {
  source: {
    label: "Stage 1: Set animator source and project context.",
    panels: ["assets"],
  },
  assets: {
    label: "Stage 2: Ingest and organize sprites, clips, and frame lists.",
    panels: ["assets"],
  },
  animation: {
    label: "Stage 3: Preview animation playback, FPS timing, and frame stepping.",
    panels: ["preview", "controls"],
  },
  masking: {
    label: "Stage 4: Tune chroma/mask controls and validate transparency.",
    panels: ["preview", "controls"],
  },
  export: {
    label: "Stage 5: Export sheets/ZIPs and inspect export metadata.",
    panels: ["controls", "code"],
  },
  integration: {
    label: "Stage 6: Copy runtime integration code and finalize handoff.",
    panels: ["code", "preview"],
  },
};

const STAGE_ACCENTS = {
  source: "#38bdf8",
  assets: "#22c55e",
  animation: "#a78bfa",
  masking: "#f59e0b",
  export: "#fb7185",
  integration: "#2dd4bf",
};

const appState = {
  selectedHtmlPath: null,
  selectedHtmlName: null,
  assets: [
    { id: "knight_idle", type: "mock", name: "Knight Idle", source: "mock://clip/knight-idle.mp4", frameCount: 12, baseHue: 220 },
    { id: "slime_bounce", type: "mock", name: "Slime Bounce", source: "mock://clip/slime-bounce.mp4", frameCount: 8, baseHue: 130 },
  ],
  selectedAssetId: "knight_idle",
  selectedFrameIndex: 0,
  disabledFrames: new Set(),
  playing: false,
  showGrid: true,
  fps: 12,
  zoom: 1,
  panX: 0,
  panY: 0,
  maskPreview: false,
  autoMaskRan: false,
  chroma: {
    color: "#00ff00",
    tolerance: 35,
  },
  playbackTimer: null,
  lastExportKind: null,
  activeStage: "source",
  stageFocusMode: false,
  clickSfxEnabled: true,
};

function getSelectedAsset() {
  return appState.assets.find((asset) => asset.id === appState.selectedAssetId) ?? appState.assets[0] ?? null;
}

function getFrameRects(asset) {
  if (!asset) {
    return [];
  }
  if (asset.type === "image" && asset.image) {
    if (Array.isArray(asset.extractedFrames) && asset.extractedFrames.length > 0) {
      return asset.extractedFrames;
    }
    return [{ x: 0, y: 0, w: asset.image.width, h: asset.image.height }];
  }
  const count = Math.max(1, asset.frameCount ?? 1);
  return Array.from({ length: count }, (_item, index) => ({ index, w: 96, h: 96 }));
}

function clampFrameIndex(asset) {
  const max = Math.max(0, getFrameRects(asset).length - 1);
  appState.selectedFrameIndex = Math.min(max, Math.max(0, appState.selectedFrameIndex));
}

function hexToRgb(hex) {
  const normalized = hex.replace("#", "");
  if (normalized.length !== 6) {
    return { r: 0, g: 255, b: 0 };
  }
  return {
    r: Number.parseInt(normalized.slice(0, 2), 16),
    g: Number.parseInt(normalized.slice(2, 4), 16),
    b: Number.parseInt(normalized.slice(4, 6), 16),
  };
}

function sanitizeFileStem(value, fallback = "sprite") {
  const cleaned = String(value || "")
    .trim()
    .replace(/[<>:"/\\|?*\u0000-\u001F]/g, "_")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-+|-+$/g, "");
  return cleaned || fallback;
}

function applyChromaToImageData(imageData) {
  const data = imageData.data;
  const key = hexToRgb(appState.chroma.color);
  const threshold = (appState.chroma.tolerance / 100) * 441;
  for (let index = 0; index < data.length; index += 4) {
    const dr = data[index] - key.r;
    const dg = data[index + 1] - key.g;
    const db = data[index + 2] - key.b;
    const distance = Math.sqrt(dr * dr + dg * dg + db * db);
    if (distance <= threshold) {
      data[index + 3] = 0;
    }
  }
}

function applyTheme(themeId) {
  const nextTheme = themeId || "void-blue";
  document.documentElement.dataset.theme = nextTheme;
  localStorage.setItem(THEME_KEY, nextTheme);
  if (themeSelect.value !== nextTheme) {
    themeSelect.value = nextTheme;
  }
  applyStageAccent();
}

function applyStageAccent() {
  const stageAccent = STAGE_ACCENTS[appState.activeStage] || "var(--accent)";
  const rootStyle = document.documentElement.style;
  rootStyle.setProperty("--stage-accent", stageAccent);
  rootStyle.setProperty("--stage-accent-soft", `color-mix(in srgb, ${stageAccent} 24%, transparent)`);
  rootStyle.setProperty("--stage-chip-bg", `color-mix(in srgb, ${stageAccent} 18%, transparent)`);
}

function ensureAudioContext() {
  if (sfxAudioContext) {
    return sfxAudioContext;
  }
  const Ctor = window.AudioContext || window.webkitAudioContext;
  if (!Ctor) {
    return null;
  }
  sfxAudioContext = new Ctor();
  return sfxAudioContext;
}

function playSoftClickSfx(intensity = 0.5) {
  if (!appState.clickSfxEnabled) {
    return;
  }
  const context = ensureAudioContext();
  if (!context) {
    return;
  }
  if (context.state === "suspended") {
    void context.resume();
  }
  const now = context.currentTime;
  const gain = context.createGain();
  const osc = context.createOscillator();
  const tone = 180 + intensity * 110;
  osc.type = "triangle";
  osc.frequency.setValueAtTime(tone, now);
  osc.frequency.exponentialRampToValueAtTime(tone * 0.7, now + 0.07);
  gain.gain.setValueAtTime(0.0001, now);
  gain.gain.exponentialRampToValueAtTime(0.035 + intensity * 0.018, now + 0.006);
  gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.09);
  osc.connect(gain).connect(context.destination);
  osc.start(now);
  osc.stop(now + 0.1);
}

function updateSfxToggleLabel() {
  if (!sfxToggle) {
    return;
  }
  sfxToggle.classList.toggle("active", appState.clickSfxEnabled);
  sfxToggle.textContent = appState.clickSfxEnabled ? "Click SFX: On" : "Click SFX: Off";
}

function applyStageFocus(stageId) {
  const nextStage = STAGE_CONFIG[stageId] ? stageId : "source";
  appState.activeStage = nextStage;
  applyStageAccent();
  const config = STAGE_CONFIG[nextStage];
  if (stageSummary) {
    stageSummary.textContent = config.label;
  }

  for (const button of stageButtons) {
    const isActive = button.dataset.stageButton === nextStage;
    button.classList.toggle("active", isActive);
  }
  if (stageFocusToggle) {
    stageFocusToggle.classList.toggle("active", appState.stageFocusMode);
    stageFocusToggle.textContent = appState.stageFocusMode ? "Focus Mode: On" : "Focus Mode: Off";
  }

  const panelMap = {
    assets: panelAssets,
    preview: panelPreview,
    controls: panelControls,
    code: panelCode,
  };
  for (const [key, panel] of Object.entries(panelMap)) {
    if (!panel) {
      continue;
    }
    const included = config.panels.includes(key);
    panel.classList.toggle("stage-dim", !included);
    panel.classList.toggle("stage-hidden", appState.stageFocusMode && !included);
  }
}

function updateTopSourceLabel() {
  if (appState.selectedHtmlPath) {
    selectedFileLabel.textContent = appState.selectedHtmlName || appState.selectedHtmlPath;
    clearFileButton.disabled = false;
    reloadFileButton.disabled = false;
  } else {
    selectedFileLabel.textContent = "No sprite animator HTML selected.";
    clearFileButton.disabled = true;
    reloadFileButton.disabled = true;
  }
}

function renderAssets() {
  const query = assetSearch.value.trim().toLowerCase();
  const filtered = appState.assets.filter((asset) => asset.name.toLowerCase().includes(query));
  assetCount.textContent = `${filtered.length} asset${filtered.length === 1 ? "" : "s"}`;
  assetList.innerHTML = "";
  for (const asset of filtered) {
    const frames = getFrameRects(asset).length;
    const button = document.createElement("button");
    button.className = `asset-item ${asset.id === appState.selectedAssetId ? "active" : ""}`;
    button.type = "button";
    button.innerHTML = `<strong>${asset.name}</strong><br /><small>${asset.type === "image" ? "image" : "mock"} · ${frames} frame${frames === 1 ? "" : "s"}</small>`;
    button.addEventListener("click", () => {
      appState.selectedAssetId = asset.id;
      appState.selectedFrameIndex = 0;
      renderAll();
    });
    assetList.appendChild(button);
  }
}

function renderFrames() {
  const asset = getSelectedAsset();
  const frames = getFrameRects(asset).map((_rect, index) => ({
    index,
    disabled: asset ? appState.disabledFrames.has(`${asset.id}:${index}`) : false,
  }));
  frameCount.textContent = `${frames.length} frames`;
  frameList.innerHTML = "";
  for (const frame of frames) {
    const button = document.createElement("button");
    button.className = `frame-item ${frame.index === appState.selectedFrameIndex ? "active" : ""}`;
    button.type = "button";
    button.innerHTML = `<strong>Frame ${frame.index}</strong><br /><small>${frame.disabled ? "Disabled in loop" : "Enabled"}</small>`;
    button.addEventListener("click", () => {
      appState.selectedFrameIndex = frame.index;
      renderAll();
    });
    button.addEventListener("contextmenu", (event) => {
      event.preventDefault();
      if (!asset) {
        return;
      }
      const key = `${asset.id}:${frame.index}`;
      if (appState.disabledFrames.has(key)) {
        appState.disabledFrames.delete(key);
      } else {
        appState.disabledFrames.add(key);
      }
      renderAll();
    });
    frameList.appendChild(button);
  }
}

function drawCheckerboard(ctx, width, height, cell = 24) {
  for (let y = 0; y < height; y += cell) {
    for (let x = 0; x < width; x += cell) {
      const isDark = ((x / cell + y / cell) | 0) % 2 === 0;
      ctx.fillStyle = isDark ? "#0f172a" : "#111827";
      ctx.fillRect(x, y, cell, cell);
    }
  }
}

function drawMockAsset(ctx, asset, frameIndex, width, height) {
  const centerX = width * 0.5 + appState.panX;
  const centerY = height * 0.5 + appState.panY;
  const radius = 30 * appState.zoom + (frameIndex % 5) * 5;
  const hue = (asset.baseHue + frameIndex * 12) % 360;
  ctx.save();
  ctx.translate(centerX, centerY);
  ctx.scale(appState.zoom, appState.zoom);
  ctx.fillStyle = `hsla(${hue} 84% 60% / 0.95)`;
  ctx.beginPath();
  ctx.arc(0, 0, radius, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = "rgba(10, 15, 25, 0.35)";
  ctx.fillRect(-radius, radius * 0.75, radius * 2, radius * 0.35);
  ctx.restore();
}

function drawImageAsset(ctx, asset, rect, width, height) {
  if (!asset.image) {
    return;
  }
  const sourceW = rect.w;
  const sourceH = rect.h;
  const destW = sourceW * appState.zoom;
  const destH = sourceH * appState.zoom;
  const destX = width * 0.5 - destW * 0.5 + appState.panX;
  const destY = height * 0.5 - destH * 0.5 + appState.panY;

  if (!appState.maskPreview || !maskCtx) {
    ctx.drawImage(asset.image, rect.x, rect.y, sourceW, sourceH, destX, destY, destW, destH);
    return;
  }

  maskCanvas.width = sourceW;
  maskCanvas.height = sourceH;
  maskCtx.clearRect(0, 0, sourceW, sourceH);
  maskCtx.drawImage(asset.image, rect.x, rect.y, sourceW, sourceH, 0, 0, sourceW, sourceH);

  const imageData = maskCtx.getImageData(0, 0, sourceW, sourceH);
  applyChromaToImageData(imageData);
  maskCtx.putImageData(imageData, 0, 0);
  ctx.drawImage(maskCanvas, 0, 0, sourceW, sourceH, destX, destY, destW, destH);
}

function renderFrameCanvas(asset, frameRect, frameIndex) {
  const width = frameRect?.w ?? 96;
  const height = frameRect?.h ?? 96;
  const frameCanvas = document.createElement("canvas");
  frameCanvas.width = width;
  frameCanvas.height = height;
  const ctx = frameCanvas.getContext("2d");
  if (!ctx) {
    return frameCanvas;
  }

  if (asset.type === "image" && asset.image && frameRect) {
    ctx.drawImage(asset.image, frameRect.x, frameRect.y, width, height, 0, 0, width, height);
    if (appState.maskPreview && maskCtx) {
      const imageData = ctx.getImageData(0, 0, width, height);
      applyChromaToImageData(imageData);
      ctx.putImageData(imageData, 0, 0);
    }
    return frameCanvas;
  }

  const hue = (asset.baseHue + frameIndex * 12) % 360;
  const radius = Math.min(width, height) * 0.28 + (frameIndex % 5) * 2;
  ctx.fillStyle = "rgba(9, 15, 28, 1)";
  ctx.fillRect(0, 0, width, height);
  ctx.fillStyle = `hsla(${hue} 84% 60% / 0.95)`;
  ctx.beginPath();
  ctx.arc(width * 0.5, height * 0.45, radius, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = "rgba(10, 15, 25, 0.35)";
  ctx.fillRect(width * 0.2, height * 0.72, width * 0.6, height * 0.16);
  return frameCanvas;
}

function drawPreview() {
  const ctx = previewCanvas.getContext("2d");
  if (!ctx) {
    return;
  }
  const rect = previewCanvas.getBoundingClientRect();
  const width = Math.max(300, Math.floor(rect.width));
  const height = Math.max(220, Math.floor(rect.height));
  if (previewCanvas.width !== width || previewCanvas.height !== height) {
    previewCanvas.width = width;
    previewCanvas.height = height;
  }
  ctx.clearRect(0, 0, width, height);
  if (appState.showGrid) {
    drawCheckerboard(ctx, width, height, 30);
  } else {
    ctx.fillStyle = "#060b1b";
    ctx.fillRect(0, 0, width, height);
  }

  const asset = getSelectedAsset();
  if (!asset) {
    return;
  }

  const frameRects = getFrameRects(asset);
  const frameRect = frameRects[appState.selectedFrameIndex] ?? frameRects[0];
  if (!frameRect) {
    return;
  }
  if (asset.type === "image") {
    drawImageAsset(ctx, asset, frameRect, width, height);
  } else {
    drawMockAsset(ctx, asset, appState.selectedFrameIndex, width, height);
  }
}

function updatePlaybackUI() {
  const asset = getSelectedAsset();
  const frames = getFrameRects(asset);
  const maxFrame = Math.max(0, frames.length - 1);
  frameIndexSlider.max = String(maxFrame);
  frameIndexSlider.value = String(appState.selectedFrameIndex);
  frameIndexValue.textContent = `Frame ${appState.selectedFrameIndex}`;
  fpsValue.textContent = `${appState.fps} FPS`;
  zoomValue.textContent = `${appState.zoom.toFixed(1)}x`;
  playToggle.textContent = appState.playing ? "Pause" : "Play";
  playbackState.textContent = appState.playing ? "Playing" : "Paused";
  toggleGrid.textContent = appState.showGrid ? "Grid On" : "Grid Off";
  toggleGrid.classList.toggle("active", appState.showGrid);
  toggleMaskPreview.textContent = appState.maskPreview ? "Mask Preview On" : "Mask Preview Off";
  toggleMaskPreview.classList.toggle("active", appState.maskPreview);
  const frameRect = frames[appState.selectedFrameIndex] ?? frames[0];
  previewMeta.textContent = asset
    ? `${asset.name} · frame ${appState.selectedFrameIndex} · ${frameRect ? `${frameRect.w}x${frameRect.h}` : "n/a"} · zoom ${appState.zoom.toFixed(1)}x`
    : "No asset selected.";
}

function updateRuntimeSnippet() {
  const asset = getSelectedAsset();
  if (!asset) {
    runtimeSnippet.value = "// No asset loaded yet.";
    return;
  }
  const frames = getFrameRects(asset);
  const frameRect = frames[appState.selectedFrameIndex] ?? frames[0];
  const frameWidth = frameRect?.w ?? 96;
  const frameHeight = frameRect?.h ?? 96;
  const frameX = frameRect?.x ?? appState.selectedFrameIndex * frameWidth;
  const frameY = frameRect?.y ?? 0;

  runtimeSnippet.value = [
    `const frameWidth = ${frameWidth};`,
    `const frameHeight = ${frameHeight};`,
    `const frameIndex = ${appState.selectedFrameIndex};`,
    `const sourceX = ${frameX};`,
    `const sourceY = ${frameY};`,
    `const drawX = x + ${appState.panX};`,
    `const drawY = y + ${appState.panY};`,
    `ctx.drawImage(${asset.id}Sheet, sourceX, sourceY, frameWidth, frameHeight, drawX, drawY, frameWidth * ${appState.zoom.toFixed(1)}, frameHeight * ${appState.zoom.toFixed(1)});`,
  ].join("\n");
}

function updateMetadataPanel() {
  const asset = getSelectedAsset();
  if (!asset) {
    sheetMetadata.textContent = "No export metadata generated yet.";
    return;
  }
  const frames = getFrameRects(asset);
  const enabledFrames = frames.filter((_rect, index) => !appState.disabledFrames.has(`${asset.id}:${index}`)).length;
  sheetMetadata.textContent = JSON.stringify(
    {
      assetId: asset.id,
      assetType: asset.type,
      source: asset.source,
      selectedFrame: appState.selectedFrameIndex,
      frameRects: frames.length,
      extraction: asset.extraction ?? null,
      fps: appState.fps,
      zoom: appState.zoom,
      pan: { x: appState.panX, y: appState.panY },
      chroma: appState.chroma,
      maskPreview: appState.maskPreview,
      autoMaskRan: appState.autoMaskRan,
      enabledFrames,
      totalFrames: frames.length,
      lastExportKind: appState.lastExportKind,
    },
    null,
    2,
  );
}

function renderAll() {
  const asset = getSelectedAsset();
  clampFrameIndex(asset);
  renderAssets();
  renderFrames();
  updateTopSourceLabel();
  updatePlaybackUI();
  updateRuntimeSnippet();
  updateMetadataPanel();
  drawPreview();
  applyStageFocus(appState.activeStage);
}

function stopPlayback() {
  if (appState.playbackTimer) {
    clearInterval(appState.playbackTimer);
    appState.playbackTimer = null;
  }
  appState.playing = false;
}

function stepFrame(direction) {
  const asset = getSelectedAsset();
  const frames = getFrameRects(asset);
  if (!frames.length) {
    return;
  }
  const max = frames.length - 1;
  appState.selectedFrameIndex += direction;
  if (appState.selectedFrameIndex > max) {
    appState.selectedFrameIndex = 0;
  }
  if (appState.selectedFrameIndex < 0) {
    appState.selectedFrameIndex = max;
  }
  renderAll();
}

function startPlayback() {
  stopPlayback();
  appState.playing = true;
  const intervalMs = Math.max(33, Math.floor(1000 / appState.fps));
  appState.playbackTimer = setInterval(() => {
    const asset = getSelectedAsset();
    const frames = getFrameRects(asset);
    if (!frames.length) {
      stopPlayback();
      renderAll();
      return;
    }
    const total = frames.length;
    let next = appState.selectedFrameIndex;
    for (let i = 0; i < total; i += 1) {
      next = (next + 1) % total;
      if (!appState.disabledFrames.has(`${asset.id}:${next}`)) {
        break;
      }
    }
    appState.selectedFrameIndex = next;
    renderAll();
  }, intervalMs);
}

async function loadImageFromFile(file) {
  const objectUrl = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = objectUrl;
    await image.decode();
    return image;
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

async function importImageAssetsFromFiles(fileList) {
  applyStageFocus("assets");
  const files = Array.from(fileList ?? []);
  const imageFiles = files.filter((file) => file.type.startsWith("image/"));
  if (imageFiles.length === 0) {
    exportStatus.textContent = "Choose image files to import.";
    return;
  }
  for (const file of imageFiles) {
    const image = await loadImageFromFile(file);
    const now = Date.now().toString(36).slice(-6);
    const asset = {
      id: `img_${now}_${Math.floor(Math.random() * 999)}`,
      type: "image",
      name: file.name.replace(/\.[^.]+$/, "") || file.name,
      source: `file://${file.path || file.name}`,
      image,
      extractedFrames: [],
      extraction: null,
    };
    appState.assets.unshift(asset);
    appState.selectedAssetId = asset.id;
  }
  appState.selectedFrameIndex = 0;
  exportStatus.textContent = `Imported ${imageFiles.length} image asset(s).`;
  renderAll();
}

function extractFramesFromSelectedAsset() {
  const asset = getSelectedAsset();
  if (!asset || asset.type !== "image" || !asset.image) {
    exportStatus.textContent = "Select an imported image asset first.";
    return;
  }
  const frameW = Number(frameWidthInput.value);
  const frameH = Number(frameHeightInput.value);
  if (!Number.isFinite(frameW) || !Number.isFinite(frameH) || frameW <= 0 || frameH <= 0) {
    exportStatus.textContent = "Frame width and height must be positive numbers.";
    return;
  }
  const cols = Math.floor(asset.image.width / frameW);
  const rows = Math.floor(asset.image.height / frameH);
  if (cols <= 0 || rows <= 0) {
    exportStatus.textContent = "Frame size is larger than image dimensions.";
    return;
  }
  const frames = [];
  for (let row = 0; row < rows; row += 1) {
    for (let col = 0; col < cols; col += 1) {
      frames.push({ x: col * frameW, y: row * frameH, w: frameW, h: frameH });
    }
  }
  asset.extractedFrames = frames;
  asset.extraction = { frameW, frameH, cols, rows };
  appState.selectedFrameIndex = 0;
  exportStatus.textContent = `Extracted ${frames.length} frames (${cols}x${rows}).`;
  renderAll();
}

function resetFrameExtraction() {
  const asset = getSelectedAsset();
  if (!asset || asset.type !== "image") {
    return;
  }
  asset.extractedFrames = [];
  asset.extraction = null;
  appState.selectedFrameIndex = 0;
  exportStatus.textContent = "Frame extraction reset to full image.";
  renderAll();
}

function runAutoMaskDetection() {
  applyStageFocus("masking");
  const asset = getSelectedAsset();
  const frames = getFrameRects(asset);
  const frameRect = frames[appState.selectedFrameIndex] ?? frames[0];
  if (!asset || asset.type !== "image" || !asset.image || !frameRect || !maskCtx) {
    appState.autoMaskRan = true;
    exportStatus.textContent = "Auto mask completed for mock asset (simulated).";
    renderAll();
    return;
  }
  const sampleW = Math.min(frameRect.w, 12);
  const sampleH = Math.min(frameRect.h, 12);
  maskCanvas.width = sampleW;
  maskCanvas.height = sampleH;
  const cornerSamples = [
    { x: frameRect.x, y: frameRect.y },
    { x: frameRect.x + frameRect.w - sampleW, y: frameRect.y },
    { x: frameRect.x, y: frameRect.y + frameRect.h - sampleH },
    { x: frameRect.x + frameRect.w - sampleW, y: frameRect.y + frameRect.h - sampleH },
  ];
  let r = 0;
  let g = 0;
  let b = 0;
  let count = 0;
  for (const corner of cornerSamples) {
    maskCtx.clearRect(0, 0, sampleW, sampleH);
    maskCtx.drawImage(asset.image, corner.x, corner.y, sampleW, sampleH, 0, 0, sampleW, sampleH);
    const data = maskCtx.getImageData(0, 0, sampleW, sampleH).data;
    for (let i = 0; i < data.length; i += 4) {
      r += data[i];
      g += data[i + 1];
      b += data[i + 2];
      count += 1;
    }
  }
  if (count > 0) {
    const rr = Math.round(r / count);
    const gg = Math.round(g / count);
    const bb = Math.round(b / count);
    const hex = `#${rr.toString(16).padStart(2, "0")}${gg.toString(16).padStart(2, "0")}${bb.toString(16).padStart(2, "0")}`;
    appState.chroma.color = hex;
    chromaColor.value = hex;
  }
  appState.autoMaskRan = true;
  appState.maskPreview = true;
  exportStatus.textContent = "Auto mask color sampled from frame corners.";
  renderAll();
}

function buildExportFrameBundle() {
  const asset = getSelectedAsset();
  const frameRects = getFrameRects(asset);
  if (!asset || frameRects.length === 0) {
    return null;
  }
  const frames = frameRects.map((frameRect, index) => {
    const frameCanvas = renderFrameCanvas(asset, frameRect, index);
    return {
      index,
      canvas: frameCanvas,
      width: frameCanvas.width,
      height: frameCanvas.height,
      dataUrl: frameCanvas.toDataURL("image/png"),
    };
  });
  return { asset, frames };
}

async function exportSpriteSheetPng() {
  applyStageFocus("export");
  const bundle = buildExportFrameBundle();
  if (!bundle) {
    exportStatus.textContent = "No asset frames are available for sheet export.";
    return;
  }
  const { asset, frames } = bundle;
  const frameW = frames[0]?.width ?? 96;
  const frameH = frames[0]?.height ?? 96;
  const cols = Math.max(1, Math.ceil(Math.sqrt(frames.length)));
  const rows = Math.max(1, Math.ceil(frames.length / cols));
  const sheetCanvas = document.createElement("canvas");
  sheetCanvas.width = cols * frameW;
  sheetCanvas.height = rows * frameH;
  const sheetCtx = sheetCanvas.getContext("2d");
  if (!sheetCtx) {
    exportStatus.textContent = "Could not create sheet canvas.";
    return;
  }
  sheetCtx.clearRect(0, 0, sheetCanvas.width, sheetCanvas.height);
  for (const frame of frames) {
    const col = frame.index % cols;
    const row = Math.floor(frame.index / cols);
    sheetCtx.drawImage(frame.canvas, col * frameW, row * frameH);
  }

  const defaultName = `${sanitizeFileStem(asset.name, "sprite")}-sheet.png`;
  const filePath = await window.shadowDesigner.exportSpriteSheetPng({
    defaultName,
    dataUrl: sheetCanvas.toDataURL("image/png"),
  });
  if (!filePath) {
    exportStatus.textContent = "Sprite sheet export canceled.";
    return;
  }
  appState.lastExportKind = "sheet";
  exportStatus.textContent = `Sprite sheet saved: ${filePath}`;
  updateMetadataPanel();
}

async function exportFramesZip() {
  applyStageFocus("export");
  const bundle = buildExportFrameBundle();
  if (!bundle) {
    exportStatus.textContent = "No asset frames are available for ZIP export.";
    return;
  }
  const { asset, frames } = bundle;
  const base = sanitizeFileStem(asset.name, "sprite");
  const payloadFrames = frames.map((frame) => ({
    name: `${base}_${String(frame.index).padStart(3, "0")}.png`,
    dataUrl: frame.dataUrl,
  }));
  const filePath = await window.shadowDesigner.exportFrameZip({
    defaultName: `${base}-frames.zip`,
    frames: payloadFrames,
  });
  if (!filePath) {
    exportStatus.textContent = "Frame ZIP export canceled.";
    return;
  }
  appState.lastExportKind = "zip";
  exportStatus.textContent = `Frame ZIP saved: ${filePath}`;
  updateMetadataPanel();
}

async function refreshSourceState() {
  const launchState = await window.shadowDesigner.getLaunchState();
  appState.selectedHtmlPath = launchState.selectedHtmlPath;
  appState.selectedHtmlName = launchState.selectedHtmlName;
  renderAll();
}

async function pickSourceHtml() {
  applyStageFocus("source");
  const result = await window.shadowDesigner.pickHtmlFile();
  if (result) {
    appState.selectedHtmlPath = result.selectedHtmlPath;
    appState.selectedHtmlName = result.selectedHtmlName;
  }
  renderAll();
}

async function clearSourceHtml() {
  const result = await window.shadowDesigner.clearSelectedHtml();
  appState.selectedHtmlPath = result.selectedHtmlPath;
  appState.selectedHtmlName = result.selectedHtmlName;
  renderAll();
}

function reloadSourceHtml() {
  if (!appState.selectedHtmlPath) {
    return;
  }
  exportStatus.textContent = `Reloaded source ${appState.selectedHtmlName ?? appState.selectedHtmlPath}.`;
}

function readDroppedHtmlPath(event) {
  const files = Array.from(event.dataTransfer.files || []);
  const file = files.find((item) => typeof item.path === "string" && /\.(html?|HTML?)$/.test(item.path));
  return file ? file.path : null;
}

async function applyDroppedHtml(event) {
  event.preventDefault();
  const htmlPath = readDroppedHtmlPath(event);
  if (!htmlPath) {
    exportStatus.textContent = "Drop an .html file to set animator source.";
    return;
  }
  const next = await window.shadowDesigner.setSelectedHtmlPath(htmlPath);
  appState.selectedHtmlPath = next.selectedHtmlPath;
  appState.selectedHtmlName = next.selectedHtmlName;
  exportStatus.textContent = `Animator source set: ${next.selectedHtmlName}`;
  renderAll();
}

function importMockAsset() {
  applyStageFocus("assets");
  const now = Date.now().toString(36).slice(-5);
  const asset = {
    id: `asset_${now}`,
    type: "mock",
    name: `Imported Clip ${appState.assets.length + 1}`,
    source: "mock://import",
    frameCount: 10 + ((appState.assets.length * 3) % 7),
    baseHue: (80 + appState.assets.length * 37) % 360,
  };
  appState.assets.unshift(asset);
  appState.selectedAssetId = asset.id;
  appState.selectedFrameIndex = 0;
  exportStatus.textContent = `${asset.name} imported into project assets (mock).`;
  renderAll();
}

function copyRuntimeCode() {
  applyStageFocus("integration");
  const value = runtimeSnippet.value || "";
  navigator.clipboard
    .writeText(value)
    .then(() => {
      exportStatus.textContent = "Runtime code copied to clipboard.";
    })
    .catch(() => {
      exportStatus.textContent = "Could not copy code. Clipboard permission denied.";
    });
}

themeSelect.addEventListener("change", () => {
  applyTheme(themeSelect.value);
  playSoftClickSfx(0.4);
});

for (const button of stageButtons) {
  button.addEventListener("click", () => {
    const nextStage = button.dataset.stageButton;
    if (!nextStage) {
      return;
    }
    applyStageFocus(nextStage);
    exportStatus.textContent = STAGE_CONFIG[nextStage].label;
  });
}

if (stageFocusToggle) {
  stageFocusToggle.addEventListener("click", () => {
    appState.stageFocusMode = !appState.stageFocusMode;
    localStorage.setItem(STAGE_FOCUS_MODE_KEY, appState.stageFocusMode ? "1" : "0");
    applyStageFocus(appState.activeStage);
  });
}

if (sfxToggle) {
  sfxToggle.addEventListener("click", () => {
    appState.clickSfxEnabled = !appState.clickSfxEnabled;
    localStorage.setItem(CLICK_SFX_KEY, appState.clickSfxEnabled ? "1" : "0");
    updateSfxToggleLabel();
    if (appState.clickSfxEnabled) {
      playSoftClickSfx(0.35);
    }
  });
}

document.addEventListener("pointerdown", (event) => {
  const target = event.target;
  if (!(target instanceof HTMLElement)) {
    return;
  }
  if (target.closest("button")) {
    playSoftClickSfx(0.55);
    return;
  }
  if (target.matches("input[type='range']")) {
    playSoftClickSfx(0.3);
  }
});

pickFileButton.addEventListener("click", () => void pickSourceHtml());
pickFileButtonEmpty.addEventListener("click", () => void pickSourceHtml());
clearFileButton.addEventListener("click", () => void clearSourceHtml());
reloadFileButton.addEventListener("click", reloadSourceHtml);
assetSearch.addEventListener("input", renderAssets);
importMockAssetButton.addEventListener("click", importMockAsset);

importImageAssetButton.addEventListener("click", () => {
  importImageInput.click();
});
importImageInput.addEventListener("change", () => {
  void importImageAssetsFromFiles(importImageInput.files).finally(() => {
    importImageInput.value = "";
  });
});

playToggle.addEventListener("click", () => {
  applyStageFocus("animation");
  if (appState.playing) {
    stopPlayback();
    renderAll();
    return;
  }
  startPlayback();
  renderAll();
});

stepPrev.addEventListener("click", () => stepFrame(-1));
stepNext.addEventListener("click", () => stepFrame(1));
toggleGrid.addEventListener("click", () => {
  appState.showGrid = !appState.showGrid;
  renderAll();
});

fpsSlider.addEventListener("input", () => {
  appState.fps = Number(fpsSlider.value);
  if (appState.playing) {
    startPlayback();
  }
  renderAll();
});

frameIndexSlider.addEventListener("input", () => {
  appState.selectedFrameIndex = Number(frameIndexSlider.value);
  renderAll();
});

zoomSlider.addEventListener("input", () => {
  appState.zoom = Number(zoomSlider.value);
  renderAll();
});

extractFramesButton.addEventListener("click", extractFramesFromSelectedAsset);
clearExtractionButton.addEventListener("click", resetFrameExtraction);

panX.addEventListener("input", () => {
  appState.panX = Number(panX.value);
  renderAll();
});

panY.addEventListener("input", () => {
  appState.panY = Number(panY.value);
  renderAll();
});

chromaColor.addEventListener("input", () => {
  appState.chroma.color = chromaColor.value;
  renderAll();
});

chromaTolerance.addEventListener("input", () => {
  appState.chroma.tolerance = Number(chromaTolerance.value);
  renderAll();
});

toggleMaskPreview.addEventListener("click", () => {
  appState.maskPreview = !appState.maskPreview;
  renderAll();
});

runAutoMask.addEventListener("click", runAutoMaskDetection);

exportSheet.addEventListener("click", () => {
  void exportSpriteSheetPng().catch((error) => {
    exportStatus.textContent = `Sprite sheet export failed: ${error.message}`;
  });
});

exportZip.addEventListener("click", () => {
  void exportFramesZip().catch((error) => {
    exportStatus.textContent = `ZIP export failed: ${error.message}`;
  });
});

copyCode.addEventListener("click", copyRuntimeCode);

canvasRoot.addEventListener("dragover", (event) => {
  event.preventDefault();
});
canvasRoot.addEventListener("drop", (event) => {
  void applyDroppedHtml(event);
});

window.addEventListener("beforeunload", () => {
  stopPlayback();
});

window.addEventListener("resize", () => {
  drawPreview();
});

window.addEventListener("DOMContentLoaded", () => {
  const storedTheme = localStorage.getItem(THEME_KEY) || "void-blue";
  appState.stageFocusMode = localStorage.getItem(STAGE_FOCUS_MODE_KEY) === "1";
  appState.clickSfxEnabled = localStorage.getItem(CLICK_SFX_KEY) !== "0";
  applyTheme(storedTheme);
  updateSfxToggleLabel();
  void refreshSourceState();
  renderAll();
  window.shadowDesigner.onRefreshState(() => {
    void refreshSourceState();
  });
});
