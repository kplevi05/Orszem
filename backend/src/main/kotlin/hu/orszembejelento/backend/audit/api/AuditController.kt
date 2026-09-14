package hu.orszembejelento.backend.audit.api

import hu.orszembejelento.backend.audit.application.AuditQueryUseCase
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.common.web.ApiPaths
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `Változási előzmények` (Phase 12 brief): a read-only query surface over the existing
 * immutable `audit_events` trail. SUPER_ADMIN only, enforced by [AuditQueryUseCase] delegating
 * to `AuditActorLoader` before any query runs - never by Android role visibility (brief §2).
 *
 * There is deliberately no write endpoint here, and none anywhere in this controller's package
 * - the audit trail stays immutable by construction (brief §1), not merely by convention.
 * Reading it never itself writes a row (brief §3): every method below is a plain query.
 */
@RestController
@RequestMapping("${ApiPaths.V1}/service/audit")
@Tag(name = "Audit history")
@SecurityRequirement(name = "bearerAuth")
class AuditController(private val queries: AuditQueryUseCase) {

    @GetMapping("/events")
    @Operation(
        summary = "List audit events",
        description = "SUPER_ADMIN only. Server-side paginated/filtered/searched, newest first. Default period LAST_30_DAYS, default size 50, max 100.",
    )
    fun listEvents(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(required = false) period: String?,
        @RequestParam(required = false) eventType: String?,
        @RequestParam(required = false) targetType: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<AuditListPageResponse> {
        val (boundedPage, boundedSize) = boundedPaging(page, size)
        val result = queries.list(principal, period, eventType, targetType, query, boundedPage, boundedSize)
        return noStore(AuditListPageResponse.from(result, boundedPage, boundedSize))
    }

    @GetMapping("/events/{auditEventId}")
    @Operation(summary = "One audit event's safe detail", description = "SUPER_ADMIN only. 404 AUDIT_EVENT_NOT_FOUND if it does not exist.")
    fun eventDetail(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable auditEventId: UUID,
    ): ResponseEntity<AuditEventDetailResponse> =
        noStore(AuditEventDetailResponse.from(queries.detail(principal, auditEventId)))

    @GetMapping("/options")
    @Operation(summary = "The actual current event/target type codes", description = "SUPER_ADMIN only. Android maps every code to a Hungarian label - never rendered raw.")
    fun options(@AuthenticationPrincipal principal: AuthenticatedActor): ResponseEntity<AuditOptionsResponse> =
        noStore(AuditOptionsResponse.from(queries.options(principal)))

    private fun boundedPaging(page: Int, size: Int?): Pair<Int, Int> =
        page.coerceAtLeast(0) to (size ?: AuditQueryUseCase.DEFAULT_PAGE_SIZE).coerceIn(1, AuditQueryUseCase.MAX_PAGE_SIZE)

    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)
}
