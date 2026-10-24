// Surface shading, shared by the terrain and by everything scattered on it.
//
// The 2D game's palette is doing the work here: near-black ground, cyan rim,
// violet sky, and the two ore types in their original colours — mass yellow,
// tech green. Those glows are not decoration. They are literally where the
// resources are, sampled from the same function the extractors will read, so
// the planet advertises its own economy from a kilometre away.
//
// Terrain and rocks share BOTH the GLSL below and the same uniform object, so
// there is no way for a boulder to be lit by a different sun than the ground it
// is sitting on.

import * as THREE from 'three';
import { WORLD } from './terrain.js';

export const PALETTE = {
  sun: new THREE.Color(0.85, 0.92, 1.0),
  skyAmbient: new THREE.Color(0.20, 0.13, 0.38),
  groundAmbient: new THREE.Color(0.04, 0.05, 0.08),
  mass: new THREE.Color(1.0, 0.78, 0.05),
  tech: new THREE.Color(0.15, 1.0, 0.42),
  fog: new THREE.Color(0.055, 0.075, 0.14),
  rim: new THREE.Color(0.0, 0.80, 1.0),
};

// Sun direction is fixed in WORLD space, not relative to the crawler. Drive far
// enough and you drive into night — where the only light left is the ore veins
// and your own hull. That is free, and it is the best-looking thing here.
export const SUN_DIR = new THREE.Vector3(0.55, 0.42, -0.72).normalize();

export const GLSL_NOISE = /* glsl */ `
  float hash31(vec3 p){
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
  }
  float vnoise(vec3 x){
    vec3 i = floor(x), f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(i + vec3(0,0,0)), hash31(i + vec3(1,0,0)), f.x),
                   mix(hash31(i + vec3(0,1,0)), hash31(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash31(i + vec3(0,0,1)), hash31(i + vec3(1,0,1)), f.x),
                   mix(hash31(i + vec3(0,1,1)), hash31(i + vec3(1,1,1)), f.x), f.y), f.z);
  }
  float fbm3(vec3 p, int oct){
    float a = 0.5, s = 0.0, n = 0.0;
    for (int i = 0; i < 6; i++){
      if (i >= oct) break;
      s += a * vnoise(p);
      n += a;
      a *= 0.5;
      p *= 2.02;
    }
    return s / n;
  }
`;

// Lighting + aerial perspective. This block DECLARES the uniforms it uses, so
// anything that includes it must not redeclare them.
export const GLSL_SURFACE = /* glsl */ `
  uniform vec3 uSunDir, uSunColor, uSkyAmbient, uGroundAmbient;
  uniform vec3 uFogColor, uRimColor;
  uniform float uFogDensity;
  uniform vec3 uLampPos, uLampDir, uLampColor;
  uniform float uLampPower;

  // N is the shading normal (may be bumped); geoN is the geometric one. The rim
  // term MUST use geoN — a fresnel driven by bump noise paints everything cyan.
  vec3 litRock(vec3 albedo, vec3 N, vec3 geoN, vec3 worldPos, vec3 up, vec3 V,
               float specPow, float specAmt) {
    float ndl = max(dot(N, uSunDir), 0.0);
    // A little wrap on the terminator so the day/night line is a gradient and
    // not a razor edge across the mountains.
    float wrapped = max(0.0, (dot(N, uSunDir) + 0.22) / 1.22);
    vec3 sunL = uSunColor * (ndl * 0.92 + wrapped * 0.22) * 1.95;

    float skyMix = 0.5 + 0.5 * dot(N, up);
    vec3 ambient = mix(uGroundAmbient, uSkyAmbient, skyMix);
    vec3 col = albedo * (sunL + ambient);

    vec3 H = normalize(uSunDir + V);
    col += uSunColor * pow(max(dot(N, H), 0.0), specPow) * specAmt * ndl;

    float rim = pow(1.0 - clamp(dot(geoN, V), 0.0, 1.0), 4.0);
    col += uRimColor * rim * 0.055;

    // The crawler's headlamps. Terrain and rocks run their own shaders and
    // ignore scene lights, so the lamps are passed in by hand — worth it,
    // because the night side is otherwise unplayable and this is the only light
    // out there that moves with you.
    vec3 toLamp = uLampPos - worldPos;
    float lampDist = length(toLamp);
    if (lampDist < 340.0 && uLampPower > 0.0) {
      vec3 L = toLamp / max(lampDist, 0.001);
      float cone = smoothstep(0.68, 0.93, dot(-L, uLampDir));
      float atten = 1.0 / (1.0 + lampDist * lampDist * 0.0013);
      float lamp = cone * atten * max(dot(N, L), 0.0) * uLampPower;
      col += uLampColor * lamp * (albedo * 5.0 + 0.06);
    }
    return col;
  }

  vec3 aerial(vec3 col, vec3 worldPos, vec3 V) {
    float d = length(cameraPosition - worldPos);
    float fog = 1.0 - exp(-d * uFogDensity);
    // Forward scattering: haze glows where you look toward the sun.
    float fwd = pow(max(dot(-V, uSunDir), 0.0), 6.0);
    vec3 fogCol = uFogColor + uSunColor * fwd * 0.28 + uSkyAmbient * 0.35;
    return mix(col, fogCol, fog);
  }
`;

