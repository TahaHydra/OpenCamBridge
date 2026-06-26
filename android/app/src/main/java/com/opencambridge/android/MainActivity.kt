package com.opencambridge.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.opencambridge.android.camera.CameraInfoDto
import com.opencambridge.android.service.StreamService
import com.opencambridge.android.state.LogEntry
import kotlin.math.roundToInt

private const val TAG = "MainActivity"

enum class NavTab { Stream, Controls, Security, Logs }

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            startStreamService()
        } else {
            Log.w(TAG, "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initial rotation
        updateServiceTargetRotation()
        
        setContent {
            OpenCamBridgeTheme {
                val isStreaming by viewModel.isStreaming.collectAsState()
                val wifiIp by viewModel.wifiIp.collectAsState()
                val cameras by viewModel.cameras.collectAsState()
                
                val selectedCameraId by viewModel.selectedCameraId.collectAsState()
                val width by viewModel.width.collectAsState()
                val height by viewModel.height.collectAsState()
                val fps by viewModel.fps.collectAsState()
                val jpegQuality by viewModel.jpegQuality.collectAsState()
                val previewFitMode by viewModel.previewFitMode.collectAsState()
                val aspectRatio by viewModel.aspectRatio.collectAsState()
                val zoomSpeed by viewModel.zoomSpeed.collectAsState()
                
                val localPreviewEnabled by viewModel.localPreviewEnabled.collectAsState()
                val rebindInProgress by viewModel.rebindInProgress.collectAsState()
                val torchEnabled by viewModel.torchEnabled.collectAsState()
                val hasTorch by viewModel.hasTorch.collectAsState()
                val linearZoom by viewModel.linearZoom.collectAsState()
                val rotationDegrees by viewModel.rotationDegrees.collectAsState()
                val displayRotation by viewModel.displayRotation.collectAsState()
                val mirror by viewModel.mirror.collectAsState()
                
                val accessMode by viewModel.accessMode.collectAsState()
                val port by viewModel.port.collectAsState()
                val accessToken by viewModel.accessToken.collectAsState()
                val streamMode by viewModel.streamMode.collectAsState()
                val logs by viewModel.logs.collectAsState()

                MainScreen(
                    isStreaming = isStreaming,
                    wifiIp = wifiIp,
                    cameras = cameras,
                    selectedCameraId = selectedCameraId,
                    width = width,
                    height = height,
                    fps = fps,
                    jpegQuality = jpegQuality,
                    previewFitMode = previewFitMode,
                    aspectRatio = aspectRatio,
                    zoomSpeed = zoomSpeed,
                    localPreviewEnabled = localPreviewEnabled,
                    rebindInProgress = rebindInProgress,
                    torchEnabled = torchEnabled,
                    hasTorch = hasTorch,
                    linearZoom = linearZoom,
                    rotationDegrees = rotationDegrees,
                    displayRotation = displayRotation,
                    mirror = mirror,
                    accessMode = accessMode,
                    port = port,
                    accessToken = accessToken,
                    streamMode = streamMode,
                    logs = logs,
                    onStartStop = { if (isStreaming) viewModel.stopStream() else requestPermissionsAndStart() },
                    onCameraSelect = { viewModel.selectCamera(it) },
                    onResolutionSelect = { w, h -> viewModel.updateResolution(w, h) },
                    onFpsSelect = { viewModel.updateFps(it) },
                    onJpegQualitySelect = { viewModel.updateJpegQuality(it) },
                    onPreviewFitModeSelect = { viewModel.updatePreviewFitMode(it) },
                    onAspectRatioSelect = { viewModel.updateAspectRatio(it) },
                    onZoomSpeedSelect = { viewModel.updateZoomSpeed(it) },
                    onTogglePreview = { viewModel.toggleLocalPreview(it) },
                    onToggleTorch = { viewModel.updateTorch(it) },
                    onZoomSelect = { viewModel.updateZoom(it) },
                    onDisplayRotationSelect = { viewModel.updateDisplayRotation(it) },
                    onMirrorSelect = { viewModel.updateMirror(it) },
                    onStreamModeSelect = { viewModel.updateStreamMode(it) },
                    onAccessModeSelect = { viewModel.updateAccessMode(it) },
                    onPortSelect = { viewModel.updatePort(it) },
                    onRegenerateToken = { viewModel.regenerateToken() },
                    onClearLogs = { viewModel.clearLogs() },
                    viewModel = viewModel
                )
            }
        }
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

    private fun startStreamService() {
        val intent = StreamService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopStreamService() {
        stopService(StreamService.startIntent(this))
    }

    private fun updateServiceTargetRotation() {
        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        com.opencambridge.android.state.StreamState.previewUseCase?.targetRotation = displayRotation
        com.opencambridge.android.state.StreamState.imageAnalysisUseCase?.targetRotation = displayRotation
    }
}

// ---- Composables ----

@Composable
fun OpenCamBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF121317),
            surface = Color(0xFF1A1B1F),
            primary = Color(0xFF00E5FF),
            tertiary = Color(0xFF4CAF50),
            error = Color(0xFFFF5252),
            onPrimary = Color.Black,
            onBackground = Color(0xFFE0E0E0),
            onSurface = Color(0xFFE0E0E0)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isStreaming: Boolean,
    wifiIp: String?,
    cameras: List<CameraInfoDto>,
    selectedCameraId: String,
    width: Int,
    height: Int,
    fps: Int,
    jpegQuality: Int,
    previewFitMode: String,
    aspectRatio: String,
    zoomSpeed: String,
    localPreviewEnabled: Boolean,
    rebindInProgress: Boolean,
    torchEnabled: Boolean,
    hasTorch: Boolean,
    linearZoom: Float,
    rotationDegrees: Int,
    displayRotation: String,
    mirror: Boolean,
    accessMode: String,
    port: Int,
    accessToken: String,
    streamMode: String,
    logs: List<LogEntry>,
    onStartStop: () -> Unit,
    onCameraSelect: (String) -> Unit,
    onResolutionSelect: (Int, Int) -> Unit,
    onFpsSelect: (Int) -> Unit,
    onJpegQualitySelect: (Int) -> Unit,
    onPreviewFitModeSelect: (String) -> Unit,
    onAspectRatioSelect: (String) -> Unit,
    onZoomSpeedSelect: (String) -> Unit,
    onTogglePreview: (Boolean) -> Unit,
    onToggleTorch: (Boolean) -> Unit,
    onZoomSelect: (Float) -> Unit,
    onDisplayRotationSelect: (String) -> Unit,
    onMirrorSelect: (Boolean) -> Unit,
    onStreamModeSelect: (String) -> Unit,
    onAccessModeSelect: (String) -> Unit,
    onPortSelect: (Int) -> Unit,
    onRegenerateToken: () -> Unit,
    onClearLogs: () -> Unit,
    viewModel: StreamViewModel
) {
    var currentTab by remember { mutableStateOf(NavTab.Stream) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                    label = { Text("Stream") },
                    selected = currentTab == NavTab.Stream,
                    onClick = { currentTab = NavTab.Stream }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Controls") },
                    selected = currentTab == NavTab.Controls,
                    onClick = { currentTab = NavTab.Controls }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Security, contentDescription = null) },
                    label = { Text("Security") },
                    selected = currentTab == NavTab.Security,
                    onClick = { currentTab = NavTab.Security }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Logs") },
                    selected = currentTab == NavTab.Logs,
                    onClick = { currentTab = NavTab.Logs }
                )
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            when (currentTab) {
                NavTab.Stream -> {
                    StreamTabContent(isLandscape, localPreviewEnabled, previewFitMode, viewModel, isStreaming, wifiIp, onStartStop, rebindInProgress, onTogglePreview)
                }
                NavTab.Controls -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MainControls(
                            isStreaming, wifiIp, cameras, selectedCameraId, width, height, fps, jpegQuality,
                            previewFitMode, aspectRatio, zoomSpeed, localPreviewEnabled, rebindInProgress,
                            torchEnabled, hasTorch, linearZoom, rotationDegrees, displayRotation, mirror, streamMode,
                            onStartStop, onCameraSelect, onResolutionSelect, onFpsSelect, onJpegQualitySelect,
                            onPreviewFitModeSelect, onAspectRatioSelect, onZoomSpeedSelect, onTogglePreview,
                            onToggleTorch, onZoomSelect, onDisplayRotationSelect, onMirrorSelect, onStreamModeSelect
                        )
                    }
                }
                NavTab.Security -> {
                    SecurityTabContent(accessMode, port, accessToken, onAccessModeSelect, onPortSelect, onRegenerateToken)
                }
                NavTab.Logs -> {
                    LogsTabContent(logs, onClearLogs)
                }
            }
        }
    }
}

