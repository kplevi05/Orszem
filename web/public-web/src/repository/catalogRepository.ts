import { publicApi, type PublicApi } from '../api/publicApi'

export interface CatalogEventType {
  readonly code: string
  readonly displayName: string
}
export interface CatalogCategory {
  readonly code: string
  readonly displayName: string
  readonly eventTypes: readonly CatalogEventType[]
}

export type CatalogResult = { readonly kind: 'loaded'; readonly categories: readonly CatalogCategory[] } | { readonly kind: 'failed' }

/**
 * The Public event catalogue - always fetched from the backend, never hardcoded (§3, §44).
 * Caches the result in memory for the current page session only (§45 - no durable offline
 * catalogue is required).
 */
export function createCatalogRepository(api: PublicApi = publicApi) {
  let cached: readonly CatalogCategory[] | null = null

  async function catalog(forceRefresh = false): Promise<CatalogResult> {
    if (!forceRefresh && cached) return { kind: 'loaded', categories: cached }

    const result = await api.reportCatalog().catch(() => undefined)
    if (!result || result.status !== 200 || !result.body) return { kind: 'failed' }

    const categories = result.body.categories.map((category) => ({
      code: category.code,
      displayName: category.displayName,
      eventTypes: category.eventTypes.map((eventType) => ({ code: eventType.code, displayName: eventType.displayName })),
    }))
    cached = categories
    return { kind: 'loaded', categories }
  }

  return { catalog }
}

export type CatalogRepository = ReturnType<typeof createCatalogRepository>
