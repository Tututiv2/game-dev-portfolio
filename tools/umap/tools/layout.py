"""Picks which UMAP does the fitting, and records which one did.

umap-learn is preferred, because it was measured to be better on the same
input with the same hyperparameters:

    engine                trustworth.   continuity    purity5   seconds
    tools/umap3d.py            0.9799       0.9596     0.7880       8.5
    umap-learn 0.5.12          0.9888       0.9745     0.7939      32.0

Read as error rather than score that is ~44% less neighbourhood distortion and
~37% less discontinuity, for 24 extra seconds once per rebuild. Worth it.
(`tools/compare_umap.py` regenerates that table.)

`tools/umap3d.py` stays as the fallback and is NOT dead code: it is what makes
the project work on a machine with nothing installed, which is the property the
rest of this portfolio is built around. It is also what supplies the exact k-NN
graph in BOTH cases -- the edges you see and the /api/neighbors answers come
from the same brute-force search either way, so switching engines changes where
things sit and never what is related to what.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import umap3d


def available():
    try:
        import umap  # noqa: F401
        return True
    except Exception:
        return False


def fit(X, n_neighbors=15, n_epochs=300, min_dist=0.4, seed=7, dim=3,
        prefer='auto', progress=None):
    """Returns (positions, engine_name).

    prefer: 'auto' (umap-learn if importable), 'reference', or 'builtin'.
    """
    X = np.ascontiguousarray(X, dtype=np.float32)
    if prefer in ('auto', 'reference'):
        try:
            import umap as umap_learn
            emb = umap_learn.UMAP(
                n_neighbors=min(n_neighbors, max(2, len(X) - 1)),
                n_components=dim, min_dist=min_dist, metric='cosine',
                random_state=seed, n_epochs=n_epochs, verbose=False,
                # PCA, not the default spectral: this corpus' k-NN graph has 36
                # connected components, so the Laplacian has no eigengap and
                # spectral init falls back to RANDOM, throwing away global
                # structure. Measured: global distance correlation 0.406 with
                # pca vs 0.313 spectral vs 0.279 random.
                init='pca',
            ).fit_transform(X)
            return normalise(np.asarray(emb, dtype=np.float32)), \
                'umap-learn %s' % umap_learn.__version__
        except Exception as exc:
            if prefer == 'reference':
                raise
            print('  umap-learn unavailable (%s), using the built-in fit' % exc)

    emb, _ = umap3d.fit(X, n_neighbors=n_neighbors, n_epochs=n_epochs,
                        min_dist=min_dist, seed=seed, dim=dim, progress=progress)
    return emb, 'tools/umap3d.py'


def normalise(emb):
    """Centre and scale so both engines hand the renderer the same range."""
    emb = emb - emb.mean(axis=0)
    scale = np.percentile(np.abs(emb), 99)
    if scale > 0:
        emb = emb / scale * 100.0
    return emb.astype(np.float32)


_normalise = normalise   # older name, kept so nothing breaks
