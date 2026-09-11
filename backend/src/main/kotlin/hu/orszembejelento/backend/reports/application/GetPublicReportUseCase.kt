package hu.orszembejelento.backend.reports.application

import hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationRepository
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.reports.domain.PublicReportAccessCredential
import hu.orszembejelento.backend.reports.domain.PublicReportAccessCredentialHasher
import hu.orszembejelento.backend.reports.domain.PublicReportStatus
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportCategory
import hu.orszembejelento.backend.reports.domain.ReportEventType
import hu.orszembejelento.backend.reports.domain.ReportNotFoundException
import hu.orszembejelento.backend.reports.domain.toPublic
import hu.orszembejelento.backend.reports.infrastructure.JdbcEventCatalogRepository
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Everything the Public report-detail response needs, already resolved.
 *
 * [publicStatus] is the one place the Phase 9 moderation override lives (brief §14, FROZEN):
 * while a report currently has an open moderation episode, this is always `CLOSED`,
 * regardless of the underlying [Report.status] - `NEW`/`IN_PROGRESS`/`ARCHIVED` all collapse
 * to the same Public value, exactly like [hu.orszembejelento.backend.reports.domain.toPublic]'s
 * own KDoc already promised. Nothing about *why* or *when* it was deleted is ever computed
 * here, let alone exposed - see [hu.orszembejelento.backend.reports.api.PublicReportController].
 */
data class PublicReportDetail(
    val report: Report,
    val settlementName: String,
    val category: ReportCategory,
    val eventType: ReportEventType,
    val currentlyModerationDeleted: Boolean,
) {
    val publicStatus: PublicReportStatus
        get() = if (currentlyModerationDeleted) PublicReportStatus.CLOSED else report.status.toPublic()
}

/**
 * Looks up one Public report by its public id and access credential (ADR 0008).
 *
 * Deliberately never calls `RoutingService` and never requires a current reference state:
 * an already-created report must stay readable even if the dataset later becomes
 * unavailable, or the settlement/event type it references is later deactivated (§28,
 * ADR 0008) - the settlement/category/event-type lookups here are the unfiltered ones,
 * exactly so a historical reference resolves regardless of current `active` status.
 *
 * Unknown public id, missing credential, malformed credential, and wrong credential are
 * all [ReportNotFoundException] - deliberately the same outcome, so report existence can
 * never be inferred from which of the four actually happened.
 */
@Service
class GetPublicReportUseCase(
    private val reportRepository: JdbcReportRepository,
    private val eventCatalogRepository: JdbcEventCatalogRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val moderation: JdbcModerationRepository,
) {

    @Transactional(readOnly = true)
    fun get(publicReportId: UUID, suppliedCredentialHeader: String?): PublicReportDetail {
        val credential = PublicReportAccessCredential.parseOrNull(suppliedCredentialHeader)
            ?: throw ReportNotFoundException()

        val report = reportRepository.findByPublicId(publicReportId)
            ?: throw ReportNotFoundException()

        if (!PublicReportAccessCredentialHasher.matches(credential, report.publicAccessCredentialHash)) {
            throw ReportNotFoundException()
        }

        // Unfiltered by design (see class KDoc): a historical report's settlement,
        // category or event type may since have been deactivated, and must still resolve.
        val settlement = referenceRepository.findSettlementById(report.settlementId)
            ?: error("report ${report.publicId} references settlement ${report.settlementId}, which no longer exists")
        val eventType = eventCatalogRepository.findEventTypeByCode(report.eventTypeCode)
            ?: error("report ${report.publicId} references event type ${report.eventTypeCode}, which no longer exists")
        val category = eventCatalogRepository.findCategoryByCode(eventType.categoryCode)
            ?: error("event type ${eventType.code} references category ${eventType.categoryCode}, which no longer exists")

        val currentlyDeleted = moderation.hasOpenEpisode(report.id)
        return PublicReportDetail(report, settlement.name, category, eventType, currentlyDeleted)
    }
}
