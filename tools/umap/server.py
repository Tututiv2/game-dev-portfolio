"""UMAP server: serves the map, and carries live agent activity onto it.

Stdlib only, on purpose -- this has to start from a double-clicked .bat on a
machine where the only interpreter with numpy is the ComfyUI environment.

Routes
------
  GET  /                        the map
  GET  /data/*                  graph.json, knn.json, pathindex.json
  GET  /api/state               agents + recent events (poll fallback)
  GET  /api/stream              server-sent events, one line per agent action
  POST /api/event               an agent did something  <- the Claude Code hook
  GET  /api/neighbors?path=...  what is semantically nearest to this file
  GET  /api/folders             the clean/flagged split index
  GET  /api/folders/<project>   one project's split
  POST /api/demo                replay a synthetic agent walk (to see the HUD)

The event endpoint is deliberately forgiving: a hook that fails is a hook that
blocks a tool call, so anything unparseable is accepted, logged as `unknown`
and answered 204 rather than argued with.
"""
import json
import os
import queue
import random
import re
import socket
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, 'web')
WEBDATA = os.path.join(WEB, 'data')
DATA = os.path.join(HERE, 'data')
sys.path.insert(0, os.path.join(HERE, 'tools'))
import common                       # noqa: E402  stdlib-only; reads instance.json
PORT = common.PORT

AGENT_TTL = 120.0          # seconds of silence before an agent is called idle
RING = 400                 # events kept for a late-joining browser

MIME = {
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
    '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2',
}

AGENT_COLORS = ['#4dd2ff', '#ff9a3c', '#7dff9e', '#ff6ec7', '#ffe066',
                '#b48cff', '#5affd6', '#ff7a7a']


# --------------------------------------------------------------------- index

class Index:
    """Resolves a filesystem path to a node in the map."""

    def __init__(self):
        self.root = ''
        # the on-disk root with its real casing; `root` is lowercased for matching
        self.root_real = common.ROOT
        self.paths = {}
        self.projects = {}
        self.nodes = []
        self.knn = {}
        self.dirs = {}
        self._text_cache = None
        self.load()

    def load(self):
        try:
            blob = json.load(open(os.path.join(WEBDATA, 'pathindex.json'), encoding='utf-8'))
            # where the map lives NOW, not where it was built: a copy that was
            # cloned or moved still resolves the absolute paths a hook sends
            self.root = common.ROOT.replace('\\', '/').lower().rstrip('/')
            self.paths = blob['paths']
            self.projects = blob['projects']
        except Exception as exc:
            print('  ! no pathindex.json yet (%s) - run Rebuild.bat' % exc)
            return
        try:
            graph = json.load(open(os.path.join(WEBDATA, 'graph.json'), encoding='utf-8'))
            self.nodes = [{'path': n['path'], 'name': n['name'], 'p': n['p'],
                           'k': n['k'], 'flagged': n['flagged'], 'lang': n['lang'],
                           'loc': n['loc']} for n in graph['nodes']]
            self.pnames = [p['id'] for p in graph['projects']]
            self.ptitles = [p['title'] for p in graph['projects']]
        except Exception:
            self.nodes, self.pnames, self.ptitles = [], [], []
        try:
            self.knn = {int(k): v for k, v in
                        json.load(open(os.path.join(WEBDATA, 'knn.json'), encoding='utf-8')).items()}
        except Exception:
            self.knn = {}
        self._text_cache = None      # a rebuild invalidates it
        # directory -> a node inside it, so a path we do not have still lands
        # somewhere sensible rather than nowhere
        self.dirs = {}
        for rel, i in self.paths.items():
            parts = rel.split('/')
            for cut in range(1, len(parts)):
                self.dirs.setdefault('/'.join(parts[:cut]), i)

    def relative(self, path):
        if not path:
            return ''
        p = str(path).replace('\\', '/').lower().strip().strip('"')
        if p.startswith(self.root):
            p = p[len(self.root):]
        return p.lstrip('/')

    def resolve(self, path):
        """(node_id, how) where how is exact | dir | project | None."""
        rel = self.relative(path)
        if not rel:
            return None, None
        if rel in self.paths:
            return self.paths[rel], 'exact'
        if rel + '/' in self.paths:
            return self.paths[rel + '/'], 'exact'
        parts = rel.split('/')
        for cut in range(len(parts) - 1, 0, -1):
            key = '/'.join(parts[:cut])
            if key in self.dirs:
                return self.dirs[key], 'dir'
        if parts[0] in self.projects:
            return None, 'project:' + parts[0]
        return None, None

    def grep(self, needle, limit=200):
        """Literal, case-insensitive search across every mapped text file.

        Contents are cached lowercased on first use -- the whole corpus is
        about 35 MB, which is nothing to hold and turns every later search into
        a memory scan. Files are read once, in map order, so the first search
        pays and the rest are instant.
        """
        needle = needle.lower()
        if self._text_cache is None:
            self._load_text_cache()
        out = []
        total = 0
        for nid, blob in self._text_cache:
            if not blob:
                continue
            count = blob.count(needle)
            if not count:
                continue
            total += 1                      # keep counting past the cap, so the
            if len(out) >= limit:           # UI can say "200 of 640" honestly
                continue                    # rather than reporting the cap as the answer
            n = self.nodes[nid]
            pos = blob.find(needle)
            start = blob.rfind('\n', 0, pos) + 1
            end = blob.find('\n', pos)
            line = blob[start:end if end != -1 else start + 160][:160].strip()
            out.append({'node': nid, 'path': n['path'], 'count': count,
                        'line': line, 'flagged': n['flagged'],
                        'project': self.pnames[n['p']] if n['p'] < len(self.pnames) else ''})
        out.sort(key=lambda r: -r['count'])
        return out, total

    def _load_text_cache(self):
        cache = []
        total = 0
        for nid, n in enumerate(self.nodes):
            if n['k'] != 'file':
                continue
            full = os.path.join(self.root_real, n['path'].replace('/', os.sep))
            try:
                with open(full, 'rb') as fh:
                    raw = fh.read(400_000)
            except OSError:
                continue
            if b'\x00' in raw[:2048]:
                continue
            try:
                text = raw.decode('utf-8', 'replace').lower()
            except Exception:
                continue
            total += len(text)
            cache.append((nid, text))
        self._text_cache = cache
        print('  search cache: %d files, %.1f MB' % (len(cache), total / 1e6))

    def project_of(self, node_id):
        if node_id is None or node_id >= len(self.nodes):
            return None
        return self.nodes[node_id]['p']


