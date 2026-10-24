import * as THREE from 'three';
import { MapScene } from './scene.js';
import { Controls } from './controls.js';
import { Hud, $ } from './hud.js';
import { Live } from './live.js';

const boot = msg => { const b = document.getElementById('bootmsg'); if (b) b.textContent = msg; };

async function start() {
  boot('reading the map…');
  const graph = await fetch('./data/graph.json').then(r => {
    if (!r.ok) throw new Error('graph.json ' + r.status + ' — run Rebuild.bat');
    return r.json();
  });
  boot(`${graph.nodes.length} nodes · lighting them up`);

  const knn = await fetch('./data/knn.json').then(r => r.ok ? r.json() : {}).catch(() => ({}));

  const canvas = document.getElementById('gl');
  const scene = new MapScene(canvas, graph);
  const controls = new Controls(scene, canvas);

  const state = {
    flagged: 0,            // 0 hidden, 1 dimmed, 2 shown
    followId: null,
    revealT: 0,
    revealing: true,
    highlightProject: null,
    selected: null,
    kin: 0,
    project: null,
  };

  /* ------------------------------------------------------------- camera */
  // Framed from the data, not from a constant. The two layouts are normalised
  // to the same scale now, but a hardcoded distance is exactly the thing that
  // silently stops fitting the moment the fit changes.
  // 98th percentile, not max: one far-flung node should not pull the camera
  // back until the other six thousand are dots.
  function extentOf(key) {
    const r = graph.nodes.map(nd => {
      const p = nd[key] || nd.pos;
      return Math.hypot(p[0], p[1], p[2]);
    }).sort((a, b) => a - b);
    return r[Math.floor(r.length * 0.98)] || 100;
  }
  const extent = { pos: extentOf('pos'), pos2: extentOf('pos2') };
  function frameAll() {
    scene.desiredTarget.set(0, 0, 0);
    const e = extent.pos + (extent.pos2 - extent.pos) * scene.blend;
    scene.desired.r = e * 1.7;
  }
  frameAll();
  scene.spherical.r = scene.desired.r;

  function focusProject(pi) {
    const p = graph.projects[pi];
    const v = scene.projectPos(p);
    scene.flyTo(v, Math.max(60, (scene.blend > 0.5 ? p.spread2 : p.spread) * 3.2));
    hud.showProject(pi);
    state.project = pi;
    if (state.kin === 1) scene.setKinMode(1, pi);
    state.selected = null;
    scene.select(null);
    // light the project's own nodes so the eye lands on the right cluster
    let k = 0;
    for (let i = 0; i < graph.nodes.length && k < 240; i++) {
      if (graph.nodes[i].p === pi) { scene.activate(i, 0.55, null); k++; }
    }
  }

  function focusNode(i) {
    const v = scene.nodePos(i);
    scene.flyTo(v, 46);
    scene.select(i);
    scene.activate(i, 1.2);
    state.selected = i;
    hud.showNode(i, (knn[i] || []).slice(0, 7).map(([j, sim]) => ({ i: j, sim })));
  }

  /* ---------------------------------------------------------------- hud */
  const hud = new Hud(graph, {
    setBlend: v => scene.setBlend(v),
    flaggedMode: () => state.flagged,
    cycleFlagged: () => {
      state.flagged = (state.flagged + 1) % 3;
      scene.setFlaggedMode(state.flagged);
      hud.updateHidden();
      renderLabels(true);
    },
    replay: () => { state.revealT = 0; state.revealing = true; },
    cycleKin: () => {
      state.kin = (state.kin + 1) % 3;
      scene.setKinMode(state.kin, state.project);
      const b = $('#btn-kin');
      b.textContent = ['links off', 'links: selected', 'links: all'][state.kin];
      b.classList.toggle('on', state.kin > 0);
    },
    demo: () => Live.demo({ steps: 30, agents: 2 }),
    focusProject, focusNode,
    highlightProject: pi => { state.highlightProject = pi; },
    follow: id => { state.followId = id; },
    setFilter: (mask) => scene.setFilter(mask),
    clearFilter: () => { scene.setFilter(null); $('#searchhits').classList.remove('open'); },
  });

  /* ------------------------------------------------------------ picking */
  controls.onPick = (i, at) => {
    if (i == null) { hud.tooltip(null); return; }
    hud.tooltip(graph.nodes[i], at, graph.projects[graph.nodes[i].p]);
  };
  controls.onClick = (i) => {
    if (i == null) return;
    focusNode(i);
  };

  /* -------------------------------------------------------------- live */
  const trails = new Map();
  const live = new Live({
    onStatus: ok => hud.connection(ok),
    onHello: snap => {
      for (const a of snap.agents || []) {
        if (a.trail?.length) { trails.set(a.id, a.trail.slice()); scene.setTrail(a.id, a.trail, a.color); }
      }
    },
    onEvent: (ev, replay) => {
      hud.agentEvent(ev, replay);

      // A search has no single file, so it would otherwise be invisible on the
      // map -- but the map already knows which files DEFINE the thing being
      // searched for, so a Grep lights all of them at once. Watching an agent
      // search is the clearest signal of what it is actually looking for.
      if (!replay && ev.node == null && ev.detail &&
          /grep|glob|search/i.test(ev.tool)) {
        const needle = ev.detail.toLowerCase().replace(/[^a-z0-9_]+/g, ' ').trim();
        if (needle.length >= 3) {
          const col = new THREE.Color(ev.color || '#9fe8ff').getHex();
          let lit = 0;
          for (let i = 0; i < graph.nodes.length && lit < 60; i++) {
            const nd = graph.nodes[i];
            if (nd.flagged && state.flagged === 0) continue;
            if ((nd.sym && nd.sym.some(s => s.toLowerCase().includes(needle))) ||
                nd.path.toLowerCase().includes(needle)) {
              scene.activate(i, 0.9, lit < 8 ? col : null);
              lit++;
            }
          }
        }
      }
      if (ev.node == null) return;
      if (!replay) {
        // one ripple, in the agent's own colour -- activate() used to fire a
        // second one in the default blue on top of it
        scene.activate(ev.node, 1.25, new THREE.Color(ev.color || '#9fe8ff').getHex());
      }
      let t = trails.get(ev.agent);
      if (!t) { t = []; trails.set(ev.agent, t); }
      if (t[t.length - 1] !== ev.node) t.push(ev.node);
      if (t.length > 30) t.shift();
      scene.setTrail(ev.agent, t, ev.color || '#9fe8ff');
      if (state.followId === ev.agent) {
        scene.flyTo(scene.nodePos(ev.node), 58);
      }
    },
  });

  /* ------------------------------------------------------------ labels */
  const labelHost = document.getElementById('labels');
  const labels = graph.projects.map((p, i) => {
    const d = document.createElement('div');
    d.className = 'plabel' + (p.flagged === 2 ? ' flagged' : '');
    d.innerHTML = `${p.title}<i>${p.n}</i>`;
    d.addEventListener('click', () => focusProject(i));
    labelHost.append(d);
    return d;
  });
  const lv = new THREE.Vector3();

  // When each project's label has earned the right to exist: the birth rank of
  // its 25th-percentile file. Without this every name in the portfolio is on
  // screen from frame one of the replay, which gives away the ending.
  const labelBirth = graph.projects.map(() => 1);
  {
    const birth = scene.points.geometry.getAttribute('abirth').array;
    const byProj = graph.projects.map(() => []);
    for (let i = 0; i < graph.nodes.length; i++) byProj[graph.nodes[i].p].push(birth[i]);
    byProj.forEach((arr, pi) => {
      if (!arr.length) return;
      arr.sort((a, b) => a - b);
      labelBirth[pi] = arr[Math.floor(arr.length * 0.25)];
    });
  }

  function renderLabels(force) {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    const mvp = new THREE.Matrix4().multiplyMatrices(
      scene.camera.projectionMatrix, scene.camera.matrixWorldInverse);
    const shown = [];
    for (let i = 0; i < graph.projects.length; i++) {
      const p = graph.projects[i], d = labels[i];
      if (state.flagged === 0 && p.flagged === 2) { d.style.opacity = 0; d.style.pointerEvents = 'none'; continue; }
      scene.projectPos(p, lv).applyMatrix4(mvp);
      if (lv.z < -1 || lv.z > 1) { d.style.opacity = 0; d.style.pointerEvents = 'none'; continue; }
      const x = (lv.x * 0.5 + 0.5) * w, y = (-lv.y * 0.5 + 0.5) * h;
      const dist = scene.camera.position.distanceTo(scene.projectPos(p));
      let op = Math.max(0, Math.min(1, 1.25 - dist / 900)) * (p.n > 12 ? 1 : 0.55);
      const born = (state.revealT - labelBirth[i]) / 0.05;
      op *= Math.max(0, Math.min(1, born));
      if (state.highlightProject === i && state.revealT > labelBirth[i]) op = 1;
      shown.push({ d, x, y, op, n: p.n });
    }
    // cheap declutter: a label loses to a bigger one it overlaps
    shown.sort((a, b) => b.n - a.n);
    const taken = [];
    for (const s of shown) {
      let hidden = false;
      for (const t of taken) {
        if (Math.abs(t.x - s.x) < 150 && Math.abs(t.y - s.y) < 22) { hidden = true; break; }
      }
      if (!hidden && s.op > 0.12) taken.push(s);
      s.d.style.transform = `translate(-50%,-50%) translate(${s.x.toFixed(1)}px,${s.y.toFixed(1)}px)`;
      s.d.style.opacity = hidden ? 0 : s.op;
      s.d.style.pointerEvents = hidden || s.op < 0.2 ? 'none' : 'auto';
    }
  }

  /* -------------------------------------------------------------- loop */
  let last = performance.now(), acc = 0, frames = 0, labelTick = 0;
  function frame(now) {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;

    if (state.revealing) {
      state.revealT += dt / 4.2;               // ~4s to grow the whole portfolio
      if (state.revealT >= 1.06) { state.revealT = 1.06; state.revealing = false; }
      scene.uniforms.uReveal.value = state.revealT;
    }

    controls.tick();
    scene.update(dt);
    if (++labelTick % 2 === 0) renderLabels();

    acc += dt; frames++;
    if (acc > 2) {
      const fps = frames / acc;
      if (fps < 40) scene.edgeUniforms.uOpacity.value = 0.14;
      acc = 0; frames = 0;
    }
    requestAnimationFrame(frame);
  }

  requestAnimationFrame(frame);
  setTimeout(() => document.getElementById('boot').classList.add('gone'), 320);

  // expose for the test harness and for the console
  // A backgrounded tab throttles requestAnimationFrame, so every animation in
  // here stops and a screenshot of it is a lie. `step` advances the same code
  // the loop does, deterministically, which is what the test harness drives.
  window.UMAP = { scene, controls, hud, graph, knn, state, focusNode, focusProject, live,
                  frameAll, renderLabels,
                  step(dt = 1 / 60, n = 1) {
                    for (let k = 0; k < n; k++) {
                      if (state.revealing) {
                        state.revealT = Math.min(1.06, state.revealT + dt / 4.2);
                        if (state.revealT >= 1.06) state.revealing = false;
                        scene.uniforms.uReveal.value = state.revealT;
                      }
                      controls.tick();
                      scene.update(dt);
                    }
                    renderLabels();
                    return { reveal: state.revealT, active: scene.activeSet.size };
                  } };
}

start().catch(err => {
  console.error(err);
  boot(String(err.message || err));
  document.getElementById('boot').style.color = '#ff6ec7';
});
