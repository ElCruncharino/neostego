#!/usr/bin/env bash
# Evaluate the DCTR + FLD-ensemble JPEG steganalyzer against the JpegUniward plugin across a
# payload sweep at a fixed quality, plus the cover-vs-cover null control. Generates any missing
# cover/stego sets first.
#
#   ./run_jpeg.sh [N] [quality]
set -euo pipefail
N="${1:-200}"
Q="${2:-90}"
ALGO="${3:-JpegUniward}"
ROOT="${STEGO_BENCH:-$HOME/stego-bench}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-$ROOT/venv/bin/python}"
mkdir -p "$ROOT/results"

COVERS="$ROOT/covers_jpeg_q${Q}"

run() { # <label> <args...>
  local label="$1"; shift
  local log="$ROOT/results/dctr_${label}.log"
  echo ">>> DCTR vs $label -> $log"
  PYTHONUNBUFFERED=1 "$PY" "$HERE/jpeg_eval.py" "$@" 2>&1 | tee "$log" | grep -E "ALGO|pairs:|features|ABORT|pairing" || true
}

# Payload sweep (bytes). Adjust to taste; capacity for 512x512 @ 4:2:0 is ~22 KB.
for P in 12000 8000 4000 2000 1000; do
  STEGO="$ROOT/stego_jpeg_${ALGO}_q${Q}_p${P}"
  if [ ! -d "$STEGO" ]; then
    "$HERE/make_jpeg_pairs.sh" "$N" "$Q" "$P" "$ALGO"
  fi
  run "${ALGO}_q${Q}_p${P}" --covers "$COVERS" --stego "$STEGO" --label "${ALGO}_q${Q}_p${P}"
done

# Null control: cover vs cover must score at chance.
run "null_q${Q}" --covers "$COVERS" --null --label "null_q${Q}"

echo
echo "==== DCTR+ensemble summary (q$Q, N=$N) ===="
for P in 12000 8000 4000 2000 1000; do
  grep -h "TEST" "$ROOT/results/dctr_${ALGO}_q${Q}_p${P}.log" 2>/dev/null || true
done
grep -h "TEST" "$ROOT/results/dctr_null_q${Q}.log" 2>/dev/null || true
