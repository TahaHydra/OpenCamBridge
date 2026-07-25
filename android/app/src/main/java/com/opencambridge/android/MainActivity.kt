package com.opencambridge.android

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.opencambridge.android.service.ServiceBridge
import com.opencambridge.android.service.StreamService
import com.opencambridge.android.state.AppLogger
import com.opencambridge.android.state.H264SettingsPolicy
import com.opencambridge.android.state.LogEntry
import com.opencambridge.android.state.StreamState
import com.opencambridge.android.ui.Chip
import com.opencambridge.android.ui.Fader
import com.opencambridge.android.ui.KeyButton
import com.opencambridge.android.ui.Lamp
import com.opencambridge.android.ui.LampDot
import com.opencambridge.android.ui.Legend
import com.opencambridge.android.ui.MonoFamily
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.OpenCamBridgeTheme
import com.opencambridge.android.ui.Picker
import com.opencambridge.android.ui.ReadoutStyle
import com.opencambridge.android.ui.Section
import com.opencambridge.android.ui.Segmented
import com.opencambridge.android.ui.StageMeter
import com.opencambridge.android.ui.StatRow
import com.opencambridge.android.ui.SwitchRow
import com.opencambridge.android.ui.TallyBar
import com.opencambridge.android.ui.Well
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "MainActivity"

enum class NavTab(val label: String, val icon: ImageVector) {
    Stream("Stream", Icons.Default.Videocam),
    Camera("Camera", Icons.Default.CameraAlt),
    Output("Output", Icons.Default.Tune),
    Link("Link", Icons.Default.Security),
    Logs("Logs", Icons.AutoMirrored.Filled.List)
}

private class CameraPreviewContainer(context: Context) : FrameLayout(context) {
    private val texture = TextureView(context)
    private var cameraSurface: Surface? = null
    private var bufferWidth = 1280
    private var bufferHeight = 720
    // What the picture LOOKS like after the SurfaceTexture transform, which is
    // not the buffer geometry whenever that transform turns the image.
    private var visibleWidth = 1280
    private var visibleHeight = 720
    private var rotationDegrees = 0
    private var mirrored = false
    private var fitMode = "fill"
    var onSurfaceChanged: ((Surface?) -> Unit)? = null

    init {
        clipChildren = true
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
                cameraSurface?.release()
                cameraSurface = Surface(surfaceTexture).also { onSurfaceChanged?.invoke(it) }
                updateChildTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updateChildTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                onSurfaceChanged?.invoke(null)
                cameraSurface?.release()
                cameraSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    /**
     * @param bufferWidth,bufferHeight the size the CAMERA writes, used for
     *   `setDefaultBufferSize`. Always the encoded geometry, e.g. 1920x1080.
     * @param visibleWidth,visibleHeight the size the picture APPEARS to have once
     *   the SurfaceTexture's own transform has been applied. These differ from the
     *   buffer whenever that transform contributes a quarter turn, and the
     *   transform maths below needs the visible pair — using the buffer pair
     *   stretched an already-upright portrait picture out to landscape.
     */
    fun configure(
        bufferWidth: Int,
        bufferHeight: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        rotation: Int,
        mirror: Boolean,
        mode: String
    ) {
        this.bufferWidth = bufferWidth.coerceAtLeast(2)
        this.bufferHeight = bufferHeight.coerceAtLeast(2)
        this.visibleWidth = visibleWidth.coerceAtLeast(2)
        this.visibleHeight = visibleHeight.coerceAtLeast(2)
        rotationDegrees = rotation.mod(360)
        mirrored = mirror
        fitMode = mode
        texture.surfaceTexture?.setDefaultBufferSize(this.bufferWidth, this.bufferHeight)
        updateChildTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateChildTransform()
    }

    /**
     * Places the camera buffer inside the view using an explicit transform matrix.
     *
     * The previous implementation sized the TextureView with `layoutParams` and
     * turned it with `View.rotation`. That looks equivalent but is not: a
     * TextureView always STRETCHES its surface to fill its own bounds, and the
     * SurfaceTexture carries a transform of its own that the layout arithmetic
     * cannot see. The two together meant the rendered aspect did not follow from
     * the numbers being computed — which is how a 16:9 frame ended up rendered
     * into a 16:9 box and still came out horizontally stretched.
     *
     * So the view now always fills the container and every scale, rotation and
     * flip is expressed in one matrix. Step 1 undoes the view-box stretch, which
     * makes the maths independent of the container's shape; after that, aspect is
     * preserved by construction rather than by arithmetic that has to agree with
     * an invisible matrix.
     */
    private fun updateChildTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        if (visibleWidth <= 0 || visibleHeight <= 0) return

        val params = texture.layoutParams
        if (params == null ||
            params.width != LayoutParams.MATCH_PARENT ||
            params.height != LayoutParams.MATCH_PARENT
        ) {
            texture.layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        // Rotation and mirroring live in the matrix; leave the View properties at
        // identity so the two mechanisms cannot fight each other.
        texture.rotation = 0f
        texture.scaleX = 1f
        texture.scaleY = 1f

        // VISIBLE units throughout: the surface transform has already been applied
        // by the time TextureView draws, so the buffer geometry is the wrong basis.
        val contentW = visibleWidth.toFloat()
        val contentH = visibleHeight.toFloat()
        val swapsAxes = rotationDegrees == 90 || rotationDegrees == 270
        // How large the picture appears once our own rotation is applied on top.
        val shownWidth = if (swapsAxes) contentH else contentW
        val shownHeight = if (swapsAxes) contentW else contentH
        val scale = if (fitMode == "fill") {
            max(viewWidth / shownWidth, viewHeight / shownHeight)
        } else {
            min(viewWidth / shownWidth, viewHeight / shownHeight)
        }

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val matrix = Matrix()
        // 1. Undo the stretch TextureView applies when filling its bounds, so one
        //    picture pixel is square again. Must use the VISIBLE aspect: doing this
        //    with the buffer aspect stretched an upright portrait picture back out
        //    to landscape, which is the horizontal stretch that was reported.
        matrix.postScale(contentW / viewWidth, contentH / viewHeight, centerX, centerY)
        // 2. Scale to cover or contain the view, uniformly on both axes.
        matrix.postScale(scale, scale, centerX, centerY)
        // 3. Rotate about the centre.
        matrix.postRotate(rotationDegrees.toFloat(), centerX, centerY)
        // 4. Mirror last, so it flips the final image the way the user expects.
        if (mirrored) matrix.postScale(-1f, 1f, centerX, centerY)
        texture.setTransform(matrix)
        texture.invalidate()
    }

    fun releasePreview() {
        onSurfaceChanged?.invoke(null)
        cameraSurface?.release()
        cameraSurface = null
        texture.surfaceTextureListener = null
    }
}

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
        // System bars are transparent (see themes.xml), so the app draws behind
        // them and Scaffold/statusBarsPadding supply the insets.
        enableEdgeToEdge()