// One uniform object, shared by every surface material. Updated once a frame.
export function surfaceUniforms() {
  return {
    uSunDir: { value: SUN_DIR.clone() },
    uSunColor: { value: PALETTE.sun.clone() },
    uSkyAmbient: { value: PALETTE.skyAmbient.clone() },
    uGroundAmbient: { value: PALETTE.groundAmbient.clone() },
    uMassColor: { value: PALETTE.mass.clone() },
    uTechColor: { value: PALETTE.tech.clone() },
    uFogColor: { value: PALETTE.fog.clone() },
    uRimColor: { value: PALETTE.rim.clone() },
    uFogDensity: { value: 0.00098 },
    uRadius: { value: WORLD.radius },
    uFlatR: { value: WORLD.radius + WORLD.flatLevel },
    uAmplitude: { value: WORLD.amplitude },
    uTime: { value: 0 },
    uLampPos: { value: new THREE.Vector3() },
    uLampDir: { value: new THREE.Vector3(0, 0, 1) },
    uLampColor: { value: new THREE.Color(0.60, 0.85, 1.0) },
    uLampPower: { value: 1.0 },
    // Build grid, drawn on the ground while anchored.
    uBuildOrigin: { value: new THREE.Vector3() },
    uBuildActive: { value: 0 },
    uBuildRadius: { value: 1 },
    uGridLevel: { value: 10 },
    // Pollution, as a tangent-plane window around the crawler.
    uPolMap: { value: null },
    uPolOrigin: { value: new THREE.Vector3() },
    uPolEast: { value: new THREE.Vector3(1, 0, 0) },
    uPolNorth: { value: new THREE.Vector3(0, 1, 0) },
    uPolHalf: { value: 1150 },
    uPolScale: { value: 1 },
  };
}

