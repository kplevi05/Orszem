package hu.orszem.servicecase.domain

import hu.orszem.reporting.domain.ReportStatus
import java.time.Instant
import java.util.UUID

interface ServiceReportRepository {

    /** Active cases: NEW before IN_PROGRESS, then receivedAt DESC. Keyset paginated. */
    fun findActive(statuses: Set<ReportStatus>, cursor: String?, limit: Int): Page<ServiceReportListItem>

    /** Archived cases: archivedAt DESC. Keyset paginated. */
    fun findArchived(cursor: String?, limit: Int): Page<ServiceReportListItem>

    fun findDetail(id: UUID): ServiceReportDetailView?

    /** Atomic `NEW -> IN_PROGRESS`. */
    fun accept(id: UUID, actorUserId: UUID, now: Instant): TransitionOutcome

    /** Atomic `IN_PROGRESS -> ARCHIVED`. */
    fun archive(id: UUID, actorUserId: UUID, now: Instant): TransitionOutcome

    /**
     * Demo v1.1: permanently removes a report in any status, together with the
     * audit rows that pointed at it, so no orphaned references remain.
     * Returns the status the report had, or null when it did not exist.
     */
    fun deletePermanently(id: UUID): ReportStatus?
}
