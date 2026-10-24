"""Checks for the UMAP pipeline and server.

Runs on stdlib alone so `Test.bat` works under `py -3`. The layout checks are
skipped, loudly, if numpy is absent rather than silently passing -- a suite
that goes green because it could not run the check is worse than no suite.

    py -3 tests/run_tests.py            everything it can
    py -3 tests/run_tests.py --live     also stand up a real server and poke it
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
UMAP = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(UMAP, 'tools'))
import common                       # noqa: E402  instance.json: which copy of the map this is
ROOT = common.ROOT
# the size floors scale with the folder this copy maps; instance.json can say
EXPECT = dict({'nodes': 2000, 'projects': 50, 'trust': 0.985, 'continuity': 0.975, 'global': 0.38,
               'purity': 0.75}, **(common.INSTANCE.get('expect') or {}))
CLASSIFIER = os.path.exists(os.path.join(UMAP, 'tools', 'classifier.py'))

PASS, FAIL, SKIP = [], [], []


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print('  %s %-58s %s' % ('ok  ' if cond else 'FAIL', name, detail))
    return cond


def skip(name, why):
    SKIP.append(name)
    print('  skip %-58s %s' % (name, why))


def section(t):
    print('\n== %s' % t)


# ---------------------------------------------------------------- tokeniser
def test_tokens():
    section('tokeniser and symbols')
    import common
    t = common.tokenize('def buildCubeSphere(uv): // tangent_warp RCONClient')
    check('camelCase splits', 'build' in t and 'cube' in t and 'sphere' in t, t[:6])
    check('snake_case splits', 'tangent' in t and 'warp' in t)
    check('an acronym stays one token', 'rcon' in t and 'client' in t, t)
    check('stopwords dropped', 'def' not in t and 'the' not in t)
    check('short tokens dropped', all(len(x) >= 3 for x in t))

    py = 'class Rig:\n    def solve(self):\n        pass\n\nasync def bake_atlas(n):\n    pass\n'
    s = common.symbols(py, 'python')
    check('python symbols', s == ['Rig', 'solve', 'bake_atlas'], s)

    js = 'export function fitCurve(a){}\nclass Deck {}\nconst reelIn = (x) => x;\n'
    s = common.symbols(js, 'js')
    check('js symbols', set(s) == {'fitCurve', 'Deck', 'reelIn'}, s)

    md = '# Title\n\nsome prose\n\n## Second\n'
    check('markdown headings', common.symbols(md, 'doc') == ['Title', 'Second'])
    check('markdown summary', common.summary('# Title\n\nreal prose here', 'doc') == 'Title')
    check('comment summary',
          common.summary('"""What this file is."""\nimport os\n', 'python') == 'What this file is.')
    check('no summary invented', common.summary('x = 1\ny = 2\n', 'python') == '')


# ------------------------------------------------------------------- corpus
def load_graph():
    p = os.path.join(UMAP, 'web', 'data', 'graph.json')
    if not os.path.exists(p):
        return None
    with open(p, encoding='utf-8') as fh:
        return json.load(fh)


def test_graph():
    section('the built map')
    g = load_graph()
    if not g:
        skip('graph.json', 'not built yet - run Rebuild.bat')
        return None
    n = len(g['nodes'])
    check('has nodes', n > EXPECT['nodes'], '%d nodes' % n)
    check('has projects', len(g['projects']) > EXPECT['projects'], '%d projects' % len(g['projects']))
    check('every node has both layouts',
          all(len(x['pos']) == 3 and len(x.get('pos2', [])) == 3 for x in g['nodes']))
    check('every node belongs to a real project',
          all(0 <= x['p'] < len(g['projects']) for x in g['nodes']))
    check('positions are finite',
          all(all(isinstance(v, (int, float)) and abs(v) < 1e4 for v in x['pos'])
              for x in g['nodes']))
    check('edges point at real nodes',
          all(0 <= a < n and 0 <= b < n for a, b, _ in g['file_edges']))
    check('no self edges', all(a != b for a, b, _ in g['file_edges']))

    # The map must not contain its own build products, or every rebuild changes
    # the thing being mapped and it can never be stable. `data/curated/` IS
    # mapped, because those are authored inputs.
    import common as _c
    selfmapped = [x['path'] for x in g['nodes'] if _c.is_generated(x['path'])]
    check('the map does not embed its own output', not selfmapped,
          '%d generated files on the map: %s' % (len(selfmapped), selfmapped[:2]))
    # anywhere in the tree: a combined map holds `Ai Game/UMAP/...`, and the
    # published copy ships without curated records at all
    if os.path.isdir(os.path.join(UMAP, 'data', 'curated')):
        check('but it does map its authored inputs',
              any('/umap/data/curated/' in '/' + x['path'].lower() for x in g['nodes']))
    else:
        skip('authored inputs', 'this copy ships without data/curated/')

    withsym = sum(1 for x in g['nodes'] if x.get('sym'))
    check('files carry their symbols', withsym > n * 0.25,
          '%d of %d nodes name what they define' % (withsym, n))
    withhead = sum(1 for x in g['nodes'] if x.get('head'))
    check('files carry a summary line', withhead > 300, '%d nodes' % withhead)

    check('records which engine placed the points', bool(g['meta'].get('engine')),
          g['meta'].get('engine', 'MISSING'))
    # The description of the fit must be generated from the fit, not typed. The
    # hardcoded version claimed min_dist=0.12 for a build that used 0.40.
    params = g['meta'].get('params') or {}
    algo = g['meta'].get('algorithm', '')
    check('the recorded parameters match the description',
          bool(params) and ('min_dist=%.2f' % params.get('min_dist', -1)) in algo
          and ('n_neighbors=%d' % params.get('n_neighbors', -1)) in algo,
          algo)

    # the map must not be one blob: measure how well it separates projects
    try:
        import numpy as np
    except ImportError:
        skip('layout separation', 'numpy missing')
        return g

    # The two layouts must be on the same scale, or the camera frames one of
    # them and shows empty space for the other. The relaxation pass in the
    # galaxy layout works in absolute units and blew this out once already.
    rad = {}
    for key in ('pos', 'pos2'):
        r = np.linalg.norm(np.array([x[key] for x in g['nodes']], dtype=np.float32), axis=1)
        rad[key] = (float(np.percentile(r, 98)), float(r.max()))
    ratio = max(rad['pos'][0], rad['pos2'][0]) / max(1e-6, min(rad['pos'][0], rad['pos2'][0]))
    check('both layouts are on the same scale', ratio < 2.0,
          'p98 radii %.0f vs %.0f (ratio %.2f)' % (rad['pos'][0], rad['pos2'][0], ratio))
    for key in ('pos', 'pos2'):
        p98, mx = rad[key]
        check('%s has no runaway node' % key, mx < p98 * 4,
              'max %.0f vs p98 %.0f' % (mx, p98))
    for key, floor in (('pos', 0.60), ('pos2', 0.90)):
        P = np.array([x[key] for x in g['nodes']], dtype=np.float32)
        lab = np.array([x['p'] for x in g['nodes']])
        idx = np.zeros((len(P), 5), dtype=int)
        for s in range(0, len(P), 1024):
            e = min(s + 1024, len(P))
            d = ((P[s:e, None, :] - P[None, :, :]) ** 2).sum(-1)
            d[np.arange(e - s), np.arange(s, e)] = 1e18
            idx[s:e] = np.argpartition(d, 5, axis=1)[:, :5]
        purity = float((lab[idx] == lab[:, None]).mean())
        check('%s layout groups a project together' % key, purity >= floor,
              '5-NN purity %.3f (floor %.2f)' % (purity, floor))
    return g


def test_map_quality(g):
    """Regression guard on the map itself, not on the code that makes it.

    The hyperparameters were swept (tools/sweep.py, tools/sweep_init.py) and
    the shipping numbers are recorded here as floors, a little under measured
    so ordinary corpus drift does not trip them. Global structure is the one
    that moves: it was 0.2966 before the sweep and 0.4431 after, which is the
    difference between spectral init silently falling back to random and PCA
    init being asked for explicitly.
    """
    section('map quality (regression floors)')
    if not g:
        skip('map quality', 'no graph')
        return
    try:
        import numpy as np
        from sklearn.manifold import trustworthiness
        sys.path.insert(0, os.path.join(UMAP, 'tools'))
        from compare_umap import load_vectors, purity
        from sweep import global_score
    except ImportError as exc:
        skip('map quality', '%s (needs the .venv interpreter)' % exc)
        return
    Z, labels = load_vectors()
    P = np.array([n['pos'] for n in g['nodes']], dtype=np.float32)
    if len(P) != len(Z):
        skip('map quality', 'graph and corpus are out of step - rebuild')
        return
    # floors measured on the main corpus; a much smaller folder sets its own in
    # instance.json `expect`, since fewer files is a harder neighbourhood to keep
    for name, value, floor in (
            ('trustworthiness', trustworthiness(Z, P, n_neighbors=15, metric='cosine'), EXPECT['trust']),
            ('continuity', trustworthiness(P, Z, n_neighbors=15, metric='cosine'), EXPECT['continuity']),
            ('global structure', global_score(Z, P), EXPECT['global']),
            ('project purity', purity(P, labels), EXPECT['purity'])):
        check('%s holds up' % name, value >= floor, '%.4f (floor %.3f)' % (value, floor))


def test_no_flag_layer(g):
    section('this copy carries no flagged layer')
    check('ships without the classifier', not os.path.exists(os.path.join(UMAP, 'tools', 'classifier.py')))
    check('ships without the audit',
          not os.path.exists(os.path.join(UMAP, 'data', 'curated', 'flag_audit.json')))
    if not g:
        skip('flags', 'no graph')
        return
    check('nothing on the map is flagged', not any(n['flagged'] for n in g['nodes']))
    check('the page is told so', g['meta'].get('flag_layer') is False)


# ------------------------------------------------------------------- umap
def test_umap():
    section('the UMAP itself')
    try:
        import numpy as np
        import umap3d
    except ImportError as exc:
        skip('umap maths', str(exc))
        return
    rng = np.random.default_rng(3)
    # three well separated blobs in 12 dimensions
    centres = rng.normal(0, 4, (3, 12))
    X = np.vstack([centres[i] + rng.normal(0, 0.35, (80, 12)) for i in range(3)])
    lab = np.repeat([0, 1, 2], 80)
    X = (X / np.linalg.norm(X, axis=1, keepdims=True)).astype(np.float32)

    idx, dist = umap3d.knn(X, 10)
    check('knn returns the right shape', idx.shape == (240, 10))
    check('knn finds same-blob neighbours', (lab[idx] == lab[:, None]).mean() > 0.95,
          '%.3f' % (lab[idx] == lab[:, None]).mean())
    check('knn never returns itself', not (idx == np.arange(240)[:, None]).any())
    check('distances are sorted', bool((np.diff(dist, axis=1) >= -1e-6).all()))

    sigma, rho = umap3d.smooth_knn(dist, 10)
    target = np.log2(10)
    got = np.exp(-np.maximum(dist - rho[:, None], 0) / sigma[:, None]).sum(1)
    check('smooth_knn hits log2(k)', float(np.abs(got - target).max()) < 0.02,
          'max error %.4f' % float(np.abs(got - target).max()))

    h, t, w = umap3d.fuzzy_graph(idx, dist, sigma, rho)
    check('fuzzy graph is undirected and finite',
          len(h) == len(t) == len(w) and bool(np.isfinite(w).all()) and float(w.max()) <= 1.0001,
          '%d edges, max weight %.3f' % (len(h), float(w.max())))

    # Initialisation. On the real corpus the k-NN graph has 36 connected
    # components, so the Laplacian has no eigengap and spectral init returns
    # component indicators that LOOK like a valid embedding.
    pca = umap3d.pca_init(X, dim=3)
    check('pca init is defined and spread out', bool(np.isfinite(pca).all())
          and float(pca.std(axis=0).max()) > 1.0, 'std %s' % np.round(pca.std(0), 2))
    # the real failure: every axis collapsed together, 0.406 out of a scale of 10
    collapsed = (rng.normal(0, 0.4, (200, 3))).astype(np.float32)
    check('a collapsed init is detected as degenerate', umap3d._degenerate(collapsed),
          'std %s' % np.round(collapsed.std(0), 2))
    check('a healthy init is not', not umap3d._degenerate(pca))
    # and an embedding that is genuinely flat in one axis is NOT degenerate
    flat = pca.copy()
    flat[:, 2] = 0.0
    check('flat in one axis is still healthy', not umap3d._degenerate(flat))

    # two blobs with no edge between them: a genuinely disconnected graph
    h2 = np.array([0, 1, 50, 51], dtype=np.int32)
    t2 = np.array([1, 2, 51, 52], dtype=np.int32)
    w2 = np.ones(4, dtype=np.float32)
    init = umap3d.spectral_init(h2, t2, w2, 100, dim=3,
                                rng=np.random.default_rng(0),
                                X=rng.normal(0, 1, (100, 8)).astype(np.float32))
    check('a disconnected graph falls back to something usable',
          bool(np.isfinite(init).all()) and float(init.std(axis=0).min()) > 0.1,
          'std %s' % np.round(init.std(0), 2))

    a, b = umap3d.find_ab(0.1, 1.0)
    check('a,b fit the published curve', abs(a - 1.577) < 0.05 and abs(b - 0.895) < 0.05,
          'a=%.3f b=%.3f' % (a, b))

    emb, _ = umap3d.fit(X, n_neighbors=10, n_epochs=200, seed=5)
    check('embedding is finite', bool(np.isfinite(emb).all()))
    # the blobs must still be blobs in 3D
    d3 = np.zeros((240, 5), dtype=int)
    dd = ((emb[:, None, :] - emb[None, :, :]) ** 2).sum(-1)
    np.fill_diagonal(dd, 1e18)
    d3 = np.argpartition(dd, 5, axis=1)[:, :5]
    purity = float((lab[d3] == lab[:, None]).mean())
    check('separated input stays separated in 3D', purity > 0.95, 'purity %.3f' % purity)

    # A min-vs-min comparison passes trivially (the nearest pair inside a
    # cluster is always touching). Compare the cluster's WIDTH -- how far
    # apart two of its own members typically are -- against the closest any
    # two clusters come to each other.
    finite = np.where(dd > 1e17, np.nan, dd)
    width = np.nanmedian([np.nanmedian(finite[lab == i][:, lab == i]) for i in range(3)])
    gap = min(finite[lab == i][:, lab == j].min()
              for i in range(3) for j in range(3) if i != j)
    check('clusters are further apart than they are wide', gap > width,
          'closest gap %.1f vs typical width %.1f' % (gap, width))

    emb2, _ = umap3d.fit(X, n_neighbors=10, n_epochs=200, seed=5)
    check('same seed gives the same map', float(np.abs(emb - emb2).max()) < 1e-5)

    # The engine is chosen at runtime, so BOTH paths have to work. The built-in
    # one is not dead code -- it is what makes this run on a machine with
    # nothing installed.
    import layout
    emb3, eng3 = layout.fit(X, n_neighbors=10, n_epochs=120, prefer='builtin')
    check('the built-in engine is selectable', eng3 == 'tools/umap3d.py', eng3)
    check('the built-in engine produces a finite map', bool(np.isfinite(emb3).all()))
    if layout.available():
        emb4, eng4 = layout.fit(X, n_neighbors=10, n_epochs=120, prefer='auto')
        check('umap-learn is preferred when present', 'umap-learn' in eng4, eng4)
        check('both engines hand back the same scale',
              abs(np.percentile(np.abs(emb4), 99) - np.percentile(np.abs(emb3), 99)) < 25,
              '%.0f vs %.0f' % (np.percentile(np.abs(emb4), 99),
                                np.percentile(np.abs(emb3), 99)))
    else:
        skip('umap-learn comparison', 'not installed under this interpreter')


# ------------------------------------------------------------------- server
def test_server_units():
    section('server logic')
    sys.path.insert(0, UMAP)
    import server

    tool, path, detail = server.extract({
        'tool_name': 'Read', 'tool_input': {'file_path': 'D:/x/VESPER/src/orbit.js'}})
    check('reads a file_path', (tool, path) == ('Read', 'D:/x/VESPER/src/orbit.js'))

    tool, path, detail = server.extract({
        'tool_name': 'Bash', 'tool_input': {'command': 'grep -n foo VESPER/src/orbit.js'}})
    check('digs a path out of a bash command', 'orbit.js' in path, path)

    tool, path, detail = server.extract({
        'tool_name': 'Grep', 'tool_input': {'pattern': 'cubeSphere'}})
    check('keeps a pattern as detail when there is no path',
          path == '' and 'cubeSphere' in detail)

    check('never raises on rubbish', server.extract({'tool_input': None})[0] == 'Tool')
    check('never raises on empty', server.extract({})[0] == 'Tool')

    # half the folders here have a space in the name, so an unquoted-token
    # regex truncates the path and it resolves to nothing
    check('a quoted path survives its spaces',
          server.bash_path('cd /d "D:/projects" && ls') == 'D:/projects')
    check('an unquoted path still yields something',
          '/' in server.bash_path('grep -n foo VESPER/src/orbit.js'))

    check('SessionStart is labelled, not blank',
          server.extract({'hook_event_name': 'SessionStart'})[0] == 'session')
    check('Stop is labelled, not blank',
          server.extract({'hook_event_name': 'Stop'})[0] == 'idle')

    idx = server.Index()
    if not idx.paths:
        skip('path resolution', 'no pathindex.json')
        return
    some = next(p for p in idx.paths if p.endswith('.js'))
    node, how = idx.resolve(os.path.join(ROOT, some.replace('/', os.sep)))
    check('resolves an absolute path to a node', how == 'exact' and node is not None, some)
    node, how = idx.resolve(some.upper())
    check('resolution is case insensitive', how == 'exact')
    top = some.split('/')[0]
    node, how = idx.resolve(os.path.join(ROOT, top, 'no', 'such', 'file.js'))
    check('an unknown file inside a known folder still lands somewhere', how in ('dir', 'project:' + top),
          str(how))
    node, how = idx.resolve('')
    check('an empty path resolves to nothing', node is None and how is None)


def test_server_live():
    section('a real server, end to end')
    sys.path.insert(0, UMAP)
    import server
    from http.server import ThreadingHTTPServer

    port = 5599
    while not server.free_port(port) and port < 5620:
        port += 1
    httpd = ThreadingHTTPServer(('127.0.0.1', port), server.Handler)
    httpd.daemon_threads = True
    th = threading.Thread(target=httpd.serve_forever, daemon=True)
    th.start()
    base = 'http://127.0.0.1:%d' % port

    def get(p, timeout=6):
        with urllib.request.urlopen(base + p, timeout=timeout) as r:
            return r.status, r.read().decode('utf-8', 'replace')
    

    def post(p, body):
        req = urllib.request.Request(base + p, data=json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'},
                                     method='POST')
        with urllib.request.urlopen(req, timeout=6) as r:
            return r.status, r.read().decode('utf-8', 'replace')

    try:
        st, body = get('/api/health')
        check('health answers', st == 200 and json.loads(body)['ok'])

        st, body = get('/')
        check('serves the page', st == 200 and '<canvas id="gl">' in body)

        st, body = get('/data/graph.json')
        check('serves the graph', st == 200 and len(body) > 100000, '%d bytes' % len(body))

        # an agent event must reach the stream
        seen = []
        stop = threading.Event()

        def listen():
            try:
                with urllib.request.urlopen(base + '/api/stream', timeout=12) as r:
                    while not stop.is_set():
                        line = r.readline()
                        if not line:
                            break
                        seen.append(line.decode('utf-8', 'replace'))
                        if len(seen) > 60:
                            break
            except Exception:
                pass

        lt = threading.Thread(target=listen, daemon=True)
        lt.start()
        time.sleep(0.6)

        sample = None
        idx = server.INDEX
        if idx.nodes:
            sample = os.path.join(idx.root_real, idx.nodes[0]['path'].replace('/', os.sep))
        st, _ = post('/api/event', {
            'tool_name': 'Read', 'session_id': 'test-agent', 'agent_name': 'suite',
            'tool_input': {'file_path': sample or 'nowhere.txt'}})
        check('accepts an agent event', st == 204)
        time.sleep(0.8)
        stop.set()
        payload = ''.join(seen)
        check('the event reaches the live stream', 'test-agent' in payload,
              '%d stream lines' % len(seen))
        if sample:
            check('the event resolved to a node on the map',
                  '"how": "exact"' in payload or '"how":"exact"' in payload)

        st, body = post('/api/event', {'garbage': True, 'tool_input': 'not a dict'})
        check('a malformed event is accepted, not argued with', st == 204)

        st, body = get('/api/state')
        state = json.loads(body)
        check('the agent shows up in state',
              any(a['id'] == 'test-agent' for a in state['agents']))

        if idx.nodes:
            p = idx.nodes[0]['path']
            st, body = get('/api/neighbors?path=' + urllib.parse.quote(p) + '&k=5')
            nb = json.loads(body)
            check('neighbours answers for a mapped file', nb['ok'] and len(nb['neighbors']) > 0,
                  '%d neighbours' % len(nb.get('neighbors', [])))
            check('a neighbour is not the file itself',
                  all(x['path'] != p for x in nb['neighbors']))

            f = next((x for x in idx.nodes if x['k'] == 'file' and x['loc'] > 5), None)
            if f:
                st, body = get('/api/file?lines=20&path=' + urllib.parse.quote(f['path']))
                blob = json.loads(body)
                check('reads a real file off disk', blob['ok'] and len(blob['text']) > 0,
                      f['path'])
        try:
            urllib.request.urlopen(base + '/api/file?path=../../../etc/passwd', timeout=4)
            check('refuses a path outside the map', False)
        except urllib.error.HTTPError as exc:
            check('refuses a path outside the map', exc.code in (404, 415))

        # full-text search: the browser can match names and symbols on its own,
        # so this endpoint only earns its place by finding files that CONTAIN
        # the term without defining it
        st, body = get('/api/search?q=def%20main', timeout=90)
        res = json.loads(body)
        check('search finds files by their contents', res['ok'] and res['count'] > 3,
              '%d files contain "def main"' % res.get('count', 0))
        if res.get('hits'):
            h = res['hits'][0]
            check('search reports where and how often',
                  h['count'] >= 1 and bool(h['line']) and bool(h['path']),
                  '%s x%d' % (h['path'], h['count']))
            check('search results are real nodes on the map',
                  all(0 <= x['node'] < len(server.INDEX.nodes) for x in res['hits']))
        try:
            get('/api/search?q=a')
            check('search refuses a one-character query', False, 'it answered 200')
        except urllib.error.HTTPError as exc:
            check('search refuses a one-character query', exc.code == 400,
                  'HTTP %d' % exc.code)

        st, body = get('/api/staleness')
        stale = json.loads(body)
        check('staleness answers', stale['ok'] and stale['scanned'] > min(1000, EXPECT['nodes']),
              '%d changed, %d added, of %d scanned'
              % (stale['changed'], stale['added'], stale['scanned']))
        check('staleness counts only real drift',
              stale['changed'] + stale['added'] <= stale['scanned'])
    finally:
        httpd.shutdown()


# -------------------------------------------------------------------- files
def test_sources_clean():
    section('source hygiene')
    # This portfolio has had FOUR control-character-in-source bugs, all written
    # through a shell heredoc, all invisible in an editor and in a diff. So the
    # class gets a check rather than each instance getting a fix.
    bad = []
    for base, _dirs, files in os.walk(UMAP):
        if any(p in base for p in ('vendor', '__pycache__', os.sep + 'data')):
            continue
        for fn in files:
            if not fn.endswith(('.py', '.js', '.css', '.html', '.json', '.bat', '.md')):
                continue
            full = os.path.join(base, fn)
            try:
                with open(full, 'rb') as fh:
                    raw = fh.read()
            except OSError:
                continue
            for ch in (0x00, 0x08, 0x0b, 0x0c, 0x1a, 0x1b, 0x7f):
                if bytes([ch]) in raw:
                    bad.append('%s has 0x%02x' % (os.path.relpath(full, UMAP), ch))
    check('no control characters in any source file', not bad, '; '.join(bad[:4]))

    # every .bat must reference a file that exists
    missing = []
    for fn in os.listdir(UMAP):
        if not fn.lower().endswith('.bat'):
            continue
        text = open(os.path.join(UMAP, fn), encoding='utf-8', errors='replace').read()
        for m in re.finditer(r'(?<![\w\\/.\-])([\w.\-]+(?:[\\/][\w.\-]+)*\.py)\b', text):
            cand = m.group(1).strip()
            if '%' in cand:
                continue
            if not os.path.exists(os.path.join(UMAP, cand.replace('\\', os.sep))):
                missing.append('%s -> %s' % (fn, cand))
    check('every .bat points at a file that exists', not missing, '; '.join(missing))

    for name in ('server.py', 'tools/scan.py', 'tools/build.py', 'tools/umap3d.py',
                 'tools/embed.py', 'tools/common.py', 'tools/classifier_off.py',
                 'hooks/umap_ping.py', 'hooks/install.py') + (('tools/classifier.py',) if CLASSIFIER else ()):
        full = os.path.join(UMAP, name.replace('/', os.sep))
        ok = os.path.exists(full)
        if ok:
            rc = subprocess.call([sys.executable, '-c',
                                  'import ast,sys;ast.parse(open(sys.argv[1],encoding="utf-8").read())',
                                  full], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            ok = rc == 0
        check('parses: %s' % name, ok)


def test_hook():
    section('the agent hook')
    ping = os.path.join(UMAP, 'hooks', 'umap_ping.py')
    payload = json.dumps({'tool_name': 'Read', 'tool_input': {'file_path': 'x.js'}})
    t0 = time.time()
    p = subprocess.run([sys.executable, ping], input=payload, text=True,
                       capture_output=True, timeout=20)
    dt = time.time() - t0
    check('exits 0 with no server listening', p.returncode == 0, 'exit %d' % p.returncode)
    check('says nothing on stdout or stderr', not p.stdout.strip() and not p.stderr.strip(),
          (p.stdout + p.stderr)[:80])
    check('returns fast when nothing is listening', dt < 5.0, '%.2fs' % dt)

    p = subprocess.run([sys.executable, ping], input='not json at all', text=True,
                       capture_output=True, timeout=20)
    check('survives a payload that is not JSON', p.returncode == 0)
    p = subprocess.run([sys.executable, ping], input='', text=True,
                       capture_output=True, timeout=20)
    check('survives an empty payload', p.returncode == 0)


# --------------------------------------------------------------------- main
def main():
    print('UMAP checks')
    test_tokens()
    if CLASSIFIER:
        sys.path.insert(0, HERE)
        import flag_checks
        flag_checks.test_flagged(check, skip, section)
    g = test_graph()
    test_map_quality(g)
    if CLASSIFIER:
        flag_checks.test_flagged_layer(g, UMAP, check, skip, section)
    else:
        test_no_flag_layer(g)
    test_umap()
    test_server_units()
    test_sources_clean()
    test_hook()
    if '--live' in sys.argv or '--all' in sys.argv:
        test_server_live()
    else:
        print('\n(pass --live to also stand up a server and drive it)')

    print('\n%d passed, %d failed, %d skipped' % (len(PASS), len(FAIL), len(SKIP)))
    if FAIL:
        print('failed:')
        for f in FAIL:
            print('  - ' + f)
    return 1 if FAIL else 0


if __name__ == '__main__':
    import urllib.error
    import urllib.parse
    sys.exit(main())
