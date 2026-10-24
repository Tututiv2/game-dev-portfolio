// Nests, and what comes out of them.
//
// Nests are not authored and not scoutable landmarks — they are hashed out of
// the same global cell grid everything else uses, so they are just *there*,
// randomly, wherever you happen to stop. You do not pick a safe spot; you find
// out whether the one you picked was.
//
// A nest does nothing at all until your pollution reaches it. Then it eats the
// cloud, and when it has eaten enough it sends back a wave that walks up the
// gradient to whoever is making it. That is the entire threat model: drills
// make the cloud, the cloud is the summons.

import * as THREE from 'three';
import { unitToUV, uvToUnit, FACES } from './cubesphere.js';
import { WORLD, elevation } from './terrain.js';

export const NEST_LEVEL = 6;             // ~73 m cells; at most one nest each
const N = 1 << NEST_LEVEL;
const NEST_CHANCE = 0.055;               // fraction of cells holding a nest
const SEARCH_CELLS = 9;                  // radius, in cells, that we keep live

const EAT_RATE = 0.55;                   // pollution consumed per second
// Raised after measuring: at 26 the seven roused nests around one base sent a
// wave every ~7 s and pinned the swarm at its cap forever. This is the dial
// that sets how hard drilling is punished.
const WAVE_COST = 70;                    // eaten pollution per swarm sent
const MAX_LIVE = 140;

const _d = new THREE.Vector3();
const _uv = {};
const _tmp = new THREE.Vector3();
const _up = new THREE.Vector3();
const _fwd = new THREE.Vector3();
const _right = new THREE.Vector3();
const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();
const _yAxis = new THREE.Vector3(0, 1, 0);

function hash(face, col, row, salt) {
  let h = Math.imul(face + 1, 0x2545f491) ^ Math.imul(col, 0x9e3779b1) ^
          Math.imul(row, 0x85ebca6b) ^ Math.imul(salt, 0x27d4eb2d);
  h = Math.imul(h ^ (h >>> 15), 0x2c1b3c6d);
  h = Math.imul(h ^ (h >>> 13), 0x297a2d39);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

function nestMaterial() {
  return new THREE.MeshStandardMaterial({
    color: 0x2a1016, roughness: 0.85, metalness: 0.1, flatShading: true,
    emissive: new THREE.Color(0xff2a3c), emissiveIntensity: 0.55,
  });
}

function nestMesh() {
  const g = new THREE.Group();
  const mat = nestMaterial();
  const mound = new THREE.Mesh(new THREE.ConeGeometry(5.5, 7.0, 7, 1), mat);
  mound.position.y = 3.2;
  g.add(mound);
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * Math.PI * 2 + 0.6;
    const spike = new THREE.Mesh(new THREE.ConeGeometry(1.0, 5.0, 5), mat);
    spike.position.set(Math.cos(a) * 3.4, 2.6, Math.sin(a) * 3.4);
    spike.rotation.z = Math.cos(a) * 0.5;
    spike.rotation.x = -Math.sin(a) * 0.5;
    g.add(spike);
  }
  const eye = new THREE.Mesh(
    new THREE.OctahedronGeometry(1.5, 0),
    new THREE.MeshStandardMaterial({
      color: 0x000000, emissive: new THREE.Color(0xff3344), emissiveIntensity: 2.6, flatShading: true,
    })
  );
  eye.position.y = 7.4;
  g.add(eye);
  g.userData.eye = eye;
  return g;
}

function swarmMesh() {
  const geo = new THREE.IcosahedronGeometry(1, 0);
  const mat = new THREE.MeshStandardMaterial({
    color: 0x140407, roughness: 0.7, metalness: 0.1, flatShading: true,
    emissive: new THREE.Color(0xff2233), emissiveIntensity: 1.4,
  });
  return new THREE.InstancedMesh(geo, mat, MAX_LIVE);
}

export class Hives {
  constructor(planet, pollution, scene) {
    this.planet = planet;
    this.pollution = pollution;
    this.nests = new Map();          // packed key -> nest
    this.group = new THREE.Group();
    scene.add(this.group);

    this.swarm = swarmMesh();
    this.swarm.frustumCulled = false;
    this.swarm.count = 0;
    scene.add(this.swarm);

    this.units = [];                 // { pos(unit), r, hp, speed, phase }
    this.stats = { nests: 0, agitated: 0, live: 0, sent: 0 };
    this.clock = 0;
  }

  static nestAtCell(face, col, row) {
    return hash(face, col, row, 7) < NEST_CHANCE;
  }

