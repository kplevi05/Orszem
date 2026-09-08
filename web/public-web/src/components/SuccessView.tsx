import { strings } from '../strings'
import { CheckIcon } from './NavIcons'
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
    <div className="card success-view">
      <div className="success-check">
        <CheckIcon />
      </div>
      <h1 className="page-title">{strings.successTitle}</h1>

      <div className="card success-details">
        <p className="meta-line">{strings.successReportId(info.publicReportId)}</p>
        <p className="meta-line">{strings.successEventType(info.eventTypeDisplay)}</p>
        {info.trainIdentifier && <p className="meta-line">{strings.successTrain(info.trainIdentifier)}</p>}
        <p className="meta-line">{strings.successSettlement(info.settlementName)}</p>
        <p className="meta-line">{strings.successStatus(strings.statusReceived)}</p>
      </div>

      <div className="success-actions">
        <button type="button" className="button button--primary" onClick={onNewReport}>
          {strings.actionNewReport}
        </button>
        <button type="button" className="button button--secondary" onClick={onViewHistory}>
          {strings.actionViewHistory}
        </button>
        <button type="button" className="button button--soft" onClick={onHome}>
          {strings.actionHome}
        </button>
      </div>
    </div>
  )
}
