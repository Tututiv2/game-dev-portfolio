/* global window */
(function () {
  function partId() {
    return `p_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 9)}`;
  }

  function partsById(project) {
    const map = {};
    (project.parts || []).forEach((p) => {
      if (p && p.id) map[p.id] = p;
    });
    return map;
  }

  function getPartChain(part, project) {
    const byId = partsById(project);
    const chain = [];
    let c = part;
    const seen = new Set();
    while (c && !seen.has(c.id)) {
      seen.add(c.id);
      chain.unshift(c);
      c = c.parentId ? byId[c.parentId] : null;
    }
    return chain;
  }

  /** World position + total yaw (Y-axis) for mesh vertices: R_y(totalYaw)*v_local + pos */
  function getWorldPose(part, project) {
    const chain = getPartChain(part, project);
    let wx = 0;
    let wy = 0;
    let wz = 0;
    let wYaw = 0;
    for (let i = 0; i < chain.length; i += 1) {
      const node = chain[i];
      const rad = (wYaw * Math.PI) / 180;
      const lx = Number(node.x) || 0;
      const ly = Number(node.y) || 0;
      const lz = Number(node.z) || 0;
      wx += lx * Math.cos(rad) - lz * Math.sin(rad);
      wy += ly;
      wz += lx * Math.sin(rad) + lz * Math.cos(rad);
      wYaw += Number(node.yaw) || 0;
    }
    return {
      x: wx,
      y: wy,
      z: wz,
      yaw: wYaw,
      w: Number(part.w) || 40,
      h: Number(part.h) || 40,
      d: Number(part.d) || 40,
      shape: part.shape || "box",
      color: part.color || "#a88d5f",
      id: part.id,
      name: part.name || "part"
    };
  }

  function cross(a, b) {
    return {
      x: a.y * b.z - a.z * b.y,
      y: a.z * b.x - a.x * b.z,
      z: a.x * b.y - a.y * b.x
    };
  }

  function len3(v) {
    return Math.hypot(v.x, v.y, v.z);
  }

  function normalize3(v) {
    const L = len3(v);
    if (L < 1e-9) return { x: 0, y: 1, z: 0 };
    return { x: v.x / L, y: v.y / L, z: v.z / L };
  }

  /** Camera-relative move: screen dx,dy -> world delta (full 3D) */
  function cameraDeltaWorld(project, dxPx, dyPx, k) {
    const yawRad = (Number(project.camera.yaw || 0) * Math.PI) / 180;
    const pitchRad = (Number(project.camera.pitch || 0) * Math.PI) / 180;
    const fx = -Math.sin(yawRad) * Math.cos(pitchRad);
    const fy = Math.sin(pitchRad);
    const fz = -Math.cos(yawRad) * Math.cos(pitchRad);
    const forward = normalize3({ x: fx, y: fy, z: fz });
    const worldUp = { x: 0, y: 1, z: 0 };
    let right = normalize3(cross(worldUp, forward));
    if (len3(right) < 1e-6) right = { x: 1, y: 0, z: 0 };
    const upM = normalize3(cross(forward, right));
    return {
      x: k * (right.x * dxPx - upM.x * dyPx),
      y: k * (right.y * dxPx - upM.y * dyPx),
      z: k * (right.z * dxPx - upM.z * dyPx)
    };
  }

  /** True if startId is startId or any ancestor is ancestorId */
  function isNodeUnderAncestor(project, startId, ancestorId) {
    if (!ancestorId || !startId) return false;
    const byId = partsById(project);
    let c = byId[startId];
    const seen = new Set();
    while (c && !seen.has(c.id)) {
      seen.add(c.id);
      if (c.id === ancestorId) return true;
      c = c.parentId ? byId[c.parentId] : null;
    }
    return false;
  }

  function wouldReparentCycle(project, childId, newParentId) {
    if (!newParentId) return false;
    if (newParentId === childId) return true;
    return isNodeUnderAncestor(project, newParentId, childId);
  }

  function validateParents(project) {
    const ids = new Set((project.parts || []).map((p) => p.id));
    (project.parts || []).forEach((p) => {
      if (p.parentId && !ids.has(p.parentId)) p.parentId = null;
      if (p.parentId === p.id) p.parentId = null;
      if (p.parentId && wouldReparentCycle(project, p.id, p.parentId)) p.parentId = null;
    });
  }

  function exportSceneJson(project) {
    return JSON.stringify(
      {
        schema: "shc-item-scene-v1",
        exportedAt: Date.now(),
        projectName: project.projectName,
        category: project.category,
        parts: project.parts
      },
      null,
      2
    );
  }

  window.ItemScene = {
    partId,
    partsById,
    getPartChain,
    getWorldPose,
    cross,
    normalize3,
    cameraDeltaWorld,
    isNodeUnderAncestor,
    wouldReparentCycle,
    validateParents,
    exportSceneJson
  };
})();
