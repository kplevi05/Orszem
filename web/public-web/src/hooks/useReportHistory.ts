import { useEffect, useState } from 'react'
import { useRepositories } from '../repositoryContext'
import type { ReportRecord } from '../storage/db'

/** Reactively reflects the local report history - re-reads whenever any repository write happens. */
export function useReportHistory(): readonly ReportRecord[] {
  const { reportRepository } = useRepositories()
  const [history, setHistory] = useState<readonly ReportRecord[]>([])

  useEffect(() => {
    let cancelled = false
    function reload(): void {
      reportRepository.listHistory().then((records) => {
        if (!cancelled) setHistory(records)
      })
    }
    reload()
    const unsubscribe = reportRepository.subscribe(reload)
    return () => {
      cancelled = true
      unsubscribe()
    }
  }, [reportRepository])

  return history
}
