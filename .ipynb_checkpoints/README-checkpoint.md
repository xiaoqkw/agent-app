# GUI Agent (Android 15, Accessibility + HTTP)

Lightweight agent app that labels visible UI elements with numeric IDs (overlay) and exposes HTTP APIs for refresh, listing labels, getting coordinates, and triggering clicks. Designed for use over ADB port-forward on an emulator (`sdk_gphone64_x86_64`, Android 15 / API 35).

## Modules
- AccessibilityService: builds stable labels from the view tree (`AgentAccessibilityService`).
- Overlay: draws boxes + numeric badges on top of any app (`LabelOverlayView`).
- HTTP server: NanoHTTPD bound to `127.0.0.1:PORT` with optional token (`AgentWebServer`).
- LabelManager: filters/normalizes nodes, keeps stable IDs between refreshes.

## Build
1. Ensure Android SDK 35 and JDK 17 are configured.
2. From `agent-app/`:
   ```bash
   ./gradlew :app:assembleDebug
   ```
   APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install & start (emulator already connected)
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# open Accessibility settings to enable the service manually (Android 15 blocks silent enable without device owner/root)
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS

# optional: start service with custom port/token
adb shell am startservice -n com.example.agent/.AgentAccessibilityService --ei PORT 9000 --es TOKEN secret123
adb forward tcp:9000 tcp:9000
```

## HTTP endpoints (all on forwarded port)
- `GET /ping` → `pong`
- `POST/GET /refresh` → rebuild labels and redraw overlay.
- `GET /list` → `{count, items:[{id,x,y,w,h,text,className}]}` (center coordinates).
- `GET /get_coords?id=5` → `{id,x,y,w,h,text,className}`
- `POST /action?type=click&id=5` or `...&x=100&y=200` → performs gesture click via `dispatchGesture`.

If `TOKEN` is set, include `?token=...` or header `X-Auth-Token: ...`.

## Design notes (mapped to requirements)
- **Single Responsibility & Cohesion**: `LabelManager` (build labels), `AgentWebServer` (API), `AgentAccessibilityService` (orchestrate), `LabelOverlayView` (render).
- **Explicitness**: defaults are local-only HTTP, cleartext limited to localhost via `network_security_config.xml`, explicit port & token via `Intent` extras.
- **Error visibility**: server start failures are logged and thrown; overlay/server stop guarded; gesture result returned to caller.
- **Performance**: refresh work on background handler; debounce on events; cap nodes (`maxNodes=400`) and min area filter.

## Quick smoke test
1. Enable service, ensure overlay badges appear on any app.
2. `curl http://localhost:9000/refresh`
3. `curl http://localhost:9000/list | jq .`
4. `curl 'http://localhost:9000/get_coords?id=1'`
5. `curl -X POST 'http://localhost:9000/action?type=click&id=1'`
