import * as THREE from 'three';

/* The whole map is ONE THREE.Points draw call.
 *
 * Every per-node behaviour the map has -- the reveal, the agent pulse, the
 * blend between the two layouts, the FLAGGED dimming, the search filter -- is an
 * attribute or a uniform on that one material. The alternative (a mesh per
 * node, or splitting the cloud into groups per state) is what makes maps like
 * this fall over at a few thousand nodes, and this one has six thousand.
 */

const VERT = /* glsl */`
attribute vec3 bpos;      // second layout
attribute vec3 acolor;
attribute float asize;
attribute float abirth;   // 0..1 position in the reveal order
attribute float aact;     // 0..1 agent activation, decays in JS
attribute float aflag;    // 1 partly flagged, 2 fully flagged, 4 asset dir
attribute float afilt;    // 1 = passes the current filter
attribute float adens;    // density compensation, see buildPoints

uniform float uBlend, uReveal, uTime, uScale, uFlaggedMode, uFilterOn;
uniform vec3 uHot;

varying vec3 vColor;
varying float vAlpha;
varying float vCore;

void main(){
  vec3 p = mix(position, bpos, uBlend);

  float born = uReveal - abirth;
  float appear = smoothstep(0.0, 0.035, born);          // fade in
  float pop = 1.0 + 1.9 * exp(-born * 90.0) * step(0.0, born);
  float flash = exp(-max(born, 0.0) * 26.0) * step(0.0, born);

  float flagged = step(0.5, aflag) * step(aflag, 2.5);
  float hide = flagged * step(uFlaggedMode, 0.5);              // 0 = hidden
  float dimN = flagged * step(0.5, uFlaggedMode) * step(uFlaggedMode, 1.5) * 0.72;

  float filt = mix(1.0, afilt, uFilterOn);
  float dimF = (1.0 - filt) * 0.86;

  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  float dist = -mv.z;
  float size = asize * pop * (1.0 + aact * 2.4);
  gl_Position = projectionMatrix * mv;
  gl_PointSize = clamp(size * uScale / max(dist, 1.0), 1.0, 90.0) * appear * (1.0 - hide);

  vColor = mix(acolor, uHot, min(aact * 0.8 + flash * 0.45, 0.92));
  vColor += flash * 0.35;
  vAlpha = appear * (1.0 - hide) * (1.0 - dimN) * (1.0 - dimF)
         * (0.55 + 0.45 * filt) * adens;
  vCore = aact;
}`;

const FRAG = /* glsl */`
precision highp float;
varying vec3 vColor;
varying float vAlpha;
varying float vCore;

void main(){
  vec2 uv = gl_PointCoord - 0.5;
  float d = length(uv);
  if (d > 0.5) discard;
  float halo = pow(smoothstep(0.5, 0.02, d), 1.7);
  float core = smoothstep(0.16, 0.0, d);
  float a = (halo * 0.46 + core * 0.85) * vAlpha;
  vec3 c = vColor * (0.95 + halo * 0.45) + core * (0.22 + vCore * 0.9);
  gl_FragColor = vec4(c, a);
}`;

const LINE_VERT = /* glsl */`
attribute vec3 bpos;
attribute float abirth;
attribute vec3 acolor;
attribute float aflag;
attribute vec2 alen;      // this edge's length in each layout
uniform float uBlend, uReveal, uFlaggedMode;
varying vec3 vColor;
varying float vAlpha;
void main(){
  vec3 p = mix(position, bpos, uBlend);
  float born = uReveal - abirth;
  float appear = smoothstep(0.0, 0.05, born);
  float flagged = step(0.5, aflag);
  float hide = flagged * step(uFlaggedMode, 0.5);

  // A handful of edges are enormous -- a real semantic link between two files
  // in projects the galaxy layout puts on opposite sides. Drawn at full
  // strength they are laser beams across the whole scene and they read as
  // errors, so length fades them out instead of a cut-off deleting them.
  float len = mix(alen.x, alen.y, uBlend);
  float far = 1.0 - smoothstep(45.0, 190.0, len);

  vColor = acolor;
  vAlpha = appear * (1.0 - hide) * (0.12 + 0.88 * far);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}`;

