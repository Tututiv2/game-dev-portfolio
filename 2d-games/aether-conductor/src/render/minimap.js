// Corner mini-map: shows the full grid bounds, placed nodes, edges, the Core, and
// the current camera viewport rectangle. Click/drag to recenter the main camera.

import { allCells, axialToPixel, HEX_SIZE, GRID_RADIUS } from "../sim/grid.js";
import { NODE_DEFS } from "../sim/nodes.js";
import { CORE_ID } from "../sim/graph.js";

export function createMinimap(scene) {
  const canvas = document.getElementById("minimap");
  if (!canvas) return { render() {} };
  const ctx = canvas.getContext("2d");
  const SIZE = canvas.width; // square, set in HTML

  // world bounds of the grid (axial extremes -> pixel)
  let bounds = computeBounds();
  function computeBounds() {
    let minX = 0, minY = 0, maxX = 0, maxY = 0;
    for (const c of allCells()) {
      const { x, y } = axialToPixel(c.q, c.r);
      minX = Math.min(minX, x); maxX = Math.max(maxX, x);
      minY = Math.min(minY, y); maxY = Math.max(maxY, y);
    }
    const pad = HEX_SIZE * 1.5;
    return { minX: minX - pad, minY: minY - pad, maxX: maxX + pad, maxY: maxY + pad };
  }

  function worldToMap(wx, wy) {
    const w = bounds.maxX - bounds.minX;
    const h = bounds.maxY - bounds.minY;
    const s = Math.min(SIZE / w, SIZE / h);
    const ox = (SIZE - w * s) / 2;
    const oy = (SIZE - h * s) / 2;
    return { x: ox + (wx - bounds.minX) * s, y: oy + (wy - bounds.minY) * s, s };
  }

  function mapToWorld(mx, my) {
    const w = bounds.maxX - bounds.minX;
    const h = bounds.maxY - bounds.minY;
    const s = Math.min(SIZE / w, SIZE / h);
    const ox = (SIZE - w * s) / 2;
    const oy = (SIZE - h * s) / 2;
    return { x: (mx - ox) / s + bounds.minX, y: (my - oy) / s + bounds.minY };
  }

  function centerOf(state, id) {
    if (id === CORE_ID) return { x: 0, y: 0 };
    const n = state.nodes.find((nn) => nn.id === id);
    return n ? axialToPixel(n.q, n.r) : null;
  }

  // Skip redraw when nothing visible changed (nodes/edges/camera/viewport).
  let lastSig = "";

  function render(state) {
    const cam = scene.cam;
    let sig = cam.x.toFixed(1) + "," + cam.y.toFixed(1) + "," + cam.zoom.toFixed(3) +
      "|" + window.innerWidth + "x" + window.innerHeight +
      "|" + state.nodes.length + "|" + state.nextNodeId;
    for (const e of state.edges) sig += ";" + e.from + ">" + e.to;
    if (sig === lastSig) return;
    lastSig = sig;

    ctx.clearRect(0, 0, SIZE, SIZE);
    ctx.fillStyle = "rgba(6,8,16,0.6)";
    ctx.fillRect(0, 0, SIZE, SIZE);

    // edges
    ctx.strokeStyle = "rgba(120,160,230,0.35)";
    ctx.lineWidth = 1;
    for (const e of state.edges) {
      const a = centerOf(state, e.from);
      const b = centerOf(state, e.to);
      if (!a || !b) continue;
      const ma = worldToMap(a.x, a.y);
      const mb = worldToMap(b.x, b.y);
      ctx.beginPath();
      ctx.moveTo(ma.x, ma.y);
      ctx.lineTo(mb.x, mb.y);
      ctx.stroke();
    }

    // core
    const mc = worldToMap(0, 0);
    ctx.fillStyle = "#ffffff";
    ctx.beginPath();
    ctx.arc(mc.x, mc.y, 2.6, 0, Math.PI * 2);
    ctx.fill();

    // nodes
    for (const n of state.nodes) {
      const { x, y } = axialToPixel(n.q, n.r);
      const m = worldToMap(x, y);
      ctx.fillStyle = NODE_DEFS[n.def].accent;
      ctx.beginPath();
      ctx.arc(m.x, m.y, 2.2, 0, Math.PI * 2);
      ctx.fill();
    }

    // viewport rect (what the main camera currently shows)
    const halfW = window.innerWidth / 2 / cam.zoom;
    const halfH = window.innerHeight / 2 / cam.zoom;
    const cx = -cam.x, cy = -cam.y;
    const tl = worldToMap(cx - halfW, cy - halfH);
    const br = worldToMap(cx + halfW, cy + halfH);
    ctx.strokeStyle = "rgba(54,230,255,0.8)";
    ctx.lineWidth = 1.2;
    ctx.strokeRect(tl.x, tl.y, br.x - tl.x, br.y - tl.y);
  }

  // click to recenter
  let dragging = false;
  function recenter(ev) {
    const rect = canvas.getBoundingClientRect();
    const mx = (ev.clientX - rect.left) * (SIZE / rect.width);
    const my = (ev.clientY - rect.top) * (SIZE / rect.height);
    const w = mapToWorld(mx, my);
    scene.cam.x = -w.x;
    scene.cam.y = -w.y;
  }
  canvas.addEventListener("pointerdown", (e) => { dragging = true; recenter(e); canvas.setPointerCapture(e.pointerId); });
  canvas.addEventListener("pointermove", (e) => { if (dragging) recenter(e); });
  canvas.addEventListener("pointerup", () => { dragging = false; });

  return { render };
}