INDEX = Index()


# ---------------------------------------------------------------- event bus

class Bus:
    def __init__(self):
        self.lock = threading.Lock()
        self.subs = []
        self.ring = []
        self.agents = {}
        self.seq = 0

    def subscribe(self):
        q = queue.Queue(maxsize=500)
        with self.lock:
            self.subs.append(q)
            backlog = list(self.ring[-80:])
        return q, backlog

    def unsubscribe(self, q):
        with self.lock:
            if q in self.subs:
                self.subs.remove(q)

    def publish(self, ev):
        with self.lock:
            self.seq += 1
            ev['seq'] = self.seq
            self.ring.append(ev)
            if len(self.ring) > RING:
                del self.ring[:-RING]
            dead = []
            for q in self.subs:
                try:
                    q.put_nowait(ev)
                except queue.Full:
                    dead.append(q)
            for q in dead:
                self.subs.remove(q)
        return ev

    def touch_agent(self, aid, name, ev):
        with self.lock:
            a = self.agents.get(aid)
            if not a:
                a = {'id': aid, 'name': name,
                     'color': AGENT_COLORS[len(self.agents) % len(AGENT_COLORS)],
                     'started': time.time(), 'events': 0, 'trail': []}
                self.agents[aid] = a
            a['name'] = name or a['name']
            a['last'] = time.time()
            a['events'] += 1
            a['tool'] = ev.get('tool')
            a['path'] = ev.get('rel') or ev.get('path')
            a['node'] = ev.get('node')
            if ev.get('node') is not None:
                a['trail'].append(ev['node'])
                if len(a['trail']) > 60:
                    del a['trail'][:-60]
            return a

    def snapshot(self):
        now = time.time()
        with self.lock:
            agents = []
            for a in self.agents.values():
                agents.append(dict(a, idle=round(now - a.get('last', now), 1),
                                   active=(now - a.get('last', now)) < AGENT_TTL))
            return {'agents': sorted(agents, key=lambda a: -a.get('last', 0)),
                    'recent': list(self.ring[-60:]), 'seq': self.seq,
                    'server_time': now}


BUS = Bus()

_STALE = {'checked': 0.0, 'result': None}


