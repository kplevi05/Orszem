package hu.orszembejelento.backend.reference

import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * The Public reference API over real HTTP (ADR 0007): no authentication, no mutation, and
 * - the point of the explicit serialization test at the bottom - no leakage of anything
 * beyond settlement/railway-line reference facts.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class PublicReferenceApiIT : AbstractAuthIntegrationTest() {

    @BeforeEach
    fun resetReferenceTables() {
        jdbc.sql(
            "TRUNCATE reference_dataset_imports, settlement_railway_lines, " +
                "service_area_railway_lines, user_service_areas, settlements, railway_lines, service_areas CASCADE",
        ).update()
    }

    // ------------------------------------------------------------------ helpers

    private fun insertSettlement(ksh: String, name: String, active: Boolean = true) =
        jdbc.sql(
            "INSERT INTO settlements (id, ksh_code, name, active, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :ksh, :name, :active, now(), now()) RETURNING id",
        ).param("ksh", ksh).param("name", name).param("active", active).query(UUID::class.java).single()

    private fun insertLine(code: String, active: Boolean = true) =
        jdbc.sql(
            "INSERT INTO railway_lines (id, line_code, display_name, active, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :code, 'Line', :active, now(), now()) RETURNING id",
        ).param("code", code).param("active", active).query(UUID::class.java).single()

    private fun insertRelation(settlementId: UUID, lineId: UUID) {
        jdbc.sql("INSERT INTO settlement_railway_lines VALUES (:s, :l)")
            .param("s", settlementId).param("l", lineId).update()
    }

    private fun setCurrentState(version: String = "pub-v1", relationsCoverage: String = "COMPLETE") {
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

    private fun searchPath(query: String) =
        "/api/v1/public/reference/settlements?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)

    // ---------------------------------------------------------------- settlement search

    @Test
    fun `settlement search returns matching active settlements`() {
        setCurrentState()
        insertSettlement("00001", "Alfaváros")

        val response = get(searchPath("Alfa"))
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.isArray && body.size() == 1)
        check(body[0].get("name").asText() == "Alfaváros")
        check(body[0].get("kshCode").asText() == "00001")
        check(body[0].has("id"))
    }

    @Test
    fun `a query shorter than the minimum is rejected`() {
        setCurrentState()
        val response = get(searchPath("A"))
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "VALIDATION_ERROR")
    }

    @Test
    fun `a two-character query is accepted at the boundary`() {
        setCurrentState()
        insertSettlement("00001", "Aba")
        val response = get(searchPath("Ab"))
        check(response.statusCode() == 200) { response.body() }
    }

    @Test
    fun `results are bounded even when many settlements match`() {
        setCurrentState()
        repeat(30) { i -> insertSettlement("%05d".format(i), "Boundtown $i") }

        val response = get(searchPath("Boundtown"))
        check(response.statusCode() == 200)
        val body = json(response)
        check(body.size() <= 20) { "expected a bounded result count, got ${body.size()}" }
    }

    @Test
    fun `an inactive settlement is excluded from search results`() {
        setCurrentState()
        insertSettlement("00001", "Rejtve", active = false)

        val response = get(searchPath("Rejtve"))
        check(response.statusCode() == 200)
        check(json(response).size() == 0) { "an inactive settlement must not be searchable" }
    }

    @Test
    fun `search without a current reference state returns 503, not an empty list`() {
        // No setCurrentState() call: no import has ever happened.
        insertSettlement("00001", "Alfaváros")

        val response = get(searchPath("Alfa"))
        check(response.statusCode() == 503) { response.body() }
        check(errorCode(response) == "REFERENCE_DATASET_UNAVAILABLE")
    }

    // ------------------------------------------------------------ railway-line listing

    @Test
    fun `railway lines for a settlement lists only active, verified lines`() {
        setCurrentState(relationsCoverage = "COMPLETE")
        val settlement = insertSettlement("00001", "Alfaváros")
        val activeLine = insertLine("1")
        val inactiveLine = insertLine("2", active = false)
        insertRelation(settlement, activeLine)
        insertRelation(settlement, inactiveLine)

        val response = get("/api/v1/public/reference/settlements/$settlement/railway-lines")
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.get("coverage").asText() == "COMPLETE")
        val items = body.get("items")
        check(items.size() == 1) { "the inactive line must be excluded: ${response.body()}" }
        check(items[0].get("code").asText() == "1")
    }

    @Test
    fun `the coverage field reflects PARTIAL when relation coverage is PARTIAL`() {
        setCurrentState(relationsCoverage = "PARTIAL")
        val settlement = insertSettlement("00001", "Alfaváros")

        val response = get("/api/v1/public/reference/settlements/$settlement/railway-lines")
        check(response.statusCode() == 200)
        check(json(response).get("coverage").asText() == "PARTIAL")
    }

    @Test
    fun `the coverage field reflects COMPLETE when relation coverage is COMPLETE`() {
        setCurrentState(relationsCoverage = "COMPLETE")
        val settlement = insertSettlement("00001", "Alfaváros")

        val response = get("/api/v1/public/reference/settlements/$settlement/railway-lines")
        check(response.statusCode() == 200)
        check(json(response).get("coverage").asText() == "COMPLETE")
    }

    @Test
    fun `an unknown settlement id returns an empty item list, not an error`() {
        setCurrentState()
        val response = get("/api/v1/public/reference/settlements/${UUID.randomUUID()}/railway-lines")
        check(response.statusCode() == 200)
        check(json(response).get("items").size() == 0)
    }

    @Test
    fun `an invalid uuid path segment is a 400, not a 500`() {
        setCurrentState()
        val response = get("/api/v1/public/reference/settlements/not-a-uuid/railway-lines")
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "VALIDATION_ERROR")
    }

    @Test
    fun `railway-line listing without a current reference state returns 503`() {
        val response = get("/api/v1/public/reference/settlements/${UUID.randomUUID()}/railway-lines")
        check(response.statusCode() == 503) { response.body() }
        check(errorCode(response) == "REFERENCE_DATASET_UNAVAILABLE")
    }

    @Test
    fun `no bearer token is required for either endpoint`() {
        setCurrentState()
        insertSettlement("00001", "Alfaváros")
        // AbstractAuthIntegrationTest's get() never sends a bearer unless told to.
        check(get(searchPath("Alfa")).statusCode() == 200)
        check(get("/api/v1/public/reference/settlements/${UUID.randomUUID()}/railway-lines").statusCode() == 200)
    }

    // ---------------------------------------------------------- serialization leak proof

    @Test
    fun `no service-area, user, moderator, routing, reuseStatus or licence field ever appears in a response`() {
        setCurrentState(relationsCoverage = "COMPLETE")
        val settlement = insertSettlement("00001", "Alfaváros")
        val line = insertLine("1")
        insertRelation(settlement, line)
        val area = jdbc.sql(
            "INSERT INTO service_areas (id, name, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), 'Secret Area', 'ACTIVE', now(), now()) RETURNING id",
        ).query(UUID::class.java).single()
        jdbc.sql("INSERT INTO service_area_railway_lines VALUES (:a, :l)")
            .param("a", area).param("l", line).update()

        val forbidden = listOf(
            "serviceArea", "service_area", "Secret Area", area.toString(),
            "user", "moderator", "role", "routing", "destination",
            "reuseStatus", "licence", "license", "source", "attribution",
        )

        val searchBody = get(searchPath("Alfa")).body()
        val linesBody = get("/api/v1/public/reference/settlements/$settlement/railway-lines").body()

        for (body in listOf(searchBody, linesBody)) {
            for (term in forbidden) {
                check(!body.contains(term, ignoreCase = true)) {
                    "response leaked forbidden term '$term': $body"
                }
            }
        }
    }

    @Test
    fun `the OpenAPI document describes both public reference endpoints and the 503 response`() {
        val response = get("/v3/api-docs")
        check(response.statusCode() == 200)
        val body = response.body()
        check(body.contains("/api/v1/public/reference/settlements")) { "missing settlement search operation" }
        check(body.contains("/railway-lines")) { "missing railway-line listing operation" }
        check(body.contains("503")) { "the 503 REFERENCE_DATASET_UNAVAILABLE response must be documented" }
    }
}
