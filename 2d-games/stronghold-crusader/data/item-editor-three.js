/* global window */
import * as THREE from "three";
import { GLTFExporter } from "three/addons/exporters/GLTFExporter.js";

const DEG2RAD = Math.PI / 180;

let scene;
let renderer;
let camera;
let rootGroup;
let canvasEl;
let wrapEl;
let gridHelper;
let lastSig = "";
const meshMap = new Map();
let pendingTextureLoads = 0;

function disposeHierarchy(group) {
  if (!group) return;
  group.traverse((obj) => {
    if (obj.geometry) obj.geometry.dispose();
    if (obj.material) {
      const mats = Array.isArray(obj.material) ? obj.material : [obj.material];
      mats.forEach((mat) => {
        if (mat.map) mat.map.dispose();
        mat.dispose();
      });
    }
  });
}

function hexToThreeColor(hex) {
  const c = new THREE.Color();
  try {
    c.set(String(hex || "#a88d5f"));
  } catch {
    c.set("#a88d5f");
  }
  return c;
}

function projectSignature(project) {
  return JSON.stringify(
    (project.parts || []).map((p) => [
      p.id,
      p.parentId,
      Number(p.x) || 0,
      Number(p.y) || 0,
      Number(p.z) || 0,
      Number(p.w) || 40,
      Number(p.h) || 40,
      Number(p.d) || 40,
      Number(p.yaw) || 0,
      p.color,
      p.shape,
      String(p.textureDataUrl || "")
    ])
  );
}

function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  const chunk = 0x8000;
  let binary = "";
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + chunk, bytes.length)));
  }
  return btoa(binary);
}

function toArrayBuffer(result) {
  if (!result) return null;
  if (result instanceof ArrayBuffer) return result;
  if (result instanceof Uint8Array) return result.buffer.slice(result.byteOffset, result.byteOffset + result.byteLength);
  return null;
}

function finishPendingLoad() {
  pendingTextureLoads = Math.max(0, pendingTextureLoads - 1);
}

function applyTextureToMesh(mesh, dataUrl, baseColorHex, selectedPartId) {
  const mat = mesh.material;
  if (!mat || !dataUrl || !String(dataUrl).startsWith("data:")) {
    if (mat && mat.map) {
      mat.map.dispose();
      mat.map = null;
    }
    if (mat) mat.color.copy(hexToThreeColor(baseColorHex));
    return;
  }
  pendingTextureLoads += 1;
  const loader = new THREE.TextureLoader();
    loader.load(
    dataUrl,
    (texture) => {
      texture.colorSpace = THREE.SRGBColorSpace;
      texture.wrapS = THREE.RepeatWrapping;
      texture.wrapT = THREE.RepeatWrapping;
      texture.flipY = true;
      texture.needsUpdate = true;
      texture.minFilter = THREE.LinearMipmapLinearFilter;
      texture.magFilter = THREE.LinearFilter;
      texture.generateMipmaps = true;
      if (renderer && typeof renderer.capabilities?.getMaxAnisotropy === "function") {
        texture.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
      }
      if (mat.map) mat.map.dispose();
      mat.map = texture;
      /* Tint texture with part color (map × color). White-only blocked paint after texturing. */
      mat.color.copy(hexToThreeColor(baseColorHex));
      mat.needsUpdate = true;
      finishPendingLoad();
      /* Map loads async; selection highlight must re-run now that mat.map exists. */
      applySelectionHighlight(selectedPartId != null ? String(selectedPartId) : null);
      if (renderer && scene && camera && window.ItemEditorThree.mounted) {
        window.ItemEditorThree.resize();
        renderer.render(scene, camera);
      }
    },
    undefined,
    () => {
      finishPendingLoad();
    }
  );
}

function rebuildScene(project) {
  const sig = projectSignature(project);
  if (sig === lastSig) return;
  lastSig = sig;
  disposeHierarchy(rootGroup);
  rootGroup.clear();
  meshMap.clear();

  const parts = project.parts || [];
  const groups = new Map();

  parts.forEach((p) => {
    const g = new THREE.Group();
    g.userData.partId = p.id;
    const w = Math.max(1, Number(p.w) || 40);
    const h = Math.max(1, Number(p.h) || 40);
    const d = Math.max(1, Number(p.d) || 40);
    let geo;
    if (p.shape === "cylinder") {
      const r = w * 0.5;
      geo = new THREE.CylinderGeometry(r, r, h, 16, 1);
    } else {
      geo = new THREE.BoxGeometry(w, h, d);
    }
    const mat = new THREE.MeshStandardMaterial({
      color: hexToThreeColor(p.color),
      roughness: 0.62,
      metalness: 0.04,
      envMapIntensity: 0.25,
      side: THREE.FrontSide,
      depthWrite: true,
      depthTest: true
    });
    mat.userData.partId = p.id;
    const mesh = new THREE.Mesh(geo, mat);
    mesh.userData.partId = p.id;
    g.add(mesh);
    meshMap.set(p.id, mesh);
    g.position.set(Number(p.x) || 0, Number(p.y) || 0, Number(p.z) || 0);
    g.rotation.y = (Number(p.yaw) || 0) * DEG2RAD;
    groups.set(p.id, g);
    const turl = String(p.textureDataUrl || "");
    if (turl.startsWith("data:")) {
      applyTextureToMesh(mesh, turl, p.color, project.selectedPartId || null);
    }
  });

  parts.forEach((p) => {
    const g = groups.get(p.id);
    const parentId = p.parentId && groups.has(p.parentId) ? p.parentId : null;
    if (parentId) groups.get(parentId).add(g);
    else rootGroup.add(g);
  });
}

