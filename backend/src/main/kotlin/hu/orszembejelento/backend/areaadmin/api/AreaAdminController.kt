package hu.orszembejelento.backend.areaadmin.api

import hu.orszembejelento.backend.areaadmin.application.ActivateServiceAreaUseCase
import hu.orszembejelento.backend.areaadmin.application.AreaAdminActorLoader
import hu.orszembejelento.backend.areaadmin.application.AreaAdminQueryUseCase
import hu.orszembejelento.backend.areaadmin.application.AssignRailwayLineUseCase
import hu.orszembejelento.backend.areaadmin.application.CreateServiceAreaUseCase
import hu.orszembejelento.backend.areaadmin.application.DeactivateServiceAreaUseCase
import hu.orszembejelento.backend.areaadmin.application.RenameServiceAreaUseCase
import hu.orszembejelento.backend.areaadmin.application.UnassignRailwayLineUseCase
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListFilter
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAssignmentFilter
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListFilter
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.common.web.ApiPaths
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
 * ServiceArea administration (Phase 10 brief): SUPER_ADMIN only, enforced by
 * [AreaAdminActorLoader] before any use case runs - never by Android role visibility (brief
 * §4/§43). Configures only *current* routing configuration (`service_areas` and
 * `service_area_railway_lines`); it never rewrites a report's routing snapshot, never edits
 * `user_service_areas`, and never touches RailwayLine reference identity (brief §1/§17/§34).
 */
