#!/usr/bin/env bash
# Payload sweep for the Adaptive (HILL+STC) algorithm vs the SRM-CNN steganalyzer.
#
# Adaptive steganography trades capacity for security: the lower the payload, the
# fewer cost-weighted +/-1 changes, and the harder a CNN can separate stego from
# cover. This embeds the SAME covers at a descending series of payloads into
# per-payload stego dirs, then trains the SRM-CNN against each and tabulates
# P_E / AUC so the security-vs-payload curve is reproducible.
#
#   ./sweep_payload.sh [N] "payload1 payload2 ..."
set -euo pipefail
N="${1:-1000}"
PAYLOADS="${2:-20000 10000 5000 2000}"
ROOT="${STEGO_BENCH:-$HOME/stego-bench}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-$ROOT/venv/bin/python}"
COVERS="$ROOT/covers_cnn"
EPOCHS="${EPOCHS:-30}"
mkdir -p "$ROOT/results"

for p in $PAYLOADS; do
  out="$ROOT/stego_sweep_Adaptive_p${p}"
  if [ ! -d "$out" ] || [ "$(ls "$out" 2>/dev/null | wc -l)" -lt "$N" ]; then
    echo "== Embed Adaptive payload=$p -> $out =="
    ( cd "$REPO" && ./gradlew -q :desktop:benchEmbed \
        -PcoverDir="$COVERS" -PoutDir="$out" -Palgo=Adaptive -Ppayload="$p" )
  else
    echo "== Reuse existing $out =="
  fi
done

echo
for p in $PAYLOADS; do
  log="$ROOT/results/sweep_Adaptive_p${p}.log"
  echo ">>> SRM-CNN vs Adaptive payload=$p (epochs=$EPOCHS) -> $log"
  PYTHONUNBUFFERED=1 "$PY" "$HERE/srnet_eval.py" --root "$ROOT" \
        --algo "Adaptive_p${p}" --stego-prefix "stego_sweep_" \
        --model srmcnn --epochs "$EPOCHS" 2>&1 | tee "$log"
done

echo
echo "==== Adaptive payload sweep summary (SRM-CNN) ===="
printf "%-10s %-8s %-8s %-8s\n" payload acc P_E AUC
for p in $PAYLOADS; do
  log="$ROOT/results/sweep_Adaptive_p${p}.log"
  line=$(grep -h "TEST" "$log" 2>/dev/null | tail -1)
  acc=$(echo "$line" | sed -n 's/.*acc \([0-9.]*\).*/\1/p')
  pe=$(echo "$line" | sed -n 's/.*P_E \([0-9.]*\).*/\1/p')
  auc=$(echo "$line" | sed -n 's/.*AUC \([0-9.]*\).*/\1/p')
  printf "%-10s %-8s %-8s %-8s\n" "$p" "$acc" "$pe" "$auc"
done
