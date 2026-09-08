import { publicStatusLabel, strings } from '../strings'
import type { PublicReportStatus } from '../domain/submissionState'
import type { ReportRecord } from '../storage/db'

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('hu-HU', { dateStyle: 'medium', timeStyle: 'short' })
}

function statusPillClass(status: PublicReportStatus): string {
  switch (status) {
    case 'RECEIVED':
      return 'status-pill status-pill--received'
    case 'PROCESSING':
      return 'status-pill status-pill--processing'
    case 'CLOSED':
      return 'status-pill status-pill--closed'
  }
}

export function HistoryItemCard({
  record,
  onRetry,
  onRefresh,
}: {
  readonly record: ReportRecord
  readonly onRetry: () => void
  readonly onRefresh: () => void
}) {
  const cardClassName =
    'card history-card' +
    (record.submissionState === 'PENDING' ? ' pending-card' : '') +
    (record.submissionState === 'ACCESS_LOST' || record.submissionState === 'CONFLICT' ? ' error-card' : '')

  return (
    <article className={cardClassName}>
      <div className="report-head">
        <h3>{record.eventTypeDisplaySnapshot}</h3>
        {record.submissionState === 'SUBMITTED' && record.publicStatus && (
          <span className={statusPillClass(record.publicStatus)}>{publicStatusLabel(record.publicStatus)}</span>
        )}
        {record.submissionState === 'PENDING' && <span className="status-pill status-pill--pending">{strings.historyUnconfirmed}</span>}
        {(record.submissionState === 'ACCESS_LOST' || record.submissionState === 'CONFLICT') && (
          <span className="status-pill status-pill--error">
            {record.submissionState === 'ACCESS_LOST' ? strings.historyAccessLost : strings.historyConflict}
          </span>
        )}
      </div>

      <p className="meta-line">{record.settlementNameSnapshot}</p>
      {record.trainIdentifier && <p className="meta-line">{record.trainIdentifier}</p>}
      <p className="meta-line">{formatDateTime(record.occurredAt)}</p>

      {record.submissionState === 'SUBMITTED' && (
        <>
          {record.lastStatusCheckedAt && <p className="meta-line">{strings.historyLastChecked(formatDateTime(record.lastStatusCheckedAt))}</p>}
          <button type="button" className="button button--soft button--inline" onClick={onRefresh}>
            {strings.historyRefresh}
          </button>
        </>
      )}

      {record.submissionState === 'PENDING' && (
        <button type="button" className="button button--primary button--inline" onClick={onRetry}>
          {strings.actionRetry}
        </button>
      )}
    </article>
  )
}
