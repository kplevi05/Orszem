package hu.orszem.catalog.domain

import java.util.UUID

/** An active event type together with its category, as shown in the public picker. */
data class EventTypeView(
    val code: String,
    val label: String,
    val description: String?,
    val sortOrder: Int,
    val categoryCode: String,
    val categoryLabel: String,
    val categorySortOrder: Int,
)

/** Minimal reference to an event type, used by other modules to classify a report. */
data class EventTypeRef(
    val id: UUID,
    val code: String,
    val label: String,
    val categoryCode: String,
    val categoryLabel: String,
    val active: Boolean,
)

/**
 * Read port over the server-controlled event catalog. The database is the
 * canonical runtime source (`event_categories` + `event_types`).
 */
interface EventCatalogPort {

    /** Active event types ordered by category sortOrder, then event type sortOrder, then label. */
    fun listActiveEventTypes(): List<EventTypeView>

    /** Resolve an event type by stable code, only if it is currently active. */
    fun findActiveByCode(code: String): EventTypeRef?

    /** Resolve an event type by id regardless of active state (existing reports may reference inactive types). */
    fun findById(id: UUID): EventTypeRef?
}
