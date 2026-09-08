import { API_BASE_PATH } from '../config'

const REPORT_ACCESS_HEADER = 'X-Orszem-Report-Access'

export interface SubmitReportRequestBody {
  readonly clientSubmissionId: string
  readonly occurredAt: string
  readonly trainIdentifier: string | null
  readonly settlementId: string
  readonly railwayLineId: string | null
  readonly eventTypeCode: string
}

export interface SubmitReportResponseBody {
  readonly reportId: string
  readonly submittedAt: string
  readonly initialStatus: string
}

export interface SettlementSummaryBody {
  readonly id: string
  readonly name: string
}
export interface CategorySummaryBody {
  readonly code: string
  readonly displayName: string
}
export interface EventTypeSummaryBody {
  readonly code: string
  readonly displayName: string
}

export interface PublicReportResponseBody {
  readonly reportId: string
  readonly occurredAt: string
  readonly submittedAt: string
  readonly trainIdentifier: string | null
  readonly settlement: SettlementSummaryBody
  readonly category: CategorySummaryBody
  readonly eventType: EventTypeSummaryBody
  readonly status: string
}

export interface ReportCatalogCategoryBody {
  readonly code: string
  readonly displayName: string
  readonly eventTypes: readonly EventTypeSummaryBody[]
}
export interface ReportCatalogResponseBody {
  readonly categories: readonly ReportCatalogCategoryBody[]
}

export interface SettlementSearchResultBody {
  readonly id: string
  readonly kshCode: string
  readonly name: string
  readonly countyName: string | null
}

export interface RailwayLineItemBody {
  readonly id: string
  readonly code: string
  readonly displayName: string
}
export interface RailwayLinesForSettlementResponseBody {
  readonly coverage: string
  readonly items: readonly RailwayLineItemBody[]
}

export interface ApiErrorBody {
  readonly code: string
  readonly message: string
  readonly correlationId: string
}

export const ApiErrorCode = {
  INVALID_REPORT_ACCESS_CREDENTIAL: 'INVALID_REPORT_ACCESS_CREDENTIAL',
  INVALID_EVENT_TYPE: 'INVALID_EVENT_TYPE',
  INVALID_SETTLEMENT: 'INVALID_SETTLEMENT',
  INVALID_RAILWAY_LINE: 'INVALID_RAILWAY_LINE',
  VALIDATION_ERROR: 'VALIDATION_ERROR',
  IDEMPOTENCY_KEY_REUSED: 'IDEMPOTENCY_KEY_REUSED',
  REPORT_NOT_FOUND: 'REPORT_NOT_FOUND',
  REFERENCE_DATASET_UNAVAILABLE: 'REFERENCE_DATASET_UNAVAILABLE',
} as const

/** A completed HTTP exchange - status plus a parsed body, never a thrown exception for a non-2xx response. */
export interface ApiResult<T> {
  readonly status: number
  readonly body: T | undefined
  readonly errorBody: ApiErrorBody | undefined
}

async function request<T>(path: string, init: RequestInit): Promise<ApiResult<T>> {
  const response = await fetch(`${API_BASE_PATH}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init.headers },
  })
  const text = await response.text()
  const parsed = text.length > 0 ? (JSON.parse(text) as unknown) : undefined

  if (response.ok) {
    return { status: response.status, body: parsed as T, errorBody: undefined }
  }
  return { status: response.status, body: undefined, errorBody: parsed as ApiErrorBody }
}

/**
 * The Phase 4 Public API surface this client uses. Every call returns an [ApiResult] rather
 * than throwing on a non-2xx response, so the caller branches on the status and the stable
 * error code - never on a caught exception, and never by parsing `message` (§K: only `code`
 * is a stable machine contract).
 *
 * The report-access header is attached only on the two calls that need it (§25's Android
 * equivalent) - never as a global fetch default.
 */
export const publicApi = {
  reportCatalog: (): Promise<ApiResult<ReportCatalogResponseBody>> => request('/public/report-catalog', { method: 'GET' }),

  searchSettlements: (query: string): Promise<ApiResult<SettlementSearchResultBody[]>> =>
    request(`/public/reference/settlements?query=${encodeURIComponent(query)}`, { method: 'GET' }),

  railwayLinesOfSettlement: (settlementId: string): Promise<ApiResult<RailwayLinesForSettlementResponseBody>> =>
    request(`/public/reference/settlements/${encodeURIComponent(settlementId)}/railway-lines`, { method: 'GET' }),

  submitReport: (body: SubmitReportRequestBody, accessCredential: string): Promise<ApiResult<SubmitReportResponseBody>> =>
    request('/public/reports', {
      method: 'POST',
      headers: { [REPORT_ACCESS_HEADER]: accessCredential },
      body: JSON.stringify(body),
    }),

  getReport: (publicReportId: string, accessCredential: string): Promise<ApiResult<PublicReportResponseBody>> =>
    request(`/public/reports/${encodeURIComponent(publicReportId)}`, {
      method: 'GET',
      headers: { [REPORT_ACCESS_HEADER]: accessCredential },
    }),
}

export type PublicApi = typeof publicApi
