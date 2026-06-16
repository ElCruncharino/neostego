#!/usr/bin/env python3
"""Robustness benchmark for the NeoStego DWTSVD watermark.

For each cover image this harness embeds a fixed watermark (via the NeoStego CLI), measures the
watermarked-image PSNR, then applies a battery of attacks (JPEG re-compression, Gaussian noise,
Gaussian blur, resampling, brightness/contrast, crop) and asks the CLI to verify the watermark on
each attacked image. It reports, per attack and strength, the mean detection correlation, the mean
bit-error rate (derived from the correlation: BER = (1 - corr) / 2) and the detection rate (fraction
with correlation above the plugin's high-watermark threshold of 0.5).

The watermark embed/extract is done by the Java CLI; only attacks and metrics are computed here.

Usage:
    python3 attacks.py --jar <neostego.jar> --covers <dir-of-pngs> --out <workdir> \
        [--limit N] [--password KEY]

Requires: Pillow, numpy.
"""
import argparse
import csv
import os
import subprocess
import sys

try:
    from PIL import Image, ImageFilter, ImageEnhance
    import numpy as np
except ImportError as exc:  # pragma: no cover - environment guidance
    sys.exit("This benchmark needs Pillow and numpy: pip install pillow numpy (%s)" % exc)

HIGH_THRESHOLD = 0.5  # matches DWTSVDPlugin.getHighWatermarkLevel()


JVM_PROPS = []  # extra -D options, populated from CLI args


