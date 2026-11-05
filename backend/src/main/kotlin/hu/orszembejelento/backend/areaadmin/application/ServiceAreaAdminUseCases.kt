package hu.orszembejelento.backend.areaadmin.application

import hu.orszembejelento.backend.areaadmin.domain.AreaAdminActor
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAlreadyActiveException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAlreadyInactiveException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaHasOpenReportsException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaHasRailwayLinesException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNameAlreadyInUseException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNameBlankException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaNotFoundException
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaStateChangedException
import hu.orszembejelento.backend.areaadmin.domain.SettlementAdminInactiveException
import hu.orszembejelento.backend.areaadmin.domain.SettlementAdminNotFoundException
import hu.orszembejelento.backend.areaadmin.domain.SettlementAlreadyAssignedToAreaException
import hu.orszembejelento.backend.areaadmin.domain.SettlementAssignmentChangedException
import hu.orszembejelento.backend.areaadmin.domain.TargetServiceAreaInactiveException
import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.reference.domain.ServiceArea
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.time.Clock
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Creates a new ServiceArea (brief §10): always ACTIVE, no RailwayLines, no user
 * assignments, `adminVersion = 0`. Deliberately no lines/users in the same transaction
 * (brief §47: "create first, then configure lines" - explicit operations, never an implicit
 * bundle).
 *
 * No advisory routing lock here: an area with no RailwayLines mapped yet cannot affect any
 * routing decision the instant it is created, so there is nothing for a concurrent
 * submission to race against.
 */
@Service
class CreateServiceAreaUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional
    fun create(actor: AreaAdminActor, rawName: String): ServiceArea {
        val name = normalizeName(rawName)
        val now = clock.instant()
        val area = ServiceArea(
            id = UUID.randomUUID(),
            name = name,
            status = ServiceAreaStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            adminVersion = 0,
        )
        try {
            serviceAreas.insert(area)
        } catch (e: DuplicateKeyException) {
            throw ServiceAreaNameAlreadyInUseException()
        }

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.SERVICE_AREA_CREATED,
            targetType = AuditTargetType.SERVICE_AREA,
            targetId = area.id,
            metadata = mapOf("name" to name),
        )
        return area
    }
}

/**
 * Renames a ServiceArea (brief §11). Display/configuration only - never touches report
 * routing, user assignments or RailwayLine mapping. No advisory routing lock: a name has no
 * bearing on any routing decision, only the id and active state do.
 */
@Service
class RenameServiceAreaUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional
    fun rename(actor: AreaAdminActor, areaId: UUID, expectedVersion: Long, rawName: String): ServiceArea {
        val locked = serviceAreas.lockById(areaId) ?: throw ServiceAreaNotFoundException()
        if (locked.adminVersion != expectedVersion) throw ServiceAreaStateChangedException()

        val newName = normalizeName(rawName)
        val oldName = locked.name
        val newVersion = locked.adminVersion + 1
        val now = clock.instant()

        if (newName != oldName) {
            try {
                serviceAreas.renameAndBumpVersion(locked.id, newName, newVersion, now)
            } catch (e: DuplicateKeyException) {
                throw ServiceAreaNameAlreadyInUseException()
            }

            audit.record(
                operationId = UUID.randomUUID(),
                actorType = AuditActorType.USER,
                actorUserId = actor.userId,
                eventType = AuditEventType.SERVICE_AREA_RENAMED,
                targetType = AuditTargetType.SERVICE_AREA,
                targetId = locked.id,
                metadata = mapOf("oldName" to oldName, "newName" to newName, "adminVersion" to newVersion.toString()),
            )
        } else {
            // No actual change - still bump the version so a caller's `expectedVersion` stays
            // meaningful for their next mutation, but nothing to audit (brief §41: audit
            // records mutations, not confirmed-unchanged no-ops).
            serviceAreas.renameAndBumpVersion(locked.id, newName, newVersion, now)
        }

        return locked.copy(name = newName, adminVersion = newVersion, updatedAt = now)
    }
}

/**
 * Activates a ServiceArea (brief §12).
 *
 * Holds the shared reference/routing advisory lock in its **exclusive** mode
 * ([JdbcReferenceRepository.acquireImportLock] - the same key
 * [hu.orszembejelento.backend.common.ReferenceStateLock] uses, reused rather than a second
 * key, per brief §25) for its whole transaction: activation changes what
 * `RoutingService.resolveLineToArea` will decide for any line already mapped here
 * (`SERVICE_AREA_INACTIVE` becomes reachable/unreachable), so a concurrent submission - which
 * holds the *shared* half of the same lock while it reads service-area state and computes its
 * snapshot - must see either the fully-before or fully-after configuration, never a half
 * transition (brief §25's own goal statement).
 */
