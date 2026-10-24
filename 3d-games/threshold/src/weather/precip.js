// Rain you can see, and — more importantly — rain you CANNOT see once you are underneath something.
//
// Precipitation is culled by the solver's world, not by a trigger volume: every drop tests the
// roof-height of its own column and dies when it reaches it. Rain does not fall through the cabin
// roof, it stops on it, and standing at the window watching it come down out there while none of
// it comes down in here is most of what makes the inside feel like inside.
//
// Drops are drawn as short line segments rather than points. Rain is a streak; a dot reads as snow.

const DOWN = -13.5;   // m/s at full intensity
const BOX = 26;       // half-extent of the volume that follows the camera
const TOP = 22;

/**
 * Height of the sky-facing roof for every column in a region, precomputed once per geometry
 * change. A per-drop roofAbove() would be 40 grid lookups times four thousand drops per frame;
 * this is one array read.
 */
export class RoofMap {
  constructor(grid, x0, z0, x1, z1, skyY = 40) {
    this.x0 = x0; this.z0 = z0;
    this.w = x1 - x0 + 1; this.h = z1 - z0 + 1;
    this.top = new Float32Array(this.w * this.h).fill(-Infinity);
    for (let x = x0; x <= x1; x++) {
      for (let z = z0; z <= z1; z++) {
        let highest = -Infinity;
        for (let y = skyY; y >= -4; y--) {
          if (grid.isSolid(x, y, z)) { highest = y + 1; break; }
        }
        this.top[(z - this.z0) * this.w + (x - this.x0)] = highest;
      }
    }
  }

  /** Y below which a drop is under cover. -Infinity means open sky all the way down. */
  at(x, z) {
    const ix = Math.floor(x) - this.x0, iz = Math.floor(z) - this.z0;
    if (ix < 0 || iz < 0 || ix >= this.w || iz >= this.h) return -Infinity;
    return this.top[iz * this.w + ix];
  }
}

export class Precipitation {
  constructor(THREE, scene, roofMap, count = 4200) {
    this.THREE = THREE;
    this.roofMap = roofMap;
    this.count = count;
    this.pos = new Float32Array(count * 3);
    this.vel = new Float32Array(count * 3);

    this.geo = new THREE.BufferGeometry();
    this.verts = new Float32Array(count * 6); // two endpoints per drop
    this.geo.setAttribute('position', new THREE.BufferAttribute(this.verts, 3));
    this.mat = new THREE.LineBasicMaterial({
      color: 0xa9c3d8, transparent: true, opacity: 0.42, depthWrite: false,
    });
    this.mesh = new THREE.LineSegments(this.geo, this.mat);
    this.mesh.frustumCulled = false;
    scene.add(this.mesh);

    // Rain blown in through an opening. Small, but it is the detail that sells the whole system:
    // the storm is not merely audible through the gap, it comes through it.
    this.sprayCount = 300;
    this.sprayPos = new Float32Array(this.sprayCount * 3);
    this.sprayVel = new Float32Array(this.sprayCount * 3);
    this.sprayLife = new Float32Array(this.sprayCount);
    this.sprayVerts = new Float32Array(this.sprayCount * 6);
    this.sprayGeo = new THREE.BufferGeometry();
    this.sprayGeo.setAttribute('position', new THREE.BufferAttribute(this.sprayVerts, 3));
    this.spray = new THREE.LineSegments(this.sprayGeo, new THREE.LineBasicMaterial({
      color: 0xc6dcec, transparent: true, opacity: 0.6, depthWrite: false,
    }));
    this.spray.frustumCulled = false;
    scene.add(this.spray);
    this.sprayNext = 0;

    this.seeded = false;
  }

  _respawn(i, cam, high) {
    this.pos[i * 3] = cam.x + (Math.random() * 2 - 1) * BOX;
    this.pos[i * 3 + 1] = high ? cam.y + TOP * (0.4 + Math.random() * 0.6) : cam.y + Math.random() * TOP;
    this.pos[i * 3 + 2] = cam.z + (Math.random() * 2 - 1) * BOX;
    const s = 0.75 + Math.random() * 0.5;
    this.vel[i * 3] = 0; this.vel[i * 3 + 1] = DOWN * s; this.vel[i * 3 + 2] = 0;
  }