function applySelectionHighlight(selectedId) {
  meshMap.forEach((mesh, id) => {
    const mat = mesh.material;
    if (!mat || mat.emissive === undefined) return;
    const sel = selectedId && id === selectedId;
    const hasTex = !!mat.map;
    /* Strong emissive + map looks "invisible" (washed out); keep subtle outline when textured. */
    mat.emissive.setHex(sel ? (hasTex ? 0x332211 : 0x553322) : 0x000000);
    mat.emissiveIntensity = sel ? (hasTex ? 0.12 : 0.35) : 0;
  });
}

function getSceneBounds() {
  if (!rootGroup) {
    return { center: new THREE.Vector3(0, 0, 0), radius: 28 };
  }
  rootGroup.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(rootGroup);
  if (box.isEmpty()) {
    return { center: new THREE.Vector3(0, 0, 0), radius: 28 };
  }
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const radius = 0.5 * Math.hypot(size.x, size.y, size.z);
  return { center, radius: Math.max(radius, 12) };
}

function setCameraFromProject(project) {
  const { center, radius } = getSceneBounds();
  const zoom = Math.max(0.35, Math.min(3, Number(project.camera?.zoom) || 1));
  let dist = 520 / zoom;
  /* Stay outside the hull — old fixed min distance orbited (0,0,0) while meshes could sit elsewhere or overlap. */
  const minDist = Math.max(radius * 2.35 + 36, 96);
  dist = Math.max(minDist, dist);
  const yaw = (Number(project.camera?.yaw) || 0) * DEG2RAD;
  const pitch = (Number(project.camera?.pitch) || 0) * DEG2RAD;
  const cp = Math.cos(pitch);
  const offset = new THREE.Vector3(-Math.sin(yaw) * cp * dist, Math.sin(pitch) * dist, Math.cos(yaw) * cp * dist);
  camera.near = Math.max(0.25, Math.min(radius * 0.04, 12));
  camera.far = Math.max(8000, radius * 80 + 4000);
  camera.position.copy(center).add(offset);
  camera.lookAt(center);
  camera.updateProjectionMatrix();
}

