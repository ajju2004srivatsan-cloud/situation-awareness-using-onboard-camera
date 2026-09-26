#!/usr/bin/env bash
# Deploy this repo to the Linux situational-awareness host and start it.
# Usage (from your Mac):
#   SERVER_PASS='…' ./scripts/deploy-to-server.sh
# Or interactive password prompt via ssh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST="${DEPLOY_HOST:-192.168.0.137}"
USER="${DEPLOY_USER:-ogden}"
REMOTE_DIR="${DEPLOY_DIR:-/home/${USER}/situational-awareness}"
PASS="${SERVER_PASS:-}"

info() { printf '\033[1;36m[deploy]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[deploy]\033[0m %s\n' "$*"; exit 1; }

SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o PreferredAuthentications=password -o PubkeyAuthentication=no)

run_ssh() {
  if [[ -n "$PASS" ]] && command -v sshpass >/dev/null 2>&1; then
    sshpass -p "$PASS" ssh "${SSH_OPTS[@]}" "${USER}@${HOST}" "$@"
  else
    ssh "${SSH_OPTS[@]}" "${USER}@${HOST}" "$@"
  fi
}

run_rsync() {
  if [[ -n "$PASS" ]] && command -v sshpass >/dev/null 2>&1; then
    sshpass -p "$PASS" rsync -az --delete \
      --exclude node_modules --exclude .git --exclude bin --exclude logs --exclude run \
      --exclude dashboard/dist --exclude .tunnel-url \
      -e "ssh ${SSH_OPTS[*]}" \
      "$ROOT/" "${USER}@${HOST}:${REMOTE_DIR}/"
  else
    rsync -az --delete \
      --exclude node_modules --exclude .git --exclude bin --exclude logs --exclude run \
      --exclude dashboard/dist --exclude .tunnel-url \
      -e "ssh ${SSH_OPTS[*]}" \
      "$ROOT/" "${USER}@${HOST}:${REMOTE_DIR}/"
  fi
}

info "Target ${USER}@${HOST}:${REMOTE_DIR}"
run_ssh "mkdir -p '$REMOTE_DIR'"
info "Syncing files…"
run_rsync
info "Ensuring Node.js is available…"
run_ssh 'bash -s' <<'REMOTE'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v node >/dev/null 2>&1; then
  NODE_VER=v20.18.1
  TMP=$(mktemp -d)
  cd "$TMP"
  if ! command -v curl >/dev/null 2>&1; then
    echo "Install curl first (sudo apt-get install -y curl)"; exit 1
  fi
  curl -fsSL "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-x64.tar.xz" -o node.tar.xz
  if command -v sudo >/dev/null 2>&1; then
    sudo tar -xJf node.tar.xz -C /usr/local --strip-components=1
  else
    tar -xJf node.tar.xz -C "$HOME/.local" --strip-components=1
    export PATH="$HOME/.local/bin:$PATH"
  fi
  cd /
  rm -rf "$TMP"
fi
cd /
node -v
npm -v
REMOTE

info "Starting stack on remote host…"
run_ssh "cd '$REMOTE_DIR' && chmod +x scripts/*.sh && ./scripts/start-all.sh"

info "Fetching status…"
run_ssh "curl -fsS http://127.0.0.1:8080/health; echo; test -f '$REMOTE_DIR/.tunnel-url' && echo WORLD_URL=\$(cat '$REMOTE_DIR/.tunnel-url') || true"
ok() { printf '\033[1;32m[deploy]\033[0m %s\n' "$*"; }
ok "Deploy complete. On the desktop, re-run anytime with: cd $REMOTE_DIR && ./scripts/start-all.sh"
