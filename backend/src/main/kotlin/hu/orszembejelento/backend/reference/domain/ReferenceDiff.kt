package hu.orszembejelento.backend.reference.domain

import java.util.UUID

/** The current state of one external key already in the database, as far as the diff needs it. */
data class ExistingReferenceRow(val id: UUID, val active: Boolean)

/**
 * What importing [CanonicalDataset] would change against the current database state.
 *
 * Pure and framework-free: no SQL, no transaction, no side effect. [diffReferenceDataset]
 * computes this from plain snapshots so it can be unit-tested without a database and reused
 * identically by both the read-only `diff` command and the mutating `import` command.
 */
data class ReferenceDiff(
    val settlementsToInsert: List<CandidateSettlement>,
    /** Present in both; upserted unconditionally (idempotent — same content is a no-op write). */
    val settlementsToUpdate: List<CandidateSettlement>,
    val settlementsToReactivate: Set<KshCode>,
    val settlementsToDeactivate: Set<KshCode>,

    val linesToInsert: List<CandidateRailwayLine>,
    val linesToUpdate: List<CandidateRailwayLine>,
    val linesToReactivate: Set<String>,
    val linesToDeactivate: Set<String>,

    val relationsToAdd: Set<CandidateRelation>,
    val relationsToRemove: Set<CandidateRelation>,
) {
    val isEmpty: Boolean
        get() = settlementsToInsert.isEmpty() && settlementsToDeactivate.isEmpty() &&
            settlementsToReactivate.isEmpty() &&
            linesToInsert.isEmpty() && linesToDeactivate.isEmpty() && linesToReactivate.isEmpty() &&
            relationsToAdd.isEmpty() && relationsToRemove.isEmpty()
}

/**
 * Computes the diff between a canonical dataset and the current reference tables.
 *
 * Presence in the canonical file means active; absence means the row should be deactivated
 * — never deleted, since a foreign key such as a future report's routing snapshot may
 * reference it (see V002's comment on why settlements/lines are deactivated, not dropped).
 *
 * [existingSettlements] and [existingRailwayLines] are keyed by the external identity
 * (`ksh_code` / `line_code`) that survives across dataset versions, so a settlement or line
 * already known keeps its internal UUID — the import never manufactures a new identity for
 * something that already exists.
 */
fun diffReferenceDataset(
    dataset: CanonicalDataset,
    existingSettlements: Map<String, ExistingReferenceRow>,
    existingRailwayLines: Map<String, ExistingReferenceRow>,
    existingRelations: Set<Pair<String, String>>,
): ReferenceDiff {
    val newSettlementCodes = dataset.settlements.map { it.kshCode.value }.toSet()
    val newLineCodes = dataset.railwayLines.map { it.lineCode }.toSet()

    val settlementsToInsert = dataset.settlements.filter { it.kshCode.value !in existingSettlements }
    val settlementsToUpdate = dataset.settlements.filter { it.kshCode.value in existingSettlements }
    val settlementsToReactivate = dataset.settlements
        .map { it.kshCode }
        .filter { existingSettlements[it.value]?.active == false }
        .toSet()
    val settlementsToDeactivate = existingSettlements
        .filterValues { it.active }
        .keys
        .filter { it !in newSettlementCodes }
        .map { KshCode.ofTrusted(it) }
        .toSet()

    val linesToInsert = dataset.railwayLines.filter { it.lineCode !in existingRailwayLines }
    val linesToUpdate = dataset.railwayLines.filter { it.lineCode in existingRailwayLines }
    val linesToReactivate = dataset.railwayLines
        .map { it.lineCode }
        .filter { existingRailwayLines[it]?.active == false }
        .toSet()
    val linesToDeactivate = existingRailwayLines
        .filterValues { it.active }
        .keys
        .filter { it !in newLineCodes }
        .toSet()

    val newRelations = dataset.relations.toSet()
    val newRelationKeys = newRelations.associateBy { it.kshCode.value to it.lineCode }
    val relationsToAdd = newRelations.filter { (it.kshCode.value to it.lineCode) !in existingRelations }.toSet()
    val relationsToRemove = existingRelations
        .filter { it !in newRelationKeys.keys }
        .map { (ksh, line) -> CandidateRelation(KshCode.ofTrusted(ksh), line) }
        .toSet()

    return ReferenceDiff(
        settlementsToInsert = settlementsToInsert,
        settlementsToUpdate = settlementsToUpdate,
        settlementsToReactivate = settlementsToReactivate,
        settlementsToDeactivate = settlementsToDeactivate,
        linesToInsert = linesToInsert,
        linesToUpdate = linesToUpdate,
        linesToReactivate = linesToReactivate,
        linesToDeactivate = linesToDeactivate,
        relationsToAdd = relationsToAdd,
        relationsToRemove = relationsToRemove,
    )
}