const LINE_FRAG = /* glsl */`
precision highp float;
varying vec3 vColor; varying float vAlpha;
uniform float uOpacity;
void main(){ gl_FragColor = vec4(vColor, vAlpha * uOpacity); }`;


export class MapScene {
  constructor(canvas, graph) {
    this.graph = graph;
    this.nodes = graph.nodes;
    this.canvas = canvas;

    this.renderer = new THREE.WebGLRenderer({
      canvas, antialias: true, alpha: false, powerPreference: 'high-performance',
    });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    this.renderer.setClearColor(0x05070d, 1);

    this.scene = new THREE.Scene();
    this.scene.fog = new THREE.FogExp2(0x05070d, 0.0016);
    this.camera = new THREE.PerspectiveCamera(55, 1, 0.5, 4000);

    this.target = new THREE.Vector3(0, 0, 0);
    this.spherical = { r: 420, theta: 0.7, phi: 1.15 };
    this.desired = { ...this.spherical };
    this.desiredTarget = this.target.clone();

    this.blend = 0.7;
    this.reveal = 1;
    this.flaggedMode = 0;
    this.filterOn = 0;
    this.clock = new THREE.Clock();

    this.buildStars();
    this.buildPoints();
    this.buildEdges();
    this.buildOverlays();
    this.resize();
    addEventListener('resize', () => this.resize());
  }

  /* ------------------------------------------------------------ geometry */

  buildStars() {
    const n = 1400, pos = new Float32Array(n * 3), col = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      const r = 1200 + Math.random() * 1400;
      const t = Math.random() * Math.PI * 2, p = Math.acos(2 * Math.random() - 1);
      pos[i * 3] = r * Math.sin(p) * Math.cos(t);
      pos[i * 3 + 1] = r * Math.cos(p);
      pos[i * 3 + 2] = r * Math.sin(p) * Math.sin(t);
      const v = 0.28 + Math.random() * 0.5;
      col[i * 3] = v * 0.7; col[i * 3 + 1] = v * 0.82; col[i * 3 + 2] = v;
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setAttribute('color', new THREE.BufferAttribute(col, 3));
    const m = new THREE.PointsMaterial({ size: 2.2, vertexColors: true, sizeAttenuation: false,
      transparent: true, opacity: 0.55, depthWrite: false });
    this.stars = new THREE.Points(g, m);
    this.scene.add(this.stars);
  }

