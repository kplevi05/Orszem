import { ApiErrorCode, publicApi, type PublicApi } from '../api/publicApi'
import { generateClientSubmissionId, generateReportAccessCredential, parseStoredCredential } from '../domain/credential'
import { normalizeReportDraft, type ReportDraft } from '../domain/normalize'
import type { PublicReportStatus } from '../domain/submissionState'
import { decryptCredential, encryptCredential } from '../storage/crypto'
import { deleteReport, getReport, insertReport, listReports, putReport, subscribeToReportsChanged, type ReportRecord } from '../storage/db'

export interface ReportDisplaySnapshot {
  readonly settlementName: string
  readonly railwayLineDisplay: string | null
  readonly categoryDisplay: string
  readonly eventTypeDisplay: string
}

export type SubmitOutcome =
  | { readonly kind: 'created'; readonly record: ReportRecord }
  | { readonly kind: 'replayed'; readonly record: ReportRecord }
  /** A definitive validation failure - §10: not treated as history, the local record is deleted. */
  | { readonly kind: 'validation-failed'; readonly code: string }
  /** Timeout, connection failure, or an ambiguous/transient server error - the local PENDING record is unchanged. */
  | { readonly kind: 'ambiguous-failure'; readonly code: string | null }
  | { readonly kind: 'conflict' }
  | { readonly kind: 'access-lost' }
  /** Durable local persistence itself failed before any network attempt - nothing was sent (§34). */
  | { readonly kind: 'local-persistence-failed' }

export type StatusRefreshOutcome =
  | { readonly kind: 'updated'; readonly status: PublicReportStatus }
  | { readonly kind: 'unavailable' } // generic 404 - existence-safe, not interpreted further (§18)
  | { readonly kind: 'access-lost' }
  | { readonly kind: 'network-failure' }
  | { readonly kind: 'not-submitted-yet' }

/**
 * Orchestrates the whole submission lifecycle (Phase 5 brief §5-19) - the Web mirror of
 * `android/public-app/.../ReportRepository.kt`. The one rule every method here is built
 * around: **local durable persistence of `clientSubmissionId` + the access credential + the
 * frozen normalized payload happens before the first network attempt, unconditionally** -
 * see `submit`.
 */
