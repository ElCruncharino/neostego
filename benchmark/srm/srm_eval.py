"""
Train and evaluate the SRM + FLD-ensemble steganalyzer against one NeoStego
algorithm, mirroring srnet_eval.py so the classical and CNN detectors can be
compared on the SAME cover/stego pairs and the SAME train/test split.

Cover/stego pairs stay in the same split (no content leakage). Features are cached
per image under --cache so covers (shared across algorithms) are extracted once.

Usage:
    python srm_eval.py --algo RandomLSB                  # positive control
    python srm_eval.py --algo Adaptive
    python srm_eval.py --algo Adaptive_p10000 --stego-prefix stego_sweep_
"""

import argparse
import hashlib
import os
import random
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from srm_features import features_from_path, FEATURE_DIM
from ensemble import FldEnsemble, auc_score, p_error


def build_pairs(root, algo, cover_dir, stego_prefix):
    cover_dir = cover_dir or os.path.join(root, "covers_cnn")
    stego_dir = os.path.join(root, stego_prefix + algo)
    ids = [f for f in os.listdir(cover_dir)
           if f.endswith(".png") and os.path.exists(os.path.join(stego_dir, f))]
    ids.sort(key=lambda s: int(os.path.splitext(s)[0]) if os.path.splitext(s)[0].isdigit() else s)
    return [(os.path.join(cover_dir, f), os.path.join(stego_dir, f)) for f in ids]


def split_pairs(pairs, seed, val_frac=0.1, test_frac=0.2):
    """Identical partition to srnet_eval.split_pairs; train+val are merged for the
    ensemble so the TEST pairs match the CNN's exactly."""
    rng = random.Random(seed)
    idx = list(range(len(pairs)))
    rng.shuffle(idx)
    n = len(idx)
    n_test = int(n * test_frac)
    n_val = int(n * val_frac)
    test_ids = idx[:n_test]
    train_ids = idx[n_test:]                 # train+val merged
    return train_ids, test_ids


def feat_cached(path, channel, cache_dir):
    if cache_dir is None:
        return features_from_path(path, channel)
    key = hashlib.md5(("%s|%d|%d" % (os.path.abspath(path), channel,
                                     int(os.path.getmtime(path)))).encode()).hexdigest()
    fp = os.path.join(cache_dir, key + ".npy")
    if os.path.exists(fp):
        return np.load(fp)
    f = features_from_path(path, channel)
    np.save(fp, f)
    return f


def assemble(pairs, ids, channel, cache_dir):
    x = np.empty((len(ids) * 2, FEATURE_DIM), dtype=np.float64)
    y = np.empty(len(ids) * 2, dtype=np.int64)
    k = 0
    for i in ids:
        cover, stego = pairs[i]
        x[k] = feat_cached(cover, channel, cache_dir); y[k] = 0; k += 1
        x[k] = feat_cached(stego, channel, cache_dir); y[k] = 1; k += 1
    return x, y


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.expanduser("~/stego-bench"))
    ap.add_argument("--algo", required=True)
    ap.add_argument("--cover-dir", default=None)
    ap.add_argument("--stego-prefix", default="stego_cnn_")
    ap.add_argument("--channel", type=int, default=1)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--n-learners", type=int, default=60)
    ap.add_argument("--d-sub", type=int, default=1200)
    ap.add_argument("--cache", default=os.path.expanduser("~/stego-bench/srm_cache"))
    args = ap.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)

    pairs = build_pairs(args.root, args.algo, args.cover_dir, args.stego_prefix)
    if not pairs:
        print("no pairs for", args.algo, "under", args.root)
        sys.exit(1)
    if args.limit:
        pairs = pairs[:args.limit]

    # pairing sanity on a few pairs (a +/-1 embed changes a pixel by at most 1)
    from srm_features import load_channel
    worst = 0
    for cover, stego in (pairs[:8] if len(pairs) >= 8 else pairs):
        c = load_channel(cover, args.channel).astype(np.int16)
        s = load_channel(stego, args.channel).astype(np.int16)
        worst = max(worst, int(np.abs(c - s).max()))
    print("pairing check: max |cover-stego| = %d (expect <=1)" % worst)
    if worst > 1:
        raise SystemExit("ABORT: pairs are not aligned (%d > 1)." % worst)

    train_ids, test_ids = split_pairs(pairs, args.seed)
    print("pairs: %d  | train imgs: %d  test: %d"
          % (len(pairs), len(train_ids) * 2, len(test_ids) * 2), flush=True)

    t0 = time.time()
    x_tr, y_tr = assemble(pairs, train_ids, args.channel, args.cache)
    x_te, y_te = assemble(pairs, test_ids, args.channel, args.cache)
    print("features extracted (%.1fs)" % (time.time() - t0), flush=True)

    t0 = time.time()
    clf = FldEnsemble(n_learners=args.n_learners, d_sub=args.d_sub, seed=args.seed)
    clf.fit(x_tr, y_tr)
    scores = clf.decision_scores(x_te)
    acc = ((scores >= 0.5).astype(int) == y_te).mean()
    pe = p_error(y_te, scores)
    auc = auc_score(y_te, scores)
    print("ensemble trained + scored (%.1fs)" % (time.time() - t0))
    print("=" * 60)
    print("ALGO %s  [SRM+ensemble]  TEST  acc %.4f  P_E %.4f  AUC %.4f"
          % (args.algo, acc, pe, auc))
    print("(P_E ~0.5 / AUC ~0.5 => the detector cannot distinguish stego from cover)")
    print("=" * 60)


if __name__ == "__main__":
    main()