@Service
class ActivateServiceAreaUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional
    fun activate(actor: AreaAdminActor, areaId: UUID, expectedVersion: Long): ServiceArea {
        referenceRepository.acquireImportLock()

        val locked = serviceAreas.lockById(areaId) ?: throw ServiceAreaNotFoundException()
        if (locked.adminVersion != expectedVersion) throw ServiceAreaStateChangedException()
        if (locked.isActive) throw ServiceAreaAlreadyActiveException()

        val newVersion = locked.adminVersion + 1
        val now = clock.instant()
        serviceAreas.updateStatusAndBumpVersion(locked.id, ServiceAreaStatus.ACTIVE, newVersion, now)
        serviceAreas.mappedSettlementIds(locked.id).forEach { settlementId ->
            serviceAreas.routeOpenReportsForSettlement(settlementId, locked.id)
        }

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.SERVICE_AREA_ACTIVATED,
            targetType = AuditTargetType.SERVICE_AREA,
            targetId = locked.id,
            metadata = mapOf("adminVersion" to newVersion.toString()),
        )
        return locked.copy(status = ServiceAreaStatus.ACTIVE, adminVersion = newVersion, updatedAt = now)
    }
}

/**
 * Deactivates a ServiceArea, only when eligible (brief §13-§15).
 *
 * Same exclusive routing-lock reasoning as [ActivateServiceAreaUseCase]. The two blocker
 * checks run **after** the row is locked and the version is confirmed current, so they see a
 * definitive snapshot - never a state that could change underneath the decision before this
 * transaction commits (brief §31 concurrency: a racing line-assignment either commits first,
 * in which case this sees `mappedRailwayLineCount > 0` and rejects, or this wins the row lock
 * first and the assignment then rejects against the now-inactive area - see
 * `AssignRailwayLineUseCase`).
 */
@Service
class DeactivateServiceAreaUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional
    fun deactivate(actor: AreaAdminActor, areaId: UUID, expectedVersion: Long): ServiceArea {
        referenceRepository.acquireImportLock()

        val locked = serviceAreas.lockById(areaId) ?: throw ServiceAreaNotFoundException()
        if (locked.adminVersion != expectedVersion) throw ServiceAreaStateChangedException()
        if (!locked.isActive) throw ServiceAreaAlreadyInactiveException()

        if (serviceAreas.countMappedRailwayLines(locked.id) > 0 || serviceAreas.countSettlementLineMappings(locked.id) > 0) throw ServiceAreaHasRailwayLinesException()
        if (serviceAreas.countOpenOperationalReports(locked.id) > 0) throw ServiceAreaHasOpenReportsException()

        val newVersion = locked.adminVersion + 1
        val now = clock.instant()
        serviceAreas.updateStatusAndBumpVersion(locked.id, ServiceAreaStatus.INACTIVE, newVersion, now)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.SERVICE_AREA_DEACTIVATED,
            targetType = AuditTargetType.SERVICE_AREA,
            targetId = locked.id,
            metadata = mapOf("adminVersion" to newVersion.toString()),
        )
        return locked.copy(status = ServiceAreaStatus.INACTIVE, adminVersion = newVersion, updatedAt = now)
    }
}

/**
 * Deletes a ServiceArea (`SUPER_ADMIN` only) and ensures zero ticket loss by converting any
 * report routing snapshots referencing the deleted area to `UNCLASSIFIED` (`SERVICE_AREA_INACTIVE`)
 * so those tickets immediately become visible to everyone.
 */
@Service
class DeleteServiceAreaUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val audit: JdbcAuditRepository,
) {
    @Transactional
    fun delete(actor: AreaAdminActor, areaId: UUID, expectedVersion: Long) {
        referenceRepository.acquireImportLock()

        val locked = serviceAreas.lockById(areaId) ?: throw ServiceAreaNotFoundException()
        if (locked.adminVersion != expectedVersion) throw ServiceAreaStateChangedException()

        serviceAreas.deleteArea(locked.id)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.SERVICE_AREA_DELETED,
            targetType = AuditTargetType.SERVICE_AREA,
            targetId = locked.id,
            metadata = mapOf("oldName" to locked.name),
        )
    }
}

