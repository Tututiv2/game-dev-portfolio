"""Turn the scanned corpus into vectors: TF-IDF over identifier tokens, then
truncated SVD (latent semantic analysis) down to a dense space UMAP can chew.

Why TF-IDF and not a sentence-transformer: the thing being embedded is mostly
source code, where the meaningful signal IS the vocabulary -- `cubeSphere`,
`rcon`, `wasapi`, `ipadapter`. A general text model flattens exactly those.
And it runs in two seconds with no model download.
"""
import math
import numpy as np
from scipy.sparse import csr_matrix
from scipy.sparse.linalg import svds


def build_vocab(token_lists, min_df=3, max_df_ratio=0.35, max_features=60000):
    n = len(token_lists)
    df = {}
    for toks in token_lists:
        for t in set(toks):
            df[t] = df.get(t, 0) + 1
    max_df = max(min_df + 1, int(n * max_df_ratio))
    keep = [(c, t) for t, c in df.items() if min_df <= c <= max_df]
    keep.sort(key=lambda p: (-p[0], p[1]))
    keep = keep[:max_features]
    vocab = {t: i for i, (_, t) in enumerate(keep)}
    idf = np.zeros(len(vocab), dtype=np.float32)
    for c, t in keep:
        idf[vocab[t]] = math.log((1 + n) / (1 + c)) + 1.0
    return vocab, idf, df


def tfidf(token_lists, vocab, idf):
    """Sublinear tf, idf weighting, L2 row norm."""
    indptr = [0]
    indices = []
    data = []
    for toks in token_lists:
        counts = {}
        for t in toks:
            j = vocab.get(t)
            if j is not None:
                counts[j] = counts.get(j, 0) + 1
        if counts:
            cols = np.fromiter(counts.keys(), dtype=np.int32, count=len(counts))
            vals = np.fromiter(counts.values(), dtype=np.float32, count=len(counts))
            vals = (1.0 + np.log(vals)) * idf[cols]
            norm = np.linalg.norm(vals)
            if norm > 0:
                vals /= norm
            indices.append(cols)
            data.append(vals)
        else:
            indices.append(np.empty(0, dtype=np.int32))
            data.append(np.empty(0, dtype=np.float32))
        indptr.append(indptr[-1] + len(indices[-1]))
    X = csr_matrix((np.concatenate(data) if data else np.empty(0, dtype=np.float32),
                    np.concatenate(indices) if indices else np.empty(0, dtype=np.int32),
                    np.array(indptr, dtype=np.int64)),
                   shape=(len(token_lists), len(vocab)), dtype=np.float32)
    return X


def reduce_dims(X, dim=128, seed=0):
    """LSA. Returns L2-normalised dense rows, so a dot product is a cosine."""
    dim = min(dim, min(X.shape) - 1)
    np.random.seed(seed)
    U, S, Vt = svds(X.astype(np.float64), k=dim)
    order = np.argsort(-S)
    Z = (U[:, order] * S[order]).astype(np.float32)
    norms = np.linalg.norm(Z, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return Z / norms, Vt[order].astype(np.float32)


def top_terms(X, rows, vocab_inv, k=8):
    """The terms that most define a set of rows -- used for cluster labels."""
    if not len(rows):
        return []
    sub = X[rows]
    scores = np.asarray(sub.sum(axis=0)).ravel()
    if not scores.any():
        return []
    top = np.argsort(-scores)[:k]
    return [vocab_inv[i] for i in top if scores[i] > 0]
