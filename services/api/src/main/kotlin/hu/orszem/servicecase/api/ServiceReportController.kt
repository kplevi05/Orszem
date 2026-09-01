package hu.orszem.servicecase.api

import hu.orszem.auth.web.currentActor
import hu.orszem.reporting.domain.ReportStatus
import hu.orszem.servicecase.application.AcceptReportUseCase
import hu.orszem.servicecase.application.ArchiveReportUseCase
import hu.orszem.servicecase.application.GetReportDetailUseCase
import hu.orszem.servicecase.application.ListActiveReportsUseCase
import hu.orszem.servicecase.application.ListArchivedReportsUseCase
import hu.orszem.shared.error.ApiException
import hu.orszem.shared.error.ErrorCode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/service")
class ServiceReportController(
    private val listActive: ListActiveReportsUseCase,
    private val listArchived: ListArchivedReportsUseCase,
    private val getDetail: GetReportDetailUseCase,
    private val acceptReport: AcceptReportUseCase,
    private val archiveReport: ArchiveReportUseCase,
) {

    @GetMapping("/reports")
    fun activeReports(
        @RequestParam(name = "status", required = false) status: List<String>?,
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: Int?,
    ): ReportListResponse {
        val statuses = status.orEmpty().map { raw ->
            runCatching { ReportStatus.valueOf(raw) }.getOrElse {
                throw ApiException(ErrorCode.VALIDATION_ERROR, "Érvénytelen státuszszűrő: $raw")
            }
        }.toSet()
        return listActive.execute(statuses, cursor, limit).toResponse()
    }

    @GetMapping("/archive")
    fun archive(
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @RequestParam(name = "limit", required = false) limit: Int?,
    ): ReportListResponse = listArchived.execute(cursor, limit).toResponse()

    @GetMapping("/reports/{reportId}")
    fun reportDetail(@PathVariable reportId: UUID): ServiceReportDetailResponse =
        getDetail.execute(reportId).toResponse()

    @PostMapping("/reports/{reportId}/accept")
    fun accept(@PathVariable reportId: UUID): ServiceReportDetailResponse =
        acceptReport.execute(reportId, actorId()).toResponse()

    @PostMapping("/reports/{reportId}/archive")
    fun archive(@PathVariable reportId: UUID): ServiceReportDetailResponse =
        archiveReport.execute(reportId, actorId()).toResponse()

    private fun actorId(): UUID = currentActor()?.userId
        ?: throw ApiException(ErrorCode.UNAUTHORIZED, "Érvényes szolgálati bejelentkezés szükséges.")
}
