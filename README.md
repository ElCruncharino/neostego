# NeoStego
NeoStego is a steganography application that provides two functionalities:

1. Data Hiding: It can hide any data within an image file.

2. Watermarking: Watermarking image files with an invisible signature. It can be used to detect unauthorized file copying.

## About this fork
NeoStego is a downstream fork of [OpenStego](https://www.openstego.com) by Samir Vaidya, with a
modernized desktop experience and a new Android app. The on-disk steganography format is unchanged,
so files created by previous versions of OpenStego remain fully readable, and unencrypted output
stays compatible with upstream OpenStego (regression tests enforce this).

Changes in this fork:
- Modern, cross-platform UI via the [FlatLaf](https://www.formdev.com/flatlaf/) look-and-feel,
  with selectable Light and Dark themes (remembered between runs).
- Native operating-system file dialogs (Windows Explorer / native desktop picker) for opening
  and saving files, plus drag-and-drop of files onto fields and a reveal button on password fields.
- Stronger encryption for newly encrypted data: PBKDF2-HMAC-SHA256 with a random salt and a high
  iteration count, combined with AES-GCM authenticated encryption. Data encrypted by older versions
  is still decrypted automatically. New encrypted output is therefore not readable by stock upstream
  OpenStego; pass `--legacyencrypt` (CLI) to write the original format when interoperability is needed.
- A more detection-resistant data-hiding algorithm: `RandomLSBMatch` uses LSB matching (&plusmn;1)
  instead of LSB replacement, which defeats the structural artifacts that RS / Sample-Pair /
  Chi-square steganalysis (e.g. StegExpose) rely on, while staying readable by the normal extractor.
- A content-adaptive algorithm: `Adaptive` combines the HILL cost function with Syndrome-Trellis
  Codes (STC) to concentrate the &plusmn;1 changes in textured, hard-to-model regions and minimise
  total embedding distortion. It is the default in the Android app. In a benchmark over 1,000
  BOSSbase&nbsp;1.01 images at ~0.4&nbsp;bpp, StegExpose flagged 0.3% of `Adaptive` stego images &mdash;
  identical to the clean-cover control, versus 99.5% for plain LSB replacement. This raises the bar
  against modern (including CNN-based) steganalysis; it does not make embedding undetectable.
- A modern, robust watermarking algorithm: `DWTSVD` is the default for digital watermarking. Unlike
  the legacy spread-spectrum DWT plugins (Dugad/Kim/Xie), which only *detect* the presence of a
  password-keyed pattern via correlation, `DWTSVD` embeds an actual multi-bit payload that is
  recovered **blindly** (no original image needed) and survives common processing. It quantizes
  (QIM) the largest singular value of 8&times;8 blocks of the level-1 DWT approximation (LL) sub-band
  of the luminance, protects the payload with Reed&ndash;Solomon error correction, and scrambles and
  repetition-tiles the code bits with a password-derived key. The QIM step is made proportional to a
  global gain-linear reference (the mean singular value &mu; across blocks) &mdash; a Rational Dither
  Modulation idea &mdash; so a global **brightness/exposure** change scales the signal and the
  quantizer bins together and the watermark is preserved. At its default strength it embeds at
  ~40&nbsp;dB PSNR and recovers the watermark cleanly through JPEG re-compression (down to Q50),
  additive noise, blurring, resampling and global brightness scaling (a benchmark over 7 covers
  detects 100% with zero bit errors across all of those). The legacy DWT plugins remain available via
  the command line. A reproducible robustness benchmark lives in
  [`benchmark/watermark`](benchmark/watermark). It also tolerates a **small pure crop** (trimming a
  modest border without rescaling, the common "crop the edges and re-save" edit): extraction searches
  small grid-phase and block-origin offsets to re-synchronise the block grid, and the code bits are
  tiled by an absolute, size-independent keyed mapping so blocks that survive a crop still decode (a
  reported weakness, upstream issue&nbsp;#69). As for other non-neural blind schemes, large
  **contrast** changes are only partially handled (contrast is an around-the-mean affine shift that
  &mu;-normalisation does not fully cancel) and larger geometric desynchronisation (big crops, crop
  **plus** rescale, large rotation/scaling to a different size) remains out of scope.
- **Audio data hiding.** A new `WavLSB` algorithm hides data in the least-significant bit of each
  integer PCM sample of an uncompressed **WAV** cover (upstream issue&nbsp;#5). The output is a
  same-format WAV that still plays normally, and compression/encryption work just like the image
  algorithms. Lossy/compressed audio (MP3, AAC, Ogg) cannot carry LSB data, by nature.
- **More cover formats and fidelity.** **WebP** covers can be read (upstream issue&nbsp;#63; output
  stays a lossless PNG). The cover's embedded **ICC colour profile** is now carried through to the
  output for both data hiding and watermarking (upstream issue&nbsp;#62), alpha images watermarked to
  JPEG are flattened instead of erroring (issue&nbsp;#58), JPEG output quality is selectable
  (`--quality` / a slider; issue&nbsp;#24), and a single filename may contain the `;` list delimiter
  (issue&nbsp;#60). Oversized payloads fail with a clear capacity/heap message rather than an
  `OutOfMemoryError` (issue&nbsp;#67).
- Command-line parsing handled by [picocli](https://picocli.info/); the plugin SPI no longer depends
  on any command-line types.
- Build and runtime updated to Java 21, with Gradle, dependencies and CI refreshed and no Gradle
  deprecation warnings.
- Restructured into Gradle modules so the steganography/crypto logic can be reused beyond the desktop:
  - `core` &mdash; platform-independent algorithms, crypto and plugin SPI (no AWT/Swing).
  - `desktop` &mdash; the Swing GUI and command-line interface.
  - `android` &mdash; a Kotlin/Jetpack&nbsp;Compose Android app (hide/reveal + encryption) built on `core`.

## Building

Desktop application and CLI distribution:
```
./gradlew clean :desktop:dist        (Linux / macOS)
gradlew clean :desktop:dist          (Windows)
```

Android debug APK (requires an Android SDK; set its path in `local.properties` as `sdk.dir=...`):
```
./gradlew :android:assembleDebug
```

## Usage

### For GUI:
Use menu shortcut for OpenStego if you used installer. For zip downloads, use the bundled batch file or shell script to launch the GUI.
```
neostego.bat                 (Windows)
```
```
./neostego.sh                (Linux / MacOS)
```

### For command line interface:
Refer to [online documentation](https://www.openstego.com/cmdline.html).

## Development
Fork the repository, clone it locally and execute following to build it fully:
```
gradlew clean dist           (Windows)
```
```
./gradlew clean dist         (Linux / MacOS)
```
*Note:* Windows installer will be generated only if you execute build on Windows environment. It needs [Inno Setup](https://jrsoftware.org/isdl.php) to be installed, and `iscc.exe` to be on `PATH`. If you don't want to generate Windows installer, you can skip the same using following command:
```
./gradlew clean dist -x distWin
```

## Limitations
- **Save and share losslessly.** Hidden data lives in the least-significant bits of the carrier, so
  the output must stay in a lossless format &mdash; PNG for images, uncompressed WAV for audio.
  Re-encoding a stego file as JPEG, MP3 &mdash; or any other lossy format, including the automatic
  recompression some chat apps apply when sending a "photo" &mdash; destroys the hidden data. Share
  the file as a document, not as a photo.
- **Capacity needs a proportionally large cover.** Data hiding stores the payload in the carrier's
  samples (roughly one payload byte per ~3 image pixels, or per 8 audio samples), so a large file
  needs a large cover; an oversized payload fails fast with a clear message rather than crashing.
- **Transparency is preserved, not used.** Images with an alpha channel keep it through the
  embed/extract round-trip; data is hidden only in the RGB channels, never in alpha. Fully
  transparent pixels still carry data in their (invisible) colour values.
- **Capacity varies by algorithm.** The content-adaptive algorithm trades capacity for security and
  holds less than plain LSB; the app shows the usable capacity once a cover image is selected.

## Authors
- Original OpenStego: Samir Vaidya (samir [at] openstego.com)
- This fork: Nick Haghiri

## Homepage
https://www.openstego.com (original project)

## License
This is a modified version of OpenStego, distributed under the **GNU General Public License,
version 2** (see the ```LICENSE``` file). In keeping with the GPL: the original copyright notices
are retained, files changed in this fork carry modification notices, and the complete corresponding
source code for the fork is the contents of this repository.

## Acknowledgement
The legacy spread-spectrum DWT watermarking plugins (Dugad/Kim/Xie) in this product are based on the code provided by Peter Meerwald. Refer to his excellent thesis on [watermarking](http://www.cosy.sbg.ac.at/~pmeerw/Watermarking/): Peter Meerwald, Digital Image Watermarking in the Wavelet Transfer Domain, Master's Thesis, Department of Scientific Computing, University of Salzburg, Austria, January 2001. The default `DWTSVD` watermark is a hybrid DWT&ndash;SVD scheme with QIM on the largest singular value, a well-established robust, blind approach (see e.g. Kang, Zhao, Lin &amp; Chen, *Multimedia Tools and Applications* 77, 2018, and subsequent DWT&ndash;SVD&ndash;QIM evaluations).

### Steganography & steganalysis algorithms
The modern data-hiding and steganalysis capabilities in NeoStego would not exist without the
foundational research published by Jessica Fridrich's [DDE Lab at Binghamton University](https://dde.binghamton.edu/download/stego_algorithms/)
and collaborators, whose papers and reference implementations defined this field. The DDE Lab's own
source code is released by them for **research and non-profit use** (the DDE Lab retains copyright).
The corresponding NeoStego plugins are **independent, clean-room implementations written from the
published papers** &mdash; they implement the *methods* described below; no DDE Lab source code is
used or derived. Citations:

- **`Adaptive` (content-adaptive spatial embedding)** implements:
  - the **HILL** cost function &mdash; B. Li, M. Wang, J. Huang &amp; X. Li, "A new cost function for spatial image steganography," *IEEE ICIP*, 2014; and
  - **Syndrome-Trellis Codes (STC)** for minimal-distortion coding &mdash; T. Filler, J. Judas &amp; J. Fridrich, "Minimizing additive distortion in steganography using syndrome-trellis codes," *IEEE TIFS*, 2011.
- **`SI-UNIWARD` / `JpegUniward` (JPEG-domain adaptive embedding)** implements **UNIWARD** &mdash; V. Holub, J. Fridrich &amp; T. Denemark, "Universal distortion function for steganography in an arbitrary domain," *EURASIP Journal on Information Security*, 2014.
- The **steganalysis benchmark harnesses** (`benchmark/`) reimplement, for evaluation only, the **DCTR** features (V. Holub &amp; J. Fridrich, *IEEE TIFS*, 2015), the **SRM** rich model and **FLD ensemble** (J. Fridrich &amp; J. Kodovsky, *IEEE TIFS*, 2012/2013), and the **SRNet** detector (M. Boroumand, M. Chen &amp; J. Fridrich, *IEEE TIFS*, 2019).

Deep gratitude to these researchers for making their work openly available to the community.