  buildPoints() {
    const nodes = this.nodes, n = nodes.length;
    const pos = new Float32Array(n * 3), bpos = new Float32Array(n * 3);
    const col = new Float32Array(n * 3), size = new Float32Array(n);
    const birth = new Float32Array(n), act = new Float32Array(n);
    const flag = new Float32Array(n), filt = new Float32Array(n);
    const dens = new Float32Array(n);
    const projects = this.graph.projects;
    const c = new THREE.Color();

    // Density compensation. Additive blending sums overlapping sprites, so a
    // project with 2,000 files in one cluster burns to solid white while a
    // 20-file project is a faint speck -- the eye then reads "big project" as
    // "bright blob" and can see nothing inside it. Each node is dimmed by the
    // cube root of its project's packing density relative to the median, which
    // is the exponent that makes the SUM over a cluster roughly constant.
    const density = projects.map(p => {
      const r = Math.max(3, p.spread2 || p.spread || 10);
      return (p.n || 1) / (r * r * r);
    });
    const sorted = density.filter(d => d > 0).sort((a, b) => a - b);
    const medianDensity = sorted[Math.floor(sorted.length / 2)] || 1;

    // Reveal order: oldest file first, so a replay reads as the portfolio
    // growing. Paced by RANK, not by raw timestamp -- mapping the clock
    // linearly gives 240 nodes for the first two thirds and then six thousand
    // in the last second, because most of this tree was touched this month.
    // Rank keeps the order exactly chronological and the pacing watchable.
    const order = nodes.map((nd, i) => [nd.mtime || (projects[nd.p]?.mtime || 0), i])
                       .sort((a, b) => a[0] - b[0]);
    const rank = new Float32Array(n);
    order.forEach(([, i], r) => { rank[i] = r / Math.max(1, n - 1); });

    for (let i = 0; i < n; i++) {
      const nd = nodes[i], p = projects[nd.p];
      pos.set(nd.pos, i * 3);
      bpos.set(nd.pos2 || nd.pos, i * 3);
      c.set(p ? p.color : '#8899aa');
      if (nd.k === 'asset') c.multiplyScalar(0.62);
      if (nd.lang === 'doc') c.lerp(new THREE.Color('#ffffff'), 0.18);
      col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b;
      // Point size is deliberately mean. Additively blended sprites stack, so
      // a size that looks right for one node turns a cluster into a white
      // blob -- the biggest file may only be five times the smallest dot.
      const weight = nd.k === 'asset' ? Math.sqrt(nd.loc || 1) * 2.2 : Math.sqrt(nd.loc || 1);
      size[i] = 2.4 + Math.min(weight * 0.42, 11);
      birth[i] = rank[i];
      flag[i] = nd.flagged === 2 ? 2 : (nd.flagged === 1 ? 1 : 0);
      filt[i] = 1;
      const d = density[nd.p] || medianDensity;
      dens[i] = Math.min(1.15, Math.max(0.4, Math.cbrt(medianDensity / d)));
    }
    this.birth = birth;

    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setAttribute('bpos', new THREE.BufferAttribute(bpos, 3));
    g.setAttribute('acolor', new THREE.BufferAttribute(col, 3));
    g.setAttribute('asize', new THREE.BufferAttribute(size, 1));
    g.setAttribute('abirth', new THREE.BufferAttribute(birth, 1));
    g.setAttribute('aact', new THREE.BufferAttribute(act, 1));
    g.setAttribute('aflag', new THREE.BufferAttribute(flag, 1));
    g.setAttribute('afilt', new THREE.BufferAttribute(filt, 1));
    g.setAttribute('adens', new THREE.BufferAttribute(dens, 1));
    g.computeBoundingSphere();

    this.uniforms = {
      uBlend: { value: this.blend }, uReveal: { value: 1 }, uTime: { value: 0 },
      uScale: { value: 260 }, uFlaggedMode: { value: 0 }, uFilterOn: { value: 0 },
      uHot: { value: new THREE.Color('#ffffff') },
    };
    const m = new THREE.ShaderMaterial({
      vertexShader: VERT, fragmentShader: FRAG, uniforms: this.uniforms,
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    });
    this.points = new THREE.Points(g, m);
    this.points.frustumCulled = false;
    this.scene.add(this.points);
    this.attr = { act: g.getAttribute('aact'), filt: g.getAttribute('afilt') };
    this.actArr = act; this.filtArr = filt;
    this.activeSet = new Set();
  }

