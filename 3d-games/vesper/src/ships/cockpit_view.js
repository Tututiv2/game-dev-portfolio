/* ============================================================================
   COCKPIT — THE RENDERING HALF. This file may import three; it may NOT decide
   anything. Every dimension it draws arrives in `spec.parts` from cockpit.js,
   already in eye-local metres, already checked against the view cone by
   `clearHalfDeg()`. If this file invented a box, that check would stop being
   binding on what is on screen, which is the whole reason the split exists.

   WHERE IT GOES: view.nearScene. view.js renders the frame in slices split at
   the geometric mean of [0.02 m, camera.near], so a canopy 1 m from the eye
   draws over a 310 km planet with no shader change. Do not touch camera.near
   and do not add a logarithmic depth buffer — planetShader.js is hand-written
   GLSL and would need three's logdepth chunks injected by hand.

   THE ONE RULE THIS GEOMETRY OBEYS: a frame, never a windscreen. Anything
   within a unit of the eye fills the screen (PYRE measured a 14 cm collar at 64
   degrees), so the parts hug the aperture edge and the middle of the view is
   empty by construction.

   MOUNTING IS NOT VISIBILITY. view.js enables the two near passes when
   `nearScene.children.length > 0`, so leaving a hidden group in there costs two
   render passes a frame for nothing. `unmount()` removes it; `setSpec(null)`
   does that for you when the pilot leaves the seat.

   LIT WITHOUT A LIGHT. nearScene has no lights and adding one would spill onto
   anything else a ship system puts in there, so shading is BAKED into vertex
   colours off a fixed key direction and drawn with MeshBasicMaterial. The frame
   is dark on purpose: it reads as a silhouette against a lit planet, which is
   the art direction ("bold readable shapes"), and the only bright things are the
   strips, authored ABOVE the bloom threshold (0.95 linear) so they actually
   glow rather than merely being pale.

   USAGE
     const cv = makeCockpitView(view);
     cv.setSpec(canopySpec(ship, api));            // null when unseated
     cv.sync(worldPose(ship, api), view.camera);   // every frame, before render
   ========================================================================= */

import * as T from 'three';

/* A fixed key direction in EYE-LOCAL space, so the frame has form. Slightly up,
   slightly right, slightly ahead — the same convention as the flat renderers in
   this portfolio: one key, one fill, no light objects. */
const KEY = new T.Vector3(0.42, 0.80, 0.43).normalize();

const TINTS = {
  //            base colour        key    ambient
  shell: { rgb: [0.052, 0.055, 0.068], key: 0.55, amb: 0.30 },
  hull:  { rgb: [0.032, 0.034, 0.042], key: 0.40, amb: 0.22 },
  glow:  { rgb: [0.90, 0.42, 1.55],    key: 0.00, amb: 1.00 },
};

/** A box with its shading baked into vertex colours. BoxGeometry's normals are
    per-face already, so this is one dot product per vertex and no lights. */
function shadedBox(part) {
  const g = new T.BoxGeometry(part.h[0] * 2, part.h[1] * 2, part.h[2] * 2);
  const t = TINTS[part.tint] || TINTS.shell;
  const n = g.attributes.normal, count = n.count;
  const col = new Float32Array(count * 3);
  for (let i = 0; i < count; i++) {
    const d = Math.max(0, n.getX(i) * KEY.x + n.getY(i) * KEY.y + n.getZ(i) * KEY.z);
    const f = t.amb + t.key * d;
    col[i * 3] = t.rgb[0] * f; col[i * 3 + 1] = t.rgb[1] * f; col[i * 3 + 2] = t.rgb[2] * f;
  }
  g.setAttribute('color', new T.BufferAttribute(col, 3));
  g.translate(part.c[0], part.c[1], part.c[2]);
  return g;
}

export function makeCockpitView(view, opts = {}) {
  const group = new T.Group();
  group.name = 'cockpit';
  group.frustumCulled = false;       // it is always around the camera
  group.renderOrder = 10;

  // Two materials for the whole canopy: one opaque, one emissive. The emissive
  // one is authored above the bloom threshold so UnrealBloomPass picks it up.
  const matShell = new T.MeshBasicMaterial({ vertexColors: true, toneMapped: false });
  const matGlow = new T.MeshBasicMaterial({ vertexColors: true, toneMapped: false });
  const meshes = [];
  let mounted = false, spec = null;

  const clear = () => {
    for (const m of meshes) { group.remove(m); m.geometry.dispose(); }
    meshes.length = 0;
  };

  function mount() {
    if (!mounted && view && view.nearScene) { view.nearScene.add(group); mounted = true; }
  }
  function unmount() {
    if (mounted && view && view.nearScene) { view.nearScene.remove(group); mounted = false; }
  }

  /** Hand it a canopySpec(), or null when there is no pilot in the seat. */
  function setSpec(s) {
    spec = s;
    clear();
    if (!s) { unmount(); return; }
    // A per-ship glow tint, from the pure layer's seeded roll — never rolled
    // here, or it would not survive a reload.
    const hue = 0.72 + 0.18 * (s.panel || 0);
    for (const p of s.parts) {
      const glow = p.glow > 0;
      const g = shadedBox(p);
      if (glow) {
        const c = g.attributes.color;
        for (let i = 0; i < c.count; i++)
          c.setXYZ(i, c.getX(i) * (2.0 - hue), c.getY(i) * hue, c.getZ(i) * (0.6 + hue));
      }
      const m = new T.Mesh(g, glow ? matGlow : matShell);
      m.frustumCulled = false;
      m.name = p.id;
      group.add(m);
      meshes.push(m);
    }
    mount();
  }

  /* The canopy is bolted to the SHIP, so it is oriented by the SEAT basis and
     never by where the pilot is looking — turn your head and the frame stays
     put, which is the difference between a cockpit and a helmet.

     Position comes from the camera when one is handed in: the pilot's eye IS
     the camera, and the game has already placed that correctly in world space.
     api.toWorld() is a rotation only, so a pose's position cannot be converted
     here without the planet group's translation, which the contract does not
     expose. */
  const M = new T.Matrix4();
  const vx = new T.Vector3(), vy = new T.Vector3(), vz = new T.Vector3();
  function sync(worldPose, camera) {
    if (!spec || !worldPose) return;
    if (camera) group.position.copy(camera.position);
    else group.position.set(worldPose.pos[0], worldPose.pos[1], worldPose.pos[2]);
    const r = worldPose.seatRight || worldPose.right;
    const u = worldPose.seatUp || worldPose.up;
    const f = worldPose.seatFwd || worldPose.fwd;
    vx.set(r[0], r[1], r[2]);
    vy.set(u[0], u[1], u[2]);
    vz.set(-f[0], -f[1], -f[2]);        // three's camera basis: +Z is backward
    M.makeBasis(vx, vy, vz);
    group.quaternion.setFromRotationMatrix(M);
  }

  function dispose() {
    clear(); unmount();
    matShell.dispose(); matGlow.dispose();
  }

  return { group, setSpec, sync, mount, unmount, dispose,
           get spec() { return spec; }, get mounted() { return mounted; } };
}
