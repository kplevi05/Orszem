package hu.orszembejelento.service.auth.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ensures at most one refresh is in flight at a time.
 *
 * This matters more here than in ordinary request de-duplication. Refresh tokens **rotate**:
 * the server consumes the presented token and treats a second presentation of it as theft,
 * revoking the whole session. So if twenty queued requests each noticed a 401 and each sent
 * the refresh token they were holding, nineteen of them would be replaying an
 * already-consumed token and the user would be signed out — by the client's own doing.
 *
 * The first caller performs the refresh; everyone else awaits that same result and receives
 * the credentials it produced. One token in, one token out.
 *
 * The server does not rely on this being correct: PostgreSQL row locking and reuse detection
 * enforce the same invariant independently. This exists so a correct client never triggers
 * that protection against itself.
 */
class SingleFlight<T>(private val scope: CoroutineScope) {

    private val mutex = Mutex()

    /**
     * Holds a `Result`, not a bare value, so a throwing block completes the coroutine
     * normally instead of failing it.
     *
     * A failed `async` propagates its exception to the parent scope, which — unless the
     * caller happened to supply a supervisor job — would cancel that scope and leave every
     * later refresh permanently broken. Capturing the failure keeps the exception visible to
     * callers while containing it here, so correctness does not depend on how the scope was
     * constructed.
     */
    private var inFlight: Deferred<Result<T>>? = null

    /** Number of times [block] actually ran. Used by tests to prove de-duplication. */
    @Volatile
    var executions: Int = 0
        private set

    suspend fun run(block: suspend () -> T): T {
        // Take the existing attempt if one is running, otherwise start one. Holding the
        // mutex only around this decision — never around the await — is what lets the other
        // callers queue up on the same Deferred instead of serialising into separate
        // refreshes.
        val attempt = mutex.withLock {
            inFlight ?: scope.async {
                executions++
                runCatching { block() }
            }.also { inFlight = it }
        }

        try {
            return attempt.await().getOrThrow()
        } finally {
            // Clear only if this is still the current attempt: a later caller may already
            // have started the next one.
            mutex.withLock { if (inFlight === attempt) inFlight = null }
        }
    }
}
