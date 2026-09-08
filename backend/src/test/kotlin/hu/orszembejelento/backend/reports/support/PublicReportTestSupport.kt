package hu.orszembejelento.backend.reports.support

import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.BeforeEach

/**
 * Shared fixtures for the Phase 4 Public reporting backend test battery (ADR 0008).
 *
 * Every reference/service-area table is truncated before each test - report submission
 * behaviour depends heavily on exact reference/routing state, so tests build it from
 * scratch rather than relying on unique keys the way [hu.orszembejelento.backend.reference.ReferenceSchemaIT]
 * does. `report_categories`/`report_event_types` are deliberately **not** truncated: they
 * are seeded, versioned taxonomy content (V003), shared read-only fixture data every test
 * class here relies on - see [EventCatalogSchemaIT][hu.orszembejelento.backend.reports.EventCatalogSchemaIT]
 * for the migration-level guarantees about their exact content.
 */
abstract class PublicReportTestSupport : AbstractAuthIntegrationTest() {

    @BeforeEach
    fun resetReportAndReferenceTables() {
        jdbc.sql(
            "TRUNCATE report_routing_snapshots, reports, reference_dataset_imports, " +
                "settlement_railway_lines, service_area_railway_lines, user_service_areas, " +
                "settlements, railway_lines, service_areas CASCADE",
        ).update()
    }

    // ------------------------------------------------------------ reference fixtures

    protected fun insertSettlement(ksh: String, name: String = "Alfaváros", active: Boolean = true): UUID =
        jdbc.sql(
            "INSERT INTO settlements (id, ksh_code, name, active, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :ksh, :name, :active, now(), now()) RETURNING id",
        ).param("ksh", ksh).param("name", name).param("active", active).query(UUID::class.java).single()

    protected fun insertLine(code: String, active: Boolean = true): UUID =
        jdbc.sql(
            "INSERT INTO railway_lines (id, line_code, display_name, active, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :code, 'Line', :active, now(), now()) RETURNING id",
        ).param("code", code).param("active", active).query(UUID::class.java).single()

    protected fun insertRelation(settlementId: UUID, lineId: UUID) {
        jdbc.sql("INSERT INTO settlement_railway_lines VALUES (:s, :l)")
            .param("s", settlementId).param("l", lineId).update()
    }

    protected fun insertArea(name: String = "Terulet", status: String = "ACTIVE"): UUID =
        jdbc.sql(
            "INSERT INTO service_areas (id, name, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :name, :status, now(), now()) RETURNING id",
        ).param("name", name).param("status", status).query(UUID::class.java).single()

    protected fun assignLineToArea(areaId: UUID, lineId: UUID) {
        jdbc.sql("INSERT INTO service_area_railway_lines VALUES (:a, :l)")
            .param("a", areaId).param("l", lineId).update()
    }

    protected fun setCurrentReferenceState(version: String = "report-v1", relationsCoverage: String = "COMPLETE") {
        jdbc.sql(
            """
            INSERT INTO reference_dataset_imports (
                id, dataset_version, manifest_sha256, imported_at,
                settlement_count, railway_line_count, mapping_count,
                settlements_coverage, railway_lines_coverage, settlement_railway_lines_coverage,
                is_current
            ) VALUES (
                gen_random_uuid(), :version, :sha, now(), 0, 0, 0, 'COMPLETE', 'COMPLETE', :coverage, TRUE
            )
            """.trimIndent(),
        ).param("version", version).param("sha", ByteArray(32)).param("coverage", relationsCoverage).update()
    }

    // ------------------------------------------------------------- credentials

    private val secureRandom = SecureRandom()

    /** A syntactically valid, freshly-random `pr_<43 url-safe base64 chars>` credential. */
    protected fun randomCredential(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "pr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // -------------------------------------------------------------- submission

    protected fun submitReportBody(
        clientSubmissionId: UUID = UUID.randomUUID(),
        occurredAt: Instant = clock.instant(),
        trainIdentifier: String? = null,
        settlementId: UUID,
        railwayLineId: UUID? = null,
        eventTypeCode: String = "FIGHT",
    ): String {
        val trainIdentifierJson = trainIdentifier?.let { "\"" + it.replace("\"", "\\\"") + "\"" } ?: "null"
        val railwayLineJson = railwayLineId?.let { "\"$it\"" } ?: "null"
        return """
            {
              "clientSubmissionId": "$clientSubmissionId",
              "occurredAt": "$occurredAt",
              "trainIdentifier": $trainIdentifierJson,
              "settlementId": "$settlementId",
              "railwayLineId": $railwayLineJson,
              "eventTypeCode": "$eventTypeCode"
            }
        """.trimIndent()
    }

    protected fun submitReport(body: String, credential: String? = randomCredential()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/public/reports"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        credential?.let { builder.header(REPORT_ACCESS_HEADER, it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun getPublicReport(publicReportId: UUID, credential: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/public/reports/$publicReportId")).GET()
        credential?.let { builder.header(REPORT_ACCESS_HEADER, it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** Creates one report end-to-end and returns its public id and the credential that unlocks it. */
    protected fun givenCreatedReport(
        settlementId: UUID,
        railwayLineId: UUID? = null,
        eventTypeCode: String = "FIGHT",
        credential: String = randomCredential(),
    ): CreatedReport {
        val response = submitReport(
            submitReportBody(settlementId = settlementId, railwayLineId = railwayLineId, eventTypeCode = eventTypeCode),
            credential,
        )
        check(response.statusCode() == 201) { "fixture report creation failed: ${response.statusCode()} ${response.body()}" }
        val publicId = UUID.fromString(json(response).get("reportId").asText())
        return CreatedReport(publicId, credential)
    }

    protected data class CreatedReport(val publicId: UUID, val credential: String)

    protected companion object {
        const val REPORT_ACCESS_HEADER = "X-Orszem-Report-Access"
    }
}
