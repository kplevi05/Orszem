import { publicStatusLabel, strings } from '../strings'
import type { PublicReportStatus } from '../domain/submissionState'
import type { ReportRecord } from '../storage/db'

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('hu-HU', { dateStyle: 'medium', timeStyle: 'short' })
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
  return (
    <article className="card history-card">
      <h3>{record.eventTypeDisplaySnapshot}</h3>
      <p>{record.settlementNameSnapshot}</p>
      {record.trainIdentifier && <p className="muted">{record.trainIdentifier}</p>}
      <p className="muted">{formatDateTime(record.occurredAt)}</p>

      {record.submissionState === 'SUBMITTED' && (
        <>
          {record.publicStatus && <p className="status-badge">{publicStatusLabel(record.publicStatus as PublicReportStatus)}</p>}
          {record.lastStatusCheckedAt && <p className="muted">{strings.historyLastChecked(formatDateTime(record.lastStatusCheckedAt))}</p>}
          <button type="button" className="button" onClick={onRefresh}>
            {strings.historyRefresh}
          </button>
        </>
      )}

      {record.submissionState === 'PENDING' && (
        <>
          <p className="status-badge status-badge--warning" role="status">
            {strings.historyUnconfirmed}
          </p>
          <button type="button" className="button button--primary" onClick={onRetry}>
            {strings.actionRetry}
          </button>
        </>
      )}

      {record.submissionState === 'ACCESS_LOST' && (
        <p className="status-badge status-badge--error" role="alert">
          {strings.historyAccessLost}
        </p>
      )}

      {record.submissionState === 'CONFLICT' && (
        <p className="status-badge status-badge--error" role="alert">
          {strings.historyConflict}
        </p>
      )}
    </article>
  )
}
