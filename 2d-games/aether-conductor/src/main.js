// Bootstrap: wire sim, render, UI, save, input. Runs the fixed-timestep sim and
// the render loop; pauses sim work when the tab is hidden.

import { createState } from "./sim/state.js";
import { createTicker } from "./core/tick.js";
import {
  tickProduction, recomputeEps, placeNode, removeNode,
  manualSiphon, nodeAt, effectiveCost, buyResearch,
} from "./sim/resources.js";
import { nodeCost, NODE_ORDER } from "./sim/nodes.js";
import { addEdge, removeEdge, CORE_ID } from "./sim/graph.js";
import { doCollapse, buyEcho, rerollChoices, enterDimension } from "./sim/prestige.js";
import { repairNode } from "./sim/heat.js";
import { planLayouts, applyLayout } from "./sim/autolayout.js";
import { load, save, applyOffline, wipe, writeRaw } from "./core/save.js";
import { exportSave, importSave } from "./core/transfer.js";
import { createScene } from "./render/scene.js";
import { createMinimap } from "./render/minimap.js";
import { createUI } from "./render/ui.js";
import { createDevMenu } from "./ui/devMenu.js";
import { formatNumber } from "./core/math.js";

// ---- state ----
let state = load() || createState();

// ---- subsystems ----
const scene = createScene();
const minimap = createMinimap(scene);
let wireSource = null; // node id selected as the wire start

const actions = {
  selectNode(id) {
    state.selectedNode = id;
    state.selectedTool = "place";
    setActiveTool("place");
  },
  toast: (m, k) => ui.toast(m, k),
  floatNumber: (x, y, t) => ui.floatNumber(x, y, t),
  toggleFps(on) {
    fpsEl.hidden = !on;
  },
  wipe() {
    wipe();
    location.reload();
  },
  buyResearch(id) {
    const r = buyResearch(state, id);
    if (!r.ok && r.reason) ui.toast(r.reason);
    else ui.toast("Researched");
    ui.renderResearch(actions);
  },
  buyEcho(id) {
    const r = buyEcho(state, id);
    if (!r.ok && r.reason) ui.toast(r.reason);
    ui.renderPrestige(actions);
  },
  collapse() {
    // Lucky Echo secret: collapse on exactly 777 Essence
    const lucky = Math.floor(state.essence) === 777;
    if (lucky && !state.codex.secrets.includes("lucky_echo")) {
      state.codex.secrets.push("lucky_echo");
      ui.toast("Secret found: Lucky Echo", "gold");
    }
    const res = doCollapse(state, lucky);
    ui.toast(`Collapsed: +${res.gained} Echoes, +${res.keys} Key${res.keys === 1 ? "" : "s"}`, "gold");
    closePanel("prestige");
    openPanel("dimselect");
  },
  enterDimension(dim) {
    enterDimension(state, dim);
    recomputeEps(state);
    ui.refreshShop();
    closePanel("dimselect");
    ui.toast(`Entered ${dim.name}`, "gold");
  },
  rerollDim() {
    if (rerollChoices(state)) ui.renderDimSelect(actions);
    else ui.toast("Need a Key to reroll");
  },
  applyLayout(edges) {
    applyLayout(state, edges);
    recomputeEps(state);
    closePanel("layout");
    ui.toast("Layout applied");
  },
  repair(q, r) {
    const res = repairNode(state, q, r);
    if (res.ok) { recomputeEps(state); ui.toast(`Repaired (-${formatNumber(res.cost)} Essence)`); }
    else if (res.reason && res.reason !== "empty" && res.reason !== "not melted") ui.toast(res.reason);
    return res.ok;
  },
};

const ui = createUI(state, actions);
createDevMenu(state, actions);

const fpsEl = document.getElementById("fps-readout");
fpsEl.hidden = !state.settings.showFps;

// ---- offline progress ----
const offline = applyOffline(state);
if (offline) showOfflineModal(offline);
recomputeEps(state);

// ---- sim loop ----
const ticker = createTicker((dt) => {
  tickProduction(state, dt);
});

// ---- input ----
setupInput();
setupTools();
setupPanels();

