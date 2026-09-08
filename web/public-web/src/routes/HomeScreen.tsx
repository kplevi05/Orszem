import { useNavigate } from 'react-router-dom'
import { strings } from '../strings'
import { useReportHistory } from '../hooks/useReportHistory'
import { HistoryItemCard } from '../components/HistoryItemCard'

export function HomeScreen() {
  const navigate = useNavigate()
  const history = useReportHistory()
  const recent = history.slice(0, 3)

  return (
    <section className="screen home-screen">
      <div className="card hero-card">
        <span className="badge">{strings.homeBadge}</span>
        <h1 className="page-title">{strings.homeHeadline}</h1>
        <p className="subtitle">{strings.homeBody}</p>

        <div className="quick-grid">
          <div className="quick-card">
            <h3>{strings.homeQuickNewReportTitle}</h3>
            <p className="muted">{strings.homeQuickNewReportBody}</p>
            <button type="button" className="button button--primary button--inline" onClick={() => navigate('/uj-bejelentes')}>
              {strings.homeQuickNewReportCta}
            </button>
          </div>
          <div className="quick-card">
            <h3>{strings.homeQuickHistoryTitle}</h3>
            <p className="muted">{strings.homeQuickHistoryBody}</p>
            <button type="button" className="button button--secondary button--inline" onClick={() => navigate('/elozmenyek')}>
              {strings.homeQuickHistoryCta}
            </button>
          </div>
        </div>
      </div>

      <div className="section-header">
        <h2>{strings.homeRecentHistoryTitle}</h2>
        {history.length > 0 && <span className="muted">{strings.homeRecentHistoryCount(history.length)}</span>}
      </div>

      {recent.length === 0 ? (
        <p className="muted">{strings.homeNoHistory}</p>
      ) : (
        <ul className="history-list">
          {recent.map((item) => (
            <li key={item.clientSubmissionId}>
              <HistoryItemCard record={item} onRetry={() => undefined} onRefresh={() => undefined} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
