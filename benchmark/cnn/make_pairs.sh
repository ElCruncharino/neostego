#!/usr/bin/env bash
# Regenerate clean, guaranteed-paired cover/stego sets for CNN steganalysis.
#
# Unlike the StegExpose benchmark (which scores each image independently), CNN
# steganalysis needs each stego matched to its exact cover. This converts N
# BOSSbase PGMs to RGB PNG covers and embeds a fixed payload with each algorithm,
# preserving filenames so covers_cnn/<name>.png pairs with stego_cnn_<algo>/<name>.png.
#
#   ./make_pairs.sh [N] [payloadBytes]
set -euo pipefail
N="${1:-1000}"
PAYLOAD="${2:-40000}"
ROOT="${STEGO_BENCH:-$HOME/stego-bench}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/BOSSbase_1.01"
COVERS="$ROOT/covers_cnn"
ALGOS=(RandomLSB RandomLSBMatch Adaptive)

mkdir -p "$COVERS"
echo "== Convert $N BOSSbase PGMs -> RGB PNG covers =="
rm -f "$COVERS"/*.png 2>/dev/null || true
# Force genuine 24-bit RGB (PNG24): a grayscale PNG would make OpenStego apply a
# GRAY->sRGB gamma conversion on read, shifting midtones by tens of levels and
# destroying the cover<->stego pixel correspondence the CNN needs.
i=0
for f in $(ls "$SRC"/*.pgm | sort -V); do
  convert "$f" -strip PNG24:"$COVERS/$(basename "${f%.pgm}").png"
  i=$((i+1)); [ "$i" -ge "$N" ] && break
done
echo "   $i covers prepared in $COVERS"

for algo in "${ALGOS[@]}"; do
  echo "== Embed $algo ($PAYLOAD bytes) =="
  ( cd "$REPO" && ./gradlew -q :desktop:benchEmbed \
      -PcoverDir="$COVERS" -PoutDir="$ROOT/stego_cnn_$algo" -Palgo="$algo" -Ppayload="$PAYLOAD" )
done
echo "DONE: covers_cnn + stego_cnn_<algo> ready (N=$N payload=$PAYLOAD)"