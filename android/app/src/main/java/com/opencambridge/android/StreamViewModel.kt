package com.opencambridge.android

import android.app.Application
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencambridge.android.camera.CameraInfoDto
import com.opencambridge.android.camera.CameraRepository
import com.opencambridge.android.server.UpdateSettingsRequest
import com.opencambridge.android.service.NetworkUtils
import com.opencambridge.android.service.ServiceBridge
import com.opencambridge.android.state.AppLogger
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

    private val _frameWidth = MutableStateFlow(0)
    val frameWidth: StateFlow<Int> = _frameWidth.asStateFlow()

    private val _frameHeight = MutableStateFlow(0)
    val frameHeight: StateFlow<Int> = _frameHeight.asStateFlow()

    private val _fps = MutableStateFlow(30)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _jpegQuality = MutableStateFlow(85)
    val jpegQuality: StateFlow<Int> = _jpegQuality.asStateFlow()

    private val _previewFitMode = MutableStateFlow("fill")
    val previewFitMode: StateFlow<String> = _previewFitMode.asStateFlow()

    private val _aspectRatio = MutableStateFlow("16:9")
    val aspectRatio: StateFlow<String> = _aspectRatio.asStateFlow()

    private val _zoomSpeed = MutableStateFlow("normal")
    val zoomSpeed: StateFlow<String> = _zoomSpeed.asStateFlow()

    private val _displayRotation = MutableStateFlow("0")
    val displayRotation: StateFlow<String> = _displayRotation.asStateFlow()

    private val _mirror = MutableStateFlow(false)
    val mirror: StateFlow<Boolean> = _mirror.asStateFlow()

    private val _streamMode = MutableStateFlow("h264")
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

    // Transient, user-facing control-failure message (shown as a Snackbar) so a
    // button that silently failed its local API call is visible, not just logged.
    private val _controlError = MutableStateFlow<String?>(null)
    val controlError: StateFlow<String?> = _controlError.asStateFlow()
    fun clearControlError() { _controlError.value = null }

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

    // Live measured FPS (frames encoded in the last 1s window).
    private val _actualFps = MutableStateFlow(0)
    val actualFps: StateFlow<Int> = _actualFps.asStateFlow()

    // Developer mode gates noisy diagnostics in the Logs tab.
    private val _developerMode = MutableStateFlow(false)
    val developerMode: StateFlow<Boolean> = _developerMode.asStateFlow()

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
                _frameWidth.value = StreamState.frameWidth.get()
                _frameHeight.value = StreamState.frameHeight.get()
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
                _actualFps.value = StreamState.actualFps.get()
                _developerMode.value = StreamState.developerMode.get()
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

    fun setCamera2PreviewSurface(surface: android.view.Surface?) {
        StreamState.camera2PreviewSurface.set(surface)
    }

    fun toggleLocalPreview(enabled: Boolean) {
        if (!enabled) {
            StreamState.previewUseCase?.setSurfaceProvider(null)
        }
        controlPatch(UpdateSettingsRequest(localPreviewEnabled = enabled, clientType = "phone"))
    }

    fun selectCamera(cameraId: String) = controlPatch(UpdateSettingsRequest(cameraId = cameraId, clientType = "phone"))
    fun updateResolution(w: Int, h: Int) = controlPatch(UpdateSettingsRequest(width = w, height = h, clientType = "phone"))
    fun updateFps(f: Int) = controlPatch(UpdateSettingsRequest(fps = f, clientType = "phone"))
    fun updateJpegQuality(q: Int) = controlPatch(UpdateSettingsRequest(jpegQuality = q, clientType = "phone"))
    fun updatePreviewFitMode(mode: String) = controlPatch(UpdateSettingsRequest(previewFitMode = mode, clientType = "phone"))
    fun updateAspectRatio(ratio: String) = controlPatch(UpdateSettingsRequest(aspectRatio = ratio, clientType = "phone"))
    fun updateZoomSpeed(speed: String) = controlPatch(UpdateSettingsRequest(zoomSpeed = speed, clientType = "phone"))
    fun updateDisplayRotation(rotation: String) = controlPatch(UpdateSettingsRequest(displayRotation = rotation, clientType = "phone"))
    fun updateMirror(mirror: Boolean) = controlPatch(UpdateSettingsRequest(mirror = mirror, clientType = "phone"))
    fun updateStreamMode(mode: String) = controlPatch(UpdateSettingsRequest(streamMode = mode, clientType = "phone"))
    fun updateAccessMode(mode: String) = controlPatch(UpdateSettingsRequest(accessMode = mode, clientType = "phone"))
    fun updatePort(p: Int) = controlPatch(UpdateSettingsRequest(port = p, clientType = "phone"))

    fun regenerateToken() {
        val newToken = java.util.UUID.randomUUID().toString().replace("-", "")
        controlPatch(UpdateSettingsRequest(accessToken = newToken, clientType = "phone"))
    }

    fun setDeveloperMode(enabled: Boolean) {
        // Minimal, device-local toggle: no /api/settings roundtrip needed since
        // this only affects what the phone UI shows. Persist so it survives
        // restarts, and reflect immediately in the StateFlow.
        StreamState.developerMode.set(enabled)
        _developerMode.value = enabled
        settingsManager.save()
    }

    fun clearLogs() {
        AppLogger.clear()
    }

    fun updateTorch(enabled: Boolean) {
        val h = ServiceBridge.setTorch
        if (h != null) {
            try { h(enabled) } catch (e: Exception) {
                AppLogger.e("Control", "Torch failed: ${e.message}")
                _controlError.value = "Torch failed: ${e.javaClass.simpleName}"
            }
        } else {
            // No camera running: reflect intent so the switch is consistent.
            StreamState.torchRequested.set(enabled)
            StreamState.torchEnabled.set(enabled)
        }
    }

    fun updateZoom(linearZoom: Float) {
        val h = ServiceBridge.setLinearZoom
        if (h != null) h(linearZoom) else StreamState.linearZoom.set(linearZoom)
    }

    fun stopStream() {
        ServiceBridge.stopCamera?.invoke()
    }

    fun startStream() {
        // If the service is running, ask it to start the camera pipeline; the
        // initial service launch itself is handled by MainActivity.
        ServiceBridge.startCamera?.invoke()
    }

    /**
     * Applies a settings patch IN-PROCESS via the running service (no loopback
     * HTTP — that's what caused IOExceptions on slow devices). If the service is
     * not running yet, persist locally so the change takes effect at next start.
     * The HTTP API remains for desktop/web/remote clients.
     */
    private fun controlPatch(req: UpdateSettingsRequest) {
        val h = ServiceBridge.applyPatch
        try {
            if (h != null) h(req, "phone") else persistPatchLocally(req)
        } catch (e: Exception) {
            AppLogger.e("Control", "Apply failed: ${e.javaClass.simpleName}: ${e.message}")
            _controlError.value = "Action failed: ${e.javaClass.simpleName}"
        }
    }

    /** Fallback used when the service is not running: persist to StreamState +
     *  SharedPreferences so settings take effect on the next stream start. */
    private fun persistPatchLocally(req: UpdateSettingsRequest) {
        req.cameraId?.let { StreamState.cameraId.set(it) }
        req.width?.let { StreamState.width.set(it) }
        req.height?.let { StreamState.height.set(it) }
        req.outputWidth?.let { StreamState.outputWidth.set(it) }
        req.outputHeight?.let { StreamState.outputHeight.set(it) }
        req.profile?.let { StreamState.profile.set(it) }
        req.fps?.let { StreamState.fps.set(it.coerceIn(1, 120)) }
        req.jpegQuality?.let { StreamState.jpegQuality.set(it.coerceIn(1, 100)) }
        req.previewFitMode?.let { StreamState.previewFitMode.set(it) }
        req.aspectRatio?.let { StreamState.aspectRatio.set(it) }
        req.zoomSpeed?.let { StreamState.zoomSpeed.set(it) }
        req.displayRotation?.let { StreamState.displayRotation.set(it) }
        req.mirror?.let { StreamState.mirror.set(it) }
        req.streamMode?.let { StreamState.streamMode.set(it) }
        req.localPreviewEnabled?.let { StreamState.localPreviewEnabled.set(it) }
        req.accessMode?.let { StreamState.accessMode.set(it) }
        req.port?.let { StreamState.port.set(it) }
        req.accessToken?.let { StreamState.accessToken.set(it) }
        settingsManager.save()
        StreamState.incrementRevision("phone")
    }
}