def staleness():
    """How out of date is the map?

    A map of a tree somebody is actively working in goes stale, and the two
    dishonest answers are to pretend it is live or to say nothing. An
    incremental re-fit would be the third: umap-learn can `transform()` a new
    point against a saved model, but that placement is an approximation from a
    frozen model and it drifts as the corpus moves, so the map would slowly
    stop being the thing the measurements describe. Counting what changed is
    cheap and true, and the rebuild is 70 seconds.

    Cached for 30 s -- the browser polls this.
    """
    now = time.time()
    if _STALE['result'] and now - _STALE['checked'] < 30:
        return _STALE['result']
    try:
        graph_mtime = os.path.getmtime(os.path.join(WEBDATA, 'graph.json'))
    except OSError:
        return {'ok': False}
    known = set(INDEX.paths)
    changed, added, scanned = 0, 0, 0
    # walk the mapped folders only: a combined map's ROOT also holds things
    # that were never on it (game installs beside the two project folders)
    walks = (w for c in common.CONTAINERS for w in os.walk(c))
    for dirpath, dirnames, filenames in walks:
        dirnames[:] = [d for d in dirnames if not common.should_prune(d)]
        for fn in filenames:
            ext = os.path.splitext(fn)[1].lower()
            if ext not in common.TEXT_EXT or ext in common.IGNORE_EXT:
                continue
            full = os.path.join(dirpath, fn)
            rel_raw = os.path.relpath(full, common.ROOT)
            if common.is_generated(rel_raw):
                continue                    # the build's own output is not drift
            scanned += 1
            try:
                mt = os.path.getmtime(full)
            except OSError:
                continue
            if mt <= graph_mtime:
                continue
            rel = os.path.relpath(full, common.ROOT).replace('\\', '/').lower()
            if rel in known:
                changed += 1
            else:
                added += 1
    result = {'ok': True, 'built': int(graph_mtime), 'age_seconds': int(now - graph_mtime),
              'changed': changed, 'added': added, 'scanned': scanned}
    _STALE['checked'] = now
    _STALE['result'] = result
    return result


PATH_KEYS = ('file_path', 'filePath', 'path', 'notebook_path', 'target_file')


def bash_path(cmd):
    """Find the file a shell command is about.

    Quoted first, and that ordering is the whole point: half the folders in
    this portfolio have a space in the name ("Ai Game", "Neon Nomad 3D"), so an
    unquoted-token regex truncates `cd /d "D:/projects"` to
    `D:/projects/ai` -- a path that resolves to nothing.
    """
    for m in re.finditer(r'"([^"]{3,240})"|\'([^\']{3,240})\'', cmd):
        cand = m.group(1) or m.group(2)
        if '/' in cand or '\\' in cand:
            return cand
    m = re.search(r'[A-Za-z]:[\\/][^\s"\']+|(?<=\s)[\w.\-]+[\\/][\w .\-\\/]+', cmd)
    return m.group(0) if m else ''


def extract(payload):
    """Pull (tool, path, detail) out of a Claude Code hook payload.

    Hook JSON is not a stable contract across tools, so this looks in the
    obvious places and then falls back to hunting a path-looking string. It
    never raises: the caller is a hook and a raising hook stalls a tool call.
    """
    tool = payload.get('tool_name') or payload.get('tool') or ''
    if not tool:
        # SessionStart and Stop carry no tool at all; labelling them "Tool"
        # put a row of blanks in the feed
        ev = payload.get('hook_event_name') or ''
        tool = {'SessionStart': 'session', 'Stop': 'idle',
                'SubagentStop': 'subagent'}.get(ev, ev or 'Tool')
    ti = payload.get('tool_input') or payload.get('input') or {}
    path = payload.get('path') or ''
    detail = ''
    if isinstance(ti, dict):
        for k in PATH_KEYS:
            if ti.get(k):
                path = ti[k]
                break
        else:
            for k in ('pattern', 'command', 'prompt', 'query', 'url', 'description'):
                if ti.get(k):
                    detail = str(ti[k])[:200]
                    break
            if not path and isinstance(ti.get('command'), str):
                path = bash_path(ti['command'])
        if not detail:
            detail = str(ti.get('description') or ti.get('pattern')
                         or ti.get('command') or '')[:200]
    return tool, path, detail


