package hu.orszembejelento.backend.reference.api

import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.reference.application.PublicReferenceQueryUseCase
import hu.orszembejelento.backend.reference.domain.RailwayLine
import hu.orszembejelento.backend.reference.domain.Settlement
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** One matching settlement. Reference facts only - no service-area or routing information. */
data class SettlementSearchResult(
    val id: UUID,
    val kshCode: String,
    val name: String,
    val countyName: String?,
) {
    companion object {
        fun from(settlement: Settlement) = SettlementSearchResult(
            id = settlement.id,
            kshCode = settlement.kshCode.toString(),
            name = settlement.name,
            countyName = settlement.countyName,
        )
    }
}

/** One railway line with a verified relation to the settlement. */
data class RailwayLineItem(
    val id: UUID,
    val code: String,
    val displayName: String,
) {
    companion object {
        fun from(line: RailwayLine) = RailwayLineItem(id = line.id, code = line.lineCode, displayName = line.displayName)
    }
}

/**
 * The railway lines verified for a settlement, plus whether that roster is complete.
 *
 * [coverage] is `COMPLETE` or `PARTIAL` and is never omitted: a client must not read the
 * absence of a second item as proof that [items] lists the *only* possible railway line
 * when `coverage` is `PARTIAL` - see ADR 0006/0007.
 */
data class RailwayLinesForSettlementResponse(
    val coverage: String,
    val items: List<RailwayLineItem>,
)

/**
 * Public, unauthenticated reference lookups for the Public client (ADR 0007).
 *
 * Read-only, no mutation, and exposes reference facts only: no service area, no user or
 * moderator information, no routing destination, no `reuseStatus` or source/licence
 * metadata. See `PublicReferenceQueryUseCase` for why an empty result is never returned
 * when no reference dataset has been imported.
 */
@RestController
@RequestMapping("${ApiPaths.V1}/public/reference")
@Tag(name = "Public reference", description = "Unauthenticated settlement and railway-line lookups")
class PublicReferenceController(private val queries: PublicReferenceQueryUseCase) {

    @GetMapping("/settlements")
    @Operation(
        summary = "Search active settlements by name",
        description = "`query` is required and must be at least 2 Unicode code points. Returns " +
            "active settlements only, bounded to a fixed maximum result count.",
    )
    @ApiResponse(responseCode = "503", description = "No reference dataset has ever been imported (REFERENCE_DATASET_UNAVAILABLE).")
    fun searchSettlements(
        @Parameter(description = "Settlement name search text, minimum 2 Unicode code points")
        @RequestParam query: String,
    ): List<SettlementSearchResult> = queries.searchSettlements(query).map(SettlementSearchResult::from)

    @GetMapping("/settlements/{settlementId}/railway-lines")
    @Operation(
        summary = "Railway lines verified for a settlement",
        description = "Active lines with a verified relation to the settlement, plus whether " +
            "the settlement<->line relation coverage is COMPLETE or PARTIAL for the whole " +
            "dataset. A settlement with no known relation, or with an unknown or inactive " +
            "settlement id, returns an empty item list rather than an error.",
    )
    @ApiResponse(responseCode = "503", description = "No reference dataset has ever been imported (REFERENCE_DATASET_UNAVAILABLE).")
    fun railwayLinesOfSettlement(@PathVariable settlementId: UUID): RailwayLinesForSettlementResponse {
        val result = queries.railwayLinesOfSettlement(settlementId)
        return RailwayLinesForSettlementResponse(
            coverage = result.coverage.name,
            items = result.lines.map(RailwayLineItem::from),
        )
    }
}
