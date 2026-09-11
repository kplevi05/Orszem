package hu.orszembejelento.backend.areaadmin.support

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import java.net.http.HttpResponse
import java.util.UUID

/**
 * Shared HTTP helpers and DB assertions for the Phase 10 ServiceArea-administration test
 * battery. Extends [ModerationTestSupport] (rather than `ReportWorkflowTestSupport` directly)
 * purely for its `delete`/`restore` HTTP helpers - brief §16 requires proving that a
 * moderation-deleted report never blocks area deactivation, so this module needs Phase 9's
 * own fixtures too, not just Phase 7's.
 */
abstract class AreaAdminTestSupport : ModerationTestSupport() {

    // ------------------------------------------------------------------------ HTTP: areas

    protected fun listAreas(bearer: String, page: Int? = null, size: Int? = null, query: String? = null, active: Boolean? = null): HttpResponse<String> {
        val params = buildList {
            page?.let { add("page=$it") }
            size?.let { add("size=$it") }
            query?.let { add("query=$it") }
            active?.let { add("active=$it") }
        }
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return get("/api/v1/service/service-area-admin/areas$qs", bearer)
    }

    protected fun areaDetail(bearer: String, areaId: UUID): HttpResponse<String> =
        get("/api/v1/service/service-area-admin/areas/$areaId", bearer)

    protected fun createArea(bearer: String, name: String): HttpResponse<String> =
        post("/api/v1/service/service-area-admin/areas", """{"name":${jsonString(name)}}""", bearer)

    protected fun renameArea(bearer: String, areaId: UUID, expectedVersion: Long, name: String): HttpResponse<String> =
        post(
            "/api/v1/service/service-area-admin/areas/$areaId/rename",
            """{"expectedVersion":$expectedVersion,"name":${jsonString(name)}}""",
            bearer,
        )

    protected fun activateArea(bearer: String, areaId: UUID, expectedVersion: Long): HttpResponse<String> =
        post("/api/v1/service/service-area-admin/areas/$areaId/activate", """{"expectedVersion":$expectedVersion}""", bearer)

    protected fun deactivateArea(bearer: String, areaId: UUID, expectedVersion: Long): HttpResponse<String> =
        post("/api/v1/service/service-area-admin/areas/$areaId/deactivate", """{"expectedVersion":$expectedVersion}""", bearer)

    protected fun rawDeactivateArea(bearer: String, areaId: UUID, expectedVersion: Long): HttpResponse<String> =
        rawPost("/api/v1/service/service-area-admin/areas/$areaId/deactivate", """{"expectedVersion":$expectedVersion}""", bearer)

    // ------------------------------------------------------------------- HTTP: railway lines

    protected fun listRailwayLines(
        bearer: String,
        page: Int? = null,
        size: Int? = null,
        query: String? = null,
        active: Boolean? = null,
        serviceAreaId: UUID? = null,
        assignment: String? = null,
    ): HttpResponse<String> {
        val params = buildList {
            page?.let { add("page=$it") }
            size?.let { add("size=$it") }
            query?.let { add("query=$it") }
            active?.let { add("active=$it") }
            serviceAreaId?.let { add("serviceAreaId=$it") }
            assignment?.let { add("assignment=$it") }
        }
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return get("/api/v1/service/service-area-admin/railway-lines$qs", bearer)
    }

    protected fun assignLine(bearer: String, lineId: UUID, targetAreaId: UUID, expectedCurrentAreaId: UUID?): HttpResponse<String> =
        post(
            "/api/v1/service/service-area-admin/railway-lines/$lineId/assign",
            """{"targetServiceAreaId":"$targetAreaId","expectedCurrentServiceAreaId":${expectedCurrentAreaId?.let { "\"$it\"" } ?: "null"}}""",
            bearer,
        )

    protected fun rawAssignLine(bearer: String, lineId: UUID, targetAreaId: UUID, expectedCurrentAreaId: UUID?): HttpResponse<String> =
        rawPost(
            "/api/v1/service/service-area-admin/railway-lines/$lineId/assign",
            """{"targetServiceAreaId":"$targetAreaId","expectedCurrentServiceAreaId":${expectedCurrentAreaId?.let { "\"$it\"" } ?: "null"}}""",
            bearer,
        )

    protected fun unassignLine(bearer: String, lineId: UUID, expectedCurrentAreaId: UUID): HttpResponse<String> =
        post(
            "/api/v1/service/service-area-admin/railway-lines/$lineId/unassign",
            """{"expectedCurrentServiceAreaId":"$expectedCurrentAreaId"}""",
            bearer,
        )

    private fun jsonString(raw: String): String = objectMapper.writeValueAsString(raw)

    // -------------------------------------------------------------------------- DB assertions

    protected fun areaAdminVersion(areaId: UUID): Long =
        jdbc.sql("SELECT admin_version FROM service_areas WHERE id = :id").param("id", areaId).query(Long::class.java).single()

    protected fun areaStatus(areaId: UUID): String =
        jdbc.sql("SELECT status FROM service_areas WHERE id = :id").param("id", areaId).query(String::class.java).single()

    protected fun currentAreaOfLine(lineId: UUID): UUID? =
        jdbc.sql("SELECT service_area_id FROM service_area_railway_lines WHERE railway_line_id = :id")
            .param("id", lineId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    protected fun mappingCount(lineId: UUID): Int =
        jdbc.sql("SELECT COUNT(*) FROM service_area_railway_lines WHERE railway_line_id = :id")
            .param("id", lineId)
            .query(Int::class.java)
            .single()

    protected fun routingSnapshotArea(publicReportId: UUID): UUID? {
        val reportId = internalReportId(publicReportId)
        return jdbc.sql("SELECT service_area_id FROM report_routing_snapshots WHERE report_id = :id")
            .param("id", reportId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
    }

    protected fun storedAssignmentExists(userId: UUID, areaId: UUID): Boolean =
        jdbc.sql("SELECT 1 FROM user_service_areas WHERE user_id = :u AND service_area_id = :a")
            .param("u", userId).param("a", areaId)
            .query(Int::class.java)
            .optional()
            .isPresent

    protected fun latestAuditMetadata(eventType: String, targetId: UUID): Map<String, Any?>? =
        jdbc.sql("SELECT metadata::text AS metadata FROM audit_events WHERE event_type = :t AND target_id = :id ORDER BY created_at DESC LIMIT 1")
            .param("t", eventType)
            .param("id", targetId)
            .query(String::class.java)
            .optional()
            .map { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }
            .orElse(null)
}
