import type { CatalogCategory } from '../repository/catalogRepository'
import type { SettlementOption } from '../repository/referenceRepository'
import { isLineStepResolved, NOT_YET_ANSWERED, type LineAnswer, type RailwayLineStep } from '../domain/lineDecision'

export type ReportStep = 'ALAPADATOK' | 'ESEMENY' | 'SUCCESS'

export type UiErrorReason = 'VALIDATION' | 'REFERENCE_UNAVAILABLE' | 'NETWORK' | 'CONFLICT' | 'LOCAL_STORAGE' | 'ACCESS_LOST' | 'GENERIC'

export interface SuccessInfo {
  readonly publicReportId: string
  readonly eventTypeDisplay: string
  readonly trainIdentifier: string | null
  readonly settlementName: string
  readonly status: string
}

export interface NewReportState {
  readonly step: ReportStep
  readonly occurredAt: Date
  readonly trainIdentifierInput: string
  readonly settlementQuery: string
  readonly settlementResults: readonly SettlementOption[]
  readonly settlementSearching: boolean
  readonly selectedSettlement: SettlementOption | null
  readonly lineStep: RailwayLineStep
  readonly lineAnswer: LineAnswer
  readonly catalog: readonly CatalogCategory[]
  readonly catalogLoading: boolean
  readonly catalogFailed: boolean
  readonly selectedCategoryCode: string | null
  readonly selectedEventTypeCode: string | null
  readonly submitting: boolean
  readonly error: UiErrorReason | null
  readonly success: SuccessInfo | null
  /** Bumped on every settlement selection/query edit, so an in-flight line lookup can tell it has gone stale. */
  readonly lineLookupToken: number
  /** Bumped on every query edit, so an in-flight settlement search can tell it has gone stale. */
  readonly settlementSearchToken: number
}

export function initialNewReportState(): NewReportState {
  return {
    step: 'ALAPADATOK',
    occurredAt: new Date(),
    trainIdentifierInput: '',
    settlementQuery: '',
    settlementResults: [],
    settlementSearching: false,
    selectedSettlement: null,
    lineStep: { kind: 'not-applicable' },
    lineAnswer: NOT_YET_ANSWERED,
    catalog: [],
    catalogLoading: false,
    catalogFailed: false,
    selectedCategoryCode: null,
    selectedEventTypeCode: null,
    submitting: false,
    error: null,
    success: null,
    lineLookupToken: 0,
    settlementSearchToken: 0,
  }
}

export function isStep1Valid(state: NewReportState): boolean {
  return state.selectedSettlement !== null && isLineStepResolved(state.lineStep, state.lineAnswer)
}

export function isStep2Valid(state: NewReportState): boolean {
  return state.selectedEventTypeCode !== null
}

export type NewReportAction =
  | { readonly type: 'occurredAtChanged'; readonly value: Date }
  | { readonly type: 'trainIdentifierChanged'; readonly value: string }
  | { readonly type: 'settlementQueryChanged'; readonly value: string }
  | { readonly type: 'settlementSearchStarted' }
  | { readonly type: 'settlementSearchSucceeded'; readonly token: number; readonly settlements: readonly SettlementOption[] }
  | { readonly type: 'settlementSearchFailed'; readonly token: number }
  | { readonly type: 'settlementSelected'; readonly settlement: SettlementOption }
  | { readonly type: 'lineLookupResolved'; readonly token: number; readonly step: RailwayLineStep }
  | { readonly type: 'lineOptionChosen'; readonly option: { id: string; code: string; displayName: string } }
  | { readonly type: 'lineUnsure' }
  | { readonly type: 'proceedToStep2' }
  | { readonly type: 'backToStep1' }
  | { readonly type: 'catalogLoadStarted' }
  | { readonly type: 'catalogLoadSucceeded'; readonly categories: readonly CatalogCategory[] }
  | { readonly type: 'catalogLoadFailed' }
  | { readonly type: 'categorySelected'; readonly code: string }
  | { readonly type: 'eventTypeSelected'; readonly code: string }
  | { readonly type: 'submitStarted' }
  | { readonly type: 'submitSucceeded'; readonly info: SuccessInfo }
  | { readonly type: 'submitFailed'; readonly reason: UiErrorReason; readonly backToStep1?: boolean }
  | { readonly type: 'reset' }

export function newReportReducer(state: NewReportState, action: NewReportAction): NewReportState {
  switch (action.type) {
    case 'occurredAtChanged':
      return { ...state, occurredAt: action.value }
    case 'trainIdentifierChanged':
      return { ...state, trainIdentifierInput: action.value }
    case 'settlementQueryChanged':
      return {
        ...state,
        settlementQuery: action.value,
        selectedSettlement: null,
        settlementResults: [],
        lineStep: { kind: 'not-applicable' },
        lineAnswer: NOT_YET_ANSWERED,
        settlementSearchToken: state.settlementSearchToken + 1,
        lineLookupToken: state.lineLookupToken + 1,
      }
    case 'settlementSearchStarted':
      return { ...state, settlementSearching: true }
    case 'settlementSearchSucceeded':
      if (action.token !== state.settlementSearchToken) return state // stale - ignored (§40)
      return { ...state, settlementSearching: false, settlementResults: action.settlements }
    case 'settlementSearchFailed':
      if (action.token !== state.settlementSearchToken) return state
      return { ...state, settlementSearching: false, settlementResults: [] }
    case 'settlementSelected':
      return {
        ...state,
        selectedSettlement: action.settlement,
        settlementQuery: action.settlement.name,
        settlementResults: [],
        lineStep: { kind: 'loading' },
        lineAnswer: NOT_YET_ANSWERED,
        lineLookupToken: state.lineLookupToken + 1,
      }
    case 'lineLookupResolved':
      if (action.token !== state.lineLookupToken) return state // stale - ignored (§43)
      return { ...state, lineStep: action.step }
    case 'lineOptionChosen':
      return { ...state, lineAnswer: { kind: 'chosen', option: action.option } }
    case 'lineUnsure':
      return { ...state, lineAnswer: { kind: 'unsure' } }
    case 'proceedToStep2':
      return isStep1Valid(state) ? { ...state, step: 'ESEMENY' } : state
    case 'backToStep1':
      return { ...state, step: 'ALAPADATOK' }
    case 'catalogLoadStarted':
      return { ...state, catalogLoading: true, catalogFailed: false }
    case 'catalogLoadSucceeded':
      return { ...state, catalogLoading: false, catalogFailed: false, catalog: action.categories }
    case 'catalogLoadFailed':
      return { ...state, catalogLoading: false, catalogFailed: true }
    case 'categorySelected':
      return { ...state, selectedCategoryCode: action.code, selectedEventTypeCode: null }
    case 'eventTypeSelected':
      return { ...state, selectedEventTypeCode: action.code }
    case 'submitStarted':
      return { ...state, submitting: true, error: null }
    case 'submitSucceeded':
      return { ...state, submitting: false, step: 'SUCCESS', success: action.info }
    case 'submitFailed':
      return { ...state, submitting: false, error: action.reason, step: action.backToStep1 ? 'ALAPADATOK' : state.step }
    case 'reset':
      return initialNewReportState()
  }
}
