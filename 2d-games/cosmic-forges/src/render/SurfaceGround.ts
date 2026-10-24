import * as THREE from "three";

const GVS = /* glsl */ `
varying float vH;
varying vec3 vPos;
varying vec3 vN;
float n2(vec2 p) { return sin(p.x*0.1)*0.5+sin(p.y*0.12)*0.5+sin(p.x*0.03+p.y*0.04); }
void main() {
  vPos = position;
  vH = n2(position.xz) * 0.35
    + sin(position.x * 0.05) * sin(position.z * 0.05) * 0.2;
  vec3 p = position + vec3(0.0, vH, 0.0);
  vN = normalize(vec3(
    n2(p.xz+vec2(0.1,0.0)) - n2(p.xz-vec2(0.1,0.0)),
    1.0,
    n2(p.xz+vec2(0.0,0.1)) - n2(p.xz-vec2(0.0,0.1))
  ));
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}
`;

const GFS = /* glsl */ `
varying float vH;
varying vec3 vPos;
varying vec3 vN;
uniform vec3 uSun;
uniform vec3 uGround;
uniform vec3 uAccent;
void main() {
  float l = 0.15 + 0.85 * max(0.0, dot(vN, uSun));
  float n = 0.5 + 0.5 * sin(vPos.x*0.08) * sin(vPos.z*0.08);
  vec3 c = mix(uGround, uAccent, 0.15 + 0.1 * n + 0.15 * (vH + 0.2));
  c *= l;
  gl_FragColor = vec4(c, 1.0);
}
`;

/**
 * Slightly bumpy ground plane with local lighting toward the sun.
 */
export function createSurfaceGround(
  size: number,
  segments: number,
  sunDir: THREE.Vector3,
  colors: { ground: THREE.Color; accent: THREE.Color }
): THREE.Mesh {
  const g = new THREE.PlaneGeometry(size, size, segments, segments);
  g.rotateX(-Math.PI / 2);
  const m = new THREE.ShaderMaterial({
    vertexShader: GVS,
    fragmentShader: GFS,
    /** Same as skydome: custom gl_FragColor without three fog blocks → disable scene fog on this pass. */
    fog: false,
    uniforms: {
      uSun: { value: sunDir.clone().normalize() },
      uGround: { value: colors.ground },
      uAccent: { value: colors.accent },
    },
  });
  return new THREE.Mesh(g, m);
}

export function updateSunUniform(mesh: THREE.Mesh, sun: THREE.Vector3): void {
  const mat = mesh.material as THREE.ShaderMaterial;
  mat.uniforms.uSun.value.copy(sun).normalize();
}
