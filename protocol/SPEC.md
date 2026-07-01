# OpenCamBridge Protocol V1

## Security and Auth

- **usbOnly (Default)**: Server binds to `127.0.0.1`. No token required for local ADB forward connections.
- **lanToken**: Server binds to `0.0.0.0`. Requires token authentication via `?token=...` query parameter or `X-OpenCamBridge-Token` header.
- Unauthenticated LAN access is not supported.

## Ports

- HTTP control/API: 8080 (or dynamically assigned)
- Stream: same HTTP server for V1

## Endpoints

GET /health  
Returns plain text `OK`. (No token required)

GET /api/device/info  
Returns app/device/server info.

GET /api/camera/list  
Returns available Android cameras.

GET /api/camera/status  
Returns current camera/stream state.

GET /api/camera/controls
Returns available control options for the current camera.

GET /api/camera/capabilities
Returns camera capabilities (resolutions, FPS, etc.).

GET /api/settings
Returns current active settings.

POST /api/settings
Updates multiple settings at once.

POST /api/stream/start  
Starts camera capture.

POST /api/stream/stop  
Stops camera capture.

POST /api/stream/recover
Attempts to recover from camera errors.

GET /api/stream/metrics
Returns performance metrics.

POST /api/camera/switch  
Switches camera by camera id.

POST /api/camera/zoom
POST /api/camera/torch
POST /api/camera/autofocus
Various camera hardware controls.

POST /api/settings/resolution  
Changes requested resolution.

POST /api/settings/fps  
Changes requested FPS.

POST /api/settings/jpeg-quality  
Changes MJPEG JPEG quality.

POST /api/settings/preview-fit-mode
POST /api/settings/aspect-ratio
Additional settings endpoints.

GET /api/logs
POST /api/logs/clear
Log management endpoints.

GET /stream.mjpeg  
Returns multipart/x-mixed-replace MJPEG stream. **(Stable)**

GET /stream.h264
Returns raw H.264 bitstream. **(Experimental)**

GET /api/stream/info
Returns stream metadata.

GET /obs
Returns a clean HTML page displaying the MJPEG stream for browser-based OBS captures.

## V1 scope

- MJPEG is the stable V1 path.
- H.264 stream is experimental (endpoint exists but not yet consumed by the Windows virtual camera).
- No audio.  
- No iOS.  
- No macOS virtual camera driver yet.
- No Bluetooth video.  
- No cloud.