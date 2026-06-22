# Changelog

All notable changes in the NeoStego fork relative to upstream OpenStego are
recorded here. NeoStego is a downstream fork of
[OpenStego](https://www.openstego.com) by Samir Vaidya and remains licensed
under the GNU General Public License, version 2.

The on-disk steganography format is unchanged: files produced by older
OpenStego versions remain readable, and unencrypted NeoStego output stays
compatible with upstream OpenStego (enforced by regression tests).

## [Unreleased]

## [1.0.3] — 2026-06-20

JPEG-cover steganography additions, an Android UI redesign, broad embedding
speed-ups, and a watermark crash fix. No change to the on-disk steganography
format or algorithms.

### Added
- **Plain J-UNIWARD mode** — a new algorithm option that runs the UNIWARD cost
  model against an *already-compressed* JPEG cover (no uncompressed precover).
  It uses the raw UNIWARD cost (no side-information scaling); extraction is the
  same parity-based path as SI-UNIWARD. SI-UNIWARD remains the preferred choice
  when the uncompressed original is available.
- **F5 plugin** — the classic Westfeld (2001) JPEG matrix-encoding scheme, for
  embedding into an existing JPEG cover. Its algorithm core and Blake2b PRNG are
  a near-verbatim port from **Secret Space Encryptor (SSE)** under the MIT
  License (Blake2b is CC0); see [NOTICE](NOTICE) and
  [docs/ALGORITHMS.md](docs/ALGORITHMS.md). Both new options are exposed in the
  desktop CLI/GUI and the Android app.
- **"System" theme mode** that follows the OS light/dark appearance.

### Changed
- **Android UI redesign** — a full Material 3 Expressive refresh (floating
  navigation toolbar, sectioned cards, cover-derived theming) plus an embed-map
  animation that visualises which regions of the image carry hidden data.
- **Faster embedding across the adaptive schemes** — the Syndrome-Trellis Codes
  inner loop (shared by Adaptive, J-UNIWARD and SI-UNIWARD), the UNIWARD cover
  residual filtering (now parallelised), and the HILL cost's box blur (now an
  O(1)-per-pixel running sum) were all sped up. STC and UNIWARD output is
  bit-identical to before; HILL changes only steer where edits go, never
  recoverability.
- **Multithreaded UNIWARD cost computation** — the per-block JPEG cost map is now
  computed in parallel. The result is bit-identical to the sequential path, so
  stego output is unchanged; only embedding speed on large images improves.

### Fixed
- **DWT-SVD watermark** — fixed an out-of-memory crash when embedding a signature
  into a large image on Android, and made verification dramatically faster
  (largest-singular-value-only power iteration plus hoisting redundant work out
  of the crop-alignment search), especially when checking un-watermarked images.

## [1.0.1] — 2026-06-16

Accessibility, contrast, and build-toolchain release. No changes to the
on-disk steganography format or algorithms.

### Accessibility
- **Desktop:** the data-hiding / watermarking navigation buttons are keyboard-
  focusable again (with Alt mnemonics and screen-reader descriptions); all
  icon-only browse buttons and the JPEG-quality slider have accessible names;
  added the missing label/field associations on the Extract panel; the About
  image has alt text.
- **Android:** the password field, file-picker cards, and algorithm radio rows
  expose proper TalkBack semantics, roles, and ≥48dp touch targets; the
  watermark verdict is conveyed by an icon as well as colour.

### Contrast (WCAG)
- Replaced hardcoded UI colours with theme-aware values. The watermark verdict
  now renders on its own contrast-checked container (AA-compliant in light and
  dark), independent of the dynamic Material You palette.

### Build & tooling
- The Windows installer is now an **MSI** (built with WiX) instead of an Inno
  Setup EXE: silent-installable by default (better for winget and unattended
  deployment) with a stable upgrade code so versions upgrade in place.
- Upgraded to Gradle 9.5, Android Gradle Plugin 9.1 (built-in Kotlin) and
  Nebula ospackage 12.3; cleared all build deprecation warnings.
- Added Spotless formatting (palantir-java-format + ktlint), enforced via
  `check`.

## [1.0.0] — 2026

First NeoStego release. Forked from OpenStego at `upstream/master`.

### Branding
- Rebranded the application, UI text, and resources to **NeoStego** with an
  original logo. The OpenStego name is retained only where it credits the
  upstream project (in-app credit line, About screen, this changelog).
- Project homepage now points to the NeoStego repository rather than
  openstego.com.

### Security & cryptography
- New encrypted output uses PBKDF2-HMAC-SHA256 (random salt, high iteration
  count) with AES-GCM authenticated encryption. Data encrypted by older
  versions is still decrypted automatically; pass `--legacyencrypt` (CLI) to
  write the original format when interoperability is required.
- Passwords are handled as `char[]` end-to-end and wiped after use; decrypted
  data is wiped after saving.

### New data-hiding algorithms
- `RandomLSBMatch` — LSB matching (±1) instead of LSB replacement, defeating
  the structural artifacts that RS / Sample-Pair / Chi-square steganalysis
  rely on, while remaining readable by the normal extractor.
- `Adaptive` — content-adaptive embedding combining the HILL cost function
  with Syndrome-Trellis Codes (STC), with an optional CMD sub-lattice variant
  (default). Concentrates ±1 changes in textured regions to minimize
  embedding distortion. Default algorithm in the Android app.
- `SI-UNIWARD` (`JpegUniward`) — JPEG-domain content-adaptive steganography on
  a pure-Java JPEG coefficient codec.
- Adaptive and UNIWARD embedding is tiled to bound peak memory on large covers.

### New watermarking algorithm
- `DWTSVD` — a robust, blind, multi-bit DWT–SVD watermark (QIM on the largest
  singular value, Reed–Solomon error correction, password-keyed scrambling and
  repetition tiling), made robust to global brightness scaling and small crops.
  It is now the default for digital watermarking. The legacy spread-spectrum
  DWT plugins (Dugad/Kim/Xie) are retained.

### Formats & image handling
- New audio (WAV PCM) data-hiding plugin.
- WebP cover support.
- Preserve ICC color profiles; fix transparency-to-JPEG handling; GUI
  JPEG-quality control for watermarking.
- Normalize Exif orientation in the shared decode path so output is upright.
- Allow semicolons in filenames.

### Desktop UI
- Modern cross-platform look-and-feel via FlatLaf, with selectable Light/Dark
  themes remembered between runs.
- Native OS file dialogs, drag-and-drop onto fields, password reveal button,
  per-algorithm guidance and a capacity indicator in the embed UI.
- Lossy re-encoding warnings.

### Android
- New Android app (Kotlin / Jetpack Compose) sharing the `:core` module, with a
  modern UI, algorithm selector, share-sheet image import, and feature parity
  with the desktop client.

### Architecture & build
- Split the project into `:core` and `:desktop` Gradle modules; moved `main()`
  out of the core API.
- Decoupled the data-hiding algorithms from AWT via a `PixelImage` /
  `ImageCodec` abstraction so the core runs on Android.
- Command-line parsing moved to picocli; config stored in the platform-native
  per-user location.

### Testing & evaluation
- Reproducible BOSSbase / StegExpose benchmark harness, plus SRM+FLD-ensemble,
  DCTR, and CNN steganalysis evaluation harnesses.
- Compatibility regression tests that assert format interoperability with
  upstream OpenStego.

### Upstream issues addressed
Includes fixes/features tracked upstream as #5, #23, #24, #58, #60, #62, #63,
#67, and #69.
