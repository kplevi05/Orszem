import { beforeEach, describe, expect, it } from 'vitest'
import { createReportRepository } from '../reportRepository'
import { createFakePublicApi } from '../../test/fakePublicApi'
import { _resetForTests, getReport } from '../../storage/db'
import type { ReportDraft } from '../../domain/normalize'
import type { ReportDisplaySnapshot } from '../reportRepository'
import type { PublicApi } from '../../api/publicApi'

const fixedOccurredAt = new Date('2026-01-01T12:00:00Z')
const display: ReportDisplaySnapshot = {
  settlementName: 'Alfaváros',
  railwayLineDisplay: null,
  categoryDisplay: 'Kategória',
  eventTypeDisplay: 'Verekedés',
}

function draft(overrides: Partial<ReportDraft> = {}): ReportDraft {
  return {
    occurredAt: fixedOccurredAt,
    trainIdentifierInput: '  G123  ',
    settlementId: 'settlement-1',
    railwayLineId: null,
    eventTypeCode: 'FIGHT',
    ...overrides,
  }
}

async function resetDb(): Promise<void> {
  await _resetForTests()
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.deleteDatabase('orszem-public-reports')
    request.onsuccess = () => resolve()
    request.onerror = () => reject(request.error)
    request.onblocked = () => resolve()
  })
}

beforeEach(resetDb)

describe('submit - persist-before-network ordering (§5)', () => {
  it('the local record is committed before the first network attempt', async () => {
    const callLog: string[] = []
    const api: PublicApi = {
      reportCatalog: async () => ({ status: 500, body: undefined, errorBody: undefined }),
      searchSettlements: async () => ({ status: 200, body: [], errorBody: undefined }),
      railwayLinesOfSettlement: async () => ({ status: 500, body: undefined, errorBody: undefined }),
      submitReport: async (body, _accessCredential) => {
        // At the moment the network call fires, the row must already be committed.
        const existing = await getReport(body.clientSubmissionId)
        callLog.push(existing ? 'dao.insert-confirmed-then-api.submitReport' : 'api.submitReport-WITHOUT-prior-insert')
        return {
          status: 201,
          body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' },
          errorBody: undefined,
        }
      },
      getReport: async () => ({ status: 404, body: undefined, errorBody: undefined }),
    }
    const repository = createReportRepository(api)

    await repository.submit(draft(), display)

    expect(callLog).toEqual(['dao.insert-confirmed-then-api.submitReport'])
  })
})