// Reading the pollution window. Shared so the ground, the rocks and the flora
// are all stained by the same cloud — a clean boulder standing in a filthy
// field would give the whole thing away.
export const GLSL_POLLUTION = /* glsl */ `
  uniform sampler2D uPolMap;
  uniform vec3 uPolOrigin, uPolEast, uPolNorth;
  uniform float uPolHalf, uPolScale;

  float pollutionAt(vec3 worldPos) {
    vec3 rel = worldPos - uPolOrigin;
    vec2 en = vec2(dot(rel, uPolEast), dot(rel, uPolNorth));
    vec2 uv = en / (2.0 * uPolHalf) + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return 0.0;
    // Fade at the window edge so the cloud does not end in a straight line.
    vec2 f = min(uv, 1.0 - uv);
    float edge = smoothstep(0.0, 0.06, min(f.x, f.y));
    return texture2D(uPolMap, uv).r * uPolScale * edge;
  }

  // A dirty amber stain that reads as "something can smell you from here".
  // Deliberately NOT the cyan/violet the rest of the palette lives in — this is
  // the one colour on the planet that means trouble.
  vec3 stain(vec3 col, float amount, float ndl) {
    // The falloff constant matters more than the colour. At 0.42 this saturated
    // by the time a cell held 7, and a settled cloud averages ~2 across
    // hundreds of cells — so the entire visible world turned orange and the
    // cloud had no visible EDGE, which is the only thing it needed to have.
    float t = 1.0 - exp(-amount * 0.075);

    // Poisoning reads as the ground losing its colour, not as an orange lamp
    // shining on it: desaturate and darken first, warm cast second.
    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    vec3 dirty = mix(col, vec3(lum), 0.55) * 0.66 + vec3(0.075, 0.026, 0.010) * t;
    col = mix(col, dirty, t);
    col += vec3(0.105, 0.030, 0.008) * t * t * (0.30 + 0.50 * (1.0 - ndl));
    return col;
  }
`;

// Unit direction -> face uv on the tangent-warped cube-sphere. The exact GLSL
// twin of `unitToUV` in cubesphere.js — which is only possible because the
// tangent warp inverts analytically. This is what lets the build grid be drawn
// as a real wrapping grid rather than a decal stuck under the crawler.
export const GLSL_CUBESPHERE = /* glsl */ `
  vec2 sphereToFaceUV(vec3 d) {
    vec3 a = abs(d);
    vec2 t;
    if (a.x >= a.y && a.x >= a.z)      t = (d.x > 0.0) ? vec2(-d.z, d.y) / a.x : vec2(d.z, d.y) / a.x;
    else if (a.y >= a.z)               t = (d.y > 0.0) ? vec2(d.x, -d.z) / a.y : vec2(d.x, d.z) / a.y;
    else                               t = (d.z > 0.0) ? vec2(d.x, d.y) / a.z : vec2(-d.x, d.y) / a.z;
    return (atan(t) / 0.78539816 + 1.0) * 0.5;
  }
`;

