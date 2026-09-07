package hu.orszembejelento.backend.reference

import hu.orszembejelento.backend.reference.domain.CandidateRailwayLine
import hu.orszembejelento.backend.reference.domain.CandidateRelation
import hu.orszembejelento.backend.reference.domain.CandidateSettlement
import hu.orszembejelento.backend.reference.domain.CanonicalDataset
import hu.orszembejelento.backend.reference.domain.CoverageStatus
import hu.orszembejelento.backend.reference.domain.DatasetManifest
import hu.orszembejelento.backend.reference.domain.ExistingReferenceRow
import hu.orszembejelento.backend.reference.domain.KshCode
import hu.orszembejelento.backend.reference.domain.ReuseStatus
import hu.orszembejelento.backend.reference.domain.VerificationStatus
import hu.orszembejelento.backend.reference.domain.diffReferenceDataset
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * [diffReferenceDataset] is pure and framework-free, so it is tested directly with plain
 * JVM data - no database, no Spring context. The PostgreSQL-backed behaviour it feeds into
 * (upserts, deactivation, the line-in-use refusal) is covered by `ReferenceImportUseCaseIT`.
 */
class ReferenceDiffTest {

    private val ksh1 = KshCode.parseOrNull("00001")!!
    private val ksh2 = KshCode.parseOrNull("00002")!!
    private val ksh3 = KshCode.parseOrNull("00003")!!

    private fun dataset(
        settlements: List<CandidateSettlement>,
        lines: List<CandidateRailwayLine>,
        relations: List<CandidateRelation>,
    ) = CanonicalDataset(
        manifest = DatasetManifest(
            datasetVersion = "test",
            verificationStatus = VerificationStatus.VERIFIED,
            coverageStatus = CoverageStatus.PARTIAL,
            reuseStatus = ReuseStatus.CLEARED,
            canonicalFileChecksums = emptyMap(),
            settlementCount = settlements.size,
            railwayLineCount = lines.size,
            mappingCount = relations.size,
            settlementsWithVerifiedRelations = relations.map { it.kshCode.value }.toSet().size,
            rawJson = "{}",
        ),
        settlements = settlements,
        railwayLines = lines,
        relations = relations,
    )

    @Test
    fun `a settlement absent from the current database is an insert`() {
        val diff = diffReferenceDataset(
            dataset(listOf(CandidateSettlement(ksh1, "Alfa", null)), emptyList(), emptyList()),
            existingSettlements = emptyMap(),
            existingRailwayLines = emptyMap(),
            existingRelations = emptySet(),
        )
        check(diff.settlementsToInsert.map { it.kshCode } == listOf(ksh1))
        check(diff.settlementsToUpdate.isEmpty())
        check(diff.settlementsToDeactivate.isEmpty())
    }

    @Test
    fun `a settlement already active and still present is an update, not a reactivation`() {
        val existingId = UUID.randomUUID()
        val diff = diffReferenceDataset(
            dataset(listOf(CandidateSettlement(ksh1, "Alfa renamed", null)), emptyList(), emptyList()),
            existingSettlements = mapOf("00001" to ExistingReferenceRow(existingId, active = true)),
            existingRailwayLines = emptyMap(),
            existingRelations = emptySet(),
        )
        check(diff.settlementsToUpdate.map { it.kshCode } == listOf(ksh1))
        check(diff.settlementsToInsert.isEmpty())
        check(diff.settlementsToReactivate.isEmpty())
    }

    @Test
    fun `a settlement that was inactive and reappears is a reactivation`() {
        val diff = diffReferenceDataset(
            dataset(listOf(CandidateSettlement(ksh1, "Alfa", null)), emptyList(), emptyList()),
            existingSettlements = mapOf("00001" to ExistingReferenceRow(UUID.randomUUID(), active = false)),
            existingRailwayLines = emptyMap(),
            existingRelations = emptySet(),
        )
        check(diff.settlementsToReactivate == setOf(ksh1))
        // Still an update, not an insert - the row already exists and keeps its UUID.
        check(diff.settlementsToUpdate.map { it.kshCode } == listOf(ksh1))
        check(diff.settlementsToInsert.isEmpty())
    }