describe('submit - normalization', () => {
  it('trims the train identifier before sending it', async () => {
    const api = createFakePublicApi()
    api.submitResult = { status: 201, body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' }, errorBody: undefined }
    const repository = createReportRepository(api)

    await repository.submit(draft({ trainIdentifierInput: '  G123  ' }), display)

    expect(api.lastSubmitBody?.trainIdentifier).toBe('G123')
  })

  it('normalizes a blank train identifier to null', async () => {
    const api = createFakePublicApi()
    api.submitResult = { status: 201, body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' }, errorBody: undefined }
    const repository = createReportRepository(api)

    await repository.submit(draft({ trainIdentifierInput: '   ' }), display)

    expect(api.lastSubmitBody?.trainIdentifier).toBeNull()
  })
})

describe('submit - outcomes', () => {
  it('a 201 response marks the record SUBMITTED (Created)', async () => {
    const api = createFakePublicApi()
    const reportId = crypto.randomUUID()
    api.submitResult = { status: 201, body: { reportId, submittedAt: '2026-01-01T12:05:00Z', initialStatus: 'RECEIVED' }, errorBody: undefined }
    const repository = createReportRepository(api)

    const outcome = await repository.submit(draft(), display)

    expect(outcome.kind).toBe('created')
    const stored = await getReport(api.lastSubmitBody!.clientSubmissionId)
    expect(stored?.submissionState).toBe('SUBMITTED')
    expect(stored?.publicReportId).toBe(reportId)
    expect(stored?.publicStatus).toBe('RECEIVED')
  })

  it('an idempotent 200 replay is reported as Replayed, also SUBMITTED', async () => {
    const api = createFakePublicApi()
    api.submitResult = {
      status: 200,
      body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' },
      errorBody: undefined,
    }
    const repository = createReportRepository(api)

    const outcome = await repository.submit(draft(), display)

    expect(outcome.kind).toBe('replayed')
  })

  it('a thrown network error leaves the record PENDING', async () => {
    const api = createFakePublicApi()
    api.throwOnSubmit = new Error('boom')
    const repository = createReportRepository(api)

    const outcome = await repository.submit(draft(), display)

    expect(outcome.kind).toBe('ambiguous-failure')
    const stored = await getReport(api.lastSubmitBody!.clientSubmissionId)
    expect(stored?.submissionState).toBe('PENDING')
  })

  it('a definitive 400 deletes the record - it is never treated as history', async () => {
    const api = createFakePublicApi()
    api.submitResult = { status: 400, body: undefined, errorBody: { code: 'INVALID_SETTLEMENT', message: '', correlationId: 'x' } }
    const repository = createReportRepository(api)

    const outcome = await repository.submit(draft(), display)

    expect(outcome).toEqual({ kind: 'validation-failed', code: 'INVALID_SETTLEMENT' })
    const stored = await getReport(api.lastSubmitBody!.clientSubmissionId)
    expect(stored).toBeUndefined()
  })

  it('after a 400, a later submission attempt gets a brand new identity', async () => {
    const api = createFakePublicApi()
    api.submitResult = { status: 400, body: undefined, errorBody: { code: 'INVALID_SETTLEMENT', message: '', correlationId: 'x' } }
    const repository = createReportRepository(api)

    await repository.submit(draft(), display)
    const firstId = api.lastSubmitBody!.clientSubmissionId

    api.submitResult = {
      status: 201,
      body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' },
      errorBody: undefined,
    }
    await repository.submit(draft(), display)
    const secondId = api.lastSubmitBody!.clientSubmissionId

    expect(secondId).not.toBe(firstId)
  })

  it('a 409 marks the record CONFLICT', async () => {
    const api = createFakePublicApi()
    api.submitResult = { status: 409, body: undefined, errorBody: { code: 'IDEMPOTENCY_KEY_REUSED', message: '', correlationId: 'x' } }
    const repository = createReportRepository(api)

    const outcome = await repository.submit(draft(), display)

    expect(outcome.kind).toBe('conflict')
    const stored = await getReport(api.lastSubmitBody!.clientSubmissionId)
    expect(stored?.submissionState).toBe('CONFLICT')
  })
})

describe('retry', () => {
  it('resends the exact same clientSubmissionId, credential and normalized payload', async () => {
    const api = createFakePublicApi()
    api.throwOnSubmit = new Error('boom')
    const repository = createReportRepository(api)

    await repository.submit(draft(), display)
    const firstBody = api.lastSubmitBody
    const firstCredential = api.lastSubmitCredential
    const id = firstBody!.clientSubmissionId

    api.throwOnSubmit = null
    api.submitResult = {
      status: 201,
      body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' },
      errorBody: undefined,
    }
    await repository.retry(id)

    expect(api.lastSubmitBody).toEqual(firstBody)
    expect(api.lastSubmitCredential).toBe(firstCredential)
  })
})

describe('status refresh', () => {
  it('a generic 404 leaves the record untouched, per the existence-safe contract (§18)', async () => {
    const api = createFakePublicApi()
    api.submitResult = {
      status: 201,
      body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' },
      errorBody: undefined,
    }
    const repository = createReportRepository(api)
    const submitOutcome = await repository.submit(draft(), display)
    const id = api.lastSubmitBody!.clientSubmissionId

    api.getReportResult = { status: 404, body: undefined, errorBody: { code: 'REPORT_NOT_FOUND', message: '', correlationId: 'x' } }
    const outcome = await repository.refreshStatus(id)

    expect(outcome.kind).toBe('unavailable')
    const stored = await getReport(id)
    expect(stored?.submissionState).toBe('SUBMITTED')
    expect(stored?.publicReportId).toBeDefined()
    expect(submitOutcome.kind).toBe('created')
  })
})

describe('history ordering', () => {
  it('is newest-first', async () => {
    const api = createFakePublicApi()
    api.submitResult = {
      status: 201,
      body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' },
      errorBody: undefined,
    }
    const repository = createReportRepository(api)
    await repository.submit(draft({ settlementId: 'settlement-a' }), display)
    await new Promise((resolve) => setTimeout(resolve, 5))
    await repository.submit(draft({ settlementId: 'settlement-b' }), display)

    const history = await repository.listHistory()
    const timestamps = history.map((h) => new Date(h.localCreatedAt).getTime())
    expect(timestamps).toEqual([...timestamps].sort((a, b) => b - a))
  })
})

describe('a PENDING record is reloaded correctly by a fresh repository instance (simulating a page reload, §65)', () => {
  it('preserves the frozen payload and retries with the identical identity', async () => {
    const firstApi = createFakePublicApi()
    firstApi.throwOnSubmit = new Error('boom')
    const firstRepository = createReportRepository(firstApi)
    await firstRepository.submit(draft({ trainIdentifierInput: 'G999' }), display)
    const id = firstApi.lastSubmitBody!.clientSubmissionId

    // A fresh repository instance, over the SAME underlying IndexedDB - exactly what a page
    // reload constructs, since the database connection itself is not test-reset here.
    const secondApi = createFakePublicApi()
    secondApi.submitResult = {
      status: 201,
      body: { reportId: crypto.randomUUID(), submittedAt: new Date().toISOString(), initialStatus: 'RECEIVED' },
      errorBody: undefined,
    }
    const secondRepository = createReportRepository(secondApi)

    const reloaded = await secondRepository.listHistory()
    expect(reloaded).toHaveLength(1)
    expect(reloaded[0]?.submissionState).toBe('PENDING')
    expect(reloaded[0]?.trainIdentifier).toBe('G999')

    await secondRepository.retry(id)
    expect(secondApi.lastSubmitBody?.trainIdentifier).toBe('G999')
    expect(secondApi.lastSubmitBody?.clientSubmissionId).toBe(id)
  })
})
