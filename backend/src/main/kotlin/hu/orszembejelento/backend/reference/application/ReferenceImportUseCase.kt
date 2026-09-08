package hu.orszembejelento.backend.reference.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.reference.domain.CanonicalDataset
import hu.orszembejelento.backend.reference.domain.RailwayLine
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetInvalidException
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetNotVerifiedException
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetReuseNotClearedException
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetVersionConflictException
import hu.orszembejelento.backend.reference.domain.ReferenceDiff
import hu.orszembejelento.backend.reference.domain.ReferenceLineInUseException
import hu.orszembejelento.backend.reference.domain.ReuseStatus
import hu.orszembejelento.backend.reference.domain.Settlement
import hu.orszembejelento.backend.reference.domain.VerificationStatus
import hu.orszembejelento.backend.reference.domain.diffReferenceDataset
import hu.orszembejelento.backend.reference.infrastructure.CanonicalDatasetLoader
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

sealed class ReferenceDiffOutcome {
    data class Computed(val dataset: CanonicalDataset, val diff: ReferenceDiff) : ReferenceDiffOutcome()
    data class DatasetInvalid(val issues: List<String>) : ReferenceDiffOutcome()
}

sealed class ReferenceImportOutcome {
    data class Applied(
        val datasetVersion: String,
        val diff: ReferenceDiff,
        val provenanceId: UUID,
    ) : ReferenceImportOutcome()

    /** The exact same dataset version and manifest content was already imported; nothing changed. */
    data class AlreadyImported(val datasetVersion: String) : ReferenceImportOutcome()
}

/**
 * Validates, previews and applies a canonical reference dataset import.
 *
 * Never reachable over HTTP - the maintenance CLI is the only caller, consistent with every
 * other maintenance action in this system (see `MaintenanceCommandRunner`). Importing
 * reference data is exactly the kind of action that should require shell access to the
 * server, not a credential that could be phished or replayed.
 *
 * `validate` and `diff` are read-only inspection tools and run on any dataset, including
 * one that is `UNVERIFIED` or `reuseStatus: PENDING` - that is precisely how a candidate
 * dataset gets reviewed before it is cleared. `import` is the only command that mutates the
 * database, and it is the only one gated: it refuses anything that is not
 * `verificationStatus: VERIFIED` and `reuseStatus: CLEARED`, with no bypass. See ADR 0006.
 */
