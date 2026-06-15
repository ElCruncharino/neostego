#!/usr/bin/env bash
# Build matched cover/stego JPEG sets for DCTR steganalysis of NeoStego's JPEG plugins.
#
# Both cover and stego are compressed by NeoStego's own codec at the same quality, so the
# detector sees only the embedding -- not a libjpeg-vs-ours compressor difference. Precovers
# are reused from covers_cnn (PNG24) if present, else converted from BOSSbase.
#
#   ./make_jpeg_pairs.sh [N] [quality] [payloadBytes] [algo]
set -euo pipefail
N="${1:-200}"
Q="${2:-90}"
PAYLOAD="${3:-8000}"
ALGO="${4:-JpegUniward}"
ROOT="${STEGO_BENCH:-$HOME/stego-bench}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/BOSSbase_1.01"
PRE="$ROOT/covers_cnn"
COVERS="$ROOT/covers_jpeg_q${Q}"
STEGO="$ROOT/stego_jpeg_${ALGO}_q${Q}_p${PAYLOAD}"

# Precovers: reuse covers_cnn if it has enough, otherwise (re)build from BOSSbase.
have=$(ls "$PRE"/*.png 2>/dev/null | wc -l || echo 0)
if [ "$have" -lt "$N" ]; then
  echo "== Convert $N BOSSbase PGMs -> RGB PNG precovers =="
  mkdir -p "$PRE"
  i=0
  for f in $(ls "$SRC"/*.pgm | sort -V); do
    convert "$f" -strip PNG24:"$PRE/$(basename "${f%.pgm}").png"
    i=$((i+1)); [ "$i" -ge "$N" ] && break
  done
fi

# Use only the first N precovers (stage them in a temp dir so both passes see the same set).
STAGE="$ROOT/.jpeg_pre_stage_${N}"
rm -rf "$STAGE"; mkdir -p "$STAGE"
mapfile -t _pre < <(ls "$PRE"/*.png | sort -V | head -n "$N")
for p in "${_pre[@]}"; do ln -sf "$p" "$STAGE/$(basename "$p")"; done

echo "== Clean JPEG covers (q$Q) -> $COVERS =="
( cd "$REPO" && ./gradlew -q :desktop:benchJpeg \
    -Pmode=cover -PcoverDir="$STAGE" -PoutDir="$COVERS" -Pquality="$Q" )

echo "== Stego JPEGs ($ALGO, q$Q, $PAYLOAD bytes) -> $STEGO =="
( cd "$REPO" && ./gradlew -q :desktop:benchJpeg \
    -Pmode=stego -PcoverDir="$STAGE" -PoutDir="$STEGO" -Pquality="$Q" -Ppayload="$PAYLOAD" -Palgo="$ALGO" )

rm -rf "$STAGE"
echo "DONE: $COVERS  +  $STEGO"
