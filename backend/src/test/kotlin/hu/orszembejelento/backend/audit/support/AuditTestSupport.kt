package hu.orszembejelento.backend.audit.support

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import java.net.http.HttpResponse
import java.util.UUID

/**
 * Shared HTTP helpers for the Phase 12 audit-query test battery. Extends
 * [AreaAdminTestSupport] for the identical reason `AnalyticsTestSupport` (Phase 11) does: one
 * inheritance chain carrying Phase 7 report-workflow fixtures/role users, Phase 9 moderation
 * delete/restore, and Phase 10 ServiceArea/RailwayLine administration - everything the
 * cross-phase proof (brief §42/§68) needs to generate real audit rows through real endpoints.
 *
 * [createUser] duplicates `AbstractUserManagementIntegrationTest.createUser`'s one HTTP call
 * rather than inheriting it - Kotlin has no multiple inheritance, and pulling in that whole
 * second test-support branch just for one endpoint would be a bigger change than the
 * duplication itself.
 */
abstract class AuditTestSupport : AreaAdminTestSupport() {

    protected fun auditEvents(
        bearer: String,
        period: String? = null,
        eventType: String? = null,
        targetType: String? = null,
        query: String? = null,
        page: Int? = null,
        size: Int? = null,
    ): HttpResponse<String> {
        val params = buildList {
            period?.let { add("period=$it") }
            eventType?.let { add("eventType=$it") }
            targetType?.let { add("targetType=$it") }
            query?.let { add("query=${java.net.URLEncoder.encode(it, Charsets.UTF_8)}") }
            page?.let { add("page=$it") }
            size?.let { add("size=$it") }
        }
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return get("/api/v1/service/audit/events$qs", bearer)
    }

    protected fun auditDetail(bearer: String, auditEventId: UUID): HttpResponse<String> =
        get("/api/v1/service/audit/events/$auditEventId", bearer)

    protected fun auditOptions(bearer: String): HttpResponse<String> = get("/api/v1/service/audit/options", bearer)

    protected fun createUser(
        bearer: String,
        role: String,
        areaIds: List<UUID> = emptyList(),
        globalAreaAccess: Boolean = false,
    ): HttpResponse<String> = post(
        "/api/v1/service/user-management/users",
        """{"role":"$role","areaIds":${areaIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},"globalAreaAccess":$globalAreaAccess}""",
        bearer,
    )

    protected fun changeRole(bearer: String, serviceId: String, role: String): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/role", """{"role":"$role"}""", bearer)

    protected fun grantArea(bearer: String, serviceId: String, areaId: UUID): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/areas/$areaId/grant", "", bearer)

    protected fun revokeArea(bearer: String, serviceId: String, areaId: UUID): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/areas/$areaId/revoke", "", bearer)

    /** Latest `audit_events.id` for [eventType]/[targetId] - a direct-DB read, used only to seed the security fixture tests, never to bypass the real writers under test. */
    protected fun latestAuditEventId(eventType: String, targetId: UUID): UUID? = jdbc
        .sql("SELECT id FROM audit_events WHERE event_type = :eventType AND target_id = :targetId ORDER BY created_at DESC LIMIT 1")
        .param("eventType", eventType)
        .param("targetId", targetId)
        .query(UUID::class.java)
        .optional()
        .orElse(null)
}
