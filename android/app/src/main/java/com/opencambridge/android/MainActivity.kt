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
import kotlin.math.roundToInt

private const val TAG = "MainActivity"

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
                    onStartStop = { if (isStreaming) stopStreamService() else requestPermissionsAndStart() },
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
    displayRotation: Int,
    mirror: Boolean,
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
    onDisplayRotationSelect: (Int) -> Unit,
    onMirrorSelect: (Boolean) -> Unit,
    viewModel: StreamViewModel
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
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
                
                // Right side: Controls
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MainControls(
                        isStreaming, wifiIp, cameras, selectedCameraId, width, height, fps, jpegQuality,
                        previewFitMode, aspectRatio, zoomSpeed, localPreviewEnabled, rebindInProgress,
                        torchEnabled, hasTorch, linearZoom, rotationDegrees, displayRotation, mirror,
                        onStartStop, onCameraSelect, onResolutionSelect, onFpsSelect, onJpegQualitySelect,
                        onPreviewFitModeSelect, onAspectRatioSelect, onZoomSpeedSelect, onTogglePreview,
                        onToggleTorch, onZoomSelect, onDisplayRotationSelect, onMirrorSelect
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                
                MainControls(
                    isStreaming, wifiIp, cameras, selectedCameraId, width, height, fps, jpegQuality,
                    previewFitMode, aspectRatio, zoomSpeed, localPreviewEnabled, rebindInProgress,
                    torchEnabled, hasTorch, linearZoom, rotationDegrees, displayRotation, mirror,
                    onStartStop, onCameraSelect, onResolutionSelect, onFpsSelect, onJpegQualitySelect,
                    onPreviewFitModeSelect, onAspectRatioSelect, onZoomSpeedSelect, onTogglePreview,
                    onToggleTorch, onZoomSelect, onDisplayRotationSelect, onMirrorSelect
                )
            }
        }
    }
}

@Composable
fun MainControls(
    isStreaming: Boolean, wifiIp: String?, cameras: List<CameraInfoDto>, selectedCameraId: String,
    width: Int, height: Int, fps: Int, jpegQuality: Int, previewFitMode: String, aspectRatio: String,
    zoomSpeed: String, localPreviewEnabled: Boolean, rebindInProgress: Boolean, torchEnabled: Boolean,
    hasTorch: Boolean, linearZoom: Float, rotationDegrees: Int, displayRotation: Int, mirror: Boolean,
    onStartStop: () -> Unit, onCameraSelect: (String) -> Unit, onResolutionSelect: (Int, Int) -> Unit,
    onFpsSelect: (Int) -> Unit, onJpegQualitySelect: (Int) -> Unit, onPreviewFitModeSelect: (String) -> Unit,
    onAspectRatioSelect: (String) -> Unit, onZoomSpeedSelect: (String) -> Unit, onTogglePreview: (Boolean) -> Unit,
    onToggleTorch: (Boolean) -> Unit, onZoomSelect: (Float) -> Unit, onDisplayRotationSelect: (Int) -> Unit,
    onMirrorSelect: (Boolean) -> Unit
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
    Spacer(modifier = Modifier.height(16.dp))

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
                AspectRatioSelector(aspectRatio, !rebindInProgress, onAspectRatioSelect)
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
                DisplayRotationSelector(displayRotation, !rebindInProgress, onDisplayRotationSelect)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Mirror Image", color = if (!rebindInProgress) Color.Unspecified else Color.Gray)
                    Switch(checked = mirror, enabled = !rebindInProgress, onCheckedChange = onMirrorSelect, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
                }
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
    val options = listOf("fit", "fill", "stretch", "original")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = fitMode.replaceFirstChar { it.uppercase() },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AspectRatioSelector(aspectRatio: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("auto", "16:9", "4:3")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = aspectRatio.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Stream Aspect Ratio") },
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
fun DisplayRotationSelector(rotation: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(0, 90, 180, 270)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "$rotation°",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Display Rotation") },
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
                    text = { Text("$opt°") },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}
