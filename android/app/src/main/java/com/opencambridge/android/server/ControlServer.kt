package com.opencambridge.android.server

import android.content.Context
import com.opencambridge.android.camera.CameraRepository
import com.opencambridge.android.camera.H264Streamer
import com.opencambridge.android.state.SettingsManager
import com.opencambridge.android.state.StreamState
import com.opencambridge.android.state.AppLogger
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
import io.ktor.server.request.path
import io.ktor.server.request.accept
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.application.call
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MJPEG_BOUNDARY = "FRAME"

/**
 * Embedded Ktor (3.x) HTTP server.
 * Route handlers are plain suspend functions that receive the RoutingCall.
 */
class ControlServer(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val h264Streamer: H264Streamer,
    private val onStartCamera: () -> Unit,
    private val onStopCamera: () -> Unit,
    private val onApplySettingsPatch: (UpdateSettingsRequest, String?) -> Unit,
    private val onSetZoomRatio: (Float) -> Unit,
    private val onSetLinearZoom: (Float) -> Unit,
    private val onSetTorch: (Boolean) -> Unit,
    private val onRecoverCamera: () -> Unit
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val cameraRepo = CameraRepository(context)

    fun start() {
        val port = StreamState.port.get()
        val accessMode = StreamState.accessMode.get()
        val host = if (accessMode == "usbOnly") "127.0.0.1" else "0.0.0.0"

        engine = embeddedServer(CIO, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                    val path = call.request.path()
                    if (path == "/health") return@intercept
                    
                    val currentMode = StreamState.accessMode.get()
                    if (currentMode == "lanToken") {
                        val token = call.request.queryParameters["token"] ?: call.request.headers["X-OpenCamBridge-Token"]
                        if (token != StreamState.accessToken.get()) {
                            AppLogger.w("Security", "Rejected unauthorized request to $path")
                            if (call.request.accept()?.contains("text/html") == true || path == "/") {
                                call.respondText("Unauthorized. Missing or invalid token.", ContentType.Text.Html, HttpStatusCode.Unauthorized)
                            } else {
                                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                            }
                            finish()
                            return@intercept
                        }
                    }
                }
                
                get("/")                           { serveIndex(call) }
                get("/health")                     { call.respondText("OK") }
                get("/api/device/info")            { serveDeviceInfo(call) }
                get("/api/camera/list")            { serveCameraList(call) }
                get("/api/camera/status")          { serveCameraStatus(call) }
                get("/api/camera/controls")        { serveCameraControls(call) }
                get("/api/settings")               { serveGetSettings(call) }
                get("/api/logs")                   { serveGetLogs(call) }
                
                post("/api/stream/start")          { serveStreamStart(call) }
                post("/api/stream/stop")           { serveStreamStop(call) }
                post("/api/stream/recover")        { serveStreamRecover(call) }
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
                
                post("/api/logs/clear")            { serveClearLogs(call) }
                
                get("/stream.mjpeg")               { serveMjpeg(call) }
                get("/stream.h264")                { serveH264(call) }
                get("/api/stream/info")            { serveStreamInfo(call) }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        engine = null
    }

    private suspend fun serveGetLogs(call: RoutingCall) {
        val logs = AppLogger.getLogs()
        call.respondText(
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.opencambridge.android.state.LogEntry.serializer()), logs),
            ContentType.Application.Json
        )
    }

    private suspend fun serveClearLogs(call: RoutingCall) {
        AppLogger.clear()
        AppLogger.i("Security", "Logs cleared by user")
        call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
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
                    position: relative;
                }
                .offline-overlay {
                    position: absolute;
                    inset: 0;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    background: rgba(0, 0, 0, 0.7);
                    color: white;
                    font-size: 1.2rem;
                    z-index: 10;
                    display: none;
                }
                .offline-overlay p { margin-bottom: 16px; font-weight: bold; }
                .refresh-btn {
                    padding: 8px 16px;
                    background: #333;
                    color: white;
                    border: 1px solid #555;
                    border-radius: 4px;
                    cursor: pointer;
                    font-size: 0.9rem;
                    flex: unset;
                }
                .refresh-btn:hover { background: #444; }
                .manual-refresh-btn {
                    position: absolute;
                    top: 16px;
                    right: 16px;
                    background: rgba(0,0,0,0.5);
                    border: 1px solid rgba(255,255,255,0.2);
                    color: white;
                    padding: 6px 12px;
                    border-radius: 4px;
                    cursor: pointer;
                    z-index: 15;
                    font-size: 0.8rem;
                    flex: unset;
                }
                .manual-refresh-btn:hover { background: rgba(0,0,0,0.8); border-color: rgba(255,255,255,0.4); }
                .preview-container img { 
                    /* Sizing is handled by JS to correctly emulate Android PreviewView rotation scaling */
                    transition: transform 0.2s ease;
                }
                
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
                
                .tabs { display: flex; gap: 8px; margin-bottom: 16px; border-bottom: 1px solid #2a2c33; padding-bottom: 0px; overflow-x: auto; }
                .tab { padding: 10px 16px; cursor: pointer; color: #aaa; border-radius: 6px 6px 0 0; transition: background 0.2s, color 0.2s; white-space: nowrap; margin-bottom: -1px; border: 1px solid transparent; }
                .tab:hover { color: #fff; }
                .tab.active { background: var(--surface); color: var(--primary); font-weight: 600; border: 1px solid #2a2c33; border-bottom: 1px solid var(--surface); }
                .tab-content { display: none; }
                .tab-content.active { display: block; }
                
                details { background: var(--surface); padding: 16px; border-radius: 8px; border: 1px solid #2a2c33; }
                summary { cursor: pointer; font-weight: 600; color: var(--primary); outline: none; }
                .status { margin-top: 12px; padding: 12px; border-radius: 6px; background: #121317; font-family: monospace; font-size: 0.8rem; max-height: 400px; overflow-y: auto; border: 1px solid #2a2c33; color: #00e5ff; white-space: pre-wrap; }
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
                        <div id="offline-overlay" class="offline-overlay">
                            <p>Stream is offline</p>
                            <button class="refresh-btn" onclick="reloadPreviewImage()">Refresh Image</button>
                        </div>
                        <button class="manual-refresh-btn" onclick="reloadPreviewImage()" title="Refresh preview">↺ Refresh</button>
                      </div>
                  </div>

                  <!-- Right side: Content -->
                  <div class="right-panel">
                    <div class="tabs">
                      <div class="tab active" onclick="switchTab('controls')">Controls</div>
                      <div class="tab" onclick="switchTab('security')">Security</div>
                      <div class="tab" onclick="switchTab('logs')">Logs</div>
                      <div class="tab" onclick="switchTab('debug')">Debug</div>
                    </div>
                    
                    <!-- Controls Tab -->
                    <div id="tab-controls" class="tab-content active">
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
                        <label>Stream Mode</label>
                        <select id="sm-select" onchange="patchSetting({streamMode: this.value})">
                           <option value="mjpeg">MJPEG</option>
                           <option value="h264">H.264 (Low Latency)</option>
                        </select>
                      </div>
                      <div id="h264-info" style="display:none; font-size: 0.85rem; color: #aaa; margin-top: 8px;">
                          H.264 stream active at: <a href="#" id="h264-link" style="color:var(--primary)" target="_blank">/stream.h264</a><br>
                          Test with: <code style="background:#000;padding:2px 4px;border-radius:4px;color:#fff;">ffplay http://[IP]/stream.h264</code>
                      </div>
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
                      </div>
                    </div>
                    
                    <!-- Security Tab -->
                    <div id="tab-security" class="tab-content">
                      <div class="controls">
                        <h3>Security Info</h3>
                        <p style="font-size: 0.9rem; color: #aaa;">Access Mode: <span id="sec-mode" style="color: var(--primary); font-weight: bold;"></span></p>
                        <p style="font-size: 0.9rem; color: #aaa;">Port: <span id="sec-port" style="color: var(--primary); font-weight: bold;"></span></p>
                        <p style="font-size: 0.8rem; color: #888;">To change security settings, please use the Android app UI. Changes to port will require an app restart.</p>
                      </div>
                    </div>
                    
                    <!-- Logs Tab -->
                    <div id="tab-logs" class="tab-content">
                      <div class="controls">
                        <div style="display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #2a2c33; padding-bottom: 8px; margin-bottom: 16px;">
                          <h3 style="margin: 0; border: none; padding: 0;">Application Logs</h3>
                          <button onclick="clearLogs()" style="padding: 6px 12px; flex: none; background: #2a2c33; color: white;">Clear</button>
                        </div>
                        <div id="logs-panel" class="status">Loading logs...</div>
                      </div>
                    </div>
                    
                    <!-- Debug Tab -->
                    <div id="tab-debug" class="tab-content">
                      <div class="controls">
                        <h3>Raw State</h3>
                        <div id="status-panel" class="status">Loading status...</div>
                      </div>
                    </div>
                  </div>
              </main>

              <script>
                const TOKEN = "${StreamState.accessToken.get()}";
                const isTokenRequired = "${StreamState.accessMode.get()}" === "lanToken";
                
                let lastStatus = null;
                const fetchWithAuth = async (url, options = {}) => {
                    const urlObj = new URL(url, window.location.origin);
                    if (isTokenRequired && TOKEN) {
                        urlObj.searchParams.set('token', TOKEN);
                    }
                    return fetch(urlObj, options);
                };

                function switchTab(tabId) {
                    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                    event.target.classList.add('active');
                    document.getElementById('tab-' + tabId).classList.add('active');
                    if (tabId === 'logs') fetchLogs();
                }
                function updateFitClass(mode, displayRot, mirror) {
                    const img = document.getElementById('stream-img');
                    const container = document.querySelector('.preview-container');
                    const cw = container.clientWidth;
                    const ch = container.clientHeight;
                    if (cw === 0 || ch === 0) return;
                    
                    const nw = img.naturalWidth || 1280;
                    const nh = img.naturalHeight || 720;
                    
                    let rw = nw;
                    let rh = nh;
                    if (displayRot === 90 || displayRot === 270) {
                        rw = nh;
                        rh = nw;
                    }
                    
                    let finalW = rw;
                    let finalH = rh;
                    
                    if (mode === 'fit') {
                        const scale = Math.min(cw / rw, ch / rh);
                        finalW = rw * scale;
                        finalH = rh * scale;
                    } else if (mode === 'fill') {
                        const scale = Math.max(cw / rw, ch / rh);
                        finalW = rw * scale;
                        finalH = rh * scale;
                    } else if (mode === 'stretch') {
                        finalW = cw;
                        finalH = ch;
                    }
                    
                    if (displayRot === 90 || displayRot === 270) {
                        img.style.width = finalH + 'px';
                        img.style.height = finalW + 'px';
                    } else {
                        img.style.width = finalW + 'px';
                        img.style.height = finalH + 'px';
                    }
                    
                    const scaleX = mirror ? -1 : 1;
                    img.style.transform = 'rotate(' + displayRot + 'deg) scaleX(' + scaleX + ')';
                }
              
                let currentRevision = 0;
                let currentLifecycleState = '';
                let isDraggingQuality = false;
                let isDraggingZoom = false;

                function reloadPreviewImage() {
                    const img = document.getElementById('stream-img');
                    const imgUrl = new URL('/stream.mjpeg', window.location.origin);
                    if (isTokenRequired && TOKEN) imgUrl.searchParams.set('token', TOKEN);
                    imgUrl.searchParams.set('ts', new Date().getTime());
                    img.src = imgUrl.toString();
                }

                async function fetchStatus() {
                    try {
                        const res = await fetchWithAuth('/api/camera/status');
                        const status = await res.json();
                        lastStatus = status;
                        
                        const previousLifecycle = currentLifecycleState;
                        currentLifecycleState = status.lifecycleState;
                        currentRevision = status.revision;
                        
                        // Auto-reconnect preview if rebind finished successfully
                        if (previousLifecycle !== 'STREAMING' && currentLifecycleState === 'STREAMING') {
                            if (status.streamMode === 'mjpeg') {
                                reloadPreviewImage();
                            }
                        }

                        document.getElementById('status-panel').innerText = JSON.stringify(status, null, 2);
                        document.getElementById('sec-mode').innerText = status.accessMode;
                        document.getElementById('sec-port').innerText = status.port;
                        
                        document.getElementById('res-select').value = status.width + 'x' + status.height;
                        document.getElementById('fps-select').value = status.fps;
                        document.getElementById('fit-select').value = status.previewFitMode;
                        document.getElementById('ar-select').value = status.aspectRatio || 'auto';
                        document.getElementById('zs-select').value = status.zoomSpeed || 'normal';
                        document.getElementById('sm-select').value = status.streamMode || 'mjpeg';
                        document.getElementById('rot-select').value = status.displayRotation || 0;
                        document.getElementById('mirror-check').checked = !!status.mirror;
                        document.getElementById('preview-check').checked = !!status.localPreviewEnabled;
                        
                        if (status.streamMode === 'h264') {
                            document.getElementById('h264-info').style.display = 'block';
                            let h264Url = new URL('/stream.h264', window.location.origin);
                            if (isTokenRequired && TOKEN) h264Url.searchParams.set('token', TOKEN);
                            document.getElementById('h264-link').href = h264Url.toString();
                        } else {
                            document.getElementById('h264-info').style.display = 'none';
                        }
                        
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
                        
                        if (currentLifecycleState !== 'STREAMING' && currentLifecycleState !== 'REBINDING') {
                            document.getElementById('offline-overlay').style.display = 'flex';
                            document.getElementById('stream-img').style.opacity = '0.3';
                        } else {
                            document.getElementById('offline-overlay').style.display = 'none';
                            document.getElementById('stream-img').style.opacity = '1';
                        }
                        
                        const camSelect = document.getElementById('camera-select');
                        if(camSelect.options.length > 0) camSelect.value = status.cameraId;
                    } catch (e) {
                        console.error('Status fetch error', e);
                    }
                }

                async function fetchCameras() {
                    try {
                        const res = await fetchWithAuth('/api/camera/list');
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
                        const res = await fetchWithAuth('/api/camera/controls');
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
                        await fetchWithAuth('/api/settings', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify(payload)
                        });
                        fetchStatus();
                    } catch (e) { console.error('Update err', e); }
                }
                
                async function updateZoom() {
                    const val = parseInt(document.getElementById('zoom-slider').value) / 100.0;
                    await fetchWithAuth('/api/camera/zoom', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ linearZoom: val })
                    });
                    fetchControls();
                }
                
                async function updateTorch() {
                    const enabled = document.getElementById('torch-check').checked;
                    await fetchWithAuth('/api/camera/torch', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ enabled })
                    });
                    fetchControls();
                }
                
                async function updateAf() {
                    const enabled = document.getElementById('af-check').checked;
                    await fetchWithAuth('/api/camera/autofocus', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ enabled })
                    });
                }

                async function startStream() {
                    await fetchWithAuth('/api/stream/start', { method: 'POST' });
                    reloadPreviewImage();
                    fetchStatus();
                }

                async function stopStream() {
                    await fetchWithAuth('/api/stream/stop', { method: 'POST' });
                    fetchStatus();
                }

                async function fetchLogs() {
                    try {
                        const res = await fetchWithAuth('/api/logs');
                        const logs = await res.json();
                        const p = document.getElementById('logs-panel');
                        p.innerText = logs.map(l => `[${'$'}{new Date(l.timestamp).toLocaleTimeString()}] ${'$'}{l.level} [${'$'}{l.source}]: ${'$'}{l.message}`).join('\n');
                    } catch (e) { console.error('Failed to load logs', e); }
                }
                
                async function clearLogs() {
                    await fetchWithAuth('/api/logs/clear', { method: 'POST' });
                    fetchLogs();
                }

                window.onload = async () => {
                    const img = document.getElementById('stream-img');
                    img.onerror = () => {
                        // If stream is supposed to be running but image broke, retry after 1s
                        if (currentLifecycleState === 'STREAMING') {
                            setTimeout(reloadPreviewImage, 1000);
                        }
                    };
                    img.onload = () => {
                        if (lastStatus) {
                            updateFitClass(lastStatus.previewFitMode, lastStatus.displayRotation || 0, !!lastStatus.mirror);
                        }
                    };
                    
                    window.addEventListener('resize', () => {
                        if (lastStatus) {
                            updateFitClass(lastStatus.previewFitMode, lastStatus.displayRotation || 0, !!lastStatus.mirror);
                        }
                    });
                    
                    reloadPreviewImage();

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
        call.respond(DeviceInfoDto(app = "OpenCamBridge", version = "0.1.0", platform = "android", serverPort = StreamState.port.get()))
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
        onStartCamera()
        call.respond(SimpleResult(true, "Stream start requested"))
    }

    private suspend fun serveStreamStop(call: RoutingCall) {
        onStopCamera()
        call.respond(SimpleResult(true, "Stream stop requested"))
    }
    
    private suspend fun serveStreamRecover(call: RoutingCall) {
        onRecoverCamera()
        call.respond(SimpleResult(true, "Stream recovery requested"))
    }

    private suspend fun serveCameraSwitch(call: RoutingCall) {
        val req = try { call.receive<CameraSwitchRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON: ${e.message}"))
            return
        }
        onApplySettingsPatch(UpdateSettingsRequest(cameraId = req.cameraId), "api")
        call.respond(SimpleResult(success = true, message = "Switched to camera ${req.cameraId}"))
    }

    private suspend fun serveSetResolution(call: RoutingCall) {
        val req = try { call.receive<ResolutionRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        onApplySettingsPatch(UpdateSettingsRequest(width = req.width, height = req.height), "api")
        call.respond(SimpleResult(success = true, message = "Resolution set to ${req.width}x${req.height}"))
    }

    private suspend fun serveSetFps(call: RoutingCall) {
        val req = try { call.receive<FpsRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        onApplySettingsPatch(UpdateSettingsRequest(fps = req.fps), "api")
        call.respond(SimpleResult(success = true, message = "FPS set to ${req.fps}"))
    }

    private suspend fun serveSetJpegQuality(call: RoutingCall) {
        val req = try { call.receive<JpegQualityRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        val quality = req.quality.coerceIn(1, 100)
        onApplySettingsPatch(UpdateSettingsRequest(jpegQuality = quality), "api")
        call.respond(SimpleResult(success = true, message = "JPEG quality set to $quality"))
    }

    private suspend fun serveSetPreviewFitMode(call: RoutingCall) {
        val req = try { call.receive<PreviewFitModeRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        onApplySettingsPatch(UpdateSettingsRequest(previewFitMode = req.previewFitMode), "api")
        call.respond(SimpleResult(success = true, message = "Preview fit mode set to ${req.previewFitMode}"))
    }

    private suspend fun serveSetAspectRatio(call: RoutingCall) {
        val req = try { call.receive<AspectRatioRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        onApplySettingsPatch(UpdateSettingsRequest(aspectRatio = req.aspectRatio), "api")
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
        
        // Delegate patch application to the central stream controller
        onApplySettingsPatch(req, req.clientType ?: "api")
        
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
                
                if (StreamState.lifecycleState.get() != com.opencambridge.android.state.LifecycleState.STREAMING) break
                
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

    private suspend fun serveH264(call: RoutingCall) {
        call.response.headers.append("Cache-Control", "no-cache")
        call.response.headers.append("Connection", "close")
        call.respondBytesWriter(
            contentType = ContentType.parse("video/h264")
        ) {
            val channel = h264Streamer.subscribe()
            try {
                for (frame in channel) {
                    if (!currentCoroutineContext().isActive || StreamState.lifecycleState.get() != com.opencambridge.android.state.LifecycleState.STREAMING) break
                    writeFully(frame)
                    flush()
                }
            } catch (e: Exception) {
            } finally {
                h264Streamer.unsubscribe(channel)
            }
        }
    }

    private suspend fun serveStreamInfo(call: RoutingCall) {
        call.respond(
            StreamInfoDto(
                mode = StreamState.streamMode.get(),
                resolution = "${StreamState.width.get()}x${StreamState.height.get()}",
                fps = StreamState.fps.get(),
                h264Bitrate = StreamState.h264Bitrate.get()
            )
        )
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
data class UpdateSettingsRequest(
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
    val localPreviewEnabled: Boolean? = null,
    val accessMode: String? = null,
    val port: Int? = null,
    val accessToken: String? = null,
    val streamMode: String? = null,
    val h264Bitrate: Int? = null,
    val h264KeyframeInterval: Int? = null
)

@Serializable
private data class StreamInfoDto(val mode: String, val resolution: String, val fps: Int, val h264Bitrate: Int)

@Serializable
private data class ZoomRequest(val zoomRatio: Float? = null, val linearZoom: Float? = null)

@Serializable
private data class TorchRequest(val enabled: Boolean)

@Serializable
private data class AutofocusRequest(val enabled: Boolean)
