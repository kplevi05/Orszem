import { useEffect, useReducer } from 'react'
import { useNavigate } from 'react-router-dom'
import { strings } from '../strings'
import { useRepositories } from '../repositoryContext'
import { Step1Form } from '../components/Step1Form'
import { Step2Form } from '../components/Step2Form'
import { SuccessView } from '../components/SuccessView'
import { BackIcon, CloseIcon } from '../components/NavIcons'
import { railwayLineStepFor, resolvedRailwayLineId } from '../domain/lineDecision'
import {
  initialNewReportState,
  isStep1Valid,
  isStep2Valid,
  newReportReducer,
  type UiErrorReason,
} from './newReportState'

const SETTLEMENT_SEARCH_DEBOUNCE_MS = 300
const MIN_SETTLEMENT_QUERY_LENGTH = 2

export function NewReportScreen() {
  const { reportRepository, catalogRepository, referenceRepository } = useRepositories()
  const navigate = useNavigate()
  const [state, dispatch] = useReducer(newReportReducer, undefined, initialNewReportState)

  // Debounced, stale-response-safe settlement search (§40).
  useEffect(() => {
    if (state.settlementQuery.length < MIN_SETTLEMENT_QUERY_LENGTH) return
    const token = state.settlementSearchToken
    const timer = setTimeout(() => {
      dispatch({ type: 'settlementSearchStarted' })
      referenceRepository.searchSettlements(state.settlementQuery).then((result) => {
        if (result.kind === 'loaded') {
          dispatch({ type: 'settlementSearchSucceeded', token, settlements: result.settlements })
        } else {
          dispatch({ type: 'settlementSearchFailed', token })
        }
      })
    }, SETTLEMENT_SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.settlementQuery, state.settlementSearchToken])

  // Stale-response-safe railway-line lookup (§43) - fires whenever a settlement is selected.
  useEffect(() => {
    const settlementId = state.selectedSettlement?.id
    if (!settlementId) return
    const token = state.lineLookupToken
    referenceRepository.railwayLinesOfSettlement(settlementId).then((result) => {
      const step = result.kind === 'loaded' ? railwayLineStepFor(result.response) : ({ kind: 'load-failed' } as const)
      dispatch({ type: 'lineLookupResolved', token, step })
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.selectedSettlement?.id, state.lineLookupToken])

  // Loads the catalogue once, on entering Step 2.
  useEffect(() => {
    if (state.step !== 'ESEMENY' || state.catalog.length > 0 || state.catalogLoading) return
    dispatch({ type: 'catalogLoadStarted' })
    catalogRepository.catalog().then((result) => {
      if (result.kind === 'loaded') {
        dispatch({ type: 'catalogLoadSucceeded', categories: result.categories })
      } else {
        dispatch({ type: 'catalogLoadFailed' })
      }
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.step, state.catalogLoading])

  async function handleSubmit(): Promise<void> {
    const settlement = state.selectedSettlement
    const eventTypeCode = state.selectedEventTypeCode
    if (!settlement || !eventTypeCode || !isStep1Valid(state) || !isStep2Valid(state)) return

    const category = state.catalog.find((c) => c.code === state.selectedCategoryCode)
    const eventType = category?.eventTypes.find((e) => e.code === eventTypeCode)
    const railwayLineId = resolvedRailwayLineId(state.lineStep, state.lineAnswer)
    const railwayLineDisplay =
      (state.lineAnswer.kind === 'chosen' ? state.lineAnswer.option.displayName : null) ??
      (state.lineStep.kind === 'single-inferred' ? state.lineStep.option.displayName : null)

    dispatch({ type: 'submitStarted' })
    const outcome = await reportRepository.submit(
      {
        occurredAt: state.occurredAt,
        trainIdentifierInput: state.trainIdentifierInput,
        settlementId: settlement.id,
        railwayLineId,
        eventTypeCode,
      },
      {
        settlementName: settlement.name,
        railwayLineDisplay,
        categoryDisplay: category?.displayName ?? '',
        eventTypeDisplay: eventType?.displayName ?? eventTypeCode,
      },
    )

    switch (outcome.kind) {
      case 'created':
      case 'replayed':
        dispatch({
          type: 'submitSucceeded',
          info: {
            publicReportId: outcome.record.publicReportId ?? '',
            eventTypeDisplay: eventType?.displayName ?? eventTypeCode,
            trainIdentifier: state.trainIdentifierInput.trim() || null,
            settlementName: settlement.name,
            status: strings.statusReceived,
          },
        })
        break
      case 'validation-failed':
        dispatch({ type: 'submitFailed', reason: 'VALIDATION', backToStep1: true })
        break
      case 'ambiguous-failure':
        dispatch({
          type: 'submitFailed',
          reason: outcome.code === 'REFERENCE_DATASET_UNAVAILABLE' ? 'REFERENCE_UNAVAILABLE' : 'NETWORK',
        })
        break
      case 'conflict':
        dispatch({ type: 'submitFailed', reason: 'CONFLICT' })
        break
      case 'access-lost':
        dispatch({ type: 'submitFailed', reason: 'ACCESS_LOST' })
        break
      case 'local-persistence-failed':
        dispatch({ type: 'submitFailed', reason: 'LOCAL_STORAGE' })
        break
    }
  }

  if (state.step === 'SUCCESS' && state.success) {
    return (
      <section className="screen new-report-screen">
        <SuccessView
          info={state.success}
          onNewReport={() => dispatch({ type: 'reset' })}
          onViewHistory={() => navigate('/elozmenyek')}
          onHome={() => navigate('/')}
        />
      </section>
    )
  }

  return (
    <section className="screen new-report-screen">
      <div className="screen-topbar">
        <h1>{strings.navNewReport}</h1>
        {state.step === 'ALAPADATOK' ? (
          <button type="button" className="icon-button" aria-label={strings.actionHome} onClick={() => navigate('/')}>
            <CloseIcon />
          </button>
        ) : (
          <button type="button" className="icon-button" aria-label={strings.actionBack} onClick={() => dispatch({ type: 'backToStep1' })}>
            <BackIcon />
          </button>
        )}
      </div>

      <div className="stepper">
        <span className={`stepper__item${state.step === 'ALAPADATOK' ? ' stepper__item--active' : ''}`}>{strings.step1Title}</span>
        <span className={`stepper__item${state.step === 'ESEMENY' ? ' stepper__item--active' : ''}`}>{strings.step2Title}</span>
      </div>

      {state.step === 'ALAPADATOK' ? <Step1Form state={state} dispatch={dispatch} /> : <Step2Form state={state} dispatch={dispatch} />}

      {state.error && <ErrorBanner reason={state.error} />}

      <div className="step-actions">
        {state.step === 'ALAPADATOK' ? (
          <button
            type="button"
            className="button button--primary"
            disabled={!isStep1Valid(state)}
            onClick={() => dispatch({ type: 'proceedToStep2' })}
          >
            {strings.actionNext}
          </button>
        ) : (
          <button
            type="button"
            className="button button--primary"
            disabled={!isStep2Valid(state) || state.submitting}
            onClick={handleSubmit}
          >
            {strings.actionSubmit}
          </button>
        )}
        {state.step === 'ESEMENY' && (
          <button type="button" className="button button--secondary" onClick={() => dispatch({ type: 'backToStep1' })}>
            {strings.actionBack}
          </button>
        )}
      </div>
    </section>
  )
}

function ErrorBanner({ reason }: { readonly reason: UiErrorReason }) {
  const text = {
    VALIDATION: strings.errorValidation,
    REFERENCE_UNAVAILABLE: strings.errorReferenceUnavailable,
    NETWORK: strings.errorNetwork,
    CONFLICT: strings.errorConflict,
    LOCAL_STORAGE: strings.errorLocalStorage,
    ACCESS_LOST: strings.errorAccessLost,
    GENERIC: strings.errorGeneric,
  }[reason]
  return (
    <p className="status-pill status-pill--error" role="alert" style={{ display: 'block', marginTop: '0.85rem' }}>
      {text}
    </p>
  )
}
