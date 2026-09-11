package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportScope
import java.util.UUID

/**
 * Canonical lock order step 1 (brief §9) for every workflow mutation: locks the report by
 * its Public-facing id and resolves its current [ReportScope] from that freshly-locked row
 * and its (immutable) routing snapshot. A missing report is reported identically to one the
 * actor cannot see — see [ReportNotVisibleException] — so this alone never distinguishes
 * "does not exist" from "not yet policy-checked".
 *
 * **Phase 9 addendum:** [isModerationDeleted] answers, from inside this same transaction
 * and after the report row is already locked, whether the report currently has an open
 * moderation episode — if so, this throws [ReportNotVisibleException] exactly like a
 * nonexistent report would (Phase 9 brief §12/§13: a moderation-hidden report is
 * existence-safe to every ordinary workflow mutation, never a distinguishable 404 variant).
 * A plain function parameter, not a concrete repository type, so this module stays
 * decoupled from `moderation.infrastructure` — every caller passes
 * `moderationRepository::hasOpenEpisode`; [hu.orszembejelento.backend.moderation.application.ModerationDeleteUseCase]
 * and [hu.orszembejelento.backend.moderation.application.ModerationRestoreUseCase] are the
 * only two callers allowed to see a currently-deleted report through this helper, and they
 * pass their own explicit re-check afterward (see those classes' own KDoc) rather than this
 * blanket rejection.
 */
internal fun lockAndResolve(
    reports: JdbcReportRepository,
    scopeResolver: ReportScopeResolver,
    isModerationDeleted: (UUID) -> Boolean,
    publicReportId: UUID,
): Pair<Report, ReportScope> {
    val locked = reports.lockByPublicId(publicReportId) ?: throw ReportNotVisibleException()
    if (isModerationDeleted(locked.id)) throw ReportNotVisibleException()
    val snapshot = reports.findRoutingSnapshot(locked.id)
        ?: error("report ${locked.publicId} has no routing snapshot - every report gets one at submission time")
    return locked to scopeResolver.resolve(locked, snapshot)
}
