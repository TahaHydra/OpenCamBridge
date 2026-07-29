package com.opencambridge.android.server

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import com.opencambridge.android.camera.CameraRepository
import com.opencambridge.android.camera.H264Streamer
import com.opencambridge.android.camera.H264ModeDto
import com.opencambridge.android.camera.CameraInfoDto
import com.opencambridge.android.service.PipelineResult
import com.opencambridge.android.service.PipelineResultCode
import com.opencambridge.android.state.SettingsManager
import com.opencambridge.android.state.StreamState
import com.opencambridge.android.state.StreamStatusDto
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
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.path
import io.ktor.server.request.accept
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
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
import kotlinx.serialization.encodeToString
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
    private val onStartCamera: suspend () -> PipelineResult,
    private val onStopCamera: suspend () -> PipelineResult,
    private val onApplySettingsPatch: suspend (UpdateSettingsRequest, String?) -> PipelineResult,
    private val onSetZoomRatio: suspend (Float, Long, String, String) -> PipelineResult,
    private val onSetLinearZoom: suspend (Float, Long, String, String) -> PipelineResult,
    private val onSetTorch: suspend (Boolean, Long, String, String) -> PipelineResult,
    private val onRecoverCamera: suspend () -> PipelineResult
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val cameraRepo = CameraRepository(context)
    private val ocb2BrowserParserSource: String by lazy {
        context.assets.open("ocb2-parser.js").bufferedReader().use { it.readText() }
    }

    /**
     * Snapshot of the access mode taken when the server socket was bound.
     * Authorization decisions MUST use this value, not the live StreamState.accessMode:
     * the bind address cannot change without a server restart, so if we bound to
     * 0.0.0.0 (LAN) the token requirement must hold even if accessMode is later
     * flipped to "usbOnly" at runtime. Otherwise an authenticated LAN client could
     * disable authentication for everyone while the server is still LAN-reachable.
     */
    @Volatile
    private var boundAuthentication = BoundAuthenticationSnapshot("usbOnly", null)

    @Synchronized
    fun start(): Boolean {
        // Android may deliver another start intent to an already-running
        // foreground service (for example when the Activity is reopened or a
        // sticky service is restored). The existing Ktor engine already owns
        // the configured port, so starting a second engine would crash the app
        // with BindException: Address already in use.
        if (engine != null) {
            AppLogger.i("System", "Control server is already running; reusing the existing listener")
            return false
        }

        val port = StreamState.port.get()
        val accessMode = StreamState.accessMode.get()
        boundAuthentication = BoundAuthenticationSnapshot(accessMode, StreamState.accessToken.get())
        val host = if (boundAuthentication.requiresToken) "0.0.0.0" else "127.0.0.1"

        if (boundAuthentication.requiresToken) {
            AppLogger.w("Security", "LAN access mode ($accessMode) is active. Requiring token for all endpoints except /health.")
        }

        engine = embeddedServer(CIO, port = port, host = host) {
            install(CORS) {
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Options)

                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowHeader("X-OpenCamBridge-Token")

                // Only the OpenCamBridge desktop app needs cross-origin access.
                // The phone web UI and the /obs page are same-origin. Allowing any
                // host would let arbitrary websites script requests against the
                // camera server, so keep this list tight.
                ControlServerSecurityPolicy.corsEndpoints.forEach { endpoint ->
                    allowHost(endpoint.host, schemes = endpoint.schemes)
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                    val path = call.request.path()
                    val token = call.request.queryParameters["token"] ?: call.request.headers["X-OpenCamBridge-Token"]
                    if (!boundAuthentication.authorizes(path, token)) {
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

                get("/")                           { serveIndex(call) }
                get("/health")                     { call.respondText("OK") }
                get("/api/device/info")            { serveDeviceInfo(call) }
                get("/api/camera/list")            { serveCameraList(call) }
                get("/api/camera/status")          { serveCameraStatus(call) }
                get("/api/camera/controls")        { serveCameraControls(call) }
                get("/api/settings")               { serveGetSettings(call) }
                get("/api/state/events")           { serveStateEvents(call) }
                get("/api/logs")                   { serveGetLogs(call) }
                get("/api/camera/capabilities")    { serveCameraCapabilities(call) }
                get("/api/pipeline/capabilities")  { servePipelineCapabilities(call) }

                post("/api/stream/start")          { serveStreamStart(call) }
                post("/api/stream/stop")           { serveStreamStop(call) }
                post("/api/stream/recover")        { serveStreamRecover(call) }
                get("/api/stream/metrics")         { serveStreamMetrics(call) }
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
                get("/stream.ocb2")                { serveOcb2(call) }
                get("/stream.h264")                {
                    call.respondText("Raw H.264 was replaced by the framed /stream.ocb2 endpoint", status = HttpStatusCode.Gone)
                }
                get("/api/stream/info")            { serveStreamInfo(call) }
                get("/obs")                        { serveObs(call) }
            }
        }.start(wait = false)
        return true
    }

    @Synchronized
    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        engine = null
    }

    /**
     * True when the request originates from the device itself (phone UI) or an
     * adb-forwarded USB connection. Both appear as loopback on the phone.
     */
    private fun isLoopbackRequest(call: RoutingCall): Boolean = try {
        ControlServerSecurityPolicy.isLoopback(call.request.origin.remoteAddress)
    } catch (_: Throwable) { false }

    private suspend fun serveObs(call: RoutingCall) {
        val fit = call.request.queryParameters["fit"].takeIf { it == "contain" || it == "cover" } ?: "cover"
        val accessMode = StreamState.accessMode.get()
        val token = StreamState.accessToken.get()

        if (StreamState.activeStreamMode.get() == "h264") {
            call.respondText(ContentType.Text.Html) {
                """
                <!doctype html><html><head><meta charset="utf-8"><style>
                html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}
                canvas{width:100%;height:100%;object-fit:$fit;display:block}
                #error{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;color:#fff;background:#000;font:18px system-ui;text-align:center;padding:24px}
                </style></head><body><canvas id="stream"></canvas><div id="error">Connecting H.264 preview…</div><script>
                $ocb2BrowserParserSource
                const token = "$token", tokenRequired = "$accessMode" === "lanToken";
                const canvas = document.getElementById('stream'), errorBox = document.getElementById('error');
                let info={effectiveRotation:0,mirror:false}, configBytes=new Uint8Array(0), decoder=null, lastTs=-1;
                const fail = message => { errorBox.textContent=message; errorBox.style.display='flex'; };
                function codec(bytes){for(let i=0;i+7<bytes.length;i++){let s=0;if(bytes[i]===0&&bytes[i+1]===0&&bytes[i+2]===1)s=i+3;else if(bytes[i]===0&&bytes[i+1]===0&&bytes[i+2]===0&&bytes[i+3]===1)s=i+4;if(s&&(bytes[s]&31)===7)return'avc1.'+[bytes[s+1],bytes[s+2],bytes[s+3]].map(v=>v.toString(16).padStart(2,'0')).join('').toUpperCase()}return'avc1.42E01E'}
                function draw(frame){const r=Number(info.effectiveRotation||0),swap=r===90||r===270,w=frame.displayWidth||frame.codedWidth,h=frame.displayHeight||frame.codedHeight;canvas.width=swap?h:w;canvas.height=swap?w:h;const c=canvas.getContext('2d',{alpha:false,desynchronized:true});c.save();c.fillStyle='#000';c.fillRect(0,0,canvas.width,canvas.height);c.translate(canvas.width/2,canvas.height/2);c.rotate(r*Math.PI/180);c.scale(info.mirror?-1:1,1);c.drawImage(frame,-w/2,-h/2,w,h);c.restore();frame.close();errorBox.style.display='none'}
                async function configure(){if(decoder||!configBytes.length)return;const c={codec:codec(configBytes),codedWidth:Number(info.width||1280),codedHeight:Number(info.height||720),optimizeForLatency:true,hardwareAcceleration:'prefer-hardware'};const support=await VideoDecoder.isConfigSupported(c);if(!support.supported)throw new Error('Browser cannot decode '+c.codec);decoder=new VideoDecoder({output:draw,error:e=>fail('H.264 decoder failed: '+e.message)});decoder.configure(c)}
                async function run(){if(!('VideoDecoder'in window)||!('EncodedVideoChunk'in window)){fail('H.264 OBS preview unavailable: this browser lacks WebCodecs. Select MJPEG compatibility mode.');return}try{const u=new URL('/stream.ocb2',location.origin);if(tokenRequired&&token)u.searchParams.set('token',token);const response=await fetch(u);if(!response.ok||!response.body)throw new Error('OCB2 HTTP '+response.status);const reader=response.body.getReader(),parser=new Ocb2Browser.Parser();while(true){const n=await reader.read();if(n.done)throw new Error('stream ended');parser.push(n.value);for(let record;(record=parser.next())!==null;){const t=record.type,f=record.flags,ts=Number(record.encoderTimestampUs),p=record.payload;if(t===1){const next=JSON.parse(new TextDecoder().decode(p)),changed=!info.width||info.width!==next.width||info.height!==next.height||info.fpsNumerator!==next.fpsNumerator||info.fpsDenominator!==next.fpsDenominator;info=next;if(changed){if(decoder){decoder.close();decoder=null}configBytes=new Uint8Array(0);lastTs=-1}}else if(t===2&&(f&1)){configBytes=p;await configure()}else if(t===3){await configure();if(decoder){const key=!!(f&2);let d=p;if(key&&configBytes.length){d=new Uint8Array(configBytes.length+p.length);d.set(configBytes);d.set(p,configBytes.length)}const stamp=Math.max(lastTs+1,ts);lastTs=stamp;decoder.decode(new EncodedVideoChunk({type:key?'key':'delta',timestamp:stamp,data:d}))}}else if(t===5||(f&8))throw new Error('stream ended');else if(t===6)throw new Error(new TextDecoder().decode(p));}}}catch(e){fail('H.264 OBS preview unavailable: '+(e.message||String(e)))}}
                run();</script></body></html>
                """.trimIndent()
            }
            return
        }

        call.respondText(ContentType.Text.Html) {
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>OBS Clean Feed</title>
              <style>
                body, html {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    height: 100%;
                    background: #000;
                    overflow: hidden;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
                #stream {
                    width: 100%;
                    height: 100%;
                    object-fit: $fit;
                }
              </style>
            </head>
            <body>
              <img id="stream" src="/stream.mjpeg?obs=1">
              <script>
                const isTokenRequired = "$accessMode" === "lanToken";
                const token = "$token";
                const img = document.getElementById('stream');
                let url = '/stream.mjpeg?obs=1';
                if (isTokenRequired && token) {
                    url += '&token=' + token;
                }
                img.src = url;

                img.onerror = () => {
                    setTimeout(() => {
                        img.src = url + '&ts=' + new Date().getTime();
                    }, 1000);
                };
              </script>
            </body>
            </html>
            """.trimIndent()
        }
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

                .preview-section {
                    width: 100%;
                    min-width: 0;
                }
                .preview-stage {
                    width: 100%;
                    height: calc(100vh - 140px);
                    max-height: 80vh;
                    min-height: 360px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    overflow: hidden;
                    background: #1a1c23;
                    border-radius: 8px;
                    padding: 8px;
                    box-sizing: border-box;
                }
                .preview-box {
                    position: relative;
                    background: #000;
                    border: 1px solid #333;
                    border-radius: 8px;
                    overflow: hidden;
                }
                .preview-box.layout-landscape {
                    width: min(100%, calc(80vh * 16 / 9));
                    aspect-ratio: 16 / 9;
                }
                .preview-box.layout-portrait {
                    width: min(100%, calc(80vh * 9 / 16));
                    aspect-ratio: 9 / 16;
                }
                .preview-box.layout-square {
                    width: min(100%, 80vh);
                    aspect-ratio: 1 / 1;
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
                .stream-rotator {
                    position: absolute;
                    left: 50%;
                    top: 50%;
                    transform-origin: center center;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
                .stream-img, .stream-canvas {
                    width: 100%;
                    height: 100%;
                    display: block;
                }
                .stream-img.fit-contain, .stream-canvas.fit-contain {
                    object-fit: contain;
                }
                .stream-img.fit-cover, .stream-canvas.fit-cover {
                    object-fit: cover;
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
                  <div id="header-rebind-warning" class="rebind-warning">Rebinding camera...</div>
              </header>

              <main>
                  <!-- Left side: Preview -->
                  <div class="preview-section">
                      <div class="preview-stage">
                        <div id="preview-box" class="preview-box layout-landscape">
                            <div id="offline-overlay" class="offline-overlay">
                                <p id="preview-overlay-text">Camera is Offline</p>
                                <button class="refresh-btn" onclick="fetchStatus()">Refresh Stream</button>
                            </div>
                            <span id="preview-rebind-warning" style="display:none; position:absolute; top:16px; left:16px; background:rgba(255,165,0,0.8); color:#000; padding:4px 8px; border-radius:4px; font-size:0.8rem; z-index:20; font-weight:bold;">RECONFIGURING...</span>
                            <button class="manual-refresh-btn" onclick="reloadPreviewImage()">Reload Image</button>
                            <div id="stream-rotator" class="stream-rotator">
                                <img id="stream-img" class="stream-img fit-contain" src="" alt="Live Stream">
                                <canvas id="h264-canvas" class="stream-canvas fit-contain" style="display:none"></canvas>
                            </div>
                        </div>
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
                        <input type="checkbox" id="preview-check" onchange="patchSetting({phonePreviewEnabled: this.checked})">
                      </div>
                      <div id="preview-state" style="font-size:12px;color:#888;margin-top:6px;">Not requested</div>
                    </div>

                    <div class="controls">
                      <h3>Camera Config</h3>
                      <div class="control-group">
                        <label>Camera</label>
                        <select id="camera-select" onchange="selectCameraMode(this.value)"></select>
                      </div>
                      <div class="control-group">
                        <label>Resolution</label>
                        <select id="res-select" onchange="selectResolutionMode(this.value)"></select>
                      </div>

                      <div class="control-group">
                        <label>FPS Limit</label>
                        <select id="fps-select" onchange="patchSetting({fps: parseInt(this.value)})"></select>
                      </div>
                    </div>

                    <div class="controls">
                      <h3>Image Controls</h3>
                      <div class="control-group">
                        <label>Stream Mode</label>
                        <select id="sm-select" onchange="selectStreamMode(this.value)">
                           <option value="h264">Hardware H.264 / OCB2</option>
                           <option value="mjpeg">MJPEG Compatibility</option>
                        </select>
                      </div>
                      <div id="h264-info" style="display:none; font-size: 0.85rem; color: #aaa; margin-top: 8px;">
                          Framed H.264 stream active at: <span id="h264-link" style="color:var(--primary)">/stream.ocb2</span><br>
                          OCB2 carries complete access units and is consumed by the OpenCamBridge Windows producer.
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
                        <label>Focus</label>
                        <span style="font-size:12px;color:#888;text-align:right;">Continuous autofocus is automatic</span>
                      </div>
                    </div>

                    <div class="controls">
                      <h3>Output & Display</h3>
                      <div class="control-group">
                        <label>Fit Mode</label>
                        <select id="fit-select" onchange="patchSetting({previewFitMode: this.value})">
                           <option value="fill">Fill (Cover, no black bars)</option>
                           <option value="fit">Fit (Contain, full frame)</option>
                        </select>
                      </div>
                      <div class="control-group">
                        <label>Orientation</label>
                        <select id="orient-select" onchange="updateOrientation(this.value)">
                            <option value="auto">Auto (follow phone)</option>
                            <option value="16:9">Horizontal (16:9)</option>
                            <option value="9:16">Vertical (9:16)</option>
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
                $ocb2BrowserParserSource
                const TOKEN = "${'$'}{StreamState.accessToken.get()}";
                const isTokenRequired = "${'$'}{StreamState.accessMode.get()}" === "lanToken";

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

                let lastMode = 'fill', lastRot = 0, lastMirror = false, lastLayout = '16:9';

                function applyDisplaySettings(mode, displayRot, mirror, layout) {
                    if (mode !== undefined) lastMode = mode;
                    if (displayRot !== undefined) lastRot = displayRot;
                    if (mirror !== undefined) lastMirror = mirror;
                    if (layout !== undefined) lastLayout = layout;

                    const box = document.getElementById('preview-box');
                    const rotator = document.getElementById('stream-rotator');
                    const img = document.getElementById('stream-img');
                    const canvas = document.getElementById('h264-canvas');

                    box.classList.remove('layout-landscape', 'layout-portrait', 'layout-square');
                    if (lastLayout === '9:16') box.classList.add('layout-portrait');
                    else if (lastLayout === '1:1') box.classList.add('layout-square');
                    else box.classList.add('layout-landscape');

                    img.classList.remove('fit-contain', 'fit-cover');
                    img.classList.add(lastMode === 'fill' ? 'fit-cover' : 'fit-contain');
                    canvas.classList.remove('fit-contain', 'fit-cover');
                    canvas.classList.add(lastMode === 'fill' ? 'fit-cover' : 'fit-contain');

                    // Both MJPEG rotation and mirror are baked into the JPEG on
                    // Android. H.264 WebCodecs applies authoritative OCB2
                    // metadata in its canvas. This wrapper never transforms
                    // either source a second time.
                    const boxW = box.clientWidth;
                    const boxH = box.clientHeight;
                    rotator.style.width = boxW + 'px';
                    rotator.style.height = boxH + 'px';

                    rotator.style.transform = 'translate(-50%, -50%)';
                }

                function updateOrientation(mode) {
                    // Orientation mode is 'auto' | '16:9' | '9:16'. It controls
                    // the VIEW: content is auto-uprighted on the phone, and the
                    // preview box follows the phone (auto) or is pinned. Also
                    // clears any legacy manual rotation offset so old saved
                    // rotations cannot leave the stream sideways.
                    patchSetting({ aspectRatio: mode, displayRotation: '0' });
                }

                let currentRevision = 0;
                let currentLifecycleState = '';
                let currentStreamMode = 'h264';
                let isDraggingQuality = false;
                let isDraggingZoom = false;
                let cameraCapabilities = [];

                function updateModeOptions(status) {
                    const resSelect = document.getElementById('res-select');
                    const fpsSelect = document.getElementById('fps-select');
                    const camera = cameraCapabilities.find(c => c.id === status.cameraId);
                    const canonical = status.streamMode === 'h264' ? camera?.h264Modes : camera?.mjpegModes;
                    const modes = Array.isArray(canonical) ? canonical : [];
                    const seen = new Set();
                    const resolutions = modes.filter(m => {
                        const key = m.width + 'x' + m.height;
                        if (seen.has(key)) return false;
                        seen.add(key); return true;
                    }).map(m => [m.width, m.height]);
                    const rates = [...new Set(modes
                        .filter(m => m.width === status.width && m.height === status.height)
                        .map(m => m.fps))];
                    resSelect.innerHTML = '';
                    resolutions.forEach(r => {
                        const option = document.createElement('option');
                        option.value = r[0] + 'x' + r[1]; option.textContent = r[0] + ' x ' + r[1];
                        resSelect.appendChild(option);
                    });
                    fpsSelect.innerHTML = '';
                    rates.forEach(rate => {
                        const option = document.createElement('option');
                        option.value = String(rate); option.textContent = rate + ' fps';
                        fpsSelect.appendChild(option);
                    });
                }

                function canonicalModes(cameraId, streamMode) {
                    const camera = cameraCapabilities.find(c => c.id === cameraId);
                    const modes = streamMode === 'h264' ? camera?.h264Modes : camera?.mjpegModes;
                    return Array.isArray(modes) ? modes : [];
                }

                function selectCameraMode(cameraId) {
                    const mode = document.getElementById('sm-select').value || lastStatus.streamMode;
                    const modes = canonicalModes(cameraId, mode);
                    const selected = modes.find(m => m.width === lastStatus.width && m.height === lastStatus.height && m.fps === lastStatus.fps) || modes[0];
                    if (!selected) { showPreviewError('No supported ' + mode.toUpperCase() + ' modes on this camera'); return; }
                    patchSetting({ cameraId, width: selected.width, height: selected.height, fps: selected.fps });
                }

                function selectStreamMode(streamMode) {
                    const cameraId = document.getElementById('camera-select').value || lastStatus.cameraId;
                    const modes = canonicalModes(cameraId, streamMode);
                    const selected = modes.find(m => m.width === lastStatus.width && m.height === lastStatus.height && m.fps === lastStatus.fps) || modes[0];
                    if (!selected) { showPreviewError('No supported ' + streamMode.toUpperCase() + ' modes on this camera'); return; }
                    patchSetting({ streamMode, width: selected.width, height: selected.height, fps: selected.fps });
                }

                function selectResolutionMode(value) {
                    const parts = value.split('x').map(Number), width = parts[0], height = parts[1];
                    const cameraId = document.getElementById('camera-select').value || lastStatus.cameraId;
                    const streamMode = document.getElementById('sm-select').value || lastStatus.streamMode;
                    const modes = canonicalModes(cameraId, streamMode).filter(m => m.width === width && m.height === height);
                    const selected = modes.find(m => m.fps === lastStatus.fps) || modes[0];
                    if (!selected) return;
                    patchSetting({ width, height, fps: selected.fps });
                }

                let h264Abort = null;
                let h264Decoder = null;
                let h264PreviewRunning = false;
                let h264Info = { effectiveRotation: 0, mirror: false };
                let h264Config = new Uint8Array(0);
                let h264LastTimestamp = -1;

                function stopH264Preview() {
                    if (h264Abort) h264Abort.abort();
                    h264Abort = null;
                    h264PreviewRunning = false;
                    if (h264Decoder) { try { h264Decoder.close(); } catch (_) {} }
                    h264Decoder = null;
                    h264Config = new Uint8Array(0);
                    h264LastTimestamp = -1;
                }

                function showPreviewError(message) {
                    document.getElementById('preview-overlay-text').textContent = message;
                    document.getElementById('offline-overlay').style.display = 'flex';
                }

                function codecFromAnnexB(bytes) {
                    for (let i = 0; i + 7 < bytes.length; i++) {
                        let start = 0;
                        if (bytes[i] === 0 && bytes[i+1] === 0 && bytes[i+2] === 1) start = i + 3;
                        else if (bytes[i] === 0 && bytes[i+1] === 0 && bytes[i+2] === 0 && bytes[i+3] === 1) start = i + 4;
                        if (start && (bytes[start] & 31) === 7 && start + 3 < bytes.length) {
                            return 'avc1.' + [bytes[start+1], bytes[start+2], bytes[start+3]]
                                .map(v => v.toString(16).padStart(2, '0')).join('').toUpperCase();
                        }
                    }
                    return 'avc1.42E01E';
                }

                function renderH264Frame(frame) {
                    const canvas = document.getElementById('h264-canvas');
                    const img = document.getElementById('stream-img');
                    const rotation = Number(h264Info.effectiveRotation || 0);
                    const swap = rotation === 90 || rotation === 270;
                    const width = frame.displayWidth || frame.codedWidth;
                    const height = frame.displayHeight || frame.codedHeight;
                    canvas.width = swap ? height : width;
                    canvas.height = swap ? width : height;
                    const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
                    ctx.save();
                    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, canvas.width, canvas.height);
                    ctx.translate(canvas.width / 2, canvas.height / 2);
                    ctx.rotate(rotation * Math.PI / 180);
                    ctx.scale(h264Info.mirror ? -1 : 1, 1);
                    ctx.drawImage(frame, -width / 2, -height / 2, width, height);
                    ctx.restore();
                    frame.close();
                    img.style.display = 'none';
                    canvas.style.display = 'block';
                    document.getElementById('offline-overlay').style.display = 'none';
                }

                async function configureH264Decoder() {
                    if (!h264Config.length || h264Decoder) return;
                    const config = {
                        codec: codecFromAnnexB(h264Config),
                        codedWidth: Number(h264Info.width || 1280),
                        codedHeight: Number(h264Info.height || 720),
                        optimizeForLatency: true,
                        hardwareAcceleration: 'prefer-hardware'
                    };
                    const support = await VideoDecoder.isConfigSupported(config);
                    if (!support.supported) throw new Error('Browser WebCodecs cannot decode ' + config.codec);
                    h264Decoder = new VideoDecoder({
                        output: renderH264Frame,
                        error: error => showPreviewError('H.264 preview decoder failed: ' + error.message)
                    });
                    h264Decoder.configure(config);
                }

                async function startH264Preview(force) {
                    if (!('VideoDecoder' in window) || !('EncodedVideoChunk' in window)) {
                        showPreviewError('H.264 preview unavailable: this browser does not support WebCodecs. Use Edge/Chrome or MJPEG compatibility mode.');
                        return;
                    }
                    if (h264PreviewRunning && !force) return;
                    stopH264Preview();
                    h264PreviewRunning = true;
                    h264Abort = new AbortController();
                    document.getElementById('stream-img').style.display = 'none';
                    document.getElementById('h264-canvas').style.display = 'block';
                    showPreviewError('Connecting H.264 WebCodecs preview…');
                    try {
                        const response = await fetchWithAuth('/stream.ocb2', { signal: h264Abort.signal });
                        if (!response.ok || !response.body) throw new Error('OCB2 HTTP ' + response.status);
                        const reader = response.body.getReader();
                        const parser = new Ocb2Browser.Parser();
                        while (h264PreviewRunning) {
                            const result = await reader.read();
                            if (result.done) throw new Error('OCB2 preview stream ended');
                            parser.push(result.value);
                            for (let record; (record = parser.next()) !== null;) {
                                const type = record.type;
                                const flags = record.flags;
                                const encoderTimestamp = Number(record.encoderTimestampUs);
                                const payload = record.payload;
                                if (type === 1) {
                                    const nextInfo = JSON.parse(new TextDecoder().decode(payload));
                                    const decodeFormatChanged = !h264Info.width
                                        || h264Info.width !== nextInfo.width
                                        || h264Info.height !== nextInfo.height
                                        || h264Info.fpsNumerator !== nextInfo.fpsNumerator
                                        || h264Info.fpsDenominator !== nextInfo.fpsDenominator;
                                    h264Info = nextInfo;
                                    // Rotation/mirror updates are canvas transforms. Preserve
                                    // WebCodecs and timestamp continuity unless the coded format
                                    // itself changed.
                                    if (decodeFormatChanged) {
                                        if (h264Decoder) { h264Decoder.close(); h264Decoder = null; }
                                        h264Config = new Uint8Array(0); h264LastTimestamp = -1;
                                    }
                                } else if (type === 2 && (flags & 1)) {
                                    h264Config = payload;
                                    await configureH264Decoder();
                                } else if (type === 3) {
                                    await configureH264Decoder();
                                    if (h264Decoder) {
                                        const key = (flags & 2) !== 0;
                                        let data = payload;
                                        if (key && h264Config.length) {
                                            data = new Uint8Array(h264Config.length + payload.length);
                                            data.set(h264Config); data.set(payload, h264Config.length);
                                        }
                                        const timestamp = Math.max(h264LastTimestamp + 1, encoderTimestamp);
                                        h264LastTimestamp = timestamp;
                                        h264Decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp, data }));
                                    }
                                } else if (type === 5 || (flags & 8)) {
                                    throw new Error('Phone ended the H.264 preview stream');
                                } else if (type === 6) {
                                    throw new Error(new TextDecoder().decode(payload));
                                }
                            }
                        }
                    } catch (error) {
                        if (error.name !== 'AbortError') {
                            showPreviewError('H.264 preview unavailable: ' + (error.message || String(error)));
                            h264PreviewRunning = false;
                        }
                    }
                }

                function reloadPreviewImage() {
                    if (currentStreamMode === 'h264') { startH264Preview(true); return; }
                    stopH264Preview();
                    document.getElementById('h264-canvas').style.display = 'none';
                    const img = document.getElementById('stream-img');
                    img.style.display = 'block';
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
                        currentStreamMode = status.activeStreamMode || status.streamMode || 'h264';
                        currentRevision = status.revision;

                        // Auto-reconnect preview if rebind finished successfully
                        if (previousLifecycle !== 'STREAMING' && currentLifecycleState === 'STREAMING') {
                            if (currentStreamMode === 'mjpeg') {
                                reloadPreviewImage();
                            } else {
                                startH264Preview(false);
                            }
                        }

                        document.getElementById('status-panel').innerText = JSON.stringify(status, null, 2);
                        document.getElementById('sec-mode').innerText = status.accessMode;
                        document.getElementById('sec-port').innerText = status.port;

                        updateModeOptions(status);
                        document.getElementById('res-select').value = status.width + 'x' + status.height;
                        document.getElementById('fps-select').value = status.fps;
                        document.getElementById('fit-select').value = status.previewFitMode;
                        document.getElementById('zs-select').value = status.zoomSpeed || 'normal';
                        document.getElementById('sm-select').value = status.streamMode || 'h264';

                        const orientSel = document.getElementById('orient-select');
                        if (orientSel) {
                            const ar = status.aspectRatio;
                            orientSel.value = (ar === '9:16' || ar === '16:9') ? ar : 'auto';
                        }
                        document.getElementById('mirror-check').checked = !!status.mirror;
                        document.getElementById('preview-check').checked = !!status.phonePreviewRequested;
                        const previewState = document.getElementById('preview-state');
                        if (previewState) {
                            previewState.textContent = !status.phonePreviewRequested ? 'Not requested'
                                : status.phonePreviewActive ? 'Active'
                                : 'Inactive: ' + (status.phonePreviewFailureReason || 'waiting for a valid target/session');
                            previewState.style.color = status.phonePreviewRequested && !status.phonePreviewActive ? '#fbc02d' : '#888';
                        }

                        if (currentStreamMode === 'h264') {
                            document.getElementById('h264-info').style.display = 'block';
                            let h264Url = new URL('/stream.ocb2', window.location.origin);
                            if (isTokenRequired && TOKEN) h264Url.searchParams.set('token', TOKEN);
                            document.getElementById('h264-link').textContent = h264Url.toString();
                        } else {
                            document.getElementById('h264-info').style.display = 'none';
                        }

                        if (!isDraggingQuality) {
                            document.getElementById('quality-slider').value = status.jpegQuality;
                            document.getElementById('quality-val').innerText = status.jpegQuality;
                        }

                        // 'auto' orientation: the view follows the actual frame
                        // the phone is streaming right now (portrait when held
                        // vertical). Forced modes pin the box.
                        let layout = status.aspectRatio || 'auto';
                        if (layout !== '9:16' && layout !== '16:9') {
                            const fp = (status.encodedHeight || 0) > (status.encodedWidth || 0);
                            layout = fp ? '9:16' : '16:9';
                        }

                        applyDisplaySettings(status.previewFitMode, 0, !!status.mirror, layout);

                        if(status.rebindInProgress) {
                            document.getElementById('header-rebind-warning').style.display = 'inline';
                            document.getElementById('preview-rebind-warning').style.display = 'inline';
                        } else {
                            document.getElementById('header-rebind-warning').style.display = 'none';
                            document.getElementById('preview-rebind-warning').style.display = 'none';
                        }

                        if (currentLifecycleState !== 'STREAMING' && currentLifecycleState !== 'RECONFIGURING' && currentLifecycleState !== 'RECOVERING') {
                            document.getElementById('preview-overlay-text').textContent = 'Camera is Offline';
                            document.getElementById('offline-overlay').style.display = 'flex';
                            document.getElementById('stream-img').style.opacity = '0.3';
                        } else if (currentStreamMode === 'mjpeg') {
                            stopH264Preview();
                            document.getElementById('h264-canvas').style.display = 'none';
                            document.getElementById('stream-img').style.display = 'block';
                            document.getElementById('offline-overlay').style.display = 'none';
                            document.getElementById('stream-img').style.opacity = '1';
                        } else {
                            startH264Preview(false);
                            if (!document.getElementById('h264-canvas').width) {
                                showPreviewError('Connecting H.264 WebCodecs preview…');
                            }
                        }

                        const camSelect = document.getElementById('camera-select');
                        if(camSelect.options.length > 0) camSelect.value = status.cameraId;
                    } catch (e) {
                        console.error('Status fetch error', e);
                    }
                }

                async function fetchCameras() {
                    try {
                        const res = await fetchWithAuth('/api/pipeline/capabilities');
                        const capability = await res.json();
                        const cameras = capability.cameras || [];
                        cameraCapabilities = cameras;
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
                        if (!isDraggingZoom) {
                            document.getElementById('zoom-slider').value = controls.linearZoom * 100;
                            document.getElementById('zoom-val').innerText = controls.linearZoom.toFixed(2);
                        }
                    } catch (e) { console.error('Failed to load controls', e); }
                }

                async function patchSetting(payload) {
                    payload.baseRevision = currentRevision;
                    payload.requestId = (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString());
                    payload.clientType = 'web';
                    try {
                        const response = await fetchWithAuth('/api/settings', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify(payload)
                        });
                        if (!response.ok) {
                            const result = await response.json().catch(() => ({}));
                            if (result.authoritativeState) {
                                currentRevision = Number(result.authoritativeState.revision || currentRevision);
                                lastStatus = result.authoritativeState;
                                updateModeOptions(result.authoritativeState);
                            }
                            fetchStatus();
                            throw new Error(result.message || ('HTTP ' + response.status));
                        }
                        const result = await response.json().catch(() => ({}));
                        currentRevision = Number(result.revision || currentRevision);
                        fetchStatus();
                    } catch (e) { console.error('Update err', e); }
                }

                async function updateZoom() {
                    const val = parseInt(document.getElementById('zoom-slider').value) / 100.0;
                    const response = await fetchWithAuth('/api/camera/zoom', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ linearZoom: val, baseRevision: currentRevision,
                            requestId: (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString()), clientType: 'web' })
                    });
                    const result = await response.json().catch(() => ({}));
                    currentRevision = Number(result.revision ?? result.authoritativeState?.revision ?? currentRevision);
                    if (!response.ok) throw new Error(result.message || ('HTTP ' + response.status));
                    fetchControls();
                }

                async function updateTorch() {
                    const enabled = document.getElementById('torch-check').checked;
                    const response = await fetchWithAuth('/api/camera/torch', {
                        method: 'POST', headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({ enabled, baseRevision: currentRevision,
                            requestId: (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString()), clientType: 'web' })
                    });
                    const result = await response.json().catch(() => ({}));
                    currentRevision = Number(result.revision ?? result.authoritativeState?.revision ?? currentRevision);
                    if (!response.ok) throw new Error(result.message || ('HTTP ' + response.status));
                    fetchControls();
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
                        if (currentLifecycleState === 'STREAMING' && currentStreamMode === 'mjpeg') {
                            setTimeout(reloadPreviewImage, 1000);
                        }
                    };
                    img.onload = () => applyDisplaySettings();
                    window.addEventListener('resize', () => applyDisplaySettings());

                    reloadPreviewImage();

                    await fetchCameras();
                    await fetchStatus();
                    await fetchControls();
                    const stateUrl = new URL('/api/state/events', window.location.origin);
                    if (isTokenRequired && TOKEN) stateUrl.searchParams.set('token', TOKEN);
                    const stateEvents = new EventSource(stateUrl);
                    stateEvents.addEventListener('state', event => {
                        try {
                            const status = JSON.parse(event.data);
                            currentRevision = status.revision || currentRevision;
                        } catch (_) {}
                        fetchStatus();
                    });
                    // Controls are hardware observations, not settings state.
                    // Poll them slowly; authoritative settings arrive by SSE.
                    setInterval(() => { fetchControls(); }, 2000);
                };
              </script>
            </body>
            </html>
            """.trimIndent()
        }
    }
    private suspend fun serveDeviceInfo(call: RoutingCall) {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        call.respond(DeviceInfoDto(app = "OpenCamBridge", version = "2.0.0", platform = "android", serverPort = StreamState.port.get(),
            manufacturer = android.os.Build.MANUFACTURER, model = android.os.Build.MODEL,
            batteryOptimizationExempt = power.isIgnoringBatteryOptimizations(context.packageName)))
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

    private suspend fun serveStateEvents(call: RoutingCall) {
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            var lastRevision = Long.MIN_VALUE
            var lastGeneration = Long.MIN_VALUE
            var lastLifecycle = ""
            while (currentCoroutineContext().isActive) {
                val status = StreamState.toStatusDto()
                if (status.revision != lastRevision || status.pipelineGeneration != lastGeneration ||
                    status.lifecycleState != lastLifecycle
                ) {
                    write("event: state\n")
                    write("data: ${Json.encodeToString(status)}\n\n")
                    flush()
                    lastRevision = status.revision
                    lastGeneration = status.pipelineGeneration
                    lastLifecycle = status.lifecycleState
                }
                delay(250)
            }
        }
    }

    private suspend fun serveCameraCapabilities(call: RoutingCall) {
        // Unified capability model: this endpoint now returns the SAME canonical
        // CameraInfoDto list as /api/camera/list (torch, lens type, per-resolution
        // max FPS, high-speed diagnostics). Previously it returned a separate,
        // thinner CameraCapabilityDto — a duplicate that could drift from the
        // real model. Kept as an alias so existing callers keep working.
        try {
            val cameras = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cameraRepo.listCameras()
            }
            call.respond(cameras)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, SimpleResult(false, "Error: ${e.message}"))
        }
    }

    private suspend fun serveCameraControls(call: RoutingCall) {
        call.respond(
            CameraControlsDto(
                hasTorch = StreamState.hasTorch.get(),
                torchEnabled = StreamState.torchEnabled.get(),
                autofocusSupported = false,
                autofocusEnabled = true,
                zoomRatio = StreamState.zoomRatio.get(),
                linearZoom = StreamState.linearZoom.get()
            )
        )
    }

    private suspend fun serveStreamStart(call: RoutingCall) {
        respondPipelineResult(call, onStartCamera())
    }

    private suspend fun servePipelineCapabilities(call: RoutingCall) {
        try {
            val cameras = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cameraRepo.listCameras()
            }
            call.respond(
                PipelineCapabilitiesDto(
                    revision = StreamState.revision.get(),
                    cameras = cameras,
                    adaptivePreference = listOf(
                        H264ModeDto(1920, 1080, 60),
                        H264ModeDto(1280, 720, 60),
                        H264ModeDto(1920, 1080, 30),
                        H264ModeDto(1280, 720, 30)
                    ),
                    compatibilityFallback = "mjpeg",
                    windowsDecoderRequirement = "Media Foundation hardware AVC decoder availability is reported by the desktop producer"
                )
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, SimpleResult(false, "Capability query failed: ${e.message}"))
        }
    }

    private suspend fun serveStreamStop(call: RoutingCall) {
        respondPipelineResult(call, onStopCamera())
    }

    private suspend fun serveStreamRecover(call: RoutingCall) {
        respondPipelineResult(call, onRecoverCamera())
    }

    private suspend fun serveCameraSwitch(call: RoutingCall) {
        val req = try { call.receive<CameraSwitchRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON: ${e.message}"))
            return
        }
        respondPipelineResult(call, onApplySettingsPatch(UpdateSettingsRequest(baseRevision = req.baseRevision, requestId = req.requestId, clientType = req.clientType, cameraId = req.cameraId), req.clientType))
    }

    private suspend fun serveSetResolution(call: RoutingCall) {
        val req = try { call.receive<ResolutionRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        respondPipelineResult(call, onApplySettingsPatch(UpdateSettingsRequest(baseRevision = req.baseRevision, requestId = req.requestId, clientType = req.clientType, width = req.width, height = req.height), req.clientType))
    }

    private suspend fun serveSetFps(call: RoutingCall) {
        val req = try { call.receive<FpsRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        respondPipelineResult(call, onApplySettingsPatch(UpdateSettingsRequest(baseRevision = req.baseRevision, requestId = req.requestId, clientType = req.clientType, fps = req.fps), req.clientType))
    }

    private suspend fun serveSetJpegQuality(call: RoutingCall) {
        val req = try { call.receive<JpegQualityRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        val quality = req.quality.coerceIn(1, 100)
        respondPipelineResult(call, onApplySettingsPatch(UpdateSettingsRequest(baseRevision = req.baseRevision, requestId = req.requestId, clientType = req.clientType, jpegQuality = quality), req.clientType))
    }

    private suspend fun serveSetPreviewFitMode(call: RoutingCall) {
        val req = try { call.receive<PreviewFitModeRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        respondPipelineResult(call, onApplySettingsPatch(UpdateSettingsRequest(baseRevision = req.baseRevision, requestId = req.requestId, clientType = req.clientType, previewFitMode = req.previewFitMode), req.clientType))
    }

    private suspend fun serveSetAspectRatio(call: RoutingCall) {
        val req = try { call.receive<AspectRatioRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        respondPipelineResult(call, onApplySettingsPatch(UpdateSettingsRequest(baseRevision = req.baseRevision, requestId = req.requestId, clientType = req.clientType, aspectRatio = req.aspectRatio), req.clientType))
    }

    private suspend fun serveUpdateSettings(call: RoutingCall) {
        var req = try { call.receive<UpdateSettingsRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }

        // Security-critical settings (access mode, port, token) may only be changed
        // from the device itself or over USB (loopback). A LAN client - even one
        // holding a valid token - must not be able to weaken authentication or move
        // the server port.
        val touchesSecurity = req.accessMode != null || req.port != null || req.accessToken != null
        var securityFieldsStripped = false
        if (touchesSecurity && !isLoopbackRequest(call)) {
            AppLogger.w("Security", "Ignoring security setting change from non-loopback client")
            req = req.copy(accessMode = null, port = null, accessToken = null)
            securityFieldsStripped = true
        }

        // Delegate patch application to the central stream controller. Guard it
        // so a failure in one setting can never turn a control action into an
        // HTTP 500 (which the UI shows as a scary "action failed").
        val result = try {
            onApplySettingsPatch(req, req.clientType ?: "api").also { result ->
            if (!result.success) {
                respondPipelineResult(call, result)
                return
            }
            }
        } catch (e: Exception) {
            AppLogger.e("System", "Settings apply failed: ${e.javaClass.simpleName}: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, SimpleResult(false, "Settings failed to apply: ${e.message}"))
            return
        }

        val message = if (securityFieldsStripped) {
            "Settings updated. Security settings (accessMode/port/accessToken) were ignored: they can only be changed from the phone or over USB."
        } else {
            "Settings updated."
        }
        respondPipelineResult(call, result.copy(message = message))
    }

    private suspend fun respondPipelineResult(call: RoutingCall, result: PipelineResult) {
        val status = when (result.code) {
            PipelineResultCode.OK -> HttpStatusCode.OK
            PipelineResultCode.CONFLICT -> HttpStatusCode.Conflict
            PipelineResultCode.UNPROCESSABLE -> HttpStatusCode.UnprocessableEntity
            PipelineResultCode.CANCELLED -> HttpStatusCode.Conflict
            PipelineResultCode.FAILED -> HttpStatusCode.InternalServerError
        }
        call.respond(
            status,
            PipelineResultDto(
                success = result.success,
                message = result.message,
                revision = result.revision,
                generation = result.generation,
                lifecycleState = result.lifecycleState,
                code = result.code.name,
                error = when (result.code) {
                    PipelineResultCode.OK -> null
                    PipelineResultCode.CONFLICT -> "Revision conflict"
                    PipelineResultCode.UNPROCESSABLE -> "Unsupported mode"
                    PipelineResultCode.CANCELLED -> "Command superseded"
                    PipelineResultCode.FAILED -> "Pipeline failure"
                },
                requested = result.requested,
                alternatives = result.alternatives,
                authoritativeState = StreamState.toStatusDto()
            )
        )
    }

    // ---- Controls ----

    private suspend fun serveSetZoom(call: RoutingCall) {
        val req = try { call.receive<ZoomRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        if (req.baseRevision == null || req.requestId.isNullOrBlank() || req.clientType.isNullOrBlank()) {
            call.respond(HttpStatusCode.Conflict, SimpleResult(false, "baseRevision, requestId and clientType are required"))
            return
        }
        if (req.linearZoom != null) {
            respondPipelineResult(call, onSetLinearZoom(req.linearZoom.coerceIn(0f, 1f), req.baseRevision, req.requestId, req.clientType))
        } else if (req.zoomRatio != null) {
            respondPipelineResult(call, onSetZoomRatio(req.zoomRatio, req.baseRevision, req.requestId, req.clientType))
        } else {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Require zoomRatio or linearZoom"))
        }
    }

    private suspend fun serveSetTorch(call: RoutingCall) {
        val req = try { call.receive<TorchRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        if (req.baseRevision == null || req.requestId.isNullOrBlank() || req.clientType.isNullOrBlank()) {
            call.respond(HttpStatusCode.Conflict, SimpleResult(false, "baseRevision, requestId and clientType are required"))
            return
        }
        respondPipelineResult(call, onSetTorch(req.enabled, req.baseRevision, req.requestId, req.clientType))
    }

    private suspend fun serveSetAutofocus(call: RoutingCall) {
        val req = try { call.receive<AutofocusRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        call.respond(
            HttpStatusCode.UnprocessableEntity,
            SimpleResult(false, "Continuous autofocus is automatic; manual autofocus switching is not supported")
        )
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
        var lastRevision = -1L
        var lastSentAtMs = 0L
        // Client accounting lets the camera side skip JPEG work when nobody
        // is watching, and surfaces "who is connected" in the status DTO.
        StreamState.mjpegClientCount.incrementAndGet()
        try {
            while (currentCoroutineContext().isActive) {
                // Throttle based on requested FPS
                val targetFps = StreamState.fps.get().coerceIn(1, 120)
                val minIntervalMs = 1000L / targetFps

                // Survive transient states so clients don't have
                // to reconnect on every settings change; only end the stream when the
                // camera is actually going away.
                val lifecycle = StreamState.lifecycleState.get()
                if (lifecycle == com.opencambridge.android.state.LifecycleState.STOPPING ||
                    lifecycle == com.opencambridge.android.state.LifecycleState.STOPPED ||
                    lifecycle == com.opencambridge.android.state.LifecycleState.FAILED
                ) break

                val currentRev = StreamState.latestFrameRevision.get()
                if (currentRev == lastRevision) {
                    delay(5)
                    continue
                }

                val frame = StreamState.latestFrame.get()
                if (frame != null && !StreamState.rebindInProgress.get()) {
                    // Pace output to the requested FPS even if the camera produces faster.
                    val now = System.currentTimeMillis()
                    val sinceLast = now - lastSentAtMs
                    if (sinceLast < minIntervalMs) {
                        delay(minIntervalMs - sinceLast)
                    }

                    lastRevision = currentRev
                    val headerStr = "--$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    val headerBytes = headerStr.toByteArray(Charsets.US_ASCII)
                    writeFully(headerBytes)
                    writeFully(frame)
                    writeFully("\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                    lastSentAtMs = System.currentTimeMillis()

                    StreamState.bytesSentThisSecond.addAndGet((headerBytes.size + frame.size + 2).toLong())
                } else {
                    delay(minIntervalMs)
                }
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            StreamState.mjpegClientCount.decrementAndGet()
        }
    }

    private suspend fun serveOcb2(call: RoutingCall) {
        call.response.headers.append("Cache-Control", "no-cache")
        call.response.headers.append("Connection", "close")
        call.respondBytesWriter(
            contentType = ContentType.parse("application/vnd.opencambridge.ocb2")
        ) {
            val channel = h264Streamer.subscribe()
            try {
                for (frame in channel) {
                    val lifecycle = StreamState.lifecycleState.get()
                    if (!currentCoroutineContext().isActive ||
                        lifecycle == com.opencambridge.android.state.LifecycleState.STOPPING ||
                        lifecycle == com.opencambridge.android.state.LifecycleState.STOPPED ||
                        lifecycle == com.opencambridge.android.state.LifecycleState.FAILED
                    ) break
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
        val status = StreamState.toStatusDto()
        val snapshot = status.snapshot
        val selected = snapshot.selected
        val actual = snapshot.actual
        val activeMode = selected?.streamMode ?: status.activeStreamMode
        val activeWidth = actual?.width ?: selected?.width ?: snapshot.desired.width
        val activeHeight = actual?.height ?: selected?.height ?: snapshot.desired.height
        val activeFps = actual?.encodedFps?.takeIf { it > 0 }
            ?: selected?.fps
            ?: snapshot.desired.fps
        call.respond(
            StreamInfoDto(
                mode = activeMode,
                resolution = "${activeWidth}x${activeHeight}",
                fps = activeFps,
                h264Bitrate = snapshot.desired.h264Bitrate,
                desired = StreamModeInfoDto(
                    mode = snapshot.desired.streamMode,
                    width = snapshot.desired.width,
                    height = snapshot.desired.height,
                    fps = snapshot.desired.fps
                ),
                selected = selected?.let {
                    StreamModeInfoDto(it.streamMode, it.width, it.height, it.fps)
                },
                actual = actual?.let {
                    StreamActualInfoDto(
                        mode = activeMode,
                        width = it.width,
                        height = it.height,
                        fps = it.encodedFps.takeIf { fps -> fps > 0 } ?: it.captureFps,
                        captureFps = it.captureFps,
                        encodedFps = it.encodedFps
                    )
                },
                // Honest transport metadata so consumers do not have to guess.
                codec = if (activeMode == "h264") "h264-annexb-access-units" else "mjpeg",
                container = if (activeMode == "h264") "OCB2 framed records" else "multipart/x-mixed-replace",
                experimental = false,
                notes = if (activeMode == "h264")
                    "Each /stream.ocb2 video record contains one complete H.264 access unit with sequence and monotonic capture/encoder timestamps."
                else
                    "Active compatibility path. Each part is a complete JPEG image."
            )
        )
    }

    private suspend fun serveStreamMetrics(call: RoutingCall) {
        val status = StreamState.toStatusDto()
        val active = status.lifecycleState == "STREAMING"
        val selected = status.snapshot.selected
        val actual = status.snapshot.actual
        val mode = selected?.streamMode ?: status.activeStreamMode
        val desired = status.snapshot.desired
        call.respond(
            PipelineMetricsDto(
                generation = status.snapshot.generation,
                lifecycleState = status.lifecycleState,
                activeStreamMode = mode,
                phonePreviewRequested = status.phonePreviewRequested,
                phonePreviewActive = status.phonePreviewActive,
                phonePreviewFailureReason = status.phonePreviewFailureReason,
                capture = if (!active || selected == null || actual == null) null else CaptureMetricsDto(
                    engine = selected.captureEngine,
                    cameraId = selected.cameraId,
                    requestedWidth = desired.width,
                    requestedHeight = desired.height,
                    actualWidth = actual.width,
                    actualHeight = actual.height,
                    requestedFps = desired.fps,
                    selectedFps = selected.fps,
                    cameraSessionFps = status.cameraSessionFps.takeIf { it > 0 } ?: actual.captureFps,
                    actualFps = actual.captureFps,
                    gpuBridgeFps = StreamState.gpuBridgeFps.get().takeIf { mode == "h264" && selected.captureEngine == "HIGH_SPEED_GPU_BRIDGE" }
                ),
                h264 = if (!active || mode != "h264") null else H264MetricsDto(
                    encoderName = selected?.encoderName.orEmpty(),
                    hardwareEncoder = selected?.hardwareEncoder == true,
                    encodedFps = actual?.encodedFps ?: 0,
                    bitrate = actual?.encodedBitrate ?: 0,
                    clientCount = StreamState.h264ClientCount.get(),
                    rejectedCapturePaths = StreamState.capturePathError.get().ifBlank { null }
                ),
                mjpeg = if (!active || mode != "mjpeg") null else MjpegMetricsDto(
                    encodedFps = actual?.encodedFps ?: 0,
                    encodeMs = StreamState.androidEncodeMsAvg.get(),
                    yuvMs = StreamState.yuvMsAvg.get(),
                    jpegMs = StreamState.jpegMsAvg.get(),
                    rotateMs = StreamState.rotateMsAvg.get(),
                    processingCapacityFps = run {
                        val theoretical = StreamState.androidEncodeMsAvg.get()
                            .takeIf { it > 0.0 }
                            ?.let { kotlin.math.floor(1000.0 / it).toInt() }
                            ?: 0
                        val measured = actual?.encodedFps ?: 0
                        listOf(theoretical, measured).filter { it > 0 }.minOrNull() ?: 0
                    },
                    latestFrameRevision = StreamState.latestFrameRevision.get(),
                    clientCount = StreamState.mjpegClientCount.get()
                ),
                transport = TransportMetricsDto(
                    estimatedMbps = StreamState.estimatedMbps.get(),
                    targetBandwidthMbps = status.targetBandwidthMbps
                ),
                selection = SelectionMetricsDto(
                    requestedAspectRatio = StreamState.requestedAspectRatio.get(),
                    selectedAspectRatio = StreamState.selectedAspectRatio.get(),
                    aspectRatioMatch = StreamState.aspectRatioMatch.get(),
                    resizeNeeded = StreamState.resizeNeeded.get(),
                    selectedRawWidth = selected?.width ?: 0,
                    selectedRawHeight = selected?.height ?: 0,
                    selectedEffectiveWidth = selected?.width ?: 0,
                    selectedEffectiveHeight = selected?.height ?: 0,
                    resolutionPolicy = StreamState.resolutionPolicy.get()
                ),
                fallback = status.snapshot.fallback?.let { FallbackMetricsDto(it.active, it.reason) }
            )
        )
    }
}

// ---- DTOs ----

@Serializable
private data class DeviceInfoDto(
    val app: String,
    val version: String,
    val platform: String,
    val serverPort: Int,
    val manufacturer: String,
    val model: String,
    val batteryOptimizationExempt: Boolean
)

@Serializable
private data class PipelineCapabilitiesDto(
    val revision: Long,
    val cameras: List<CameraInfoDto>,
    val adaptivePreference: List<H264ModeDto>,
    val compatibilityFallback: String,
    val windowsDecoderRequirement: String
)

@Serializable
data class SimpleResult(val success: Boolean, val message: String)

@Serializable
data class PipelineResultDto(
    val success: Boolean,
    val message: String,
    val revision: Long,
    val generation: Long,
    val lifecycleState: String,
    val code: String,
    val error: String?,
    val requested: String?,
    val alternatives: List<String>,
    /** Returned for success, 409, 422, and failure so clients can immediately
     * replace local state with the authoritative desired/selected/actual view. */
    val authoritativeState: StreamStatusDto
)

@Serializable
data class CameraCapabilityDto(
    val id: String,
    val facing: String,
    val label: String,
    val minZoom: Float,
    val maxZoom: Float,
    val sensorRotation: Int
)

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
private data class CameraSwitchRequest(val cameraId: String, val baseRevision: Long? = null, val requestId: String? = null, val clientType: String? = null)

@Serializable
private data class ResolutionRequest(val width: Int, val height: Int, val baseRevision: Long? = null, val requestId: String? = null, val clientType: String? = null)

@Serializable
private data class FpsRequest(val fps: Int, val baseRevision: Long? = null, val requestId: String? = null, val clientType: String? = null)

@Serializable
private data class JpegQualityRequest(val quality: Int, val baseRevision: Long? = null, val requestId: String? = null, val clientType: String? = null)

@Serializable
private data class PreviewFitModeRequest(val previewFitMode: String, val baseRevision: Long? = null, val requestId: String? = null, val clientType: String? = null)

@Serializable
private data class AspectRatioRequest(val aspectRatio: String, val baseRevision: Long? = null, val requestId: String? = null, val clientType: String? = null)

@Serializable
data class UpdateSettingsRequest(
    val baseRevision: Long? = null,
    val requestId: String? = null,
    val clientType: String? = null,
    val cameraId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
    val profile: String? = null,
    val fps: Int? = null,
    val jpegQuality: Int? = null,
    val previewFitMode: String? = null,
    val aspectRatio: String? = null,
    val zoomSpeed: String? = null,
    val displayRotation: String? = null,
    val mirror: Boolean? = null,
    val localPreviewEnabled: Boolean? = null,
    val phonePreviewEnabled: Boolean? = null,
    val accessMode: String? = null,
    val port: Int? = null,
    val accessToken: String? = null,
    val streamMode: String? = null,
    val h264Bitrate: Int? = null,
    val h264KeyframeInterval: Int? = null,
    val targetBandwidthMbps: Int? = null
)

@Serializable
private data class StreamInfoDto(
    val mode: String,
    val resolution: String,
    val fps: Int,
    val h264Bitrate: Int,
    val desired: StreamModeInfoDto,
    val selected: StreamModeInfoDto?,
    val actual: StreamActualInfoDto?,
    val codec: String = "mjpeg",
    val container: String = "multipart/x-mixed-replace",
    val experimental: Boolean = false,
    val notes: String = ""
)

@Serializable
private data class StreamModeInfoDto(
    val mode: String,
    val width: Int,
    val height: Int,
    val fps: Int
)

@Serializable
private data class StreamActualInfoDto(
    val mode: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val captureFps: Int,
    val encodedFps: Int
)

@Serializable
private data class ZoomRequest(
    val zoomRatio: Float? = null,
    val linearZoom: Float? = null,
    val baseRevision: Long? = null,
    val requestId: String? = null,
    val clientType: String? = null
)

@Serializable
private data class TorchRequest(
    val enabled: Boolean,
    val baseRevision: Long? = null,
    val requestId: String? = null,
    val clientType: String? = null
)

@Serializable
private data class AutofocusRequest(val enabled: Boolean)

@Serializable
private data class PipelineMetricsDto(
    val generation: Long,
    val lifecycleState: String,
    val activeStreamMode: String,
    val phonePreviewRequested: Boolean,
    val phonePreviewActive: Boolean,
    val phonePreviewFailureReason: String,
    val capture: CaptureMetricsDto?,
    val h264: H264MetricsDto?,
    val mjpeg: MjpegMetricsDto?,
    val transport: TransportMetricsDto,
    val selection: SelectionMetricsDto,
    val fallback: FallbackMetricsDto?
)

@Serializable
private data class CaptureMetricsDto(
    val engine: String,
    val cameraId: String,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int,
    val requestedFps: Int,
    val selectedFps: Int,
    val cameraSessionFps: Int,
    val actualFps: Int,
    val gpuBridgeFps: Int?
)

@Serializable
private data class H264MetricsDto(
    val encoderName: String,
    val hardwareEncoder: Boolean,
    val encodedFps: Int,
    val bitrate: Int,
    val clientCount: Int,
    val rejectedCapturePaths: String?
)

@Serializable
private data class MjpegMetricsDto(
    val encodedFps: Int,
    val encodeMs: Double,
    val yuvMs: Double,
    val jpegMs: Double,
    val rotateMs: Double,
    val processingCapacityFps: Int,
    val latestFrameRevision: Long,
    val clientCount: Int
)

@Serializable
private data class TransportMetricsDto(
    val estimatedMbps: String,
    val targetBandwidthMbps: Int
)

@Serializable
private data class SelectionMetricsDto(
    val requestedAspectRatio: String,
    val selectedAspectRatio: String,
    val aspectRatioMatch: Boolean,
    val resizeNeeded: Boolean,
    val selectedRawWidth: Int,
    val selectedRawHeight: Int,
    val selectedEffectiveWidth: Int,
    val selectedEffectiveHeight: Int,
    val resolutionPolicy: String
)

@Serializable
private data class FallbackMetricsDto(val active: Boolean, val reason: String)
