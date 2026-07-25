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

    private val _h264Bitrate = MutableStateFlow(4_000_000)
    val h264Bitrate: StateFlow<Int> = _h264Bitrate.asStateFlow()

    private val _h264KeyframeInterval = MutableStateFlow(
        com.opencambridge.android.state.H264SettingsPolicy.DEFAULT_KEYFRAME_INTERVAL_SECONDS
    )
    val h264KeyframeInterval: StateFlow<Int> = _h264KeyframeInterval.asStateFlow()

    // ---- Pipeline truth -----------------------------------------------------
    // The phone could previously only report "streaming" or "stopped", which says
    // nothing about whether the desktop is actually receiving anything. These are
    // the measurements that answer that, and they are what the Stream tab shows.

    /** Desktop clients currently subscribed to the active transport. */
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    /** Frames the encoder emitted in the last one-second window. */
    private val _encodedFps = MutableStateFlow(0)
    val encodedFps: StateFlow<Int> = _encodedFps.asStateFlow()

    private val _encodedBitrate = MutableStateFlow(0)
    val encodedBitrate: StateFlow<Int> = _encodedBitrate.asStateFlow()

    private val _encoderName = MutableStateFlow("")
    val encoderName: StateFlow<String> = _encoderName.asStateFlow()

    private val _hardwareEncoder = MutableStateFlow(false)
    val hardwareEncoder: StateFlow<Boolean> = _hardwareEncoder.asStateFlow()

    private val _captureEngine = MutableStateFlow("")
    val captureEngine: StateFlow<String> = _captureEngine.asStateFlow()

    /** What the pipeline actually settled on, which can differ from the request. */
    private val _activeStreamMode = MutableStateFlow("h264")
    val activeStreamMode: StateFlow<String> = _activeStreamMode.asStateFlow()

    private val _fallbackReason = MutableStateFlow("")
    val fallbackReason: StateFlow<String> = _fallbackReason.asStateFlow()

    private val _encodedWidth = MutableStateFlow(0)
    val encodedWidth: StateFlow<Int> = _encodedWidth.asStateFlow()

    private val _encodedHeight = MutableStateFlow(0)
    val encodedHeight: StateFlow<Int> = _encodedHeight.asStateFlow()

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

    // Foreground-service launch errors need a retry action, unlike ordinary
    // transient control errors. MainActivity owns the actual retry because it
    // must re-run the Android permission/startForegroundService boundary.
    private val _serviceStartError = MutableStateFlow<String?>(null)
    val serviceStartError: StateFlow<String?> = _serviceStartError.asStateFlow()
    fun reportServiceStartFailure(message: String) { _serviceStartError.value = message }
    fun clearServiceStartError() { _serviceStartError.value = null }

    // Preview / Controls
    private val _localPreviewEnabled = MutableStateFlow(false)
    val localPreviewEnabled: StateFlow<Boolean> = _localPreviewEnabled.asStateFlow()

    private val _phonePreviewActive = MutableStateFlow(false)
    val phonePreviewActive: StateFlow<Boolean> = _phonePreviewActive.asStateFlow()

    private val _phonePreviewFailureReason = MutableStateFlow("")
    val phonePreviewFailureReason: StateFlow<String> = _phonePreviewFailureReason.asStateFlow()

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

    // The inputs to the rotation transform, exposed so the Logs tab can show
    // WHY the effective rotation is what it is. Deriving it from sensor
    // orientation, physical device rotation and the manual offset is not
    // guessable from the outside, which made orientation reports hard to act on.
    private val _sensorOrientation = MutableStateFlow(0)
    val sensorOrientation: StateFlow<Int> = _sensorOrientation.asStateFlow()

    private val _deviceSurfaceRotation = MutableStateFlow(0)
    val deviceSurfaceRotation: StateFlow<Int> = _deviceSurfaceRotation.asStateFlow()

    /** Sensor/device correction alone. Decides whether the picture the camera
     *  surface hands to the local preview is landscape or portrait. */
    private val _autoRotation = MutableStateFlow(0)
    val autoRotation: StateFlow<Int> = _autoRotation.asStateFlow()

    /**
     * Rotation for THIS phone's preview: the sensor/device correction without
     * the manual offset. Distinct from [rotationDegrees], which is what the
     * desktop applies and therefore includes the offset.
     */
    private val _previewRotation = MutableStateFlow(0)
    val previewRotation: StateFlow<Int> = _previewRotation.asStateFlow()

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
                val config = StreamState.currentConfig()
                val lifecycle = StreamState.lifecycleState.get()
                _isStreaming.value = lifecycle == com.opencambridge.android.state.LifecycleState.STREAMING
                _lifecycleState.value = lifecycle.name
                _lastError.value = StreamState.lastError.get()
                _wifiIp.value = NetworkUtils.getWifiIpAddress(getApplication())
                _selectedCameraId.value = config.cameraId
                _width.value = config.width
                _height.value = config.height
                _frameWidth.value = StreamState.frameWidth.get()
                _frameHeight.value = StreamState.frameHeight.get()
                _fps.value = config.fps
                _jpegQuality.value = config.jpegQuality
                _previewFitMode.value = config.previewFitMode
                _aspectRatio.value = config.aspectRatio
                _zoomSpeed.value = config.zoomSpeed
                _displayRotation.value = config.displayRotation
                _mirror.value = config.mirror
                _streamMode.value = config.streamMode
                _localPreviewEnabled.value = config.localPreviewEnabled
                _phonePreviewActive.value = StreamState.phonePreviewActive.get()
                _phonePreviewFailureReason.value = StreamState.phonePreviewFailureReason.get()
                _rebindInProgress.value = StreamState.rebindInProgress.get()
                _torchEnabled.value = StreamState.torchEnabled.get()
                _hasTorch.value = StreamState.hasTorch.get()
                _linearZoom.value = StreamState.linearZoom.get()
                _rotationDegrees.value = StreamState.rotationDegrees.get()
                _sensorOrientation.value = StreamState.sensorOrientation.get()
                _deviceSurfaceRotation.value = StreamState.deviceSurfaceRotation.get()
                _autoRotation.value = StreamState.autoRotation.get()
                _previewRotation.value = StreamState.previewRotation.get()
                _actualFps.value = StreamState.actualFps.get()
                _h264Bitrate.value = config.h264Bitrate
                _h264KeyframeInterval.value = config.h264KeyframeInterval
                val active = StreamState.activeStreamMode.get()
                _activeStreamMode.value = active
                // Count only the transport that is actually carrying video, so an
                // idle subscriber on the other endpoint cannot read as "connected".
                _clientCount.value = if (active == "h264") {
                    StreamState.h264ClientCount.get()
                } else {
                    StreamState.mjpegClientCount.get()
                }
                _encodedFps.value = StreamState.encodedFps.get()
                _encodedBitrate.value = StreamState.encodedBitrate.get()
                _encoderName.value = StreamState.encoderName.get() ?: ""
                _hardwareEncoder.value = StreamState.hardwareEncoder.get()
                _captureEngine.value = StreamState.captureEngine.get() ?: ""
                _fallbackReason.value = StreamState.fallbackReason.get() ?: ""
                _encodedWidth.value = StreamState.encodedWidth.get()
                _encodedHeight.value = StreamState.encodedHeight.get()
                _developerMode.value = StreamState.developerMode.get()
                _accessMode.value = config.accessMode
                _port.value = config.port
                _accessToken.value = config.accessToken
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
        if (StreamState.currentConfig().streamMode == "mjpeg") {
            StreamState.publishPhonePreview(
                StreamState.pipelineGeneration.get(),
                StreamState.currentConfig().localPreviewEnabled && provider != null,
                if (StreamState.currentConfig().localPreviewEnabled && provider == null) {
                    "CameraX preview requested but no phone SurfaceProvider is attached"
                } else ""
            )
        }
    }

    fun setCamera2PreviewSurface(surface: android.view.Surface?) {
        StreamState.camera2PreviewSurface.set(surface)
        val handler = ServiceBridge.previewSurfaceChanged ?: return
        viewModelScope.launch {
            try {
                val result = handler(surface?.isValid == true)
                if (!result.success) _controlError.value = result.message
            } catch (e: Exception) {
                AppLogger.e("Control", "Preview surface change failed: ${e.javaClass.simpleName}: ${e.message}")
                _controlError.value = "Preview update failed: ${e.javaClass.simpleName}"
            }
        }
    }

    fun toggleLocalPreview(enabled: Boolean) {
        if (!enabled) {
            StreamState.previewUseCase?.setSurfaceProvider(null)
        }
        controlPatch(UpdateSettingsRequest(phonePreviewEnabled = enabled, clientType = "phone"))
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
    fun updateH264Bitrate(bitrate: Int) = controlPatch(UpdateSettingsRequest(h264Bitrate = bitrate, clientType = "phone"))
    fun updateH264KeyframeInterval(seconds: Int) =
        controlPatch(UpdateSettingsRequest(h264KeyframeInterval = seconds, clientType = "phone"))
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
            viewModelScope.launch {
                try {
                    val result = h(enabled, StreamState.revision.get(), java.util.UUID.randomUUID().toString(), "phone")
                    if (!result.success) _controlError.value = result.message
                } catch (e: Exception) {
                    AppLogger.e("Control", "Torch failed: ${e.message}")
                    _controlError.value = "Torch failed: ${e.javaClass.simpleName}"
                }
            }
        } else {
            _controlError.value = "Streaming service is starting; try again"
        }
    }

    fun updateZoom(linearZoom: Float) {
        val h = ServiceBridge.setLinearZoom
        if (h != null) viewModelScope.launch {
            val result = h(linearZoom, StreamState.revision.get(), java.util.UUID.randomUUID().toString(), "phone")
            if (!result.success) _controlError.value = result.message
        } else _controlError.value = "Streaming service is starting; try again"
    }

    fun stopStream() {
        submitPipelineControl("Stop stream", ServiceBridge.stopCamera)
    }

    fun startStream() {
        // If the service is running, ask it to start the camera pipeline; the
        // initial service launch itself is handled by MainActivity.
        submitPipelineControl("Start stream", ServiceBridge.startCamera)
    }

    /**
     * Applies a settings patch IN-PROCESS via the same serialized controller as
     * HTTP clients and awaits its authoritative result. There is intentionally
     * no second local mutation path: while the service is starting, controls
     * report that state and the user can retry after the controller is ready.
     */
    private fun controlPatch(req: UpdateSettingsRequest) {
        val handler = ServiceBridge.applyPatch
        if (handler == null) {
            _controlError.value = "Streaming service is starting; try again"
            return
        }
        viewModelScope.launch {
            try {
                val authoritative = req.copy(
                    baseRevision = StreamState.revision.get(),
                    requestId = java.util.UUID.randomUUID().toString(),
                    clientType = "phone"
                )
                val result = handler(authoritative, "phone")
                if (!result.success) {
                    AppLogger.w("Control", "Settings rejected: ${result.message}")
                    _controlError.value = result.message
                }
            } catch (e: Exception) {
                AppLogger.e("Control", "Apply failed: ${e.javaClass.simpleName}: ${e.message}")
                _controlError.value = "Action failed: ${e.javaClass.simpleName}"
            }
        }
    }

    private fun submitPipelineControl(
        label: String,
        handler: (suspend () -> com.opencambridge.android.service.PipelineResult)?
    ) {
        if (handler == null) {
            _controlError.value = "Streaming service is starting; try again"
            return
        }
        viewModelScope.launch {
            try {
                val result = handler()
                if (!result.success) _controlError.value = result.message
            } catch (e: Exception) {
                AppLogger.e("Control", "$label failed: ${e.javaClass.simpleName}: ${e.message}")
                _controlError.value = "$label failed: ${e.javaClass.simpleName}"
            }
        }
    }
}
