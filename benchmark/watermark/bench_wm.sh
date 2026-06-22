#!/bin/bash
# Reproducible robustness benchmark for the NeoStego DWTSVD watermark.
# Embeds a fixed watermark into each cover, applies JPEG / noise / blur / scaling / brightness /
# contrast / crop attacks, and reports the detection correlation, bit-error rate and detection rate
# per attack, plus the watermarked-image PSNR. See attacks.py for the metric definitions.
#
# Usage: ./bench_wm.sh [N]
#
# Provide a directory of PNG covers via WM_COVERS (defaults to $WM_BENCH/covers). The covers from
# the steganalysis benchmark work well, e.g.:
#   WM_COVERS=~/stego-bench/covers_boss ./bench_wm.sh 20
set -euo pipefail

N="${1:-12}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT="${WM_BENCH:-$HOME/wm-bench}"
COVERS="${WM_COVERS:-$ROOT/covers}"
JAR="$REPO/desktop/build/distributions/package/lib/neostego.jar"

echo "== 1. Build the NeoStego CLI bundle =="
( cd "$REPO" && ./gradlew -q :desktop:distBase )
[ -f "$JAR" ] || { echo "jar not found at $JAR"; exit 1; }

if [ ! -d "$COVERS" ] || [ -z "$(ls -A "$COVERS"/*.png 2>/dev/null || true)" ]; then
  echo "No PNG covers found in $COVERS."
  echo "Set WM_COVERS to a directory of PNG images (>= ~180x180), e.g. the covers_boss set"
  echo "produced by benchmark/bench_boss.sh."
  exit 1
fi

mkdir -p "$ROOT"
echo "== 2. Embed, attack and verify (N=$N) =="
python3 "$REPO/benchmark/watermark/attacks.py" \
  --jar "$JAR" --covers "$COVERS" --out "$ROOT/work" --limit "$N" --password "benchmark-key"
