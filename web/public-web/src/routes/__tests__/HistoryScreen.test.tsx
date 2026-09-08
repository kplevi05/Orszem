import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HistoryScreen } from '../HistoryScreen'
import { RepositoriesProvider, type Repositories } from '../../repositoryContext'
import type { ReportRecord } from '../../storage/db'

/**
 * Regression test for a real bug this exact scenario caught during a live end-to-end run
 * against a real backend (not a mocked-IndexedDB unit test, which never surfaced it): the
 * one-time "refresh every submitted record on entering History" effect fired immediately,
 * before the async local-history load had resolved, so it always refreshed an empty list
 * and never actually refreshed anything. See routes/HistoryScreen.tsx's own comment.
 */
function submittedRecord(id: string): ReportRecord {
  return {
    clientSubmissionId: id,
    publicReportId: 'public-' + id,
    encryptedCredentialCiphertext: new ArrayBuffer(0),
    encryptedCredentialIv: new ArrayBuffer(0),
    occurredAt: new Date().toISOString(),
    trainIdentifier: null,
    settlementId: 'settlement-1',
    settlementNameSnapshot: 'Alfaváros',
    railwayLineId: null,
    railwayLineDisplaySnapshot: null,
    categoryDisplaySnapshot: 'Kategória',
    eventTypeCode: 'FIGHT',
    eventTypeDisplaySnapshot: 'Verekedés',
    submissionState: 'SUBMITTED',
    publicStatus: 'RECEIVED',
    serverSubmittedAt: new Date().toISOString(),
    localCreatedAt: new Date().toISOString(),
    lastStatusCheckedAt: null,
    lastErrorCode: null,
  }
}

function renderHistoryScreen(reportRepository: Repositories['reportRepository']) {
  const repositories: Repositories = {
    reportRepository,
    catalogRepository: { catalog: vi.fn() },
    referenceRepository: { searchSettlements: vi.fn(), railwayLinesOfSettlement: vi.fn() },
  }
  return render(
    <MemoryRouter>
      <RepositoriesProvider value={repositories}>
        <HistoryScreen />
      </RepositoriesProvider>
    </MemoryRouter>,
  )
}

describe('HistoryScreen refresh-on-entry', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  it('refreshes submitted records once the asynchronously-loaded history actually arrives, not before', async () => {
    const record = submittedRecord('a')
    // A deliberately delayed resolution - exactly the real IndexedDB/Room shape: the first
    // render happens before this promise settles.
    const listHistory = vi.fn().mockImplementation(() => new Promise((resolve) => setTimeout(() => resolve([record]), 20)))
    const refreshAll = vi.fn().mockResolvedValue(undefined)
    const subscribe = vi.fn().mockReturnValue(() => undefined)

    renderHistoryScreen({
      submit: vi.fn(),
      retry: vi.fn(),
      refreshStatus: vi.fn(),
      refreshAll,
      listHistory,
      subscribe,
    })

    // Immediately after the first render, the effect must not have refreshed anything -
    // the old bug's failure mode was calling refreshAll([]) right here.
    expect(refreshAll).not.toHaveBeenCalled()

    await waitFor(() => expect(screen.getByText('Verekedés')).toBeInTheDocument())

    await waitFor(() => expect(refreshAll).toHaveBeenCalledTimes(1))
    expect(refreshAll).toHaveBeenCalledWith(['a'])
  })

  it('does not refresh again on subsequent history updates (no refresh loop)', async () => {
    const record = submittedRecord('b')
    const listHistory = vi.fn().mockResolvedValue([record])
    const refreshAll = vi.fn().mockResolvedValue(undefined)
    let notify: (() => void) | undefined
    const subscribe = vi.fn().mockImplementation((listener: () => void) => {
      notify = listener
      return () => undefined
    })

    renderHistoryScreen({
      submit: vi.fn(),
      retry: vi.fn(),
      refreshStatus: vi.fn(),
      refreshAll,
      listHistory,
      subscribe,
    })

    await waitFor(() => expect(refreshAll).toHaveBeenCalledTimes(1))

    // Simulate a later, unrelated local write (e.g. the refresh itself completing and
    // updating lastStatusCheckedAt) - must not trigger a second automatic refresh.
    await act(async () => {
      notify?.()
    })

    expect(refreshAll).toHaveBeenCalledTimes(1)
  })
})
