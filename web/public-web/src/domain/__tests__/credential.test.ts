import { describe, expect, it } from 'vitest'
import { generateClientSubmissionId, generateReportAccessCredential, parseStoredCredential } from '../credential'

const PATTERN = /^pr_[A-Za-z0-9_-]{43}$/

describe('generateReportAccessCredential', () => {
  it('matches the exact backend shape', () => {
    expect(generateReportAccessCredential()).toMatch(PATTERN)
  })

  it('never produces two equal values - real crypto.getRandomValues, not Math.random', () => {
    const a = generateReportAccessCredential()
    const b = generateReportAccessCredential()
    expect(a).not.toBe(b)
  })

  it('generating many credentials never collides and never produces a malformed value', () => {
    const seen = new Set<string>()
    for (let i = 0; i < 500; i++) {
      const credential = generateReportAccessCredential()
      expect(credential).toMatch(PATTERN)
      expect(seen.has(credential)).toBe(false)
      seen.add(credential)
    }
  })
})

describe('parseStoredCredential', () => {
  it('accepts a well-formed generated value and round-trips it exactly', () => {
    const generated = generateReportAccessCredential()
    expect(parseStoredCredential(generated)).toBe(generated)
  })

  it('rejects anything not matching the exact shape', () => {
    const malformed = [
      'not-even-close',
      'pr_tooshort',
      'wrongprefix_' + 'x'.repeat(43),
      'pr_' + 'x'.repeat(42),
      'pr_' + 'x'.repeat(44),
      'pr_' + '!'.repeat(43),
      '',
    ]
    for (const value of malformed) {
      expect(parseStoredCredential(value)).toBeNull()
    }
  })
})

describe('generateClientSubmissionId', () => {
  it('produces a valid, non-repeating UUID', () => {
    const a = generateClientSubmissionId()
    const b = generateClientSubmissionId()
    expect(a).toMatch(/^[0-9a-f-]{36}$/)
    expect(a).not.toBe(b)
  })
})
