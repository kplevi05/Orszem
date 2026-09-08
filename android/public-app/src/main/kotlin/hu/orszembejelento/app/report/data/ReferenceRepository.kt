package hu.orszembejelento.app.report.data

import hu.orszembejelento.app.report.data.network.PublicApi
import hu.orszembejelento.app.report.domain.LineCoverage
import hu.orszembejelento.app.report.domain.RailwayLineOption
import hu.orszembejelento.app.report.domain.RailwayLinesForSettlement
import java.util.UUID

data class SettlementOption(val id: UUID, val name: String, val countyName: String?)

sealed class SettlementSearchResult {
    data class Loaded(val settlements: List<SettlementOption>) : SettlementSearchResult()
    data object Failed : SettlementSearchResult()
}

sealed class RailwayLineLookupResult {
    data class Loaded(val response: RailwayLinesForSettlement) : RailwayLineLookupResult()
    data object Failed : RailwayLineLookupResult()
}

/** Settlement search and railway-line lookup - always server-authoritative (§3, §40-41). */
class ReferenceRepository(private val api: PublicApi) {

    suspend fun searchSettlements(query: String): SettlementSearchResult {
        val response = runCatching { api.searchSettlements(query) }.getOrNull()
        val body = response?.takeIf { it.isSuccessful }?.body() ?: return SettlementSearchResult.Failed
        val options = body.mapNotNull { item ->
            runCatching { UUID.fromString(item.id) }.getOrNull()?.let { SettlementOption(it, item.name, item.countyName) }
        }
        return SettlementSearchResult.Loaded(options)
    }

    suspend fun railwayLinesOfSettlement(settlementId: UUID): RailwayLineLookupResult {
        val response = runCatching { api.railwayLinesOfSettlement(settlementId.toString()) }.getOrNull()
        val body = response?.takeIf { it.isSuccessful }?.body() ?: return RailwayLineLookupResult.Failed

        val coverage = runCatching { LineCoverage.valueOf(body.coverage) }.getOrNull() ?: return RailwayLineLookupResult.Failed
        val items = body.items.mapNotNull { item ->
            runCatching { UUID.fromString(item.id) }.getOrNull()?.let { RailwayLineOption(it, item.code, item.displayName) }
        }
        return RailwayLineLookupResult.Loaded(RailwayLinesForSettlement(coverage, items))
    }
}
