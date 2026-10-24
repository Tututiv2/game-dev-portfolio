// Tiny static server. ES modules cannot be loaded from file:// (CORS blocks
// every import), so the game has to be served over http even locally.
// Play.bat runs this and it opens the browser once the port is actually up.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const PORT = 5480;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

const server = http.createServer((req, res) => {
  let rel = decodeURIComponent(req.url.split('?')[0]);
  if (rel === '/') rel = '/index.html';

  // Keep every request inside ROOT: a path that escapes it is a 403, not a read.
  const file = path.resolve(ROOT, '.' + rel);
  if (!file.startsWith(ROOT)) {
    res.writeHead(403).end('forbidden');
    return;
  }

  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' }).end('404 ' + rel);
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      // Dev server: never cache, or an edited shader shows up as "no change".
      'Cache-Control': 'no-store',
    });
    res.end(data);
  });
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.log(`\n  Port ${PORT} is already in use — the game is probably`);
    console.log(`  already running. Open http://localhost:${PORT}/\n`);
  } else {
    console.error(e);
  }
  process.exit(1);
});

server.listen(PORT, () => {
  const url = `http://localhost:${PORT}/`;
  console.log('\n  NEON NOMAD 3D');
  console.log('  serving ' + ROOT);
  console.log('  ' + url);
  console.log('\n  (close this window to stop)\n');
  spawn('cmd', ['/c', 'start', '""', url], { detached: true, stdio: 'ignore' }).unref();
});
