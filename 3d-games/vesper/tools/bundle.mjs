/* ============================================================================
   BUILD THE ONE-CLICK FILE.

   Produces `VESPER.html` at the project root: a single self-contained page with
   every line of JavaScript inlined, that runs by DOUBLE-CLICKING it. No server,
   no npm, no terminal, no node — nothing for the owner to run, ever.

   Two things make that possible and both are load-bearing:
     - vite.config.js builds an IIFE, not an ES module. Chrome flatly refuses to
       load `type="module"` over file://, which is exactly the failure that made
       the viewer open to a dead black page.
     - the script is inlined rather than referenced, because a relative <script
       src> would still be a second file to keep next to the first one.

   Run:  node tools/bundle.mjs      (this is MY job, never the owner's)
   ========================================================================= */

import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'VESPER.html');

console.log('  building...');
execFileSync(process.execPath,
  [path.join(ROOT, 'node_modules', 'vite', 'bin', 'vite.js'), 'build'],
  { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'] });

const dist = path.join(ROOT, 'dist');
let html = fs.readFileSync(path.join(dist, 'orbit.html'), 'utf8');

/* ---- pull out every <script src>, inline it AT THE END OF THE BODY ------
   Vite puts the bundle in <head> as type="module". Module scripts are deferred,
   so they run after the DOM is parsed. An INLINE classic script is not: strip
   the module type and leave it in <head> and it executes before <body> exists,
   `document.getElementById('cv')` returns null, and the page dies with
   "Cannot read properties of null (reading 'width')" — which is exactly the
   dead-black-page this whole build step is meant to prevent.
   So the script is removed from wherever Vite put it and re-inserted last. */
const scripts = [];
html = html.replace(/<script\b[^>]*\bsrc="([^"]+)"[^>]*><\/script>/g, (m, src) => {
  const file = path.join(dist, src.replace(/^\//, ''));
  if (!fs.existsSync(file)) {
    throw new Error(`bundle: referenced script not found: ${src}`);
  }
  const js = fs.readFileSync(file, 'utf8');
  if (/^\s*(import|export)\s/m.test(js)) {
    throw new Error('bundle: bundle still contains ES module syntax — ' +
      'it will not run from file://. Check that rollup output.format is "iife".');
  }
  // </script> anywhere inside the JS would end the tag early
  scripts.push('<script>\n' + js.replace(/<\/script>/gi, '<\\/script>') + '\n</script>');
  return '';
});
const inlined = scripts.length;
if (inlined) {
  const block = '\n' + scripts.join('\n') + '\n';
  // A FUNCTION replacer, never a string. This cost a full debugging round:
  // String.replace interprets $&, $`, $' and $$ INSIDE THE REPLACEMENT, and
  // the replacement here is the entire minified bundle. Minified three
  // contains sequences like `$=d(M,N,k,B),$&&m(...)` — that `$&` was expanded
  // to the matched text, so the literal string "</body>" got spliced into the
  // middle of a JavaScript expression. The page then died with
  // "SyntaxError: Unexpected token '<'" pointing at minified vendor code, and
  // nothing in the source had anything wrong with it.
  //
  // It is triggered by whatever names the minifier happens to pick, so it
  // appears and disappears for unrelated reasons. A function replacer receives
  // the text verbatim and never substitutes anything.
  html = /<\/body>/i.test(html)
    ? html.replace(/<\/body>/i, () => block + '</body>')
    : html + block;
}

/* ---- inline every <link rel=stylesheet> --------------------------------- */
html = html.replace(/<link\b[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/g, (m, href) => {
  const file = path.join(dist, href.replace(/^\//, ''));
  if (!fs.existsSync(file)) return m;
  return '<style>\n' + fs.readFileSync(file, 'utf8') + '\n</style>';
});

if (!inlined) throw new Error('bundle: no scripts were inlined — nothing would run.');
if (/\bsrc="\//.test(html) || /\bhref="\/(?!\/)/.test(html)) {
  throw new Error('bundle: an absolute path survived; it will 404 over file://');
}

/* ---- prove the script survived the splice, before anyone double-clicks it --
   Pull the inlined script back OUT of the finished HTML and check it is still
   valid JavaScript. This is the guard on the $& bug above and on anything else
   that could corrupt the payload on its way into the page: the failure mode is
   a page that loads, shows the HUD, and runs nothing — indistinguishable from
   a crash, and it reached the owner once already. */
const back = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)]
  .map(m => m[1]).join('\n').replace(/<\\\/script>/gi, '</script>');
try {
  new Function(back);
} catch (e) {
  const at = /position (\d+)/.exec(String(e.message));
  const near = at ? back.slice(Math.max(0, +at[1] - 120), +at[1] + 120) : '';
  throw new Error('bundle: the INLINED script does not parse — the payload was ' +
    'corrupted on its way into the HTML.\n    ' + e.message +
    (near ? '\n    near: ...' + near.replace(/\s+/g, ' ') + '...' : ''));
}

fs.writeFileSync(OUT, html);
const kb = (fs.statSync(OUT).size / 1024).toFixed(0);
console.log(`  wrote VESPER.html  (${kb} KB, ${inlined} script inlined, parses clean)`);