// If a Collapse happened but no Dimension was chosen yet, force the choice now.
if (state.dimChoices) openPanel("dimselect");

// ---- render loop ----
let lastFrame = performance.now();
let fpsAccum = 0, fpsFrames = 0, fpsValue = 0;
let miniAcc = 0;
let hidden = document.hidden;

document.addEventListener("visibilitychange", () => {
  hidden = document.hidden;
  if (!hidden) {
    // returning to tab: award offline-style catch-up, then resume
    const off = applyOffline(state);
    if (off && off.gained > 1) ui.toast(`+${formatNumber(off.gained)} Aether while away`);
    lastFrame = performance.now();
  } else {
    state.lastSeen = Date.now();
    save(state);
  }
});

function frame(now) {
  const frameMs = Math.min(now - lastFrame, 250);
  lastFrame = now;
  const dt = frameMs / 1000;

  if (!hidden) {
    ticker(frameMs);
    scene.render(state, dt);
    ui.update(dt);
    // minimap is cheap but redundant at 60fps — refresh ~7 Hz
    miniAcc += dt;
    if (miniAcc >= 0.14) { miniAcc = 0; minimap.render(state); }
  }

  // fps meter
  fpsAccum += dt; fpsFrames++;
  if (fpsAccum >= 0.5) {
    fpsValue = Math.round(fpsFrames / fpsAccum);
    fpsAccum = 0; fpsFrames = 0;
    if (state.settings.showFps) fpsEl.textContent = fpsValue + " fps";
  }

  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

// ---- autosave ----
setInterval(() => save(state), 10000);
window.addEventListener("beforeunload", () => { state.lastSeen = Date.now(); save(state); });

// ===================== input handling =====================
function setupInput() {
  const canvas = document.getElementById("scene");
  let dragging = false;
  let dragMoved = false;
  let last = { x: 0, y: 0 };
  let downBtn = -1;

  canvas.addEventListener("contextmenu", (e) => e.preventDefault());

  canvas.addEventListener("pointerdown", (e) => {
    downBtn = e.button;
    dragging = true;
    dragMoved = false;
    last = { x: e.clientX, y: e.clientY };
    canvas.setPointerCapture(e.pointerId);
  });

  canvas.addEventListener("pointermove", (e) => {
    scene.setPointer(e.clientX, e.clientY, true);
    if (dragging) {
      const dx = e.clientX - last.x;
      const dy = e.clientY - last.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) dragMoved = true;
      // pan only with middle button or while moved significantly with left in inspect
      if (downBtn === 1 || (downBtn === 0 && state.selectedTool === "inspect")) {
        scene.pan(dx, dy);
      }
      last = { x: e.clientX, y: e.clientY };
    }
  });

  canvas.addEventListener("pointerup", (e) => {
    dragging = false;
    if (dragMoved) { downBtn = -1; return; }
    const cell = scene.screenToCell(e.clientX, e.clientY);

    if (e.button === 2) {
      // right-click delete (also clears wire selection)
      cancelWire();
      const refund = removeNode(state, cell.q, cell.r);
      if (refund.ok) {
        ui.toast(`Removed (+${formatNumber(refund.refund)} Aether)`);
        ui.refreshShop();
      }
    } else if (e.button === 0) {
      if (state.selectedTool === "place") tryPlace(cell, e.clientX, e.clientY);
      else if (state.selectedTool === "wire") handleWire(cell);
      else if (state.selectedTool === "inspect") {
        const n = nodeAt(state, cell.q, cell.r);
        if (n && n.melted) { actions.repair(cell.q, cell.r); ui.refreshShop(); }
      }
    }
    downBtn = -1;
  });

  canvas.addEventListener("pointerleave", () => scene.setPointer(0, 0, false));

  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
    scene.zoomAt(e.clientX, e.clientY, factor);
  }, { passive: false });

  // keyboard
  window.addEventListener("keydown", (e) => {
    // don't hijack keys while typing (e.g. the save import textarea)
    const t = e.target;
    if (t && (t.tagName === "TEXTAREA" || t.tagName === "INPUT" || t.isContentEditable)) return;
    if (e.code === "Space") {
      e.preventDefault();
      doSiphon();
    } else if (e.code === "Tab") {
      e.preventDefault();
      const cycle = ["place", "wire", "inspect"];
      const next = cycle[(cycle.indexOf(state.selectedTool) + 1) % cycle.length];
      state.selectedTool = next;
      setActiveTool(next);
    } else if (e.key >= "1" && e.key <= "9") {
      const idx = parseInt(e.key, 10) - 1;
      if (NODE_ORDER[idx]) actions.selectNode(NODE_ORDER[idx]);
    } else if (e.key === "w" || e.key === "W") {
      state.selectedTool = "wire";
      setActiveTool("wire");
    }
  });

  // siphon button
  document.getElementById("siphon-btn").addEventListener("click", doSiphon);

  function doSiphon() {
    const power = manualSiphon(state);
    // float number near the rift / button
    const btn = document.getElementById("siphon-btn").getBoundingClientRect();
    ui.floatNumber(btn.left + btn.width / 2, btn.top, "+" + formatNumber(power));
  }

  function tryPlace(cell, sx, sy) {
    const id = state.selectedNode;
    const cost = effectiveCost(state, id);
    const res = placeNode(state, id, cell.q, cell.r);
    if (res.ok) {
      ui.floatNumber(sx, sy, "-" + formatNumber(cost));
      ui.refreshShop();
    } else if (res.reason === "too expensive") {
      ui.toast("Not enough Aether");
    } else if (res.reason === "core") {
      ui.toast("The Core occupies the center");
    } else if (res.reason === "locked") {
      ui.toast("Locked \u2014 Collapse into a Dimension to unlock");
    }
  }

  // Returns the wire target id at a cell: "core" for center, node id, or null.
  function targetAt(cell) {
    if (cell.q === 0 && cell.r === 0) return CORE_ID;
    const n = nodeAt(state, cell.q, cell.r);
    return n ? n.id : null;
  }

  function handleWire(cell) {
    const target = targetAt(cell);

    if (wireSource == null) {
      if (target === CORE_ID) { ui.toast("Wire FROM a node, not the Core"); return; }
      if (target == null) { ui.toast("Click a node to start a link"); return; }
      wireSource = target;
      scene.setWireSource(target);
      return;
    }

    // second click
    if (target == null || target === wireSource) { cancelWire(); return; }

    // toggle: remove existing edge, else create
    const exists = state.edges.some((e) => e.from === wireSource && e.to === target);
    if (exists) {
      removeEdge(state, wireSource, target);
      ui.toast("Link removed");
    } else {
      const res = addEdge(state, wireSource, target);
      if (!res.ok) ui.toast("Can't link: " + res.reason);
    }
    cancelWire();
  }

  function cancelWire() {
    wireSource = null;
    scene.setWireSource(null);
  }

  // expose for tool switching
  window.__cancelWire = cancelWire;
}

