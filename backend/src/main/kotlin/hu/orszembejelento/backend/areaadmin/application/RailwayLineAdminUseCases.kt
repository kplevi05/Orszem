package hu.orszembejelento.backend.areaadmin.application

import hu.orszembejelento.backend.areaadmin.domain.AreaAdminActor
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminInactiveException
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminNotFoundException
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAlreadyAssignedToAreaException
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAssignmentChangedException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNotFoundException
import hu.orszembejelento.backend.areaadmin.domain.TargetServiceAreaInactiveException
import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.reference.domain.ServiceArea
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Assigns an unassigned active RailwayLine to an ACTIVE ServiceArea, or - the identical
 * operation, brief §20 - moves an already-mapped line from one area to another when
 * [expectedCurrentServiceAreaId] names its current area. Never creates an intermediate
 * committed UNASSIGNED state for a move (brief §20): both the removal of the old mapping and
 * the creation of the new one happen inside this one transaction.
 *
 * Canonical lock order (brief §27): (1) the exclusive routing advisory lock, so a concurrent
 * Public submission sees either the fully-before or fully-after mapping, never a half-move;
 * (2) the RailwayLine row; (3) the affected ServiceArea row(s), in canonical UUID order (both,
 * for a move, to give two concurrent cross-moves of the same pair of areas one deterministic
 * lock-acquisition order and rule out a lock-order deadlock, brief §26/§28).
 *
 * Only future submissions ever see the new mapping (brief §2/§3/§63): nothing here calls
 * `RoutingService` against any existing report, and no report's routing snapshot is rewritten.
 */
@Service
class AssignRailwayLineUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional
    fun assign(
        actor: AreaAdminActor,
        railwayLineId: UUID,
        targetServiceAreaId: UUID,
        expectedCurrentServiceAreaId: UUID?,
    ) {
        referenceRepository.acquireImportLock()

        val line = referenceRepository.lockRailwayLineById(railwayLineId) ?: throw RailwayLineAdminNotFoundException()
        if (!line.active) throw RailwayLineAdminInactiveException()

        val currentAreaId = serviceAreas.findAreaOfRailwayLine(railwayLineId)?.id
        if (currentAreaId != expectedCurrentServiceAreaId) throw RailwayLineAssignmentChangedException()
        if (currentAreaId == targetServiceAreaId) throw RailwayLineAlreadyAssignedToAreaException()

        // Canonical UUID order for the area row lock(s), so a concurrent cross-move of the
        // same two areas (A->B racing B->A, say) always acquires them in the same order and
        // can never deadlock against this use case's own other invocation.
        val idsToLock = listOfNotNull(currentAreaId, targetServiceAreaId).sorted()
        val lockedAreas = idsToLock.associateWith { serviceAreas.lockById(it) ?: throw ServiceAreaNotFoundException() }

        val target = lockedAreas.getValue(targetServiceAreaId)
        if (!target.isActive) throw TargetServiceAreaInactiveException()

        val now = clock.instant()
        val newTargetVersion = target.adminVersion + 1

        if (currentAreaId != null) {
            val source = lockedAreas.getValue(currentAreaId)
            serviceAreas.removeRailwayLineMapping(source.id, railwayLineId)
            serviceAreas.bumpAdminVersion(source.id, source.adminVersion + 1, now)
        }
        serviceAreas.assignRailwayLine(target.id, railwayLineId)
        serviceAreas.bumpAdminVersion(target.id, newTargetVersion, now)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = if (currentAreaId != null) AuditEventType.RAILWAY_LINE_SERVICE_AREA_MOVED else AuditEventType.RAILWAY_LINE_SERVICE_AREA_ASSIGNED,
            targetType = AuditTargetType.RAILWAY_LINE,
            targetId = line.id,
            metadata = buildMap {
                put("railwayLineCode", line.lineCode)
                put("toAreaId", target.id.toString())
                currentAreaId?.let { put("fromAreaId", it.toString()) }
            },
        )
    }
}

/**
 * Removes a RailwayLine's current ServiceArea mapping (brief §21). Unlike [AssignRailwayLineUseCase],
 * an inactive RailwayLine reference row is deliberately still allowed here - unassigning is
 * cleanup of an existing mapping, never the creation of a new one, so brief §24's "do not
 * create a NEW mapping for an inactive line" rule simply does not apply to this direction.
 *
 * After this commits, a future submission on this line resolves through the existing,
 * unchanged Phase 3 `RAILWAY_LINE_UNASSIGNED` outcome (brief §21) - nothing new is invented
 * here.
 */
@Service
class UnassignRailwayLineUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional
    fun unassign(actor: AreaAdminActor, railwayLineId: UUID, expectedCurrentServiceAreaId: UUID) {
        referenceRepository.acquireImportLock()

        val line = referenceRepository.lockRailwayLineById(railwayLineId) ?: throw RailwayLineAdminNotFoundException()

        val currentAreaId = serviceAreas.findAreaOfRailwayLine(railwayLineId)?.id
        if (currentAreaId != expectedCurrentServiceAreaId) throw RailwayLineAssignmentChangedException()

        val area: ServiceArea = serviceAreas.lockById(expectedCurrentServiceAreaId) ?: throw ServiceAreaNotFoundException()

        val now = clock.instant()
        serviceAreas.removeRailwayLineMapping(area.id, railwayLineId)
        val newVersion = area.adminVersion + 1
        serviceAreas.bumpAdminVersion(area.id, newVersion, now)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.RAILWAY_LINE_SERVICE_AREA_UNASSIGNED,
            targetType = AuditTargetType.RAILWAY_LINE,
            targetId = line.id,
            metadata = mapOf("railwayLineCode" to line.lineCode, "fromAreaId" to area.id.toString()),
        )
    }
}
