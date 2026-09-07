package hu.orszembejelento.backend.reference

import hu.orszembejelento.backend.reference.application.ReferenceDiffOutcome
import hu.orszembejelento.backend.reference.application.ReferenceImportOutcome
import hu.orszembejelento.backend.reference.application.ReferenceImportUseCase
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetNotVerifiedException
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetReuseNotClearedException
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetVersionConflictException
import hu.orszembejelento.backend.reference.domain.ReferenceLineInUseException
import hu.orszembejelento.backend.reference.infrastructure.CanonicalDatasetLoader
import hu.orszembejelento.backend.reference.support.ReferenceDatasetFixture
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

/**
 * The backend reference-dataset importer, against real PostgreSQL.
 *
 * Every test here uses small synthetic fixtures via [ReferenceDatasetFixture]. The real
 * VPE-derived dataset under `reference-data/local-research/` is never imported anywhere, including
 * here: it is `reuseStatus: PENDING`, and the whole point of the gate under test is that a
 * PENDING dataset cannot be imported. See ADR 0006.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ReferenceImportUseCaseIT : AbstractAuthIntegrationTest() {

    @Autowired
    private lateinit var referenceImport: ReferenceImportUseCase

    @BeforeEach
    fun resetReferenceTables() {
        jdbc.sql(
            "TRUNCATE reference_dataset_imports, settlement_railway_lines, " +
                "service_area_railway_lines, user_service_areas, settlements, railway_lines, service_areas CASCADE",
        ).update()
    }

    // ------------------------------------------------------------------- gates

    @Test
    fun `refuses to import a dataset that is not VERIFIED`(@TempDir tmp: Path) {
        val dir = ReferenceDatasetFixture.write(tmp, version = "v1", verification = "UNVERIFIED")

        assertThrows<ReferenceDatasetNotVerifiedException> { referenceImport.import(dir) }

        check(settlementCount() == 0) { "nothing may be written when the gate refuses the dataset" }
    }

    @Test
    fun `refuses to import a dataset whose reuseStatus is PENDING, with no bypass`(@TempDir tmp: Path) {
        val dir = ReferenceDatasetFixture.write(tmp, version = "v1", reuse = "PENDING")

        val ex = assertThrows<ReferenceDatasetReuseNotClearedException> { referenceImport.import(dir) }
        check(ex.code == "REFERENCE_DATASET_REUSE_NOT_CLEARED")
        check(settlementCount() == 0)
    }

    @Test
    fun `validate and diff both work on a PENDING or UNVERIFIED dataset - only import is gated`(@TempDir tmp: Path) {
        val dir = ReferenceDatasetFixture.write(tmp, version = "v1", verification = "UNVERIFIED", reuse = "PENDING")

        val validated = referenceImport.validate(dir)
        check(validated is CanonicalDatasetLoader.LoadResult.Valid) { "structurally valid regardless of the gates" }

        val diffed = referenceImport.diff(dir)
        check(diffed is ReferenceDiffOutcome.Computed)
        check((diffed as ReferenceDiffOutcome.Computed).diff.settlementsToInsert.size == 3)
        check(settlementCount() == 0) { "diff must never write" }
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `a first import inserts every row, records provenance and audits the operation`(@TempDir tmp: Path) {
        val dir = ReferenceDatasetFixture.write(tmp, version = "v1")

        val outcome = referenceImport.import(dir) as ReferenceImportOutcome.Applied
        check(outcome.datasetVersion == "v1")
        check(outcome.diff.settlementsToInsert.size == 3)
        check(outcome.diff.linesToInsert.size == 2)
        check(outcome.diff.relationsToAdd.size == 4)

        check(settlementCount() == 3)
        check(lineCount() == 2)
        check(relationCount() == 4)

        val provenance = jdbc.sql(
            "SELECT dataset_version, settlement_count, railway_line_count, mapping_count " +
                "FROM reference_dataset_imports WHERE id = :id",
        ).param("id", outcome.provenanceId).query { rs, _ ->
            Triple(rs.getString("dataset_version"), rs.getInt("settlement_count"), rs.getInt("railway_line_count"))
        }.single()
        check(provenance.first == "v1")
        check(provenance.second == 3)
        check(provenance.third == 2)

        val auditRow = jdbc.sql(
            "SELECT actor_type, actor_user_id, target_type, target_id, metadata::text AS metadata " +
                "FROM audit_events WHERE event_type = 'REFERENCE_DATASET_IMPORTED'",
        ).query { rs, _ ->
            Triple(rs.getString("actor_type"), rs.getObject("actor_user_id"), rs.getObject("target_id", UUID::class.java))
        }.single()
        check(auditRow.first == "SYSTEM")
        check(auditRow.second == null) { "a system actor must not claim a user" }
        check(auditRow.third == outcome.provenanceId)
    }

    // -------------------------------------------------------------- idempotency

    @Test
    fun `re-importing the identical dataset version is a no-op, not an error`(@TempDir tmp: Path) {
        val dir = ReferenceDatasetFixture.write(tmp, version = "v1")

        val first = referenceImport.import(dir) as ReferenceImportOutcome.Applied
        val second = referenceImport.import(dir)

        check(second is ReferenceImportOutcome.AlreadyImported)
        check((second as ReferenceImportOutcome.AlreadyImported).datasetVersion == "v1")

        check(settlementCount() == 3) { "no duplicated rows" }
        val provenanceRows = jdbc.sql("SELECT COUNT(*) FROM reference_dataset_imports WHERE dataset_version = 'v1'")
            .query(Int::class.java).single()
        check(provenanceRows == 1) { "must not write a second provenance row for the same content" }
        val auditRows = jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'REFERENCE_DATASET_IMPORTED'")
            .query(Int::class.java).single()
        check(auditRows == 1) { "a no-op re-import must not audit a second import" }
        check(first.provenanceId != UUID.fromString("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `the same version with different content is a conflict, and nothing is written`(@TempDir tmp: Path) {
        val dirA = ReferenceDatasetFixture.write(tmp.resolve("a"), version = "conflict-1")
        val dirB = ReferenceDatasetFixture.write(
            tmp.resolve("b"),
            version = "conflict-1", // same version...
            settlements = listOf(Triple("00009", "Different", null)), // ...different content
            lines = emptyList(),
            relations = emptyList(),
            countsOverride = Triple(1, 0, 0),
            coveredOverride = 0,
        )

        referenceImport.import(dirA)
        val before = settlementCount()

        val ex = assertThrows<ReferenceDatasetVersionConflictException> { referenceImport.import(dirB) }
        check(ex.code == "REFERENCE_DATASET_VERSION_CONFLICT")
        check(ex.datasetVersion == "conflict-1")

        check(settlementCount() == before) { "the conflicting import must not change anything" }
        check(settlementByKsh("00009") == null) { "content from the refused import must not leak in" }
    }

    // ------------------------------------------------------ UUID preservation

    @Test
    fun `a settlement keeps its UUID across versions, deactivation and reactivation`(@TempDir tmp: Path) {
        val v1 = ReferenceDatasetFixture.write(
            tmp.resolve("v1"),
            version = "uuid-v1",
            settlements = listOf(Triple("00001", "Alfa", "Megye A")),
            lines = emptyList(),
            relations = emptyList(),
            countsOverride = Triple(1, 0, 0),
            coveredOverride = 0,
        )
        referenceImport.import(v1)
        val originalId = settlementByKsh("00001")!!

        // v2: renamed, still present -> update, same UUID.
        val v2 = ReferenceDatasetFixture.write(
            tmp.resolve("v2"),
            version = "uuid-v2",
            settlements = listOf(Triple("00001", "Alfa Updated", "Megye A")),
            lines = emptyList(),
            relations = emptyList(),
            countsOverride = Triple(1, 0, 0),
            coveredOverride = 0,
        )
        referenceImport.import(v2)
        check(settlementByKsh("00001") == originalId)
        check(settlementName("00001") == "Alfa Updated")
        check(settlementActive("00001"))

        // v3: absent -> deactivated, same UUID retained (never deleted).
        val v3 = ReferenceDatasetFixture.write(
            tmp.resolve("v3"),
            version = "uuid-v3",
            settlements = emptyList(),
            lines = emptyList(),
            relations = emptyList(),
            countsOverride = Triple(0, 0, 0),
            coveredOverride = 0,
        )
        referenceImport.import(v3)
        check(settlementByKsh("00001") == originalId) { "a deactivated row keeps its identity" }
        check(!settlementActive("00001"))

        // v4: reappears -> reactivated, still the same UUID.
        referenceImport.import(
            ReferenceDatasetFixture.write(
                tmp.resolve("v4"),
                version = "uuid-v4",
                settlements = listOf(Triple("00001", "Alfa Updated", "Megye A")),
                lines = emptyList(),
                relations = emptyList(),
                countsOverride = Triple(1, 0, 0),
                coveredOverride = 0,
            ),
        )
        check(settlementByKsh("00001") == originalId)
        check(settlementActive("00001"))
    }

    // -------------------------------------------------- ADR 0006: per-component coverage

    @Test
    fun `PARTIAL relation coverage preserves a relation missing from a later snapshot`(@TempDir tmp: Path) {
        val v1 = ReferenceDatasetFixture.write(
            tmp.resolve("v1"),
            version = "cov-rel-v1",
            settlements = listOf(Triple("00001", "Alfa", null), Triple("00002", "Beta", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "1", "00002" to "1"), // A and B
            countsOverride = Triple(2, 1, 2),
            coveredOverride = 2,
        )
        referenceImport.import(v1)
        check(relationCount() == 2)

        val v2 = ReferenceDatasetFixture.write(
            tmp.resolve("v2"),
            version = "cov-rel-v2",
            settlements = listOf(Triple("00001", "Alfa", null), Triple("00002", "Beta", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "1"), // only A - B omitted
            relationsCoverage = "PARTIAL",
            countsOverride = Triple(2, 1, 1),
            coveredOverride = 1,
        )
        val outcome = referenceImport.import(v2) as ReferenceImportOutcome.Applied
        check(outcome.diff.relationsToRemove.isEmpty())
        check(outcome.diff.relationsPreservedDespiteAbsence.size == 1)

        check(relationCount() == 2) { "B must survive: PARTIAL relation coverage is not evidence it no longer exists" }
        check(relationExists("00001", "1") && relationExists("00002", "1"))
    }

    @Test
    fun `COMPLETE relation coverage removes a relation missing from a later snapshot`(@TempDir tmp: Path) {
        val v1 = ReferenceDatasetFixture.write(
            tmp.resolve("v1"),
            version = "cov-rel-c-v1",
            settlements = listOf(Triple("00001", "Alfa", null), Triple("00002", "Beta", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "1", "00002" to "1"),
            countsOverride = Triple(2, 1, 2),
            coveredOverride = 2,
        )
        referenceImport.import(v1)

        val v2 = ReferenceDatasetFixture.write(
            tmp.resolve("v2"),
            version = "cov-rel-c-v2",
            settlements = listOf(Triple("00001", "Alfa", null), Triple("00002", "Beta", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "1"),
            relationsCoverage = "COMPLETE",
            countsOverride = Triple(2, 1, 1),
            coveredOverride = 1,
        )
        referenceImport.import(v2)

        check(relationCount() == 1) { "COMPLETE relation coverage may remove what a newer snapshot omits" }
        check(relationExists("00001", "1") && !relationExists("00002", "1"))
    }

    @Test
    fun `PARTIAL railway-line coverage cannot deactivate an omitted existing line`(@TempDir tmp: Path) {
        val v1 = ReferenceDatasetFixture.write(
            tmp.resolve("v1"),
            version = "cov-line-p-v1",
            settlements = emptyList(),
            lines = listOf("1" to "Line 1"),
            relations = emptyList(),
            countsOverride = Triple(0, 1, 0),
            coveredOverride = 0,
        )
        referenceImport.import(v1)

        val v2 = ReferenceDatasetFixture.write(
            tmp.resolve("v2"),
            version = "cov-line-p-v2",
            settlements = emptyList(),
            lines = emptyList(), // line "1" omitted
            relations = emptyList(),
            railwayLinesCoverage = "PARTIAL",
            countsOverride = Triple(0, 0, 0),
            coveredOverride = 0,
        )
        val outcome = referenceImport.import(v2) as ReferenceImportOutcome.Applied
        check(outcome.diff.linesToDeactivate.isEmpty())
        check(outcome.diff.linesPreservedDespiteAbsence == setOf("1"))

        val active = jdbc.sql("SELECT active FROM railway_lines WHERE line_code = '1'").query(Boolean::class.java).single()
        check(active) { "PARTIAL railway-line coverage is not evidence the line no longer exists" }
    }

    @Test
    fun `COMPLETE railway-line coverage deactivates an omitted, unassigned line`(@TempDir tmp: Path) {
        val v1 = ReferenceDatasetFixture.write(
            tmp.resolve("v1"),
            version = "cov-line-c-v1",
            settlements = emptyList(),
            lines = listOf("1" to "Line 1"),
            relations = emptyList(),
            countsOverride = Triple(0, 1, 0),
            coveredOverride = 0,
        )
        referenceImport.import(v1)

        val v2 = ReferenceDatasetFixture.write(
            tmp.resolve("v2"),
            version = "cov-line-c-v2",
            settlements = emptyList(),
            lines = emptyList(),
            relations = emptyList(),
            railwayLinesCoverage = "COMPLETE",
            countsOverride = Triple(0, 0, 0),
            coveredOverride = 0,
        )
        referenceImport.import(v2)

        val active = jdbc.sql("SELECT active FROM railway_lines WHERE line_code = '1'").query(Boolean::class.java).single()
        check(!active) { "COMPLETE railway-line coverage, with nothing depending on the line, may deactivate it" }
    }

    // -------------------------------------------------------------- line in use

    @Test
    fun `refuses to deactivate a line assigned to a service area, and rolls back the whole import`(
        @TempDir tmp: Path,
    ) {
        val v1 = ReferenceDatasetFixture.write(
            tmp.resolve("v1"),
            version = "lock-v1",
            settlements = listOf(Triple("00001", "Alfa", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "1"),
            countsOverride = Triple(1, 1, 1),
            coveredOverride = 1,
        )
        referenceImport.import(v1)

        val areaId = jdbc.sql(
            "INSERT INTO service_areas (id, name, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), 'Test Area', 'ACTIVE', now(), now()) RETURNING id",
        ).query(UUID::class.java).single()
        val lineId = jdbc.sql("SELECT id FROM railway_lines WHERE line_code = '1'").query(UUID::class.java).single()
        jdbc.sql("INSERT INTO service_area_railway_lines VALUES (:a, :l)")
            .param("a", areaId).param("l", lineId).update()

        // v2 drops line "1" entirely (would deactivate it) and also introduces a brand-new
        // settlement, to prove the whole transaction rolls back, not just the line update.
        val v2 = ReferenceDatasetFixture.write(
            tmp.resolve("v2"),
            version = "lock-v2",
            settlements = listOf(Triple("00001", "Alfa", null), Triple("00099", "NewOne", null)),
            lines = emptyList(),
            relations = emptyList(),
            countsOverride = Triple(2, 0, 0),
            coveredOverride = 0,
        )

        val ex = assertThrows<ReferenceLineInUseException> { referenceImport.import(v2) }
        check(ex.code == "REFERENCE_LINE_IN_USE")
        check(ex.lineCodes == setOf("1"))

        // Full rollback: the new settlement from the refused import must not exist...
        check(settlementByKsh("00099") == null)
        // ...and the line under contention must be untouched.
        check(settlementActive("00001"))
        val lineStillActive = jdbc.sql("SELECT active FROM railway_lines WHERE line_code = '1'")
            .query(Boolean::class.java).single()
        check(lineStillActive)
        check(relationCount() == 1)
        val provenanceRows = jdbc.sql("SELECT COUNT(*) FROM reference_dataset_imports WHERE dataset_version = 'lock-v2'")
            .query(Int::class.java).single()
        check(provenanceRows == 0)
    }

    // ------------------------------------------------------------- concurrency

    @Test
    fun `two concurrent imports of the same new version are serialised - exactly one applies`(@TempDir tmp: Path) {
        val dir = ReferenceDatasetFixture.write(tmp, version = "race-v1")

        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        try {
            val futures = (1..2).map {
                pool.submit<ReferenceImportOutcome> {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    referenceImport.import(dir)
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }

            val applied = outcomes.count { it is ReferenceImportOutcome.Applied }
            val alreadyImported = outcomes.count { it is ReferenceImportOutcome.AlreadyImported }
            check(applied == 1) { "exactly one concurrent attempt may perform the import, got $applied" }
            check(alreadyImported == 1) { "the other must observe it as already done, got $alreadyImported" }
        } finally {
            pool.shutdownNow()
        }

        check(settlementCount() == 3) { "no duplicated writes from the race" }
        val provenanceRows = jdbc.sql("SELECT COUNT(*) FROM reference_dataset_imports WHERE dataset_version = 'race-v1'")
            .query(Int::class.java).single()
        check(provenanceRows == 1)
    }

    // ------------------------------------------------------------------ helpers

    private fun settlementCount(): Int = jdbc.sql("SELECT COUNT(*) FROM settlements").query(Int::class.java).single()
    private fun lineCount(): Int = jdbc.sql("SELECT COUNT(*) FROM railway_lines").query(Int::class.java).single()
    private fun relationCount(): Int =
        jdbc.sql("SELECT COUNT(*) FROM settlement_railway_lines").query(Int::class.java).single()

    private fun settlementByKsh(ksh: String): UUID? =
        jdbc.sql("SELECT id FROM settlements WHERE ksh_code = :ksh").param("ksh", ksh)
            .query(UUID::class.java).optional().orElse(null)

    private fun settlementName(ksh: String): String =
        jdbc.sql("SELECT name FROM settlements WHERE ksh_code = :ksh").param("ksh", ksh)
            .query(String::class.java).single()

    private fun settlementActive(ksh: String): Boolean =
        jdbc.sql("SELECT active FROM settlements WHERE ksh_code = :ksh").param("ksh", ksh)
            .query(Boolean::class.java).single()

    private fun relationExists(ksh: String, lineCode: String): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM settlement_railway_lines m
                  JOIN settlements s ON s.id = m.settlement_id
                  JOIN railway_lines l ON l.id = m.railway_line_id
                 WHERE s.ksh_code = :ksh AND l.line_code = :line
            )
            """.trimIndent(),
        ).param("ksh", ksh).param("line", lineCode).query(Boolean::class.java).single()
}
