package com.opencambridge.android.service

import com.opencambridge.android.server.UpdateSettingsRequest
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

enum class PipelineResultCode {
    OK,
    CONFLICT,
    UNPROCESSABLE,
    CANCELLED,
    FAILED
}

internal object ApplyPatchCoalescingPolicy {
    private const val CAPTURE = 1
    private const val TRANSFORM = 1 shl 1
    private const val PREVIEW = 1 shl 2
    private const val NETWORK = 1 shl 3
    private const val ENCODING = 1 shl 4
    private const val DISPLAY = 1 shl 5

    fun canMerge(
        older: UpdateSettingsRequest,
        olderSource: String?,
        newer: UpdateSettingsRequest,
        newerSource: String?
    ): Boolean {
        val oldSourceIdentity = olderSource ?: older.clientType
        val newSourceIdentity = newerSource ?: newer.clientType
        val oldClientIdentity = older.clientType ?: olderSource
        val newClientIdentity = newer.clientType ?: newerSource
        return !oldSourceIdentity.isNullOrBlank() && oldSourceIdentity == newSourceIdentity &&
            !oldClientIdentity.isNullOrBlank() && oldClientIdentity == newClientIdentity &&
            older.baseRevision != null && older.baseRevision == newer.baseRevision &&
            categoryMask(older) == categoryMask(newer)
    }

    private fun categoryMask(request: UpdateSettingsRequest): Int {
        var mask = 0
        if (request.cameraId != null || request.width != null || request.height != null ||
            request.outputWidth != null || request.outputHeight != null || request.profile != null ||
            request.fps != null || request.streamMode != null
        ) mask = mask or CAPTURE
        if (request.displayRotation != null || request.mirror != null) mask = mask or TRANSFORM
        if (request.localPreviewEnabled != null || request.phonePreviewEnabled != null) mask = mask or PREVIEW
        if (request.accessMode != null || request.port != null || request.accessToken != null) mask = mask or NETWORK
        if (request.jpegQuality != null || request.h264Bitrate != null ||
            request.h264KeyframeInterval != null || request.targetBandwidthMbps != null
        ) mask = mask or ENCODING
        if (request.previewFitMode != null || request.aspectRatio != null || request.zoomSpeed != null) {
            mask = mask or DISPLAY
        }
        return mask
    }
}

data class PipelineResult(
    val code: PipelineResultCode,
    val message: String,
    val revision: Long,
    val generation: Long,
    val lifecycleState: String,
    val requested: String? = null,
    val alternatives: List<String> = emptyList()
) {
    val success: Boolean get() = code == PipelineResultCode.OK
}

sealed class PipelineCommand(val label: String) {
    internal val completion = CompletableDeferred<PipelineResult>()

    class Start : PipelineCommand("START")
    class Stop : PipelineCommand("STOP")
    class Recover : PipelineCommand("RECOVER")
    class RuntimeH264Failure(val reason: String) : PipelineCommand("H264_FAILURE")
    class AdaptiveDowngrade(val reason: String) : PipelineCommand("ADAPTIVE_DOWNGRADE")
    class PreviewSurfaceChanged(val attached: Boolean) : PipelineCommand("PREVIEW_SURFACE_CHANGED")
    class SetTorch(val enabled: Boolean, val baseRevision: Long, val requestId: String, val source: String) : PipelineCommand("SET_TORCH")
    class SetLinearZoom(val linear: Float, val baseRevision: Long, val requestId: String, val source: String) : PipelineCommand("SET_LINEAR_ZOOM")
    class SetZoomRatio(val ratio: Float, val baseRevision: Long, val requestId: String, val source: String) : PipelineCommand("SET_ZOOM_RATIO")
    class ApplySettings(
        var request: UpdateSettingsRequest,
        val source: String?
    ) : PipelineCommand("APPLY_SETTINGS")
}

/**
 * Single owner for capture-pipeline mutations. The pending queue is bounded by
 * coalescing settings and preview commands before they reach the actor. Stop
 * and recovery are inserted ahead of ordinary reconfiguration work. No caller
 * coroutine is left suspended merely trying to send to a rendezvous channel.
 */