        // Initial rotation
        updateServiceTargetRotation()

        setContent {
            OpenCamBridgeTheme {
                // State is read inside each screen from the ViewModel rather than
                // threaded through a forty-parameter signature, which is how the
                // previous version ended up passing values that were never used
                // and hiding controls that were never wired.
                MainScreen(
                    viewModel = viewModel,
                    onStartStop = {
                        if (viewModel.isStreaming.value) stopEverything() else startCameraOrService()
                    },
                    onRetryStreamStart = { requestPermissionsAndStart() }
                )
            }
        }

        // Development and cable-reconnect launches must bring the foreground
        // service back without requiring a tap on the phone. If permissions
        // are not granted yet this uses the normal permission launcher.
        requestPermissionsAndStart()
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
        // Stop only tears down the camera pipeline; the foreground service and
        // its HTTP control server deliberately stay alive. Restart that
        // existing pipeline in-process instead of sending a second service
        // start intent (which previously tried to bind port 8080 again).
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
     * The pipeline is stopped first so the camera is released cleanly, then the
     * service is stopped, which runs onDestroy: control server down, listener
     * disabled, receiver unregistered, wakelock released, notification gone.
     *
     * The cost is stated in the UI: with the server gone the desktop cannot start
     * the camera again, so the next start has to come from this phone.
     */
    private fun stopEverything() {
        viewModel.stopStream()
        stopService(StreamService.startIntent(this))
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

// ---- Screen ----------------------------------------------------------------

@Composable
fun MainScreen(
    viewModel: StreamViewModel,
    onStartStop: () -> Unit,
    onRetryStreamStart: () -> Unit
) {
    var currentTab by remember { mutableStateOf(NavTab.Stream) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Surface control failures (failed local API POSTs) as a Snackbar so a
    // button that silently failed is visible, not buried in the Logs tab.
    val snackbarHostState = remember { SnackbarHostState() }
    val controlError by viewModel.controlError.collectAsState()
    val serviceStartError by viewModel.serviceStartError.collectAsState()
    LaunchedEffect(controlError) {
        controlError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearControlError()
        }
    }
    LaunchedEffect(serviceStartError) {
        serviceStartError?.let { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Retry",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite
            )
            viewModel.clearServiceStartError()
            if (result == SnackbarResult.ActionPerformed) {
                onRetryStreamStart()
            }
        }
    }

    val logs by viewModel.logs.collectAsState()
    val problemCount = logs.count { it.level == "ERROR" || it.level == "WARN" }

    Scaffold(
        containerColor = Ocb.Void,
        snackbarHost = {
            // The default M3 snackbar paints itself with inverseSurface, which on a
            // dark-only app arrives as a bright slab across the interface.
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Ocb.PanelTop,
                    contentColor = Ocb.Ink,
                    actionColor = Ocb.Signal,
                    dismissActionContentColor = Ocb.Ink3,
                    shape = RoundedCornerShape(Ocb.CornerControl)
                )
            }
        },
        topBar = { AppBar(viewModel) },
        bottomBar = {
            NavigationBar(containerColor = Ocb.Panel, tonalElevation = 0.dp) {
                NavTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = {
                            BadgedBox(badge = {
                                if (tab == NavTab.Logs && problemCount > 0) {
                                    Badge(containerColor = Ocb.Warn, contentColor = Ocb.Void) {
                                        Text("$problemCount", fontSize = 9.sp)
                                    }
                                }
                            }) { Icon(tab.icon, contentDescription = tab.label) }
                        },
                        label = {
                            Text(
                                tab.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ocb.Void,
                            selectedTextColor = Ocb.Ink,
                            indicatorColor = Ocb.Signal,
                            unselectedIconColor = Ocb.Ink3,
                            unselectedTextColor = Ocb.Ink3
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Ocb.Void)) {
            when (currentTab) {
                NavTab.Stream -> StreamTab(viewModel, isLandscape, onStartStop)
                NavTab.Camera -> CameraTab(viewModel)
                NavTab.Output -> OutputTab(viewModel)
                NavTab.Link -> LinkTab(viewModel)
                NavTab.Logs -> LogsTab(viewModel)
            }
        }
    }
}

@Composable
private fun AppBar(viewModel: StreamViewModel) {
    val lifecycleState by viewModel.lifecycleState.collectAsState()
    val accessMode by viewModel.accessMode.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()

    val lamp = when {
        rebinding || lifecycleState in setOf("STARTING", "RECONFIGURING", "RECOVERING") -> Lamp.Busy
        lifecycleState == "STREAMING" -> Lamp.Ready
        lifecycleState == "FAILED" -> Lamp.Fail
        else -> Lamp.Off
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF171B21), Color(0xFF101318))))
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.White, Color(0xFFB9C2CE))),
                    RoundedCornerShape(5.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Videocam, contentDescription = null, tint = Ocb.Void, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "OPENCAMBRIDGE",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
            color = Ocb.Ink
        )
        Spacer(Modifier.weight(1f))
        Chip(if (accessMode == "lanToken") "Wi-Fi" else "USB", tone = Ocb.Ink2)
        Spacer(Modifier.width(6.dp))
        Chip(
            if (rebinding) "Rebinding" else lifecycleState,
            tone = when (lamp) {
                Lamp.Ready -> Ocb.Ready
                Lamp.Busy -> Ocb.Warn
                Lamp.Fail -> Ocb.Fail
                else -> Ocb.Ink3
            },
            lamp = lamp
        )
    }
}

// ---- Stream tab ------------------------------------------------------------

