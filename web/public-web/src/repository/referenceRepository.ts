import { publicApi, type PublicApi } from '../api/publicApi'
import type { LineCoverage, RailwayLinesForSettlement } from '../domain/lineDecision'

export interface SettlementOption {
  readonly id: string
  readonly name: string
  readonly countyName: string | null
}

export type SettlementSearchResult =
  | { readonly kind: 'loaded'; readonly settlements: readonly SettlementOption[] }
  | { readonly kind: 'failed' }

export type RailwayLineLookupResult =
  | { readonly kind: 'loaded'; readonly response: RailwayLinesForSettlement }
  | { readonly kind: 'failed' }

/** Settlement search and railway-line lookup - always server-authoritative (§3, §40-41). */
export function createReferenceRepository(api: PublicApi = publicApi) {
  async function searchSettlements(query: string): Promise<SettlementSearchResult> {
    const result = await api.searchSettlements(query).catch(() => undefined)
    if (!result || result.status !== 200 || !result.body) return { kind: 'failed' }
    const settlements = result.body.map((item) => ({ id: item.id, name: item.name, countyName: item.countyName }))
    return { kind: 'loaded', settlements }
  }

  async function railwayLinesOfSettlement(settlementId: string): Promise<RailwayLineLookupResult> {
    const result = await api.railwayLinesOfSettlement(settlementId).catch(() => undefined)
    if (!result || result.status !== 200 || !result.body) return { kind: 'failed' }
    const coverage = result.body.coverage as LineCoverage
    if (coverage !== 'COMPLETE' && coverage !== 'PARTIAL') return { kind: 'failed' }
    const items = result.body.items.map((item) => ({ id: item.id, code: item.code, displayName: item.displayName }))
    return { kind: 'loaded', response: { coverage, items } }
  }

  return { searchSettlements, railwayLinesOfSettlement }
}

export type ReferenceRepository = ReturnType<typeof createReferenceRepository>
