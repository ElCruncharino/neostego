# Algorithms, benchmarks & citations

This document covers the steganography and watermarking algorithms in NeoStego, how detection-resistant
they are, the reproducible benchmarks behind those claims, and the academic work they implement.

> **Honest framing.** Steganography hides the *presence* of data; it does not make embedding
> mathematically undetectable. The algorithms below raise the bar against current steganalysis — in
> several cases dramatically — but a sufficiently strong, targeted detector can still succeed.

## Data-hiding algorithms

### `RandomLSBMatch` — LSB matching (±1)

Uses LSB **matching** (randomly ±1) instead of LSB **replacement**. This defeats the structural
artifacts that RS / Sample-Pair / Chi-square steganalysis (e.g. StegExpose) rely on, while staying
readable by the normal extractor.

### `Adaptive` — content-adaptive (HILL + STC)

Combines the **HILL** cost function with **Syndrome-Trellis Codes (STC)** to concentrate the ±1
changes in textured, hard-to-model regions and minimise total embedding distortion. It is the default
algorithm in the Android app.

In a benchmark over 1,000 BOSSbase 1.01 images at ~0.4 bpp, StegExpose flagged **0.3%** of `Adaptive`
stego images — identical to the clean-cover control, versus **99.5%** for plain LSB replacement. This
raises the bar against modern (including CNN-based) steganalysis; it does not make embedding undetectable.

### `SI-UNIWARD` / `JpegUniward` — JPEG-domain adaptive

Side-Informed UNIWARD compresses an uncompressed precover (PNG/BMP) to JPEG itself, using the
quantization rounding errors as side information to steer minimal, STC-coded ±1 DCT changes into
wavelet-textured regions. It is the consensus most-secure practical JPEG scheme and strongly resists
modern DCT-domain steganalysis.

Selecting the **plain J-UNIWARD** mode (a separate algorithm entry) runs the same plugin against an
**already-compressed JPEG** cover instead of a precover: with no rounding errors available it uses the
raw UNIWARD cost (no side-information scaling) and embeds with ±1 changes. This lets a user who only
has a JPEG still use the UNIWARD cost model; it is faster but less secure than true SI-UNIWARD, which
should be preferred whenever the uncompressed original is available. Extraction is identical for both
(parity-based, side-information-free), so either is revealed by the same plugin.

### `F5` — classic JPEG matrix encoding

