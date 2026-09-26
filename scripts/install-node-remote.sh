#!/usr/bin/env bash
# Install Node.js 20 from official binary tarball (no nodesource needed).
set -euo pipefail
PASS="${1:-}"
NODE_VER=v20.18.1
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) NARCH=x64 ;;
  aarch64) NARCH=arm64 ;;
  *) echo "bad arch $ARCH"; exit 1 ;;
esac
PREFIX="/usr/local"
TMP="$(mktemp -d)"
cd "$TMP"
# Prefer existing curl; else use wget after apt
if ! command -v curl >/dev/null 2>&1; then
  echo "$PASS" | sudo -S apt-get update -y
  echo "$PASS" | sudo -S apt-get install -y curl ca-certificates tar xz-utils
fi
curl -fsSL "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-${NARCH}.tar.xz" -o node.tar.xz
echo "$PASS" | sudo -S tar -xJf node.tar.xz -C "$PREFIX" --strip-components=1
node -v
npm -v
rm -rf "$TMP"