function setupTools() {
  document.querySelectorAll(".tool").forEach((btn) => {
    btn.addEventListener("click", () => {
      const t = btn.dataset.tool;
      state.selectedTool = t;
      setActiveTool(t);
    });
  });
  setActiveTool(state.selectedTool);
}

function openPanel(id) {
  const el = document.getElementById(id);
  if (!el) return;
  if (id === "research") ui.renderResearch(actions);
  else if (id === "prestige") ui.renderPrestige(actions);
  else if (id === "codex") ui.renderCodex();
  else if (id === "layout") ui.renderLayout(actions, planLayouts(state));
  else if (id === "dimselect") ui.renderDimSelect(actions);
  else if (id === "savepanel") resetSavePanel();
  el.hidden = false;
}
function closePanel(id) {
  const el = document.getElementById(id);
  if (el) el.hidden = true;
}

function setupPanels() {
  document.querySelectorAll("[data-open]").forEach((b) =>
    b.addEventListener("click", () => openPanel(b.dataset.open))
  );
  document.querySelectorAll("[data-close]").forEach((b) =>
    b.addEventListener("click", () => closePanel(b.dataset.close))
  );
  // click backdrop to close (dimension select is mandatory — not dismissible)
  document.querySelectorAll(".overlay").forEach((ov) => {
    if (ov.id === "dimselect") return;
    ov.addEventListener("click", (e) => { if (e.target === ov) ov.hidden = true; });
  });
  document.getElementById("collapse-btn").addEventListener("click", () => actions.collapse());
  document.getElementById("dim-reroll").addEventListener("click", () => actions.rerollDim());
  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      document.querySelectorAll(".overlay").forEach((o) => { if (o.id !== "dimselect") o.hidden = true; });
    }
  });

  // ---- save transfer ----
  const exportBox = document.getElementById("save-export");
  const importBox = document.getElementById("save-import");
  const saveMsg = document.getElementById("save-msg");
  document.getElementById("save-generate").addEventListener("click", () => {
    state.lastSeen = Date.now();
    exportBox.value = exportSave(state);
    exportBox.focus(); exportBox.select();
    saveMsg.textContent = "Generated.";
  });
  document.getElementById("save-copy").addEventListener("click", async () => {
    if (!exportBox.value) exportBox.value = exportSave(state);
    try {
      await navigator.clipboard.writeText(exportBox.value);
      saveMsg.textContent = "Copied to clipboard.";
    } catch (err) {
      exportBox.focus(); exportBox.select();
      saveMsg.textContent = "Press Ctrl+C to copy.";
    }
  });
  document.getElementById("save-load").addEventListener("click", () => {
    const str = importBox.value.trim();
    if (!str) { saveMsg.textContent = "Paste a save string first."; return; }
    try {
      const raw = importSave(str);
      writeRaw(raw);
      saveMsg.textContent = "Imported! Reloading...";
      setTimeout(() => location.reload(), 500);
    } catch (err) {
      saveMsg.textContent = "Invalid save string.";
    }
  });
}