  buildEdges() {
    const E = this.graph.file_edges, nodes = this.nodes, projects = this.graph.projects;
    const n = E.length;
    const pos = new Float32Array(n * 6), bpos = new Float32Array(n * 6);
    const col = new Float32Array(n * 6), birth = new Float32Array(n * 2);
    const flag = new Float32Array(n * 2), len = new Float32Array(n * 4);
    const c = new THREE.Color();
    const dist3 = (u, v) => Math.hypot(u[0] - v[0], u[1] - v[1], u[2] - v[2]);
    for (let e = 0; e < n; e++) {
      const [a, b, w] = E[e];
      const na = nodes[a], nb = nodes[b];
      pos.set(na.pos, e * 6); pos.set(nb.pos, e * 6 + 3);
      bpos.set(na.pos2 || na.pos, e * 6); bpos.set(nb.pos2 || nb.pos, e * 6 + 3);
      const l1 = dist3(na.pos, nb.pos);
      const l2 = dist3(na.pos2 || na.pos, nb.pos2 || nb.pos);
      len[e * 4] = l1; len[e * 4 + 1] = l2; len[e * 4 + 2] = l1; len[e * 4 + 3] = l2;
      const cross = na.p !== nb.p;
      for (const [k, nd] of [[0, na], [1, nb]]) {
        const p = projects[nd.p];
        c.set(p ? p.color : '#8899aa');
        c.multiplyScalar(cross ? 0.5 + w * 0.5 : 0.24 + w * 0.3);
        col[e * 6 + k * 3] = c.r; col[e * 6 + k * 3 + 1] = c.g; col[e * 6 + k * 3 + 2] = c.b;
        birth[e * 2 + k] = Math.max(this.birth[a], this.birth[b]);
        flag[e * 2 + k] = (na.flagged || nb.flagged) ? 1 : 0;
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setAttribute('bpos', new THREE.BufferAttribute(bpos, 3));
    g.setAttribute('acolor', new THREE.BufferAttribute(col, 3));
    g.setAttribute('abirth', new THREE.BufferAttribute(birth, 1));
    g.setAttribute('aflag', new THREE.BufferAttribute(flag, 1));
    g.setAttribute('alen', new THREE.BufferAttribute(len, 2));
    this.edgeUniforms = {
      uBlend: this.uniforms.uBlend, uReveal: this.uniforms.uReveal,
      uFlaggedMode: this.uniforms.uFlaggedMode, uOpacity: { value: 0.22 },
    };
    const m = new THREE.ShaderMaterial({
      vertexShader: LINE_VERT, fragmentShader: LINE_FRAG, uniforms: this.edgeUniforms,
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
    });
    this.edges = new THREE.LineSegments(g, m);
    this.edges.frustumCulled = false;
    this.scene.add(this.edges);
  }

  buildOverlays() {
    // selection ring
    const ring = new THREE.RingGeometry(1.0, 1.16, 48);
    this.selRing = new THREE.Mesh(ring, new THREE.MeshBasicMaterial({
      color: 0x9fe8ff, transparent: true, opacity: 0.9, side: THREE.DoubleSide, depthWrite: false }));
    this.selRing.visible = false;
    this.scene.add(this.selRing);

    // agent ripples: a small pool of expanding rings, reused
    this.ripples = [];
    const rg = new THREE.RingGeometry(0.86, 1.0, 40);
    for (let i = 0; i < 26; i++) {
      const mesh = new THREE.Mesh(rg, new THREE.MeshBasicMaterial({
        color: 0xffffff, transparent: true, opacity: 0, side: THREE.DoubleSide,
        depthWrite: false, blending: THREE.AdditiveBlending }));
      mesh.visible = false;
      mesh.userData.age = 99;
      this.scene.add(mesh);
      this.ripples.push(mesh);
    }

    // agent trails: one Line per agent, rebuilt as the trail grows
    this.trails = new Map();
    this.trailGroup = new THREE.Group();
    this.scene.add(this.trailGroup);

    this.buildKin();
  }

  /* Curated project kinship, drawn as arcs.
   *
   * These are the ONLY lines on the map that a person wrote: the file edges
   * are derived from the tokens, but "VESPER derives_from Space Game!" is a
   * fact somebody knew. They bow outward instead of running straight, so they
   * read as a separate layer over the cloud rather than as more of it, and so
   * two arcs between nearby clusters do not lie on top of each other.
   */
  buildKin() {
    const kin = this.graph.kin_edges || [];
    this.kinMode = 0;
    this.kinSelected = null;
    this.kinSegs = 18;
    const verts = kin.length * this.kinSegs * 2 * 3;
    const pos = new Float32Array(verts);
    const col = new Float32Array(verts);
    this.kinFull = new Float32Array(verts);   // pristine arc geometry
    const c1 = new THREE.Color(), c2 = new THREE.Color(), cm = new THREE.Color();
    let w = 0;
    for (const e of kin) {
      const pa = this.graph.projects[e.a], pb = this.graph.projects[e.b];
      c1.set(pa.color); c2.set(pb.color);
      for (let s = 0; s < this.kinSegs; s++) {
        for (const t of [s / this.kinSegs, (s + 1) / this.kinSegs]) {
          cm.copy(c1).lerp(c2, t);
          col[w] = cm.r; col[w + 1] = cm.g; col[w + 2] = cm.b;
          w += 3;
        }
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setAttribute('color', new THREE.BufferAttribute(col, 3));
    this.kinGeo = g;
    this.kinLines = new THREE.LineSegments(g, new THREE.LineBasicMaterial({
      vertexColors: true, transparent: true, opacity: 0.0,
      blending: THREE.AdditiveBlending, depthWrite: false }));
    this.kinLines.frustumCulled = false;
    this.kinLines.visible = false;
    this.scene.add(this.kinLines);
    this.refreshKin();
  }

  /** Recompute arc geometry for the current blend. ~157 arcs, cheap.
   *
   * Writes into `kinFull`, never into the live attribute: hiding an arc works
   * by collapsing it to a point, and doing that in place would destroy the
   * geometry the next "show all" needs. */
  refreshKin() {
    const kin = this.graph.kin_edges || [];
    if (!kin.length || !this.kinGeo) return;
    const pos = { array: this.kinFull };
    const A = new THREE.Vector3(), B = new THREE.Vector3(),
          M = new THREE.Vector3(), P = new THREE.Vector3();
    let w = 0;
    for (const e of kin) {
      this.projectPos(this.graph.projects[e.a], A);
      this.projectPos(this.graph.projects[e.b], B);
      const span = A.distanceTo(B);
      M.copy(A).add(B).multiplyScalar(0.5);
      // bow away from the centre of the map; straight up if it sits on it
      const out = M.lengthSq() > 1e-6 ? M.clone().normalize() : new THREE.Vector3(0, 1, 0);
      M.addScaledVector(out, span * 0.22);
      for (let s = 0; s < this.kinSegs; s++) {
        for (const t of [s / this.kinSegs, (s + 1) / this.kinSegs]) {
          const u = 1 - t;
          P.set(u * u * A.x + 2 * u * t * M.x + t * t * B.x,
                u * u * A.y + 2 * u * t * M.y + t * t * B.y,
                u * u * A.z + 2 * u * t * M.z + t * t * B.z);
          pos.array[w] = P.x; pos.array[w + 1] = P.y; pos.array[w + 2] = P.z;
          w += 3;
        }
      }
    }
    this.applyKinVisibility();
  }

  /** mode: 0 off, 1 only the selected project's, 2 all of them. */
  setKinMode(mode, selectedProject) {
    this.kinMode = mode;
    if (selectedProject !== undefined) this.kinSelected = selectedProject;
    this.applyKinVisibility();
  }

  applyKinVisibility() {
    if (!this.kinLines) return;
    const kin = this.graph.kin_edges || [];
    const on = this.kinMode > 0 && kin.length > 0;
    this.kinLines.visible = on;
    if (!on) return;
    this.kinLines.material.opacity = this.kinMode === 2 ? 0.34 : 0.8;
    // "only the selected project's" is done by collapsing the others to a
    // point, copied fresh out of kinFull each time so nothing is lost
    const pos = this.kinGeo.getAttribute('position');
    const per = this.kinSegs * 2 * 3;
    pos.array.set(this.kinFull);
    if (this.kinMode === 1) {
      const sel = this.kinSelected;
      for (let i = 0; i < kin.length; i++) {
        if (sel != null && (kin[i].a === sel || kin[i].b === sel)) continue;
        const base = i * per;
        const x = pos.array[base], y = pos.array[base + 1], z = pos.array[base + 2];
        for (let k = 0; k < per; k += 3) {
          pos.array[base + k] = x;
          pos.array[base + k + 1] = y;
          pos.array[base + k + 2] = z;
        }
      }
    }
    pos.needsUpdate = true;
  }

  /* -------------------------------------------------------------- state */

  nodePos(i, out = new THREE.Vector3()) {
    const nd = this.nodes[i];
    const a = nd.pos, b = nd.pos2 || nd.pos, t = this.blend;
    return out.set(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
  }

  projectPos(p, out = new THREE.Vector3()) {
    const a = p.pos, b = p.pos2 || p.pos, t = this.blend;
    return out.set(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
  }

  setBlend(v) {
    this.blend = v;
    this.uniforms.uBlend.value = v;
    // the arcs are baked geometry, so they have to follow the layout
    this.refreshKin();
  }
  setFlaggedMode(m) { this.flaggedMode = m; this.uniforms.uFlaggedMode.value = m; }

  setFilter(mask) {
    if (!mask) {
      this.uniforms.uFilterOn.value = 0;
      return;
    }
    this.uniforms.uFilterOn.value = 1;
    for (let i = 0; i < this.filtArr.length; i++) this.filtArr[i] = mask[i] ? 1 : 0;
    this.attr.filt.needsUpdate = true;
  }

  /** Light a node up. Decays back to 0 on its own.
   *  `color` null means no ripple -- the caller only wants the glow. */
  activate(i, strength = 1, color = 0x9fe8ff) {
    if (i == null || i < 0 || i >= this.actArr.length) return;
    this.actArr[i] = Math.min(1.6, this.actArr[i] + strength);
    this.activeSet.add(i);
    this.attr.act.needsUpdate = true;
    if (color !== null) this.ripple(i, color);
  }

  ripple(i, color = 0x9fe8ff) {
    const free = this.ripples.find(r => r.userData.age > 1) || this.ripples[0];
    this.nodePos(i, free.position);
    free.userData.age = 0;
    free.material.color.setHex(color);
    free.visible = true;
  }

  setTrail(agentId, nodeIds, colorHex) {
    let line = this.trails.get(agentId);
    const pts = nodeIds.slice(-24);
    if (pts.length < 2) {
      if (line) line.visible = false;
      return;
    }
    const arr = new Float32Array(pts.length * 3);
    const v = new THREE.Vector3();
    pts.forEach((id, k) => { this.nodePos(id, v).toArray(arr, k * 3); });
    if (!line) {
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.BufferAttribute(arr, 3));
      line = new THREE.Line(g, new THREE.LineBasicMaterial({
        color: new THREE.Color(colorHex), transparent: true, opacity: 0.55,
        blending: THREE.AdditiveBlending, depthWrite: false }));
      line.frustumCulled = false;
      this.trailGroup.add(line);
      this.trails.set(agentId, line);
    } else {
      line.geometry.dispose();
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.BufferAttribute(arr, 3));
      line.geometry = g;
      line.material.color.set(colorHex);
      line.visible = true;
    }
  }

  select(i) {
    this.selected = i;
    this.selRing.visible = i != null;
  }

  flyTo(target, radius = 90) {
    this.desiredTarget.copy(target);
    this.desired.r = Math.max(28, radius);
  }

  /* ---------------------------------------------------------- rendering */

  resize() {
    const w = this.canvas.clientWidth || innerWidth, h = this.canvas.clientHeight || innerHeight;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.uniforms.uScale.value = h * 0.42;
  }

  update(dt) {
    // camera easing
    const s = this.spherical, d = this.desired;
    const k = 1 - Math.pow(0.0015, dt);
    s.r += (d.r - s.r) * k;
    s.theta += (d.theta - s.theta) * k;
    s.phi += (d.phi - s.phi) * k;
    this.target.lerp(this.desiredTarget, k);
    const sp = Math.max(0.05, Math.min(Math.PI - 0.05, s.phi));
    this.camera.position.set(
      this.target.x + s.r * Math.sin(sp) * Math.cos(s.theta),
      this.target.y + s.r * Math.cos(sp),
      this.target.z + s.r * Math.sin(sp) * Math.sin(s.theta));
    this.camera.lookAt(this.target);

    // activation decay
    if (this.activeSet.size) {
      const fall = Math.pow(0.16, dt);
      for (const i of this.activeSet) {
        this.actArr[i] *= fall;
        if (this.actArr[i] < 0.004) { this.actArr[i] = 0; this.activeSet.delete(i); }
      }
      this.attr.act.needsUpdate = true;
    }

    // ripples
    const v = new THREE.Vector3();
    for (const r of this.ripples) {
      if (r.userData.age > 1) { r.visible = false; continue; }
      r.userData.age += dt * 1.1;
      const a = r.userData.age;
      const sc = 3 + a * 19;
      r.scale.setScalar(sc);
      r.material.opacity = Math.max(0, 0.62 * (1 - a) * (1 - a));
      r.lookAt(this.camera.position);
    }

    if (this.selected != null) {
      this.nodePos(this.selected, this.selRing.position);
      const dist = this.camera.position.distanceTo(this.selRing.position);
      this.selRing.scale.setScalar(Math.max(3, dist * 0.022));
      this.selRing.lookAt(this.camera.position);
      this.selRing.material.opacity = 0.55 + 0.35 * Math.sin(performance.now() * 0.005);
    }

    this.stars.rotation.y += dt * 0.004;
    this.uniforms.uTime.value += dt;
    this.renderer.render(this.scene, this.camera);
  }
}
