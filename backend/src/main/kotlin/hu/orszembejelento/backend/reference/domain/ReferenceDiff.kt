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
 *
 * Absence is only ever treated as a removal signal for a component whose
 * [DatasetCoverage] says COMPLETE. Under PARTIAL, an existing row absent from the new
 * dataset is left exactly as it is - `settlementsPreservedDespiteAbsence`,
 * `linesPreservedDespiteAbsence` and `relationsPreservedDespiteAbsence` name what would have
 * been removed under the old, coverage-blind rule, purely so an operator can see it. See
 * ADR 0006.
 */
data class ReferenceDiff(
    val settlementsToInsert: List<CandidateSettlement>,
    /** Present in both; upserted unconditionally (idempotent — same content is a no-op write). */
    val settlementsToUpdate: List<CandidateSettlement>,
    val settlementsToReactivate: Set<KshCode>,
    /** Empty unless [DatasetCoverage.settlements] is COMPLETE. */
    val settlementsToDeactivate: Set<KshCode>,
    /** Populated instead of [settlementsToDeactivate] when settlement coverage is PARTIAL. */
    val settlementsPreservedDespiteAbsence: Set<KshCode>,

    val linesToInsert: List<CandidateRailwayLine>,
    val linesToUpdate: List<CandidateRailwayLine>,
    val linesToReactivate: Set<String>,
    /** Empty unless [DatasetCoverage.railwayLines] is COMPLETE. */
    val linesToDeactivate: Set<String>,
    /** Populated instead of [linesToDeactivate] when railway-line coverage is PARTIAL. */
    val linesPreservedDespiteAbsence: Set<String>,

    val relationsToAdd: Set<CandidateRelation>,
    /** Empty unless [DatasetCoverage.settlementRailwayLines] is COMPLETE. */
    val relationsToRemove: Set<CandidateRelation>,
    /** Populated instead of [relationsToRemove] when relation coverage is PARTIAL. */
    val relationsPreservedDespiteAbsence: Set<CandidateRelation>,
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
 * A row absent from the new dataset is deactivated/removed only when the dataset's own
 * manifest claims COMPLETE coverage for that component ([CanonicalDataset.manifest]'s
 * [DatasetCoverage]). Under PARTIAL, absence is not evidence of anything - the HÜSZ annexes
 * do not enumerate every settlement a line crosses, so a line or relation missing from one
 * snapshot may simply be a gap in that snapshot, not a fact about the railway network. See
 * ADR 0006 and `PHASE_3B_DECISION_GATE.md` §7.
 *
 * Never deletes a row outright, even when removal is permitted: presence is deactivated,
 * absence... stays absent. A foreign key such as a future report's routing snapshot may
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
    val coverage = dataset.manifest.coverage

    val newSettlementCodes = dataset.settlements.map { it.kshCode.value }.toSet()
    val newLineCodes = dataset.railwayLines.map { it.lineCode }.toSet()

    val settlementsToInsert = dataset.settlements.filter { it.kshCode.value !in existingSettlements }
    val settlementsToUpdate = dataset.settlements.filter { it.kshCode.value in existingSettlements }
    val settlementsToReactivate = dataset.settlements
        .map { it.kshCode }
        .filter { existingSettlements[it.value]?.active == false }
        .toSet()
    val absentActiveSettlements = existingSettlements
        .filterValues { it.active }
        .keys
        .filter { it !in newSettlementCodes }
        .map { KshCode.ofTrusted(it) }
        .toSet()
    val settlementsComplete = coverage.settlements == CoverageComponentStatus.COMPLETE
    val settlementsToDeactivate = if (settlementsComplete) absentActiveSettlements else emptySet()
    val settlementsPreservedDespiteAbsence = if (settlementsComplete) emptySet() else absentActiveSettlements

    val linesToInsert = dataset.railwayLines.filter { it.lineCode !in existingRailwayLines }
    val linesToUpdate = dataset.railwayLines.filter { it.lineCode in existingRailwayLines }
    val linesToReactivate = dataset.railwayLines
        .map { it.lineCode }
        .filter { existingRailwayLines[it]?.active == false }
        .toSet()
    val absentActiveLines = existingRailwayLines
        .filterValues { it.active }
        .keys
        .filter { it !in newLineCodes }
        .toSet()
    val linesComplete = coverage.railwayLines == CoverageComponentStatus.COMPLETE
    val linesToDeactivate = if (linesComplete) absentActiveLines else emptySet()
    val linesPreservedDespiteAbsence = if (linesComplete) emptySet() else absentActiveLines

    val newRelations = dataset.relations.toSet()
    val newRelationKeys = newRelations.associateBy { it.kshCode.value to it.lineCode }
    val relationsToAdd = newRelations.filter { (it.kshCode.value to it.lineCode) !in existingRelations }.toSet()
    val absentRelations = existingRelations
        .filter { it !in newRelationKeys.keys }
        .map { (ksh, line) -> CandidateRelation(KshCode.ofTrusted(ksh), line) }
        .toSet()
    val relationsComplete = coverage.settlementRailwayLines == CoverageComponentStatus.COMPLETE
    val relationsToRemove = if (relationsComplete) absentRelations else emptySet()
    val relationsPreservedDespiteAbsence = if (relationsComplete) emptySet() else absentRelations

    return ReferenceDiff(
        settlementsToInsert = settlementsToInsert,
        settlementsToUpdate = settlementsToUpdate,
        settlementsToReactivate = settlementsToReactivate,
        settlementsToDeactivate = settlementsToDeactivate,
        settlementsPreservedDespiteAbsence = settlementsPreservedDespiteAbsence,
        linesToInsert = linesToInsert,
        linesToUpdate = linesToUpdate,
        linesToReactivate = linesToReactivate,
        linesToDeactivate = linesToDeactivate,
        linesPreservedDespiteAbsence = linesPreservedDespiteAbsence,
        relationsToAdd = relationsToAdd,
        relationsToRemove = relationsToRemove,
        relationsPreservedDespiteAbsence = relationsPreservedDespiteAbsence,
    )
}