function resetSavePanel() {
  const exportBox = document.getElementById("save-export");
  const saveMsg = document.getElementById("save-msg");
  if (exportBox) exportBox.value = "";
  if (saveMsg) saveMsg.textContent = "";
}

function setActiveTool(tool) {
  document.querySelectorAll(".tool").forEach((b) =>
    b.classList.toggle("active", b.dataset.tool === tool)
  );
  if (window.__cancelWire) window.__cancelWire();
  const canvas = document.getElementById("scene");
  canvas.style.cursor = tool === "place" ? "crosshair" : tool === "wire" ? "pointer" : "grab";
}

// ===================== offline modal =====================
function showOfflineModal(off) {
  const backdrop = document.getElementById("modal-backdrop");
  const timeEl = document.getElementById("offline-time");
  const breakdown = document.getElementById("offline-breakdown");
  const totalEl = document.getElementById("offline-total");

  const h = Math.floor(off.cappedSec / 3600);
  const m = Math.floor((off.cappedSec % 3600) / 60);
  const s = Math.floor(off.cappedSec % 60);
  const awayH = Math.floor(off.elapsedSec / 3600);
  const awayM = Math.floor((off.elapsedSec % 3600) / 60);
  timeEl.innerHTML = `You were away for <b>${awayH}h ${awayM}m</b>` +
    (off.capped ? ` <span style="color:var(--ink-dim)">(capped at 8h)</span>` : "");

  breakdown.innerHTML = `
    <div class="ob-row"><span>Flow rate</span><span>${formatNumber(off.eps)}/s</span></div>
    ${off.essGained > 0 ? `<div class="ob-row"><span>Essence rate</span><span>${formatNumber(off.essRate)}/s</span></div>` : ""}
    <div class="ob-row"><span>Time credited</span><span>${h}h ${m}m ${s}s</span></div>`;
  totalEl.innerHTML = "+" + formatNumber(off.gained) + " Aether" +
    (off.essGained > 0 ? `<br><span style="font-size:18px;color:var(--magenta)">+${formatNumber(off.essGained)} Essence</span>` : "");

  backdrop.hidden = false;
  document.getElementById("offline-claim").onclick = () => {
    backdrop.hidden = true;
  };
}
