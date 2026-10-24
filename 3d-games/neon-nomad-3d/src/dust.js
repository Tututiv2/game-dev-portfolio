// Wheel dust.
//
// Purely for feel. 274 kph over a smooth height field reads as about 40 unless
// something is being thrown off the wheels — the ground has no features small
// enough to stream past you, so the dust IS the speed cue.
//
// A fixed pool, recycled oldest-first: no allocation once it is running.

import * as THREE from 'three';

const MAX = 320;
const LIFE = 1.15;          // seconds
const SPAWN_SPEED = 5;      // m/s below which nothing is thrown

const _spawn = new THREE.Vector3();
const _right = new THREE.Vector3();

export class Dust {
  constructor(uniforms) {
    this.positions = new Float32Array(MAX * 3);
    this.velocities = new Float32Array(MAX * 3);
    this.ages = new Float32Array(MAX).fill(Infinity);   // Infinity == dead
    this.seeds = new Float32Array(MAX);
    this.next = 0;
    this.carry = 0;

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(this.positions, 3));
    geo.setAttribute('aAge', new THREE.BufferAttribute(this.ages, 1));
    geo.setAttribute('aSeed', new THREE.BufferAttribute(this.seeds, 1));
    // Particles move every frame and are scattered across the world, so a
    // bounding sphere is useless — never cull this.
    geo.boundingSphere = new THREE.Sphere(new THREE.Vector3(), Infinity);

    this.material = new THREE.ShaderMaterial({
      uniforms: {
        uSunDir: uniforms.uSunDir,
        uSunColor: uniforms.uSunColor,
        uSkyAmbient: uniforms.uSkyAmbient,
        uLife: { value: LIFE },
        uPixelRatio: { value: Math.min(devicePixelRatio, 2) },
      },
      transparent: true,
      depthWrite: false,
      blending: THREE.NormalBlending,
      vertexShader: /* glsl */ `
        attribute float aAge;
        attribute float aSeed;
        uniform float uLife, uPixelRatio;
        varying float vFade;
        varying float vSeed;
        void main() {
          float t = clamp(aAge / uLife, 0.0, 1.0);
          vFade = (1.0 - t) * smoothstep(0.0, 0.12, t);   // puff in, fade out
          vSeed = aSeed;
          vec4 mv = viewMatrix * modelMatrix * vec4(position, 1.0);
          // Grows as it drifts, like a real puff losing coherence.
          float size = (0.6 + t * 2.2 + aSeed * 0.8) * uPixelRatio;
          gl_PointSize = size * (300.0 / max(-mv.z, 0.001));
          gl_Position = projectionMatrix * mv;
          if (aAge > uLife) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);  // offscreen
        }
      `,
      fragmentShader: /* glsl */ `
        precision mediump float;
        uniform vec3 uSunColor, uSkyAmbient;
        varying float vFade;
        varying float vSeed;
        void main() {
          vec2 d = gl_PointCoord - 0.5;
          float r = dot(d, d);
          if (r > 0.25) discard;
          float soft = 1.0 - smoothstep(0.02, 0.25, r);
          vec3 col = mix(uSkyAmbient * 0.9, uSunColor * 0.55, 0.45 + vSeed * 0.3);
          gl_FragColor = vec4(col, soft * vFade * 0.15);
        }
      `,
    });

    this.points = new THREE.Points(geo, this.material);
    this.points.frustumCulled = false;
    this.geo = geo;
  }

  update(dt, crawler) {
    const speed = Math.abs(crawler.speed);

    // Spawn, rate proportional to speed. The carry keeps the rate honest at
    // any frame rate instead of quantising to one per frame.
    if (speed > SPAWN_SPEED && !crawler.deployed) {
      this.carry += dt * (6 + speed * 1.7);
      const n = Math.min(24, Math.floor(this.carry));
      this.carry -= n;
      _right.crossVectors(crawler.fwd, crawler.pos).normalize();
      for (let i = 0; i < n; i++) {
        const side = Math.random() < 0.5 ? -1 : 1;
        _spawn.copy(crawler.worldPos)
          .addScaledVector(crawler.fwd, -3.2 + (Math.random() - 0.5) * 2.0)
          .addScaledVector(_right, side * (2.6 + Math.random() * 1.2))
          .addScaledVector(crawler.pos, 0.4);

        const k = this.next;
        this.next = (this.next + 1) % MAX;
        this.positions[k * 3] = _spawn.x;
        this.positions[k * 3 + 1] = _spawn.y;
        this.positions[k * 3 + 2] = _spawn.z;
        // Kicked backwards and outwards, with a little lift.
        const back = -speed * (0.10 + Math.random() * 0.14);
        const out = side * (1.2 + Math.random() * 2.4);
        const lift = 1.4 + Math.random() * 3.0;
        this.velocities[k * 3] = crawler.fwd.x * back + _right.x * out + crawler.pos.x * lift;
        this.velocities[k * 3 + 1] = crawler.fwd.y * back + _right.y * out + crawler.pos.y * lift;
        this.velocities[k * 3 + 2] = crawler.fwd.z * back + _right.z * out + crawler.pos.z * lift;
        this.ages[k] = 0;
        this.seeds[k] = Math.random();
      }
    }

    // Integrate. Drag plus a gentle pull toward the planet — good enough, and
    // it costs nothing next to a real gravity solve.
    const drag = Math.pow(0.22, dt);
    for (let i = 0; i < MAX; i++) {
      if (this.ages[i] > LIFE) continue;
      this.ages[i] += dt;
      const j = i * 3;
      this.velocities[j] *= drag;
      this.velocities[j + 1] *= drag;
      this.velocities[j + 2] *= drag;
      this.positions[j] += this.velocities[j] * dt;
      this.positions[j + 1] += this.velocities[j + 1] * dt;
      this.positions[j + 2] += this.velocities[j + 2] * dt;
    }

    this.geo.getAttribute('position').needsUpdate = true;
    this.geo.getAttribute('aAge').needsUpdate = true;
    this.geo.getAttribute('aSeed').needsUpdate = true;
  }
}
