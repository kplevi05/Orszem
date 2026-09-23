package hu.orszembejelento.backend.areaadmin.application

import hu.orszembejelento.backend.areaadmin.domain.*
import hu.orszembejelento.backend.areaadmin.infrastructure.JdbcSettlementLineMappingRepository
import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.reference.domain.KshCode
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetUnavailableException
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Bounded, atomic operational imports. No reference creation, grants or snapshot writes. */
@Service
class SettlementLineMappingUseCase(
    private val reference: JdbcReferenceRepository,
    private val areas: JdbcServiceAreaRepository,
    private val mappings: JdbcSettlementLineMappingRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun preview(actor: AreaAdminActor, referenceVersion: String, changes: List<SettlementLineChange>): SettlementLineBatchResult {
        authorize(actor)
        reference.acquireSharedReferenceStateLock()
        val resolved = validate(referenceVersion, changes)
        return result(referenceVersion, false, resolved)
    }

    @Transactional
    fun apply(actor: AreaAdminActor, referenceVersion: String, changes: List<SettlementLineChange>): SettlementLineBatchResult {
        authorize(actor)
        // Same lock as reference import, legacy line assignment and Public submission.
        // Revalidate the entire request under the exclusive lock: preview is never authority.
        reference.acquireImportLock()
        val resolved = validate(referenceVersion, changes)
        resolved.map { it.lineId }.distinct().sorted().forEach { reference.lockRailwayLineById(it) }
        val changed = resolved.filter { it.preview.changed }
        val affectedIds = changed.flatMap { listOfNotNull(it.preview.currentServiceAreaId, it.preview.targetServiceAreaId) }.distinct().sorted()
        val locked = affectedIds.associateWith { areas.lockById(it) ?: throw ServiceAreaNotFoundException() }
        changed.mapNotNull { it.preview.targetServiceAreaId }.forEach {
            if (!locked.getValue(it).isActive) throw TargetServiceAreaInactiveException()
        }
        val operationId = UUID.randomUUID()
        for (row in changed) {
            val p = row.preview
            mappings.set(row.settlementId, row.lineId, p.targetServiceAreaId)
            audit.record(
                operationId = operationId, actorType = AuditActorType.USER, actorUserId = actor.userId,
                eventType = AuditEventType.SETTLEMENT_LINE_SERVICE_AREA_CHANGED,
                targetType = AuditTargetType.RAILWAY_LINE, targetId = row.lineId,
                metadata = buildMap {
                    put("kshCode", p.kshCode)
                    put("settlementName", row.settlementName)
                    put("railwayLineCode", p.lineCode)
                    put("datasetVersion", referenceVersion)
                    p.currentServiceAreaId?.let { put("fromAreaId", it.toString()) }
                    p.targetServiceAreaId?.let { put("toAreaId", it.toString()) }
                },
            )
        }
        val now = clock.instant()
        locked.values.forEach { areas.bumpAdminVersion(it.id, it.adminVersion + 1, now) }
        return result(referenceVersion, true, resolved)
    }

    @Transactional(readOnly = true)
    fun list(actor: AreaAdminActor, areaId: UUID?, page: Int, size: Int): List<SettlementLineMappingView> {
        authorize(actor)
        return mappings.list(areaId, page.coerceAtLeast(0).toLong() * size.coerceIn(1, 100), size.coerceIn(1, 100))
    }

    private data class Resolved(val settlementId: UUID, val lineId: UUID, val settlementName: String, val preview: SettlementLineChangePreview)

    private fun validate(referenceVersion: String, changes: List<SettlementLineChange>): List<Resolved> {
        if (changes.isEmpty() || changes.size > 1000 || changes.map { it.kshCode to it.lineCode }.distinct().size != changes.size ||
            changes.any { !it.kshCode.matches(Regex("[0-9]{5}")) || it.lineCode.isBlank() || it.lineCode.length > 16 }
        ) fail(SettlementLineConfigurationProblem.INVALID_BATCH)
        val current = reference.findCurrentReferenceState() ?: throw ReferenceDatasetUnavailableException()
        if (referenceVersion != current.datasetVersion) fail(SettlementLineConfigurationProblem.REFERENCE_CHANGED)
        return changes.map { change ->
            val settlement = reference.findSettlementByKshCode(KshCode.ofTrusted(change.kshCode))
                ?: fail(SettlementLineConfigurationProblem.REFERENCE_NOT_AVAILABLE)
            val line = reference.findRailwayLineByCode(change.lineCode)
                ?: fail(SettlementLineConfigurationProblem.REFERENCE_NOT_AVAILABLE)
            if (!reference.relationExists(settlement.id, line.id)) fail(SettlementLineConfigurationProblem.REFERENCE_NOT_AVAILABLE)
            val before = mappings.currentArea(settlement.id, line.id)
            if (before != change.expectedCurrentServiceAreaId) fail(SettlementLineConfigurationProblem.ASSIGNMENT_CHANGED)
            if (change.targetServiceAreaId != null) {
                if (!settlement.active || !line.active) fail(SettlementLineConfigurationProblem.REFERENCE_NOT_AVAILABLE)
                if (areas.findAreaOfRailwayLine(line.id) != null) fail(SettlementLineConfigurationProblem.MIXED_ROUTING_MODES)
                val area = areas.findById(change.targetServiceAreaId) ?: throw ServiceAreaNotFoundException()
                if (!area.isActive) throw TargetServiceAreaInactiveException()
            }
            Resolved(settlement.id, line.id, settlement.name, SettlementLineChangePreview(
                change.kshCode, change.lineCode, before, change.targetServiceAreaId, before != change.targetServiceAreaId,
            ))
        }
    }

    private fun result(version: String, applied: Boolean, resolved: List<Resolved>) =
        SettlementLineBatchResult(version, applied, resolved.count { it.preview.changed }, resolved.map { it.preview })

    private fun authorize(actor: AreaAdminActor) {
        if (!actor.isSuperAdmin) throw ServiceAreaAdminForbiddenException()
    }

    private fun fail(problem: SettlementLineConfigurationProblem): Nothing = throw SettlementLineConfigurationException(problem)
}
