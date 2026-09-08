import { useEffect } from 'react'
import { strings } from '../strings'
import { useReportHistory } from '../hooks/useReportHistory'
import { useRepositories } from '../repositoryContext'
import { HistoryItemCard } from '../components/HistoryItemCard'

export function HistoryScreen() {
  const { reportRepository } = useRepositories()
  const history = useReportHistory()

  useEffect(() => {
    const submittedIds = history.filter((item) => item.submissionState === 'SUBMITTED').map((item) => item.clientSubmissionId)
    if (submittedIds.length > 0) {
      reportRepository.refreshAll(submittedIds)
    }
    // Only on mount, matching Android's "optionally on entering the history screen" (§17) -
    // not on every history change, which would refresh-loop after refreshAll's own writes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <section className="screen history-screen">
      <h1>{strings.historyTitle}</h1>
      <p className="muted">{strings.historyStorageNotice}</p>

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
