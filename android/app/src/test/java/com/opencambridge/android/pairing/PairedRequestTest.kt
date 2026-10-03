package com.opencambridge.android.pairing

import com.opencambridge.android.service.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PairedRequestTest {
    private fun result() = PipelineResult(PipelineResultCode.OK, "ok", 0, 0, "STOPPED")
    @Test fun revokedRequestCannotExecuteAfterWaitingInPipelineQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var ran = false
        val controller = PipelineController(scope) { command ->
            if (command is PipelineCommand.Start) { entered.complete(Unit); release.await() }
            else ran = true
            result()
        }
        val blocker = controller.enqueue(PipelineCommand.Start()); entered.await()
        val request = Job()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            withContext(PairedRequest(request)) { controller.submit(PipelineCommand.SetTorch(true, 0, "id", "pc")) }
        }
        request.cancel(); release.complete(Unit); blocker.await(); work.await()
        assertFalse(ran)
        controller.close(); scope.cancel()
    }
    @Test fun revocationCancelsActiveControlWithoutClosingPipeline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val controller = PipelineController(scope) { command ->
            if (command is PipelineCommand.SetTorch) { entered.complete(Unit); awaitCancellation() }
            result()
        }
        val request = Job()
        val work = async { withContext(PairedRequest(request)) { controller.submit(PipelineCommand.SetTorch(true, 0, "id", "pc")) } }
        entered.await(); request.cancel()
        assertEquals(PipelineResultCode.CANCELLED, work.await().code)
        assertEquals(PipelineResultCode.OK, controller.submit(PipelineCommand.Start()).code)
        controller.close(); scope.cancel()
    }
    @Test fun serviceShutdownStillCancelsPairedControlWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val controller = PipelineController(scope) {
            try { entered.complete(Unit); awaitCancellation() }
            finally { stopped.complete(Unit) }
        }
        val request = Job()
        val work = async { withContext(PairedRequest(request)) { controller.submit(PipelineCommand.Start()) } }
        entered.await(); controller.close(); scope.cancel()
        try { withTimeout(2000) { stopped.await() }; work.await() }
        finally { request.cancel() }
        Unit
    }
}
