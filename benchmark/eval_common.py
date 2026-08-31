"""
Shared eval-harness helpers for the benchmark/cnn, benchmark/srm and
benchmark/jpeg steganalysis scripts: AUC/P_E scoring (numpy-only, no sklearn)
and the cover/stego pairing + feature-caching plumbing used by the classical
(SRM, DCTR) FLD-ensemble evaluators.
"""

import hashlib
import os
import random

import numpy as np


def auc_score(labels, scores):
    """Mann-Whitney U AUC (handles ties by average rank)."""
    labels = np.asarray(labels)
    scores = np.asarray(scores, dtype=np.float64)
    order = np.argsort(scores, kind="mergesort")
    ranks = np.empty(len(scores), dtype=np.float64)
    sorted_s = scores[order]
    i = 0
    while i < len(sorted_s):
        j = i
        while j + 1 < len(sorted_s) and sorted_s[j + 1] == sorted_s[i]:
            j += 1
        ranks[order[i:j + 1]] = (i + j) / 2.0 + 1.0
        i = j + 1
    pos = labels == 1
    n_pos = pos.sum()
    n_neg = len(labels) - n_pos
    if n_pos == 0 or n_neg == 0:
        return float("nan")
    return (ranks[pos].sum() - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg)


def p_error(labels, scores):
    """Minimal P_E = min_t 0.5*(P_FA + P_MD)."""
    labels = np.asarray(labels)
    scores = np.asarray(scores, dtype=np.float64)
    pos = labels == 1
    neg = ~pos
    n_pos = max(1, pos.sum())
    n_neg = max(1, neg.sum())
    best = 1.0
    for t in np.unique(scores):
        pred = scores >= t
        p_fa = (pred & neg).sum() / n_neg
        p_md = (~pred & pos).sum() / n_pos
        best = min(best, 0.5 * (p_fa + p_md))
    return best


def build_pairs(cover_dir, stego_dir, ext):
    """Cover/stego pairs matched by filename (same extension in both dirs),
    sorted numerically by the (numeric) stem when possible."""
    ids = [f for f in os.listdir(cover_dir)
           if f.endswith(ext) and os.path.exists(os.path.join(stego_dir, f))]
    ids.sort(key=lambda s: int(os.path.splitext(s)[0]) if os.path.splitext(s)[0].isdigit() else s)
    return [(os.path.join(cover_dir, f), os.path.join(stego_dir, f)) for f in ids]


def split_pairs(pairs, seed, val_frac=0.1, test_frac=0.2):
    """Train+val merged into one training split (no separate val set): used by
    the FLD-ensemble evaluators so their TEST pairs match the CNN's exactly.
    val_frac is accepted for signature parity but unused (train absorbs it)."""
    rng = random.Random(seed)
    idx = list(range(len(pairs)))
    rng.shuffle(idx)
    n = len(idx)
    n_test = int(n * test_frac)
    test_ids = idx[:n_test]
    train_ids = idx[n_test:]
    return train_ids, test_ids


def feat_cached(path, param, cache_dir, extractor, param_fmt="%d"):
    """extractor(path, param) result, cached to <cache_dir>/<md5>.npy keyed on
    path + param + mtime. param_fmt controls how param is rendered into the
    cache key (e.g. "%d" for an int channel, "%.3f" for a float quality)."""
    if cache_dir is None:
        return extractor(path, param)
    key = hashlib.md5(("%s|%s|%d" % (os.path.abspath(path), param_fmt % param,
                                     int(os.path.getmtime(path)))).encode()).hexdigest()
    fp = os.path.join(cache_dir, key + ".npy")
    if os.path.exists(fp):
        return np.load(fp)
    f = extractor(path, param)
    np.save(fp, f)
    return f


def assemble(pairs, ids, param, cache_dir, feat_dim, extractor, param_fmt="%d"):
    """Stack cached features for the (cover, stego) pairs at `ids` into an
    (2*len(ids), feat_dim) matrix with labels 0=cover, 1=stego."""
    x = np.empty((len(ids) * 2, feat_dim), dtype=np.float64)
    y = np.empty(len(ids) * 2, dtype=np.int64)
    k = 0
    for i in ids:
        cover, stego = pairs[i]
        x[k] = feat_cached(cover, param, cache_dir, extractor, param_fmt); y[k] = 0; k += 1
        x[k] = feat_cached(stego, param, cache_dir, extractor, param_fmt); y[k] = 1; k += 1
    return x, y
