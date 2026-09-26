# Situation Awareness Using Onboard Camera — Android publisher

## Open & run

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** → select this `android/` folder.
3. Wait for Gradle sync (Android Studio will offer to create the Gradle Wrapper if needed).
4. Pick an emulator (Pixel recommended) or a USB phone with USB debugging.
5. Click **Run**.

## Server host tips

| Where the stack runs | What to type in “Server host” |
|----------------------|-------------------------------|
| Linux desktop `192.168.0.137` (same LAN) | `192.168.0.137` |
| Emulator talking to services on the **Mac** | `10.0.2.2` |
| Emulator talking to the Linux desktop | `192.168.0.137` (usually works) |

## Modes

- **Auto** — detects Skydroid G20 via `Build.MODEL`
- **Phone camera** — CameraX / RootEncoder RTMP + GPS/compass telemetry
- **Skydroid G20** — RTSP preview + screen capture RTMP + MAVLink UDP (`14550`)

## Preview without a phone

Use the **simulator** already running on the server (`ENABLE_SIMULATOR=1`). Markers appear on the dashboard even without the APK.

See [G20_SETUP.md](G20_SETUP.md) for live UAV connection.
