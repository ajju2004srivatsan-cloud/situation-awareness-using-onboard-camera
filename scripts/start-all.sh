#!/usr/bin/env bash
# =============================================================================
# Situational Awareness — one-shot activator
# Run on the Linux desktop (software-dev):
#   ./scripts/start-all.sh
#
# Starts MediaMTX (RTMP/WebRTC), telemetry server + dashboard, optional
# simulator, and a Cloudflare quick tunnel so the dashboard is reachable
# from anywhere on the internet.
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="$ROOT/.env"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE"; set +a
fi

SERVER_HOST="${SERVER_HOST:-192.168.0.137}"
TELEMETRY_PORT="${TELEMETRY_PORT:-8080}"
ENABLE_SIMULATOR="${ENABLE_SIMULATOR:-1}"
ENABLE_WORLD_TUNNEL="${ENABLE_WORLD_TUNNEL:-1}"
ENABLE_RTMP_TUNNEL="${ENABLE_RTMP_TUNNEL:-0}"
BIN_DIR="$ROOT/bin"
LOG_DIR="$ROOT/logs"
PID_DIR="$ROOT/run"
mkdir -p "$BIN_DIR" "$LOG_DIR" "$PID_DIR"

export SERVER_HOST TELEMETRY_PORT
export DASHBOARD_DIST="$ROOT/dashboard/dist"
export PATH="$BIN_DIR:$PATH"

info()  { printf '\033[1;36m[sa]\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m[sa]\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m[sa]\033[0m %s\n' "$*"; }
fail()  { printf '\033[1;31m[sa]\033[0m %s\n' "$*"; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing dependency: $1"
}

detect_arch() {
  local a
  a="$(uname -m)"
  case "$a" in
    x86_64|amd64) echo amd64 ;;
    aarch64|arm64) echo arm64 ;;
    *) fail "Unsupported arch: $a" ;;
  esac
}

install_node_deps() {
  need_cmd node
  need_cmd npm
  info "Installing / updating Node dependencies…"
  (cd "$ROOT/server" && npm install --omit=dev)
  (cd "$ROOT/dashboard" && npm install)
  (cd "$ROOT/simulator" && npm install --omit=dev)
  info "Building React dashboard…"
  (cd "$ROOT/dashboard" && npm run build)
}

ensure_mediamtx() {
  if [[ -x "$BIN_DIR/mediamtx" ]]; then
    return 0
  fi
  need_cmd curl
  need_cmd tar
  local arch ver url tmp
  arch="$(detect_arch)"
  ver="${MEDIAMTX_VERSION:-v1.11.3}"
  url="https://github.com/bluenviron/mediamtx/releases/download/${ver}/mediamtx_${ver}_linux_${arch}.tar.gz"
  tmp="$(mktemp -d)"
  info "Downloading MediaMTX ${ver} (${arch})…"
  curl -fsSL "$url" -o "$tmp/mediamtx.tgz"
  tar -xzf "$tmp/mediamtx.tgz" -C "$tmp"
  install -m 755 "$tmp/mediamtx" "$BIN_DIR/mediamtx"
  rm -rf "$tmp"
  ok "MediaMTX installed → $BIN_DIR/mediamtx"
}

ensure_cloudflared() {
  if [[ -x "$BIN_DIR/cloudflared" ]]; then
    return 0
  fi
  need_cmd curl
  local arch url tmp
  arch="$(detect_arch)"
  url="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${arch}"
  tmp="$(mktemp)"
  info "Downloading cloudflared…"
  curl -fsSL "$url" -o "$tmp"
  install -m 755 "$tmp" "$BIN_DIR/cloudflared"
  rm -f "$tmp"
  ok "cloudflared installed → $BIN_DIR/cloudflared"
}

ensure_bore() {
  if [[ -x "$BIN_DIR/bore" ]]; then
    return 0
  fi
  need_cmd curl
  need_cmd tar
  local arch ver url tmp
  arch="$(detect_arch)"
  # bore release arch names: x86_64 / aarch64
  local bore_arch="x86_64"
  [[ "$arch" == "arm64" ]] && bore_arch="aarch64"
  ver="${BORE_VERSION:-0.5.2}"
  url="https://github.com/ekzhang/bore/releases/download/v${ver}/bore-v${ver}-${bore_arch}-unknown-linux-musl.tar.gz"
  tmp="$(mktemp -d)"
  info "Downloading bore v${ver} (public TCP tunnel for RTMP)…"
  curl -fsSL "$url" -o "$tmp/bore.tgz"
  tar -xzf "$tmp/bore.tgz" -C "$tmp"
  install -m 755 "$tmp/bore" "$BIN_DIR/bore"
  rm -rf "$tmp"
  ok "bore installed → $BIN_DIR/bore"
}