/**
 * Assigns or moves a settlement to an active ServiceArea (`SUPER_ADMIN` only).
 * Also routes any currently open (`NEW`, non-deleted) reports for this settlement into the
 * target ServiceArea.
 */
@Service
class AssignSettlementUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional
    fun assign(
        actor: AreaAdminActor,
        settlementId: UUID,
        targetServiceAreaId: UUID,
        expectedCurrentServiceAreaId: UUID?,
    ) {
        referenceRepository.acquireImportLock()

        val settlement = referenceRepository.findSettlementById(settlementId) ?: throw SettlementAdminNotFoundException()
        if (!settlement.active) throw SettlementAdminInactiveException()

        val currentArea = serviceAreas.findAreaOfSettlement(settlement.id)
        if (currentArea?.id != expectedCurrentServiceAreaId) throw SettlementAssignmentChangedException()
        if (currentArea?.id == targetServiceAreaId) throw SettlementAlreadyAssignedToAreaException()

        val areaIdsToLock = listOfNotNull(currentArea?.id, targetServiceAreaId).distinct().sortedBy { it.toString() }
        val lockedById = areaIdsToLock.associateWith { id ->
            serviceAreas.lockById(id) ?: throw ServiceAreaNotFoundException()
        }

        val targetArea = lockedById.getValue(targetServiceAreaId)
        if (!targetArea.isActive) throw TargetServiceAreaInactiveException()

        val now = clock.instant()
        serviceAreas.assignSettlement(targetArea.id, settlement.id)
        serviceAreas.routeOpenReportsForSettlement(settlement.id, targetArea.id)

        serviceAreas.bumpAdminVersion(targetArea.id, targetArea.adminVersion + 1, now)
        if (currentArea != null) {
            val lockedFrom = lockedById.getValue(currentArea.id)
            serviceAreas.bumpAdminVersion(lockedFrom.id, lockedFrom.adminVersion + 1, now)
        }

        val isMove = currentArea != null
        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = if (isMove) AuditEventType.SETTLEMENT_SERVICE_AREA_MOVED else AuditEventType.SETTLEMENT_SERVICE_AREA_ASSIGNED,
            targetType = AuditTargetType.SERVICE_AREA,
            targetId = targetArea.id,
            metadata = buildMap {
                put("settlementName", settlement.name)
                put("kshCode", settlement.kshCode.value)
                put("toAreaId", targetArea.id.toString())
                if (currentArea != null) put("fromAreaId", currentArea.id.toString())
            },
        )
    }
}

/**
 * Unassigns a settlement from its current ServiceArea (`SUPER_ADMIN` only) and moves any
 * currently open (`NEW`, non-deleted) reports for this settlement in that area back to
 * `UNCLASSIFIED` so no ticket is lost.
 */
@Service
class UnassignSettlementUseCase(
    private val serviceAreas: JdbcServiceAreaRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    @Transactional
    fun unassign(actor: AreaAdminActor, settlementId: UUID, expectedCurrentServiceAreaId: UUID) {
        referenceRepository.acquireImportLock()

        val settlement = referenceRepository.findSettlementById(settlementId) ?: throw SettlementAdminNotFoundException()
        val currentArea = serviceAreas.findAreaOfSettlement(settlement.id)
        if (currentArea == null || currentArea.id != expectedCurrentServiceAreaId) {
            throw SettlementAssignmentChangedException()
        }

        val lockedArea = serviceAreas.lockById(currentArea.id) ?: throw ServiceAreaNotFoundException()
        val removed = serviceAreas.removeSettlementMapping(lockedArea.id, settlement.id)
        if (removed == 0) throw SettlementAssignmentChangedException()

        serviceAreas.unclassifyOpenReportsForSettlement(settlement.id, lockedArea.id)
        serviceAreas.bumpAdminVersion(lockedArea.id, lockedArea.adminVersion + 1, clock.instant())

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.SETTLEMENT_SERVICE_AREA_UNASSIGNED,
            targetType = AuditTargetType.SERVICE_AREA,
            targetId = lockedArea.id,
            metadata = mapOf(
                "settlementName" to settlement.name,
                "kshCode" to settlement.kshCode.value,
                "fromAreaId" to lockedArea.id.toString(),
            ),
        )
    }
}

/** Trim, and reject blank (brief §9) - never silently truncated; the existing VARCHAR(200) column constraint is left to the database. */
internal fun normalizeName(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) throw ServiceAreaNameBlankException()
    return trimmed
}
