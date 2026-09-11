package hu.orszembejelento.backend.reports.api

import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.reports.application.GetPublicReportUseCase
import hu.orszembejelento.backend.reports.application.PublicReportDetail
import hu.orszembejelento.backend.reports.application.SubmitReportCommand
import hu.orszembejelento.backend.reports.application.SubmitReportOutcome
import hu.orszembejelento.backend.reports.application.SubmitReportUseCase
import hu.orszembejelento.backend.reports.domain.Report
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The custom capability header every Public report request needs - see [PublicReportController]. */
private const val REPORT_ACCESS_HEADER = "X-Orszem-Report-Access"

/**
 * An unrecognised field (free text, GPS coordinates, a client-supplied `categoryCode`,
 * anything else this DTO does not declare) is rejected outright rather than silently
 * discarded - see [SubmitReportBodyAdvice], which enforces this. `@JsonIgnoreProperties
 * (ignoreUnknown = false)` alone does not achieve this on this Jackson version: the
 * per-class annotation does not override the application's default, globally-lenient
 * `FAIL_ON_UNKNOWN_PROPERTIES` setting - confirmed by testing, not assumed - so strictness
 * is enforced explicitly instead, scoped to this one endpoint, with no change to the
 * shared, globally-lenient Jackson configuration every other endpoint relies on (§13).
 */
data class SubmitReportRequest(
    @field:NotNull
    val clientSubmissionId: UUID?,
    @field:NotNull
    val occurredAt: Instant?,
    @field:Size(max = 256)
    val trainIdentifier: String?,
    @field:NotNull
    val settlementId: UUID?,
    val railwayLineId: UUID?,
    @field:NotBlank
    val eventTypeCode: String?,
)

/** The stable, immutable creation receipt - never the current (mutable) status. See §24. */
data class SubmitReportResponse(
    val reportId: UUID,
    val submittedAt: Instant,
    val initialStatus: String,
)

data class SettlementSummary(val id: UUID, val name: String)
data class CategorySummary(val code: String, val displayName: String)
data class EventTypeSummary(val code: String, val displayName: String)

data class PublicReportResponse(
    val reportId: UUID,
    val occurredAt: Instant,
    val submittedAt: Instant,
    val trainIdentifier: String?,
    val settlement: SettlementSummary,
    val category: CategorySummary,
    val eventType: EventTypeSummary,
    val status: String,
)

/**
 * Anonymous report submission and lookup (ADR 0008). No authentication, no Public account -
 * access to a specific report is the `X-Orszem-Report-Access` header alone.
 *
 * Never returns the internal report id, `ServiceArea` information, the internal `status`
 * vocabulary, or the routing decision - see [PublicReportResponse] and
 * `GetPublicReportUseCase`.
 */
@RestController
@RequestMapping("${ApiPaths.V1}/public/reports")
@Tag(name = "Public reports", description = "Anonymous report submission and status lookup")
class PublicReportController(
    private val submitReport: SubmitReportUseCase,
    private val getPublicReport: GetPublicReportUseCase,
) {

    @PostMapping
    @Operation(
        summary = "Submit a report anonymously",
        description = "Requires the client-generated `X-Orszem-Report-Access` credential header on " +
            "every request, including this one. `clientSubmissionId` is the idempotency key: " +
            "repeating the identical request with the same id and credential returns the same " +
            "report (200); the same id with a different payload or credential is refused (409).",
    )
    @ApiResponse(responseCode = "201", description = "A new report was created.")
    @ApiResponse(responseCode = "200", description = "An identical prior submission was replayed.")
    @ApiResponse(responseCode = "400", description = "INVALID_REPORT_ACCESS_CREDENTIAL, INVALID_SETTLEMENT, INVALID_EVENT_TYPE, INVALID_RAILWAY_LINE, or VALIDATION_ERROR.")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_REUSED.")
    @ApiResponse(responseCode = "503", description = "REFERENCE_DATASET_UNAVAILABLE - no report was created.")
    fun submit(
        @Valid @RequestBody request: SubmitReportRequest,
        @Parameter(
            description = "Client-generated report access credential, format `pr_<43 URL-safe base64 characters>` " +
                "(the unpadded encoding of 256 random bits). Bind this same value to `clientSubmissionId` " +
                "for the life of this report - every retry and later lookup must resend it.",
            required = true,
        )
        @RequestHeader(value = REPORT_ACCESS_HEADER, required = false) accessCredential: String?,
    ): ResponseEntity<SubmitReportResponse> {
        val command = SubmitReportCommand(
            clientSubmissionId = request.clientSubmissionId!!,
            occurredAt = request.occurredAt!!,
            trainIdentifier = request.trainIdentifier,
            settlementId = request.settlementId!!,
            railwayLineId = request.railwayLineId,
            eventTypeCode = request.eventTypeCode!!,
        )
        return when (val outcome = submitReport.submit(command, accessCredential)) {
            is SubmitReportOutcome.Created -> receipt(outcome.report, HttpStatus.CREATED, withLocation = true)
            is SubmitReportOutcome.Replayed -> receipt(outcome.report, HttpStatus.OK, withLocation = false)
        }
    }

    @GetMapping("/{publicReportId}")
    @Operation(
        summary = "Look up a report's current status",
        description = "Requires the `X-Orszem-Report-Access` credential header bound to this report " +
            "at submission time. An unknown id, a missing, malformed, or wrong credential are all " +
            "reported identically as 404 REPORT_NOT_FOUND, so report existence is never disclosed.",
    )
    @ApiResponse(responseCode = "200", description = "The report's current Public detail.")
    @ApiResponse(responseCode = "404", description = "REPORT_NOT_FOUND.")
    fun get(
        @PathVariable publicReportId: UUID,
        @Parameter(description = "The same report access credential bound to this report at submission time.", required = true)
        @RequestHeader(value = REPORT_ACCESS_HEADER, required = false) accessCredential: String?,
    ): ResponseEntity<PublicReportResponse> {
        val detail = getPublicReport.get(publicReportId, accessCredential)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(detail.toResponse())
    }

    private fun receipt(report: Report, status: HttpStatus, withLocation: Boolean): ResponseEntity<SubmitReportResponse> {
        val body = SubmitReportResponse(
            reportId = report.publicId,
            submittedAt = report.submittedAt,
            // Always "RECEIVED": only ever NEW reports are created in Phase 4, and NEW maps
            // to RECEIVED - see ReportStatus.toPublic. A stable literal here, not a lookup,
            // is deliberate: a replay must always render the identical receipt.
            initialStatus = "RECEIVED",
        )
        val builder = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
        if (withLocation) {
            builder.location(URI.create("${ApiPaths.V1}/public/reports/${report.publicId}"))
        }
        return builder.body(body)
    }

    // Phase 9: `publicStatus` already folds in the moderation override (see
    // PublicReportDetail's own KDoc) - nothing here ever renders `report.status` directly,
    // and nothing about deletion (reason/actor/timestamp/moderation state) is ever put on
    // this response at all (brief §14/§54).
    private fun PublicReportDetail.toResponse() = PublicReportResponse(
        reportId = report.publicId,
        occurredAt = report.occurredAt,
        submittedAt = report.submittedAt,
        trainIdentifier = report.trainIdentifier,
        settlement = SettlementSummary(report.settlementId, settlementName),
        category = CategorySummary(category.code, category.displayName),
        eventType = EventTypeSummary(eventType.code, eventType.displayName),
        status = publicStatus.name,
    )
}
