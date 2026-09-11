package hu.orszembejelento.backend.moderation.support

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/** Shared HTTP helpers and DB assertions for the Phase 9 moderation test battery (brief §63-65). */
abstract class ModerationTestSupport : ReportWorkflowTestSupport() {

    // ---------------------------------------------------------------------------- HTTP: mutations

    protected fun delete(bearer: String, publicReportId: UUID, expectedVersion: Long, reason: String = "SPAM"): HttpResponse<String> =
        post(
            "/api/v1/service/moderation/reports/$publicReportId/delete",
            """{"expectedVersion":$expectedVersion,"reason":"$reason"}""",
            bearer,
        )

    protected fun restore(bearer: String, publicReportId: UUID, expectedVersion: Long): HttpResponse<String> =
        post("/api/v1/service/moderation/reports/$publicReportId/restore", """{"expectedVersion":$expectedVersion}""", bearer)

    protected fun rawDelete(publicReportId: UUID, expectedVersion: Long, bearer: String, reason: String = "SPAM"): HttpResponse<String> =
        rawPost(
            "/api/v1/service/moderation/reports/$publicReportId/delete",
            """{"expectedVersion":$expectedVersion,"reason":"$reason"}""",
            bearer,
        )

    protected fun rawRestore(publicReportId: UUID, expectedVersion: Long, bearer: String): HttpResponse<String> =
        rawPost("/api/v1/service/moderation/reports/$publicReportId/restore", """{"expectedVersion":$expectedVersion}""", bearer)

    // ------------------------------------------------------------------------------- HTTP: queries

    protected fun deletedList(bearer: String, page: Int? = null, size: Int? = null, reason: String? = null, areaId: UUID? = null): HttpResponse<String> {
        val params = buildList {
            page?.let { add("page=$it") }
            size?.let { add("size=$it") }
            reason?.let { add("reason=$it") }
            areaId?.let { add("areaId=$it") }
        }
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return get("/api/v1/service/moderation/deleted$qs", bearer)
    }

    protected fun deletedDetail(bearer: String, publicReportId: UUID): HttpResponse<String> =
        get("/api/v1/service/moderation/deleted/$publicReportId", bearer)

    // -------------------------------------------------------------------------- DB assertions

    protected data class ModerationEpisodeRow(
        val reason: String,
        val deletedByUserId: UUID,
        val statusBeforeDelete: String,
        val restoredAt: Instant?,
        val restoredByUserId: UUID?,
    )

    protected fun openModerationEpisode(publicId: UUID): ModerationEpisodeRow? {
        val reportId = internalReportId(publicId)
        return jdbc.sql(
            "SELECT reason, deleted_by_user_id, status_before_delete, restored_at, restored_by_user_id " +
                "FROM report_moderation_episodes WHERE report_id = :rid AND restored_at IS NULL",
        )
            .param("rid", reportId)
            .query { rs, _ ->
                ModerationEpisodeRow(
                    reason = rs.getString("reason"),
                    deletedByUserId = rs.getObject("deleted_by_user_id", UUID::class.java),
                    statusBeforeDelete = rs.getString("status_before_delete"),
                    restoredAt = rs.getTimestamp("restored_at")?.toInstant(),
                    restoredByUserId = rs.getObject("restored_by_user_id", UUID::class.java),
                )
            }
            .list()
            .singleOrNull()
    }

    protected fun moderationEpisodeCount(publicId: UUID): Int {
        val reportId = internalReportId(publicId)
        return jdbc.sql("SELECT COUNT(*) FROM report_moderation_episodes WHERE report_id = :rid")
            .param("rid", reportId).query(Int::class.java).single()
    }

    protected fun openModerationEpisodeCount(publicId: UUID): Int {
        val reportId = internalReportId(publicId)
        return jdbc.sql("SELECT COUNT(*) FROM report_moderation_episodes WHERE report_id = :rid AND restored_at IS NULL")
            .param("rid", reportId).query(Int::class.java).single()
    }
}
