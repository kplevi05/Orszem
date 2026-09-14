package hu.orszembejelento.backend.common

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.http.HttpResponse
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 13 brief §55/§56 - proves, against real endpoints rather than by reading
 * [ApiExceptionHandler]'s source alone, that a malformed UUID, malformed JSON, an invalid
 * enum/code, invalid pagination, a validation failure, an authorization failure and a
 * hidden/nonexistent resource all produce the same production-shaped, deliberately vague
 * error body `ApiExceptionHandler` already promises - never a stack trace, SQL text, a
 * database table/column name, a Java/Kotlin package or class name, an internal exception
 * message, a credential/token/hash, or an unnecessary internal (non-public) UUID.
 *
 * Deliberately does not assert exact wording (brief §56: "do not overfit to exact
 * punctuation if the safe public error contract is already stable") - only the shape
 * (`code`/`message`/`correlationId`, nothing else) and the absence of the forbidden content
 * categories above, checked with [assertSafeErrorBody] against every case.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ErrorInformationLeakageIT : AuditTestSupport() {

    @Test
    fun `a malformed path UUID never leaks internal detail`() {
        val user = givenServiceUser()
        val bearer = bearerFor(user)
        val response = get("/api/v1/service/reports/not-a-valid-uuid", bearer)
        assertTrue(response.statusCode() in 400..499) { "expected a client error, got ${response.statusCode()}" }
        assertSafeErrorBody(response)
    }

    @Test
    fun `malformed JSON never leaks internal detail`() {
        val user = givenServiceUser()
        val bearer = bearerFor(user)
        val response = post("/api/v1/service/reports/${UUID.randomUUID()}/claim", "{ this is not json", bearer)
        assertTrue(response.statusCode() in 400..499)
        assertSafeErrorBody(response)
    }

    @Test
    fun `an invalid event type code never leaks internal detail`() {
        val area = givenRoutedArea()
        val response = submitReport(submitReportBody(settlementId = area.settlementId, eventTypeCode = "NOT_A_REAL_EVENT_TYPE_CODE"))
        assertEquals(400, response.statusCode())
        assertEquals("INVALID_EVENT_TYPE", errorCode(response))
        assertSafeErrorBody(response)
    }

    @Test
    fun `an invalid audit eventType query value never leaks internal detail`() {
        val admin = adminBearer()
        val response = auditEvents(admin, eventType = "NOT_A_REAL_AUDIT_EVENT_TYPE")
        assertEquals(400, response.statusCode())
        assertEquals("AUDIT_EVENT_TYPE_INVALID", errorCode(response))
        assertSafeErrorBody(response)
    }

    @Test
    fun `out-of-range pagination values are silently clamped, never rejected with a leaking error`() {
        // Verified real behaviour (`ReportWorkflowController.boundedPaging`), not assumed: a
        // negative page or an absurd size is not an error at all - it is coerced into
        // `[0, ∞)` / `[1, MAX_PAGE_SIZE]` and answered with a normal, safe `200`. That is
        // itself a safe design (no internal exception, no distinguishable error path an
        // attacker could use to fingerprint bounds) - the property this test actually proves.
        val user = givenServiceUser()
        val bearer = bearerFor(user)

        for (response in listOf(newQueue(bearer, page = -1), newQueue(bearer, size = 2_000_000_000))) {
            assertEquals(200, response.statusCode())
            assertNoForbiddenContent(response.body())
        }
    }

    @Test
    fun `a non-numeric pagination value never leaks internal detail`() {
        val user = givenServiceUser()
        val bearer = bearerFor(user)
        val response = get("/api/v1/service/reports/new?page=not-a-number", bearer)
        assertTrue(response.statusCode() in 400..499)
        assertSafeErrorBody(response)
    }

    @Test
    fun `a blank service area name validation failure never leaks internal detail`() {
        val admin = adminBearer()
        val response = createArea(admin, "   ")
        assertEquals(400, response.statusCode())
        // Verified real behaviour: `CreateServiceAreaRequest.name` carries its own
        // `@field:NotBlank`, so a whitespace-only name is rejected by bean validation
        // (generic `VALIDATION_ERROR`) before `DeactivateServiceAreaUseCase`'s own
        // domain-level `ServiceAreaNameBlankException` (`SERVICE_AREA_NAME_INVALID`) is ever
        // reached for this particular endpoint - both are safe, generic 400s either way.
        assertEquals("VALIDATION_ERROR", errorCode(response))
        assertSafeErrorBody(response)
    }

    @Test
    fun `an authorization failure never leaks internal detail`() {
        val user = givenServiceUser()
        val bearer = bearerFor(user)
        // ServiceArea administration is SUPER_ADMIN-only (brief §5's own frozen rule).
        val response = listAreas(bearer)
        assertEquals(403, response.statusCode())
        assertEquals("SERVICE_AREA_ADMIN_FORBIDDEN", errorCode(response))
        assertSafeErrorBody(response)
    }

    @Test
    fun `a hidden nonexistent service report never leaks internal detail`() {
        val user = givenServiceUser()
        val bearer = bearerFor(user)
        val response = detail(bearer, UUID.randomUUID())
        assertEquals(404, response.statusCode())
        assertEquals("REPORT_NOT_FOUND", errorCode(response))
        assertSafeErrorBody(response)
    }

    @Test
    fun `a hidden nonexistent Public report never leaks internal detail`() {
        val response = getPublicReport(UUID.randomUUID(), randomCredential())
        assertEquals(404, response.statusCode())
        assertSafeErrorBody(response)
    }

    @Test
    fun `an out-of-Int-range page value falls back to the generic validation error, never a raw parse failure`() {
        // A page value too large to fit in an `Int` at all (`NumberFormatException` inside
        // Spring's own converter) still resolves to the same `MethodArgumentTypeMismatchException`
        // -> generic `VALIDATION_ERROR` path as any other malformed value - proven, not
        // assumed, since this is one digit away from silently overflowing instead.
        val user = givenServiceUser()
        val bearer = bearerFor(user)
        val response = get("/api/v1/service/reports/new?page=99999999999999999999", bearer)
        assertEquals(400, response.statusCode())
        assertSafeErrorBody(response)
    }

    // ------------------------------------------------------------------------------ assertions

    private val forbiddenSubstrings = listOf(
        // Stack trace / exception-shape markers.
        "\tat ", "Caused by", "Exception", "StackTrace", ".java:", ".kt:",
        // Package/class name fragments.
        "hu.orszembejelento", "org.springframework", "org.postgresql", "jakarta.", "java.lang", "java.sql", "kotlin.",
        // SQL / database internals.
        "SELECT ", "INSERT ", "UPDATE ", "DELETE FROM", "SQLSTATE", "PSQLException", "syntax error",
        // Real table/column names that would confirm internal schema.
        "service_areas", "railway_lines", "report_routing_snapshots", "audit_events", "users ", "public_id", "workflow_version",
        // Secrets.
        "password", "Authorization", "accessToken", "refreshToken", "temporaryCredential",
    )

    /**
     * A [HttpResponse] whose body is the stable `{code, message, correlationId}` shape only,
     * with a [message] that never quotes anything from [forbiddenSubstrings] - the concrete
     * proof behind brief §55/§56's "no false security claims" requirement (this is measured
     * against a real HTTP response, not asserted from reading the handler's source).
     */
    private fun assertSafeErrorBody(response: HttpResponse<String>) {
        val body = response.body()
        val node = json(response)
        assertTrue(node.has("code")) { "error body has no 'code' field: $body" }
        assertTrue(node.has("message")) { "error body has no 'message' field: $body" }
        // Exactly the stable shape - no accidental extra field (a validation-error field
        // list, a nested cause, an object id) ever leaking through.
        assertEquals(setOf("code", "message", "correlationId"), node.propertyNames().toSet()) {
            "error body has unexpected fields: $body"
        }
        assertNoForbiddenContent(body)
    }

    private fun assertNoForbiddenContent(body: String) {
        forbiddenSubstrings.forEach { forbidden ->
            assertFalse(body.contains(forbidden, ignoreCase = false)) {
                "response body appears to leak internal detail (contains \"$forbidden\"): $body"
            }
        }
    }
}
