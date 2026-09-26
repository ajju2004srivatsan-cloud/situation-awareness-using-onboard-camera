# Worldwide access (any network)

Same Wi‑Fi is **not** required. Use world mode:

```bash
cd ~/situational-awareness
./scripts/start-world.sh
```

That starts:

| Tunnel | Carries | Used for |
|--------|---------|----------|
| **Cloudflare HTTPS** | Dashboard, WSS telemetry, **HLS video play** | Operators anywhere |
| **bore.pub TCP** | **RTMP ingest** | G20 / phone publishing from anywhere |

The script prints two URLs — paste both into the **SA G20** app Settings:

1. **World HTTPS URL** → `https://….trycloudflare.com`  
2. **World RTMP** → `rtmp://bore.pub:PORT`

Then on any phone/laptop open the **World HTTPS URL** to see the Cesium map + live video.

## G20 app settings (world)

- World HTTPS URL = Cloudflare link  
- World RTMP = `rtmp://bore.pub:….`  
- Device ID = `drone_g20_01`  
- Mode = Skydroid G20 UAV  
- GO LIVE  

Leave World fields empty only if you stay on LAN (`192.168.0.137`).

## Notes

- Cloudflare quick-tunnel URLs **change each restart** — re-copy into the app after `start-world.sh`.
- HLS over HTTPS works worldwide; LAN mode still uses lower-latency WebRTC when you open `http://192.168.0.137:8080/`.
- Stop: `./scripts/stop-all.sh`
