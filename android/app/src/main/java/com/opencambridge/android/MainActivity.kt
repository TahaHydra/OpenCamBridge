package com.opencambridge.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.opencambridge.android.camera.CameraInfoDto
import com.opencambridge.android.service.StreamService

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()

    // Permission launcher — requests CAMERA + POST_NOTIFICATIONS then starts service
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

                MainScreen(
                    isStreaming = isStreaming,
                    wifiIp = wifiIp,
                    cameras = cameras,
                    selectedCameraId = selectedCameraId,
                    onStartStop = { if (isStreaming) stopStreamService() else requestPermissionsAndStart() },
                    onCameraSelect = { viewModel.selectCamera(it) }
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
            primary = Color(0xFF4FC3F7),
            onPrimary = Color(0xFF003549),
            surface = Color(0xFF0D1B2A),
            onSurface = Color(0xFFE0E0E0),
            background = Color(0xFF0D1B2A),
            onBackground = Color(0xFFE0E0E0)
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
    onStartStop: () -> Unit,
    onCameraSelect: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D1B2A), Color(0xFF1B2838))
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Title
            Text(
                text = "OpenCamBridge",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4FC3F7)
            )
            Text(
                text = "Phone as webcam over Wi-Fi · USB",
                fontSize = 13.sp,
                color = Color(0xFF90A4AE)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status card
            StatusCard(isStreaming = isStreaming, wifiIp = wifiIp)

            // Camera selector
            if (cameras.isNotEmpty()) {
                CameraSelector(
                    cameras = cameras,
                    selectedId = selectedCameraId,
                    onSelect = onCameraSelect
                )
            }

            // Start / Stop button
            Button(
                onClick = onStartStop,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) Color(0xFFEF5350) else Color(0xFF4FC3F7),
                    contentColor = Color(0xFF0D1B2A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = if (isStreaming) "Stop Stream" else "Start Stream",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            if (isStreaming && wifiIp != null) {
                Text(
                    text = "Stream: http://$wifiIp:8080/stream.mjpeg",
                    fontSize = 12.sp,
                    color = Color(0xFF80CBC4)
                )
            }
        }
    }
}

@Composable
fun StatusCard(isStreaming: Boolean, wifiIp: String?) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2838)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (isStreaming) Color(0xFF66BB6A) else Color(0xFF90A4AE),
                            shape = RoundedCornerShape(50)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "Streaming" else "Idle",
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE0E0E0)
                )
            }
            Text(
                text = if (wifiIp != null) "IP: $wifiIp" else "Wi-Fi not connected",
                fontSize = 13.sp,
                color = Color(0xFF90A4AE)
            )
            Text(
                text = "Port: 8080",
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
            value = "Camera ${selectedCamera.id} (${selectedCamera.facing})",
            onValueChange = {},
            readOnly = true,
            label = { Text("Camera") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color(0xFF37474F),
                focusedLabelColor = Color(0xFF4FC3F7),
                focusedTextColor = Color(0xFFE0E0E0),
                unfocusedTextColor = Color(0xFFE0E0E0)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            cameras.forEach { camera ->
                DropdownMenuItem(
                    text = { Text("Camera ${camera.id} — ${camera.facing}") },
                    onClick = {
                        onSelect(camera.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
