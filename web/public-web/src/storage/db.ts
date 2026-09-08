import type { PublicReportStatus, SubmissionState } from '../domain/submissionState'

/**
 * One local report-history record (Phase 5 brief §32, mirroring the Android Room entity's
 * field set - `android/public-app/.../ReportHistoryEntity.kt`). Never stores a raw
 * credential, GPS coordinates, or free text.
 */
export interface ReportRecord {
  readonly clientSubmissionId: string
  readonly publicReportId: string | null
  /** AES-GCM ciphertext of the UTF-8 credential bytes - never the plaintext credential. */
  readonly encryptedCredentialCiphertext: ArrayBuffer
  readonly encryptedCredentialIv: ArrayBuffer
  readonly occurredAt: string
  readonly trainIdentifier: string | null
  readonly settlementId: string
  readonly settlementNameSnapshot: string
  readonly railwayLineId: string | null
  readonly railwayLineDisplaySnapshot: string | null
  readonly categoryDisplaySnapshot: string
  readonly eventTypeCode: string
  readonly eventTypeDisplaySnapshot: string
  readonly submissionState: SubmissionState
  readonly publicStatus: PublicReportStatus | null
  readonly serverSubmittedAt: string | null
  readonly localCreatedAt: string
  readonly lastStatusCheckedAt: string | null
  readonly lastErrorCode: string | null
}

/** The single origin-local AES-GCM key record. `key` is a non-extractable `CryptoKey`, structured-cloned as-is. */
export interface CryptoKeyRecord {
  readonly id: 'report-credential-key'
  readonly key: CryptoKey
}

const DB_NAME = 'orszem-public-reports'
const DB_VERSION = 1
export const REPORTS_STORE = 'reports'
export const CRYPTO_STORE = 'crypto'

let dbPromise: Promise<IDBDatabase> | null = null

/**
 * Opens (and, on first use, creates) the local database. Cached per page load - repeatedly
 * calling this does not reopen the connection.
 */
export function openDatabase(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise

  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)

    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(REPORTS_STORE)) {
        db.createObjectStore(REPORTS_STORE, { keyPath: 'clientSubmissionId' })
      }
      if (!db.objectStoreNames.contains(CRYPTO_STORE)) {
        db.createObjectStore(CRYPTO_STORE, { keyPath: 'id' })
      }
    }

    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('failed to open the local database'))
  })

  return dbPromise
}

function promisifyRequest<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'))
  })
}

function promisifyTransaction(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error ?? new Error('IndexedDB transaction failed'))
    transaction.onabort = () => reject(transaction.error ?? new Error('IndexedDB transaction aborted'))
  })
}

// A minimal pub-sub so React components can re-render on any local write, without a full
// reactive-query library for three screens (mirrors §51's "smallest maintainable" stance).
const listeners = new Set<() => void>()
export function subscribeToReportsChanged(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
function notifyReportsChanged(): void {
  for (const listener of listeners) listener()
}

export async function putReport(record: ReportRecord): Promise<void> {
  const db = await openDatabase()
  const tx = db.transaction(REPORTS_STORE, 'readwrite')
  tx.objectStore(REPORTS_STORE).put(record)
  await promisifyTransaction(tx)
  notifyReportsChanged()
}

/** Fails (throws) rather than overwriting if a record with this id already exists - see `insertReport` callers. */
export async function insertReport(record: ReportRecord): Promise<void> {
  const db = await openDatabase()
  const tx = db.transaction(REPORTS_STORE, 'readwrite')
  const existing = await promisifyRequest(tx.objectStore(REPORTS_STORE).get(record.clientSubmissionId))
  if (existing) {
    tx.abort()
    throw new Error(`duplicate clientSubmissionId ${record.clientSubmissionId}`)
  }
  tx.objectStore(REPORTS_STORE).add(record)
  await promisifyTransaction(tx)
  notifyReportsChanged()
}

export async function getReport(clientSubmissionId: string): Promise<ReportRecord | undefined> {
  const db = await openDatabase()
  const tx = db.transaction(REPORTS_STORE, 'readonly')
  const result = await promisifyRequest<ReportRecord | undefined>(tx.objectStore(REPORTS_STORE).get(clientSubmissionId))
  return result
}

export async function deleteReport(clientSubmissionId: string): Promise<void> {
  const db = await openDatabase()
  const tx = db.transaction(REPORTS_STORE, 'readwrite')
  tx.objectStore(REPORTS_STORE).delete(clientSubmissionId)
  await promisifyTransaction(tx)
  notifyReportsChanged()
}

/** Newest first - matches the Android history ordering (§16). */
export async function listReports(): Promise<ReportRecord[]> {
  const db = await openDatabase()
  const tx = db.transaction(REPORTS_STORE, 'readonly')
  const all = await promisifyRequest<ReportRecord[]>(tx.objectStore(REPORTS_STORE).getAll())
  return [...all].sort((a, b) => new Date(b.localCreatedAt).getTime() - new Date(a.localCreatedAt).getTime())
}

export async function getCryptoKeyRecord(): Promise<CryptoKeyRecord | undefined> {
  const db = await openDatabase()
  const tx = db.transaction(CRYPTO_STORE, 'readonly')
  return promisifyRequest<CryptoKeyRecord | undefined>(tx.objectStore(CRYPTO_STORE).get('report-credential-key'))
}

export async function putCryptoKeyRecord(record: CryptoKeyRecord): Promise<void> {
  const db = await openDatabase()
  const tx = db.transaction(CRYPTO_STORE, 'readwrite')
  tx.objectStore(CRYPTO_STORE).put(record)
  await promisifyTransaction(tx)
}

/**
 * Test-only: closes and forgets the cached connection so a fresh `openDatabase()`
 * genuinely reopens it, and so a subsequent `indexedDB.deleteDatabase(...)` in a test's
 * `beforeEach` does not hang waiting for a connection this module never explicitly closes
 * in production (a real page never needs to - the connection lives for the page's
 * lifetime, exactly like the Android app's Room database).
 */
export async function _resetForTests(): Promise<void> {
  if (dbPromise) {
    const db = await dbPromise
    db.close()
  }
  dbPromise = null
}
