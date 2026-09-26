# Skydroid G20 — connect real UAV to Situational Awareness

The Linux stack is **stopped** until you start it for live devices.

## 1. Start the server for worldwide access

On `ogden@192.168.0.137`:

```bash
cd ~/situational-awareness
./scripts/start-world.sh
```

Copy the printed **World URL** and **World RTMP** into the G20 app.
Open the World URL from any network (or LAN: http://192.168.0.137:8080/).

See also [WORLD_ACCESS.md](../WORLD_ACCESS.md).

Stop later with: `./scripts/stop-all.sh`

## 2. Network picture (worldwide)

```
G20 (any network: LTE / other Wi‑Fi)
   │  RTMP ──► bore.pub TCP tunnel ──► Linux :1935 (MediaMTX)
   │  WSS  ──► Cloudflare HTTPS    ──► Linux :8080 (telemetry)
   │
Operators (any network)
   └── browser ──► Cloudflare HTTPS ──► Cesium + HLS video
```

LAN shortcut still works: `http://192.168.0.137:8080/` and `rtmp://192.168.0.137:1935/...`.

## 3. Forward MAVLink on the G20

ArduPilot / Pixhawk telemetry must be UDP to the app.

Example with MAVProxy on the G20:

```bash
mavproxy.py --master=/dev/ttyUSB0 --baudrate=57600 --out=udp:127.0.0.1:14550
```

If your FC is already bridged by Skydroid software, add an extra UDP output to `127.0.0.1:14550`.

In the app **Settings → MAVLink UDP port** leave `14550` unless you changed it.

## 4. RTSP video URL

Default Skydroid air-unit stream:

```
rtsp://192.168.144.108:554/stream=0
```

If preview fails, try `stream=1` or check the Skydroid Ground Station app for the working RTSP URL, then paste it into **Settings → Drone RTSP URL**.

## 5. Install / run the app

1. On your Mac, open `/Users/ajay_sr/situational-awareness/android` in **Android Studio**.
2. Connect the G20 over USB (enable USB debugging) **or** build an APK and sideload it.
3. Run the **SA G20** app.
4. Settings:
   - **World HTTPS URL:** paste from `start-world.sh` (`https://….trycloudflare.com`)
   - **World RTMP:** paste from `start-world.sh` (`rtmp://bore.pub:PORT`)
   - **Device ID:** `drone_g20_01`
   - **Mode:** Skydroid G20 UAV
5. Tap **Preview RTSP** — you should see the drone camera.
6. Tap **GO LIVE** → allow **screen capture**.
7. Watch the pills: RTSP · MAVLink · RTMP · Telemetry (green = good).
8. Open the **World HTTPS URL** in any browser → click the UAV cone → video.

## 6. Phone / ground unit (optional)

Same APK, mode **Phone / ground** → publishes as a **ground box** on the map (`type: ground`).

## 7. Checklist if something is red

| Pill | Failure | Fix |
|------|---------|-----|
| RTSP | No preview | Wrong RTSP URL / air unit not linked |
| MAVLink | pkts stay 0 | MAVProxy UDP output not running |
| RTMP | failed | Server not started, or G20 cannot reach `:1935` |
| Telemetry | reconnecting | Server not started, or cannot reach `:8080` |

Quick server test from G20 browser or `adb shell`:

```bash
ping 192.168.0.137
# from any LAN machine:
curl http://192.168.0.137:8080/health
```