@Composable
fun StreamTabContent(
    isLandscape: Boolean,
    localPreviewEnabled: Boolean,
    previewFitMode: String,
    viewModel: StreamViewModel,
    isStreaming: Boolean,
    wifiIp: String?,
    onStartStop: () -> Unit,
    rebindInProgress: Boolean,
    onTogglePreview: (Boolean) -> Unit
) {
    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Left side: Preview
            Box(modifier = Modifier.weight(0.6f).fillMaxHeight().background(Color.Black, RoundedCornerShape(8.dp))) {
                if (localPreviewEnabled) {
                    LocalPreviewBox(previewFitMode, viewModel)
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Preview disabled", color = Color.Gray, fontSize = 18.sp)
                        Text("Stream available from Web/USB", color = Color.DarkGray, fontSize = 14.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Right side: Stream Status/Controls
            Column(
                modifier = Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("OpenCamBridge", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.weight(1f))
                    if (rebindInProgress) {
                        Text("Rebinding...", color = Color(0xFFFBC02D), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            
                // Status Card
                StatusCard(isStreaming, wifiIp)
                Spacer(modifier = Modifier.height(16.dp))
                
                // Connection / Stream Control
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Button(
                            onClick = onStartStop,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(imageVector = if (isStreaming) Icons.Default.VideocamOff else Icons.Default.Videocam, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isStreaming) "STOP STREAM" else "START STREAM", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Preview on phone", color = if (!rebindInProgress) Color.Unspecified else Color.Gray)
                                Text(if (localPreviewEnabled) "Enabled" else "Disabled", color = Color.Gray, fontSize = 12.sp)
                            }
                            Switch(checked = localPreviewEnabled, enabled = !rebindInProgress, onCheckedChange = onTogglePreview, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("OpenCamBridge", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.weight(1f))
                if (rebindInProgress) {
                    Text("Rebinding...", color = Color(0xFFFBC02D), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Top Preview
            if (localPreviewEnabled) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black, RoundedCornerShape(8.dp))) {
                    LocalPreviewBox(previewFitMode, viewModel)
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color(0xFF000000), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Preview disabled", color = Color.Gray, fontSize = 18.sp)
                        Text("Stream available from Web/USB", color = Color.DarkGray, fontSize = 14.sp)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Status Card
            StatusCard(isStreaming, wifiIp)
            Spacer(modifier = Modifier.height(16.dp))
            
            // Connection / Stream Control
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = onStartStop,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(imageVector = if (isStreaming) Icons.Default.VideocamOff else Icons.Default.Videocam, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isStreaming) "STOP STREAM" else "START STREAM", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Preview on phone", color = if (!rebindInProgress) Color.Unspecified else Color.Gray)
                            Text(if (localPreviewEnabled) "Enabled" else "Disabled", color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(checked = localPreviewEnabled, enabled = !rebindInProgress, onCheckedChange = onTogglePreview, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

@Composable
fun MainControls(
    isStreaming: Boolean, wifiIp: String?, cameras: List<CameraInfoDto>, selectedCameraId: String,
    width: Int, height: Int, fps: Int, jpegQuality: Int, previewFitMode: String, aspectRatio: String,
    zoomSpeed: String, localPreviewEnabled: Boolean, rebindInProgress: Boolean, torchEnabled: Boolean,
    hasTorch: Boolean, linearZoom: Float, rotationDegrees: Int, displayRotation: String, mirror: Boolean, streamMode: String,
    onStartStop: () -> Unit, onCameraSelect: (String) -> Unit, onResolutionSelect: (Int, Int) -> Unit,
    onFpsSelect: (Int) -> Unit, onJpegQualitySelect: (Int) -> Unit, onPreviewFitModeSelect: (String) -> Unit,
    onAspectRatioSelect: (String) -> Unit, onZoomSpeedSelect: (String) -> Unit, onTogglePreview: (Boolean) -> Unit,
    onToggleTorch: (Boolean) -> Unit, onZoomSelect: (Float) -> Unit, onDisplayRotationSelect: (String) -> Unit,
    onMirrorSelect: (Boolean) -> Unit, onStreamModeSelect: (String) -> Unit
) {
    if (cameras.isNotEmpty()) {
        // Camera Config
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Camera Configuration", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                CameraSelector(cameras, selectedCameraId, !rebindInProgress, onCameraSelect)
                Spacer(modifier = Modifier.height(16.dp))
                ResolutionSelector(width, height, !rebindInProgress, onResolutionSelect)
                Spacer(modifier = Modifier.height(16.dp))

                FpsSelector(fps, !rebindInProgress, onFpsSelect)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Image Controls
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Image Controls", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                StreamModeSelector(streamMode, !rebindInProgress, onStreamModeSelect)
                if (streamMode == "h264") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("H.264 stream available at /stream.h264. MJPEG preview is running in background at 5 FPS.", color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
                QualitySlider(jpegQuality, !rebindInProgress, onJpegQualitySelect)
                Spacer(modifier = Modifier.height(16.dp))
                ZoomSpeedSelector(zoomSpeed, !rebindInProgress, onZoomSpeedSelect)
                Spacer(modifier = Modifier.height(16.dp))
                ZoomSlider(linearZoom, !rebindInProgress, onZoomSelect)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Torch / Lamp", color = if (hasTorch && !rebindInProgress) Color.Unspecified else Color.Gray)
                        if (!hasTorch) Text("Unsupported", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(checked = torchEnabled, enabled = hasTorch && !rebindInProgress, onCheckedChange = onToggleTorch, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Output & Display
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Output & Display", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                FitModeSelector(previewFitMode, !rebindInProgress, onPreviewFitModeSelect)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(
                        onClick = {
                            val currentRot = displayRotation.toIntOrNull() ?: 0
                            val newRot = (currentRot + 90) % 360
                            onDisplayRotationSelect(newRot.toString())
                        },
                        enabled = !rebindInProgress,
                        modifier = Modifier.fillMaxWidth(0.5f).padding(end = 8.dp)
                    ) {
                        Text("↻ Rotate 90°")
                    }
                    Button(
                        onClick = { onMirrorSelect(!mirror) },
                        enabled = !rebindInProgress,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp)
                    ) {
                        Text(if (mirror) "Mirror: ON" else "Mirror: OFF")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutputOrientationSelector(aspectRatio, displayRotation, !rebindInProgress) { ar, rot ->
                    onAspectRatioSelect(ar)
                    onDisplayRotationSelect(rot)
                }
                // OutputOrientationSelector removed the old Mirror switch
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    } else {
        Text("No cameras found", color = Color.Red)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun LocalPreviewBox(fitMode: String, viewModel: StreamViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, RoundedCornerShape(8.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Provide the surface to StreamState via ViewModel
                    viewModel.setSurfaceProvider(this.surfaceProvider)
                }
            },
            update = { previewView ->
                previewView.scaleType = when (fitMode) {
                    "fit" -> PreviewView.ScaleType.FIT_CENTER
                    "fill" -> PreviewView.ScaleType.FILL_CENTER
                    "stretch" -> PreviewView.ScaleType.FIT_CENTER // No exact stretch in PreviewView, fallback to fit
                    else -> PreviewView.ScaleType.FIT_CENTER
                }
            },
            onRelease = {
                // When Stream tab is unmounted, properly detach the surface without stopping CameraX
                viewModel.setSurfaceProvider(null)
            }
        )
    }
}

@Composable
fun StatusCard(isStreaming: Boolean, wifiIp: String?) {
    val containerColor = if (isStreaming) Color(0xFF1A3320) else MaterialTheme.colorScheme.surface
    val borderColor = if (isStreaming) MaterialTheme.colorScheme.tertiary else Color(0xFF37474F)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(if (isStreaming) MaterialTheme.colorScheme.tertiary else Color(0xFF90A4AE), shape = RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "Streaming" else "Stopped",
                    fontWeight = FontWeight.Bold,
                    color = if (isStreaming) MaterialTheme.colorScheme.tertiary else Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (wifiIp != null) "Wi-Fi: http://$wifiIp:8080" else "Wi-Fi not connected",
                fontSize = 13.sp,
                color = Color(0xFF90A4AE)
            )
            Text(
                text = "USB: http://127.0.0.1:8080 (via adb)",
                fontSize = 13.sp,
                color = Color(0xFF90A4AE)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSelector(
    cameras: List<CameraInfoDto>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedCamera = cameras.find { it.id == selectedId } ?: cameras.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "${selectedCamera.label} (${selectedCamera.id})",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Camera") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF37474F)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            cameras.forEach { camera ->
                DropdownMenuItem(
                    text = { Text("${camera.label} (${camera.id})") },
                    onClick = {
                        onSelect(camera.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolutionSelector(width: Int, height: Int, enabled: Boolean, onSelect: (Int, Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        Pair(1920, 1080),
        Pair(1280, 720),
        Pair(640, 480)
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "${width}x${height}",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Resolution") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF37474F)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (w, h) ->
                DropdownMenuItem(
                    text = { Text("${w}x${h}") },
                    onClick = {
                        onSelect(w, h)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsSelector(fps: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(15, 30, 60)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "$fps fps",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("FPS Limit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF37474F)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text("$opt fps") },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitModeSelector(fitMode: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("fill", "fit")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        val labels = mapOf(
            "fill" to "Fill (Cover, no black bars)",
            "fit" to "Fit (Contain, full frame)"
        )
        OutlinedTextField(
            value = labels[fitMode] ?: fitMode.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Preview Fit Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF37474F)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(labels[opt] ?: opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputOrientationSelector(aspectRatio: String, displayRotation: String, enabled: Boolean, onSelect: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    val options = listOf(
        Pair("16:9", "0"),
        Pair("9:16", "90"),
        Pair("9:16", "270"),
        Pair("16:9", "180")
    )
    val labels = mapOf(
        Pair("16:9", "0") to "Landscape (0°)",
        Pair("9:16", "90") to "Portrait CW (90°)",
        Pair("9:16", "270") to "Portrait CCW (270°)",
        Pair("16:9", "180") to "Upside Down (180°)"
    )
    
    val currentKey = Pair(aspectRatio, displayRotation)
    val currentValue = labels[currentKey] ?: "Landscape (0°)"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = currentValue,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Output Orientation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF37474F)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(labels[opt] ?: "") },
                    onClick = {
                        onSelect(opt.first, opt.second)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoomSpeedSelector(zoomSpeed: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("slow", "normal", "fast")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = zoomSpeed.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Zoom Speed") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF37474F)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun QualitySlider(quality: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "JPEG Quality: $quality%", color = if (enabled) Color(0xFFE0E0E0) else Color.Gray, fontSize = 14.sp)
        Slider(
            value = quality.toFloat(),
            onValueChange = { onSelect(it.roundToInt()) },
            valueRange = 10f..100f,
            steps = 90,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4FC3F7),
                activeTrackColor = Color(0xFF4FC3F7),
                inactiveTrackColor = Color(0xFF37474F)
            )
        )
    }
}

@Composable
fun ZoomSlider(zoom: Float, enabled: Boolean, onSelect: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Linear Zoom: ${String.format("%.2f", zoom)}", color = if (enabled) Color(0xFFE0E0E0) else Color.Gray, fontSize = 14.sp)
        Slider(
            value = zoom,
            onValueChange = onSelect,
            valueRange = 0f..1f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4FC3F7),
                activeTrackColor = Color(0xFF4FC3F7),
                inactiveTrackColor = Color(0xFF37474F)
            )
        )
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityTabContent(
    accessMode: String,
    port: Int,
    accessToken: String,
    onAccessModeSelect: (String) -> Unit,
    onPortSelect: (Int) -> Unit,
    onRegenerateToken: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Security Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                var expanded by remember { mutableStateOf(false) }
                val modeOptions = listOf("usbOnly", "lanOpen", "lanToken")
                val modeLabels = mapOf(
                    "usbOnly" to "USB Only (Localhost)",
                    "lanOpen" to "LAN Open (Unsafe)",
                    "lanToken" to "LAN Token (Secure)"
                )
                
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = modeLabels[accessMode] ?: accessMode,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Access Mode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        modeOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(modeLabels[opt] ?: opt) },
                                onClick = { onAccessModeSelect(opt); expanded = false }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                if (accessMode == "lanOpen") {
                    Text("Warning: LAN Open allows anyone on your network to view the stream and control the camera.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                } else if (accessMode == "lanToken") {
                    Text("Token required for LAN access. You must append ?token=... to the URL.", color = Color(0xFF4CAF50), fontSize = 12.sp)
                } else {
                    Text("Server bound to 127.0.0.1. Accessible via adb forward.", color = Color.Gray, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = port.toString(),
                    onValueChange = { 
                        val newPort = it.toIntOrNull()
                        if (newPort != null && newPort in 1024..65535) onPortSelect(newPort)
                    },
                    label = { Text("Port (1024-65535)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Note: Changing the port requires restarting the stream to take effect.", color = Color.Gray, fontSize = 12.sp)

                if (accessMode == "lanToken") {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = accessToken,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Access Token") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRegenerateToken, modifier = Modifier.fillMaxWidth()) {
                        Text("Regenerate Token")
                    }
                }
            }
        }
    }
}

@Composable
fun LogsTabContent(logs: List<LogEntry>, onClearLogs: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("System Logs", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onClearLogs) {
                Text("Clear")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(scrollState)) {
                if (logs.isEmpty()) {
                    Text("No logs.", color = Color.Gray)
                } else {
                    logs.forEach { log ->
                        val color = when (log.level) {
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "WARN" -> Color(0xFFFBC02D)
                            else -> Color(0xFFE0E0E0)
                        }
                        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(log.timestamp))
                        Text(
                            text = "[$timeStr] ${log.level} [${log.source}]: ${log.message}",
                            color = color,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamModeSelector(mode: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        Pair("mjpeg", "MJPEG"),
        Pair("h264", "H.264 (Low Latency)")
    )
    val displayMode = options.find { it.first == mode }?.second ?: mode

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = displayMode,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Stream Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF37474F)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
