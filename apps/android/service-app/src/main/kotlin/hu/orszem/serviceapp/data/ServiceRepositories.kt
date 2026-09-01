package hu.orszem.serviceapp.data

import hu.orszem.core.common.DispatcherProvider
import hu.orszem.core.common.Outcome
import hu.orszem.core.common.map
import hu.orszem.core.model.AnalyticsSummary
import hu.orszem.core.model.CategoryStat
import hu.orszem.core.model.EventTypeStat
import hu.orszem.core.model.ReportPage
import hu.orszem.core.model.ServiceProfile
import hu.orszem.core.model.ServiceCapability
import hu.orszem.core.model.ServiceReportDetail
import hu.orszem.core.model.ServiceReportSummary
import hu.orszem.core.model.SettlementStat
import hu.orszem.core.model.TrainStat
import hu.orszem.core.network.OrszemApi
import hu.orszem.core.network.dto.ServiceLoginRequestDto
import hu.orszem.core.network.safeApiCall
import hu.orszem.core.network.safeEmptyApiCall
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

class AuthRepository @Inject constructor(
    private val api: OrszemApi,
    private val sessionManager: SessionManager,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun login(username: String, password: String): Outcome<Unit> = withContext(dispatchers.io) {
        when (val result = safeApiCall { api.login(ServiceLoginRequestDto(username.trim(), password)) }) {
            is Outcome.Success -> {
                sessionManager.onLoggedIn(result.value.accessToken, Instant.parse(result.value.expiresAt))
                Outcome.Success(Unit)
            }
            is Outcome.Failure -> result
        }
    }

    suspend fun profile(): Outcome<ServiceProfile> = withContext(dispatchers.io) {
        safeApiCall { api.me() }.map {
            ServiceProfile(
                id = it.id,
                username = it.username,
                displayName = it.displayName,
                role = it.role,
                capabilities = it.capabilities.mapNotNull { c -> runCatching { ServiceCapability.valueOf(c) }.getOrNull() }.toSet(),
            )
        }
    }

    suspend fun logout() = sessionManager.logout()
}

class ServiceReportRepository @Inject constructor(
    private val api: OrszemApi,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun activeReports(cursor: String? = null, limit: Int? = null): Outcome<ReportPage> =
        withContext(dispatchers.io) {
            safeApiCall { api.activeReports(status = "NEW,IN_PROGRESS", cursor = cursor, limit = limit) }.map { it.toModel() }
        }

    suspend fun archivedReports(cursor: String? = null, limit: Int? = null): Outcome<ReportPage> =
        withContext(dispatchers.io) {
            safeApiCall { api.archivedReports(cursor = cursor, limit = limit) }.map { it.toModel() }
        }

    suspend fun detail(reportId: String): Outcome<ServiceReportDetail> = withContext(dispatchers.io) {
        safeApiCall { api.reportDetail(reportId) }.map { it.toModel() }
    }

    suspend fun accept(reportId: String): Outcome<ServiceReportDetail> = withContext(dispatchers.io) {
        safeApiCall { api.acceptReport(reportId) }.map { it.toModel() }
    }

    suspend fun archive(reportId: String): Outcome<ServiceReportDetail> = withContext(dispatchers.io) {
        safeApiCall { api.archiveReport(reportId) }.map { it.toModel() }
    }

    /**
     * Demo v1.1 pilot/test-data cleanup. Permanent; the backend additionally
     * refuses this unless the deployment enables demo deletion, so a 404 here can
     * also mean "not enabled in this environment".
     */
    suspend fun delete(reportId: String): Outcome<Unit> = withContext(dispatchers.io) {
        safeEmptyApiCall { api.deleteReport(reportId) }
    }

    /**
     * Loads the COMPLETE active dataset by following the existing opaque cursor
     * until it is exhausted (Demo v1.1 §C/§D). The backend paging contract and its
     * default ordering are untouched; the client simply consumes every page.
     *
     * [pageLimit] is the contract maximum so the number of round trips stays low,
     * and [maxPages] is a safety stop so a misbehaving cursor cannot loop forever.
     */
    suspend fun allActiveReports(pageLimit: Int = 100, maxPages: Int = 50): Outcome<List<ServiceReportSummary>> =
        withContext(dispatchers.io) {
            val collected = mutableListOf<ServiceReportSummary>()
            val seen = mutableSetOf<String>()
            var cursor: String? = null
            var pages = 0
            while (true) {
                when (val outcome = activeReports(cursor = cursor, limit = pageLimit)) {
                    is Outcome.Failure -> return@withContext outcome
                    is Outcome.Success -> {
                        // Defensive de-duplication: a report accepted between two
                        // page fetches can otherwise appear twice.
                        outcome.value.items.forEach { if (seen.add(it.id)) collected += it }
                        cursor = outcome.value.nextCursor
                        pages++
                        if (cursor == null || pages >= maxPages) {
                            return@withContext Outcome.Success(collected.toList())
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Outcome.Success(collected.toList())
        }
}

data class Analytics(
    val summary: AnalyticsSummary,
    val eventTypes: List<EventTypeStat>,
    val categories: List<CategoryStat>,
    val settlements: List<SettlementStat>,
    val trains: List<TrainStat>,
)

class AnalyticsRepository @Inject constructor(
    private val api: OrszemApi,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun load(): Outcome<Analytics> = withContext(dispatchers.io) {
        val summary = safeApiCall { api.analyticsSummary() }
        if (summary is Outcome.Failure) return@withContext summary
        val eventTypes = safeApiCall { api.analyticsEventTypes() }
        if (eventTypes is Outcome.Failure) return@withContext eventTypes
        val categories = safeApiCall { api.analyticsCategories() }
        if (categories is Outcome.Failure) return@withContext categories
        val settlements = safeApiCall { api.analyticsSettlements() }
        if (settlements is Outcome.Failure) return@withContext settlements
        val trains = safeApiCall { api.analyticsTrains() }
        if (trains is Outcome.Failure) return@withContext trains

        Outcome.Success(
            Analytics(
                summary = (summary as Outcome.Success).value.toModel(),
                eventTypes = (eventTypes as Outcome.Success).value.toModel(),
                categories = (categories as Outcome.Success).value.toModel(),
                settlements = (settlements as Outcome.Success).value.toModel(),
                trains = (trains as Outcome.Success).value.toModel(),
            ),
        )
    }
}
