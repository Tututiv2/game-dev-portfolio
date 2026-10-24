// Dev panel toggled with backtick. Accelerates testing: add Aether, skip time,
// toggle FPS readout, wipe save.

import { recomputeEps } from "../sim/resources.js";

export function createDevMenu(state, actions) {
  const panel = document.getElementById("dev-panel");
  const toggleBtn = document.getElementById("dev-toggle");

  function toggle() {
    panel.hidden = !panel.hidden;
  }

  window.addEventListener("keydown", (e) => {
    const t = e.target;
    if (t && (t.tagName === "TEXTAREA" || t.tagName === "INPUT" || t.isContentEditable)) return;
    if (e.key === "`" || e.code === "Backquote") {
      e.preventDefault();
      toggle();
    }
  });
  toggleBtn.addEventListener("click", toggle);

  panel.addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-dev]");
    if (!btn) return;
    const act = btn.dataset.dev;
    if (act === "add1k") { state.aether += 1000; state.totalAether += 1000; }
    else if (act === "add1m") { state.aether += 1e6; state.totalAether += 1e6; }
    else if (act === "ess") { state.essence += 1e4; state.runEssence += 1e4; }
    else if (act === "echo") { state.echoes += 50; }
    else if (act === "keys") { state.keys += 5; }
    else if (act === "melt") {
      const candidates = state.nodes.filter((n) => !n.melted);
      if (candidates.length) {
        const n = candidates[Math.floor(Math.random() * candidates.length)];
        n.melted = true; n.heat = 999;
        state._meltAlert = true; state.epsDirty = true; state.flowDirty = true;
        recomputeEps(state);
      } else actions.toast("No nodes to melt");
    }
    else if (act === "surge") {
      state.surge.active = true;
      state.surge.timeLeft = 30 + (state.mods ? state.mods.surgeDur : 0);
      state.surge.cooldown = 0;
      state.surge.pulses++;
    }
    else if (act === "skip1h") {
      recomputeEps(state);
      const g = state.eps * 3600;
      state.aether += g; state.totalAether += g;
      actions.toast(`Skipped 1h: +${Math.floor(g)} Aether`);
    }
    else if (act === "fps") {
      state.settings.showFps = !state.settings.showFps;
      actions.toggleFps(state.settings.showFps);
    }
    else if (act === "wipe") {
      if (confirm("Wipe save and restart?")) actions.wipe();
    }
  });
}