@Composable
private fun StreamTab(viewModel: StreamViewModel, isLandscape: Boolean, onStartStop: () -> Unit) {
    // The LOCAL preview rotation, i.e. the one the TextureView actually applies.
    // Shaped to match the DESKTOP output, so the two apps agree: standing the
    // phone up gives 9:16 in both. The camera frame is landscape, so the tall
    // viewfinder is filled by centre-cropping — which is what was asked for, and
    // is what a phone camera app does. Aspect is preserved by the matrix in
    // CameraPreviewContainer, so cropping no longer comes with a stretch.
    val rotation by viewModel.rotationDegrees.collectAsState()
    val encodedWidth by viewModel.encodedWidth.collectAsState()
    val encodedHeight by viewModel.encodedHeight.collectAsState()
    val requestedWidth by viewModel.width.collectAsState()
    val requestedHeight by viewModel.height.collectAsState()

    val aspect = previewAspect(
        outputRotation = rotation,
        width = if (encodedWidth > 0) encodedWidth else requestedWidth,
        height = if (encodedHeight > 0) encodedHeight else requestedHeight
    )

    // Landscape and portrait share one implementation. The previous version
    // duplicated the header, status card and control card verbatim across both
    // branches, so every change had to be made twice.
    if (isLandscape) {
        // A portrait picture in a landscape window needs far less width than a
        // landscape one, and a fixed split left most of the preview column empty.
        // Give the controls that room instead.
        val previewWeight = if (aspect < 1f) 0.32f else 0.56f
        Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Box(
                modifier = Modifier.weight(previewWeight).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) { Viewfinder(viewModel, modifier = Modifier.aspectRatio(aspect)) }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f - previewWeight)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) { StreamControls(viewModel, onStartStop) }
        }
    } else {
        // Cap the height so a tall (9:16) viewfinder cannot push the tally and the
        // start button below the fold.
        val maxPreviewHeight = (LocalConfiguration.current.screenHeightDp * 0.48f).dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(max = maxPreviewHeight),
                contentAlignment = Alignment.Center
            ) { Viewfinder(viewModel, modifier = Modifier.aspectRatio(aspect)) }
            StreamControls(viewModel, onStartStop)
        }
    }
}

/**
 * Shape of the viewfinder box: ALWAYS the shape of the upright picture.
 *
 * The phone's own preview shows SENSOR-oriented pixels rotated upright for
 * display, so when the effective rotation swaps axes the upright picture is
 * portrait even though the encoded frame is landscape — and vice versa. The box
 * is derived from that so it never letterboxes or crops.
 *
 * This deliberately does NOT honour the `aspectRatio` preference. That setting
 * pins the DESKTOP preview's layout, and letting it pin this box too meant a
 * phone whose output was genuinely 16:9 (sensor 90° cancelled by a 270° manual
 * offset, giving an effective rotation of 0) still got squeezed into a 9:16
 * window, because the preference happened to be set to Tall. The phone's
 * viewfinder is a monitor of the local camera; pinning it to a shape the content
 * does not have can only ever misrepresent it.
 *
 * Matching the box to the content also makes Fill vs Fit a no-op here, because
 * there is nothing left to crop.
 */
private fun previewAspect(outputRotation: Int, width: Int, height: Int): Float {
    val w = if (width > 0) width else 16
    val h = if (height > 0) height else 9
    // The rotation the DESKTOP applies, so the viewfinder is the same shape as
    // the picture the desktop receives.
    val swapsAxes = outputRotation == 90 || outputRotation == 270
    val uprightWidth = if (swapsAxes) h else w
    val uprightHeight = if (swapsAxes) w else h
    return uprightWidth.toFloat() / uprightHeight.toFloat()
}

/**
 * The preview, framed as a viewfinder: registration brackets on the bezel and a
 * HUD reading out what is actually being encoded. Nothing is drawn over the
 * image itself except the two corner chips.
 */
@Composable
private fun Viewfinder(viewModel: StreamViewModel, modifier: Modifier = Modifier) {
    val previewEnabled by viewModel.localPreviewEnabled.collectAsState()
    val previewActive by viewModel.phonePreviewActive.collectAsState()
    val previewFailure by viewModel.phonePreviewFailureReason.collectAsState()
    val encodedWidth by viewModel.encodedWidth.collectAsState()
    val encodedHeight by viewModel.encodedHeight.collectAsState()
    val encodedFps by viewModel.encodedFps.collectAsState()
    val activeMode by viewModel.activeStreamMode.collectAsState()
    val clients by viewModel.clientCount.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            // clip() is load-bearing, not decoration. background(shape) only
            // PAINTS a rounded rect; it does not clip children, and the preview
            // is an AndroidView whose TextureView is laid out at cover scale —
            // larger than this box. Without a hard clip the video drew across the
            // tally bar and the stage meters below it.
            .clip(RoundedCornerShape(Ocb.CornerPanel))
            .background(Color.Black)
            .border(1.dp, Ocb.Rule2, RoundedCornerShape(Ocb.CornerPanel))
    ) {
        if (previewEnabled) {
            // Centre-crop to fill. The viewfinder is the shape of the output and
            // the camera frame is landscape, so filling it means cropping the sides
            // — the requested behaviour, and aspect-correct via the matrix.
            LocalPreviewBox("fill", viewModel)
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("PREVIEW OFF", style = com.opencambridge.android.ui.LegendStyle, color = Ocb.Ink3)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Capture and delivery continue as normal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ocb.Ink4
                )
            }
        }

        // Registration brackets, drawn on the bezel so they never cover video.
        CornerBracket(Alignment.TopStart)
        CornerBracket(Alignment.BottomEnd)

        // What is really being encoded, not what was requested.
        Box(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(
                // Compact enough to stay on one line in a 9:16 box, which is
                // narrow: the full "1920×1080 · 31 fps · H264" wrapped there.
                if (encodedWidth > 0) {
                    "${describeHeight(encodedHeight)} · $encodedFps fps · ${activeMode.uppercase()}"
                } else {
                    "no signal"
                },
                style = ReadoutStyle.copy(fontSize = 11.sp),
                color = Ocb.Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(Color(0xB8060810), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }

        Box(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            Chip(
                text = if (clients > 0) "On air" else if (isStreaming) "Ready" else "Off",
                tone = if (clients > 0) Ocb.Tally else if (isStreaming) Ocb.Ready else Ocb.Ink3,
                lamp = if (clients > 0) Lamp.Live else if (isStreaming) Lamp.Ready else Lamp.Off
            )
        }

        if (previewEnabled && !previewActive && previewFailure.isNotBlank()) {
            Box(modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                Chip("Preview inactive", tone = Ocb.Warn, lamp = Lamp.Warn)
            }
        }
    }
}

