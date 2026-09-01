package hu.orszem.catalog.application

import hu.orszem.catalog.domain.EventCatalogPort
import hu.orszem.catalog.domain.EventTypeView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListEventTypesUseCase(
    private val catalog: EventCatalogPort,
) {
    @Transactional(readOnly = true)
    fun execute(): List<EventTypeView> = catalog.listActiveEventTypes()
}
