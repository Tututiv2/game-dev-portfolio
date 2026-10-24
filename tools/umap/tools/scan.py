"""Walk the portfolio and build the raw corpus the map is embedded from.

Emits data/corpus.json:

  {
    "root": "...", "generated": epoch,
    "projects": {name: {files, loc, bytes, langs{}, mtime, flagged{}, assets{}}},
    "docs":     [ {path, project, ...} ]        # one record per text file
    "assets":   [ {dir, project, kind, count, bytes, flagged} ]  # one per asset dir
  }

Text files carry their token list; asset directories carry only path tokens,
because we never open a binary. That is a deliberate limit and it is why the
flag verdict for an image is made from its directory, not its pixels.
"""
import json
import os
import sys
import time
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common
try:
    import classifier as flag_mod    # a private copy ships one; the public one does not
except ImportError:
    import classifier_off as flag_mod


def project_roots(root, containers):
    """[(project name, path, loose file names or None, container name)].

    One container is the original layout: its child folders are the projects
    and its loose files are `__root__`. With several, a name that is already
    taken gets its container's name appended, so two folders that both hold a
    `UMAP/` stay two projects.
    """
    out, taken = [], set()
    for cont in containers:
        try:
            entries = sorted(os.scandir(cont), key=lambda e: e.name.lower())
        except OSError:
            print('  ! missing root', cont)
            continue
        label = os.path.basename(cont.rstrip('\\/'))
        for e in entries:
            if not e.is_dir() or common.should_prune(e.name):
                continue
            name = e.name if e.name not in taken else '%s (%s)' % (e.name, label)
            taken.add(name)
            out.append((name, e.path, None, label))
        loose = [e.name for e in entries if e.is_file()]
        if loose:
            name = '__root__' if len(containers) == 1 else '__root__ (%s)' % label
            out.append((name, cont, loose, label))
    return out


def scan(root=None, verbose=True, containers=None):
    root = root or common.ROOT
    containers = containers or (common.CONTAINERS if root == common.ROOT else [root])

    projects = {}
    docs = []
    assets = []
    t0 = time.time()

    for pname, ppath, loose, group in project_roots(root, containers):
        # The map maps itself. It is a project in this portfolio like any other,
        # and leaving it out would be the one gap in "everything". Its own
        # generated data (graph.json, corpus.json) is >300 KB and so is already
        # counted as a data blob rather than embedded as prose.
        rec = {
            'files': 0, 'loc': 0, 'bytes': 0, 'mtime': 0,
            'langs': collections.Counter(), 'assets': collections.Counter(),
            'asset_bytes': 0, 'flagged_files': 0, 'flagged_assets': 0, 'flagged_max': 0,
            'flagged_why': '', 'dirs': 0, 'group': group,
        }
        file_levels = []
        asset_levels = []
        asset_dirs = collections.defaultdict(lambda: collections.Counter())
        asset_dir_bytes = collections.Counter()

        if loose is not None:
            walker = [(ppath, [], loose)]
        else:
            walker = os.walk(ppath)

        for dirpath, dirnames, filenames in walker:
            if loose is None:
                dirnames[:] = [d for d in dirnames if not common.should_prune(d)]
                rec['dirs'] += 1
            for fn in filenames:
                ext = os.path.splitext(fn)[1].lower()
                if ext in common.IGNORE_EXT:
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, root)
                relp = rel.replace('\\', '/')
                try:
                    st = os.stat(full)
                except OSError:
                    continue
                rec['files'] += 1
                rec['mtime'] = max(rec['mtime'], int(st.st_mtime))

                if common.is_generated(relp):
                    rec['files'] -= 1        # the map does not map its own output
                    continue
                is_blob = (ext in common.DATA_TEXT_EXT
                           and st.st_size > common.DATA_TEXT_BYTES)
                if ext in common.TEXT_EXT and st.st_size <= common.MAX_TEXT_FILE and not is_blob:
                    vendored = common.is_vendored(relp)
                    text = '' if vendored else common.read_text(full)
                    lvl, why = flag_mod.verdict(relp, text or None)
                    lang = common.TEXT_EXT[ext]
                    loc = text.count('\n') + 1 if text else 0
                    rec['bytes'] += st.st_size
                    if not vendored:
                        rec['loc'] += loc
                        rec['langs'][lang] += 1
                    file_levels.append(lvl)
                    if lvl:
                        rec['flagged_files'] += 1
                        if lvl > max(file_levels[:-1] or [0]):
                            rec['flagged_why'] = why
                    syms = common.symbols(text, lang) if text else []
                    head = common.summary(text, lang) if text else ''
                    # the names a file DEFINES weigh more than the words it
                    # merely uses -- two files that declare the same things are
                    # about the same thing
                    toks = (common.path_tokens(relp) * 3
                            + [t for s in syms for t in common.tokenize(s)] * 2
                            + (common.tokenize(text) if text else []))
                    docs.append({
                        'path': relp, 'project': pname, 'name': fn, 'ext': ext,
                        'lang': lang, 'size': st.st_size, 'loc': loc,
                        'mtime': int(st.st_mtime), 'vendored': vendored,
                        'flagged': lvl, 'flagged_why': why, 'tok': toks,
                        'sym': syms, 'head': head,
                    })
                elif ext in common.ASSET_EXT or is_blob:
                    kind = 'data' if is_blob else common.ASSET_EXT[ext]
                    d = os.path.relpath(dirpath, root).replace('\\', '/')
                    asset_dirs[d][kind] += 1
                    asset_dir_bytes[d] += st.st_size
                    rec['assets'][kind] += 1
                    rec['asset_bytes'] += st.st_size
                    lvl, _ = flag_mod.path_verdict(relp)
                    asset_levels.append(lvl)
                    if lvl:
                        rec['flagged_assets'] += 1

        for d, kinds in asset_dirs.items():
            lvl, why = flag_mod.path_verdict(d + '/')
            assets.append({
                'dir': d, 'project': pname, 'kinds': dict(kinds),
                'count': sum(kinds.values()), 'bytes': asset_dir_bytes[d],
                'flagged': lvl, 'flagged_why': why,
            })

        rec['flagged_max'] = flag_mod.roll_up(file_levels, asset_levels)
        rec['langs'] = dict(rec['langs'])
        rec['assets'] = dict(rec['assets'])
        projects[pname] = rec
        if verbose:
            print(f"  {pname:<44} {rec['files']:>6} files  {rec['loc']:>8} loc"
                  f"  flagged:{flag_mod.LEVEL_NAME[rec['flagged_max']]}")

    return {
        'root': root, 'containers': containers, 'generated': int(time.time()),
        'elapsed': round(time.time() - t0, 1),
        'projects': projects, 'docs': docs, 'assets': assets,
    }


def main():
    out_path = os.path.join(common.HERE, 'data', 'corpus.json')
    print('scanning', ', '.join(common.CONTAINERS))
    corpus = scan()
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8') as fh:
        json.dump(corpus, fh)
    n_tok = sum(len(d['tok']) for d in corpus['docs'])
    print(f"\n{len(corpus['projects'])} projects, {len(corpus['docs'])} text files, "
          f"{len(corpus['assets'])} asset dirs, {n_tok:,} tokens in "
          f"{corpus['elapsed']}s -> {out_path}")


if __name__ == '__main__':
    main()
