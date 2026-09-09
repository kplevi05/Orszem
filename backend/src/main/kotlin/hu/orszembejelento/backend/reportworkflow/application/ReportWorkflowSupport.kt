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
 */
internal fun lockAndResolve(
    reports: JdbcReportRepository,
    scopeResolver: ReportScopeResolver,
    publicReportId: UUID,
): Pair<Report, ReportScope> {
    val locked = reports.lockByPublicId(publicReportId) ?: throw ReportNotVisibleException()
    val snapshot = reports.findRoutingSnapshot(locked.id)
        ?: error("report ${locked.publicId} has no routing snapshot - every report gets one at submission time")
    return locked to scopeResolver.resolve(locked, snapshot)
}
