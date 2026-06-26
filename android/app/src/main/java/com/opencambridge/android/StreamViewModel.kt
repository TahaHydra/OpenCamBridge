package com.opencambridge.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencambridge.android.camera.CameraInfoDto
import com.opencambridge.android.camera.CameraRepository
import com.opencambridge.android.service.NetworkUtils
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin ViewModel that polls StreamState and exposes UI state as StateFlow.
 */
class StreamViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraRepo = CameraRepository(application)

    private val _cameras = MutableStateFlow<List<CameraInfoDto>>(emptyList())
    val cameras: StateFlow<List<CameraInfoDto>> = _cameras.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _wifiIp = MutableStateFlow<String?>(null)
    val wifiIp: StateFlow<String?> = _wifiIp.asStateFlow()

    private val _selectedCameraId = MutableStateFlow("0")
    val selectedCameraId: StateFlow<String> = _selectedCameraId.asStateFlow()

    init {
        // Load camera list once
        viewModelScope.launch {
            _cameras.value = cameraRepo.listCameras()
            if (_cameras.value.isNotEmpty()) {
                _selectedCameraId.value = _cameras.value.first().id
            }
        }
        // Poll StreamState at ~2 Hz to update UI
        viewModelScope.launch {
            while (true) {
                _isStreaming.value = StreamState.streaming.get()
                _wifiIp.value = NetworkUtils.getWifiIpAddress(getApplication())
                delay(500)
            }
        }
    }

    fun selectCamera(cameraId: String) {
        _selectedCameraId.value = cameraId
        StreamState.cameraId.set(cameraId)
    }
}
