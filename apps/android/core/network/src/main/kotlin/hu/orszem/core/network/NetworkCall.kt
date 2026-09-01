package hu.orszem.core.network

import hu.orszem.core.common.ApiError
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.FieldError
import hu.orszem.core.common.Outcome
import hu.orszem.core.network.dto.ProblemDetailsDto
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Runs a Retrofit call and normalises the outcome:
 * - 2xx -> Success(body)
 * - problem+json -> Failure(ApiError) with the contract error code
 * - IOException / timeouts -> Failure(NETWORK) so the UI can offer a retry
 */
suspend fun <T : Any> safeApiCall(
    json: Json = LenientJson,
    block: suspend () -> Response<T>,
): Outcome<T> = try {
    val response = block()
    if (response.isSuccessful) {
        val body = response.body()
        if (body != null) Outcome.Success(body) else Outcome.Failure(ApiError(ApiErrorCode.INTERNAL_ERROR, null))
    } else {
        Outcome.Failure(parseProblem(json, response))
    }
} catch (e: SocketTimeoutException) {
    // Checked before IOException: SocketTimeoutException is a subclass.
    Outcome.Failure(ApiError(ApiErrorCode.TIMEOUT, null, cause = e))
} catch (e: IOException) {
    // UnknownHostException (DNS), ConnectException, SSLException, ...
    Outcome.Failure(ApiError(ApiErrorCode.NETWORK, null, cause = e))
} catch (e: Exception) {
    // `message` is deliberately dropped: it can carry hostnames, TLS details or
    // library class names, none of which may reach the user (Demo v1.1 §F).
    Outcome.Failure(ApiError(ApiErrorCode.UNKNOWN, null, cause = e))
}

/**
 * Same normalisation as [safeApiCall] for endpoints that answer with no body
 * (HTTP 204). A missing body is the success case here, not an error.
 */
suspend fun safeEmptyApiCall(
    json: Json = LenientJson,
    block: suspend () -> Response<*>,
): Outcome<Unit> = try {
    val response = block()
    if (response.isSuccessful) Outcome.Success(Unit) else Outcome.Failure(parseProblem(json, response))
} catch (e: SocketTimeoutException) {
    Outcome.Failure(ApiError(ApiErrorCode.TIMEOUT, null, cause = e))
} catch (e: IOException) {
    Outcome.Failure(ApiError(ApiErrorCode.NETWORK, null, cause = e))
} catch (e: Exception) {
    Outcome.Failure(ApiError(ApiErrorCode.UNKNOWN, null, cause = e))
}

private fun parseProblem(json: Json, response: Response<*>): ApiError {
    val raw = runCatching { response.errorBody()?.string() }.getOrNull()
    val problem = raw?.let { runCatching { json.decodeFromString<ProblemDetailsDto>(it) }.getOrNull() }
    val code = problem?.code?.let { runCatching { ApiErrorCode.valueOf(it) }.getOrNull() }
        ?: statusToCode(response.code())
    return ApiError(
        code = code,
        message = problem?.detail ?: problem?.title,
        fieldErrors = problem?.fieldErrors?.map { FieldError(it.field, it.code, it.message) } ?: emptyList(),
    )
}

private fun statusToCode(status: Int): ApiErrorCode = when (status) {
    400 -> ApiErrorCode.VALIDATION_ERROR
    401 -> ApiErrorCode.UNAUTHORIZED
    404 -> ApiErrorCode.REPORT_NOT_FOUND
    409 -> ApiErrorCode.REPORT_ID_CONFLICT
    429 -> ApiErrorCode.RATE_LIMITED
    in 500..599 -> ApiErrorCode.INTERNAL_ERROR
    else -> ApiErrorCode.UNKNOWN
}

val LenientJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
