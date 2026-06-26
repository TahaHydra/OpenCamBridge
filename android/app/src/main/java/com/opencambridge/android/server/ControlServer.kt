package com.opencambridge.android.server

import android.content.Context
import com.opencambridge.android.camera.CameraRepository
import com.opencambridge.android.state.SettingsManager
import com.opencambridge.android.state.StreamState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PORT = 8080
private const val MJPEG_BOUNDARY = "FRAME"

/**
 * Embedded Ktor (3.x) HTTP server on port 8080.
 * Route handlers are plain suspend functions that receive the RoutingCall.
 */
class ControlServer(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val onStreamStart: () -> Unit,
    private val onStreamStop: () -> Unit,
    private val onSettingsChanged: () -> Unit,
    private val onSetZoomRatio: (Float) -> Unit,
    private val onSetLinearZoom: (Float) -> Unit,
    private val onSetTorch: (Boolean) -> Unit
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val cameraRepo = CameraRepository(context)

    fun start() {
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                get("/")                           { serveIndex(call) }
                get("/health")                     { call.respondText("OK") }
                get("/api/device/info")            { serveDeviceInfo(call) }
                get("/api/camera/list")            { serveCameraList(call) }
                get("/api/camera/status")          { serveCameraStatus(call) }
                get("/api/camera/controls")        { serveCameraControls(call) }
                get("/api/settings")               { serveGetSettings(call) }
                
                post("/api/stream/start")          { serveStreamStart(call) }
                post("/api/stream/stop")           { serveStreamStop(call) }
                post("/api/camera/switch")         { serveCameraSwitch(call) }
                
                // Settings
                post("/api/settings/resolution")   { serveSetResolution(call) }
                post("/api/settings/fps")          { serveSetFps(call) }
                post("/api/settings/jpeg-quality") { serveSetJpegQuality(call) }
                post("/api/settings/preview-fit-mode") { serveSetPreviewFitMode(call) }
                post("/api/settings")              { serveUpdateSettings(call) }
                
                // Controls
                post("/api/camera/zoom")           { serveSetZoom(call) }
                post("/api/camera/torch")          { serveSetTorch(call) }
                post("/api/camera/autofocus")      { serveSetAutofocus(call) }
                
                get("/stream.mjpeg")               { serveMjpeg(call) }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        engine = null
    }

    private suspend fun serveIndex(call: RoutingCall) {
        call.respondText(ContentType.Text.Html) {
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>OpenCamBridge</title>
              <style>
                :root { --bg: #121212; --surface: #1e1e1e; --text: #e0e0e0; --primary: #4FC3F7; }
                body { font-family: system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); max-width: 800px; margin: 0 auto; padding: 20px; }
                h1 { color: var(--primary); margin-bottom: 0.5rem; }
                .preview-container { background: #000; border-radius: 8px; overflow: hidden; margin: 20px 0; aspect-ratio: 16/9; display: flex; align-items: center; justify-content: center; }
                .preview-container img { width: 100%; height: 100%; }
                /* Object fit classes applied via JS */
                .fit-contain { object-fit: contain; }
                .fit-cover { object-fit: cover; }
                .fit-fill { object-fit: fill; }
                .fit-none { object-fit: none; }
                
                .controls-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; }
                @media (max-width: 600px) { .controls-grid { grid-template-columns: 1fr; } }
                
                .controls { background: var(--surface); padding: 20px; border-radius: 8px; margin-bottom: 15px; }
                .control-group { display: flex; flex-direction: column; gap: 5px; margin-bottom: 10px; }
                .control-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
                
                label { font-size: 0.9rem; color: #aaa; }
                select, input[type="range"] { padding: 8px; border-radius: 4px; background: #2c2c2c; color: white; border: 1px solid #444; width: 100%; }
                
                .btn-group { display: flex; gap: 10px; margin-top: 10px; }
                button { flex: 1; padding: 10px; border: none; border-radius: 4px; font-weight: bold; cursor: pointer; }
                .btn-start { background: var(--primary); color: #000; }
                .btn-stop { background: #cf6679; color: #000; }
                .status { margin-top: 15px; padding: 10px; border-radius: 4px; background: #2c2c2c; font-family: monospace; font-size: 0.85rem; }
                .rebind-warning { color: #fbc02d; font-weight: bold; display: none; }
              </style>
            </head>
            <body>
              <h1>OpenCamBridge</h1>
              <p>Live MJPEG Stream Control <span id="rebind-warning" class="rebind-warning">(Rebinding camera...)</span></p>
              
              <div class="preview-container">
                <img id="stream-img" src="/stream.mjpeg" class="fit-contain" alt="Live stream" onerror="this.alt='Stream stopped'">
              </div>

              <div class="controls-grid">
                  <div class="controls">
                    <h3>Settings</h3>
                    <div class="control-group">
                      <label>Camera</label>
                      <select id="camera-select" onchange="updateSettings()"></select>
                    </div>
                    <div class="control-group">
                      <label>Resolution</label>
                      <select id="res-select" onchange="updateSettings()">
                         <option value="1920x1080">1920 x 1080</option>
                         <option value="1280x720">1280 x 720</option>
                         <option value="640x480">640 x 480</option>
                      </select>
                    </div>
                    <div class="control-group">
                      <label>FPS Limit</label>
                      <select id="fps-select" onchange="updateSettings()">
                         <option value="15">15 fps</option>
                         <option value="30">30 fps</option>
                         <option value="60">60 fps</option>
                      </select>
                    </div>
                    <div class="control-group">
                      <label>Preview Fit Mode</label>
                      <select id="fit-select" onchange="updateSettings()">
                         <option value="fit">Fit (Contain)</option>
                         <option value="fill">Fill (Cover)</option>
                         <option value="stretch">Stretch</option>
                         <option value="original">Original (None)</option>
                      </select>
                    </div>
                    <div class="control-group">
                      <label>JPEG Quality: <span id="quality-val">85</span>%</label>
                      <input type="range" id="quality-slider" min="10" max="100" value="85" onchange="updateSettings()" oninput="document.getElementById('quality-val').innerText=this.value">
                    </div>
                  </div>
                  
                  <div class="controls">
                    <h3>Controls</h3>
                    <div class="control-group">
                      <label>Zoom (Linear): <span id="zoom-val">0</span></label>
                      <input type="range" id="zoom-slider" min="0" max="100" value="0" onchange="updateZoom()" oninput="document.getElementById('zoom-val').innerText=(this.value/100).toFixed(2)">
                    </div>
                    <div class="control-row">
                      <label>Torch / Lamp</label>
                      <input type="checkbox" id="torch-check" onchange="updateTorch()">
                    </div>
                    <div class="control-row">
                      <label>Continuous Autofocus</label>
                      <input type="checkbox" id="af-check" onchange="updateAf()">
                    </div>
                    <div class="btn-group">
                      <button class="btn-start" onclick="startStream()">Start Stream</button>
                      <button class="btn-stop" onclick="stopStream()">Stop Stream</button>
                    </div>
                  </div>
              </div>
              
              <div id="status-panel" class="status">Loading status...</div>

              <script>
                function updateFitClass(mode) {
                    const img = document.getElementById('stream-img');
                    img.className = '';
                    if (mode === 'fit') img.classList.add('fit-contain');
                    else if (mode === 'fill') img.classList.add('fit-cover');
                    else if (mode === 'stretch') img.classList.add('fit-fill');
                    else if (mode === 'original') img.classList.add('fit-none');
                }
              
                async function fetchStatus() {
                    try {
                        const res = await fetch('/api/camera/status');
                        const status = await res.json();
                        document.getElementById('status-panel').innerText = JSON.stringify(status, null, 2);
                        document.getElementById('res-select').value = status.width + 'x' + status.height;
                        document.getElementById('fps-select').value = status.fps;
                        document.getElementById('fit-select').value = status.previewFitMode;
                        document.getElementById('quality-slider').value = status.jpegQuality;
                        document.getElementById('quality-val').innerText = status.jpegQuality;
                        
                        updateFitClass(status.previewFitMode);
                        
                        if(status.rebindInProgress) {
                            document.getElementById('rebind-warning').style.display = 'inline';
                        } else {
                            document.getElementById('rebind-warning').style.display = 'none';
                        }
                        
                        const camSelect = document.getElementById('camera-select');
                        if(camSelect.options.length > 0) camSelect.value = status.cameraId;
                    } catch (e) {
                        console.error('Status fetch error', e);
                    }
                }

                async function fetchCameras() {
                    try {
                        const res = await fetch('/api/camera/list');
                        const cameras = await res.json();
                        const select = document.getElementById('camera-select');
                        select.innerHTML = '';
                        cameras.forEach(c => {
                            const opt = document.createElement('option');
                            opt.value = c.id;
                            opt.innerText = c.label + ' (' + c.id + ')';
                            select.appendChild(opt);
                        });
                    } catch (e) { console.error('Failed to load cameras', e); }
                }

                async function fetchControls() {
                    try {
                        const res = await fetch('/api/camera/controls');
                        const controls = await res.json();
                        document.getElementById('torch-check').checked = controls.torchEnabled;
                        document.getElementById('af-check').checked = controls.autofocusEnabled;
                        document.getElementById('zoom-slider').value = controls.linearZoom * 100;
                        document.getElementById('zoom-val').innerText = controls.linearZoom.toFixed(2);
                    } catch (e) { console.error('Failed to load controls', e); }
                }

                async function updateSettings() {
                    const cameraId = document.getElementById('camera-select').value;
                    const resStr = document.getElementById('res-select').value.split('x');
                    const width = parseInt(resStr[0]);
                    const height = parseInt(resStr[1]);
                    const fps = parseInt(document.getElementById('fps-select').value);
                    const previewFitMode = document.getElementById('fit-select').value;
                    const jpegQuality = parseInt(document.getElementById('quality-slider').value);
                    
                    try {
                        await fetch('/api/settings', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({ cameraId, width, height, fps, jpegQuality, previewFitMode })
                        });
                        setTimeout(fetchStatus, 500); // Give camera time to start rebinding
                    } catch (e) { console.error('Update err', e); }
                }
                
                async function updateZoom() {
                    const val = parseInt(document.getElementById('zoom-slider').value) / 100.0;
                    await fetch('/api/camera/zoom', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ linearZoom: val })
                    });
                }
                
                async function updateTorch() {
                    const enabled = document.getElementById('torch-check').checked;
                    await fetch('/api/camera/torch', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ enabled })
                    });
                }
                
                async function updateAf() {
                    const enabled = document.getElementById('af-check').checked;
                    await fetch('/api/camera/autofocus', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ enabled })
                    });
                }

                async function startStream() {
                    await fetch('/api/stream/start', { method: 'POST' });
                    document.getElementById('stream-img').src = '/stream.mjpeg?' + new Date().getTime();
                    fetchStatus();
                }

                async function stopStream() {
                    await fetch('/api/stream/stop', { method: 'POST' });
                    fetchStatus();
                }

                window.onload = async () => {
                    await fetchCameras();
                    await fetchStatus();
                    await fetchControls();
                    setInterval(fetchStatus, 2000); // Poll status to clear rebind flag
                };
              </script>
            </body>
            </html>
            """.trimIndent()
        }
    }

    private suspend fun serveDeviceInfo(call: RoutingCall) {
        call.respond(DeviceInfoDto(app = "OpenCamBridge", version = "0.1.0", platform = "android", serverPort = PORT))
    }

    private suspend fun serveCameraList(call: RoutingCall) {
        call.respond(cameraRepo.listCameras())
    }

    private suspend fun serveCameraStatus(call: RoutingCall) {
        call.respond(StreamState.toStatusDto())
    }

    private suspend fun serveGetSettings(call: RoutingCall) {
        call.respond(StreamState.toStatusDto())
    }
    
    private suspend fun serveCameraControls(call: RoutingCall) {
        call.respond(
            CameraControlsDto(
                hasTorch = true, // Simplified for MVP
                torchEnabled = StreamState.torchEnabled.get(),
                autofocusSupported = false, // Simplified for MVP (CameraX defaults continuous)
                autofocusEnabled = StreamState.autofocusEnabled.get(),
                zoomRatio = StreamState.zoomRatio.get(),
                linearZoom = StreamState.linearZoom.get()
            )
        )
    }

    private suspend fun serveStreamStart(call: RoutingCall) {
        onStreamStart()
        call.respond(SimpleResult(success = true, message = "Stream started"))
    }

    private suspend fun serveStreamStop(call: RoutingCall) {
        onStreamStop()
        call.respond(SimpleResult(success = true, message = "Stream stopped"))
    }

    private suspend fun serveCameraSwitch(call: RoutingCall) {
        val req = try { call.receive<CameraSwitchRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON: ${e.message}"))
            return
        }
        StreamState.cameraId.set(req.cameraId)
        settingsManager.save()
        onSettingsChanged()
        call.respond(SimpleResult(success = true, message = "Switched to camera ${req.cameraId}"))
    }

    private suspend fun serveSetResolution(call: RoutingCall) {
        val req = try { call.receive<ResolutionRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        StreamState.width.set(req.width)
        StreamState.height.set(req.height)
        settingsManager.save()
        onSettingsChanged()
        call.respond(SimpleResult(success = true, message = "Resolution set to ${req.width}x${req.height}"))
    }

    private suspend fun serveSetFps(call: RoutingCall) {
        val req = try { call.receive<FpsRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        StreamState.fps.set(req.fps.coerceIn(1, 120))
        settingsManager.save()
        onSettingsChanged()
        call.respond(SimpleResult(success = true, message = "FPS set to ${req.fps}"))
    }

    private suspend fun serveSetJpegQuality(call: RoutingCall) {
        val req = try { call.receive<JpegQualityRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        val quality = req.quality.coerceIn(1, 100)
        StreamState.jpegQuality.set(quality)
        settingsManager.save()
        call.respond(SimpleResult(success = true, message = "JPEG quality set to $quality"))
    }

    private suspend fun serveSetPreviewFitMode(call: RoutingCall) {
        val req = try { call.receive<PreviewFitModeRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        StreamState.previewFitMode.set(req.previewFitMode)
        settingsManager.save()
        call.respond(SimpleResult(success = true, message = "Preview fit mode set to ${req.previewFitMode}"))
    }

    private suspend fun serveUpdateSettings(call: RoutingCall) {
        val req = try { call.receive<UpdateSettingsRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        
        StreamState.cameraId.set(req.cameraId)
        StreamState.width.set(req.width)
        StreamState.height.set(req.height)
        StreamState.fps.set(req.fps.coerceIn(1, 120))
        StreamState.jpegQuality.set(req.jpegQuality.coerceIn(1, 100))
        StreamState.previewFitMode.set(req.previewFitMode)
        
        settingsManager.save()
        onSettingsChanged()
        
        call.respond(SimpleResult(success = true, message = "Settings updated"))
    }
    
    // ---- Controls ----
    
    private suspend fun serveSetZoom(call: RoutingCall) {
        val req = try { call.receive<ZoomRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        if (req.linearZoom != null) {
            onSetLinearZoom(req.linearZoom.coerceIn(0f, 1f))
            call.respond(SimpleResult(true, "Linear zoom set to ${req.linearZoom}"))
        } else if (req.zoomRatio != null) {
            onSetZoomRatio(req.zoomRatio)
            call.respond(SimpleResult(true, "Zoom ratio set to ${req.zoomRatio}"))
        } else {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Require zoomRatio or linearZoom"))
        }
    }
    
    private suspend fun serveSetTorch(call: RoutingCall) {
        val req = try { call.receive<TorchRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        onSetTorch(req.enabled)
        call.respond(SimpleResult(true, "Torch set to ${req.enabled}"))
    }
    
    private suspend fun serveSetAutofocus(call: RoutingCall) {
        val req = try { call.receive<AutofocusRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        // Always return success: false because CameraX defaults continuous AF and we don't hack interop.
        call.respond(SimpleResult(false, "Manual autofocus control not supported in MVP 1.6"))
    }

    private suspend fun serveMjpeg(call: RoutingCall) {
        call.response.headers.append("Cache-Control", "no-cache")
        call.response.headers.append("Connection", "close")

        call.respondBytesWriter(
            contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY")
        ) {
            streamMjpegFrames()
        }
    }

    private suspend fun ByteWriteChannel.streamMjpegFrames() {
        try {
            while (currentCoroutineContext().isActive) {
                // Throttle based on requested FPS
                val targetFps = StreamState.fps.get().coerceIn(1, 120)
                val delayMs = 1000L / targetFps
                
                val frame = StreamState.latestFrame.get()
                if (frame != null && !StreamState.rebindInProgress.get()) {
                    val header = "--$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    writeFully(header.toByteArray(Charsets.US_ASCII))
                    writeFully(frame)
                    writeFully("\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                delay(delayMs)
            }
        } catch (_: Exception) {
            // Client disconnected
        }
    }
}

// ---- DTOs ----

@Serializable
private data class DeviceInfoDto(val app: String, val version: String, val platform: String, val serverPort: Int)

@Serializable
data class SimpleResult(val success: Boolean, val message: String)

@Serializable
data class CameraControlsDto(
    val hasTorch: Boolean,
    val torchEnabled: Boolean,
    val autofocusSupported: Boolean,
    val autofocusEnabled: Boolean,
    val zoomRatio: Float,
    val linearZoom: Float
)

@Serializable
private data class CameraSwitchRequest(val cameraId: String)

@Serializable
private data class ResolutionRequest(val width: Int, val height: Int)

@Serializable
private data class FpsRequest(val fps: Int)

@Serializable
private data class JpegQualityRequest(val quality: Int)

@Serializable
private data class PreviewFitModeRequest(val previewFitMode: String)

@Serializable
private data class UpdateSettingsRequest(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val jpegQuality: Int,
    val previewFitMode: String
)

@Serializable
private data class ZoomRequest(val zoomRatio: Float? = null, val linearZoom: Float? = null)

@Serializable
private data class TorchRequest(val enabled: Boolean)

@Serializable
private data class AutofocusRequest(val enabled: Boolean)
