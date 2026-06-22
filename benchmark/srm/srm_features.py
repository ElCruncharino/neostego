"""
Spatial Rich Model (SRM) co-occurrence features for steganalysis -- a numpy-only
subset of Fridrich & Kodovsky, "Rich Models for Steganalysis of Digital Images"
(IEEE TIFS 2012).

Why add this alongside the CNN: a small CNN trained on ~1k pairs is a weak judge
at low payload. The SRM + FLD-ensemble pipeline is the classical gold standard and
is frequently STRONGER than a light CNN against content-adaptive embedding, so it
is the honest adversary for deciding whether the Adaptive algorithm is really hard
to detect. A finding only survives if it survives this detector too.

This implements a faithful subset of the model: noise residuals (1st/2nd/3rd
order, the KB 3x3 'square' kernel, and min/max non-linear submodels), each
quantized + truncated, then summarized by 4th-order co-occurrence histograms along
the horizontal and vertical scans, L1-normalized per submodel. It is not the full
39-submodel SRM, but it is selection-channel-agnostic, well-defined, and -- as the
positive control confirms -- detects plain LSB / LSB-matching near-perfectly.

All residuals operate on one image channel (default green) so the comparison with
the single-channel CNN harness is apples-to-apples.
"""

import numpy as np
from PIL import Image

T = 2                     # residual truncation: values clipped to [-T, T]
Q = 1.0                   # quantization step (SRMQ1)
_S = 2 * T + 1            # symbols per residual position (=5)
_BINS = _S ** 4          # 4th-order co-occurrence size (=625)


def load_channel(path, channel=1):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float64)[:, :, channel]


def _quant_trunc(r):
    r = np.round(r / Q)
    np.clip(r, -T, T, out=r)
    return r.astype(np.int64)


def _cooc(rq):
    """4th-order co-occurrence over horizontal AND vertical 4-tuples of a quantized
    residual map, returned as one L1-normalized 2*625 vector."""
    feats = []
    for m in (rq, rq.T):                       # horizontal, then vertical (via transpose)
        a = m[:, 0:-3] + T
        b = m[:, 1:-2] + T
        c = m[:, 2:-1] + T
        d = m[:, 3:] + T
        idx = ((a * _S + b) * _S + c) * _S + d
        h = np.bincount(idx.ravel(), minlength=_BINS).astype(np.float64)
        s = h.sum()
        feats.append(h / s if s > 0 else h)
    return np.concatenate(feats)


# ---- residuals (each returns a float map; borders trimmed by the kernel) ----

def _r1(x):
    return x[:, 1:] - x[:, :-1]                                  # 1st order, horizontal


def _r2(x):
    return x[:, :-2] - 2.0 * x[:, 1:-1] + x[:, 2:]              # 2nd order


def _r3(x):
    return x[:, :-3] - 3.0 * x[:, 1:-2] + 3.0 * x[:, 2:-1] - x[:, 3:]   # 3rd order


def _square(x):
    # KB 3x3 high-pass: [[-1,2,-1],[2,-4,2],[-1,2,-1]]
    return (-x[:-2, :-2] + 2 * x[:-2, 1:-1] - x[:-2, 2:]
            + 2 * x[1:-1, :-2] - 4 * x[1:-1, 1:-1] + 2 * x[1:-1, 2:]
            - x[2:, :-2] + 2 * x[2:, 1:-1] - x[2:, 2:])


def _minmax(x):
    """1st-order residuals in 4 directions on a shared interior, combined by
    pixelwise min and max (the SRM 'minmax' non-linear submodels)."""
    up = x[:-2, 1:-1] - x[1:-1, 1:-1]
    down = x[2:, 1:-1] - x[1:-1, 1:-1]
    left = x[1:-1, :-2] - x[1:-1, 1:-1]
    right = x[1:-1, 2:] - x[1:-1, 1:-1]
    stack = np.stack([up, down, left, right])
    return stack.min(axis=0), stack.max(axis=0)


def features(img):
    """SRM feature vector for one channel image (2-D float array)."""
    blocks = []
    blocks.append(_cooc(_quant_trunc(_r1(img))))
    blocks.append(_cooc(_quant_trunc(_r2(img))))
    blocks.append(_cooc(_quant_trunc(_r3(img))))
    blocks.append(_cooc(_quant_trunc(_square(img))))
    rmin, rmax = _minmax(img)
    blocks.append(_cooc(_quant_trunc(rmin)))
    blocks.append(_cooc(_quant_trunc(rmax)))
    return np.concatenate(blocks)


def features_from_path(path, channel=1):
    return features(load_channel(path, channel))


FEATURE_DIM = 6 * 2 * _BINS   # 6 submodels x {h,v} x 625 = 7500


if __name__ == "__main__":
    # quick shape/throughput check
    import sys, time
    p = sys.argv[1]
    t0 = time.time()
    f = features_from_path(p)
    print("dim", f.shape[0], "expected", FEATURE_DIM, "time %.3fs" % (time.time() - t0))
    print("sum per submodel:", [round(float(f[i * 1250:(i + 1) * 1250].sum()), 3) for i in range(6)])
