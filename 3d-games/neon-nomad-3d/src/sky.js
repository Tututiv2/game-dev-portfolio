// The sky: nebula, stars, a gas giant overhead, and the horizon haze that
// welds the sky to the ground.
//
// Everything is analytic in one shader on an inside-out sphere that rides with
// the camera. The horizon band is driven by the LOCAL up vector, so as you
// drive around the planet the glow slides across the sky on its own — which is
// half of what sells the curvature.

import * as THREE from 'three';
import { GLSL_NOISE, SUN_DIR, PALETTE } from './materials.js';

export class Sky {
  constructor() {
    this.uniforms = {
      uUp: { value: new THREE.Vector3(0, 1, 0) },
      uSunDir: { value: SUN_DIR.clone() },
      uTime: { value: 0 },
      uGiantDir: { value: new THREE.Vector3(-0.35, 0.62, 0.70).normalize() },
      uGiantAngle: { value: 0.22 },          // radians of angular radius — big
      uHazeColor: { value: PALETTE.fog.clone() },
      uNebulaA: { value: new THREE.Color(0.42, 0.08, 0.62) },
      uNebulaB: { value: new THREE.Color(0.0, 0.42, 0.75) },
    };

    const geo = new THREE.SphereGeometry(1, 48, 32);
    const mat = new THREE.ShaderMaterial({
      uniforms: this.uniforms,
      side: THREE.BackSide,
      depthWrite: false,
      depthTest: false,
      fog: false,
      vertexShader: /* glsl */ `
        varying vec3 vDir;
        void main() {
          vDir = normalize(position);
          // The dome is infinitely far away, so strip the translation out of
          // the view matrix and pin z to w — that lands every vertex exactly on
          // the far plane and no near-clipping can eat the sky.
          vec4 p = projectionMatrix * mat4(mat3(viewMatrix)) * vec4(position, 1.0);
          gl_Position = p.xyww;
        }
      `,
      fragmentShader: /* glsl */ `
        precision highp float;
        uniform vec3 uUp, uSunDir, uGiantDir, uHazeColor, uNebulaA, uNebulaB;
        uniform float uTime, uGiantAngle;
        varying vec3 vDir;

        ${GLSL_NOISE}

        void main() {
          vec3 d = normalize(vDir);
          float h = dot(d, uUp);            // 1 zenith, 0 horizon, -1 underfoot

          // --- deep space gradient -------------------------------------------
          vec3 zenith  = vec3(0.002, 0.003, 0.011);
          vec3 horizon = vec3(0.013, 0.020, 0.044);
          vec3 col = mix(horizon, zenith, smoothstep(0.0, 0.45, h));

          // --- nebula ---------------------------------------------------------
          float n1 = fbm3(d * 2.1 + 11.0, 5);
          float n2 = fbm3(d * 4.7 - 3.0, 4);
          float band = pow(1.0 - abs(dot(d, normalize(vec3(0.3, 0.85, -0.42)))), 3.0);
          float cloud = smoothstep(0.46, 0.80, n1) * band;
          col += mix(uNebulaA, uNebulaB, n2) * cloud * 0.50;
          col += uNebulaB * pow(band, 2.0) * 0.030;

          // --- stars ----------------------------------------------------------
          // Two densities so the field has both a scatter and a few standouts.
          float s1 = pow(hash31(floor(d * 620.0)), 300.0);
          float s2 = pow(hash31(floor(d * 260.0) + 7.3), 520.0);
          float twinkle = 0.75 + 0.25 * sin(uTime * 2.1 + hash31(floor(d * 620.0)) * 60.0);
          vec3 starTint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.85, 0.7), hash31(floor(d * 260.0)));
          col += starTint * (s1 * 1.4 + s2 * 3.0) * twinkle * (1.0 - cloud * 0.7);

          // --- day ------------------------------------------------------------
          // Without this, day and night look the same and the fixed sun buys
          // nothing. The atmosphere is thin, so daylight here is a washed
          // indigo rather than an Earth blue — but it is unmistakably day, and
          // the stars burn out as it comes up.
          float sunUp = dot(uSunDir, uUp);
          float day = smoothstep(-0.14, 0.34, sunUp);
          if (day > 0.001) {
            vec3 dayZenith  = vec3(0.030, 0.055, 0.135);
            vec3 dayHorizon = vec3(0.215, 0.225, 0.330);
            vec3 dayCol = mix(dayHorizon, dayZenith, smoothstep(0.0, 0.80, h));
            // Forward scattering around the sun.
            dayCol += vec3(0.60, 0.50, 0.44) * pow(max(dot(d, uSunDir), 0.0), 7.0) * 0.55;
            col = mix(col, dayCol, day * 0.90);
          }

          // --- gas giant ------------------------------------------------------
          // A real ray-sphere hit against a body parked far away, so it has a
          // proper terminator lit by the same sun as the ground.
          float D = 100.0;
          float Rg = D * sin(uGiantAngle);
          vec3 C = uGiantDir * D;
          float tca = dot(C, d);
          if (tca > 0.0) {
            float d2 = dot(C, C) - tca * tca;
            float r2 = Rg * Rg;
            if (d2 < r2) {
              float thc = sqrt(r2 - d2);
              vec3 hit = d * (tca - thc);
              vec3 gn = normalize(hit - C);
              float lat = dot(gn, normalize(vec3(0.1, 0.97, 0.2)));
              float bands = fbm3(vec3(lat * 9.0, 2.0, 0.0), 4);
              vec3 base = mix(vec3(0.36, 0.20, 0.34), vec3(0.62, 0.44, 0.30), bands);
              base = mix(base, vec3(0.20, 0.34, 0.52), smoothstep(0.55, 0.95, abs(lat)));
              float gl = max(dot(gn, uSunDir), 0.0);
              float limb = pow(1.0 - abs(dot(gn, -d)), 2.0);
              vec3 gcol = base * (gl * 1.15 + 0.035) + uNebulaA * limb * 0.22;
              // Hard-edged, so it reads as a body and not a decal.
              float edge = smoothstep(r2, r2 * 0.985, d2);
              col = mix(col, gcol, edge);
            }
          }

          // --- sun ------------------------------------------------------------
          float sd = max(dot(d, uSunDir), 0.0);
          col += vec3(1.0, 0.96, 0.90) * pow(sd, 3000.0) * 24.0;
          col += vec3(0.55, 0.72, 1.0) * pow(sd, 22.0) * 0.30;

          // --- horizon haze ----------------------------------------------------
          // Ties the dome to the terrain. Brightens toward the sun, so sunrise
          // over a ridge actually looks like sunrise.
          float haze = pow(1.0 - clamp(abs(h), 0.0, 1.0), 9.0);
          float sunAmt = pow(max(dot(normalize(d - uUp * h), uSunDir), 0.0), 3.0);
          vec3 hazeCol = mix(uHazeColor * 1.5, vec3(0.34, 0.35, 0.45), day)
                       + vec3(0.30, 0.42, 0.62) * sunAmt * 0.40;
          col = mix(col, hazeCol, haze * 0.80);

          // Below the local horizon the atmosphere thickens into nothing — the
          // ground covers most of it, but the sliver past the limb should not
          // be full of stars.
          col = mix(col, uHazeColor * 1.6, smoothstep(0.0, -0.16, h));

          gl_FragColor = vec4(col, 1.0);
        }
      `,
    });

    this.mesh = new THREE.Mesh(geo, mat);
    this.mesh.frustumCulled = false;
    this.mesh.renderOrder = -1000;
    this.mesh.scale.setScalar(1);
  }

  update(up, time) {
    this.uniforms.uUp.value.copy(up);
    this.uniforms.uTime.value = time;
  }
}
