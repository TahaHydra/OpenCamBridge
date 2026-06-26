# OpenCamBridge Protocol V1

## Ports

- HTTP control/API: 8080
- Stream: same HTTP server for V1

## Endpoints

GET /health  
Returns plain text `OK`.

GET /api/device/info  
Returns app/device/server info.

GET /api/camera/list  
Returns available Android cameras.

GET /api/camera/status  
Returns current camera/stream state.

POST /api/stream/start  
Starts camera capture.

POST /api/stream/stop  
Stops camera capture.

POST /api/camera/switch  
Switches camera by camera id.

POST /api/settings/resolution  
Changes requested resolution.

POST /api/settings/fps  
Changes requested FPS.

POST /api/settings/jpeg-quality  
Changes MJPEG JPEG quality.

GET /stream.mjpeg  
Returns multipart/x-mixed-replace MJPEG stream.

## V1 scope

MJPEG first.  
No H.264.  
No audio.  
No iOS.  
No Bluetooth video.  
No cloud.