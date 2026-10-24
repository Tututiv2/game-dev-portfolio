// Building.
//
// The 2D game's rule, unchanged: you can only build while ANCHORED, everything
// goes on a grid around the core, and unanchoring packs the whole base up for
// half your Mass back. That refund is what makes running away a decision
// instead of a punishment.
//
// The grid is not invented here. `planet.cellAt()` has returned real cells that
// wrap the entire planet since the day the cube-sphere went in, so a structure
// snaps to a cell that exists whether or not anyone is looking at it.

import * as THREE from 'three';
import { FACES, uvToUnit } from './cubesphere.js';
import { WORLD, elevation, oreAt } from './terrain.js';

export const GRID_LEVEL = 10;                       // ~4.6 m cells
export const BUILD_RANGE = 11;                      // cells from the core
export const EMIT_PER_DRILL = 3.4;                  // pollution per second, per extractor

// Cannon, from the 2D game's stats block (range 300 px, damage 25, fireRate 40).
const CANNON_RANGE = 88;                            // metres
const CANNON_DAMAGE = 26;
const CANNON_COOLDOWN = 0.62;                       // seconds
const TRACER_LIFE = 0.07;
const MAX_TRACERS = 48;

// Straight from the 2D game's CONFIG.costs.
export const STRUCTURES = {
  wall:      { label: 'Wall',      key: '1', mass: 10,  tech: 0, needsOre: false },
  extractor: { label: 'Extractor', key: '2', mass: 50,  tech: 0, needsOre: true  },
  cannon:    { label: 'Cannon',    key: '3', mass: 100, tech: 0, needsOre: false },
};

const _dir = new THREE.Vector3();
const _p = new THREE.Vector3();
// The ray direction needs its own vector. Sharing `_p` with the march position
// means step one overwrites the direction it is stepping along.
const _rayDir = new THREE.Vector3();
const _hit = new THREE.Vector3();
const _up = new THREE.Vector3();
const _q = new THREE.Quaternion();
const _m = new THREE.Matrix4();
const _right = new THREE.Vector3();
const _fwd = new THREE.Vector3();
const _yAxis = new THREE.Vector3(0, 1, 0);
const _uv = {};

function surfaceR(d) { return WORLD.radius + elevation(d.x, d.y, d.z); }

// --- cursor ------------------------------------------------------------------

// Where a ray from the camera hits the ground.
//
// Deliberately NOT a three.js raycast: that would test ~900 chunk meshes and
// hundreds of thousands of triangles every frame. Marching the height field
// directly is a few dozen elevation() calls and, more importantly, it hits the
// same surface the crawler drives on rather than whichever LOD happens to be
// drawn right now.
export function raymarchGround(origin, dir, maxDist = 520) {
  let t = 0;
  let prev = origin.length() - surfaceR(_dir.copy(origin).normalize());
  if (prev < 0) return null;                       // started underground
  const step = 3.0;
  while (t < maxDist) {
    t += step;
    _p.copy(origin).addScaledVector(dir, t);
    const h = _p.length() - surfaceR(_dir.copy(_p).normalize());
    if (h < 0) {
      // Bisect the crossing — 12 halvings takes 3 m down to under a millimetre.
      let lo = t - step, hi = t;
      for (let i = 0; i < 12; i++) {
        const mid = (lo + hi) * 0.5;
        _p.copy(origin).addScaledVector(dir, mid);
        const hm = _p.length() - surfaceR(_dir.copy(_p).normalize());
        if (hm < 0) hi = mid; else lo = mid;
      }
      return _hit.copy(origin).addScaledVector(dir, hi);
    }
    prev = h;
  }
  return null;
}

// --- cells -------------------------------------------------------------------

export function cellKey(c) { return `${c.face}:${c.col}:${c.row}`; }

// Centre of a cell, on the ground.
export function cellCentre(cell, out = new THREE.Vector3()) {
  const n = 1 << cell.level;
  uvToUnit(FACES[cell.face], (cell.col + 0.5) / n, (cell.row + 0.5) / n, _dir);
  return out.copy(_dir).multiplyScalar(surfaceR(_dir));
}

export function cellUnit(cell, out = new THREE.Vector3()) {
  const n = 1 << cell.level;
  uvToUnit(FACES[cell.face], (cell.col + 0.5) / n, (cell.row + 0.5) / n, _dir);
  return out.copy(_dir);
}

// --- structure meshes --------------------------------------------------------

function mat(color, emissive, ei = 0) {
  return new THREE.MeshStandardMaterial({
    color, roughness: 0.5, metalness: 0.30, flatShading: true,
    emissive: new THREE.Color(emissive || 0x000000), emissiveIntensity: ei,
  });
}

