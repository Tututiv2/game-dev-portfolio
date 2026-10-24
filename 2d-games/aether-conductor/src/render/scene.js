// Canvas2D renderer: starfield void + hex grid, edges, nodes, Core, ghost preview,
// wire-tool line, heat tint, bottleneck pulse, and flow particles. Render loop is
// driven by main.js which calls render(state, dt).

import {
  allCells, axialToPixel, pixelToAxial, hexCorners, inBounds,
  HEX_SIZE, key,
} from "../sim/grid.js";
import { NODE_DEFS } from "../sim/nodes.js";
import { nodeAt } from "../sim/resources.js";
import { CORE_ID, edgeKey } from "../sim/graph.js";
import { heatLevel } from "../sim/heat.js";
import { dimensionColor } from "../content/dimensions.js";
import {
  createParticleSystem, spawnMote, updateParticles, drawParticles,
} from "./particles.js";

export function createScene() {
  const voidCanvas = document.getElementById("void");
  const sceneCanvas = document.getElementById("scene");
  const vctx = voidCanvas.getContext("2d");
  const ctx = sceneCanvas.getContext("2d");

  const cam = { x: 0, y: 0, zoom: 1, minZoom: 0.45, maxZoom: 2.4 };
  const ps = createParticleSystem();
  const stars = [];
  let dpr = Math.min(window.devicePixelRatio || 1, 2);
  let W = 0, H = 0;
  let time = 0;
  let spawnAcc = 0;

  const pointer = { x: 0, y: 0, cell: null, active: false };
  const wire = { sourceId: null };
  let lastPulses = 0;
  let dimTint = null; // active dimension color tints the void
  const shockwaves = []; // {t}
  const ripples = []; // {x,y,t,color} node-placement rings
  let shake = 0;       // camera shake magnitude (px), decays each frame
  let lastNextId = 0;  // detect node placement
  let lastMelted = 0;  // detect new melts
  let voidGrad = null; // cached static base gradient

  // Precompute static hex geometry once (avoids ~550 allocations/frame).
  const gridGeo = allCells()
    .filter((c) => !(c.q === 0 && c.r === 0))
    .map((c) => {
      const { x, y } = axialToPixel(c.q, c.r);
      return { q: c.q, r: c.r, x, y, corners: hexCorners(x, y) };
    });

  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = window.innerWidth;
    H = window.innerHeight;
    for (const c of [voidCanvas, sceneCanvas]) {
      c.width = W * dpr;
      c.height = H * dpr;
    }
    vctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    voidGrad = vctx.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, Math.max(W, H) * 0.75);
    voidGrad.addColorStop(0, "#0a0f20");
    voidGrad.addColorStop(1, "#04050c");
    buildStars();
    drawVoid();
  }

  function buildStars() {
    stars.length = 0;
    const count = Math.min(140, Math.floor((W * H) / 9000));
    for (let i = 0; i < count; i++) {
      stars.push({
        x: Math.random() * W, y: Math.random() * H,
        r: Math.random() * 1.4 + 0.3, tw: Math.random() * Math.PI * 2,
        depth: 0.3 + Math.random() * 0.7,
      });
    }
  }

  function drawVoid() {
    vctx.fillStyle = voidGrad || "#06080f";
    vctx.fillRect(0, 0, W, H);

    // Dimension ambiance: a soft colored haze drifting across the void.
    if (dimTint) {
      const cx = W / 2 + Math.sin(time * 0.18) * W * 0.12;
      const cy = H * 0.42 + Math.cos(time * 0.14) * H * 0.1;
      const dg = vctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(W, H) * 0.55);
      dg.addColorStop(0, hexToRgba(dimTint, 0.16));
      dg.addColorStop(1, hexToRgba(dimTint, 0));
      vctx.fillStyle = dg;
      vctx.fillRect(0, 0, W, H);
    }
    for (const s of stars) {
      const a = 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(time * 1.5 * s.depth + s.tw));
      vctx.globalAlpha = a * s.depth;
      vctx.fillStyle = "#bfe9ff";
      vctx.beginPath();
      vctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
      vctx.fill();
    }
    vctx.globalAlpha = 1;
  }

  // ---- transforms ----
  function screenToWorld(sx, sy) {
    return { x: (sx - W / 2) / cam.zoom - cam.x, y: (sy - H / 2) / cam.zoom - cam.y };
  }
  function screenToCell(sx, sy) {
    const w = screenToWorld(sx, sy);
    return pixelToAxial(w.x, w.y);
  }
  function setPointer(sx, sy, active) {
    pointer.x = sx; pointer.y = sy; pointer.active = active;
    pointer.cell = active ? screenToCell(sx, sy) : null;
  }
  function setWireSource(id) { wire.sourceId = id; }

  // pixel center of a node id or the Core
  function centerOf(state, id) {
    if (id === CORE_ID) return { x: 0, y: 0 };
    const n = state.nodes.find((nn) => nn.id === id);
    if (!n) return null;
    return axialToPixel(n.q, n.r);
  }

  // ---- main render ----
  function render(state, dt) {
    time += dt;
    dimTint = state.dimension ? dimensionColor(state.dimension) : null;
    drawVoid();

    // camera shake (screen-space), decays smoothly
    shake *= Math.pow(0.001, dt); // ~fast decay
    if (shake < 0.15) shake = 0;
    const sx = shake ? (Math.random() * 2 - 1) * shake : 0;
    const sy = shake ? (Math.random() * 2 - 1) * shake : 0;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    ctx.save();
    ctx.translate(W / 2 + sx, H / 2 + sy);
    ctx.scale(cam.zoom, cam.zoom);
    ctx.translate(cam.x, cam.y);

    drawGridCells(state);
    drawEdges(state);
    drawCore(state);
    spawnFlow(state, dt);
    updateParticles(ps, dt);
    drawParticles(ctx, ps);
    drawNodes(state);
    updateRipples(state, dt);
    drawWireGhost(state);
    drawPlaceGhost(state);
    updateShockwaves(state, dt);

    ctx.restore();

    // Full-screen surge flash (screen space, only while surging — cheap).
    if (state.surge && state.surge.active) {
      const pulse = 0.5 + 0.5 * Math.sin(time * 6);
      const fg = ctx.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.2, W / 2, H / 2, Math.max(W, H) * 0.7);
      fg.addColorStop(0, "rgba(255,209,102,0)");
      fg.addColorStop(1, `rgba(255,170,60,${0.06 + 0.06 * pulse})`);
      ctx.fillStyle = fg;
      ctx.fillRect(0, 0, W, H);
    }
  }

  // Detect node placement + new melts to trigger ripples / shake.
  function updateRipples(state, dt) {
    if (state.nextNodeId > lastNextId) {
      if (lastNextId > 0) {
        const n = state.nodes.find((nn) => nn.id === state.nextNodeId - 1);
        if (n) {
          const { x, y } = axialToPixel(n.q, n.r);
          ripples.push({ x, y, t: 0, color: NODE_DEFS[n.def].accent });
          shake = Math.max(shake, 5);
        }
      }
      lastNextId = state.nextNodeId;
    }
    const melted = state.nodes.reduce((a, n) => a + (n.melted ? 1 : 0), 0);
    if (melted > lastMelted) shake = Math.max(shake, 16);
    lastMelted = melted;

    for (let i = ripples.length - 1; i >= 0; i--) {
      const w = ripples[i];
      w.t += dt;
      const dur = 0.6;
      if (w.t >= dur) { ripples.splice(i, 1); continue; }
      const p = w.t / dur;
      ctx.globalAlpha = (1 - p) * 0.8;
      ctx.strokeStyle = w.color;
      ctx.lineWidth = 3 * (1 - p) + 0.5;
      ctx.beginPath();
      ctx.arc(w.x, w.y, p * HEX_SIZE * 1.8, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
  }

  // Expanding ring from the Core when a Harmonic Surge begins.
  function updateShockwaves(state, dt) {
    if (state.surge && state.surge.pulses > lastPulses) {
      lastPulses = state.surge.pulses;
      shockwaves.push({ t: 0 });
      shake = Math.max(shake, 10);
    }
    for (let i = shockwaves.length - 1; i >= 0; i--) {
      const w = shockwaves[i];
      w.t += dt;
      const dur = 1.1;
      if (w.t >= dur) { shockwaves.splice(i, 1); continue; }
      const p = w.t / dur;
      const radius = p * HEX_SIZE * 9;
      ctx.globalAlpha = (1 - p) * 0.8;
      ctx.strokeStyle = "#ffd166";
      ctx.lineWidth = 4 * (1 - p) + 1;
      ctx.beginPath();
      ctx.arc(0, 0, radius, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
  }

  function drawGridCells(state) {
    const hq = pointer.cell ? pointer.cell.q : null;
    const hr = pointer.cell ? pointer.cell.r : null;
    // Base cells (one batched path) — cheap fill + stroke for all non-hovered.
    ctx.beginPath();
    for (const c of gridGeo) {
      if (c.q === hq && c.r === hr) continue;
      const cs = c.corners;
      ctx.moveTo(cs[0].x, cs[0].y);
      for (let i = 1; i < 6; i++) ctx.lineTo(cs[i].x, cs[i].y);
      ctx.closePath();
    }
    ctx.fillStyle = "rgba(255,255,255,0.018)";
    ctx.fill();
    ctx.lineWidth = 1;
    ctx.strokeStyle = "rgba(90,130,210,0.16)";
    ctx.stroke();

    // Hovered cell highlighted separately.
    if (hq !== null) {
      const c = gridGeo.find((g) => g.q === hq && g.r === hr);
      if (c) {
        const cs = c.corners;
        ctx.beginPath();
        ctx.moveTo(cs[0].x, cs[0].y);
        for (let i = 1; i < 6; i++) ctx.lineTo(cs[i].x, cs[i].y);
        ctx.closePath();
        ctx.fillStyle = "rgba(54,230,255,0.1)";
        ctx.fill();
        ctx.lineWidth = 1.5;
        ctx.strokeStyle = "rgba(54,230,255,0.55)";
        ctx.stroke();
      }
    }
  }

  function drawCore(state) {
    const pulse = 0.5 + 0.5 * Math.sin(time * 2.2);
    const epsGlow = Math.min(1, Math.log10(state.eps + 1) / 4);
    const r = HEX_SIZE * (0.5 + 0.12 * pulse) * (1 + epsGlow * 0.5);
    const g = ctx.createRadialGradient(0, 0, 0, 0, 0, r * 2.6);
    g.addColorStop(0, "rgba(255,255,255,0.95)");
    g.addColorStop(0.22, "rgba(54,230,255,0.8)");
    g.addColorStop(0.6, "rgba(120,80,255,0.28)");
    g.addColorStop(1, "rgba(120,80,255,0)");
    const prevOp = ctx.globalCompositeOperation;
    ctx.globalCompositeOperation = "lighter";
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(0, 0, r * 2.6, 0, Math.PI * 2);
    ctx.fill();
    ctx.globalCompositeOperation = prevOp;

    // slow rotating energy arcs
    const arcR = HEX_SIZE * (0.95 + 0.12 * pulse);
    ctx.strokeStyle = `rgba(120,220,255,${0.25 + 0.2 * pulse})`;
    ctx.lineWidth = 2;
    for (let i = 0; i < 3; i++) {
      const a0 = time * 0.8 + (i * Math.PI * 2) / 3;
      ctx.beginPath();
      ctx.arc(0, 0, arcR, a0, a0 + 1.1);
      ctx.stroke();
    }
    ctx.fillStyle = "rgba(255,255,255,0.92)";
    ctx.beginPath();
    ctx.arc(0, 0, r * 0.45, 0, Math.PI * 2);
    ctx.fill();
    // ring indicating the Core accepts links
    ctx.strokeStyle = "rgba(255,255,255,0.18)";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(0, 0, HEX_SIZE * 0.78, 0, Math.PI * 2);
    ctx.stroke();
  }

  function drawEdges(state) {
    const flow = state.flow;
    for (const e of state.edges) {
      const a = centerOf(state, e.from);
      const b = centerOf(state, e.to);
      if (!a || !b) continue;
      const k = edgeKey(e.from, e.to);
      const util = flow && flow.edgeUtil.has(k) ? flow.edgeUtil.get(k) : 0;
      const isBottleneck = flow && flow.bottleneck === k && flow.bottleneckUtil > 0.85;

      // color: teal (idle) -> gold -> red as utilization rises (heat)
      const col = heatColor(util);
      ctx.lineWidth = 3 / 1;
      ctx.strokeStyle = col;
      ctx.globalAlpha = 0.85;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();

      if (isBottleneck) {
        const p = 0.5 + 0.5 * Math.sin(time * 8);
        ctx.globalAlpha = 0.4 + 0.5 * p;
        ctx.strokeStyle = "#ff3b4e";
        ctx.lineWidth = 6;
        ctx.beginPath();
        ctx.moveTo(a.x, a.y);
        ctx.lineTo(b.x, b.y);
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
      drawArrow(a, b, col);
    }
  }

  function drawArrow(a, b, col) {
    const ang = Math.atan2(b.y - a.y, b.x - a.x);
    // place arrowhead a bit before the target node
    const back = HEX_SIZE * 0.9;
    const hx = b.x - Math.cos(ang) * back;
    const hy = b.y - Math.sin(ang) * back;
    const s = 7;
    ctx.fillStyle = col;
    ctx.beginPath();
    ctx.moveTo(hx, hy);
    ctx.lineTo(hx - Math.cos(ang - 0.5) * s, hy - Math.sin(ang - 0.5) * s);
    ctx.lineTo(hx - Math.cos(ang + 0.5) * s, hy - Math.sin(ang + 0.5) * s);
    ctx.closePath();
    ctx.fill();
  }

  function drawNodes(state) {
    const hoverCell = pointer.cell;
    for (const n of state.nodes) {
      const def = NODE_DEFS[n.def];
      const { x, y } = axialToPixel(n.q, n.r);
      const isWireSrc = wire.sourceId === n.id;
      const isHover = hoverCell && hoverCell.q === n.q && hoverCell.r === n.r;
      drawNodeBody(x, y, def, 1, n, isWireSrc || (state.selectedTool === "wire" && isHover));
    }
  }

  function drawNodeBody(x, y, def, alpha, inst, highlight) {
    const corners = hexCorners(x, y, HEX_SIZE * 0.78);
    ctx.globalAlpha = alpha;

    const melted = inst && inst.melted;
    const heat = inst ? heatLevel(inst) : 0;
    const util = inst && inst._util ? inst._util : 0;

    // Melted nodes: render as a dark, cracked husk with a pulsing danger ring.
    if (melted) {
      const pulse = 0.5 + 0.5 * Math.sin(time * 6);
      const glow = ctx.createRadialGradient(x, y, 0, x, y, HEX_SIZE * 1.4);
      glow.addColorStop(0, `rgba(255,80,40,${0.35 + 0.25 * pulse})`);
      glow.addColorStop(1, "rgba(255,80,40,0)");
      ctx.fillStyle = glow;
      ctx.beginPath(); ctx.arc(x, y, HEX_SIZE * 1.4, 0, Math.PI * 2); ctx.fill();

      ctx.beginPath();
      corners.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
      ctx.closePath();
      ctx.fillStyle = "rgba(20,8,8,0.95)";
      ctx.fill();
      ctx.setLineDash([5, 4]);
      ctx.lineWidth = 2;
      ctx.strokeStyle = `rgba(255,80,60,${0.6 + 0.4 * pulse})`;
      ctx.stroke();
      ctx.setLineDash([]);
      // cracks
      ctx.strokeStyle = "rgba(255,120,90,0.7)";
      ctx.lineWidth = 1.3;
      ctx.beginPath();
      ctx.moveTo(x - HEX_SIZE * 0.3, y - HEX_SIZE * 0.35);
      ctx.lineTo(x + HEX_SIZE * 0.05, y);
      ctx.lineTo(x - HEX_SIZE * 0.15, y + HEX_SIZE * 0.35);
      ctx.moveTo(x + HEX_SIZE * 0.05, y);
      ctx.lineTo(x + HEX_SIZE * 0.35, y + HEX_SIZE * 0.1);
      ctx.stroke();
      ctx.globalAlpha = 1;
      return;
    }

    // heat: blend accent toward warm based on heat first, then utilization
    const warm = Math.max(heat, util * 0.6);
    const glowCol = warm > 0.05 ? heatColor(warm) : def.accent;

    const glow = ctx.createRadialGradient(x, y, 0, x, y, HEX_SIZE * 1.3);
    glow.addColorStop(0, hexToRgba(glowCol, 0.5 + warm * 0.3));
    glow.addColorStop(1, hexToRgba(glowCol, 0));
    ctx.fillStyle = glow;
    ctx.beginPath();
    ctx.arc(x, y, HEX_SIZE * 1.3, 0, Math.PI * 2);
    ctx.fill();

    ctx.beginPath();
    corners.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
    ctx.closePath();
    ctx.fillStyle = "rgba(10,14,28,0.92)";
    ctx.fill();
    ctx.lineWidth = highlight ? 3 : 2;
    ctx.strokeStyle = highlight ? "#ffffff" : def.accent;
    ctx.stroke();

    let coreA = 0.85;
    if (inst && inst._throughput) coreA = Math.min(1, 0.5 + Math.log10(inst._throughput + 1) / 3);
    ctx.fillStyle = hexToRgba(def.accent, coreA);
    ctx.beginPath();
    ctx.arc(x, y, HEX_SIZE * 0.22, 0, Math.PI * 2);
    ctx.fill();

    // danger arc when a node is getting hot (telegraph before it melts)
    if (heat > 0.45) {
      const p = 0.5 + 0.5 * Math.sin(time * 5);
      ctx.strokeStyle = `rgba(255,${Math.round(140 - heat * 90)},60,${0.5 + 0.4 * p})`;
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.arc(x, y, HEX_SIZE * 0.92, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * heat);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
  }

  function drawPlaceGhost(state) {
    if (!pointer.active || state.selectedTool !== "place" || !pointer.cell) return;
    const { q, r } = pointer.cell;
    const isCore = q === 0 && r === 0;
    const valid = !isCore && inBounds(q, r) && !nodeAt(state, q, r);
    const { x, y } = axialToPixel(q, r);
    const def = NODE_DEFS[state.selectedNode];
    if (!def) return;
    if (valid) drawNodeBody(x, y, def, 0.45, null, false);
    else {
      const corners = hexCorners(x, y, HEX_SIZE * 0.78);
      ctx.beginPath();
      corners.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
      ctx.closePath();
      ctx.strokeStyle = "rgba(255,93,108,0.7)";
      ctx.lineWidth = 2;
      ctx.stroke();
    }
  }

  function drawWireGhost(state) {
    if (state.selectedTool !== "wire" || wire.sourceId == null) return;
    const a = centerOf(state, wire.sourceId);
    if (!a) return;
    const w = screenToWorld(pointer.x, pointer.y);
    ctx.strokeStyle = "rgba(255,255,255,0.55)";
    ctx.setLineDash([6, 6]);
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.lineTo(w.x, w.y);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  // Emit motes along edges (proportional to flow) + unwired siphons toward Core.
  function spawnFlow(state, dt) {
    spawnAcc += dt;
    const interval = 0.07;
    if (spawnAcc < interval) return;
    spawnAcc = 0;
    const flow = state.flow;

    if (flow) {
      for (const e of state.edges) {
        const k = edgeKey(e.from, e.to);
        const f = flow.edgeFlow.get(k) || 0;
        if (f <= 0) continue;
        if (Math.random() > Math.min(1, f / 8)) continue;
        const a = centerOf(state, e.from);
        const b = centerOf(state, e.to);
        if (!a || !b) continue;
        const src = state.nodes.find((nn) => nn.id === e.from);
        const col = src ? NODE_DEFS[src.def].accent : "#36e6ff";
        spawnMote(ps, a.x, a.y, b.x, b.y, col);
      }
    }

    // unwired siphons drip toward core (melted ones produce nothing — no motes)
    for (const n of state.nodes) {
      if (n.melted) continue;
      const def = NODE_DEFS[n.def];
      if (def.role !== "producer") continue;
      const wired = state.edges.some((e) => e.from === n.id);
      if (wired) continue;
      if (Math.random() > 0.5) continue;
      const { x, y } = axialToPixel(n.q, n.r);
      spawnMote(ps, x, y, 0, 0, def.accent);
    }
  }

  // ---- camera ----
  function pan(dx, dy) { cam.x += dx / cam.zoom; cam.y += dy / cam.zoom; }
  function zoomAt(sx, sy, factor) {
    const before = screenToWorld(sx, sy);
    cam.zoom = Math.max(cam.minZoom, Math.min(cam.maxZoom, cam.zoom * factor));
    const after = screenToWorld(sx, sy);
    cam.x += after.x - before.x;
    cam.y += after.y - before.y;
  }

  window.addEventListener("resize", resize);
  resize();

  return {
    render, resize, setPointer, setWireSource, screenToCell,
    pan, zoomAt, cam, particles: ps,
  };
}

// Utilization -> heat color (teal -> gold -> red).
function heatColor(u) {
  u = Math.max(0, Math.min(1.2, u));
  if (u < 0.5) {
    const t = u / 0.5; // teal -> gold
    return lerpColor([54, 230, 255], [255, 209, 102], t);
  }
  const t = Math.min(1, (u - 0.5) / 0.5); // gold -> red
  return lerpColor([255, 209, 102], [255, 59, 78], t);
}
function lerpColor(a, b, t) {
  const r = Math.round(a[0] + (b[0] - a[0]) * t);
  const g = Math.round(a[1] + (b[1] - a[1]) * t);
  const bl = Math.round(a[2] + (b[2] - a[2]) * t);
  return `rgb(${r},${g},${bl})`;
}
function hexToRgba(hex, a) {
  if (hex.startsWith("rgb")) return hex.replace("rgb(", "rgba(").replace(")", `,${a})`);
  const h = hex.replace("#", "");
  const r = parseInt(h.substring(0, 2), 16);
  const g = parseInt(h.substring(2, 4), 16);
  const b = parseInt(h.substring(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}
