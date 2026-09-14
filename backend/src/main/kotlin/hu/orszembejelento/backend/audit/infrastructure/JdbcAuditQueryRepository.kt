package hu.orszembejelento.backend.audit.infrastructure

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditPeriodRange
import hu.orszembejelento.backend.audit.domain.AuditQueryFilter
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * One `audit_events` row joined with just enough to resolve safe display identities (brief
 * §8/§9/§11) - never the row's own raw metadata handed further than this class. Everything
 * downstream ([hu.orszembejelento.backend.audit.application.AuditEventSafeProjector]) reads
 * from [metadataJson] through an explicit per-event-type whitelist, never by serialising it.
 *
 * [actorServiceId] and the three `target*` display columns are `LEFT JOIN`ed here so listing a
 * page never N+1-resolves rows one at a time (brief §29) - exactly one query per page, plus one
 * batch query for any metadata-embedded ServiceArea ids ([JdbcAuditQueryRepository.serviceAreaNamesByIds]).
 */
data class AuditRawRow(
    val id: UUID,
    val actorType: AuditActorType,
    val actorServiceId: String?,
    // Null only for a value unknown to this build's enum (brief §6/§27) - a
    // forward-compatibility guard against a future writer's new event/target type, never
    // reachable from today's closed writer set.
    val eventType: AuditEventType?,
    val targetType: AuditTargetType?,
    val targetId: UUID?,
    val targetUserServiceId: String?,
    val targetAreaName: String?,
    val targetLineCode: String?,
    val targetLineDisplayName: String?,
    val metadataJson: String,
    val createdAt: Instant,
)

data class AuditRawPage(val items: List<AuditRawRow>, val totalElements: Int)

/**
 * The read model behind the Phase 12 audit list/detail endpoints - queries the immutable
 * `audit_events` table only (brief §1/§4: never writes, never touched by Phase 12's own reads
 * per §3). Mirrors [hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationQueryRepository]'s
 * shape: a two-step id-then-batch-fetch list query, a shared `whereClause`/`filterClause`
 * builder, parameterised search with `escapeLike`.
 */
@Repository
class JdbcAuditQueryRepository(private val jdbc: JdbcClient) {

    fun findById(id: UUID): AuditRawRow? =
        jdbc.sql("$SELECT_ROW WHERE ae.id = :id").param("id", id).query(::mapRow).optional().orElse(null)

    fun findPage(range: AuditPeriodRange, filter: AuditQueryFilter, page: Int, size: Int): AuditRawPage {
        val (where, params) = whereClause(range, filter)
        val total = jdbc.sql("SELECT COUNT(*) $FROM_JOINS $where").params(params).query(Int::class.java).single()

        val items = jdbc.sql(
            "SELECT ae.id $FROM_JOINS $where ORDER BY ae.created_at DESC, ae.id DESC LIMIT :limit OFFSET :offset",
        )
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .let(::findByIdsPreservingOrder)

        return AuditRawPage(items, total)
    }

    /**
     * Batch-resolves ServiceArea names for the UUIDs a handful of event types carry *inside*
     * their metadata (`areaId`/`areaIds`/`fromAreaId`/`toAreaId`) - one query for a whole page,
     * never one query per row (brief §29). Deactivated/renamed-since areas still resolve: this
     * is a *current display label* (brief §12), not a historical value.
     */
    fun serviceAreaNamesByIds(ids: Collection<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql("SELECT id, name FROM service_areas WHERE id IN (:ids)")
            .param("ids", ids)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("name") }
            .list()
            .toMap()
    }

    private fun findByIdsPreservingOrder(ids: List<UUID>): List<AuditRawRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = jdbc.sql("$SELECT_ROW WHERE ae.id IN (:ids)")
            .param("ids", ids)
            .query(::mapRow)
            .list()
            .associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private fun whereClause(range: AuditPeriodRange, filter: AuditQueryFilter): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        clauses += "ae.created_at <= :to"
        params["to"] = Timestamp.from(range.to)
        range.from?.let {
            clauses += "ae.created_at >= :from"
            params["from"] = Timestamp.from(it)
        }
        filter.eventType?.let {
            clauses += "ae.event_type = :eventType"
            params["eventType"] = it.name
        }
        filter.targetType?.let {
            clauses += "ae.target_type = :targetType"
            params["targetType"] = it.name
        }
        filter.query?.takeIf { it.isNotBlank() }?.let { q ->
            // Every branch here is a single named, safe, whitelisted column (brief §17) -
            // never a search over the whole `metadata` blob. The `publicReportId` branch reads
            // one specific known JSONB key by name, which is exactly the explicit-whitelist
            // search the brief allows - not the generic `metadata::text ILIKE` it forbids.
            clauses += """(
                au.service_id ILIKE :q OR
                tu.service_id ILIKE :q OR
                tsa.name ILIKE :q OR
                trl.line_code ILIKE :q OR
                trl.display_name ILIKE :q OR
                (ae.metadata ->> 'publicReportId') ILIKE :q
            )"""
            params["q"] = "%${escapeLike(q)}%"
        }

        return "WHERE " + clauses.joinToString(" AND ") to params
    }

    private fun escapeLike(raw: String): String = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = AuditRawRow(
        id = rs.getObject("id", UUID::class.java),
        actorType = AuditActorType.valueOf(rs.getString("actor_type")),
        actorServiceId = rs.getString("actor_service_id"),
        eventType = runCatching { AuditEventType.valueOf(rs.getString("event_type")) }.getOrNull(),
        targetType = runCatching { AuditTargetType.valueOf(rs.getString("target_type")) }.getOrNull(),
        targetId = rs.getObject("target_id", UUID::class.java),
        targetUserServiceId = rs.getString("target_user_service_id"),
        targetAreaName = rs.getString("target_area_name"),
        targetLineCode = rs.getString("target_line_code"),
        targetLineDisplayName = rs.getString("target_line_display_name"),
        metadataJson = rs.getString("metadata_json"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private companion object {
        const val FROM_JOINS = """
              FROM audit_events ae
              LEFT JOIN users au ON au.id = ae.actor_user_id
              LEFT JOIN users tu ON tu.id = ae.target_id AND ae.target_type = 'USER'
              LEFT JOIN service_areas tsa ON tsa.id = ae.target_id AND ae.target_type = 'SERVICE_AREA'
              LEFT JOIN railway_lines trl ON trl.id = ae.target_id AND ae.target_type = 'RAILWAY_LINE'
        """

        val SELECT_ROW = """
            SELECT ae.id, ae.actor_type, au.service_id AS actor_service_id,
                   ae.event_type, ae.target_type, ae.target_id,
                   tu.service_id AS target_user_service_id,
                   tsa.name AS target_area_name,
                   trl.line_code AS target_line_code, trl.display_name AS target_line_display_name,
                   ae.metadata::text AS metadata_json,
                   ae.created_at
            $FROM_JOINS
        """
    }
}
