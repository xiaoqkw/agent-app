# GUI Agent (Android 15, Accessibility + HTTP)

Lightweight agent app that labels visible UI elements with numeric IDs (overlay) and exposes HTTP APIs for refresh, listing labels, getting coordinates, and triggering clicks. Designed for use over ADB port-forward on an emulator (`sdk_gphone64_x86_64`, Android 15 / API 35).

## Modules
- AccessibilityService: builds stable labels from the view tree (`AgentAccessibilityService`).
- Overlay: draws boxes + numeric badges on top of any app (`LabelOverlayView`).
- HTTP server: NanoHTTPD bound to `127.0.0.1:PORT` with optional token (`AgentWebServer`).
- LabelManager: filters/normalizes nodes, keeps stable IDs between refreshes.

### Safety & robustness (recent fixes)
- All `AccessibilityNodeInfo` instances are recycled to avoid leaks (root + children).
- Label IDs are assigned under a single synchronized block to prevent collisions; full label list is swapped atomically for readers.
- Worker thread drains callbacks on destroy and waits briefly to avoid use-after-destroy of overlay/server.

## Build
1. JDK 17 required. 本仓库已在 `gradle.properties` 配置 `org.gradle.java.home=/home/qianyi/qiankunwei/jdk-17.0.17+10`，若路径变动请同步修改。
2. 使用自带 Gradle Wrapper（无需系统 gradle）：
   ```bash
   cd agent-app
   ./gradlew :app:assembleDebug
   ```
   首次构建会自动下载 Android Build-Tools 34.0.0 等依赖（需网络）。
   APK 输出：`app/build/outputs/apk/debug/app-debug.apk`.

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
- **Memory correctness**: every obtained `AccessibilityNodeInfo` is recycled; root is not retained beyond each refresh.
- **Thread safety**: label snapshots use atomic swap; ID allocator is synchronized; worker shutdown removes pending runnables.

## Quick smoke test
1. Enable service, ensure overlay badges appear on any app.
2. `curl http://localhost:9000/refresh`
3. `curl http://localhost:9000/list | jq .`
4. `curl 'http://localhost:9000/get_coords?id=1'`
5. `curl -X POST 'http://localhost:9000/action?type=click&id=1'`
