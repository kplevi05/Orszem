package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportRoutingSnapshot
import hu.orszembejelento.backend.reports.domain.RoutingSnapshotStatus
import hu.orszembejelento.backend.reportworkflow.domain.ReportScope
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import org.springframework.stereotype.Component

/**
 * Builds the [ReportScope] every workflow mutation authorises against, from a just-locked
 * [Report] and its (immutable, brief §69 — never recomputed here) routing snapshot. Shared
 * by every mutation use case so "is this report's area currently ACTIVE" is answered
 * exactly once, not reimplemented per operation.
 */
@Component
class ReportScopeResolver(private val serviceAreas: JdbcServiceAreaRepository) {

    fun resolve(report: Report, snapshot: ReportRoutingSnapshot): ReportScope =
        if (snapshot.routingStatus == RoutingSnapshotStatus.UNCLASSIFIED) {
            ReportScope.unclassified(report.status)
        } else {
            val areaId = requireNotNull(snapshot.serviceAreaId) { "a ROUTED snapshot always names a service area" }
            val area = serviceAreas.findById(areaId)
            ReportScope.routed(
                status = report.status,
                serviceAreaId = areaId,
                serviceAreaActive = area?.status == ServiceAreaStatus.ACTIVE,
                assignedUserId = report.assignedUserId,
            )
        }
}
