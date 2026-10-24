/* global window */
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";

let renderer;
let scene;
let camera;
let root;
let canvas;
let lastUrl = "";
let raf = 0;
const loader = new GLTFLoader();

function disposeHierarchy(obj) {
  if (!obj) return;
  obj.traverse((o) => {
    if (o.geometry) o.geometry.dispose();
    if (o.material) {
      if (Array.isArray(o.material)) o.material.forEach((m) => m.dispose());
      else o.material.dispose();
    }
  });
}

function ensureScene(wrap) {
  if (!wrap) return;
  if (!renderer) {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: "low-power" });
    renderer.setClearColor(0x1e1610, 1);
    canvas = renderer.domElement;
    canvas.id = "sprite-mesh-three-canvas";
    canvas.style.cssText = "width:100%;height:160px;display:block;border-radius:6px;";
    wrap.innerHTML = "";
    wrap.appendChild(canvas);
    scene = new THREE.Scene();
    scene.add(new THREE.AmbientLight(0xffffff, 0.72));
    const d = new THREE.DirectionalLight(0xffeed9, 0.95);
    d.position.set(60, 120, 40);
    scene.add(d);
    camera = new THREE.PerspectiveCamera(38, 1, 0.2, 5000);
    camera.position.set(160, 110, 220);
    camera.lookAt(0, 35, 0);
    root = new THREE.Group();
    scene.add(root);
  }
  if (canvas && canvas.parentElement !== wrap) {
    wrap.innerHTML = "";
    wrap.appendChild(canvas);
  }
  const rect = wrap.getBoundingClientRect();
  const rw = Math.max(120, Math.floor(rect.width) || 280);
  const rh = 160;
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  renderer.setSize(rw, rh, false);
  camera.aspect = rw / rh;
  camera.updateProjectionMatrix();
}

function tick() {
  if (!renderer || !scene || !camera || !root) return;
  root.rotation.y += 0.012;
  renderer.render(scene, camera);
  raf = requestAnimationFrame(tick);
}

window.SpriteMeshPreview = {
  sync(project) {
    const wrap = document.getElementById("sprite-mesh-viewport");
    const dataUrl = project?.mesh3d?.dataUrl || "";
    if (!wrap) return;
    if (!dataUrl) {
      if (canvas && canvas.parentNode) canvas.parentNode.innerHTML = '<div class="small-readout">No mesh.</div>';
      lastUrl = "";
      if (raf) cancelAnimationFrame(raf);
      raf = 0;
      return;
    }
    ensureScene(wrap);
    if (lastUrl !== dataUrl) {
      lastUrl = dataUrl;
      while (root.children.length) {
        const o = root.children.pop();
        disposeHierarchy(o);
      }
      loader.load(
        dataUrl,
        (gltf) => {
          const m = gltf.scene;
          const box = new THREE.Box3().setFromObject(m);
          const size = new THREE.Vector3();
          box.getSize(size);
          const maxL = Math.max(size.x, size.y, size.z, 1);
          const sc = 120 / maxL;
          m.scale.setScalar(sc);
          root.add(m);
        },
        undefined,
        () => {
          wrap.innerHTML = '<div class="small-readout">Could not parse mesh.</div>';
        }
      );
    }
    if (!raf) raf = requestAnimationFrame(tick);
  },

  dispose() {
    if (raf) cancelAnimationFrame(raf);
    raf = 0;
    lastUrl = "";
    if (renderer) renderer.dispose();
    renderer = null;
    scene = null;
    camera = null;
    root = null;
    canvas = null;
  }
};
