#!/usr/bin/env bash
# =============================================================================
# Worldwide mode — dashboard + telemetry + video reachable from any network.
#
#   ./scripts/start-world.sh
#
# - Cloudflare HTTPS tunnel → dashboard, WebSocket (WSS), HLS video play
# - bore.pub TCP tunnel → RTMP ingest from G20/phone anywhere
# - No simulator (real devices only)
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export ENABLE_SIMULATOR=0
export ENABLE_WORLD_TUNNEL=1
export ENABLE_RTMP_TUNNEL=1
export SERVER_HOST="${SERVER_HOST:-192.168.0.137}"

if [[ -f .env ]]; then
  grep -q '^ENABLE_SIMULATOR=' .env && sed -i 's/^ENABLE_SIMULATOR=.*/ENABLE_SIMULATOR=0/' .env || echo 'ENABLE_SIMULATOR=0' >> .env
  grep -q '^ENABLE_WORLD_TUNNEL=' .env && sed -i 's/^ENABLE_WORLD_TUNNEL=.*/ENABLE_WORLD_TUNNEL=1/' .env || echo 'ENABLE_WORLD_TUNNEL=1' >> .env
  grep -q '^ENABLE_RTMP_TUNNEL=' .env && sed -i 's/^ENABLE_RTMP_TUNNEL=.*/ENABLE_RTMP_TUNNEL=1/' .env || echo 'ENABLE_RTMP_TUNNEL=1' >> .env
fi

exec "$ROOT/scripts/start-all.sh"
