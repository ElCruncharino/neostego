#!/usr/bin/env bash
# Evaluate the SRM + FLD-ensemble steganalyzer against every NeoStego algorithm and
# across the Adaptive payload sweep, on the same pairs the CNN harness uses.
#
#   ./run_srm.sh
set -euo pipefail
ROOT="${STEGO_BENCH:-$HOME/stego-bench}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-$ROOT/venv/bin/python}"
mkdir -p "$ROOT/results"

run() { # <label> <args...>
  local label="$1"; shift
  local log="$ROOT/results/srm_${label}.log"
  echo ">>> SRM vs $label -> $log"
  PYTHONUNBUFFERED=1 "$PY" "$HERE/srm_eval.py" "$@" 2>&1 | tee "$log" | grep -E "ALGO|pairs:|features|ABORT" || true
}

# main comparison at ~0.4 bpp
for algo in RandomLSB RandomLSBMatch Adaptive; do
  run "$algo" --algo "$algo"
done

# Adaptive payload sweep (reuses the stego sets made by ../cnn/sweep_payload.sh)
for p in 20000 10000 5000 2000; do
  run "Adaptive_p${p}" --algo "Adaptive_p${p}" --stego-prefix stego_sweep_
done

echo
echo "==== SRM+ensemble summary ===="
for f in RandomLSB RandomLSBMatch Adaptive Adaptive_p20000 Adaptive_p10000 Adaptive_p5000 Adaptive_p2000; do
  grep -h "TEST" "$ROOT/results/srm_${f}.log" 2>/dev/null || true
done