function buildStructureMesh(type, oreKind) {
  const g = new THREE.Group();
  const hull = mat(0x3d465e);
  const trim = mat(0x66748f);

  if (type === 'wall') {
    const body = new THREE.Mesh(new THREE.CylinderGeometry(2.05, 2.35, 3.6, 6), hull);
    // Slightly taller than a cell is wide, so a wall line reads as a wall.
    body.position.y = 1.8;
    g.add(body);
    const cap = new THREE.Mesh(new THREE.TorusGeometry(2.06, 0.09, 6, 6), mat(0x000000, 0x00ccff, 1.15));
    cap.rotation.x = Math.PI / 2;
    cap.position.y = 3.6;
    g.add(cap);
  } else if (type === 'extractor') {
    const glow = oreKind === 'tech' ? 0x26ff6b : 0xffc70d;
    const base = new THREE.Mesh(new THREE.CylinderGeometry(1.9, 2.3, 1.2, 8), hull);
    base.position.y = 0.6;
    g.add(base);
    for (let i = 0; i < 4; i++) {
      const a = (i / 4) * Math.PI * 2 + Math.PI / 4;
      const leg = new THREE.Mesh(new THREE.BoxGeometry(0.35, 2.6, 0.35), trim);
      leg.position.set(Math.cos(a) * 1.6, 1.3, Math.sin(a) * 1.6);
      leg.rotation.z = Math.cos(a) * 0.22;
      leg.rotation.x = -Math.sin(a) * 0.22;
      g.add(leg);
    }
    const head = new THREE.Mesh(new THREE.CylinderGeometry(0.85, 1.15, 1.6, 6), trim);
    head.position.y = 3.0;
    g.add(head);
    const drill = new THREE.Mesh(new THREE.ConeGeometry(0.75, 2.2, 6), mat(0x000000, glow, 1.8));
    drill.position.y = 1.9;
    drill.rotation.x = Math.PI;
    g.add(drill);
    g.userData.spin = drill;
  } else {
    const base = new THREE.Mesh(new THREE.CylinderGeometry(2.0, 2.4, 1.3, 8), hull);
    base.position.y = 0.65;
    g.add(base);
    const turret = new THREE.Group();
    turret.position.y = 1.6;
    const body = new THREE.Mesh(new THREE.BoxGeometry(2.2, 1.3, 2.6), trim);
    turret.add(body);
    const barrel = new THREE.Mesh(new THREE.CylinderGeometry(0.30, 0.36, 3.2, 8), hull);
    barrel.rotation.x = Math.PI / 2;
    barrel.position.set(0, 0.15, -2.0);
    turret.add(barrel);
    const muzzle = new THREE.Mesh(new THREE.TorusGeometry(0.34, 0.07, 5, 8), mat(0x000000, 0x00ccff, 2.4));
    muzzle.rotation.x = Math.PI / 2;
    muzzle.position.set(0, 0.15, -3.5);
    turret.add(muzzle);
    g.add(turret);
    g.userData.turret = turret;
  }
  return g;
}

// --- the system --------------------------------------------------------------

export class BuildSystem {
  constructor(planet, crawler, scene, resources) {
    this.planet = planet;
    this.crawler = crawler;
    this.scene = scene;
    this.resources = resources;

    this.structures = new Map();      // cellKey -> { type, cell, mesh, ore }
    this.selected = 'wall';
    this.cursorCell = null;
    this.cursorValid = false;
    this.reason = '';

    this.group = new THREE.Group();
    scene.add(this.group);

    // Tracers: one pooled LineSegments, because a scene object per shot would
    // be an allocation storm during a wave. Dead tracers collapse to zero
    // length rather than being removed.
    const tg = new THREE.BufferGeometry();
    this.tracerPos = new Float32Array(MAX_TRACERS * 6);
    tg.setAttribute('position', new THREE.BufferAttribute(this.tracerPos, 3));
    tg.boundingSphere = new THREE.Sphere(new THREE.Vector3(), Infinity);
    this.tracers = new THREE.LineSegments(tg, new THREE.LineBasicMaterial({
      color: 0x9fe8ff, transparent: true, opacity: 0.85, depthWrite: false,
    }));
    this.tracers.frustumCulled = false;
    this.group.add(this.tracers);
    this.tracerLife = new Float32Array(MAX_TRACERS);
    this.tracerNext = 0;

    this.kills = 0;

    this.ghost = null;
    this.ghostType = null;
    this.ghostMat = new THREE.MeshStandardMaterial({
      color: 0x00ccff, transparent: true, opacity: 0.32, depthWrite: false,
      emissive: new THREE.Color(0x00ccff), emissiveIntensity: 0.8, flatShading: true,
    });
  }

