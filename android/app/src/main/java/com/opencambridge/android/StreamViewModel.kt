package com.opencambridge.android

import android.app.Application
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencambridge.android.camera.CameraInfoDto
import com.opencambridge.android.camera.CameraRepository
import com.opencambridge.android.service.NetworkUtils
import com.opencambridge.android.state.SettingsManager
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StreamViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraRepo = CameraRepository(application)
    private val settingsManager = SettingsManager(application)

    private val _cameras = MutableStateFlow<List<CameraInfoDto>>(emptyList())
    val cameras: StateFlow<List<CameraInfoDto>> = _cameras.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _lifecycleState = MutableStateFlow("STOPPED")
    val lifecycleState: StateFlow<String> = _lifecycleState.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _wifiIp = MutableStateFlow<String?>(null)
    val wifiIp: StateFlow<String?> = _wifiIp.asStateFlow()

    // Settings
    private val _selectedCameraId = MutableStateFlow("0")
    val selectedCameraId: StateFlow<String> = _selectedCameraId.asStateFlow()

    private val _width = MutableStateFlow(1280)
    val width: StateFlow<Int> = _width.asStateFlow()

    private val _height = MutableStateFlow(720)
    val height: StateFlow<Int> = _height.asStateFlow()

    private val _fps = MutableStateFlow(30)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _jpegQuality = MutableStateFlow(85)
    val jpegQuality: StateFlow<Int> = _jpegQuality.asStateFlow()

    private val _previewFitMode = MutableStateFlow("fit")
    val previewFitMode: StateFlow<String> = _previewFitMode.asStateFlow()
    
    private val _aspectRatio = MutableStateFlow("auto")
    val aspectRatio: StateFlow<String> = _aspectRatio.asStateFlow()
    
    private val _zoomSpeed = MutableStateFlow("normal")
    val zoomSpeed: StateFlow<String> = _zoomSpeed.asStateFlow()
    
    private val _displayRotation = MutableStateFlow(0)
    val displayRotation: StateFlow<Int> = _displayRotation.asStateFlow()
    
    private val _mirror = MutableStateFlow(false)
    val mirror: StateFlow<Boolean> = _mirror.asStateFlow()
    
    private val _streamMode = MutableStateFlow("mjpeg")
    val streamMode: StateFlow<String> = _streamMode.asStateFlow()
    
    // Security
    private val _accessMode = MutableStateFlow("usbOnly")
    val accessMode: StateFlow<String> = _accessMode.asStateFlow()

    private val _port = MutableStateFlow(8080)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _accessToken = MutableStateFlow("")
    val accessToken: StateFlow<String> = _accessToken.asStateFlow()
    
    private val _logs = MutableStateFlow<List<com.opencambridge.android.state.LogEntry>>(emptyList())
    val logs: StateFlow<List<com.opencambridge.android.state.LogEntry>> = _logs.asStateFlow()

    // Preview / Controls
    private val _localPreviewEnabled = MutableStateFlow(false)
    val localPreviewEnabled: StateFlow<Boolean> = _localPreviewEnabled.asStateFlow()

    private val _rebindInProgress = MutableStateFlow(false)
    val rebindInProgress: StateFlow<Boolean> = _rebindInProgress.asStateFlow()

    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()
    
    private val _hasTorch = MutableStateFlow(false)
    val hasTorch: StateFlow<Boolean> = _hasTorch.asStateFlow()
    
    private val _linearZoom = MutableStateFlow(0f)
    val linearZoom: StateFlow<Float> = _linearZoom.asStateFlow()
    
    private val _rotationDegrees = MutableStateFlow(0)
    val rotationDegrees: StateFlow<Int> = _rotationDegrees.asStateFlow()

    init {
        viewModelScope.launch {
            _cameras.value = cameraRepo.listCameras()
        }
        
        viewModelScope.launch {
            while (true) {
                _isStreaming.value = StreamState.streaming.get()
                _lifecycleState.value = StreamState.lifecycleState.get().name
                _lastError.value = StreamState.lastError.get()
                _wifiIp.value = NetworkUtils.getWifiIpAddress(getApplication())
                _selectedCameraId.value = StreamState.cameraId.get()
                _width.value = StreamState.width.get()
                _height.value = StreamState.height.get()
                _fps.value = StreamState.fps.get()
                _jpegQuality.value = StreamState.jpegQuality.get()
                _previewFitMode.value = StreamState.previewFitMode.get()
                _aspectRatio.value = StreamState.aspectRatio.get()
                _zoomSpeed.value = StreamState.zoomSpeed.get()
                _displayRotation.value = StreamState.displayRotation.get()
                _mirror.value = StreamState.mirror.get()
                _streamMode.value = StreamState.streamMode.get()
                _localPreviewEnabled.value = StreamState.localPreviewEnabled.get()
                _rebindInProgress.value = StreamState.rebindInProgress.get()
                _torchEnabled.value = StreamState.torchEnabled.get()
                _hasTorch.value = StreamState.hasTorch.get()
                _linearZoom.value = StreamState.linearZoom.get()
                _rotationDegrees.value = StreamState.rotationDegrees.get()
                _accessMode.value = StreamState.accessMode.get()
                _port.value = StreamState.port.get()
                _accessToken.value = StreamState.accessToken.get()
                _logs.value = com.opencambridge.android.state.AppLogger.getLogs()
                delay(500)
            }
        }
    }

    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        StreamState.surfaceProvider = provider
        // Dynamically attach or detach the surface to the active Preview UseCase
        // This avoids tearing down the entire CameraX session when switching tabs.
        StreamState.previewUseCase?.setSurfaceProvider(provider)
    }

    fun toggleLocalPreview(enabled: Boolean) {
        // We still save the preference, but if it's disabled, we detach the surface explicitly.
        // It's handled by ControlServer now, but we can also detach instantly here.
        if (!enabled) {
            StreamState.previewUseCase?.setSurfaceProvider(null)
        }
        viewModelScope.launch {
            postLocalApiSuspend("/api/settings", """{"localPreviewEnabled": $enabled, "clientType": "phone"}""")
        }
    }

    fun selectCamera(cameraId: String) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"cameraId": "$cameraId", "clientType": "phone"}""") }
    }

    fun updateResolution(w: Int, h: Int) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"width": $w, "height": $h, "clientType": "phone"}""") }
    }

    fun updateFps(f: Int) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"fps": $f, "clientType": "phone"}""") }
    }

    fun updateJpegQuality(q: Int) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"jpegQuality": $q, "clientType": "phone"}""") }
    }

    fun updatePreviewFitMode(mode: String) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"previewFitMode": "$mode", "clientType": "phone"}""") }
    }
    
    fun updateAspectRatio(ratio: String) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"aspectRatio": "$ratio", "clientType": "phone"}""") }
    }
    
    fun updateZoomSpeed(speed: String) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"zoomSpeed": "$speed", "clientType": "phone"}""") }
    }
    
    fun updateDisplayRotation(rotation: Int) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"displayRotation": $rotation, "clientType": "phone"}""") }
    }
    
    fun updateMirror(mirror: Boolean) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"mirror": $mirror, "clientType": "phone"}""") }
    }
    
    fun updateStreamMode(mode: String) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"streamMode": "$mode", "clientType": "phone"}""") }
    }
    
    fun updateAccessMode(mode: String) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"accessMode": "$mode", "clientType": "phone"}""") }
    }
    
    fun updatePort(p: Int) {
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"port": $p, "clientType": "phone"}""") }
    }
    
    fun regenerateToken() {
        val newToken = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        viewModelScope.launch { postLocalApiSuspend("/api/settings", """{"accessToken": "$newToken", "clientType": "phone"}""") }
    }
    
    fun clearLogs() {
        viewModelScope.launch { postLocalApiSuspend("/api/logs/clear", "{}") }
    }
    
    fun updateTorch(enabled: Boolean) {
        viewModelScope.launch {
            postLocalApiSuspend("/api/camera/torch", """{"enabled": $enabled}""")
        }
    }
    
    fun updateZoom(linearZoom: Float) {
        viewModelScope.launch { postLocalApiSuspend("/api/camera/zoom", """{"linearZoom": $linearZoom}""") }
    }

    fun stopStream() {
        viewModelScope.launch { postLocalApiSuspend("/api/stream/stop", "{}") }
    }

    fun startStream() {
        // We still need to ensure the service is running, but if it is, this API call tells it to start the camera pipeline.
        viewModelScope.launch { postLocalApiSuspend("/api/stream/start", "{}") }
    }

    // buildSettingsJson is no longer needed since we patch individually

    private suspend fun postLocalApiSuspend(path: String, jsonPayload: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val port = StreamState.port.get()
                val token = StreamState.accessToken.get()
                java.net.HttpURLConnection.setFollowRedirects(false)
                val url = java.net.URL("http://127.0.0.1:$port$path")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (token.isNotEmpty()) {
                    conn.setRequestProperty("X-OpenCamBridge-Token", token)
                }
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.outputStream.write(jsonPayload.toByteArray())
                conn.responseCode // Wait for completion
                conn.disconnect()
            } catch (e: Exception) {
                // Ignore. Server probably not running.
            }
        }
    }
}
