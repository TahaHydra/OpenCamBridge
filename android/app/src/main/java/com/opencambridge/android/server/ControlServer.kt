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
                post("/api/settings/aspect-ratio") { serveSetAspectRatio(call) }
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
                :root { 
                    --bg: #121317; 
                    --surface: #1a1b1f; 
                    --text: #ffffff; 
                    --primary: #00e5ff; 
                    --active: #4caf50; 
                    --error: #ff5252; 
                }
                body { 
                    font-family: system-ui, -apple-system, sans-serif; 
                    background: var(--bg); 
                    color: var(--text); 
                    margin: 0; 
                    padding: 24px; 
                    box-sizing: border-box; 
                }
                header { margin-bottom: 24px; display: flex; align-items: center; justify-content: space-between; }
                h1 { color: var(--primary); margin: 0; font-size: 1.5rem; display: flex; align-items: center; gap: 8px; }
                h1::before { content: ""; display: inline-block; width: 16px; height: 16px; border-radius: 50%; background: var(--primary); }
                .subtitle { color: #aaa; font-size: 0.9rem; margin-top: 4px; }
                
                main {
                  display: grid;
                  grid-template-columns: minmax(0, 60%) minmax(320px, 40%);
                  gap: 24px;
                  align-items: start;
                }
                @media (max-width: 900px) {
                  main { grid-template-columns: 1fr; }
                }

                .preview-container { 
                    background: #000; 
                    border-radius: 8px; 
                    overflow: hidden; 
                    width: 100%;
                    aspect-ratio: 16/9; 
                    display: flex; 
                    align-items: center; 
                    justify-content: center; 
                    border: 1px solid #333;
                }
                .preview-container img { 
                    width: 100%; 
                    height: 100%; 
                    transition: transform 0.2s ease;
                }
                /* Object fit classes applied via JS */
                .fit-contain { object-fit: contain; }
                .fit-cover { object-fit: cover; }
                .fit-fill { object-fit: fill; }
                .fit-none { object-fit: none; }
                
                .controls-grid { display: flex; flex-direction: column; gap: 16px; }
                
                .controls { background: var(--surface); padding: 20px; border-radius: 8px; border: 1px solid #2a2c33; }
                .controls h3 { margin-top: 0; margin-bottom: 16px; color: var(--primary); font-size: 1rem; border-bottom: 1px solid #2a2c33; padding-bottom: 8px; }
                .control-group { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
                .control-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 16px; }
                
                label { font-size: 0.85rem; color: #aaa; font-weight: 500; }
                select, input[type="range"] { 
                    padding: 10px; 
                    border-radius: 6px; 
                    background: #2a2c33; 
                    color: white; 
                    border: 1px solid #3a3c44; 
                    width: 100%; 
                    box-sizing: border-box; 
                    outline: none;
                    transition: border-color 0.2s;
                }
                select:focus, input[type="range"]:focus { border-color: var(--primary); }
                input[type="checkbox"] { accent-color: var(--primary); width: 18px; height: 18px; }
                
                .btn-group { display: flex; gap: 12px; margin-top: 8px; }
                button { flex: 1; padding: 14px; border: none; border-radius: 6px; font-weight: 600; cursor: pointer; transition: opacity 0.2s; }
                button:hover { opacity: 0.9; }
                .btn-start { background: var(--active); color: #000; }
                .btn-stop { background: var(--error); color: #000; }
                
                details { background: var(--surface); padding: 16px; border-radius: 8px; border: 1px solid #2a2c33; }
                summary { cursor: pointer; font-weight: 600; color: var(--primary); outline: none; }
                .status { margin-top: 12px; padding: 12px; border-radius: 6px; background: #121317; font-family: monospace; font-size: 0.8rem; max-height: 300px; overflow-y: auto; border: 1px solid #2a2c33; color: #00e5ff; }
                .rebind-warning { color: #fbc02d; font-weight: 600; display: none; font-size: 0.9rem; }
            </style>
            </head>
            <body>
              <header>
                  <div>
                      <h1>OpenCamBridge</h1>
                      <div class="subtitle">Live MJPEG Stream Control</div>
                  </div>
                  <div id="rebind-warning" class="rebind-warning">Rebinding camera...</div>
              </header>
              
              <main>
                  <!-- Left side: Preview -->
                  <div class="preview-section">
                      <div class="preview-container">
                        <img id="stream-img" src="/stream.mjpeg" class="fit-contain" alt="Live stream" onerror="this.alt='Stream stopped'">
                      </div>
                  </div>

                  <!-- Right side: Controls -->
                  <div class="controls-grid">
                    <div class="controls">
                      <h3>Connection</h3>
                      <div class="btn-group">
                        <button class="btn-start" onclick="startStream()">Start Stream</button>
                        <button class="btn-stop" onclick="stopStream()">Stop Stream</button>
                      </div>
                      <div class="control-row" style="margin-top:16px;">
                        <label>Phone Preview</label>
                        <input type="checkbox" id="preview-check" onchange="patchSetting({localPreviewEnabled: this.checked})">
                      </div>
                    </div>
                    
                    <div class="controls">
                      <h3>Camera Config</h3>
                      <div class="control-group">
                        <label>Camera</label>
                        <select id="camera-select" onchange="patchSetting({cameraId: this.value})"></select>
                      </div>
                      <div class="control-group">
                        <label>Resolution</label>
                        <select id="res-select" onchange="patchSetting({width: parseInt(this.value.split('x')[0]), height: parseInt(this.value.split('x')[1])})">
                           <option value="1920x1080">1920 x 1080</option>
                           <option value="1280x720">1280 x 720</option>
                           <option value="640x480">640 x 480</option>
                        </select>
                      </div>
                      <div class="control-group">
                        <label>Aspect Ratio</label>
                        <select id="ar-select" onchange="patchSetting({aspectRatio: this.value})">
                           <option value="auto">Auto</option>
                           <option value="16:9">16:9</option>
                           <option value="4:3">4:3</option>
                        </select>
                      </div>
                      <div class="control-group">
                        <label>FPS Limit</label>
                        <select id="fps-select" onchange="patchSetting({fps: parseInt(this.value)})">
                           <option value="15">15 fps</option>
                           <option value="30">30 fps</option>
                           <option value="60">60 fps</option>
                        </select>
                      </div>
                    </div>
                    
                    <div class="controls">
                      <h3>Image Controls</h3>
                      <div class="control-group">
                        <label>JPEG Quality: <span id="quality-val">85</span>%</label>
                        <input type="range" id="quality-slider" min="10" max="100" value="85" onmousedown="isDraggingQuality=true" onmouseup="isDraggingQuality=false; patchSetting({jpegQuality: parseInt(this.value)})" oninput="document.getElementById('quality-val').innerText=this.value">
                      </div>
                      <div class="control-group">
                        <label>Zoom (Linear): <span id="zoom-val">0</span></label>
                        <input type="range" id="zoom-slider" min="0" max="100" value="0" onmousedown="isDraggingZoom=true" onmouseup="isDraggingZoom=false; updateZoom()" oninput="document.getElementById('zoom-val').innerText=(this.value/100).toFixed(2)">
                      </div>
                      <div class="control-group">
                        <label>Zoom Speed</label>
                        <select id="zs-select" onchange="patchSetting({zoomSpeed: this.value})">
                           <option value="slow">Slow</option>
                           <option value="normal">Normal</option>
                           <option value="fast">Fast</option>
                        </select>
                      </div>
                      <div class="control-row">
                        <label>Torch / Lamp</label>
                        <input type="checkbox" id="torch-check" onchange="updateTorch()">
                      </div>
                      <div class="control-row">
                        <label>Autofocus</label>
                        <input type="checkbox" id="af-check" onchange="updateAf()">
                      </div>
                    </div>
                    
                    <div class="controls">
                      <h3>Output & Display</h3>
                      <div class="control-group">
                        <label>Fit Mode</label>
                        <select id="fit-select" onchange="patchSetting({previewFitMode: this.value})">
                           <option value="fit">Fit (Contain)</option>
                           <option value="fill">Fill (Cover)</option>
                           <option value="stretch">Stretch</option>
                           <option value="original">Original (None)</option>
                        </select>
                      </div>
                      <div class="control-group">
                        <label>Rotation</label>
                        <select id="rot-select" onchange="patchSetting({displayRotation: parseInt(this.value)})">
                           <option value="0">0°</option>
                           <option value="90">90°</option>
                           <option value="180">180°</option>
                           <option value="270">270°</option>
                        </select>
                      </div>
                      <div class="control-row">
                        <label>Mirror</label>
                        <input type="checkbox" id="mirror-check" onchange="patchSetting({mirror: this.checked})">
                      </div>
                    </div>
                    
                    <details>
                        <summary>Advanced / Debug</summary>
                        <div id="status-panel" class="status">Loading status...</div>
                    </details>
                  </div>
              </main>

              <script>
                function updateFitClass(mode, displayRot, mirror) {
                    const img = document.getElementById('stream-img');
                    
                    // Reset sizing to let CSS compute naturally before transform
                    img.style.width = '100%';
                    img.style.height = '100%';
                    
                    img.className = '';
                    if (mode === 'fit') img.classList.add('fit-contain');
                    else if (mode === 'fill') img.classList.add('fit-cover');
                    else if (mode === 'stretch') img.classList.add('fit-fill');
                    else if (mode === 'original') img.classList.add('fit-none');
                    
                    // Calculate transform
                    const scaleX = mirror ? -1 : 1;
                    img.style.transform = 'rotate(' + displayRot + 'deg) scaleX(' + scaleX + ')';
                    
                    // If rotated 90 or 270 degrees, the width and height bounds are flipped
                    // To prevent it from overflowing vertically and breaking the layout,
                    // we swap the dimension boundaries within the parent container's box.
                    if (displayRot === 90 || displayRot === 270) {
                        // The max dimension is the parent's height. To stretch correctly inside,
                        // its width needs to be bounded by the parent height, and vice versa.
                        // Simple robust fix: swap CSS height and width assignments.
                        const parent = document.querySelector('.preview-container');
                        const cw = parent.clientWidth;
                        const ch = parent.clientHeight;
                        // We set explicit pixel bounds so rotation bounds don't blow up
                        img.style.width = ch + 'px';
                        img.style.height = cw + 'px';
                    }
                }
              
                let currentRevision = 0;
                let isDraggingQuality = false;
                let isDraggingZoom = false;

                async function fetchStatus() {
                    try {
                        const res = await fetch('/api/camera/status');
                        const status = await res.json();
                        currentRevision = status.revision;
                        document.getElementById('status-panel').innerText = JSON.stringify(status, null, 2);
                        document.getElementById('res-select').value = status.width + 'x' + status.height;
                        document.getElementById('fps-select').value = status.fps;
                        document.getElementById('fit-select').value = status.previewFitMode;
                        document.getElementById('ar-select').value = status.aspectRatio || 'auto';
                        document.getElementById('zs-select').value = status.zoomSpeed || 'normal';
                        document.getElementById('rot-select').value = status.displayRotation || 0;
                        document.getElementById('mirror-check').checked = !!status.mirror;
                        document.getElementById('preview-check').checked = !!status.localPreviewEnabled;
                        
                        if (!isDraggingQuality) {
                            document.getElementById('quality-slider').value = status.jpegQuality;
                            document.getElementById('quality-val').innerText = status.jpegQuality;
                        }
                        
                        updateFitClass(status.previewFitMode, status.displayRotation || 0, !!status.mirror);
                        
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
                        if (!isDraggingZoom) {
                            document.getElementById('zoom-slider').value = controls.linearZoom * 100;
                            document.getElementById('zoom-val').innerText = controls.linearZoom.toFixed(2);
                        }
                    } catch (e) { console.error('Failed to load controls', e); }
                }

                async function patchSetting(payload) {
                    payload.clientRevision = currentRevision;
                    payload.clientType = 'web';
                    try {
                        await fetch('/api/settings', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify(payload)
                        });
                        fetchStatus();
                    } catch (e) { console.error('Update err', e); }
                }
                
                async function updateZoom() {
                    const val = parseInt(document.getElementById('zoom-slider').value) / 100.0;
                    await fetch('/api/camera/zoom', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ linearZoom: val })
                    });
                    fetchControls();
                }
                
                async function updateTorch() {
                    const enabled = document.getElementById('torch-check').checked;
                    await fetch('/api/camera/torch', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ enabled })
                    });
                    fetchControls();
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
                    setInterval(() => { fetchStatus(); fetchControls(); }, 500);
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
                hasTorch = StreamState.hasTorch.get(),
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
        StreamState.incrementRevision("api")
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
        StreamState.incrementRevision("api")
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
        StreamState.incrementRevision("api")
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
        StreamState.incrementRevision("api")
        call.respond(SimpleResult(success = true, message = "JPEG quality set to $quality"))
    }

    private suspend fun serveSetPreviewFitMode(call: RoutingCall) {
        val req = try { call.receive<PreviewFitModeRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        StreamState.previewFitMode.set(req.previewFitMode)
        settingsManager.save()
        StreamState.incrementRevision("api")
        call.respond(SimpleResult(success = true, message = "Preview fit mode set to ${req.previewFitMode}"))
    }

    private suspend fun serveSetAspectRatio(call: RoutingCall) {
        val req = try { call.receive<AspectRatioRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        StreamState.aspectRatio.set(req.aspectRatio)
        settingsManager.save()
        StreamState.incrementRevision("api")
        call.respond(SimpleResult(success = true, message = "Aspect ratio set to ${req.aspectRatio}"))
    }

    private suspend fun serveUpdateSettings(call: RoutingCall) {
        val req = try { call.receive<UpdateSettingsRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        
        if (req.clientRevision != null && req.clientRevision < StreamState.revision.get()) {
            call.respond(HttpStatusCode.Conflict, SimpleResult(false, "Stale revision. Client sent ${req.clientRevision}, server is at ${StreamState.revision.get()}"))
            return
        }
        
        var requiresRebind = false
        var requiresSettingsSave = false
        
        req.cameraId?.let { StreamState.cameraId.set(it); requiresRebind = true; requiresSettingsSave = true }
        
        if (req.width != null || req.height != null || req.aspectRatio != null || req.cameraId != null) {
            var finalW = req.width ?: StreamState.width.get()
            var finalH = req.height ?: StreamState.height.get()
            val finalAR = req.aspectRatio ?: StreamState.aspectRatio.get()
            val finalCam = req.cameraId ?: StreamState.cameraId.get()
            
            // Find nearest matching resolution based on aspect ratio constraint
            val camInfo = cameraRepo.listCameras().find { it.id == finalCam }
            if (camInfo != null) {
                val validSizes = camInfo.supportedSizes.filter {
                    if (finalAR == "16:9") kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - 1.77f) < 0.1f
                    else if (finalAR == "4:3") kotlin.math.abs((it.width.toFloat() / it.height.toFloat()) - 1.33f) < 0.1f
                    else true
                }
                val exactMatch = validSizes.find { it.width == finalW && it.height == finalH }
                if (exactMatch == null && validSizes.isNotEmpty()) {
                    val fallback = validSizes.minByOrNull { kotlin.math.abs(it.width * it.height - finalW * finalH) }
                    if (fallback != null) {
                        finalW = fallback.width
                        finalH = fallback.height
                    }
                }
            }
            StreamState.width.set(finalW)
            StreamState.height.set(finalH)
            requiresRebind = true
            requiresSettingsSave = true
        }
        
        req.fps?.let { StreamState.fps.set(it.coerceIn(1, 120)); requiresRebind = true; requiresSettingsSave = true }
        req.jpegQuality?.let { StreamState.jpegQuality.set(it.coerceIn(1, 100)); requiresSettingsSave = true }
        req.previewFitMode?.let { StreamState.previewFitMode.set(it); requiresSettingsSave = true }
        req.aspectRatio?.let { StreamState.aspectRatio.set(it); requiresRebind = true; requiresSettingsSave = true }
        req.zoomSpeed?.let { StreamState.zoomSpeed.set(it); requiresSettingsSave = true }
        req.displayRotation?.let { StreamState.displayRotation.set(it); requiresSettingsSave = true }
        req.mirror?.let { StreamState.mirror.set(it); requiresSettingsSave = true }
        req.localPreviewEnabled?.let { StreamState.localPreviewEnabled.set(it); requiresRebind = true; requiresSettingsSave = true }
        
        if (requiresSettingsSave) settingsManager.save()
        StreamState.incrementRevision(req.clientType ?: "api")
        if (requiresRebind) onSettingsChanged()
        
        call.respond(SimpleResult(success = true, message = "Settings updated."))
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
private data class AspectRatioRequest(val aspectRatio: String)

@Serializable
private data class UpdateSettingsRequest(
    val clientRevision: Long? = null,
    val clientType: String? = null,
    val cameraId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val jpegQuality: Int? = null,
    val previewFitMode: String? = null,
    val aspectRatio: String? = null,
    val zoomSpeed: String? = null,
    val displayRotation: Int? = null,
    val mirror: Boolean? = null,
    val localPreviewEnabled: Boolean? = null
)

@Serializable
private data class ZoomRequest(val zoomRatio: Float? = null, val linearZoom: Float? = null)

@Serializable
private data class TorchRequest(val enabled: Boolean)

@Serializable
private data class AutofocusRequest(val enabled: Boolean)