@Composable
private fun BoxScope.CornerBracket(alignment: Alignment) {
    val top = alignment == Alignment.TopStart
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(8.dp)
            .size(18.dp)
    ) {
        // Two 1dp strips form an L. Cheaper and crisper than a drawn path.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(if (top) Alignment.TopStart else Alignment.BottomEnd)
                .background(Ocb.Signal.copy(alpha = 0.5f))
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .align(if (top) Alignment.TopStart else Alignment.BottomEnd)
                .background(Ocb.Signal.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun StreamControls(viewModel: StreamViewModel, onStartStop: () -> Unit) {
    val isStreaming by viewModel.isStreaming.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()
    val clients by viewModel.clientCount.collectAsState()
    val captureFps by viewModel.actualFps.collectAsState()
    val encodedFps by viewModel.encodedFps.collectAsState()
    val targetFps by viewModel.fps.collectAsState()
    val previewEnabled by viewModel.localPreviewEnabled.collectAsState()
    val previewActive by viewModel.phonePreviewActive.collectAsState()
    val previewFailure by viewModel.phonePreviewFailureReason.collectAsState()
    val fallbackReason by viewModel.fallbackReason.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val wifiIp by viewModel.wifiIp.collectAsState()
    val port by viewModel.port.collectAsState()
    val accessMode by viewModel.accessMode.collectAsState()
    val encodedBitrate by viewModel.encodedBitrate.collectAsState()

    // "Streaming" alone never told the user whether the desktop was receiving
    // anything. On air means a client is really subscribed to the transport.
    val tally = when {
        clients > 0 -> Lamp.Live
        rebinding -> Lamp.Busy
        isStreaming -> Lamp.Ready
        else -> Lamp.Off
    }
    TallyBar(
        state = tally,
        title = when (tally) {
            Lamp.Live -> "On air"
            Lamp.Busy -> "Rebinding"
            Lamp.Ready -> "Ready"
            else -> "Off air"
        },
        note = when (tally) {
            Lamp.Live -> "$clients desktop client${if (clients == 1) "" else "s"} receiving video."
            Lamp.Busy -> "Reconfiguring the camera; controls are briefly disabled."
            Lamp.Ready -> "Camera is running. Connect from the desktop app to go live."
            else -> "Fully stopped: no camera, no server, nothing running in the background."
        }
    )

    val healthy = max(1, (targetFps * 0.85f).roundToInt())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StageMeter(
            name = "Lens",
            value = if (isStreaming) "$captureFps" else "—",
            unit = if (isStreaming) "fps" else "",
            state = stageLamp(captureFps, isStreaming, healthy),
            modifier = Modifier.weight(1f)
        )
        StageMeter(
            name = "Encode",
            value = if (isStreaming) "$encodedFps" else "—",
            unit = if (isStreaming) "fps" else "",
            state = stageLamp(encodedFps, isStreaming, healthy),
            modifier = Modifier.weight(1f)
        )
        StageMeter(
            name = "Desktop",
            value = if (clients > 0) "$clients" else "—",
            unit = if (clients > 0) "client" else "",
            state = if (clients > 0) Lamp.Live else Lamp.Off,
            modifier = Modifier.weight(1f)
        )
    }

    if (lastError.isNotBlank()) {
        Notice(lastError, Ocb.Fail, "Camera error")
    }
    if (fallbackReason.isNotBlank()) {
        Notice(fallbackReason, Ocb.Warn, "Running on a fallback path")
    }

    KeyButton(
        text = if (isStreaming) "Stop camera" else "Start camera",
        onClick = onStartStop,
        danger = isStreaming,
        enabled = !rebinding,
        leading = {
            Icon(
                if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isStreaming) Ocb.Fail else Ocb.Void,
                modifier = Modifier.size(22.dp)
            )
        }
    )

    if (isStreaming) {
        Text(
            "Stop shuts down everything: camera, control server and background service. " +
                "The desktop cannot restart it remotely afterwards — start it from here.",
            style = MaterialTheme.typography.bodySmall,
            color = Ocb.Ink3
        )
    }

    Section("Phone preview") {
        SwitchRow(
            title = "Show preview on this phone",
            note = when {
                !previewEnabled -> "Off — saves battery and one camera surface."
                previewActive -> "Active."
                previewFailure.isNotBlank() -> previewFailure
                else -> "Requested — waiting for a preview target."
            },
            checked = previewEnabled,
            enabled = !rebinding,
            onChange = viewModel::toggleLocalPreview
        )
    }

    Section("Connect from your PC") {
        StatRow(
            "USB (recommended)",
            "127.0.0.1:$port",
            tone = if (accessMode == "usbOnly") Ocb.Ready else Ocb.Ink2
        )
        StatRow(
            "Wi-Fi",
            when {
                accessMode != "lanToken" -> "off — USB only mode"
                wifiIp != null -> "$wifiIp:$port"
                else -> "no Wi-Fi connection"
            },
            tone = when {
                accessMode != "lanToken" -> Ocb.Ink3
                wifiIp != null -> Ocb.Ready
                else -> Ocb.Warn
            }
        )
        if (encodedBitrate > 0) {
            StatRow("Sending", formatMbps(encodedBitrate, 1), tone = Ocb.Signal)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "USB keeps video inside the cable. Wi-Fi needs the access token from the Link tab.",
            style = MaterialTheme.typography.bodySmall,
            color = Ocb.Ink3
        )
    }
}

private fun stageLamp(value: Int, live: Boolean, healthy: Int): Lamp = when {
    !live -> Lamp.Off
    value <= 0 -> Lamp.Fail
    value >= healthy -> Lamp.Ready
    else -> Lamp.Warn
}

