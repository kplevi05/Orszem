package hu.orszem.shared.error

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * `application/problem+json` body, matching the `ProblemDetails` schema in the
 * canonical OpenAPI contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProblemResponse(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val code: String,
    val detail: String? = null,
    val instance: String? = null,
    val correlationId: String? = null,
    val fieldErrors: List<FieldErrorResponse>? = null,
)

data class FieldErrorResponse(
    val field: String,
    val code: String,
    val message: String,
)
