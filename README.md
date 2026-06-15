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
The digital watermarking code in this product is based on the code provided by Peter Meerwald. Refer to his excellent thesis on [watermarking](http://www.cosy.sbg.ac.at/~pmeerw/Watermarking/): Peter Meerwald, Digital Image Watermarking in the Wavelet Transfer Domain, Master's Thesis, Department of Scientific Computing, University of Salzburg, Austria, January 2001.
