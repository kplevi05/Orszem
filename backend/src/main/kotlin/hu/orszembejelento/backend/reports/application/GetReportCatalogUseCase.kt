package hu.orszembejelento.backend.reports.application

import hu.orszembejelento.backend.reports.domain.ReportEventType
import hu.orszembejelento.backend.reports.infrastructure.JdbcEventCatalogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** One active category with its active event types, in the Public catalogue's shape. */
data class CatalogCategory(
    val code: String,
    val displayName: String,
    val eventTypes: List<ReportEventType>,
)

/**
 * The Public event catalogue: exactly the currently active taxonomy, nested and
 * deterministically ordered by `display_order` (ADR 0008).
 *
 * The only source of truth for what a client may submit as `eventTypeCode` - no client is
 * ever expected to hardcode a copy, and the backend never trusts one that does (see
 * `SubmitReportUseCase`).
 */
@Service
class GetReportCatalogUseCase(private val repository: JdbcEventCatalogRepository) {

    @Transactional(readOnly = true)
    fun catalog(): List<CatalogCategory> =
        repository.findActiveCategories().map { category ->
            CatalogCategory(
                code = category.code,
                displayName = category.displayName,
                eventTypes = repository.findActiveEventTypesByCategory(category.code),
            )
        }
}
