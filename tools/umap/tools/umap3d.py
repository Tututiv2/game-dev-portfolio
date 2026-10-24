"""UMAP, implemented here in numpy -- the fallback engine and the k-NN source.

This is NOT the default fit. `umap-learn` is installed in `UMAP/.venv` and
measured better (see tools/layout.py for the table). This module is kept for
two jobs it does better than being deleted:

  * it is what makes the map build on a machine with nothing installed, which
    is the property the rest of this portfolio is built around;
  * `knn()` supplies the exact neighbour graph in BOTH cases, so the edges
    drawn and the /api/neighbors answers never depend on which engine placed
    the points.

It is the real algorithm, not a force-directed lookalike:

  1. k-nearest neighbours under cosine distance
  2. per-point local connectivity: solve for sigma_i so that the row's
     membership strengths sum to log2(k), with rho_i the distance to the
     nearest neighbour (this is what makes UMAP scale-free per point)
  3. symmetrise the fuzzy sets:  P = A + A' - A .* A'
  4. spectral initialisation from the normalised graph Laplacian, falling
     back to PCA (never silently to random) when the graph is disconnected
  5. SGD on the cross-entropy, attractive force along edges, repulsive force
     to `negative_sample_rate` random points, gradients clipped to +/-4

The one deliberate difference from the reference implementation is that the
SGD is vectorised over edges per epoch rather than asynchronous per edge.
Without numba, asynchronous per-edge Python is minutes; this is seconds and
lands in the same place. Where that matters it is noted in the docstring.
"""
import math
import numpy as np


# ---------------------------------------------------------------- neighbours

def knn(X, k=15, block=512):
    """Exact k-NN under cosine distance for L2-normalised rows.

    Brute force on purpose: n is a few thousand here, so an exact answer costs
    less than an approximate index would, and it removes a whole class of
    "the map moved and nothing changed" bugs.
    """
    n = X.shape[0]
    k = min(k, n - 1)
    idx = np.empty((n, k), dtype=np.int32)
    dist = np.empty((n, k), dtype=np.float32)
    for start in range(0, n, block):
        stop = min(start + block, n)
        sim = X[start:stop] @ X.T                      # cosine similarity
        rows = np.arange(stop - start)
        sim[rows, np.arange(start, stop)] = -np.inf    # never your own neighbour
        part = np.argpartition(-sim, k, axis=1)[:, :k]
        vals = np.take_along_axis(sim, part, axis=1)
        order = np.argsort(-vals, axis=1)
        idx[start:stop] = np.take_along_axis(part, order, axis=1)
        d = 1.0 - np.take_along_axis(vals, order, axis=1)
        dist[start:stop] = np.clip(d, 0.0, 2.0)
    return idx, dist


def smooth_knn(dist, k, n_iter=64, local_connectivity=1.0, bandwidth=1.0):
    """Solve per row for sigma so that sum(exp(-(d - rho)/sigma)) == log2(k).

    rho is the distance to the nearest neighbour, which is what makes the
    embedding indifferent to how dense that point's neighbourhood happens to
    be -- the property that separates UMAP from a plain Gaussian kernel.
    """
    target = math.log2(k) * bandwidth
    n = dist.shape[0]
    rho = np.zeros(n, dtype=np.float32)
    sigma = np.zeros(n, dtype=np.float32)
    for i in range(n):
        row = dist[i]
        nz = row[row > 0.0]
        if nz.size >= local_connectivity:
            rho[i] = nz[int(local_connectivity) - 1] if local_connectivity >= 1 else nz[0]
        elif nz.size:
            rho[i] = nz.max()
        lo, hi, mid = 0.0, np.inf, 1.0
        for _ in range(n_iter):
            psum = np.exp(-np.maximum(row - rho[i], 0.0) / mid).sum()
            if abs(psum - target) < 1e-5:
                break
            if psum > target:
                hi = mid
                mid = (lo + hi) / 2.0
            else:
                lo = mid
                mid = mid * 2 if hi == np.inf else (lo + hi) / 2.0
        sigma[i] = mid
        # floor sigma so a point in a tight cluster cannot get an infinitely
        # sharp kernel; the reference implementation does the same.
        mean_d = row.mean()
        sigma[i] = max(sigma[i], 1e-3 * mean_d if mean_d > 0 else 1e-3)
    return sigma, rho


