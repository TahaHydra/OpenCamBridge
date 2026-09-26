package com.opencambridge.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.opencambridge.android.service.ServiceBridge
import com.opencambridge.android.service.StreamService
import com.opencambridge.android.state.AppLogger
import com.opencambridge.android.state.StreamState
import com.opencambridge.android.ui.OpenCamBridgeTheme
import com.opencambridge.android.ui.PhoneUiViewModel
import com.opencambridge.android.ui.screens.OpenCamBridgeApp

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()
    private val uiViewModel: PhoneUiViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            startStreamService()
        } else {
            Log.w(TAG, "Camera permission denied")
            viewModel.reportServiceStartFailure("OpenCamBridge needs camera access to start.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System bars are transparent (see themes.xml), so the app draws behind
        // them and each screen supplies its own insets.
        enableEdgeToEdge()

        // Initial rotation
        updateServiceTargetRotation()

        setContent {
            OpenCamBridgeTheme {
                // Start and Stop are this Activity's lifecycle operations, exactly
                // as before: Start launches the service (or, after a remote camera
                // stop, restarts the camera in-process); Stop tears everything
                // down. The screens only decide what to show.
                OpenCamBridgeApp(
                    viewModel = viewModel,
                    ui = uiViewModel,
                    onStart = { startCameraOrService() },
                    onStop = { stopEverything() },
                    onRetryStreamStart = { requestPermissionsAndStart() }
                )
            }
        }
        // Opening the Activity is deliberately side-effect free. A stopped app
        // stays stopped until the user presses Start. If a user-started
        // foreground service is already streaming, the ViewModel observes that
        // existing state without starting a second service.
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startStreamService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCameraOrService() {
        // A remote control can stop only the camera while intentionally leaving
        // the control server alive. In that case restart the existing pipeline
        // in-process instead of trying to bind port 8080 a second time.
        if (ServiceBridge.isServiceRunning) {
            viewModel.startStream()
        } else {
            requestPermissionsAndStart()
        }
    }

    /**
     * Stop means STOP: nothing of this app keeps running afterwards.
     *
     * Previously the button only tore down the camera pipeline and deliberately
     * left the foreground service alive so the desktop could start the camera
     * again remotely. What survived was not free: the HTTP control server stayed
     * bound to its port, the screen-unlock receiver stayed registered, and the
     * OrientationEventListener kept polling the accelerometer — which is a real
     * background drain and is why Android was killing the app's other work.
     *
     * The service's explicit stop action serializes camera shutdown through the
     * pipeline controller before stopSelf(). onDestroy then takes down the
     * control server, listener, receiver, wakelock, and notification.
     *
     * The cost is stated in the UI: with the server gone the desktop cannot start
     * the camera again, so the next start has to come from this phone.
     */
    private fun stopEverything() {
        if (ServiceBridge.isServiceRunning) {
            // The service is already a foreground service, so this is a normal
            // command to that live instance, not a new background launch.
            startService(StreamService.stopIntent(this))
        } else {
            // A stale UI snapshot must still leave no service behind.
            stopService(StreamService.startIntent(this))
        }
    }

    private fun startStreamService() {
        viewModel.clearServiceStartError()
        val intent = StreamService.startIntent(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Keep the original Throwable/stack in logcat for engineering
            // diagnosis. App/UI state receives a deliberately sanitized
            // message so an exception cannot leak intent extras or secrets.
            val safeMessage = "Unable to start the camera service (${e.javaClass.simpleName})."
            Log.e(TAG, safeMessage, e)
            AppLogger.e("System", safeMessage)
            StreamState.lastError.set(safeMessage)
            viewModel.reportServiceStartFailure(safeMessage)
        }
    }

    private fun updateServiceTargetRotation() {
        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        StreamState.previewUseCase?.targetRotation = displayRotation
        StreamState.imageAnalysisUseCase?.targetRotation = displayRotation
    }
}
