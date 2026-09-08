import type { Dispatch } from 'react'
import { strings } from '../strings'
import type { NewReportAction, NewReportState } from '../routes/newReportState'

export function Step2Form({ state, dispatch }: { readonly state: NewReportState; readonly dispatch: Dispatch<NewReportAction> }) {
  if (state.catalogLoading) {
    return (
      <div className="step-form">
        <h2>{strings.step2Title}</h2>
        <p className="muted">…</p>
      </div>
    )
  }

  if (state.catalogFailed) {
    return (
      <div className="step-form">
        <h2>{strings.step2Title}</h2>
        <p className="status-badge status-badge--error" role="alert">
          {strings.catalogLoadFailed}
        </p>
        <button type="button" className="button" onClick={() => dispatch({ type: 'catalogLoadStarted' })}>
          {strings.actionRetry}
        </button>
      </div>
    )
  }

  const selectedCategory = state.catalog.find((category) => category.code === state.selectedCategoryCode)

  return (
    <div className="step-form">
      <h2>{strings.step2Title}</h2>

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
