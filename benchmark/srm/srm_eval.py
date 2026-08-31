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
import os
import random
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from srm_features import features_from_path, FEATURE_DIM
from ensemble import FldEnsemble, auc_score, p_error
from eval_common import build_pairs as _build_pairs, split_pairs, assemble as _assemble


def build_pairs(root, algo, cover_dir, stego_prefix):
    cover_dir = cover_dir or os.path.join(root, "covers_cnn")
    stego_dir = os.path.join(root, stego_prefix + algo)
    return _build_pairs(cover_dir, stego_dir, ".png")


def assemble(pairs, ids, channel, cache_dir):
    return _assemble(pairs, ids, channel, cache_dir, FEATURE_DIM, features_from_path)


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
