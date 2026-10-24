// HUD: animated resource counters, EPS sparkline, shop, toasts, floating numbers.
// Reads from state; calls back into game actions via the `actions` object.

import { formatNumber, formatRate, approach } from "../core/math.js";
import { NODE_ORDER, NODE_DEFS, nodeCost, nodeUnlocked } from "../sim/nodes.js";
import { effectiveCost } from "../sim/resources.js";
import { RESEARCH, canResearch } from "../content/research.js";
import { ECHO_UPGRADES, echoCost } from "../content/echoes.js";
import { SECRETS, TOTAL_SECRETS } from "../content/secrets.js";
import { collapsePreview, canCollapse } from "../sim/prestige.js";
import { MODIFIERS, RARITY, dimensionColor } from "../content/dimensions.js";

export function createUI(state, actions) {
  const el = {
    aether: document.getElementById("aether-val"),
    essence: document.getElementById("essence-val"),
    eps: document.getElementById("eps-val"),
    shopList: document.getElementById("shop-list"),
    statDrip: document.getElementById("stat-drip"),
    statDirect: document.getElementById("stat-direct"),
    statNet: document.getElementById("stat-net"),
    statTotal: document.getElementById("stat-total"),
    statNodes: document.getElementById("stat-nodes"),
    statBottleneck: document.getElementById("stat-bottleneck"),
    statBottleneckVal: document.getElementById("stat-bottleneck-val"),
    spark: document.getElementById("eps-spark"),
    surge: document.getElementById("surge-status"),
    toastStack: document.getElementById("toast-stack"),
    introTip: document.getElementById("intro-tip"),
    echoDisplay: document.getElementById("echo-display"),
    echoVal: document.getElementById("echo-val"),
    codexCount: document.getElementById("codex-count"),
    // research panel
    researchGrid: document.getElementById("research-grid"),
    researchEssence: document.getElementById("research-essence"),
    // prestige panel
    collapseRunEssence: document.getElementById("collapse-run-essence"),
    collapseGain: document.getElementById("collapse-gain"),
    collapseTotal: document.getElementById("collapse-total"),
    collapseCount: document.getElementById("collapse-count"),
    collapseBtn: document.getElementById("collapse-btn"),
    echoGrid: document.getElementById("echo-grid"),
    // codex panel
    codexNodes: document.getElementById("codex-nodes"),
    codexSecrets: document.getElementById("codex-secrets"),
    codexSecretCount: document.getElementById("codex-secret-count"),
    // stage 4
    keyDisplay: document.getElementById("key-display"),
    keyVal: document.getElementById("key-val"),
    dimChip: document.getElementById("dim-chip"),
    statMelt: document.getElementById("stat-melt"),
    statMeltVal: document.getElementById("stat-melt-val"),
    dimGrid: document.getElementById("dim-grid"),
    dimKeysNote: document.getElementById("dim-keys-note"),
    dimReroll: document.getElementById("dim-reroll"),
    layoutGrid: document.getElementById("layout-grid"),
  };
  const sctx = el.spark.getContext("2d");

  let displayAether = state.aether;
  const epsHistory = new Array(60).fill(0);
  let epsTimer = 0;
  let uiAcc = 0.1; // accumulator to throttle heavy DOM writes (~10 Hz)
  const surgeSpan = el.surge.querySelector("span");
  const shopItems = {};

  buildShop();

  function buildShop() {
    el.shopList.innerHTML = "";
    for (const id of NODE_ORDER) {
      const def = NODE_DEFS[id];
      const btn = document.createElement("button");
      btn.className = "shop-item";
      btn.style.setProperty("--accent", def.accent);
      btn.innerHTML = `
        <span class="si-name">${def.name}</span>
        <span class="si-desc">${def.desc}</span>
        <span class="si-cost">0</span>`;
      btn.addEventListener("click", () => {
        if (!nodeUnlocked(state, id)) { actions.toast("Locked \u2014 Collapse into a Dimension to unlock"); return; }
        actions.selectNode(id);
      });
      el.shopList.appendChild(btn);
      shopItems[id] = { btn, cost: btn.querySelector(".si-cost") };
    }
  }

  function refreshShop() {
    for (const id of NODE_ORDER) {
      const { btn, cost } = shopItems[id];
      const owned = state.owned[id] || 0;
      const unlocked = nodeUnlocked(state, id);
      if (!unlocked) {
        cost.textContent = "Locked \u2014 Collapse to unlock";
        btn.classList.add("locked");
        btn.classList.remove("affordable", "too-expensive", "selected");
        continue;
      }
      btn.classList.remove("locked");
      const c = effectiveCost(state, id);
      cost.textContent = `${formatNumber(c)} \u2726  \u00d7${owned}`;
      btn.classList.toggle("selected", state.selectedNode === id && state.selectedTool === "place");
      const afford = state.aether >= c;
      btn.classList.toggle("affordable", afford);
      btn.classList.toggle("too-expensive", !afford);
    }
  }

  // Called every render frame. Cheap per-frame updates run always; the heavier
  // DOM writes (shop, stat rows, status chips) are throttled to ~10 Hz.
  function update(dt) {
    // smooth per-frame counters (cheap: a few textContent writes)
    displayAether = approach(displayAether, state.aether, dt, 12);
    el.aether.textContent = formatNumber(displayAether);
    el.eps.textContent = formatRate(state.eps);
    el.essence.textContent = formatNumber(state.essence);

    // one-shot events + intro tip handled every frame (snappy feedback)
    consumeEvents();

    uiAcc += dt;
    if (uiAcc < 0.1) return;
    uiAcc = 0;

    const flow = state.flow || { directFlow: 0, networkedFlow: 0, bottleneck: null, bottleneckUtil: 0 };
    el.statDrip.textContent = formatRate(state.dripRate);
    el.statDirect.textContent = formatRate(flow.directFlow || 0);
    el.statNet.textContent = formatRate(flow.networkedFlow || 0);
    el.statTotal.textContent = formatRate(state.eps);
    el.statNodes.textContent = `${state.nodes.length} / ${state.edges.length}`;

    if (flow.bottleneck && flow.bottleneckUtil > 0.85) {
      el.statBottleneck.hidden = false;
      el.statBottleneckVal.textContent = Math.round(flow.bottleneckUtil * 100) + "%";
    } else {
      el.statBottleneck.hidden = true;
    }

    refreshShop();
    drawSpark(0.1);

    // Echoes display (appears once you've earned any)
    if (state.echoes > 0 || state.collapses > 0) {
      el.echoDisplay.hidden = false;
      el.echoVal.textContent = formatNumber(state.echoes);
    }

    // Surge status
    const s = state.surge;
    if (s.active) {
      el.surge.classList.add("active");
      surgeSpan.textContent = `+200% ${Math.ceil(s.timeLeft)}s`;
    } else {
      el.surge.classList.remove("active");
      surgeSpan.textContent = s.cooldown > 0
        ? `cooldown ${Math.ceil(s.cooldown)}s`
        : "tune a 2:3:5 chain";
    }

    // Keys + active dimension (Stage 4)
    if (state.keys > 0 || state.collapses > 0) {
      el.keyDisplay.hidden = false;
      el.keyVal.textContent = formatNumber(state.keys);
    }
    if (state.dimension) {
      if (el.dimChip._name !== state.dimension.name) {
        el.dimChip._name = state.dimension.name;
        el.dimChip.textContent = state.dimension.name;
        el.dimChip.style.background = `linear-gradient(135deg, ${dimensionColor(state.dimension)}, var(--violet))`;
      }
      el.dimChip.hidden = false;
    } else {
      el.dimChip.hidden = true;
    }

    // Melted node count
    const melted = state.nodes.reduce((n, x) => n + (x.melted ? 1 : 0), 0);
    if (melted > 0) {
      el.statMelt.hidden = false;
      el.statMeltVal.textContent = `${melted} (Inspect to repair)`;
    } else {
      el.statMelt.hidden = true;
    }

    // Codex sidebar count
    el.codexCount.textContent = `${state.codex.secrets.length} / ${TOTAL_SECRETS} secrets`;
  }

  // One-shot sim events -> toasts (checked every frame for snappy feedback).
  function consumeEvents() {
    if (state._newMilestone !== undefined) {
      toast(`Milestone: ${formatRate(Math.pow(10, state._newMilestone))} flow (+production)`, "gold");
      state._newMilestone = undefined;
    }
    if (state._surgeStarted) {
      toast("HARMONIC SURGE — Essence x3!", "gold");
      state._surgeStarted = false;
    }
    if (state._newSecret) {
      const sec = SECRETS[state._newSecret];
      if (sec) toast(`Secret found: ${sec.name}`, "gold");
      state._newSecret = null;
    }
    if (state._meltAlert) {
      toast("\u2620 A node OVERHEATED and melted! Inspect it to repair.", "");
      state._meltAlert = false;
    }
    if (state._windfall) {
      toast(`Windfall! +${formatNumber(state._windfall)} Aether`, "gold");
      state._windfall = 0;
    }
    if (state.nodes.length > 0 && el.introTip && !el.introTip._hidden) {
      el.introTip.style.opacity = "0";
      el.introTip._hidden = true;
      setTimeout(() => el.introTip && el.introTip.remove(), 600);
    }
  }

  // ---- panel renderers (called by main on open + after purchases) ----
  function renderResearch(actions) {
    el.researchEssence.textContent = formatNumber(Math.floor(state.essence));
    el.researchGrid.innerHTML = "";
    for (const r of RESEARCH) {
      const owned = state.research.includes(r.id);
      const can = canResearch(state, r.id) && state.essence >= r.cost;
      const unlockable = canResearch(state, r.id);
      const div = document.createElement("button");
      div.className = "research-item" + (owned ? " owned" : can ? " affordable" : !unlockable ? " locked" : "");
      div.innerHTML = `
        <span class="ri-branch">${r.branch}</span>
        <span class="ri-name">${r.name}</span>
        <span class="ri-desc">${r.desc}</span>
        <span class="ri-cost">${owned ? "Researched" : formatNumber(r.cost) + " Essence"}</span>`;
      if (!owned && unlockable) div.addEventListener("click", () => actions.buyResearch(r.id));
      el.researchGrid.appendChild(div);
    }
  }

  function renderPrestige(actions) {
    el.collapseRunEssence.textContent = formatNumber(Math.floor(state.runEssence));
    el.collapseGain.textContent = formatNumber(collapsePreview(state));
    el.collapseTotal.textContent = formatNumber(state.echoes);
    el.collapseCount.textContent = String(state.collapses);
    el.collapseBtn.disabled = !canCollapse(state);
    el.collapseBtn.textContent = canCollapse(state)
      ? `Collapse  (+${formatNumber(collapsePreview(state))} Echoes)`
      : "Need more Essence";

    el.echoGrid.innerHTML = "";
    for (const up of ECHO_UPGRADES) {
      const lvl = state.echoShop[up.id] || 0;
      const maxed = lvl >= up.max;
      const cost = echoCost(up, lvl);
      const can = !maxed && state.echoes >= cost;
      const div = document.createElement("button");
      div.className = "echo-item" + (maxed ? " maxed" : can ? " affordable" : "");
      div.innerHTML = `
        <span class="ei-name">${up.name} <span>Lv ${lvl}${maxed ? " (max)" : "/" + up.max}</span></span>
        <span class="ei-desc">${up.desc}</span>
        <span class="ei-cost">${maxed ? "Maxed" : formatNumber(cost) + " Echoes"}</span>`;
      if (!maxed) div.addEventListener("click", () => actions.buyEcho(up.id));
      el.echoGrid.appendChild(div);
    }
  }

  function renderCodex() {
    // nodes
    el.codexNodes.innerHTML = "";
    for (const id of NODE_ORDER) {
      const def = NODE_DEFS[id];
      const found = state.codex.nodes.includes(id);
      const div = document.createElement("div");
      div.className = "codex-cell" + (found ? " found" : " hidden-entry");
      div.innerHTML = found
        ? `<div class="cc-name" style="color:${def.accent}">${def.name}</div><div class="cc-lore">${def.desc}</div>`
        : `<div>??? undiscovered</div>`;
      el.codexNodes.appendChild(div);
    }
    // secrets
    el.codexSecretCount.textContent = `${state.codex.secrets.length} / ${TOTAL_SECRETS}`;
    el.codexSecrets.innerHTML = "";
    const order = ["golden_relay", "void_click", "mirror_shard", "lucky_echo"];
    let shown = 0;
    for (const id of order) {
      const sec = SECRETS[id];
      const found = state.codex.secrets.includes(id);
      const div = document.createElement("div");
      div.className = "codex-cell" + (found ? " found" : " hidden-entry");
      div.innerHTML = found
        ? `<div class="cc-name gold">${sec.name}</div><div class="cc-lore">${sec.lore}</div>`
        : `<div>??? hidden secret</div>`;
      el.codexSecrets.appendChild(div);
      shown++;
    }
    // remaining locked placeholders up to TOTAL_SECRETS
    for (let i = shown; i < Math.min(TOTAL_SECRETS, shown + 8); i++) {
      const div = document.createElement("div");
      div.className = "codex-cell hidden-entry";
      div.innerHTML = `<div>??? (later stages)</div>`;
      el.codexSecrets.appendChild(div);
    }
  }

  // ---- Stage 4 renderers ----
  function renderDimSelect(actions) {
    el.dimKeysNote.textContent = `You have ${state.keys} Key${state.keys === 1 ? "" : "s"}.`;
    el.dimReroll.disabled = state.keys < 1;
    el.dimReroll.style.opacity = state.keys < 1 ? "0.45" : "1";
    el.dimGrid.innerHTML = "";
    const choices = state.dimChoices || [];
    for (const dim of choices) {
      const color = dimensionColor(dim);
      const maxR = Math.min(4, Math.max(1, ...dim.mods.map((id) => (MODIFIERS[id] ? MODIFIERS[id].rarity : 1))));
      const card = document.createElement("button");
      card.className = "dim-opt";
      card.style.setProperty("--dimc", color);
      const modsHtml = dim.mods.map((id) => {
        const m = MODIFIERS[id];
        if (!m) return "";
        const mr = RARITY[m.rarity].color;
        return `<div class="dim-mod ${m.good ? "boon" : "bane"}" style="--mr:${mr}">
          <span><span class="dm-name">${m.name}</span> <span class="dm-desc">${m.desc}</span></span>
        </div>`;
      }).join("");
      card.innerHTML = `
        <span class="do-tier" style="background:${color}">${RARITY[maxR].name} \u00b7 Tier ${dim.tier}</span>
        <div class="do-name">${dim.name}</div>
        <div class="do-hz">${dim.hz}Hz resonance</div>
        <div class="dim-mods">${modsHtml}</div>`;
      card.addEventListener("click", () => actions.enterDimension(dim));
      el.dimGrid.appendChild(card);
    }
  }

  function renderLayout(actions, plans) {
    el.layoutGrid.innerHTML = "";
    if (!plans || plans.length === 0) {
      el.layoutGrid.innerHTML = `<p class="muted">Place a few nodes first, then I can wire them.</p>`;
      return;
    }
    const best = Math.max(...plans.map((p) => p.eps));
    for (const p of plans) {
      const card = document.createElement("button");
      card.className = "layout-opt" + (p.eps >= best && best > 0 ? " best" : "");
      card.innerHTML = `
        <div class="lo-name">${p.name}</div>
        <div class="lo-desc">${p.desc}</div>
        <div class="lo-eps"><b>${formatRate(p.eps)}</b>${p.eps >= best && best > 0 ? " \u2605 best" : ""}</div>`;
      card.addEventListener("click", () => actions.applyLayout(p.edges));
      el.layoutGrid.appendChild(card);
    }
  }

  function drawSpark(dt) {
    epsTimer += dt;
    if (epsTimer >= 1) {
      epsTimer = 0;
      epsHistory.push(state.eps);
      epsHistory.shift();
    }
    const w = el.spark.width, h = el.spark.height;
    sctx.clearRect(0, 0, w, h);
    const max = Math.max(1, ...epsHistory);
    sctx.beginPath();
    epsHistory.forEach((v, i) => {
      const x = (i / (epsHistory.length - 1)) * w;
      const y = h - (v / max) * (h - 4) - 2;
      i ? sctx.lineTo(x, y) : sctx.moveTo(x, y);
    });
    sctx.strokeStyle = "#36e6ff";
    sctx.lineWidth = 1.5;
    sctx.stroke();
    sctx.lineTo(w, h);
    sctx.lineTo(0, h);
    sctx.closePath();
    sctx.fillStyle = "rgba(54,230,255,0.12)";
    sctx.fill();
  }

  // Milestone toasts come from the sim's `_newMilestone` event (see consumeEvents);
  // a second local tracker here used to double-toast and mis-fire on load.

  function toast(msg, kind = "") {
    const t = document.createElement("div");
    t.className = "toast" + (kind ? " " + kind : "");
    t.textContent = msg;
    el.toastStack.appendChild(t);
    setTimeout(() => t.remove(), 3200);
  }

  function floatNumber(sx, sy, text) {
    const f = document.createElement("div");
    f.className = "float-num";
    f.textContent = text;
    f.style.left = sx + "px";
    f.style.top = sy + "px";
    document.body.appendChild(f);
    setTimeout(() => f.remove(), 900);
  }

  function setSurge(text) {
    el.surge.querySelector("span").textContent = text;
  }

  return { update, toast, floatNumber, setSurge, refreshShop, renderResearch, renderPrestige, renderCodex, renderDimSelect, renderLayout };
}