def fuzzy_graph(idx, dist, sigma, rho):
    """Membership strengths, symmetrised into an undirected weighted graph.

    Returns (heads, tails, weights) as flat arrays -- the edge list the SGD
    walks. Duplicate (i,j)/(j,i) pairs are fused by the probabilistic t-conorm
    P = A + A' - A.*A' , which is what stops a mutual neighbour pair from
    pulling twice as hard as a one-way one.
    """
    n, k = idx.shape
    rows = np.repeat(np.arange(n, dtype=np.int64), k)
    cols = idx.reshape(-1).astype(np.int64)
    vals = np.exp(-np.maximum(dist.reshape(-1) - rho[rows], 0.0) / sigma[rows])
    vals = vals.astype(np.float32)

    keep = rows != cols
    rows, cols, vals = rows[keep], cols[keep], vals[keep]

    # transpose lookup via a dict of packed keys: n is small enough that this
    # is cheaper and clearer than building a scipy sparse matrix.
    key = rows * n + cols
    tkey = cols * n + rows
    order = np.argsort(key)
    skey, sval = key[order], vals[order]
    pos = np.searchsorted(skey, tkey)
    pos = np.clip(pos, 0, len(skey) - 1)
    trans = np.where(skey[pos] == tkey, sval[pos], 0.0).astype(np.float32)

    fused = vals + trans - vals * trans
    # keep one copy of each undirected edge
    keep = rows < cols
    both = np.where(trans > 0, keep, True)
    h, t, w = rows[both], cols[both], fused[both]
    good = w > 1e-6
    return h[good].astype(np.int32), t[good].astype(np.int32), w[good]


# ---------------------------------------------------------------- init

def pca_init(X, dim=3, rng=None, scale=10.0):
    """First `dim` principal components, scaled. Always defined."""
    rng = rng or np.random.default_rng(0)
    A = np.asarray(X, dtype=np.float32)
    A = A - A.mean(axis=0)
    try:
        _, _, Vt = np.linalg.svd(A, full_matrices=False)
        emb = A @ Vt[:dim].T
    except Exception:
        return rng.normal(0, scale / 2, (len(A), dim)).astype(np.float32)
    m = np.abs(emb).max()
    if not np.isfinite(m) or m < 1e-9:
        return rng.normal(0, scale / 2, (len(A), dim)).astype(np.float32)
    emb = emb / m * scale
    return (emb + rng.normal(0, 1e-3, emb.shape)).astype(np.float32)


def _degenerate(emb, scale=10.0):
    """Did the eigensolver hand back structure, or component indicators?

    On this corpus the k-NN graph has 36 connected components, so the
    normalised Laplacian has 36 zero eigenvalues and no eigengap -- the
    eigenvectors come back as arbitrary mixtures of component indicators, which
    LOOK like a valid embedding (finite, right shape, no exception) and carry
    no structure at all. umap-learn warns and falls back; this returned it
    silently, which is worse. Nearly all the variance collapsed: std was 0.406
    per axis against a scale of 10.
    """
    sd = emb.std(axis=0)
    if not np.isfinite(sd).all():
        return True
    # The test is on the LARGEST axis, not the smallest. A perfectly good
    # embedding can be nearly flat in one direction -- data with two dimensions
    # of real structure puts noise in the third, and PCA of it legitimately
    # returns std [7.3, 4.5, 0.35]. Requiring every axis to be spread rejected
    # that. What actually went wrong was every axis collapsing together, to
    # 0.406 out of 10.
    return bool(sd.max() < scale * 0.08)


def spectral_init(h, t, w, n, dim=3, rng=None, X=None):
    """Eigenvectors of the normalised Laplacian as the starting layout.

    A random start also converges, but slowly and to a worse global
    arrangement: the init is what makes the big clusters land in sensible
    relative positions before SGD ever runs -- measured on this corpus, it is
    worth 0.41 vs 0.28 on global distance correlation.

    Falls back to PCA (not random) when the spectrum is degenerate, and says so.
    """
    rng = rng or np.random.default_rng(0)
    try:
        from scipy.sparse import coo_matrix, identity
        from scipy.sparse.linalg import eigsh
        A = coo_matrix((np.concatenate([w, w]),
                        (np.concatenate([h, t]), np.concatenate([t, h]))),
                       shape=(n, n)).tocsr()
        deg = np.asarray(A.sum(axis=1)).ravel()
        deg[deg == 0] = 1.0
        Dinv = 1.0 / np.sqrt(deg)
        L = identity(n, format='csr') - (A.multiply(Dinv[:, None]).multiply(Dinv[None, :]))
        vals, vecs = eigsh(L, k=dim + 1, sigma=-1e-5, which='LM',
                           v0=rng.standard_normal(n), maxiter=n * 8)
        emb = vecs[:, 1:dim + 1]
        scale = np.abs(emb).max()
        if not np.isfinite(scale) or scale < 1e-9:
            raise ValueError('degenerate spectrum')
        emb = emb / scale * 10.0
        emb = (emb + rng.normal(0, 1e-3, emb.shape)).astype(np.float32)
        if _degenerate(emb):
            raise ValueError('spectrum has no eigengap (disconnected graph)')
        return emb
    except Exception as exc:
        if X is not None:
            print('  spectral init unusable (%s) - using PCA init' % exc)
            return pca_init(X, dim=dim, rng=rng)
        print('  spectral init unusable (%s) - using random init' % exc)
        return rng.normal(0, 5.0, (n, dim)).astype(np.float32)


