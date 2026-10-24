import * as THREE from "three";

/**
 * @param {HTMLElement} container
 * @param {{ getDistance: () => number, getHour: () => number, getIsNight: () => boolean }} getters
 */
export function createWorld3D(container, getters) {
  const scene = new THREE.Scene();
  const w = container.clientWidth || 800;
  const h = container.clientHeight || 400;
  const camera = new THREE.PerspectiveCamera(55, w / h, 0.1, 500);
  camera.position.set(-8, 4, 12);
  camera.lookAt(0, 1, 0);

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(w, h);
  renderer.setClearColor(0x1a1510, 1);
  container.appendChild(renderer.domElement);

  const hemi = new THREE.HemisphereLight(0xaaccff, 0x442211, 0.7);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xffddaa, 0.9);
  sun.position.set(20, 40, 10);
  scene.add(sun);

  const groundGeo = new THREE.PlaneGeometry(200, 400, 32, 64);
  const groundMat = new THREE.MeshStandardMaterial({
    color: 0xc4a574,
    roughness: 0.95,
    metalness: 0.02,
    flatShading: true
  });
  const ground = new THREE.Mesh(groundGeo, groundMat);
  ground.rotation.x = -Math.PI / 2;
  ground.position.y = 0;
  scene.add(ground);

  const railMat = new THREE.MeshStandardMaterial({ color: 0x2a2a2a, roughness: 0.6 });
  const tieGeo = new THREE.BoxGeometry(2.2, 0.12, 0.6);
  const railGroup = new THREE.Group();
  for (let i = -40; i < 80; i++) {
    const tie = new THREE.Mesh(tieGeo, railMat);
    tie.position.set(0, 0.06, -i * 1.2);
    railGroup.add(tie);
  }
  const leftRail = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.12, 140), railMat);
  leftRail.position.set(-0.45, 0.12, -20);
  const rightRail = leftRail.clone();
  rightRail.position.x = 0.45;
  railGroup.add(leftRail, rightRail);
  scene.add(railGroup);

  const train = new THREE.Group();
  const body = new THREE.Mesh(
    new THREE.BoxGeometry(3, 1.4, 2.2),
    new THREE.MeshStandardMaterial({ color: 0x3a2818, roughness: 0.85 })
  );
  body.position.y = 1.1;
  train.add(body);
  const cab = new THREE.Mesh(
    new THREE.BoxGeometry(1.2, 1.6, 1.8),
    new THREE.MeshStandardMaterial({ color: 0x2a1810, roughness: 0.9 })
  );
  cab.position.set(-1.4, 1.2, 0);
  train.add(cab);
  const stack = new THREE.Mesh(
    new THREE.CylinderGeometry(0.35, 0.45, 1.2, 8),
    new THREE.MeshStandardMaterial({ color: 0x1a1010, roughness: 0.8 })
  );
  stack.position.set(1.2, 2, 0);
  train.add(stack);
  const car1 = new THREE.Mesh(
    new THREE.BoxGeometry(2.4, 1.2, 2),
    new THREE.MeshStandardMaterial({ color: 0x4a3520, roughness: 0.88 })
  );
  car1.position.set(-3.5, 0.9, 0);
  train.add(car1);
  const car2 = car1.clone();
  car2.position.x = -6.4;
  train.add(car2);
  const wheelGeo = new THREE.CylinderGeometry(0.35, 0.35, 0.2, 10);
  const wheelMat = new THREE.MeshStandardMaterial({ color: 0x111111, roughness: 0.5 });
  [1.2, -1.2, -4.2, -6.2].forEach((x) => {
    const w1 = new THREE.Mesh(wheelGeo, wheelMat);
    w1.rotation.z = Math.PI / 2;
    w1.position.set(x, 0.35, 0.85);
    train.add(w1);
    const w2 = w1.clone();
    w2.position.z = -0.85;
    train.add(w2);
  });
  train.position.set(0, 0, 0);
  scene.add(train);

  const fog = new THREE.Fog(0xd4b896, 25, 120);
  scene.fog = fog;

  const skyColor = new THREE.Color();

  function applyAtmosphere() {
    const hour = getters.getHour();
    const night = getters.getIsNight();
    const t = (hour % 24) / 24;
    if (night) {
      hemi.intensity = 0.25;
      sun.intensity = 0.15;
      renderer.setClearColor(0x0a0810, 1);
      fog.color.setHex(0x1a1520);
      groundMat.color.setHex(0x6a5845);
    } else {
      hemi.intensity = 0.65;
      sun.intensity = 0.95;
      skyColor.setHSL(0.55 + 0.05 * Math.sin(t * Math.PI * 2), 0.35, 0.55);
      renderer.setClearColor(skyColor.getHex(), 1);
      fog.color.copy(skyColor);
      groundMat.color.setHex(0xc4a574);
    }
  }

  function resize() {
    const rw = container.clientWidth || 800;
    const rh = container.clientHeight || 400;
    camera.aspect = rw / rh;
    camera.updateProjectionMatrix();
    renderer.setSize(rw, rh);
  }
  window.addEventListener("resize", resize);

  let raf = 0;
  const camTarget = new THREE.Vector3();
  function loop() {
    raf = requestAnimationFrame(loop);
    const d = getters.getDistance();
    const scroll = (d * 0.012) % 12;
    railGroup.position.z = scroll;
    train.position.y = 0.02 * Math.sin(d * 0.02);
    applyAtmosphere();
    camTarget.set(-7.5 + Math.sin(d * 0.001) * 0.3, 4, 12 + Math.cos(d * 0.0008) * 0.5);
    camera.position.lerp(camTarget, 0.05);
    camera.lookAt(0, 1.1, 0);
    renderer.render(scene, camera);
  }
  loop();

  return {
    dispose() {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
      scene.traverse((obj) => {
        if (obj.geometry) obj.geometry.dispose();
        if (obj.material) {
          const mats = Array.isArray(obj.material) ? obj.material : [obj.material];
          for (const m of mats) m.dispose();
        }
      });
      renderer.dispose();
      if (renderer.domElement.parentNode) {
        renderer.domElement.parentNode.removeChild(renderer.domElement);
      }
    },
    resize
  };
}