    @Test
    fun `an active settlement absent from the new dataset is deactivated`() {
        val diff = diffReferenceDataset(
            dataset(emptyList(), emptyList(), emptyList()),
            existingSettlements = mapOf("00001" to ExistingReferenceRow(UUID.randomUUID(), active = true)),
            existingRailwayLines = emptyMap(),
            existingRelations = emptySet(),
        )
        check(diff.settlementsToDeactivate == setOf(ksh1))
    }

    @Test
    fun `an already-inactive settlement absent from the new dataset is not re-reported as a deactivation`() {
        val diff = diffReferenceDataset(
            dataset(emptyList(), emptyList(), emptyList()),
            existingSettlements = mapOf("00001" to ExistingReferenceRow(UUID.randomUUID(), active = false)),
            existingRailwayLines = emptyMap(),
            existingRelations = emptySet(),
        )
        check(diff.settlementsToDeactivate.isEmpty()) {
            "an already-inactive row deactivating again is a no-op, not a reported change"
        }
    }

    @Test
    fun `railway lines follow the identical insert-update-reactivate-deactivate rules`() {
        val line = CandidateRailwayLine("1", "Alfa - Beta")
        val diff = diffReferenceDataset(
            dataset(emptyList(), listOf(line), emptyList()),
            existingSettlements = emptyMap(),
            existingRailwayLines = mapOf("1" to ExistingReferenceRow(UUID.randomUUID(), active = false)),
            existingRelations = emptySet(),
        )
        check(diff.linesToReactivate == setOf("1"))
        check(diff.linesToInsert.isEmpty())
    }

    @Test
    fun `relations are added and removed by exact external key, unrelated pairs are untouched`() {
        val diff = diffReferenceDataset(
            dataset(
                listOf(CandidateSettlement(ksh1, "Alfa", null), CandidateSettlement(ksh2, "Beta", null)),
                listOf(CandidateRailwayLine("1", "Line 1")),
                relations = listOf(CandidateRelation(ksh1, "1")), // ksh2-line1 dropped, ksh1-line1 kept
            ),
            existingSettlements = mapOf(
                "00001" to ExistingReferenceRow(UUID.randomUUID(), active = true),
                "00002" to ExistingReferenceRow(UUID.randomUUID(), active = true),
            ),
            existingRailwayLines = mapOf("1" to ExistingReferenceRow(UUID.randomUUID(), active = true)),
            existingRelations = setOf("00001" to "1", "00002" to "1"),
        )
        check(diff.relationsToAdd.isEmpty()) { "the surviving pair must not be re-added" }
        check(diff.relationsToRemove == setOf(CandidateRelation(ksh2, "1")))
    }

    @Test
    fun `a genuinely new relation between two already-known rows is an add`() {
        val diff = diffReferenceDataset(
            dataset(
                listOf(CandidateSettlement(ksh3, "Gamma", null)),
                listOf(CandidateRailwayLine("2", "Line 2")),
                relations = listOf(CandidateRelation(ksh3, "2")),
            ),
            existingSettlements = mapOf("00003" to ExistingReferenceRow(UUID.randomUUID(), active = true)),
            existingRailwayLines = mapOf("2" to ExistingReferenceRow(UUID.randomUUID(), active = true)),
            existingRelations = emptySet(),
        )
        check(diff.relationsToAdd == setOf(CandidateRelation(ksh3, "2")))
        check(diff.relationsToRemove.isEmpty())
    }

    @Test
    fun `an unchanged dataset against identical existing state is an empty diff`() {
        val diff = diffReferenceDataset(
            dataset(
                listOf(CandidateSettlement(ksh1, "Alfa", null)),
                listOf(CandidateRailwayLine("1", "Line 1")),
                relations = listOf(CandidateRelation(ksh1, "1")),
            ),
            existingSettlements = mapOf("00001" to ExistingReferenceRow(UUID.randomUUID(), active = true)),
            existingRailwayLines = mapOf("1" to ExistingReferenceRow(UUID.randomUUID(), active = true)),
            existingRelations = setOf("00001" to "1"),
        )
        // Not literally isEmpty (settlementsToUpdate/linesToUpdate list the unchanged rows,
        // which is what makes re-applying them idempotent) but nothing structural changes.
        check(diff.settlementsToInsert.isEmpty() && diff.settlementsToDeactivate.isEmpty())
        check(diff.linesToInsert.isEmpty() && diff.linesToDeactivate.isEmpty())
        check(diff.relationsToAdd.isEmpty() && diff.relationsToRemove.isEmpty())
        check(diff.isEmpty)
    }
}
