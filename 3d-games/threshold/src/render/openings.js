// Door and window leaves — the only moving geometry in the world.
//
// Each is a slab hinged on one vertical edge, driven straight off `opening.openness`. Nothing here
// reads the keyboard: the audio follows this animation and this animation follows the input, which
// is the ordering that keeps a door from being able to click (BUILD_PROMPT §3.6).

import { GLASS } from '../world/materials.js';
import { makeGlassMaterial } from './glass.js';

const MAX_SWING = 1.45; // radians, about 83 degrees

/**
 * Work out the slab's box and hinge from the cells the opening occupies. Doing it from the cells
 * rather than from a hand-written table means an opening placed anywhere, at any size, hangs
 * correctly with no extra data — which is the same reason the acoustics need no authoring.
 */
function geometryOf(opening) {
  let minX = Infinity, minY = Infinity, minZ = Infinity;
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
  for (const [x, y, z] of opening.cells) {
    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
  }
  const nAxis = opening.normal[0] !== 0 ? 0 : (opening.normal[1] !== 0 ? 1 : 2);
  const wAxis = nAxis === 0 ? 2 : 0;             // the horizontal axis across the opening
  const size = [maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1];
  const min = [minX, minY, minZ];

  const width = size[wAxis];
  const height = size[1];
  const thickness = 0.11;

  // Hinge on the low end of the width axis, at the centre of the wall's thickness.
  const hinge = [min[0], min[1], min[2]];
  hinge[nAxis] += 0.5;
  hinge[1] = min[1];
  hinge[wAxis] = min[wAxis];

  return { width, height, thickness, hinge, nAxis, wAxis };
}

export class OpeningView {
  constructor(THREE, opening, glassMaterial) {
    this.THREE = THREE;
    this.opening = opening;
    const g = geometryOf(opening);
    this.geo = g;

    this.pivot = new THREE.Group();
    this.pivot.position.set(g.hinge[0], g.hinge[1], g.hinge[2]);

    const size = [0, 0, 0];
    size[g.wAxis] = g.width;
    size[1] = g.height;
    size[g.nAxis] = g.thickness;

    const isGlass = opening.material === GLASS;
    const box = new THREE.BoxGeometry(size[0], size[1], size[2]);
    const mat = isGlass ? glassMaterial : new THREE.MeshStandardMaterial({
      color: 0x5b3f27, roughness: 0.8,
    });
    this.mesh = new THREE.Mesh(box, mat);
    this.mesh.castShadow = !isGlass;
    this.mesh.receiveShadow = true;

    // Offset so the slab hangs off the hinge rather than being centred on it.
    const off = [0, 0, 0];
    off[g.wAxis] = g.width / 2;
    off[1] = g.height / 2;
    this.mesh.position.set(off[0], off[1], off[2]);
    this.pivot.add(this.mesh);

    if (!isGlass) {
      // A latch, so a door reads as a door at a glance.
      const knob = new THREE.Mesh(
        new THREE.SphereGeometry(0.055, 10, 8),
        new THREE.MeshStandardMaterial({ color: 0x9a7b3f, roughness: 0.4, metalness: 0.7 }));
      const kp = [0, 0, 0];
      kp[g.wAxis] = g.width - 0.16;
      kp[1] = g.height * 0.5;
      kp[g.nAxis] = g.thickness * 0.5 + 0.03;
      knob.position.set(kp[0], kp[1], kp[2]);
      this.pivot.add(knob);
    } else {
      // A frame of FOUR BARS around the pane.
      //
      // The first version of this was one box the size of the opening, sitting behind a slightly
      // smaller pane — which is a wall, not a frame. The window was completely opaque and the
      // storm outside it invisible, in the one spot in the game where looking out of the window is
      // the entire point.
      const bar = new THREE.MeshStandardMaterial({ color: 0x4d3520, roughness: 0.85 });
      const t = g.thickness * 1.5;
      const jamb = 0.09;
      const add = (sw, sh, ow, oh) => {
        const size = [0, 0, 0];
        size[g.wAxis] = sw;
        size[1] = sh;
        size[g.nAxis] = t;
        const m = new THREE.Mesh(new THREE.BoxGeometry(size[0], size[1], size[2]), bar);
        const p = [0, 0, 0];
        p[g.wAxis] = ow;
        p[1] = oh;
        m.position.set(p[0], p[1], p[2]);
        m.castShadow = true;
        this.pivot.add(m);
      };
      add(g.width, jamb, g.width / 2, jamb / 2);                       // sill
      add(g.width, jamb, g.width / 2, g.height - jamb / 2);            // head
      add(jamb, g.height, jamb / 2, g.height / 2);                     // hinge stile
      add(jamb, g.height, g.width - jamb / 2, g.height / 2);           // latch stile
      if (g.width > 1.5) add(jamb * 0.8, g.height, g.width / 2, g.height / 2); // centre mullion

      // The pane sits inside the bars.
      this.mesh.scale.set(
        g.wAxis === 0 ? 0.94 : 1.0, 0.9, g.wAxis === 2 ? 0.94 : 1.0);
      this.mesh.renderOrder = 2;
    }

    // Swing outward, away from the room the normal points out of.
    this.dir = (g.nAxis === 0 ? opening.normal[0] : opening.normal[2]) > 0 ? -1 : 1;
    if (g.nAxis === 2) this.dir *= -1;
    this.update();
  }

  update() {
    this.pivot.rotation.y = this.dir * this.opening.openness * MAX_SWING;
  }
}

export function buildOpeningViews(THREE, grid) {
  const group = new THREE.Group();
  group.name = 'openings';
  const glass = makeGlassMaterial(THREE);
  const views = grid.openingList.map(op => {
    const v = new OpeningView(THREE, op, glass);
    group.add(v.pivot);
    return v;
  });
  return { group, views, glass };
}