@Composable
private fun Notice(text: String, tone: Color, title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.09f), RoundedCornerShape(Ocb.CornerControl))
            .border(1.dp, tone.copy(alpha = 0.3f), RoundedCornerShape(Ocb.CornerControl))
            .padding(12.dp)
    ) {
        Text(title.uppercase(), style = com.opencambridge.android.ui.FieldLabelStyle, color = tone)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ocb.Ink2)
    }
}

@Composable
private fun LocalPreviewBox(fitMode: String, viewModel: StreamViewModel) {
    val streamMode by viewModel.activeStreamMode.collectAsState()
    val requestedWidth by viewModel.width.collectAsState()
    val requestedHeight by viewModel.height.collectAsState()
    val encodedWidth by viewModel.encodedWidth.collectAsState()
    val encodedHeight by viewModel.encodedHeight.collectAsState()
    // previewRotation = effectiveRotation - 90. It follows the manual offset, so
    // the rotation buttons turn this preview too, and it carries the quarter-turn
    // the View-transform path needs relative to the producer's pixel rotation.
    val rotation by viewModel.previewRotation.collectAsState()
    // The SENSOR orientation decides the shape of the picture the surface hands
    // over. It is a property of the hardware, NOT of how the phone is held, so it
    // does not change when the phone is rotated — which is exactly the mistake
    // that stretched the picture vertically in landscape.
    val sensorOrientation by viewModel.sensorOrientation.collectAsState()
    val mirror by viewModel.mirror.collectAsState()

    // These sizes become setDefaultBufferSize on the preview SurfaceTexture, so
    // they must be what the camera ACTUALLY selected, not what was asked for. The
    // adaptive profile routinely lands on a different mode than requested, and
    // sizing the buffer from the request while the camera writes a different
    // geometry stretches the preview.
    val width = if (encodedWidth > 0) encodedWidth else requestedWidth
    val height = if (encodedHeight > 0) encodedHeight else requestedHeight

    // The container already constrains the box; forcing a second aspect ratio
    // here fought the outer one and collapsed the landscape layout.
    if (streamMode == "h264") {
        AndroidView<CameraPreviewContainer>(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CameraPreviewContainer(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    onSurfaceChanged = viewModel::setCamera2PreviewSurface
                }
            },
            update = {
            // Swapped versus the buffer whenever the SENSOR is mounted a quarter
            // turn out, which is the transform the surface has already applied.
            // Keying this off any rotation that tracks how the phone is held made
            // the assumed aspect flip when the phone was turned, stretching the
            // picture vertically in landscape.
            val swaps = sensorOrientation == 90 || sensorOrientation == 270
            it.configure(
                bufferWidth = width,
                bufferHeight = height,
                visibleWidth = if (swaps) height else width,
                visibleHeight = if (swaps) width else height,
                rotation = rotation,
                mirror = mirror,
                mode = fitMode
            )
        },
            onRelease = { it.releasePreview() }
        )
    } else {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    viewModel.setSurfaceProvider(this.surfaceProvider)
                }
            },
            update = { previewView ->
                previewView.scaleType = when (fitMode) {
                    "fill" -> PreviewView.ScaleType.FILL_CENTER
                    else -> PreviewView.ScaleType.FIT_CENTER
                }
            },
            onRelease = {
                // Detach the surface without stopping CameraX.
                viewModel.setSurfaceProvider(null)
            }
        )
    }
}

// ---- Camera tab ------------------------------------------------------------

@Composable
private fun CameraTab(viewModel: StreamViewModel) {
    val cameras by viewModel.cameras.collectAsState()
    val selectedCameraId by viewModel.selectedCameraId.collectAsState()
    val streamMode by viewModel.streamMode.collectAsState()
    val width by viewModel.width.collectAsState()
    val height by viewModel.height.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()
    val hasTorch by viewModel.hasTorch.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
    val linearZoom by viewModel.linearZoom.collectAsState()
    val displayRotation by viewModel.displayRotation.collectAsState()
    val mirror by viewModel.mirror.collectAsState()
    val captureEngine by viewModel.captureEngine.collectAsState()
    val actualFps by viewModel.actualFps.collectAsState()

    val enabled = !rebinding
    val activeCam = cameras.find { it.id == selectedCameraId }
    // CameraRepository reads FLASH_INFO_AVAILABLE per lens, which is accurate
    // regardless of which streamer is running. Absent capability data (older
    // build, list not loaded yet) falls back to the live flag rather than
    // hiding a torch that works.
    val torchSupported = activeCam?.hasTorch ?: hasTorch
    val modes = if (streamMode == "h264") activeCam?.h264Modes else activeCam?.mjpegModes
    val resolutions = modes?.map { it.width to it.height }?.distinct().orEmpty()
    val fpsOptions = modes?.filter { it.width == width && it.height == height }
        ?.map { it.fps }?.distinct()?.sorted().orEmpty()

    TabColumn {
        if (cameras.isEmpty()) {
            Notice(
                "No cameras were reported. Grant the camera permission and restart the app.",
                Ocb.Fail,
                "No cameras"
            )
            return@TabColumn
        }

        if (rebinding) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ocb.Warn.copy(alpha = 0.09f), RoundedCornerShape(Ocb.CornerControl))
                    .border(1.dp, Ocb.Warn.copy(alpha = 0.3f), RoundedCornerShape(Ocb.CornerControl))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LampDot(Lamp.Busy, size = 8)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Rebinding the camera — controls are briefly disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ocb.Warn
                )
            }
        }

        Section("Lens") {
            Picker(
                label = "Camera",
                options = cameras.map { it.id to "${it.label} (${it.id})" },
                selected = selectedCameraId,
                enabled = enabled,
                onSelect = viewModel::selectCamera
            )
            if (captureEngine.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                StatRow("Capture path", engineLabel(captureEngine), tone = Ocb.Ink2)
            }
        }

        Section("Format") {
            Picker(
                label = "Resolution",
                options = resolutions.map { (w, h) -> (w to h) to "${describeHeight(h)} · ${w}×$h" },
                selected = width to height,
                enabled = enabled,
                emptyText = "No modes on this lens",
                onSelect = { (w, h) -> viewModel.updateResolution(w, h) }
            )
            Spacer(Modifier.height(14.dp))
            if (fpsOptions.size in 2..3) {
                Text("FRAME RATE", style = com.opencambridge.android.ui.FieldLabelStyle, color = Ocb.Ink2)
                Spacer(Modifier.height(6.dp))
                Segmented(
                    options = fpsOptions.map { it to "$it fps" },
                    selected = fps,
                    enabled = enabled,
                    onSelect = viewModel::updateFps
                )
            } else {
                Picker(
                    label = "Frame rate",
                    options = fpsOptions.map { it to "$it fps" },
                    selected = fps,
                    enabled = enabled,
                    emptyText = "No rate for this resolution",
                    onSelect = viewModel::updateFps
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (streamMode == "h264") {
                    "Only complete Camera2 surface plus hardware AVC encoder modes are offered."
                } else {
                    "Actual rate depends on the lens and the light available."
                } + if (actualFps > 0) " Delivering $actualFps of $fps fps now." else "",
                style = MaterialTheme.typography.bodySmall,
                color = Ocb.Ink3
            )
        }

        Section("Image") {
            Fader(
                label = "Zoom",
                readout = "${(linearZoom * 100).roundToInt()}%",
                value = linearZoom,
                range = 0f..1f,
                enabled = enabled,
                onChange = viewModel::updateZoom
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "Torch",
                // Gated on the SELECTED LENS reporting a flash, not on the live
                // StreamState flag: that flag is only published by the MJPEG path,
                // so on H.264 it stayed false and disabled this control outright.
                // Never gated on rebindInProgress either — the torch is a camera
                // control call, so a rebind must not grey it out and leave it stuck.
                note = if (torchSupported) "Hold the LED on for a dim room." else "This lens has no flash.",
                checked = torchEnabled,
                enabled = torchSupported,
                onChange = viewModel::updateTorch
            )
        }

        Section("Framing") {
            Text("ROTATION", style = com.opencambridge.android.ui.FieldLabelStyle, color = Ocb.Ink2)
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf("0" to "0°", "90" to "90°", "180" to "180°", "270" to "270°"),
                selected = (displayRotation.toIntOrNull() ?: 0).toString(),
                enabled = enabled,
                onSelect = viewModel::updateDisplayRotation
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Video is already uprighted for how the phone is held. This adds an offset on top.",
                style = MaterialTheme.typography.bodySmall,
                color = Ocb.Ink3
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "Mirror",
                note = "Flip horizontally, as a front camera normally previews.",
                checked = mirror,
                enabled = enabled,
                onChange = viewModel::updateMirror
            )
        }
    }
}