def run_cli(jar, args):
    """Invoke the NeoStego CLI and return stdout (text)."""
    proc = subprocess.run(["java"] + JVM_PROPS + ["-jar", jar] + args,
                          capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError("CLI failed: %s\n%s" % (" ".join(args), proc.stderr))
    return proc.stdout


def checkmark(jar, sig, image):
    out = run_cli(jar, ["checkmark", "-a", "DWTSVD", "-gf", sig, "-sf", image])
    line = out.strip().splitlines()[-1].strip()
    return float(line)


def psnr(a_path, b_path):
    a = np.asarray(Image.open(a_path).convert("RGB"), dtype=np.float64)
    b = np.asarray(Image.open(b_path).convert("RGB"), dtype=np.float64)
    mse = np.mean((a - b) ** 2)
    if mse == 0:
        return 99.0
    return 10.0 * np.log10(255.0 * 255.0 / mse)


# ---------------------------------------------------------------------------
# Attacks: each returns a callable img -> img
# ---------------------------------------------------------------------------

def attack_jpeg(quality):
    def f(img, path):
        img.convert("RGB").save(path, "JPEG", quality=quality)
        return Image.open(path)
    return ("jpeg", quality, f)


def attack_noise(sigma):
    def f(img, path):
        arr = np.asarray(img.convert("RGB"), dtype=np.float64)
        rng = np.random.default_rng(1234)
        arr = np.clip(arr + rng.normal(0, sigma, arr.shape), 0, 255).astype(np.uint8)
        out = Image.fromarray(arr)
        out.save(path, "PNG")
        return out
    return ("noise", sigma, f)


def attack_blur(radius):
    def f(img, path):
        out = img.convert("RGB").filter(ImageFilter.GaussianBlur(radius))
        out.save(path, "PNG")
        return out
    return ("blur", radius, f)


def attack_scale(factor):
    def f(img, path):
        w, h = img.size
        small = img.convert("RGB").resize((max(1, int(w * factor)), max(1, int(h * factor))), Image.BICUBIC)
        out = small.resize((w, h), Image.BICUBIC)  # back to original dims so the block grid realigns
        out.save(path, "PNG")
        return out
    return ("scale", factor, f)


def attack_brightness(factor):
    def f(img, path):
        out = ImageEnhance.Brightness(img.convert("RGB")).enhance(factor)
        out.save(path, "PNG")
        return out
    return ("brightness", factor, f)


def attack_contrast(factor):
    def f(img, path):
        out = ImageEnhance.Contrast(img.convert("RGB")).enhance(factor)
        out.save(path, "PNG")
        return out
    return ("contrast", factor, f)


def attack_crop(keep):
    # Center-crop to `keep` of each dimension then resize back: destructive to block sync (documented limitation).
    def f(img, path):
        w, h = img.size
        cw, ch = int(w * keep), int(h * keep)
        left, top = (w - cw) // 2, (h - ch) // 2
        out = img.convert("RGB").crop((left, top, left + cw, top + ch)).resize((w, h), Image.BICUBIC)
        out.save(path, "PNG")
        return out
    return ("crop", keep, f)


def build_attacks():
    attacks = []
    for q in (95, 90, 80, 70, 60, 50):
        attacks.append(attack_jpeg(q))
    for s in (1, 2, 3, 5, 8):
        attacks.append(attack_noise(s))
    for r in (0.5, 1.0, 1.5, 2.0):
        attacks.append(attack_blur(r))
    for f in (0.9, 0.75, 1.25):
        attacks.append(attack_scale(f))
    for b in (0.9, 1.1):
        attacks.append(attack_brightness(b))
    for c in (0.9, 1.1):
        attacks.append(attack_contrast(c))
    for k in (0.95, 0.9):
        attacks.append(attack_crop(k))
    return attacks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--covers", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=12)
    ap.add_argument("--password", default="benchmark-key")
    ap.add_argument("--strength", type=float, default=None,
                    help="override the relative QIM step (dwtsvd.strength) for tuning")
    args = ap.parse_args()

    if args.strength is not None:
        JVM_PROPS.append("-Ddwtsvd.strength=%s" % args.strength)

    os.makedirs(args.out, exist_ok=True)
    sig = os.path.join(args.out, "key.sig")
    run_cli(args.jar, ["gensig", "-a", "DWTSVD", "-p", args.password, "-gf", sig])

    covers = sorted(f for f in os.listdir(args.covers) if f.lower().endswith(".png"))[:args.limit]
    if not covers:
        sys.exit("No PNG covers found in %s" % args.covers)

    attacks = build_attacks()
    # accumulators keyed by (attack, param)
    agg = {}
    psnrs = []

    print("== embedding + attacking %d covers ==" % len(covers))
    for idx, name in enumerate(covers):
        cover = os.path.join(args.covers, name)
        stego = os.path.join(args.out, "stego_%d.png" % idx)
        run_cli(args.jar, ["embedmark", "-a", "DWTSVD", "-gf", sig, "-cf", cover, "-sf", stego])
        psnrs.append(psnr(cover, stego))

        # clean (no attack) baseline
        record(agg, "clean", 0, checkmark(args.jar, sig, stego))

        base = Image.open(stego)
        for (atk, param, fn) in attacks:
            attacked = os.path.join(args.out, "atk.png" if atk != "jpeg" else "atk.jpg")
            fn(base.copy(), attacked)
            corr = checkmark(args.jar, sig, attacked)
            record(agg, atk, param, corr)
        sys.stdout.write("\r   %d/%d covers" % (idx + 1, len(covers)))
        sys.stdout.flush()
    print()

    # write CSV + summary
    csv_path = os.path.join(args.out, "robustness.csv")
    with open(csv_path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["attack", "param", "mean_corr", "mean_ber", "detect_rate", "n"])
        print("\n%-12s %-7s %9s %9s %12s" % ("attack", "param", "mean_corr", "mean_ber", "detect_rate"))
        print("-" * 56)
        for key in sorted(agg.keys(), key=lambda k: (k[0], k[1])):
            vals = agg[key]
            mean_corr = sum(vals) / len(vals)
            mean_ber = (1.0 - mean_corr) / 2.0
            detect = sum(1 for v in vals if v > HIGH_THRESHOLD) / len(vals)
            w.writerow([key[0], key[1], "%.4f" % mean_corr, "%.4f" % mean_ber, "%.3f" % detect, len(vals)])
            print("%-12s %-7s %9.3f %9.4f %11.1f%%" % (key[0], key[1], mean_corr, mean_ber, detect * 100))
    print("-" * 56)
    print("watermark PSNR: mean=%.2f dB  min=%.2f dB" % (sum(psnrs) / len(psnrs), min(psnrs)))
    print("CSV written to %s" % csv_path)


def record(agg, attack, param, corr):
    agg.setdefault((attack, param), []).append(corr)


if __name__ == "__main__":
    main()
