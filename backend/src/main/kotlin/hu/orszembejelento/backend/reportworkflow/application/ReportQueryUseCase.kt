package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.reports.domain.RoutingSnapshotStatus
import hu.orszembejelento.backend.reportworkflow.domain.AgeBucket
import hu.orszembejelento.backend.reportworkflow.domain.AssignmentEndReason
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportScope
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportWorkflowQueryRepository
import hu.orszembejelento.backend.reportworkflow.infrastructure.ReportListFilter
import hu.orszembejelento.backend.reportworkflow.infrastructure.ReportWorkflowPage
import hu.orszembejelento.backend.reportworkflow.infrastructure.ReportWorkflowRow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ReportQueueItem(val row: ReportWorkflowRow, val ageBucket: AgeBucket?)

data class ReportQueuePageResult(
    val items: List<ReportQueueItem>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

/**
 * One assignment episode with its user references already resolved to `serviceId` (brief
 * §25: never an internal user UUID in a response). [endedByServiceId]/[endReason] are both
 * null together, for an episode still open.
 */
data class ResolvedAssignmentEpisode(
    val assigneeServiceId: String,
    val assignedByServiceId: String,
    val assignedAt: Instant,
    val endedAt: Instant?,
    val endedByServiceId: String?,
    val endReason: AssignmentEndReason?,
)

data class ReportDetailResult(val row: ReportWorkflowRow, val history: List<ResolvedAssignmentEpisode>)

/** Read-only report-workflow queries: the three queues and one detail lookup. */
@Service
class ReportQueryUseCase(
    private val queryRepository: JdbcReportWorkflowQueryRepository,
    private val assignmentRepository: JdbcReportAssignmentRepository,
    private val users: JdbcUserRepository,
    private val policy: ReportWorkflowPolicy,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun newQueue(actor: ReportWorkflowActor, filter: ReportListFilter, page: Int, size: Int): ReportQueuePageResult {
        val cutoff = clock.instant().minus(NEW_QUEUE_RECENT_WINDOW)
        val result = queryRepository.findNewQueue(actor, filter, cutoff, page, size)
        return toPageResult(result, page, size) { row -> if (row.submittedAt >= cutoff) AgeBucket.RECENT else AgeBucket.OLDER }
    }

    @Transactional(readOnly = true)
    fun inProgressQueue(actor: ReportWorkflowActor, filter: ReportListFilter, page: Int, size: Int): ReportQueuePageResult {
        val result = queryRepository.findInProgressQueue(actor, filter, page, size)
        return toPageResult(result, page, size) { null }
    }

    @Transactional(readOnly = true)
    fun archiveQueue(actor: ReportWorkflowActor, filter: ReportListFilter, page: Int, size: Int): ReportQueuePageResult {
        val result = queryRepository.findArchiveQueue(actor, filter, page, size)
        return toPageResult(result, page, size) { null }
    }

    /** @throws ReportNotVisibleException for both a nonexistent report and one outside the actor's visibility (brief §28, §41). */
    @Transactional(readOnly = true)
    fun detail(actor: ReportWorkflowActor, publicReportId: UUID): ReportDetailResult {
        val row = queryRepository.findByPublicId(publicReportId) ?: throw ReportNotVisibleException()
        if (!policy.canViewReport(actor, row.toScope())) throw ReportNotVisibleException()

        val episodes = assignmentRepository.findHistoryByReport(row.id)
        val serviceIdOf = mutableMapOf<UUID, String>()
        fun resolve(userId: UUID) = serviceIdOf.getOrPut(userId) {
            // Users are never hard-deleted (Phase 6 brief §44), so every historical
            // assignment reference must still resolve - exactly the same assumption
            // GetPublicReportUseCase already makes about settlement/event-type references.
            users.findById(userId)?.serviceId?.value
                ?: error("assignment history references user $userId, which no longer exists")
        }

        val history = episodes.map { episode ->
            ResolvedAssignmentEpisode(
                assigneeServiceId = resolve(episode.assigneeUserId),
                assignedByServiceId = resolve(episode.assignedByUserId),
                assignedAt = episode.assignedAt,
                endedAt = episode.endedAt,
                endedByServiceId = episode.endedByUserId?.let(::resolve),
                endReason = episode.endReason,
            )
        }

        return ReportDetailResult(row, history)
    }

    private fun toPageResult(
        result: ReportWorkflowPage,
        page: Int,
        size: Int,
        ageBucketOf: (ReportWorkflowRow) -> AgeBucket?,
    ): ReportQueuePageResult {
        val items = result.items.map { ReportQueueItem(it, ageBucketOf(it)) }
        val totalPages = if (size <= 0) 0 else (result.totalElements + size - 1) / size
        return ReportQueuePageResult(items, page, size, result.totalElements, totalPages)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100

        /** The fixed 168-hour NEW-queue product rule (brief §19) — always measured against the injected backend Clock. */
        val NEW_QUEUE_RECENT_WINDOW: Duration = Duration.ofHours(168)
    }
}

/** Converts a fully-resolved read-model row into the narrow fact set [ReportWorkflowPolicy] needs. */
internal fun ReportWorkflowRow.toScope(): ReportScope =
    if (routingStatus == RoutingSnapshotStatus.UNCLASSIFIED) {
        ReportScope.unclassified(status)
    } else {
        ReportScope.routed(
            status = status,
            serviceAreaId = requireNotNull(serviceAreaId) { "a ROUTED row always names a service area" },
            serviceAreaActive = serviceAreaStatus == ServiceAreaStatus.ACTIVE,
            assignedUserId = assignedUserId,
        )
    }