def ingest(payload, source='hook'):
    tool, path, detail = extract(payload)
    node, how = INDEX.resolve(path)
    rel = INDEX.relative(path)
    aid = (payload.get('session_id') or payload.get('agent_id')
           or payload.get('agent') or 'agent')
    name = payload.get('agent_name') or payload.get('agent') or ''
    # A raw Claude Code payload carries no name, and two concurrent sessions
    # would both be "claude" anyway -- the tail of the session id is what tells
    # them apart in the HUD.
    if (not name or name == 'claude') and payload.get('session_id'):
        name = 'claude ' + str(payload['session_id'])[-4:]
    elif not name:
        name = aid[:8]
    ev = {
        't': time.time(), 'tool': tool, 'path': path, 'rel': rel,
        'node': node, 'how': how, 'detail': detail, 'agent': aid,
        'agent_name': name, 'source': source,
        'event': payload.get('hook_event_name') or payload.get('event') or '',
        'project': INDEX.project_of(node),
    }
    agent = BUS.touch_agent(aid, name, ev)
    ev['color'] = agent['color']
    return BUS.publish(ev)


def relay(payload):
    """Pass a hook event on to the other brains listed in instance.json.

    The hook only knows one port per folder, so a map that covers several
    folders hears about agents through the maps that own them. Off the request
    thread, short timeout, failures swallowed: a relay target that is not
    running must cost the hook nothing. The header stops a loop if two brains
    ever list each other.
    """
    import urllib.request
    body = json.dumps(payload).encode('utf-8')

    def send():
        for port in common.RELAY:
            try:
                req = urllib.request.Request(
                    'http://127.0.0.1:%d/api/event' % port, data=body, method='POST',
                    headers={'Content-Type': 'application/json', 'X-UMAP-Relayed': '1'})
                urllib.request.urlopen(req, timeout=0.5).close()
            except Exception:
                pass
    threading.Thread(target=send, daemon=True).start()


