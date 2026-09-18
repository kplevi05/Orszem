package hu.orszembejelento.backend.common

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 13 brief §54, and the owner's post-approval follow-up closing it out properly - a
 * client-caused routing/method mistake must not masquerade as an internal server failure.
 * Audit and Analytics are read-only surfaces (every endpoint is a `@GetMapping`; grepped and
 * confirmed, not assumed). This proves, against real endpoints with a real, fully-authorised
 * SUPER_ADMIN bearer (so the result is genuinely about the missing route/method mapping, not
 * merely an authentication/authorization rejection arriving first), that `POST`/`PATCH`/
 * `DELETE` against those paths cannot mutate anything, and now correctly answer `405`.
 *
 * **Root cause, found live (not by inspection alone)**: `ApiExceptionHandler`'s own generic
 * `@ExceptionHandler(Exception::class)` catch-all was intercepting
 * [org.springframework.web.HttpRequestMethodNotSupportedException] - a genuinely 405-shaped
 * condition Spring itself would map correctly - before Spring's own default resolver ever
 * got a chance, simply because no more specific handler existed for it. **Fixed** by adding
 * `ApiExceptionHandler.handleMethodNotSupported`, a dedicated `@ExceptionHandler` for exactly
 * that exception type - Spring always prefers the more specific handler within one advice
 * bean, so no dispatch configuration changed, no per-route special-casing was added, no
 * business rule or authentication behaviour changed, and the response body stays the exact
 * same generic shape every other error already uses (`{code, message, correlationId}`), only
 * the code (`METHOD_NOT_ALLOWED`) and status (`405`, with a correct `Allow` header) are new.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class UnsupportedHttpMethodIT : AuditTestSupport() {

    private val readOnlyPaths = listOf(
        "/api/v1/service/audit/events",
        "/api/v1/service/audit/options",
        "/api/v1/service/analytics/summary",
        "/api/v1/service/analytics/areas",
    )

    @Test
    fun `POST against every read-only Audit and Analytics endpoint is safely rejected as 405 and never mutates anything`() {
        val admin = adminBearer()
        val before = auditEventCount("SERVICE_AREA_CREATED")
        readOnlyPaths.forEach { path ->
            val response = rawMethod("POST", path, admin)
            assertMethodRejectedSafely(response, path)
        }
        assertEquals(before, auditEventCount("SERVICE_AREA_CREATED")) { "no unsupported-method call may itself write an audit row" }
    }

    @Test
    fun `PATCH against every read-only Audit and Analytics endpoint is safely rejected as 405 and never mutates anything`() {
        val admin = adminBearer()
        readOnlyPaths.forEach { path ->
            val response = rawMethod("PATCH", path, admin)
            assertMethodRejectedSafely(response, path)
        }
    }

    @Test
    fun `DELETE against every read-only Audit and Analytics endpoint is safely rejected as 405 and never mutates anything`() {
        val admin = adminBearer()
        readOnlyPaths.forEach { path ->
            val response = rawMethod("DELETE", path, admin)
            assertMethodRejectedSafely(response, path)
        }
    }

    @Test
    fun `a completely unmapped path is safely rejected as 404, not 500`() {
        val admin = adminBearer()
        val response = rawMethod("GET", "/api/v1/service/this-route-does-not-exist", admin)
        assertEquals(404, response.statusCode())
        assertEquals("NOT_FOUND", errorCode(response))
        assertEquals(setOf("code", "message", "correlationId"), json(response).propertyNames().toSet())
        assertNoLeak(response.body())
    }

    @Test
    fun `an unmapped path under the Public surface is also safely rejected as 404, not 500`() {
        // No Authorization header at all - the Public surface has no authentication to
        // intercept first, so this proves the 404 handling holds independent of auth.
        val response = rawMethod("GET", "/api/v1/public/this-route-does-not-exist", bearer = null)
        assertEquals(404, response.statusCode())
        assertEquals("NOT_FOUND", errorCode(response))
        assertNoLeak(response.body())
    }

    @Test
    fun `a real domain 404 - a nonexistent report - is unaffected and still uses its own specific code`() {
        // Proves the new generic NOT_FOUND handler does not shadow an existing, more
        // specific 404 (REPORT_NOT_FOUND) - the two must stay distinguishable.
        val admin = adminBearer()
        val response = detail(admin, java.util.UUID.randomUUID())
        assertEquals(404, response.statusCode())
        assertEquals("REPORT_NOT_FOUND", errorCode(response))
    }

    @Test
    fun `validation and authorization behaviour is unchanged by the routing fix`() {
        // Malformed JSON against a real POST-accepting route still validates the same way
        // (400 VALIDATION_ERROR) - `audit/events` itself only accepts GET, so a malformed
        // body there would now correctly report 405 first (method checked before the body is
        // ever parsed); login is the right route to prove body validation is untouched.
        val admin = adminBearer()
        val malformed = post("/api/v1/service/auth/login", "{ not json", admin)
        assertEquals(400, malformed.statusCode())
        assertEquals("VALIDATION_ERROR", errorCode(malformed))

        // ...and an unauthenticated request to a real, protected GET-only route still gets
        // 401 before any routing/method concern is even reached - the fix did not change
        // authentication ordering.
        val unauthenticated = rawMethod("GET", "/api/v1/service/audit/events", bearer = null)
        assertEquals(401, unauthenticated.statusCode())
        assertEquals("SESSION_INVALID", errorCode(unauthenticated))
    }

    private fun assertMethodRejectedSafely(response: HttpResponse<String>, path: String) {
        assertEquals(405, response.statusCode()) { "$path: expected 405 Method Not Allowed" }
        assertEquals("METHOD_NOT_ALLOWED", errorCode(response)) { "$path" }
        assertEquals(setOf("code", "message", "correlationId"), json(response).propertyNames().toSet()) { "$path: unexpected body shape" }
        assertTrue(response.headers().firstValue("Allow").isPresent) { "$path: a 405 must carry an Allow header" }
        assertEquals("GET", response.headers().firstValue("Allow").get()) { "$path: Allow must name the real supported method" }
        assertNoLeak(response.body())
    }

    private fun assertNoLeak(body: String) {
        assertFalse(body.contains("hu.orszembejelento")) { "leaked an internal package name: $body" }
        assertFalse(body.contains("Exception")) { "leaked an exception type name: $body" }
        assertFalse(body.contains("\tat ")) { "leaked a stack frame: $body" }
    }

    private fun rawMethod(verb: String, path: String, bearer: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .method(verb, HttpRequest.BodyPublishers.noBody())
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
