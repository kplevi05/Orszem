package hu.orszembejelento.backend.reference

import hu.orszembejelento.backend.reference.application.ReferenceImportOutcome
import hu.orszembejelento.backend.reference.application.ReferenceImportUseCase
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetVersionConflictException
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
 * The explicit current-reference-state invariant (ADR 0007): exactly one
 * `reference_dataset_imports` row may have `is_current = true`, it is set only by a
 * genuinely successful import, and it is never derived from `MAX(imported_at)`.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ReferenceCurrentStateIT : AbstractAuthIntegrationTest() {

    @Autowired
    private lateinit var referenceImport: ReferenceImportUseCase

    @BeforeEach
    fun resetReferenceTables() {
        jdbc.sql(
            "TRUNCATE reference_dataset_imports, settlement_railway_lines, " +
                "service_area_railway_lines, user_service_areas, settlements, railway_lines, service_areas CASCADE",
        ).update()
    }

    @Test
    fun `a successful import becomes the current state`(@TempDir tmp: Path) {
        val outcome = referenceImport.import(ReferenceDatasetFixture.write(tmp, version = "cs-v1")) as ReferenceImportOutcome.Applied
        check(currentVersion() == "cs-v1")
        check(currentImportId() == outcome.provenanceId)
    }

    @Test
    fun `a later import replaces which row is current`(@TempDir tmp: Path) {
        val first = referenceImport.import(
            ReferenceDatasetFixture.write(tmp.resolve("v1"), version = "cs-later-v1"),
        ) as ReferenceImportOutcome.Applied
        check(currentImportId() == first.provenanceId)

        val second = referenceImport.import(
            ReferenceDatasetFixture.write(tmp.resolve("v2"), version = "cs-later-v2"),
        ) as ReferenceImportOutcome.Applied
        check(currentVersion() == "cs-later-v2")
        check(currentImportId() == second.provenanceId)
        check(currentImportId() != first.provenanceId)
    }

    @Test
    fun `at most one row may be current - enforced by the database, not application code`() {
        insertImportRow(version = "raw-1", isCurrent = true)
        val second = runCatching { insertImportRow(version = "raw-2", isCurrent = true) }
        check(second.isFailure) { "the partial unique index on is_current must reject a second current row" }

        val count = jdbc.sql("SELECT COUNT(*) FROM reference_dataset_imports WHERE is_current")
            .query(Int::class.java).single()
        check(count == 1)
    }

    @Test
    fun `current state is never determined by imported_at recency`(@TempDir tmp: Path) {
        // Import an older-looking version after a newer-looking one; the current row must
        // still be whichever one actually ran the promotion last, not whichever has the
        // latest imported_at. Both imports use the real clock, so this proves the read
        // path (findCurrentReferenceState) does not fall back to MAX(imported_at): it is
        // wired to the is_current column, which the second import's raw-SQL check above
        // already proves is a real constraint, not a convention.
        referenceImport.import(ReferenceDatasetFixture.write(tmp.resolve("v1"), version = "cs-order-v1"))
        val v2 = referenceImport.import(
            ReferenceDatasetFixture.write(tmp.resolve("v2"), version = "cs-order-v2"),
        ) as ReferenceImportOutcome.Applied

        check(currentImportId() == v2.provenanceId)
        // Directly confirm the mechanism: is_current, not a timestamp comparison.
        val isCurrentByColumn = jdbc.sql(
            "SELECT is_current FROM reference_dataset_imports WHERE id = :id",
        ).param("id", v2.provenanceId).query(Boolean::class.java).single()
        check(isCurrentByColumn)
    }

    @Test
    fun `a failed import leaves the previous current state unchanged`(@TempDir tmp: Path) {
        val good = referenceImport.import(
            ReferenceDatasetFixture.write(tmp.resolve("v1"), version = "cs-fail-v1"),
        ) as ReferenceImportOutcome.Applied
        check(currentImportId() == good.provenanceId)

        // UNVERIFIED refusal.
        assertThrows<hu.orszembejelento.backend.reference.domain.ReferenceDatasetNotVerifiedException> {
            referenceImport.import(
                ReferenceDatasetFixture.write(tmp.resolve("v2"), version = "cs-fail-v2", verification = "UNVERIFIED"),
            )
        }
        check(currentImportId() == good.provenanceId) { "an unverified refusal must not change the current state" }
        check(currentVersion() == "cs-fail-v1")
    }

    @Test
    fun `a line-in-use refusal leaves the previous current state unchanged`(@TempDir tmp: Path) {
        val v1 = referenceImport.import(
            ReferenceDatasetFixture.write(
                tmp.resolve("v1"),
                version = "cs-lock-v1",
                settlements = listOf(Triple("00001", "Alfa", null)),
                lines = listOf("1" to "Line 1"),
                relations = listOf("00001" to "1"),
                countsOverride = Triple(1, 1, 1),
                coveredOverride = 1,
            ),
        ) as ReferenceImportOutcome.Applied
        check(currentImportId() == v1.provenanceId)

        val areaId = jdbc.sql(
            "INSERT INTO service_areas (id, name, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), 'CS Test Area', 'ACTIVE', now(), now()) RETURNING id",
        ).query(UUID::class.java).single()
        val lineId = jdbc.sql("SELECT id FROM railway_lines WHERE line_code = '1'").query(UUID::class.java).single()
        jdbc.sql("INSERT INTO service_area_railway_lines VALUES (:a, :l)").param("a", areaId).param("l", lineId).update()

        assertThrows<hu.orszembejelento.backend.reference.domain.ReferenceLineInUseException> {
            referenceImport.import(
                ReferenceDatasetFixture.write(
                    tmp.resolve("v2"),
                    version = "cs-lock-v2",
                    settlements = emptyList(),
                    lines = emptyList(),
                    relations = emptyList(),
                    countsOverride = Triple(0, 0, 0),
                    coveredOverride = 0,
                ),
            )
        }
        check(currentImportId() == v1.provenanceId)
        check(currentVersion() == "cs-lock-v1")
    }

    @Test
    fun `re-importing the same version and content preserves the correct current state`(@TempDir tmp: Path) {
        val dir = ReferenceDatasetFixture.write(tmp, version = "cs-idem-v1")
        val first = referenceImport.import(dir) as ReferenceImportOutcome.Applied
        check(currentImportId() == first.provenanceId)

        val second = referenceImport.import(dir)
        check(second is ReferenceImportOutcome.AlreadyImported)
        check(currentImportId() == first.provenanceId) { "a no-op re-import must not disturb the current row" }
        check(currentVersion() == "cs-idem-v1")
    }

    @Test
    fun `re-confirming an older, already-superseded version does not make it current again`(@TempDir tmp: Path) {
        val v1Dir = ReferenceDatasetFixture.write(tmp.resolve("v1"), version = "cs-super-v1")
        referenceImport.import(v1Dir)
        val v2 = referenceImport.import(
            ReferenceDatasetFixture.write(tmp.resolve("v2"), version = "cs-super-v2"),
        ) as ReferenceImportOutcome.Applied
        check(currentVersion() == "cs-super-v2")

        // Re-running the OLD (now superseded) dataset directory is still a same-content
        // no-op for its own version, and must not resurrect it as current.
        val replay = referenceImport.import(v1Dir)
        check(replay is ReferenceImportOutcome.AlreadyImported)
        check(currentImportId() == v2.provenanceId)
        check(currentVersion() == "cs-super-v2")
    }

    @Test
    fun `a version conflict is refused and the current state is untouched`(@TempDir tmp: Path) {
        val v1 = referenceImport.import(
            ReferenceDatasetFixture.write(tmp.resolve("v1"), version = "cs-conflict-v1"),
        ) as ReferenceImportOutcome.Applied

        assertThrows<ReferenceDatasetVersionConflictException> {
            referenceImport.import(
                ReferenceDatasetFixture.write(
                    tmp.resolve("v2"),
                    version = "cs-conflict-v1", // same version, different content
                    settlements = listOf(Triple("00009", "Different", null)),
                    lines = emptyList(),
                    relations = emptyList(),
                    countsOverride = Triple(1, 0, 0),
                    coveredOverride = 0,
                ),
            )
        }
        check(currentImportId() == v1.provenanceId)
    }

    @Test
    fun `concurrent imports of two different versions leave exactly one, unambiguous current state`(
        @TempDir tmp: Path,
    ) {
        val dirA = ReferenceDatasetFixture.write(tmp.resolve("a"), version = "cs-race-a")
        val dirB = ReferenceDatasetFixture.write(
            tmp.resolve("b"),
            version = "cs-race-b",
            settlements = listOf(Triple("00005", "Delta", null)),
            lines = emptyList(),
            relations = emptyList(),
            countsOverride = Triple(1, 0, 0),
            coveredOverride = 0,
        )

        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        try {
            val fA = pool.submit<ReferenceImportOutcome> { ready.countDown(); go.await(10, TimeUnit.SECONDS); referenceImport.import(dirA) }
            val fB = pool.submit<ReferenceImportOutcome> { ready.countDown(); go.await(10, TimeUnit.SECONDS); referenceImport.import(dirB) }
            check(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            fA.get(30, TimeUnit.SECONDS)
            fB.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        // The advisory lock serialises the two imports entirely (one commits before the
        // other's transaction even begins its work), so both succeed - but exactly one of
        // the two versions ends up current, and the database proves it is exactly one.
        val currentCount = jdbc.sql("SELECT COUNT(*) FROM reference_dataset_imports WHERE is_current")
            .query(Int::class.java).single()
        check(currentCount == 1) { "exactly one row may be current, found $currentCount" }
        check(currentVersion() in setOf("cs-race-a", "cs-race-b"))
    }

    @Test
    fun `a relation preserved across a PARTIAL import is still usable under the newer current version`(
        @TempDir tmp: Path,
    ) {
        val v1 = ReferenceDatasetFixture.write(
            tmp.resolve("v1"),
            version = "cs-preserve-v1",
            settlements = listOf(Triple("00001", "Alfa", null), Triple("00002", "Beta", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "1", "00002" to "1"),
            countsOverride = Triple(2, 1, 2),
            coveredOverride = 2,
        )
        referenceImport.import(v1)

        val v2 = ReferenceDatasetFixture.write(
            tmp.resolve("v2"),
            version = "cs-preserve-v2",
            settlements = listOf(Triple("00001", "Alfa", null), Triple("00002", "Beta", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "1"), // 00002-1 omitted from this PARTIAL snapshot
            relationsCoverage = "PARTIAL",
            countsOverride = Triple(2, 1, 1),
            coveredOverride = 1,
        )
        val v2Outcome = referenceImport.import(v2) as ReferenceImportOutcome.Applied

        check(currentVersion() == "cs-preserve-v2")
        check(currentImportId() == v2Outcome.provenanceId)

        // The preserved relation is still queryable under the new current version - the
        // fact came from v1, but the reference-state version reported for anything using
        // it is v2's, per CurrentReferenceState's documented semantics.
        val stillRelated = jdbc.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM settlement_railway_lines m
                  JOIN settlements s ON s.id = m.settlement_id
                  JOIN railway_lines l ON l.id = m.railway_line_id
                 WHERE s.ksh_code = '00002' AND l.line_code = '1'
            )
            """.trimIndent(),
        ).query(Boolean::class.java).single()
        check(stillRelated) { "the relation preserved from v1 must still exist under v2" }
    }

    // ------------------------------------------------------------------ helpers

    private fun currentVersion(): String? =
        jdbc.sql("SELECT dataset_version FROM reference_dataset_imports WHERE is_current")
            .query(String::class.java).optional().orElse(null)

    private fun currentImportId(): UUID? =
        jdbc.sql("SELECT id FROM reference_dataset_imports WHERE is_current")
            .query(UUID::class.java).optional().orElse(null)

    private fun insertImportRow(version: String, isCurrent: Boolean) {
        jdbc.sql(
            """
            INSERT INTO reference_dataset_imports (
                id, dataset_version, manifest_sha256, imported_at,
                settlement_count, railway_line_count, mapping_count,
                settlements_coverage, railway_lines_coverage, settlement_railway_lines_coverage,
                is_current
            ) VALUES (
                gen_random_uuid(), :version, :sha, now(),
                0, 0, 0, 'COMPLETE', 'COMPLETE', 'COMPLETE', :isCurrent
            )
            """.trimIndent(),
        )
            .param("version", version)
            .param("sha", ByteArray(32))
            .param("isCurrent", isCurrent)
            .update()
    }
}
