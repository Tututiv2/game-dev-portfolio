const fs = require("fs");
const path = require("path");

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function read(filePath) {
  return fs.readFileSync(filePath, "utf8");
}

function run() {
  const root = path.resolve(__dirname, "..");
  const gameJsPath = path.join(root, "game.js");
  const indexHtmlPath = path.join(root, "index.html");
  const schemaPath = path.join(root, "data", "sprite-project-schema.js");
  const registryPath = path.join(root, "data", "sprite-registry.js");
  const exporterPath = path.join(root, "data", "sprite-exporter.js");
  const generatedReadmePath = path.join(root, "data", "generated", "README.md");

  assert(fs.existsSync(gameJsPath), "game.js missing");
  assert(fs.existsSync(indexHtmlPath), "index.html missing");
  assert(fs.existsSync(schemaPath), "sprite-project-schema.js missing");
  assert(fs.existsSync(registryPath), "sprite-registry.js missing");
  assert(fs.existsSync(exporterPath), "sprite-exporter.js missing");
  assert(fs.existsSync(generatedReadmePath), "data/generated README missing");

  const gameJs = read(gameJsPath);
  const indexHtml = read(indexHtmlPath);
  const schemaJs = read(schemaPath);
  const registryJs = read(registryPath);
  const exporterJs = read(exporterPath);

  assert(indexHtml.includes("screen-sprite-editor"), "Sprite editor template missing in index.html");
  assert(indexHtml.includes("go-sprite-editor"), "Main menu sprite editor action missing");
  assert(indexHtml.includes("go-item-editor"), "Main menu item editor action missing");
  assert(indexHtml.includes("sprite-onion-enabled"), "Onion skin control missing");
  assert(indexHtml.includes("sprite-onion-opacity"), "Onion opacity control missing");
  assert(indexHtml.includes("sprite-ghost-opacity"), "Ghost opacity control missing");
  assert(indexHtml.includes("sprite-symmetry-enabled"), "Symmetry mode control missing");
  assert(indexHtml.includes("sprite-redo"), "Redo action missing in editor toolbar");
  assert(indexHtml.includes("sprite-collision-w"), "Collision footprint controls missing");
  assert(indexHtml.includes("sprite-save-snapshot"), "Snapshot controls missing");
  assert(indexHtml.includes("screen-item-editor"), "Item editor template missing");
  assert(indexHtml.includes("item-preview-canvas"), "Item editor preview canvas missing");
  assert(indexHtml.includes("item-viewport-wrap"), "Item editor viewport wrap missing");
  assert(indexHtml.includes("item-bg-color"), "Item editor background color control missing");
  assert(indexHtml.includes("item-set-tool"), "Item editor transform tool buttons missing");
  assert(indexHtml.includes("data/sprite-project-schema.js"), "Schema script not loaded in index.html");
  assert(indexHtml.includes("data/sprite-exporter.js"), "Exporter script not loaded in index.html");
  assert(indexHtml.includes("type=\"importmap\""), "Three.js import map missing in index.html");
  assert(indexHtml.includes("data/item-scene.js"), "Item scene script not loaded in index.html");
  assert(indexHtml.includes("data/item-editor-three.js"), "Item editor Three module missing in index.html");
  assert(indexHtml.includes("item-hierarchy-tree"), "Item hierarchy outliner missing in index.html");
  assert(indexHtml.includes("sprite-mesh-readout"), "Sprite mesh handoff panel missing in index.html");

  assert(schemaJs.includes("createDefaultSpriteProject"), "Default sprite project factory missing");
  assert(schemaJs.includes("namingPattern"), "Naming pattern schema missing");
  assert(schemaJs.includes("snapshots"), "Snapshot schema missing");
  assert(schemaJs.includes("normalizeSpriteProject"), "Sprite project normalizer missing");
  assert(schemaJs.includes("mesh3d"), "mesh3d field missing from sprite project schema");
  assert(registryJs.includes("SPRITE_RUNTIME_REGISTRY"), "Sprite runtime registry missing");
  assert(exporterJs.includes("createSpriteExportSlug"), "Sprite export slug helper missing");
  assert(exporterJs.includes("buildSpriteExportPayload"), "Sprite export payload builder missing");

  assert(gameJs.includes("mountSpriteEditor"), "Sprite editor mount hook missing in game.js");
  assert(gameJs.includes("mountItemEditor"), "Item editor mount hook missing in game.js");
  assert(gameJs.includes("renderItemEditorPreview"), "Item editor preview renderer missing");
  assert(gameJs.includes("item-export-json"), "Item editor export action missing");
  assert(gameJs.includes("resizeItemEditorCanvas"), "Item editor canvas resize helper missing");
  assert(gameJs.includes("item-set-tool"), "Item tool action handler missing");
  assert(gameJs.includes("item-open-in-animator"), "Animator handoff action missing");
  assert(gameJs.includes("consumeAnimatorMeshHandoff"), "Animator handoff consumer missing");
  assert(gameJs.includes("MissionThreeLayer"), "Mission WebGL layer hook missing");
  assert(gameJs.includes("validateSpriteProject"), "Sprite validation function missing");
  assert(gameJs.includes("renderRuntimeSpriteEntity"), "Runtime sprite render hook missing");
  assert(gameJs.includes("applySpriteImportPreset"), "Import preset pipeline missing");
  assert(gameJs.includes("buildSpriteFrameName"), "Naming convention helper missing");
  assert(gameJs.includes("undoSpriteEditorState"), "Undo stack logic missing");
  assert(gameJs.includes("redoSpriteEditorState"), "Redo stack logic missing");
  assert(gameJs.includes("applySymmetryForActiveFrame"), "Symmetry mirroring logic missing");
  assert(gameJs.includes("drag.mode === \"axis\""), "Symmetry axis drag handling missing");
  assert(gameJs.includes("setupSpritePreviewCanvasInput"), "Preview drag input setup missing");
  assert(gameJs.includes("sprite-export-atlas"), "Sprite export action missing");
  assert(gameJs.includes("sprite-run-smoke"), "Sprite smoke action missing");

  console.log("Sprite editor smoke checks passed.");
}

try {
  run();
  process.exit(0);
} catch (err) {
  console.error("Sprite editor smoke checks failed.");
  console.error(err.message);
  process.exit(1);
}