def find_ab(min_dist=0.1, spread=1.0):
    """Fit 1/(1 + a*d^(2b)) to the piecewise target curve.

    Closed form does not exist; the reference uses scipy curve_fit and so do
    we, with the published constants as the fallback.
    """
    xv = np.linspace(0, spread * 3, 300)
    yv = np.where(xv < min_dist, 1.0, np.exp(-(xv - min_dist) / spread))
    try:
        from scipy.optimize import curve_fit
        f = lambda x, a, b: 1.0 / (1.0 + a * x ** (2 * b))
        (a, b), _ = curve_fit(f, xv, yv, p0=(1.0, 1.0), maxfev=10000)
        return float(a), float(b)
    except Exception:
        return 1.576943460405378, 0.8950608781227859


# ---------------------------------------------------------------- optimise

def optimise(emb, h, t, w, n_epochs=250, a=1.577, b=0.895, lr=1.0,
             negative_sample_rate=5, rng=None, progress=None):
    """Vectorised SGD on the UMAP cross-entropy.

    Per epoch every edge fires once (weighted by its membership strength via
    the same epochs-per-sample schedule the reference uses) and each endpoint
    is repelled from `negative_sample_rate` random points. Gradients are
    clipped to +/-4, which is the detail that keeps the layout from exploding
    when two points land on top of each other.
    """
    rng = rng or np.random.default_rng(1)
    emb = np.ascontiguousarray(emb, dtype=np.float32)
    n, dim = emb.shape
    w = w.astype(np.float32)
    wmax = w.max() if w.size else 1.0
    # epochs_per_sample: strong edges fire every epoch, weak ones rarely
    eps = wmax / np.maximum(w, 1e-12)
    eps[w < wmax / n_epochs] = np.inf
    next_fire = eps.copy()
    alpha = lr

    for epoch in range(n_epochs):
        fire = next_fire <= epoch
        if not fire.any():
            next_fire = np.minimum(next_fire, epoch + eps)
            continue
        hi, ti = h[fire], t[fire]
        diff = emb[hi] - emb[ti]
        d2 = np.einsum('ij,ij->i', diff, diff)

        # attractive
        grad_coeff = np.zeros_like(d2)
        nzero = d2 > 0
        grad_coeff[nzero] = (-2.0 * a * b * np.power(d2[nzero], b - 1.0)
                             / (a * np.power(d2[nzero], b) + 1.0))
        g = np.clip(grad_coeff[:, None] * diff, -4.0, 4.0) * alpha
        np.add.at(emb, hi, g)
        np.add.at(emb, ti, -g)

        # repulsive: negative_sample_rate random partners per fired edge
        m = hi.shape[0]
        for _ in range(negative_sample_rate):
            neg = rng.integers(0, n, size=m)
            ndiff = emb[hi] - emb[neg]
            nd2 = np.einsum('ij,ij->i', ndiff, ndiff)
            ncoeff = np.zeros_like(nd2)
            ok = nd2 > 0
            ncoeff[ok] = (2.0 * b) / ((0.001 + nd2[ok]) * (a * np.power(nd2[ok], b) + 1.0))
            ng = np.where(ok[:, None], ncoeff[:, None] * ndiff, 4.0)
            ng = np.clip(ng, -4.0, 4.0) * alpha
            same = hi == neg
            ng[same] = 0.0
            np.add.at(emb, hi, ng)

        next_fire[fire] = epoch + eps[fire]
        alpha = lr * (1.0 - epoch / n_epochs)
        if progress and epoch % 25 == 0:
            progress(epoch, n_epochs)
    return emb


def fit(X, n_neighbors=15, n_epochs=250, min_dist=0.4, spread=1.0,
        seed=42, dim=3, progress=None):
    """X: (n, d) float32, rows L2-normalised. Returns (n, dim) float32."""
    rng = np.random.default_rng(seed)
    n = X.shape[0]
    k = max(2, min(n_neighbors, n - 1))
    idx, dist = knn(X, k)
    sigma, rho = smooth_knn(dist, k)
    h, t, w = fuzzy_graph(idx, dist, sigma, rho)
    emb = spectral_init(h, t, w, n, dim=dim, rng=rng, X=X)
    a, b = find_ab(min_dist, spread)
    emb = optimise(emb, h, t, w, n_epochs=n_epochs, a=a, b=b, rng=rng,
                   progress=progress)
    emb -= emb.mean(axis=0)
    scale = np.percentile(np.abs(emb), 99)
    if scale > 0:
        emb = emb / scale * 100.0
    return emb.astype(np.float32), (idx, dist, h, t, w)
