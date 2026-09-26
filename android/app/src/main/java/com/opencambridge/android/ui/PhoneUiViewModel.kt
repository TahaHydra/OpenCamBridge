package com.opencambridge.android.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencambridge.android.service.ServiceBridge
import com.opencambridge.android.state.StoppedSettingsStore
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen curtain: dims the phone to black while it streams. */
enum class ScreenCurtainMode(val key: String, val label: String) {
    Auto("auto", "Auto"),
    Always("always", "Always"),
    Never("never", "Never");

    companion object {
        fun from(key: String?): ScreenCurtainMode = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/** What the phone can tell about its USB link to a computer. */
data class UsbStatus(
    /** A USB data connection to a host is up (or, failing that, USB charging). */
    val connected: Boolean = false,
    /** USB debugging is switched on (needed for the adb port forward). */
    val debugging: Boolean = false,
)

/** The bind a running service actually started with (mode/port apply at start). */
data class BoundConnection(val accessMode: String, val port: Int)

/**
 * Phone-only UI state that is not part of the streaming pipeline: presentation
 * preferences, whether the service is up, USB status, and the persisted
 * settings shown while OpenCamBridge is stopped. Pipeline state and controls
 * stay in StreamViewModel.
 */
class PhoneUiViewModel(application: Application) : AndroidViewModel(application) {
    private val uiPrefs = application.getSharedPreferences("OpenCamBridgeUi", Context.MODE_PRIVATE)

    private val _screenCurtain = MutableStateFlow(ScreenCurtainMode.from(uiPrefs.getString("screenCurtain", null)))
    val screenCurtain: StateFlow<ScreenCurtainMode> = _screenCurtain.asStateFlow()

    private val _mirrorPreview = MutableStateFlow(uiPrefs.getBoolean("mirrorPreview", false))
    val mirrorPreview: StateFlow<Boolean> = _mirrorPreview.asStateFlow()

    private val _serviceRunning = MutableStateFlow(ServiceBridge.isServiceRunning)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _usb = MutableStateFlow(UsbStatus())
    val usb: StateFlow<UsbStatus> = _usb.asStateFlow()

    private val _stoppedSettings = MutableStateFlow(StoppedSettingsStore.read(application))
    val stoppedSettings: StateFlow<StoppedSettingsStore.Snapshot> = _stoppedSettings.asStateFlow()

    private val _boundConnection = MutableStateFlow<BoundConnection?>(null)
    val boundConnection: StateFlow<BoundConnection?> = _boundConnection.asStateFlow()

    init {
        viewModelScope.launch {
            var tick = 0
            while (true) {
                val running = ServiceBridge.isServiceRunning
                if (running && _boundConnection.value == null) {
                    // Settings are loaded before the bridge is published, so this is
                    // the configuration the control server bound with.
                    val config = StreamState.currentConfig()
                    _boundConnection.value = BoundConnection(config.accessMode, config.port)
                }
                if (!running) _boundConnection.value = null
                _serviceRunning.value = running
                if (!running) {
                    _stoppedSettings.value = StoppedSettingsStore.read(getApplication())
                    if (tick % 2 == 0) _usb.value = withContext(Dispatchers.IO) { readUsbStatus() }
                }
                tick++
                delay(500)
            }
        }
    }

    fun setScreenCurtain(mode: ScreenCurtainMode) {
        _screenCurtain.value = mode
        uiPrefs.edit { putString("screenCurtain", mode.key) }
    }

    fun setMirrorPreview(enabled: Boolean) {
        _mirrorPreview.value = enabled
        uiPrefs.edit { putBoolean("mirrorPreview", enabled) }
    }

    // ---- Stopped-only settings (see StoppedSettingsStore) -----------------------

    fun setStoppedAccessMode(mode: String) = whileStopped {
        StoppedSettingsStore.writeConnection(getApplication(), accessMode = mode)
    }

    fun setStoppedPort(port: Int) = whileStopped {
        if (port in 1024..65535) StoppedSettingsStore.writeConnection(getApplication(), port = port)
    }

    fun regenerateStoppedToken() = whileStopped {
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        StoppedSettingsStore.writeConnection(getApplication(), accessToken = token)
    }

    fun setStoppedPhonePreview(enabled: Boolean) = whileStopped {
        StoppedSettingsStore.writePhonePreview(getApplication(), enabled)
    }

    private fun whileStopped(write: () -> Unit) {
        // A running service owns these settings; its controller must apply them.
        if (ServiceBridge.isServiceRunning) return
        write()
        _stoppedSettings.value = StoppedSettingsStore.read(getApplication())
    }

    private fun readUsbStatus(): UsbStatus {
        val context: Context = getApplication()
        val debugging = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        // The sticky USB_STATE broadcast reports a data connection to a host and
        // whether the adb function is active. Fall back to USB charging state.
        val usbState = try {
            ContextCompat.registerReceiver(
                context,
                null,
                IntentFilter("android.hardware.usb.action.USB_STATE"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            null
        }
        if (usbState != null) {
            return UsbStatus(
                connected = usbState.getBooleanExtra("connected", false),
                debugging = debugging || usbState.getBooleanExtra("adb", false)
            )
        }
        val battery = try {
            ContextCompat.registerReceiver(
                context,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            null
        }
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return UsbStatus(connected = plugged == BatteryManager.BATTERY_PLUGGED_USB, debugging = debugging)
    }
}
