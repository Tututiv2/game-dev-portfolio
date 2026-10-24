// Uploading the pollution field to the GPU so the ground can be stained by it.
//
// The field lives on cube-sphere cells, which have face seams. Rendering
// through those seams would be a mess, so the texture is NOT cell-aligned:
// it is a tangent-plane window centred on the crawler, sampled from the field.
// The shader converts a fragment's world position into east/north metres and
// reads straight in. No seams, because the window has no seams.

import * as THREE from 'three';

const SIZE = 96;                 // texels per side
export const HALF_EXTENT = 1150; // metres from centre to edge

const _rel = new THREE.Vector3();
const _d = new THREE.Vector3();

export class PollutionMap {
  constructor(pollution) {
    this.pollution = pollution;
    this.data = new Uint8Array(SIZE * SIZE);
    this.texture = new THREE.DataTexture(this.data, SIZE, SIZE, THREE.RedFormat);
    this.texture.minFilter = THREE.LinearFilter;
    this.texture.magFilter = THREE.LinearFilter;
    this.texture.wrapS = THREE.ClampToEdgeWrapping;
    this.texture.wrapT = THREE.ClampToEdgeWrapping;
    this.texture.needsUpdate = true;

    this.origin = new THREE.Vector3();
    this.east = new THREE.Vector3(1, 0, 0);
    this.north = new THREE.Vector3(0, 1, 0);
    this.scale = 1;              // divides pollution into 0..1 for the texture
  }

  // Rebuilt on the pollution tick, not per frame — the field only changes that
  // often, and 9,216 samples is not free.
  rebuild(crawler) {
    const pol = this.pollution;
    this.origin.copy(crawler.worldPos);

    // A tangent frame at the crawler. Any pair will do as long as it is stable
    // frame to frame, so it is built from the crawler's own facing.
    const up = crawler.pos;
    this.north.copy(crawler.fwd).addScaledVector(up, -crawler.fwd.dot(up)).normalize();
    this.east.crossVectors(this.north, up).normalize();

    // Scale so the densest cell maps near the top of the byte range; a fixed
    // scale would either clip early or leave a faint cloud invisible.
    this.scale = Math.max(1.5, pol.peak);

    const inv = 255 / this.scale;
    const step = (HALF_EXTENT * 2) / SIZE;
    const R = this.origin.length();
    let i = 0;
    for (let y = 0; y < SIZE; y++) {
      const n = (y + 0.5) * step - HALF_EXTENT;
      for (let x = 0; x < SIZE; x++) {
        const e = (x + 0.5) * step - HALF_EXTENT;
        _rel.copy(this.origin).addScaledVector(this.east, e).addScaledVector(this.north, n);
        _d.copy(_rel).normalize();
        const amt = pol.amountAt(_d);
        this.data[i++] = amt > 0 ? Math.min(255, amt * inv) : 0;
      }
    }
    this.texture.needsUpdate = true;
  }

  bindTo(uniforms) {
    uniforms.uPolMap.value = this.texture;
    uniforms.uPolOrigin.value.copy(this.origin);
    uniforms.uPolEast.value.copy(this.east);
    uniforms.uPolNorth.value.copy(this.north);
    uniforms.uPolHalf.value = HALF_EXTENT;
    uniforms.uPolScale.value = this.scale;
  }
}
