package dev.oxbreeze

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SessionPoolTest {
    private class Session : AutoCloseable {
        var running = true
        val request = CompletableFuture<String>()
        override fun close() {
            running = false
            request.completeExceptionally(IllegalStateException("Stopped"))
        }
    }

    @Test fun restartInvalidatesOutstandingRequestsAndUiResults() {
        val pool = SessionPool<String, Session>({ it.running }) { Session() }
        val oldGeneration = pool.generation
        val first = pool.acquire("package", oldGeneration)!!
        val pendingResult = CompletableFuture.supplyAsync { first.request.get(2, TimeUnit.SECONDS) }
        val stopped = pool.invalidate()
        assertNotEquals(oldGeneration, pool.generation, "Already queued results must fail the generation guard")
        assertNull(pool.acquire("package", oldGeneration), "Old work must not recreate a process")
        stopped.forEach { it.close() }
        assertFails { pendingResult.get(2, TimeUnit.SECONDS) }
        val replacement = pool.acquire("package", pool.generation)!!
        assertNotSame(first, replacement)
        replacement.request.complete("formatted")
        assertEquals("formatted", replacement.request.get())
        assertEquals(1, pool.activeCount())
        pool.invalidate().forEach { it.close() }
        assertFalse(replacement.running)
        assertEquals(0, pool.activeCount())
    }

    @Test fun oldFailureCannotRemoveReplacementSession() {
        val pool = SessionPool<String, Session>({ it.running }) { Session() }
        val first = pool.acquire("package", pool.generation)!!
        pool.invalidate().forEach { it.close() }
        val replacement = pool.acquire("package", pool.generation)!!
        pool.remove("package", first)
        assertSame(replacement, pool.acquire("package", pool.generation))
        assertSame(replacement, pool.acquire("package", pool.generation))
        assertEquals(1, pool.activeCount())
    }

    @Test fun restartDuringProcessCreationDoesNotBlockOrLeakTheProcess() {
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val created = Session()
        val pool = SessionPool<String, Session>({ it.running }) {
            creating.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            created
        }
        val generation = pool.generation
        val acquisition = CompletableFuture.supplyAsync { pool.acquire("package", generation) }
        try {
            assertTrue(creating.await(2, TimeUnit.SECONDS))
            val restart = CompletableFuture.supplyAsync { pool.invalidate() }
            assertTrue(restart.get(1, TimeUnit.SECONDS).isEmpty())
        } finally { release.countDown() }
        assertNull(acquisition.get(2, TimeUnit.SECONDS))
        assertFalse(created.running)
        assertEquals(0, pool.activeCount())
    }
}
