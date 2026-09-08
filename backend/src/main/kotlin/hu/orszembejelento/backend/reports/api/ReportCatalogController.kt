package hu.orszembejelento.backend.reports.api

import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.reports.application.CatalogCategory
import hu.orszembejelento.backend.reports.application.GetReportCatalogUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ReportCatalogResponse(val categories: List<ReportCatalogCategoryResponse>)

data class ReportCatalogCategoryResponse(
    val code: String,
    val displayName: String,
    val eventTypes: List<EventTypeSummary>,
)

/**
 * The Public event catalogue: the currently active taxonomy, nested and deterministically
 * ordered, and never authored twice - a client is never expected to hardcode a copy (ADR
 * 0008, docs/product/EVENT_CATALOG_V2.md §1).
 */
@RestController
@RequestMapping(ApiPaths.V1)
@Tag(name = "Public reports", description = "Anonymous report submission and status lookup")
class ReportCatalogController(private val getReportCatalog: GetReportCatalogUseCase) {

    @GetMapping("/public/report-catalog")
    @Operation(
        summary = "The active event catalogue",
        description = "Active categories with their active event types, ordered deterministically " +
            "by the stored display order. The only authoritative source of valid `eventTypeCode` values.",
    )
    fun catalog(): ReportCatalogResponse =
        ReportCatalogResponse(getReportCatalog.catalog().map { it.toResponse() })

    private fun CatalogCategory.toResponse() = ReportCatalogCategoryResponse(
        code = code,
        displayName = displayName,
        eventTypes = eventTypes.map { EventTypeSummary(it.code, it.displayName) },
    )
}
