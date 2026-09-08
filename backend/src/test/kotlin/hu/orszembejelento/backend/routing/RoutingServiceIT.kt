package hu.orszembejelento.backend.routing

import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.routing.application.RoutingService
import hu.orszembejelento.backend.routing.domain.RoutingOutcome
import hu.orszembejelento.backend.routing.domain.UnclassifiedReason
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

/**
 * [RoutingService] against real PostgreSQL - the COMPLETE/PARTIAL inference matrix (ADR
 * 0006/0007), explicit-line validation, and the operational-state checks once a line is
 * resolved. Every fixture here is synthetic, built directly with SQL rather than the
 * canonical dataset tooling, since routing cares about combinations of coverage and
 * relation counts, not the dataset file format.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class RoutingServiceIT : AbstractAuthIntegrationTest() {

    @Autowired
    private lateinit var routingService: RoutingService

    @Autowired
    private lateinit var referenceRepository: JdbcReferenceRepository

    @BeforeEach
    fun resetReferenceTables() {
        jdbc.sql(
            "TRUNCATE reference_dataset_imports, settlement_railway_lines, " +
                "service_area_railway_lines, user_service_areas, settlements, railway_lines, service_areas CASCADE",
        ).update()
    }

    // ------------------------------------------------------------------ helpers

    private fun insertSettlement(ksh: String, active: Boolean = true) =
        jdbc.sql(
            "INSERT INTO settlements (id, ksh_code, name, active, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :ksh, 'Test', :active, now(), now()) RETURNING id",
        ).param("ksh", ksh).param("active", active).query(UUID::class.java).single()

    private fun insertLine(code: String, active: Boolean = true) =
        jdbc.sql(
            "INSERT INTO railway_lines (id, line_code, display_name, active, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :code, 'Line', :active, now(), now()) RETURNING id",
        ).param("code", code).param("active", active).query(UUID::class.java).single()

    private fun insertRelation(settlementId: UUID, lineId: UUID) {
        jdbc.sql("INSERT INTO settlement_railway_lines VALUES (:s, :l)")
            .param("s", settlementId).param("l", lineId).update()
    }

    private fun insertArea(name: String, status: String = "ACTIVE") =
        jdbc.sql(
            "INSERT INTO service_areas (id, name, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :name, :status, now(), now()) RETURNING id",
        ).param("name", name).param("status", status).query(UUID::class.java).single()

    private fun assignLineToArea(lineId: UUID, areaId: UUID) {
        jdbc.sql("INSERT INTO service_area_railway_lines VALUES (:a, :l)")
            .param("a", areaId).param("l", lineId).update()
    }

    /** Directly establishes the current reference state, bypassing the import pipeline. */
    private fun setCurrentState(version: String, relationsCoverage: String) {
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

    // ------------------------------------------------------------ infrastructure

    @Test
    fun `no current dataset yields ReferenceDatasetUnavailable`() {
        val outcome = routingService.route(UUID.randomUUID())
        check(outcome === RoutingOutcome.ReferenceDatasetUnavailable)
    }

    // ------------------------------------------------------------------- COMPLETE

    @Test
    fun `COMPLETE coverage, zero relations, no selection yields NO_VERIFIED_RAILWAY_LINE_REFERENCE`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE)
        check(outcome.referenceDatasetVersion == "v1")
    }

    @Test
    fun `COMPLETE coverage, one relation, no selection is inferred and routed`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        insertRelation(settlement, line)
        val area = insertArea("Area A")
        assignLineToArea(line, area)

        val outcome = routingService.route(settlement) as RoutingOutcome.Routed
        check(outcome.serviceAreaId == area)
        check(outcome.railwayLineId == line)
        check(outcome.referenceDatasetVersion == "v1")
    }

    @Test
    fun `COMPLETE coverage, multiple relations, no selection yields RAILWAY_LINE_NOT_SELECTED`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val lineA = insertLine("1")
        val lineB = insertLine("2")
        insertRelation(settlement, lineA)
        insertRelation(settlement, lineB)

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED)
    }

    // --------------------------------------------------------------------- PARTIAL

    @Test
    fun `PARTIAL coverage, zero active candidates, no selection yields NO_VERIFIED_RAILWAY_LINE_REFERENCE`() {
        // Zero active candidates is a fact about the current reference state ("none on
        // record right now"), not a claim of physical absence - it holds under PARTIAL
        // exactly as it does under COMPLETE. See NO_VERIFIED_RAILWAY_LINE_REFERENCE's KDoc.
        setCurrentState("v1", "PARTIAL")
        val settlement = insertSettlement("00001")

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE)
    }

    @Test
    fun `PARTIAL coverage, zero active candidates but one INACTIVE relation, still yields NO_VERIFIED_RAILWAY_LINE_REFERENCE`() {
        // An inactive relation is never an inference candidate, PARTIAL or COMPLETE alike
        // (the earlier candidate-set fix) - so this must not fall through to
        // RAILWAY_LINE_NOT_SELECTED just because a (non-candidate) relation exists.
        setCurrentState("v1", "PARTIAL")
        val settlement = insertSettlement("00001")
        insertRelation(settlement, insertLine("1", active = false))

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE)
    }

    @Test
    fun `PARTIAL coverage, exactly one active candidate, no selection must NOT be inferred`() {
        setCurrentState("v1", "PARTIAL")
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        insertRelation(settlement, line)
        val area = insertArea("Area A")
        assignLineToArea(line, area)

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED) {
            "a single relation under PARTIAL coverage must never be auto-inferred"
        }
    }

    @Test
    fun `PARTIAL coverage, multiple active candidates, no selection yields RAILWAY_LINE_NOT_SELECTED`() {
        setCurrentState("v1", "PARTIAL")
        val settlement = insertSettlement("00001")
        insertRelation(settlement, insertLine("1"))
        insertRelation(settlement, insertLine("2"))

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED)
    }

    // --------------------------------------------------------------- explicit line

    @Test
    fun `an explicit valid line is routed even under PARTIAL coverage`() {
        setCurrentState("v1", "PARTIAL")
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        insertRelation(settlement, line)
        val area = insertArea("Area A")
        assignLineToArea(line, area)

        val outcome = routingService.route(settlement, line) as RoutingOutcome.Routed
        check(outcome.serviceAreaId == area)
        check(outcome.railwayLineId == line)
    }

    @Test
    fun `an explicit line that exists but has no verified relation is a REFERENCE_MISMATCH`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val unrelatedLine = insertLine("9")

        val outcome = routingService.route(settlement, unrelatedLine) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.REFERENCE_MISMATCH)
    }

    @Test
    fun `an explicit line that does not exist at all is also a REFERENCE_MISMATCH`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")

        val outcome = routingService.route(settlement, UUID.randomUUID()) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.REFERENCE_MISMATCH)
    }

    // -------------------------------------------- inactive-relation candidate semantics

    @Test
    fun `COMPLETE, one ACTIVE plus one INACTIVE relation, no selection infers the ACTIVE line`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val activeLine = insertLine("1")
        val inactiveLine = insertLine("2", active = false)
        insertRelation(settlement, activeLine)
        insertRelation(settlement, inactiveLine)
        val area = insertArea("Area A")
        assignLineToArea(activeLine, area)

        val outcome = routingService.route(settlement) as RoutingOutcome.Routed
        check(outcome.railwayLineId == activeLine) {
            "the inactive relation must not be a candidate; only the active one may be inferred"
        }
        check(outcome.serviceAreaId == area)
    }

    @Test
    fun `COMPLETE, only an INACTIVE relation, no selection has no active candidate and is not inferred`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val line = insertLine("1", active = false)
        insertRelation(settlement, line)

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.NO_VERIFIED_RAILWAY_LINE_REFERENCE) {
            "a settlement whose only verified relation is to an inactive line must behave " +
                "as if it had zero candidates for automatic inference - it must never silently " +
                "resolve a line the public API would never have offered as an option"
        }
    }

    @Test
    fun `an inactive explicitly selected line yields RAILWAY_LINE_INACTIVE, not REFERENCE_MISMATCH`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val line = insertLine("1", active = false)
        insertRelation(settlement, line)

        val outcome = routingService.route(settlement, line) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.RAILWAY_LINE_INACTIVE) {
            "explicit selection of a genuinely-verified line must still surface its inactivity, " +
                "never be reported as if the relation did not exist"
        }
    }

    @Test
    fun `routing infers from exactly the candidate set the public API would list as available`() {
        // Direct parity proof: build a settlement with a mix of active and inactive
        // verified relations, read the same candidate set the public API's line listing
        // reads (JdbcReferenceRepository.findActiveLinesOfSettlement), and confirm
        // RoutingService's no-selection inference agrees with it exactly - not merely a
        // property that happens to hold for one hand-picked line.
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val activeLine = insertLine("1")
        val inactiveLine = insertLine("2", active = false)
        insertRelation(settlement, activeLine)
        insertRelation(settlement, inactiveLine)
        val area = insertArea("Area A")
        assignLineToArea(activeLine, area)

        val publicApiCandidates = referenceRepository.findActiveLinesOfSettlement(settlement)
        check(publicApiCandidates.map { it.id } == listOf(activeLine)) { "sanity check on the fixture" }

        val outcome = routingService.route(settlement) as RoutingOutcome.Routed
        check(outcome.railwayLineId == publicApiCandidates.single().id) {
            "routing inferred a different line than the one the public API would have offered"
        }
    }

    // ------------------------------------------------------ operational-state checks

    @Test
    fun `an active line with no service-area mapping yields RAILWAY_LINE_UNASSIGNED`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        insertRelation(settlement, line)

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.RAILWAY_LINE_UNASSIGNED)
    }

    @Test
    fun `a line mapped to an inactive service area yields SERVICE_AREA_INACTIVE`() {
        setCurrentState("v1", "COMPLETE")
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        insertRelation(settlement, line)
        val area = insertArea("Inactive Area", status = "INACTIVE")
        assignLineToArea(line, area)

        val outcome = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(outcome.reason == UnclassifiedReason.SERVICE_AREA_INACTIVE)
    }

    // -------------------------------------------------------------------- success

    @Test
    fun `a fully valid explicit selection is Routed with the resolved area, line and version`() {
        setCurrentState("routed-version", "COMPLETE")
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        insertRelation(settlement, line)
        val area = insertArea("Area A")
        assignLineToArea(line, area)

        val outcome = routingService.route(settlement, line) as RoutingOutcome.Routed
        check(outcome.serviceAreaId == area)
        check(outcome.railwayLineId == line)
        check(outcome.referenceDatasetVersion == "routed-version")
    }

    @Test
    fun `the reference-state version is carried on every outcome kind`() {
        setCurrentState("carried-version", "COMPLETE")
        val settlement = insertSettlement("00001")

        // Unclassified case.
        val unclassified = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(unclassified.referenceDatasetVersion == "carried-version")

        // Routed case.
        val line = insertLine("1")
        insertRelation(settlement, line)
        assignLineToArea(line, insertArea("Area A"))
        val routed = routingService.route(settlement) as RoutingOutcome.Routed
        check(routed.referenceDatasetVersion == "carried-version")
    }

    // ------------------------------------------------------------- PARTIAL preservation

    @Test
    fun `a relation preserved across a PARTIAL revision remains usable by routing`() {
        // Simulates the outcome of an import pipeline that preserved a relation: the
        // relation row exists in the database (regardless of which import version
        // originally wrote it), and the CURRENT row is what routing consults.
        val settlement = insertSettlement("00001")
        val line = insertLine("1")
        insertRelation(settlement, line)
        val area = insertArea("Area A")
        assignLineToArea(line, area)

        // The dataset "moved on" to a newer PARTIAL revision that no longer restates this
        // relation, but the relation itself was preserved (ADR 0006) and is still present.
        setCurrentState("preserved-under-newer-version", "PARTIAL")

        // No explicit selection: PARTIAL coverage means this must NOT be silently inferred
        // even though the preserved relation is the only one that exists.
        val implicit = routingService.route(settlement) as RoutingOutcome.Unclassified
        check(implicit.reason == UnclassifiedReason.RAILWAY_LINE_NOT_SELECTED)
        check(implicit.referenceDatasetVersion == "preserved-under-newer-version")

        // With an explicit selection, the preserved relation is honoured and resolves
        // normally, carrying the NEW current version even though the fact predates it.
        val explicit = routingService.route(settlement, line) as RoutingOutcome.Routed
        check(explicit.serviceAreaId == area)
        check(explicit.referenceDatasetVersion == "preserved-under-newer-version")
    }
}
