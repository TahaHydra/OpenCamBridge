package com.opencambridge.android.server

import android.content.Context
import com.opencambridge.android.camera.CameraRepository
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
private const val FRAME_DELAY_MS = 33L  // ~30 fps cap

/**
 * Embedded Ktor (3.x) HTTP server on port 8080.
 *
 * Route handlers are plain suspend functions that receive the RoutingCall
 * as a parameter — avoids any receiver-type ambiguity between RoutingCall
 * and ApplicationCall extension functions.
 *
 * The engine field is typed exactly as embeddedServer() returns so the
 * assignment compiles without a type mismatch.
 */
class ControlServer(
    private val context: Context,
    private val onStreamStart: () -> Unit,
    private val onStreamStop: () -> Unit,
    private val onCameraSwitch: (String) -> Unit
) {
    // Fix: store the concrete return type of embeddedServer(CIO, ...)
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val cameraRepo = CameraRepository(context)

    fun start() {
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                // Fix: pass `call` explicitly to plain functions — no receiver mismatch
                get("/")                           { serveIndex(call) }
                get("/health")                     { call.respondText("OK") }
                get("/api/device/info")            { serveDeviceInfo(call) }
                get("/api/camera/list")            { serveCameraList(call) }
                get("/api/camera/status")          { serveCameraStatus(call) }
                post("/api/stream/start")          { serveStreamStart(call) }
                post("/api/stream/stop")           { serveStreamStop(call) }
                post("/api/camera/switch")         { serveCameraSwitch(call) }
                post("/api/settings/resolution")   { serveSetResolution(call) }
                post("/api/settings/jpeg-quality") { serveSetJpegQuality(call) }
                get("/stream.mjpeg")               { serveMjpeg(call) }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        engine = null
    }

    // ---- Route handlers — plain suspend functions taking RoutingCall ----

    private suspend fun serveIndex(call: RoutingCall) {
        call.respondText(ContentType.Text.Html) {
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <title>OpenCamBridge</title>
              <style>
                body{font-family:sans-serif;max-width:600px;margin:40px auto;padding:0 16px}
                h1{color:#1a73e8}
                a{color:#1a73e8}
                .stream{width:100%;border-radius:8px;margin-top:16px}
              </style>
            </head>
            <body>
              <h1>OpenCamBridge</h1>
              <p>Android phone as a local webcam source over Wi-Fi / USB.</p>
              <ul>
                <li><a href="/health">/health</a></li>
                <li><a href="/api/device/info">/api/device/info</a></li>
                <li><a href="/api/camera/list">/api/camera/list</a></li>
                <li><a href="/api/camera/status">/api/camera/status</a></li>
                <li><a href="/stream.mjpeg">/stream.mjpeg</a></li>
              </ul>
              <h2>Live stream</h2>
              <img class="stream" src="/stream.mjpeg" alt="MJPEG stream">
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
        onCameraSwitch(req.cameraId)
        call.respond(SimpleResult(success = true, message = "Switched to camera ${req.cameraId}"))
    }

    private suspend fun serveSetResolution(call: RoutingCall) {
        val req = try { call.receive<ResolutionRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        StreamState.width.set(req.width)
        StreamState.height.set(req.height)
        call.respond(SimpleResult(success = true, message = "Resolution set to ${req.width}x${req.height}"))
    }

    private suspend fun serveSetJpegQuality(call: RoutingCall) {
        val req = try { call.receive<JpegQualityRequest>() } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SimpleResult(false, "Invalid JSON"))
            return
        }
        val quality = req.quality.coerceIn(1, 100)
        StreamState.jpegQuality.set(quality)
        call.respond(SimpleResult(success = true, message = "JPEG quality set to $quality"))
    }

    /**
     * MJPEG stream: loops indefinitely writing the latest JPEG frame as a
     * multipart boundary chunk. Browsers and OBS decode each chunk as a JPEG.
     */
    private suspend fun serveMjpeg(call: RoutingCall) {
        call.response.headers.append("Cache-Control", "no-cache")
        call.response.headers.append("Connection", "close")

        call.respondBytesWriter(
            contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY")
        ) {
            streamMjpegFrames()
        }
    }

    /**
     * Fix: use currentCoroutineContext().isActive instead of bare `isActive`,
     * which is not in scope inside a ByteWriteChannel extension function.
     */
    private suspend fun ByteWriteChannel.streamMjpegFrames() {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = StreamState.latestFrame.get()
                if (frame != null) {
                    val header = "--$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    writeFully(header.toByteArray(Charsets.US_ASCII))
                    writeFully(frame)
                    writeFully("\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                delay(FRAME_DELAY_MS)
            }
        } catch (_: Exception) {
            // Client disconnected or coroutine cancelled — expected exit.
        }
    }
}

// ---- DTOs ----

@Serializable
private data class DeviceInfoDto(
    val app: String,
    val version: String,
    val platform: String,
    val serverPort: Int
)

@Serializable
data class SimpleResult(val success: Boolean, val message: String)

@Serializable
private data class CameraSwitchRequest(val cameraId: String)

@Serializable
private data class ResolutionRequest(val width: Int, val height: Int)

@Serializable
private data class JpegQualityRequest(val quality: Int)
