// The things that make it a place rather than a box: the fire, a lamp, a curtain, dust in the air,
// a few trees to bend in the wind.
//
// The curtain is the one worth reading. It only moves when the window beside it is open, and only
// as much as the wind and the gap allow — the visual twin of the aperture emitter, driven off the
// same `openness` number. Shut the window and it hangs dead. That is the whole design in one prop.

const CURTAIN_W = 6, CURTAIN_H = 8;

// Candela. three.js has used physical light units since r155, so the plausible-looking small
// numbers you would pick by instinct are roughly forty times too dim and the cabin comes out
// pitch black. These are the only magic numbers in the file and they were found by looking.
export const FIRE_CD = 16;
export const LAMP_CD = 11;

export class Props {
  constructor(THREE, scene, openings) {
    this.THREE = THREE;
    this.openings = openings;
    this.group = new THREE.Group();
    this.group.name = 'props';
    scene.add(this.group);
    this.time = 0;

    // ---- the hearth ------------------------------------------------------------------------
    // 2200 K. Everything outdoors is 6500 K and up. That gap is the shot.
    this.fireLight = new THREE.PointLight(0xff7b26, FIRE_CD, 16, 1.55);
    this.fireLight.position.set(7.4, 1.7, 1.5);
    this.fireLight.castShadow = true;
    this.fireLight.shadow.mapSize.set(512, 512);
    this.fireLight.shadow.bias = -0.004;
    this.group.add(this.fireLight);

    const fireGeo = new THREE.IcosahedronGeometry(0.17, 1);
    this.fire = new THREE.Mesh(fireGeo, new THREE.MeshBasicMaterial({ color: 0xffb257 }));
    this.fire.position.copy(this.fireLight.position).setY(1.42);
    this.group.add(this.fire);

    const emberGeo = new THREE.IcosahedronGeometry(0.27, 1);
    this.embers = new THREE.Mesh(emberGeo, new THREE.MeshBasicMaterial({ color: 0x8c2b06 }));
    this.embers.position.copy(this.fire.position).setY(1.22);
    this.group.add(this.embers);

    // ---- a lamp on the far side, so the room has two warm sources ---------------------------
    this.lamp = new THREE.PointLight(0xffb066, LAMP_CD, 11, 1.6);
    this.lamp.position.set(1.6, 2.6, 5.4);
    this.group.add(this.lamp);
    const lampMesh = new THREE.Mesh(
      new THREE.SphereGeometry(0.13, 10, 8),
      new THREE.MeshBasicMaterial({ color: 0xffd9a0 }));
    lampMesh.position.copy(this.lamp.position);
    this.group.add(lampMesh);

    // ---- the curtain, beside the west window ------------------------------------------------
    this.curtainGeo = new THREE.PlaneGeometry(0.85, 1.25, CURTAIN_W, CURTAIN_H);
    this.curtain = new THREE.Mesh(this.curtainGeo, new THREE.MeshStandardMaterial({
      color: 0x8c3f3a, roughness: 1, side: THREE.DoubleSide,
    }));
    this.curtain.position.set(1.07, 2.42, 2.72);
    this.curtain.rotation.y = Math.PI / 2;
    this.curtain.castShadow = true;
    this.group.add(this.curtain);
    this.curtainRest = this.curtainGeo.attributes.position.array.slice();

    // ---- dust in the lamplight ---------------------------------------------------------------
    const N = 260;
    const pos = new Float32Array(N * 3);
    this.dustSeed = new Float32Array(N * 3);
    for (let i = 0; i < N; i++) {
      pos[i * 3] = 1 + Math.random() * 7;
      pos[i * 3 + 1] = 1 + Math.random() * 2.2;
      pos[i * 3 + 2] = 1 + Math.random() * 5;
      this.dustSeed[i * 3] = Math.random() * 100;
      this.dustSeed[i * 3 + 1] = 0.1 + Math.random() * 0.3;
      this.dustSeed[i * 3 + 2] = Math.random() * 100;
    }
    const dustGeo = new THREE.BufferGeometry();
    dustGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    this.dust = new THREE.Points(dustGeo, new THREE.PointsMaterial({
      color: 0xffcc99, size: 0.014, transparent: true, opacity: 0.38,
      depthWrite: false, sizeAttenuation: true,
    }));
    this.group.add(this.dust);

    // ---- trees, to show you the wind ---------------------------------------------------------
    this.trees = [];
    const trunkMat = new THREE.MeshStandardMaterial({ color: 0x3d2f22, roughness: 1 });
    const leafMat = new THREE.MeshStandardMaterial({ color: 0x33452a, roughness: 1 });
    const spots = [
      [-7, -6], [-9, 5], [-5, 13], [4, -9], [14, -5], [17, 6], [13, 14], [2, 16],
      [-12, 1], [20, 1], [-3, 20], [9, 19],
    ];
    for (const [x, z] of spots) {
      const t = new THREE.Group();
      const h = 3.2 + Math.random() * 2.4;
      const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.10, 0.17, h, 6), trunkMat);
      trunk.position.y = h / 2;
      trunk.castShadow = true;
      t.add(trunk);
      const crown = new THREE.Mesh(new THREE.ConeGeometry(1.0 + Math.random() * 0.5, h * 0.95, 7), leafMat);
      crown.position.y = h * 0.92;
      crown.castShadow = true;
      t.add(crown);
      t.position.set(x + 4.5, 1, z + 3.5);
      t.userData.phase = Math.random() * 10;
      this.group.add(t);
      this.trees.push(t);
    }
  }

  update(dt, weather, flash) {
    this.time += dt;
    const t = this.time;

    // Firelight flicker — two detuned wobbles plus an occasional flare, never a sine.
    const f = 0.82 + 0.13 * Math.sin(t * 11.3) + 0.09 * Math.sin(t * 4.1 + 1.3)
      + 0.06 * Math.sin(t * 23.7 + 0.4);
    this.fireLight.intensity = FIRE_CD * f;
    this.fireLight.position.x = 7.4 + Math.sin(t * 5.7) * 0.05;
    this.fire.scale.setScalar(0.9 + f * 0.18);
    this.fire.rotation.y = t * 0.8;
    this.embers.scale.setScalar(0.94 + Math.sin(t * 2.2) * 0.05);
    this.lamp.intensity = LAMP_CD * (0.96 + 0.04 * Math.sin(t * 3.3 + 2));

    // ---- the curtain only knows about the window next to it --------------------------------
    const w = this.openings.windowWest;
    const draught = w.openness * weather.windNow;
    const arr = this.curtainGeo.attributes.position.array;
    for (let i = 0; i < arr.length; i += 3) {
      const rx = this.curtainRest[i], ry = this.curtainRest[i + 1];
      // Pinned along the top, free at the bottom, so it billows the way cloth does.
      const hang = Math.max(0, (0.62 - ry) / 1.25);
      const sway = Math.sin(t * 3.1 + rx * 2.2 + ry * 1.4) * 0.5
        + Math.sin(t * 6.7 + rx * 3.9) * 0.25;
      arr[i + 2] = this.curtainRest[i + 2] + sway * hang * draught * 0.75;
      arr[i] = rx + sway * hang * draught * 0.12;
    }
    this.curtainGeo.attributes.position.needsUpdate = true;
    this.curtainGeo.computeVertexNormals();

    // ---- dust drifts, and catches the light -------------------------------------------------
    const dp = this.dust.geometry.attributes.position.array;
    for (let i = 0; i < dp.length; i += 3) {
      const s = this.dustSeed[i], sp = this.dustSeed[i + 1];
      dp[i] += Math.sin(t * 0.4 + s) * 0.0016;
      dp[i + 1] += Math.sin(t * 0.31 + s * 1.7) * 0.0011 + sp * 0.0004;
      dp[i + 2] += Math.cos(t * 0.37 + s * 0.9) * 0.0016;
      if (dp[i + 1] > 3.1) dp[i + 1] = 1.05;
    }
    this.dust.geometry.attributes.position.needsUpdate = true;

    // ---- trees lean on the wind -------------------------------------------------------------
    const gust = weather.windNow;
    for (const tree of this.trees) {
      const p = tree.userData.phase;
      tree.rotation.z = -(0.09 + Math.sin(t * 1.7 + p) * 0.05) * gust;
      tree.rotation.x = (Math.sin(t * 1.1 + p * 1.9) * 0.04) * gust;
    }

    // Lightning briefly overwhelms the warm interior, which is exactly why it is startling.
    if (flash > 0.01) this.fireLight.intensity *= 1 - flash * 0.4;
  }
}
