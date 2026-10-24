"""Sweep the UMAP hyperparameters against measurable quality.

n_neighbors=15 / min_dist=0.12 were the defaults, not a decision. This fits the
real corpus at each setting and scores four things, because they disagree and
the disagreement IS the trade-off:

  trustworthiness  local fidelity: do 3D neighbours deserve to be neighbours?
  continuity       the reverse: are real neighbours kept together?
  global           Spearman correlation of pairwise distance, high-dim vs 3D.
                   Trustworthiness is blind to this -- a map can have perfect
                   local structure and put related clusters on opposite sides.
  purity5          do a project's own files land together (what this map is for)

Run:  .venv\\Scripts\\python.exe tools/sweep.py
Writes data/sweep.json and prints the table.
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import common
import layout
from compare_umap import load_vectors, purity

DATA = os.path.join(common.HERE, 'data')
NEIGHBORS = [8, 15, 30, 50]
MIN_DIST = [0.0, 0.15, 0.4]
SEED = 7


def global_score(Z, emb, sample=1600, seed=0):
    """Spearman correlation of pairwise distances on a random subsample.

    Full pairwise on 6.5k points is 42M entries twice over; a subsample of 1600
    is 2.5M and the correlation is stable to about the third decimal across
    seeds, which is finer than the differences being compared.
    """
    rng = np.random.default_rng(seed)
    idx = rng.choice(len(Z), size=min(sample, len(Z)), replace=False)
    A, B = Z[idx], emb[idx]
    da = 1.0 - (A @ A.T)                       # cosine distance, rows are unit
    db = np.sqrt(((B[:, None, :] - B[None, :, :]) ** 2).sum(-1))
    iu = np.triu_indices(len(idx), k=1)
    x, y = da[iu], db[iu]
    rx = np.argsort(np.argsort(x)).astype(np.float64)
    ry = np.argsort(np.argsort(y)).astype(np.float64)
    rx -= rx.mean(); ry -= ry.mean()
    return float((rx @ ry) / np.sqrt((rx @ rx) * (ry @ ry)))


def main():
    Z, labels = load_vectors()
    print('corpus: %d points, %d dims\n' % Z.shape)
    from sklearn.manifold import trustworthiness

    rows = []
    print('%3s %5s | %6s %6s %6s %6s | %5s' %
          ('nn', 'mdist', 'trust', 'cont', 'global', 'pur5', 'secs'))
    print('-' * 56)
    for nn in NEIGHBORS:
        for md in MIN_DIST:
            t0 = time.time()
            emb, engine = layout.fit(Z, n_neighbors=nn, min_dist=md,
                                     n_epochs=300, seed=SEED)
            secs = time.time() - t0
            tw = trustworthiness(Z, emb, n_neighbors=15, metric='cosine')
            co = trustworthiness(emb, Z, n_neighbors=15, metric='cosine')
            gl = global_score(Z, emb)
            pu = purity(emb, labels)
            rows.append({'n_neighbors': nn, 'min_dist': md, 'trust': tw,
                         'continuity': co, 'global': gl, 'purity': pu,
                         'seconds': round(secs, 1), 'engine': engine})
            print('%3d %5.2f | %6.4f %6.4f %6.4f %6.4f | %5.0f'
                  % (nn, md, tw, co, gl, pu, secs))
            sys.stdout.flush()

    with open(os.path.join(DATA, 'sweep.json'), 'w', encoding='utf-8') as fh:
        json.dump({'generated': int(time.time()), 'rows': rows}, fh, indent=1)

    print('\nbest by each measure:')
    for key in ('trust', 'continuity', 'global', 'purity'):
        b = max(rows, key=lambda r: r[key])
        print('  %-11s nn=%-3d min_dist=%-5.2f  %.4f'
              % (key, b['n_neighbors'], b['min_dist'], b[key]))

    # A single number to argue with: local fidelity and global shape both
    # matter for a map you navigate, so weight them together and let purity
    # break ties.
    for r in rows:
        r['combined'] = 0.4 * r['trust'] + 0.25 * r['continuity'] + \
                        0.25 * r['global'] + 0.10 * r['purity']
    best = max(rows, key=lambda r: r['combined'])
    print('\ncombined (0.40 trust + 0.25 cont + 0.25 global + 0.10 purity):')
    for r in sorted(rows, key=lambda r: -r['combined'])[:5]:
        print('  nn=%-3d min_dist=%-5.2f  %.4f%s'
              % (r['n_neighbors'], r['min_dist'], r['combined'],
                 '   <-- best' if r is best else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main())
