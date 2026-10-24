import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { getSolarBodies, getSystemName, isBlackHoleSystem, type CelestialBody } from "../sim/universe/proceduralSystem";
import type { DevSettings } from "./devSettings";

const COL_SUN = 0xffcc66;

type BodyLodGroup = {
  group: THREE.Group;
  dot: THREE.Mesh;
  low: THREE.Mesh;
  high: THREE.Mesh;
};

/**
 * 3D solar system with three body LODs: far dot, mid low-poly, near full mesh.
 */
export class SolarView {
  readonly domElement: HTMLCanvasElement;
  private readonly scene = new THREE.Scene();
  private readonly camera: THREE.PerspectiveCamera;
  private readonly renderer: THREE.WebGLRenderer;
  private readonly controls: OrbitControls;
  private readonly bodyGroup = new THREE.Group();
  private readonly orbitGroup = new THREE.Group();
  private bodyLods: BodyLodGroup[] = [];
  private readonly _worldPos = new THREE.Vector3();
  systemSeed = 0;
  private labels: CelestialBody[] = [];
  private readonly centerSun: THREE.Mesh;
  private accretionDisk: THREE.Mesh | null = null;

  constructor(canvas: HTMLCanvasElement) {
    this.domElement = canvas;
    this.camera = new THREE.PerspectiveCamera(50, 1, 0.1, 500);
    this.camera.position.set(0, 18, 28);
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
    this.renderer.setPixelRatio(Math.min(2, window.devicePixelRatio));
    this.scene.background = new THREE.Color(0x020408);
    this.scene.add(new THREE.AmbientLight(0x444466, 0.4));
    const p = new THREE.PointLight(0xffeedd, 2.5, 200);
    p.position.set(0, 0, 0);
    this.scene.add(p);
    this.centerSun = new THREE.Mesh(
      new THREE.SphereGeometry(1.2, 32, 32),
      new THREE.MeshBasicMaterial({ color: COL_SUN })
    );
    this.scene.add(this.centerSun);
    this.scene.add(this.orbitGroup);
    this.scene.add(this.bodyGroup);
    this.controls = new OrbitControls(this.camera, this.domElement);
    this.controls.target.set(0, 0, 0);
    this.resize();
    this.rebuild();
    window.addEventListener("resize", () => this.resize());
  }

  private resize(): void {
    const w = this.domElement.clientWidth;
    const h = this.domElement.clientHeight;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h, false);
  }

  getBodiesWithLabels(): { bodies: CelestialBody[]; systemName: string } {
    const bodies = getSolarBodies(this.systemSeed);
    return { bodies, systemName: getSystemName(this.systemSeed) };
  }

  setSystemSeed(seed: number): void {
    if (this.systemSeed === seed) return;
    this.systemSeed = seed;
    this.rebuild();
  }

  private rebuild(): void {
    // Dispose GPU resources before dropping the old system, or every warp leaks
    // geometries/materials (Group.clear() does not dispose).
    const disposeChildren = (g: THREE.Group): void => {
      g.traverse((o) => {
        const m = o as THREE.Mesh;
        if (m.isMesh) {
          m.geometry.dispose();
          (m.material as THREE.Material).dispose();
        }
      });
      g.clear();
    };
    disposeChildren(this.bodyGroup);
    disposeChildren(this.orbitGroup);
    this.bodyLods = [];
    if (this.accretionDisk) {
      this.scene.remove(this.accretionDisk);
      this.accretionDisk.geometry.dispose();
      (this.accretionDisk.material as THREE.Material).dispose();
      this.accretionDisk = null;
    }
    const bh = isBlackHoleSystem(this.systemSeed);
    (this.centerSun.material as THREE.MeshBasicMaterial).color.setHex(bh ? 0x020205 : COL_SUN);
    if (bh) {
      const ad = new THREE.Mesh(
        new THREE.RingGeometry(1.4, 2.2, 64),
        new THREE.MeshBasicMaterial({
          color: 0xff5522,
          side: THREE.DoubleSide,
          transparent: true,
          opacity: 0.7,
        })
      );
      ad.rotation.x = -Math.PI / 2;
      this.accretionDisk = ad;
      this.scene.add(ad);
    }
    this.labels = getSolarBodies(this.systemSeed);
    for (const b of this.labels) {
      const r = b.kind === "gas" ? 0.4 : 0.22;
      const ang = (b.id * 0.7 + this.systemSeed * 0.01) % (Math.PI * 2);
      const x = Math.cos(ang) * b.orbit;
      const z = Math.sin(ang) * b.orbit;
      const g = new THREE.Group();
      g.position.set(x, 0, z);

      const matDot = new THREE.MeshBasicMaterial({ color: b.color, toneMapped: true });
      const matLow = new THREE.MeshStandardMaterial({
        color: b.color,
        emissive: b.color,
        emissiveIntensity: 0.12,
        metalness: 0.2,
        roughness: 0.65,
      });
      const matHigh = new THREE.MeshStandardMaterial({
        color: b.color,
        emissive: b.color,
        emissiveIntensity: 0.18,
        metalness: 0.2,
        roughness: 0.6,
      });

      const dot = new THREE.Mesh(new THREE.SphereGeometry(0.09, 5, 5), matDot);
      const low = new THREE.Mesh(new THREE.SphereGeometry(r, 8, 8), matLow);
      const high = new THREE.Mesh(new THREE.SphereGeometry(r, 22, 22), matHigh);
      g.add(dot);
      g.add(low);
      g.add(high);
      this.bodyLods.push({ group: g, dot, low, high });
      this.bodyGroup.add(g);

      const ring = new THREE.RingGeometry(b.orbit - 0.05, b.orbit + 0.05, 64);
      const rMesh = new THREE.Mesh(
        ring,
        new THREE.MeshBasicMaterial({
          color: 0x1a2a3a,
          side: THREE.DoubleSide,
          transparent: true,
          opacity: 0.4,
        })
      );
      rMesh.rotation.x = -Math.PI / 2;
      this.orbitGroup.add(rMesh);
    }
  }

  updateLods(solar: DevSettings["solar"]): void {
    this.bodyGroup.updateMatrixWorld(true);
    for (const o of this.bodyLods) {
      o.group.getWorldPosition(this._worldPos);
      const d = this.camera.position.distanceTo(this._worldPos);
      const f = solar.lodFullMax;
      const m = solar.lodMidMax;
      o.high.visible = d <= f;
      o.low.visible = d > f && d <= m;
      o.dot.visible = d > m;
      o.dot.scale.setScalar(solar.dotSize / 0.09);
    }
  }

  render(solar: DevSettings["solar"]): void {
    this.updateLods(solar);
    this.controls.update();
    this.renderer.render(this.scene, this.camera);
  }
}