@RestController
@RequestMapping("${ApiPaths.V1}/service/service-area-admin")
@Tag(name = "Service area administration")
@SecurityRequirement(name = "bearerAuth")
class AreaAdminController(
    private val actorLoader: AreaAdminActorLoader,
    private val queries: AreaAdminQueryUseCase,
    private val createArea: CreateServiceAreaUseCase,
    private val renameArea: RenameServiceAreaUseCase,
    private val activateArea: ActivateServiceAreaUseCase,
    private val deactivateArea: DeactivateServiceAreaUseCase,
    private val assignLine: AssignRailwayLineUseCase,
    private val unassignLine: UnassignRailwayLineUseCase,
) {

    @GetMapping("/areas")
    @Operation(summary = "List service areas", description = "SUPER_ADMIN only. Server-side paginated, active-first then name-ordered.")
    fun listAreas(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) active: Boolean?,
    ): ResponseEntity<ServiceAreaAdminListPageResponse> {
        actorLoader.load(principal)
        val (boundedPage, boundedSize) = boundedPaging(page, size)
        val filter = ServiceAreaAdminListFilter(query = query?.takeIf { it.isNotBlank() }, active = active)
        return noStore(ServiceAreaAdminListPageResponse.from(queries.areaList(filter, boundedPage, boundedSize), boundedPage, boundedSize))
    }

    @GetMapping("/areas/{areaId}")
    @Operation(summary = "One service area's admin detail", description = "SUPER_ADMIN only. 404 SERVICE_AREA_NOT_FOUND if it does not exist.")
    fun areaDetail(@AuthenticationPrincipal principal: AuthenticatedActor, @PathVariable areaId: UUID): ResponseEntity<ServiceAreaAdminDetailResponse> {
        actorLoader.load(principal)
        return noStore(ServiceAreaAdminDetailResponse.from(queries.areaDetail(areaId)))
    }

    @PostMapping("/areas")
    @Operation(summary = "Create a service area", description = "SUPER_ADMIN only. Always ACTIVE, no railway lines, adminVersion 0.")
    fun createArea(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @Valid @RequestBody request: CreateServiceAreaRequest,
    ): ResponseEntity<ServiceAreaAdminResponse> {
        val actor = actorLoader.load(principal)
        return noStore(ServiceAreaAdminResponse.from(createArea.create(actor, request.name)))
    }

    @PostMapping("/areas/{areaId}/rename")
    @Operation(summary = "Rename a service area", description = "SUPER_ADMIN only. Display/configuration only - never reroutes reports or touches assignments.")
    fun renameArea(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable areaId: UUID,
        @Valid @RequestBody request: RenameServiceAreaRequest,
    ): ResponseEntity<ServiceAreaAdminResponse> {
        val actor = actorLoader.load(principal)
        val renamed = renameArea.rename(actor, areaId, requireNotNull(request.expectedVersion), request.name)
        return noStore(ServiceAreaAdminResponse.from(renamed))
    }

    @PostMapping("/areas/{areaId}/activate")
    @Operation(summary = "Activate a service area", description = "SUPER_ADMIN only. 409 SERVICE_AREA_ALREADY_ACTIVE if already active.")
    fun activateArea(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable areaId: UUID,
        @Valid @RequestBody request: ServiceAreaVersionedRequest,
    ): ResponseEntity<ServiceAreaAdminResponse> {
        val actor = actorLoader.load(principal)
        return noStore(ServiceAreaAdminResponse.from(activateArea.activate(actor, areaId, requireNotNull(request.expectedVersion))))
    }

    @PostMapping("/areas/{areaId}/deactivate")
    @Operation(
        summary = "Deactivate a service area",
        description = "SUPER_ADMIN only. 409 SERVICE_AREA_HAS_RAILWAY_LINES or SERVICE_AREA_HAS_OPEN_REPORTS if not yet eligible.",
    )
    fun deactivateArea(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable areaId: UUID,
        @Valid @RequestBody request: ServiceAreaVersionedRequest,
    ): ResponseEntity<ServiceAreaAdminResponse> {
        val actor = actorLoader.load(principal)
        return noStore(ServiceAreaAdminResponse.from(deactivateArea.deactivate(actor, areaId, requireNotNull(request.expectedVersion))))
    }

    @GetMapping("/railway-lines")
    @Operation(summary = "List/search railway lines for administration", description = "SUPER_ADMIN only. Server-side paginated.")
    fun listRailwayLines(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) serviceAreaId: UUID?,
        @RequestParam(defaultValue = "ALL") assignment: String,
    ): ResponseEntity<RailwayLineAdminListPageResponse> {
        actorLoader.load(principal)
        val (boundedPage, boundedSize) = boundedPaging(page, size)
        val filter = RailwayLineAdminListFilter(
            query = query?.takeIf { it.isNotBlank() },
            active = active,
            serviceAreaId = serviceAreaId,
            assignment = runCatching { RailwayLineAssignmentFilter.valueOf(assignment) }.getOrDefault(RailwayLineAssignmentFilter.ALL),
        )
        return noStore(RailwayLineAdminListPageResponse.from(queries.railwayLineList(filter, boundedPage, boundedSize), boundedPage, boundedSize))
    }

    @PostMapping("/railway-lines/{railwayLineId}/assign")
    @Operation(
        summary = "Assign or move a railway line",
        description = "SUPER_ADMIN only. `expectedCurrentServiceAreaId` null assigns an unassigned line; non-null moves it from that area. " +
            "Never rewrites an existing report's routing snapshot - only future submissions see the new mapping.",
    )
    fun assignRailwayLine(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable railwayLineId: UUID,
        @Valid @RequestBody request: AssignRailwayLineRequest,
    ): ResponseEntity<Void> {
        val actor = actorLoader.load(principal)
        assignLine.assign(actor, railwayLineId, requireNotNull(request.targetServiceAreaId), request.expectedCurrentServiceAreaId)
        return noContent()
    }

    @PostMapping("/railway-lines/{railwayLineId}/unassign")
    @Operation(summary = "Unassign a railway line", description = "SUPER_ADMIN only. Future submissions on this line resolve RAILWAY_LINE_UNASSIGNED, unchanged from Phase 3.")
    fun unassignRailwayLine(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @PathVariable railwayLineId: UUID,
        @Valid @RequestBody request: UnassignRailwayLineRequest,
    ): ResponseEntity<Void> {
        val actor = actorLoader.load(principal)
        unassignLine.unassign(actor, railwayLineId, requireNotNull(request.expectedCurrentServiceAreaId))
        return noContent()
    }

    private fun boundedPaging(page: Int, size: Int?): Pair<Int, Int> =
        page.coerceAtLeast(0) to (size ?: AreaAdminQueryUseCase.DEFAULT_PAGE_SIZE).coerceIn(1, AreaAdminQueryUseCase.MAX_PAGE_SIZE)

    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)

    private fun noContent(): ResponseEntity<Void> =
        ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
}
