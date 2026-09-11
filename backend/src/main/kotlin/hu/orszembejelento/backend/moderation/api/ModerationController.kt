package hu.orszembejelento.backend.moderation.api

import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.moderation.application.ModerationDeleteUseCase
import hu.orszembejelento.backend.moderation.application.ModerationQueryUseCase
import hu.orszembejelento.backend.moderation.application.ModerationRestoreUseCase
import hu.orszembejelento.backend.moderation.domain.ModerationReason
import hu.orszembejelento.backend.moderation.infrastructure.DeletedReportListFilter
import hu.orszembejelento.backend.reportworkflow.application.ReportQueryUseCase
import hu.orszembejelento.backend.reportworkflow.application.ReportWorkflowActorLoader
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
 * Report moderation (Phase 9 brief §17): explicit delete/restore mutations plus the deleted
 * list/detail — no generic status PATCH, exactly like [hu.orszembejelento.backend.reportworkflow.api.ReportWorkflowController]'s
 * own KDoc explains for the ordinary workflow. Authenticated Service session only, covered
 * by the existing default-deny `SecurityConfig` — no new security scheme.
 */
@RestController
@RequestMapping("${ApiPaths.V1}/service/moderation")
@Tag(name = "Service report moderation")
@SecurityRequirement(name = "bearerAuth")
class ModerationController(
    private val actorLoader: ReportWorkflowActorLoader,
    private val deleteReport: ModerationDeleteUseCase,
    private val restoreReport: ModerationRestoreUseCase,
    private val queries: ModerationQueryUseCase,
) {

    @PostMapping("/reports/{publicReportId}/delete")
    @Operation(
        summary = "Moderation-delete a report (soft deletion)",
        description = "MODERATOR/SUPER_ADMIN only, within the actor's current moderation scope. The report row " +
            "and its assignment history are never destroyed. 409 REPORT_ALREADY_DELETED if already deleted.",
    )
    fun delete(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable publicReportId: UUID,
        @Valid @RequestBody request: ModerationDeleteRequest,
    ): ResponseEntity<Void> {
        val actor = actorLoader.load(principal)
        deleteReport.delete(actor, publicReportId, requireNotNull(request.expectedVersion), ModerationReason.valueOf(request.reason))
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    @PostMapping("/reports/{publicReportId}/restore")
    @Operation(
        summary = "Restore a moderation-deleted report",
        description = "SUPER_ADMIN only. A formerly IN_PROGRESS report restores to NEW, unassigned - its prior " +
            "assignment is never resurrected. 409 REPORT_NOT_DELETED if not currently deleted.",
    )
    fun restore(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable publicReportId: UUID,
        @Valid @RequestBody request: ModerationRestoreRequest,
    ): ResponseEntity<Void> {
        val actor = actorLoader.load(principal)
        restoreReport.restore(actor, publicReportId, requireNotNull(request.expectedVersion))
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    @GetMapping("/deleted")
    @Operation(
        summary = "The deleted-report list",
        description = "Every currently moderation-deleted report within the actor's moderation scope (brief §21: " +
            "area-based, never restricted to reports the caller personally deleted), deletedAt DESC.",
    )
    fun deletedList(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) reason: String?,
        @RequestParam(required = false) areaId: UUID?,
    ): ResponseEntity<DeletedReportPageResponse> {
        val actor = actorLoader.load(principal)
        val (boundedPage, boundedSize) = boundedPaging(page, size)
        val filter = DeletedReportListFilter(
            query = query?.takeIf { it.isNotBlank() },
            reason = reason?.let { runCatching { ModerationReason.valueOf(it) }.getOrNull() },
            areaId = areaId,
        )
        val result = queries.deletedList(actor, filter, boundedPage, boundedSize)
        return noStore(DeletedReportPageResponse.from(result, boundedPage, boundedSize))
    }

    @GetMapping("/deleted/{publicReportId}")
    @Operation(
        summary = "One deleted report's full moderation detail",
        description = "Returns 404 REPORT_NOT_FOUND identically for a nonexistent report, one not currently " +
            "deleted, and one outside the actor's moderation scope.",
    )
    fun deletedDetail(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable publicReportId: UUID,
    ): ResponseEntity<DeletedReportDetailResponse> {
        val actor = actorLoader.load(principal)
        return noStore(DeletedReportDetailResponse.from(queries.deletedDetail(actor, publicReportId)))
    }

    private fun boundedPaging(page: Int, size: Int?): Pair<Int, Int> =
        page.coerceAtLeast(0) to (size ?: ReportQueryUseCase.DEFAULT_PAGE_SIZE).coerceIn(1, ReportQueryUseCase.MAX_PAGE_SIZE)

    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)
}
