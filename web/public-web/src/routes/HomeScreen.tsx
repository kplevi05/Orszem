import { useNavigate } from 'react-router-dom'
import { strings } from '../strings'
import { useReportHistory } from '../hooks/useReportHistory'
import { HistoryItemCard } from '../components/HistoryItemCard'

export function HomeScreen() {
  const navigate = useNavigate()
  const history = useReportHistory()

  return (
    <section className="screen home-screen">
      <h1>{strings.homeHeadline}</h1>
      <p>{strings.homeBody}</p>

      <button type="button" className="button button--primary" onClick={() => navigate('/uj-bejelentes')}>
        {strings.homeNewReportCta}
      </button>

      <h2>{strings.homeRecentHistoryTitle}</h2>
      {history.length === 0 ? (
        <p className="muted">{strings.homeNoHistory}</p>
      ) : (
        <ul className="history-list">
          {history.slice(0, 3).map((item) => (
            <li key={item.clientSubmissionId}>
              <HistoryItemCard record={item} onRetry={() => undefined} onRefresh={() => undefined} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
