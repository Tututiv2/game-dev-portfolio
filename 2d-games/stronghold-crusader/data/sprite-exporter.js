window.createSpriteExportSlug = function createSpriteExportSlug(project) {
  const base = String(project?.projectName || "sprite-project")
    .replace(/[^a-z0-9_-]+/gi, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "")
    .toLowerCase() || "sprite-project";
  const payload = JSON.stringify(project || {});
  let h = 2166136261;
  for (let i = 0; i < payload.length; i += 1) {
    h ^= payload.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  const suffix = (h >>> 0).toString(16).slice(0, 8);
  return `${base}-${suffix}`;
};

window.buildSpriteExportPayload = function buildSpriteExportPayload(project, slug) {
  const safeSlug = String(slug || window.createSpriteExportSlug(project));
  const atlasPath = `data/generated/${safeSlug}.png`;
  const manifestPath = `data/generated/${safeSlug}.json`;
  const manifest = {
    schemaVersion: 1,
    unitType: String(project?.unitType || "militia"),
    archetype: String(project?.archetype || "unit"),
    namingPattern: String(project?.namingPattern || "unit_<anim>_<dir>_<frame>"),
    atlasPath,
    sourceImageName: String(project?.sourceImageName || "atlas-source.png"),
    frameSizeHint: { w: 64, h: 64 },
    animations: project?.animations || {},
    exportedAt: Date.now()
  };
  return { atlasPath, manifestPath, manifest };
};
