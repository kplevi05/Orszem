export type LineCoverage = 'COMPLETE' | 'PARTIAL'

/** One railway line with a verified relation to the selected settlement. */
export interface RailwayLineOption {
  readonly id: string
  readonly code: string
  readonly displayName: string
}

/** The backend's `{coverage, items}` contract for one settlement (ADR 0007). */
export interface RailwayLinesForSettlement {
  readonly coverage: LineCoverage
  readonly items: readonly RailwayLineOption[]
}

/**
 * The COMPLETE/PARTIAL railway-line UX matrix (Phase 5 brief §42), computed once the
 * railway-line lookup for a settlement resolves. `coverage` is never used to infer
 * completeness from item count - it is read directly from the backend response.
 */
export type RailwayLineStep =
  | { readonly kind: 'not-applicable' }
  | { readonly kind: 'loading' }
  | { readonly kind: 'load-failed' }
  /**
   * Zero verified candidates, under either coverage. The UI explains that nothing is
   * currently on record - never that no railway physically exists (ADR 0006/0007's own
   * distinction, carried into the client). No selector; the report submits with
   * `railwayLineId: null`.
   */
  | { readonly kind: 'no-verified-candidate'; readonly coverage: LineCoverage }
  /**
   * Exactly one COMPLETE candidate. May be displayed for reassurance, but the user is
   * never required to select it, and the client does not invent routing: `railwayLineId`
   * is still sent as `null`, letting the backend's own COMPLETE inference resolve it.
   */
  | { readonly kind: 'single-inferred'; readonly coverage: LineCoverage; readonly option: RailwayLineOption }
  /**
   * Two-or-more COMPLETE candidates, or one-or-more PARTIAL candidates. The user must
   * explicitly pick one, or explicitly say they are unsure - never a default first item,
   * and PARTIAL coverage never auto-selects even a single candidate.
   */
  | { readonly kind: 'requires-choice'; readonly coverage: LineCoverage; readonly options: readonly RailwayLineOption[] }

export function railwayLineStepFor(response: RailwayLinesForSettlement): RailwayLineStep {
  if (response.items.length === 0) {
    return { kind: 'no-verified-candidate', coverage: response.coverage }
  }
  if (response.coverage === 'COMPLETE' && response.items.length === 1) {
    return { kind: 'single-inferred', coverage: response.coverage, option: response.items[0]! }
  }
  return { kind: 'requires-choice', coverage: response.coverage, options: response.items }
}

/** What the user has answered for a `requires-choice` step. */
export type LineAnswer =
  | { readonly kind: 'not-yet-answered' }
  | { readonly kind: 'chosen'; readonly option: RailwayLineOption }
  /** "Nem tudom / nem vagyok biztos benne" (COMPLETE) or "Nem tudom / másik vonal" (PARTIAL) - explicit, valid, sends null. */
  | { readonly kind: 'unsure' }

export const NOT_YET_ANSWERED: LineAnswer = { kind: 'not-yet-answered' }
export const UNSURE: LineAnswer = { kind: 'unsure' }

/** The `railwayLineId` this step contributes to the submission, given the current answer. */
export function resolvedRailwayLineId(step: RailwayLineStep, answer: LineAnswer): string | null {
  if (step.kind === 'requires-choice' && answer.kind === 'chosen') {
    return answer.option.id
  }
  return null
}

/** Whether Step 1 may proceed to Step 2 given the current railway-line state. */
export function isLineStepResolved(step: RailwayLineStep, answer: LineAnswer): boolean {
  switch (step.kind) {
    case 'not-applicable':
    case 'loading':
    case 'load-failed':
      return false
    case 'no-verified-candidate':
    case 'single-inferred':
      return true
    case 'requires-choice':
      return answer.kind !== 'not-yet-answered'
  }
}
