package hu.orszem.catalog.api

import hu.orszem.catalog.domain.EventTypeView

/** Matches OpenAPI `EventType`. */
data class EventTypeResponse(
    val code: String,
    val categoryCode: String,
    val categoryLabel: String,
    val categorySortOrder: Int,
    val label: String,
    val description: String?,
    val sortOrder: Int,
)

/** Matches OpenAPI `EventTypeListResponse`. */
data class EventTypeListResponse(
    val items: List<EventTypeResponse>,
)

fun EventTypeView.toResponse() = EventTypeResponse(
    code = code,
    categoryCode = categoryCode,
    categoryLabel = categoryLabel,
    categorySortOrder = categorySortOrder,
    label = label,
    description = description,
    sortOrder = sortOrder,
)
