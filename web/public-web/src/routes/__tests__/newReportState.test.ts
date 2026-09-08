import { describe, expect, it } from 'vitest'
import { initialNewReportState, isStep1Valid, newReportReducer } from '../newReportState'

describe('settlement selection clears any prior railway-line decision (§40)', () => {
  it('editing the settlement query after a selection clears both the selection and the line step', () => {
    let state = initialNewReportState()
    state = newReportReducer(state, {
      type: 'settlementSelected',
      settlement: { id: 's1', name: 'Alfaváros', countyName: null },
    })
    state = newReportReducer(state, {
      type: 'lineLookupResolved',
      token: state.lineLookupToken,
      step: { kind: 'no-verified-candidate', coverage: 'COMPLETE' },
    })
    expect(state.lineStep.kind).toBe('no-verified-candidate')

    state = newReportReducer(state, { type: 'settlementQueryChanged', value: 'Something else' })

    expect(state.selectedSettlement).toBeNull()
    expect(state.lineStep).toEqual({ kind: 'not-applicable' })
  })
})

describe('stale settlement search results are ignored (§40)', () => {
  it('a settlement-search result carrying an old token is discarded', () => {
    let state = initialNewReportState()
    const staleToken = state.settlementSearchToken

    // The query changes again, bumping the token before the stale response arrives.
    state = newReportReducer(state, { type: 'settlementQueryChanged', value: 'Alfa' })
    state = newReportReducer(state, {
      type: 'settlementSearchSucceeded',
      token: staleToken,
      settlements: [{ id: 'stale', name: 'Stale result', countyName: null }],
    })

    expect(state.settlementResults).toEqual([])
  })

  it('a settlement-search result carrying the current token is applied', () => {
    let state = initialNewReportState()
    state = newReportReducer(state, { type: 'settlementQueryChanged', value: 'Alfa' })
    const currentToken = state.settlementSearchToken

    state = newReportReducer(state, {
      type: 'settlementSearchSucceeded',
      token: currentToken,
      settlements: [{ id: 'fresh', name: 'Fresh result', countyName: null }],
    })

    expect(state.settlementResults).toEqual([{ id: 'fresh', name: 'Fresh result', countyName: null }])
  })
})

describe('a stale railway-line lookup for a previously-selected settlement is ignored (§43)', () => {
  it('resolving with an old lineLookupToken is discarded once a newer settlement selection has started', () => {
    let state = initialNewReportState()
    state = newReportReducer(state, {
      type: 'settlementSelected',
      settlement: { id: 'settlement-a', name: 'Alfaváros', countyName: null },
    })
    const staleToken = state.lineLookupToken

    // Switch to a different settlement before A's slow response arrives.
    state = newReportReducer(state, {
      type: 'settlementSelected',
      settlement: { id: 'settlement-b', name: 'Beta', countyName: null },
    })
    const currentToken = state.lineLookupToken

    // The stale response for A arrives late.
    state = newReportReducer(state, {
      type: 'lineLookupResolved',
      token: staleToken,
      step: { kind: 'no-verified-candidate', coverage: 'COMPLETE' },
    })
    expect(state.lineStep.kind).toBe('loading') // still waiting on B, untouched by the stale A response

    // B's response arrives.
    state = newReportReducer(state, {
      type: 'lineLookupResolved',
      token: currentToken,
      step: { kind: 'requires-choice', coverage: 'PARTIAL', options: [{ id: 'line-1', code: '1', displayName: 'Line 1' }] },
    })
    expect(state.lineStep).toEqual({
      kind: 'requires-choice',
      coverage: 'PARTIAL',
      options: [{ id: 'line-1', code: '1', displayName: 'Line 1' }],
    })
    expect(state.selectedSettlement?.id).toBe('settlement-b')
  })
})

describe('step 1 validity', () => {
  it('is false with no settlement selected', () => {
    expect(isStep1Valid(initialNewReportState())).toBe(false)
  })

  it('is true once the settlement is selected and a no-candidate line step needs no answer', () => {
    let state = initialNewReportState()
    state = newReportReducer(state, {
      type: 'settlementSelected',
      settlement: { id: 's1', name: 'Alfaváros', countyName: null },
    })
    state = newReportReducer(state, {
      type: 'lineLookupResolved',
      token: state.lineLookupToken,
      step: { kind: 'no-verified-candidate', coverage: 'COMPLETE' },
    })
    expect(isStep1Valid(state)).toBe(true)
  })

  it('is false while a requires-choice line step has not been answered yet', () => {
    let state = initialNewReportState()
    state = newReportReducer(state, {
      type: 'settlementSelected',
      settlement: { id: 's1', name: 'Alfaváros', countyName: null },
    })
    state = newReportReducer(state, {
      type: 'lineLookupResolved',
      token: state.lineLookupToken,
      step: { kind: 'requires-choice', coverage: 'COMPLETE', options: [{ id: 'l1', code: '1', displayName: 'Line 1' }] },
    })
    expect(isStep1Valid(state)).toBe(false)
  })
})
