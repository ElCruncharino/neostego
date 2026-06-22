#!/bin/bash
# Reproducible NeoStego steganalysis benchmark over a BOSSbase subset using StegExpose.
# Embeds a fixed payload into each cover with each algorithm, runs StegExpose, and reports the
# fraction of stego images flagged ("Above stego threshold?") per algorithm vs the clean control.
#
# Usage: ./bench_boss.sh [N] [payloadBytes]
#
# Expects a working area at $STEGO_BENCH (default ~/stego-bench) containing:
#   - BOSSbase_1.01/   the BOSSbase 1.01 corpus (10,000 grayscale 512x512 PGMs)
#   - StegExpose.jar    https://github.com/b3dk7/StegExpose
# The repo is auto-located relative to this script, so it can run from a checkout.
set -euo pipefail

N="${1:-1000}"
PAYLOAD="${2:-9800}"
ROOT="${STEGO_BENCH:-$HOME/stego-bench}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/BOSSbase_1.01"
COVERS="$ROOT/covers_boss"
RESULTS="$ROOT/results"
ALGOS=(RandomLSB RandomLSBMatch Adaptive)

cd "$ROOT"
mkdir -p "$COVERS" "$RESULTS"

echo "== 1. Convert $N BOSSbase PGMs to RGB PNG covers =="
rm -f "$COVERS"/*.png 2>/dev/null || true
i=0
for f in $(ls "$SRC"/*.pgm | sort -V); do
  convert "$f" -type TrueColor "$COVERS/$(basename "${f%.pgm}").png"
  i=$((i+1))
  [ "$i" -ge "$N" ] && break
done
echo "   $i covers prepared"

echo "== 2. Embed payload ($PAYLOAD bytes) with each algorithm =="
for algo in "${ALGOS[@]}"; do
  echo "  -> $algo"
  ( cd "$REPO" && ./gradlew -q :desktop:benchEmbed \
      -PcoverDir="$COVERS" -PoutDir="$ROOT/stego_$algo" -Palgo="$algo" -Ppayload="$PAYLOAD" )
done

echo "== 3. Run StegExpose on clean covers and each stego set =="
run_stegexpose () { # dir csv
  java -jar "$ROOT/StegExpose.jar" "$1" default default "$2" >/dev/null 2>&1 || true
}
run_stegexpose "$COVERS" "$RESULTS/clean.csv"
for algo in "${ALGOS[@]}"; do
  run_stegexpose "$ROOT/stego_$algo" "$RESULTS/$algo.csv"
done

echo "== 4. Detection summary (fraction flagged as stego) =="
summarize () { # label csv
  if [ ! -s "$2" ]; then echo "$1: (no results)"; return; fi
  # Column 2 is "Above stego threshold?" (true/false)
  awk -F, 'NR>1 && $2!="" {tot++; if (tolower($2)=="true") det++} END {
    if (tot>0) printf "%-16s %5d/%-5d flagged = %5.1f%%\n", lab, det, tot, 100*det/tot;
  }' lab="$1" "$2"
}
echo "------------------------------------------------------------"
summarize "clean (control)" "$RESULTS/clean.csv"
summarize "RandomLSB" "$RESULTS/RandomLSB.csv"
summarize "RandomLSBMatch" "$RESULTS/RandomLSBMatch.csv"
summarize "Adaptive" "$RESULTS/Adaptive.csv"
echo "------------------------------------------------------------"
echo "N=$N payload=$PAYLOAD bytes (~$(awk "BEGIN{printf \"%.3f\", $PAYLOAD*8/(512*512*3)}") bpp)"
