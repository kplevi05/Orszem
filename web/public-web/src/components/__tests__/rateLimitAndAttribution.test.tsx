import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HistoryItemCard } from '../HistoryItemCard'
import { Step1Form } from '../Step1Form'
import { HistoryScreen } from '../../routes/HistoryScreen'
import { initialNewReportState } from '../../routes/newReportState'
import { RepositoriesProvider, type Repositories } from '../../repositoryContext'
import { strings } from '../../strings'
import type { ReportRecord } from '../../storage/db'

// This suite does not enable Testing Library's automatic cleanup, so unmount explicitly.
afterEach(cleanup)

/** The owner-approved wording, pinned exactly so a later edit cannot quietly change it. */
const RATE_LIMITED_TEXT = 'Túl sok bejelentés érkezett rövid időn belül. Kérjük, várjon egy kicsit, majd próbálja újra.'
const KSH_TEXT = 'Településadatok forrása: KSH (CC BY 4.0)'

function pending(lastErrorCode: string | null): ReportRecord {
  return {
    clientSubmissionId: 'client-1',
    publicReportId: null,
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
    submissionState: 'PENDING',
    publicStatus: null,
    serverSubmittedAt: null,
    localCreatedAt: new Date().toISOString(),
    lastStatusCheckedAt: null,
    lastErrorCode,
  }
}

describe('approved copy', () => {
  it('the rate-limit message is the approved text', () => {
    expect(strings.errorRateLimited).toBe(RATE_LIMITED_TEXT)
  })

  it('the KSH attribution is the approved text', () => {
    expect(strings.settlementDataSource).toBe(KSH_TEXT)
  })

  it('the rate-limit message does not blame the person (the limit is per network)', () => {
    const lower = strings.errorRateLimited.toLowerCase()
    for (const word of ['hibáz', 'rosszul', 'gyanús', 'visszaél', 'spam', 'tiltott']) {
      expect(lower.includes(word), `copy must not contain '${word}'`).toBe(false)
    }
  })
})

describe('a throttled PENDING report in History', () => {
  it('explains why and still offers the manual retry, which fires exactly once per click', async () => {
    const onRetry = vi.fn()
    render(<HistoryItemCard record={pending('RATE_LIMITED')} onRetry={onRetry} onRefresh={vi.fn()} />)

    expect(screen.getByText(RATE_LIMITED_TEXT)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: strings.actionRetry }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('never shows the throttling message on other PENDING reports', () => {
    render(<HistoryItemCard record={pending('SOMETHING_ELSE')} onRetry={vi.fn()} onRefresh={vi.fn()} />)
    expect(screen.queryByText(RATE_LIMITED_TEXT)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: strings.actionRetry })).toBeInTheDocument()
  })

  it('never shows it when there was no error at all', () => {
    render(<HistoryItemCard record={pending(null)} onRetry={vi.fn()} onRefresh={vi.fn()} />)
    expect(screen.queryByText(RATE_LIMITED_TEXT)).not.toBeInTheDocument()
  })
})

describe('KSH attribution (CC BY 4.0) beside the settlement data', () => {
  it('appears in the new-report form where a settlement is chosen', () => {
    render(<Step1Form state={initialNewReportState()} dispatch={vi.fn()} />)
    expect(screen.getByText(KSH_TEXT)).toBeInTheDocument()
  })

  it('appears on the History screen, which lists settlement names', () => {
    const reportRepository = {
      submit: vi.fn(),
      retry: vi.fn(),
      refreshStatus: vi.fn(),
      refreshAll: vi.fn(),
      listHistory: vi.fn().mockResolvedValue([]),
      subscribe: vi.fn().mockReturnValue(() => undefined),
    } as unknown as Repositories['reportRepository']
    const repositories: Repositories = {
      reportRepository,
      catalogRepository: { catalog: vi.fn() },
      referenceRepository: { searchSettlements: vi.fn(), railwayLinesOfSettlement: vi.fn() },
    }
    render(
      <MemoryRouter>
        <RepositoriesProvider value={repositories}>
          <HistoryScreen />
        </RepositoriesProvider>
      </MemoryRouter>,
    )
    expect(screen.getByText(KSH_TEXT)).toBeInTheDocument()
  })
})