  update(dt, weather, camera, openings) {
    const cam = camera.position;
    const rain = weather.rain;
    const wind = weather.windNow;

    if (!this.seeded) {
      for (let i = 0; i < this.count; i++) this._respawn(i, cam, false);
      this.seeded = true;
    }

    const active = Math.floor(this.count * Math.min(1, rain * 1.05));
    // Wind pushes the whole field sideways; at full gale rain arrives at a real angle.
    const wx = wind * 6.5, wz = wind * 2.2;

    for (let i = 0; i < active; i++) {
      const o = i * 3;
      this.pos[o] += wx * dt;
      this.pos[o + 1] += this.vel[o + 1] * dt;
      this.pos[o + 2] += wz * dt;

      // THE CULL. A drop stops at whatever is over it.
      const floorY = this.roofMap.at(this.pos[o], this.pos[o + 2]);
      if (this.pos[o + 1] < floorY || this.pos[o + 1] < cam.y - 12) {
        this._respawn(i, cam, true);
        continue;
      }
      // Keep the field around the camera without teleporting drops through the roof test.
      if (this.pos[o] - cam.x > BOX) this.pos[o] -= BOX * 2;
      else if (this.pos[o] - cam.x < -BOX) this.pos[o] += BOX * 2;
      if (this.pos[o + 2] - cam.z > BOX) this.pos[o + 2] -= BOX * 2;
      else if (this.pos[o + 2] - cam.z < -BOX) this.pos[o + 2] += BOX * 2;
      if (this.pos[o + 1] > cam.y + TOP) this.pos[o + 1] = cam.y - 2;

      const streak = Math.abs(this.vel[o + 1]) * 0.035;
      const v = i * 6;
      this.verts[v] = this.pos[o];
      this.verts[v + 1] = this.pos[o + 1];
      this.verts[v + 2] = this.pos[o + 2];
      this.verts[v + 3] = this.pos[o] - wx * 0.035;
      this.verts[v + 4] = this.pos[o + 1] + streak;
      this.verts[v + 5] = this.pos[o + 2] - wz * 0.035;
    }
    this.geo.setDrawRange(0, active * 2);
    this.geo.attributes.position.needsUpdate = true;
    this.mat.opacity = 0.18 + rain * 0.3;

    this._updateSpray(dt, rain, wind, openings);
  }

  _updateSpray(dt, rain, wind, openings) {
    // Emit from any opening that is actually open, in proportion to how open it is.
    if (rain > 0.08 && openings) {
      for (const op of openings) {
        if (op.openness < 0.12) continue;
        const n = Math.random() < op.openness * rain * wind * 0.9 ? 1 : 0;
        for (let k = 0; k < n; k++) {
          const i = this.sprayNext = (this.sprayNext + 1) % this.sprayCount;
          const o = i * 3;
          const spread = Math.sqrt(op.area) * 0.5;
          this.sprayPos[o] = op.center[0] + (Math.random() - 0.5) * spread;
          this.sprayPos[o + 1] = op.center[1] + (Math.random() - 0.5) * 0.7;
          this.sprayPos[o + 2] = op.center[2] + (Math.random() - 0.5) * spread;
          // Inward, against the opening's outward normal.
          const push = (0.8 + Math.random() * 1.6) * wind;
          this.sprayVel[o] = -op.normal[0] * push;
          this.sprayVel[o + 1] = -2.2 - Math.random() * 2;
          this.sprayVel[o + 2] = -op.normal[2] * push;
          this.sprayLife[i] = 0.55 + Math.random() * 0.5;
        }
      }
    }

    let n = 0;
    for (let i = 0; i < this.sprayCount; i++) {
      if (this.sprayLife[i] <= 0) continue;
      this.sprayLife[i] -= dt;
      const o = i * 3;
      this.sprayVel[o + 1] -= 9.8 * dt;
      this.sprayPos[o] += this.sprayVel[o] * dt;
      this.sprayPos[o + 1] += this.sprayVel[o + 1] * dt;
      this.sprayPos[o + 2] += this.sprayVel[o + 2] * dt;
      const v = n * 6;
      this.sprayVerts[v] = this.sprayPos[o];
      this.sprayVerts[v + 1] = this.sprayPos[o + 1];
      this.sprayVerts[v + 2] = this.sprayPos[o + 2];
      this.sprayVerts[v + 3] = this.sprayPos[o] - this.sprayVel[o] * 0.02;
      this.sprayVerts[v + 4] = this.sprayPos[o + 1] - this.sprayVel[o + 1] * 0.02;
      this.sprayVerts[v + 5] = this.sprayPos[o + 2] - this.sprayVel[o + 2] * 0.02;
      n++;
    }
    this.sprayGeo.setDrawRange(0, n * 2);
    this.sprayGeo.attributes.position.needsUpdate = true;
  }
}
