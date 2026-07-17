package com.opencambridge.android.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GPU-only bridge used when a constrained high-speed session can expose (for
 * example) 1080p120 but the webcam profile is 1080p60. Camera2 writes to an OES
 * SurfaceTexture; GLES renders paced frames into MediaCodec's input Surface and
 * optionally the phone preview. No camera pixels are read by Kotlin or the CPU.
 */
class HighSpeedGpuBridge(
    private val encoderSurface: Surface,
    private val previewSurface: Surface?,
    private val width: Int,
    private val height: Int,
    private val outputFps: Int,
    private val onFrameRendered: (timestampNs: Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private data class Target(val eglSurface: EGLSurface, val width: Int, val height: Int)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var encoderTarget: Target? = null
    private var previewTarget: Target? = null
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var program = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var texMatrixHandle = -1
    private val textureMatrix = FloatArray(16)
    private val positions = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoords = floatBufferOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val frameIntervalNs = 1_000_000_000L / outputFps.coerceAtLeast(1)
    private var nextOutputTimestampNs = 0L
    @Volatile private var stopped = false

    suspend fun start(): Surface = suspendCancellableCoroutine { continuation ->
        val renderThread = HandlerThread("OCB2-HighSpeed-GPU").apply { start() }
        thread = renderThread
        val renderHandler = Handler(renderThread.looper)
        handler = renderHandler
        continuation.invokeOnCancellation { renderHandler.post { releaseOnRenderThread() } }
        renderHandler.post {
            try {
                initializeEgl()
                val texture = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ renderFrame() }, renderHandler)
                }
                surfaceTexture = texture
                val input = Surface(texture)
                cameraSurface = input
                if (continuation.isActive) continuation.resume(input)
            } catch (e: Exception) {
                releaseOnRenderThread()
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }

    suspend fun stop() = suspendCancellableCoroutine { continuation ->
        val renderHandler = handler
        if (renderHandler == null) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }
        renderHandler.post {
            releaseOnRenderThread()
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            "eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        val config = chooseConfig()
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        encoderTarget = createWindowTarget(config, encoderSurface, width, height)
        check(EGL14.eglMakeCurrent(display, encoderTarget!!.eglSurface, encoderTarget!!.eglSurface, context)) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        previewTarget = if (previewSurface?.isValid == true) {
            try {
                createWindowTarget(config, previewSurface, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Optional preview EGL surface rejected: ${e.message}")
                null
            }
        } else {
            null
        }
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("initialize bridge texture")
    }

    private fun chooseConfig(): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no recordable EGL configuration: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        return requireNotNull(configs[0])
    }

    private fun createWindowTarget(config: EGLConfig, surface: Surface, knownWidth: Int?, knownHeight: Int?): Target {
        val eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        val value = IntArray(1)
        val targetWidth = knownWidth ?: run {
            check(EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, value, 0)) { "eglQuerySurface width failed" }
            value[0]
        }
        val targetHeight = knownHeight ?: run {
            check(EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, value, 0)) { "eglQuerySurface height failed" }
            value[0]
        }
        return Target(eglSurface, targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
    }

    private fun renderFrame() {
        if (stopped) return
        val texture = surfaceTexture ?: return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(textureMatrix)
            val cameraTimestampNs = texture.timestamp
            if (nextOutputTimestampNs != 0L && cameraTimestampNs < nextOutputTimestampNs) return
            nextOutputTimestampNs = if (nextOutputTimestampNs == 0L) {
                cameraTimestampNs + frameIntervalNs
            } else {
                var next = nextOutputTimestampNs + frameIntervalNs
                while (next <= cameraTimestampNs) next += frameIntervalNs
                next
            }
            encoderTarget?.let {
                draw(it)
                EGLExt.eglPresentationTimeANDROID(display, it.eglSurface, cameraTimestampNs)
                check(EGL14.eglSwapBuffers(display, it.eglSurface)) {
                    "encoder eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
                }
            }
            previewTarget?.let {
                draw(it)
                if (!EGL14.eglSwapBuffers(display, it.eglSurface)) {
                    Log.w(TAG, "Preview surface detached: 0x${Integer.toHexString(EGL14.eglGetError())}")
                    EGL14.eglDestroySurface(display, it.eglSurface)
                    previewTarget = null
                }
            }
            onFrameRendered(cameraTimestampNs)
        } catch (e: Exception) {
            onError("High-speed GPU bridge frame failure: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun draw(target: Target) {
        check(EGL14.eglMakeCurrent(display, target.eglSurface, target.eglSurface, context)) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        positions.position(0)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        checkGl("draw bridge frame")
    }

    private fun releaseOnRenderThread() {
        if (stopped) return
        stopped = true
        surfaceTexture?.setOnFrameAvailableListener(null)
        cameraSurface?.release()
        cameraSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            encoderTarget?.let { EGL14.eglDestroySurface(display, it.eglSurface) }
            previewTarget?.let { EGL14.eglDestroySurface(display, it.eglSurface) }
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        encoderTarget = null
        previewTarget = null
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val linked = GLES20.glCreateProgram()
        GLES20.glAttachShader(linked, vertex)
        GLES20.glAttachShader(linked, fragment)
        GLES20.glLinkProgram(linked)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
        val message = GLES20.glGetProgramInfoLog(linked)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) { "bridge shader link failed: $message" }
        return linked
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "bridge shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$operation failed: GL 0x${Integer.toHexString(error)}" }
    }

    companion object {
        private const val TAG = "OCB2-GpuBridge"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }
    }
}