export function createReportRepository(api: PublicApi = publicApi) {
  async function deliver(record: ReportRecord, credential: string): Promise<SubmitOutcome> {
    const body = {
      clientSubmissionId: record.clientSubmissionId,
      occurredAt: record.occurredAt,
      trainIdentifier: record.trainIdentifier,
      settlementId: record.settlementId,
      railwayLineId: record.railwayLineId,
      eventTypeCode: record.eventTypeCode,
    }

    let result
    try {
      result = await api.submitReport(body, credential)
    } catch {
      return { kind: 'ambiguous-failure', code: null }
    }

    if (result.status === 200 || result.status === 201) {
      if (!result.body) return { kind: 'ambiguous-failure', code: null }
      const updated: ReportRecord = {
        ...record,
        submissionState: 'SUBMITTED',
        publicReportId: result.body.reportId,
        serverSubmittedAt: result.body.submittedAt,
        publicStatus: 'RECEIVED',
        lastErrorCode: null,
      }
      await putReport(updated)
      return { kind: result.status === 201 ? 'created' : 'replayed', record: updated }
    }

    if (result.status === 400) {
      // Definitive: the backend proved no report was created. Not history - delete the
      // local record entirely (§10). A later attempt must start over with a brand-new
      // identity, never this one.
      await deleteReport(record.clientSubmissionId)
      return { kind: 'validation-failed', code: result.errorBody?.code ?? ApiErrorCode.VALIDATION_ERROR }
    }

    if (result.status === 409) {
      await putReport({ ...record, submissionState: 'CONFLICT', lastErrorCode: ApiErrorCode.IDEMPOTENCY_KEY_REUSED })
      return { kind: 'conflict' }
    }

    if (result.status === 503) {
      await putReport({ ...record, submissionState: 'PENDING', lastErrorCode: ApiErrorCode.REFERENCE_DATASET_UNAVAILABLE })
      return { kind: 'ambiguous-failure', code: ApiErrorCode.REFERENCE_DATASET_UNAVAILABLE }
    }

    // Any other/ambiguous status: stays PENDING, retryable.
    const code = result.errorBody?.code ?? null
    await putReport({ ...record, submissionState: 'PENDING', lastErrorCode: code })
    return { kind: 'ambiguous-failure', code }
  }

  async function submit(draft: ReportDraft, display: ReportDisplaySnapshot): Promise<SubmitOutcome> {
    const normalized = normalizeReportDraft(draft)
    const clientSubmissionId = generateClientSubmissionId()
    const credential = generateReportAccessCredential()

    let record: ReportRecord
    try {
      const encrypted = await encryptCredential(credential)
      record = {
        clientSubmissionId,
        publicReportId: null,
        encryptedCredentialCiphertext: encrypted.ciphertext,
        encryptedCredentialIv: encrypted.iv,
        occurredAt: normalized.occurredAt,
        trainIdentifier: normalized.trainIdentifier,
        settlementId: normalized.settlementId,
        settlementNameSnapshot: display.settlementName,
        railwayLineId: normalized.railwayLineId,
        railwayLineDisplaySnapshot: display.railwayLineDisplay,
        categoryDisplaySnapshot: display.categoryDisplay,
        eventTypeCode: normalized.eventTypeCode,
        eventTypeDisplaySnapshot: display.eventTypeDisplay,
        submissionState: 'PENDING',
        publicStatus: null,
        serverSubmittedAt: null,
        localCreatedAt: new Date().toISOString(),
        lastStatusCheckedAt: null,
        lastErrorCode: null,
      }
      await insertReport(record)
    } catch {
      return { kind: 'local-persistence-failed' }
    }

    // Local commit has happened. Only now does the first network attempt begin.
    return deliver(record, credential)
  }

  /** Resends the exact frozen identity/payload/credential already on record - never reconstructed from UI state. */
  async function retry(clientSubmissionId: string): Promise<SubmitOutcome> {
    const record = await getReport(clientSubmissionId)
    if (!record) return { kind: 'local-persistence-failed' }

    const credential = await decryptCredential({
      ciphertext: record.encryptedCredentialCiphertext,
      iv: record.encryptedCredentialIv,
    })
    if (credential === null || parseStoredCredential(credential) === null) {
      await putReport({ ...record, submissionState: 'ACCESS_LOST' })
      return { kind: 'access-lost' }
    }

    return deliver(record, credential)
  }

  async function refreshStatus(clientSubmissionId: string): Promise<StatusRefreshOutcome> {
    const record = await getReport(clientSubmissionId)
    if (!record || !record.publicReportId) return { kind: 'not-submitted-yet' }

    const credential = await decryptCredential({
      ciphertext: record.encryptedCredentialCiphertext,
      iv: record.encryptedCredentialIv,
    })
    if (credential === null) {
      await putReport({ ...record, submissionState: 'ACCESS_LOST' })
      return { kind: 'access-lost' }
    }

    let result
    try {
      result = await api.getReport(record.publicReportId, credential)
    } catch {
      await putReport({ ...record, lastStatusCheckedAt: new Date().toISOString() })
      return { kind: 'network-failure' }
    }

    if (result.status === 200 && result.body) {
      const status = result.body.status as PublicReportStatus
      await putReport({ ...record, publicStatus: status, lastStatusCheckedAt: new Date().toISOString(), lastErrorCode: null })
      return { kind: 'updated', status }
    }

    if (result.status === 404) {
      // Existence-safe by design on the server - never interpreted as "the credential must
      // be wrong" or "the report must not exist" (§18). The credential and row are
      // untouched beyond recording the check itself.
      await putReport({
        ...record,
        lastStatusCheckedAt: new Date().toISOString(),
        lastErrorCode: ApiErrorCode.REPORT_NOT_FOUND,
      })
      return { kind: 'unavailable' }
    }

    await putReport({ ...record, lastStatusCheckedAt: new Date().toISOString(), lastErrorCode: result.errorBody?.code ?? null })
    return { kind: 'network-failure' }
  }

  /** Refreshes several SUBMITTED records with a small bounded concurrency (§17) - never one unbounded burst. */
  async function refreshAll(clientSubmissionIds: readonly string[], concurrency = 3): Promise<void> {
    let index = 0
    async function worker(): Promise<void> {
      while (index < clientSubmissionIds.length) {
        const current = clientSubmissionIds[index]
        index += 1
        if (current) await refreshStatus(current)
      }
    }
    await Promise.all(Array.from({ length: Math.min(concurrency, clientSubmissionIds.length) }, worker))
  }

  return {
    submit,
    retry,
    refreshStatus,
    refreshAll,
    listHistory: listReports,
    subscribe: subscribeToReportsChanged,
  }
}

export type ReportRepository = ReturnType<typeof createReportRepository>
