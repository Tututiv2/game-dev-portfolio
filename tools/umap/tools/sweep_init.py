"""Does the initialisation matter here? (It does.)

The first sweep printed "Spectral initialisation failed ... falling back to
random" on nearly every fit, and the diagnosis is that the k-NN graph of this
corpus has **36 connected components** -- projects whose vocabulary shares
nothing with the rest at k=15. A disconnected graph's normalised Laplacian has
one zero eigenvalue per component, so there is no eigengap and the eigenvectors
that spectral init wants are arbitrary mixtures of component indicators.

Random init throws away all global structure, which is why `global` (the
correlation between high-dimensional and 3D distance) came out around 0.30 while
local fidelity was 0.99. PCA init is defined regardless of connectivity, so it
is the obvious thing to test.

Run:  .venv\\Scripts\\python.exe tools/sweep_init.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import common
from compare_umap import load_vectors, purity
from sweep import global_score

DATA = os.path.join(common.HERE, 'data')


def main():
    Z, labels = load_vectors()
    import umap as umap_learn
    from sklearn.manifold import trustworthiness
    print('corpus: %d points, %d dims\n' % Z.shape)

    rows = []
    print('%3s %5s %-9s | %6s %6s %6s %6s | %5s' %
          ('nn', 'mdist', 'init', 'trust', 'cont', 'global', 'pur5', 'secs'))
    print('-' * 62)
    for nn in (8, 15, 30):
        for init in ('spectral', 'pca', 'random'):
            t0 = time.time()
            emb = umap_learn.UMAP(n_neighbors=nn, n_components=3, min_dist=0.4,
                                  metric='cosine', random_state=7, n_epochs=300,
                                  init=init, verbose=False).fit_transform(Z)
            emb = np.asarray(emb, dtype=np.float32)
            secs = time.time() - t0
            tw = trustworthiness(Z, emb, n_neighbors=15, metric='cosine')
            co = trustworthiness(emb, Z, n_neighbors=15, metric='cosine')
            gl = global_score(Z, emb)
            pu = purity(emb, labels)
            rows.append({'n_neighbors': nn, 'min_dist': 0.4, 'init': init,
                         'trust': tw, 'continuity': co, 'global': gl,
                         'purity': pu, 'seconds': round(secs, 1)})
            print('%3d %5.2f %-9s | %6.4f %6.4f %6.4f %6.4f | %5.0f'
                  % (nn, 0.4, init, tw, co, gl, pu, secs))
            sys.stdout.flush()

    with open(os.path.join(DATA, 'sweep_init.json'), 'w', encoding='utf-8') as fh:
        json.dump({'generated': int(time.time()), 'rows': rows}, fh, indent=1)

    print('\nmean by init, across n_neighbors:')
    for init in ('spectral', 'pca', 'random'):
        sub = [r for r in rows if r['init'] == init]
        print('  %-9s trust %.4f  cont %.4f  global %.4f  purity %.4f'
              % (init, np.mean([r['trust'] for r in sub]),
                 np.mean([r['continuity'] for r in sub]),
                 np.mean([r['global'] for r in sub]),
                 np.mean([r['purity'] for r in sub])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
