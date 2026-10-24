"""Does umap-learn produce a better map than tools/umap3d.py?

Argument is cheap; this measures. Both fits get the IDENTICAL LSA matrix and
the same hyperparameters, and are scored on:

  trustworthiness  - do points that are neighbours in 3D deserve to be? This is
                     the standard measure of embedding quality (Venna & Kaski).
  continuity       - the reverse: are real neighbours kept together?
  purity           - do a project's own files land near each other (what this
                     particular map is FOR)?
  wall clock       - including numba's compile time, which a user pays too.

Run:  .venv\\Scripts\\python.exe tools/compare_umap.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import common
import embed

DATA = os.path.join(common.HERE, 'data')
K = 15
SEED = 7


def load_vectors():
    corpus = json.load(open(os.path.join(DATA, 'corpus.json'), encoding='utf-8'))
    docs = corpus['docs']
    assets = [a for a in corpus['assets'] if a['count'] > 0]
    tokens, labels = [], []
    pidx = {p: i for i, p in enumerate(sorted(corpus['projects']))}
    for d in docs:
        tokens.append(d['tok'])
        labels.append(pidx[d['project']])
    for a in assets:
        tokens.append(common.path_tokens(a['dir']) * 4 + list(a['kinds'].keys()) * 3)
        labels.append(pidx[a['project']])
    vocab, idf, _ = embed.build_vocab(tokens)
    X = embed.tfidf(tokens, vocab, idf)
    Z, _ = embed.reduce_dims(X, dim=128)
    return Z, np.array(labels)


def purity(emb, labels, k=5):
    n = len(emb)
    idx = np.zeros((n, k), dtype=int)
    for s in range(0, n, 1024):
        e = min(s + 1024, n)
        d = ((emb[s:e, None, :] - emb[None, :, :]) ** 2).sum(-1)
        d[np.arange(e - s), np.arange(s, e)] = 1e18
        idx[s:e] = np.argpartition(d, k, axis=1)[:, :k]
    return float((labels[idx] == labels[:, None]).mean())


def main():
    Z, labels = load_vectors()
    print('input: %d points, %d dims, %d projects\n' % (Z.shape[0], Z.shape[1], labels.max() + 1))

    results = {}

    # ---- ours
    import umap3d
    t0 = time.time()
    mine, _ = umap3d.fit(Z, n_neighbors=K, n_epochs=300, min_dist=0.12, seed=SEED)
    results['tools/umap3d.py'] = (mine, time.time() - t0)
    print('ours        fitted in %.1fs' % results['tools/umap3d.py'][1])

    # ---- umap-learn
    try:
        import umap as umap_learn
    except ImportError:
        print('\numap-learn not importable under this interpreter.')
        print('run:  .venv\\Scripts\\python.exe tools/compare_umap.py')
        return 2
    t0 = time.time()
    theirs = umap_learn.UMAP(n_neighbors=K, n_components=3, min_dist=0.12,
                             metric='cosine', random_state=SEED,
                             verbose=False).fit_transform(Z)
    results['umap-learn %s' % umap_learn.__version__] = (np.asarray(theirs), time.time() - t0)
    print('umap-learn  fitted in %.1fs  (includes numba compile)\n'
          % list(results.values())[1][1])

    # ---- score
    from sklearn.manifold import trustworthiness
    print('%-26s %12s %12s %10s %9s' % ('', 'trustworth.', 'continuity', 'purity5', 'seconds'))
    for name, (emb, secs) in results.items():
        tw = trustworthiness(Z, emb, n_neighbors=K, metric='cosine')
        # continuity is trustworthiness with the roles swapped
        co = trustworthiness(emb, Z, n_neighbors=K, metric='cosine')
        pu = purity(emb, labels)
        print('%-26s %12.4f %12.4f %10.4f %9.1f' % (name, tw, co, pu, secs))

    return 0


if __name__ == '__main__':
    sys.exit(main())
