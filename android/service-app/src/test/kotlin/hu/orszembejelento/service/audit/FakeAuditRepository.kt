package hu.orszembejelento.service.audit

import hu.orszembejelento.service.audit.data.AuditEventDetailResponse
import hu.orszembejelento.service.audit.data.AuditFilter
import hu.orszembejelento.service.audit.data.AuditListItemResponse
import hu.orszembejelento.service.audit.data.AuditListPageResponse
import hu.orszembejelento.service.audit.data.AuditOptionsResponse
import hu.orszembejelento.service.audit.data.AuditRepository
import hu.orszembejelento.service.common.data.ApiResult

/** A fully configurable test double, mirroring `FakeAnalyticsRepository`/`FakeAreaAdminRepository`. */
class FakeAuditRepository(
    var eventsResult: ApiResult<AuditListPageResponse> = ApiResult.Success(defaultPage()),
    var optionsResult: ApiResult<AuditOptionsResponse> = ApiResult.Success(AuditOptionsResponse(emptyList(), emptyList())),
    var detailResult: ApiResult<AuditEventDetailResponse> = ApiResult.Success(defaultDetail()),
) : AuditRepository {

    var eventsCalls = 0
    var optionsCalls = 0
    var detailCalls = 0
    val seenFilters = mutableListOf<AuditFilter>()
    val seenPages = mutableListOf<Int>()
    val seenDetailIds = mutableListOf<String>()

    override suspend fun events(filter: AuditFilter, page: Int, size: Int): ApiResult<AuditListPageResponse> {
        eventsCalls++
        seenFilters += filter
        seenPages += page
        return eventsResult
    }

    override suspend fun detail(auditEventId: String): ApiResult<AuditEventDetailResponse> {
        detailCalls++
        seenDetailIds += auditEventId
        return detailResult
    }

    override suspend fun options(): ApiResult<AuditOptionsResponse> {
        optionsCalls++
        return optionsResult
    }

    companion object {
        fun defaultPage(items: List<AuditListItemResponse> = emptyList(), page: Int = 0, totalPages: Int = 1, totalElements: Int = items.size) =
            AuditListPageResponse(items = items, page = page, size = 50, totalElements = totalElements, totalPages = totalPages)

        fun item(id: String = "evt-1", eventType: String? = "USER_ROLE_CHANGED", actorServiceId: String? = "SZ-100001") = AuditListItemResponse(
            auditEventId = id,
            occurredAt = "2026-09-13T18:24:37Z",
            eventType = eventType,
            actorServiceId = actorServiceId,
            targetType = "USER",
            targetDisplayLabel = "SZ-200002",
            summary = emptyList(),
        )

        fun defaultDetail(id: String = "evt-1") = AuditEventDetailResponse(
            auditEventId = id,
            occurredAt = "2026-09-13T18:24:37Z",
            eventType = "USER_ROLE_CHANGED",
            actorServiceId = "SZ-100001",
            targetType = "USER",
            targetDisplayLabel = "SZ-200002",
            details = emptyList(),
        )
    }
}
