# MVP 1

The first working version must only do this:

1. Android app builds.
2. User grants camera permission.
3. App starts local HTTP server on port 8080.
4. Browser opens http://PHONE_IP:8080.
5. GET /health returns OK.
6. GET /api/camera/list returns available cameras.
7. GET /stream.mjpeg displays live camera video.
8. OBS Browser Source can load /stream.mjpeg.
9. USB works with:

```powershell
adb reverse tcp:8080 tcp:8080