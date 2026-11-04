package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationRepository
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.reportworkflow.domain.ReportMessage
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowForbiddenException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportMessageRepository
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Appends a one-way message from an authenticated service user to the anonymous reporter of
 * a visible report (any status: NEW, IN_PROGRESS, ARCHIVED, provided it is not currently
 * moderation-deleted). Once committed, the message is immutable and cannot be withdrawn.
 */
@Service
class SendReportMessageUseCase(
    private val reports: JdbcReportRepository,
    private val users: JdbcUserRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
    private val messages: JdbcReportMessageRepository,
    private val scopeResolver: ReportScopeResolver,
    private val policy: ReportWorkflowPolicy,
    private val audit: JdbcAuditRepository,
    private val moderation: JdbcModerationRepository,
    private val clock: Clock,
) {

    @Transactional
    fun send(actor: ReportWorkflowActor, publicReportId: UUID, rawMessageText: String?): ReportMessage {
        val normalizedText = ReportMessage.normalizeText(rawMessageText)

        val (locked, scope) = lockAndResolve(reports, scopeResolver, moderation::hasOpenEpisode, publicReportId)
        if (!policy.canViewReport(actor, scope)) throw ReportNotVisibleException()

        val selfUser = users.lockByServiceId(actor.serviceId)
            ?: error("authenticated user ${actor.userId} has no corresponding users row")
        if (selfUser.status != UserStatus.ACTIVE) {
            throw ReportWorkflowForbiddenException()
        }
        val selfAreaActor = serviceAreas.loadAreaActor(selfUser.id)
            ?: error("authenticated user ${actor.userId} has no corresponding users row")
        val freshActor = actor.copy(
            role = selfAreaActor.role,
            globalAreaAccess = selfAreaActor.globalAreaAccess,
            ownActiveAreaIds = serviceAreas.activeAssignedAreaIds(selfUser.id),
        )
        if (!policy.canViewReport(freshActor, scope)) throw ReportNotVisibleException()

        val now = clock.instant()
        val message = messages.insertMessage(
            reportId = locked.id,
            senderUserId = actor.userId,
            messageText = normalizedText,
            sentAt = now,
        )

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.REPORT_MESSAGE_SENT,
            targetType = AuditTargetType.REPORT,
            targetId = locked.id,
            metadata = mapOf(
                "publicReportId" to locked.publicId.toString(),
                "messageId" to message.id.toString(),
            ),
        )

        return message
    }
}