export function terrainMaterial(uniforms) {
  return new THREE.ShaderMaterial({
    uniforms,
    vertexShader: /* glsl */ `
      attribute vec4 aOre;
      varying vec3 vWorld;
      varying vec3 vNormal;
      varying vec4 vOre;
      void main() {
        vec4 wp = modelMatrix * vec4(position, 1.0);
        vWorld = wp.xyz;
        vNormal = normalize(mat3(modelMatrix) * normal);
        vOre = aOre;
        gl_Position = projectionMatrix * viewMatrix * wp;
      }
    `,
    fragmentShader: /* glsl */ `
      precision highp float;

      uniform vec3 uMassColor, uTechColor;
      uniform float uRadius, uFlatR, uAmplitude, uTime;
      uniform vec3 uBuildOrigin;
      uniform float uBuildActive, uBuildRadius, uGridLevel;

      varying vec3 vWorld;
      varying vec3 vNormal;
      varying vec4 vOre;

      ${GLSL_NOISE}
      ${GLSL_SURFACE}
      ${GLSL_CUBESPHERE}
      ${GLSL_POLLUTION}

      void main() {
        vec3 up = normalize(vWorld);
        float radius = length(vWorld);
        float elev = radius - uRadius;
        vec3 geoN = normalize(vNormal);
        vec3 N = geoN;
        vec3 toCam = cameraPosition - vWorld;
        float dist = length(toCam);
        vec3 V = toCam / max(dist, 0.0001);

        // The Flats: basins of solidified coolant. Computed up here because it
        // has to suppress the grain below — a bumped, glittering plain reads as
        // open water, and the player needs to know at a glance that this is
        // ground they can drive on.
        float flats = 1.0 - smoothstep(0.0, 14.0, radius - uFlatR);

        // --- metre-scale material -------------------------------------------
        // The vertex grid gets down to ~0.8 m, which is still big enough to
        // read as smooth plastic up close. This is the layer that puts grit on
        // it, and it fades out with distance so it never aliases into sparkle.
        float detailFade = (1.0 - smoothstep(55.0, 300.0, dist)) * (1.0 - flats * 0.92);
        float grain = 0.0;
        if (detailFade > 0.002) {
          // Roughness varies in ~160 m patches. Applying the same grain
          // everywhere is what makes procedural ground look like sandpaper:
          // real terrain has scoured flats next to broken-up fields, and it is
          // the CONTRAST between them that reads as material.
          float roughField = fbm3(vWorld * 0.0062, 3);
          float rough = mix(0.18, 1.30, smoothstep(0.34, 0.66, roughField));

          // Strength is the whole game here. The gradient of an fbm across a
          // 0.4 m step is around 0.1, so anything past ~4 swamps the real
          // surface normal and the ground turns into camouflage blotches
          // instead of rock.
          float e = 0.4;
          vec3 t1 = normalize(cross(N, vec3(0.0, 1.0, 0.0) + N.yzx * 0.3));
          vec3 t2 = cross(N, t1);
          float h0 = fbm3(vWorld * 0.85, 3);
          float h1 = fbm3((vWorld + t1 * e) * 0.85, 3);
          float h2 = fbm3((vWorld + t2 * e) * 0.85, 3);
          N = normalize(N - (t1 * (h1 - h0) + t2 * (h2 - h0)) * 1.7 * rough * detailFade);
          grain = (h0 - 0.5) * detailFade * rough;
        }

        // --- albedo ----------------------------------------------------------
        float slope = 1.0 - clamp(dot(N, up), 0.0, 1.0);
        float hN = clamp((elev + uAmplitude * 0.35) / (uAmplitude * 1.35), 0.0, 1.0);

        vec3 deep = vec3(0.022, 0.028, 0.046);
        vec3 mid  = vec3(0.074, 0.084, 0.112);
        vec3 high = vec3(0.182, 0.192, 0.219);
        vec3 rock = mix(deep, mid, smoothstep(0.10, 0.55, hN));
        rock = mix(rock, high, smoothstep(0.62, 0.95, hN));

        // Exposed strata on anything steep. Banding by elevation is what makes
        // a cliff read as rock rather than as a grey ramp.
        float strata = sin(elev * 0.42 + fbm3(vWorld * 0.05, 2) * 9.0) * 0.5 + 0.5;
        rock = mix(rock, rock * (0.70 + 0.55 * strata), smoothstep(0.28, 0.78, slope));
        rock *= 1.0 + grain * 0.30;

        // Matte, not glassy. A broad low-exponent sheen says "flat mineral
        // pan"; a tight bright one says "lake", and this is not a lake.
        rock = mix(rock, vec3(0.030, 0.052, 0.070), flats);

        vec3 col = litRock(rock, N, geoN, vWorld, up, V,
                           mix(24.0, 46.0, flats), mix(0.05, 0.13, flats));

        // --- ore -------------------------------------------------------------
        vec3 oreCol = uMassColor * vOre.x + uTechColor * vOre.y;
        float veinAmt = vOre.x + vOre.y;
        if (veinAmt > 0.001) {
          // Crisp the vertex-interpolated vein up with fragment noise, and give
          // it a slow pulse so the ground feels alive rather than painted.
          float crisp = smoothstep(0.30, 0.80, veinAmt) *
                        (0.45 + 0.55 * fbm3(vWorld * 1.6, 2));
          float pulse = 0.82 + 0.18 * sin(uTime * 1.3 + elev * 0.08);
          col += oreCol * crisp * 0.95 * pulse;
          col = mix(col, oreCol * 0.16, crisp * 0.30);
        }
        // Broad field glow, safe at any level of detail: the far side of a
        // valley still shows you there is something worth stopping for.
        vec3 field = uMassColor * vOre.z + uTechColor * vOre.w;
        col += field * 0.022 * (0.55 + 0.45 * max(dot(N, uSunDir), 0.0));

        // --- pollution --------------------------------------------------------
        // Applied after the ore glow on purpose: a vein under a thick cloud
        // should look poisoned, not cheerful.
        col = stain(col, pollutionAt(vWorld), max(dot(N, uSunDir), 0.0));

        // --- build grid -------------------------------------------------------
        // Only while anchored, and only near the core. These are the real cells
        // planet.cellAt() returns, so what you see is what you can build on.
        // (No backticks in here — this whole shader is a template literal, and
        // one stray backtick ends it mid-string.)
        if (uBuildActive > 0.001) {
          float ring = 1.0 - smoothstep(uBuildRadius * 0.72, uBuildRadius, distance(vWorld, uBuildOrigin));
          if (ring > 0.001) {
            vec2 cell = sphereToFaceUV(up) * exp2(uGridLevel);
            vec2 f = abs(fract(cell) - 0.5);
            float edge = max(f.x, f.y);
            float w = fwidth(edge) * 1.6 + 0.004;
            float line = smoothstep(0.5 - w, 0.5, edge);
            col += uRimColor * line * ring * uBuildActive * 0.20;
          }
        }

        gl_FragColor = vec4(aerial(col, vWorld, V), 1.0);
      }
    `,
    side: THREE.FrontSide,
  });
}