  // Keep nests live only near the crawler. A nest far away has nothing to eat
  // anyway, because the cloud never reaches it.
  refresh(crawlerDir) {
    unitToUV(crawlerDir.x, crawlerDir.y, crawlerDir.z, _uv);
    const cc = Math.min(N - 1, (_uv.u * N) | 0);
    const cr = Math.min(N - 1, (_uv.v * N) | 0);
    const face = _uv.face;

    const wanted = new Set();
    for (let dr = -SEARCH_CELLS; dr <= SEARCH_CELLS; dr++) {
      for (let dc = -SEARCH_CELLS; dc <= SEARCH_CELLS; dc++) {
        const col = cc + dc, row = cr + dr;
        // Staying on one face keeps this simple; a nest that would sit past a
        // face edge just does not spawn. At 73 m cells and a 4712 m face that
        // is a seam you will essentially never park on.
        if (col < 0 || row < 0 || col >= N || row >= N) continue;
        if (!Hives.nestAtCell(face, col, row)) continue;
        const key = (face << 16) | (row << 8) | col;
        wanted.add(key);
        if (this.nests.has(key)) continue;

        // Jitter inside the cell so nests are not on a visible lattice.
        const u = (col + 0.25 + hash(face, col, row, 11) * 0.5) / N;
        const v = (row + 0.25 + hash(face, col, row, 12) * 0.5) / N;
        uvToUnit(FACES[face], u, v, _d);
        const e = elevation(_d.x, _d.y, _d.z);
        if (e < WORLD.flatLevel + 6) continue;      // not on the Flats

        const mesh = nestMesh();
        const dir = _d.clone().normalize();
        const pos = dir.clone().multiplyScalar(WORLD.radius + e - 0.8);
        mesh.position.copy(pos);
        _q.setFromUnitVectors(_yAxis, dir);
        mesh.quaternion.copy(_q);
        this.group.add(mesh);
        this.nests.set(key, { key, dir, pos, mesh, eaten: 0, agitation: 0 });
      }
    }

    for (const [key, nest] of this.nests) {
      if (wanted.has(key)) continue;
      this.group.remove(nest.mesh);
      nest.mesh.traverse((o) => { if (o.isMesh) o.geometry.dispose(); });
      this.nests.delete(key);
    }
    this.stats.nests = this.nests.size;
  }

  update(dt, crawler, elapsed, onDamage) {
    this.clock = elapsed;
    this.refresh(crawler.pos);

    let agitated = 0;
    for (const nest of this.nests.values()) {
      const local = this.pollution.amountAt(nest.dir);
      if (local > 0.25) {
        // Eating the cloud is what removes it — so a nest downwind is also a
        // sink, and killing nests will make your own pollution worse. That is
        // the Factorio bargain and it is worth keeping.
        const bite = Math.min(local, EAT_RATE * dt);
        nest.eaten += bite;
        nest.agitation = Math.min(1, nest.agitation + dt * 0.6);
        agitated++;
      } else {
        nest.agitation = Math.max(0, nest.agitation - dt * 0.25);
      }

      const eye = nest.mesh.userData.eye;
      if (eye) {
        eye.rotation.y += dt * (0.6 + nest.agitation * 3.0);
        eye.material.emissiveIntensity = 1.4 + nest.agitation * 3.4 +
          Math.sin(elapsed * 6.0) * nest.agitation * 0.8;
      }

      if (nest.eaten >= WAVE_COST) {
        nest.eaten -= WAVE_COST;
        this.sendWave(nest, crawler);
      }
    }
    this.stats.agitated = agitated;

    this.updateUnits(dt, crawler, onDamage);
  }

  sendWave(nest, crawler) {
    const n = 3 + Math.floor(Math.random() * 4);
    for (let i = 0; i < n; i++) {
      if (this.units.length >= MAX_LIVE) break;
      const p = nest.dir.clone();
      // Scatter them around the mouth of the nest.
      _tmp.set(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5);
      p.addScaledVector(_tmp, 0.0025).normalize();
      this.units.push({
        dir: p,
        worldPos: new THREE.Vector3(),
        dead: false,
        hp: 34,
        speed: 11 + Math.random() * 5,
        phase: Math.random() * 6.28,
        size: 0.9 + Math.random() * 0.7,
      });
      this.stats.sent++;
    }
  }

  updateUnits(dt, crawler, onDamage) {
    const target = crawler.pos;
    let n = 0;
    for (let i = this.units.length - 1; i >= 0; i--) {
      const u = this.units[i];
      if (u.dead) { this.units.splice(i, 1); continue; }

      // Great-circle step toward the crawler: rotate the unit's direction
      // toward the target's, which needs no pathfinding and cannot fall off
      // the world.
      _tmp.copy(target).addScaledVector(u.dir, -target.dot(u.dir));
      const len = _tmp.length();
      if (len > 1e-6) {
        _tmp.divideScalar(len);
        const arc = (u.speed * dt) / WORLD.radius;
        u.dir.addScaledVector(_tmp, Math.tan(Math.min(arc, 0.2))).normalize();
      }

      const dot = Math.min(1, Math.max(-1, u.dir.dot(target)));
      const dist = Math.acos(dot) * WORLD.radius;
      if (dist < 6) {
        if (onDamage) onDamage(12 * dt);
      }

      // World position is kept up to date for everything else to use — the
      // cannons target off this, so it must be written every frame, not only
      // for the units that happen to fit in the instance buffer.
      const r = WORLD.radius + elevation(u.dir.x, u.dir.y, u.dir.z) + 1.3 +
                Math.sin(u.phase + this.clock) * 0.35;
      u.worldPos.copy(u.dir).multiplyScalar(r);

      if (n < MAX_LIVE) {
        _q.setFromUnitVectors(_yAxis, u.dir);
        _m.compose(u.worldPos, _q, _up.set(u.size, u.size * 0.7, u.size * 1.3));
        this.swarm.setMatrixAt(n, _m);
        n++;
      }
    }
    this.swarm.count = n;
    if (n > 0) this.swarm.instanceMatrix.needsUpdate = true;
    this.stats.live = this.units.length;
  }

  // Distance to the nearest live nest, in metres — for the HUD, so you can at
  // least tell how much trouble you are parked next to.
  nearestNest(dir) {
    let best = Infinity;
    for (const nest of this.nests.values()) {
      const dot = Math.min(1, Math.max(-1, nest.dir.dot(dir)));
      const d = Math.acos(dot) * WORLD.radius;
      if (d < best) best = d;
    }
    return best;
  }

  clearUnits() { this.units.length = 0; this.swarm.count = 0; }
}
