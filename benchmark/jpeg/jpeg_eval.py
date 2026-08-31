"""
Train and evaluate the DCTR + FLD-ensemble JPEG steganalyzer against one NeoStego
JPEG algorithm, mirroring ../srm/srm_eval.py so the spatial and JPEG detectors
share the same ensemble, split convention, and reporting.

Covers and stegos are both JPEGs produced by NeoStego's own codec at the same
quality (see make_jpeg_pairs.sh), so the detector sees only the embedding change,
not a compressor mismatch. Pairs are matched by filename and kept in the same
train/test split (no content leakage). DCTR features are cached per image.

A --null run pairs covers against *other* covers (no stego) to confirm the
detector scores at chance when there is nothing to find -- the honesty control.

Usage:
    python jpeg_eval.py --covers ~/stego-bench/covers_jpeg_q90 \
                        --stego  ~/stego-bench/stego_jpeg_JpegUniward_q90_p8000
    python jpeg_eval.py --covers ... --stego ... --null
"""

import argparse
import os
import random
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "srm"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from dctr_features import features_from_path, FEATURE_DIM, load_gray
from ensemble import FldEnsemble, auc_score, p_error
from eval_common import build_pairs as _build_pairs, split_pairs, assemble as _assemble


def build_pairs(cover_dir, stego_dir):
    return _build_pairs(cover_dir, stego_dir, ".jpg")


def build_null_pairs(cover_dir):
    """Cover-vs-cover control: split the covers into two disjoint halves and label
    one half '1'. There is no embedding, so a fair detector must score at chance."""
    files = sorted(f for f in os.listdir(cover_dir) if f.endswith(".jpg"))
    files = [os.path.join(cover_dir, f) for f in files]
    half = len(files) // 2
    return [(files[2 * i], files[2 * i + 1]) for i in range(half)]


def assemble(pairs, ids, q, cache_dir):
    return _assemble(pairs, ids, q, cache_dir, FEATURE_DIM, features_from_path, param_fmt="%.3f")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--covers", required=True)
    ap.add_argument("--stego", default=None)
    ap.add_argument("--null", action="store_true", help="cover-vs-cover honesty control")
    ap.add_argument("--label", default=None)
    ap.add_argument("--q", type=float, default=4.0)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--n-learners", type=int, default=60)
    ap.add_argument("--d-sub", type=int, default=1200)
    ap.add_argument("--cache", default=os.path.expanduser("~/stego-bench/dctr_cache"))
    args = ap.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)

    label = args.label or (("null:" + os.path.basename(args.covers)) if args.null
                           else os.path.basename(args.stego or ""))

    if args.null:
        pairs = build_null_pairs(args.covers)
    else:
        if not args.stego:
            print("--stego is required unless --null")
            sys.exit(2)
        pairs = build_pairs(args.covers, args.stego)
    if not pairs:
        print("no pairs for", label)
        sys.exit(1)
    if args.limit:
        pairs = pairs[:args.limit]

    # Sanity: in a real (non-null) run, cover and stego must actually differ.
    if not args.null:
        diffs = 0
        for cover, stego in (pairs[:8] if len(pairs) >= 8 else pairs):
            c = load_gray(cover)
            s = load_gray(stego)
            if c.shape == s.shape and int(np.abs(c - s).sum()) > 0:
                diffs += 1
        print("pairing check: %d/%d sampled pairs differ (expect all)" % (diffs, min(8, len(pairs))))
        if diffs == 0:
            raise SystemExit("ABORT: cover and stego are identical -- embedding did nothing.")

    train_ids, test_ids = split_pairs(pairs, args.seed)
    print("pairs: %d  | train imgs: %d  test: %d"
          % (len(pairs), len(train_ids) * 2, len(test_ids) * 2), flush=True)

    t0 = time.time()
    x_tr, y_tr = assemble(pairs, train_ids, args.q, args.cache)
    x_te, y_te = assemble(pairs, test_ids, args.q, args.cache)
    print("DCTR features extracted (%.1fs)" % (time.time() - t0), flush=True)

    t0 = time.time()
    clf = FldEnsemble(n_learners=args.n_learners, d_sub=args.d_sub, seed=args.seed)
    clf.fit(x_tr, y_tr)
    scores = clf.decision_scores(x_te)
    acc = ((scores >= 0.5).astype(int) == y_te).mean()
    pe = p_error(y_te, scores)
    auc = auc_score(y_te, scores)
    print("ensemble trained + scored (%.1fs)" % (time.time() - t0))
    print("=" * 60)
    print("ALGO %s  [DCTR+ensemble]  TEST  acc %.4f  P_E %.4f  AUC %.4f"
          % (label, acc, pe, auc))
    print("(P_E ~0.5 / AUC ~0.5 => the detector cannot distinguish stego from cover)")
    print("=" * 60)


if __name__ == "__main__":
    main()
