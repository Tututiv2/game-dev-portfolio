/* ============================================================================
   SHADER-STRING LINT.

   Every shader in this project lives inside a JS template literal. A single
   backtick inside a GLSL comment silently ENDS that string, and the rest of the
   shader is then parsed as JavaScript. What you get is
   "SyntaxError: Unexpected identifier 'plate'" pointing at a line of GLSL,
   which tells you nothing, and the page simply never boots.

   That cost two debugging rounds on day one, so now it is a lint that runs
   before every shoot. Two passes:

     1. `node --check` on every source file. This is the real JavaScript parser,
        not a heuristic, so it catches the break with certainty.
     2. If a file fails, a backtick scan points at the likely culprit line,
        because the parser's own message never does.
   ========================================================================= */

import fs from 'fs';
import path from 'path';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(HERE, '..', 'src');

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.js')) out.push(p);
  }
  return out;
}

/** backticks that are not a plausible template terminator */
function suspiciousTicks(file) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const hits = [];
  let open = false;
  lines.forEach((line, i) => {
    for (let c = 0; c < line.length; c++) {
      if (line[c] !== '`') continue;
      if (!open) { open = true; continue; }
      // a real closer is followed by nothing but punctuation
      if (!/^\s*[;,)\]}]*\s*$/.test(line.slice(c + 1))) {
        hits.push(`${i + 1}: ${line.trim().slice(0, 100)}`);
      }
      open = false;
    }
  });
  return hits;
}

const files = walk(SRC);
const bad = [];
for (const f of files) {
  try {
    execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
  } catch (e) {
    bad.push({ f, msg: (e.stderr || Buffer.from('')).toString().split('\n').slice(0, 6).join('\n') });
  }
}

if (bad.length) {
  console.log('\nSHADER LINT FAILED — a source file does not parse as JavaScript\n');
  for (const b of bad) {
    console.log('  ! ' + path.relative(SRC, b.f));
    console.log(b.msg.split('\n').map(l => '      ' + l).join('\n'));
    const hits = suspiciousTicks(b.f);
    if (hits.length) {
      console.log('      likely cause — backtick inside a shader string:');
      hits.forEach(h => console.log('        ' + h));
    }
  }
  console.log('');
  process.exit(1);
}
console.log(`  shader lint: ${files.length} files parse clean`);
