"""Assemble everything into web/data/graph.json.

  corpus.json          the filesystem scan (tokens, sizes, flagged per file)
  data/curated/*.json  agent-written project records
  data/curated/flag_audit.json   an evidence-based audit, private copies only
  _brain/knowledge.json          the owner's older hand-curated layer

  ->  TF-IDF -> LSA -> UMAP(3D)  ->  web/data/graph.json
                                     data/folders/<project>.json  (clean/flagged split)
                                     web/data/pathindex.json      (live HUD lookup)
"""
import json
import os
import sys
import time
import glob
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common
try:
    import classifier as flag_mod    # a private copy ships one; the public one does not
except ImportError:
    import classifier_off as flag_mod

HERE = common.HERE
DATA = os.path.join(HERE, 'data')
WEBDATA = os.path.join(HERE, 'web', 'data')

STACK_COLOR = {
    'java':   '#ff7a45',
    'csharp': '#a06bff',
    'web':    '#35d0ff',
    'python': '#ffd23f',
    'lua':    '#4be08a',
    'skript': '#ff5fa2',
    # 'mixed' was #9aa7b8, a light grey -- and grey is the one hue that stacks
    # additively straight to white, which turned the second-largest project
    # into an unreadable blob. Darker and bluer keeps it a colour.
    'mixed':  '#5f76a0',
    'doc':    '#6b7a90',
}
# A project with no curated record (every project, in a copy that ships without
# data/curated/) is coloured by the language most of its source files are in.
LANG_STACK = {'java': 'java', 'csharp': 'csharp', 'js': 'web', 'ts': 'web', 'web': 'web',
              'python': 'python', 'lua': 'lua', 'skript': 'skript'}


def dominant_stack(langs):
    counts = collections.Counter()
    for lang, n in (langs or {}).items():
        if lang in LANG_STACK:
            counts[LANG_STACK[lang]] += n
    return counts.most_common(1)[0][0] if counts else 'mixed'


STATUS_RING = ['active', 'built', 'fixed', 'prototype', 'archive', 'scratch', 'empty', 'unknown']


def load_curated():
    cur = {}
    audit = {}
    for path in sorted(glob.glob(os.path.join(DATA, 'curated', '*.json'))):
        name = os.path.basename(path)
        try:
            blob = json.load(open(path, encoding='utf-8-sig'))
        except Exception as exc:
            print('  ! could not parse', name, exc)
            continue
        if name == 'flag_audit.json':
            audit = blob
            continue
        cur.update(blob)
    # the agents key the portfolio-root record differently from the scanner
    if '__root_docs__' in cur:
        cur['__root__'] = cur.pop('__root_docs__')
    return cur, audit


def load_knowledge():
    """Every container's `_brain/knowledge.json`, merged (projects and edges)."""
    merged = {'projects': {}, 'edges': []}
    for cont in common.CONTAINERS:
        path = os.path.join(cont, '_brain', 'knowledge.json')
        try:
            blob = json.load(open(path, encoding='utf-8-sig'))
        except Exception:
            continue
        for k, v in (blob.get('projects') or {}).items():
            merged['projects'].setdefault(k, v)
        merged['edges'] += blob.get('edges') or []
    return merged


def _relax(points, radius, iterations=220):
    """Push overlapping spheres apart without losing the arrangement.

    UMAP places the project centroids; this only stops two big projects from
    sitting inside one another. Small steps, many of them, so the global shape
    survives -- a hard separation pass would scramble exactly the relationships
    the layout is there to show.
    """
    import numpy as np
    P = np.array(points, dtype=np.float32).copy()
    r = np.asarray(radius, dtype=np.float32)
    n = len(P)
    for _ in range(iterations):
        moved = 0.0
        d = P[:, None, :] - P[None, :, :]
        dist = np.sqrt((d ** 2).sum(-1)) + 1e-6
        want = (r[:, None] + r[None, :]) * 1.12
        np.fill_diagonal(dist, 1e9)
        over = np.maximum(want - dist, 0.0)
        if not over.any():
            break
        push = (d / dist[..., None]) * (over * 0.25)[..., None]
        step = push.sum(axis=1)
        P += step
        moved = float(np.abs(step).max())
        if moved < 1e-3:
            break
    return P


