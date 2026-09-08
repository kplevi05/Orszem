package hu.orszembejelento.backend.reports.infrastructure

import hu.orszembejelento.backend.reports.domain.ReportCategory
import hu.orszembejelento.backend.reports.domain.ReportEventType
import java.sql.ResultSet
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * The product event taxonomy (`report_categories` / `report_event_types`) - explicit SQL,
 * consistent with the rest of the backend.
 *
 * Lookups by code are deliberately unfiltered by `active`: a historical report's event
 * type or category may since have been deactivated, and its detail must still resolve
 * (docs/product/EVENT_CATALOG_V2.md §1; ADR 0008). Callers that need "active only" -
 * submission validation and the Public catalogue - filter explicitly.
 */
@Repository
class JdbcEventCatalogRepository(private val jdbc: JdbcClient) {

    fun findCategoryByCode(code: String): ReportCategory? =
        jdbc.sql("$SELECT_CATEGORY WHERE code = :code")
            .param("code", code)
            .query(::mapCategory)
            .optional()
            .orElse(null)

    fun findEventTypeByCode(code: String): ReportEventType? =
        jdbc.sql("$SELECT_EVENT_TYPE WHERE code = :code")
            .param("code", code)
            .query(::mapEventType)
            .optional()
            .orElse(null)

    /** Active categories, deterministically ordered - the Public catalogue's top level. */
    fun findActiveCategories(): List<ReportCategory> =
        jdbc.sql("$SELECT_CATEGORY WHERE active ORDER BY display_order")
            .query(::mapCategory)
            .list()

    /** Active event types of one active-or-not category, deterministically ordered. */
    fun findActiveEventTypesByCategory(categoryCode: String): List<ReportEventType> =
        jdbc.sql("$SELECT_EVENT_TYPE WHERE category_code = :code AND active ORDER BY display_order")
            .param("code", categoryCode)
            .query(::mapEventType)
            .list()

    private fun mapCategory(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ReportCategory(
        code = rs.getString("code"),
        displayName = rs.getString("display_name"),
        displayOrder = rs.getInt("display_order"),
        active = rs.getBoolean("active"),
    )

    private fun mapEventType(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ReportEventType(
        code = rs.getString("code"),
        categoryCode = rs.getString("category_code"),
        displayName = rs.getString("display_name"),
        displayOrder = rs.getInt("display_order"),
        active = rs.getBoolean("active"),
    )

    private companion object {
        const val SELECT_CATEGORY = "SELECT code, display_name, display_order, active FROM report_categories"
        const val SELECT_EVENT_TYPE =
            "SELECT code, category_code, display_name, display_order, active FROM report_event_types"
    }
}
