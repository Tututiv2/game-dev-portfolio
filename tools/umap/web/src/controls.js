import * as THREE from 'three';

/* Orbit + pan + zoom, and picking.
 *
 * Picking a point cloud has no raycaster worth using: three's raycaster on a
 * Points with 6,375 vertices tests every one against a threshold in world
 * space, which means the hit radius changes with distance and you cannot hit a
 * far node at all. So the pick is done in screen space -- project everything
 * once per moved frame, take the nearest within a pixel radius, break ties by
 * depth. It also gives the tooltip the exact screen position for free.
 */
export class Controls {
  constructor(scene, dom) {
    this.s = scene;
    this.dom = dom;
    this.mouse = { x: -1e5, y: -1e5, moved: false, down: false, dragged: false };
    this.hover = null;
    this.onPick = () => {};
    this.onClick = () => {};
    this.enabled = true;

    this._proj = new Float32Array(scene.nodes.length * 3);
    this._mvp = new THREE.Matrix4();
    this._v = new THREE.Vector3();

    dom.addEventListener('pointerdown', e => this.down(e));
    addEventListener('pointerup', e => this.up(e));
    addEventListener('pointermove', e => this.move(e));
    dom.addEventListener('wheel', e => this.wheel(e), { passive: false });
    dom.addEventListener('contextmenu', e => e.preventDefault());
    // Selection rides on pointerup. If an input path delivers a plain `click`
    // without pointer events -- some remote-input and synthetic setups do
    // exactly that, which is how this was noticed -- the map would look dead
    // to every click. This costs nothing and removes that silent failure.
    dom.addEventListener('click', e => {
      if (performance.now() - (this.lastUp || 0) < 400) return;   // already handled
      this.mouse.x = e.clientX;
      this.mouse.y = e.clientY;
      this.mouse.moved = true;
      this.tick();
      this.onClick(this.hover, e);
    });
    dom.addEventListener('pointerleave', () => { this.mouse.x = -1e5; this.hover = null; this.onPick(null); });
  }

  down(e) {
    this.mouse.down = true;
    this.mouse.dragged = false;
    this.last = { x: e.clientX, y: e.clientY, btn: e.button, shift: e.shiftKey };
    this.dom.setPointerCapture?.(e.pointerId);
  }

  up(e) {
    if (this.mouse.down && !this.mouse.dragged) {
      this.lastUp = performance.now();
      this.onClick(this.hover, e);
    }
    this.mouse.down = false;
  }

  move(e) {
    this.mouse.x = e.clientX;
    this.mouse.y = e.clientY;
    this.mouse.moved = true;
    if (!this.mouse.down || !this.last) return;
    const dx = e.clientX - this.last.x, dy = e.clientY - this.last.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) this.mouse.dragged = true;
    this.last.x = e.clientX; this.last.y = e.clientY;
    const d = this.s.desired;
    if (this.last.btn === 2 || this.last.shift) {
      // pan: move the orbit target in the camera's own plane
      const cam = this.s.camera;
      const right = new THREE.Vector3().setFromMatrixColumn(cam.matrix, 0);
      const up = new THREE.Vector3().setFromMatrixColumn(cam.matrix, 1);
      const k = d.r * 0.0016;
      this.s.desiredTarget.addScaledVector(right, -dx * k).addScaledVector(up, dy * k);
    } else {
      d.theta -= dx * 0.005;
      d.phi = Math.max(0.06, Math.min(Math.PI - 0.06, d.phi - dy * 0.005));
    }
  }

  wheel(e) {
    e.preventDefault();
    const d = this.s.desired;
    d.r = Math.max(6, Math.min(2200, d.r * Math.pow(1.0016, e.deltaY)));
  }

  /** Called once per frame from the render loop. */
  tick() {
    if (!this.enabled) return;
    const s = this.s;
    const w = s.canvas.clientWidth, h = s.canvas.clientHeight;
    this._mvp.multiplyMatrices(s.camera.projectionMatrix, s.camera.matrixWorldInverse);
    const nodes = s.nodes, P = this._proj, v = this._v;
    const blend = s.blend;
    for (let i = 0; i < nodes.length; i++) {
      const nd = nodes[i], a = nd.pos, b = nd.pos2 || nd.pos;
      v.set(a[0] + (b[0] - a[0]) * blend, a[1] + (b[1] - a[1]) * blend,
            a[2] + (b[2] - a[2]) * blend).applyMatrix4(this._mvp);
      P[i * 3] = (v.x * 0.5 + 0.5) * w;
      P[i * 3 + 1] = (-v.y * 0.5 + 0.5) * h;
      P[i * 3 + 2] = v.z;
    }
    if (!this.mouse.moved || this.mouse.x < -1000) return;
    this.mouse.moved = false;

    const mx = this.mouse.x, my = this.mouse.y;
    const R = 13;
    let best = -1, bestScore = Infinity;
    const flaggedMode = s.flaggedMode, filterOn = s.uniforms.uFilterOn.value;
    for (let i = 0; i < nodes.length; i++) {
      const z = P[i * 3 + 2];
      if (z < -1 || z > 1) continue;
      const dx = P[i * 3] - mx, dy = P[i * 3 + 1] - my;
      if (dx < -R || dx > R || dy < -R || dy > R) continue;
      const nd = nodes[i];
      if (nd.flagged && flaggedMode === 0) continue;
      if (filterOn && !s.filtArr[i]) continue;
      const d2 = dx * dx + dy * dy;
      if (d2 > R * R) continue;
      const score = d2 + z * 40;      // nearer to the cursor, then nearer to the eye
      if (score < bestScore) { bestScore = score; best = i; }
    }
    const pick = best >= 0 ? best : null;
    if (pick !== this.hover) {
      this.hover = pick;
      this.onPick(pick, pick != null ? { x: P[pick * 3], y: P[pick * 3 + 1] } : null);
    }
  }

  screenOf(i) {
    return { x: this._proj[i * 3], y: this._proj[i * 3 + 1], z: this._proj[i * 3 + 2] };
  }
}
