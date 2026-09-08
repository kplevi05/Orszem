package hu.orszembejelento.app.report

import hu.orszembejelento.app.report.data.network.PublicApi
import hu.orszembejelento.app.report.data.network.RailwayLinesForSettlementResponseBody
import hu.orszembejelento.app.report.data.network.ReportCatalogResponseBody
import hu.orszembejelento.app.report.data.network.SettlementSearchResultBody
import hu.orszembejelento.app.report.data.network.SubmitReportRequestBody
import hu.orszembejelento.app.report.data.network.SubmitReportResponseBody
import hu.orszembejelento.app.report.data.network.PublicReportResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/** Records every call so ordering (e.g. "persist before network") can be asserted directly. */
class FakePublicApi(private val callLog: MutableList<String>? = null) : PublicApi {

    var submitResponse: Response<SubmitReportResponseBody> = errorResponse(500, "")
    var getReportResponse: Response<PublicReportResponseBody> = errorResponse(404, """{"code":"REPORT_NOT_FOUND"}""")
    var throwOnSubmit: Throwable? = null

    var lastSubmitBody: SubmitReportRequestBody? = null
    var lastSubmitCredential: String? = null
    var submitCallCount = 0

    /** Overridable per test - lets a test simulate a slow response for one specific settlement id (staleness tests). */
    var railwayLinesHandler: suspend (String) -> Response<RailwayLinesForSettlementResponseBody> = { errorResponse(500, "") }
    var searchSettlementsHandler: suspend (String) -> Response<List<SettlementSearchResultBody>> = { Response.success(emptyList()) }

    override suspend fun reportCatalog(): Response<ReportCatalogResponseBody> = errorResponse(500, "")

    override suspend fun searchSettlements(query: String): Response<List<SettlementSearchResultBody>> = searchSettlementsHandler(query)

    override suspend fun railwayLinesOfSettlement(settlementId: String): Response<RailwayLinesForSettlementResponseBody> =
        railwayLinesHandler(settlementId)

    override suspend fun submitReport(body: SubmitReportRequestBody, accessCredential: String): Response<SubmitReportResponseBody> {
        callLog?.add("api.submitReport")
        submitCallCount++
        lastSubmitBody = body
        lastSubmitCredential = accessCredential
        throwOnSubmit?.let { throw it }
        return submitResponse
    }

    override suspend fun getReport(publicReportId: String, accessCredential: String): Response<PublicReportResponseBody> =
        getReportResponse

    companion object {
        fun <T> errorResponse(code: Int, json: String): Response<T> =
            Response.error(code, json.toResponseBody("application/json".toMediaType()))
    }
}