// ---- Output tab ------------------------------------------------------------

@Composable
private fun OutputTab(viewModel: StreamViewModel) {
    val streamMode by viewModel.streamMode.collectAsState()
    val activeMode by viewModel.activeStreamMode.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()
    val bitrate by viewModel.h264Bitrate.collectAsState()
    val keyframeInterval by viewModel.h264KeyframeInterval.collectAsState()
    val fitMode by viewModel.previewFitMode.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val zoomSpeed by viewModel.zoomSpeed.collectAsState()
    val rebinding by viewModel.rebindInProgress.collectAsState()
    val developerMode by viewModel.developerMode.collectAsState()
    val encoderName by viewModel.encoderName.collectAsState()
    val hardwareEncoder by viewModel.hardwareEncoder.collectAsState()
    val encodedBitrate by viewModel.encodedBitrate.collectAsState()

    val enabled = !rebinding

    TabColumn {
        Section("Codec") {
            // This control existed in the code but was never placed on screen, so
            // switching transport from the phone was impossible.
            Segmented(
                options = listOf("h264" to "H.264", "mjpeg" to "MJPEG"),
                selected = streamMode,
                enabled = enabled,
                onSelect = viewModel::updateStreamMode
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (streamMode == "h264") {
                    "Hardware H.264 over OCB2: the camera writes straight into the encoder and the desktop " +
                        "decodes in hardware. This is the low-latency path."
                } else {
                    "MJPEG is the compatibility path. Every frame is a full JPEG, so it costs much more " +
                        "bandwidth and CPU than H.264."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ocb.Ink3
            )
            if (activeMode != streamMode) {
                Spacer(Modifier.height(10.dp))
                Notice(
                    "Requested ${streamMode.uppercase()} but the pipeline is running ${activeMode.uppercase()}.",
                    Ocb.Warn,
                    "Mode differs from request"
                )
            }
        }

        if (streamMode == "h264") {
            Section("Encoding") {
                Fader(
                    label = "Bitrate",
                    readout = "${bitrate / 1_000_000} Mb/s",
                    value = (bitrate / 1_000_000).toFloat(),
                    range = 1f..20f,
                    steps = 18,
                    enabled = enabled,
                    onChange = { viewModel.updateH264Bitrate(it.roundToInt() * 1_000_000) },
                    note = "Applies live. Very low requests are raised to a floor appropriate for the resolution."
                )
                Spacer(Modifier.height(14.dp))
                Fader(
                    label = "Keyframe interval",
                    readout = "${keyframeInterval}s",
                    value = keyframeInterval.toFloat(),
                    range = H264SettingsPolicy.MIN_KEYFRAME_INTERVAL_SECONDS.toFloat()..
                        H264SettingsPolicy.MAX_KEYFRAME_INTERVAL_SECONDS.toFloat(),
                    steps = H264SettingsPolicy.MAX_KEYFRAME_INTERVAL_SECONDS -
                        H264SettingsPolicy.MIN_KEYFRAME_INTERVAL_SECONDS - 1,
                    enabled = enabled,
                    onChange = { viewModel.updateH264KeyframeInterval(it.roundToInt()) },
                    note = "Keyframes are also requested on demand whenever a desktop connects, so this is " +
                        "only a safety net. Longer intervals spend more of the bitrate on the picture."
                )
                if (encoderName.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    StatRow(
                        "Encoder",
                        shortCodec(encoderName),
                        tone = if (hardwareEncoder) Ocb.Ink else Ocb.Warn
                    )
                    StatRow(
                        "Acceleration",
                        if (hardwareEncoder) "hardware" else "software fallback",
                        tone = if (hardwareEncoder) Ocb.Ready else Ocb.Warn
                    )
                    if (encodedBitrate > 0) {
                        StatRow("Measured", formatMbps(encodedBitrate, 2), tone = Ocb.Signal)
                    }
                }
            }
        } else {
            Section("Encoding") {
                Fader(
                    label = "JPEG quality",
                    readout = "$jpegQuality%",
                    value = jpegQuality.toFloat(),
                    range = 10f..100f,
                    steps = 89,
                    enabled = enabled,
                    onChange = { viewModel.updateJpegQuality(it.roundToInt()) },
                    note = "Higher quality costs bandwidth on every single frame, because MJPEG never " +
                        "reuses information between frames."
                )
            }
        }

        Section("Preview shape") {
            Text("FIT", style = com.opencambridge.android.ui.FieldLabelStyle, color = Ocb.Ink2)
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf("fill" to "Fill", "fit" to "Fit"),
                selected = fitMode,
                enabled = enabled,
                onSelect = viewModel::updatePreviewFitMode
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Fill crops to the edges; Fit shows the whole frame with bars. This shapes previews only — " +
                    "never the video the desktop receives.",
                style = MaterialTheme.typography.bodySmall,
                color = Ocb.Ink3
            )
            Spacer(Modifier.height(14.dp))
            Text("DESKTOP PREVIEW SHAPE", style = com.opencambridge.android.ui.FieldLabelStyle, color = Ocb.Ink2)
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf("auto" to "Auto", "16:9" to "Wide", "9:16" to "Tall"),
                selected = aspectRatio,
                enabled = enabled,
                onSelect = { viewModel.updateAspectRatio(it) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pins the shape of the preview box in the DESKTOP app. This phone's viewfinder always " +
                    "follows the real picture, so it cannot misrepresent what is being sent.",
                style = MaterialTheme.typography.bodySmall,
                color = Ocb.Ink3
            )
        }

        if (developerMode) {
            Section("Developer") {
                Text("ZOOM SPEED", style = com.opencambridge.android.ui.FieldLabelStyle, color = Ocb.Ink2)
                Spacer(Modifier.height(6.dp))
                Segmented(
                    options = listOf("slow" to "Slow", "normal" to "Normal", "fast" to "Fast"),
                    selected = zoomSpeed,
                    enabled = enabled,
                    onSelect = viewModel::updateZoomSpeed
                )
            }
        }
    }
}

