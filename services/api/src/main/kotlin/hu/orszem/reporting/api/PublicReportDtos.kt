package hu.orszem.reporting.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Matches OpenAPI `PublicReportCreateRequest`. */
data class PublicReportCreateRequest(
    @field:NotNull(message = "A report azonosító kötelező.")
    val id: UUID?,

    @field:NotBlank(message = "Az eseménytípus megadása kötelező.")
    @field:Size(max = 64)
    val eventTypeCode: String?,

    @field:NotBlank(message = "A vonat megadása kötelező.")
    @field:Size(max = 64)
    val trainIdentifier: String?,

    @field:NotBlank(message = "A település megadása kötelező.")
    @field:Size(max = 128)
    val settlement: String?,

    @field:NotNull(message = "Az esemény időpontja kötelező.")
    val occurredAt: Instant?,
)

/** Matches OpenAPI `PublicReportCreateResponse`. */
data class PublicReportCreateResponse(
    val id: UUID,
    val status: String,
    val receivedAt: Instant,
)
