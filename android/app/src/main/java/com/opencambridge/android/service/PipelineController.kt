package com.opencambridge.android.service

import com.opencambridge.android.server.UpdateSettingsRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

enum class PipelineResultCode {
    OK,
    CONFLICT,
    UNPROCESSABLE,
    FAILED
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
    class ApplySettings(
        val request: UpdateSettingsRequest,
        val source: String?
    ) : PipelineCommand("APPLY_SETTINGS")
}

/**
 * A single-consumer actor for every capture-pipeline mutation. API handlers,
 * the phone UI, and watchdogs enqueue commands and await the same completion;
 * none of them may independently stop/start Camera2 or MediaCodec.
 */
class PipelineController(
    scope: CoroutineScope,
    private val handle: suspend (PipelineCommand) -> PipelineResult
) {
    // Rendezvous keeps lifecycle/control queue depth at zero: each producer
    // hands one command directly to the sole owner and then awaits completion.
    // This prevents a burst of stale reconfiguration commands accumulating.
    private val commands = Channel<PipelineCommand>(Channel.RENDEZVOUS)
    private val actor = scope.launch {
        for (command in commands) {
            try {
                command.completion.complete(handle(command))
            } catch (e: Exception) {
                command.completion.completeExceptionally(e)
            }
        }
    }

    suspend fun submit(command: PipelineCommand): PipelineResult {
        commands.send(command)
        return command.completion.await()
    }

    fun enqueue(scope: CoroutineScope, command: PipelineCommand) {
        scope.launch { submit(command) }
    }

    fun close() {
        commands.close()
        actor.cancel()
    }
}
