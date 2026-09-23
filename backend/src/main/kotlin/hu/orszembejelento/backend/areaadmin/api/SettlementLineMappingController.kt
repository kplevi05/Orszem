package hu.orszembejelento.backend.areaadmin.api

import hu.orszembejelento.backend.areaadmin.application.AreaAdminActorLoader
import hu.orszembejelento.backend.areaadmin.application.SettlementLineMappingUseCase
import hu.orszembejelento.backend.areaadmin.domain.SettlementLineChange
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.common.web.ApiPaths
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class SettlementLineChangeRequest(
    @field:Pattern(regexp = "[0-9]{5}") val kshCode: String,
    @field:NotBlank @field:Size(max = 16) val lineCode: String,
    val targetServiceAreaId: UUID?,
    val expectedCurrentServiceAreaId: UUID?,
) {
    fun toDomain() = SettlementLineChange(kshCode, lineCode, targetServiceAreaId, expectedCurrentServiceAreaId)
}

data class SettlementLineBatchRequest(
    @field:NotBlank @field:Size(max = 64) val expectedReferenceVersion: String,
    @field:Valid @field:Size(min = 1, max = 1000) val changes: List<SettlementLineChangeRequest>,
)

@RestController
@RequestMapping("${ApiPaths.V1}/service/service-area-admin/settlement-line-mappings")
@Tag(name = "Service area administration")
@SecurityRequirement(name = "bearerAuth")
class SettlementLineMappingController(
    private val actors: AreaAdminActorLoader,
    private val useCase: SettlementLineMappingUseCase,
) {
    @GetMapping
    @Operation(summary = "List settlement-line area assignments", description = "SUPER_ADMIN only; bounded page, optional area filter. Existing clients keep their legacy whole-line view.")
    fun list(
        @AuthenticationPrincipal principal: AuthenticatedActor,
        @RequestParam(required = false) serviceAreaId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "100") size: Int,
    ) = noStore(useCase.list(actors.load(principal), serviceAreaId, page, size))

    @PostMapping("/preview")
    @Operation(summary = "Validate and preview an operational mapping batch", description = "SUPER_ADMIN only. No writes. Requires current reference version and expected assignment for each pair; never creates reference data or grants.")
    fun preview(@AuthenticationPrincipal principal: AuthenticatedActor, @Valid @RequestBody request: SettlementLineBatchRequest) =
        noStore(useCase.preview(actors.load(principal), request.expectedReferenceVersion, request.changes.map { it.toDomain() }))

    @PostMapping("/apply")
    @Operation(summary = "Apply an operational mapping batch atomically", description = "SUPER_ADMIN only. Revalidates all rows. Null target removes a mapping; current assignment equal to target is a no-op. At most 1000 distinct pairs. Whole-line and pair modes cannot coexist on a line. Existing report snapshots are unchanged.")
    fun apply(@AuthenticationPrincipal principal: AuthenticatedActor, @Valid @RequestBody request: SettlementLineBatchRequest) =
        noStore(useCase.apply(actors.load(principal), request.expectedReferenceVersion, request.changes.map { it.toDomain() }))

    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)
}
