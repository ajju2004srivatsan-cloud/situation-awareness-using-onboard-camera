#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT/run"

stop_one() {
  local name="$1"
  local pf="$PID_DIR/$name.pid"
  if [[ -f "$pf" ]]; then
    local pid
    pid="$(cat "$pf" || true)"
    if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
      echo "[sa] stopping $name ($pid)"
      kill "$pid" 2>/dev/null || true
      sleep 0.3
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pf"
  fi
}

stop_one tunnel
stop_one rtmp-tunnel
stop_one simulator
stop_one telemetry
stop_one mediamtx
rm -f "$ROOT/.tunnel-url" "$ROOT/.public-urls.json" 2>/dev/null || true
echo "[sa] all stopped"
