package hu.orszembejelento.backend.reports.domain

/**
 * One category of the frozen V2 event taxonomy (`docs/product/EVENT_CATALOG_V2.md`).
 *
 * Product-controlled business taxonomy, not reference data (ADR 0008 vs ADR 0006): it
 * changes through deliberate, reviewed migrations, never a runtime importer. [code] is a
 * stable identifier - once published its meaning never changes; a retired category is
 * deactivated ([active] = false), never deleted, so historical reports always resolve.
 */
data class ReportCategory(
    val code: String,
    val displayName: String,
    val displayOrder: Int,
    val active: Boolean,
)

/** One event type within a [ReportCategory]. Same stability rules as the category. */
data class ReportEventType(
    val code: String,
    val categoryCode: String,
    val displayName: String,
    val displayOrder: Int,
    val active: Boolean,
)
