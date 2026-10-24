// Static server for Play.bat. ESM because package.json says type:module — a CommonJS
// serve.js in this folder would not load at all.
//
// The only non-obvious thing here is Cache-Control: no-cache. Without it an edited module
// keeps serving stale and you debug a file the browser never loaded.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join, normalize, extname, sep } from 'node:path';

const PORT = Number(process.argv[2]) || 5500;
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.wav': 'audio/wav',
  '.ogg': 'audio/ogg',
};

createServer(async (req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/') p = '/index.html';
  const file = join(ROOT, normalize(p).replace(/^[/\\]+/, ''));
  if (!file.startsWith(ROOT + sep) && file !== ROOT) { res.writeHead(403).end('no'); return; }
  try {
    const data = await readFile(file);
    res.writeHead(200, {
      'Content-Type': TYPES[extname(file).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(data);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain' }).end('not found: ' + p);
  }
}).listen(PORT, '127.0.0.1', () => console.log(`serving ${ROOT} on http://localhost:${PORT}/`));
