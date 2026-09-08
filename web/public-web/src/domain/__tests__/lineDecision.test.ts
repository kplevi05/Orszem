import { describe, expect, it } from 'vitest'
import {
  isLineStepResolved,
  NOT_YET_ANSWERED,
  railwayLineStepFor,
  resolvedRailwayLineId,
  UNSURE,
  type RailwayLineOption,
} from '../lineDecision'

function option(name = '1'): RailwayLineOption {
  return { id: `line-${name}`, code: name, displayName: `Line ${name}` }
}

describe('the §42 COMPLETE/PARTIAL matrix', () => {
  it('COMPLETE with zero items - no selector, sends null', () => {
    const step = railwayLineStepFor({ coverage: 'COMPLETE', items: [] })
    expect(step.kind).toBe('no-verified-candidate')
    expect(isLineStepResolved(step, NOT_YET_ANSWERED)).toBe(true)
    expect(resolvedRailwayLineId(step, NOT_YET_ANSWERED)).toBeNull()
  })

  it('PARTIAL with zero items - no selector, sends null, never claims no railway exists', () => {
    const step = railwayLineStepFor({ coverage: 'PARTIAL', items: [] })
    expect(step.kind).toBe('no-verified-candidate')
    if (step.kind === 'no-verified-candidate') expect(step.coverage).toBe('PARTIAL')
  })

  it('COMPLETE with exactly one item - auto-displayed, still sends null, never required to select', () => {
    const single = option()
    const step = railwayLineStepFor({ coverage: 'COMPLETE', items: [single] })
    expect(step.kind).toBe('single-inferred')
    if (step.kind === 'single-inferred') expect(step.option).toEqual(single)
    expect(isLineStepResolved(step, NOT_YET_ANSWERED)).toBe(true)
    expect(resolvedRailwayLineId(step, NOT_YET_ANSWERED)).toBeNull()
  })

  it('PARTIAL with exactly one item - NEVER auto-selected, user must explicitly answer', () => {
    const single = option()
    const step = railwayLineStepFor({ coverage: 'PARTIAL', items: [single] })
    expect(step.kind).toBe('requires-choice')
    expect(isLineStepResolved(step, NOT_YET_ANSWERED)).toBe(false)
    expect(isLineStepResolved(step, UNSURE)).toBe(true)
    expect(isLineStepResolved(step, { kind: 'chosen', option: single })).toBe(true)
  })

  it('COMPLETE with two or more items requires an explicit choice, no default first item', () => {
    const a = option('1')
    const b = option('2')
    const step = railwayLineStepFor({ coverage: 'COMPLETE', items: [a, b] })
    expect(step.kind).toBe('requires-choice')
    expect(isLineStepResolved(step, NOT_YET_ANSWERED)).toBe(false)
    expect(resolvedRailwayLineId(step, NOT_YET_ANSWERED)).toBeNull()
  })

  it('choosing a specific option resolves to that option id', () => {
    const a = option('1')
    const b = option('2')
    const step = railwayLineStepFor({ coverage: 'COMPLETE', items: [a, b] })
    expect(resolvedRailwayLineId(step, { kind: 'chosen', option: a })).toBe(a.id)
    expect(resolvedRailwayLineId(step, { kind: 'chosen', option: b })).toBe(b.id)
  })

  it('choosing unsure resolves to null but is still a valid, resolved answer', () => {
    const step = railwayLineStepFor({ coverage: 'PARTIAL', items: [option()] })
    expect(isLineStepResolved(step, UNSURE)).toBe(true)
    expect(resolvedRailwayLineId(step, UNSURE)).toBeNull()
  })

  it('loading/not-applicable/load-failed states are never resolved', () => {
    expect(isLineStepResolved({ kind: 'loading' }, NOT_YET_ANSWERED)).toBe(false)
    expect(isLineStepResolved({ kind: 'not-applicable' }, NOT_YET_ANSWERED)).toBe(false)
    expect(isLineStepResolved({ kind: 'load-failed' }, NOT_YET_ANSWERED)).toBe(false)
  })
})