write_mediamtx_runtime_config() {
  local cfg="$ROOT/infra/mediamtx.runtime.yml"
  cat > "$cfg" <<EOF
logLevel: info
logDestinations: [stdout]
rtmp: yes
rtmpAddress: :1935
webrtc: yes
webrtcAddress: :8889
webrtcLocalUDPAddress: :8189
webrtcLocalTCPAddress: :8189
webrtcIPsFromInterfaces: yes
webrtcAdditionalHosts: [${SERVER_HOST}]
hls: yes
hlsAddress: :8888
hlsAlwaysRemux: yes
hlsVariant: lowLatency
hlsSegmentCount: 7
hlsSegmentDuration: 1s
hlsPartDuration: 200ms
rtsp: no
srt: no
pathDefaults:
  source: publisher
paths:
  all_others:
EOF
  echo "$cfg"
}

stop_pidfile() {
  local name="$1"
  local pf="$PID_DIR/$name.pid"
  if [[ -f "$pf" ]]; then
    local pid
    pid="$(cat "$pf" || true)"
    if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
      info "Stopping $name (pid $pid)…"
      kill "$pid" 2>/dev/null || true
      sleep 0.5
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pf"
  fi
}

start_bg() {
  local name="$1"; shift
  stop_pidfile "$name"
  info "Starting $name…"
  nohup "$@" >"$LOG_DIR/$name.log" 2>&1 &
  echo $! >"$PID_DIR/$name.pid"
  sleep 0.4
  if ! kill -0 "$(cat "$PID_DIR/$name.pid")" 2>/dev/null; then
    fail "$name failed to start — see $LOG_DIR/$name.log"
  fi
  ok "$name running (pid $(cat "$PID_DIR/$name.pid"))"
}

open_firewall() {
  if command -v ufw >/dev/null 2>&1; then
    if ufw status 2>/dev/null | grep -qi 'Status: active'; then
      info "Opening UFW ports 8080/1935/8889/tcp and 8189/udp…"
      sudo ufw allow 8080/tcp || true
      sudo ufw allow 1935/tcp || true
      sudo ufw allow 8889/tcp || true
      sudo ufw allow 8189/udp || true
      sudo ufw allow 8189/tcp || true
    fi
  fi
}

