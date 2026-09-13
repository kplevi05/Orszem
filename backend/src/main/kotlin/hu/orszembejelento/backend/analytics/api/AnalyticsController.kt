package hu.orszembejelento.backend.analytics.api

import hu.orszembejelento.backend.analytics.application.AnalyticsQueryUseCase
import hu.orszembejelento.backend.analytics.application.RawAnalyticsFilter
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.reportworkflow.application.ReportWorkflowActorLoader
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Descriptive operational analytics (Phase 11 brief) — available to every authenticated
 * Service role (SERVICE_USER, MODERATOR, SUPER_ADMIN), scoped in SQL by
 * [ReportWorkflowActorLoader]'s [hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor],
 * the exact same actor the report-workflow queues already load — no second authorization
 * model (brief §7). No public endpoint, no generic SQL/query endpoint (brief §14). Every
 * response is `no-store` (brief §31): this is authenticated operational information, never
 * cached client-side.
 */
@RestController
@RequestMapping("${ApiPaths.V1}/service/analytics")
@Tag(name = "Service analytics")
@SecurityRequirement(name = "bearerAuth")
class AnalyticsController(
    private val actorLoader: ReportWorkflowActorLoader,
    private val queries: AnalyticsQueryUseCase,
) {

    @GetMapping("/summary")
    @Operation(
        summary = "Descriptive analytics summary for a fixed period",
        description = "period defaults to LAST_30_DAYS. areaId and unclassifiedOnly=true are mutually exclusive. " +
            "Both area and unclassifiedOnly are validated against the actor's current analytics scope (brief §7/§22).",
    )
    fun summary(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(required = false) period: String?,
        @RequestParam(required = false) areaId: UUID?,
        @RequestParam(required = false, defaultValue = "false") unclassifiedOnly: Boolean,
        @RequestParam(required = false) categoryCode: String?,
    ): ResponseEntity<AnalyticsSummaryResponse> {
        val actor = actorLoader.load(principal)
        val filter = RawAnalyticsFilter(areaId = areaId, unclassifiedOnly = unclassifiedOnly, categoryCode = categoryCode)
        return noStore(AnalyticsSummaryResponse.from(queries.summary(actor, period, filter)))
    }

    @GetMapping("/areas")
    @Operation(
        summary = "Legal analytics-area filter choices for the current actor",
        description = "Never a fake `Besorolatlan`/`Minden terület` ServiceArea row - see canViewUnclassified instead (brief §21).",
    )
    fun areas(@AuthenticationPrincipal principal: AuthenticatedActor): ResponseEntity<AnalyticsAreaOptionsResponse> {
        val actor = actorLoader.load(principal)
        return noStore(AnalyticsAreaOptionsResponse.from(queries.areaOptions(actor)))
    }

    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)
}