function setBackground(project) {
  const bg = String(project.backgroundColor || "#2a2115");
  try {
    scene.background = hexToThreeColor(/^#([0-9a-f]{6})$/i.test(bg) ? bg : "#2a2115");
  } catch {
    scene.background = new THREE.Color(0x2a2115);
  }
}

function updateGrid(project) {
  const show = project.showGrid !== false;
  if (!gridHelper) return;
  gridHelper.visible = show;
  const { center, radius } = getSceneBounds();
  gridHelper.position.set(center.x, center.y - Math.max(58, radius + 38), center.z);
}

function waitForPendingTextures(maxMs = 4000) {
  const t0 = performance.now();
  return new Promise((resolve) => {
    function tick() {
      if (pendingTextureLoads <= 0 || performance.now() - t0 > maxMs) resolve();
      else setTimeout(tick, 40);
    }
    tick();
  });
}

window.ItemEditorThree = {
  mounted: false,

  mount(wrap) {
    if (this.mounted) return;
    wrapEl = wrap;
    scene = new THREE.Scene();
    rootGroup = new THREE.Group();
    scene.add(rootGroup);

    camera = new THREE.PerspectiveCamera(42, 1, 3, 20000);
    camera.position.set(260, 180, 320);

    scene.add(new THREE.AmbientLight(0xffffff, 0.92));
    scene.add(new THREE.HemisphereLight(0xe8f0ff, 0x493828, 0.62));
    const dirLight = new THREE.DirectionalLight(0xffffff, 0.95);
    dirLight.position.set(120, 200, 140);
    scene.add(dirLight);

    gridHelper = new THREE.GridHelper(560, 28, 0x7a6040, 0x3a2e20);
    gridHelper.position.y = -70;
    scene.add(gridHelper);

    renderer = new THREE.WebGLRenderer({
      antialias: true,
      alpha: false,
      powerPreference: "high-performance",
      logarithmicDepthBuffer: true
    });
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    if (renderer.outputColorSpace !== undefined) {
      renderer.outputColorSpace = THREE.SRGBColorSpace;
    }
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.12;
    renderer.shadowMap.enabled = false;
    canvasEl = renderer.domElement;
    canvasEl.id = "item-three-canvas";
    canvasEl.style.cssText = "display:block;width:100%;height:100%;touch-action:none;";
    wrap.insertBefore(canvasEl, wrap.firstChild);

    const canvas2d = document.getElementById("item-preview-canvas");
    if (canvas2d) {
      canvas2d.style.display = "none";
      canvas2d.setAttribute("aria-hidden", "true");
    }

    this.resize();
    this.mounted = true;
    lastSig = "";
  },

  isReady() {
    return this.mounted && !!renderer;
  },

  sync(project) {
    if (!this.mounted || !renderer) return;
    rebuildScene(project);
    applySelectionHighlight(project.selectedPartId || null);
    setBackground(project);
    updateGrid(project);
    setCameraFromProject(project);
    this.resize();
    renderer.render(scene, camera);
  },

  /** Force rebuild (e.g. before export after async texture loads). */
  invalidateRebuild() {
    lastSig = "";
  },

  resize() {
    if (!renderer || !wrapEl) return;
    const rect = wrapEl.getBoundingClientRect();
    const w = Math.max(4, Math.floor(rect.width));
    const h = Math.max(4, Math.floor(rect.height));
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    renderer.setPixelRatio(dpr);
    renderer.setSize(w, h, false);
    camera.aspect = w / Math.max(1, h);
    camera.updateProjectionMatrix();
  },

  pickPartId(clientX, clientY) {
    if (!this.mounted || !camera || !canvasEl) return null;
    const raycaster = new THREE.Raycaster();
    const rect = canvasEl.getBoundingClientRect();
    const x = ((clientX - rect.left) / Math.max(1, rect.width)) * 2 - 1;
    const y = -((clientY - rect.top) / Math.max(1, rect.height)) * 2 + 1;
    raycaster.setFromCamera(new THREE.Vector2(x, y), camera);
    const hits = raycaster.intersectObjects(rootGroup.children, true);
    for (let i = 0; i < hits.length; i += 1) {
      let o = hits[i].object;
      while (o) {
        if (o.userData && o.userData.partId) return String(o.userData.partId);
        o = o.parent;
      }
    }
    return null;
  },

  dispose() {
    if (!this.mounted) return;
    disposeHierarchy(rootGroup);
    if (renderer) {
      try {
        renderer.dispose();
      } catch {
        /* ignore */
      }
    }
    if (canvasEl && canvasEl.parentNode) canvasEl.parentNode.removeChild(canvasEl);
    canvasEl = null;
    renderer = null;
    scene = null;
    camera = null;
    rootGroup = null;
    meshMap.clear();
    pendingTextureLoads = 0;
    lastSig = "";
    const canvas2d = document.getElementById("item-preview-canvas");
    if (canvas2d) {
      canvas2d.style.display = "";
      canvas2d.removeAttribute("aria-hidden");
    }
    this.mounted = false;
  },

  async exportEmbeddedGltfDataUrl(project) {
    if (!project || typeof project !== "object") return null;
    const wrap = document.getElementById("item-viewport-wrap");
    if (!this.mounted && wrap) {
      try {
        this.mount(wrap);
      } catch (e) {
        console.warn("ItemEditorThree mount:", e);
        return null;
      }
    }
    if (!this.mounted || !rootGroup) return null;

    try {
      lastSig = "";
      rebuildScene(project);
      applySelectionHighlight(project.selectedPartId || null);
      rootGroup.updateMatrixWorld(true);
      await waitForPendingTextures(6000);

      const exporter = new GLTFExporter();
      rootGroup.updateMatrixWorld(true);

      let result = await exporter.parseAsync(rootGroup, { binary: true, embedImages: true });
      const glbBuf = toArrayBuffer(result);
      if (glbBuf) {
        return `data:model/gltf-binary;base64,${arrayBufferToBase64(glbBuf)}`;
      }
      result = await exporter.parseAsync(rootGroup, { binary: false, embedImages: true });
      const json = JSON.stringify(result);
      return `data:model/gltf+json;charset=utf-8,${encodeURIComponent(json)}`;
    } catch (err) {
      console.warn("GLTF export failed:", err);
      try {
        const exporter = new GLTFExporter();
        const json = await exporter.parseAsync(rootGroup, { binary: false, embedImages: true });
        return `data:model/gltf+json;charset=utf-8,${encodeURIComponent(JSON.stringify(json))}`;
      } catch (e2) {
        console.warn("GLTF export fallback failed:", e2);
        return null;
      }
    }
  }
};
