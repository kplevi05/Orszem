package hu.orszem.catalog.api

import hu.orszem.catalog.application.ListEventTypesUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/public")
class PublicCatalogController(
    private val listEventTypes: ListEventTypesUseCase,
) {

    /** `GET /api/v1/public/event-types` — anonymous. */
    @GetMapping("/event-types")
    fun listEventTypes(): EventTypeListResponse =
        EventTypeListResponse(items = listEventTypes.execute().map { it.toResponse() })
}
