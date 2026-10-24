// Sky, weather lighting, and lightning.
//
// The load-bearing visual decision in the whole project is COLOUR TEMPERATURE CONTRAST. The storm
// outside is 6500–7500 K, blue-grey and flat; the hearth inside is about 2200 K, orange and
// directional. Stand in the doorway and you should have warm light on one cheek and cold on the
// other. That single relationship does more work than any other visual choice here, and it is why
// the interior reads as shelter rather than as a smaller outdoors.

const SKY_VERT = `
  varying vec3 vDir;
  void main() {
    vDir = normalize(position);
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const SKY_FRAG = `
  precision highp float;
  uniform vec3 uTop;
  uniform vec3 uHorizon;
  uniform float uFlash;
  uniform float uTime;
  varying vec3 vDir;

  float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
  }

  void main() {
    float h = clamp(vDir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 col = mix(uHorizon, uTop, pow(h, 0.8));

    /* Torn cloud, so an overcast sky is not a flat gradient. */
    vec2 p = vDir.xz / max(abs(vDir.y) + 0.35, 0.35);
    float n = hash(floor(p * 3.0 + uTime * 0.01));
    float n2 = hash(floor(p * 7.0 - uTime * 0.02));
    col *= 0.9 + 0.14 * n + 0.08 * n2;

    /* Lightning lights the cloud deck from inside it. */
    col += vec3(0.72, 0.78, 0.95) * uFlash * (0.45 + 0.55 * h);

    gl_FragColor = vec4(col, 1.0);
  }
`;

export class Sky {
  constructor(THREE, scene) {
    this.THREE = THREE;
    this.scene = scene;

    this.material = new THREE.ShaderMaterial({
      vertexShader: SKY_VERT,
      fragmentShader: SKY_FRAG,
      side: THREE.BackSide,
      depthWrite: false,
      uniforms: {
        uTop: { value: new THREE.Color(0x2c3a4c) },
        uHorizon: { value: new THREE.Color(0x6a7686) },
        uFlash: { value: 0 },
        uTime: { value: 0 },
      },
    });
    this.dome = new THREE.Mesh(new THREE.SphereGeometry(400, 24, 16), this.material);
    this.dome.frustumCulled = false;
    scene.add(this.dome);

    // Cold, flat daylight through cloud. Note how HIGH these are: a storm is still daytime, and
    // the first pass at this dialled everything down until the exterior read as midnight, which
    // wrecks the whole point — the interior can only feel warm and sheltered by contrast with a
    // bright, cold, unpleasant outside.
    this.hemi = new THREE.HemisphereLight(0x8ba2b8, 0x4a5440, 1.7);
    scene.add(this.hemi);

    this.sun = new THREE.DirectionalLight(0xc3d2e4, 1.6);
    this.sun.position.set(-30, 55, -34);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(2048, 2048);
    const c = this.sun.shadow.camera;
    c.left = -22; c.right = 22; c.top = 22; c.bottom = -22; c.near = 1; c.far = 140;
    this.sun.shadow.bias = -0.0012;
    scene.add(this.sun);
    scene.add(this.sun.target);

    // The lightning bolt itself: a very bright, very brief light with no shadow cost.
    this.bolt = new THREE.DirectionalLight(0xd8e4ff, 0);
    this.bolt.position.set(20, 60, -40);
    scene.add(this.bolt);

    // The land beyond the block grid. Without it the world visibly ends in mid-air a few metres
    // past the last grass block, which no amount of fog hides.
    const ground = new THREE.Mesh(
      new THREE.PlaneGeometry(900, 900),
      new THREE.MeshStandardMaterial({ color: 0x4a6135, roughness: 1 }));
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = 0.985; // just under the grass block tops, so it cannot z-fight with them
    ground.receiveShadow = true;
    scene.add(ground);
    this.ground = ground;

    scene.fog = new THREE.FogExp2(0x6a7686, 0.008);
    this.flash = 0;
  }

  update(weather, flash, time, camera) {
    const THREE = this.THREE;
    this.dome.position.copy(camera.position);

    const storm = weather.cloud;
    // Everything darkens and desaturates toward the storm — but only as far as heavy overcast,
    // never to night.
    const top = new THREE.Color(0x6f8dab).lerp(new THREE.Color(0x39445a), storm);
    const hor = new THREE.Color(0xaebece).lerp(new THREE.Color(0x717d90), storm);
    this.material.uniforms.uTop.value.copy(top);
    this.material.uniforms.uHorizon.value.copy(hor);
    this.material.uniforms.uTime.value = time;
    this.material.uniforms.uFlash.value = flash;

    this.hemi.intensity = 1.75 - storm * 0.80;
    this.hemi.color.copy(hor);
    this.sun.intensity = 1.6 - storm * 1.16;

    // Rain thickens the air. Indoors this costs nothing, because fog is distance-based and no
    // sightline in a cabin is long enough to accumulate any — so it stops at the walls for free.
    this.scene.fog.color.copy(hor);
    this.scene.fog.density = 0.005 + weather.rain * 0.028 + storm * 0.006;

    this.bolt.intensity = flash * 6.0;
    this.flash = flash;
  }
}