class PipelineController(
    scope: CoroutineScope,
    private val handle: suspend (PipelineCommand) -> PipelineResult
) {
    private val lock = Any()
    private val pending = ArrayDeque<PipelineCommand>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var active: PipelineCommand? = null
    private val actor: Job = scope.launch {
        try {
            for (ignored in wake) {
                while (true) {
                    val command = synchronized(lock) {
                        pending.removeFirstOrNull()?.also { active = it }
                    } ?: break
                    try {
                        command.completion.complete(handle(command))
                    } catch (e: Exception) {
                        command.completion.completeExceptionally(e)
                    } finally {
                        synchronized(lock) { if (active === command) active = null }
                    }
                }
            }
        } finally {
            val cancelled = synchronized(lock) {
                val all = pending.toList() + listOfNotNull(active)
                pending.clear()
                active = null
                all
            }
            cancelled.forEach { cancel(it, "Pipeline controller closed") }
        }
    }

    suspend fun submit(command: PipelineCommand): PipelineResult {
        enqueue(command)
        return command.completion.await()
    }

    fun enqueue(command: PipelineCommand): CompletableDeferred<PipelineResult> {
        val cancelled = mutableListOf<PipelineCommand>()
        synchronized(lock) {
            if (closed) {
                cancel(command, "Pipeline controller is closed")
                return command.completion
            }
            when (command) {
                is PipelineCommand.ApplySettings -> {
                    val older = pending.filterIsInstance<PipelineCommand.ApplySettings>().filter {
                        ApplyPatchCoalescingPolicy.canMerge(it.request, it.source, command.request, command.source)
                    }
                    older.forEach {
                        pending.remove(it)
                        cancelled += it
                    }
                    val merged = older.fold(command.request) { newest, old ->
                        mergePatches(old.request, newest)
                    }
                    command.request = merged
                    pending.addLast(command)
                }
                is PipelineCommand.PreviewSurfaceChanged -> {
                    pending.filterIsInstance<PipelineCommand.PreviewSurfaceChanged>().forEach {
                        pending.remove(it)
                        cancelled += it
                    }
                    pending.addLast(command)
                }
                is PipelineCommand.SetLinearZoom,
                is PipelineCommand.SetZoomRatio -> {
                    pending.filter { it is PipelineCommand.SetLinearZoom || it is PipelineCommand.SetZoomRatio }
                        .forEach { pending.remove(it); cancelled += it }
                    pending.addLast(command)
                }
                is PipelineCommand.SetTorch -> {
                    pending.filterIsInstance<PipelineCommand.SetTorch>().forEach {
                        pending.remove(it); cancelled += it
                    }
                    pending.addLast(command)
                }
                is PipelineCommand.Stop -> {
                    pending.filter {
                        it is PipelineCommand.ApplySettings || it is PipelineCommand.PreviewSurfaceChanged ||
                            it is PipelineCommand.SetTorch || it is PipelineCommand.SetLinearZoom ||
                            it is PipelineCommand.SetZoomRatio
                    }
                        .forEach { pending.remove(it); cancelled += it }
                    pending.addFirst(command)
                }
                is PipelineCommand.Recover -> {
                    pending.filterIsInstance<PipelineCommand.Recover>().forEach {
                        pending.remove(it); cancelled += it
                    }
                    pending.addFirst(command)
                }
                is PipelineCommand.RuntimeH264Failure -> {
                    pending.filterIsInstance<PipelineCommand.RuntimeH264Failure>().forEach {
                        pending.remove(it); cancelled += it
                    }
                    pending.addFirst(command)
                }
                else -> pending.addLast(command)
            }
        }
        cancelled.forEach { cancel(it, "Superseded by a newer complete pipeline command") }
        wake.trySend(Unit)
        return command.completion
    }

    fun close() {
        val cancelled: List<PipelineCommand>
        synchronized(lock) {
            if (closed) return
            closed = true
            cancelled = pending.toList()
            pending.clear()
        }
        cancelled.forEach { cancel(it, "Pipeline controller closed") }
        wake.close()
        actor.cancel()
    }

    private fun cancel(command: PipelineCommand, reason: String) {
        command.completion.complete(
            PipelineResult(
                PipelineResultCode.CANCELLED,
                reason,
                StreamState.revision.get(),
                StreamState.pipelineGeneration.get(),
                StreamState.lifecycleState.get().name
            )
        )
    }

    /** Newer non-null values win while older pending fields are retained, so a
     * burst of partial slider/dropdown patches becomes one complete desired
     * state rather than a replay of obsolete intermediate configurations. */
    private fun mergePatches(older: UpdateSettingsRequest, newer: UpdateSettingsRequest) = newer.copy(
        baseRevision = newer.baseRevision ?: older.baseRevision,
        requestId = newer.requestId ?: older.requestId,
        clientType = newer.clientType ?: older.clientType,
        cameraId = newer.cameraId ?: older.cameraId,
        width = newer.width ?: older.width,
        height = newer.height ?: older.height,
        outputWidth = newer.outputWidth ?: older.outputWidth,
        outputHeight = newer.outputHeight ?: older.outputHeight,
        profile = newer.profile ?: older.profile,
        fps = newer.fps ?: older.fps,
        jpegQuality = newer.jpegQuality ?: older.jpegQuality,
        previewFitMode = newer.previewFitMode ?: older.previewFitMode,
        aspectRatio = newer.aspectRatio ?: older.aspectRatio,
        zoomSpeed = newer.zoomSpeed ?: older.zoomSpeed,
        displayRotation = newer.displayRotation ?: older.displayRotation,
        mirror = newer.mirror ?: older.mirror,
        localPreviewEnabled = newer.localPreviewEnabled ?: older.localPreviewEnabled,
        phonePreviewEnabled = newer.phonePreviewEnabled ?: older.phonePreviewEnabled,
        accessMode = newer.accessMode ?: older.accessMode,
        port = newer.port ?: older.port,
        accessToken = newer.accessToken ?: older.accessToken,
        streamMode = newer.streamMode ?: older.streamMode,
        h264Bitrate = newer.h264Bitrate ?: older.h264Bitrate,
        h264KeyframeInterval = newer.h264KeyframeInterval ?: older.h264KeyframeInterval,
        targetBandwidthMbps = newer.targetBandwidthMbps ?: older.targetBandwidthMbps
    )
}
