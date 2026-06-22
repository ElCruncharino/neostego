#!/usr/bin/env bash
# Train + evaluate SRNet CNN steganalysis against each NeoStego algorithm.
#
# Expects the BOSSbase cover/stego sets produced by ../bench_boss.sh under
# $STEGO_BENCH (default ~/stego-bench) and a Python venv with torch + pillow at
# $STEGO_BENCH/venv (see install_torch.log). Results are written to results/.
#
#   ./run_srnet.sh                 # full run, all three algorithms
#   ./run_srnet.sh --smoke         # quick sanity run
#   ALGOS="Adaptive" ./run_srnet.sh
set -euo pipefail

ROOT="${STEGO_BENCH:-$HOME/stego-bench}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-$ROOT/venv/bin/python}"
ALGOS="${ALGOS:-RandomLSB RandomLSBMatch Adaptive}"
EPOCHS="${EPOCHS:-30}"
MODEL="${MODEL:-srmcnn}"
mkdir -p "$ROOT/results"

EXTRA=""
TAG="${MODEL}_full"
if [ "${1:-}" = "--smoke" ]; then EXTRA="--smoke"; TAG="${MODEL}_smoke"; fi

for algo in $ALGOS; do
  log="$ROOT/results/srnet_${algo}_${TAG}.log"
  echo ">>> $MODEL vs $algo  (epochs=$EPOCHS, tag=$TAG) -> $log"
  PYTHONUNBUFFERED=1 "$PY" "$HERE/srnet_eval.py" --root "$ROOT" --algo "$algo" \
        --model "$MODEL" --epochs "$EPOCHS" $EXTRA 2>&1 | tee "$log"
done

echo
echo "==== $MODEL summary ===="
for algo in $ALGOS; do
  log="$ROOT/results/srnet_${algo}_${TAG}.log"
  grep -h "TEST" "$log" 2>/dev/null || true
done
