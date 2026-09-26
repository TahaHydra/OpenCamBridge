package com.opencambridge.android.service

import com.opencambridge.android.server.UpdateSettingsRequest
import com.opencambridge.android.state.LifecycleState
import com.opencambridge.android.state.StreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineControllerTest {
    private fun ok(command: PipelineCommand) = PipelineResult(
        PipelineResultCode.OK, command.label, StreamState.revision.get(),
        StreamState.pipelineGeneration.get(), StreamState.lifecycleState.get().name
    )

    @Test fun lifecyclePolicyCoversStartStopReconfigureFailureAndRecovery() {
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.STOPPED, LifecycleState.STARTING))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.STOPPED, LifecycleState.RECOVERING))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.STARTING, LifecycleState.STREAMING))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.STREAMING, LifecycleState.RECONFIGURING))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.RECONFIGURING, LifecycleState.STREAMING))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.STREAMING, LifecycleState.FAILED))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.FAILED, LifecycleState.RECOVERING))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.RECOVERING, LifecycleState.STREAMING))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.STREAMING, LifecycleState.STOPPING))
        assertTrue(PipelineLifecyclePolicy.permits(LifecycleState.STOPPING, LifecycleState.STOPPED))
        assertFalse(PipelineLifecyclePolicy.permits(LifecycleState.STOPPED, LifecycleState.STREAMING))
        assertFalse(PipelineLifecyclePolicy.permits(LifecycleState.STOPPING, LifecycleState.RECONFIGURING))
    }

    @Test fun recoverRunsBeforeOrdinaryPendingCommand() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val controller = PipelineController(scope) { command ->
            synchronized(order) { order += command.label }
            if (command is PipelineCommand.Start) { entered.complete(Unit); release.await() }
            ok(command)
        }
        val start = controller.enqueue(PipelineCommand.Start())
        entered.await()
        val apply = controller.enqueue(PipelineCommand.ApplySettings(patch("web", fps = 30), "web"))
        val recover = controller.enqueue(PipelineCommand.Recover())
        release.complete(Unit)
        assertEquals(PipelineResultCode.OK, start.await().code)
        assertEquals(PipelineResultCode.OK, recover.await().code)
        assertEquals(PipelineResultCode.OK, apply.await().code)
        assertEquals(listOf("START", "RECOVER", "APPLY_SETTINGS"), synchronized(order) { order.toList() })
        controller.close(); scope.cancel()
    }

    @Test fun stopCancelsObsoleteReconfigurationAndPreview() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val controller = PipelineController(scope) { command ->
            synchronized(order) { order += command.label }
            if (command is PipelineCommand.Start) { entered.complete(Unit); release.await() }
            ok(command)
        }
        val start = controller.enqueue(PipelineCommand.Start())
        entered.await()
        val apply = controller.enqueue(PipelineCommand.ApplySettings(patch("phone", fps = 30), "phone"))
        val preview = controller.enqueue(PipelineCommand.PreviewSurfaceChanged(true))
        val stop = controller.enqueue(PipelineCommand.Stop())
        assertEquals(PipelineResultCode.CANCELLED, apply.await().code)
        assertEquals(PipelineResultCode.CANCELLED, preview.await().code)
        release.complete(Unit)
        start.await(); stop.await()
        assertEquals(listOf("START", "STOP"), synchronized(order) { order.toList() })
        controller.close(); scope.cancel()
    }

    @Test fun compatibleSettingsCoalesceIntoNewestCompleteState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var applied: UpdateSettingsRequest? = null
        val controller = PipelineController(scope) { command ->
            if (command is PipelineCommand.Start) { entered.complete(Unit); release.await() }
            if (command is PipelineCommand.ApplySettings) applied = command.request
            ok(command)
        }
        val start = controller.enqueue(PipelineCommand.Start())
        entered.await()
        val old = controller.enqueue(PipelineCommand.ApplySettings(patch("tauri", width = 1280), "tauri"))
        val newest = controller.enqueue(PipelineCommand.ApplySettings(patch("tauri", fps = 60), "tauri"))
        assertEquals(PipelineResultCode.CANCELLED, old.await().code)
        release.complete(Unit)
        start.await(); newest.await()
        assertEquals(1280, applied?.width)
        assertEquals(60, applied?.fps)
        assertTrue(applied?.requestId?.contains("60") == true)
        controller.close(); scope.cancel()
    }

    @Test fun handlerFailureDoesNotDisableRecovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PipelineController(scope) { command ->
            if (command is PipelineCommand.Start) error("simulated start failure")
            ok(command)
        }
        assertTrue(runCatching { controller.submit(PipelineCommand.Start()) }.isFailure)
        assertEquals(PipelineResultCode.OK, controller.submit(PipelineCommand.Recover()).code)
        controller.close(); scope.cancel()
    }

    @Test fun closeCompletesActiveAndPendingCommandsAsCancelled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val controller = PipelineController(scope) { command ->
            if (command is PipelineCommand.Start) {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            ok(command)
        }
        val active = controller.enqueue(PipelineCommand.Start())
        entered.await()
        val pending = controller.enqueue(PipelineCommand.ApplySettings(patch("web", fps = 30), "web"))
        controller.close()
        assertEquals(PipelineResultCode.CANCELLED, active.await().code)
        assertEquals(PipelineResultCode.CANCELLED, pending.await().code)
        scope.cancel()
    }

    @Test fun pendingQueueIsBounded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = PipelineController(scope) { command ->
            if (command is PipelineCommand.Start && !entered.isCompleted) {
                entered.complete(Unit); release.await()
            }
            ok(command)
        }
        controller.enqueue(PipelineCommand.Start())
        entered.await()
        val queued = (0 until 70).map { controller.enqueue(PipelineCommand.Start()) }
        assertTrue(queued.count { it.isCompleted } >= 6)
        assertTrue(queued.filter { it.isCompleted }.all { it.await().code == PipelineResultCode.CANCELLED })
        controller.close(); release.complete(Unit); scope.cancel()
    }

    @Test fun staleGenerationCallbacksCannotPublishSelectedOrActualState() {
        val oldGeneration = StreamState.pipelineGeneration.incrementAndGet()
        StreamState.publishSelectedPipeline(oldGeneration, "0", "h264", "regular", 1920, 1080, 60, "encoder", true)
        StreamState.publishActualPipeline(oldGeneration, 1920, 1080, 60, 60, 8_000_000)
        assertEquals(oldGeneration, StreamState.toStatusDto().snapshot.selected?.generation)

        val currentGeneration = StreamState.pipelineGeneration.incrementAndGet()
        StreamState.publishSelectedPipeline(oldGeneration, "0", "mjpeg", "stale", 640, 480, 15, null, false)
        StreamState.publishActualPipeline(oldGeneration, 640, 480, 15, 15, 0)
        StreamState.publishPhonePreview(oldGeneration, true)
        val status = StreamState.toStatusDto()
        assertEquals(currentGeneration, status.pipelineGeneration)
        assertNull(status.snapshot.selected)
        assertNull(status.snapshot.actual)
        assertFalse(status.phonePreviewActive)
    }

    private fun patch(source: String, width: Int? = null, fps: Int? = null) = UpdateSettingsRequest(
        baseRevision = 42,
        requestId = "$source-${width ?: fps}",
        clientType = source,
        width = width,
        fps = fps
    )
}
