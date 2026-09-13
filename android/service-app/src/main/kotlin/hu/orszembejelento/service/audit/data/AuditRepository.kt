package hu.orszembejelento.service.audit.data

import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.common.data.apiCall

/** The five fixed audit reporting windows (brief §14) - frozen product vocabulary, never computed on Android. */
enum class AuditPeriod { TODAY, LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS, ALL }

/**
 * The audit list/search filter (brief §16-19/§49/§61). Session-local UI state only - never
 * persisted to Room/DataStore, never carried across a logout (brief §60/§61).
 */
data class AuditFilter(
    val period: AuditPeriod = AuditPeriod.LAST_30_DAYS,
    val eventType: String? = null,
    val targetType: String? = null,
    val query: String? = null,
) {
    val activeFacetCount: Int get() = listOfNotNull(eventType, targetType, query?.takeIf { it.isNotBlank() }).size
}

/**
 * The Phase 12 audit-query surface every `Változási előzmények` screen/ViewModel depends on. An
 * interface, not a class - mirrors every other Phase 8-11 repository's own reasoning: a test
 * supplies a plain fake, production code only ever constructs [DefaultAuditRepository] via
 * [hu.orszembejelento.service.auth.data.NetworkModule].
 */
interface AuditRepository {
    suspend fun events(filter: AuditFilter, page: Int, size: Int): ApiResult<AuditListPageResponse>
    suspend fun detail(auditEventId: String): ApiResult<AuditEventDetailResponse>
    suspend fun options(): ApiResult<AuditOptionsResponse>
}

class DefaultAuditRepository(private val api: AuditApi, private val auth: AuthRepository) : AuditRepository {

    override suspend fun events(filter: AuditFilter, page: Int, size: Int): ApiResult<AuditListPageResponse> =
        apiCall(auth) { bearer ->
            api.events(
                bearer = bearer,
                period = filter.period.name,
                eventType = filter.eventType,
                targetType = filter.targetType,
                query = filter.query?.takeIf { it.isNotBlank() },
                page = page,
                size = size,
            )
        }

    override suspend fun detail(auditEventId: String): ApiResult<AuditEventDetailResponse> =
        apiCall(auth) { bearer -> api.detail(bearer, auditEventId) }

    override suspend fun options(): ApiResult<AuditOptionsResponse> =
        apiCall(auth) { bearer -> api.options(bearer) }
}
