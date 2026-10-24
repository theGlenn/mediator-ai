package ai.xybrid.mediator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionWorkTest {
    @Test fun repeatedCancellationWaitsForTheActualNativeOperation() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val work = SessionWork(this)
        work.launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }
        runCurrent()
        var drained = false
        work.cancelAndDrain { drained = true }
        runCurrent()
        work.cancelAndDrain { drained = true }
        runCurrent()
        assertFalse(drained)
        assertThrows(IllegalStateException::class.java) { work.launch { } }
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(drained)
        var nextRan = false
        work.launch { nextRan = true }
        runCurrent()
        assertTrue(nextRan)
    }
}
