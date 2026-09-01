package hu.orszem.publicapp.data

import hu.orszem.core.common.DispatcherProvider
import hu.orszem.core.common.Outcome
import hu.orszem.core.common.map
import hu.orszem.core.model.EventType
import hu.orszem.core.network.OrszemApi
import hu.orszem.core.network.safeApiCall
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Loads the server-controlled event catalog. The app never hardcodes a competing list. */
class CatalogRepository @Inject constructor(
    private val api: OrszemApi,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun loadEventTypes(): Outcome<List<EventType>> = withContext(dispatchers.io) {
        safeApiCall { api.listEventTypes() }.map { response ->
            response.items
                .map {
                    EventType(
                        code = it.code,
                        label = it.label,
                        description = it.description,
                        sortOrder = it.sortOrder,
                        categoryCode = it.categoryCode,
                        categoryLabel = it.categoryLabel,
                        categorySortOrder = it.categorySortOrder,
                    )
                }
                .sortedWith(compareBy({ it.categorySortOrder }, { it.sortOrder }, { it.label }))
        }
    }
}
