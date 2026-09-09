package hu.orszembejelento.backend.reportworkflow.api

import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.reportworkflow.application.ClaimReportUseCase
import hu.orszembejelento.backend.reportworkflow.application.CloseReportUseCase
import hu.orszembejelento.backend.reportworkflow.application.ReassignReportUseCase
import hu.orszembejelento.backend.reportworkflow.application.ReportQueryUseCase
import hu.orszembejelento.backend.reportworkflow.application.ReportWorkflowActorLoader
import hu.orszembejelento.backend.reportworkflow.application.ReturnReportUseCase
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.infrastructure.ReportListFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Service report-workflow: the NEW/IN_PROGRESS/Archive queues, detail, and the four
 * explicit workflow operations (brief §18-45). Every endpoint requires an authenticated
 * Service session — the existing default-deny `SecurityConfig` already covers this, no new
 * security scheme is introduced, and no Public report-access credential is ever accepted
 * here (brief §75/§17).
 *
 * No generic status PATCH exists (brief §78) — only the explicit operations below, and
 * controllers hold no business logic: every authorisation and transition decision lives in
 * [hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy] or the use case
 * itself.
 */
@RestController
@RequestMapping("${ApiPaths.V1}/service/reports")
@Tag(name = "Service report workflow")
@SecurityRequirement(name = "bearerAuth")
class ReportWorkflowController(
    private val actorLoader: ReportWorkflowActorLoader,
    private val queries: ReportQueryUseCase,
    private val claimReport: ClaimReportUseCase,
    private val returnReport: ReturnReportUseCase,
    private val closeReport: CloseReportUseCase,
    private val reassignReport: ReassignReportUseCase,
) {

    @GetMapping("/new")
    @Operation(
        summary = "The NEW report queue",
        description = "RECENT (submitted within the last 168 hours, newest first) before OLDER " +
            "(submitted earlier, oldest first); `ageBucket` names which, the backend never renders " +
            "a divider. Scope-aware: a SERVICE_USER never sees UNCLASSIFIED; a territorial MODERATOR " +
            "never sees UNCLASSIFIED; a global MODERATOR or SUPER_ADMIN sees everything in scope.",
    )
    fun newQueue(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) categoryCode: String?,
        @RequestParam(required = false) eventTypeCode: String?,
        @RequestParam(required = false) settlementId: UUID?,
        @RequestParam(required = false) areaId: UUID?,
    ): ResponseEntity<ReportQueuePageResponse> {
        val actor = actorLoader.load(principal)
        val (boundedPage, boundedSize) = boundedPaging(page, size)
        val result = queries.newQueue(actor, filter(query, categoryCode, eventTypeCode, settlementId, areaId), boundedPage, boundedSize)
        return noStore(ReportQueuePageResponse.from(result))
    }

    @GetMapping("/in-progress")
    @Operation(
        summary = "The IN_PROGRESS report queue",
        description = "A SERVICE_USER sees only their own currently-authorized reports; " +
            "MODERATOR/SUPER_ADMIN see every visible IN_PROGRESS report in scope. Oldest submitted first.",
    )
    fun inProgressQueue(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) categoryCode: String?,
        @RequestParam(required = false) eventTypeCode: String?,
        @RequestParam(required = false) settlementId: UUID?,
        @RequestParam(required = false) areaId: UUID?,
        @RequestParam(required = false) assigneeServiceId: String?,
    ): ResponseEntity<ReportQueuePageResponse> {
        val actor = actorLoader.load(principal)
        val (boundedPage, boundedSize) = boundedPaging(page, size)
        val result = queries.inProgressQueue(
            actor,
            filter(query, categoryCode, eventTypeCode, settlementId, areaId, assigneeServiceId),
            boundedPage,
            boundedSize,
        )
        return noStore(ReportQueuePageResponse.from(result))
    }

    @GetMapping("/archive")
    @Operation(
        summary = "The Archive",
        description = "ARCHIVED reports within the actor's visibility scope, most recently archived first. Read-only: no reopen, no delete.",
    )
    fun archiveQueue(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) categoryCode: String?,
        @RequestParam(required = false) eventTypeCode: String?,
        @RequestParam(required = false) settlementId: UUID?,
        @RequestParam(required = false) areaId: UUID?,
    ): ResponseEntity<ReportQueuePageResponse> {
        val actor = actorLoader.load(principal)
        val (boundedPage, boundedSize) = boundedPaging(page, size)
        val result = queries.archiveQueue(actor, filter(query, categoryCode, eventTypeCode, settlementId, areaId), boundedPage, boundedSize)
        return noStore(ReportQueuePageResponse.from(result))
    }

    @GetMapping("/{publicReportId}")
    @Operation(
        summary = "One report's full workflow detail",
        description = "Returns 404 REPORT_NOT_FOUND identically for a nonexistent report and one outside the actor's scope.",
    )
    fun detail(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable publicReportId: UUID,
    ): ResponseEntity<ReportDetailResponse> {
        val actor = actorLoader.load(principal)
        return noStore(ReportDetailResponse.from(queries.detail(actor, publicReportId)))
    }

    @PostMapping("/{publicReportId}/claim")
    @Operation(
        summary = "Self-claim a NEW report",
        description = "SERVICE_USER only. 409 REPORT_ALREADY_ASSIGNED if another user claimed it first; " +
            "409 REPORT_STATE_CHANGED if expectedVersion is stale for another reason.",
    )
    fun claim(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable publicReportId: UUID,
        @Valid @RequestBody request: WorkflowMutationRequest,
    ): ResponseEntity<ReportDetailResponse> = mutate(principal, publicReportId) { actor ->
        claimReport.claim(actor, publicReportId, requireNotNull(request.expectedVersion))
    }

    @PostMapping("/{publicReportId}/return")
    @Operation(
        summary = "Return an IN_PROGRESS report to NEW",
        description = "A SERVICE_USER may return only their own report; MODERATOR/SUPER_ADMIN any visible one in scope.",
    )
    fun returnToNew(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable publicReportId: UUID,
        @Valid @RequestBody request: WorkflowMutationRequest,
    ): ResponseEntity<ReportDetailResponse> = mutate(principal, publicReportId) { actor ->
        returnReport.returnReport(actor, publicReportId, requireNotNull(request.expectedVersion))
    }

    @PostMapping("/{publicReportId}/close")
    @Operation(
        summary = "Close a report (NEW or IN_PROGRESS) to ARCHIVED",
        description = "A SERVICE_USER may close only their own IN_PROGRESS report, never an unclaimed NEW one. " +
            "MODERATOR/SUPER_ADMIN may close a visible NEW or IN_PROGRESS report, including UNCLASSIFIED for a global actor.",
    )
    fun close(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable publicReportId: UUID,
        @Valid @RequestBody request: WorkflowMutationRequest,
    ): ResponseEntity<ReportDetailResponse> = mutate(principal, publicReportId) { actor ->
        closeReport.close(actor, publicReportId, requireNotNull(request.expectedVersion))
    }

    @PostMapping("/{publicReportId}/reassign")
    @Operation(
        summary = "Reassign an IN_PROGRESS report to a different SERVICE_USER",
        description = "MODERATOR/SUPER_ADMIN only. The target must be ACTIVE, SERVICE_USER, and currently have " +
            "area access to this report's ServiceArea. 409 REPORT_UNCLASSIFIED_CANNOT_ASSIGN for an UNCLASSIFIED " +
            "report. Reassigning to the current assignee is an idempotent no-op.",
    )
    fun reassign(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable publicReportId: UUID,
        @Valid @RequestBody request: ReassignRequest,
    ): ResponseEntity<ReportDetailResponse> = mutate(principal, publicReportId) { actor ->
        reassignReport.reassign(actor, publicReportId, requireNotNull(request.expectedVersion), request.targetServiceId)
    }

    // ------------------------------------------------------------------------------ helpers

    /** Runs one workflow mutation, then re-reads the committed state through the same visibility-checked query path used everywhere else. */
    private fun mutate(
        principal: AuthenticatedActor,
        publicReportId: UUID,
        operation: (ReportWorkflowActor) -> Unit,
    ): ResponseEntity<ReportDetailResponse> {
        val actor = actorLoader.load(principal)
        operation(actor)
        return noStore(ReportDetailResponse.from(queries.detail(actor, publicReportId)))
    }

    private fun filter(
        query: String?,
        categoryCode: String?,
        eventTypeCode: String?,
        settlementId: UUID?,
        areaId: UUID?,
        assigneeServiceId: String? = null,
    ) = ReportListFilter(
        query = query?.takeIf { it.isNotBlank() },
        categoryCode = categoryCode,
        eventTypeCode = eventTypeCode,
        settlementId = settlementId,
        areaId = areaId,
        assigneeServiceId = assigneeServiceId,
    )

    private fun boundedPaging(page: Int, size: Int?): Pair<Int, Int> =
        page.coerceAtLeast(0) to (size ?: ReportQueryUseCase.DEFAULT_PAGE_SIZE).coerceIn(1, ReportQueryUseCase.MAX_PAGE_SIZE)

    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)
}
