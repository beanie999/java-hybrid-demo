#!/usr/bin/env bash
# Exercises every java-hybrid-demo endpoint in turn, sleeping between requests.
# Usage: ./load.sh [sleep_seconds=5] [executions=100]
#   One execution = one pass through all endpoints, so 100 executions = 100 x 6 requests.
# Override the target with BASE_URL (default http://localhost:8080).
set -euo pipefail

SLEEP="${1:-5}"
COUNT="${2:-100}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

ENDPOINTS=(
  "/health"
  "/quote"
  "/quote?qty=12&currency=EUR"
  "/quote?currency=GBP"
  "/quote?slow=true"
  "/quote?currency=XYZ"
)

echo "Target: $BASE_URL | executions: $COUNT | sleep: ${SLEEP}s between requests"

for ((i = 1; i <= COUNT; i++)); do
  for path in "${ENDPOINTS[@]}"; do
    status=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL$path" || echo "000")
    printf '[%d/%d] %s -> %s\n' "$i" "$COUNT" "$path" "$status"
    sleep "$SLEEP"
  done
done
