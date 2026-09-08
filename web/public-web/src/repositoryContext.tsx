import { createContext, useContext, type ReactNode } from 'react'
import { createCatalogRepository, type CatalogRepository } from './repository/catalogRepository'
import { createReferenceRepository, type ReferenceRepository } from './repository/referenceRepository'
import { createReportRepository, type ReportRepository } from './repository/reportRepository'

export interface Repositories {
  readonly reportRepository: ReportRepository
  readonly catalogRepository: CatalogRepository
  readonly referenceRepository: ReferenceRepository
}

/** Production singletons - constructed once per page load. Plain factories, no DI framework (mirrors the Android `AppContainer` convention). */
export function createDefaultRepositories(): Repositories {
  return {
    reportRepository: createReportRepository(),
    catalogRepository: createCatalogRepository(),
    referenceRepository: createReferenceRepository(),
  }
}

const RepositoriesContext = createContext<Repositories | null>(null)

export function RepositoriesProvider({ value, children }: { readonly value: Repositories; readonly children: ReactNode }) {
  return <RepositoriesContext.Provider value={value}>{children}</RepositoriesContext.Provider>
}

export function useRepositories(): Repositories {
  const value = useContext(RepositoriesContext)
  if (!value) throw new Error('useRepositories() called outside RepositoriesProvider')
  return value
}