Andreas Westfeld's F5 (2001): it permutes the quantized DCT coefficients with a password-seeded
Blake2b PRNG and uses `(1, n, k)` matrix encoding to hide each bit while changing as few coefficients
as possible, always decrementing magnitude toward zero (with shrinkage handling at zero crossings).
It embeds into an already-compressed JPEG cover and reuses the cover's quantization tables. F5 is fast
and well established but is a fixed (non-adaptive) embedder, so it is more detectable than SI-UNIWARD —
offered as a lightweight, broadly-compatible option for small payloads. The algorithm core and PRNG
are a near-verbatim port of [Secret Space Encryptor (SSE)](https://paranoiaworks.mobi) under the MIT
License (Blake2b is CC0); see [NOTICE](../NOTICE).

### `WavLSB` — audio

Hides data in the least-significant bit of each integer PCM sample of an uncompressed **WAV** cover.
The output is a same-format WAV that still plays normally, and compression/encryption work just like
the image algorithms. Lossy/compressed audio (MP3, AAC, Ogg) cannot carry LSB data, by nature.

## Watermarking

### `DWTSVD` — robust blind watermark (default)

Unlike the legacy spread-spectrum DWT plugins (Dugad/Kim/Xie), which only *detect* the presence of a
password-keyed pattern via correlation, `DWTSVD` embeds an actual multi-bit payload that is recovered
**blindly** (no original image needed) and survives common processing.

How it works:

- Quantizes (QIM) the largest singular value of 8×8 blocks of the level-1 DWT approximation (LL)
  sub-band of the luminance.
- Protects the payload with **Reed–Solomon** error correction, then scrambles and repetition-tiles
  the code bits with a password-derived key.
- The QIM step is made proportional to a global gain-linear reference (the mean singular value μ
  across blocks) — a Rational Dither Modulation idea — so a global **brightness/exposure** change
  scales the signal and the quantizer bins together and the watermark is preserved.

At its default strength it embeds at ~40 dB PSNR and recovers the watermark cleanly through JPEG
re-compression (down to Q50), additive noise, blurring, resampling and global brightness scaling (a
benchmark over 7 covers detects 100% with zero bit errors across all of those). It also tolerates a
**small pure crop** (trimming a modest border without rescaling): extraction searches small grid-phase
and block-origin offsets to re-synchronise the block grid, and the code bits are tiled by an absolute,
size-independent keyed mapping so blocks that survive a crop still decode.

**Out of scope:** large **contrast** changes are only partially handled (contrast is an
around-the-mean affine shift that μ-normalisation does not fully cancel), and larger geometric
desynchronisation (big crops, crop **plus** rescale, large rotation/scaling) remains unsupported.

The legacy DWT plugins (Dugad/Kim/Xie) remain available via the command line.

## Reproducible benchmarks

The [`benchmark/`](../benchmark) directory contains reproducible harnesses:

- **`benchmark/watermark`** — DWTSVD robustness across JPEG/noise/blur/resample/brightness/crop.
- BOSSbase 1.01 + **StegExpose** evaluation of the data-hiding algorithms.
- Rich-model and learning-based steganalysis adversaries: **DCTR**, **SRM** + **FLD ensemble**, and a
  **SRNet** CNN harness.

## Citations

The modern data-hiding and steganalysis capabilities implement methods published by Jessica Fridrich's
[DDE Lab at Binghamton University](https://dde.binghamton.edu/download/stego_algorithms/) and
collaborators. The DDE Lab's own source code is released by them for **research and non-profit use**
(the DDE Lab retains copyright). NeoStego's plugins are **independent, clean-room implementations
written from the published papers** — no DDE Lab source code is used or derived.

**Data hiding**

- **HILL** — B. Li, M. Wang, J. Huang & X. Li, "A new cost function for spatial image steganography," *IEEE ICIP*, 2014.
- **Syndrome-Trellis Codes (STC)** — T. Filler, J. Judas & J. Fridrich, "Minimizing additive distortion in steganography using syndrome-trellis codes," *IEEE TIFS*, 2011.
- **UNIWARD** — V. Holub, J. Fridrich & T. Denemark, "Universal distortion function for steganography in an arbitrary domain," *EURASIP Journal on Information Security*, 2014.
- **F5** — A. Westfeld, "F5 — A Steganographic Algorithm," *Information Hiding 2001 (LNCS 2137)*. The implementation is a licensed port of [SSE](https://paranoiaworks.mobi) (MIT), not clean-room — see [NOTICE](../NOTICE).

**Steganalysis (evaluation harnesses)**

- **DCTR** — V. Holub & J. Fridrich, "Low-complexity features for JPEG steganalysis using undecimated DCT," *IEEE TIFS*, 2015.
- **SRM** + **FLD ensemble** — J. Fridrich & J. Kodovský, "Rich models for steganalysis of digital images," *IEEE TIFS*, 2012; J. Kodovský, J. Fridrich & V. Holub, "Ensemble classifiers for steganalysis of digital media," *IEEE TIFS*, 2012.
- **SRNet** — M. Boroumand, M. Chen & J. Fridrich, "Deep residual network for steganalysis of digital images," *IEEE TIFS*, 2019.

**Watermarking**

- **Spread-spectrum DWT (Dugad/Kim/Xie)** — based on code from Peter Meerwald, *Digital Image Watermarking in the Wavelet Transfer Domain*, Master's Thesis, University of Salzburg, 2001.
- **DWT–SVD–QIM** — a well-established robust, blind approach; see e.g. Kang, Zhao, Lin & Chen, *Multimedia Tools and Applications* 77, 2018, and subsequent DWT–SVD–QIM evaluations.

Deep gratitude to these researchers for making their work openly available to the community.
