package hu.orszembejelento.backend.analytics.application

import hu.orszembejelento.backend.analytics.domain.AnalyticsAreaNotAvailableException
import hu.orszembejelento.backend.analytics.domain.AnalyticsAreaOption
import hu.orszembejelento.backend.analytics.domain.AnalyticsAreaOptions
import hu.orszembejelento.backend.analytics.domain.AnalyticsCategoryNotFoundException
import hu.orszembejelento.backend.analytics.domain.AnalyticsFilter
import hu.orszembejelento.backend.analytics.domain.AnalyticsFilterInvalidException
import hu.orszembejelento.backend.analytics.domain.AnalyticsPeriod
import hu.orszembejelento.backend.analytics.domain.AnalyticsPeriodCalculator
import hu.orszembejelento.backend.analytics.domain.AnalyticsPeriodInvalidException
import hu.orszembejelento.backend.analytics.domain.AnalyticsSummary
import hu.orszembejelento.backend.analytics.domain.AnalyticsTrendPoint
import hu.orszembejelento.backend.analytics.domain.AnalyticsUnclassifiedForbiddenException
import hu.orszembejelento.backend.analytics.infrastructure.JdbcAnalyticsRepository
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.reports.infrastructure.JdbcEventCatalogRepository
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * Read-only analytics queries (Phase 11 brief). Reuses [ReportWorkflowActor] directly — no
 * second authorization model is invented (brief §7).
 */
@Service
class AnalyticsQueryUseCase(
    private val repository: JdbcAnalyticsRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
    private val eventCatalog: JdbcEventCatalogRepository,
    private val clock: Clock,
) {

    /** true for a global MODERATOR or SUPER_ADMIN (brief §7/§23) — never a territorial actor, never any SERVICE_USER. */
    private fun canViewUnclassified(actor: ReportWorkflowActor): Boolean =
        actor.role == UserRole.SUPER_ADMIN || (actor.role == UserRole.MODERATOR && actor.globalAreaAccess)

    /**
     * Whether [areaId] is a legal analytics-area choice for [actor] right now (brief §7/§22):
     * SUPER_ADMIN may pick any ServiceArea, active or inactive; every other role only an
     * ACTIVE area within its own current scope (own assigned areas for a territorial actor,
     * any active area for a global SERVICE_USER/MODERATOR).
     */
    private fun areaInScope(actor: ReportWorkflowActor, areaId: java.util.UUID): Boolean {
        val area = serviceAreas.findById(areaId) ?: return false
        if (actor.role == UserRole.SUPER_ADMIN) return true
        if (!area.isActive) return false
        if (actor.role == UserRole.MODERATOR && actor.globalAreaAccess) return true
        if (actor.role == UserRole.SERVICE_USER && actor.globalAreaAccess) return true
        return areaId in actor.ownActiveAreaIds
    }

    /**
     * One coherent snapshot (brief §13): all four aggregates run inside one short read-only
     * REPEATABLE READ transaction, so a concurrent workflow mutation can never be reflected
     * in some aggregates here and not others (e.g. `totalReports = 20` while status counts
     * only sum to 19). Only this aggregate-query block is inside the transaction — no
     * network/client work is ever done while it is held open.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun summary(actor: ReportWorkflowActor, periodCode: String?, rawFilter: RawAnalyticsFilter): AnalyticsSummary {
        val period = parsePeriod(periodCode)
        val filter = resolveFilter(actor, rawFilter)
        val range = AnalyticsPeriodCalculator.resolve(period, clock.instant())

        val statusCounts = repository.statusCounts(actor, range.from, range.to, filter)
        val dailyCounts = repository.dailyCounts(actor, range.from, range.to, filter)
        val trend = range.localDates.map { AnalyticsTrendPoint(it, dailyCounts[it] ?: 0) }
        val categories = repository.categoryCounts(actor, range.from, range.to, filter)
        val topEventTypes = repository.topEventTypes(actor, range.from, range.to, filter)

        return AnalyticsSummary(
            period = range,
            generatedAt = clock.instant(),
            totalReports = statusCounts.total,
            statusCounts = statusCounts,
            trend = trend,
            categories = categories,
            topEventTypes = topEventTypes,
        )
    }

    @Transactional(readOnly = true)
    fun areaOptions(actor: ReportWorkflowActor): AnalyticsAreaOptions {
        val areas = when {
            actor.role == UserRole.SUPER_ADMIN -> serviceAreas.findAll()
            actor.role == UserRole.MODERATOR && actor.globalAreaAccess -> serviceAreas.findAllActive()
            actor.role == UserRole.SERVICE_USER && actor.globalAreaAccess -> serviceAreas.findAllActive()
            else -> serviceAreas.findAll().filter { it.id in actor.ownActiveAreaIds }
        }
        return AnalyticsAreaOptions(
            areas = areas.map { AnalyticsAreaOption(it.id, it.name, it.isActive) },
            canViewUnclassified = canViewUnclassified(actor),
        )
    }

    // ------------------------------------------------------------------------------- private

    private fun parsePeriod(raw: String?): AnalyticsPeriod {
        if (raw == null) return AnalyticsPeriod.LAST_30_DAYS
        return runCatching { AnalyticsPeriod.valueOf(raw) }.getOrElse { throw AnalyticsPeriodInvalidException() }
    }

    private fun resolveFilter(actor: ReportWorkflowActor, raw: RawAnalyticsFilter): AnalyticsFilter {
        if (raw.areaId != null && raw.unclassifiedOnly) {
            throw AnalyticsFilterInvalidException("areaId and unclassifiedOnly are mutually exclusive.")
        }
        if (raw.unclassifiedOnly && !canViewUnclassified(actor)) {
            throw AnalyticsUnclassifiedForbiddenException()
        }
        raw.areaId?.let { if (!areaInScope(actor, it)) throw AnalyticsAreaNotAvailableException() }
        raw.categoryCode?.let { eventCatalog.findCategoryByCode(it) ?: throw AnalyticsCategoryNotFoundException() }

        return AnalyticsFilter(areaId = raw.areaId, unclassifiedOnly = raw.unclassifiedOnly, categoryCode = raw.categoryCode)
    }
}

/** The unvalidated request-level filter inputs, before [AnalyticsQueryUseCase] checks them against the actor's scope. */
data class RawAnalyticsFilter(val areaId: java.util.UUID? = null, val unclassifiedOnly: Boolean = false, val categoryCode: String? = null)
