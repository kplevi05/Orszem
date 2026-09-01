package hu.orszem.publicapp.data

import hu.orszem.core.common.DispatcherProvider
import hu.orszem.core.common.Outcome
import hu.orszem.core.common.map
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.SubmittedReport
import hu.orszem.core.network.OrszemApi
import hu.orszem.core.network.dto.PublicReportCreateRequestDto
import hu.orszem.core.network.safeApiCall
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class ReportRepository @Inject constructor(
    private val api: OrszemApi,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * Creates a report. The client generates the UUID up front so a retry after a
     * network failure is idempotent (BUSINESS_RULES §3). Reuse [reportId] across retries.
     */
    suspend fun submit(
        reportId: UUID,
        eventTypeCode: String,
        trainIdentifier: String,
        settlement: String,
        occurredAt: Instant,
    ): Outcome<SubmittedReport> = withContext(dispatchers.io) {
        val request = PublicReportCreateRequestDto(
            id = reportId.toString(),
            eventTypeCode = eventTypeCode,
            trainIdentifier = trainIdentifier,
            settlement = settlement,
            occurredAt = occurredAt.toString(),
        )
        safeApiCall { api.createReport(request) }.map {
            SubmittedReport(
                id = it.id,
                status = runCatching { ReportStatus.valueOf(it.status) }.getOrDefault(ReportStatus.NEW),
                receivedAt = Instant.parse(it.receivedAt),
            )
        }
    }
}
