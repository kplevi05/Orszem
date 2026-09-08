package hu.orszembejelento.app.report.data

import hu.orszembejelento.app.report.data.network.PublicApi

data class CatalogEventType(val code: String, val displayName: String)
data class CatalogCategory(val code: String, val displayName: String, val eventTypes: List<CatalogEventType>)

sealed class CatalogResult {
    data class Loaded(val categories: List<CatalogCategory>) : CatalogResult()
    data object Failed : CatalogResult()
}

/**
 * The Public event catalogue - always fetched from the backend, never hardcoded (§3, §44).
 *
 * Caches the result in memory for the current process only (§45 - no durable offline
 * catalogue is required), so re-entering the report flow within one app session does not
 * refetch on every recomposition.
 */
class CatalogRepository(private val api: PublicApi) {

    @Volatile
    private var cached: List<CatalogCategory>? = null

    suspend fun catalog(forceRefresh: Boolean = false): CatalogResult {
        if (!forceRefresh) {
            cached?.let { return CatalogResult.Loaded(it) }
        }

        val response = runCatching { api.reportCatalog() }.getOrNull()
        val body = response?.takeIf { it.isSuccessful }?.body() ?: return CatalogResult.Failed

        val categories = body.categories.map { category ->
            CatalogCategory(
                code = category.code,
                displayName = category.displayName,
                eventTypes = category.eventTypes.map { CatalogEventType(it.code, it.displayName) },
            )
        }
        cached = categories
        return CatalogResult.Loaded(categories)
    }
}
