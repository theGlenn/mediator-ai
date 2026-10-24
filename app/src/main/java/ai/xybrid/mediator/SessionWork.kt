package ai.xybrid.mediator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keep the native operation separate from replaceable cancellation waiters. Main-thread owned. */
internal class SessionWork(private val scope: CoroutineScope) {
    private var operation: Job? = null
    private var drain: Job? = null

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        check(operation?.isCompleted != false) { "The previous operation is still finishing." }
        operation = scope.launch(block = block)
    }

    fun cancelAndDrain(onDrained: () -> Unit) {
        val actualOperation = operation
        actualOperation?.cancel()
        drain?.cancel()
        drain = scope.launch {
            actualOperation?.join()
            onDrained()
        }
    }

    fun cancel() { operation?.cancel() }
    suspend fun join() { operation?.join() }
}