// ---- Link tab --------------------------------------------------------------

@Composable
private fun LinkTab(viewModel: StreamViewModel) {
    val accessMode by viewModel.accessMode.collectAsState()
    val port by viewModel.port.collectAsState()
    val accessToken by viewModel.accessToken.collectAsState()
    val wifiIp by viewModel.wifiIp.collectAsState()
    val developerMode by viewModel.developerMode.collectAsState()
    val clients by viewModel.clientCount.collectAsState()

    var tokenVisible by remember { mutableStateOf(false) }
    var portText by remember(port) { mutableStateOf(port.toString()) }
    val clipboard = LocalClipboardManager.current

    TabColumn {
        Section("Access mode") {
            Segmented(
                options = listOf("usbOnly" to "USB only", "lanToken" to "Wi-Fi + token"),
                selected = accessMode,
                onSelect = viewModel::updateAccessMode
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (accessMode == "lanToken") {
                    "The server is reachable on your network. Every route except the health check requires " +
                        "the token below, and security settings can only be changed from this phone."
                } else {
                    "The server is bound to 127.0.0.1, so nothing on the network can reach it. The desktop " +
                        "connects through an adb forward over the USB cable."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (accessMode == "lanToken") Ocb.Warn else Ocb.Ready
            )
        }

        Section("Endpoint") {
            StatRow("Connected desktops", if (clients > 0) "$clients" else "none", tone = if (clients > 0) Ocb.Ready else Ocb.Ink3)
            StatRow("USB", "127.0.0.1:$port", tone = Ocb.Ink)
            StatRow(
                "Wi-Fi",
                if (accessMode != "lanToken") "disabled" else wifiIp?.let { "$it:$port" } ?: "no Wi-Fi",
                tone = if (accessMode == "lanToken" && wifiIp != null) Ocb.Ink else Ocb.Ink3
            )
            Spacer(Modifier.height(12.dp))
            Text("PORT", style = com.opencambridge.android.ui.FieldLabelStyle, color = Ocb.Ink2)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = portText,
                onValueChange = { text ->
                    portText = text.filter { it.isDigit() }.take(5)
                    portText.toIntOrNull()?.let { if (it in 1024..65535) viewModel.updatePort(it) }
                },
                singleLine = true,
                textStyle = ReadoutStyle,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ocb.Signal,
                    unfocusedBorderColor = Ocb.Rule2,
                    focusedContainerColor = Ocb.Inset,
                    unfocusedContainerColor = Ocb.Inset
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "1024–65535. A port change takes effect when the stream restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = Ocb.Ink3
            )
        }

        if (accessMode == "lanToken") {
            Section("Access token") {
                // Typing a UUID into the desktop by hand was the only option
                // before; copy is the whole point of showing it here.
                Well {
                    Text(
                        if (tokenVisible) accessToken.ifBlank { "not generated yet" } else "•".repeat(32),
                        style = ReadoutStyle.copy(fontSize = 13.sp),
                        color = Ocb.Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { tokenVisible = !tokenVisible },
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Ocb.Rule2)
                    ) {
                        Icon(
                            if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Ocb.Ink2
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (tokenVisible) "Hide" else "Show", color = Ocb.Ink2)
                    }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(accessToken)) },
                        enabled = accessToken.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Ocb.Rule2)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Ocb.Signal
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Copy", color = Ocb.Signal)
                    }
                    OutlinedButton(
                        onClick = viewModel::regenerateToken,
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Ocb.Rule2)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Ocb.Warn
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("New", color = Ocb.Warn)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Regenerating disconnects any desktop using the old token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ocb.Ink3
                )
            }
        }

        Section("Diagnostics") {
            SwitchRow(
                title = "Developer mode",
                note = "Shows every log level and extra controls. Off keeps the Logs tab to warnings and errors.",
                checked = developerMode,
                onChange = viewModel::setDeveloperMode
            )
        }
    }
}

// ---- Logs tab --------------------------------------------------------------

