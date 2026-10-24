// THE STAR — the render half of sim/events/star.js.
//
// Two events share this file because they share a sun: `starflare` and
// `stardim` are exported as separate FX modules (and together as FX) so the
// integrator can register each against its own id.
//
// Everything here hangs off the REAL sun. render.js builds the day in the plane
// of where you landed and parks a DirectionalLight at that direction times 900;
// we find that light in the scene and read its position rather than guessing an
// angle, so the flare always comes out of the thing casting the shadows. If the
// light is ever renamed away, ctx.sunDir is honoured, and failing that the
// player's own up -- but the light is the truth and the other two are ballast.
//
// The whole-planet look is done with two full-frame quads rather than by
// writing to the shared lights, because an FX hook may run either side of
// renderer.render and the day/night block in render.js rewrites those
// intensities from absolute values every frame -- so anything we set there
// would be silently undone. A quad in the scene cannot be overwritten by
// arithmetic somewhere else.

const BURN_TINT = [1.0, 0.86, 0.58];
const COLD_TINT = [0.30, 0.36, 0.52];       // multiply wash: the world goes cold
const SUN_DIST = 900;                        // render.js parks the light here
const WASH_DIST = 0.6;                       // in front of the near plane

/* ---------------------------------------------------------------- shared -- */

function lcg(seed) {
  let s = seed | 0;
  return () => { s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff; return s / 0x7fffffff; };
}

