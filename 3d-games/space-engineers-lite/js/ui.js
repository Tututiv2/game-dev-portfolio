import { CONFIG, updateConfig } from "./config.js";
import { G } from "./gameState.js";
import { BLOCKS } from "./blocks.js";
import { updateGhostGeometry } from "./build.js";

export function showToast(msg) {
  const el = document.getElementById("messages");
  if (el) el.textContent = msg;
}

export function renderLibrary(category) {
  const container = document.getElementById("lib-content");
  if (!container) return;
  container.innerHTML = "";
  for (const [id, def] of Object.entries(BLOCKS)) {
    if (def.cat !== category) continue;
    const el = document.createElement("div");
    el.className = "lib-item";
    el.draggable = true;
    const hex = def.color.toString(16).padStart(6, "0");
    el.innerHTML = `<div style="width:30px;height:30px;background:#${hex};border:1px solid #666;border-radius:4px;"></div><span class="item-name">${def.name}</span>`;
    el.addEventListener("dragstart", () => {
      G.draggedItem = parseInt(id, 10);
    });
    container.appendChild(el);
  }
}

export function setTab(category, tabEl) {
  document.querySelectorAll(".lib-tab").forEach((el) => el.classList.remove("active"));
  if (tabEl) tabEl.classList.add("active");
  renderLibrary(category);
}

export function renderHotbar() {
  const container = document.getElementById("hotbar-container");
  if (!container) return;
  container.innerHTML = "";
  G.Hotbar.forEach((itemId, index) => {
    const el = document.createElement("div");
    el.className = "slot";
    if (G.Build.isActive && G.Build.activeSlotIndex === index) el.classList.add("selected");
    const def = BLOCKS[itemId];
    const hex = def ? def.color.toString(16).padStart(6, "0") : "333";
    el.innerHTML = def
      ? `<span class="slot-num">${index + 1}</span><div style="width:30px;height:30px;background:#${hex};border:1px solid #fff;border-radius:4px;"></div>`
      : `<span class="slot-num">${index + 1}</span>`;
    el.addEventListener("dragover", (e) => e.preventDefault());
    el.addEventListener("drop", (e) => {
      e.preventDefault();
      if (G.draggedItem) {
        G.Hotbar[index] = G.draggedItem;
        renderHotbar();
        if (G.Build.activeSlotIndex === index) selectSlot(index);
      }
    });
    el.addEventListener("mousedown", () => selectSlot(index));
    container.appendChild(el);
  });
}

export function toggleLibrary() {
  G.isLibraryOpen = !G.isLibraryOpen;
  const modal = document.getElementById("library-modal");
  if (!modal || !G.controls) return;
  if (G.isLibraryOpen) {
    modal.style.display = "flex";
    G.controls.unlock();
  } else {
    modal.style.display = "none";
    G.controls.lock();
  }
}

export function selectSlot(index) {
  if (G.Build.isActive && G.Build.activeSlotIndex === index) {
    G.Build.isActive = false;
    G.Build.ghost.visible = false;
    renderHotbar();
    return;
  }
  G.Build.activeSlotIndex = index;
  G.Build.selectedBlockId = G.Hotbar[index];
  G.Build.isActive = true;
  G.Build.ghost.visible = true;
  updateGhostGeometry(G.Build.selectedBlockId);
  renderHotbar();
}

export function toggleGridMode() {
  G.Build.mode = G.Build.mode === "large" ? "small" : "large";
  const size = G.Build.mode === "large" ? CONFIG.largeGridSize : CONFIG.smallGridSize;
  const ind = document.getElementById("grid-mode-indicator");
  if (ind) ind.textContent = `MODE: ${G.Build.mode.toUpperCase()} GRID (${size}m)`;
  updateGhostGeometry(G.Build.selectedBlockId);
}

/** updateHUD runs every frame — only touch the DOM when a value actually changed. */
function setText(el, txt) {
  if (el && el.textContent !== txt) el.textContent = txt;
}
function setClass(el, cls) {
  if (el && el.className !== cls) el.className = cls;
}

export function updateHUD() {
  const jet = document.getElementById("hud-jet");
  const damp = document.getElementById("hud-damp-suit");
  const planet = document.getElementById("hud-planet");
  if (jet) {
    setText(jet, G.Player.jetpack ? "ON" : "OFF");
    setClass(jet, G.Player.jetpack ? "stat-val active-sys" : "stat-val");
  }
  const lamp = document.getElementById("hud-lamp");
  if (lamp) {
    setText(lamp, G.headLampOn ? "ON" : "OFF");
    setClass(lamp, G.headLampOn ? "stat-val active-sys" : "stat-val");
  }
  if (damp) {
    setText(damp, G.Player.suitDampeners ? "ON" : "OFF");
    setClass(damp, G.Player.suitDampeners ? "stat-val active-sys" : "stat-val inactive-sys");
  }
  setText(planet, G.activePlanet ? G.activePlanet.userData.name : "DEEP SPACE");

  if (G.Player.inShip && G.Player.controlledGrid) {
    const g = G.Player.controlledGrid;
    const pow = document.getElementById("ship-state-power");
    const dmp = document.getElementById("ship-state-damp");
    const prk = document.getElementById("ship-state-park");
    if (pow) {
      setText(pow, g.isOn ? "ONLINE" : "OFFLINE");
      setClass(pow, g.isOn ? "active-sys" : "inactive-sys");
    }
    if (dmp) {
      setText(dmp, g.dampeners ? "ON" : "OFF");
      setClass(dmp, g.dampeners ? "active-sys" : "inactive-sys");
    }
    if (prk) {
      setText(prk, g.isParked ? "LOCKED" : "OFF");
      setClass(prk, g.isParked ? "inactive-sys" : "active-sys");
    }
    const fill = document.getElementById("power-bar-fill");
    const txt = document.getElementById("power-text");
    const draw = document.getElementById("power-draw");
    const pct = g.totalCapacity > 0 ? (g.currentPower / g.totalCapacity) * 100 : 0;
    const w = `${pct}%`;
    if (fill && fill.style.width !== w) fill.style.width = w;
    setText(txt, `${pct.toFixed(1)}%`);
    setText(draw, `Draw: ${(g.powerDraw * 100).toFixed(0)} kW`);
  }
}

export function setupUI() {
  renderHotbar();
  renderLibrary("structure");

  document.querySelectorAll(".lib-tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      const cat = tab.dataset.cat || "structure";
      setTab(cat, tab);
    });
  });

  document.getElementById("dev-menu")?.querySelectorAll("input[type=range]").forEach((input) => {
    input.addEventListener("input", () => {
      const key = input.dataset.cfg;
      if (key) updateConfig(key, input.value);
    });
  });
}