def main():
    t0 = time.time()
    corpus = json.load(open(os.path.join(DATA, 'corpus.json'), encoding='utf-8'))
    curated, audit = load_curated()
    knowledge = load_knowledge()
    print(f'corpus: {len(corpus["docs"])} text files, {len(corpus["assets"])} asset dirs, '
          f'{len(curated)} curated records, audit covers {len(audit.get("projects", {}))}')

    # ---------------------------------------------------------------- nodes
    docs = corpus['docs']
    assets = [a for a in corpus['assets'] if a['count'] > 0]
    proj_names = sorted(corpus['projects'].keys())
    pindex = {p: i for i, p in enumerate(proj_names)}

    token_lists = []
    nodes = []
    for d in docs:
        token_lists.append(d['tok'])
        nodes.append({
            'k': 'file', 'path': d['path'].replace('\\', '/'), 'p': pindex[d['project']],
            'name': d['name'], 'ext': d['ext'], 'lang': d['lang'],
            'loc': d['loc'], 'size': d['size'], 'mtime': d['mtime'],
            'flagged': d['flagged'], 'why': d['flagged_why'], 'vend': 1 if d['vendored'] else 0,
            'sym': d.get('sym') or [], 'head': d.get('head') or '',
        })
    for a in assets:
        toks = common.path_tokens(a['dir']) * 4 + list(a['kinds'].keys()) * 3
        token_lists.append(toks)
        kinds = ', '.join(f'{v} {k}' for k, v in sorted(a['kinds'].items(), key=lambda p: -p[1]))
        nodes.append({
            'k': 'asset', 'path': a['dir'].replace('\\', '/') + '/', 'p': pindex[a['project']],
            'name': os.path.basename(a['dir']) or a['dir'], 'ext': '', 'lang': 'asset',
            'loc': a['count'], 'size': a['bytes'], 'mtime': 0,
            'flagged': a['flagged'], 'why': a['flagged_why'] or kinds, 'vend': 0,
            'kinds': a['kinds'],
        })

    # ---------------------------------------------------------------- vectors
    import numpy as np
    import embed
    import umap3d
    import layout

    vocab, idf, df = embed.build_vocab(token_lists)
    print(f'vocab: {len(vocab)} terms')
    X = embed.tfidf(token_lists, vocab, idf)
    print(f'tfidf: {X.shape}, {X.nnz} nonzeros')
    Z, _ = embed.reduce_dims(X, dim=128)
    print(f'lsa:   {Z.shape}')

    def prog(e, n):
        print(f'  umap epoch {e}/{n}')

    # The k-NN graph comes from the exact brute-force search in either case, so
    # the edges drawn and the /api/neighbors answers do not depend on which
    # engine placed the points. Only the positions do.
    FIT = dict(n_neighbors=15, n_epochs=300, min_dist=0.40, seed=7)
    knn_idx, knn_dist = umap3d.knn(Z, FIT['n_neighbors'])
    pos, engine = layout.fit(Z, progress=prog, **FIT)
    print(f'umap:  {pos.shape} via {engine} in {round(time.time() - t0, 1)}s')

    vocab_inv = {i: t for t, i in vocab.items()}

    # ------------------------------------------------------ second layout
    # The honest UMAP interleaves projects, because a CLAUDE.md in one project
    # really is the nearest neighbour of a CLAUDE.md in another. That is the
    # useful answer and a hard map to find your own work in, so a second set of
    # positions is computed and the view lerps between them:
    #   pos  = semantic   (what UMAP actually says)
    #   pos2 = galaxy     (projects placed by UMAP over their centroids, each
    #                      project's own files keeping their local shape)
    pos2 = np.zeros_like(pos)
    rows_by_proj = collections.defaultdict(list)
    for i, nd in enumerate(nodes):
        rows_by_proj[nd['p']].append(i)

    cent_vec = np.zeros((len(proj_names), Z.shape[1]), dtype=np.float32)
    for pi in range(len(proj_names)):
        rws = rows_by_proj.get(pi)
        if rws:
            v = Z[rws].mean(axis=0)
            nrm = np.linalg.norm(v)
            cent_vec[pi] = v / nrm if nrm else v
    ppos, _ = layout.fit(cent_vec, n_neighbors=min(8, len(proj_names) - 1),
                         n_epochs=500, min_dist=0.55, seed=11)
    # Cube root, not log. A cluster is a volume, so the radius that keeps
    # points-per-unit-volume constant goes as n^(1/3); log gave the 2,000-node
    # project only 2.2x the radius of a 30-node one, which packed it dense
    # enough that additive blending burned it to a solid white blob.
    radius = np.array([5.0 + 3.2 * (len(rows_by_proj.get(pi, [])) ** (1.0 / 3.0))
                       for pi in range(len(proj_names))], dtype=np.float32)
    ppos = _relax(ppos, radius)

    for pi, rws in rows_by_proj.items():
        if not rws:
            continue
        local = pos[rws] - np.median(pos[rws], axis=0)
        norms = np.linalg.norm(local, axis=1)
        scale = np.percentile(norms, 90)
        if scale > 1e-6:
            local = local / scale * radius[pi]
            # A file that is a semantic outlier within its own project gets its
            # offset multiplied by the same factor as everything else, and one
            # of them landed at radius 1007 while every project centroid sat
            # inside 134. It should read as "off to one side of its cluster",
            # not as a node in another galaxy.
            cap = radius[pi] * 2.2
            mag = np.linalg.norm(local, axis=1)
            far = mag > cap
            if far.any():
                local[far] *= (cap / mag[far])[:, None]
        pos2[rws] = ppos[pi] + local
    # The relaxation pass pushes clusters apart in absolute units, so the
    # galaxy layout ends up on a different scale from the semantic one -- 682
    # units across against 170, which left the camera framing empty space when
    # you dragged the slider. Both layouts get the same normalisation.
    pos2 = layout.normalise(pos2)

    # ---------------------------------------------------------------- projects
    projects = []
    folders = {}
    for name in proj_names:
        rows = [i for i, n in enumerate(nodes) if n['p'] == pindex[name]]
        scan_rec = corpus['projects'][name]
        cur = curated.get(name, {})
        kn = (knowledge.get('projects') or {}).get(name, {})

        file_levels = [nodes[i]['flagged'] for i in rows if nodes[i]['k'] == 'file']
        asset_levels = [nodes[i]['flagged'] for i in rows if nodes[i]['k'] == 'asset']
        heur = flag_mod.roll_up(file_levels, asset_levels)
        aud = (audit.get('projects') or {}).get(name)
        if aud:
            lvl = {'none': 0, 'partial': 1, 'full': 2}.get(aud.get('level'), heur)
            why = aud.get('why') or ''
            src = 'audit'
        else:
            declared = {'none': 0, 'partial': 1, 'full': 2}.get(cur.get('flagged'), None)
            lvl = declared if declared is not None else heur
            why = cur.get('flagged_why') or scan_rec.get('flagged_why') or ''
            src = 'curated' if declared is not None else 'scan'
        # The audit only lists flagged projects, so a project it omits keeps
        # whatever the file scan found -- but a DECLARED verdict (from the audit
        # or from a curated record) is a considered judgement and wins outright.
        # Without that precedence this line dragged UMAP itself back to
        # "full" over its own curated "none".
        if src == 'scan':
            lvl = max(lvl, 2 if 2 in file_levels + asset_levels and heur == 2 else 0)

        if rows:
            P = pos[rows]
            centre = np.median(P, axis=0)
            centre2 = np.median(pos2[rows], axis=0)
            spread = float(np.percentile(np.linalg.norm(P - centre, axis=1), 75)) if len(rows) > 1 else 4.0
            spread2 = float(np.percentile(np.linalg.norm(pos2[rows] - centre2, axis=1), 90)) if len(rows) > 1 else 4.0
        else:
            centre = np.zeros(3, dtype=np.float32)
            centre2 = np.zeros(3, dtype=np.float32)
            spread = spread2 = 3.0

        terms = embed.top_terms(X, rows, vocab_inv, k=10)
        stack = cur.get('stack') or kn.get('stack') or dominant_stack(scan_rec['langs'])
        title = cur.get('title') or kn.get('title') or name
        if name == '__root__':
            title = cur.get('title') or 'Portfolio root'

        projects.append({
            'id': name,
            'title': title,
            'status': cur.get('status') or kn.get('status') or 'unknown',
            'stack': stack,
            'color': STACK_COLOR.get(stack, STACK_COLOR['mixed']),
            'tech': cur.get('tech') or kn.get('tech') or '',
            'entry': cur.get('entry') or kn.get('entry') or '',
            'what': cur.get('what') or kn.get('what') or '',
            'fact': cur.get('fact') or kn.get('fact') or '',
            'lesson': cur.get('lesson') or kn.get('note') or '',
            'tags': cur.get('tags') or [],
            'kin': cur.get('kin') or [],
            'kin_why': cur.get('kin_why') or {},
            'docs': cur.get('docs') or [],
            'unmapped': bool(cur.get('unmapped')),
            'files': scan_rec['files'],
            'loc': scan_rec['loc'],
            'bytes': scan_rec['bytes'] + scan_rec['asset_bytes'],
            'langs': scan_rec['langs'],
            'assets': scan_rec['assets'],
            'mtime': scan_rec['mtime'],
            'group': scan_rec.get('group', ''),
            'flagged': int(lvl),
            'flagged_why': why,
            'flagged_src': src,
            'flagged_files': scan_rec['flagged_files'] + scan_rec['flagged_assets'],
            'terms': terms,
            'pos': [round(float(v), 2) for v in centre],
            'pos2': [round(float(v), 2) for v in centre2],
            'spread': round(spread, 2),
            'spread2': round(spread2, 2),
            'n': len(rows),
        })

        clean = sorted(nodes[i]['path'] for i in rows if not nodes[i]['flagged'])
        dirty = sorted((nodes[i]['path'], nodes[i]['flagged'], nodes[i]['why'])
                       for i in rows if nodes[i]['flagged'])
        folders[name] = {
            'project': name, 'title': title,
            'level': flag_mod.LEVEL_NAME[int(lvl)],
            'reason': why,
            'source': src,
            'clean': {'count': len(clean), 'paths': clean},
            'flagged': {'count': len(dirty),
                     'paths': [{'path': p, 'level': flag_mod.LEVEL_NAME[l], 'why': w}
                               for p, l, w in dirty]},
            'evidence': (aud or {}).get('evidence', []),
        }

    # ---------------------------------------------------------------- edges
    # file-level: the 3 strongest semantic neighbours of each node
    seen = set()
    fedges = []
    for i in range(knn_idx.shape[0]):
        for j in range(min(3, knn_idx.shape[1])):
            b = int(knn_idx[i, j])
            key = (min(i, b), max(i, b))
            if key in seen:
                continue
            seen.add(key)
            sim = 1.0 - float(knn_dist[i, j])
            if sim < 0.12:
                continue
            fedges.append([key[0], key[1], round(sim, 3)])

    # project-level: curated kinship, made symmetric
    kin_pairs = {}
    for p in projects:
        for other in p['kin']:
            if other not in pindex:
                continue
            key = tuple(sorted((p['id'], other)))
            why = p['kin_why'].get(other, '')
            if key not in kin_pairs or (why and not kin_pairs[key]):
                kin_pairs[key] = why
    # knowledge.json stores edges as [from, relation, to, why]
    for edge in (knowledge.get('edges') or []):
        if not isinstance(edge, (list, tuple)) or len(edge) < 3:
            continue
        a, rel, b = edge[0], edge[1], edge[2]
        why = edge[3] if len(edge) > 3 else ''
        if a in pindex and b in pindex:
            key = tuple(sorted((a, b)))
            kin_pairs.setdefault(key, f'{rel.replace("_", " ")}: {why}' if why else rel)
    kedges = [{'a': pindex[a], 'b': pindex[b], 'why': w} for (a, b), w in sorted(kin_pairs.items())]

    # ---------------------------------------------------------------- write
    for n, p, q in zip(nodes, pos, pos2):
        n['pos'] = [round(float(v), 2) for v in p]
        n['pos2'] = [round(float(v), 2) for v in q]

    os.makedirs(WEBDATA, exist_ok=True)
    os.makedirs(os.path.join(DATA, 'folders'), exist_ok=True)

    flagged_counts = collections.Counter(n['flagged'] for n in nodes)
    graph = {
        'meta': {
            'generated': int(time.time()),
            'name': common.NAME,
            'root': common.ROOT,
            'containers': [os.path.relpath(c, common.ROOT).replace('\\', '/') for c in common.CONTAINERS],
            # whether this copy carries a classifier at all; the page
            # hides its flagged-layer and split controls when it does not
            'flag_layer': bool(getattr(flag_mod, 'ENABLED', False)),
            'flag_label': common.INSTANCE.get('flag_label') or 'flagged',
            'nodes': len(nodes), 'projects': len(projects),
            'file_edges': len(fedges), 'kin_edges': len(kedges),
            'vocab': len(vocab),
            'flagged_nodes': int(flagged_counts[1] + flagged_counts[2]),
            'build_seconds': round(time.time() - t0, 1),
            # Written from the values actually used. The hardcoded version of
            # this string said min_dist=0.12 for a build that used 0.40 -- a
            # description of the code that the code could not keep honest.
            'algorithm': ('tfidf -> lsa(%d) -> umap(n_neighbors=%d, min_dist=%.2f, '
                          '%d epochs, init=pca)'
                          % (Z.shape[1], FIT['n_neighbors'], FIT['min_dist'],
                             FIT['n_epochs'])),
            'params': FIT,
            'engine': engine,
        },
        'stack_color': STACK_COLOR,
        'projects': projects,
        'nodes': nodes,
        'file_edges': fedges,
        'kin_edges': kedges,
    }
    out = os.path.join(WEBDATA, 'graph.json')
    with open(out, 'w', encoding='utf-8') as fh:
        json.dump(graph, fh, separators=(',', ':'))

    # Nearest neighbours, exported so an agent can ASK the map what is like a
    # file it is holding (/api/neighbors). That query is what makes the live
    # HUD show the map being used rather than just watched.
    knn_out = {}
    for i in range(knn_idx.shape[0]):
        knn_out[i] = [[int(knn_idx[i, j]), round(1.0 - float(knn_dist[i, j]), 3)]
                      for j in range(min(8, knn_idx.shape[1]))
                      if 1.0 - float(knn_dist[i, j]) > 0.05]
    with open(os.path.join(WEBDATA, 'knn.json'), 'w', encoding='utf-8') as fh:
        json.dump(knn_out, fh, separators=(',', ':'))

    pathindex = {n['path'].lower(): i for i, n in enumerate(nodes)}
    with open(os.path.join(WEBDATA, 'pathindex.json'), 'w', encoding='utf-8') as fh:
        json.dump({'root': common.ROOT.replace('\\', '/').lower(),
                   'paths': pathindex,
                   'projects': {p['id'].lower(): i for i, p in enumerate(projects)}},
                  fh, separators=(',', ':'))

    for name, blob in folders.items():
        safe = name.replace('/', '_').replace('\\', '_')
        with open(os.path.join(DATA, 'folders', safe + '.json'), 'w', encoding='utf-8') as fh:
            json.dump(blob, fh, indent=1)

    index = sorted(
        ({'project': k, 'title': v['title'], 'level': v['level'],
          'clean': v['clean']['count'], 'flagged': v['flagged']['count'], 'reason': v['reason']}
         for k, v in folders.items()),
        key=lambda r: (-{'full': 2, 'partial': 1, 'none': 0}[r['level']], r['project']))
    with open(os.path.join(DATA, 'folders', '_index.json'), 'w', encoding='utf-8') as fh:
        json.dump(index, fh, indent=1)

    size = os.path.getsize(out) / 1024
    print(f"\n{len(nodes)} nodes, {len(projects)} projects, {len(fedges)} file edges, "
          f"{len(kedges)} kin edges -> {out} ({size:.0f} KB) in "
          f"{round(time.time() - t0, 1)}s")
    print(f"flagged: {flagged_counts[2]} full + {flagged_counts[1]} partial nodes; "
          f"{sum(1 for p in projects if p['flagged'] == 2)} fully flagged projects")


if __name__ == '__main__':
    main()