/** A radial falloff. An additive sphere at flat opacity renders as a disc. */
function glowTexture(ctx) {
  if (typeof ctx.glowTexture === 'function') return ctx.glowTexture();
  const n = 128;
  const cv = document.createElement('canvas');
  cv.width = cv.height = n;
  const g = cv.getContext('2d');
  const grad = g.createRadialGradient(n / 2, n / 2, 0, n / 2, n / 2, n / 2);
  grad.addColorStop(0.00, 'rgba(255,255,255,1)');
  grad.addColorStop(0.18, 'rgba(255,246,214,0.92)');
  grad.addColorStop(0.45, 'rgba(255,170,80,0.35)');
  grad.addColorStop(1.00, 'rgba(255,120,40,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, n, n);
  const tex = new ctx.THREE.CanvasTexture(cv);
  tex.needsUpdate = true;
  return tex;
}

/** The direction of the sun, from the light that is actually lighting the world. */
function sunDir(self, ctx, w, out) {
  if (!self.sunLight) {
    for (const o of ctx.scene.children) {
      if (o.isDirectionalLight) { self.sunLight = o; break; }
    }
  }
  if (self.sunLight && self.sunLight.position.lengthSq() > 1e-6) {
    return out.copy(self.sunLight.position).normalize();
  }
  if (ctx.sunDir) return out.copy(ctx.sunDir).normalize();
  const p = w.player.dir;
  return out.set(p.x, p.y, p.z).normalize();
}

/**
 * Two full-frame quads: one additive (the day gets violently brighter) and one
 * multiplying (the day goes out). They are separate materials because changing
 * a blending mode at runtime recompiles the shader, and a hitch in the middle
 * of a flare is exactly when you would notice one.
 */
function makeWash(ctx) {
  const T = ctx.THREE;
  const geo = new T.PlaneGeometry(1, 1);
  const mk = (blending, colour) => {
    const m = new T.MeshBasicMaterial({
      color: new T.Color(colour), transparent: true, opacity: 0,
      depthTest: false, depthWrite: false, blending, side: T.DoubleSide, fog: false,
    });
    const q = new T.Mesh(geo, m);
    q.frustumCulled = false;
    q.renderOrder = 10000;
    q.visible = false;
    ctx.root.add(q);
    return q;
  };
  return {
    geo,
    burn: mk(T.AdditiveBlending, 0xffffff),
    dark: mk(T.MultiplyBlending, 0xffffff),
    follow(camera, fwd) {
      fwd.set(0, 0, -1).applyQuaternion(camera.quaternion);
      const h = 2 * WASH_DIST * Math.tan((camera.fov * Math.PI) / 360) * 1.7;
      const wq = h * (camera.aspect || 1);
      for (const q of [this.burn, this.dark]) {
        if (!q.visible) continue;
        q.position.copy(camera.position).addScaledVector(fwd, WASH_DIST);
        q.quaternion.copy(camera.quaternion);
        q.scale.set(wq, h, 1);
      }
    },
    dispose() {
      for (const q of [this.burn, this.dark]) {
        if (q.parent) q.parent.remove(q);
        q.material.dispose();
      }
      this.geo.dispose();
    },
  };
}

/** A cloud of specks in the air over the whole planet. Built once, never per frame. */
function makeMotes(ctx, count, seed, colour, size, lift) {
  const T = ctx.THREE;
  const pos = new Float32Array(count * 3);
  const rnd = lcg(seed);
  for (let i = 0; i < count; i++) {
    const u = rnd() * 2 - 1;
    const th = rnd() * Math.PI * 2;
    const s = Math.sqrt(Math.max(0, 1 - u * u));
    const d = { x: s * Math.cos(th), y: u, z: s * Math.sin(th) };
    const r = ctx.P.groundR(d) + 1.5 + rnd() * lift;
    pos[i * 3] = d.x * r; pos[i * 3 + 1] = d.y * r; pos[i * 3 + 2] = d.z * r;
  }
  const geo = new T.BufferGeometry();
  geo.setAttribute('position', new T.BufferAttribute(pos, 3));
  const mat = new T.PointsMaterial({
    color: colour, size, sizeAttenuation: true, transparent: true, opacity: 0,
    depthWrite: false, blending: T.AdditiveBlending, fog: false,
  });
  const pts = new T.Points(geo, mat);
  pts.frustumCulled = false;
  pts.visible = false;
  ctx.root.add(pts);
  return pts;
}

function killMotes(pts) {
  if (pts.parent) pts.parent.remove(pts);
  pts.geometry.dispose();
  pts.material.dispose();
}

const clamp01 = (x) => (x > 1 ? 1 : x < 0 ? 0 : x || 0);

/* ================================================================ FLARE == */

export const flareFx = {
  id: 'starflare',

  build(ctx) {
    const T = ctx.THREE;
    const tex = glowTexture(ctx);

    // the star itself, swollen. A sprite so it faces you from anywhere on the sphere
    const corona = new T.Sprite(new T.SpriteMaterial({
      map: tex, color: 0xffd9a0, transparent: true, opacity: 0,
      blending: T.AdditiveBlending, depthWrite: false, fog: false,
    }));
    corona.renderOrder = 5;
    (ctx.sky || ctx.scene).add(corona);

    // the wave: a shell centred on the star, swelling until it has swallowed the
    // planet. depthTest stays ON so the world occludes the half behind it, which
    // is what makes it read as coming past you rather than being drawn over you.
    const shell = new T.Mesh(
      new T.SphereGeometry(1, 40, 26),
      new T.MeshBasicMaterial({
        color: 0xffb257, transparent: true, opacity: 0, depthWrite: false,
        blending: T.AdditiveBlending, side: T.DoubleSide, fog: false,
      }));
    shell.frustumCulled = false;
    shell.visible = false;
    ctx.root.add(shell);

    // and a second light from exactly where the sun is, so the GROUND burns.
    // Adding a light is safe where writing to render.js's own is not.
    const light = new T.DirectionalLight(0xffd2a0, 0);
    ctx.scene.add(light);

    const cinders = makeMotes(ctx, 520, 4241, 0xff9a4a, 0.85, 16);

    return {
      corona, shell, light, cinders, tex,
      wash: makeWash(ctx),
      sun: new T.Vector3(0, 1, 0),
      fwd: new T.Vector3(),
      sunLight: null,
      spin: 0,
    };
  },

  update(self, ev, w, dt, ctx) {
    const d = ev.data || {};
    const swell = clamp01(d.swell);
    const front = clamp01(d.front);
    const glare = clamp01(d.glare);

    const s = sunDir(self, ctx, w, self.sun);

    self.corona.position.copy(s).multiplyScalar(SUN_DIST);
    const size = 90 + 260 * swell;
    self.corona.scale.set(size, size, 1);
    self.corona.material.opacity = 0.35 + 0.65 * swell;

    // the front, expanding out of the star until the planet is inside it
    const reach = front * (SUN_DIST + 260);
    self.shell.visible = front > 0.001 && front < 0.999;
    if (self.shell.visible) {
      self.shell.position.copy(s).multiplyScalar(SUN_DIST);
      self.shell.scale.setScalar(Math.max(0.001, reach));
      // a shell that keeps its brightness as it grows is a wall, not a wave
      self.shell.material.opacity = 0.55 * (1 - front) * (1 - front) + 0.05;
    }

    self.light.position.copy(s).multiplyScalar(SUN_DIST);
    self.light.intensity = 5.5 * glare;

    self.cinders.visible = glare > 0.01;
    if (self.cinders.visible) {
      self.cinders.material.opacity = 0.75 * glare;
      self.spin += dt * 0.03;
      self.cinders.rotation.y = self.spin;
    }

    self.wash.burn.visible = glare > 0.002;
    self.wash.dark.visible = false;
    if (self.wash.burn.visible) {
      self.wash.burn.material.opacity = 0.62 * glare * glare;
      self.wash.burn.material.color.setRGB(
        BURN_TINT[0], BURN_TINT[1] + (1 - BURN_TINT[1]) * (1 - glare),
        BURN_TINT[2] + (1 - BURN_TINT[2]) * (1 - glare));
    }
    self.wash.follow(ctx.camera, self.fwd);
  },

  dispose(self, ctx) {
    if (self.corona.parent) self.corona.parent.remove(self.corona);
    self.corona.material.dispose();
    if (self.shell.parent) self.shell.parent.remove(self.shell);
    self.shell.geometry.dispose();
    self.shell.material.dispose();
    if (self.light.parent) self.light.parent.remove(self.light);
    killMotes(self.cinders);
    self.wash.dispose();
    if (self.tex && self.tex.dispose) self.tex.dispose();
  },
};

/* =============================================================== DIMMING == */

export const dimFx = {
  id: 'stardim',

  build(ctx) {
    const T = ctx.THREE;
    const tex = glowTexture(ctx);

    const corona = new T.Sprite(new T.SpriteMaterial({
      map: tex, color: 0xffcf9a, transparent: true, opacity: 0.9,
      blending: T.AdditiveBlending, depthWrite: false, fog: false,
    }));
    corona.renderOrder = 5;
    (ctx.sky || ctx.scene).add(corona);

    // What is actually putting the sun out. It sits nearer than the corona and
    // writes depth, so it eats the glow from the inside as it grows -- which is
    // an eclipse the sun is doing to itself.
    const shroud = new T.Mesh(
      new T.SphereGeometry(1, 28, 20),
      new T.MeshBasicMaterial({ color: 0x160a08, fog: false }));
    shroud.frustumCulled = false;
    shroud.visible = false;
    (ctx.sky || ctx.scene).add(shroud);

    const frost = makeMotes(ctx, 420, 9187, 0xbfd9ff, 0.55, 10);

    return {
      corona, shroud, frost, tex,
      wash: makeWash(ctx),
      sun: new T.Vector3(0, 1, 0),
      fwd: new T.Vector3(),
      sunLight: null,
    };
  },

  update(self, ev, w, dt, ctx) {
    const d = ev.data || {};
    const dark = clamp01(d.dark);
    const flash = clamp01(d.flash);

    const s = sunDir(self, ctx, w, self.sun);

    self.corona.position.copy(s).multiplyScalar(SUN_DIST);
    const size = 90 * (1 - 0.55 * dark);
    self.corona.scale.set(size, size, 1);
    self.corona.material.opacity = 0.9 * (1 - 0.7 * dark) + 0.9 * flash;
    // it goes red on the way out, the way anything does
    self.corona.material.color.setRGB(1, 0.81 - 0.42 * dark, 0.60 - 0.48 * dark);

    self.shroud.visible = dark > 0.02;
    if (self.shroud.visible) {
      self.shroud.position.copy(s).multiplyScalar(SUN_DIST - 22);
      self.shroud.scale.setScalar(Math.max(0.001, 46 * dark));
    }

    self.frost.visible = dark > 0.05;
    if (self.frost.visible) self.frost.material.opacity = 0.35 * dark;

    self.wash.dark.visible = dark > 0.002;
    if (self.wash.dark.visible) {
      // multiply blending ignores opacity: fade by walking the colour to white
      self.wash.dark.material.color.setRGB(
        1 - (1 - COLD_TINT[0]) * dark,
        1 - (1 - COLD_TINT[1]) * dark,
        1 - (1 - COLD_TINT[2]) * dark);
    }
    self.wash.burn.visible = flash > 0.002;
    if (self.wash.burn.visible) {
      self.wash.burn.material.opacity = 0.7 * flash;
      self.wash.burn.material.color.setRGB(BURN_TINT[0], BURN_TINT[1], BURN_TINT[2]);
    }
    self.wash.follow(ctx.camera, self.fwd);
  },

  dispose(self, ctx) {
    if (self.corona.parent) self.corona.parent.remove(self.corona);
    self.corona.material.dispose();
    if (self.shroud.parent) self.shroud.parent.remove(self.shroud);
    self.shroud.geometry.dispose();
    self.shroud.material.dispose();
    killMotes(self.frost);
    self.wash.dispose();
    if (self.tex && self.tex.dispose) self.tex.dispose();
  },
};

export const FX = [flareFx, dimFx];
