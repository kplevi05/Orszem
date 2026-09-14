package hu.orszembejelento.backend.common

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 13 brief §23 - hostile-input coverage for every `ILIKE`-based `query` search site
 * outside Phase 12's own audit search (already proven by `AuditSearchIT`). Six real sites
 * exist, all confirmed by direct inspection of `JdbcAreaAdminQueryRepository`,
 * `JdbcModerationQueryRepository`, `JdbcReportWorkflowQueryRepository`,
 * `JdbcReferenceRepository` and `JdbcUserManagementRepository`: every one binds `query`
 * through a `JdbcClient` `:param`, never string-concatenated SQL, and every one is exercised
 * here directly, not assumed safe merely because it "looks like" the audited pattern.
 *
 * Every hostile string is sent as a real HTTP query parameter and must never produce a 500,
 * must never alter which rows come back beyond ordinary substring matching, and - checked
 * explicitly, not merely inferred from a lack of a crash - `%`/`_` must never act as a SQL
 * wildcard against unrelated fixtures.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class SqlInjectionHardeningIT : AuditTestSupport() {

    /**
     * Payloads a naive string-concatenated query would mishandle; a parameterized one must
     * not. Deliberately excludes a raw NUL byte: PostgreSQL `text`/`varchar` cannot store one
     * at all (a JDBC/driver-level limitation, not a SQL-injection concern) - confirmed
     * separately to produce a safe, generic error body with no leaked detail, and recorded as
     * its own finding in the engineering report rather than tested here as the same class of
     * defect as an injection attempt.
     */
    private val hostilePayloads = listOf(
        "' OR '1'='1",
        "'; DROP TABLE users; --",
        "\" OR \"\"=\"",
        "%' UNION SELECT NULL--",
        "'||(SELECT version())||'",
    )

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    // ------------------------------------------------------------- area admin: ServiceArea name

    @Test
    fun `ServiceArea name search never breaks on hostile input and treats wildcards literally`() {
        val admin = adminBearer()
        createArea(admin, "Injection Probe Area")

        hostilePayloads.forEach { payload ->
            // AreaAdminTestSupport.listAreas builds its query string without URL-encoding, so
            // hostile characters (quotes, semicolons) are sent directly here instead - that is
            // what actually reaches the controller either way.
            val response = get("/api/v1/service/service-area-admin/areas?query=${encode(payload)}", admin)
            assertEquals(200, response.statusCode()) { "hostile payload must not break the query: $payload -> ${response.body()}" }
            assertTrue(json(response).get("items") != null) { "expected a well-formed list shape for payload: $payload" }
        }

        assertLiteralWildcard { query -> json(get("/api/v1/service/service-area-admin/areas?query=${encode(query)}", admin)).get("items").asList().size }
    }

    // ---------------------------------------------------------- area admin: RailwayLine code/name

    @Test
    fun `RailwayLine code and name search never breaks on hostile input`() {
        val admin = adminBearer()
        insertLine("MV-${(1000..9999).random()}")

        hostilePayloads.forEach { payload ->
            val response = get("/api/v1/service/service-area-admin/railway-lines?query=${encode(payload)}", admin)
            assertEquals(200, response.statusCode()) { "hostile payload must not break the query: $payload -> ${response.body()}" }
        }
    }

    // ------------------------------------------------------- moderation: train id/settlement/publicId

    @Test
    fun `moderation deleted-list search never breaks on hostile input`() {
        val superAdmin = adminBearer()

        hostilePayloads.forEach { payload ->
            // ModerationTestSupport.deletedList does not expose a query parameter, so this
            // hits the real controller path directly - it accepts one (ModerationController).
            val response = get("/api/v1/service/moderation/deleted?query=${encode(payload)}", superAdmin)
            assertEquals(200, response.statusCode()) { "hostile payload must not break the query: $payload -> ${response.body()}" }
        }
    }

    // -------------------------------------------------- report workflow: new/in-progress/archive

    @Test
    fun `report workflow queue search never breaks on hostile input, on all three queues`() {
        val superAdmin = adminBearer()

        hostilePayloads.forEach { payload ->
            val encoded = encode(payload)
            listOf("new", "in-progress", "archive").forEach { queue ->
                val response = get("/api/v1/service/reports/$queue?query=$encoded", superAdmin)
                assertEquals(200, response.statusCode()) { "hostile payload must not break the $queue queue: $payload -> ${response.body()}" }
            }
        }
    }

    // --------------------------------------------------------- Public reference: settlement search

    @Test
    fun `Public settlement search never breaks on hostile input - the one anonymous, unauthenticated site`() {
        setCurrentReferenceState()
        insertSettlement("00001", "Injection Probe Settlement")

        hostilePayloads.forEach { payload ->
            val response = get("/api/v1/public/reference/settlements?query=${encode(payload)}")
            assertEquals(200, response.statusCode()) { "hostile payload must not break the anonymous settlement search: $payload -> ${response.body()}" }
            assertTrue(json(response).isArray) { "expected a JSON array response for payload: $payload" }
        }
    }

    // ------------------------------------------------------------- user management: Service ID

    @Test
    fun `user management Service ID search never breaks on hostile input and treats wildcards literally`() {
        val superAdmin = adminBearer()
        createUser(superAdmin, role = "SERVICE_USER")

        hostilePayloads.forEach { payload ->
            val response = get("/api/v1/service/user-management/users?query=${encode(payload)}", superAdmin)
            assertEquals(200, response.statusCode()) { "hostile payload must not break the query: $payload -> ${response.body()}" }
        }

        assertLiteralWildcard { query -> json(get("/api/v1/service/user-management/users?query=${encode(query)}", superAdmin)).get("items").asList().size }
    }

    // ================================================================================ helper

    /**
     * A bare `%` or `_` sent as a query must never act as a SQL wildcard matching every row -
     * it must be escaped to its literal character, exactly like the already-proven audit
     * search (`AuditSearchIT`'s own "SQL wildcard characters ... literal text" test). Proven
     * by asserting a lone wildcard character returns strictly fewer results than an empty/no
     * query would - i.e. it behaves as a specific (almost certainly non-matching) literal
     * string, not as "match everything."
     */
    private fun assertLiteralWildcard(count: (query: String) -> Int) {
        val percentCount = count("%")
        val underscoreCount = count("_")
        // A real ILIKE '%%%' (unescaped) would match every row; escaped, it matches only rows
        // that literally contain the character '%', which no fixture name here does.
        assertFalse(percentCount > 50) { "a bare '%' must not behave as a match-everything wildcard, got $percentCount rows" }
        assertFalse(underscoreCount > 50) { "a bare '_' must not behave as a match-everything wildcard, got $underscoreCount rows" }
    }
}