// Boulders and shards standing on the ground. Instanced, and lit by exactly the
// same code as the terrain underneath them.
export function rockMaterial(uniforms) {
  return new THREE.ShaderMaterial({
    uniforms,
    vertexShader: /* glsl */ `
      varying vec3 vWorld;
      varying vec3 vNormal;
      varying float vTint;
      void main() {
        mat4 m = modelMatrix;
        #ifdef USE_INSTANCING
          m = m * instanceMatrix;
        #endif
        vec4 wp = m * vec4(position, 1.0);
        vWorld = wp.xyz;
        vNormal = normalize(mat3(m) * normal);
        // Per-instance shade variation, derived from where the instance sits so
        // it is stable across rebuilds and costs no extra attribute.
        vTint = fract(dot(wp.xyz, vec3(0.0173, 0.0231, 0.0197)));
        gl_Position = projectionMatrix * viewMatrix * wp;
      }
    `,
    fragmentShader: /* glsl */ `
      precision highp float;
      varying vec3 vWorld;
      varying vec3 vNormal;
      varying float vTint;

      ${GLSL_SURFACE}
      ${GLSL_POLLUTION}

      void main() {
        vec3 up = normalize(vWorld);
        vec3 N = normalize(vNormal);
        vec3 toCam = cameraPosition - vWorld;
        vec3 V = toCam / max(length(toCam), 0.0001);

        // Slightly lighter and warmer than the ground, so boulders separate
        // from what they are standing on instead of dissolving into it.
        vec3 albedo = mix(vec3(0.042, 0.048, 0.066), vec3(0.098, 0.100, 0.112), vTint);
        // Sun-scoured tops, dark undersides.
        albedo *= 0.86 + 0.24 * clamp(dot(N, up), 0.0, 1.0);

        vec3 col = litRock(albedo, N, N, vWorld, up, V, 34.0, 0.06);
        col = stain(col, pollutionAt(vWorld), max(dot(N, uSunDir), 0.0));
        gl_FragColor = vec4(aerial(col, vWorld, V), 1.0);
      }
    `,
    side: THREE.FrontSide,
  });
}