wait_http() {
  local url="$1" tries="${2:-30}"
  for ((i=1; i<=tries; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

start_tunnel() {
  ensure_cloudflared
  stop_pidfile tunnel
  info "Starting Cloudflare HTTPS tunnel (dashboard + WSS + HLS)…"
  : >"$LOG_DIR/tunnel.log"
  nohup "$BIN_DIR/cloudflared" tunnel --url "http://127.0.0.1:${TELEMETRY_PORT}" \
    --protocol http2 \
    --no-autoupdate >"$LOG_DIR/tunnel.log" 2>&1 &
  echo $! >"$PID_DIR/tunnel.pid"

  local url=""
  for ((i=1; i<=40; i++)); do
    url="$(grep -oE 'https://[a-zA-Z0-9.-]+\.trycloudflare\.com' "$LOG_DIR/tunnel.log" | head -n1 || true)"
    if [[ -n "$url" ]]; then
      break
    fi
    sleep 0.5
  done

  if [[ -z "$url" ]]; then
    warn "Tunnel URL not detected yet — check $LOG_DIR/tunnel.log"
    return 0
  fi

  echo "$url" >"$ROOT/.tunnel-url"
  export PUBLIC_BASE_URL="$url"
  ok "World dashboard URL: $url"
  write_public_urls
}

start_rtmp_tunnel() {
  ensure_bore
  stop_pidfile rtmp-tunnel
  info "Starting bore TCP tunnel for RTMP :1935 (G20 can publish from anywhere)…"
  : >"$LOG_DIR/rtmp-tunnel.log"
  nohup "$BIN_DIR/bore" local 1935 --to bore.pub >"$LOG_DIR/rtmp-tunnel.log" 2>&1 &
  echo $! >"$PID_DIR/rtmp-tunnel.pid"

  local endpoint=""
  for ((i=1; i<=40; i++)); do
    # listening at bore.pub:PORT
    endpoint="$(grep -oE 'bore\.pub:[0-9]+' "$LOG_DIR/rtmp-tunnel.log" | head -n1 || true)"
    if [[ -n "$endpoint" ]]; then
      break
    fi
    sleep 0.4
  done

  if [[ -z "$endpoint" ]]; then
    warn "RTMP tunnel endpoint not detected — see $LOG_DIR/rtmp-tunnel.log"
    return 0
  fi

  export RTMP_PUBLIC="rtmp://${endpoint}"
  ok "World RTMP ingest: rtmp://${endpoint}/live/<deviceId>"
  write_public_urls
}

write_public_urls() {
  local pub="${PUBLIC_BASE_URL:-}"
  local rtmp="${RTMP_PUBLIC:-}"
  [[ -f "$ROOT/.tunnel-url" && -z "$pub" ]] && pub="$(cat "$ROOT/.tunnel-url")"
  cat >"$ROOT/.public-urls.json" <<EOF
{
  "publicBaseUrl": "${pub}",
  "rtmpPublic": "${rtmp}",
  "lanHost": "${SERVER_HOST}",
  "updatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
  # Also export for currently running node if we restart telemetry after tunnels
  export PUBLIC_BASE_URL="$pub"
  export RTMP_PUBLIC="$rtmp"
}

print_banner() {
  local tunnel="" rtmp=""
  [[ -f "$ROOT/.tunnel-url" ]] && tunnel="$(cat "$ROOT/.tunnel-url")"
  if [[ -f "$ROOT/.public-urls.json" ]]; then
    rtmp="$(grep -oE 'rtmp://[^"]+' "$ROOT/.public-urls.json" | head -n1 || true)"
  fi
  cat <<EOF

╔══════════════════════════════════════════════════════════════╗
║           Situational Awareness — LIVE                       ║
╠══════════════════════════════════════════════════════════════╣
║  LAN dashboard : http://${SERVER_HOST}:${TELEMETRY_PORT}/
║  World URL     : ${tunnel:-'(HTTPS tunnel off)'}
║  World RTMP    : ${rtmp:-'(TCP tunnel off)'}/live/<deviceId>
║  LAN RTMP      : rtmp://${SERVER_HOST}:1935/live/<deviceId>
║  World HLS     : ${tunnel:-http://${SERVER_HOST}:${TELEMETRY_PORT}}/hls/live/<id>/index.m3u8
║  G20 settings  : paste World URL + World RTMP into the app
║  Logs          : $LOG_DIR
║  Stop          : $ROOT/scripts/stop-all.sh
╚══════════════════════════════════════════════════════════════╝

EOF
}

main() {
  info "Root: $ROOT"
  open_firewall
  ensure_mediamtx
  install_node_deps

  local mtx_cfg
  mtx_cfg="$(write_mediamtx_runtime_config)"

  # Clear stale public urls
  rm -f "$ROOT/.tunnel-url" "$ROOT/.public-urls.json"

  start_bg mediamtx "$BIN_DIR/mediamtx" "$mtx_cfg"

  if [[ "$ENABLE_WORLD_TUNNEL" == "1" ]]; then
    # Start tunnels first so PUBLIC_* env is ready for telemetry process
    :
  fi

  start_bg telemetry env SERVER_HOST="$SERVER_HOST" TELEMETRY_PORT="$TELEMETRY_PORT" \
    DASHBOARD_DIST="$DASHBOARD_DIST" \
    PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-}" \
    RTMP_PUBLIC="${RTMP_PUBLIC:-}" \
    node "$ROOT/server/src/index.js"

  if ! wait_http "http://127.0.0.1:${TELEMETRY_PORT}/health" 40; then
    fail "Telemetry server did not become healthy — see $LOG_DIR/telemetry.log"
  fi
  ok "Telemetry healthy"

  if [[ "$ENABLE_SIMULATOR" == "1" ]]; then
    start_bg simulator env WS_URL="ws://127.0.0.1:${TELEMETRY_PORT}/ws" \
      SERVER_HOST="$SERVER_HOST" node "$ROOT/simulator/index.js"
  fi

  if [[ "$ENABLE_WORLD_TUNNEL" == "1" ]]; then
    start_tunnel
  fi
  if [[ "$ENABLE_RTMP_TUNNEL" == "1" ]]; then
    start_rtmp_tunnel
  fi

  # Restart telemetry with public URLs baked into env (reads .public-urls.json anyway)
  if [[ -f "$ROOT/.public-urls.json" ]]; then
    write_public_urls
    stop_pidfile telemetry
    start_bg telemetry env SERVER_HOST="$SERVER_HOST" TELEMETRY_PORT="$TELEMETRY_PORT" \
      DASHBOARD_DIST="$DASHBOARD_DIST" \
      PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-}" \
      RTMP_PUBLIC="${RTMP_PUBLIC:-}" \
      node "$ROOT/server/src/index.js"
    wait_http "http://127.0.0.1:${TELEMETRY_PORT}/health" 40 || true
  fi

  print_banner
}

main "$@"