  get count() { return this.structures.size; }

  select(type) { if (STRUCTURES[type]) this.selected = type; }

  // Cells within BUILD_RANGE of the core, measured in cells rather than metres
  // so the buildable area is the same shape as the grid it snaps to.
  inRange(cell) {
    const core = this.planet.cellAt(this.crawler.pos, GRID_LEVEL);
    if (cell.face !== core.face) return false;      // keep it simple: one face
    return Math.abs(cell.col - core.col) <= BUILD_RANGE &&
           Math.abs(cell.row - core.row) <= BUILD_RANGE;
  }

  // Updates the cursor from a screen ray. Returns the hovered cell or null.
  updateCursor(camera, ndc) {
    if (!this.crawler.deployed) {
      this.cursorCell = null;
      this.setGhostVisible(false);
      return null;
    }
    _rayDir.set(ndc.x, ndc.y, 0.5).unproject(camera).sub(camera.position).normalize();
    const hit = raymarchGround(camera.position, _rayDir);
    if (!hit) {
      this.cursorCell = null;
      this.setGhostVisible(false);
      return null;
    }

    _dir.copy(hit).normalize();
    const cell = this.planet.cellAt(_dir, GRID_LEVEL);
    this.cursorCell = cell;

    const key = cellKey(cell);
    const spec = STRUCTURES[this.selected];
    const ore = oreAt(_dir.x, _dir.y, _dir.z);
    const oreKind = ore.massVein > ore.techVein ? 'mass' : 'tech';
    const oreAmt = Math.max(ore.massVein, ore.techVein);

    this.cursorValid = true;
    this.reason = '';
    if (this.structures.has(key)) { this.cursorValid = false; this.reason = 'OCCUPIED'; }
    else if (!this.inRange(cell)) { this.cursorValid = false; this.reason = 'OUT OF RANGE'; }
    else if (spec.needsOre && oreAmt < 0.18) { this.cursorValid = false; this.reason = 'NO ORE'; }
    else if (!this.resources.canAfford(spec)) { this.cursorValid = false; this.reason = 'NEED MASS'; }

    this.showGhost(cell, oreKind);
    return cell;
  }

  showGhost(cell, oreKind) {
    if (this.ghostType !== this.selected) {
      if (this.ghost) this.group.remove(this.ghost);
      this.ghost = buildStructureMesh(this.selected, oreKind);
      this.ghost.traverse((o) => { if (o.isMesh) o.material = this.ghostMat; });
      this.ghostType = this.selected;
      this.group.add(this.ghost);
    }
    this.ghost.visible = true;
    this.ghostMat.color.setHex(this.cursorValid ? 0x00ccff : 0xff3344);
    this.ghostMat.emissive.setHex(this.cursorValid ? 0x00ccff : 0xff3344);
    this.orient(this.ghost, cell);
  }

  setGhostVisible(v) { if (this.ghost) this.ghost.visible = v; }

  orient(obj, cell) {
    cellUnit(cell, _up);
    cellCentre(cell, _p);
    obj.position.copy(_p);
    // Stand on the planet normal, not the terrain normal: a tower leaning with
    // the hillside looks broken, and a base of them looks drunk.
    _q.setFromUnitVectors(_yAxis, _up);
    obj.quaternion.copy(_q);
  }

  place() {
    if (!this.crawler.deployed || !this.cursorCell || !this.cursorValid) return false;
    const spec = STRUCTURES[this.selected];
    if (!this.resources.spend(spec)) return false;

    const cell = this.cursorCell;
    cellUnit(cell, _dir);
    const ore = oreAt(_dir.x, _dir.y, _dir.z);
    const oreKind = ore.massVein > ore.techVein ? 'mass' : 'tech';

    const mesh = buildStructureMesh(this.selected, oreKind);
    this.orient(mesh, cell);
    this.group.add(mesh);
    this.structures.set(cellKey(cell), {
      type: this.selected, cell, mesh, ore: oreKind,
      rate: Math.max(ore.massVein, ore.techVein),
    });
    return true;
  }

  // Unanchoring packs everything up and hands back half the Mass — the 2D
  // game's rule, and the reason fleeing is a decision rather than a loss.
  packUp() {
    let refund = 0;
    for (const s of this.structures.values()) {
      refund += STRUCTURES[s.type].mass * 0.5;
      this.group.remove(s.mesh);
      s.mesh.traverse((o) => { if (o.isMesh) o.geometry.dispose(); });
    }
    this.structures.clear();
    this.resources.mass += refund;
    return refund;
  }

