/* ============================================================================
   BUILD TREES.html — the tree bake-off, as ONE double-clickable file.

   Same two laws as tools/bundle.mjs, for the same reasons:
     - NOTHING may be a module. Chrome refuses type="module" over file://, and a
       relative <script src> would be a second file to keep next to the first.
     - three is INLINED, from the CommonJS build (node_modules/three/build/
       three.cjs), which has no require() calls at all — so wrapping it in an
       IIFE with a local `exports` gives the whole namespace as a plain global.
       three.module.js cannot be used: it imports from ./three.core.js.

   Run:  node tools/build_trees.mjs      (my job, never the owner's)
   ========================================================================= */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'tools', 'trees');
const OUT = path.join(ROOT, 'TREES.html');

const three = fs.readFileSync(path.join(ROOT, 'node_modules', 'three', 'build', 'three.cjs'), 'utf8');
if (/\brequire\s*\(/.test(three)) {
  throw new Error('build_trees: three.cjs contains require() — it is not self-contained.');
}
/* ORDER MATTERS AND IS NOT ALPHABETICAL.
   geom.js builds ICO/OCT at load time, so it needs THREE already defined; and
   grow.js calls geom.js's tube()/blob()/finish() at RUN time, so it only needs
   to be defined before app.js first grows a tree. Concatenated in this order,
   every reference resolves without a module system — which is the point, since
   Chrome will not load one over file://. */
const parts = ['geom.js', 'grow.js', 'recipes.js', 'worlds.js', 'app.js']
  .map(f => ({ f, src: fs.readFileSync(path.join(SRC, f), 'utf8') }));
let html = fs.readFileSync(path.join(SRC, 'page.html'), 'utf8');

const esc = (js) => js.replace(/<\/script>/gi, '<\\/script>');

const threeBlock =
  '<script>\n' +
  'var THREE = (function(){ "use strict";\n' +
  'var module = { exports: {} }; var exports = module.exports;\n' +
  esc(three) +
  '\nreturn module.exports; })();\n' +
  '</script>';

const appBlock = parts
  .map(p => '<script>\n/* ---- ' + p.f + ' ---- */\n' + esc(p.src) + '\n</script>')
  .join('\n');

/* A FUNCTION replacer, always. String.replace expands $&, $`, $' and $$ inside
   the replacement, and the replacement here is the whole of three — which is
   full of sequences like `$&&`. bundle.mjs lost a full debugging round to
   exactly that, with the error pointing at vendor code that was fine. */
if (!html.includes('<!--THREE-->') || !html.includes('<!--APP-->')) {
  throw new Error('build_trees: page.html is missing its <!--THREE--> / <!--APP--> markers.');
}
html = html.replace('<!--THREE-->', () => threeBlock);
html = html.replace('<!--APP-->', () => appBlock);

if (/type="module"/.test(html)) throw new Error('build_trees: a module script survived.');
if (/<script[^>]+\bsrc=/.test(html)) throw new Error('build_trees: an external script survived.');

/* Prove the payload survived the splice BEFORE anyone double-clicks it. The
   failure mode is a page that loads, shows the chrome and runs nothing, which
   is indistinguishable from a crash. */
const back = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)]
  .map(m => m[1]).join('\n').replace(/<\\\/script>/gi, '</script>');
try {
  new Function(back);
} catch (e) {
  const at = /position (\d+)/.exec(String(e.message));
  const near = at ? back.slice(Math.max(0, +at[1] - 140), +at[1] + 140) : '';
  throw new Error('build_trees: the INLINED script does not parse.\n    ' + e.message +
    (near ? '\n    near: ...' + near.replace(/\s+/g, ' ') + '...' : ''));
}

fs.writeFileSync(OUT, html);
const own = parts.reduce((a, p) => a + p.src.length, 0);
console.log(`  wrote TREES.html  (${(fs.statSync(OUT).size / 1024).toFixed(0)} KB, ` +
  `three ${(three.length / 1024).toFixed(0)} KB + ` +
  parts.map(p => `${p.f} ${(p.src.length / 1024).toFixed(0)}K`).join(' + ') +
  ` = ${(own / 1024).toFixed(0)} KB of ours, parses clean)`);
