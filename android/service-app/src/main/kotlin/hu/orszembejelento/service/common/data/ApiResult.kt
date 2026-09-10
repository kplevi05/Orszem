package hu.orszembejelento.service.common.data

import hu.orszembejelento.service.auth.data.ApiErrorBody
import hu.orszembejelento.service.auth.data.AuthRepository
import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * The outcome of one authenticated backend call, in terms every screen can act on uniformly.
 *
 * Every feature repository (report workflow, user management) returns this instead of a raw
 * Retrofit [Response], so the UI layer never re-derives "was this successful, and if not,
 * what stable code explains it" from scratch - see [apiCall].
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /** A rejected request the server explained with a stable [code] (brief §78/§79). */
    data class Failure(val code: String?, val httpStatus: Int) : ApiResult<Nothing>

    /** The stored session is gone; every screen should return to sign-in. */
    data object SessionEnded : ApiResult<Nothing>

    /** Transport failure - no response was ever received. */
    data object NetworkError : ApiResult<Nothing>
}

/**
 * Runs one call through [AuthRepository.authorizedCall] and maps the result to [ApiResult],
 * decoding the stable error code from a failed response's body exactly once.
 *
 * Every feature repository method is a one-line wrapper around this - see
 * `ReportWorkflowRepository`/`UserManagementRepository`. Centralizing it here is what keeps
 * every screen's conflict handling (brief §23-25, §60-62) working from the same stable codes
 * rather than each repository parsing error bodies its own way.
 */
suspend fun <T> apiCall(auth: AuthRepository, call: suspend (bearer: String) -> Response<T>): ApiResult<T> {
    val response = try {
        auth.authorizedCall(call) ?: return ApiResult.SessionEnded
    } catch (_: Exception) {
        return ApiResult.NetworkError
    }

    if (response.isSuccessful) {
        val body = response.body()
        @Suppress("UNCHECKED_CAST")
        return if (body != null) ApiResult.Success(body) else ApiResult.Success(Unit as T)
    }

    val code = runCatching {
        response.errorBody()?.string()?.let { json.decodeFromString<ApiErrorBody>(it).code }
    }.getOrNull()
    return ApiResult.Failure(code, response.code())
}

private val json = Json { ignoreUnknownKeys = true }
