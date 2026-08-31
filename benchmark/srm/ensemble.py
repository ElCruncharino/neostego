"""
FLD ensemble classifier for rich-model steganalysis -- after Kodovsky, Fridrich &
Holub, "Ensemble Classifiers for Steganalysis of Digital Media" (IEEE TIFS 2012).

A bank of L Fisher Linear Discriminants, each trained on a random subspace of
d_sub features drawn from the high-dimensional SRM vector. Each base learner sets
its own threshold to minimize the training detection error P_E; the ensemble
decision is the majority vote, and the averaged standardized projection serves as
a continuous score for ROC AUC. numpy only -- no sklearn.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from eval_common import auc_score, p_error  # noqa: F401  (re-exported for callers)


class FldEnsemble:
    def __init__(self, n_learners=60, d_sub=1200, ridge=1e-3, seed=0):
        self.n_learners = n_learners
        self.d_sub = d_sub
        self.ridge = ridge
        self.seed = seed
        self.mu_ = None
        self.sd_ = None
        self.subspaces_ = []
        self.weights_ = []     # (w, threshold) per learner

    def _standardize_fit(self, x):
        self.mu_ = x.mean(axis=0)
        self.sd_ = x.std(axis=0) + 1e-8
        return (x - self.mu_) / self.sd_

    def _standardize(self, x):
        return (x - self.mu_) / self.sd_

    def fit(self, x, y):
        """x: (N, D) features, y: (N,) in {0=cover, 1=stego}."""
        rng = np.random.RandomState(self.seed)
        xs = self._standardize_fit(x)
        n, d = xs.shape
        d_sub = min(self.d_sub, d)
        pos = y == 1
        neg = ~pos
        for _ in range(self.n_learners):
            cols = rng.choice(d, size=d_sub, replace=False)
            sub = xs[:, cols]
            m1 = sub[pos].mean(axis=0)
            m0 = sub[neg].mean(axis=0)
            # pooled within-class scatter (regularized) via covariance
            cov = np.cov(sub.T) + self.ridge * np.eye(d_sub)
            try:
                w = np.linalg.solve(cov, m1 - m0)
            except np.linalg.LinAlgError:
                w = m1 - m0
            proj = sub @ w
            thr = self._best_threshold(proj, pos, neg)
            self.subspaces_.append(cols)
            self.weights_.append((w, thr))
        return self

    @staticmethod
    def _best_threshold(proj, pos, neg):
        """Threshold minimizing 0.5*(P_FA + P_MD) on the training projections."""
        order = np.argsort(proj)
        cand = proj[order]
        n_pos = max(1, pos.sum())
        n_neg = max(1, neg.sum())
        best_t, best_e = cand[0] - 1.0, 1.0
        # sweep midpoints between sorted projections
        edges = np.concatenate(([cand[0] - 1.0], (cand[:-1] + cand[1:]) / 2.0, [cand[-1] + 1.0]))
        for t in edges:
            pred = proj >= t
            p_fa = (pred & neg).sum() / n_neg
            p_md = (~pred & pos).sum() / n_pos
            e = 0.5 * (p_fa + p_md)
            if e < best_e:
                best_e, best_t = e, t
        return best_t

    def decision_scores(self, x):
        """Continuous score in [0,1]: fraction of learners voting 'stego'."""
        xs = self._standardize(x)
        votes = np.zeros(xs.shape[0], dtype=np.float64)
        for cols, (w, thr) in zip(self.subspaces_, self.weights_):
            proj = xs[:, cols] @ w
            votes += (proj >= thr).astype(np.float64)
        return votes / len(self.weights_)

    def predict(self, x):
        return (self.decision_scores(x) >= 0.5).astype(int)
