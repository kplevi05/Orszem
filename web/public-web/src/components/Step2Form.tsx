import type { Dispatch } from 'react'
import { strings } from '../strings'
import type { NewReportAction, NewReportState } from '../routes/newReportState'

export function Step2Form({ state, dispatch }: { readonly state: NewReportState; readonly dispatch: Dispatch<NewReportAction> }) {
  if (state.catalogLoading) {
    return (
      <div className="card step-form">
        <p className="muted">…</p>
      </div>
    )
  }

  if (state.catalogFailed) {
    return (
      <div className="card step-form">
        <p className="status-pill status-pill--error" role="alert" style={{ display: 'block', marginBottom: '0.75rem' }}>
          {strings.catalogLoadFailed}
        </p>
        <button type="button" className="button button--soft button--inline" onClick={() => dispatch({ type: 'catalogLoadStarted' })}>
          {strings.actionRetry}
        </button>
      </div>
    )
  }

  const selectedCategory = state.catalog.find((category) => category.code === state.selectedCategoryCode)

  return (
    <div className="card step-form">
      <fieldset className="category-choice">
        <legend>{strings.categoryChooseTitle}</legend>
        <div className="chip-row">
          {state.catalog.map((category) => (
            <button
              key={category.code}
              type="button"
              className={`chip${state.selectedCategoryCode === category.code ? ' chip--selected' : ''}`}
              onClick={() => dispatch({ type: 'categorySelected', code: category.code })}
            >
              {category.displayName}
            </button>
          ))}
        </div>
      </fieldset>

      {selectedCategory && (
        <fieldset className="event-type-choice">
          <legend>{strings.eventTypeChooseTitle}</legend>
          {selectedCategory.eventTypes.map((eventType) => (
            <label key={eventType.code} className="radio-row">
              <input
                type="radio"
                name="event-type"
                checked={state.selectedEventTypeCode === eventType.code}
                onChange={() => dispatch({ type: 'eventTypeSelected', code: eventType.code })}
              />
              {eventType.displayName}
            </label>
          ))}
        </fieldset>
      )}
    </div>
  )
}