# ------------------------------------------------------------------ handler

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'UMAP/1.0'

    def log_message(self, fmt, *args):
        if '/api/stream' in (self.path or ''):
            return
        sys.stdout.write('  %s %s\n' % (self.command, self.path))

    # -- helpers
    def _send(self, code, body=b'', ctype='text/plain; charset=utf-8', extra=None):
        if isinstance(body, str):
            body = body.encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body and self.command != 'HEAD':
            self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj), 'application/json; charset=utf-8')

    def _body(self):
        try:
            n = int(self.headers.get('Content-Length') or 0)
        except ValueError:
            n = 0
        raw = self.rfile.read(n) if n else b''
        if not raw:
            return {}
        try:
            return json.loads(raw.decode('utf-8', 'replace'))
        except Exception:
            return {'raw': raw.decode('utf-8', 'replace')[:400]}

    # -- routes
    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        route = url.path
        qs = urllib.parse.parse_qs(url.query)

        if route == '/api/state':
            return self._json(BUS.snapshot())
        if route == '/api/stream':
            return self.stream()
        if route == '/api/neighbors':
            return self.neighbors(qs)
        if route == '/api/file':
            return self.peek(qs)
        if route == '/api/search':
            return self.search(qs)
        if route == '/api/folders':
            return self.serve_file(os.path.join(DATA, 'folders', '_index.json'))
        if route.startswith('/api/folders/'):
            name = urllib.parse.unquote(route[len('/api/folders/'):])
            safe = re.sub(r'[\\/:*?"<>|]', '_', name)
            return self.serve_file(os.path.join(DATA, 'folders', safe + '.json'))
        if route == '/api/staleness':
            return self._json(staleness())
        if route == '/api/health':
            return self._json({'ok': True, 'name': common.NAME, 'nodes': len(INDEX.nodes),
                               'port': PORT, 'agents': len(BUS.agents)})

        rel = route.lstrip('/') or 'index.html'
        full = os.path.normpath(os.path.join(WEB, rel))
        if not full.startswith(WEB):
            return self._send(403, 'no')
        if os.path.isdir(full):
            full = os.path.join(full, 'index.html')
        return self.serve_file(full)

    def do_HEAD(self):
        self.do_GET()

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == '/api/event':
            payload = self._body()
            try:
                ingest(payload)
            except Exception as exc:            # never argue with a hook
                sys.stdout.write('  ! event ignored: %r\n' % (exc,))
            if common.RELAY and not self.headers.get('X-UMAP-Relayed'):
                relay(payload)
            return self._send(204)
        if url.path == '/api/demo':
            body = self._body()
            threading.Thread(target=demo_walk,
                             args=(int(body.get('steps') or 28),
                                   body.get('project'),
                                   int(body.get('agents') or 2)),
                             daemon=True).start()
            return self._json({'ok': True})
        if url.path == '/api/reload':
            INDEX.load()
            return self._json({'ok': True, 'nodes': len(INDEX.nodes)})
        return self._send(404, 'no such route')

    # -- implementations
    def serve_file(self, full):
        if not os.path.isfile(full):
            return self._send(404, 'not found: ' + os.path.basename(full))
        ext = os.path.splitext(full)[1].lower()
        try:
            with open(full, 'rb') as fh:
                data = fh.read()
        except OSError as exc:
            return self._send(500, str(exc))
        return self._send(200, data, MIME.get(ext, 'application/octet-stream'))

    def search(self, qs):
        """Full-text search over the mapped files.

        The browser can already match names, symbols and tags out of graph.json,
        but a function is USED in more places than it is defined, and
        `cubeSphere` found one file that way against nine that contain it. The
        tokens in the corpus are split (cubeSphere -> cube, sphere), so they
        cannot answer a compound query either -- the honest answer is to read
        the files, which is cheap because the server is already sitting on
        them.
        """
        q = (qs.get('q') or [''])[0].strip()
        limit = min(int((qs.get('limit') or ['200'])[0]), 500)
        if len(q) < 2:
            return self._json({'ok': False, 'reason': 'query too short'}, 400)
        hits, total = INDEX.grep(q, limit=limit)
        return self._json({'ok': True, 'q': q, 'count': total, 'shown': len(hits),
                           'capped': total > len(hits), 'hits': hits})

    def peek(self, qs):
        """The first N lines of a real file, so the map shows what is IN a node.

        Read-only, and it will not step outside the portfolio root: the path is
        resolved through the same index the map was built from, so the only
        readable files are ones already ON the map.
        """
        path = (qs.get('path') or [''])[0]
        limit = min(int((qs.get('lines') or ['160'])[0]), 600)
        node, how = INDEX.resolve(path)
        if node is None or how != 'exact':
            return self._json({'ok': False, 'reason': 'not a mapped file'}, 404)
        nd = INDEX.nodes[node]
        if nd['k'] != 'file':
            return self._json({'ok': False, 'reason': 'that node is a directory of assets',
                               'count': nd['loc']}, 415)
        full = os.path.join(INDEX.root_real, nd['path'].replace('/', os.sep))
        try:
            with open(full, 'rb') as fh:
                raw = fh.read(160_000)
        except OSError as exc:
            return self._json({'ok': False, 'reason': str(exc)}, 404)
        if b'\x00' in raw[:2048]:
            return self._json({'ok': False, 'reason': 'binary'}, 415)
        text = raw.decode('utf-8', 'replace')
        lines = text.splitlines()
        return self._json({'ok': True, 'path': nd['path'], 'lang': nd['lang'],
                           'flagged': nd['flagged'], 'total': nd['loc'],
                           'truncated': len(lines) > limit,
                           'text': '\n'.join(lines[:limit])})

    def neighbors(self, qs):
        path = (qs.get('path') or [''])[0]
        k = min(int((qs.get('k') or ['8'])[0]), 24)
        node, how = INDEX.resolve(path)
        if node is None:
            return self._json({'ok': False, 'reason': 'unmapped path', 'how': how,
                               'rel': INDEX.relative(path)}, 404)
        out = []
        for nid, sim in INDEX.knn.get(node, [])[:k]:
            n = INDEX.nodes[nid]
            out.append({'path': n['path'], 'similarity': sim, 'lang': n['lang'],
                        'loc': n['loc'], 'flagged': n['flagged'],
                        'project': INDEX.pnames[n['p']] if n['p'] < len(INDEX.pnames) else ''})
        # asking the map a question is itself agent activity worth seeing
        ingest({'tool': 'MapQuery', 'tool_input': {'file_path': path},
                'session_id': (qs.get('agent') or ['map-query'])[0],
                'agent_name': (qs.get('agent') or ['map query'])[0]}, source='api')
        self_node = INDEX.nodes[node]
        return self._json({'ok': True, 'of': self_node['path'], 'how': how,
                           'neighbors': out})

    def stream(self):
        q, backlog = BUS.subscribe()
        try:
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'keep-alive')
            self.send_header('X-Accel-Buffering', 'no')
            self.end_headers()
            for ev in backlog:
                self.sse(ev, 'replay')
            self.sse(BUS.snapshot(), 'hello')
            last = time.time()
            while True:
                try:
                    ev = q.get(timeout=5.0)
                    self.sse(ev, 'act')
                except queue.Empty:
                    if time.time() - last > 14:
                        self.wfile.write(b': keepalive\n\n')
                        self.wfile.flush()
                        last = time.time()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            BUS.unsubscribe(q)

    def sse(self, obj, kind):
        data = json.dumps(obj)
        self.wfile.write(('event: %s\ndata: %s\n\n' % (kind, data)).encode('utf-8'))
        self.wfile.flush()


