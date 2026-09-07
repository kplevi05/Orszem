package hu.orszembejelento.service.auth

import hu.orszembejelento.service.auth.data.SingleFlight
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property under test is not merely "fewer requests": it is that a rotating refresh
 * token is never sent twice. A second send of an already-consumed token is indistinguishable
 * from theft to the server, which revokes the session — so a client that fans out on 401
 * would sign its own user out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {

    @Test
    fun `twenty concurrent callers cause exactly one execution`() = runTest {
        val scope = TestScope(testScheduler)
        val singleFlight = SingleFlight<String>(scope)
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()

        val callers = (1..20).map {
            async {
                singleFlight.run {
                    started.incrementAndGet()
                    // Hold the flight open so every caller arrives while it is running.
                    gate.await()
                    "rotated"
                }
            }
        }

        advanceUntilIdle()
        gate.complete(Unit)
        val results = callers.awaitAll()

        assertEquals("the work must run exactly once", 1, started.get())
        assertEquals(1, singleFlight.executions)
        // Every caller receives the result of that single execution.
        assertTrue(results.all { it == "rotated" })
        assertEquals(20, results.size)
    }

    @Test
    fun `a later caller starts a new flight once the previous one finished`() = runTest {
        val scope = TestScope(testScheduler)
        val singleFlight = SingleFlight<Int>(scope)

        val first = singleFlight.run { 1 }
        advanceUntilIdle()
        val second = singleFlight.run { 2 }
        advanceUntilIdle()

        assertEquals(1, first)
        assertEquals(2, second)
        // Sequential calls are genuinely separate refreshes, not a cached result.
        assertEquals(2, singleFlight.executions)
    }

    @Test
    fun `a failing flight propagates to every waiting caller and does not stick`() = runTest {
        val scope = TestScope(testScheduler)
        val singleFlight = SingleFlight<String>(scope)
        val gate = CompletableDeferred<Unit>()

        val failing = (1..5).map {
            async {
                runCatching {
                    singleFlight.run {
                        gate.await()
                        error("refresh failed")
                    }
                }
            }
        }
        advanceUntilIdle()
        gate.complete(Unit)
        val outcomes = failing.awaitAll()

        assertTrue("every caller must see the failure", outcomes.all { it.isFailure })
        assertEquals(1, singleFlight.executions)

        // The coordinator must not be wedged by the failure.
        val recovered = singleFlight.run { "ok" }
        advanceUntilIdle()
        assertEquals("ok", recovered)
        assertEquals(2, singleFlight.executions)
    }
}
