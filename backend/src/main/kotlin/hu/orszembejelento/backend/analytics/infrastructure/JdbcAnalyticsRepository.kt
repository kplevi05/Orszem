package hu.orszembejelento.backend.analytics.infrastructure

import hu.orszembejelento.backend.analytics.domain.AnalyticsCategoryCount
import hu.orszembejelento.backend.analytics.domain.AnalyticsEventTypeCount
import hu.orszembejelento.backend.analytics.domain.AnalyticsFilter
import hu.orszembejelento.backend.analytics.domain.AnalyticsStatusCounts
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * The aggregate queries behind the analytics summary (Phase 11 brief) — four bounded
 * `GROUP BY` queries sharing one `[from, to)` + role-scope + optional-filter predicate,
 * never a raw-row fetch aggregated in Kotlin (brief §12/§54).
 *
 * Role/scope visibility mirrors [hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportWorkflowQueryRepository.visibilityClause]
 * exactly for SUPER_ADMIN, territorial actors and a global SERVICE_USER, reusing the same
 * [ReportWorkflowActor] the report-workflow queues already load (brief §7: "share/reuse
 * existing scope semantics wherever possible") — deliberately **not** a copy-pasted private
 * method, since Kotlin `private` prevents literal reuse across files, but the exact same
 * fragment shapes and column names, so the two never drift silently.
 *
 * One case is a **deliberate**, brief-mandated difference, not an accidental divergence: a
 * global MODERATOR's report-workflow visibility is unrestricted by area status (queues must
 * keep showing reports in areas that were later deactivated, so past operational work is
 * never stranded — see that class's own KDoc), but Phase 11 analytics brief §7/§26 explicitly
 * scopes a global MODERATOR to "all *currently-operational* ServiceAreas" and reserves
 * historical inactive-area visibility for SUPER_ADMIN alone. That one restriction
 * (`sa.status = 'ACTIVE'`, alongside the always-visible UNCLASSIFIED bucket) is added here on
 * purpose and is exactly why this repository cannot simply delegate to the existing method.
 */
@Repository
class JdbcAnalyticsRepository(private val jdbc: JdbcClient) {

    fun statusCounts(actor: ReportWorkflowActor, from: Instant, to: Instant, filter: AnalyticsFilter): AnalyticsStatusCounts {
        val (where, params) = whereClause(actor, from, to, filter)
        val counts = jdbc.sql("SELECT r.status, COUNT(*) AS cnt $FROM_JOINS $where GROUP BY r.status")
            .params(params)
            .query { rs, _ -> rs.getString("status") to rs.getInt("cnt") }
            .list()
            .toMap()
        return AnalyticsStatusCounts(
            new = counts["NEW"] ?: 0,
            inProgress = counts["IN_PROGRESS"] ?: 0,
            archived = counts["ARCHIVED"] ?: 0,
        )
    }

    /**
     * Daily local-calendar buckets (brief §18). `AT TIME ZONE 'Europe/Budapest'` on a
     * `timestamptz` yields the local wall-clock timestamp in that zone — real IANA DST
     * rules from PostgreSQL's own tz database, never a hardcoded offset (brief §29); the
     * literal must stay in sync with [hu.orszembejelento.backend.analytics.domain.AnalyticsPeriodCalculator.ZONE].
     * Only days with at least one report are returned — the caller zero-fills the rest
     * from the period's own requested date list, so a day with no reports is never
     * silently absent from the response.
     */
    fun dailyCounts(actor: ReportWorkflowActor, from: Instant, to: Instant, filter: AnalyticsFilter): Map<LocalDate, Int> {
        val (where, params) = whereClause(actor, from, to, filter)
        return jdbc.sql(
            "SELECT (r.submitted_at AT TIME ZONE 'Europe/Budapest')::date AS local_date, COUNT(*) AS cnt $FROM_JOINS $where GROUP BY 1",
        )
            .params(params)
            .query { rs, _ -> rs.getObject("local_date", LocalDate::class.java) to rs.getInt("cnt") }
            .list()
            .toMap()
    }

    /** Only categories with count > 0 (brief §19) — a `GROUP BY` naturally never emits an absent combination. */
    fun categoryCounts(actor: ReportWorkflowActor, from: Instant, to: Instant, filter: AnalyticsFilter): List<AnalyticsCategoryCount> {
        val (where, params) = whereClause(actor, from, to, filter)
        return jdbc.sql(
            """
            SELECT cat.code, cat.display_name, COUNT(*) AS cnt
            $FROM_JOINS $where
            GROUP BY cat.code, cat.display_name
            ORDER BY COUNT(*) DESC, cat.code ASC
            """.trimIndent(),
        )
            .params(params)
            .query { rs, _ -> AnalyticsCategoryCount(rs.getString("code"), rs.getString("display_name"), rs.getInt("cnt")) }
            .list()
    }

    /** Top 5 event types (brief §20) — deterministic tie order, count DESC then code ASC. */
    fun topEventTypes(actor: ReportWorkflowActor, from: Instant, to: Instant, filter: AnalyticsFilter): List<AnalyticsEventTypeCount> {
        val (where, params) = whereClause(actor, from, to, filter)
        return jdbc.sql(
            """
            SELECT et.code, et.display_name, et.category_code, COUNT(*) AS cnt
            $FROM_JOINS $where
            GROUP BY et.code, et.display_name, et.category_code
            ORDER BY COUNT(*) DESC, et.code ASC
            LIMIT 5
            """.trimIndent(),
        )
            .params(params)
            .query { rs, _ -> AnalyticsEventTypeCount(rs.getString("code"), rs.getString("display_name"), rs.getString("category_code"), rs.getInt("cnt")) }
            .list()
    }

    // ------------------------------------------------------------------------------- private

    private fun whereClause(actor: ReportWorkflowActor, from: Instant, to: Instant, filter: AnalyticsFilter): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf(
            // Inclusive on both ends: `to` is literally `clock.instant()` ("now" itself, brief
            // §3's "-> backend now"), not a rounded exclusive boundary - a report submitted at
            // the exact instant a request is made must still be included.
            "r.submitted_at >= :fromInstant",
            "r.submitted_at <= :toInstant",
            // Currently moderation-deleted excluded, restored included again (brief §4) —
            // the exact `rme.id IS NULL` predicate every ordinary report read already uses.
            "rme.id IS NULL",
        )
        val params = mutableMapOf<String, Any>("fromInstant" to Timestamp.from(from), "toInstant" to Timestamp.from(to))

        scopeClause(actor)?.let { (clause, clauseParams) -> clauses += "($clause)"; params.putAll(clauseParams) }
        filterClause(filter)?.let { (clause, clauseParams) -> clauses += "($clause)"; params.putAll(clauseParams) }

        return ("WHERE " + clauses.joinToString(" AND ")) to params
    }

    /** See the class KDoc for exactly how and why this differs from the report-workflow queue's own visibility clause. */
    private fun scopeClause(actor: ReportWorkflowActor): Pair<String, Map<String, Any>>? {
        if (actor.role == UserRole.SUPER_ADMIN) return null

        if (actor.role == UserRole.MODERATOR && actor.globalAreaAccess) {
            return "rs.routing_status = 'UNCLASSIFIED' OR (rs.routing_status = 'ROUTED' AND sa.status = 'ACTIVE')" to emptyMap()
        }

        if (actor.role == UserRole.SERVICE_USER && actor.globalAreaAccess) {
            return "rs.routing_status = 'ROUTED' AND sa.status = 'ACTIVE'" to emptyMap()
        }

        val ids = actor.ownActiveAreaIds.ifEmpty { setOf(NEVER_MATCHES) }
        return "rs.routing_status = 'ROUTED' AND sa.status = 'ACTIVE' AND rs.service_area_id IN (:ownAreaIds)" to mapOf("ownAreaIds" to ids)
    }

    /** [AnalyticsFilter.areaId]/[AnalyticsFilter.categoryCode]/[AnalyticsFilter.unclassifiedOnly] — already validated against the actor's scope by the use case before this is ever called (brief §22). */
    private fun filterClause(filter: AnalyticsFilter): Pair<String, Map<String, Any>>? {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        if (filter.unclassifiedOnly) {
            clauses += "rs.routing_status = 'UNCLASSIFIED'"
        }
        filter.areaId?.let { clauses += "rs.service_area_id = :fArea"; params["fArea"] = it }
        filter.categoryCode?.let {
            clauses += "et.category_code = :fCategory"
            params["fCategory"] = it
        }

        if (clauses.isEmpty()) return null
        return clauses.joinToString(" AND ") to params
    }

    private companion object {
        val NEVER_MATCHES: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

        const val FROM_JOINS = """
              FROM reports r
              JOIN report_routing_snapshots rs ON rs.report_id = r.id
              JOIN report_event_types et ON et.code = r.event_type_code
              JOIN report_categories cat ON cat.code = et.category_code
              LEFT JOIN service_areas sa ON sa.id = rs.service_area_id
              LEFT JOIN report_moderation_episodes rme ON rme.report_id = r.id AND rme.restored_at IS NULL
        """
    }
}
