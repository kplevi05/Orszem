import { strings } from '../strings'
import type { SuccessInfo } from '../routes/newReportState'

export function SuccessView({
  info,
  onNewReport,
  onViewHistory,
  onHome,
}: {
  readonly info: SuccessInfo
  readonly onNewReport: () => void
  readonly onViewHistory: () => void
  readonly onHome: () => void
}) {
  return (
    <div className="step-form success-view">
      <h2>{strings.successTitle}</h2>
      <p>{strings.successReportId(info.publicReportId)}</p>
      <p>{strings.successEventType(info.eventTypeDisplay)}</p>
      {info.trainIdentifier && <p>{strings.successTrain(info.trainIdentifier)}</p>}
      <p>{strings.successSettlement(info.settlementName)}</p>
      <p>{strings.successStatus(strings.statusReceived)}</p>

      <button type="button" className="button button--primary" onClick={onNewReport}>
        {strings.actionNewReport}
      </button>
      <button type="button" className="button" onClick={onViewHistory}>
        {strings.actionViewHistory}
      </button>
      <button type="button" className="button" onClick={onHome}>
        {strings.actionHome}
      </button>
    </div>
  )
}
