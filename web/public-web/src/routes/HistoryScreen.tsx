import { useEffect, useRef } from 'react'
import { strings } from '../strings'
import { useReportHistory } from '../hooks/useReportHistory'
import { useRepositories } from '../repositoryContext'
import { HistoryItemCard } from '../components/HistoryItemCard'

export function HistoryScreen() {
  const { reportRepository } = useRepositories()
  const history = useReportHistory()

  // useReportHistory() loads asynchronously, so `history` is still [] on the very first
  // render - an effect with an empty dependency array would capture that stale empty array
  // and never actually refresh anything (caught by a real end-to-end run against a live
  // backend, not by a mocked-IndexedDB unit test, which never surfaces this ordering).
  // This ref makes the one-time refresh fire exactly once, on whichever render is the first
  // to see real data - never repeatedly as refreshAll's own writes update `history` again.
  const hasRefreshedOnce = useRef(false)
  useEffect(() => {
    if (hasRefreshedOnce.current || history.length === 0) return
    hasRefreshedOnce.current = true
    const submittedIds = history.filter((item) => item.submissionState === 'SUBMITTED').map((item) => item.clientSubmissionId)
    if (submittedIds.length > 0) {
      reportRepository.refreshAll(submittedIds)
    }
  }, [history, reportRepository])

  return (
    <section className="screen history-screen">
      <div className="screen-topbar">
        <h1>{strings.historyTitle}</h1>
      </div>

      <div className="card">
        <p className="muted" style={{ margin: 0 }}>
          {strings.historyStorageNotice}
        </p>
      </div>

      {history.length === 0 ? (
        <p className="muted">{strings.historyEmpty}</p>
      ) : (
        <ul className="history-list">
          {history.map((item) => (
            <li key={item.clientSubmissionId}>
              <HistoryItemCard
                record={item}
                onRetry={() => reportRepository.retry(item.clientSubmissionId)}
                onRefresh={() => reportRepository.refreshStatus(item.clientSubmissionId)}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
