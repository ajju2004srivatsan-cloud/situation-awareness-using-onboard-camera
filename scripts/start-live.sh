#!/usr/bin/env bash
# Start the Situational Awareness stack for REAL devices (no simulator).
# On the Linux desktop:
#   ./scripts/start-live.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Force real-device mode
export ENABLE_SIMULATOR=0
export ENABLE_WORLD_TUNNEL="${ENABLE_WORLD_TUNNEL:-0}"
export SERVER_HOST="${SERVER_HOST:-192.168.0.137}"

# Persist into .env for child processes
if [[ -f .env ]]; then
  grep -q '^ENABLE_SIMULATOR=' .env && sed -i 's/^ENABLE_SIMULATOR=.*/ENABLE_SIMULATOR=0/' .env || echo 'ENABLE_SIMULATOR=0' >> .env
  grep -q '^ENABLE_WORLD_TUNNEL=' .env || echo "ENABLE_WORLD_TUNNEL=${ENABLE_WORLD_TUNNEL}" >> .env
fi

exec "$ROOT/scripts/start-all.sh"
