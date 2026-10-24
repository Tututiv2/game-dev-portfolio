// A static file server, stdlib only. No dependencies, no install, no build step.
// Its whole job is to make ES modules loadable, which file:// will not do.

import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { join, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const PORT = Number(process.argv[2]) || 5520;

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.md': 'text/plain; charset=utf-8',
  '.wasm': 'application/wasm',
};

createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    let p = decodeURIComponent(url.pathname);
    if (p.endsWith('/')) p += 'index.html';
    // Refuse anything that climbs out of the project folder.
    const full = join(ROOT, normalize(p).replace(/^([/\\])+/, ''));
    if (!full.startsWith(ROOT)) { res.writeHead(403).end('no'); return; }

    const s = await stat(full);
    if (s.isDirectory()) { res.writeHead(302, { Location: p + '/' }).end(); return; }

    const body = await readFile(full);
    res.writeHead(200, {
      'Content-Type': TYPES[extname(full).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(body);
  } catch (e) {
    res.writeHead(e.code === 'ENOENT' ? 404 : 500).end(String(e.message || e));
  }
}).listen(PORT, () => {
  console.log(`  serving ${ROOT}`);
  console.log(`  http://localhost:${PORT}/          the game`);
  console.log(`  http://localhost:${PORT}/?test     the spectral suite`);
});
