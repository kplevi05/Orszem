package hu.orszembejelento.backend.moderation.domain

import hu.orszembejelento.backend.reports.domain.ReportStatus
import java.time.Instant
import java.util.UUID

/**
 * The exact, frozen deletion-reason vocabulary (Phase 9 brief §4) - stable backend codes
 * only, never free text, never a "note". `OTHER` is enum-only, exactly like every other
 * value: it carries no accompanying explanation field.
 */
enum class ModerationReason { SPAM, TROLL_OR_FALSE_REPORT, DUPLICATE, INCORRECT, IRRELEVANT, OTHER }

/**
 * One moderation episode (brief §6) - "was this report ever moderation-deleted, and is it
 * currently deleted", a deliberately different question from [hu.orszembejelento.backend.reportworkflow.domain.ReportAssignment]
 * (operational ownership) and from `audit_events` (who performed a mutation). [restoredAt]
 * and [restoredByUserId] are null together for a still-open episode - mirrors
 * [hu.orszembejelento.backend.reportworkflow.domain.ReportAssignment]'s identical
 * ended-fields coherence shape.
 */
data class ModerationEpisode(
    val id: UUID,
    val reportId: UUID,
    val reason: ModerationReason,
    val deletedByUserId: UUID,
    val deletedAt: Instant,
    val statusBeforeDelete: ReportStatus,
    val restoredByUserId: UUID?,
    val restoredAt: Instant?,
) {
    val isOpen: Boolean get() = restoredAt == null

    /**
     * The workflow status a restore returns this report to (brief §9, FROZEN): NEW and
     * IN_PROGRESS both restore to NEW - a deleted IN_PROGRESS report's prior assignment was
     * deliberately terminated at delete time and is never resurrected - ARCHIVED restores
     * to ARCHIVED.
     */
    val restoreTargetStatus: ReportStatus
        get() = if (statusBeforeDelete == ReportStatus.ARCHIVED) ReportStatus.ARCHIVED else ReportStatus.NEW
}
