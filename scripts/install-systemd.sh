#!/usr/bin/env bash
# Install systemd units so SA starts on boot (Linux desktop).
# Usage: sudo ./scripts/install-systemd.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNIT_DIR=/etc/systemd/system

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo $0"
  exit 1
fi

install -m 644 "$ROOT/infra/systemd/sa-mediamtx.service" "$UNIT_DIR/"
install -m 644 "$ROOT/infra/systemd/sa-telemetry.service" "$UNIT_DIR/"
install -m 644 "$ROOT/infra/systemd/sa-simulator.service" "$UNIT_DIR/"

# Rewrite paths/user if repo is not under /home/ogden/situational-awareness
SA_USER="${SUDO_USER:-ogden}"
SA_ROOT="$ROOT"
for unit in sa-mediamtx.service sa-telemetry.service sa-simulator.service; do
  sed -i \
    -e "s|/home/ogden/situational-awareness|${SA_ROOT}|g" \
    -e "s|^User=ogden|User=${SA_USER}|g" \
    -e "s|^Group=ogden|Group=${SA_USER}|g" \
    "$UNIT_DIR/$unit"
done

# Ensure node path exists in units
NODE_BIN="$(command -v node || true)"
if [[ -n "$NODE_BIN" ]]; then
  sed -i "s|/usr/local/bin/node|${NODE_BIN}|g" \
    "$UNIT_DIR/sa-telemetry.service" \
    "$UNIT_DIR/sa-simulator.service"
fi

systemctl daemon-reload
systemctl enable sa-mediamtx.service sa-telemetry.service sa-simulator.service
systemctl restart sa-mediamtx.service sa-telemetry.service sa-simulator.service
systemctl --no-pager --full status sa-mediamtx.service sa-telemetry.service sa-simulator.service || true
echo "[sa] systemd units installed and started"
echo "    systemctl status sa-telemetry"
echo "    journalctl -u sa-telemetry -f"
