import { describe, expect, it, vi } from 'vitest'
import { createCatalogRepository } from '../catalogRepository'
import { createFakePublicApi } from '../../test/fakePublicApi'

describe('catalogRepository', () => {
  it('always fetches from the backend - never returns a hardcoded taxonomy on failure', async () => {
    const api = createFakePublicApi()
    // reportCatalog defaults to a 500 in the fake - no override, so this exercises the
    // genuine failure path with nothing injected.
    const repository = createCatalogRepository(api)

    const result = await repository.catalog()

    expect(result).toEqual({ kind: 'failed' })
  })

  it('nests event types under their category exactly as the backend returned them', async () => {
    const api = createFakePublicApi()
    api.reportCatalog = async () => ({
      status: 200,
      body: {
        categories: [
          {
            code: 'VIOLENCE_DANGER',
            displayName: 'Erőszak és közvetlen veszély',
            eventTypes: [{ code: 'FIGHT', displayName: 'Verekedés' }],
          },
        ],
      },
      errorBody: undefined,
    })
    const repository = createCatalogRepository(api)

    const result = await repository.catalog()

    expect(result.kind).toBe('loaded')
    if (result.kind === 'loaded') {
      expect(result.categories).toHaveLength(1)
      expect(result.categories[0]?.eventTypes).toEqual([{ code: 'FIGHT', displayName: 'Verekedés' }])
    }
  })

  it('caches the result in memory for the current session - a second call does not refetch', async () => {
    const api = createFakePublicApi()
    const spy = vi.fn(async () => ({
      status: 200 as const,
      body: { categories: [] },
      errorBody: undefined,
    }))
    api.reportCatalog = spy
    const repository = createCatalogRepository(api)

    await repository.catalog()
    await repository.catalog()

    expect(spy).toHaveBeenCalledTimes(1)
  })

  it('forceRefresh bypasses the cache', async () => {
    const api = createFakePublicApi()
    const spy = vi.fn(async () => ({ status: 200 as const, body: { categories: [] }, errorBody: undefined }))
    api.reportCatalog = spy
    const repository = createCatalogRepository(api)

    await repository.catalog()
    await repository.catalog(true)

    expect(spy).toHaveBeenCalledTimes(2)
  })
})
