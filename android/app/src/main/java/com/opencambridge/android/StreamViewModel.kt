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

    // Preview / Controls
    private val _localPreviewEnabled = MutableStateFlow(false)
    val localPreviewEnabled: StateFlow<Boolean> = _localPreviewEnabled.asStateFlow()

    private val _rebindInProgress = MutableStateFlow(false)
    val rebindInProgress: StateFlow<Boolean> = _rebindInProgress.asStateFlow()

    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()
    
    private val _linearZoom = MutableStateFlow(0f)
    val linearZoom: StateFlow<Float> = _linearZoom.asStateFlow()

    init {
        viewModelScope.launch {
            _cameras.value = cameraRepo.listCameras()
        }
        
        viewModelScope.launch {
            while (true) {
                _isStreaming.value = StreamState.streaming.get()
                _wifiIp.value = NetworkUtils.getWifiIpAddress(getApplication())
                _selectedCameraId.value = StreamState.cameraId.get()
                _width.value = StreamState.width.get()
                _height.value = StreamState.height.get()
                _fps.value = StreamState.fps.get()
                _jpegQuality.value = StreamState.jpegQuality.get()
                _previewFitMode.value = StreamState.previewFitMode.get()
                _localPreviewEnabled.value = StreamState.localPreviewEnabled.get()
                _rebindInProgress.value = StreamState.rebindInProgress.get()
                _torchEnabled.value = StreamState.torchEnabled.get()
                _linearZoom.value = StreamState.linearZoom.get()
                delay(500)
            }
        }
    }

    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        StreamState.surfaceProvider = provider
        // If streaming is already active, toggling preview requires a rebind
        if (StreamState.streaming.get()) {
            postLocalApi("/api/settings", buildSettingsJson())
        }
    }

    fun toggleLocalPreview(enabled: Boolean) {
        StreamState.localPreviewEnabled.set(enabled)
        settingsManager.save()
        if (StreamState.streaming.get()) {
            postLocalApi("/api/settings", buildSettingsJson())
        }
    }

    fun selectCamera(cameraId: String) {
        StreamState.cameraId.set(cameraId)
        settingsManager.save()
        postLocalApi("/api/settings", buildSettingsJson())
    }

    fun updateResolution(w: Int, h: Int) {
        StreamState.width.set(w)
        StreamState.height.set(h)
        settingsManager.save()
        postLocalApi("/api/settings", buildSettingsJson())
    }

    fun updateFps(f: Int) {
        StreamState.fps.set(f)
        settingsManager.save()
        postLocalApi("/api/settings", buildSettingsJson())
    }

    fun updateJpegQuality(q: Int) {
        StreamState.jpegQuality.set(q)
        settingsManager.save()
        postLocalApi("/api/settings", buildSettingsJson())
    }

    fun updatePreviewFitMode(mode: String) {
        StreamState.previewFitMode.set(mode)
        settingsManager.save()
        postLocalApi("/api/settings", buildSettingsJson())
    }
    
    fun updateTorch(enabled: Boolean) {
        postLocalApi("/api/camera/torch", """{"enabled": $enabled}""")
    }
    
    fun updateZoom(linearZoom: Float) {
        postLocalApi("/api/camera/zoom", """{"linearZoom": $linearZoom}""")
    }

    private fun buildSettingsJson(): String {
        return """
            {
                "cameraId": "${StreamState.cameraId.get()}",
                "width": ${StreamState.width.get()},
                "height": ${StreamState.height.get()},
                "fps": ${StreamState.fps.get()},
                "jpegQuality": ${StreamState.jpegQuality.get()},
                "previewFitMode": "${StreamState.previewFitMode.get()}"
            }
        """.trimIndent()
    }

    private fun postLocalApi(path: String, jsonPayload: String) {
        viewModelScope.launch {
            try {
                java.net.HttpURLConnection.setFollowRedirects(false)
                val url = java.net.URL("http://127.0.0.1:8080$path")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
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
