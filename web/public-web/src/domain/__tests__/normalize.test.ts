import { describe, expect, it } from 'vitest'
import { normalizeReportDraft, payloadsEqual, type ReportDraft } from '../normalize'

const occurredAt = new Date('2026-01-01T12:00:00Z')
const settlementId = 'settlement-1'

function draft(trainIdentifierInput: string | null): ReportDraft {
  return { occurredAt, trainIdentifierInput, settlementId, railwayLineId: null, eventTypeCode: 'FIGHT' }
}

describe('normalizeReportDraft', () => {
  it('trims surrounding whitespace', () => {
    expect(normalizeReportDraft(draft('  G123  ')).trainIdentifier).toBe('G123')
  })

  it('normalizes a blank value to null', () => {
    expect(normalizeReportDraft(draft('   ')).trainIdentifier).toBeNull()
  })

  it('normalizes an empty string to null', () => {
    expect(normalizeReportDraft(draft('')).trainIdentifier).toBeNull()
  })

  it('keeps null as null', () => {
    expect(normalizeReportDraft(draft(null)).trainIdentifier).toBeNull()
  })

  it('preserves internal whitespace', () => {
    expect(normalizeReportDraft(draft(' G 123 ')).trainIdentifier).toBe('G 123')
  })
})

describe('payloadsEqual', () => {
  it('treats two logically identical drafts as equal after normalization', () => {
    const a = normalizeReportDraft(draft('  G123  '))
    const b = normalizeReportDraft(draft('G123'))
    expect(payloadsEqual(a, b)).toBe(true)
  })

  it('treats equivalent instants in different textual forms as equal', () => {
    const a = normalizeReportDraft(draft(null))
    const b = { ...a, occurredAt: '2026-01-01T13:00:00+01:00' } // same instant as 12:00Z
    expect(payloadsEqual(a, b)).toBe(true)
  })

  it('treats a different settlement as unequal', () => {
    const a = normalizeReportDraft(draft(null))
    const b = { ...a, settlementId: 'settlement-2' }
    expect(payloadsEqual(a, b)).toBe(false)
  })
})
