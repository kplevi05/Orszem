package hu.orszembejelento.service.audit.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

// ------------------------------------------------------------------------------- response DTOs
//
// Mirror the Phase 12 backend response shapes (AuditDtos.kt) field-for-field. `eventType` and
// `targetType` are nullable Strings, not enums - matching the backend's own explicit
// forward-compatibility guard (a value this build doesn't recognise renders as a generic safe
// row, never a crash - brief §6/§27). Never a raw metadata object anywhere in this file; the
// backend's own safe projector has already done that work before this DTO exists on the wire.

@Serializable
data class AuditDetailItemResponse(val code: String, val value: String)

@Serializable
data class AuditListItemResponse(
    val auditEventId: String,
    val occurredAt: String,
    val eventType: String? = null,
    val actorServiceId: String? = null,
    val targetType: String? = null,
    val targetDisplayLabel: String? = null,
    val summary: List<AuditDetailItemResponse> = emptyList(),
)

@Serializable
data class AuditListPageResponse(
    val items: List<AuditListItemResponse> = emptyList(),
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

@Serializable
data class AuditEventDetailResponse(
    val auditEventId: String,
    val occurredAt: String,
    val eventType: String? = null,
    val actorServiceId: String? = null,
    val targetType: String? = null,
    val targetDisplayLabel: String? = null,
    val details: List<AuditDetailItemResponse> = emptyList(),
)

@Serializable
data class AuditOptionsResponse(val eventTypes: List<String> = emptyList(), val targetTypes: List<String> = emptyList())

/**
 * The Phase 12 audit-query API. SUPER_ADMIN only on the backend (brief §2) - every call is
 * routed through [hu.orszembejelento.service.auth.data.AuthRepository.authorizedCall], never
 * called directly, exactly like every other Service API interface.
 */
interface AuditApi {

    @GET("api/v1/service/audit/events")
    suspend fun events(
        @Header("Authorization") bearer: String,
        @Query("period") period: String? = null,
        @Query("eventType") eventType: String? = null,
        @Query("targetType") targetType: String? = null,
        @Query("query") query: String? = null,
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
    ): Response<AuditListPageResponse>

    @GET("api/v1/service/audit/events/{auditEventId}")
    suspend fun detail(@Header("Authorization") bearer: String, @Path("auditEventId") auditEventId: String): Response<AuditEventDetailResponse>

    @GET("api/v1/service/audit/options")
    suspend fun options(@Header("Authorization") bearer: String): Response<AuditOptionsResponse>
}