# --------------------------------------------------------------------- demo

def demo_walk(steps=28, project=None, agents=2):
    """Synthesise agent activity so the HUD can be seen without a live agent.

    It walks the real knn graph, so the trail it draws is a path the map
    itself says is coherent -- which is also what a real agent's trail looks
    like when it is following a thread through the code.
    """
    if not INDEX.nodes:
        return
    tools = ['Read', 'Grep', 'Edit', 'Read', 'Read', 'Write', 'Bash', 'MapQuery']
    pool = [i for i, n in enumerate(INDEX.nodes)
            if n['k'] == 'file' and not n['flagged']
            and (project is None or INDEX.pnames[n['p']] == project)]
    if not pool:
        pool = [i for i, n in enumerate(INDEX.nodes) if not n['flagged']]
    for a in range(agents):
        cur = random.choice(pool)
        aid = 'demo-%d' % (a + 1)
        name = ['scout', 'builder', 'reviewer', 'archivist'][a % 4]

        def walk(cur=cur, aid=aid, name=name, a=a):
            time.sleep(a * 0.7)
            node = cur
            for _ in range(steps):
                n = INDEX.nodes[node]
                ingest({'tool': random.choice(tools),
                        'tool_input': {'file_path': os.path.join(INDEX.root, n['path'])},
                        'session_id': aid, 'agent_name': name,
                        'hook_event_name': 'PreToolUse'}, source='demo')
                nbrs = INDEX.knn.get(node) or []
                nbrs = [x for x in nbrs if not INDEX.nodes[x[0]]['flagged']]
                node = (random.choice(nbrs[:5])[0] if nbrs and random.random() < 0.8
                        else random.choice(pool))
                time.sleep(0.45 + random.random() * 0.5)
        threading.Thread(target=walk, daemon=True).start()


# --------------------------------------------------------------------- main

def free_port(port):
    s = socket.socket()
    try:
        s.bind(('127.0.0.1', port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def already_ours(port):
    """Is the thing on this port another copy of THIS brain (not just any map)?"""
    try:
        import urllib.request
        with urllib.request.urlopen('http://127.0.0.1:%d/api/health' % port, timeout=1.5) as r:
            h = json.loads(r.read().decode())
            return h.get('ok') is True and h.get('name', 'UMAP') == common.NAME
    except Exception:
        return False


def main():
    port = PORT
    if not free_port(port):
        # Shifting to the next free port would be worse than refusing: the
        # agent hook posts to a fixed port, so a second copy on 5581 would
        # show an empty map while every event went to the first one.
        if already_ours(port):
            url = 'http://127.0.0.1:%d/' % port
            print('%s is already running on %d.' % (common.NAME, port))
            print('   %s' % url)
            if '--open' in sys.argv:
                import webbrowser
                webbrowser.open(url)
            return 0
        for alt in range(port + 1, port + 12):
            if free_port(alt):
                print('  port %d is taken by something else, using %d' % (port, alt))
                print('  NOTE: the agent hook posts to %d. Set UMAP_PORT=%d for both.'
                      % (PORT, alt))
                port = alt
                break
        else:
            print('no free port near %d' % port)
            return 1
    httpd = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    httpd.daemon_threads = True
    url = 'http://127.0.0.1:%d/' % port
    print('%s  %d nodes, %d projects' % (common.NAME, len(INDEX.nodes), len(getattr(INDEX, 'pnames', []))))
    print('      %s' % url)
    print('      POST /api/event  <- agent hook   |   POST /api/demo  <- synthetic walk')
    if '--open' in sys.argv:
        import webbrowser
        threading.Timer(0.6, webbrowser.open, args=(url,)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print('\nbye')
    return 0


if __name__ == '__main__':
    sys.exit(main())
