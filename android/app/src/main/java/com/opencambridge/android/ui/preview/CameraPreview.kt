package com.opencambridge.android.ui.preview

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.opencambridge.android.StreamViewModel
import kotlin.math.max
import kotlin.math.min

/**
 * The phone's local camera preview, moved here unchanged from MainActivity so
 * the screens around it can change freely. The only addition is `mirrorPreview`:
 * a view-only flip layered on top of the output mirror, so the person in front
 * of the phone can see themselves as in a mirror without changing what the
 * computer receives.
 */
internal class CameraPreviewContainer(context: Context) : FrameLayout(context) {
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

/**
 * Shape of the viewfinder box: ALWAYS the shape of the upright picture.
 *
 * The phone's own preview shows SENSOR-oriented pixels rotated upright for
 * display, so when the effective rotation swaps axes the upright picture is
 * portrait even though the encoded frame is landscape — and vice versa. The box
 * is derived from that so it never letterboxes or crops.
 *
 * This deliberately does NOT honour the `aspectRatio` preference. That setting
 * pins the DESKTOP preview's layout; the phone's viewfinder is a monitor of the
 * local camera, and pinning it to a shape the content does not have can only
 * ever misrepresent it.
 */
internal fun previewAspect(outputRotation: Int, width: Int, height: Int): Float {
    val w = if (width > 0) width else 16
    val h = if (height > 0) height else 9
    // The rotation the DESKTOP applies, so the viewfinder is the same shape as
    // the picture the desktop receives.
    val swapsAxes = outputRotation == 90 || outputRotation == 270
    val uprightWidth = if (swapsAxes) h else w
    val uprightHeight = if (swapsAxes) w else h
    return uprightWidth.toFloat() / uprightHeight.toFloat()
}

@Composable
internal fun LocalPreviewBox(fitMode: String, viewModel: StreamViewModel, mirrorPreview: Boolean) {
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
                    // The preview shows the output; "Mirror preview" flips the view
                    // once more without touching what the desktop receives.
                    mirror = mirror xor mirrorPreview,
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
                    // A TextureView-backed preview honours the view flip below;
                    // a SurfaceView would ignore it.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    viewModel.setSurfaceProvider(this.surfaceProvider)
                }
            },
            update = { previewView ->
                previewView.scaleType = when (fitMode) {
                    "fill" -> PreviewView.ScaleType.FILL_CENTER
                    else -> PreviewView.ScaleType.FIT_CENTER
                }
                previewView.scaleX = if (mirrorPreview) -1f else 1f
            },
            onRelease = {
                // Detach the surface without stopping CameraX.
                viewModel.setSurfaceProvider(null)
            }
        )
    }
}
