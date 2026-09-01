package hu.orszem.core.common

/** Stable machine error codes shared with the backend contract. */
enum class ApiErrorCode {
    VALIDATION_ERROR,
    EVENT_TYPE_INVALID,
    OCCURRED_AT_IN_FUTURE,
    REPORT_ID_CONFLICT,
    INVALID_CREDENTIALS,
    UNAUTHORIZED,
    REPORT_NOT_FOUND,
    REPORT_NOT_ACCEPTABLE,
    REPORT_NOT_ARCHIVABLE,
    RATE_LIMITED,
    INTERNAL_ERROR,

    // Transport-level outcomes. These are produced by the client, not the
    // backend contract, and exist so the UI can tell "no connection" apart from
    // "the request took too long" (Demo v1.1 §F).
    /** DNS failure, no route, connection refused/reset. */
    NETWORK,
    /** Connect/read/write timeout. */
    TIMEOUT,
    UNKNOWN,
}

data class FieldError(val field: String, val code: String, val message: String)

/** Normalised failure surfaced to view models. */
data class ApiError(
    val code: ApiErrorCode,
    val message: String?,
    val fieldErrors: List<FieldError> = emptyList(),
    val cause: Throwable? = null,
) {
    val isUnauthorized: Boolean get() = code == ApiErrorCode.UNAUTHORIZED || code == ApiErrorCode.INVALID_CREDENTIALS

    /** Worth offering a Retry action for: nothing about the request itself was wrong. */
    val isRecoverableNetwork: Boolean
        get() = code == ApiErrorCode.NETWORK ||
            code == ApiErrorCode.TIMEOUT ||
            code == ApiErrorCode.RATE_LIMITED ||
            code == ApiErrorCode.INTERNAL_ERROR
}

/** Result type for the repository layer. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: ApiError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(block: (ApiError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}