@Composable
private fun LogsTab(viewModel: StreamViewModel) {
    val logs by viewModel.logs.collectAsState()
    val developerMode by viewModel.developerMode.collectAsState()
    val cameras by viewModel.cameras.collectAsState()
    val selectedCameraId by viewModel.selectedCameraId.collectAsState()
    val width by viewModel.width.collectAsState()
    val height by viewModel.height.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val actualFps by viewModel.actualFps.collectAsState()
    val encodedFps by viewModel.encodedFps.collectAsState()
    val activeMode by viewModel.activeStreamMode.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val sensorOrientation by viewModel.sensorOrientation.collectAsState()
    val deviceSurfaceRotation by viewModel.deviceSurfaceRotation.collectAsState()
    val effectiveRotation by viewModel.rotationDegrees.collectAsState()
    val previewRotation by viewModel.previewRotation.collectAsState()
    val manualRotation by viewModel.displayRotation.collectAsState()
    val encodedWidth by viewModel.encodedWidth.collectAsState()
    val encodedHeight by viewModel.encodedHeight.collectAsState()
    val mirror by viewModel.mirror.collectAsState()

    var problemsOnly by remember(developerMode) { mutableStateOf(!developerMode) }

    val errorCount = logs.count { it.level == "ERROR" }
    val warnCount = logs.count { it.level == "WARN" }
    val visible = if (problemsOnly) logs.filter { it.level == "ERROR" || it.level == "WARN" } else logs

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Section("Snapshot") {
            StatRow(
                "Pipeline",
                if (isStreaming) "streaming · ${activeMode.uppercase()}" else "stopped",
                tone = if (isStreaming) Ocb.Ready else Ocb.Ink3
            )
            StatRow("Lens", cameras.find { it.id == selectedCameraId }?.label ?: selectedCameraId)
            StatRow("Requested", "${width}×$height @ $fps")
            StatRow(
                "Capture / encode",
                "$actualFps / $encodedFps fps",
                tone = if (encodedFps >= fps - 5) Ocb.Ready else Ocb.Warn
            )
            StatRow(
                "Errors / warnings",
                "$errorCount / $warnCount",
                tone = if (errorCount > 0) Ocb.Fail else if (warnCount > 0) Ocb.Warn else Ocb.Ready
            )
        }

        Spacer(Modifier.height(12.dp))

        // The rotation chain, spelled out. Effective rotation is what the Windows
        // producer applies and what shapes the preview box, and it is the sum of
        // three inputs that are otherwise invisible from the outside — which made
        // "the preview is rotated wrong" impossible to act on without a debugger.
        Section("Orientation") {
            StatRow("Sensor orientation", "$sensorOrientation°")
            StatRow("Phone held at", "${surfaceRotationDegrees(deviceSurfaceRotation)}°")
            StatRow("Manual offset", "${manualRotation.toIntOrNull() ?: 0}°")
            StatRow("Desktop rotation", "$effectiveRotation°", tone = Ocb.Signal)
            StatRow("Phone preview rotation", "$previewRotation°", tone = Ocb.Signal)
            StatRow("Mirror", if (mirror) "on" else "off")
            val swaps = effectiveRotation == 90 || effectiveRotation == 270
            StatRow(
                "Encoded → upright",
                "${encodedWidth}×$encodedHeight → " +
                    if (swaps) "${encodedHeight}×$encodedWidth" else "${encodedWidth}×$encodedHeight",
                tone = Ocb.Signal
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Both follow the manual offset, so the rotation buttons move both. They sit a quarter " +
                    "turn apart because the desktop rotates pixels and the phone rotates a view.",
                style = MaterialTheme.typography.bodySmall,
                color = Ocb.Ink3
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend("Event log", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                Segmented(
                    options = listOf(true to "Problems", false to "Everything"),
                    selected = problemsOnly,
                    onSelect = { problemsOnly = it }
                )
            }
            OutlinedButton(
                onClick = viewModel::clearLogs,
                border = androidx.compose.foundation.BorderStroke(1.dp, Ocb.Rule2)
            ) { Text("Clear", color = Ocb.Ink2) }
        }
        Spacer(Modifier.height(10.dp))

        Well(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (visible.isEmpty()) {
                    Text(
                        if (problemsOnly) "No warnings or errors." else "No log entries yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ocb.Ink3
                    )
                } else {
                    visible.asReversed().forEach { entry -> LogLine(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogLine(log: LogEntry) {
    val tone = when (log.level) {
        "ERROR" -> Ocb.Fail
        "WARN" -> Ocb.Warn
        else -> Ocb.Ink2
    }
    val time = remember(log.timestamp) {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(log.timestamp))
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(time, fontFamily = MonoFamily, fontSize = 11.sp, color = Ocb.Ink4)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                log.source.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = tone.copy(alpha = 0.8f)
            )
            Text(
                log.message,
                fontFamily = MonoFamily,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = tone,
                fontWeight = if (log.level == "ERROR") FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

// ---- Shared ----------------------------------------------------------------

@Composable
private fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

/** Machine readout: dot decimal separator regardless of device locale. */
private fun formatMbps(bitsPerSecond: Int, decimals: Int): String =
    String.format(java.util.Locale.US, "%.${decimals}f Mb/s", bitsPerSecond / 1_000_000f)

/** "OMX.qcom.video.encoder.avc" -> "qcom.avc", so it fits one line. */
private fun shortCodec(name: String): String = name
    .removePrefix("OMX.")
    .removePrefix("c2.")
    .removePrefix("C2.")
    .replace("video.", "")
    .removeSuffix(".encoder")
    .removeSuffix("encoder.")
    .ifBlank { name }

/** Surface.ROTATION_* to degrees, matching FrameTransformPolicy. */
private fun surfaceRotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private fun describeHeight(height: Int): String = when (height) {
    1080 -> "1080p"
    720 -> "720p"
    540 -> "540p"
    480 -> "480p"
    else -> "${height}p"
}

private fun engineLabel(engine: String): String = when (engine) {
    "REGULAR_SURFACE" -> "Camera2 surface (direct)"
    "HIGH_SPEED_GPU_BRIDGE" -> "High-speed GPU bridge"
    else -> engine.lowercase().replace('_', ' ')
}
