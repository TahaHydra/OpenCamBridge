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
                
                val localPreviewEnabled by viewModel.localPreviewEnabled.collectAsState()
                val rebindInProgress by viewModel.rebindInProgress.collectAsState()
                val torchEnabled by viewModel.torchEnabled.collectAsState()
                val linearZoom by viewModel.linearZoom.collectAsState()

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
                    localPreviewEnabled = localPreviewEnabled,
                    rebindInProgress = rebindInProgress,
                    torchEnabled = torchEnabled,
                    linearZoom = linearZoom,
                    onStartStop = { if (isStreaming) stopStreamService() else requestPermissionsAndStart() },
                    onCameraSelect = { viewModel.selectCamera(it) },
                    onResolutionSelect = { w, h -> viewModel.updateResolution(w, h) },
                    onFpsSelect = { viewModel.updateFps(it) },
                    onJpegQualitySelect = { viewModel.updateJpegQuality(it) },
                    onPreviewFitModeSelect = { viewModel.updatePreviewFitMode(it) },
                    onTogglePreview = { viewModel.toggleLocalPreview(it) },
                    onToggleTorch = { viewModel.updateTorch(it) },
                    onZoomSelect = { viewModel.updateZoom(it) },
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
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            primary = Color(0xFF4FC3F7),
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
    localPreviewEnabled: Boolean,
    rebindInProgress: Boolean,
    torchEnabled: Boolean,
    linearZoom: Float,
    onStartStop: () -> Unit,
    onCameraSelect: (String) -> Unit,
    onResolutionSelect: (Int, Int) -> Unit,
    onFpsSelect: (Int) -> Unit,
    onJpegQualitySelect: (Int) -> Unit,
    onPreviewFitModeSelect: (String) -> Unit,
    onTogglePreview: (Boolean) -> Unit,
    onToggleTorch: (Boolean) -> Unit,
    onZoomSelect: (Float) -> Unit,
    viewModel: StreamViewModel
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "OpenCamBridge",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (rebindInProgress) {
                Text("Rebinding camera...", color = Color(0xFFFBC02D), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))

            StatusCard(isStreaming, wifiIp)
            Spacer(modifier = Modifier.height(24.dp))
            
            // Optional local preview
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Preview on phone")
                Switch(
                    checked = localPreviewEnabled,
                    onCheckedChange = onTogglePreview,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
            }
            if (localPreviewEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                LocalPreviewBox(previewFitMode, viewModel)
                Spacer(modifier = Modifier.height(24.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (cameras.isNotEmpty()) {
                CameraSelector(cameras, selectedCameraId, onCameraSelect)
                Spacer(modifier = Modifier.height(16.dp))
                
                ResolutionSelector(width, height, onResolutionSelect)
                Spacer(modifier = Modifier.height(16.dp))

                FpsSelector(fps, onFpsSelect)
                Spacer(modifier = Modifier.height(16.dp))
                
                FitModeSelector(previewFitMode, onPreviewFitModeSelect)
                Spacer(modifier = Modifier.height(16.dp))

                QualitySlider(jpegQuality, onJpegQualitySelect)
                Spacer(modifier = Modifier.height(16.dp))
                
                ZoomSlider(linearZoom, onZoomSelect)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Torch / Lamp")
                    Switch(checked = torchEnabled, onCheckedChange = onToggleTorch, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
                }
                Spacer(modifier = Modifier.height(32.dp))
            } else {
                Text("No cameras found", color = Color.Red)
                Spacer(modifier = Modifier.height(32.dp))
            }

            Button(
                onClick = onStartStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) Color(0xFFCF6679) else MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "STOP STREAM" else "START STREAM",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
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
    val gradient = if (isStreaming) {
        Brush.horizontalGradient(listOf(Color(0xFF2E7D32), Color(0xFF1B5E20)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF37474F), Color(0xFF263238)))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(gradient, shape = RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(if (isStreaming) Color(0xFF69F0AE) else Color(0xFF90A4AE), shape = RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "Streaming" else "Idle",
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE0E0E0)
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
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedCamera = cameras.find { it.id == selectedId } ?: cameras.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "${selectedCamera.label} (${selectedCamera.id})",
            onValueChange = {},
            readOnly = true,
            label = { Text("Camera") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
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
fun ResolutionSelector(width: Int, height: Int, onSelect: (Int, Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        Pair(1920, 1080),
        Pair(1280, 720),
        Pair(640, 480)
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "${width}x${height}",
            onValueChange = {},
            readOnly = true,
            label = { Text("Resolution") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
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
fun FpsSelector(fps: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(15, 30, 60)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "$fps fps",
            onValueChange = {},
            readOnly = true,
            label = { Text("FPS Limit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
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
fun FitModeSelector(fitMode: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("fit", "fill", "stretch", "original")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = fitMode.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Preview Fit Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
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
fun QualitySlider(quality: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "JPEG Quality: $quality%", color = Color(0xFFE0E0E0), fontSize = 14.sp)
        Slider(
            value = quality.toFloat(),
            onValueChange = { onSelect(it.roundToInt()) },
            valueRange = 10f..100f,
            steps = 90,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4FC3F7),
                activeTrackColor = Color(0xFF4FC3F7),
                inactiveTrackColor = Color(0xFF37474F)
            )
        )
    }
}

@Composable
fun ZoomSlider(zoom: Float, onSelect: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Linear Zoom: ${String.format("%.2f", zoom)}", color = Color(0xFFE0E0E0), fontSize = 14.sp)
        Slider(
            value = zoom,
            onValueChange = onSelect,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4FC3F7),
                activeTrackColor = Color(0xFF4FC3F7),
                inactiveTrackColor = Color(0xFF37474F)
            )
        )
    }
}