@Service
class ReferenceImportUseCase(
    private val loader: CanonicalDatasetLoader,
    private val repository: JdbcReferenceRepository,
    private val audit: JdbcAuditRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    /** Pure structural/content validation - no database access at all. */
    fun validate(directory: Path): CanonicalDatasetLoader.LoadResult = loader.load(directory)

    /** What importing [directory] would change against the current database. Never writes. */
    @Transactional(readOnly = true)
    fun diff(directory: Path): ReferenceDiffOutcome {
        return when (val loaded = loader.load(directory)) {
            is CanonicalDatasetLoader.LoadResult.Invalid -> ReferenceDiffOutcome.DatasetInvalid(loaded.issues)
            is CanonicalDatasetLoader.LoadResult.Valid -> {
                val diff = diffReferenceDataset(
                    loaded.dataset,
                    repository.allSettlementKshCodes(),
                    repository.allRailwayLineCodes(),
                    repository.allRelationsByExternalKey(),
                )
                ReferenceDiffOutcome.Computed(loaded.dataset, diff)
            }
        }
    }

    /**
     * Loads, gates and applies [directory] as the new reference dataset.
     *
     * Everything happens in one transaction, behind a PostgreSQL advisory lock that
     * serialises concurrent import attempts for the lifetime of the transaction: any
     * refusal - an unverified dataset, an uncleared reuse status, a version/content
     * conflict, or a line still assigned to a service area - throws before any write is
     * visible, and Spring's default rollback-on-unchecked-exception undoes the rest.
     */
    @Transactional
    fun import(directory: Path): ReferenceImportOutcome {
        val dataset = when (val loaded = loader.load(directory)) {
            is CanonicalDatasetLoader.LoadResult.Invalid -> throw ReferenceDatasetInvalidException(loaded.issues)
            is CanonicalDatasetLoader.LoadResult.Valid -> loaded.dataset
        }

        if (dataset.manifest.verificationStatus != VerificationStatus.VERIFIED) {
            throw ReferenceDatasetNotVerifiedException(dataset.manifest.verificationStatus)
        }
        if (dataset.manifest.reuseStatus != ReuseStatus.CLEARED) {
            throw ReferenceDatasetReuseNotClearedException(dataset.manifest.reuseStatus)
        }

        val manifestHash = loader.manifestSha256(directory)

        // Held for the rest of this transaction: no other import can run concurrently,
        // whether it targets the same dataset version or a different one.
        repository.acquireImportLock()

        val existingImport = repository.findImportByVersion(dataset.manifest.datasetVersion)
        if (existingImport != null) {
            if (existingImport.manifestSha256.contentEquals(manifestHash)) {
                // Same version, byte-identical manifest: re-running the same import is a
                // no-op, not an error. Nothing has been written in this transaction.
                return ReferenceImportOutcome.AlreadyImported(dataset.manifest.datasetVersion)
            }
            throw ReferenceDatasetVersionConflictException(dataset.manifest.datasetVersion)
        }

        val existingSettlements = repository.allSettlementKshCodes()
        val existingLines = repository.allRailwayLineCodes()
        val existingRelations = repository.allRelationsByExternalKey()

        val diff = diffReferenceDataset(dataset, existingSettlements, existingLines, existingRelations)

        // Fail before any write: silently deactivating a line that operational
        // configuration depends on would orphan that configuration (ADR 0005).
        val blockedLines = diff.linesToDeactivate.intersect(repository.lineCodesAssignedToAnArea())
        if (blockedLines.isNotEmpty()) {
            throw ReferenceLineInUseException(blockedLines)
        }

        val now = clock.instant()

        val settlementIdByKsh = existingSettlements.mapValuesTo(HashMap()) { it.value.id }
        for (candidate in diff.settlementsToInsert) {
            val id = UUID.randomUUID()
            repository.insertSettlement(
                Settlement(
                    id = id,
                    kshCode = candidate.kshCode,
                    name = candidate.name,
                    countyCode = null,
                    countyName = candidate.countyName,
                    active = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            settlementIdByKsh[candidate.kshCode.value] = id
        }
        for (candidate in diff.settlementsToUpdate) {
            val id = settlementIdByKsh.getValue(candidate.kshCode.value)
            repository.updateSettlement(
                id = id,
                name = candidate.name,
                countyCode = null,
                countyName = candidate.countyName,
                active = true,
                now = now,
            )
        }
        // Only ever the codes diffReferenceDataset already decided are safe to deactivate -
        // empty whenever settlement coverage is PARTIAL (ADR 0006).
        for (kshCode in diff.settlementsToDeactivate) {
            repository.deactivateSettlement(settlementIdByKsh.getValue(kshCode.value), now)
        }

        val lineIdByCode = existingLines.mapValuesTo(HashMap()) { it.value.id }
        for (candidate in diff.linesToInsert) {
            val id = UUID.randomUUID()
            repository.insertRailwayLine(
                RailwayLine(
                    id = id,
                    lineCode = candidate.lineCode,
                    displayName = candidate.displayName,
                    active = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            lineIdByCode[candidate.lineCode] = id
        }
        for (candidate in diff.linesToUpdate) {
            val id = lineIdByCode.getValue(candidate.lineCode)
            repository.updateRailwayLine(id = id, displayName = candidate.displayName, active = true, now = now)
        }
        // Only ever the codes diffReferenceDataset already decided are safe to deactivate -
        // empty whenever railway-line coverage is PARTIAL. Safe now regardless: the
        // conflict check above already proved none of these are assigned to a service area.
        for (lineCode in diff.linesToDeactivate) {
            repository.deactivateRailwayLine(lineIdByCode.getValue(lineCode), now)
        }

        for (relation in diff.relationsToRemove) {
            val settlementId = settlementIdByKsh[relation.kshCode.value]
            val lineId = lineIdByCode[relation.lineCode]
            if (settlementId != null && lineId != null) {
                repository.deleteRelation(settlementId, lineId)
            }
        }
        for (relation in diff.relationsToAdd) {
            repository.insertRelation(
                settlementIdByKsh.getValue(relation.kshCode.value),
                lineIdByCode.getValue(relation.lineCode),
            )
        }

        val provenanceId = UUID.randomUUID()
        val sourcesJson = objectMapper.readTree(dataset.manifest.rawJson).path("sources").toString()
        repository.insertImportProvenance(
            id = provenanceId,
            datasetVersion = dataset.manifest.datasetVersion,
            manifestSha256 = manifestHash,
            importedAt = now,
            settlementCount = dataset.manifest.settlementCount,
            railwayLineCount = dataset.manifest.railwayLineCount,
            mappingCount = dataset.manifest.mappingCount,
            coverage = dataset.manifest.coverage,
            sourceMetadataJson = sourcesJson,
        )
        // A successful import is the only thing that ever changes which row is current -
        // a failed import throws before this line, so the previous current state (if any)
        // is untouched, and a no-op re-import returns before this line too (see the
        // AlreadyImported branch above).
        repository.promoteToCurrentImport(provenanceId)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.SYSTEM,
            actorUserId = null,
            eventType = AuditEventType.REFERENCE_DATASET_IMPORTED,
            targetType = AuditTargetType.REFERENCE_DATASET,
            targetId = provenanceId,
            metadata = mapOf(
                "source" to "MAINTENANCE_CLI",
                "datasetVersion" to dataset.manifest.datasetVersion,
                "settlements" to dataset.manifest.settlementCount.toString(),
                "railwayLines" to dataset.manifest.railwayLineCount.toString(),
                "mappings" to dataset.manifest.mappingCount.toString(),
            ),
        )

        return ReferenceImportOutcome.Applied(dataset.manifest.datasetVersion, diff, provenanceId)
    }
}
