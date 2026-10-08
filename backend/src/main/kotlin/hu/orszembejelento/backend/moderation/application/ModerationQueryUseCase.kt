package hu.orszembejelento.backend.moderation.application

import hu.orszembejelento.backend.identity.domain.User
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.moderation.domain.ModerationEpisode
import hu.orszembejelento.backend.moderation.domain.ModerationPolicy
import hu.orszembejelento.backend.moderation.infrastructure.DeletedReportListFilter
import hu.orszembejelento.backend.moderation.infrastructure.DeletedReportPage
import hu.orszembejelento.backend.moderation.infrastructure.DeletedReportRow
import hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationQueryRepository
import hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationRepository
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.reports.domain.RoutingSnapshotStatus
import hu.orszembejelento.backend.reportworkflow.application.ResolvedAssignmentEpisode
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportScope
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class DeletedReportDetailResult(val row: DeletedReportRow, val history: List<ResolvedAssignmentEpisode>, val episode: ModerationEpisode)

/** Read-only moderation queries: the deleted-report list and one deleted-report detail (brief §18-21). */
@Service
class ModerationQueryUseCase(
    private val queryRepository: JdbcModerationQueryRepository,
    private val moderationRepository: JdbcModerationRepository,
    private val assignmentRepository: JdbcReportAssignmentRepository,
    private val users: JdbcUserRepository,
    private val policy: ModerationPolicy,
) {

    @Transactional(readOnly = true)
    fun deletedList(actor: ReportWorkflowActor, filter: DeletedReportListFilter, page: Int, size: Int): DeletedReportPage {
        requireModerationActor(actor)
        return queryRepository.findDeletedList(actor, filter, page, size)
    }

    /** @throws ReportNotVisibleException for a nonexistent report, one not currently deleted, and one outside the actor's moderation scope alike (brief §3). */
    @Transactional(readOnly = true)
    fun deletedDetail(actor: ReportWorkflowActor, publicReportId: UUID): DeletedReportDetailResult {
        requireModerationActor(actor)

        val row = queryRepository.findByPublicId(publicReportId) ?: throw ReportNotVisibleException()
        if (!policy.canViewDeleted(actor, row.toScope())) throw ReportNotVisibleException()

        val episode = moderationRepository.findOpenEpisode(row.id)
            ?: error("report ${row.publicId} was returned by the deleted-report read model but has no open moderation episode")

        val episodes = assignmentRepository.findHistoryByReport(row.id)
        val userOf = mutableMapOf<UUID, User>()
        fun resolve(userId: UUID) = userOf.getOrPut(userId) {
            users.findById(userId)
                ?: error("assignment history references user $userId, which no longer exists")
        }
        val history = episodes.map { ep ->
            val assignee = resolve(ep.assigneeUserId)
            val assignedBy = resolve(ep.assignedByUserId)
            val endedBy = ep.endedByUserId?.let(::resolve)
            ResolvedAssignmentEpisode(
                assigneeServiceId = assignee.serviceId.value,
                assigneeNickname = assignee.nickname,
                assignedByServiceId = assignedBy.serviceId.value,
                assignedByNickname = assignedBy.nickname,
                assignedAt = ep.assignedAt,
                endedAt = ep.endedAt,
                endedByServiceId = endedBy?.serviceId?.value,
                endedByNickname = endedBy?.nickname,
                endReason = ep.endReason,
            )
        }

        return DeletedReportDetailResult(row, history, episode)
    }
}

/** Converts the deleted-report read-model row into the narrow fact set [ModerationPolicy] needs — mirrors `ReportWorkflowRow.toScope()`. */
internal fun DeletedReportRow.toScope(): ReportScope =
    if (routingStatus == RoutingSnapshotStatus.UNCLASSIFIED) {
        ReportScope.unclassified(statusBeforeDelete)
    } else {
        ReportScope.routed(
            status = statusBeforeDelete,
            serviceAreaId = requireNotNull(serviceAreaId) { "a ROUTED row always names a service area" },
            serviceAreaActive = serviceAreaStatus == ServiceAreaStatus.ACTIVE,
            assignedUserId = null,
        )
    }
