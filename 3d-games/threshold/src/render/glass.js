// Rain on the window.
//
// The single most "cozy" image there is, and it costs one fragment shader. Droplets sit on the
// pane, swell, then break loose and slide, leaving a trail; density and speed follow the storm.
//
// GLSL landmines this project has already paid for elsewhere and avoids here:
//   · a backtick anywhere inside a shader written as a template literal terminates the literal and
//     kills the whole module, comments included. There are none below.
//   · `flat` is a reserved word in GLSL and cannot be a variable name.

const VERT = `
  varying vec2 vUv;
  varying vec3 vNormalW;
  varying vec3 vViewDir;
  void main() {
    vUv = uv;
    vec4 wp = modelMatrix * vec4(position, 1.0);
    vNormalW = normalize(mat3(modelMatrix) * normal);
    vViewDir = normalize(cameraPosition - wp.xyz);
    gl_Position = projectionMatrix * viewMatrix * wp;
  }
`;

const FRAG = `
  precision highp float;
  uniform float uTime;
  uniform float uRain;
  uniform float uWind;
  uniform vec3  uTint;
  uniform vec3  uSky;
  varying vec2 vUv;
  varying vec3 vNormalW;
  varying vec3 vViewDir;

  float hash21(vec2 p) {
    p = fract(p * vec2(233.34, 851.73));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
  }

  /* One layer of droplets on a jittered grid. Returns coverage and a slide trail. */
  vec2 dropLayer(vec2 uv, float t, float scale) {
    vec2 gv = uv * scale;
    vec2 id = floor(gv);
    vec2 fv = fract(gv) - 0.5;

    float rnd = hash21(id);
    float rnd2 = hash21(id + 41.7);

    /* Each drop hangs, then runs. Staggered so the pane is never uniformly busy. */
    float speed = 0.25 + rnd * 0.75;
    float phase = fract(t * speed * 0.35 + rnd2);
    float fall = smoothstep(0.15, 1.0, phase);

    vec2 c = vec2((rnd - 0.5) * 0.7, 0.42 - fall * 0.95);
    float r = (0.07 + rnd2 * 0.11) * smoothstep(0.0, 0.12, phase);
    float d = length((fv - c) * vec2(1.0, 0.85));
    float drop = smoothstep(r, r * 0.35, d);

    /* The trail it leaves behind, thinning as it goes. */
    float trailX = abs(fv.x - c.x);
    float above = smoothstep(c.y, c.y + 0.9, fv.y);
    float trail = smoothstep(r * 0.55, 0.0, trailX) * above * 0.45
                * smoothstep(0.0, 0.3, phase) * (1.0 - fall * 0.35);

    return vec2(drop, trail);
  }

  void main() {
    /* Wind pushes the whole sheet sideways. */
    vec2 uv = vUv + vec2(uWind * 0.06 * sin(uTime * 0.7), 0.0);

    vec2 a = dropLayer(uv, uTime, 12.0);
    vec2 b = dropLayer(uv + 0.37, uTime * 1.31 + 5.0, 19.0);
    float drops = max(a.x, b.x);
    float trails = max(a.y, b.y) * 0.8;
    float wet = clamp((drops + trails) * uRain, 0.0, 1.0);

    /* A drop is a tiny lens: it bends light, so it reads brighter and picks up the sky. */
    float fres = pow(1.0 - abs(dot(normalize(vNormalW), normalize(vViewDir))), 3.0);
    vec3 col = uTint;
    col = mix(col, uSky, 0.35 + fres * 0.4);
    col += uSky * wet * 0.55;
    col += vec3(1.0) * pow(drops * uRain, 3.0) * 0.30;

    /* Sheeting water makes the glass less clear, not more. */
    float alpha = 0.14 + fres * 0.30 + wet * 0.40;
    gl_FragColor = vec4(col, clamp(alpha, 0.0, 0.92));
  }
`;

export function makeGlassMaterial(THREE) {
  const m = new THREE.ShaderMaterial({
    vertexShader: VERT,
    fragmentShader: FRAG,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    uniforms: {
      uTime: { value: 0 },
      uRain: { value: 0 },
      uWind: { value: 0 },
      uTint: { value: new THREE.Color(0x8fb3c9) },
      uSky: { value: new THREE.Color(0x6d829a) },
    },
  });
  m.name = 'glass';
  return m;
}
