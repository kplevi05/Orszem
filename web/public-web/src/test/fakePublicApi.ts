import type {
  ApiErrorBody,
  ApiResult,
  PublicApi,
  PublicReportResponseBody,
  RailwayLinesForSettlementResponseBody,
  SettlementSearchResultBody,
  SubmitReportRequestBody,
  SubmitReportResponseBody,
} from '../api/publicApi'

function ok<T>(status: number, body: T): ApiResult<T> {
  return { status, body, errorBody: undefined }
}
function fail<T>(status: number, code: string): ApiResult<T> {
  const errorBody: ApiErrorBody = { code, message: '', correlationId: 'test' }
  return { status, body: undefined, errorBody }
}

/** Records every submitReport call so ordering (e.g. "persist before network") can be asserted directly. */
export function createFakePublicApi(callLog?: string[]): PublicApi & {
  submitResult: ApiResult<SubmitReportResponseBody>
  getReportResult: ApiResult<PublicReportResponseBody>
  throwOnSubmit: Error | null
  lastSubmitBody: SubmitReportRequestBody | null
  lastSubmitCredential: string | null
  submitCallCount: number
  railwayLinesHandler: (settlementId: string) => Promise<ApiResult<RailwayLinesForSettlementResponseBody>>
  searchSettlementsHandler: (query: string) => Promise<ApiResult<SettlementSearchResultBody[]>>
} {
  return {
    submitResult: fail(500, 'INTERNAL_ERROR'),
    getReportResult: fail(404, 'REPORT_NOT_FOUND'),
    throwOnSubmit: null,
    lastSubmitBody: null,
    lastSubmitCredential: null,
    submitCallCount: 0,
    railwayLinesHandler: async () => fail(500, 'INTERNAL_ERROR'),
    searchSettlementsHandler: async () => ok(200, []),

    reportCatalog: async () => fail(500, 'INTERNAL_ERROR'),
    searchSettlements(query: string) {
      return this.searchSettlementsHandler(query)
    },
    railwayLinesOfSettlement(settlementId: string) {
      return this.railwayLinesHandler(settlementId)
    },
    async submitReport(body, accessCredential) {
      callLog?.push('api.submitReport')
      this.submitCallCount++
      this.lastSubmitBody = body
      this.lastSubmitCredential = accessCredential
      if (this.throwOnSubmit) throw this.throwOnSubmit
      return this.submitResult
    },
    async getReport() {
      return this.getReportResult
    },
  }
}
