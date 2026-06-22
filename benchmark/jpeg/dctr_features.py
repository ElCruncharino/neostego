"""
DCTR (Discrete Cosine Transform Residual) features for JPEG steganalysis -- a
numpy-only implementation of Holub & Fridrich, "Low-Complexity Features for JPEG
Steganalysis Using Undecimated DCT" (IEEE TIFS 2015).

Why a JPEG-specific model: the spatial SRM in ../srm judges PNG/spatial schemes.
SI-UNIWARD embeds in the DCT domain, and a single JPEG re-save scrambles the
spatial pixels relative to the precover, so spatial co-occurrences are the wrong
lens. DCTR is the standard low-complexity JPEG rich model: it filters the
*decompressed* image with the 64 8x8 DCT basis patterns (the "undecimated DCT"),
then histograms the quantized/truncated residuals split by the residual's phase
within the 8x8 JPEG grid -- exactly the structure that DCT-domain embedding
disturbs. It only needs the decompressed image, so it works straight from PIL.

Structure of the 7875-dim vector:
  * 63 AC DCT modes (the DC mode (0,0) is a local average -- it carries no AC
    embedding signal and saturates the truncation, so it is dropped),
  * x 25 phase classes: the 8x8 grid phase (a,b) folded by the cosine symmetry
    a ~ 8-a into 5 classes per axis (0; 1,7; 2,6; 3,5; 4), so 5x5 = 25,
  * x (T+1)=5 magnitude bins after abs/quantize/truncate (T=4),
  * L1-normalized per mode.

It is selection-channel-agnostic and, as the benchmark's null control confirms,
gives AUC ~0.5 on cover-vs-cover while strongly separating high-payload stego.
This is a faithful DCTR-style model (the documented phase folding), not a
bit-exact reproduction of the reference binary -- same spirit as ../srm.
"""

import numpy as np
from PIL import Image

T = 4                       # residual truncation: |.| clipped to [0, T]
DEFAULT_Q = 4.0             # residual quantization step (cover/stego share QF, so absolute scale is moot)
_NBINS = T + 1              # 5
_FOLD = np.array([0, 1, 2, 3, 4, 3, 2, 1])   # phase fold a ~ 8-a -> 5 classes
_NPHASE = 5 * 5             # 25
_NMODES = 63               # 64 DCT modes minus the DC mode

FEATURE_DIM = _NMODES * _NPHASE * _NBINS    # 63 * 25 * 5 = 7875


def _dct_matrix():
    """Orthonormal 8-point DCT-II matrix A[u][n] (matches the codec's Dct8x8)."""
    a = np.zeros((8, 8))
    a[0, :] = np.sqrt(1.0 / 8.0)
    for u in range(1, 8):
        for n in range(8):
            a[u, n] = np.sqrt(2.0 / 8.0) * np.cos((2 * n + 1) * u * np.pi / 16.0)
    return a


# 64 separable 8x8 DCT basis patterns, flattened to (64, 64); row index = u*8+v.
_A = _dct_matrix()
_BASES = np.empty((64, 64))
for _u in range(8):
    for _v in range(8):
        _BASES[_u * 8 + _v] = np.outer(_A[_u], _A[_v]).ravel()

# Mode order with DC dropped: indices 1..63 of u*8+v.
_AC_MODES = np.array([m for m in range(64) if m != 0])


def load_gray(path):
    return np.asarray(Image.open(path).convert("L"), dtype=np.float64)


def features(img, q=DEFAULT_Q):
    """DCTR feature vector for one decompressed grayscale image (2-D float array)."""
    h, w = img.shape
    if h < 8 or w < 8:
        raise ValueError("image too small for 8x8 DCT residual")

    # Undecimated DCT: every 8x8 window dotted with every basis -> residual per mode.
    # windows: (h-7, w-7, 8, 8) -> (P, 64); U: (P, 64) one column per mode.
    win = np.lib.stride_tricks.sliding_window_view(img, (8, 8))
    P0, P1 = win.shape[0], win.shape[1]
    flat = win.reshape(P0 * P1, 64)
    u = flat @ _BASES.T                      # (P, 64)

    # Quantize, abs, truncate -> symbols in {0..T}.
    sym = np.abs(np.round(u / q)).astype(np.int64)
    np.clip(sym, 0, T, out=sym)
    sym = sym.reshape(P0, P1, 64)

    # Phase grid: position (i,j) of a residual maps to phase (i%8, j%8); fold by symmetry.
    ii = _FOLD[np.arange(P0) % 8]            # (P0,)
    jj = _FOLD[np.arange(P1) % 8]            # (P1,)
    phase = (ii[:, None] * 5 + jj[None, :]).ravel()   # (P0*P1,) in 0..24

    out = np.zeros(FEATURE_DIM, dtype=np.float64)
    sym2 = sym.reshape(P0 * P1, 64)
    for mi, mode in enumerate(_AC_MODES):
        s = sym2[:, mode]                    # symbol per position for this mode
        # joint index = phase*_NBINS + symbol, histogram -> 125 values for this mode
        idx = phase * _NBINS + s
        hist = np.bincount(idx, minlength=_NPHASE * _NBINS).astype(np.float64)
        tot = hist.sum()
        if tot > 0:
            hist /= tot
        out[mi * _NPHASE * _NBINS:(mi + 1) * _NPHASE * _NBINS] = hist
    return out


def features_from_path(path, q=DEFAULT_Q):
    return features(load_gray(path), q)


if __name__ == "__main__":
    import sys
    import time

    p = sys.argv[1]
    t0 = time.time()
    f = features_from_path(p)
    print("dim", f.shape[0], "expected", FEATURE_DIM, "time %.3fs" % (time.time() - t0))
    print("finite:", bool(np.isfinite(f).all()), " per-mode sums ~1:",
          [round(float(f[i * 125:(i + 1) * 125].sum()), 3) for i in range(4)])
