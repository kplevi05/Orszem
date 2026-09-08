import type { Dispatch } from 'react'
import { strings } from '../strings'
import type { NewReportAction, NewReportState } from '../routes/newReportState'

function toLocalDateTimeInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function Step1Form({ state, dispatch }: { readonly state: NewReportState; readonly dispatch: Dispatch<NewReportAction> }) {
  return (
    <div className="step-form">
      <h2>{strings.step1Title}</h2>

      <label className="field">
        <span>{strings.fieldOccurredAt}</span>
        <input
          type="datetime-local"
          value={toLocalDateTimeInputValue(state.occurredAt)}
          onChange={(event) => {
            const value = event.target.value
            if (value) dispatch({ type: 'occurredAtChanged', value: new Date(value) })
          }}
        />
      </label>

      <label className="field">
        <span>{strings.fieldTrainIdentifier}</span>
        <input
          type="text"
          value={state.trainIdentifierInput}
          onChange={(event) => dispatch({ type: 'trainIdentifierChanged', value: event.target.value })}
        />
      </label>

      <label className="field">
        <span>{strings.fieldSettlement}</span>
        <input
          type="text"
          placeholder={strings.fieldSettlementHint}
          value={state.settlementQuery}
          onChange={(event) => dispatch({ type: 'settlementQueryChanged', value: event.target.value })}
          aria-describedby="settlement-results"
        />
      </label>
      {state.settlementSearching && <p className="muted">…</p>}
      {state.settlementResults.length > 0 && (
        <ul id="settlement-results" className="suggestion-list">
          {state.settlementResults.map((settlement) => (
            <li key={settlement.id}>
              <button type="button" className="suggestion" onClick={() => dispatch({ type: 'settlementSelected', settlement })}>
                {settlement.name}
                {settlement.countyName ? ` (${settlement.countyName})` : ''}
              </button>
            </li>
          ))}
        </ul>
      )}

      <RailwayLineSection state={state} dispatch={dispatch} />
    </div>
  )
}

function RailwayLineSection({ state, dispatch }: { readonly state: NewReportState; readonly dispatch: Dispatch<NewReportAction> }) {
  const step = state.lineStep
  switch (step.kind) {
    case 'not-applicable':
      return null
    case 'loading':
      return <p className="muted">…</p>
    case 'load-failed':
      return (
        <p className="status-badge status-badge--error" role="alert">
          {strings.catalogLoadFailed}
        </p>
      )
    case 'no-verified-candidate':
      return <p className="muted">{strings.lineNoneVerified}</p>
    case 'single-inferred':
      return <p className="muted">{strings.lineAutoIdentified(step.option.displayName)}</p>
    case 'requires-choice': {
      const unsureLabel = step.coverage === 'PARTIAL' ? strings.lineChooseUnsurePartial : strings.lineChooseUnsure
      return (
        <fieldset className="line-choice">
          <legend>{strings.lineChooseTitle}</legend>
          {step.options.map((option) => (
            <label key={option.id} className="radio-row">
              <input
                type="radio"
                name="railway-line"
                checked={state.lineAnswer.kind === 'chosen' && state.lineAnswer.option.id === option.id}
                onChange={() => dispatch({ type: 'lineOptionChosen', option })}
              />
              {option.displayName}
            </label>
          ))}
          <label className="radio-row">
            <input
              type="radio"
              name="railway-line"
              checked={state.lineAnswer.kind === 'unsure'}
              onChange={() => dispatch({ type: 'lineUnsure' })}
            />
            {unsureLabel}
          </label>
        </fieldset>
      )
    }
  }
}