  // Nearest live unit inside a cannon's range. Linear over the swarm, which is
  // fine at these counts and avoids a spatial index that would have to be
  // rebuilt every frame anyway.
  acquire(fromPos, units) {
    let best = null, bestD = CANNON_RANGE * CANNON_RANGE;
    for (let i = 0; i < units.length; i++) {
      const u = units[i];
      if (!u.worldPos) continue;
      const d = fromPos.distanceToSquared(u.worldPos);
      if (d < bestD) { bestD = d; best = u; }
    }
    return best;
  }

  serviceCannon(dt, s, elapsed, units) {
    const turret = s.mesh.userData.turret;
    s.cool = (s.cool || 0) - dt;

    const target = units && units.length ? this.acquire(s.mesh.position, units) : null;
    if (!target) {
      // Idle sweep, so an unthreatened base still looks alive.
      turret.rotation.y += dt * 0.35;
      return;
    }

    // Aim in the turret's own space, which is already oriented to the surface,
    // so no spherical maths is needed here at all.
    _p.copy(target.worldPos);
    s.mesh.worldToLocal(_p);
    const want = Math.atan2(_p.x, -_p.z);
    let delta = want - turret.rotation.y;
    delta = Math.atan2(Math.sin(delta), Math.cos(delta));
    const maxTurn = 4.2 * dt;
    turret.rotation.y += Math.max(-maxTurn, Math.min(maxTurn, delta));

    // Only fire when actually pointed at it.
    if (s.cool <= 0 && Math.abs(delta) < 0.22) {
      s.cool = CANNON_COOLDOWN;
      target.hp -= CANNON_DAMAGE;
      if (target.hp <= 0) { target.dead = true; this.kills++; }
      this.fireTracer(s.mesh, turret, target.worldPos);
    }
  }

  fireTracer(mesh, turret, targetPos) {
    const i = this.tracerNext;
    this.tracerNext = (this.tracerNext + 1) % MAX_TRACERS;
    _p.set(0, 0.15, -3.6);
    turret.localToWorld(_p);
    const o = i * 6;
    this.tracerPos[o] = _p.x; this.tracerPos[o + 1] = _p.y; this.tracerPos[o + 2] = _p.z;
    this.tracerPos[o + 3] = targetPos.x;
    this.tracerPos[o + 4] = targetPos.y;
    this.tracerPos[o + 5] = targetPos.z;
    this.tracerLife[i] = TRACER_LIFE;
    this.tracers.geometry.getAttribute('position').needsUpdate = true;
  }

  ageTracers(dt) {
    let changed = false;
    for (let i = 0; i < MAX_TRACERS; i++) {
      if (this.tracerLife[i] <= 0) continue;
      this.tracerLife[i] -= dt;
      if (this.tracerLife[i] <= 0) {
        const o = i * 6;
        for (let k = 0; k < 6; k++) this.tracerPos[o + k] = 0;
        changed = true;
      }
    }
    if (changed) this.tracers.geometry.getAttribute('position').needsUpdate = true;
  }

  update(dt, elapsed, pollution, units) {
    this.ageTracers(dt);
    for (const s of this.structures.values()) {
      if (s.mesh.userData.spin) s.mesh.userData.spin.rotation.y += dt * 5.0;
      if (s.mesh.userData.turret) this.serviceCannon(dt, s, elapsed, units);
      if (s.type === 'extractor') {
        const gain = s.rate * dt * 3.5;
        if (s.ore === 'mass') this.resources.mass += gain;
        else this.resources.tech += gain;
        // The whole game in two lines: the same drill that pays you emits into
        // the cloud that fetches them. Income and threat come from one act, so
        // there is no way to have one without the other.
        if (pollution) {
          if (!s.dir) s.dir = cellUnit(s.cell, new THREE.Vector3());
          pollution.emit(s.dir, EMIT_PER_DRILL * dt);
        }
      }
    }
  }

  get drillCount() {
    let n = 0;
    for (const s of this.structures.values()) if (s.type === 'extractor') n++;
    return n;
  }
}

export class Resources {
  constructor(mass = 300, tech = 0) { this.mass = mass; this.tech = tech; }
  canAfford(spec) { return this.mass >= spec.mass && this.tech >= spec.tech; }
  spend(spec) {
    if (!this.canAfford(spec)) return false;
    this.mass -= spec.mass; this.tech -= spec.tech;
    return true;
  }
}