// The flora. One geometry per species carries trunk and canopy together, told
// apart by `aPart`, so a whole tree is a single instanced draw.
//
// The canopy is emissive on purpose: it is the only thing besides your own hull
// and the ore veins that is visible on the night side, and it is what makes a
// grove somewhere you can navigate by after dark.
export function treeMaterial(uniforms) {
  return new THREE.ShaderMaterial({
    uniforms,
    vertexShader: /* glsl */ `
      attribute float aPart;
      uniform float uTime;
      varying vec3 vWorld;
      varying vec3 vNormal;
      varying float vPart;
      varying float vSeed;
      varying float vHue;

      ${GLSL_NOISE}

      void main() {
        mat4 m = modelMatrix;
        #ifdef USE_INSTANCING
          m = m * instanceMatrix;
        #endif
        vec4 wp = m * vec4(position, 1.0);
        vec3 ip = m[3].xyz;                       // where this tree stands

        // Per-tree variation (size of sway, pulse phase, bark tone).
        vSeed = fract(dot(ip, vec3(0.0173, 0.0231, 0.0197)));
        // Hue is SPATIAL, not per-tree: a low-frequency field about 300 m
        // across, so a whole grove is one colour. Deriving it from a per-tree
        // hash instead gives a fruit salad where every neighbouring tree
        // disagrees, which reads as plastic.
        vHue = fbm3(ip * 0.0035, 2);

        // Only the canopy sways, and only a little. Amplitude is in metres.
        if (aPart > 0.5) {
          float ph = uTime * 0.9 + vSeed * 40.0;
          wp.xyz += vec3(sin(ph), 0.0, cos(ph * 0.83)) * 0.16;
        }
        vWorld = wp.xyz;
        vNormal = normalize(mat3(m) * normal);
        vPart = aPart;
        gl_Position = projectionMatrix * viewMatrix * wp;
      }
    `,
    fragmentShader: /* glsl */ `
      precision highp float;
      uniform float uTime;
      varying vec3 vWorld;
      varying vec3 vNormal;
      varying float vPart;
      varying float vSeed;
      varying float vHue;

      ${GLSL_SURFACE}
      ${GLSL_POLLUTION}

      void main() {
        vec3 up = normalize(vWorld);
        vec3 N = normalize(vNormal);
        vec3 toCam = cameraPosition - vWorld;
        vec3 V = toCam / max(length(toCam), 0.0001);

        vec3 col;
        if (vPart > 0.5) {
          float h = smoothstep(0.38, 0.62, vHue);
          // Two separate colours doing two jobs. The albedo is a muted, mostly
          // desaturated foliage that takes real light and shows the shape of
          // the canopy; the glow is the saturated neon. Making the albedo the
          // neon colour is what turned these into flat plastic.
          vec3 leaf = mix(vec3(0.055, 0.115, 0.130), vec3(0.098, 0.062, 0.135), h);
          vec3 glow = mix(vec3(0.10, 0.78, 1.00), vec3(0.62, 0.28, 1.00), h);

          col = litRock(leaf, N, N, vWorld, up, V, 18.0, 0.05);

          // Bioluminescence is nearly invisible at noon and takes over at
          // night — which is when it earns its place, because a grove becomes
          // something you can navigate by in the dark.
          float night = 1.0 - smoothstep(-0.18, 0.28, dot(up, uSunDir));
          float pulse = 0.84 + 0.16 * sin(uTime * 0.8 + vSeed * 30.0);
          col += glow * (0.045 + night * 0.42) * pulse;
        } else {
          vec3 bark = mix(vec3(0.030, 0.032, 0.044), vec3(0.062, 0.058, 0.070), vSeed);
          col = litRock(bark, N, N, vWorld, up, V, 26.0, 0.04);
        }
        // Choking. Pollution both stains the canopy and puts its glow out,
        // which is the visible cost of parking a drill line in a grove.
        float pol = pollutionAt(vWorld);
        col = stain(col, pol, max(dot(N, uSunDir), 0.0));
        gl_FragColor = vec4(aerial(col, vWorld, V), 1.0);
      }
    `,
    side: THREE.DoubleSide,   // open-ended trunks read wrong from the inside
  });
}
