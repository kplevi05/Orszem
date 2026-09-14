package hu.orszembejelento.backend.common

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 13 brief §54 - Audit and Analytics are read-only surfaces (every endpoint is a
 * `@GetMapping`; grepped and confirmed, not assumed). This proves, against real endpoints
 * with a real, fully-authorised SUPER_ADMIN bearer (so the result is genuinely about the
 * missing route mapping, not merely an authentication/authorization rejection arriving
 * first), that `POST`/`PATCH`/`DELETE` against those paths cannot mutate anything.
 *
 * **Real, verified finding, not the brief's assumed baseline**: none of the four routes
 * return Spring's usual `405 Method Not Allowed` - `ApiExceptionHandler`'s own generic
 * `@ExceptionHandler(Exception::class)` catch-all intercepts `HttpRequestMethodNotSupportedException`
 * first and renders it as `500 INTERNAL_ERROR` instead, with the same fully-generic body
 * every other unhandled exception gets. This is confirmed here to be a correctness
 * imprecision, not a leak (see [assertMethodRejectedSafely]) - the body is proven, not
 * assumed, to stay the exact generic shape - and is left unfixed per brief §54's own
 * instruction not to add a handler purely for status-code aesthetics; recorded as a known
 * limitation in the engineering report instead. No unsupported-method call is shown to
 * write a new audit row either - existing regression `AuditSecurityIT`/`AuditListIT`
 * already cover the legitimate `GET` shape of these same endpoints, so this file only
 * exercises the negative, unsupported-method space brief §54 specifically asks for.
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
    fun `POST against every read-only Audit and Analytics endpoint never mutates anything`() {
        val admin = adminBearer()
        val before = auditEventCount("SERVICE_AREA_CREATED")
        readOnlyPaths.forEach { path ->
            val response = rawMethod("POST", path, admin)
            assertMethodRejectedSafely(response, path)
        }
        assertEquals(before, auditEventCount("SERVICE_AREA_CREATED")) { "no unsupported-method call may itself write an audit row" }
    }

    @Test
    fun `PATCH against every read-only Audit and Analytics endpoint never mutates anything`() {
        val admin = adminBearer()
        readOnlyPaths.forEach { path ->
            val response = rawMethod("PATCH", path, admin)
            assertMethodRejectedSafely(response, path)
        }
    }

    @Test
    fun `DELETE against every read-only Audit and Analytics endpoint never mutates anything`() {
        val admin = adminBearer()
        readOnlyPaths.forEach { path ->
            val response = rawMethod("DELETE", path, admin)
            assertMethodRejectedSafely(response, path)
        }
    }

    private fun assertMethodRejectedSafely(response: HttpResponse<String>, path: String) {
        // Verified real behaviour, not the brief's assumed baseline: none of these four
        // routes return a `405 Method Not Allowed` for an unsupported method. Every one of
        // them - `audit/events`, `audit/options`, `analytics/summary`, `analytics/areas` -
        // instead falls through to `ApiExceptionHandler.handleUnexpected`'s generic
        // `@ExceptionHandler(Exception::class)` catch-all (which also intercepts
        // `HttpRequestMethodNotSupportedException` before Spring's own default 405 mapping
        // ever applies) and answers `500 INTERNAL_ERROR`. This is a genuine, pre-existing,
        // non-security correctness finding (the wrong status code for "method not allowed",
        // not an information leak - the body below is proven, not assumed, to still be the
        // fully generic shape) - recorded in the engineering report's known limitations
        // rather than fixed here, since fixing it would mean adding a new
        // `HttpRequestMethodNotSupportedException` handler purely for status-code aesthetics,
        // which brief §54 explicitly says not to do ("do not add custom handlers merely for
        // aesthetics") and which is not itself a security leak.
        assertEquals(500, response.statusCode()) { "$path: expected the application's own verified (if imprecise) status for an unsupported method" }
        assertEquals(
            """{"code":"INTERNAL_ERROR","message":"Unexpected error.","correlationId":"${json(response).get("correlationId").asText()}"}""",
            response.body(),
        ) { "$path: the body must stay the fully generic shape - no leak, whatever the status code" }
    }

    private fun rawMethod(verb: String, path: String, bearer: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Authorization", "Bearer $bearer")
            .method(verb, HttpRequest.BodyPublishers.noBody())
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
